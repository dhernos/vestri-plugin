package com.vestri.dynamic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.command.SimpleCommand.Invocation;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MinecraftLifecycleCommandTest {
    @TempDir
    Path directory;

    @Test
    void offersConsoleCompletionsFromCurrentConfigButNeverToPlayers() throws Exception {
        Path config = directory.resolve("completion.json");
        Files.writeString(config, "{\"templates\":{\"BentoBox\":\"not-needed-for-completion\",\"Bed Wars\":\"\"}}");
        MinecraftLifecycleCommand command = new MinecraftLifecycleCommand(task -> {}, config);
        Invocation invocation = mock(Invocation.class);
        when(invocation.source()).thenReturn(mock(ConsoleCommandSource.class));
        when(invocation.arguments()).thenReturn(new String[]{"s"});
        assertEquals(List.of("spawn", "sync"), command.suggest(invocation));
        when(invocation.arguments()).thenReturn(new String[]{"spawn", "ben"});
        assertEquals(List.of("BentoBox"), command.suggest(invocation));
        Files.writeString(config, "{\"templates\":{\"BentoBoxNew\":\"\"}}");
        assertEquals(List.of("BentoBoxNew"), command.suggest(invocation));
        when(invocation.source()).thenReturn(mock(Player.class));
        assertEquals(List.of(), command.suggest(invocation));
        when(invocation.source()).thenReturn(mock(ConsoleCommandSource.class));
        Files.writeString(config, "{");
        when(invocation.arguments()).thenReturn(new String[]{"s"});
        assertEquals(List.of("spawn", "sync"), command.suggest(invocation));
    }

    @Test
    void parsesMultiwordNamesAndStableRetryIds() {
        UUID requestId = UUID.randomUUID();
        var parsed = MinecraftLifecycleCommand.parse(new String[]{"spawn", "Bed", "Wars", "--request-id", requestId.toString()});
        assertEquals("Bed Wars", parsed.template());
        assertEquals(requestId, parsed.requestId());
        assertNotNull(MinecraftLifecycleCommand.parse(new String[]{"spawn", "Bed Wars"}).requestId());
        assertEquals("Bed Wars", MinecraftLifecycleCommand.parse(new String[]{"list", "\"Bed", "Wars\""}).template());
    }

    @Test
    void parsesOptionalSpawnPlayerWithoutChangingTemplateOrRetryId() {
        UUID requestId = UUID.randomUUID();
        var parsed = MinecraftLifecycleCommand.parse(new String[]{"spawn", "Bed", "Wars", "--send-player", "Luis_1",
                "--request-id", requestId.toString()});
        assertEquals("Bed Wars", parsed.template());
        assertEquals("Luis_1", parsed.sendPlayer());
        assertEquals(requestId, parsed.requestId());
        assertEquals(null, MinecraftLifecycleCommand.parse(new String[]{"spawn", "Bed Wars"}).sendPlayer());
        for (String[] arguments : new String[][]{
                {"spawn", "Bed Wars", "--send-player"}, {"spawn", "Bed Wars", "--send-player", "{player}"},
                {"spawn", "Bed Wars", "--send-player", "name;stop"},
                {"spawn", "Bed Wars", "--send-player", "abcdefghijklmnopq"},
                {"spawn", "Bed Wars", "--send-player", "Luis", "--send-player", "Other"},
                {"list", "Bed Wars", "--send-player", "Luis"},
                {"delete", "Bed Wars", "--confirm", "--send-player", "Luis"}}) {
            assertThrows(IllegalArgumentException.class, () -> MinecraftLifecycleCommand.parse(arguments));
        }
    }

    @Test
    void requiresConfirmationAndValidActionOptions() {
        for (String[] arguments : new String[][]{
                {}, {"delete", "Bed Wars"}, {"spawn", "Bed Wars", "--confirm"},
                {"list", "Bed Wars", "--instance", UUID.randomUUID().toString()},
                {"spawn", "Bed Wars", "--request-id"}, {"delete", "--confirm"},
                {"spawn", "test", "--request-id", "1-1-1-1-1"}}) {
            assertThrows(IllegalArgumentException.class, () -> MinecraftLifecycleCommand.parse(arguments));
        }
        UUID instance = UUID.randomUUID();
        assertEquals(instance, MinecraftLifecycleCommand.parse(new String[]{"delete", "Bed Wars", "--instance", instance.toString(), "--confirm"}).instanceId());
    }

    @Test
    void restrictsLifecycleOperationsToConsoleAndSchedulesOffThread() {
        List<Runnable> scheduled = new ArrayList<>();
        MinecraftLifecycleCommand command = new MinecraftLifecycleCommand(scheduled::add, Path.of("missing-config.json"));
        Invocation player = mock(Invocation.class);
        when(player.source()).thenReturn(mock(Player.class));
        assertFalse(command.hasPermission(player));
        command.execute(player);
        assertEquals(0, scheduled.size());

        Invocation console = mock(Invocation.class);
        when(console.source()).thenReturn(mock(ConsoleCommandSource.class));
        when(console.arguments()).thenReturn(new String[]{"templates"});
        assertTrue(command.hasPermission(console));
        command.execute(console);
        assertEquals(1, scheduled.size());
    }

    @Test
    void rejectsOfflinePlayerBeforeSchedulingProvisioning() {
        ProxyServer proxy = mock(ProxyServer.class);
        when(proxy.getPlayer("Luis")).thenReturn(Optional.empty());
        List<Runnable> scheduled = new ArrayList<>();
        MinecraftLifecycleCommand command = new MinecraftLifecycleCommand(scheduled::add, directory.resolve("absent.json"),
                new SpawnPlayerTransfer(proxy, (task, delay) -> scheduled.add(task)));
        Invocation invocation = mock(Invocation.class);
        ConsoleCommandSource console = mock(ConsoleCommandSource.class);
        when(invocation.source()).thenReturn(console);
        when(invocation.arguments()).thenReturn(new String[]{"spawn", "Bed Wars", "--send-player", "Luis"});
        command.execute(invocation);
        assertEquals(0, scheduled.size());
        assertFalse(Files.exists(directory.resolve("absent.json")));
        verify(console).sendMessage(Component.text("Player is not connected to this proxy; no instance was spawned.", net.kyori.adventure.text.format.NamedTextColor.AQUA));
    }

    @Test
    void startsReadinessCheckFromExactSpawnResponseWithoutAnotherApiCall() throws Exception {
        UUID instance = UUID.randomUUID();
        UUID request = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        String slug = "mcgame-" + "b".repeat(32);
        String key = "vmc_" + "a".repeat(64);
        List<String> calls = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/minecraft/v1/instances", exchange -> {
            calls.add(exchange.getRequestMethod());
            assertEquals("Bearer " + key, exchange.getRequestHeaders().getFirst("Authorization"));
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(body.contains(request.toString()));
            byte[] response = ("{\"serverId\":\"" + instance + "\",\"slug\":\"" + slug + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            Path config = directory.resolve("spawn.json");
            Files.writeString(config, "{\"backendUrl\":\"http://127.0.0.1:" + server.getAddress().getPort()
                    + "\",\"templates\":{\"Bed Wars\":\"" + key + "\"}}");
            ProxyServer proxy = mock(ProxyServer.class);
            Player player = mock(Player.class);
            when(player.getUniqueId()).thenReturn(playerId);
            when(player.getUsername()).thenReturn("Luis");
            when(player.isActive()).thenReturn(true);
            when(proxy.getPlayer("Luis")).thenReturn(Optional.of(player));
            when(proxy.getPlayer(playerId)).thenReturn(Optional.of(player));
            RegisteredServer target = mock(RegisteredServer.class);
            when(target.getServerInfo()).thenReturn(new ServerInfo("BedWars-1", InetSocketAddress.createUnresolved("vestri-" + slug, 25565)));
            when(proxy.getAllServers()).thenReturn(List.of(target));
            when(target.ping()).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("starting")));
            List<Runnable> scheduled = new ArrayList<>();
            List<Runnable> polls = new ArrayList<>();
            MinecraftLifecycleCommand command = new MinecraftLifecycleCommand(scheduled::add, config,
                    new SpawnPlayerTransfer(proxy, (task, delay) -> polls.add(task)));
            Invocation invocation = mock(Invocation.class);
            ConsoleCommandSource console = mock(ConsoleCommandSource.class);
            when(invocation.source()).thenReturn(console);
            when(invocation.arguments()).thenReturn(new String[]{"spawn", "Bed Wars", "--send-player", "Luis", "--request-id", request.toString()});
            command.execute(invocation);
            assertEquals(0, calls.size());
            scheduled.get(0).run();
            assertEquals(List.of("POST"), calls);
            verify(target).ping();
            assertEquals(1, polls.size());
            verify(console).sendMessage(Component.text("Server starting: Bed Wars", net.kyori.adventure.text.format.NamedTextColor.AQUA));
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"plugin", "vestri"})
    void handledMinigameJoinUsesExactTargetAndForwardsHookOutputWithoutOrdinaryTransfer(String lobbyMode) throws Exception {
        Minigames minigames = mock(Minigames.class);
        String message = lobbyMode.equals("plugin") ? "Reserved native minigame slot." : "Joined managed minigame queue.";
        doAnswer(invocation -> {
            Consumer<String> output = invocation.getArgument(2);
            output.accept(message);
            return true;
        }).when(minigames).joinInstance(any(), any(), any());

        SpawnResult result = executeSpawn(minigames, true,
                "{\"minigames\":{\"Bed Wars\":{\"lobbyMode\":\"" + lobbyMode + "\"}}}");

        assertEquals(List.of("POST"), result.calls());
        verify(minigames).joinInstance(eq(result.player()), eq(result.instance()), any());
        verify(result.transfer(), never()).start(any(), any(), any());
        verify(result.console()).sendMessage(Component.text(message, NamedTextColor.AQUA));
    }

    @Test
    void unhandledJoinFallsBackToOrdinaryTransferWithExactTarget() throws Exception {
        Minigames minigames = mock(Minigames.class);
        when(minigames.joinInstance(any(), any(), any())).thenReturn(false);

        SpawnResult result = executeSpawn(minigames, true, null);

        assertEquals(List.of("POST"), result.calls());
        verify(minigames).joinInstance(eq(result.player()), eq(result.instance()), any());
        verify(result.transfer()).start(eq(result.player()), eq(result.instance()), any());
    }

    @Test
    void unavailableMinigamesRejectsMatchingTemplateBeforeSpawning() throws Exception {
        SpawnResult result = executeSpawn(null, true, "{\"minigames\":{\"bed wars\":{}}}");

        assertEquals(List.of(), result.calls());
        verify(result.transfer(), never()).start(any(), any(), any());
        verify(result.console()).sendMessage(Component.text(
                "Vestri command failed: Minigame management is unavailable; no instance was spawned.", NamedTextColor.AQUA));
    }

    @Test
    void unavailableMinigamesDoesNotBlockOrdinaryTemplateInManifest() throws Exception {
        SpawnResult result = executeSpawn(null, true, "{\"minigames\":{\"Other Game\":{}}}");

        assertEquals(List.of("POST"), result.calls());
        verify(result.transfer()).start(eq(result.player()), eq(result.instance()), any());
    }

    @Test
    void legacyFourArgumentConstructorWithoutManifestStillTransfersOrdinarySpawn() throws Exception {
        SpawnResult result = executeSpawn(null, true, null);

        assertEquals(List.of("POST"), result.calls());
        verify(result.transfer()).start(eq(result.player()), eq(result.instance()), any());
    }

    @Test
    void plainConsoleSpawnDoesNotInvokeMinigameJoinOrPlayerTransfer() throws Exception {
        Minigames minigames = mock(Minigames.class);
        SpawnResult result = executeSpawn(minigames, false, "{\"minigames\":{\"Bed Wars\":{}}}");

        assertEquals(List.of("POST"), result.calls());
        verifyNoInteractions(minigames, result.transfer());
        verify(result.console()).sendMessage(Component.text("Server starting: BedWars-7", NamedTextColor.AQUA));
    }

    @Test
    void plainConsoleMinigameSpawnStillWorksWithoutCoordinator() throws Exception {
        SpawnResult result = executeSpawn(null, false, "{\"minigames\":{\"Bed Wars\":{}}}");

        assertEquals(List.of("POST"), result.calls());
        verifyNoInteractions(result.transfer());
        verify(result.console()).sendMessage(Component.text("Server starting: BedWars-7", NamedTextColor.AQUA));
    }

    private SpawnResult executeSpawn(Minigames minigames, boolean sendPlayer, String manifest) throws Exception {
        return executeSpawn(minigames, sendPlayer, manifest, "");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void forwardsVariantForPlainAndSelectedSpawnsWithoutBypassingRoster(boolean sendPlayer) throws Exception {
        Minigames minigames = mock(Minigames.class);
        when(minigames.joinInstance(any(), any(), any())).thenReturn(true);
        SpawnResult result = executeSpawn(minigames, sendPlayer, "{\"minigames\":{\"Bed Wars\":{}}}", "4v4v4v4");
        assertEquals(List.of("POST"), result.calls());
        if (sendPlayer) {
            verify(minigames).joinInstance(eq(result.player()), eq(result.instance()), any());
        } else {
            verifyNoInteractions(minigames);
        }
        verify(result.transfer(), never()).start(any(), any(), any());
    }

    @Test
    void rejectsOwnedVariantBeforeSchedulingOrLookingUpPlayer() {
        List<Runnable> scheduled = new ArrayList<>();
        SpawnPlayerTransfer transfer = mock(SpawnPlayerTransfer.class);
        PlayerServers owned = mock(PlayerServers.class);
        MinecraftLifecycleCommand command = new MinecraftLifecycleCommand(scheduled::add, directory.resolve("absent.json"), transfer, owned);
        Invocation invocation = mock(Invocation.class);
        when(invocation.source()).thenReturn(mock(ConsoleCommandSource.class));
        for (String type : List.of("permanent", "temporary")) {
            when(invocation.arguments()).thenReturn(new String[]{"spawn", "Bed Wars", "--variant", "solo", "--type", type, "--send-player", "Luis"});
            command.execute(invocation);
        }
        assertEquals(List.of(), scheduled);
        verifyNoInteractions(transfer, owned);
    }

    private SpawnResult executeSpawn(Minigames minigames, boolean sendPlayer, String manifest, String variant) throws Exception {
        UUID request = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        var instance = new MinecraftInstanceClient.SpawnedInstance(UUID.randomUUID().toString(),
                "mcgame-" + "b".repeat(32), "BedWars-7");
        Player player = (Player) java.lang.reflect.Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[]{Player.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "getUniqueId" -> playerId;
                    case "getUsername" -> "Luis";
                    case "isActive" -> true;
                    case "equals" -> proxy == arguments[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "Luis";
                    default -> null;
                });
        SpawnPlayerTransfer transfer = mock(SpawnPlayerTransfer.class);
        when(transfer.findPlayer("Luis")).thenReturn(player);
        ConsoleCommandSource console = mock(ConsoleCommandSource.class);
        Invocation invocation = mock(Invocation.class);
        when(invocation.source()).thenReturn(console);
        List<String> arguments = new ArrayList<>(List.of("spawn", "Bed", "Wars", "--request-id", request.toString()));
        if (sendPlayer) {
            arguments.addAll(List.of("--send-player", "Luis"));
        }
        if (!variant.isEmpty()) {
            arguments.addAll(List.of("--variant", variant));
        }
        when(invocation.arguments()).thenReturn(arguments.toArray(String[]::new));
        List<String> calls = new ArrayList<>();
        List<String> bodies = new ArrayList<>();
        String key = "vmc_" + "a".repeat(64);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/minecraft/v1/instances", exchange -> {
            calls.add(exchange.getRequestMethod());
            assertEquals("Bearer " + key, exchange.getRequestHeaders().getFirst("Authorization"));
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = ("{\"serverId\":\"" + instance.serverId() + "\",\"slug\":\"" + instance.slug()
                    + "\",\"name\":\"" + instance.name() + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            Path config = directory.resolve("spawn.json");
            Files.writeString(config, "{\"backendUrl\":\"http://127.0.0.1:" + server.getAddress().getPort()
                    + "\",\"templates\":{\"Bed Wars\":\"" + key + "\"}}");
            if (manifest != null) {
                Files.writeString(directory.resolve("vestri-dynamic-servers.json"), manifest);
            }
            List<Runnable> scheduled = new ArrayList<>();
            MinecraftLifecycleCommand command = minigames == null
                    ? new MinecraftLifecycleCommand(scheduled::add, config, transfer, null)
                    : new MinecraftLifecycleCommand(scheduled::add, config, transfer, null, minigames);
            command.execute(invocation);
            assertEquals(List.of(), calls);
            assertEquals(1, scheduled.size());
            scheduled.get(0).run();
            if (!calls.isEmpty()) {
                var expected = new com.google.gson.JsonObject();
                expected.addProperty("requestId", request.toString());
                if (!variant.isEmpty()) {
                    expected.addProperty("variant", variant);
                }
                assertEquals(List.of(expected), bodies.stream().map(com.google.gson.JsonParser::parseString).toList());
            }
            return new SpawnResult(calls, player, instance, transfer, console);
        } finally {
            server.stop(0);
        }
    }

    private record SpawnResult(List<String> calls, Player player, MinecraftInstanceClient.SpawnedInstance instance,
            SpawnPlayerTransfer transfer, ConsoleCommandSource console) {}

    @Test
    void executesScheduledCommandWithConfigurationFromDisk() throws Exception {
        Path config = directory.resolve("config.json");
        Files.writeString(config, "{\"backendUrl\":\"https://backend.example\",\"templates\":{\"Bed Wars\":\"vmc_"
                + "a".repeat(64) + "\"}}");
        List<Runnable> scheduled = new ArrayList<>();
        MinecraftLifecycleCommand command = new MinecraftLifecycleCommand(scheduled::add, config);
        Invocation invocation = mock(Invocation.class);
        ConsoleCommandSource console = mock(ConsoleCommandSource.class);
        when(invocation.source()).thenReturn(console);
        when(invocation.arguments()).thenReturn(new String[]{"templates"});

        command.execute(invocation);
        scheduled.get(0).run();

        verify(console).sendMessage(Component.text("Configured templates: bed wars", net.kyori.adventure.text.format.NamedTextColor.AQUA));
        when(invocation.arguments()).thenReturn(new String[]{"delete", "Bed Wars"});
        command.execute(invocation);
        assertEquals(1, scheduled.size());
    }
}