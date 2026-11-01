package com.vestri.dynamic;

import com.google.gson.JsonArray;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.HexFormat;

final class MinecraftInstanceClient {
    private final URI endpoint;
    private final Map<String, String> templateKeys;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();

    MinecraftInstanceClient(URI backend, Map<String, String> keys) {
        String host = backend.getHost();
        boolean local = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
                || "[::1]".equals(host) || "::1".equals(host);
        if (host == null || backend.getUserInfo() != null || backend.getQuery() != null
                || backend.getFragment() != null || !("https".equals(backend.getScheme())
                || ("http".equals(backend.getScheme()) && local))) {
            throw new IllegalArgumentException("backendUrl must use HTTPS (HTTP is allowed only on loopback)");
        }
        endpoint = URI.create(backend.toString().replaceAll("/+$", "") + "/api/minecraft/v1/instances");
        templateKeys = new LinkedHashMap<>();
        keys.forEach((name, key) -> {
            String normalized = normalizeName(name);
            if (normalized.isEmpty() || !key.matches("vmc_[a-fA-F0-9]{64}")
                    || templateKeys.putIfAbsent(normalized, key) != null) {
                throw new IllegalArgumentException("Each template must have a unique non-empty name and a Minecraft API key: vmc_ followed by 64 hexadecimal characters (no placeholder text)");
            }
        });
        if (templateKeys.isEmpty()) {
            throw new IllegalArgumentException("Add at least one saved-template Minecraft API key to templates");
        }
    }

    static boolean createDefaultConfiguration(Path file) throws IOException {
        JsonObject config = new JsonObject();
        config.addProperty("backendUrl", "https://panel.example.com");
        config.add("templates", new JsonObject());
        JsonObject players = new JsonObject();
        players.addProperty("enabled", false);
        players.addProperty("maxPermanentPerUser", 1);
        players.addProperty("maxTemporaryPerUser", 2);
        players.addProperty("pauseEnabled", true);
        players.addProperty("maxPausedPerUser", 1);
        players.addProperty("emptyGraceSeconds", 30);
        players.addProperty("deletionGraceSeconds", 300);
        config.add("playerServers", players);
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        JsonObject bridge = new JsonObject();
        bridge.addProperty("secret", HexFormat.of().formatHex(secret));
        bridge.add("allowedServers", new JsonArray());
        config.add("consoleBridge", bridge);
        try {
            if (Files.getFileStore(file.toAbsolutePath().getParent()).supportsFileAttributeView("posix")) {
                Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } else {
                Files.createFile(file);
            }
        } catch (FileAlreadyExistsException existing) {
            return false;
        }
        Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(config) + "\n", StandardCharsets.UTF_8);
        return true;
    }

    static MinecraftInstanceClient load(Path file) throws IOException {
        if (createDefaultConfiguration(file)) {
            throw new IOException("Created " + file.getFileName() + "; set backendUrl to your frontend origin and add template API keys before spawning");
        }
        if (Files.size(file) > 64 * 1024) {
            throw new IOException("Plugin configuration exceeds 64 KiB");
        }
        JsonObject config;
        try {
            config = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException failure) {
            throw new IOException("Invalid plugin configuration: expected a JSON object (check commas, quotes, and braces)");
        }
        if (!config.has("backendUrl") || !config.get("backendUrl").isJsonPrimitive()
                || !config.getAsJsonPrimitive("backendUrl").isString()) {
            throw new IOException("Invalid plugin configuration: backendUrl must be your frontend origin as a string, e.g. https://panel.example.com");
        }
        if (!config.has("templates") || !config.get("templates").isJsonObject()) {
            throw new IOException("Invalid plugin configuration: templates must be an object mapping template names to Minecraft API keys");
        }
        URI backend;
        try {
            backend = URI.create(config.get("backendUrl").getAsString().trim());
        } catch (IllegalArgumentException failure) {
            throw new IOException("Invalid plugin configuration: backendUrl must be a valid frontend origin URL (include https://, omit /en and /api)");
        }
        try {
            Map<String, String> keys = new LinkedHashMap<>();
            for (var entry : config.getAsJsonObject("templates").entrySet()) {
                if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString()) {
                    throw new IllegalArgumentException("Template API keys must be strings");
                }
                keys.put(entry.getKey(), entry.getValue().getAsString().trim());
            }
            return new MinecraftInstanceClient(backend, keys);
        } catch (IllegalArgumentException failure) {
            throw new IOException("Invalid plugin configuration: " + failure.getMessage());
        }
    }

    List<String> templates() {
        return List.copyOf(templateKeys.keySet());
    }

    String spawn(String template, UUID requestId) throws IOException, InterruptedException {
        return UUID.fromString(spawnResponse(template, requestId).get("serverId").getAsString()).toString();
    }

    SpawnedInstance spawnWithTarget(String template, UUID requestId) throws IOException, InterruptedException {
        JsonObject result = spawnResponse(template, requestId);
        String serverId = UUID.fromString(result.get("serverId").getAsString()).toString();
        try {
            return new SpawnedInstance(serverId, result.get("slug").getAsString());
        } catch (RuntimeException failure) {
            throw new IOException("Instance " + serverId + " was spawned, but its connection target is invalid; check the panel before retrying");
        }
    }

    record SpawnedInstance(String serverId, String slug) {
        SpawnedInstance {
            UUID.fromString(serverId);
            if (slug == null || !slug.matches("mcgame-[a-f0-9]{32}")) {
                throw new IllegalArgumentException("Invalid instance slug");
            }
        }
    }

    private JsonObject spawnResponse(String template, UUID requestId) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("requestId", requestId.toString());
        return request(template, "POST", "", body.toString());
    }

    List<String> instances(String template) throws IOException, InterruptedException {
        JsonArray result = request(template, "GET", "", null).getAsJsonArray("instances");
        List<String> ids = new ArrayList<>();
        for (var entry : result) {
            ids.add(UUID.fromString(entry.getAsJsonObject().get("serverId").getAsString()).toString());
        }
        return ids;
    }

    String delete(String template, UUID selected) throws IOException, InterruptedException {
        List<String> ids = instances(template);
        String id;
        if (selected != null) {
            id = selected.toString();
            if (!ids.contains(id)) {
                throw new IOException("Instance is not owned by this template API key");
            }
        } else {
            if (ids.size() != 1) {
                throw new IOException("Expected one instance, found " + ids.size() + "; use list and --instance <UUID>");
            }
            id = ids.get(0);
        }
        request(template, "DELETE", "/" + id, null);
        return id;
    }

    void power(String template, UUID instance, boolean start) throws IOException, InterruptedException {
        request(template, "POST", "/" + instance + (start ? "/start" : "/stop"), "{}");
    }

    void deleteIfPresent(String template, UUID instance) throws IOException, InterruptedException {
        try {
            request(template, "DELETE", "/" + instance, null);
        } catch (APIException failure) {
            if (failure.status != 404) {
                throw failure;
            }
        }
    }

    private static final class APIException extends IOException {
        private final int status;

        APIException(int status) {
            super("Vestri API returned HTTP " + status + "; check key expiry, template limits, and backend logs");
            this.status = status;
        }
    }

    private JsonObject request(String template, String method, String suffix, String body)
            throws IOException, InterruptedException {
        String key = templateKeys.get(normalizeName(template));
        if (key == null) {
            throw new IOException("Unknown configured template; use vestridynamic templates");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint + suffix))
                .timeout(Duration.ofMinutes(6)).header("Authorization", "Bearer " + key)
                .header("Accept", "application/json");
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        HttpResponse<String> response = http.send(builder.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new APIException(response.statusCode());
        }
        if ("DELETE".equals(method)) {
            return new JsonObject();
        }
        try {
            return JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (RuntimeException failure) {
            throw new IOException("Invalid Vestri API response");
        }
    }

    private static String normalizeName(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }
}