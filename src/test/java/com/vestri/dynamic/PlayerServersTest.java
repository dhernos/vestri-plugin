package com.vestri.dynamic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sun.net.httpserver.HttpServer;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.mockito.ArgumentCaptor;

class PlayerServersTest {
    @TempDir Path directory;
    private final AtomicLong clock = new AtomicLong(1000000);
    private final List<String> calls = new ArrayList<>();
    private HttpServer api;
    private OwnedServerRegistry registry;
    private PlayerServers service;
    private ProxyServer proxy;
    private Player player;
    private RegisteredServer target;
    private Path configuration;
    private int apiStatus;
    private UUID spawnedId;
    private String spawnBody;
    private int transientSpawnFailures;
    private String preparationResponse = "{\"instance\":null}";
    private int deletionStatus;
    private final List<String> spawnRequests = new ArrayList<>();

    @BeforeEach
    void setup() throws Exception {
        apiStatus = 200;
        spawnedId = UUID.randomUUID();
        api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        api.createContext("/api/minecraft/v1/instances", exchange -> {
            calls.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            spawnBody = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            boolean spawning = exchange.getRequestURI().getPath().equals("/api/minecraft/v1/instances");
            if (spawning) {
                spawnRequests.add(spawnBody);
            }
            String body = spawning ? "{\"serverId\":\"" + spawnedId + "\",\"slug\":\"mcgame-" + "a".repeat(32)
                    + "\",\"name\":\"Survival-1\"}" : "{}";
            if (exchange.getRequestURI().getPath().contains("/requests/")) {
                body = preparationResponse;
            }
            byte[] response = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            int responseCode = spawning && transientSpawnFailures-- > 0 ? 502 : apiStatus;
            if ("DELETE".equals(exchange.getRequestMethod()) && deletionStatus != 0) {
                responseCode = deletionStatus;
            }
            exchange.sendResponseHeaders(responseCode, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        api.start();
        configuration = directory.resolve("config.json");
        configure(true, 1);
        registry = new OwnedServerRegistry(directory.resolve("owned.json"));
        proxy = mock(ProxyServer.class);
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isActive()).thenReturn(true);
        when(player.getUsername()).thenReturn("Owner");
        when(player.hasPermission(anyString())).thenReturn(true);
        when(proxy.getPlayer(player.getUniqueId())).thenReturn(java.util.Optional.of(player));
        target = mock(RegisteredServer.class);
        when(target.getServerInfo()).thenReturn(new ServerInfo("Survival-1",
                InetSocketAddress.createUnresolved("vestri-mcgame-" + "a".repeat(32), 25565)));
        when(target.getPlayersConnected()).thenReturn(List.of());
        when(target.ping()).thenReturn(CompletableFuture.failedFuture(new java.net.ConnectException()));
        when(proxy.getAllServers()).thenReturn(List.of(target));
        var transfer = new SpawnPlayerTransfer(proxy, (task, delay) -> {});
        service = new PlayerServers(proxy, configuration, registry, transfer, Runnable::run, mock(Logger.class), clock::get);
    }

    private void configure(boolean pauseEnabled, int pausedLimit) throws Exception {
        Files.writeString(configuration, "{\"backendUrl\":\"http://127.0.0.1:" + api.getAddress().getPort()
                + "\",\"templates\":{\"Survival\":\"vmc_" + "a".repeat(64)
                + "\"},\"playerServers\":{\"enabled\":true,\"pauseEnabled\":" + pauseEnabled
                + ",\"maxPausedPerUser\":" + pausedLimit + ",\"emptyGraceSeconds\":1}}");
    }

    private OwnedServerRegistry.Entry entry(OwnedServerRegistry.Type type) throws Exception {
        var entry = registry.reserve(player.getUniqueId(), "survival", type, 10);
        entry.serverId = UUID.randomUUID().toString();
        entry.slug = "mcgame-" + "a".repeat(32);
        entry.state = OwnedServerRegistry.State.ACTIVE;
        registry.save();
        return entry;
    }

    private SimpleCommand.Invocation invocation(String... arguments) {
        var invocation = mock(SimpleCommand.Invocation.class);
        when(invocation.source()).thenReturn(player);
        when(invocation.arguments()).thenReturn(arguments);
        return invocation;
    }

    private void becomeEmpty() {
        service.sweep();
        clock.addAndGet(1001);
        service.sweep();
    }

    @AfterEach
    void teardown() {
        api.stop(0);
    }

    @Test
    void fullPreConnectWiringPreservesOwnedServerAclWithMinigamesPresent() throws Exception {
        var entry = entry(OwnedServerRegistry.Type.PERMANENT);
        Player guest = guest("Guest");
        var plugin = new VestriDynamicServers(proxy, mock(Logger.class), directory);
        var playerServers = VestriDynamicServers.class.getDeclaredField("playerServers");
        playerServers.setAccessible(true);
        playerServers.set(plugin, service);
        try (var minigames = new Minigames(proxy, new Parties(), directory, mock(Logger.class), clock::get)) {
            var minigamesField = VestriDynamicServers.class.getDeclaredField("minigames");
            minigamesField.setAccessible(true);
            minigamesField.set(plugin, minigames);
            var continuation = mock(com.velocitypowered.api.event.Continuation.class);
            var denied = new ServerPreConnectEvent(guest, target);
            plugin.beforeServerConnect(denied).execute(continuation);
            assertTrue(denied.getResult().getServer().isEmpty());
            assertEquals(0, entry.joinUntil);
            service.execute(invocation("invite", "Survival-1", "Guest"));
            var allowed = new ServerPreConnectEvent(guest, target);
            plugin.beforeServerConnect(allowed).execute(continuation);
            assertEquals(java.util.Optional.of(target), allowed.getResult().getServer());
            assertTrue(entry.joinUntil > clock.get());
            service.execute(invocation("revoke", "Survival-1", "Guest"));
            var revoked = new ServerPreConnectEvent(guest, target);
            plugin.beforeServerConnect(revoked).execute(continuation);
            assertTrue(revoked.getResult().getServer().isEmpty());
            verify(continuation, times(3)).resume();
            verify(continuation, never()).resumeWithException(any());
            assertTrue(calls.isEmpty());
        }
    }

    @Test
    void spawnSendsOnlyOneBlueStartingAnnouncement() {
        service.execute(invocation("spawn", "temporary", "Survival"));
        var captured = ArgumentCaptor.forClass(Component.class);
        verify(player, atLeastOnce()).sendMessage(captured.capture());
        var announcements = captured.getAllValues().stream()
                .filter(component -> text(component).contains(" is starting.")).toList();
        assertEquals(1, announcements.size());
        assertTrue(text(announcements.get(0)).contains("We'll connect you when it's ready."));
        assertEquals(net.kyori.adventure.text.format.NamedTextColor.AQUA, announcements.get(0).color());
    }

    @Test
    void temporaryDeletesOnlyWhenEmptyAndPermanentIsPreserved() throws Exception {
        var temporary = entry(OwnedServerRegistry.Type.TEMPORARY);
        when(target.getPlayersConnected()).thenReturn(List.of(player));
        becomeEmpty();
        assertTrue(calls.isEmpty());
        when(target.getPlayersConnected()).thenReturn(List.of());
        becomeEmpty();
        assertEquals(OwnedServerRegistry.State.PENDING_DELETION, temporary.state);
        assertEquals(List.of("POST /api/minecraft/v1/instances/" + temporary.serverId + "/stop"), calls);
        clock.addAndGet(300000);
        service.sweep();
        assertEquals(List.of("POST /api/minecraft/v1/instances/" + temporary.serverId + "/stop",
            "DELETE /api/minecraft/v1/instances/" + temporary.serverId), calls);
        assertTrue(registry.all().isEmpty());
        calls.clear();
        entry(OwnedServerRegistry.Type.PERMANENT);
        becomeEmpty();
        assertTrue(calls.isEmpty());
        assertEquals(1, registry.all().size());
    }

    @Test
    void pausedTemporaryStopsPersistsAndWakesOnDirectJoin() throws Exception {
        var entry = entry(OwnedServerRegistry.Type.TEMPORARY);
        service.pauseCommand().execute(invocation(entry.serverId));
        assertTrue(entry.paused);
        becomeEmpty();
        assertEquals(OwnedServerRegistry.State.STOPPED, entry.state);
        assertEquals(OwnedServerRegistry.State.STOPPED,
                new OwnedServerRegistry(directory.resolve("owned.json")).all().get(0).state);
        service.sweep();
        assertEquals(1, calls.size());
        var event = new ServerPreConnectEvent(player, target);
        service.beforeConnect(event);
        assertTrue(event.getResult().getServer().isEmpty());
        assertEquals(OwnedServerRegistry.State.ACTIVE, entry.state);
        assertTrue(entry.paused);
        assertEquals(List.of("POST /api/minecraft/v1/instances/" + entry.serverId + "/stop",
                "POST /api/minecraft/v1/instances/" + entry.serverId + "/start"), calls);
        service.sweep();
        assertEquals(2, calls.size());
    }

    @Test
    void pauseLimitsAndOwnershipCannotBeBypassed() throws Exception {
        var first = entry(OwnedServerRegistry.Type.TEMPORARY);
        var second = entry(OwnedServerRegistry.Type.TEMPORARY);
        service.pauseCommand().execute(invocation(first.serverId));
        service.pauseCommand().execute(invocation(second.serverId));
        assertTrue(first.paused);
        assertFalse(second.paused);
        configure(false, 0);
        service.pauseCommand().execute(invocation(first.serverId));
        assertFalse(first.paused);
        service.pauseCommand().execute(invocation(second.serverId));
        assertFalse(second.paused);
        first.owner = UUID.randomUUID();
        service.execute(invocation("delete", first.serverId, "--confirm"));
        assertTrue(calls.isEmpty());
        when(player.hasPermission("vestri.servers")).thenReturn(false);
        assertFalse(service.hasPermission(invocation("list")));
    }

    @Test
        void ownerCanDeletePendingPreparationWithoutRetryingOrSpawning() throws Exception {
        var pending = registry.reserve(player.getUniqueId(), "survival", OwnedServerRegistry.Type.TEMPORARY, 1);
            assertEquals(List.of(pending.name), service.suggest(invocation("delete", "")));
            UUID owner = player.getUniqueId();
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            service.execute(invocation("delete", pending.name, "--confirm"));
            assertTrue(calls.isEmpty());
            assertEquals(1, registry.all().size());
            when(player.getUniqueId()).thenReturn(owner);
        service.execute(invocation("delete", pending.name));
        assertTrue(calls.isEmpty());
        service.execute(invocation("delete", pending.name, "--confirm"));
        assertTrue(registry.all().isEmpty());
        assertEquals(List.of("GET /api/minecraft/v1/instances/requests/" + pending.requestId), calls);
        assertTrue(spawnRequests.isEmpty());
        }

        @Test
        void pendingDeletionResolvesCreatedInstanceAndRefusesOccupiedServer() throws Exception {
        var pending = registry.reserve(player.getUniqueId(), "survival", OwnedServerRegistry.Type.PERMANENT, 1);
        preparationResponse = "{\"instance\":{\"serverId\":\"" + spawnedId + "\",\"slug\":\"mcgame-" + "a".repeat(32)
            + "\",\"name\":\"Survival-1\"}}";
        when(target.getPlayersConnected()).thenReturn(List.of(player));
        service.execute(invocation("delete", pending.name, "--confirm"));
        assertEquals(1, registry.all().size());
        assertEquals(OwnedServerRegistry.State.PROVISIONING, pending.state);
        assertEquals(spawnedId.toString(), pending.serverId);
        assertEquals(1, calls.size());
        when(target.getPlayersConnected()).thenReturn(List.of());
        service.execute(invocation("delete", pending.name, "--confirm"));
        assertTrue(registry.all().isEmpty());
        assertEquals(List.of("GET /api/minecraft/v1/instances/requests/" + pending.requestId,
            "DELETE /api/minecraft/v1/instances/" + spawnedId), calls);
        assertTrue(spawnRequests.isEmpty());
        }

        @Test
        void preparationTimeoutSurvivesRestartAndCleansPermanentAndTemporaryReservations() throws Exception {
        var permanent = registry.reserve(player.getUniqueId(), "survival", OwnedServerRegistry.Type.PERMANENT, 1);
        var temporary = registry.reserve(player.getUniqueId(), "survival", OwnedServerRegistry.Type.TEMPORARY, 1);
        service.sweep();
        assertEquals(clock.get() + 300000, permanent.preparationDeleteAfter);
        assertEquals(permanent.preparationDeleteAfter, temporary.preparationDeleteAfter);
        assertTrue(calls.isEmpty());
        var restored = new OwnedServerRegistry(directory.resolve("owned.json"));
        service = new PlayerServers(proxy, configuration, restored, new SpawnPlayerTransfer(proxy, (task, delay) -> {}),
            Runnable::run, mock(Logger.class), clock::get);
        clock.addAndGet(299999);
        service.sweep();
        assertTrue(calls.isEmpty());
        clock.incrementAndGet();
        service.sweep();
        assertTrue(restored.all().isEmpty());
        assertEquals(2, calls.size());
        assertTrue(calls.stream().allMatch(call -> call.startsWith("GET ")));
        }

        @Test
        void timedOutPreparationDeletesBackendInstanceAndRetainsFailedDeletionForRetry() throws Exception {
        var pending = registry.reserve(player.getUniqueId(), "survival", OwnedServerRegistry.Type.PERMANENT, 1);
        pending.preparationDeleteAfter = clock.get();
        preparationResponse = "{\"instance\":{\"serverId\":\"" + spawnedId + "\",\"slug\":\"mcgame-" + "a".repeat(32)
            + "\",\"name\":\"Survival-1\"}}";
        deletionStatus = 502;
        service.sweep();
        assertEquals(OwnedServerRegistry.State.DELETING, pending.state);
        assertEquals(1, new OwnedServerRegistry(directory.resolve("owned.json")).all().size());
        service.sweep();
        assertEquals(2, calls.size());
        clock.addAndGet(60001);
        deletionStatus = 404;
        service.sweep();
        assertTrue(registry.all().isEmpty());
        assertEquals(List.of("GET /api/minecraft/v1/instances/requests/" + pending.requestId,
            "DELETE /api/minecraft/v1/instances/" + spawnedId,
            "DELETE /api/minecraft/v1/instances/" + spawnedId), calls);
        }

        @Test
        void failedPreparationLookupKeepsQuotaAndRetriesAfterBackoff() throws Exception {
        var pending = registry.reserve(player.getUniqueId(), "survival", OwnedServerRegistry.Type.TEMPORARY, 1);
        pending.preparationDeleteAfter = clock.get();
        apiStatus = 503;
        service.sweep();
        assertEquals(1, registry.all().size());
        assertThrows(java.io.IOException.class, () -> registry.reserve(player.getUniqueId(), "survival",
            OwnedServerRegistry.Type.TEMPORARY, 1));
        apiStatus = 200;
        service.sweep();
        assertEquals(1, calls.size());
        clock.addAndGet(60001);
        service.sweep();
        assertTrue(registry.all().isEmpty());
        assertEquals(2, calls.size());
        }

        @Test
        void configuredPreparationTimerResetsOnFailureAndClearsAfterSuccessfulRetry() throws Exception {
            var config = com.google.gson.JsonParser.parseString(Files.readString(configuration)).getAsJsonObject();
            config.getAsJsonObject("playerServers").addProperty("preparationTimeoutSeconds", 12);
            Files.writeString(configuration, config.toString());
            apiStatus = 401;
            service.execute(invocation("spawn", "permanent", "Survival"));
            var pending = registry.all().get(0);
            assertEquals(clock.get() + 12000, pending.preparationDeleteAfter);
            clock.addAndGet(5000);
            service.execute(invocation("retry", pending.name));
            assertEquals(clock.get() + 12000, pending.preparationDeleteAfter);
            assertEquals(pending.preparationDeleteAfter,
                    new OwnedServerRegistry(directory.resolve("owned.json")).all().get(0).preparationDeleteAfter);
            apiStatus = 200;
            service.execute(invocation("retry", pending.name));
            assertEquals(OwnedServerRegistry.State.ACTIVE, pending.state);
            assertEquals(0, pending.preparationDeleteAfter);
            int completedCalls = calls.size();
            clock.addAndGet(12000);
            service.sweep();
            assertEquals(completedCalls, calls.size());
            assertEquals(1, registry.all().size());
        }

        @Test
        void automaticPreparationCleanupWaitsForRecoveredInstanceToBecomeEmpty() throws Exception {
            var pending = registry.reserve(player.getUniqueId(), "survival", OwnedServerRegistry.Type.TEMPORARY, 1);
            pending.preparationDeleteAfter = clock.get();
            preparationResponse = "{\"instance\":{\"serverId\":\"" + spawnedId + "\",\"slug\":\"mcgame-" + "a".repeat(32)
                    + "\",\"name\":\"Survival-1\"}}";
            when(target.getPlayersConnected()).thenReturn(List.of(player));
            service.sweep();
            assertEquals(1, registry.all().size());
            assertEquals(1, calls.size());
            clock.addAndGet(60001);
            service.sweep();
            assertEquals(1, calls.size());
            when(target.getPlayersConnected()).thenReturn(List.of());
            service.sweep();
            assertTrue(registry.all().isEmpty());
            assertEquals("DELETE /api/minecraft/v1/instances/" + spawnedId, calls.get(1));
        }

        @Test
    void occupiedDeletionAndUnconfirmedDeletionAreRefused() throws Exception {
        var entry = entry(OwnedServerRegistry.Type.PERMANENT);
        service.execute(invocation("delete", entry.serverId));
        when(target.getPlayersConnected()).thenReturn(List.of(player));
        service.execute(invocation("delete", entry.serverId, "--confirm"));
        assertTrue(calls.isEmpty());
        when(target.getPlayersConnected()).thenReturn(List.of());
        service.execute(invocation("delete", entry.serverId, "--confirm"));
        assertTrue(registry.all().isEmpty());
    }

    @Test
    void joinLeaseProtectsNewServersAndApiFailuresRetainRetryableRecords() throws Exception {
        var entry = entry(OwnedServerRegistry.Type.TEMPORARY);
        entry.joinUntil = clock.get() + 360000;
        becomeEmpty();
        assertTrue(calls.isEmpty());
        clock.addAndGet(360000);
        apiStatus = 502;
        becomeEmpty();
        assertEquals(OwnedServerRegistry.State.STOPPING, entry.state);
        assertEquals(1, registry.all().size());
        service.sweep();
        assertEquals(1, calls.size());
        clock.addAndGet(60001);
        apiStatus = 200;
        service.sweep();
        assertEquals(OwnedServerRegistry.State.PENDING_DELETION, entry.state);
        clock.addAndGet(300000);
        apiStatus = 502;
        service.sweep();
        assertEquals(OwnedServerRegistry.State.DELETING, entry.state);
        clock.addAndGet(60001);
        apiStatus = 404;
        service.sweep();
        assertTrue(registry.all().isEmpty());
    }

    @Test
    void typeFlagRequiresAnOwnerAndSurvivesParsing() {
        assertThrows(IllegalArgumentException.class, () -> LifecycleRequest.parse(new String[]{"spawn", "Survival", "--type", "temporary"}));
        var request = LifecycleRequest.parse(new String[]{"spawn", "Survival", "--send-player", "Luis", "--type", "temporary"});
        assertEquals("temporary", request.serverType());
        assertEquals("Luis", request.sendPlayer());
        assertEquals(List.of("temporary"), LifecycleSuggestions.complete(new String[]{"spawn", "Survival", "--type", "t"}, List.of(), true));
    }

    @Test
    void activeJoinProtectionSurvivesProxyRestart() throws Exception {
        var entry = entry(OwnedServerRegistry.Type.TEMPORARY);
        var event = new ServerPreConnectEvent(player, target);
        service.beforeConnect(event);
        assertTrue(event.getResult().getServer().isPresent());
        var restored = new OwnedServerRegistry(directory.resolve("owned.json"));
        assertEquals(clock.get() + 360000, restored.all().get(0).joinUntil);
        var restarted = new PlayerServers(proxy, configuration, restored,
                new SpawnPlayerTransfer(proxy, (task, delay) -> {}), Runnable::run, mock(Logger.class), clock::get);
        restarted.sweep();
        assertTrue(calls.isEmpty());
        assertEquals(entry.serverId, restored.all().get(0).serverId);
    }

    @Test
    void failedWakeAndStopAreNotRecordedAsSuccessful() throws Exception {
        var entry = entry(OwnedServerRegistry.Type.TEMPORARY);
        entry.paused = true;
        apiStatus = 502;
        becomeEmpty();
        assertEquals(OwnedServerRegistry.State.STOPPING, entry.state);
        var event = new ServerPreConnectEvent(player, target);
        service.beforeConnect(event);
        assertEquals(OwnedServerRegistry.State.STARTING, entry.state);
        assertTrue(event.getResult().getServer().isEmpty());
        clock.addAndGet(60001);
        apiStatus = 200;
        service.sweep();
        assertEquals(OwnedServerRegistry.State.ACTIVE, entry.state);
        assertTrue(entry.paused);
    }

    @Test
    void connectedPlayerEndsStartupAllowanceSoLastLeaveUsesEmptyGrace() throws Exception {
        var entry = entry(OwnedServerRegistry.Type.TEMPORARY);
        entry.joinUntil = clock.get() + 360000;
        service.connected(target);
        assertEquals(0, entry.joinUntil);
        becomeEmpty();
        assertEquals(OwnedServerRegistry.State.PENDING_DELETION, entry.state);
        clock.addAndGet(300000);
        service.sweep();
        assertTrue(registry.all().isEmpty());
    }

    @Test
    void failedSpawnReservesQuotaAndRetryReusesSameRequest() {
        apiStatus = 502;
        service.execute(invocation("spawn", "temporary", "Survival"));
        var pending = registry.all().get(0);
        assertEquals(OwnedServerRegistry.State.PROVISIONING, pending.state);
        assertEquals(pending.requestId.toString(), com.google.gson.JsonParser.parseString(spawnBody)
                .getAsJsonObject().get("requestId").getAsString());
        apiStatus = 200;
        service.execute(invocation("retry", pending.requestId.toString()));
        assertEquals(OwnedServerRegistry.State.ACTIVE, pending.state);
        assertEquals(spawnedId.toString(), pending.serverId);
        assertEquals(pending.requestId.toString(), com.google.gson.JsonParser.parseString(spawnBody)
                .getAsJsonObject().get("requestId").getAsString());
        assertEquals(1, registry.all().size());
    }

    @Test
    void npcSpawningUsesSameOwnershipLimitsAndStableRequestIds() throws Exception {
        UUID requestId = UUID.randomUUID();
        var request = LifecycleRequest.parse(new String[]{"spawn", "Survival", "--send-player", "Luis",
                "--type", "permanent", "--request-id", requestId.toString()});
        service.spawnForConsole(player, request);
        service.spawnForConsole(player, request);
        assertEquals(1, registry.all().size());
        assertEquals(1, calls.size());
        assertEquals(player.getUniqueId(), registry.all().get(0).owner);
        assertThrows(java.io.IOException.class, () -> service.spawnForConsole(player,
                LifecycleRequest.parse(new String[]{"spawn", "Survival", "--send-player", "Luis", "--type", "permanent"})));
        service.execute(invocation("spawn", "permanent", "Survival"));
        assertEquals(1, calls.size());
        service.execute(invocation("spawn", "temporary", "Survival"));
        assertEquals(2, calls.size());
    }

    @Test
    void rejoiningStoppedTemporaryCancelsDeletionAndRestartsItsEmptyCycle() throws Exception {
        var entry = entry(OwnedServerRegistry.Type.TEMPORARY);
        becomeEmpty();
        long originalDeadline = entry.deleteAfter;
        clock.addAndGet(299000);
        service.beforeConnect(new ServerPreConnectEvent(player, target));
        assertEquals(OwnedServerRegistry.State.ACTIVE, entry.state);
        assertEquals(0, entry.deleteAfter);
        assertEquals(List.of("POST /api/minecraft/v1/instances/" + entry.serverId + "/stop",
                "POST /api/minecraft/v1/instances/" + entry.serverId + "/start"), calls);
        clock.set(originalDeadline + 1);
        service.sweep();
        assertEquals(1, registry.all().size());
        service.connected(target);
        becomeEmpty();
        assertEquals(OwnedServerRegistry.State.PENDING_DELETION, entry.state);
        assertTrue(entry.deleteAfter > originalDeadline);
    }

    @Test
    void deletionDeadlineSurvivesRestartAndLateJoinsAreRefused() throws Exception {
        var entry = entry(OwnedServerRegistry.Type.TEMPORARY);
        becomeEmpty();
        var restored = new OwnedServerRegistry(directory.resolve("owned.json"));
        assertEquals(entry.deleteAfter, restored.all().get(0).deleteAfter);
        var restarted = new PlayerServers(proxy, configuration, restored,
                new SpawnPlayerTransfer(proxy, (task, delay) -> {}), Runnable::run, mock(Logger.class), clock::get);
        clock.set(entry.deleteAfter - 1);
        restarted.sweep();
        assertEquals(1, calls.size());
        clock.incrementAndGet();
        restarted.execute(invocation("join", entry.serverId));
        assertEquals(1, calls.size());
        restarted.sweep();
        assertTrue(restored.all().isEmpty());
    }

    @Test
    void pausingPendingDeletionPreservesTheStoppedServerPastDeadline() throws Exception {
        var entry = entry(OwnedServerRegistry.Type.TEMPORARY);
        becomeEmpty();
        service.pauseCommand().execute(invocation(entry.serverId));
        assertEquals(OwnedServerRegistry.State.STOPPED, entry.state);
        assertEquals(0, entry.deleteAfter);
        clock.addAndGet(300001);
        service.sweep();
        assertEquals(1, registry.all().size());
        assertEquals(1, calls.size());
    }

    @Test
    void freshTemporarySpawnDeletesPendingServersBeforeQuotaReservation() throws Exception {
        var old = entry(OwnedServerRegistry.Type.TEMPORARY);
        becomeEmpty();
        var config = com.google.gson.JsonParser.parseString(Files.readString(configuration)).getAsJsonObject();
        config.getAsJsonObject("playerServers").addProperty("maxTemporaryPerUser", 1);
        Files.writeString(configuration, config.toString());
        service.execute(invocation("spawn", "temporary", "Survival"));
        assertEquals(List.of("POST /api/minecraft/v1/instances/" + old.serverId + "/stop",
                "DELETE /api/minecraft/v1/instances/" + old.serverId, "POST /api/minecraft/v1/instances"), calls);
        assertEquals(1, registry.all().size());
        assertEquals(spawnedId.toString(), registry.all().get(0).serverId);
    }

    @Test
    void newPermanentNpcSpawnAlsoDeletesPendingTemporaryServersButNotPausedOrOtherOwners() throws Exception {
        var old = entry(OwnedServerRegistry.Type.TEMPORARY);
        var retained = entry(OwnedServerRegistry.Type.TEMPORARY);
        retained.paused = true;
        var other = entry(OwnedServerRegistry.Type.TEMPORARY);
        other.owner = UUID.randomUUID();
        becomeEmpty();
        calls.clear();
        service.spawnForConsole(player, LifecycleRequest.parse(new String[]{"spawn", "Survival", "--type", "permanent",
                "--send-player", "Luis"}));
        assertEquals(List.of("DELETE /api/minecraft/v1/instances/" + old.serverId, "POST /api/minecraft/v1/instances"), calls);
        assertTrue(registry.all().contains(retained));
        assertTrue(registry.all().contains(other));
    }

    @Test
    void makingRoomNeverDeletesPermanentServers() throws Exception {
        var temporary = entry(OwnedServerRegistry.Type.TEMPORARY);
        var permanent = entry(OwnedServerRegistry.Type.PERMANENT);
        becomeEmpty();
        permanent.state = OwnedServerRegistry.State.PENDING_DELETION;
        permanent.deleteAfter = clock.get() + 60_000;
        registry.save();
        calls.clear();
        service.execute(invocation("spawn", "temporary", "Survival"));
        assertEquals(List.of("DELETE /api/minecraft/v1/instances/" + temporary.serverId, "POST /api/minecraft/v1/instances"), calls);
        assertTrue(registry.all().contains(permanent));
        assertFalse(registry.all().contains(temporary));
    }

    @Test
    void rejectedSpawnsDoNotDeletePendingServersAndOccupiedServersAreProtected() throws Exception {
        var old = entry(OwnedServerRegistry.Type.TEMPORARY);
        becomeEmpty();
        entry(OwnedServerRegistry.Type.PERMANENT);
        calls.clear();
        service.execute(invocation("spawn", "permanent", "Survival"));
        service.execute(invocation("spawn", "temporary", "Unknown"));
        assertTrue(calls.isEmpty());
        when(target.getPlayersConnected()).thenReturn(List.of(player));
        service.execute(invocation("spawn", "temporary", "Survival"));
        assertEquals(List.of("POST /api/minecraft/v1/instances"), calls);
        assertTrue(registry.all().contains(old));
    }

    @Test
    void retryingNpcRequestRejoinsPendingServerInsteadOfReplacingIt() throws Exception {
        var old = entry(OwnedServerRegistry.Type.TEMPORARY);
        var other = entry(OwnedServerRegistry.Type.TEMPORARY);
        becomeEmpty();
        calls.clear();
        service.spawnForConsole(player, LifecycleRequest.parse(new String[]{"spawn", "Survival", "--type", "temporary",
                "--send-player", "Luis", "--request-id", old.requestId.toString()}));
        assertEquals(List.of("POST /api/minecraft/v1/instances/" + old.serverId + "/start"), calls);
        assertEquals(OwnedServerRegistry.State.ACTIVE, old.state);
        assertEquals(OwnedServerRegistry.State.PENDING_DELETION, other.state);
        assertEquals(2, registry.all().size());
    }

    @Test
    void replacementDeletionFailureDoesNotSpawnAndCanBeRetried() throws Exception {
        var old = entry(OwnedServerRegistry.Type.TEMPORARY);
        becomeEmpty();
        calls.clear();
        apiStatus = 502;
        service.execute(invocation("spawn", "temporary", "Survival"));
        assertEquals(OwnedServerRegistry.State.DELETING, old.state);
        assertEquals(List.of("DELETE /api/minecraft/v1/instances/" + old.serverId), calls);
        assertEquals(1, registry.all().size());
        apiStatus = 404;
        service.execute(invocation("spawn", "temporary", "Survival"));
        assertFalse(registry.all().contains(old));
        assertEquals(OwnedServerRegistry.State.PROVISIONING, registry.all().get(0).state);
        assertEquals("POST /api/minecraft/v1/instances", calls.get(calls.size() - 1));
    }

    @Test
    void observedOccupancyCancelsOldDeadlineBeforeASecondDeparture() throws Exception {
        var entry = entry(OwnedServerRegistry.Type.TEMPORARY);
        becomeEmpty();
        long oldDeadline = entry.deleteAfter;
        when(target.getPlayersConnected()).thenReturn(List.of(player));
        service.sweep();
        assertEquals(OwnedServerRegistry.State.ACTIVE, entry.state);
        assertEquals(0, entry.deleteAfter);
        clock.set(oldDeadline + 1);
        when(target.getPlayersConnected()).thenReturn(List.of());
        becomeEmpty();
        assertEquals(OwnedServerRegistry.State.PENDING_DELETION, entry.state);
        assertTrue(entry.deleteAfter > oldDeadline);
        assertTrue(calls.stream().noneMatch(call -> call.startsWith("DELETE")));
    }

    @Test
    void customDeletionWindowStartsOnlyAfterSuccessfulShutdown() throws Exception {
        var config = com.google.gson.JsonParser.parseString(Files.readString(configuration)).getAsJsonObject();
        config.getAsJsonObject("playerServers").addProperty("deletionGraceSeconds", 120);
        Files.writeString(configuration, config.toString());
        var entry = entry(OwnedServerRegistry.Type.TEMPORARY);
        becomeEmpty();
        assertEquals(clock.get() + 120000, entry.deleteAfter);
        clock.addAndGet(119999);
        service.sweep();
        assertEquals(1, calls.size());
        clock.incrementAndGet();
        service.sweep();
        assertTrue(registry.all().isEmpty());
    }

    private Player guest(String username) {
        Player guest = mock(Player.class);
        when(guest.getUniqueId()).thenReturn(UUID.randomUUID());
        when(guest.getUsername()).thenReturn(username);
        when(guest.isActive()).thenReturn(true);
        when(guest.hasPermission("vestri.servers")).thenReturn(true);
        when(guest.hasPermission("vestri.servers.join")).thenReturn(true);
        when(guest.hasPermission("vestri.servers.list")).thenReturn(true);
        when(proxy.getPlayer(username)).thenReturn(java.util.Optional.of(guest));
        when(proxy.getPlayer(guest.getUniqueId())).thenReturn(java.util.Optional.of(guest));
        return guest;
    }

    private SimpleCommand.Invocation from(Player source, String... arguments) {
        var invocation = mock(SimpleCommand.Invocation.class);
        when(invocation.source()).thenReturn(source);
        when(invocation.arguments()).thenReturn(arguments);
        return invocation;
    }

    private static String text(Component component) {
        StringBuilder output = new StringBuilder(component instanceof TextComponent text ? text.content() : "");
        component.children().forEach(child -> output.append(text(child)));
        return output.toString();
    }

    @Test
    void npcSpawnAutomaticallyRecoversInitialGatewayErrorWithoutManualRetry() throws Exception {
        transientSpawnFailures = 2;
        when(player.hasPermission(anyString())).thenReturn(false);
        UUID requestId = UUID.randomUUID();
        service.spawnForConsole(player, LifecycleRequest.parse(new String[]{"spawn", "Survival", "--type", "temporary",
                "--send-player", "Owner", "--request-id", requestId.toString()}));
        assertEquals(3, spawnRequests.size());
        assertEquals(1, spawnRequests.stream().distinct().count());
        assertEquals(1, registry.all().size());
        assertEquals(requestId, registry.all().get(0).requestId);
        assertEquals(player.getUniqueId(), registry.all().get(0).owner);
        assertEquals(OwnedServerRegistry.State.ACTIVE, registry.all().get(0).state);
        assertEquals("Survival-1", registry.all().get(0).name);
        verify(target).ping();
    }

    @Test
    void invitedGuestCanJoinPermanentServerWhileOwnerIsOfflineButCannotManageIt() throws Exception {
        var entry = entry(OwnedServerRegistry.Type.PERMANENT);
        Player guest = guest("Guest");
        service.execute(invocation("invite", "Survival-1", "Guest"));
        assertTrue(entry.invited.containsKey(guest.getUniqueId()));
        assertEquals(List.of("Survival-1"), service.suggest(from(guest, "join", "")));
        when(player.isActive()).thenReturn(false);
        when(proxy.getPlayer(player.getUniqueId())).thenReturn(java.util.Optional.empty());
        service.execute(from(guest, "join", "Survival-1"));
        verify(target).ping();
        assertEquals(player.getUniqueId(), entry.owner);
        when(guest.hasPermission("vestri.servers.delete")).thenReturn(true);
        when(guest.hasPermission("vestri.servers.invite")).thenReturn(true);
        service.execute(from(guest, "delete", "Survival-1", "--confirm"));
        service.execute(from(guest, "invite", "Survival-1", "Other"));
        assertTrue(calls.isEmpty());
        assertEquals(1, entry.invited.size());
        var restored = new OwnedServerRegistry(directory.resolve("owned.json"));
        assertEquals(entry.serverId, restored.requireJoin(guest.getUniqueId(), "Survival-1", false).serverId);
    }

    @Test
    void uninvitedAndRevokedGuestsCannotJoinOrSeeOtherServers() throws Exception {
        var entry = entry(OwnedServerRegistry.Type.PERMANENT);
        Player guest = guest("Guest");
        service.execute(from(guest, "join", "Survival-1"));
        assertEquals(List.of(), service.suggest(from(guest, "join", "")));
        verify(target, never()).ping();
        service.execute(invocation("invite", "Survival-1", "Guest"));
        service.execute(invocation("revoke", "Survival-1", "Guest"));
        assertFalse(entry.invited.containsKey(guest.getUniqueId()));
        service.execute(from(guest, "join", "Survival-1"));
        verify(target, never()).ping();
        assertEquals(List.of(), service.suggest(from(guest, "join", "")));
    }

    @Test
    void adminJoinPermissionBypassesAccessWithoutGrantingOwnershipOrRequiringNormalJoin() throws Exception {
        var entry = entry(OwnedServerRegistry.Type.PERMANENT);
        Player admin = guest("Admin");
        when(admin.hasPermission("vestri.servers.join")).thenReturn(false);
        when(admin.hasPermission("vestri.servers.join.admin")).thenReturn(true);
        service.execute(from(admin, "join", "Survival-1"));
        verify(target).ping();
        assertEquals(List.of("Survival-1"), service.suggest(from(admin, "join", "")));
        when(admin.hasPermission("vestri.servers.delete")).thenReturn(true);
        service.execute(from(admin, "delete", "Survival-1", "--confirm"));
        assertEquals(player.getUniqueId(), entry.owner);
        assertTrue(calls.isEmpty());
    }

    @Test
    void permissionsRestrictEveryActionAndRetryCannotBypassJoinPermission() throws Exception {
        var entry = entry(OwnedServerRegistry.Type.PERMANENT);
        for (String action : List.of("list", "retry", "delete", "invite", "revoke")) {
            when(player.hasPermission("vestri.servers." + action)).thenReturn(false);
            assertFalse(service.hasPermission(invocation(action)));
        }
        when(player.hasPermission("vestri.servers.spawn.permanent")).thenReturn(false);
        when(player.hasPermission("vestri.servers.spawn.temporary")).thenReturn(true);
        assertFalse(service.hasPermission(invocation("spawn", "permanent", "Survival")));
        assertTrue(service.hasPermission(invocation("spawn", "temporary", "Survival")));
        assertEquals(List.of("temporary"), service.suggest(invocation("spawn", "")));
        when(player.hasPermission("vestri.servers.join")).thenReturn(false);
        when(player.hasPermission("vestri.servers.join.admin")).thenReturn(false);
        when(player.hasPermission("vestri.servers.retry")).thenReturn(true);
        service.execute(invocation("join", "Survival-1"));
        service.execute(invocation("retry", "Survival-1"));
        verify(target, never()).ping();
        when(player.hasPermission("vestri.servers.pause")).thenReturn(false);
        assertFalse(service.pauseCommand().hasPermission(invocation(entry.serverId)));
    }

    @Test
    void invitationsArePermanentOnlyAndRevocationWorksForOfflineGuests() throws Exception {
        var temporary = entry(OwnedServerRegistry.Type.TEMPORARY);
        Player guest = guest("Guest");
        service.execute(invocation("invite", "Survival-1", "Guest"));
        assertTrue(temporary.invited.isEmpty());
        temporary.type = OwnedServerRegistry.Type.PERMANENT;
        service.execute(invocation("invite", "Survival-1", "Guest"));
        when(proxy.getPlayer("Guest")).thenReturn(java.util.Optional.empty());
        service.execute(invocation("revoke", "Survival-1", "Guest"));
        assertTrue(temporary.invited.isEmpty());
        assertThrows(java.io.IOException.class, () -> registry.requireJoin(guest.getUniqueId(), "Survival-1", false));
    }

    @Test
    void playerMessagesAndCompletionUseNamesNeverInternalIds() throws Exception {
        service.execute(invocation("spawn", "temporary", "Survival"));
        var entry = registry.all().get(0);
        service.execute(invocation("list"));
        service.pauseCommand().execute(invocation("Survival-1"));
        service.execute(invocation("join", "Survival-1"));
        var messages = ArgumentCaptor.forClass(Component.class);
        verify(player, atLeastOnce()).sendMessage(messages.capture());
        String all = String.join("\n", messages.getAllValues().stream().map(PlayerServersTest::text).toList());
        assertTrue(all.contains("Survival-1"));
        assertFalse(all.contains(entry.serverId));
        assertFalse(all.contains(entry.requestId.toString()));
        assertFalse(all.contains(entry.slug));
        assertTrue(messages.getAllValues().stream().anyMatch(component -> component.color() != null));
        assertEquals(List.of("Survival-1"), service.suggest(invocation("join", "")));
    }

    @Test
    void revokingInvitationCancelsAlreadyQueuedGuestJoin() throws Exception {
        var entry = entry(OwnedServerRegistry.Type.PERMANENT);
        Player guest = guest("Guest");
        List<Runnable> waiting = new ArrayList<>();
        var guarded = new PlayerServers(proxy, configuration, registry,
                new SpawnPlayerTransfer(proxy, (task, delay) -> waiting.add(task)), Runnable::run, mock(Logger.class), clock::get);
        guarded.execute(invocation("invite", "Survival-1", "Guest"));
        guarded.execute(from(guest, "join", "Survival-1"));
        assertEquals(1, waiting.size());
        guarded.execute(invocation("revoke", "Survival-1", "Guest"));
        waiting.remove(0).run();
        verify(target, times(1)).ping();
        verify(guest, never()).createConnectionRequest(any());
        assertTrue(waiting.isEmpty());
        assertTrue(entry.invited.isEmpty());
    }
}