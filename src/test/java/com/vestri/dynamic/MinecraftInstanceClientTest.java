package com.vestri.dynamic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MinecraftInstanceClientTest {
    @TempDir
    Path directory;
    private final String key = "vmc_" + "a".repeat(64);
    private final UUID instance = UUID.randomUUID();
    private final List<String> calls = new ArrayList<>();
    private HttpServer server;
    private MinecraftInstanceClient client;
    private String listed;
    private String spawnBody;
    private String spawnSlug;
    private int responseStatus;
    private int transientSpawnFailures;
    private String errorBody;
    private String preparationResponse = "{\"instance\":null}";
    private final List<Long> retryDelays = new ArrayList<>();
    private final List<String> spawnRequests = new ArrayList<>();

    @Test
    void generatesSafeStarterConfigurationWithoutOverwritingExistingFiles() throws Exception {
        Path config = directory.resolve("config.json");
        assertTrue(MinecraftInstanceClient.createDefaultConfiguration(config));
        String original = Files.readString(config);
        var parsed = JsonParser.parseString(original).getAsJsonObject();
        assertEquals("https://panel.example.com", parsed.get("backendUrl").getAsString());
        assertEquals(0, parsed.getAsJsonObject("templates").size());
        assertTrue(parsed.getAsJsonObject("consoleBridge").get("secret").getAsString().matches("[a-f0-9]{64}"));
        assertEquals(0, parsed.getAsJsonObject("consoleBridge").getAsJsonArray("allowedServers").size());
        assertFalse(MinecraftInstanceClient.createDefaultConfiguration(config));
        assertEquals(original, Files.readString(config));
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(config));
        }
    }

    @Test
    void loadingMissingConfigGeneratesItAndExplainsSetup() {
        Path config = directory.resolve("missing.json");
        IOException failure = assertThrows(IOException.class, () -> MinecraftInstanceClient.load(config));
        assertTrue(Files.exists(config));
        assertTrue(failure.getMessage().contains("Created"));
    }

    @Test
    void configErrorsExplainTheProblemWithoutLeakingKeysOrUrlCredentials() throws Exception {
        Path config = directory.resolve("invalid.json");
        for (String invalid : List.of("{", "{}", "{\"backendUrl\":\"https://panel.example.com\",\"templates\":[]}",
                "{\"backendUrl\":\"https://panel.example.com\",\"templates\":{\"BentoBox\":\"vmc_SECRET_PLACEHOLDER\"}}",
                "{\"backendUrl\":\"https://user:SECRET_PASSWORD@panel.example.com\",\"templates\":{}}")) {
            Files.writeString(config, invalid);
            IOException failure = assertThrows(IOException.class, () -> MinecraftInstanceClient.load(config));
            assertTrue(failure.getMessage().contains("Invalid plugin configuration"));
            assertFalse(failure.getMessage().contains("SECRET"));
        }
    }

    @Test
    void loadsFrontendOriginConfigurationAndPreservesApiPathAndBearerKey() throws Exception {
        Path config = directory.resolve("frontend.json");
        Files.writeString(config, "{\"backendUrl\":\"http://127.0.0.1:" + server.getAddress().getPort()
                + "/\",\"templates\":{\"BentoBox\":\" " + key + " \"}}");
        assertEquals(instance.toString(), MinecraftInstanceClient.load(config).spawn("BentoBox", UUID.randomUUID()));
        assertEquals(List.of("POST /api/minecraft/v1/instances"), calls);
    }

    @BeforeEach
    void startServer() throws Exception {
        responseStatus = 200;
        spawnSlug = "mcgame-" + "b".repeat(32);
        listed = "{\"instances\":[{\"serverId\":\"" + instance + "\"}]}";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/minecraft/v1/instances", exchange -> {
            assertEquals("Bearer " + key, exchange.getRequestHeaders().getFirst("Authorization"));
            calls.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            String body = exchange.getRequestURI().getPath().contains("/requests/") ? preparationResponse : listed;
            if ("POST".equals(exchange.getRequestMethod())) {
                spawnBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                spawnRequests.add(spawnBody);
                body = "{\"serverId\":\"" + instance + "\",\"slug\":\"" + spawnSlug + "\",\"state\":\"starting\"}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            if ("DELETE".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
            } else {
                int statusCode = exchange.getRequestURI().getPath().equals("/api/minecraft/v1/instances")
                    && "POST".equals(exchange.getRequestMethod()) && transientSpawnFailures-- > 0 ? 502 : responseStatus;
                if (statusCode >= 400 && errorBody != null) {
                    bytes = errorBody.getBytes(StandardCharsets.UTF_8);
                }
                exchange.sendResponseHeaders(statusCode, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        client = new MinecraftInstanceClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
            Map.of("Bed Wars", key), retryDelays::add);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void snapshotPinsOnlyOriginalTemplateAndOriginAcrossRotationAndMutation() throws Exception {
        Path config = directory.resolve("snapshot.json");
        Files.writeString(config, "{\"backendUrl\":\"http://127.0.0.1:" + server.getAddress().getPort()
                + "\",\"templates\":{\"Bed Wars\":\"" + key + "\",\"Other\":\"vmc_" + "c".repeat(64)
                + "\"},\"consoleBridge\":{\"secret\":\"not-captured\"}}");
        var snapshot = MinecraftInstanceClient.snapshot(config, "Bed Wars");
        assertEquals(Set.of("backendUrl", "templates"), snapshot.keySet());
        assertEquals(Set.of("Bed Wars"), snapshot.getAsJsonObject("templates").keySet());
        MinecraftInstanceClient captured = new MinecraftInstanceClient(snapshot);
        Files.writeString(config, "{\"backendUrl\":\"https://changed.example.com\",\"templates\":{\"Bed Wars\":\"vmc_"
                + "d".repeat(64) + "\"}}");
        assertEquals(instance.toString(), new MinecraftInstanceClient(snapshot).spawn("Bed Wars", UUID.randomUUID()));
        snapshot.addProperty("backendUrl", "https://changed.example.com");
        snapshot.getAsJsonObject("templates").addProperty("Bed Wars", "vmc_" + "d".repeat(64));
        Files.writeString(config, "{");
        assertEquals(instance.toString(), captured.spawn("Bed Wars", UUID.randomUUID()));
        assertEquals(2, calls.size());
    }

    @Test
    void spawnsWithStableRequestIdAndScopedBearerKey() throws Exception {
        UUID request = UUID.randomUUID();
        assertEquals(instance.toString(), client.spawn("BED WARS", request));
        assertEquals(request.toString(), JsonParser.parseString(spawnBody).getAsJsonObject().get("requestId").getAsString());
        assertEquals(List.of("POST /api/minecraft/v1/instances"), calls);
    }

    @Test
    void variantIsOptionalAndPreservedAcrossGatewayRetries() throws Exception {
        client.spawnWithTarget("Bed Wars", UUID.randomUUID());
        assertFalse(JsonParser.parseString(spawnBody).getAsJsonObject().has("variant"));
        transientSpawnFailures = 1;
        UUID request = UUID.randomUUID();
        client.spawnWithTarget("Bed Wars", request, "4v4v4v4");
        assertEquals(spawnRequests.get(1), spawnRequests.get(2));
        var body = JsonParser.parseString(spawnBody).getAsJsonObject();
        assertEquals("4v4v4v4", body.get("variant").getAsString());
        assertEquals(request.toString(), body.get("requestId").getAsString());
        assertEquals(Set.of("requestId", "variant"), body.keySet());
        responseStatus = 409;
        assertThrows(IOException.class, () -> client.spawnWithTarget("Bed Wars", request, "other"));
        assertEquals(4, spawnRequests.size());
    }

    @Test
    void automaticallyRecoversGatewayFailureUsingExactlyTheSameRequest() throws Exception {
        transientSpawnFailures = 1;
        UUID requestId = UUID.randomUUID();
        assertEquals(instance.toString(), client.spawnWithTarget("Bed Wars", requestId).serverId());
        assertEquals(2, spawnRequests.size());
        assertEquals(spawnRequests.get(0), spawnRequests.get(1));
        assertEquals(requestId.toString(), JsonParser.parseString(spawnRequests.get(1)).getAsJsonObject().get("requestId").getAsString());
        assertEquals(List.of(5000L), retryDelays);
    }

    @Test
    void gatewayRecoveryIsBoundedAndDoesNotRetryAuthorizationFailures() {
        responseStatus = 502;
        assertThrows(IOException.class, () -> client.spawn("Bed Wars", UUID.randomUUID()));
        assertEquals(3, spawnRequests.size());
        assertEquals(1, spawnRequests.stream().distinct().count());
        assertEquals(List.of(5000L, 10000L), retryDelays);
        responseStatus = 401;
        assertThrows(IOException.class, () -> client.spawn("Bed Wars", UUID.randomUUID()));
        assertEquals(4, spawnRequests.size());
        assertEquals(List.of(5000L, 10000L), retryDelays);
    }

    @Test
    void waitsForBackendRecoveryBeforeRetryingTheSameRequest() throws Exception {
        responseStatus = 502;
        client = new MinecraftInstanceClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                Map.of("Bed Wars", key), milliseconds -> {
                    retryDelays.add(milliseconds);
                    responseStatus = 200;
                });
        assertEquals(instance.toString(), client.spawnWithTarget("Bed Wars", UUID.randomUUID()).serverId());
        assertEquals(2, spawnRequests.size());
        assertEquals(1, spawnRequests.stream().distinct().count());
        assertEquals(List.of(5000L), retryDelays);
    }

    @Test
    void interruptionDuringBackoffStopsFurtherRequests() {
        responseStatus = 503;
        client = new MinecraftInstanceClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                Map.of("Bed Wars", key), milliseconds -> { throw new InterruptedException(); });
        assertThrows(InterruptedException.class, () -> client.spawn("Bed Wars", UUID.randomUUID()));
        assertEquals(1, spawnRequests.size());
    }

    @Test
    void reportsKnownFailureStageWithoutExposingArbitraryResponseBodies() {
        responseStatus = 502;
        errorBody = "{\"message\":\"Failed to copy server template\",\"secret\":\"" + key + "\"}";
        IOException failure = assertThrows(IOException.class, () -> client.spawn("Bed Wars", UUID.randomUUID()));
        assertTrue(failure.getMessage().contains("Failed to copy server template"));
        assertFalse(failure.getMessage().contains(key));
        for (String body : List.of("<html>SECRET</html>", "{}", "{\"message\":\"SECRET " + key + "\"}")) {
            errorBody = body;
            failure = assertThrows(IOException.class, () -> client.spawn("Bed Wars", UUID.randomUUID()));
            assertTrue(failure.getMessage().contains("HTTP 502"));
            assertFalse(failure.getMessage().contains("SECRET"));
            assertFalse(failure.getMessage().contains(key));
        }
    }

    @Test
    void usesReturnedSlugRatherThanDerivingItFromServerId() throws Exception {
        var result = client.spawnWithTarget("Bed Wars", UUID.randomUUID());
        assertEquals(instance.toString(), result.serverId());
        assertEquals(spawnSlug, result.slug());
        spawnSlug = "lobby";
        IOException failure = assertThrows(IOException.class, () -> client.spawnWithTarget("Bed Wars", UUID.randomUUID()));
        assertTrue(failure.getMessage().contains(instance.toString()));
    }

    @Test
    void looksUpPreparationWithoutStartingAnotherInstance() throws Exception {
        UUID requestId = UUID.randomUUID();
        assertEquals(null, client.findPreparation("Bed Wars", requestId));
        preparationResponse = "{\"instance\":{\"serverId\":\"" + instance + "\",\"slug\":\"" + spawnSlug + "\"}}";
        var target = client.findPreparation("Bed Wars", requestId);
        assertEquals(instance.toString(), target.serverId());
        assertEquals(spawnSlug, target.slug());
        assertEquals(List.of("GET /api/minecraft/v1/instances/requests/" + requestId,
                "GET /api/minecraft/v1/instances/requests/" + requestId), calls);
        assertTrue(spawnRequests.isEmpty());
    }

    @Test
    void failedOrUnsupportedPreparationLookupIsNotTreatedAsMissing() {
        UUID requestId = UUID.randomUUID();
        for (String invalid : List.of("{}", "{\"instance\":{}}", "{\"instance\":false}")) {
            preparationResponse = invalid;
            assertThrows(IOException.class, () -> client.findPreparation("Bed Wars", requestId));
        }
        responseStatus = 404;
        assertThrows(IOException.class, () -> client.findPreparation("Bed Wars", requestId));
        responseStatus = 503;
        assertThrows(IOException.class, () -> client.findPreparation("Bed Wars", requestId));
        assertTrue(spawnRequests.isEmpty());
    }

    @Test
    void deletesOnlySingleOwnedInstance() throws Exception {
        assertEquals(instance.toString(), client.delete("bed wars", null));
        assertEquals(List.of("GET /api/minecraft/v1/instances", "DELETE /api/minecraft/v1/instances/" + instance), calls);
    }

    @Test
    void refusesAmbiguousMissingAndUnownedInstances() {
        listed = "{\"instances\":[]}";
        assertThrows(IOException.class, () -> client.delete("bed wars", null));
        listed = "{\"instances\":[{\"serverId\":\"" + instance + "\"},{\"serverId\":\"" + UUID.randomUUID() + "\"}]}";
        assertThrows(IOException.class, () -> client.delete("bed wars", null));
        assertThrows(IOException.class, () -> client.delete("bed wars", UUID.randomUUID()));
        assertEquals(3, calls.size());
    }

    @Test
    void allowsExplicitOwnedInstanceSelection() throws Exception {
        listed = "{\"instances\":[{\"serverId\":\"" + instance + "\"},{\"serverId\":\"" + UUID.randomUUID() + "\"}]}";
        assertEquals(instance.toString(), client.delete("bed wars", instance));
    }

    @Test
    void rejectsUnknownNamesAndApiFailures() {
        assertThrows(IOException.class, () -> client.spawn("other", UUID.randomUUID()));
        assertEquals(0, calls.size());
        responseStatus = 401;
        assertThrows(IOException.class, () -> client.instances("bed wars"));
    }

    @Test
    void powersInstancesThroughScopedEndpoints() throws Exception {
        client.power("Bed Wars", instance, false);
        client.power("Bed Wars", instance, true);
        assertEquals(List.of("POST /api/minecraft/v1/instances/" + instance + "/stop",
                "POST /api/minecraft/v1/instances/" + instance + "/start"), calls);
    }

    @Test
    void rejectsRemotePlaintextAndCredentialBearingUrls() {
        for (String url : List.of("http://backend.example", "https://user:password@backend.example", "https://backend.example?key=secret")) {
            assertThrows(IllegalArgumentException.class, () -> new MinecraftInstanceClient(URI.create(url), Map.of("test", key)));
        }
    }
}