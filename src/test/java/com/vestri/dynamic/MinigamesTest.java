package com.vestri.dynamic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;

class MinigamesTest {
    @TempDir Path directory;
    private final AtomicLong clock = new AtomicLong(1000000);
    private final ProxyServer proxy = mock(ProxyServer.class);
    private final Parties parties = new Parties();
    private final Logger logger = mock(Logger.class);
    private Minigames service;
    private static final String TOKEN = "a".repeat(64);
    private static final String SERVER_ID = "11111111-1111-1111-1111-111111111111";
    private final List<Player> occupants = new ArrayList<>();
    private final List<UUID> transfers = new ArrayList<>();
    private RegisteredServer target;
    private static final String ADMISSION_SLUG = "mcgame-" + "1".repeat(32);
    private static final String SECOND_ADMISSION_SLUG = "mcgame-" + "2".repeat(32);

    @Test
    void pinnedLifecyclePersistsBeforeSpawnAndKeepsOriginalKeyAndOriginAfterRestart() throws Exception {
        service = new Minigames(proxy, parties, directory, logger, clock::get);
        Minigames.Instance instance = pending();
        List<String> calls = new ArrayList<>();
        List<String> authorizations = new ArrayList<>();
        HttpServer api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        api.createContext("/api/minecraft/v1/instances", exchange -> {
            calls.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            try {
                assertEquals(instance.lifecycleConfig, persisted().get(0).lifecycleConfig);
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
            byte[] body = ("{\"serverId\":\"" + SERVER_ID + "\",\"slug\":\"" + ADMISSION_SLUG + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            if (exchange.getRequestMethod().equals("DELETE")) {
                exchange.sendResponseHeaders(204, -1);
            } else {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        api.start();
        try {
            Path config = directory.resolve("vestri-dynamic-config.json");
            Files.writeString(config, "{\"backendUrl\":\"http://127.0.0.1:" + api.getAddress().getPort()
                    + "\",\"templates\":{\"Arena\":\"vmc_" + TOKEN + "\"},\"consoleBridge\":{\"secret\":\"not-pinned\"}}");
            var capture = Minigames.class.getDeclaredMethod("captureLifecycleConfiguration", Minigames.Instance.class, boolean.class);
            capture.setAccessible(true);
            capture.invoke(service, instance, false);
            instances().add(instance);
            var provision = Minigames.class.getDeclaredMethod("provision", Minigames.Instance.class);
            provision.setAccessible(true);
            Files.writeString(config, "{\"backendUrl\":\"https://rotated.example.com\",\"templates\":{\"Arena\":\"vmc_" + "b".repeat(64) + "\"}}");
            provision.invoke(service, instance);
            assertEquals(SERVER_ID, instance.serverId);
            assertFalse(instance.lifecycleCredentialsUnverified);
            assertEquals(Set.of("backendUrl", "templates"), persisted().get(0).lifecycleConfig.keySet());
            assertEquals(Set.of("Arena"), instance.lifecycleConfig.getAsJsonObject("templates").keySet());
            Files.writeString(config, "{");
            provision.invoke(service, instance);
            service.close();
            service = new Minigames(proxy, parties, directory, logger, clock::get);
            cleanup(instances().get(0));
            assertTrue(instances().isEmpty());
            assertEquals(List.of("POST /api/minecraft/v1/instances", "DELETE /api/minecraft/v1/instances/" + SERVER_ID), calls);
            assertEquals(List.of("Bearer vmc_" + TOKEN, "Bearer vmc_" + TOKEN), authorizations);
            if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
                assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
                        Files.getPosixFilePermissions(directory.resolve("vestri-minigames.json")));
                assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(directory));
            }
            verifyNoInteractions(logger);
        } finally {
            api.stop(0);
        }
    }

    @Test
    void pinnedPendingCleanupUsesOriginalLookupKeyEvenWithMalformedCurrentConfig() throws Exception {
        service = new Minigames(proxy, parties, directory, logger, clock::get);
        Minigames.Instance instance = pending();
        List<String> calls = new ArrayList<>();
        HttpServer api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        api.createContext("/api/minecraft/v1/instances", exchange -> {
            assertEquals("Bearer vmc_" + TOKEN, exchange.getRequestHeaders().getFirst("Authorization"));
            calls.add(exchange.getRequestMethod());
            byte[] body = ("{\"instance\":{\"serverId\":\"" + SERVER_ID + "\",\"slug\":\"" + ADMISSION_SLUG + "\"}}")
                    .getBytes(StandardCharsets.UTF_8);
            if (exchange.getRequestMethod().equals("DELETE")) {
                exchange.sendResponseHeaders(204, -1);
            } else {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        api.start();
        try {
            Path config = directory.resolve("vestri-dynamic-config.json");
            Files.writeString(config, "{\"backendUrl\":\"http://127.0.0.1:" + api.getAddress().getPort()
                    + "\",\"templates\":{\"Arena\":\"vmc_" + TOKEN + "\"}}");
            instance.lifecycleConfig = MinecraftInstanceClient.snapshot(config, "Arena");
            instances().add(instance);
            Files.writeString(config, "{");
            cleanup(instance);
            assertEquals(List.of("GET", "DELETE"), calls);
            assertTrue(persisted().isEmpty());
        } finally {
            api.stop(0);
        }
    }

    @Test
    void pinnedProvisionRetryAfter509KeepsKnownOriginalCredentialsWithoutAnInstanceId() throws Exception {
        service = new Minigames(proxy, parties, directory, logger, clock::get);
        Minigames.Instance instance = pending();
        List<String> authorizations = new ArrayList<>();
        List<String> requests = new ArrayList<>();
        HttpServer api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        api.createContext("/api/minecraft/v1/instances", exchange -> {
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = ("{\"serverId\":\"" + SERVER_ID + "\",\"slug\":\"" + ADMISSION_SLUG + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(requests.size() == 1 ? 509 : 200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        api.start();
        try {
            Path config = directory.resolve("vestri-dynamic-config.json");
            Files.writeString(config, "{\"backendUrl\":\"http://127.0.0.1:" + api.getAddress().getPort()
                    + "\",\"templates\":{\"Arena\":\"vmc_" + TOKEN + "\"}}");
            instance.lifecycleConfig = MinecraftInstanceClient.snapshot(config, instance.template);
            instances().add(instance);
            var provision = Minigames.class.getDeclaredMethod("provision", Minigames.Instance.class);
            provision.setAccessible(true);
            provision.invoke(service, instance);
            assertNull(instance.serverId);
            assertEquals(instance.lifecycleConfig, persisted().get(0).lifecycleConfig);
            Files.writeString(config, "{\"backendUrl\":\"https://rotated.example.com\",\"templates\":{\"Arena\":\"vmc_" + "b".repeat(64) + "\"}}");
            provision.invoke(service, instance);
            assertEquals(SERVER_ID, instance.serverId);
            assertEquals(List.of("Bearer vmc_" + TOKEN, "Bearer vmc_" + TOKEN), authorizations);
            assertEquals(requests.get(0), requests.get(1));
            var details = org.mockito.ArgumentCaptor.forClass(Object[].class);
            verify(logger).warn(contains("Minigame preparation pending"), details.capture());
            assertFalse(java.util.Arrays.toString(details.getValue()).contains(TOKEN));
        } finally {
            api.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lifecycleNeverContactsBackendIfCredentialLedgerExceedsLimits(boolean tooManyInstances) throws Exception {
        service = new Minigames(proxy, parties, directory, logger, clock::get);
        configure("Arena");
        Minigames.Instance instance = pending();
        instance.lifecycleConfig = MinecraftInstanceClient.snapshot(directory.resolve("vestri-dynamic-config.json"), "Arena");
        instances().add(instance);
        if (tooManyInstances) {
            for (int index = 0; index < 100; index++) {
                instances().add(pending());
            }
        } else {
            instance.specification = new JsonObject();
            instance.specification.addProperty("padding", "x".repeat(1024 * 1024));
        }
        var provision = Minigames.class.getDeclaredMethod("provision", Minigames.Instance.class);
        provision.setAccessible(true);
        provision.invoke(service, instance);
        assertNull(instance.serverId);
        var failed = Minigames.class.getDeclaredField("persistenceFailed");
        failed.setAccessible(true);
        assertEquals(true, failed.get(service));
        assertFalse(Files.exists(directory.resolve("vestri-minigames.json")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void legacyLifecycleNeverLosesQuotaOrKnownIdWithReplacementKey(boolean knownId) throws Exception {
        service = new Minigames(proxy, parties, directory, logger, clock::get);
        Minigames.Instance instance = pending();
        instance.serverId = knownId ? SERVER_ID : null;
        instances().add(instance);
        List<String> calls = new ArrayList<>();
        HttpServer api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        api.createContext("/api/minecraft/v1/instances", exchange -> {
            calls.add(exchange.getRequestMethod());
            exchange.sendResponseHeaders(403, -1);
            exchange.close();
        });
        api.start();
        try {
            Path config = directory.resolve("vestri-dynamic-config.json");
            Files.writeString(config, "{\"backendUrl\":\"http://127.0.0.1:" + api.getAddress().getPort()
                    + "\",\"templates\":{\"Arena\":\"vmc_" + "b".repeat(64) + "\"}}");
            cleanup(instance);
            assertEquals(knownId ? List.of("DELETE") : List.of(), calls);
            assertEquals(knownId ? SERVER_ID : null, persisted().get(0).serverId);
            assertTrue(persisted().get(0).lifecycleCredentialsUnverified);
            assertEquals(1, instances().size());
            var details = org.mockito.ArgumentCaptor.forClass(Object[].class);
            verify(logger, atLeastOnce()).warn(anyString(), details.capture());
            for (Object[] arguments : details.getAllValues()) {
                assertFalse(java.util.Arrays.toString(arguments).contains("b".repeat(64)));
                assertFalse(java.util.Arrays.toString(arguments).contains("vmc_"));
            }
            Files.writeString(config, "{");
            cleanup(instance);
            assertEquals(1, instances().size());
        } finally {
            api.stop(0);
        }
    }

    @Test
    void ledgerTemporaryAndExistingFinalAreOwnerOnlyBeforeCredentialsAreWritten() throws Exception {
        service = new Minigames(proxy, parties, directory, logger, clock::get);
        Path ledger = directory.resolve("vestri-minigames.json");
        Files.writeString(ledger, "[]");
        boolean posix = Files.getFileStore(directory).supportsFileAttributeView("posix");
        if (posix) {
            Files.setPosixFilePermissions(ledger, java.nio.file.attribute.PosixFilePermissions.fromString("rw-rw-rw-"));
            Files.setPosixFilePermissions(directory, java.nio.file.attribute.PosixFilePermissions.fromString("rwxrwxrwx"));
        }
        var prepare = Minigames.class.getDeclaredMethod("privateLedgerTemporary");
        prepare.setAccessible(true);
        Path temporary = (Path) prepare.invoke(service);
        try {
            if (posix) {
                var privateFile = java.nio.file.attribute.PosixFilePermissions.fromString("rw-------");
                assertEquals(privateFile, Files.getPosixFilePermissions(temporary));
                assertEquals(privateFile, Files.getPosixFilePermissions(ledger));
                assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(directory));
            }
        } finally {
            Files.delete(temporary);
        }
    }

    @Test
    void specificNativeReadyPartyWaitsForExactRosterAckAndTransfersOnlyToRequestedServer() throws Exception {
        JsonObject manifest = lobbyManifest(true, true);
        Minigames.Instance first = admissionReady(manifest);
        Minigames.Instance exact = secondManualInstance(manifest);
        Player[] members = party(2);
        List<String> output = new ArrayList<>();
        assertTrue(service.joinInstance(members[0], spawned(exact), output::add));
        assertTrue(first.roster.isEmpty());
        assertEquals(2, exact.roster.size());
        assertEquals("joining", exact.state);
        assertTrue(queue().isEmpty());
        assertTrue(transfers.isEmpty());
        assertTrue(output.get(0).contains("complete roster handshake"));
        assertTrue(service.joinInstance(members[0], spawned(exact), output::add));
        assertEquals(1, exact.rosterVersion);
        assertEquals(1, exact.groups.size());
        assertTrue(output.get(1).contains("Awaiting"));
        service.report(exact, versionedReport(exact, "running", 0));
        assertTrue(transfers.isEmpty());
        service.report(exact, versionedReport(exact, "running", 1));
        RegisteredServer exactServer = proxy.getServer("Arena-2").orElseThrow();
        for (Player member : members) {
            verify(member).createConnectionRequest(exactServer);
            verify(member, never()).createConnectionRequest(target);
        }
        assertEquals(2, transfers.size());
        assertEquals("running", exact.state);
        assertEquals(2, persisted().size());
    }

    @Test
    void specificNativeRunningAdditionWaitsForPriorRosterThenReservesWholeParty() throws Exception {
        Minigames.Instance instance = admissionReady(lobbyManifest(true, true));
        Player first = player("First");
        assertTrue(service.joinInstance(first, spawned(instance), ignored -> {}));
        Player[] additions = party(2);
        List<String> output = new ArrayList<>();
        assertTrue(service.joinInstance(additions[0], spawned(instance), output::add));
        sweepWithoutNetwork(1);
        assertEquals(1, instance.rosterVersion);
        service.report(instance, versionedReport(instance, "running", 1));
        sweepWithoutNetwork(1);
        assertEquals(1, instance.roster.size());
        occupants.add(first);
        service.report(instance, versionedReport(instance, "running", 1, first));
        sweepWithoutNetwork(1);
        assertEquals(2, instance.rosterVersion);
        assertEquals(3, instance.roster.size());
        assertEquals(1, transfers.size());
        service.report(instance, versionedReport(instance, "running", 1, first));
        assertEquals(1, transfers.size());
        service.report(instance, versionedReport(instance, "running", 2, first));
        assertEquals(3, transfers.size());
        assertEquals(1, instances().size());
        assertTrue(queue().isEmpty());
    }

    @Test
    void specificNativeCapacityNeverPartiallyAdmitsPartyOrUsesAnotherReadyInstance() throws Exception {
        JsonObject manifest = lobbyManifest(true, true);
        Minigames.Instance instance = admissionReady(manifest);
        Minigames.Instance spare = secondManualInstance(manifest);
        Player[] existing = party(3);
        assertTrue(service.joinInstance(existing[0], spawned(instance), ignored -> {}));
        occupants.addAll(List.of(existing));
        service.report(instance, versionedReport(instance, "running", 1, existing));
        Player[] next = party(2);
        List<String> output = new ArrayList<>();
        assertTrue(service.joinInstance(next[0], spawned(instance), output::add));
        assertTrue(output.get(0).contains("insufficient capacity"));
        sweepWithoutNetwork(1);
        assertEquals(3, instance.roster.size());
        assertTrue(spare.roster.isEmpty());
        assertEquals(1, instance.rosterVersion);
        assertTrue(queue().isEmpty());
        verify(next[0], never()).createConnectionRequest(any());
        verify(next[1], never()).createConnectionRequest(any());
    }

    @Test
    void specificAdmissionRequiresPlayerPermissionEvenForConsoleApi() throws Exception {
        Minigames.Instance instance = admissionReady(lobbyManifest(true, true));
        Player denied = player("Denied");
        when(denied.hasPermission("vestri.minigames")).thenReturn(false);
        List<String> output = new ArrayList<>();
        assertTrue(service.joinInstance(denied, spawned(instance), output::add));
        assertTrue(output.get(0).contains("vestri.minigames"));
        assertTrue(instance.roster.isEmpty());
        assertTrue(transfers.isEmpty());
    }

    @Test
    void specificAdmissionRequiresLeaderOnlinePermittedUnqueuedParty() throws Exception {
        Minigames.Instance instance = admissionReady(lobbyManifest(true, true));
        Player[] members = party(2);
        List<String> output = new ArrayList<>();
        assertTrue(service.joinInstance(members[1], spawned(instance), output::add));
        assertTrue(output.get(0).contains("party leader"));
        when(proxy.getPlayer(members[1].getUniqueId())).thenReturn(Optional.empty());
        service.joinInstance(members[0], spawned(instance), output::add);
        assertTrue(output.get(1).contains("online"));
        when(proxy.getPlayer(members[1].getUniqueId())).thenReturn(Optional.of(members[1]));
        when(members[1].hasPermission("vestri.minigames")).thenReturn(false);
        service.joinInstance(members[0], spawned(instance), output::add);
        assertTrue(output.get(2).contains("lacks permission"));
        when(members[1].hasPermission("vestri.minigames")).thenReturn(true);
        queue(members[0]);
        service.joinInstance(members[0], spawned(instance), output::add);
        assertTrue(output.get(3).contains("queued or playing elsewhere"));
        assertTrue(instance.roster.isEmpty());
    }

    @Test
    void specificNativeRosterMemberCanReconnectWithoutLeadingParty() throws Exception {
        Minigames.Instance instance = admissionReady(lobbyManifest(true, true));
        Player[] members = party(2);
        service.joinInstance(members[0], spawned(instance), ignored -> {});
        service.report(instance, versionedReport(instance, "running", 1));
        service.joinInstance(members[1], spawned(instance), ignored -> {});
        assertEquals(3, transfers.size());
        assertEquals(1, instance.rosterVersion);
        assertEquals(1, instance.groups.size());
        ServerPreConnectEvent reconnect = new ServerPreConnectEvent(members[1], target);
        service.beforeConnect(reconnect);
        assertTrue(reconnect.getResult().isAllowed());
    }

    @Test
    void specificManagedAdmissionUsesNormalMinPlayersQueueWithoutSpoofingRoster() throws Exception {
        Minigames.Instance instance = admissionReady(lobbyManifest(false, true));
        Player first = player("First");
        List<String> output = new ArrayList<>();
        assertTrue(service.joinInstance(first, spawned(instance), output::add));
        assertTrue(output.get(0).contains("Waiting for minPlayers (2)"));
        assertTrue(output.get(0).contains("use /minigame join Arena"));
        sweepWithoutNetwork(1);
        assertEquals("ready", instance.state);
        assertTrue(instance.roster.isEmpty());
        assertEquals(1, queue().size());
        assertTrue(transfers.isEmpty());
    }

    @Test
    void specificUnknownOrStaleTargetIsNotTransferred() throws Exception {
        Minigames.Instance instance = admissionReady(lobbyManifest(true, true));
        Player first = player("First");
        List<String> output = new ArrayList<>();
        assertFalse(service.joinInstance(first, new MinecraftInstanceClient.SpawnedInstance(UUID.randomUUID().toString(),
                "mcgame-" + "3".repeat(32), "Arena-1"), output::add));
        assertTrue(output.isEmpty());
        assertTrue(service.joinInstance(first, new MinecraftInstanceClient.SpawnedInstance(UUID.randomUUID().toString(),
                instance.slug, "Arena-1"), output::add));
        assertTrue(output.get(0).contains("unavailable"));
        assertTrue(instance.roster.isEmpty());
        assertTrue(transfers.isEmpty());
    }

    @Test
    void specificNativePrereadyWaitsOnlyForTargetAndExpiresAt300Seconds() throws Exception {
        JsonObject manifest = lobbyManifest(true, true);
        Minigames.Instance instance = admissionReady(manifest);
        Minigames.Instance spare = secondManualInstance(manifest);
        instance.state = "provisioning";
        instance.heartbeat = 0;
        Player first = player("First");
        List<String> output = new ArrayList<>();
        service.joinInstance(first, spawned(instance), output::add);
        sweepWithoutNetwork(3);
        assertTrue(instance.roster.isEmpty());
        assertTrue(spare.roster.isEmpty());
        assertEquals(2, instances().size());
        assertTrue(queue().isEmpty());
        service.report(instance, report("ready", null));
        sweepWithoutNetwork(1);
        assertEquals(1, instance.roster.size());
        assertTrue(spare.roster.isEmpty());
        assertTrue(transfers.isEmpty());

        spare.state = "provisioning";
        spare.heartbeat = 0;
        Player next = player("Next");
        service.joinInstance(next, spawned(spare), output::add);
        clock.addAndGet(300000);
        sweepWithoutNetwork(1);
        assertTrue(output.get(output.size() - 1).contains("no other server was requested"));
        assertTrue(queue().isEmpty());
        assertEquals(2, instances().size());
        assertTrue(transfers.isEmpty());
    }

    @Test
    void specificNativePendingJoinRejectsOtherTargetsAndPartyChanges() throws Exception {
        JsonObject manifest = lobbyManifest(true, true);
        Minigames.Instance instance = admissionReady(manifest);
        Minigames.Instance spare = secondManualInstance(manifest);
        instance.state = "provisioning";
        Player[] members = party(2);
        List<String> output = new ArrayList<>();
        service.joinInstance(members[0], spawned(instance), output::add);
        service.joinInstance(members[0], spawned(spare), output::add);
        assertTrue(output.get(1).contains("another minigame instance"));
        when(members[1].hasPermission("vestri.minigames")).thenReturn(false);
        service.report(instance, report("ready", null));
        sweepWithoutNetwork(1);
        assertTrue(instance.roster.isEmpty());
        assertTrue(spare.roster.isEmpty());
        assertTrue(output.get(2).contains("unavailable"));
    }

    @Test
    void nativeDirectPreConnectDeniesOriginalAttemptThenTransfersAfterRosterAck() throws Exception {
        Minigames.Instance instance = admissionReady(lobbyManifest(true, true));
        Player first = player("First");
        var connection = mock(com.velocitypowered.api.proxy.ServerConnection.class);
        when(first.getCurrentServer()).thenReturn(Optional.of(connection));
        ServerPreConnectEvent direct = new ServerPreConnectEvent(first, target);
        service.beforeConnect(direct);
        assertFalse(direct.getResult().isAllowed());
        assertEquals(1, instance.roster.size());
        assertTrue(transfers.isEmpty());
        ServerPreConnectEvent duplicate = new ServerPreConnectEvent(first, target);
        service.beforeConnect(duplicate);
        assertFalse(duplicate.getResult().isAllowed());
        assertEquals(1, instance.rosterVersion);
        service.report(instance, versionedReport(instance, "running", 1));
        assertEquals(List.of(first.getUniqueId()), transfers);
        ServerPreConnectEvent acknowledged = new ServerPreConnectEvent(first, target);
        service.beforeConnect(acknowledged);
        assertTrue(acknowledged.getResult().isAllowed());
    }

    @Test
    void nativeInitialConnectionNeverAutomaticallyReservesRoster() throws Exception {
        Minigames.Instance instance = admissionReady(lobbyManifest(true, true));
        Player first = player("Initial");
        ServerPreConnectEvent initial = new ServerPreConnectEvent(first, target);
        service.beforeConnect(initial);
        assertFalse(initial.getResult().isAllowed());
        assertTrue(instance.roster.isEmpty());
        assertTrue(transfers.isEmpty());
    }

    @Test
    void specificNativeKnownInstanceWaitsForMissingManifestBindingWithoutReplacement() throws Exception {
        JsonObject manifest = lobbyManifest(true, true);
        Minigames.Instance instance = admissionReady(manifest);
        JsonObject partial = manifest.deepCopy();
        partial.getAsJsonObject("servers").remove(ADMISSION_SLUG);
        service.synchronize(partial);
        Player first = player("First");
        List<String> output = new ArrayList<>();
        assertTrue(service.joinInstance(first, spawned(instance), output::add));
        sweepWithoutNetwork(2);
        assertEquals(1, instances().size());
        assertTrue(instance.roster.isEmpty());
        assertTrue(queue().isEmpty());
        service.synchronize(manifest);
        sweepWithoutNetwork(1);
        assertEquals(1, instance.roster.size());
        assertTrue(transfers.isEmpty());
        service.report(instance, versionedReport(instance, "running", 1));
        assertEquals(List.of(first.getUniqueId()), transfers);
    }

    @Test
    void nativeDirectPermissionDenialIsInformativeAndNeverReserves() throws Exception {
        Minigames.Instance instance = admissionReady(lobbyManifest(true, true));
        Player first = player("Denied");
        when(first.hasPermission("vestri.minigames")).thenReturn(false);
        when(first.getCurrentServer()).thenReturn(Optional.of(mock(com.velocitypowered.api.proxy.ServerConnection.class)));
        ServerPreConnectEvent direct = new ServerPreConnectEvent(first, target);
        service.beforeConnect(direct);
        assertFalse(direct.getResult().isAllowed());
        assertTrue(instance.roster.isEmpty());
        verify(first).sendMessage(Component.text("You need vestri.minigames permission to join this minigame."));
        sweepWithoutNetwork(1);
        assertTrue(instance.roster.isEmpty());
        assertTrue(transfers.isEmpty());
    }

    @Test
    void specificNativeAckNeverTransfersToOrdinaryServerReusingFriendlyName() throws Exception {
        Minigames.Instance instance = admissionReady(lobbyManifest(true, true));
        Player first = player("First");
        service.joinInstance(first, spawned(instance), ignored -> {});
        RegisteredServer ordinary = mock(RegisteredServer.class);
        when(ordinary.getServerInfo()).thenReturn(new ServerInfo("Arena-1", InetSocketAddress.createUnresolved("ordinary", 25565)));
        when(proxy.getServer("Arena-1")).thenReturn(Optional.of(ordinary));
        service.report(instance, versionedReport(instance, "running", 1));
        assertTrue(transfers.isEmpty());
        verify(first, never()).createConnectionRequest(ordinary);
        ServerPreConnectEvent ordinaryConnect = new ServerPreConnectEvent(first, ordinary);
        service.beforeConnect(ordinaryConnect);
        assertTrue(ordinaryConnect.getResult().isAllowed());
    }

    @Test
    void specificNativeTransferFailureKeepsExistingDiagnosticsAndNoFallbackSpawn() throws Exception {
        Minigames.Instance instance = admissionReady(lobbyManifest(true, true));
        Player first = player("First");
        service.joinInstance(first, spawned(instance), ignored -> {});
        ConnectionRequestBuilder connection = (ConnectionRequestBuilder) Proxy.newProxyInstance(
                ConnectionRequestBuilder.class.getClassLoader(), new Class<?>[]{ConnectionRequestBuilder.class},
                (receiver, method, arguments) -> {
                    if (method.getName().equals("connect")) {
                        return CompletableFuture.failedFuture(new IllegalStateException("Connection failed"));
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        doReturn(connection).when(first).createConnectionRequest(target);
        service.report(instance, versionedReport(instance, "running", 1));
        verify(logger).warn(contains("Minigame transfer rejected"), any(Object[].class));
        verify(first).sendMessage(Component.text("Could not join the minigame. Check with an administrator; the match join timeout still applies."));
        assertEquals(1, instances().size());
        assertTrue(queue().isEmpty());
    }

    private MinecraftInstanceClient.SpawnedInstance spawned(Minigames.Instance instance) {
        return new MinecraftInstanceClient.SpawnedInstance(instance.serverId, instance.slug,
                instance.slug.equals(ADMISSION_SLUG) ? "Arena-1" : "Arena-2");
    }

    private Minigames.Instance admissionReady(JsonObject manifest) throws Exception {
        JsonObject servers = manifest.getAsJsonObject("servers");
        servers.add(ADMISSION_SLUG, servers.remove("arena"));
        Minigames.Instance instance = ready(manifest);
        when(target.getServerInfo()).thenReturn(new ServerInfo("Arena-1",
                InetSocketAddress.createUnresolved("vestri-" + ADMISSION_SLUG, 25565)));
        return instance;
    }

    private Minigames.Instance secondManualInstance(JsonObject manifest) throws Exception {
        JsonObject second = manifest.getAsJsonObject("servers").getAsJsonObject(ADMISSION_SLUG).deepCopy();
        second.addProperty("serverId", "22222222-2222-2222-2222-222222222222");
        second.addProperty("name", "Arena-2");
        second.addProperty("spawnRequestId", UUID.randomUUID().toString());
        manifest.getAsJsonObject("servers").add(SECOND_ADMISSION_SLUG, second);
        RegisteredServer secondServer = mock(RegisteredServer.class);
        when(secondServer.getServerInfo()).thenReturn(new ServerInfo("Arena-2",
                InetSocketAddress.createUnresolved("vestri-" + SECOND_ADMISSION_SLUG, 25565)));
        when(secondServer.getPlayersConnected()).thenReturn(List.of());
        when(proxy.getServer("Arena-2")).thenReturn(Optional.of(secondServer));
        service.synchronize(manifest);
        Minigames.Instance instance = instances().stream().filter(value -> SECOND_ADMISSION_SLUG.equals(value.slug)).findFirst().orElseThrow();
        assertEquals("provisioning", instance.state);
        instance.busy = true;
        service.report(instance, report("ready", null));
        return instance;
    }

    @Test
    void cleanupDeleteCallbackWithAuthoritativeSyncRemovesExactlyOnce() throws Exception {
        Minigames.Instance instance = ready();
        instance.state = "ending";
        instance.busy = true;
        List<String> calls = new ArrayList<>();
        HttpServer api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        api.createContext("/api/minecraft/v1/instances", exchange -> {
            calls.add(exchange.getRequestMethod());
            JsonObject snapshot = emptyManifest();
            snapshot.addProperty("authoritative", true);
            service.synchronize(snapshot);
            service.sweep();
            assertEquals(List.of(instance), assertDoesNotThrow(this::instances));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        api.start();
        try {
            Files.writeString(directory.resolve("vestri-dynamic-config.json"),
                    "{\"backendUrl\":\"http://127.0.0.1:" + api.getAddress().getPort()
                    + "\",\"templates\":{\"Arena\":\"vmc_" + TOKEN + "\"}}");
            cleanup(instance);
            cleanup(instance);
            assertEquals(List.of("DELETE"), calls);
            assertTrue(instances().isEmpty());
            assertTrue(persisted().isEmpty());
        } finally {
            api.stop(0);
        }
    }

    @Test
    void permanentPartiallyJoinedReservationEvacuatesAndRequeuesWholeParty() throws Exception {
        Minigames.Instance instance = ready(permanentManifest());
        Player[] members = party(2);
        queue(members[0]);
        sweepWithoutNetwork(1);
        Matchmaker.Group group = instance.groups.get(0);
        occupants.add(members[0]);
        service.report(instance, versionedReport(instance, "running", 1, members[0]));
        assertEquals(2, transfers.size());
        clock.set(instance.deadline);
        sweepWithoutNetwork(1);
        assertEquals("running", instance.state);
        assertTrue(instance.roster.isEmpty());
        assertEquals(2, instance.rosterVersion);
        assertEquals(List.of(group), queue());
        verify(members[0]).disconnect(any(Component.class));
        verify(members[1], never()).disconnect(any(Component.class));
        assertEquals(1, instances().size());
    }

    @Test
    void permanentReadyRestartRequiresFreshReportAndDoesNotCreateReplacement() throws Exception {
        JsonObject manifest = permanentManifest();
        ready(manifest);
        service.close();
        service = new Minigames(proxy, parties, directory, logger, clock::get);
        service.synchronize(manifest);
        Minigames.Instance instance = instances().get(0);
        queue(player("Next"));
        sweepWithoutNetwork(1);
        assertEquals("ready", instance.state);
        assertEquals(1, queue().size());
        assertEquals(1, instances().size());
        service.report(instance, report("ready", null));
        sweepWithoutNetwork(1);
        assertEquals("joining", instance.state);
        assertTrue(queue().isEmpty());
        assertEquals(1, instances().size());
    }

    @Test
    void permanentHttpRosterRemovalIsDurableAndPaperRestartRetainsMatch() throws Exception {
        JsonObject manifest = permanentManifest();
        JsonObject spec = manifest.getAsJsonObject("minigames").getAsJsonObject("Arena");
        Minigames.Instance instance = ready(manifest);
        Path execution = directory.resolve("permanent-paper.json");
        Player first = player("First");
        String match;
        try (PaperAgent paper = new PaperAgent(spec, execution)) {
            paper.agent.ready();
            queue(first);
            sweepWithoutNetwork(1);
            paper.exchange(Set.of());
            paper.exchange(Set.of());
            occupants.add(first);
            paper.exchange(Set.of(first.getUniqueId()));
            match = instance.matchId;
            occupants.clear();
            Object response = paper.exchange(Set.of());
            assertEquals(2, paper.value(response, "rosterVersion"));
            assertFalse(paper.allows(first.getUniqueId()));
            assertPaperRoster(execution, instance, 2);
            paper.exchange(Set.of());
            assertTrue(instance.rosterJoined);
        }
        try (PaperAgent restarted = new PaperAgent(spec, execution)) {
            restarted.agent.ready();
            assertEquals("running", restarted.report(Set.of()).get("state").getAsString());
            assertEquals(match, restarted.report(Set.of()).get("matchId").getAsString());
            restarted.exchange(Set.of());
            assertEquals("running", instance.state);
            assertEquals(2, instance.rosterVersion);
            assertTrue(instance.rosterJoined);
        }
    }

    private void cleanup(Minigames.Instance instance) throws Exception {
        var cleanup = Minigames.class.getDeclaredMethod("cleanup", Minigames.Instance.class);
        cleanup.setAccessible(true);
        cleanup.invoke(service, instance);
    }

    @ParameterizedTest
    @ValueSource(strings = {"absent", "false", "string"})
    void partialLegacySnapshotsNeverRetireMissingBoundInstances(String authority) throws Exception {
        Minigames.Instance instance = ready();
        JsonObject manifest = emptyManifest();
        if (authority.equals("false")) {
            manifest.addProperty("authoritative", false);
        } else if (authority.equals("string")) {
            manifest.addProperty("authoritative", "true");
        }
        service.synchronize(manifest);
        assertFalse(instance.externallyDeleted);
        assertEquals("ready", instance.state);
        assertEquals(1, instances().size());
    }

    @Test
    void invalidAuthoritativeSnapshotDoesNotRetireOrReplaceBindings() throws Exception {
        Minigames.Instance instance = ready();
        JsonObject manifest = emptyManifest();
        manifest.addProperty("authoritative", true);
        manifest.getAsJsonObject("servers").add("ordinary", JsonParser.parseString("{\"serverId\":\"invalid\"}"));
        assertThrows(java.io.IOException.class, () -> service.synchronize(manifest));
        assertFalse(instance.externallyDeleted);
        assertEquals("ready", instance.state);
        assertEquals(200, http("POST", "arena", "Bearer " + TOKEN, report("ready", null)).status());
    }

    @Test
    void authoritativeSnapshotNeverPrunesUnmatchedUnboundPreparation() throws Exception {
        seed(pending());
        JsonObject manifest = emptyManifest();
        manifest.addProperty("authoritative", true);
        start(manifest);
        Minigames.Instance instance = instances().get(0);
        service.synchronize(manifest);
        assertNull(instance.serverId);
        assertFalse(instance.externallyDeleted);
        assertEquals("provisioning", instance.state);
        assertEquals(1, instances().size());
    }

    @Test
    void authoritativeRetirementRetainsActualOldOccupantsAfterRegistryRemoval() throws Exception {
        Minigames.Instance instance = ready();
        service.synchronize(manifest(instance.requestId));
        Player occupant = mock(Player.class);
        occupants.add(occupant);
        when(proxy.getServer("Arena-1")).thenReturn(Optional.empty());
        JsonObject manifest = emptyManifest();
        manifest.getAsJsonObject("minigames").remove("Arena");
        manifest.addProperty("authoritative", true);
        service.synchronize(manifest);
        service.sweep();
        verify(occupant).disconnect(any(Component.class));
        assertEquals(1, instances().size());
        cleanup(instance);
        assertEquals(1, instances().size());
        occupants.clear();
        cleanup(instance);
        cleanup(instance);
        assertTrue(instances().isEmpty());
        assertTrue(persisted().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void authoritativeSyncDuringSpawnRetainsTheSameBusyInstanceAndResponse(boolean bound) throws Exception {
        Minigames.Instance instance;
        if (bound) {
            instance = provisioning();
        } else {
            seed(pending());
            start(emptyManifest());
            instance = instances().get(0);
        }
        instance.busy = true;
        HttpServer api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        api.createContext("/api/minecraft/v1/instances", exchange -> {
            JsonObject manifest = emptyManifest();
            manifest.addProperty("authoritative", true);
            service.synchronize(manifest);
            service.sweep();
                byte[] body = ("{\"serverId\":\"" + SERVER_ID + "\",\"slug\":\"mcgame-" + "1".repeat(32)
                    + "\",\"name\":\"Arena-1\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        api.start();
        try {
            Files.writeString(directory.resolve("vestri-dynamic-config.json"),
                    "{\"backendUrl\":\"http://127.0.0.1:" + api.getAddress().getPort()
                    + "\",\"templates\":{\"Arena\":\"vmc_" + TOKEN + "\"}}");
                instance.lifecycleConfig = MinecraftInstanceClient.snapshot(directory.resolve("vestri-dynamic-config.json"), instance.template);
            var provision = Minigames.class.getDeclaredMethod("provision", Minigames.Instance.class);
            provision.setAccessible(true);
            provision.invoke(service, instance);
            assertSame(instance, instances().get(0));
            assertEquals(SERVER_ID, instance.serverId);
            assertEquals("mcgame-" + "1".repeat(32), instance.slug);
            assertEquals(SERVER_ID, persisted().get(0).serverId);
            assertEquals(bound, instance.externallyDeleted);
            assertEquals(bound ? "ending" : "provisioning", instance.state);
        } finally {
            api.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {204, 404, 403, 500})
    void cleanupUnregistersOnlySuccessfulDeletionsAndBacksOffFailures(int status) throws Exception {
        Minigames.Instance instance = ready();
        instance.state = "ending";
        List<String> calls = new ArrayList<>();
        HttpServer api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        api.createContext("/api/minecraft/v1/instances", exchange -> {
            calls.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        api.start();
        try {
            Files.writeString(directory.resolve("vestri-dynamic-config.json"),
                    "{\"backendUrl\":\"http://127.0.0.1:" + api.getAddress().getPort()
                    + "\",\"templates\":{\"Arena\":\"vmc_" + TOKEN + "\"}}");
            cleanup(instance);
            assertEquals(List.of("DELETE /api/minecraft/v1/instances/" + SERVER_ID), calls);
            if (status == 204 || status == 404) {
                assertTrue(instances().isEmpty());
                verify(proxy).unregisterServer(target.getServerInfo());
            } else {
                assertEquals(List.of(instance), instances());
                assertEquals(clock.get() + 60000, instance.retryAfter);
                verify(proxy, never()).unregisterServer(any());
                var details = org.mockito.ArgumentCaptor.forClass(Object[].class);
                verify(logger).warn(contains("Minigame cleanup pending"), details.capture());
                assertTrue(details.getValue()[4].toString().contains("HTTP " + status));
            }
        } finally {
            api.stop(0);
        }
    }

    @Test
    void authoritativeCleanupNeverUnregistersOrdinaryServerReusingTheName() throws Exception {
        Minigames.Instance instance = ready();
        RegisteredServer ordinary = mock(RegisteredServer.class);
        when(ordinary.getServerInfo()).thenReturn(new ServerInfo("Arena-1", InetSocketAddress.createUnresolved("healthy-host", 25565)));
        when(proxy.getServer("Arena-1")).thenReturn(Optional.of(ordinary));
        JsonObject manifest = emptyManifest();
        manifest.addProperty("authoritative", true);
        service.synchronize(manifest);
        cleanup(instance);
        assertTrue(instances().isEmpty());
        verify(proxy, never()).unregisterServer(any());
    }

    private JsonObject permanentManifest() {
        JsonObject manifest = lobbyManifest(true, true);
        JsonObject spec = manifest.getAsJsonObject("minigames").getAsJsonObject("Arena");
        spec.addProperty("instanceMode", "permanent");
        spec.addProperty("readyServers", 1);
        spec.getAsJsonObject("lifecycle").getAsJsonObject("end").addProperty("deleteServer", false);
        manifest.getAsJsonObject("servers").getAsJsonObject("arena").add("spec", spec.deepCopy());
        return manifest;
    }

    @Test
    void permanentDepartureRemovesRosterOnceAndWaitsForExactAckBeforeReuse() throws Exception {
        Minigames.Instance instance = ready(permanentManifest());
        Player first = player("First");
        queue(first);
        sweepWithoutNetwork(1);
        assertEquals(1, instances().size(), "Reservations must not replenish a permanent warm pool");
        occupants.add(first);
        service.report(instance, versionedReport(instance, "running", 1, first));
        assertTrue(instance.rosterJoined);
        String match = instance.matchId;
        occupants.clear();
        JsonObject removed = service.report(instance, versionedReport(instance, "running", 1));
        assertEquals(2, removed.get("rosterVersion").getAsInt());
        assertTrue(removed.getAsJsonArray("roster").isEmpty());
        assertFalse(instance.rosterJoined);
        queue(player("Next"));
        sweepWithoutNetwork(1);
        assertTrue(instance.roster.isEmpty());
        service.report(instance, versionedReport(instance, "running", 1));
        assertEquals(2, instance.rosterVersion);
        service.report(instance, versionedReport(instance, "running", 2));
        sweepWithoutNetwork(1);
        assertEquals(match, instance.matchId);
        assertEquals(3, instance.rosterVersion);
        assertEquals(1, instance.roster.size());
        assertEquals(1, instances().size());
    }

    @Test
    void permanentUnavailableStaleRosterRecoversThroughActualPaperHttpParser() throws Exception {
        JsonObject manifest = permanentManifest();
        Minigames.Instance instance = ready(manifest);
        Player first = player("First");
        Path execution = directory.resolve("unavailable-paper.json");
        try (PaperAgent paper = new PaperAgent(manifest.getAsJsonObject("minigames").getAsJsonObject("Arena"), execution)) {
            paper.agent.ready();
            queue(first);
            sweepWithoutNetwork(1);
            paper.exchange(Set.of());
            paper.exchange(Set.of());
            occupants.add(first);
            paper.exchange(Set.of(first.getUniqueId()));
            occupants.clear();
            service.report(instance, versionedReport(instance, "running", 1));
            assertEquals(2, instance.rosterVersion);
            service.report(instance, versionedReport(instance, "failed", 1));
            assertEquals("unavailable", instance.state);
            Object response = paper.exchange(Set.of());
            assertEquals("unavailable", paper.value(response, "state"));
            assertEquals(2, paper.value(response, "rosterVersion"));
            assertEquals("running", paper.report(Set.of()).get("state").getAsString());
            assertEquals("unavailable", instance.state, "Roster delivery is not an acknowledgement");
            assertFalse(paper.allows(first.getUniqueId()));
            assertFalse(instance.rosterJoined);
            assertPaperRoster(execution, instance, 2);
            paper.exchange(Set.of());
            assertEquals("running", instance.state);
            assertTrue(instance.rosterJoined);
            assertEquals(0, persisted().get(0).deadline);
        }
    }

    @Test
    void permanentReadyRecoveryDeniesExistingRosterUntilExactAck() throws Exception {
        Minigames.Instance instance = ready(permanentManifest());
        Player first = player("First");
        queue(first);
        sweepWithoutNetwork(1);
        occupants.add(first);
        service.report(instance, versionedReport(instance, "running", 1, first));
        service.report(instance, report("ready", null));
        assertEquals("joining", instance.state);
        ServerPreConnectEvent before = new ServerPreConnectEvent(first, target);
        service.beforeConnect(before);
        assertTrue(before.getResult().getServer().isEmpty());
        service.report(instance, versionedReport(instance, "running", 0, first));
        ServerPreConnectEvent stale = new ServerPreConnectEvent(first, target);
        service.beforeConnect(stale);
        assertTrue(stale.getResult().getServer().isEmpty());
        service.report(instance, versionedReport(instance, "running", 1, first));
        ServerPreConnectEvent acknowledged = new ServerPreConnectEvent(first, target);
        service.beforeConnect(acknowledged);
        assertEquals(Optional.of(target), acknowledged.getResult().getServer());
        assertEquals(1, transfers.size(), "Recovery must not replay completed transfers");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void permanentOutagePreservesAndExpiresPendingDeadlineAcrossRestart(boolean acknowledge) throws Exception {
        JsonObject manifest = permanentManifest();
        Minigames.Instance instance = ready(manifest);
        Player first = player("First");
        queue(first);
        sweepWithoutNetwork(1);
        occupants.add(first);
        service.report(instance, versionedReport(instance, "running", 1, first));
        Player[] additions = party(2);
        queue(additions[0]);
        sweepWithoutNetwork(1);
        Matchmaker.Group failed = instance.groups.get(1);
        long deadline = instance.deadline;
        if (acknowledge) {
            clock.incrementAndGet();
            service.report(instance, versionedReport(instance, "running", 2, first));
            assertEquals(deadline, instance.deadline, "Acknowledgement cannot extend a permanent reservation");
        }
        service.report(instance, versionedReport(instance, "failed", 2, first));
        assertEquals("unavailable", instance.state);
        assertEquals(deadline, instance.deadline);
        assertEquals(List.of(additions[0].getUniqueId(), additions[1].getUniqueId()), persisted().get(0).reservationPlayers);
        service.close();
        clock.incrementAndGet();
        service = new Minigames(proxy, parties, directory, logger, clock::get);
        service.synchronize(manifest);
        instance = instances().get(0);
        assertEquals(deadline, instance.deadline);
        assertEquals(acknowledge ? List.of() : instance.reservationPlayers, instance.pendingTransfers);
        clock.set(deadline - 1);
        sweepWithoutNetwork(1);
        assertEquals(3, instance.roster.size());
        clock.incrementAndGet();
        sweepWithoutNetwork(1);
        assertEquals("unavailable", instance.state, "Expiry cannot reopen an unavailable server");
        assertEquals(0, instance.deadline);
        assertEquals(List.of(first.getUniqueId().toString()), instance.roster.stream().map(Minigames.Member::uuid).toList());
        assertEquals(List.of(failed), queue());
        assertEquals(3, instance.rosterVersion);
        assertTrue(instance.pendingTransfers.isEmpty());
        assertTrue(persisted().get(0).reservationPlayers.isEmpty());
        verify(first, never()).disconnect(any(Component.class));
        service.report(instance, versionedReport(instance, "running", 2, first));
        assertEquals("unavailable", instance.state);
        service.report(instance, versionedReport(instance, "running", 3, first));
        assertEquals("running", instance.state);
        assertTrue(instance.rosterJoined);
        assertEquals(0, persisted().get(0).deadline);
    }

    @Test
    void permanentPendingTransferRestoresAndCompletionPersistsZeroDeadline() throws Exception {
        JsonObject manifest = permanentManifest();
        Minigames.Instance instance = ready(manifest);
        Player first = player("First");
        queue(first);
        sweepWithoutNetwork(1);
        long deadline = instance.deadline;
        service.close();
        clock.incrementAndGet();
        service = new Minigames(proxy, parties, directory, logger, clock::get);
        service.synchronize(manifest);
        instance = instances().get(0);
        assertEquals("unavailable", instance.state);
        assertEquals(deadline, instance.deadline);
        assertEquals(List.of(first.getUniqueId()), instance.reservationPlayers);
        assertEquals(instance.reservationPlayers, instance.pendingTransfers);
        assertTrue(instance.transferPending);
        service.report(instance, versionedReport(instance, "running", 0));
        assertTrue(transfers.isEmpty());
        service.report(instance, versionedReport(instance, "running", 1));
        assertEquals(List.of(first.getUniqueId()), transfers);
        assertEquals(deadline, instance.deadline);
        occupants.add(first);
        service.report(instance, versionedReport(instance, "running", 1, first));
        assertTrue(instance.rosterJoined);
        Minigames.Instance saved = persisted().get(0);
        assertEquals(0, saved.deadline);
        assertTrue(saved.reservationPlayers.isEmpty());
        assertTrue(saved.pendingTransfers.isEmpty());
        assertFalse(saved.transferPending);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void permanentStartupRecoveryRetriesSameIdentityWithPinnedClientAndBackoff(boolean knownServer) throws Exception {
        List<String> calls = new java.util.concurrent.CopyOnWriteArrayList<>();
        HttpServer api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        api.createContext("/api/minecraft/v1/instances", exchange -> {
            calls.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " "
                    + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = ("{\"serverId\":\"" + SERVER_ID + "\",\"slug\":\"mcgame-" + "1".repeat(32)
                    + "\",\"name\":\"Arena-1\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(calls.size() == 1 ? 500 : 200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        api.start();
        try {
            Files.writeString(directory.resolve("vestri-dynamic-config.json"),
                    "{\"backendUrl\":\"http://127.0.0.1:" + api.getAddress().getPort()
                    + "\",\"templates\":{\"Arena\":\"vmc_" + TOKEN + "\"}}");
            JsonObject manifest = permanentManifest();
            Minigames.Instance saved = pending();
            saved.created = clock.get() - 300000;
            saved.specification = manifest.getAsJsonObject("minigames").getAsJsonObject("Arena").deepCopy();
            saved.lifecycleConfig = MinecraftInstanceClient.snapshot(directory.resolve("vestri-dynamic-config.json"), "Arena");
            if (knownServer) {
                saved.serverId = SERVER_ID;
                saved.slug = "arena";
            } else {
                manifest.getAsJsonObject("servers").remove("arena");
            }
            seed(saved);
            start(manifest);
            Minigames.Instance instance = instances().get(0);
            service.sweep();
            assertEquals("unavailable", instance.state);
            assertTrue(calls.isEmpty(), "Busy recovery must not start parallel work");
            instance.busy = false;
            service.sweep();
            awaitLifecycle();
            assertEquals(1, calls.size());
            assertEquals(clock.get() + 60000, instance.retryAfter);
            assertEquals(saved.requestId, instance.requestId);
            assertEquals("unavailable", instance.state);
            Files.writeString(directory.resolve("vestri-dynamic-config.json"), "{}");
            clock.addAndGet(59999);
            service.sweep();
            awaitLifecycle();
            assertEquals(1, calls.size());
            clock.incrementAndGet();
            service.sweep();
            awaitLifecycle();
            assertEquals(2, calls.size());
            assertEquals(SERVER_ID, instance.serverId);
            assertEquals(calls.get(0), calls.get(1), "Both attempts must retain the same instance/request identity");
            if (knownServer) {
                assertTrue(calls.get(0).startsWith("POST /api/minecraft/v1/instances/" + SERVER_ID + "/start "));
            } else {
                assertTrue(calls.get(0).contains(saved.requestId));
            }
            assertTrue(calls.stream().noneMatch(call -> call.startsWith("DELETE")));
            assertEquals(1, instances().size());
            assertEquals(saved.requestId, persisted().get(0).requestId);
            verify(proxy, never()).unregisterServer(any());
        } finally {
            api.stop(0);
        }
    }

    private void awaitLifecycle() throws Exception {
        var field = Minigames.class.getDeclaredField("network");
        field.setAccessible(true);
        ((java.util.concurrent.ThreadPoolExecutor) field.get(service)).submit(() -> {}).get(5, TimeUnit.SECONDS);
    }

    @Test
    void permanentLateRecoveryAckExpiresBeforeTransferringWithoutSweep() throws Exception {
        Minigames.Instance instance = ready(permanentManifest());
        Player first = player("First");
        queue(first);
        sweepWithoutNetwork(1);
        Matchmaker.Group failed = instance.groups.get(0);
        service.report(instance, versionedReport(instance, "failed", 0));
        clock.set(instance.deadline);
        JsonObject response = service.report(instance, versionedReport(instance, "running", 1));
        assertEquals("unavailable", response.get("state").getAsString());
        assertEquals(2, response.get("rosterVersion").getAsInt());
        assertTrue(instance.roster.isEmpty());
        assertEquals(List.of(failed), queue());
        assertTrue(transfers.isEmpty());
        assertEquals(0, persisted().get(0).deadline);
    }

    @Test
    void permanentLegacyNullReservationFieldsInitializeSafely() throws Exception {
        JsonObject manifest = permanentManifest();
        Minigames.Instance saved = pending();
        saved.specification = manifest.getAsJsonObject("minigames").getAsJsonObject("Arena").deepCopy();
        saved.serverId = SERVER_ID;
        saved.slug = "arena";
        saved.state = "unavailable";
        JsonObject legacy = new Gson().toJsonTree(saved).getAsJsonObject();
        legacy.add("reservationPlayers", com.google.gson.JsonNull.INSTANCE);
        legacy.add("pendingTransfers", com.google.gson.JsonNull.INSTANCE);
        Files.writeString(directory.resolve("vestri-minigames.json"), "[" + legacy + "]");
        configure("Arena");
        start(manifest);
        Minigames.Instance instance = instances().get(0);
        assertNotNull(instance.reservationPlayers);
        assertNotNull(instance.pendingTransfers);
        service.report(instance, report("ready", null));
        assertEquals("ready", instance.state);
        assertTrue(persisted().get(0).reservationPlayers.isEmpty());
        assertTrue(persisted().get(0).pendingTransfers.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void permanentReservationTimeoutKeepsExistingPlayersAndRequeuesOnlyFailedGroup(boolean acknowledge) throws Exception {
        Minigames.Instance instance = ready(permanentManifest());
        Player first = player("First");
        queue(first);
        sweepWithoutNetwork(1);
        occupants.add(first);
        service.report(instance, versionedReport(instance, "running", 1, first));
        Player[] additions = party(2);
        queue(additions[0]);
        sweepWithoutNetwork(1);
        Matchmaker.Group group = instance.groups.get(1);
        if (acknowledge) {
            service.report(instance, versionedReport(instance, "running", 2, first));
        }
        clock.set(instance.deadline);
        sweepWithoutNetwork(1);
        assertEquals("running", instance.state);
        assertEquals(List.of(first.getUniqueId().toString()), instance.roster.stream().map(Minigames.Member::uuid).toList());
        assertEquals(3, instance.rosterVersion);
        assertEquals(List.of(group), queue());
        assertEquals(1, instances().size());
        assertFalse(instance.transferPending);
        assertFalse(instance.rosterJoined);
        verify(first, never()).disconnect(any(Component.class));
        service.report(instance, versionedReport(instance, "running", 2, first));
        assertFalse(instance.rosterJoined);
        service.report(instance, versionedReport(instance, "running", 3, first));
        assertTrue(instance.rosterJoined);
    }

    @Test
    void permanentExpiryFailureAndTemplateMismatchNeverDeleteAndRequireRecovery() throws Exception {
        JsonObject manifest = permanentManifest();
        Minigames.Instance instance = ready(manifest);
        clock.addAndGet(30001);
        sweepWithoutNetwork(1);
        assertEquals("unavailable", instance.state);
        service.report(instance, report("ready", null));
        assertEquals("ready", instance.state);
        service.report(instance, report("failed", null));
        assertEquals("unavailable", instance.state);
        service.report(instance, report("ready", null));
        assertEquals("ready", instance.state);
        manifest.getAsJsonObject("minigames").getAsJsonObject("Arena").addProperty("maxPlayers", 3);
        service.synchronize(manifest);
        assertEquals("unavailable", instance.state);
        service.report(instance, report("ready", null));
        sweepWithoutNetwork(2);
        assertEquals("unavailable", instance.state);
        assertEquals(1, instances().size());
        manifest.getAsJsonObject("minigames").remove("Arena");
        manifest.getAsJsonObject("servers").remove("arena");
        service.synchronize(manifest);
        sweepWithoutNetwork(1);
        assertEquals("unavailable", instance.state);
        assertEquals(1, instances().size());
    }

    @Test
    void permanentRunningRestartRetainsMatchAndRestoresRosterFromFreshReport() throws Exception {
        JsonObject manifest = permanentManifest();
        Minigames.Instance instance = ready(manifest);
        Player first = player("First");
        queue(first);
        sweepWithoutNetwork(1);
        occupants.add(first);
        service.report(instance, versionedReport(instance, "running", 1, first));
        String match = instance.matchId;
        service.close();
        service = new Minigames(proxy, parties, directory, logger, clock::get);
        service.synchronize(manifest);
        Minigames.Instance restored = instances().get(0);
        assertEquals("running", restored.state);
        assertEquals(match, restored.matchId);
        assertEquals(instance.roster, restored.roster);
        assertEquals(0, restored.heartbeat);
        service.report(restored, versionedReport(restored, "running", 1, first));
        assertTrue(restored.rosterJoined);
        assertTrue(restored.joinedPlayers.contains(first.getUniqueId().toString()));
        assertEquals(1, transfers.size());
    }

    @Test
    void authoritativeMissingBoundServerRetiresWithoutAnApiKeyAndRetainsBusyWork() throws Exception {
        Minigames.Instance instance = ready();
        Files.delete(directory.resolve("vestri-dynamic-config.json"));
        instance.busy = true;
        JsonObject snapshot = manifest(null);
        snapshot.addProperty("authoritative", true);
        snapshot.getAsJsonObject("servers").remove("arena");
        service.synchronize(snapshot);
        assertTrue(instance.externallyDeleted);
        assertEquals("ending", instance.state);
        assertSame(target, instance.retiredTarget);
        service.sweep();
        assertTrue(instances().contains(instance));
        instance.busy = false;
        var cleanup = Minigames.class.getDeclaredMethod("cleanup", Minigames.Instance.class);
        cleanup.setAccessible(true);
        cleanup.invoke(service, instance);
        assertTrue(instances().isEmpty());
        assertTrue(persisted().isEmpty());
    }

    @Test
    void nativeHttpProtocolPersistsFullFfaRosterBeforeEachVersionedTransfer() throws Exception {
        JsonObject manifest = lobbyManifest(true, true);
        JsonObject spec = manifest.getAsJsonObject("minigames").getAsJsonObject("Arena");
        spec.addProperty("minPlayers", 3);
        manifest.getAsJsonObject("servers").getAsJsonObject("arena").add("spec", spec.deepCopy());
        Minigames.Instance instance = ready(manifest);
        instance.state = "provisioning";
        instance.heartbeat = 0;
        Path execution = directory.resolve("paper-execution.json");
        try (PaperAgent paper = new PaperAgent(spec, execution)) {
            paper.agent.ready();
            Object initial = paper.exchange(Set.of());
            assertEquals("ready", paper.value(initial, "state"));
            assertEquals("ready", instance.state);
            Player[] first = party(2);
            queue(first[0]);
            service.sweep();
            assertEquals(2, instance.roster.size());
            assertEquals(1, instance.groups.size());
            assertTrue(instance.roster.size() < spec.get("minPlayers").getAsInt());
            assertTrue(instance.roster.stream().allMatch(member -> member.team().isEmpty()));
            Object assigned = paper.exchange(Set.of());
            String matchId = instance.matchId;
            assertEquals("joining", paper.value(assigned, "state"));
            assertEquals(1, paper.value(assigned, "rosterVersion"));
            assertTrue(transfers.isEmpty());
            assertPaperRoster(execution, instance, 1);
            assertEquals(1, paper.report(Set.of()).get("rosterVersion").getAsInt());
            for (Player member : first) {
                assertTrue(paper.allows(member.getUniqueId()));
            }
            assertFalse(paper.allows(UUID.randomUUID()));
            paper.exchange(Set.of());
            List<UUID> firstIds = List.of(first).stream().map(Player::getUniqueId).toList();
            assertEquals(firstIds, transfers);
            assertEquals("running", instance.state);
            assertFalse(instance.rosterJoined);
            occupants.addAll(List.of(first));
            Set<UUID> online = Set.copyOf(firstIds);
            paper.exchange(online);
            assertTrue(instance.rosterJoined);
            assertEquals(0, instance.deadline);

            Player[] second = party(2);
            queue(second[0]);
            service.sweep();
            assertEquals(matchId, instance.matchId);
            assertEquals("running", instance.state);
            assertEquals(2, instance.groups.size());
            assertEquals(4, instance.roster.size());
            assertEquals(2, instance.rosterVersion);
            assertEquals(List.of(second).stream().map(Player::getUniqueId).toList(), instance.pendingTransfers);
            Object appended = paper.exchange(online);
            assertEquals("running", paper.value(appended, "state"));
            assertEquals(2, paper.value(appended, "rosterVersion"));
            assertEquals(4, ((List<?>) paper.value(appended, "roster")).size());
            assertEquals(firstIds, transfers, "Version 1 must not acknowledge the appended roster");
            assertPaperRoster(execution, instance, 2);
            assertEquals(2, paper.report(online).get("rosterVersion").getAsInt());
            for (Player member : occupants) {
                assertTrue(paper.allows(member.getUniqueId()));
            }
            for (Player member : second) {
                assertTrue(paper.allows(member.getUniqueId()));
            }
            paper.exchange(online);
            List<UUID> allIds = new ArrayList<>(firstIds);
            allIds.addAll(List.of(second).stream().map(Player::getUniqueId).toList());
            assertEquals(allIds, transfers, "Only the newly appended party is transferred");
            paper.exchange(online);
            assertEquals(allIds, transfers, "Acknowledgment replay must not transfer again");
            occupants.addAll(List.of(second));
            online = Set.copyOf(allIds);
            paper.exchange(online);
            assertTrue(instance.rosterJoined);
            assertEquals(0, instance.deadline);
            paper.observeLog("Game finished");
            paper.apply(null, online);
            assertEquals("ended", paper.report(online).get("state").getAsString());
            Object ending = paper.exchange(online);
            assertEquals("ending", paper.value(ending, "state"));
            assertEquals("ending", instance.state);
            assertEquals("ending", persisted().get(0).state);
            assertFalse(paper.allows(firstIds.get(0)));
            assertTrue(queue().isEmpty());
            assertEquals(allIds, transfers);
        }
    }

    @Test
    void managedHttpProtocolAcceptsPaperVersionZeroAndRunsStartupOnce() throws Exception {
        JsonObject manifest = lobbyManifest(false, true);
        JsonObject spec = manifest.getAsJsonObject("minigames").getAsJsonObject("Arena");
        spec.getAsJsonObject("lifecycle").add("onAllJoined", JsonParser.parseString(
                "[{\"command\":\"start {matchId}\"}]"));
        manifest.getAsJsonObject("servers").getAsJsonObject("arena").add("spec", spec.deepCopy());
        Minigames.Instance instance = ready(manifest);
        Path execution = directory.resolve("managed-paper-execution.json");
        try (PaperAgent paper = new PaperAgent(spec, execution)) {
            paper.agent.ready();
            Player[] players = party(2);
            queue(players[0]);
            service.sweep();
            Object assigned = paper.exchange(Set.of());
            assertEquals("joining", paper.value(assigned, "state"));
            assertEquals(1, paper.value(assigned, "rosterVersion"));
            assertEquals(1, instance.rosterVersion);
            JsonObject ack = paper.report(Set.of());
            assertEquals("joining", ack.get("state").getAsString());
            assertEquals(instance.matchId, ack.get("matchId").getAsString());
            assertEquals(0, ack.get("rosterVersion").getAsInt());
            assertTrue(transfers.isEmpty());
            assertTrue(instance.transferPending);
            paper.exchange(Set.of());
            List<UUID> ids = List.of(players).stream().map(Player::getUniqueId).toList();
            assertEquals(ids, transfers, "Managed version 0 acknowledgment must release transfers");
            assertFalse(instance.transferPending);
            assertEquals("joining", instance.state);
            paper.exchange(Set.of());
            assertEquals(ids, transfers);
            occupants.addAll(List.of(players));
            Set<UUID> online = Set.copyOf(ids);
            List<String> commands = new ArrayList<>();
            Object starting = paper.exchange(online, command -> {
                commands.add(command);
                return true;
            });
            assertEquals("starting", paper.value(starting, "state"));
            assertEquals(List.of("start " + instance.matchId), commands);
            assertEquals("running", paper.report(online).get("state").getAsString());
            assertEquals(0, paper.report(online).get("rosterVersion").getAsInt());
            JsonObject saved = JsonParser.parseString(Files.readString(execution)).getAsJsonObject();
            assertTrue(saved.get("startupClaimed").getAsBoolean());
            assertEquals(0, saved.get("rosterVersion").getAsInt());
            assertEquals(new Gson().toJsonTree(instance.roster), saved.get("roster"));
            Object running = paper.exchange(online);
            assertEquals("running", paper.value(running, "state"));
            assertEquals("running", instance.state);
            assertEquals(ids, transfers);
            assertEquals(1, commands.size());
        }
    }

    private void assertPaperRoster(Path execution, Minigames.Instance instance, int version) throws Exception {
        JsonObject saved = JsonParser.parseString(Files.readString(execution)).getAsJsonObject();
        assertEquals(instance.matchId, saved.get("matchId").getAsString());
        assertEquals(version, saved.get("rosterVersion").getAsInt());
        assertTrue(saved.get("startupClaimed").getAsBoolean());
        assertEquals(new Gson().toJsonTree(instance.roster), saved.get("roster"));
    }

    private static final class PaperAgent implements AutoCloseable {
        private final com.vestri.dynamic.bukkit.MinigameAgent agent;
        private final Class<?> control = Class.forName("com.vestri.dynamic.bukkit.MinigameAgent$Control");

        PaperAgent(JsonObject spec, Path execution) throws Exception {
            JsonObject configuration = new JsonObject();
            configuration.addProperty("slug", "mcgame-arena");
            configuration.addProperty("token", TOKEN);
            configuration.addProperty("controlUrl", "http://vestri-proxy:25580");
            JsonObject paperSpec = spec.deepCopy();
            JsonObject lifecycle = paperSpec.getAsJsonObject("lifecycle");
            lifecycle.add("ready", JsonParser.parseString("{\"trigger\":\"explicit\"}"));
            if (!MinigameSpec.parse(spec).permanent()) {
                lifecycle.getAsJsonObject("end").addProperty("logPattern", "Game finished");
            }
            configuration.add("spec", paperSpec);
            Object parsed = invoke("configuration", new Class<?>[]{String.class}, null, configuration.toString());
            var components = parsed.getClass().getRecordComponents();
            Class<?>[] types = new Class<?>[components.length];
            Object[] values = new Object[components.length];
            for (int index = 0; index < components.length; index++) {
                types[index] = components[index].getType();
                var accessor = components[index].getAccessor();
                accessor.setAccessible(true);
                values[index] = accessor.invoke(parsed);
            }
            values[0] = URI.create("http://127.0.0.1:25580/minigame/arena");
            var configConstructor = parsed.getClass().getDeclaredConstructor(types);
            configConstructor.setAccessible(true);
            Object local = configConstructor.newInstance(values);
            var constructor = com.vestri.dynamic.bukkit.MinigameAgent.class.getDeclaredConstructor(
                    org.bukkit.plugin.java.JavaPlugin.class, parsed.getClass(), Path.class);
            constructor.setAccessible(true);
            agent = (com.vestri.dynamic.bukkit.MinigameAgent) constructor.newInstance(null, local, execution);
        }

        Object exchange(Set<UUID> online) throws Exception {
            return exchange(online, command -> fail("Unexpected setup command"));
        }

        Object exchange(Set<UUID> online, java.util.function.Predicate<String> execute) throws Exception {
            Object response = invoke("poll", new Class<?>[]{String.class}, agent, report(online).toString());
            assertNotNull(response, "The real Paper HTTP helper must accept the proxy response");
            apply(response, online, execute);
            return response;
        }

        void apply(Object response, Set<UUID> online) throws Exception {
            apply(response, online, command -> fail("Native mode must not dispatch setup commands"));
        }

        private void apply(Object response, Set<UUID> online, java.util.function.Predicate<String> execute) throws Exception {
            invoke("apply", new Class<?>[]{control, Set.class, java.util.function.Predicate.class}, agent,
                    response, online, execute);
        }

        JsonObject report(Set<UUID> online) throws Exception {
            return JsonParser.parseString((String) invoke("statusReport", new Class<?>[]{Set.class}, agent, online))
                    .getAsJsonObject();
        }

        boolean allows(UUID player) throws Exception {
            return (boolean) invoke("allows", new Class<?>[]{UUID.class}, agent, player);
        }

        void observeLog(String message) throws Exception {
            invoke("observeLog", new Class<?>[]{String.class}, agent, message);
        }

        Object value(Object response, String name) throws Exception {
            var accessor = control.getDeclaredMethod(name);
            accessor.setAccessible(true);
            return accessor.invoke(response);
        }

        private static Object invoke(String name, Class<?>[] types, Object receiver, Object... arguments) throws Exception {
            var method = com.vestri.dynamic.bukkit.MinigameAgent.class.getDeclaredMethod(name, types);
            method.setAccessible(true);
            return method.invoke(receiver, arguments);
        }

        @Override
        public void close() {
            agent.close();
        }
    }

    @Test
    void vestriTeamfreePartyCanExceedTeamSizeAndRetainsTheManagedStartupSequence() throws Exception {
        JsonObject manifest = lobbyManifest(false, true);
        Minigames.Instance instance = ready(manifest);
        Player[] players = party(3);
        queue(players[0]);
        service.sweep();
        assertEquals(3, instance.roster.size());
        assertTrue(instance.roster.stream().allMatch(member -> member.team().isEmpty()));
        assertTrue(transfers.isEmpty());
        service.report(instance, report("joining", instance.matchId));
        assertEquals(3, transfers.size());
        occupants.addAll(List.of(players));
        service.report(instance, report("joining", instance.matchId, players));
        assertEquals("starting", instance.state);
        service.report(instance, report("running", instance.matchId, players));
        assertEquals("running", instance.state);
    }

    @Test
    void nativeSoloCreatesDemandBelowMinPlayersEvenWithoutAWarmPool() throws Exception {
        JsonObject manifest = lobbyManifest(true, true);
        manifest.getAsJsonObject("servers").remove("arena");
        configure("Arena");
        start(manifest);
        queue(player("Solo"));
        sweepWithoutNetwork(10);
        assertEquals(1, instances().size());
        assertEquals("provisioning", instances().get(0).state);
        assertEquals(Set.of("backendUrl", "templates"), instances().get(0).lifecycleConfig.keySet());
        assertEquals(instances().get(0).lifecycleConfig, persisted().get(0).lifecycleConfig);
        assertFalse(instances().get(0).lifecycleCredentialsUnverified);
        assertEquals(1, queue().size());
    }

    @Test
    void nativeInitialReservationRequiresVersionAndBypassesManagedStarting() throws Exception {
        Minigames.Instance instance = ready(lobbyManifest(true, true));
        Player first = player("First");
        queue(first);
        service.sweep();
        assertEquals("joining", instance.state);
        assertEquals(1, instance.rosterVersion);
        assertEquals(clock.get() + 10000, instance.deadline);
        assertEquals(List.of(first.getUniqueId()), instance.pendingTransfers);
        JsonObject response = service.report(instance, report("ready", null));
        assertEquals("joining", response.get("state").getAsString());
        assertEquals(1, response.get("rosterVersion").getAsInt());
        assertEquals(1, response.getAsJsonArray("roster").size());
        assertFalse(response.has("commands"));
        for (String state : List.of("joining", "running")) {
            service.report(instance, report(state, instance.matchId, first));
        }
        assertTrue(transfers.isEmpty());
        ServerPreConnectEvent premature = new ServerPreConnectEvent(first, target);
        service.beforeConnect(premature);
        assertTrue(premature.getResult().getServer().isEmpty());
        JsonObject ack = versionedReport(instance, "running", 1);
        response = service.report(instance, ack);
        assertEquals("running", response.get("state").getAsString());
        assertEquals("running", instance.state);
        assertFalse(instance.transferPending);
        assertEquals(List.of(first.getUniqueId()), transfers);
        assertFalse(instance.rosterJoined);
        long deadline = instance.deadline;
        clock.incrementAndGet();
        service.report(instance, ack);
        assertEquals(deadline, instance.deadline);
        assertEquals(1, transfers.size());
        occupants.add(first);
        service.report(instance, versionedReport(instance, "running", 1, first));
        assertTrue(instance.rosterJoined);
        assertEquals(0, instance.deadline);
        assertEquals("running", persisted().get(0).state);
        assertEquals(1, persisted().get(0).rosterVersion);
    }

    @Test
    void nativeAdditionsWaitForBothPreviousViewsAndAckTheWholeNewRosterBeforeTransfer() throws Exception {
        Minigames.Instance instance = ready(lobbyManifest(true, true));
        Player first = player("First");
        Player second = player("Second");
        Player third = player("Third");
        queue(first);
        service.sweep();
        String matchId = instance.matchId;
        queue(second);
        sweepWithoutNetwork(2);
        assertEquals(1, instances().size());
        assertEquals(1, instance.roster.size());
        service.report(instance, versionedReport(instance, "running", 1, first));
        sweepWithoutNetwork(2);
        assertEquals(1, instance.roster.size(), "Paper alone cannot open admission");
        occupants.add(first);
        service.report(instance, versionedReport(instance, "running", 1));
        sweepWithoutNetwork(2);
        assertEquals(1, instance.roster.size(), "Velocity alone cannot open admission");
        service.report(instance, versionedReport(instance, "running", 1, first));
        service.sweep();
        assertEquals(2, instance.roster.size());
        assertEquals(2, instance.rosterVersion);
        assertEquals(matchId, instance.matchId);
        assertEquals("running", instance.state);
        assertTrue(instance.transferPending);
        assertEquals(List.of(second.getUniqueId()), instance.pendingTransfers);
        assertEquals(List.of(first.getUniqueId()), transfers);
        assertEquals(2, persisted().get(0).roster.size());
        queue(third);
        sweepWithoutNetwork(2);
        assertEquals(1, instances().size());
        assertEquals(2, instance.roster.size());
        service.report(instance, versionedReport(instance, "running", 1, first, second));
        service.report(instance, report("running", matchId, first, second));
        assertEquals(1, transfers.size());
        JsonObject fullRoster = service.report(instance, versionedReport(instance, "running", 1, first));
        assertEquals(2, fullRoster.getAsJsonArray("roster").size());
        assertEquals(2, fullRoster.get("rosterVersion").getAsInt());
        assertFalse(fullRoster.has("commands"));
        service.report(instance, versionedReport(instance, "running", 2, first));
        assertEquals(List.of(first.getUniqueId(), second.getUniqueId()), transfers);
        assertTrue(instance.pendingTransfers.isEmpty());
        service.report(instance, versionedReport(instance, "running", 2, first));
        assertEquals(2, transfers.size());
        sweepWithoutNetwork(1);
        assertEquals(2, instance.roster.size());
        occupants.add(second);
        service.report(instance, versionedReport(instance, "running", 2, first, second));
        service.sweep();
        assertEquals(3, instance.rosterVersion);
        assertEquals(3, instance.roster.size());
        assertEquals(List.of(third.getUniqueId()), instance.pendingTransfers);
        assertEquals(1, instances().size());
    }

    @Test
    void nativeFfaAdditionsKeepPartiesAtomicAndNeverExceedTheCap() throws Exception {
        Minigames.Instance instance = ready(lobbyManifest(true, true));
        Player first = player("First");
        queue(first);
        service.sweep();
        occupants.add(first);
        service.report(instance, versionedReport(instance, "running", 1, first));
        Player[] party = party(3);
        queue(party[0]);
        service.sweep();
        assertEquals(4, instance.roster.size());
        assertEquals(2, instance.groups.size());
        assertEquals(3, instance.groups.get(1).players().size());
        assertTrue(instance.roster.stream().allMatch(member -> member.team().isEmpty()));
        service.report(instance, versionedReport(instance, "running", 1, first));
        assertEquals(1, transfers.size());
        service.report(instance, versionedReport(instance, "running", 2, first));
        assertEquals(4, transfers.size());
        occupants.addAll(List.of(party));
        service.report(instance, versionedReport(instance, "running", 2, occupants.toArray(Player[]::new)));
        queue(player("Overflow"));
        sweepWithoutNetwork(2);
        assertEquals(4, instance.roster.size());
        assertEquals(2, instances().size(), "A full lobby can create another instance within the cap");
        assertEquals(1, queue().size());
        Player[] oversized = party(5);
        queue(oversized[0]);
        assertEquals(1, queue().size());
    }

    @Test
    void nativeTeamfulAdditionsUseExistingCountsRatherThanOverfillATeam() throws Exception {
        Minigames.Instance instance = ready(lobbyManifest(true, false));
        Player first = player("First");
        Player second = player("Second");
        queue(first);
        queue(second);
        service.sweep();
        assertEquals(List.of("red", "blue"), instance.roster.stream().map(Minigames.Member::team).toList());
        occupants.addAll(List.of(first, second));
        service.report(instance, versionedReport(instance, "running", 1, first, second));
        Player[] blocked = party(2);
        Player solo = player("Solo");
        queue(blocked[0]);
        queue(solo);
        service.sweep();
        assertEquals(3, instance.roster.size());
        assertEquals("red", instance.roster.get(2).team());
        assertEquals(solo.getUniqueId().toString(), instance.roster.get(2).uuid());
        assertEquals(1, queue().size());
        assertEquals(parties.members(blocked[0].getUniqueId()), queue().get(0).players());
    }

    @Test
    void nativeVersionMustBeANonnegativeBoundedIntegerAndMalformedHttpDoesNotMutate() throws Exception {
        Minigames.Instance instance = ready(lobbyManifest(true, true));
        queue(player("Solo"));
        service.sweep();
        long heartbeat = instance.heartbeat;
        clock.incrementAndGet();
        for (String value : List.of("null", "\"1\"", "true", "-1", "2", "1.5", "2147483648", "{}", "[]")) {
            JsonObject report = report("running", instance.matchId);
            report.add("rosterVersion", JsonParser.parseString(value));
            assertEquals(400, http("POST", "arena", "Bearer " + TOKEN, report).status(), value);
            assertEquals(heartbeat, instance.heartbeat, value);
            assertTrue(instance.transferPending, value);
            assertTrue(transfers.isEmpty(), value);
        }
        JsonObject stale = versionedReport(instance, "running", 0);
        assertEquals(200, http("POST", "arena", "Bearer " + TOKEN, stale).status());
        assertTrue(instance.transferPending);
        assertEquals(200, http("POST", "arena", "Bearer " + TOKEN, versionedReport(instance, "running", 1)).status());
        assertEquals(1, transfers.size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nativeAdditionalAckAndJoinTimeoutsEndTheWholeLobbyAndRequeueIntactGroups(boolean acknowledge) throws Exception {
        Minigames.Instance instance = ready(lobbyManifest(true, true));
        Player first = player("First");
        queue(first);
        service.sweep();
        occupants.add(first);
        service.report(instance, versionedReport(instance, "running", 1, first));
        Player[] additions = party(2);
        queue(additions[0]);
        service.sweep();
        assertEquals("running", instance.state);
        assertEquals(clock.get() + 10000, instance.deadline);
        if (acknowledge) {
            service.report(instance, versionedReport(instance, "running", 2, first));
        }
        occupants.clear();
        List<Matchmaker.Group> original = instance.groups;
        clock.set(instance.deadline);
        sweepWithoutNetwork(1);
        assertEquals("ending", instance.state);
        assertEquals(original, queue());
        sweepWithoutNetwork(1);
        assertEquals(original, queue());
        assertEquals(acknowledge ? 3 : 1, transfers.size());
    }

    @Test
    void nativeInitialAckTimeoutRequeuesButChangedPartiesDoNot() throws Exception {
        Minigames.Instance instance = ready(lobbyManifest(true, true));
        Player[] players = party(3);
        queue(players[0]);
        service.sweep();
        assertEquals(3, instance.roster.size());
        parties.leave(players[1].getUniqueId());
        clock.set(instance.deadline);
        sweepWithoutNetwork(1);
        assertEquals("ending", instance.state);
        assertTrue(queue().isEmpty());
        assertTrue(transfers.isEmpty());
    }

    @Test
    void nativePendingJoinsAreNotEndedByEmptyGraceButEndSignalsStillWork() throws Exception {
        JsonObject manifest = lobbyManifest(true, true);
        JsonObject spec = manifest.getAsJsonObject("minigames").getAsJsonObject("Arena");
        spec.getAsJsonObject("lifecycle").getAsJsonObject("end").addProperty("whenEmpty", true);
        manifest.getAsJsonObject("servers").getAsJsonObject("arena").add("spec", spec.deepCopy());
        Minigames.Instance instance = ready(manifest);
        queue(player("Solo"));
        service.sweep();
        service.report(instance, versionedReport(instance, "running", 1));
        sweepWithoutNetwork(1);
        clock.addAndGet(1001);
        sweepWithoutNetwork(1);
        assertEquals("running", instance.state);
        assertEquals(0, instance.emptySince);
        service.report(instance, versionedReport(instance, "ended", 1));
        assertEquals("ending", instance.state);
        assertTrue(queue().isEmpty());
    }

    @Test
    void nativeAdmissionStopsAfterAReportedOrProxyRosterDeparture() throws Exception {
        Minigames.Instance instance = ready(lobbyManifest(true, true));
        Player first = player("First");
        queue(first);
        service.sweep();
        occupants.add(first);
        service.report(instance, versionedReport(instance, "running", 1, first));
        queue(player("Second"));
        occupants.clear();
        service.sweep();
        assertEquals(1, instance.roster.size());
        occupants.add(first);
        service.report(instance, versionedReport(instance, "running", 1));
        service.sweep();
        assertEquals(1, instance.roster.size());
    }

    @Test
    void rejectedTransferLogsStatusAndRedactedReasonAndNotifiesPlayer() throws Exception {
        Minigames.Instance instance = ready();
        instance.matchId = UUID.randomUUID().toString();
        Player player = player("Rejected");
        ConnectionRequestBuilder.Result result = new ConnectionRequestBuilder.Result() {
            @Override
            public ConnectionRequestBuilder.Status getStatus() {
                return ConnectionRequestBuilder.Status.SERVER_DISCONNECTED;
            }

            @Override
            public Optional<Component> getReasonComponent() {
                return Optional.of(Component.text("whitelist denied vmc_" + TOKEN));
            }

            @Override
            public RegisteredServer getAttemptedConnection() {
                return target;
            }
        };
        assertFalse(result.isSuccessful());
        doReturn(connection(() -> CompletableFuture.completedFuture(result)))
                .when(player).createConnectionRequest(target);

        assertDoesNotThrow(() -> connect(instance, player));

        var args = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(logger).warn(eq("Minigame transfer rejected template={} instanceId={} matchId={} player={} reason={}"), args.capture());
        Object[] warning = args.getValue();
        assertEquals(List.of("Arena", instance.serverId, instance.matchId, player.getUniqueId()),
                List.of(warning[0], warning[1], warning[2], warning[3]));
        String reason = warning[4].toString();
        assertTrue(reason.startsWith("SERVER_DISCONNECTED: "));
        assertTrue(reason.contains("whitelist denied"));
        assertTrue(reason.contains("[redacted]"));
        assertFalse(java.util.Arrays.toString(warning).contains(TOKEN));
        verifyTransferFailureMessage(player);
        verify(logger, never()).info(contains("transfer connected"), any(Object[].class));
    }

    @Test
    void exceptionalTransferFutureLogsExceptionClassAndNotifiesPlayer() throws Exception {
        Minigames.Instance instance = ready();
        instance.matchId = UUID.randomUUID().toString();
        Player player = player("Exceptional");
        CompletableFuture<ConnectionRequestBuilder.Result> future = new CompletableFuture<>();
        doReturn(connection(() -> future)).when(player).createConnectionRequest(target);

        assertDoesNotThrow(() -> connect(instance, player));
        verify(logger, never()).warn(contains("Minigame transfer"), any(Object[].class));
        verify(player, never()).sendMessage(any(Component.class));
        assertTrue(future.completeExceptionally(new IllegalStateException("whitelist " + TOKEN)));

        var args = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(logger).warn(eq("Minigame transfer rejected template={} instanceId={} matchId={} player={} reason={}"), args.capture());
        assertEquals(List.of("Arena", instance.serverId, instance.matchId, player.getUniqueId(), "IllegalStateException"),
                List.of(args.getValue()));
        assertFalse(java.util.Arrays.toString(args.getValue()).contains(TOKEN));
        verifyTransferFailureMessage(player);
        verify(logger, never()).info(contains("transfer connected"), any(Object[].class));
    }

    @Test
    void synchronousTransferFailureLogsExceptionClassAndRedactedDetail() throws Exception {
        Minigames.Instance instance = ready();
        Player player = player("Synchronous");
        doReturn(connection(() -> {
            throw new IllegalArgumentException("whitelist unavailable vmc_" + TOKEN);
        })).when(player).createConnectionRequest(target);

        assertDoesNotThrow(() -> connect(instance, player));

        var args = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(logger).warn(eq("Minigame transfer failed template={} instanceId={} player={} error={}: {}"), args.capture());
        assertEquals(List.of("Arena", instance.serverId, player.getUniqueId(), "IllegalArgumentException",
                "whitelist unavailable [redacted]"), List.of(args.getValue()));
        assertFalse(java.util.Arrays.toString(args.getValue()).contains(TOKEN));
        verify(logger, never()).info(contains("transfer connected"), any(Object[].class));
    }

    private ConnectionRequestBuilder connection(
            java.util.function.Supplier<CompletableFuture<ConnectionRequestBuilder.Result>> result) {
        return (ConnectionRequestBuilder) Proxy.newProxyInstance(ConnectionRequestBuilder.class.getClassLoader(),
                new Class<?>[]{ConnectionRequestBuilder.class}, (receiver, method, arguments) -> {
                    if (method.getName().equals("connect")) {
                        return result.get();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private void connect(Minigames.Instance instance, Player player) throws Exception {
        var connect = Minigames.class.getDeclaredMethod("connect", Minigames.Instance.class,
                Player.class, RegisteredServer.class);
        connect.setAccessible(true);
        connect.invoke(service, instance, player, target);
    }

    private void verifyTransferFailureMessage(Player player) {
        verify(player).sendMessage(Component.text("Could not join the minigame. Check with an administrator; the match join timeout still applies."));
    }

    @Test
    void safeFailureRedactsSecretsNormalizesControlsAndBoundsOutput() {
        assertEquals("No error detail available", Minigames.safeFailure(new java.io.IOException()));
        String message = Minigames.safeFailure(new java.io.IOException("HTTP 503\r\nvmc_" + TOKEN
                + "\t" + TOKEN.toUpperCase() + "\u001b" + "x".repeat(600)));
        assertEquals(512, message.length());
        assertTrue(message.startsWith("HTTP 503  [redacted] [redacted] "));
        assertFalse(message.contains(TOKEN));
        assertFalse(message.contains("\n"));
        assertFalse(message.contains("\u001b"));
    }

    @Test
    void preparationFailureLogsExceptionClassAndSafeApiStatusWithoutRawResponse() throws Exception {
        Minigames.Instance instance = provisioning();
        HttpServer api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        api.createContext("/api/minecraft/v1/instances", exchange -> {
            byte[] body = ("{\"message\":\"" + TOKEN + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        api.start();
        try {
            Files.writeString(directory.resolve("vestri-dynamic-config.json"),
                    "{\"backendUrl\":\"http://127.0.0.1:" + api.getAddress().getPort()
                    + "\",\"templates\":{\"Arena\":\"vmc_" + TOKEN + "\"}}");
            var provision = Minigames.class.getDeclaredMethod("provision", Minigames.Instance.class);
            provision.setAccessible(true);
            provision.invoke(service, instance);
            var args = org.mockito.ArgumentCaptor.forClass(Object[].class);
            verify(logger).warn(eq("Minigame preparation pending template={} requestId={} error={}: {}; the same request will be retried"), args.capture());
            assertEquals("APIException", args.getValue()[2]);
            assertTrue(args.getValue()[3].toString().contains("HTTP 400"));
            assertFalse(args.getValue()[3].toString().contains(TOKEN));
            assertEquals("provisioning", instance.state);
        } finally {
            api.stop(0);
        }
    }

    @Test
    void malformedSuccessfulSpawnResponseIsLoggedAndRetriedWithTheSameRequestId() throws Exception {
        Minigames.Instance instance = provisioning();
        List<String> requests = new ArrayList<>();
        HttpServer api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        api.createContext("/api/minecraft/v1/instances", exchange -> {
            requests.add(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject().get("requestId").getAsString());
            byte[] body = "{\"serverId\":\"invalid\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        api.start();
        try {
            Files.writeString(directory.resolve("vestri-dynamic-config.json"),
                    "{\"backendUrl\":\"http://127.0.0.1:" + api.getAddress().getPort()
                    + "\",\"templates\":{\"Arena\":\"vmc_" + TOKEN + "\"}}");
            var provision = Minigames.class.getDeclaredMethod("provision", Minigames.Instance.class);
            provision.setAccessible(true);
            provision.invoke(service, instance);
            provision.invoke(service, instance);
            assertEquals(List.of(instance.requestId, instance.requestId), requests);
            assertEquals("provisioning", instance.state);
            var args = org.mockito.ArgumentCaptor.forClass(Object[].class);
            verify(logger, times(2)).warn(eq("Minigame preparation pending template={} requestId={} error={}: {}; the same request will be retried"), args.capture());
            assertEquals("IllegalArgumentException", args.getValue()[2]);
            assertEquals("Invalid UUID string: invalid", args.getValue()[3]);
        } finally {
            api.stop(0);
        }
    }

    @Test
    void startupStallWarningIsOnceAfter30SecondsAndIncludesIdentityAndDnsAdvice() throws Exception {
        Minigames.Instance instance = provisioning();
        clock.addAndGet(29999);
        sweepWithoutNetwork(1);
        verifyNoInteractions(logger);
        clock.incrementAndGet();
        sweepWithoutNetwork(3);
        var args = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(logger, times(1)).warn(contains("no agent report after 30s"), args.capture());
        assertEquals(List.of("Arena", SERVER_ID, instance.requestId), List.of(args.getValue()));
        assertFalse(instance.state.equals("ending"));
        assertTrue(instance.startupWarned);
        service.report(instance, report("ready", null));
        clock.addAndGet(30000);
        sweepWithoutNetwork(1);
        verify(logger, times(1)).warn(contains("proxy DNS"), any(Object[].class));
    }

    private Player[] party(int size) {
        Player[] players = java.util.stream.IntStream.range(0, size)
                .mapToObj(index -> player("Party" + index)).toArray(Player[]::new);
        for (int index = 1; index < size; index++) {
            parties.invite(players[0].getUniqueId(), players[index].getUniqueId(), clock.get());
            parties.accept(players[index].getUniqueId(), players[0].getUniqueId(), clock.get());
        }
        return players;
    }

    private JsonObject versionedReport(Minigames.Instance instance, String state, int version, Player... players) {
        JsonObject report = report(state, instance.matchId, players);
        report.addProperty("rosterVersion", version);
        return report;
    }

    private JsonObject lobbyManifest(boolean nativeLobby, boolean teamfree) {
        JsonObject manifest = manifest(null);
        JsonObject spec = manifest.getAsJsonObject("minigames").getAsJsonObject("Arena");
        if (nativeLobby) {
            spec.addProperty("lobbyMode", "plugin");
        }
        if (teamfree) {
            spec.remove("teams");
            spec.addProperty("teamSize", 0);
        }
        manifest.getAsJsonObject("servers").getAsJsonObject("arena").add("spec", spec.deepCopy());
        return manifest;
    }

    @AfterEach
    void close() {
        if (service != null) {
            service.close();
        }
    }

    @Test
    void earlyDiscoveryCorrelatesOnlyTheExactSpawnRequest() throws Exception {
        Minigames.Instance unrelated = pending();
        Minigames.Instance requested = pending();
        Files.writeString(directory.resolve("vestri-minigames.json"), new Gson().toJson(List.of(unrelated, requested)));
        start(manifest(requested.requestId));
        assertEquals(2, instances().size());
        assertNull(instances().get(0).slug);
        assertEquals("arena", instances().get(1).slug);
        assertEquals(SERVER_ID, instances().get(1).serverId);
        assertEquals("provisioning", instances().get(1).state);
    }

    @Test
    void freshDiscoveryCanBecomeReadyOnlyAfterAnEmptyReadySignal() throws Exception {
        start(manifest(null));
        Minigames.Instance discovered = instances().get(0);
        assertNotEquals("ready", discovered.state);
        assertEquals("ready", service.report(discovered, report("ready", null)).get("state").getAsString());
        assertEquals("ready", discovered.state);
        assertEquals(clock.get(), discovered.heartbeat);
    }

    @Test
    void discoveryWithoutOptionalInstanceSpecStillRequiresFreshEmptyReadiness() throws Exception {
        start(manifest(null, null));
        Minigames.Instance discovered = instances().get(0);
        assertEquals("provisioning", discovered.state);
        service.report(discovered, report("ready", null, player("Unexpected")));
        assertEquals("provisioning", discovered.state);
        service.report(discovered, report("ready", null));
        assertEquals("ready", discovered.state);
        assertEquals(clock.get(), discovered.heartbeat);
    }

    @Test
    void unmatchedRequestNeverFallsBackToSameTemplatePendingInstance() throws Exception {
        Minigames.Instance pending = pending();
        seed(pending);
        String manualRequest = UUID.randomUUID().toString();
        start(manifest(manualRequest));
        assertEquals(2, instances().size());
        assertNull(instances().get(0).slug);
        assertNull(instances().get(0).serverId);
        assertEquals("provisioning", instances().get(0).state);
        assertEquals(pending.requestId, instances().get(0).requestId);
        Minigames.Instance manual = instances().get(1);
        assertEquals(manualRequest, manual.requestId);
        assertEquals("provisioning", manual.state);
        service.report(manual, report("ready", null));
        assertEquals("ready", manual.state);
        assertEquals(2, persisted().size());
        assertEquals("provisioning", persisted().get(0).state);
    }

    @Test
    void missingRequestNeverClaimsAnUnrelatedPendingInstance() throws Exception {
        seed(pending());
        start(manifest(null));
        assertEquals(2, instances().size());
        assertNull(instances().get(0).slug);
    }

    @Test
    void requestCorrelationAlsoRequiresTheExactTemplate() throws Exception {
        Minigames.Instance pending = pending();
        pending.template = "Other";
        seed(pending);
        start(manifest(pending.requestId));
        assertNull(instances().get(0).slug);
        assertEquals(2, instances().size());
    }

    @Test
    void repeatedDiscoveryDoesNotDuplicateTheCorrelatedInstance() throws Exception {
        Minigames.Instance pending = pending();
        seed(pending);
        JsonObject manifest = manifest(pending.requestId);
        start(manifest);
        service.synchronize(manifest);
        assertEquals(1, instances().size());
        assertEquals(pending.requestId, instances().get(0).requestId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"teams", "logCommands"})
    void changedTemplateDrainsReadyInstanceWithOldPinnedSpecification(String change) throws Exception {
        Minigames.Instance instance = ready();
        JsonObject original = instance.specification.deepCopy();
        JsonObject updated = manifest(instance.requestId, true);
        JsonObject current = updated.getAsJsonObject("minigames").getAsJsonObject("Arena");
        if (change.equals("teams")) {
            current.add("teams", JsonParser.parseString("[\"green\",\"yellow\"]"));
        } else {
            current.getAsJsonObject("lifecycle").getAsJsonObject("end")
                    .add("logCommands", JsonParser.parseString("[\"say updated\"]"));
        }
        service.synchronize(updated);
        assertEquals("ending", instance.state);
        assertEquals(original, instance.specification);
        assertEquals("ending", persisted().get(0).state);
        assertEquals(original, persisted().get(0).specification);
    }

    @Test
    void runningMatchSurvivesTemplateChangesAndKeepsPersistedSettings() throws Exception {
        Minigames.Instance instance = matched();
        Player[] players = instance.roster.stream()
                .map(member -> proxy.getPlayer(UUID.fromString(member.uuid())).orElseThrow()).toArray(Player[]::new);
        service.report(instance, report("joining", instance.matchId));
        occupants.addAll(List.of(players));
        service.report(instance, report("joining", instance.matchId, players));
        service.report(instance, report("running", instance.matchId, players));
        assertEquals("running", instance.state);
        JsonObject original = instance.specification.deepCopy();
        JsonObject updated = manifest(instance.requestId, true);
        JsonObject current = updated.getAsJsonObject("minigames").getAsJsonObject("Arena");
        current.add("teams", JsonParser.parseString("[\"green\",\"yellow\"]"));
        current.addProperty("joinTimeout", "45s");
        JsonObject end = current.getAsJsonObject("lifecycle").getAsJsonObject("end");
        end.addProperty("returnPlayersTo", "new-lobby");
        end.addProperty("whenEmpty", true);
        end.addProperty("emptyGrace", "20s");
        end.add("logCommands", JsonParser.parseString("[\"say updated\"]"));
        service.synchronize(updated);
        sweepWithoutNetwork(1);
        assertEquals("running", instance.state);
        assertEquals(original, instance.specification);
        var settings = Minigames.class.getDeclaredMethod("settings", Minigames.Instance.class);
        settings.setAccessible(true);
        MinigameSpec pinned = (MinigameSpec) settings.invoke(service, instance);
        assertEquals(List.of("red", "blue"), pinned.teams());
        assertEquals("lobby", pinned.fallback());
        assertEquals(10000, pinned.joinTimeout());
        assertFalse(pinned.whenEmpty());
        assertEquals(1000, pinned.emptyGrace());
        assertEquals("running", persisted().get(0).state);
        assertEquals(original, persisted().get(0).specification);
    }

    @Test
    void acknowledgedJoinStillUsesPinnedTimeoutAfterTemplateChange() throws Exception {
        Minigames.Instance instance = matched();
        JsonObject original = instance.specification.deepCopy();
        JsonObject updated = manifest(instance.requestId, true);
        updated.getAsJsonObject("minigames").getAsJsonObject("Arena").addProperty("joinTimeout", "45s");
        service.synchronize(updated);
        assertEquals("joining", instance.state);
        service.report(instance, report("joining", instance.matchId));
        assertEquals(clock.get() + 10000, instance.deadline);
        assertEquals(original, persisted().get(0).specification);
    }

    @Test
    void poolSizingChangesAloneDoNotDrainPinnedReadyInstance() throws Exception {
        Minigames.Instance instance = ready();
        JsonObject original = instance.specification.deepCopy();
        JsonObject updated = manifest(instance.requestId, true);
        JsonObject current = updated.getAsJsonObject("minigames").getAsJsonObject("Arena");
        current.addProperty("readyServers", 1);
        current.addProperty("maxInstances", 3);
        service.synchronize(updated);
        sweepWithoutNetwork(5);
        assertEquals("ready", instance.state);
        assertEquals(1, instances().size());
        assertEquals(original, instance.specification);
        assertEquals(original, persisted().get(0).specification);
    }

    @Test
    void freshManualDiscoveryOfKnownServerDoesNotAllocateDuplicatePreparation() throws Exception {
        configure("Arena");
        JsonObject manifest = manifest(null, true);
        manifest.getAsJsonObject("minigames").getAsJsonObject("Arena").addProperty("readyServers", 1);
        start(manifest);
        Minigames.Instance instance = instances().get(0);
        assertEquals("provisioning", instance.state);
        assertEquals(SERVER_ID, instance.serverId);
        String requestId = instance.requestId;
        service.synchronize(manifest);
        sweepWithoutNetwork(10);
        assertEquals(1, instances().size());
        assertSame(instance, instances().get(0));
        assertEquals(requestId, instance.requestId);
        List<String> spawnRequests = new ArrayList<>();
        HttpServer api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        api.createContext("/api/minecraft/v1/instances", exchange -> {
            spawnRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            byte[] response = ("{\"serverId\":\"" + SERVER_ID
                    + "\",\"slug\":\"arena\",\"name\":\"Arena-1\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        api.start();
        try {
            Files.writeString(directory.resolve("vestri-dynamic-config.json"),
                    "{\"backendUrl\":\"http://127.0.0.1:" + api.getAddress().getPort()
                    + "\",\"templates\":{\"Arena\":\"vmc_" + TOKEN + "\"}}");
            var provision = Minigames.class.getDeclaredMethod("provision", Minigames.Instance.class);
            provision.setAccessible(true);
            provision.invoke(service, instance);
            assertTrue(spawnRequests.isEmpty(), "Discovered server must not spawn again: " + spawnRequests);
        } finally {
            api.stop(0);
        }
        service.report(instance, report("ready", null));
        sweepWithoutNetwork(10);
        assertEquals("ready", instance.state);
        assertEquals(1, instances().size());
        assertEquals(SERVER_ID, persisted().get(0).serverId);
    }

    @Test
    void prepareRosterIsAcknowledgedBeforeTransfersAndBothViewsMustAgree() throws Exception {
        Minigames.Instance instance = ready();
        Player first = player("First");
        Player second = player("Second");
        queue(first);
        queue(second);
        service.sweep();
        assertEquals("joining", instance.state);
        assertTrue(instance.transferPending);
        assertTrue(transfers.isEmpty());
        assertTrue(queue().isEmpty());
        JsonObject preparation = service.report(instance, report("ready", null));
        assertEquals("joining", preparation.get("state").getAsString());
        assertEquals(instance.matchId, preparation.get("matchId").getAsString());
        assertEquals(2, preparation.getAsJsonArray("roster").size());
        assertEquals(instance.roster, new Gson().fromJson(preparation.get("roster"),
                new com.google.gson.reflect.TypeToken<List<Minigames.Member>>() {}.getType()));
        assertTrue(transfers.isEmpty());

        service.report(instance, report("joining", instance.matchId));
        assertFalse(instance.transferPending);
        assertEquals(clock.get() + 10000, instance.deadline);
        assertEquals(List.of(first.getUniqueId(), second.getUniqueId()), transfers);
        service.report(instance, report("joining", instance.matchId, first, second));
        assertEquals("joining", instance.state);
        occupants.addAll(List.of(first, second));
        service.report(instance, report("joining", instance.matchId, first));
        assertEquals("joining", instance.state);
        service.report(instance, report("joining", instance.matchId, first, second));
        assertEquals("starting", instance.state);
        assertEquals(clock.get() + 30000, instance.deadline);
        service.report(instance, report("joining", instance.matchId, first, second));
        assertEquals("starting", instance.state);
        service.report(instance, report("running", instance.matchId, first, second));
        assertEquals("running", instance.state);
        occupants.clear();
        assertEquals("ending", service.report(instance, report("ended", instance.matchId))
                .get("state").getAsString());
        assertTrue(queue().isEmpty());
        assertEquals("ending", persisted().get(0).state);
    }

    @Test
    void replayedAcknowledgementDoesNotTransferTwiceOrExtendJoinDeadline() throws Exception {
        Minigames.Instance instance = matched();
        JsonObject ack = report("joining", instance.matchId);
        service.report(instance, ack);
        long deadline = instance.deadline;
        clock.incrementAndGet();
        service.report(instance, ack);
        assertEquals(2, transfers.size());
        assertEquals(deadline, instance.deadline);
        assertEquals("joining", instance.state);
    }

    @Test
    void staleMatchReportCannotRefreshHeartbeatOrEndCurrentMatch() throws Exception {
        Minigames.Instance instance = matched();
        long heartbeat = instance.heartbeat;
        String matchId = instance.matchId;
        clock.incrementAndGet();
        for (String state : List.of("joining", "running", "ended", "failed")) {
            assertThrows(IllegalArgumentException.class,
                    () -> service.report(instance, report(state, UUID.randomUUID().toString())));
        }
        assertEquals(heartbeat, instance.heartbeat);
        assertEquals(matchId, instance.matchId);
        assertTrue(instance.transferPending);
        assertTrue(transfers.isEmpty());
        assertEquals("joining", instance.state);
    }

    @Test
    void uncorrelatedEndSignalCannotCancelAnAssignedMatch() throws Exception {
        Minigames.Instance instance = matched();
        service.report(instance, report("ended", null));
        service.report(instance, report("failed", null));
        assertEquals("joining", instance.state);
        assertTrue(instance.transferPending);
    }

    @Test
    void httpRequiresExactBearerAndKnownBindingWithoutMutatingState() throws Exception {
        Minigames.Instance instance = provisioning();
        for (String authorization : List.of("", TOKEN, "bearer " + TOKEN, "Bearer " + "b".repeat(64))) {
            assertEquals(403, http("POST", "arena", authorization, report("ready", null)).status());
        }
        assertEquals(403, http("POST", "unknown", "Bearer " + TOKEN, report("ready", null)).status());
        assertEquals(0, instance.heartbeat);
        assertEquals("provisioning", instance.state);
        HttpReply accepted = http("POST", "arena", "Bearer " + TOKEN, report("ready", null));
        assertEquals(200, accepted.status());
        assertEquals("ready", JsonParser.parseString(accepted.body()).getAsJsonObject().get("state").getAsString());
        assertEquals("ready", instance.state);
    }

    @Test
    void httpRejectsWrongMethodsAndOversizedBodyBeforeMutation() throws Exception {
        Minigames.Instance instance = provisioning();
        assertEquals(405, http("GET", "arena", "Bearer " + TOKEN, null).status());
        assertEquals(405, http("PUT", "arena", "Bearer " + TOKEN, report("ready", null)).status());
        JsonObject oversized = report("ready", null);
        oversized.addProperty("padding", "x".repeat(65537));
        assertEquals(413, http("POST", "arena", "Bearer " + TOKEN, oversized).status());
        assertEquals(0, instance.heartbeat);
        assertEquals("provisioning", instance.state);
    }

    @Test
    void httpStaleMatchHasAnExplicitClientErrorAndLeavesReservationIntact() throws Exception {
        Minigames.Instance instance = matched();
        long heartbeat = instance.heartbeat;
        clock.incrementAndGet();
        assertAll(
            () -> {
                HttpReply rejected = assertDoesNotThrow(() -> http("POST", "arena", "Bearer " + TOKEN,
                    report("ended", UUID.randomUUID().toString())),
                    "Stale match must receive an HTTP rejection, not a closed connection");
                assertEquals(400, rejected.status());
            },
            () -> assertEquals("joining", instance.state),
            () -> assertEquals(heartbeat, instance.heartbeat),
            () -> assertTrue(instance.transferPending),
            () -> assertTrue(transfers.isEmpty()));
    }

    @Test
    void httpAcknowledgementReplayDoesNotDuplicateTransfers() throws Exception {
        Minigames.Instance instance = matched();
        JsonObject ack = report("joining", instance.matchId);
        assertEquals(200, http("POST", "arena", "Bearer " + TOKEN, ack).status());
        assertEquals(200, http("POST", "arena", "Bearer " + TOKEN, ack).status());
        assertEquals(2, transfers.size());
        assertFalse(instance.transferPending);
    }

    @Test
    void httpMalformedReportsReturn400WithoutRefreshingOrCancellingReservation() throws Exception {
        Minigames.Instance instance = matched();
        long heartbeat = instance.heartbeat;
        String matchId = instance.matchId;
        clock.incrementAndGet();
        for (String body : List.of("{", "[]", "null", "{}",
                "{\"state\":\"unknown\",\"players\":[]}",
                "{\"state\":\"ready\"}",
                "{\"state\":\"ready\",\"players\":\"invalid\"}",
                "{\"state\":\"ready\",\"players\":[\"not-a-uuid\"]}")) {
            HttpReply rejected = assertDoesNotThrow(() -> httpRaw("POST", "arena", "Bearer " + TOKEN, body));
            assertEquals(400, rejected.status(), body);
            assertEquals("joining", instance.state, body);
            assertEquals(heartbeat, instance.heartbeat, body);
            assertEquals(matchId, instance.matchId, body);
            assertTrue(instance.transferPending, body);
            assertTrue(transfers.isEmpty(), body);
        }
    }

    @Test
    void joinTimeoutRequeuesAnIntactConnectedPartyExactlyOnce() throws Exception {
        Minigames.Instance instance = partyMatch();
        List<Matchmaker.Group> groups = instance.groups;
        clock.set(instance.deadline - 1);
        service.sweep();
        assertEquals("joining", instance.state);
        assertTrue(queue().isEmpty());
        clock.incrementAndGet();
        service.sweep();
        assertEquals("ending", instance.state);
        assertEquals(groups, queue());
        assertEquals(2, queue().get(0).players().size());
        service.sweep();
        assertEquals(groups, queue());
    }

    @Test
    void joinTimeoutDoesNotRequeueAChangedParty() throws Exception {
        Minigames.Instance instance = partyMatch();
        parties.leave(instance.groups.get(0).players().get(1));
        clock.set(instance.deadline);
        service.sweep();
        assertEquals("ending", instance.state);
        assertTrue(queue().isEmpty());
    }

    @Test
    void joinTimeoutDoesNotRequeueADisconnectedParty() throws Exception {
        Minigames.Instance instance = partyMatch();
        UUID guest = instance.groups.get(0).players().get(1);
        when(proxy.getPlayer(guest)).thenReturn(Optional.empty());
        service.disconnected(guest);
        clock.set(instance.deadline);
        service.sweep();
        assertEquals("ending", instance.state);
        assertTrue(queue().isEmpty());
    }

    @Test
    void joinTimeoutDoesNotRequeueAPartyThatLostPermission() throws Exception {
        Minigames.Instance instance = partyMatch();
        Player guest = proxy.getPlayer(instance.groups.get(0).players().get(1)).orElseThrow();
        when(guest.hasPermission("vestri.minigames")).thenReturn(false);
        clock.set(instance.deadline);
        service.sweep();
        assertTrue(queue().isEmpty());
        assertEquals("ending", instance.state);
    }

    @Test
    void readyPoolHonorsCapsAndNeverInventsReadiness() throws Exception {
        JsonObject manifest = emptyManifest();
        manifest.getAsJsonObject("minigames").getAsJsonObject("Arena").addProperty("readyServers", 2);
        configure("Arena");
        start(manifest);
        sweepWithoutNetwork(20);
        assertEquals(2, instances().size());
        assertTrue(instances().stream().allMatch(instance -> instance.state.equals("provisioning")));
        Minigames.Instance first = instances().get(0);
        service.report(first, report("ready", null, player("Unexpected")));
        assertEquals("provisioning", first.state);
        service.report(first, report("ready", UUID.randomUUID().toString()));
        assertEquals("ending", first.state);
        Minigames.Instance second = instances().get(1);
        service.report(second, report("ready", null));
        assertEquals("ready", second.state);
        sweepWithoutNetwork(5);
        assertEquals(2, instances().size());
        assertEquals(1, instances().stream().filter(instance -> instance.state.equals("ready")).count());
    }

    @Test
    void absentOrUnavailableConfiguredKeyNeverCreatesUnboundedPreparations() throws Exception {
        JsonObject manifest = emptyManifest();
        manifest.getAsJsonObject("minigames").getAsJsonObject("Arena").addProperty("readyServers", 2);
        start(manifest);
        sweepWithoutNetwork(10);
        assertTrue(instances().isEmpty());
        configure("Other");
        sweepWithoutNetwork(10);
        assertTrue(instances().isEmpty());
        Files.writeString(directory.resolve("vestri-dynamic-config.json"), "{}");
        sweepWithoutNetwork(10);
        assertTrue(instances().isEmpty());
    }

    @Test
    void assignedRecordsRecoverAsEndingAndCannotRerunTheirRoster() throws Exception {
        List<Minigames.Instance> saved = new ArrayList<>();
        for (String state : List.of("joining", "starting", "running", "ending")) {
            Minigames.Instance instance = pending();
            instance.state = state;
            instance.matchId = UUID.randomUUID().toString();
            instance.roster = List.of(new Minigames.Member(UUID.randomUUID().toString(), "Old", "red"));
            saved.add(instance);
        }
        Files.writeString(directory.resolve("vestri-minigames.json"), new Gson().toJson(saved));
        start(emptyManifest());
        for (Minigames.Instance instance : instances()) {
            assertEquals("ending", instance.state);
            service.report(instance, report("ready", null));
            service.report(instance, report("running", instance.matchId));
            assertEquals("ending", instance.state);
        }
        service.sweep();
        assertEquals(4, instances().size());
        assertTrue(queue().isEmpty());
        assertTrue(transfers.isEmpty());
        assertTrue(persisted().stream().allMatch(instance -> instance.state.equals("ending")));
    }

    @Test
    void readyAndProvisioningRecoveryStillRequireFreshReadiness() throws Exception {
        Minigames.Instance ready = pending();
        ready.state = "ready";
        Files.writeString(directory.resolve("vestri-minigames.json"), new Gson().toJson(List.of(ready, pending())));
        configure("Arena");
        start(emptyManifest());
        Player first = player("First");
        Player second = player("Second");
        queue(first);
        queue(second);
        service.sweep();
        assertEquals("ready", instances().get(0).state);
        assertEquals(0, instances().get(0).heartbeat);
        assertEquals(2, queue().size());
        service.report(instances().get(0), report("ready", null));
        service.sweep();
        assertEquals("joining", instances().get(0).state);
        assertTrue(queue().isEmpty());
    }

    @Test
    void restoredReadyWithOldCreationGetsAFullInitializationHeartbeatGrace() throws Exception {
        Minigames.Instance saved = pending();
        saved.state = "ready";
        saved.created = clock.get() - 600000;
        seed(saved);
        configure("Arena");
        start(emptyManifest());
        Minigames.Instance restored = instances().get(0);
        queue(player("First"));
        queue(player("Second"));
        service.sweep();
        assertEquals("ready", restored.state);
        assertEquals(0, restored.heartbeat);
        assertNull(restored.matchId);
        assertEquals(2, queue().size());
        clock.addAndGet(30000);
        sweepWithoutNetwork(1);
        assertEquals("ready", restored.state);
        clock.incrementAndGet();
        sweepWithoutNetwork(1);
        assertEquals("ending", restored.state);
        assertEquals("ending", persisted().get(0).state);
        assertEquals(2, queue().size());
        assertTrue(transfers.isEmpty());
    }

    @Test
    void preparationExpiresAt300000EvenWhenBusyAndBackendIsUnavailable() throws Exception {
        Minigames.Instance saved = pending();
        seed(saved);
        configure("Arena");
        start(emptyManifest());
        Minigames.Instance instance = instances().get(0);
        assertTrue(instance.busy);
        clock.set(instance.created + 299999);
        service.sweep();
        assertEquals("provisioning", instance.state);
        clock.incrementAndGet();
        service.sweep();
        assertEquals("ending", instance.state);
        assertEquals("ending", persisted().get(0).state);
        assertEquals(1, instances().size());
        assertTrue(queue().isEmpty());
        assertTrue(transfers.isEmpty());
    }

    @Test
    void preparationExpiresAt300000EvenWithoutUsableNetworkConfiguration() throws Exception {
        seed(pending());
        start(emptyManifest());
        Minigames.Instance instance = instances().get(0);
        clock.set(instance.created + 299999);
        service.sweep();
        assertEquals("provisioning", instance.state);
        clock.incrementAndGet();
        service.sweep();
        assertEquals("ending", instance.state);
        assertEquals("ending", persisted().get(0).state);
        assertEquals(1, instances().size());
    }

    @Test
    void admissionRejectsReadyDirectJoinsAndSpectatorsButAllowsAssignedMembers() throws Exception {
        Minigames.Instance instance = ready();
        Player member = player("Member");
        Player spectator = player("Spectator");
        ServerPreConnectEvent direct = new ServerPreConnectEvent(member, target);
        service.beforeConnect(direct);
        assertTrue(direct.getResult().getServer().isEmpty());
        instance.roster = List.of(new Minigames.Member(member.getUniqueId().toString(), "Member", "red"));
        for (String state : List.of("joining", "starting", "running")) {
            instance.state = state;
            ServerPreConnectEvent allowed = new ServerPreConnectEvent(member, target);
            service.beforeConnect(allowed);
            assertEquals(Optional.of(target), allowed.getResult().getServer());
            ServerPreConnectEvent denied = new ServerPreConnectEvent(spectator, target);
            service.beforeConnect(denied);
            assertTrue(denied.getResult().getServer().isEmpty());
        }
        instance.state = "ending";
        ServerPreConnectEvent ended = new ServerPreConnectEvent(member, target);
        service.beforeConnect(ended);
        assertTrue(ended.getResult().getServer().isEmpty());
        RegisteredServer lobby = mock(RegisteredServer.class);
        when(lobby.getServerInfo()).thenReturn(new ServerInfo("lobby", new InetSocketAddress("127.0.0.1", 25565)));
        ServerPreConnectEvent ordinary = new ServerPreConnectEvent(spectator, lobby);
        service.beforeConnect(ordinary);
        assertEquals(Optional.of(lobby), ordinary.getResult().getServer());
        assertTrue(service.isMatchServer(target));
        assertFalse(service.isMatchServer(lobby));
    }

    @Test
    void concurrentQueueAndSweepsCannotReserveTheSamePlayersTwice() throws Exception {
        Minigames.Instance first = ready();
        Minigames.Instance second = pending();
        second.state = "ready";
        second.heartbeat = clock.get();
        instances().add(second);
        Player leader = player("Leader");
        Player guest = player("Guest");
        parties.invite(leader.getUniqueId(), guest.getUniqueId(), clock.get());
        parties.accept(guest.getUniqueId(), leader.getUniqueId(), clock.get());
        SimpleCommand.Invocation invocation = invocation(leader, "join", "Arena");
        var workers = Executors.newFixedThreadPool(8);
        CountDownLatch gate = new CountDownLatch(1);
        List<java.util.concurrent.Future<?>> tasks = new ArrayList<>();
        try {
            for (int index = 0; index < 24; index++) {
                tasks.add(workers.submit(() -> {
                    gate.await();
                    service.execute(invocation);
                    service.sweep();
                    return null;
                }));
            }
            gate.countDown();
            for (var task : tasks) {
                task.get(5, TimeUnit.SECONDS);
            }
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertEquals(1, List.of(first, second).stream().filter(instance -> instance.state.equals("joining")).count());
        assertEquals(2, instances().stream().flatMap(instance -> instance.roster.stream()).count());
        assertTrue(queue().isEmpty());
        assertTrue(transfers.isEmpty());
    }

    @Test
    void staleReadyHeartbeatCannotBeReserved() throws Exception {
        Minigames.Instance instance = ready();
        queue(player("First"));
        queue(player("Second"));
        clock.addAndGet(5000);
        service.sweep();
        assertEquals("ready", instance.state);
        assertEquals(2, queue().size());
        assertNull(instance.matchId);
    }

    @Test
    void acknowledgedJoinUsesConfiguredTimeoutAndRequeuesTheOriginalParty() throws Exception {
        Minigames.Instance instance = partyMatch();
        List<Matchmaker.Group> original = instance.groups;
        clock.addAndGet(3000);
        service.report(instance, report("joining", instance.matchId));
        assertEquals(clock.get() + 10000, instance.deadline);
        clock.set(instance.deadline - 1);
        service.sweep();
        assertEquals("joining", instance.state);
        clock.incrementAndGet();
        service.sweep();
        assertEquals("ending", instance.state);
        assertEquals(original, queue());
        assertEquals(2, transfers.size());
    }

    @Test
    void startingTimeoutRequeuesButExplicitFailureDoesNot() throws Exception {
        Minigames.Instance instance = partyMatch();
        List<Matchmaker.Group> original = instance.groups;
        Player[] players = original.get(0).players().stream().map(id -> proxy.getPlayer(id).orElseThrow()).toArray(Player[]::new);
        service.report(instance, report("joining", instance.matchId));
        occupants.addAll(List.of(players));
        service.report(instance, report("joining", instance.matchId, players));
        assertEquals("starting", instance.state);
        occupants.clear();
        clock.set(instance.deadline);
        service.sweep();
        assertEquals(original, queue());
        assertEquals("ending", instance.state);
        queue().clear();
        Minigames.Instance failed = pending();
        failed.state = "joining";
        failed.matchId = UUID.randomUUID().toString();
        failed.groups = original;
        failed.roster = instance.roster;
        instances().add(failed);
        service.report(failed, report("failed", failed.matchId));
        assertEquals("ending", failed.state);
        assertTrue(queue().isEmpty());
    }

    @Test
    void revokedLeadershipPreventsTimeoutRequeue() throws Exception {
        Minigames.Instance instance = partyMatch();
        Matchmaker.Group group = instance.groups.get(0);
        parties.transfer(group.leader(), group.players().get(1));
        clock.set(instance.deadline);
        service.sweep();
        assertEquals("ending", instance.state);
        assertTrue(queue().isEmpty());
    }

    @Test
    void readyHeartbeatExpiryEndsRatherThanRequeues() throws Exception {
        Minigames.Instance instance = ready();
        clock.addAndGet(30000);
        service.sweep();
        assertEquals("ready", instance.state);
        clock.incrementAndGet();
        service.sweep();
        assertEquals("ending", instance.state);
        assertTrue(queue().isEmpty());
    }

    @Test
    void endingInstancesStillCountAgainstMaxInstancesUntilCleanup() throws Exception {
        JsonObject manifest = emptyManifest();
        manifest.getAsJsonObject("minigames").getAsJsonObject("Arena").addProperty("readyServers", 2);
        Minigames.Instance first = pending();
        Minigames.Instance second = pending();
        first.state = "ending";
        second.state = "ending";
        Files.writeString(directory.resolve("vestri-minigames.json"), new Gson().toJson(List.of(first, second)));
        configure("Arena");
        start(manifest);
        sweepWithoutNetwork(10);
        assertEquals(2, instances().size());
        assertTrue(instances().stream().allMatch(instance -> instance.state.equals("ending")));
    }

    @Test
    void queuedDemandCreatesOnePreparationEvenWithZeroWarmPool() throws Exception {
        configure("Arena");
        start(emptyManifest());
        queue(player("First"));
        queue(player("Second"));
        sweepWithoutNetwork(20);
        assertEquals(1, instances().size());
        assertEquals("provisioning", instances().get(0).state);
        assertEquals(2, queue().size());
        assertNull(instances().get(0).matchId);
        assertTrue(transfers.isEmpty());
    }

    @Test
    void queueRejectsNonLeaderAndOfflineOrUnauthorizedMembers() throws Exception {
        ready();
        Player leader = player("Leader");
        Player guest = player("Guest");
        parties.invite(leader.getUniqueId(), guest.getUniqueId(), clock.get());
        parties.accept(guest.getUniqueId(), leader.getUniqueId(), clock.get());
        queue(guest);
        assertTrue(queue().isEmpty());
        when(guest.hasPermission("vestri.minigames")).thenReturn(false);
        queue(leader);
        assertTrue(queue().isEmpty());
        when(guest.hasPermission("vestri.minigames")).thenReturn(true);
        when(proxy.getPlayer(guest.getUniqueId())).thenReturn(Optional.empty());
        queue(leader);
        assertTrue(queue().isEmpty());
        when(proxy.getPlayer(guest.getUniqueId())).thenReturn(Optional.of(guest));
        queue(leader);
        queue(leader);
        assertEquals(1, queue().size());
        assertEquals(parties.members(leader.getUniqueId()), queue().get(0).players());
    }

    @Test
    void selectedTemplateWithoutItsOwnConfiguredKeyRejectsQueue() throws Exception {
        configure("Other");
        start(emptyManifest());
        Player first = player("First");
        Player second = player("Second");
        queue(first);
        queue(second);
        assertTrue(queue().isEmpty());
        sweepWithoutNetwork(1);
        assertTrue(instances().isEmpty());
        assertTrue(transfers.isEmpty());
        configure("Arena");
        queue(first);
        queue(second);
        assertEquals(2, queue().size());
    }

    @Test
    void leavingAssignedMatchCancelsWithoutRequeueAndDisconnectRemovesQueuedParty() throws Exception {
        Minigames.Instance instance = partyMatch();
        Player leader = proxy.getPlayer(instance.groups.get(0).leader()).orElseThrow();
        service.execute(invocation(leader, "leave"));
        assertEquals("ending", instance.state);
        assertTrue(queue().isEmpty());
        queue(leader);
        assertEquals(1, queue().size());
        service.disconnected(instance.groups.get(0).players().get(1));
        assertTrue(queue().isEmpty());
    }

    @Test
    void runningAcknowledgementStillRequiresBothViewsToContainTheRoster() throws Exception {
        Minigames.Instance instance = partyMatch();
        Player[] players = instance.groups.get(0).players().stream().map(id -> proxy.getPlayer(id).orElseThrow()).toArray(Player[]::new);
        service.report(instance, report("joining", instance.matchId));
        occupants.addAll(List.of(players));
        service.report(instance, report("joining", instance.matchId, players));
        assertEquals("starting", instance.state);
        occupants.clear();
        service.report(instance, report("running", instance.matchId, players));
        assertEquals("starting", instance.state);
        occupants.addAll(List.of(players));
        service.report(instance, report("running", instance.matchId, players[0]));
        assertEquals("starting", instance.state);
        service.report(instance, report("running", instance.matchId, players));
        assertEquals("running", instance.state);
    }

    private void seed(Minigames.Instance instance) throws Exception {
        Files.writeString(directory.resolve("vestri-minigames.json"), new Gson().toJson(List.of(instance)));
    }

    private List<Minigames.Instance> persisted() throws Exception {
        return List.of(new Gson().fromJson(Files.readString(directory.resolve("vestri-minigames.json")), Minigames.Instance[].class));
    }

    private Minigames.Instance provisioning() throws Exception {
        Minigames.Instance pending = pending();
        seed(pending);
        start(manifest(pending.requestId));
        return instances().get(0);
    }

    private Minigames.Instance ready() throws Exception {
        return ready(null);
    }

    private Minigames.Instance ready(JsonObject manifest) throws Exception {
        configure("Arena");
        Minigames.Instance instance;
        if (manifest == null) {
            instance = provisioning();
        } else {
            start(manifest);
            instance = instances().get(0);
        }
        service.report(instance, report("ready", null));
        target = mock(RegisteredServer.class);
        when(target.getServerInfo()).thenReturn(new ServerInfo("Arena-1", InetSocketAddress.createUnresolved("vestri-arena", 25565)));
        when(target.getPlayersConnected()).thenAnswer(ignored -> List.copyOf(occupants));
        when(proxy.getServer("Arena-1")).thenReturn(Optional.of(target));
        return instance;
    }

    private Minigames.Instance matched() throws Exception {
        Minigames.Instance instance = ready();
        queue(player("First"));
        queue(player("Second"));
        service.sweep();
        assertEquals("joining", instance.state);
        return instance;
    }

    private Minigames.Instance partyMatch() throws Exception {
        Minigames.Instance instance = ready();
        Player leader = player("Leader");
        Player guest = player("Guest");
        parties.invite(leader.getUniqueId(), guest.getUniqueId(), clock.get());
        parties.accept(guest.getUniqueId(), leader.getUniqueId(), clock.get());
        queue(leader);
        service.sweep();
        assertEquals(1, instance.groups.size());
        return instance;
    }

    private Player player(String name) {
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        when(player.getUsername()).thenReturn(name);
        when(player.hasPermission("vestri.minigames")).thenReturn(true);
        when(player.isActive()).thenReturn(true);
        when(proxy.getPlayer(id)).thenReturn(Optional.of(player));
        ConnectionRequestBuilder connection = (ConnectionRequestBuilder) Proxy.newProxyInstance(
                ConnectionRequestBuilder.class.getClassLoader(), new Class<?>[]{ConnectionRequestBuilder.class},
                (ignored, method, arguments) -> {
                    if (method.getName().equals("connect")) {
                        transfers.add(id);
                        ConnectionRequestBuilder.Result result = (ConnectionRequestBuilder.Result) Proxy.newProxyInstance(
                                ConnectionRequestBuilder.Result.class.getClassLoader(),
                                new Class<?>[]{ConnectionRequestBuilder.Result.class}, (receiver, called, values) -> switch (called.getName()) {
                                    case "isSuccessful" -> true;
                                    case "getStatus" -> ConnectionRequestBuilder.Status.SUCCESS;
                                    case "getReasonComponent" -> Optional.empty();
                                    case "getAttemptedConnection" -> target;
                                    default -> throw new UnsupportedOperationException(called.getName());
                                });
                        return CompletableFuture.completedFuture(result);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        when(player.createConnectionRequest(any(RegisteredServer.class))).thenAnswer(ignored -> {
            Minigames.Instance assigned = instances().stream().filter(instance -> instance.roster.stream()
                    .anyMatch(member -> member.uuid().equals(id.toString()))).findFirst().orElseThrow();
            assertFalse(assigned.transferPending, "Roster must be acknowledged before transfer");
            Minigames.Instance saved = persisted().stream().filter(instance -> instance.requestId.equals(assigned.requestId))
                    .findFirst().orElseThrow();
            assertEquals(assigned.roster, saved.roster);
            assertEquals(assigned.matchId, saved.matchId);
            return connection;
        });
        return player;
    }

    private SimpleCommand.Invocation invocation(Player actor, String... arguments) {
        return (SimpleCommand.Invocation) Proxy.newProxyInstance(SimpleCommand.Invocation.class.getClassLoader(),
                new Class<?>[]{SimpleCommand.Invocation.class}, (ignored, method, values) -> switch (method.getName()) {
                    case "source" -> actor;
                    case "arguments" -> arguments;
                    case "alias" -> "minigame";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private void queue(Player actor) {
        service.execute(invocation(actor, "join", "Arena"));
    }

    @SuppressWarnings("unchecked")
    private List<Matchmaker.Group> queue() throws Exception {
        var field = Minigames.class.getDeclaredField("queues");
        field.setAccessible(true);
        Map<String, List<Matchmaker.Group>> queues = (Map<String, List<Matchmaker.Group>>) field.get(service);
        return queues.getOrDefault("Arena", List.of());
    }

    private void configure(String template) throws Exception {
        Files.writeString(directory.resolve("vestri-dynamic-config.json"),
                "{\"backendUrl\":\"http://127.0.0.1:1\",\"templates\":{\"" + template + "\":\"vmc_" + TOKEN + "\"}}");
    }

    private void sweepWithoutNetwork(int count) throws Exception {
        for (int index = 0; index < count; index++) {
            instances().forEach(instance -> instance.busy = true);
            service.sweep();
        }
    }

    private JsonObject report(String state, String matchId, Player... players) {
        JsonObject report = new JsonObject();
        report.addProperty("state", state);
        if (matchId != null) {
            report.addProperty("matchId", matchId);
        }
        var online = new com.google.gson.JsonArray();
        for (Player player : players) {
            online.add(player.getUniqueId().toString());
        }
        report.add("players", online);
        return report;
    }

    private record HttpReply(int status, String body) {}

    private HttpReply http(String method, String slug, String authorization, JsonObject body) throws Exception {
        return httpRaw(method, slug, authorization, body == null ? null : body.toString());
    }

    private HttpReply httpRaw(String method, String slug, String authorization, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create("http://127.0.0.1:25580/minigame/" + slug)
                .toURL().openConnection();
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(2000);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Authorization", authorization);
        try {
            if (body != null) {
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(body.getBytes(StandardCharsets.UTF_8).length);
                try (var output = connection.getOutputStream()) {
                    output.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = connection.getResponseCode();
            var input = status >= 400 ? connection.getErrorStream() : status < 0 ? null : connection.getInputStream();
            if (input == null) {
                return new HttpReply(status, "");
            }
            try (input) {
                return new HttpReply(status, new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
        } finally {
            connection.disconnect();
        }
    }

    private JsonObject emptyManifest() {
        JsonObject manifest = manifest(null);
        manifest.getAsJsonObject("servers").remove("arena");
        return manifest;
    }

    private Minigames.Instance pending() {
        Minigames.Instance instance = new Minigames.Instance();
        instance.template = "Arena";
        instance.requestId = UUID.randomUUID().toString();
        instance.created = clock.get();
        instance.busy = true;
        return instance;
    }

    private void start(JsonObject manifest) throws Exception {
        service = new Minigames(proxy, parties, directory, logger, clock::get);
        service.synchronize(manifest);
        instances().forEach(instance -> instance.busy = true);
    }

    @SuppressWarnings("unchecked")
    private List<Minigames.Instance> instances() throws Exception {
        var field = Minigames.class.getDeclaredField("instances");
        field.setAccessible(true);
        return (List<Minigames.Instance>) field.get(service);
    }

    private JsonObject manifest(String requestId) {
        return manifest(requestId, true);
    }

    private JsonObject manifest(String requestId, boolean includeSpecification) {
        JsonObject manifest = JsonParser.parseString("""
                {"minigames":{"Arena":{"teams":["red","blue"],"teamSize":2,
                "minPlayers":2,"maxPlayers":4,"joinTimeout":"10s","readyServers":0,"maxInstances":2,
                "lifecycle":{"end":{"returnPlayersTo":"lobby","deleteServer":true,
                "whenEmpty":false,"emptyGrace":"1s"}}}},"servers":{}}
                """).getAsJsonObject();
        JsonObject binding = new JsonObject();
        binding.addProperty("name", "Arena-1");
        binding.addProperty("serverId", SERVER_ID);
        binding.addProperty("template", "Arena");
        binding.addProperty("token", TOKEN);
        if (includeSpecification) {
            binding.add("spec", manifest.getAsJsonObject("minigames").getAsJsonObject("Arena").deepCopy());
        }
        if (requestId != null) {
            binding.addProperty("spawnRequestId", requestId);
        }
        manifest.getAsJsonObject("servers").add("arena", binding);
        return manifest;
    }

    private JsonObject manifest(String requestId, JsonObject specification) {
        JsonObject manifest = manifest(requestId);
        JsonObject binding = manifest.getAsJsonObject("servers").getAsJsonObject("arena");
        if (specification == null) {
            binding.remove("spec");
        } else {
            binding.add("spec", specification.deepCopy());
        }
        return manifest;
    }
}