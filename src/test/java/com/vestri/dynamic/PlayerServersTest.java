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

    @BeforeEach
    void setup() throws Exception {
        apiStatus = 200;
        spawnedId = UUID.randomUUID();
        api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        api.createContext("/api/minecraft/v1/instances", exchange -> {
            calls.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
                spawnBody = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                String body = exchange.getRequestURI().getPath().equals("/api/minecraft/v1/instances")
                    ? "{\"serverId\":\"" + spawnedId + "\",\"slug\":\"mcgame-" + "a".repeat(32) + "\"}" : "{}";
                byte[] response = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(apiStatus, response.length);
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
}