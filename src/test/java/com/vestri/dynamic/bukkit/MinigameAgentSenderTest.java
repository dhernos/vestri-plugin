package com.vestri.dynamic.bukkit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.random.RandomGenerator;
import org.bukkit.Server;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MinigameAgentSenderTest {
    @TempDir Path data;
    private static final UUID MATCH = UUID.fromString("12345678-1234-1234-1234-123456789abc");
    private static final MinigameAgent.Member FIRST = new MinigameAgent.Member(
            UUID.fromString("11111111-1111-1111-1111-111111111111"), "First", "red");
    private static final MinigameAgent.Member SECOND = new MinigameAgent.Member(
            UUID.fromString("22222222-2222-2222-2222-222222222222"), "Second", "blue");
    private static final MinigameAgent.Member THIRD = new MinigameAgent.Member(
            UUID.fromString("33333333-3333-3333-3333-333333333333"), "Third", "red");

    private static JsonObject root() {
        JsonObject root = JsonParser.parseString("""
                {"slug":"mcgame-123","controlUrl":"http://vestri-proxy-123:25580",
                 "spec":{"teams":["red","blue","empty"],"lifecycle":{
                   "ready":{"trigger":"explicit"},"onAllJoined":[],"teamCommands":[],
                   "startCommands":[],"onPlayerJoin":[],"end":{}}}}
                """).getAsJsonObject();
        root.addProperty("token", "a".repeat(64));
        return root;
    }

    private static JsonObject lifecycle(JsonObject root) {
        return root.getAsJsonObject("spec").getAsJsonObject("lifecycle");
    }

    private static void phase(JsonObject root, String phase, String entries) {
        lifecycle(root).add(phase, JsonParser.parseString(entries));
    }

    private static MinigameAgent.Configuration parse(JsonObject root) {
        return MinigameAgent.configuration(root.toString());
    }

    private static JsonObject mode(String mode) {
        JsonObject root = root();
        if (!mode.equals("vestri")) root.getAsJsonObject("spec").addProperty("lobbyMode", "plugin");
        if (mode.equals("permanent")) {
            root.getAsJsonObject("spec").addProperty("instanceMode", "permanent");
            lifecycle(root).getAsJsonObject("end").addProperty("deleteServer", false);
            lifecycle(root).getAsJsonObject("end").addProperty("whenEmpty", false);
        }
        return root;
    }

    @Test
    void legacyDefaultsObjectsAndCompatibilityConstructors() {
        JsonObject root = root();
        phase(root, "onAllJoined", "[{\"command\":\"setup\"}]");
        phase(root, "teamCommands", "[{\"team\":\"red\",\"command\":\"assign {player}\"}]");
        phase(root, "startCommands", "[\"legacy\",{\"command\":\"object\"},{\"command\":\"play {player} {uuid} {team}\",\"sender\":\"player\"}]");
        phase(root, "onPlayerJoin", "[{\"command\":\"grant {player} {uuid} {team}\"}]");
        var config = parse(root);
        assertEquals("server", config.actions().get(0).sender());
        assertEquals("server", config.teamCommands().get(0).sender());
        assertEquals(List.of(new MinigameAgent.ExecutableCommand("legacy"), new MinigameAgent.ExecutableCommand("object"),
                new MinigameAgent.ExecutableCommand("play {player} {uuid} {team}", "player")), config.startCommands());
        assertEquals("server", config.onPlayerJoin().get(0).sender());
        assertEquals("server", new MinigameAgent.Action("setup", "").sender());
        assertEquals("server", new MinigameAgent.TeamCommand("red", "assign").sender());
        var legacy = new MinigameAgent.Configuration(config.endpoint(), config.token(), config.teams(), config.trigger(),
                config.actions(), null, "vestri", "temporary", null, config.teamCommands(), List.of("start"));
        assertEquals(List.of(new MinigameAgent.ExecutableCommand("start")), legacy.startCommands());
        assertTrue(legacy.onPlayerJoin().isEmpty());
        assertEquals(List.of("setup", "assign First", "start"), MinigameAgent.expand(legacy, List.of(FIRST), MATCH));
    }

    @Test
    void everyPhaseRejectsUnknownOrNonStringSender() {
        for (String phase : List.of("onAllJoined", "teamCommands", "startCommands", "onPlayerJoin")) {
            for (String sender : List.of("null", "false", "1", "[]", "{}", "\"\"", "\"console\"", "\"PLAYER\"", "\"server \"")) {
                JsonObject root = root();
                phase(root, phase, "[{\"command\":\"safe\",\"team\":\"red\",\"sender\":" + sender + "}]");
                assertThrows(RuntimeException.class, () -> parse(root), phase + ": " + sender);
            }
        }
    }

    @Test
    void placeholdersDependOnSenderScopeAndJoiningContext() {
        for (String phase : List.of("onAllJoined", "startCommands")) {
            for (String placeholder : List.of("player", "uuid", "team")) {
                JsonObject root = root();
                phase(root, phase, "[{\"command\":\"use {" + placeholder + "}\"}]");
                assertThrows(IllegalArgumentException.class, () -> parse(root));
                lifecycle(root).getAsJsonArray(phase).get(0).getAsJsonObject().addProperty("sender", "player");
                assertNotNull(parse(root));
            }
        }
        JsonObject root = root();
        phase(root, "onAllJoined", "[{\"command\":\"use {player} {uuid} {team}\",\"forEach\":\"team\",\"sender\":\"player\"}]");
        assertNotNull(parse(root));
        lifecycle(root).getAsJsonArray("onAllJoined").get(0).getAsJsonObject().addProperty("sender", "server");
        assertThrows(IllegalArgumentException.class, () -> parse(root));
        phase(root, "onAllJoined", "[]");
        phase(root, "onPlayerJoin", "[{\"command\":\"grant {player} {uuid} {team} {matchId}\",\"sender\":\"server\"}]");
        assertNotNull(parse(root));
        root.getAsJsonObject("spec").add("teams", JsonParser.parseString("[]"));
        assertThrows(IllegalArgumentException.class, () -> parse(root));
        phase(root, "onPlayerJoin", "[{\"command\":\"grant {player} {uuid}\"}]");
        assertNotNull(parse(root));
    }

    @Test
    void joinAndStartObjectsAreOnceOnlyAndCommandsRemainStrict() {
        for (String phase : List.of("startCommands", "onPlayerJoin")) {
            for (String scope : List.of("\"\"", "\"player\"", "\"team\"", "null", "false")) {
                JsonObject root = root();
                phase(root, phase, "[{\"command\":\"safe\",\"forEach\":" + scope + "}]");
                assertThrows(RuntimeException.class, () -> parse(root));
            }
            for (String command : List.of("/op First", "bad\\nline", "use {unknown}", "use {player", "", "x".repeat(1025))) {
                JsonObject root = root();
                phase(root, phase, "[{\"command\":\"" + command + "\",\"sender\":\"player\"}]");
                assertThrows(RuntimeException.class, () -> parse(root));
            }
        }
        for (String entries : List.of("[\"legacy-not-join\"]", "[false]", "[null]")) {
            JsonObject root = root();
            phase(root, "onPlayerJoin", entries);
            assertThrows(RuntimeException.class, () -> parse(root));
        }
    }

    @Test
    void combinedHundredIncludesJoinAndNativePermitsOnlyJoin() {
        JsonObject root = root();
        for (String name : List.of("onAllJoined", "teamCommands", "startCommands", "onPlayerJoin")) {
            phase(root, name, "[]");
            for (int index = 0; index < 25; index++) {
                lifecycle(root).getAsJsonArray(name).add(JsonParser.parseString("{\"command\":\"safe\",\"team\":\"red\"}"));
            }
        }
        assertNotNull(parse(root));
        lifecycle(root).getAsJsonArray("onPlayerJoin").add(JsonParser.parseString("{\"command\":\"overflow\"}"));
        assertThrows(IllegalArgumentException.class, () -> parse(root));
        for (String mode : List.of("plugin", "permanent")) {
            JsonObject nativeRoot = mode(mode);
            phase(nativeRoot, "onPlayerJoin", "[{\"command\":\"join {player}\",\"sender\":\"player\"}]");
            assertNotNull(parse(nativeRoot));
            for (String name : List.of("onAllJoined", "teamCommands", "startCommands")) {
                JsonObject invalid = nativeRoot.deepCopy();
                phase(invalid, name, "[{\"command\":\"startup\",\"team\":\"red\"}]");
                assertThrows(IllegalArgumentException.class, () -> parse(invalid));
            }
        }
    }

    @Test
    void deterministicPlansBindEveryPlaceholderToItsActualPlayerSender() {
        JsonObject root = root();
        phase(root, "onAllJoined", """
                [{"command":"once {player} {uuid} {team}","sender":"player"},
                 {"command":"team {player} {uuid} {team}","forEach":"team","sender":"player"},
                 {"command":"each {player} {uuid} {team}","forEach":"player","sender":"player"}]
                """);
        phase(root, "teamCommands", "[{\"team\":\"red\",\"command\":\"assign {player} {uuid} {team}\",\"sender\":\"player\"}]");
        phase(root, "startCommands", "[{\"command\":\"start {player} {uuid} {team}\",\"sender\":\"player\"},\"console\"]");
        var config = parse(root);
        var roster = List.of(FIRST, SECOND, THIRD);
        var online = Set.of(FIRST.uuid(), SECOND.uuid(), THIRD.uuid());
        var plan = MinigameAgent.expandInvocations(config, roster, online, MATCH, new Random(17));
        assertEquals(plan, MinigameAgent.expandInvocations(config, roster, online, MATCH, new Random(17)));
        assertEquals(10, plan.size());
        assertEquals(List.of("once", "team", "team", "each", "each", "each", "assign", "assign", "start", "console"),
                plan.stream().map(invocation -> invocation.command().split(" ")[0]).toList());
        for (var invocation : plan.subList(0, 9)) {
            var member = roster.stream().filter(candidate -> candidate.uuid().equals(invocation.player())).findFirst().orElseThrow();
            assertTrue(invocation.command().endsWith(member.name() + " " + member.uuid() + " " + member.team()));
        }
        assertEquals("red", roster.stream().filter(member -> member.uuid().equals(plan.get(1).player())).findFirst().orElseThrow().team());
        assertEquals(SECOND.uuid(), plan.get(2).player());
        assertEquals(FIRST.uuid(), plan.get(6).player());
        assertEquals(THIRD.uuid(), plan.get(7).player());
        assertNull(plan.get(9).player());
    }

    @Test
    void selectionUsesOnlyOnlineMembersAndNeverFallsBackAcrossTeamsOrToConsole() {
        JsonObject root = root();
        phase(root, "onAllJoined", "[{\"command\":\"once {player}\",\"sender\":\"player\"}]");
        var roster = List.of(FIRST, SECOND);
        assertEquals(SECOND.uuid(), MinigameAgent.expandInvocations(parse(root), roster,
                Set.of(SECOND.uuid()), MATCH, new Random(1)).get(0).player());
        assertThrows(IllegalArgumentException.class, () -> MinigameAgent.expandInvocations(parse(root), roster,
                Set.of(), MATCH, new Random(1)));
        phase(root, "onAllJoined", "[{\"command\":\"team {player}\",\"sender\":\"player\",\"forEach\":\"team\"}]");
        assertThrows(IllegalArgumentException.class, () -> MinigameAgent.expandInvocations(parse(root), roster,
                Set.of(SECOND.uuid()), MATCH, new Random(1)));
        var plan = MinigameAgent.expandInvocations(parse(root), List.of(SECOND), Set.of(SECOND.uuid()), MATCH, new Random(1));
        assertEquals(List.of(new MinigameAgent.CommandInvocation("team Second", SECOND.uuid())), plan);
    }

    @Test
    void serverTeamAndPerPlayerCommandsRetainTheirAssignedContext() {
        JsonObject root = root();
        phase(root, "onAllJoined", "[{\"command\":\"prepare {player} {uuid} {team}\",\"forEach\":\"player\"}]");
        phase(root, "teamCommands", "[{\"team\":\"red\",\"command\":\"assign {player} {uuid} {team}\"}]");
        var plan = MinigameAgent.expandInvocations(parse(root), List.of(FIRST, SECOND), Set.of(FIRST.uuid(), SECOND.uuid()), MATCH, new Random(1));
        assertEquals(List.of("prepare First " + FIRST.uuid() + " red", "prepare Second " + SECOND.uuid() + " blue",
                "assign First " + FIRST.uuid() + " red"), plan.stream().map(MinigameAgent.CommandInvocation::command).toList());
        assertTrue(plan.stream().allMatch(invocation -> invocation.player() == null));
    }

    @Test
    void productionPlanAllowsTenThousandAndRejectsOverflowBeforeDispatch() {
        JsonObject root = root();
        phase(root, "onAllJoined", "[]");
        for (int index = 0; index < 100; index++) {
            lifecycle(root).getAsJsonArray("onAllJoined").add(JsonParser.parseString("{\"command\":\"each {player}\",\"forEach\":\"player\"}"));
        }
        List<MinigameAgent.Member> roster = new ArrayList<>();
        for (int index = 0; index < 101; index++) roster.add(new MinigameAgent.Member(UUID.randomUUID(), "Player" + index, "red"));
        var online = Set.copyOf(roster.stream().map(MinigameAgent.Member::uuid).toList());
        assertEquals(10_000, MinigameAgent.expandInvocations(parse(root), roster.subList(0, 100), online, MATCH, new Random(1)).size());
        assertThrows(IllegalArgumentException.class, () -> MinigameAgent.expandInvocations(parse(root), roster, online, MATCH, new Random(1)));
    }

    @Test
    void randomSelectionOccursAfterDurableClaimAndNeverRerollsOnFailureOrRestart() throws Exception {
        JsonObject root = root();
        phase(root, "onAllJoined", "[{\"command\":\"once {player}\",\"sender\":\"player\"}]");
        var config = parse(root);
        Path execution = data.resolve("claim.json");
        RandomGenerator random = mock(RandomGenerator.class);
        when(random.nextInt(2)).thenAnswer(call -> {
            assertTrue(JsonParser.parseString(Files.readString(execution)).getAsJsonObject().get("startupClaimed").getAsBoolean());
            return 1;
        });
        var control = new MinigameAgent.Control("starting", MATCH, List.of(FIRST, SECOND));
        try (var agent = new MinigameAgent(null, config, execution, diagnostics(new ArrayList<>()), random)) {
            agent.ready();
            agent.applyInvocations(control, Set.of(FIRST.uuid(), SECOND.uuid()), invocation -> {
                assertEquals(new MinigameAgent.CommandInvocation("once Second", SECOND.uuid()), invocation);
                return false;
            });
            assertTrue(agent.statusSummary().contains("state=failed"));
            agent.applyInvocations(control, Set.of(FIRST.uuid(), SECOND.uuid()), invocation -> fail("No retry"));
        }
        try (var agent = new MinigameAgent(null, config, execution, diagnostics(new ArrayList<>()), random)) {
            agent.ready();
            agent.applyInvocations(control, Set.of(FIRST.uuid(), SECOND.uuid()), invocation -> fail("No restart replay"));
        }
        verify(random, times(1)).nextInt(2);
    }

    @Test
    void failedFenceWritePreventsRandomSelection() throws Exception {
        JsonObject root = root();
        phase(root, "onAllJoined", "[{\"command\":\"once\",\"sender\":\"player\"}]");
        Path execution = data.resolve("blocked.json");
        RandomGenerator random = mock(RandomGenerator.class);
        try (var agent = new MinigameAgent(null, parse(root), execution, diagnostics(new ArrayList<>()), random)) {
            agent.ready();
            agent.apply(new MinigameAgent.Control("joining", MATCH, List.of(FIRST)), Set.of(), command -> true);
            Files.delete(execution);
            Files.createDirectory(execution);
            agent.applyInvocations(new MinigameAgent.Control("starting", MATCH, List.of(FIRST)), Set.of(FIRST.uuid()), invocation -> fail("No dispatch"));
            assertTrue(agent.frozen(FIRST.uuid()));
        }
        verifyNoInteractions(random);
    }

    @Test
    void runtimeTickUsesActualPlayerAndConsoleInOrderWithoutPermissionMutation() throws Exception {
        JsonObject root = root();
        phase(root, "onAllJoined", "[{\"command\":\"setup\"},{\"command\":\"player {player}\",\"sender\":\"player\",\"forEach\":\"player\"}]");
        phase(root, "teamCommands", "[{\"team\":\"red\",\"command\":\"team {player}\",\"sender\":\"player\"}]");
        phase(root, "startCommands", "[{\"command\":\"start {player}\",\"sender\":\"player\"},\"finish\"]");
        try (Harness harness = new Harness(root, data.resolve("runtime.json"))) {
            Player player = harness.connect(FIRST);
            harness.agent.ready();
            harness.tick(new MinigameAgent.Control("starting", MATCH, List.of(FIRST)));
            assertEquals(List.of("server:setup", "First:player First", "First:team First", "First:start First", "server:finish"), harness.executed);
            assertTrue(harness.agent.statusSummary().contains("state=running"));
            verify(player, never()).setOp(anyBoolean());
            verify(player, never()).chat(anyString());
            verify(player, never()).addAttachment(any());
            verify(harness.server, never()).dispatchCommand(eq(player), anyString());
        }
    }

    @Test
    void missingOfflineReplacedOrOffThreadPlayerNeverFallsBackToConsole() throws Exception {
        for (String failure : List.of("missing", "offline", "replaced", "renamed", "thread", "denied")) {
            JsonObject root = root();
            phase(root, "onAllJoined", "[{\"command\":\"play\",\"sender\":\"player\"},{\"command\":\"never\"}]");
            try (Harness harness = new Harness(root, data.resolve("runtime-" + failure + ".json"))) {
                Player player = harness.connect(FIRST);
                harness.agent.ready();
                harness.agent.applyInvocations(new MinigameAgent.Control("starting", MATCH, List.of(FIRST)), Set.of(FIRST.uuid()), invocation -> {
                    Map<UUID, Player> snapshot = Map.of(FIRST.uuid(), player);
                    switch (failure) {
                        case "missing" -> snapshot = Map.of();
                        case "offline" -> when(player.isOnline()).thenReturn(false);
                        case "replaced" -> harness.connect(FIRST);
                        case "renamed" -> when(player.getName()).thenReturn("Renamed");
                        case "thread" -> when(harness.server.isPrimaryThread()).thenReturn(false);
                        case "denied" -> harness.accept = command -> false;
                        default -> fail("Unknown failure");
                    }
                    return harness.agent.executeInvocation(invocation, snapshot);
                });
                assertTrue(harness.agent.statusSummary().contains("state=failed"));
                assertTrue(harness.agent.frozen(FIRST.uuid()));
                verify(harness.server, never()).dispatchCommand(any(), anyString());
                verify(player, never()).setOp(anyBoolean());
                verify(player, never()).chat(anyString());
            }
        }
    }

    @Test
    void joinRunsOneTickLaterOncePerConnectionInAllModesAndReplayDoesNotRepeat() throws Exception {
        for (String mode : List.of("vestri", "plugin", "permanent")) {
            JsonObject root = mode(mode);
            phase(root, "onPlayerJoin", "[{\"command\":\"grant {player} {uuid} {team}\"},{\"command\":\"enter {player}\",\"sender\":\"player\"}]");
            try (Harness harness = new Harness(root, data.resolve("join-" + mode + ".json"))) {
                harness.admit(List.of(FIRST));
                Player player = harness.connect(FIRST);
                harness.join(player);
                harness.join(player);
                assertEquals(1, harness.queued.size());
                assertTrue(harness.executed.isEmpty());
                assertEquals(0, reportPlayers(harness.agent));
                assertEquals(1L, harness.queued.get(0).delay());
                harness.next();
                assertEquals(List.of("server:grant First " + FIRST.uuid() + " red", "First:enter First"), harness.executed);
                assertEquals(1, reportPlayers(harness.agent));
                harness.join(player);
                harness.tick(new MinigameAgent.Control(mode.equals("vestri") ? "starting" : "running", MATCH, List.of(FIRST), 1));
                harness.tick(new MinigameAgent.Control("running", MATCH, List.of(FIRST), 1));
                assertEquals(2, harness.executed.size());
                assertTrue(harness.agent.statusSummary().contains("joinCommands=2"));
                assertTrue(harness.agent.statusSummary().contains("playerSenderCommands=1"));
                assertTrue(harness.agent.statusSummary().contains("serverSenderCommands=1"));
                assertTrue(harness.agent.statusSummary().contains("state=running"));
                verify(player, never()).setOp(anyBoolean());
                verify(player, never()).chat(anyString());
            }
        }
    }

    @Test
    void quitAndReplacementInvalidateOldCallbacksWithoutCancellingNewSession() throws Exception {
        JsonObject root = mode("permanent");
        phase(root, "onPlayerJoin", "[{\"command\":\"enter\",\"sender\":\"player\"}]");
        try (Harness harness = new Harness(root, data.resolve("sessions.json"))) {
            harness.admit(List.of(FIRST));
            Player old = harness.connect(FIRST);
            harness.join(old);
            Queued oldCallback = harness.queued.remove(0);
            harness.quit(old);
            verify(oldCallback.task()).cancel();
            Player replacement = harness.connect(FIRST);
            harness.join(replacement);
            Queued newCallback = harness.queued.remove(0);
            harness.quit(old);
            oldCallback.runnable().run();
            assertTrue(harness.executed.isEmpty());
            verify(newCallback.task(), never()).cancel();
            newCallback.runnable().run();
            oldCallback.runnable().run();
            newCallback.runnable().run();
            assertEquals(List.of("First:enter"), harness.executed);
            verify(old, never()).performCommand(anyString());
            harness.quit(replacement);
            Player rejoined = harness.connect(FIRST);
            harness.join(rejoined);
            harness.next();
            assertEquals(List.of("First:enter", "First:enter"), harness.executed);
            verify(rejoined).performCommand("enter");
        }
    }

    @Test
    void replacementJoinWithoutOldQuitStillCancelsOnlyTheOldTask() throws Exception {
        JsonObject root = mode("permanent");
        phase(root, "onPlayerJoin", "[{\"command\":\"enter\",\"sender\":\"player\"}]");
        try (Harness harness = new Harness(root, data.resolve("replacement.json"))) {
            harness.admit(List.of(FIRST));
            Player old = harness.connect(FIRST);
            harness.join(old);
            Queued pending = harness.queued.remove(0);
            Player replacement = harness.connect(FIRST);
            harness.join(replacement);
            verify(pending.task()).cancel();
            pending.runnable().run();
            harness.next();
            assertEquals(List.of("First:enter"), harness.executed);
            verify(old, never()).performCommand(anyString());
        }
    }

    @Test
    void joinWaitsForAdmissionAndReadinessWithoutRetryingSideEffects() throws Exception {
        JsonObject root = mode("permanent");
        phase(root, "onPlayerJoin", "[{\"command\":\"grant {player}\"},{\"command\":\"enter\",\"sender\":\"player\"}]");
        try (Harness harness = new Harness(root, data.resolve("race.json"))) {
            Player player = harness.connect(FIRST);
            harness.join(player);
            for (int tick = 0; tick < 4; tick++) harness.next();
            assertTrue(harness.executed.isEmpty());
            harness.admit(List.of(FIRST));
            harness.next();
            assertEquals(List.of("server:grant First", "First:enter"), harness.executed);
            harness.join(player);
            assertTrue(harness.queued.isEmpty());
            verify(player, never()).kickPlayer(anyString());
        }
    }

    @Test
    void joinTimeoutDisconnectsOnlyCurrentSessionAfterBoundedWait() throws Exception {
        JsonObject root = mode("permanent");
        phase(root, "onPlayerJoin", "[{\"command\":\"enter\",\"sender\":\"player\"}]");
        try (Harness harness = new Harness(root, data.resolve("timeout.json"))) {
            Player player = harness.connect(FIRST);
            harness.join(player);
            for (int tick = 0; tick < 100; tick++) harness.next();
            assertTrue(harness.queued.isEmpty());
            assertTrue(harness.executed.isEmpty());
            verify(player).kickPlayer("Minigame admission failed. Please reconnect.");
            harness.admit(List.of(FIRST));
            harness.join(player);
            assertTrue(harness.queued.isEmpty());
            assertEquals(0, reportPlayers(harness.agent));
        }
    }

    @Test
    void failedJoinStopsRemainingCommandsDisconnectsAndDoesNotFailPermanentMatch() throws Exception {
        for (boolean throwing : List.of(false, true)) {
            JsonObject root = mode("permanent");
            phase(root, "onPlayerJoin", "[{\"command\":\"first\"},{\"command\":\"secret-password\",\"sender\":\"player\"},{\"command\":\"never\"}]");
            try (Harness harness = new Harness(root, data.resolve("join-failure-" + throwing + ".json"))) {
                harness.admit(List.of(FIRST, SECOND));
                Player player = harness.connect(FIRST);
                harness.accept = command -> {
                    if (!command.equals("secret-password")) return true;
                    if (throwing) throw new IllegalStateException("secret-password " + "a".repeat(64));
                    return false;
                };
                harness.join(player);
                harness.next();
                assertEquals(List.of("server:first", "First:secret-password"), harness.executed);
                verify(player).kickPlayer("Minigame admission failed. Please reconnect.");
                assertTrue(harness.agent.statusSummary().contains("state=running"));
                assertEquals(0, reportPlayers(harness.agent));
                assertTrue(harness.logs.stream().anyMatch(record -> record.getMessage().contains("index=2, sender=player, player=" + FIRST.uuid())));
                assertFalse(harness.logs.stream().anyMatch(record -> record.getMessage().contains("secret-password") || record.getMessage().contains("a".repeat(64))));
                assertFalse(harness.agent.statusSummary().contains("secret-password"));
                Player other = harness.connect(SECOND);
                harness.accept = command -> true;
                harness.join(other);
                harness.next();
                verify(other).performCommand("secret-password");
                verify(other, never()).kickPlayer(anyString());
            }
        }
    }

    @Test
    void joinServerContextMustMatchTheLiveSessionBeforeAnyGrant() throws Exception {
        JsonObject root = mode("permanent");
        phase(root, "onPlayerJoin", "[{\"command\":\"grant {player}\"}]");
        try (Harness harness = new Harness(root, data.resolve("join-name.json"))) {
            harness.admit(List.of(FIRST));
            Player player = harness.connect(FIRST);
            when(player.getName()).thenReturn("Renamed");
            harness.join(player);
            harness.next();
            assertTrue(harness.executed.isEmpty());
            verify(player).kickPlayer("Minigame admission failed. Please reconnect.");
            assertEquals(0, reportPlayers(harness.agent));
        }
    }

    @Test
    void lostConnectionBetweenJoinCommandsStopsBeforeNextServerCommand() throws Exception {
        JsonObject root = mode("permanent");
        phase(root, "onPlayerJoin", "[{\"command\":\"first\"},{\"command\":\"never\"}]");
        try (Harness harness = new Harness(root, data.resolve("mid-join-quit.json"))) {
            harness.admit(List.of(FIRST));
            Player player = harness.connect(FIRST);
            harness.accept = command -> {
                harness.players.remove(FIRST.uuid());
                return true;
            };
            harness.join(player);
            harness.next();
            assertEquals(List.of("server:first"), harness.executed);
            verify(player, never()).kickPlayer(anyString());
            assertEquals(0, reportPlayers(harness.agent));
        }
    }

    @Test
    void closeCancelsPendingJoinAndStaleCallbacksCannotDispatch() throws Exception {
        JsonObject root = mode("permanent");
        phase(root, "onPlayerJoin", "[{\"command\":\"enter\",\"sender\":\"player\"}]");
        Harness harness = new Harness(root, data.resolve("close.json"));
        harness.admit(List.of(FIRST));
        Player player = harness.connect(FIRST);
        harness.join(player);
        Queued pending = harness.queued.remove(0);
        harness.close();
        verify(pending.task()).cancel();
        pending.runnable().run();
        assertTrue(harness.executed.isEmpty());
    }

    @Test
    void reattachingAgentNeverSynthesizesJoinForAlreadyOnlinePlayers() throws Exception {
        JsonObject root = mode("permanent");
        phase(root, "onPlayerJoin", "[{\"command\":\"enter\",\"sender\":\"player\"}]");
        Path path = data.resolve("reattach.json");
        try (Harness harness = new Harness(root, path)) {
            harness.admit(List.of(FIRST));
            harness.join(harness.connect(FIRST));
            harness.next();
            assertEquals(List.of("First:enter"), harness.executed);
        }
        try (Harness harness = new Harness(root, path)) {
            harness.connect(FIRST);
            harness.agent.ready();
            harness.tick(new MinigameAgent.Control("running", MATCH, List.of(FIRST), 1));
            assertTrue(harness.executed.isEmpty());
            assertTrue(harness.queued.isEmpty());
        }
    }

    @Test
    void startupWaitsForSuccessfulJoinAndFailureLogsNeverDumpCommands() throws Exception {
        JsonObject root = root();
        phase(root, "onPlayerJoin", "[{\"command\":\"enter\",\"sender\":\"player\"}]");
        phase(root, "startCommands", "[{\"command\":\"private-token-" + "a".repeat(64) + "\",\"sender\":\"player\"},\"never\"]");
        try (Harness harness = new Harness(root, data.resolve("join-before-start.json"))) {
            harness.admit(List.of(FIRST));
            harness.join(harness.connect(FIRST));
            var starting = new MinigameAgent.Control("starting", MATCH, List.of(FIRST));
            harness.tick(starting);
            assertTrue(harness.executed.isEmpty());
            harness.next();
            harness.accept = command -> { throw new IllegalArgumentException(command); };
            harness.tick(starting);
            assertTrue(harness.agent.frozen(FIRST.uuid()));
            assertTrue(harness.agent.statusSummary().contains("state=failed"));
            assertFalse(harness.logs.stream().anyMatch(record -> record.getMessage().contains("private-token") || record.getMessage().contains("a".repeat(64))));
        }
    }

    private static int reportPlayers(MinigameAgent agent) {
        return JsonParser.parseString(agent.statusReport(Set.of(FIRST.uuid()))).getAsJsonObject().getAsJsonArray("players").size();
    }

    private static MinigameAgent.Diagnostics diagnostics(List<LogRecord> records) {
        Logger logger = new Logger("agent-sender-test", null) { };
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { records.add(record); }
            @Override public void flush() { }
            @Override public void close() { }
        });
        return new MinigameAgent.Diagnostics(logger, System::nanoTime);
    }

    private record Queued(Runnable runnable, BukkitTask task, long delay) { }

    private static final class Harness implements AutoCloseable {
        final JavaPlugin plugin = mock(JavaPlugin.class);
        final Server server = mock(Server.class);
        final BukkitScheduler scheduler = mock(BukkitScheduler.class);
        final Map<UUID, Player> players = new HashMap<>();
        final List<Queued> queued = new ArrayList<>();
        final List<String> executed = new ArrayList<>();
        final List<LogRecord> logs = new ArrayList<>();
        final MinigameAgent agent;
        Predicate<String> accept = command -> true;

        Harness(JsonObject root, Path execution) throws Exception {
            when(plugin.getServer()).thenReturn(server);
            when(server.getScheduler()).thenReturn(scheduler);
            when(server.isPrimaryThread()).thenReturn(true);
            when(server.getPlayer(any(UUID.class))).thenAnswer(call -> players.get(call.getArgument(0)));
            when(server.getOnlinePlayers()).thenAnswer(call -> List.copyOf(players.values()));
            when(server.getConsoleSender()).thenReturn(mock(ConsoleCommandSender.class));
            when(server.dispatchCommand(any(), anyString())).thenAnswer(call -> {
                String command = call.getArgument(1);
                executed.add("server:" + command);
                return accept.test(command);
            });
            when(scheduler.runTaskLater(eq(plugin), any(Runnable.class), anyLong())).thenAnswer(call -> {
                BukkitTask task = mock(BukkitTask.class);
                queued.add(new Queued(call.getArgument(1), task, call.getArgument(2)));
                return task;
            });
            agent = new MinigameAgent(plugin, parse(root), execution, diagnostics(logs), new Random(17));
        }

        Player connect(MinigameAgent.Member member) {
            Player player = mock(Player.class);
            when(player.getUniqueId()).thenReturn(member.uuid());
            when(player.getName()).thenReturn(member.name());
            when(player.isOnline()).thenReturn(true);
            when(player.performCommand(anyString())).thenAnswer(call -> {
                String command = call.getArgument(0);
                executed.add(member.name() + ":" + command);
                return accept.test(command);
            });
            players.put(member.uuid(), player);
            return player;
        }

        void admit(List<MinigameAgent.Member> roster) {
            agent.ready();
            agent.apply(new MinigameAgent.Control("joining", MATCH, roster, 1), Set.of(), command -> fail("No startup"));
        }

        void join(Player player) { agent.onJoin(new PlayerJoinEvent(player, null)); }
        void quit(Player player) { agent.onQuit(new PlayerQuitEvent(player, null)); }
        void next() { queued.remove(0).runnable().run(); }

        @SuppressWarnings("unchecked")
        void tick(MinigameAgent.Control control) throws Exception {
            var incoming = MinigameAgent.class.getDeclaredField("incoming");
            incoming.setAccessible(true);
            ((AtomicReference<MinigameAgent.Control>) incoming.get(agent)).set(control);
            var tick = MinigameAgent.class.getDeclaredMethod("tick");
            tick.setAccessible(true);
            tick.invoke(agent);
        }

        @Override public void close() { agent.close(); }
    }
}