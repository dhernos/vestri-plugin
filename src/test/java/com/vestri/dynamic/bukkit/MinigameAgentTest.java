package com.vestri.dynamic.bukkit;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Flow;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MinigameAgentTest {
        @TempDir Path data;
    private final UUID match = UUID.fromString("12345678-1234-1234-1234-123456789abc");

        @Test
        void listensToActualUnregisteredPluginLoggerWithoutParentHandlers() throws Exception {
                var config = MinigameAgent.configuration(config("http://vestri-proxy-123:25580"));
                Logger actual = new Logger("Game", null) { };
                actual.setUseParentHandlers(false);
                var installed = org.mockito.Mockito.mock(org.bukkit.plugin.Plugin.class);
                org.mockito.Mockito.when(installed.getLogger()).thenReturn(actual);
                try (var agent = new MinigameAgent(null, config, data.resolve("direct-logger.json"))) {
                        agent.configureLogSource(new org.bukkit.plugin.Plugin[]{installed},
                                        Logger.getLogger("Vestri"), Logger.getLogger("Minecraft"));
                        agent.attachEndListener();
                        assertEquals(1, actual.getHandlers().length);
                        agent.ready();
                        agent.apply(new MinigameAgent.Control("starting", match,
                                        List.of(new MinigameAgent.Member(match, "First", "red"))), Set.of(match), command -> true);
                        actual.info("finished");
                        agent.apply(null, Set.of(match), command -> true);
                        assertEquals("ended", status(agent));
                }
                assertEquals(0, actual.getHandlers().length);
        }

        @Test
        void statusShowsSavedPhasesAndProtectionUntilFinalStartDispatch() throws Exception {
                var config = MinigameAgent.configuration(phasedConfig());
                var member = new MinigameAgent.Member(match, "First", "red");
                try (var agent = new MinigameAgent(null, config, data.resolve("status.json"))) {
                        agent.ready();
                        assertTrue(agent.statusSummary().contains("state=ready"));
                        agent.apply(new MinigameAgent.Control("joining", match, List.of(member)), Set.of(match),
                                        command -> fail("Joining must not dispatch setup"));
                        assertTrue(agent.frozen(match));
                        assertTrue(agent.statusSummary().contains("frozen=1"));
                        assertTrue(agent.statusSummary().contains("teamCommands=" + config.teamCommands().size()));
                        assertTrue(agent.statusSummary().contains("startCommands=" + config.startCommands().size()));
                        agent.apply(new MinigameAgent.Control("starting", match, List.of(member)), Set.of(match), command -> {
                                assertTrue(agent.frozen(match));
                                return true;
                        });
                        assertFalse(agent.frozen(match));
                        assertTrue(agent.statusSummary().contains("state=running"));
                        assertTrue(agent.statusSummary().contains("frozen=0"));
                        assertFalse(agent.statusSummary().contains(config.token()));
                }
        }

        @Test
        void onlyVerifiedExactPluginLoggerCanEndAndRawAnchorsHaveNoPrefix() throws Exception {
                String text = config("http://vestri-proxy-123:25580").replace("finished", "^xy$");
                var config = MinigameAgent.configuration(text);
                try (var agent = new MinigameAgent(null, config, data.resolve("logger.json"))) {
                        trustGameLogger(agent);
                        agent.ready();
                        agent.apply(new MinigameAgent.Control("starting", match,
                                        List.of(new MinigameAgent.Member(match, "Player", "red"))), Set.of(match), command -> true);
                        for (String source : List.of("", "Minecraft", "Vestri", "OtherGame", "game", "Game.child")) {
                                agent.observeLog(log(source, "xy"));
                                agent.apply(null, Set.of(match), command -> true);
                                assertEquals("running", status(agent));
                        }
                        agent.observeLog("xy");
                        agent.observeLog(log(null, "xy"));
                        agent.observeLog(log("Game", "[Game] xy"));
                        agent.apply(null, Set.of(match), command -> true);
                        assertEquals("running", status(agent));
                        agent.observeLog(log("Game", "xy"));
                        agent.apply(null, Set.of(match), command -> true);
                        assertEquals("ended", status(agent));
                }
        }

        private static LogRecord log(String source, String text) {
                var record = new LogRecord(java.util.logging.Level.INFO, text);
                record.setLoggerName(source);
                return record;
        }

        private static org.bukkit.plugin.Plugin gamePlugin(String loggerName) {
                return (org.bukkit.plugin.Plugin) java.lang.reflect.Proxy.newProxyInstance(
                                MinigameAgentTest.class.getClassLoader(), new Class<?>[]{org.bukkit.plugin.Plugin.class},
                                (proxy, method, args) -> method.getName().equals("getLogger") ? Logger.getLogger(loggerName) : null);
        }

        private static void trustGameLogger(MinigameAgent agent) {
                agent.configureLogSource(new org.bukkit.plugin.Plugin[]{gamePlugin("Game")},
                                Logger.getLogger("Vestri"), Logger.getLogger("Minecraft"));
        }

        @Test
        void allPhasesRunInOrderAndOnlyUnlockAfterTheLastCommand() throws Exception {
                String text = phasedConfig();
                var config = MinigameAgent.configuration(text);
                var first = new MinigameAgent.Member(match, "First", "red");
                var second = new MinigameAgent.Member(UUID.randomUUID(), "Second", "blue");
                var third = new MinigameAgent.Member(UUID.randomUUID(), "Third", "red");
                var roster = List.of(first, second, third);
                var expected = List.of("start " + match, "red First red " + match, "red Third red " + third.uuid(),
                                "again First", "again Third", "blue Second", "launch " + match, "final");
                assertEquals(expected, MinigameAgent.expand(config, roster, match));
                var starting = new MinigameAgent.Control("starting", match, roster);
                Set<UUID> online = Set.of(first.uuid(), second.uuid(), third.uuid());
                for (int failAt : List.of(-1, 0, 1, 6, 7)) {
                        Path path = data.resolve("phases-" + failAt + ".json");
                        try (var agent = new MinigameAgent(null, config, path)) {
                                agent.ready();
                                List<String> dispatched = new ArrayList<>();
                                agent.apply(starting, online, command -> {
                                        assertEquals("starting", status(agent));
                                        dispatched.add(command);
                                        return dispatched.size() - 1 != failAt;
                                });
                                assertEquals(failAt < 0 ? "running" : "failed", status(agent));
                                assertEquals(expected.subList(0, failAt < 0 ? expected.size() : failAt + 1), dispatched);
                                agent.apply(starting, online, command -> fail("No phase replay"));
                        }
                        try (var restored = new MinigameAgent(null, config, path)) {
                                assertEquals("failed", status(restored));
                                restored.ready();
                                restored.apply(starting, online, command -> fail("No restart replay"));
                        }
                }
        }

        @Test
        void newPhasesValidateTeamsScopesTypesAndNativeOwnership() {
                String text = phasedConfig();
                for (String invalid : List.of(
                                text.replace("\"teams\":[\"red\",\"blue\"]", "\"teams\":[]"),
                                text.replace("\"team\":\"red\"", "\"team\":\"unknown\""),
                                text.replace("\"teams\":", "\"lobbyMode\":\"plugin\",\"teams\":"),
                                text.replace("launch {matchId}", "launch {player}"),
                                text.replace("launch {matchId}", "launch {team}"),
                                text.replace("again {player}", "/op {player}"),
                                text.replace("again {player}", "again {unknown}"),
                                text.replace("again {player}", "again\\n{player}"),
                                text.replace("\"final\"", "false"))) {
                        assertThrows(RuntimeException.class, () -> MinigameAgent.configuration(invalid), invalid);
                }
                var legacy = MinigameAgent.configuration(config("http://vestri-proxy-123:25580"));
                assertTrue(legacy.teamCommands().isEmpty());
                assertTrue(legacy.startCommands().isEmpty());
        }

        @Test
        void boundsNewPhaseListsAndTotalExpansionBeforeAnyDispatch() throws Exception {
                for (String phase : List.of("teamCommands", "startCommands")) {
                        var root = com.google.gson.JsonParser.parseString(phasedConfig()).getAsJsonObject();
                        var entries = root.getAsJsonObject("spec").getAsJsonObject("lifecycle").getAsJsonArray(phase);
                        var entry = entries.get(0).deepCopy();
                        for (String other : List.of("onAllJoined", "teamCommands", "startCommands")) {
                                if (!other.equals(phase)) root.getAsJsonObject("spec").getAsJsonObject("lifecycle").remove(other);
                        }
                        while (entries.size() < 100) entries.add(entry.deepCopy());
                        assertNotNull(MinigameAgent.configuration(root.toString()));
                        entries.add(entry);
                        assertThrows(IllegalArgumentException.class, () -> MinigameAgent.configuration(root.toString()));
                }
                var parsed = MinigameAgent.configuration(phasedConfig());
                var roster = new ArrayList<MinigameAgent.Member>();
                for (int index = 0; index < 512; index++) {
                        roster.add(new MinigameAgent.Member(UUID.randomUUID(), "Player" + index, "red"));
                }
                var config = new MinigameAgent.Configuration(parsed.endpoint(), parsed.token(), parsed.teams(), parsed.trigger(),
                                java.util.Collections.nCopies(20, new MinigameAgent.Action("prepare {player}", "player")), null,
                                "vestri", "temporary", null, List.of(new MinigameAgent.TeamCommand("red", "assign {player}")), List.of());
                assertThrows(IllegalArgumentException.class, () -> MinigameAgent.expand(config, roster, match));
                try (var agent = new MinigameAgent(null, config, data.resolve("expansion-limit.json"))) {
                        agent.ready();
                        agent.apply(new MinigameAgent.Control("starting", match, roster),
                                        Set.copyOf(roster.stream().map(MinigameAgent.Member::uuid).toList()), command -> fail("Expand before dispatch"));
                        assertEquals("failed", status(agent));
                        assertTrue(agent.frozen(roster.get(0).uuid()));
                }
                String ffa = config("http://vestri-proxy-123:25580").replace("[\"red\",\"blue\"]", "[]")
                                .replace("\"end\":", "\"teamCommands\":[],\"startCommands\":[\"begin {matchId}\"],\"end\":");
                assertEquals(List.of("start " + match, "begin " + match), MinigameAgent.expand(MinigameAgent.configuration(ffa),
                                List.of(new MinigameAgent.Member(match, "Player", "")), match));
        }

        private String phasedConfig() {
                return config("http://vestri-proxy-123:25580").replace("\"end\":", "\"teamCommands\":["
                                + "{\"team\":\"red\",\"command\":\"red {player} {team} {uuid}\"},"
                                + "{\"team\":\"red\",\"command\":\"again {player}\"},"
                                + "{\"team\":\"blue\",\"command\":\"blue {player}\"}],"
                                + "\"startCommands\":[\"launch {matchId}\",\"final\"],\"end\":");
        }

        @Test
        void broadRegexNeverAcceptsRootChatOrOtherPluginRecords() throws Exception {
                var config = MinigameAgent.configuration(config("http://vestri-proxy-123:25580").replace("finished", ".*"));
                try (var agent = new MinigameAgent(null, config, data.resolve("chat.json"))) {
                        trustGameLogger(agent);
                        agent.ready();
                        agent.apply(new MinigameAgent.Control("starting", match,
                                        List.of(new MinigameAgent.Member(match, "Player", "red"))), Set.of(match), command -> true);
                        for (String source : List.of("", "Minecraft", "global", "Vestri", "OtherGame")) {
                                agent.observeLog(log(source, "<Player> Game finished"));
                                agent.apply(null, Set.of(match), command -> true);
                                assertEquals("running", status(agent));
                        }
                        agent.observeLog(log("Game", "genuine plugin signal"));
                        agent.apply(null, Set.of(match), command -> true);
                        assertEquals("ended", status(agent));
                }
        }

        @Test
        void legacyMissingUninstalledAndReservedLoggersDisableOnlyAutomaticEnd() throws Exception {
                for (String source : List.of("missing", "Uninstalled", "Vestri", "Minecraft", "global", "server", "Game")) {
                        String text = config("http://vestri-proxy-123:25580");
                        text = source.equals("missing") ? text.replace("\"loggerName\":\"Game\",", "")
                                        : text.replace("\"loggerName\":\"Game\"", "\"loggerName\":\"" + source + "\"");
                        var config = MinigameAgent.configuration(text);
                        Logger logger = Logger.getLogger("vestri-disabled-log-" + UUID.randomUUID());
                        logger.setUseParentHandlers(false);
                        var logs = capture(logger);
                        try (var agent = new MinigameAgent(null, config, data.resolve(source + ".json"),
                                        new MinigameAgent.Diagnostics(logger, System::nanoTime))) {
                                agent.configureLogSource(new org.bukkit.plugin.Plugin[]{gamePlugin("Vestri"), gamePlugin("Minecraft"),
                                                gamePlugin("global"), gamePlugin("server")}, Logger.getLogger("Vestri"), Logger.getLogger("Minecraft"));
                                assertEquals(1, logs.size());
                                assertTrue(logs.get(0).getMessage().contains("automatic log end disabled"));
                                agent.ready();
                                agent.apply(new MinigameAgent.Control("starting", match,
                                                List.of(new MinigameAgent.Member(match, "Player", "red"))), Set.of(match), command -> true);
                                agent.observeLog(log(source, "Game finished"));
                                agent.apply(null, Set.of(match), command -> true);
                                assertEquals("running", status(agent));
                                agent.end();
                                assertEquals("ended", status(agent));
                        }
                }
        }

        @Test
        void loggerNameIsBoundedAsciiAndMustBeAString() {
                String text = config("http://vestri-proxy-123:25580");
                for (String name : List.of("A", "a".repeat(128), "com.example.Game:logger-1_2")) {
                        assertEquals(name, MinigameAgent.configuration(text.replace("\"Game\"", "\"" + name + "\"")).loggerName());
                }
                for (String invalid : List.of("\"\"", "null", "123", "true", "[]", "{}", "\" Game\"",
                                "\"Game \"", "\"Game\\n\"", "\"G\\u0430me\"", "\"" + "a".repeat(129) + "\"")) {
                        assertThrows(RuntimeException.class, () -> MinigameAgent.configuration(text.replace("\"Game\"", invalid)));
                }
        }

        @Test
        void managedGuardTracksRosterStartupFailureEndRestartAndCloseButNeverNative() throws Exception {
                var config = MinigameAgent.configuration(phasedConfig());
                var roster = List.of(new MinigameAgent.Member(match, "Player", "red"));
                var starting = new MinigameAgent.Control("starting", match, roster);
                for (String outcome : List.of("success", "false", "throw", "end")) {
                        Path path = data.resolve("guard-" + outcome + ".json");
                        var agent = new MinigameAgent(null, config, path);
                        try (agent) {
                                assertFalse(agent.frozen(match));
                                agent.apply(starting, Set.of(match), command -> fail("Wait for readiness"));
                                assertTrue(agent.frozen(match));
                                assertFalse(agent.frozen(UUID.randomUUID()));
                                agent.ready();
                                agent.apply(starting, Set.of(), command -> fail("Wait for every player"));
                                assertTrue(agent.frozen(match));
                                agent.apply(starting, Set.of(match), command -> {
                                        assertTrue(agent.frozen(match));
                                        if (outcome.equals("throw")) throw new IllegalStateException();
                                        if (outcome.equals("end")) agent.end();
                                        return !outcome.equals("false");
                                });
                                assertEquals(!outcome.equals("success"), agent.frozen(match));
                                agent.end();
                                assertTrue(agent.frozen(match));
                        }
                        assertFalse(agent.frozen(match));
                        try (var restored = new MinigameAgent(null, config, path)) {
                                assertTrue(restored.frozen(match));
                        }
                }
                var nativeConfig = MinigameAgent.configuration(config("http://vestri-proxy-123:25580")
                                .replace("[{\"command\":\"start {matchId}\"}]", "[]")
                                .replace("\"teams\":", "\"lobbyMode\":\"plugin\",\"teams\":"));
                try (var nativeAgent = new MinigameAgent(null, nativeConfig, data.resolve("unguarded-native.json"))) {
                        nativeAgent.apply(new MinigameAgent.Control("starting", match, roster, 1), Set.of(), command -> fail("Native phase"));
                        assertFalse(nativeAgent.frozen(match));
                        nativeAgent.ready();
                        assertFalse(nativeAgent.frozen(match));
                        nativeAgent.end();
                        assertFalse(nativeAgent.frozen(match));
                }
        }

        @Test
        void unavailableControlIsPermanentOnly() {
                String response = "{\"state\":\"unavailable\",\"matchId\":\"" + match
                                + "\",\"rosterVersion\":2,\"roster\":[]}";
                assertThrows(IllegalArgumentException.class, () -> MinigameAgent.control(response, List.of()));
                var control = MinigameAgent.control(response, List.of(), "permanent");
                assertEquals("unavailable", control.state());
                assertEquals(2, control.rosterVersion());
                assertEquals(match, control.matchId());
        }

    @Test
    void expandsActionsInConfiguredOrder() {
        UUID first = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID second = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        var actions = List.of(new MinigameAgent.Action("team add {team}", "team"),
                new MinigameAgent.Action("assign {player} {uuid} {team} {matchId}", "player"),
                new MinigameAgent.Action("start {matchId}", ""));
        var roster = List.of(new MinigameAgent.Member(second, "Second", "blue"),
                new MinigameAgent.Member(first, "First", "red"));
        assertEquals(List.of("team add red", "team add blue",
                "assign Second " + second + " blue " + match,
                "assign First " + first + " red " + match, "start " + match),
                MinigameAgent.expand(actions, List.of("red", "blue"), roster, match));
    }

    @Test
        void fencesDuplicateAndForeignMatchesEvenAfterFailedAttempt() throws Exception {
                var fence = new MinigameAgent.MatchFence(data.resolve("minigame-execution.json"), List.of());
        assertFalse(fence.accept(null));
                assertTrue(fence.accept(match, List.of(new MinigameAgent.Member(match, "Player", ""))));
        assertTrue(fence.accept(match));
        assertFalse(fence.claimStartup(UUID.randomUUID()));
        assertTrue(fence.claimStartup(match));
        assertFalse(fence.claimStartup(match));
        assertFalse(fence.accept(UUID.randomUUID()));
        assertEquals(match, fence.matchId());
    }

        @Test
        void durableClaimSurvivesRecreationBeforeAnyAction() throws Exception {
                Path path = data.resolve("minigame-execution.json");
                var roster = List.of(new MinigameAgent.Member(match, "Player", "red"));
                var first = new MinigameAgent.MatchFence(path, List.of("red"));
                assertTrue(first.accept(match, roster));
                var assigned = new MinigameAgent.MatchFence(path, List.of("red"));
                assertEquals(roster, assigned.roster());
                assertFalse(assigned.startupClaimed());
                assertTrue(assigned.claimStartup(match));
                var recreated = new MinigameAgent.MatchFence(path, List.of("red"));
                assertTrue(recreated.startupClaimed());
                assertEquals(match, recreated.matchId());
                assertEquals(roster, recreated.roster());
                assertFalse(recreated.claimStartup(match));
                assertFalse(recreated.accept(UUID.randomUUID(), roster));
                assertFalse(recreated.accept(match, List.of()));
                try (var files = Files.list(data)) {
                        assertEquals(List.of(path), files.toList());
                }
        }

        @Test
        void agentClaimsBeforeDispatchAndRecreatedAgentReportsFailedWithoutReplay() throws Exception {
                Path path = data.resolve("minigame-execution.json");
                var config = MinigameAgent.configuration(config("http://vestri-proxy-123:25580"));
                var roster = List.of(new MinigameAgent.Member(match, "Player", "red"));
                var starting = new MinigameAgent.Control("starting", match, roster);
                List<String> commands = new ArrayList<>();
                try (var agent = new MinigameAgent(null, config, path)) {
                        assertFalse(agent.allows(match));
                        agent.ready();
                        assertEquals("ready", status(agent));
                        assertFalse(agent.allows(match));
                        agent.apply(starting, Set.of(), command -> fail("Must wait for the roster to join"));
                        assertTrue(agent.allows(match));
                        assertFalse(agent.allows(UUID.randomUUID()));
                        agent.apply(starting, Set.of(match), command -> {
                                try {
                                        var durable = new MinigameAgent.MatchFence(path, config.teams());
                                        assertTrue(durable.startupClaimed());
                                        assertEquals(roster, durable.roster());
                                } catch (Exception failure) {
                                        throw new AssertionError(failure);
                                }
                                commands.add(command);
                                return true;
                        });
                        assertEquals("running", status(agent));
                        agent.apply(starting, Set.of(match), command -> fail("Duplicate startup"));
                }
                assertEquals(List.of("start " + match), commands);
                try (var restarted = new MinigameAgent(null, config, path)) {
                        restarted.ready();
                        restarted.apply(starting, Set.of(match), command -> fail("Restart replay"));
                        assertEquals("failed", status(restarted));
                        var report = com.google.gson.JsonParser.parseString(restarted.statusReport(Set.of(match))).getAsJsonObject();
                        assertEquals(match.toString(), report.get("matchId").getAsString());
                        assertEquals(match.toString(), report.getAsJsonArray("players").get(0).getAsString());
                        assertFalse(restarted.allows(match));
                }
                assertTrue(Files.exists(path));
        }

        @Test
        void assignedButUnclaimedRestartStillWaitsForReadinessAndAllPlayers() throws Exception {
                Path path = data.resolve("minigame-execution.json");
                var config = MinigameAgent.configuration(config("http://vestri-proxy-123:25580"));
                var starting = new MinigameAgent.Control("starting", match,
                                List.of(new MinigameAgent.Member(match, "Player", "red")));
                try (var first = new MinigameAgent(null, config, path)) {
                        first.apply(starting, Set.of(match), command -> fail("Pre-ready execution"));
                        assertFalse(first.allows(match));
                        first.ready();
                        assertTrue(first.allows(match));
                }
                try (var restarted = new MinigameAgent(null, config, path)) {
                        assertEquals("joining", status(restarted));
                        assertFalse(restarted.allows(match));
                        restarted.apply(starting, Set.of(match), command -> fail("Pre-ready restart execution"));
                        restarted.ready();
                        assertTrue(restarted.allows(match));
                        restarted.apply(starting, Set.of(match), command -> true);
                        assertEquals("running", status(restarted));
                }
        }

        @Test
        void logEndingOnlyAppliesAfterRunningIncludingLogsDuringStartup() throws Exception {
                var config = MinigameAgent.configuration(config("http://vestri-proxy-123:25580"));
                var starting = new MinigameAgent.Control("starting", match,
                                List.of(new MinigameAgent.Member(match, "Player", "red")));
                try (var agent = new MinigameAgent(null, config, data.resolve("minigame-execution.json"))) {
                        trustGameLogger(agent);
                        agent.observeLog(log("Game", "previous Game finished"));
                        agent.apply(null, Set.of(), command -> true);
                        assertEquals("joining", status(agent));
                        agent.ready();
                        agent.observeLog(log("Game", "Game finished"));
                        agent.apply(starting, Set.of(), command -> true);
                        assertEquals("joining", status(agent));
                        agent.observeLog(log("Game", "Game finished"));
                        agent.apply(starting, Set.of(match), command -> {
                                agent.observeLog(log("Game", "Game finished"));
                                return true;
                        });
                        agent.apply(null, Set.of(match), command -> true);
                        assertEquals("running", status(agent));
                        agent.observeLog(log("Game", "unrelated"));
                        agent.apply(null, Set.of(match), command -> true);
                        assertEquals("running", status(agent));
                        agent.observeLog(log("Game", "Game finished"));
                        agent.apply(null, Set.of(match), command -> true);
                        assertEquals("ended", status(agent));
                        assertFalse(agent.allows(match));
                }
        }

        @Test
        void explicitEndWorksBeforeReadinessAndDuringStartupAndProxyEnding() throws Exception {
                var config = MinigameAgent.configuration(config("http://vestri-proxy-123:25580")
                                .replace("start {matchId}", "first"));
                for (String initial : List.of("joining", "ready", "running")) {
                        Path path = data.resolve(initial + ".json");
                        try (var agent = new MinigameAgent(null, config, path)) {
                                if (!initial.equals("joining")) {
                                        agent.ready();
                                }
                                if (initial.equals("running")) {
                                        agent.apply(new MinigameAgent.Control("starting", match,
                                                        List.of(new MinigameAgent.Member(match, "Player", "red"))), Set.of(match), command -> true);
                                }
                                assertEquals(initial, status(agent));
                                agent.end();
                                agent.ready();
                                assertEquals("ended", status(agent));
                        }
                }
                var twoActions = new MinigameAgent.Configuration(config.endpoint(), config.token(), config.teams(),
                                config.trigger(), List.of(new MinigameAgent.Action("first", ""), new MinigameAgent.Action("last", "")),
                                config.endPattern());
                try (var agent = new MinigameAgent(null, twoActions, data.resolve("explicit.json"))) {
                        agent.ready();
                        List<String> commands = new ArrayList<>();
                        agent.apply(new MinigameAgent.Control("starting", match,
                                        List.of(new MinigameAgent.Member(match, "Player", "red"))), Set.of(match), command -> {
                                commands.add(command);
                                agent.end();
                                return true;
                        });
                        assertEquals(List.of("first"), commands);
                        assertEquals("ended", status(agent));
                }
                try (var agent = new MinigameAgent(null, config, data.resolve("proxy.json"))) {
                        agent.apply(new MinigameAgent.Control("ending", match, null), Set.of(), command -> fail("Ending execution"));
                        assertEquals("ended", status(agent));
                }
        }

        @Test
        void persistenceFailurePreventsActionsAndClosesAdmission() throws Exception {
                Path path = data.resolve("minigame-execution.json");
                var config = MinigameAgent.configuration(config("http://vestri-proxy-123:25580"));
                try (var agent = new MinigameAgent(null, config, path)) {
                        agent.ready();
                        agent.apply(new MinigameAgent.Control("joining", match,
                                        List.of(new MinigameAgent.Member(match, "Player", "red"))), Set.of(), command -> true);
                        Files.delete(path);
                        Files.createDirectory(path);
                        agent.apply(new MinigameAgent.Control("starting", match, null), Set.of(match),
                                        command -> fail("Cannot execute without a durable claim"));
                        assertEquals("failed", status(agent));
                        assertFalse(agent.allows(match));
                }
        }

        @Test
        void partialStartupFailuresRemainClaimedAcrossRestart() throws Exception {
                var parsed = MinigameAgent.configuration(config("http://vestri-proxy-123:25580"));
                var config = new MinigameAgent.Configuration(parsed.endpoint(), parsed.token(), parsed.teams(), parsed.trigger(),
                                List.of(new MinigameAgent.Action("first", ""), new MinigameAgent.Action("fail", ""),
                                                new MinigameAgent.Action("last", "")), parsed.endPattern());
                var starting = new MinigameAgent.Control("starting", match,
                                List.of(new MinigameAgent.Member(match, "Player", "red")));
                for (boolean throwing : List.of(false, true)) {
                        Path path = data.resolve("failure-" + throwing + ".json");
                        List<String> commands = new ArrayList<>();
                        try (var agent = new MinigameAgent(null, config, path)) {
                                agent.ready();
                                agent.apply(starting, Set.of(match), command -> {
                                        commands.add(command);
                                        if (command.equals("fail") && throwing) {
                                                throw new IllegalStateException("Startup failed");
                                        }
                                        return !command.equals("fail");
                                });
                                assertEquals("failed", status(agent));
                                assertEquals(List.of("first", "fail"), commands);
                                agent.apply(starting, Set.of(match), command -> fail("Failure replay"));
                        }
                        try (var restarted = new MinigameAgent(null, config, path)) {
                                restarted.ready();
                                restarted.apply(starting, Set.of(match), command -> fail("Partial startup replay"));
                                assertEquals("failed", status(restarted));
                                assertFalse(restarted.allows(match));
                        }
                }
        }

        @Test
        void corruptExecutionAndInvalidConfigurationDenyEntry() throws Exception {
                Path path = data.resolve("minigame-execution.json");
                for (String invalid : List.of("{", "{}", "{\"matchId\":\"" + match + "\",\"startupClaimed\":true}",
                                "{\"matchId\":\"" + match + "\",\"startupClaimed\":\"true\"}", "x".repeat(MinigameAgent.MAX_BYTES + 1))) {
                        Files.writeString(path, invalid);
                        assertThrows(Exception.class, () -> new MinigameAgent.MatchFence(path, List.of("red")));
                }
                var event = new org.bukkit.event.player.AsyncPlayerPreLoginEvent("Player",
                                java.net.InetAddress.getLoopbackAddress(), match);
                new MinigameAgent.DenyEntry().onLogin(event);
                assertEquals(org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST, event.getLoginResult());
        }

        @Test
        void configurationUsesBackendStringIdentifiersAndActionLimits() {
                String text = config("http://vestri-proxy-123:25580");
                for (String valid : List.of("0", "1_red-blue", "A".repeat(64))) {
                        assertEquals(valid, MinigameAgent.configuration(text.replace("\"red\"", "\"" + valid + "\"")).teams().get(0));
                }
                for (String invalid : List.of("\"_red\"", "\"-red\"", "\"\"", "\"" + "A".repeat(65) + "\"",
                                "123", "{\"id\":\"red\"}", "[\"red\"]")) {
                        assertThrows(RuntimeException.class,
                                        () -> MinigameAgent.configuration(text.replace("\"red\"", invalid)));
                }
                for (String command : List.of("/start", "start {unknown}", "start {team}", "start {player}",
                                "start {uuid}", "start {matchId", "start {}", "x".repeat(1025), "start\\tgame")) {
                        assertThrows(IllegalArgumentException.class,
                                        () -> MinigameAgent.configuration(text.replace("start {matchId}", command)));
                }
                assertThrows(IllegalArgumentException.class, () -> MinigameAgent.configuration(text.replace("finished", "end\\nround")));
                assertEquals(List.of(), MinigameAgent.configuration(text.replace("[\"red\",\"blue\"]", "[]")).teams());
                var many = com.google.gson.JsonParser.parseString(text).getAsJsonObject();
                var actions = many.getAsJsonObject("spec").getAsJsonObject("lifecycle").getAsJsonArray("onAllJoined");
                var action = actions.get(0).deepCopy();
                for (int count = 1; count < 100; count++) {
                        actions.add(action.deepCopy());
                }
                assertEquals(100, MinigameAgent.configuration(many.toString()).actions().size());
                actions.add(action);
                assertThrows(IllegalArgumentException.class, () -> MinigameAgent.configuration(many.toString()));
        }

        private String status(MinigameAgent agent) {
                return com.google.gson.JsonParser.parseString(agent.statusReport(Set.of())).getAsJsonObject().get("state").getAsString();
        }

    @Test
    void validatesPrivateEndpointAndConfiguration() {
        String config = config("http://vestri-proxy-123:25580");
        var parsed = MinigameAgent.configuration(config);
        assertEquals("http://vestri-proxy-123:25580/minigame/mcgame-123", parsed.endpoint().toString());
        assertEquals(List.of("red", "blue"), parsed.teams());
        assertEquals("explicit", parsed.trigger());
        assertTrue(parsed.endPattern().matcher("Game finished").find());
        for (String url : List.of("http://example.com:25580", "https://vestri-proxy:25580",
                "http://vestri-proxy:25580/path", "http://secret@vestri-proxy:25580")) {
            assertThrows(IllegalArgumentException.class, () -> MinigameAgent.configuration(config(url)));
        }
        assertThrows(IllegalArgumentException.class,
                () -> MinigameAgent.configuration(config.replace("explicit", "unknown")));
        assertThrows(IllegalArgumentException.class,
                () -> MinigameAgent.configuration(config.replace("finished", "[")));
    }

    @Test
    void rejectsUnsafeOrDuplicateRosterAndInvalidMatchIds() {
        String body = "{\"state\":\"starting\",\"matchId\":\"" + match + "\",\"roster\":["
                + "{\"uuid\":\"" + match + "\",\"name\":\"Player\",\"team\":\"red\"}]}";
        assertEquals(1, MinigameAgent.control(body, List.of("red")).roster().size());
        assertThrows(IllegalArgumentException.class,
                () -> MinigameAgent.control(body.replace("Player", "Player;op"), List.of("red")));
        assertThrows(IllegalArgumentException.class, () -> MinigameAgent.control(body, List.of("blue")));
        assertThrows(IllegalArgumentException.class,
                () -> MinigameAgent.control(body.replace(match.toString(), "1-1-1-1-1"), List.of("red")));
        String member = "{\"uuid\":\"" + match + "\",\"name\":\"Player\",\"team\":\"red\"}";
        assertThrows(IllegalArgumentException.class,
                () -> MinigameAgent.control(body.replace(member, member + "," + member), List.of("red")));
        assertNull(MinigameAgent.control("{\"state\":\"ready\"}", List.of()).matchId());
    }

    @Test
    void teamFreeConfigurationAndPlayerActionsRequireNoTeam() {
        String text = config("http://vestri-proxy-123:25580");
        for (String teams : List.of("\"teams\":[],", "\"teams\":null,", "")) {
            String ffa = text.replace("\"teams\":[\"red\",\"blue\"],", teams)
                    .replace("{\"command\":\"start {matchId}\"}",
                            "{\"command\":\"assign {player} {uuid} {matchId}\",\"forEach\":\"player\"}");
            var parsed = MinigameAgent.configuration(ffa);
            var roster = MinigameAgent.control("{\"state\":\"starting\",\"matchId\":\"" + match
                    + "\",\"roster\":[{\"uuid\":\"" + match + "\",\"name\":\"Player\",\"team\":\"\"}]}", parsed.teams()).roster();
            assertEquals(List.of("assign Player " + match + " " + match),
                    MinigameAgent.expand(parsed.actions(), parsed.teams(), roster, match));
            assertThrows(IllegalArgumentException.class, () -> MinigameAgent.configuration(ffa.replace("{uuid}", "{team}")));
            assertThrows(IllegalArgumentException.class, () -> MinigameAgent.configuration(ffa
                    .replace("assign {player} {uuid} {matchId}", "start {matchId}").replace("\"player\"", "\"team\"")));
        }
        String body = "{\"state\":\"starting\",\"matchId\":\"" + match + "\",\"roster\":["
                + "{\"uuid\":\"" + match + "\",\"name\":\"Player\",\"team\":\"\"}]}";
        assertThrows(IllegalArgumentException.class, () -> MinigameAgent.control(body, List.of("red")));
        assertThrows(IllegalArgumentException.class, () -> MinigameAgent.control(body.replace("\"team\":\"\"", "\"team\":\"red\""), List.of()));
    }

    @Test
        void nativeLobbyAdmitsIncrementalRosterWithoutStartupAndFailsOnRestart() throws Exception {
                var config = MinigameAgent.configuration(config("http://vestri-proxy-123:25580")
                                .replace("[{\"command\":\"start {matchId}\"}]", "[]")
                                .replace("\"teams\":", "\"lobbyMode\":\"plugin\",\"teams\":"));
                var first = new MinigameAgent.Member(match, "Player", "red");
                var second = new MinigameAgent.Member(UUID.randomUUID(), "Second", "blue");
                Path path = data.resolve("native.json");
                try (var agent = new MinigameAgent(null, config, path)) {
                        agent.apply(new MinigameAgent.Control("joining", match, List.of(first), 1), Set.of(),
                                        command -> fail("Native must never dispatch"));
                        assertEquals("joining", status(agent));
                        assertFalse(agent.allows(match));
                        assertTrue(new MinigameAgent.MatchFence(path, config.teams(), "plugin").startupClaimed());
                        agent.ready();
                        assertEquals("running", status(agent));
                        assertTrue(agent.allows(match));
                        agent.apply(new MinigameAgent.Control("joining", match, List.of(first, second), 2), Set.of(),
                                        command -> fail("No all-online or starting gate"));
                        assertTrue(agent.allows(second.uuid()));
                        assertEquals(2, reportVersion(agent));
                        var durable = new MinigameAgent.MatchFence(path, config.teams(), "plugin");
                        assertEquals(List.of(first, second), durable.roster());
                        assertEquals(2, durable.rosterVersion());
                        for (var invalid : List.of(
                                        new MinigameAgent.Control("running", match, List.of(first), 3),
                                        new MinigameAgent.Control("running", match, List.of(first), 1),
                                        new MinigameAgent.Control("running", UUID.randomUUID(), List.of(first, second), 3),
                                        new MinigameAgent.Control("running", match, List.of(first,
                                                        new MinigameAgent.Member(second.uuid(), "Renamed", "blue")), 3),
                                        new MinigameAgent.Control("running", match, List.of(first,
                                                        new MinigameAgent.Member(second.uuid(), "Second", "red")), 3))) {
                                agent.apply(invalid, Set.of(), command -> fail("Native dispatch"));
                                assertEquals(2, reportVersion(agent));
                                assertTrue(agent.allows(second.uuid()));
                        }
                        agent.apply(new MinigameAgent.Control("ending", match, List.of(first, second), 1), Set.of(),
                                        command -> fail("Cancel dispatch"));
                        assertEquals("ended", status(agent));
                        assertEquals(2, reportVersion(agent));
                }
                try (var restarted = new MinigameAgent(null, config, path)) {
                        restarted.ready();
                        assertEquals("failed", status(restarted));
                        assertFalse(restarted.allows(match));
                        assertEquals(2, reportVersion(restarted));
                }
        }

        @Test
        void nativeFenceRequiresIncreasingVersionsAndOnlySafeAdditions() throws Exception {
                Path path = data.resolve("native-fence.json");
                var fence = new MinigameAgent.MatchFence(path, List.of(), "plugin");
                var first = new MinigameAgent.Member(match, "Player", "");
                var second = new MinigameAgent.Member(UUID.randomUUID(), "Second", "");
                assertFalse(fence.updateNative(match, List.of(first), 0));
                assertFalse(Files.exists(path));
                assertTrue(fence.updateNative(match, List.of(first), 1));
                assertFalse(fence.updateNative(match, List.of(first, second), 1));
                assertTrue(fence.updateNative(match, List.of(first), 1));
                assertTrue(fence.updateNative(match, List.of(first, second), 2));
                assertFalse(fence.updateNative(match, List.of(second), 3));
                assertFalse(fence.updateNative(match, List.of(first, new MinigameAgent.Member(UUID.randomUUID(), "Second", "")), 3));
                assertThrows(IllegalArgumentException.class, () -> fence.updateNative(match, List.of(first, first), 3));
                assertThrows(IllegalArgumentException.class, () -> fence.updateNative(match,
                                List.of(first, new MinigameAgent.Member(second.uuid(), "bad;op", "")), 3));
                assertThrows(IllegalArgumentException.class, () -> fence.updateNative(match,
                                List.of(first, new MinigameAgent.Member(second.uuid(), "Second", "red")), 3));
                assertEquals(2, fence.rosterVersion());
                assertEquals(List.of(first, second), new MinigameAgent.MatchFence(path, List.of(), "plugin").roster());
                assertFalse(fence.accept(match, List.of(first, second)));
                var vestri = new MinigameAgent.MatchFence(data.resolve("vestri-fence.json"), List.of());
                assertFalse(vestri.updateNative(match, List.of(first), 1));
                assertTrue(vestri.accept(match, List.of(first)));
                assertFalse(vestri.accept(match, List.of(first, second)));
        }

        @Test
        void nativeEmptyLobbyStillNeedsReadyAndLogEndWorksBeforeAllJoined() throws Exception {
                var config = MinigameAgent.configuration(config("http://vestri-proxy-123:25580")
                                .replace("[{\"command\":\"start {matchId}\"}]", "[]")
                                .replace("\"teams\":[\"red\",\"blue\"]", "\"lobbyMode\":\"plugin\",\"teams\":[]"));
                Path path = data.resolve("empty-native.json");
                try (var agent = new MinigameAgent(null, config, path)) {
                        agent.ready();
                        assertEquals("ready", status(agent));
                        agent.apply(new MinigameAgent.Control("joining", match, List.of(), 1), Set.of(), command -> fail("Native dispatch"));
                        assertEquals("running", status(agent));
                        trustGameLogger(agent);
                        agent.observeLog(log("Game", "Game finished"));
                        agent.apply(null, Set.of(), command -> fail("Native dispatch"));
                        assertEquals("ended", status(agent));
                }
                try (var agent = new MinigameAgent(null, config, path)) {
                        assertEquals("failed", status(agent));
                }
        }

        @Test
        void nativePersistenceFailureDoesNotAcknowledgeOrAdmitAdditions() throws Exception {
                var config = MinigameAgent.configuration(config("http://vestri-proxy-123:25580")
                                .replace("[{\"command\":\"start {matchId}\"}]", "[]")
                                .replace("\"teams\":", "\"lobbyMode\":\"plugin\",\"teams\":"));
                var first = new MinigameAgent.Member(match, "Player", "red");
                var second = new MinigameAgent.Member(UUID.randomUUID(), "Second", "blue");
                Path path = data.resolve("native-failure.json");
                try (var agent = new MinigameAgent(null, config, path)) {
                        agent.ready();
                        agent.apply(new MinigameAgent.Control("joining", match, List.of(first), 1), Set.of(), command -> fail("Native dispatch"));
                        Files.delete(path);
                        Files.createDirectory(path);
                        agent.apply(new MinigameAgent.Control("running", match, List.of(first, second), 2), Set.of(), command -> fail("Native dispatch"));
                        assertEquals("failed", status(agent));
                        assertEquals(1, reportVersion(agent));
                        assertFalse(agent.allows(second.uuid()));
                }
        }

        @Test
        void lobbyModeAndWireVersionsAreStrictWithLegacyDefaultZero() throws Exception {
                String text = config("http://vestri-proxy-123:25580");
                assertEquals("vestri", MinigameAgent.configuration(text).lobbyMode());
                for (String invalid : List.of("null", "1", "\"native\"", "\"\"")) {
                        assertThrows(IllegalArgumentException.class, () -> MinigameAgent.configuration(
                                        text.replace("\"teams\":", "\"lobbyMode\":" + invalid + ",\"teams\":")));
                }
                assertEquals(0, MinigameAgent.control("{\"state\":\"ready\"}", List.of()).rosterVersion());
                assertEquals(12, MinigameAgent.control("{\"state\":\"ready\",\"rosterVersion\":12}", List.of()).rosterVersion());
                for (String invalid : List.of("null", "-1", "1.5", "1.0", "1e2", "\"1\"", "true", "2147483648")) {
                        assertThrows(IllegalArgumentException.class, () -> MinigameAgent.control(
                                        "{\"state\":\"ready\",\"rosterVersion\":" + invalid + "}", List.of()));
                }
                try (var agent = new MinigameAgent(null, MinigameAgent.configuration(text), data.resolve("legacy-report.json"))) {
                        assertEquals(0, reportVersion(agent));
                }
                Path legacy = data.resolve("legacy-claimed.json");
                Files.writeString(legacy, "{\"matchId\":\"" + match + "\",\"startupClaimed\":true,\"roster\":["
                                + "{\"uuid\":\"" + match + "\",\"name\":\"Player\",\"team\":\"red\"}]}");
                try (var agent = new MinigameAgent(null, MinigameAgent.configuration(text), legacy)) {
                        assertEquals("failed", status(agent));
                        assertEquals(0, reportVersion(agent));
                }
        }

        @Test
        void permanentNativeRestartRestoresRosterButWaitsForReadinessWithoutReplay() throws Exception {
                String text = config("http://vestri-proxy-123:25580")
                                .replace("\"teams\":", "\"instanceMode\":\"permanent\",\"lobbyMode\":\"plugin\",\"teams\":")
                                .replace("[{\"command\":\"start {matchId}\"}]", "[]")
                                .replace("\"logPattern\":\"finished\"", "\"deleteServer\":false,\"whenEmpty\":false");
                var config = MinigameAgent.configuration(text);
                Path path = data.resolve("permanent.json");
                var roster = List.of(new MinigameAgent.Member(match, "Player", "red"));
                var control = new MinigameAgent.Control("starting", match, roster, 1);
                try (var agent = new MinigameAgent(null, config, path)) {
                        agent.ready();
                        agent.apply(control, Set.of(match), command -> fail("Permanent native dispatch"));
                        assertEquals("running", status(agent));
                }
                try (var restored = new MinigameAgent(null, config, path)) {
                        assertEquals("joining", status(restored));
                        assertEquals(1, reportVersion(restored));
                        assertFalse(restored.allows(match));
                        restored.apply(control, Set.of(match), command -> fail("Restart replay"));
                        assertEquals("joining", status(restored));
                        restored.ready();
                        assertEquals("running", status(restored));
                        assertTrue(restored.allows(match));
                        assertEquals(match.toString(), com.google.gson.JsonParser.parseString(restored.statusReport(Set.of()))
                                        .getAsJsonObject().get("matchId").getAsString());
                }
        }

        @Test
        void permanentUnavailableRosterWaitsForReadyThenReportsRunningAndExactVersion() throws Exception {
                String text = config("http://vestri-proxy-123:25580")
                                .replace("\"teams\":", "\"instanceMode\":\"permanent\",\"lobbyMode\":\"plugin\",\"teams\":")
                                .replace("[{\"command\":\"start {matchId}\"}]", "[]")
                                .replace("\"logPattern\":\"finished\"", "\"deleteServer\":false,\"whenEmpty\":false");
                var config = MinigameAgent.configuration(text);
                String response = "{\"state\":\"unavailable\",\"matchId\":\"" + match
                                + "\",\"rosterVersion\":2,\"roster\":[{\"uuid\":\"" + match
                                + "\",\"name\":\"Player\",\"team\":\"red\"}]}";
                var control = MinigameAgent.control(response, config.teams(), config.instanceMode());
                try (var agent = new MinigameAgent(null, config, data.resolve("unavailable.json"))) {
                        agent.apply(control, Set.of(), command -> fail("Permanent native dispatch"));
                        assertEquals("joining", status(agent));
                        assertFalse(agent.allows(match));
                        assertEquals(2, reportVersion(agent));
                        agent.ready();
                        assertEquals("running", status(agent));
                        assertTrue(agent.allows(match));
                        assertEquals(2, reportVersion(agent));
                        agent.apply(control, Set.of(), command -> fail("Permanent native dispatch"));
                        assertEquals("running", status(agent));
                }
        }

        @Test
        void permanentFullRosterRemovesReplacesAndRestoresEmptyDenyAll() throws Exception {
                var config = MinigameAgent.configuration(permanentConfig());
                var first = new MinigameAgent.Member(match, "Player", "red");
                var second = new MinigameAgent.Member(UUID.randomUUID(), "Second", "blue");
                var replacement = new MinigameAgent.Member(UUID.randomUUID(), "Replacement", "blue");
                Path path = data.resolve("permanent-roster.json");
                try (var agent = new MinigameAgent(null, config, path)) {
                        assertEquals("joining", status(agent));
                        agent.ready();
                        assertEquals("ready", status(agent));
                        agent.apply(new MinigameAgent.Control("running", match, List.of(first, second), 1), Set.of(),
                                        command -> fail("Native dispatch"));
                        assertTrue(agent.allows(second.uuid()));
                        agent.apply(new MinigameAgent.Control("running", match, List.of(first, replacement), 2), Set.of(first.uuid()),
                                        command -> fail("Native reset"));
                        assertFalse(agent.allows(second.uuid()));
                        assertTrue(agent.allows(first.uuid()));
                        assertTrue(agent.allows(replacement.uuid()));
                        assertEquals("running", status(agent));
                        var empty = MinigameAgent.control("{\"state\":\"running\",\"matchId\":\"" + match
                                        + "\",\"rosterVersion\":3,\"roster\":[]}", config.teams());
                        agent.apply(empty, Set.of(), command -> fail("Empty native reset"));
                        assertEquals("running", status(agent));
                        assertEquals(3, reportVersion(agent));
                        assertFalse(agent.allows(first.uuid()));
                        assertFalse(agent.allows(replacement.uuid()));
                        assertFalse(agent.allows(UUID.randomUUID()));
                }
                try (var restored = new MinigameAgent(null, config, path)) {
                        assertEquals("joining", status(restored));
                        assertEquals(3, reportVersion(restored));
                        restored.ready();
                        assertEquals("running", status(restored));
                        assertFalse(restored.allows(match));
                        var report = com.google.gson.JsonParser.parseString(restored.statusReport(Set.of())).getAsJsonObject();
                        assertEquals(match.toString(), report.get("matchId").getAsString());
                        assertTrue(report.getAsJsonArray("players").isEmpty());
                }
        }

        @Test
        void permanentRejectsStaleVersionsAndRetainedIdentityChangesWithoutWriting() throws Exception {
                var config = MinigameAgent.configuration(permanentConfig());
                Path path = data.resolve("permanent-identities.json");
                var first = new MinigameAgent.Member(match, "Player", "red");
                var second = new MinigameAgent.Member(UUID.randomUUID(), "Second", "blue");
                try (var agent = new MinigameAgent(null, config, path)) {
                        agent.ready();
                        agent.apply(new MinigameAgent.Control("running", match, List.of(first, second), 5), Set.of(),
                                        command -> fail("Native dispatch"));
                        String saved = Files.readString(path);
                        for (var invalid : List.of(
                                        new MinigameAgent.Control("running", match, List.of(first), 5),
                                        new MinigameAgent.Control("running", match, List.of(first, second), 4),
                                        new MinigameAgent.Control("running", match, List.of(), 0),
                                        new MinigameAgent.Control("running", UUID.randomUUID(), List.of(first), 6),
                                        new MinigameAgent.Control("running", match, List.of(new MinigameAgent.Member(match, "Renamed", "red")), 6),
                                        new MinigameAgent.Control("running", match, List.of(new MinigameAgent.Member(match, "Player", "blue")), 6))) {
                                agent.apply(invalid, Set.of(), command -> fail("Native dispatch"));
                                assertEquals(5, reportVersion(agent));
                                assertEquals("running", status(agent));
                                assertTrue(agent.allows(first.uuid()));
                                assertTrue(agent.allows(second.uuid()));
                                assertEquals(saved, Files.readString(path));
                        }
                        agent.apply(new MinigameAgent.Control("starting", match, List.of(second, first), 5), Set.of(),
                                        command -> fail("No repeated round dispatch"));
                        assertEquals(saved, Files.readString(path));
                        agent.apply(new MinigameAgent.Control("running", match, List.of(first), 6), Set.of(),
                                        command -> fail("Native dispatch"));
                        assertEquals(6, reportVersion(agent));
                        assertFalse(agent.allows(second.uuid()));
                }
                try (var restored = new MinigameAgent(null, config, path)) {
                        restored.ready();
                        assertTrue(restored.allows(first.uuid()));
                        assertFalse(restored.allows(second.uuid()));
                        assertEquals(6, reportVersion(restored));
                }
        }

        @Test
        void permanentLogManualAndProxyEndRemainActiveAndInformational() throws Exception {
                var config = MinigameAgent.configuration(permanentConfig());
                Logger logger = Logger.getLogger("vestri-permanent-end-" + UUID.randomUUID());
                logger.setUseParentHandlers(false);
                var logs = capture(logger);
                Path path = data.resolve("permanent-end.json");
                try (var agent = new MinigameAgent(null, config, path, new MinigameAgent.Diagnostics(logger, System::nanoTime))) {
                        agent.end();
                        assertEquals("joining", status(agent));
                        agent.ready();
                        agent.end();
                        assertEquals("ready", status(agent));
                        var roster = List.of(new MinigameAgent.Member(match, "Player", "red"));
                        agent.apply(new MinigameAgent.Control("starting", match, roster, 1), Set.of(),
                                        command -> fail("Permanent commands"));
                        String saved = Files.readString(path);
                        agent.observeLog("Game finished");
                        agent.apply(null, Set.of(), command -> fail("Log dispatch"));
                        assertEquals("running", status(agent));
                        agent.end();
                        agent.apply(new MinigameAgent.Control("ending", match, roster, 1), Set.of(),
                                        command -> fail("End dispatch"));
                        agent.apply(new MinigameAgent.Control("ending", match, null, 1), Set.of(),
                                        command -> fail("End dispatch"));
                        assertEquals("running", status(agent));
                        assertTrue(agent.allows(match));
                        assertEquals(saved, Files.readString(path));
                        var endLogs = logs.stream().filter(log -> log.getMessage().contains("end ignored")).toList();
                        assertEquals(4, endLogs.size());
                        for (var log : endLogs) {
                                assertEquals(java.util.logging.Level.INFO, log.getLevel());
                                assertTrue(log.getMessage().contains("end ignored"));
                                assertFalse(log.getMessage().contains(config.token()));
                        }
                }
        }

        @Test
        void permanentLifecycleIsStrictAndOptionalTeamsRemainSupported() {
                String text = permanentConfig();
                assertEquals("temporary", MinigameAgent.configuration(config("http://vestri-proxy-123:25580")).instanceMode());
                assertEquals("permanent", MinigameAgent.configuration(text).instanceMode());
                for (String invalid : List.of("null", "true", "1", "\"\"", "\"persistent\"")) {
                        assertThrows(IllegalArgumentException.class, () -> MinigameAgent.configuration(
                                        text.replace("\"instanceMode\":\"permanent\"", "\"instanceMode\":" + invalid)));
                }
                for (String invalid : List.of(
                                text.replace("\"lobbyMode\":\"plugin\",", ""),
                                text.replace("\"lobbyMode\":\"plugin\"", "\"lobbyMode\":\"vestri\""),
                                text.replace("\"deleteServer\":false", "\"deleteServer\":true"),
                                text.replace("\"whenEmpty\":false", "\"whenEmpty\":true"),
                                text.replace("\"deleteServer\":false,", ""),
                                text.replace(",\"whenEmpty\":false", ""),
                                text.replace("\"whenEmpty\":false", "\"whenEmpty\":\"false\""),
                                text.replace("\"whenEmpty\":false", "\"whenEmpty\":null"),
                                text.replace("\"whenEmpty\":false", "\"whenEmpty\":false,\"logPattern\":\"\""),
                                text.replace("\"whenEmpty\":false", "\"whenEmpty\":false,\"logPattern\":\"finished\""),
                                text.replace("\"onAllJoined\":[]", "\"onAllJoined\":[{\"command\":\"restart\"}]"))) {
                        assertThrows(IllegalArgumentException.class, () -> MinigameAgent.configuration(invalid));
                }
                for (String teams : List.of("\"teams\":[],", "\"teams\":null,", "")) {
                        var parsed = MinigameAgent.configuration(text.replace("\"teams\":[\"red\",\"blue\"],", teams));
                        assertTrue(parsed.teams().isEmpty());
                        assertNull(parsed.endPattern());
                }
        }

        @Test
        void permanentFenceBindsCredentialsAndCannotBeReinterpretedAsTemporary() throws Exception {
                var config = MinigameAgent.configuration(permanentConfig());
                Path path = data.resolve("permanent-credentials.json");
                try (var agent = new MinigameAgent(null, config, path)) {
                        agent.apply(new MinigameAgent.Control("running", match, List.of(), 1), Set.of(),
                                        command -> fail("Native dispatch"));
                }
                assertFalse(Files.readString(path).contains(config.token()));
                for (String changed : List.of(permanentConfig().replace("a".repeat(64), "b".repeat(64)),
                                permanentConfig().replace("mcgame-123", "mcgame-456"),
                                permanentConfig().replace("vestri-proxy-123", "vestri-proxy-456"),
                                permanentConfig().replace("\"instanceMode\":\"permanent\"", "\"instanceMode\":\"temporary\""))) {
                        assertThrows(IllegalArgumentException.class, () -> new MinigameAgent(null,
                                        MinigameAgent.configuration(changed), path));
                }
                var saved = com.google.gson.JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                saved.remove("credentialBinding");
                Files.writeString(path, saved.toString());
                assertThrows(IllegalArgumentException.class, () -> new MinigameAgent(null, config, path));
        }

        @Test
        void permanentPersistenceFailureClosesAdmissionWithoutAcknowledgingNewRoster() throws Exception {
                var config = MinigameAgent.configuration(permanentConfig());
                Path path = data.resolve("permanent-write-failure.json");
                try (var agent = new MinigameAgent(null, config, path)) {
                        agent.ready();
                        agent.apply(new MinigameAgent.Control("running", match,
                                        List.of(new MinigameAgent.Member(match, "Player", "red")), 1), Set.of(),
                                        command -> fail("Native dispatch"));
                        Files.delete(path);
                        Files.createDirectory(path);
                        agent.apply(new MinigameAgent.Control("running", match, List.of(), 2), Set.of(),
                                        command -> fail("Native dispatch"));
                        assertEquals("failed", status(agent));
                        assertEquals(1, reportVersion(agent));
                        assertFalse(agent.allows(match));
                        agent.end();
                        agent.ready();
                        assertEquals("failed", status(agent));
                }
        }

        private String permanentConfig() {
                return config("http://vestri-proxy-123:25580")
                                .replace("\"teams\":", "\"instanceMode\":\"permanent\",\"lobbyMode\":\"plugin\",\"teams\":")
                                .replace("[{\"command\":\"start {matchId}\"}]", "[]")
                                .replace("\"logPattern\":\"finished\"", "\"deleteServer\":false,\"whenEmpty\":false");
        }

        private int reportVersion(MinigameAgent agent) {
                return com.google.gson.JsonParser.parseString(agent.statusReport(Set.of())).getAsJsonObject().get("rosterVersion").getAsInt();
        }

        @Test
    void boundsResponseBytesAcrossChunksAndCancelsOverflow() {
        var body = new MinigameAgent.BoundedBody(4);
        AtomicBoolean cancelled = new AtomicBoolean();
        body.onSubscribe(new Flow.Subscription() {
            public void request(long count) { }
            public void cancel() { cancelled.set(true); }
        });
        body.onNext(List.of(ByteBuffer.wrap(new byte[]{1, 2}), ByteBuffer.wrap(new byte[]{3, 4})));
        body.onComplete();
        assertArrayEquals(new byte[]{1, 2, 3, 4}, body.getBody().toCompletableFuture().join());
        assertFalse(cancelled.get());
        var oversized = new MinigameAgent.BoundedBody(1);
        oversized.onSubscribe(new Flow.Subscription() {
            public void request(long count) { }
            public void cancel() { cancelled.set(true); }
        });
        oversized.onNext(List.of(ByteBuffer.wrap(new byte[]{1, 2})));
        assertTrue(cancelled.get());
        assertTrue(oversized.getBody().toCompletableFuture().isCompletedExceptionally());
    }

    @Test
    void boundsExpandedActionCountBeforeDispatch() {
        var action = new MinigameAgent.Action("team {team}", "team");
        assertThrows(IllegalArgumentException.class, () -> MinigameAgent.expand(
                java.util.Collections.nCopies(17, action), java.util.Collections.nCopies(256, "red"), List.of(), match));
    }

        @Test
        void commandFalseOrThrowStopsRemainingActions() {
                List<String> dispatched = new ArrayList<>();
                assertFalse(MinigameAgent.dispatch(List.of("first", "fail", "last"), command -> {
                        dispatched.add(command);
                        return !command.equals("fail");
                }));
                assertEquals(List.of("first", "fail"), dispatched);
                dispatched.clear();
                assertThrows(IllegalStateException.class, () -> MinigameAgent.dispatch(List.of("first", "fail", "last"), command -> {
                        dispatched.add(command);
                        if (command.equals("fail")) {
                                throw new IllegalStateException();
                        }
                        return true;
                }));
                assertEquals(List.of("first", "fail"), dispatched);
                assertTrue(MinigameAgent.dispatch(List.of(), command -> false));
        }

        @Test
        void diagnosticsRateLimitAcrossFailureKindsAndLogRecoveryOnce() {
                Logger logger = Logger.getLogger("vestri-test-" + UUID.randomUUID());
                logger.setUseParentHandlers(false);
                List<LogRecord> logs = capture(logger);
                AtomicLong now = new AtomicLong();
                var diagnostics = new MinigameAgent.Diagnostics(logger, now::get);
                diagnostics.failure("HTTP status=503");
                diagnostics.failure("invalid response class=IllegalArgumentException");
                assertEquals(1, logs.size());
                now.set(TimeUnit.SECONDS.toNanos(29));
                diagnostics.failure("transport class=TimeoutException");
                assertEquals(1, logs.size());
                now.set(TimeUnit.SECONDS.toNanos(30));
                diagnostics.failure("transport class=TimeoutException");
                assertEquals(2, logs.size());
                diagnostics.recovered();
                diagnostics.recovered();
                assertEquals(3, logs.size());
                assertEquals("Minigame control recovered.", logs.get(2).getMessage());
                diagnostics.failure("HTTP status=401");
                assertEquals(3, logs.size());
                now.set(TimeUnit.SECONDS.toNanos(60));
                diagnostics.failure("HTTP status=401");
                assertEquals(4, logs.size());
                String message = MinigameAgent.loadedMessage(MinigameAgent.configuration(config("http://vestri-proxy-123:25580")));
                assertEquals("Minigame agent configured: slug=mcgame-123, lobbyMode=vestri, readiness=explicit", message);
                assertFalse(message.contains("a".repeat(64)));
                assertFalse(message.contains("http"));
        }

        @Test
        void realPollingWarnsSafelyOnStatusAndMalformedResponseThenRecovers() throws Exception {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                AtomicInteger responseCode = new AtomicInteger(503);
                var body = new java.util.concurrent.atomic.AtomicReference<>("secret invalid body");
                var authorization = new java.util.concurrent.atomic.AtomicReference<String>();
                server.createContext("/control", exchange -> {
                        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                        exchange.getRequestBody().readAllBytes();
                        byte[] bytes = body.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(responseCode.get(), bytes.length);
                        exchange.getResponseBody().write(bytes);
                        exchange.close();
                });
                server.start();
                Logger logger = Logger.getLogger("vestri-http-test-" + UUID.randomUUID());
                logger.setUseParentHandlers(false);
                List<LogRecord> logs = capture(logger);
                AtomicLong now = new AtomicLong();
                var config = new MinigameAgent.Configuration(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/control"),
                                "a".repeat(64), List.of(), "explicit", List.of(), null);
                try (var agent = new MinigameAgent(null, config, data.resolve("http.json"), new MinigameAgent.Diagnostics(logger, now::get))) {
                        assertNull(agent.poll(agent.statusReport(Set.of())));
                        assertEquals("Bearer " + config.token(), authorization.get());
                        assertTrue(logs.get(0).getMessage().contains("status=503"));
                        responseCode.set(200);
                        assertNull(agent.poll(agent.statusReport(Set.of())));
                        assertEquals(1, logs.size());
                        now.set(TimeUnit.SECONDS.toNanos(30));
                        assertNull(agent.poll(agent.statusReport(Set.of())));
                        assertEquals(2, logs.size());
                        assertTrue(logs.get(1).getMessage().contains("invalid response class="));
                        body.set("{\"state\":\"ready\"}");
                        assertEquals("ready", agent.poll(agent.statusReport(Set.of())).state());
                        assertEquals("ready", agent.poll(agent.statusReport(Set.of())).state());
                        assertEquals(3, logs.size());
                        assertEquals("Minigame control recovered.", logs.get(2).getMessage());
                        for (LogRecord record : logs) {
                                assertFalse(record.getMessage().contains(config.token()));
                                assertFalse(record.getMessage().contains("http://"));
                                assertFalse(record.getMessage().contains("secret"));
                                assertNull(record.getThrown());
                        }
                } finally {
                        server.stop(0);
                }
        }

        private List<LogRecord> capture(Logger logger) {
                List<LogRecord> logs = new java.util.concurrent.CopyOnWriteArrayList<>();
                logger.addHandler(new Handler() {
                        public void publish(LogRecord record) { logs.add(record); }
                        public void flush() { }
                        public void close() { }
                });
                return logs;
        }

        @Test
        void realPollDeadlinesCoverDelayedHeadersAndStalledBodyAndRecover() throws Exception {
                for (boolean sendHeaders : List.of(false, true)) {
                        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                        CountDownLatch release = new CountDownLatch(1);
                        CountDownLatch entered = new CountDownLatch(1);
                        AtomicBoolean stall = new AtomicBoolean(true);
                        server.createContext("/control", exchange -> {
                                exchange.getRequestBody().readAllBytes();
                                if (stall.get()) {
                                        if (sendHeaders) {
                                                exchange.sendResponseHeaders(200, 100);
                                                exchange.getResponseBody().write('{');
                                                exchange.getResponseBody().flush();
                                        }
                                        entered.countDown();
                                        try {
                                                release.await(10, TimeUnit.SECONDS);
                                        } catch (InterruptedException failure) {
                                                Thread.currentThread().interrupt();
                                        } finally {
                                                exchange.close();
                                        }
                                        return;
                                }
                                byte[] body = "{\"state\":\"ready\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                                exchange.sendResponseHeaders(200, body.length);
                                exchange.getResponseBody().write(body);
                                exchange.close();
                        });
                        server.start();
                        Logger logger = Logger.getLogger("vestri-timeout-test-" + UUID.randomUUID());
                        logger.setUseParentHandlers(false);
                        List<LogRecord> logs = capture(logger);
                        var config = new MinigameAgent.Configuration(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/control"),
                                        "a".repeat(64), List.of(), "explicit", List.of(), null);
                        try (var agent = new MinigameAgent(null, config, data.resolve("timeout-" + sendHeaders + ".json"),
                                        new MinigameAgent.Diagnostics(logger, System::nanoTime))) {
                                long started = System.nanoTime();
                                var pending = java.util.concurrent.CompletableFuture.supplyAsync(() -> agent.poll(agent.statusReport(Set.of())));
                                assertTrue(entered.await(2, TimeUnit.SECONDS));
                                assertNull(pending.get(6, TimeUnit.SECONDS));
                                assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(6));
                                assertEquals(1, logs.size());
                                assertTrue(logs.get(0).getMessage().contains("transport class="));
                                assertTrue(logs.get(0).getMessage().contains("TimeoutException"));
                                assertFalse(logs.get(0).getMessage().contains(config.token()));
                                assertFalse(logs.get(0).getMessage().contains("http://"));
                                stall.set(false);
                                release.countDown();
                                assertEquals("ready", agent.poll(agent.statusReport(Set.of())).state());
                                assertEquals(2, logs.size());
                                assertEquals("Minigame control recovered.", logs.get(1).getMessage());
                        } finally {
                                release.countDown();
                                server.stop(0);
                        }
                }
        }

        @Test
        void fullBodyDeadlineAndSubsequentExchangeWorkWithoutJdkMocks() throws Exception {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                CountDownLatch release = new CountDownLatch(1);
                server.createContext("/stall", exchange -> {
                        exchange.sendResponseHeaders(200, 10);
                        exchange.getResponseBody().write(1);
                        exchange.getResponseBody().flush();
                        try {
                                release.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException failure) {
                                Thread.currentThread().interrupt();
                        } finally {
                                exchange.close();
                        }
                });
                server.createContext("/ok", exchange -> {
                        byte[] body = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, body.length);
                        exchange.getResponseBody().write(body);
                        exchange.close();
                });
                server.start();
                try {
                        HttpClient client = HttpClient.newHttpClient();
                        String base = "http://127.0.0.1:" + server.getAddress().getPort();
                        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/stall")).build();
                        assertThrows(TimeoutException.class, () -> MinigameAgent.exchange(client, request, Duration.ofMillis(250)));
                        release.countDown();
                        assertEquals(200, MinigameAgent.exchange(client,
                                        HttpRequest.newBuilder(URI.create(base + "/ok")).build(), Duration.ofSeconds(3)).statusCode());
                } finally {
                        release.countDown();
                        server.stop(0);
                }
        }

    private String config(String url) {
        return "{\"slug\":\"mcgame-123\",\"token\":\"" + "a".repeat(64) + "\",\"controlUrl\":\"" + url
                + "\",\"spec\":{\"teams\":[\"red\",\"blue\"],\"lifecycle\":{"
                + "\"ready\":{\"trigger\":\"explicit\"},\"onAllJoined\":[{\"command\":\"start {matchId}\"}],"
                + "\"end\":{\"loggerName\":\"Game\",\"logPattern\":\"finished\"}}}}";
    }
}