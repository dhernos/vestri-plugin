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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MinigameAgentTest {
        @TempDir Path data;
    private final UUID match = UUID.fromString("12345678-1234-1234-1234-123456789abc");

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
                        agent.observeLog("previous Game finished");
                        agent.apply(null, Set.of(), command -> true);
                        assertEquals("joining", status(agent));
                        agent.ready();
                        agent.observeLog("Game finished");
                        agent.apply(starting, Set.of(), command -> true);
                        assertEquals("joining", status(agent));
                        agent.observeLog("Game finished");
                        agent.apply(starting, Set.of(match), command -> {
                                agent.observeLog("Game finished");
                                return true;
                        });
                        agent.apply(null, Set.of(match), command -> true);
                        assertEquals("running", status(agent));
                        agent.observeLog("unrelated");
                        agent.apply(null, Set.of(match), command -> true);
                        assertEquals("running", status(agent));
                        agent.observeLog("Game finished");
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
                assertThrows(IllegalArgumentException.class, () -> MinigameAgent.configuration(text.replace("[\"red\",\"blue\"]", "[]")));
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
                + "\"end\":{\"logPattern\":\"finished\"}}}}";
    }
}