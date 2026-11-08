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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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
    private Minigames service;
    private static final String TOKEN = "a".repeat(64);
    private static final String SERVER_ID = "11111111-1111-1111-1111-111111111111";
    private final List<Player> occupants = new ArrayList<>();
    private final List<UUID> transfers = new ArrayList<>();
    private RegisteredServer target;

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
        start(manifest(UUID.randomUUID().toString()));
        assertEquals(2, instances().size());
        assertNull(instances().get(0).slug);
        assertNull(instances().get(0).serverId);
        assertEquals("provisioning", instances().get(0).state);
        assertEquals("ending", instances().get(1).state);
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
        configure("Arena");
        Minigames.Instance instance = provisioning();
        service.report(instance, report("ready", null));
        target = mock(RegisteredServer.class);
        when(target.getServerInfo()).thenReturn(new ServerInfo("Arena-1", new InetSocketAddress("127.0.0.1", 25565)));
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
                        return CompletableFuture.completedFuture(null);
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
        service = new Minigames(proxy, parties, directory, mock(Logger.class), clock::get);
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