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
            String body = listed;
            if ("POST".equals(exchange.getRequestMethod())) {
                spawnBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                body = "{\"serverId\":\"" + instance + "\",\"slug\":\"" + spawnSlug + "\",\"state\":\"starting\"}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            if ("DELETE".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
            } else {
                exchange.sendResponseHeaders(responseStatus, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        client = new MinecraftInstanceClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                Map.of("Bed Wars", key));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void spawnsWithStableRequestIdAndScopedBearerKey() throws Exception {
        UUID request = UUID.randomUUID();
        assertEquals(instance.toString(), client.spawn("BED WARS", request));
        assertEquals(request.toString(), JsonParser.parseString(spawnBody).getAsJsonObject().get("requestId").getAsString());
        assertEquals(List.of("POST /api/minecraft/v1/instances"), calls);
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