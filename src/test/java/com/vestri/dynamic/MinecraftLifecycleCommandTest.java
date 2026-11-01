package com.vestri.dynamic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
        verify(console).sendMessage(Component.text("Player is not connected to this proxy; no instance was spawned."));
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
            verify(console).sendMessage(Component.text("Instance starting: " + instance));
        } finally {
            server.stop(0);
        }
    }

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

        verify(console).sendMessage(Component.text("Configured templates: bed wars"));
        when(invocation.arguments()).thenReturn(new String[]{"delete", "Bed Wars"});
        command.execute(invocation);
        assertEquals(1, scheduled.size());
    }
}