package com.vestri.dynamic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.command.CommandManager;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;

class ConsoleBridgeReceiverTest {
    @TempDir
    Path directory;

    @Test
    void runsAuthenticatedAllowedBackendRequestAsProxyConsole() throws Exception {
        ProxyServer proxy = mock(ProxyServer.class);
        CommandManager manager = mock(CommandManager.class);
        ConsoleCommandSource console = mock(ConsoleCommandSource.class);
        when(proxy.getCommandManager()).thenReturn(manager);
        when(proxy.getConsoleCommandSource()).thenReturn(console);
        String secret = "a".repeat(64);
        Path config = directory.resolve("config.json");
        Files.writeString(config, "{\"consoleBridge\":{\"secret\":\"" + secret + "\",\"allowedServers\":[\"lobby\"]}}");
        PluginMessageEvent event = backendEvent("lobby", ConsoleBridgeProtocol.encode(secret,
                "vestridynamic list Bed Wars", System.currentTimeMillis(), UUID.randomUUID()));
        new VestriDynamicServers(proxy, mock(Logger.class)).forwardConsoleCommand(event, config);
        assertEquals(PluginMessageEvent.ForwardResult.handled(), event.getResult());
        verify(manager).executeAsync(console, "vestridynamic list Bed Wars");
    }

    @Test
    void rejectsPlayerSourcesAndUnlistedBackends() throws Exception {
        ProxyServer proxy = mock(ProxyServer.class);
        VestriDynamicServers plugin = new VestriDynamicServers(proxy, mock(Logger.class));
        Path config = directory.resolve("config.json");
        String secret = "a".repeat(64);
        Files.writeString(config, "{\"consoleBridge\":{\"secret\":\"" + secret + "\",\"allowedServers\":[\"lobby\"]}}");
        byte[] payload = ConsoleBridgeProtocol.encode(secret, "vestridynamic templates", System.currentTimeMillis(), UUID.randomUUID());
        PluginMessageEvent player = new PluginMessageEvent(mock(Player.class), mock(Player.class),
            MinecraftChannelIdentifier.from(ConsoleBridgeProtocol.CHANNEL), payload);
        plugin.forwardConsoleCommand(player, config);
        plugin.forwardConsoleCommand(backendEvent("untrusted", payload), config);
        plugin.forwardConsoleCommand(backendEvent("lobby", ConsoleBridgeProtocol.encode("b".repeat(64),
                "vestridynamic templates", System.currentTimeMillis(), UUID.randomUUID())), config);
        assertEquals(PluginMessageEvent.ForwardResult.handled(), player.getResult());
        verify(proxy, never()).getCommandManager();
    }

    private PluginMessageEvent backendEvent(String name, byte[] payload) {
        ServerConnection source = mock(ServerConnection.class);
        RegisteredServer server = mock(RegisteredServer.class);
        when(server.getServerInfo()).thenReturn(new ServerInfo(name, new InetSocketAddress("127.0.0.1", 25565)));
        when(source.getServer()).thenReturn(server);
        return new PluginMessageEvent(source, mock(Player.class), MinecraftChannelIdentifier.from(ConsoleBridgeProtocol.CHANNEL), payload);
    }
}