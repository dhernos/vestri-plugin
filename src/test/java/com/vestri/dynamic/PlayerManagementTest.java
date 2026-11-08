package com.vestri.dynamic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.proxy.InboundConnection;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;

class PlayerManagementTest {
    private static final UUID PLAYER = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
    private static final InetSocketAddress ADDRESS = new InetSocketAddress("203.0.113.8", 24560);
    @TempDir
    Path directory;

    private PlayerManagement management() throws Exception {
        return new PlayerManagement(directory, mock(Logger.class), ip -> null);
    }

    private Player player() {
        Player player = mock(Player.class);
        when(player.getRemoteAddress()).thenReturn(ADDRESS);
        when(player.getUniqueId()).thenReturn(PLAYER);
        when(player.getUsername()).thenReturn("Notch");
        when(player.isActive()).thenReturn(true);
        return player;
    }

    private InboundConnection connection(boolean active) {
        InboundConnection connection = mock(InboundConnection.class);
        when(connection.getRemoteAddress()).thenReturn(ADDRESS);
        when(connection.isActive()).thenReturn(active);
        return connection;
    }

    private JsonObject journal() throws Exception {
        return JsonParser.parseString(Files.readString(directory.resolve("vestri-players.json"))).getAsJsonObject();
    }

    private void policy(boolean enabled, String whitelist, String bans, String ips) throws Exception {
        Files.writeString(directory.resolve("vestri-player-policy.json"), "{\"whitelistEnabled\":" + enabled
                + ",\"whitelist\":" + whitelist + ",\"bans\":" + bans + ",\"ipBans\":" + ips + "}");
    }

    private String entry() {
        return "[{\"uuid\":\"" + PLAYER + "\",\"name\":\"Notch\"}]";
    }

    private void countryPolicy(boolean enabled, String allowed, String banned) throws Exception {
        policy(false, "[]", "[]", "[]");
        JsonObject value = JsonParser.parseString(Files.readString(directory.resolve("vestri-player-policy.json"))).getAsJsonObject();
        value.addProperty("countryWhitelistEnabled", enabled);
        value.add("countryWhitelist", JsonParser.parseString(allowed));
        value.add("countryBans", JsonParser.parseString(banned));
        Files.writeString(directory.resolve("vestri-player-policy.json"), value.toString());
    }

    @Test
    void countryBansOverrideWhitelistAndUnknownCountriesFailClosedOnlyWithWhitelist() throws Exception {
        countryPolicy(false, "[]", "[\"DE\"]");
        PlayerManagement known = new PlayerManagement(directory, mock(Logger.class), ip -> "DE");
        PlayerManagement unknown = management();
        assertTrue(known.denial(null, "203.0.113.8").contains("banned"));
        assertNull(unknown.denial(null, "203.0.113.8"));
        countryPolicy(true, "[\"DE\"]", "[\"DE\"]");
        known.reloadPolicy();
        assertTrue(known.denial(PLAYER, "203.0.113.8").contains("banned"));
        countryPolicy(true, "[\"DE\"]", "[]");
        known.reloadPolicy();
        unknown.reloadPolicy();
        assertNull(known.denial(PLAYER, "203.0.113.8"));
        assertNotNull(unknown.denial(null, "203.0.113.8"));
        countryPolicy(true, "[\"US\"]", "[]");
        known.reloadPolicy();
        assertNotNull(known.denial(null, "203.0.113.8"));
    }

    @Test
    void countriesAreRecordedAlongsideTheirIPsAndUpdatedRulesDisconnectOnlinePlayers() throws Exception {
        PlayerManagement management = new PlayerManagement(directory, mock(Logger.class), ip -> "DE");
        Player player = player();
        management.beforeLogin(new PreLoginEvent(connection(true), "Notch"));
        management.login(new LoginEvent(player));
        management.connected(player);
        management.flush();
        JsonObject known = journal().getAsJsonArray("players").get(0).getAsJsonObject();
        assertEquals("DE", known.get("lastCountry").getAsString());
        assertTrue(journal().get("countryPolicySupported").getAsBoolean());
        assertTrue(journal().get("countryLookupAvailable").getAsBoolean());
        assertEquals("DE", known.get("lastLoginCountry").getAsString());
        assertEquals("DE", journal().getAsJsonArray("attempts").get(0).getAsJsonObject().get("country").getAsString());
        countryPolicy(false, "[]", "[\"DE\"]");
        ProxyServer proxy = mock(ProxyServer.class);
        when(proxy.getAllPlayers()).thenReturn(List.of(player));
        management.sweep(proxy);
        verify(player).disconnect(any(Component.class));
        PreLoginEvent denied = new PreLoginEvent(connection(true), "Notch");
        management.beforeLogin(denied);
        assertFalse(denied.getResult().isAllowed());
    }

    @Test
    void malformedCountryRulesFailClosedAndLegacyPoliciesRemainCompatible() throws Exception {
        PlayerManagement management = management();
        for (String countries : List.of("null", "[\"ZZ\"]", "[\"de\"]", "[true]")) {
            countryPolicy(false, "[]", countries);
            management.reloadPolicy();
            assertNotNull(management.denial(null, "203.0.113.8"));
        }
        policy(false, "[]", "[]", "[]");
        management.reloadPolicy();
        assertNull(management.denial(PLAYER, "203.0.113.8"));
    }

    @Test
    void enforcesUuidWhitelistBansAndEquivalentIPv6Addresses() throws Exception {
        policy(true, "[]", "[]", "[]");
        PlayerManagement management = management();
        assertNotNull(management.denial(PLAYER, "203.0.113.8"));
        policy(true, entry(), "[]", "[]");
        management.reloadPolicy();
        assertNull(management.denial(PLAYER, "203.0.113.8"));
        policy(true, entry(), entry(), "[]");
        management.reloadPolicy();
        assertTrue(management.denial(PLAYER, "203.0.113.8").contains("banned"));
        policy(false, "[]", "[]", "[\"2001:0db8::1\"]");
        management.reloadPolicy();
        assertNotNull(management.denial(null, "2001:db8:0:0:0:0:0:1"));
    }

    @Test
    void failedAuthenticationHasNoFabricatedUuidAndSurvivesRestart() throws Exception {
        PlayerManagement management = management();
        management.beforeLogin(new PreLoginEvent(connection(false), "Notch", PLAYER));
        ProxyServer proxy = mock(ProxyServer.class);
        when(proxy.getAllPlayers()).thenReturn(List.of());
        management.sweep(proxy);
        JsonObject saved = journal();
        JsonObject attempt = saved.getAsJsonArray("attempts").get(0).getAsJsonObject();
        assertEquals("failed", attempt.get("outcome").getAsString());
        assertEquals("203.0.113.8", attempt.get("ip").getAsString());
        assertFalse(attempt.has("uuid"));
        assertEquals(0, saved.getAsJsonArray("players").size());
        assertNotNull(attempt.get("time"));
        PlayerManagement restored = management();
        restored.flush();
        assertEquals(1, journal().getAsJsonArray("attempts").size());
        try (var files = Files.list(directory.resolve("vestri-player-logs"))) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void recordsSuccessOnlyAfterBackendConnectionAndKeepsLastSuccessfulLogin() throws Exception {
        PlayerManagement management = management();
        Player player = player();
        management.beforeLogin(new PreLoginEvent(connection(true), "Notch"));
        management.login(new LoginEvent(player));
        management.connected(player);
        management.connected(player);
        management.flush();
        assertEquals(1, journal().getAsJsonArray("attempts").size());
        JsonObject known = journal().getAsJsonArray("players").get(0).getAsJsonObject();
        assertEquals(PLAYER.toString(), known.get("uuid").getAsString());
        assertNotNull(known.get("lastLogin"));
        assertEquals("203.0.113.8", known.get("lastLoginIP").getAsString());
        policy(false, "[]", entry(), "[]");
        management.beforeLogin(new PreLoginEvent(connection(true), "Notch"));
        LoginEvent denied = new LoginEvent(player);
        management.login(denied);
        assertFalse(denied.getResult().isAllowed());
        management.flush();
        assertEquals("denied", journal().getAsJsonArray("attempts").get(0).getAsJsonObject().get("outcome").getAsString());
        assertEquals(known.get("lastLogin"), journal().getAsJsonArray("players").get(0).getAsJsonObject().get("lastLogin"));
    }

    @Test
    void recordsPreLoginDenialWithoutOverridingOtherPlugins() throws Exception {
        PlayerManagement management = management();
        PreLoginEvent event = new PreLoginEvent(connection(true), "Notch");
        event.setResult(PreLoginEvent.PreLoginComponentResult.denied(Component.text("Maintenance")));
        management.beforeLogin(event);
        management.flush();
        assertFalse(event.getResult().isAllowed());
        JsonObject attempt = journal().getAsJsonArray("attempts").get(0).getAsJsonObject();
        assertEquals("denied", attempt.get("outcome").getAsString());
        assertEquals("Maintenance", attempt.get("reason").getAsString());
    }

    @Test
    void corruptOrRemovedPolicyFailsClosedAndRecoveryIsLive() throws Exception {
        policy(true, entry(), "[]", "[]");
        PlayerManagement management = management();
        Files.delete(directory.resolve("vestri-player-policy.json"));
        management.reloadPolicy();
        assertNotNull(management.denial(PLAYER, "203.0.113.8"));
        Files.writeString(directory.resolve("vestri-player-policy.json"), "not json");
        management.reloadPolicy();
        assertNotNull(management.denial(PLAYER, "203.0.113.8"));
        policy(false, "[]", "[]", "[]");
        management.reloadPolicy();
        assertNull(management.denial(PLAYER, "203.0.113.8"));
    }

    @Test
    void shutdownPersistsIncompleteAttempts() throws Exception {
        PlayerManagement management = management();
        management.beforeLogin(new PreLoginEvent(connection(true), "Notch"));
        management.shutdown();
        JsonObject attempt = journal().getAsJsonArray("attempts").get(0).getAsJsonObject();
        assertEquals("failed", attempt.get("outcome").getAsString());
        assertEquals("Proxy shutdown during login", attempt.get("reason").getAsString());
    }

    @Test
    void journalAndDailyArchivesKeepNewestThousandEventsAcrossRestart() throws Exception {
        policy(false, "[]", "[]", "[\"203.0.113.8\"]");
        PlayerManagement management = management();
        InboundConnection connection = connection(true);
        for (int attempt = 0; attempt < 1005; attempt++) {
            management.beforeLogin(new PreLoginEvent(connection, "Notch"));
        }
        management.flush();
        assertEquals(1000, journal().getAsJsonArray("attempts").size());
        try (var files = Files.list(directory.resolve("vestri-player-logs"))) {
            try (var lines = Files.lines(files.findFirst().orElseThrow())) {
                List<String> retained = lines.toList();
                assertEquals(1000, retained.size());
                var attempts = journal().getAsJsonArray("attempts");
                assertEquals(attempts.get(999), JsonParser.parseString(retained.get(0)));
                assertEquals(attempts.get(0), JsonParser.parseString(retained.get(999)));
            }
        }
        PlayerManagement restored = management();
        restored.beforeLogin(new PreLoginEvent(connection, "Notch"));
        restored.flush();
        Path archive = directory.resolve("vestri-player-logs").resolve(LocalDate.now(ZoneOffset.UTC) + ".jsonl");
        List<String> retained = Files.readAllLines(archive);
        assertEquals(1000, retained.size());
        assertEquals(journal().getAsJsonArray("attempts").get(999), JsonParser.parseString(retained.get(0)));
        assertEquals(journal().getAsJsonArray("attempts").get(0), JsonParser.parseString(retained.get(999)));
    }

    @Test
    void startupPrunesLegacyArchivesByCountAndAgeWithoutTouchingOtherFiles() throws Exception {
        Path logs = Files.createDirectories(directory.resolve("vestri-player-logs"));
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Path expired = logs.resolve(today.minusDays(30) + ".jsonl");
        Path oldest = logs.resolve(today.minusDays(29) + ".jsonl");
        Path boundary = logs.resolve(today.minusDays(1) + ".jsonl");
        Path newest = logs.resolve(today + ".jsonl");
        Files.writeString(expired, "{\"id\":\"expired\"}\n");
        Files.writeString(oldest, "{\"id\":\"oldest\"}\n".repeat(200));
        StringBuilder legacy = new StringBuilder();
        for (int event = 0; event < 1500; event++) {
            legacy.append("{\"id\":").append(event).append("}\n");
        }
        Files.writeString(boundary, legacy);
        var permissions = java.nio.file.attribute.PosixFilePermissions.fromString("rw-r-----");
        Files.setPosixFilePermissions(boundary, permissions);
        Files.writeString(newest, "{\"id\":\"newest\"}\n".repeat(200));
        Path unrelated = logs.resolve("operator-backup.jsonl");
        Files.writeString(unrelated, "keep");
        Path invalidDate = logs.resolve("2026-99-99.jsonl");
        Files.writeString(invalidDate, "keep");
        Path external = directory.resolve("external.jsonl");
        Files.writeString(external, "keep");
        Path linked = logs.resolve(today.minusDays(2) + ".jsonl");
        Files.createSymbolicLink(linked, external);

        management();
        assertFalse(Files.exists(expired));
        assertFalse(Files.exists(oldest));
        List<String> retained = Files.readAllLines(boundary);
        assertEquals(800, retained.size());
        assertEquals("{\"id\":700}", retained.get(0));
        assertEquals("{\"id\":1499}", retained.get(799));
        assertEquals(permissions, Files.getPosixFilePermissions(boundary));
        assertEquals(200, Files.readAllLines(newest).size());
        assertEquals("keep", Files.readString(unrelated));
        assertEquals("keep", Files.readString(invalidDate));
        assertTrue(Files.isSymbolicLink(linked));
        assertEquals("keep", Files.readString(external));
        management();
        assertEquals(retained, Files.readAllLines(boundary));
        try (var files = Files.list(logs)) {
            assertFalse(files.anyMatch(file -> file.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void exactLimitKeepsDayTwentyNineUntilNextEventDeletesOldestFile() throws Exception {
        Path logs = Files.createDirectories(directory.resolve("vestri-player-logs"));
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Path oldest = logs.resolve(today.minusDays(29) + ".jsonl");
        Path newest = logs.resolve(today + ".jsonl");
        Files.writeString(oldest, "{\"id\":\"oldest\"}\n");
        Files.writeString(newest, "{\"id\":\"recent\"}\n".repeat(999));
        policy(false, "[]", "[]", "[\"203.0.113.8\"]");
        PlayerManagement management = management();
        assertTrue(Files.exists(oldest));
        assertEquals(999, Files.readAllLines(newest).size());
        management.beforeLogin(new PreLoginEvent(connection(true), "Notch"));
        management.flush();
        assertFalse(Files.exists(oldest));
        List<String> retained = Files.readAllLines(newest);
        assertEquals(1000, retained.size());
        assertEquals("{\"id\":\"recent\"}", retained.get(0));
        assertEquals(journal().getAsJsonArray("attempts").get(0), JsonParser.parseString(retained.get(999)));
    }

    @Test
    void olderAttemptCannotOverwriteNewerPlayerDetails() throws Exception {
        Files.writeString(directory.resolve("vestri-players.json"), "{\"players\":[{\"uuid\":\"" + PLAYER
                + "\",\"name\":\"NewName\",\"lastAttempt\":\"2099-01-01T00:00:00Z\",\"lastLogin\":\"2099-01-01T00:00:00Z\","
                + "\"lastIP\":\"198.51.100.1\",\"lastLoginIP\":\"198.51.100.1\"}],\"attempts\":[]}");
        PlayerManagement management = management();
        management.beforeLogin(new PreLoginEvent(connection(true), "Notch"));
        management.login(new LoginEvent(player()));
        management.connected(player());
        management.flush();
        JsonObject known = journal().getAsJsonArray("players").get(0).getAsJsonObject();
        assertEquals("NewName", known.get("name").getAsString());
        assertEquals("198.51.100.1", known.get("lastIP").getAsString());
        assertEquals("198.51.100.1", known.get("lastLoginIP").getAsString());
    }

    @Test
    void disconnectBeforeJoiningIsNotSuccessfulAndOnlineBansKick() throws Exception {
        PlayerManagement management = management();
        Player player = player();
        management.beforeLogin(new PreLoginEvent(connection(true), "Notch"));
        management.login(new LoginEvent(player));
        management.disconnected(new DisconnectEvent(player, DisconnectEvent.LoginStatus.values()[0]));
        policy(false, "[]", entry(), "[]");
        ProxyServer proxy = mock(ProxyServer.class);
        when(proxy.getAllPlayers()).thenReturn(List.of(player));
        management.sweep(proxy);
        verify(player).disconnect(any(Component.class));
        assertEquals("failed", journal().getAsJsonArray("attempts").get(0).getAsJsonObject().get("outcome").getAsString());
        assertFalse(journal().getAsJsonArray("players").get(0).getAsJsonObject().has("lastLogin"));
    }
}