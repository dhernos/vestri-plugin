package com.vestri.dynamic;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;

class VestriDynamicServersTest {
    @TempDir
    Path directory;

    @Test
    void registersFriendlyNamesAndRemovesStaleFriendlyTargets() throws Exception {
        String id = "mcgame-" + "b".repeat(32);
        ProxyServer proxy = mock(ProxyServer.class);
        ServerInfo stale = new ServerInfo("BedWars-1", InetSocketAddress.createUnresolved("vestri-mcgame-" + "a".repeat(32), 25565));
        RegisteredServer registered = mock(RegisteredServer.class);
        when(registered.getServerInfo()).thenReturn(stale);
        when(proxy.getAllServers()).thenReturn(List.of(registered));
        when(proxy.getServer("BedWars-2")).thenReturn(Optional.empty());
        Path manifest = directory.resolve("friendly.json");
        Files.writeString(manifest, "{\"servers\":{\"" + id + "\":{\"name\":\"BedWars-2\",\"host\":\"vestri-" + id + "\",\"port\":25565}}}");
        new VestriDynamicServers(proxy, mock(Logger.class)).synchronizeServers(manifest);
        verify(proxy).unregisterServer(stale);
        verify(proxy).registerServer(new ServerInfo("BedWars-2", InetSocketAddress.createUnresolved("vestri-" + id, 25565)));
    }

    @Test
    void refusesFriendlyNameCollisionsBeforeMutatingRegistry() throws Exception {
        String id = "mcgame-" + "b".repeat(32);
        ProxyServer proxy = mock(ProxyServer.class);
        RegisteredServer ordinary = mock(RegisteredServer.class);
        when(ordinary.getServerInfo()).thenReturn(new ServerInfo("BedWars-1", new InetSocketAddress("127.0.0.1", 25565)));
        when(proxy.getServer("BedWars-1")).thenReturn(Optional.of(ordinary));
        Path manifest = directory.resolve("conflict.json");
        Files.writeString(manifest, "{\"servers\":{\"" + id + "\":{\"name\":\"BedWars-1\",\"host\":\"vestri-" + id + "\",\"port\":25565}}}");
        assertThrows(IOException.class, () -> new VestriDynamicServers(proxy, mock(Logger.class)).synchronizeServers(manifest));
        verify(proxy, never()).registerServer(any());
        verify(proxy, never()).unregisterServer(any());
    }

    @Test
    void registersNewGamesAndRemovesOnlyStaleDynamicServers() throws Exception {
        String oldName = "mcgame-" + "a".repeat(32);
        String newName = "mcgame-" + "b".repeat(32);
        ServerInfo stale = new ServerInfo(oldName, InetSocketAddress.createUnresolved("vestri-" + oldName, 25565));
        ServerInfo lobby = new ServerInfo("lobby", new InetSocketAddress("127.0.0.1", 25565));
        RegisteredServer staleServer = mock(RegisteredServer.class);
        RegisteredServer lobbyServer = mock(RegisteredServer.class);
        when(staleServer.getServerInfo()).thenReturn(stale);
        when(lobbyServer.getServerInfo()).thenReturn(lobby);
        ProxyServer proxy = mock(ProxyServer.class);
        when(proxy.getAllServers()).thenReturn(List.of(staleServer, lobbyServer));
        when(proxy.getServer(newName)).thenReturn(Optional.empty());
        Path manifest = directory.resolve("servers.json");
        Files.writeString(manifest, "{\"servers\":{\"" + newName + "\":{\"host\":\"vestri-" + newName + "\",\"port\":25565}}}");

        new VestriDynamicServers(proxy, mock(Logger.class)).synchronizeServers(manifest);

        verify(proxy).unregisterServer(stale);
        verify(proxy, never()).unregisterServer(lobby);
        verify(proxy).registerServer(new ServerInfo(newName, InetSocketAddress.createUnresolved("vestri-" + newName, 25565)));
    }

    @Test
    void rejectsUnownedNamesAndExternalAddressesBeforeChangingProxy() throws Exception {
        String name = "mcgame-" + "a".repeat(32);
        for (String invalid : List.of(
                "{\"servers\":{\"lobby\":{\"host\":\"vestri-lobby\",\"port\":25565}}}",
                "{\"servers\":{\"" + name + "\":{\"host\":\"external.invalid\",\"port\":25565}}}",
                "{\"servers\":{\"" + name + "\":{\"host\":\"vestri-" + name + "\",\"port\":25566}}}")) {
            ProxyServer proxy = mock(ProxyServer.class);
            Path manifest = directory.resolve("invalid.json");
            Files.writeString(manifest, invalid);
            VestriDynamicServers bridge = new VestriDynamicServers(proxy, mock(Logger.class));

            assertThrows(IOException.class, () -> bridge.synchronizeServers(manifest));
            verify(proxy, never()).registerServer(any());
            verify(proxy, never()).unregisterServer(any());
        }
    }

    @Test
    void rejectsOversizedManifest() throws Exception {
        ProxyServer proxy = mock(ProxyServer.class);
        Path manifest = directory.resolve("large.json");
        Files.writeString(manifest, " ".repeat(1024 * 1024 + 1));
        VestriDynamicServers bridge = new VestriDynamicServers(proxy, mock(Logger.class));
        assertThrows(IOException.class, () -> bridge.synchronizeServers(manifest));
        verify(proxy, never()).registerServer(any());
        verify(proxy, never()).unregisterServer(any());
    }
}