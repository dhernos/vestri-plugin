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
        return new PlayerManagement(directory, mock(Logger.class));
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
    void boundedDashboardDoesNotTruncateDailyAuditLog() throws Exception {
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
                assertEquals(1005, lines.count());
            }
        }
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