package com.vestri.dynamic;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.ResultedEvent.ComponentResult;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.proxy.InboundConnection;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.slf4j.Logger;

final class PlayerManagement {
    private static final Gson JSON = new Gson();
    private static final int MAX_ATTEMPTS = 1000;
    private static final int MAX_PLAYERS = 10000;
    private final Path directory;
    private final Logger logger;
    private final Map<InetSocketAddress, PendingLogin> pending = new LinkedHashMap<>();
    private final Map<String, JsonObject> players = new LinkedHashMap<>();
    private final ArrayDeque<JsonObject> attempts = new ArrayDeque<>();
    private JsonObject policy = defaultPolicy();
    private boolean policyHealthy = true;
    private String policyError;
    private LocalDate lastPruned;

    PlayerManagement(Path directory, Logger logger) throws IOException {
        this.directory = directory;
        this.logger = logger;
        Path journal = directory.resolve("vestri-players.json");
        if (Files.exists(journal)) {
            if (Files.size(journal) > 16 * 1024 * 1024) {
                throw new IOException("Player journal exceeds 16 MiB");
            }
            try {
                JsonObject saved = JsonParser.parseString(Files.readString(journal)).getAsJsonObject();
                for (var entry : saved.getAsJsonArray("players")) {
                    JsonObject player = entry.getAsJsonObject();
                    players.put(UUID.fromString(player.get("uuid").getAsString()).toString(), player);
                }
                for (var entry : saved.getAsJsonArray("attempts")) {
                    attempts.addLast(entry.getAsJsonObject());
                }
                trimHistory();
            } catch (RuntimeException failure) {
                throw new IOException("Invalid player journal; restore or repair it before starting the proxy", failure);
            }
        }
        reloadPolicy();
    }

    private static JsonObject defaultPolicy() {
        JsonObject policy = new JsonObject();
        policy.addProperty("whitelistEnabled", false);
        policy.add("whitelist", new JsonArray());
        policy.add("bans", new JsonArray());
        policy.add("ipBans", new JsonArray());
        return policy;
    }

    synchronized void reloadPolicy() {
        try {
            Path file = directory.resolve("vestri-player-policy.json");
            if (!Files.exists(file)) {
                if (!policy.equals(defaultPolicy())) {
                    throw new IOException("Player policy was removed; retaining the last policy");
                }
                policyHealthy = true;
                policyError = null;
                return;
            }
            if (Files.size(file) > 4 * 1024 * 1024) {
                throw new IOException("Player policy exceeds 4 MiB");
            }
            JsonObject next = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (!next.get("whitelistEnabled").getAsJsonPrimitive().isBoolean()) {
                throw new IOException("Invalid whitelistEnabled");
            }
            for (String list : new String[]{"whitelist", "bans"}) {
                for (var value : next.getAsJsonArray(list)) {
                    JsonObject entry = value.getAsJsonObject();
                    UUID.fromString(entry.get("uuid").getAsString());
                    if (!entry.get("name").getAsString().matches("[A-Za-z0-9_]{3,16}")) {
                        throw new IOException("Invalid player name");
                    }
                }
            }
            for (var value : next.getAsJsonArray("ipBans")) {
                if (!value.getAsString().matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}|[0-9a-fA-F]*:[0-9a-fA-F:.]*")) {
                    throw new IOException("Invalid IP ban");
                }
                java.net.InetAddress.getByName(value.getAsString());
            }
            policy = next;
            policyHealthy = true;
            policyError = null;
        } catch (IOException | RuntimeException failure) {
            if (policyHealthy) {
                logger.error("Player policy unavailable; new logins are denied until the policy is repaired", failure);
            }
            policyHealthy = false;
            policyError = "Player policy is unavailable or invalid";
        }
    }

    synchronized String denial(UUID uuid, String ip) {
        if (!policyHealthy) {
            return "Player access policy is temporarily unavailable.";
        }
        for (var value : policy.getAsJsonArray("ipBans")) {
            if (sameIP(value.getAsString(), ip)) {
                return "Your IP address is banned from this proxy.";
            }
        }
        if (uuid == null) {
            return null;
        }
        if (containsUUID("bans", uuid)) {
            return "You are banned from this proxy.";
        }
        if (policy.get("whitelistEnabled").getAsBoolean() && !containsUUID("whitelist", uuid)) {
            return "You are not whitelisted on this proxy.";
        }
        return null;
    }

    private boolean containsUUID(String list, UUID uuid) {
        for (var value : policy.getAsJsonArray(list)) {
            if (uuid.toString().equalsIgnoreCase(value.getAsJsonObject().get("uuid").getAsString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameIP(String left, String right) {
        try {
            return java.net.InetAddress.getByName(left).equals(java.net.InetAddress.getByName(right));
        } catch (java.net.UnknownHostException failure) {
            return false;
        }
    }

    synchronized void beforeLogin(PreLoginEvent event) {
        reloadPolicy();
        InboundConnection connection = event.getConnection();
        PendingLogin login = new PendingLogin(connection, event.getUsername());
        String denied = denial(null, login.ip());
        if (denied != null) {
            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(Component.text(denied)));
        }
        if (!event.getResult().isAllowed()) {
            record(login, "denied", event.getResult().getReasonComponent()
                    .map(PlainTextComponentSerializer.plainText()::serialize).orElse("Pre-login denied"));
            return;
        }
        if (pending.size() >= MAX_PLAYERS) {
            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(Component.text("Too many pending logins. Please retry.")));
            record(login, "denied", "Pending login limit reached");
            return;
        }
        pending.put(connection.getRemoteAddress(), login);
    }

    synchronized void login(LoginEvent event) {
        reloadPolicy();
        Player player = event.getPlayer();
        PendingLogin login = pending.computeIfAbsent(player.getRemoteAddress(), address -> new PendingLogin(player, player.getUsername()));
        login.uuid = player.getUniqueId();
        login.name = player.getUsername();
        String denied = denial(login.uuid, login.ip());
        if (denied != null) {
            event.setResult(ComponentResult.denied(Component.text(denied)));
        }
        if (!event.getResult().isAllowed()) {
            pending.remove(player.getRemoteAddress());
            record(login, "denied", event.getResult().getReasonComponent()
                    .map(PlainTextComponentSerializer.plainText()::serialize).orElse("Login denied"));
        }
    }

    synchronized void connected(Player player) {
        PendingLogin login = pending.remove(player.getRemoteAddress());
        if (login == null) {
            return;
        }
        login.uuid = player.getUniqueId();
        login.name = player.getUsername();
        record(login, "success", "Connected to proxy and backend");
    }

    synchronized void disconnected(DisconnectEvent event) {
        PendingLogin login = pending.remove(event.getPlayer().getRemoteAddress());
        if (login != null) {
            login.uuid = event.getPlayer().getUniqueId();
            if (!login.failureRecorded) {
                record(login, "failed", "Disconnected before joining: " + event.getLoginStatus().name());
            }
        }
    }

    synchronized void kicked(KickedFromServerEvent event) {
        if (!event.kickedDuringServerConnect()) {
            return;
        }
        Player player = event.getPlayer();
        PendingLogin login = pending.get(player.getRemoteAddress());
        if (login == null) {
            login = new PendingLogin(player, player.getUsername());
        }
        login.uuid = player.getUniqueId();
        record(login, "failed", "Backend " + event.getServer().getServerInfo().getName() + ": "
                + event.getServerKickReason().map(PlainTextComponentSerializer.plainText()::serialize).orElse("Connection rejected"));
        login.failureRecorded = true;
    }

    synchronized void sweep(ProxyServer proxy) {
        reloadPolicy();
        var iterator = pending.values().iterator();
        while (iterator.hasNext()) {
            PendingLogin login = iterator.next();
            if (!login.connection.isActive() || login.time.isBefore(Instant.now().minusSeconds(300))) {
                iterator.remove();
                if (!login.failureRecorded) {
                    record(login, "failed", login.connection.isActive() ? "Login did not complete within 5 minutes" : "Authentication or connection closed before joining");
                }
            }
        }
        if (policyHealthy) {
            for (Player player : proxy.getAllPlayers()) {
                String denied = denial(player.getUniqueId(), player.getRemoteAddress().getAddress().getHostAddress());
                if (denied != null) {
                    player.disconnect(Component.text(denied));
                }
            }
        }
        try {
            flush();
            pruneArchives();
        } catch (IOException failure) {
            logger.error("Could not persist player journal", failure);
        }
    }

    private void record(PendingLogin login, String outcome, String reason) {
        JsonObject attempt = new JsonObject();
        attempt.addProperty("id", UUID.randomUUID().toString());
        attempt.addProperty("name", login.name);
        if (login.uuid != null) {
            attempt.addProperty("uuid", login.uuid.toString());
        }
        attempt.addProperty("ip", login.ip());
        attempt.addProperty("time", login.time.toString());
        attempt.addProperty("completedAt", Instant.now().toString());
        attempt.addProperty("outcome", outcome);
        attempt.addProperty("reason", reason.substring(0, Math.min(reason.length(), 512)));
        attempts.addFirst(attempt);
        if (login.uuid != null) {
            String id = login.uuid.toString();
            JsonObject player = players.remove(id);
            if (player == null) {
                player = new JsonObject();
                player.addProperty("firstSeen", login.time.toString());
            }
            player.addProperty("uuid", id);
            if (!player.has("lastAttempt") || !Instant.parse(player.get("lastAttempt").getAsString()).isAfter(login.time)) {
                player.addProperty("name", login.name);
                player.addProperty("lastAttempt", login.time.toString());
                player.addProperty("lastIP", login.ip());
            }
            if ("success".equals(outcome) && (!player.has("lastLogin") || !Instant.parse(player.get("lastLogin").getAsString()).isAfter(login.time))) {
                player.addProperty("lastLogin", login.time.toString());
                player.addProperty("lastLoginIP", login.ip());
            }
            players.put(id, player);
        }
        trimHistory();
        try {
            Path logs = directory.resolve("vestri-player-logs");
            Files.createDirectories(logs);
            Files.writeString(logs.resolve(LocalDate.now(ZoneOffset.UTC) + ".jsonl"), JSON.toJson(attempt) + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException failure) {
            logger.error("Could not append player login audit event {}", attempt.get("id"), failure);
        }
    }

    private void trimHistory() {
        while (attempts.size() > MAX_ATTEMPTS) {
            attempts.removeLast();
        }
        while (players.size() > MAX_PLAYERS) {
            players.remove(players.keySet().iterator().next());
        }
    }

    synchronized void flush() throws IOException {
        JsonObject journal = new JsonObject();
        journal.addProperty("updatedAt", Instant.now().toString());
        journal.addProperty("policyHealthy", policyHealthy);
        if (policyError != null) {
            journal.addProperty("policyError", policyError);
        }
        JsonArray known = new JsonArray();
        players.values().forEach(known::add);
        journal.add("players", known);
        JsonArray recent = new JsonArray();
        attempts.forEach(recent::add);
        journal.add("attempts", recent);
        Path temporary = directory.resolve("vestri-players.json.tmp");
        Files.writeString(temporary, JSON.toJson(journal) + "\n", StandardCharsets.UTF_8);
        Files.move(temporary, directory.resolve("vestri-players.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    synchronized void shutdown() throws IOException {
        for (PendingLogin login : pending.values()) {
            if (!login.failureRecorded) {
                record(login, "failed", "Proxy shutdown during login");
            }
        }
        pending.clear();
        flush();
    }

    private void pruneArchives() throws IOException {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        if (today.equals(lastPruned)) {
            return;
        }
        Path logs = directory.resolve("vestri-player-logs");
        if (Files.isDirectory(logs)) {
            try (var files = Files.list(logs)) {
                for (Path file : files.toList()) {
                    String name = file.getFileName().toString();
                    if (name.matches("\\d{4}-\\d{2}-\\d{2}\\.jsonl")
                            && LocalDate.parse(name.substring(0, 10)).isBefore(today.minusDays(29))) {
                        Files.delete(file);
                    }
                }
            }
        }
        lastPruned = today;
    }

    private static final class PendingLogin {
        final InboundConnection connection;
        final Instant time = Instant.now();
        String name;
        UUID uuid;
        boolean failureRecorded;

        PendingLogin(InboundConnection connection, String name) {
            this.connection = connection;
            this.name = name;
        }

        String ip() {
            return connection.getRemoteAddress().getAddress().getHostAddress();
        }
    }
}