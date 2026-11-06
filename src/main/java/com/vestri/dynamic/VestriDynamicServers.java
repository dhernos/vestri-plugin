package com.vestri.dynamic;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.ServerInfo;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

@Plugin(id = "vestri-dynamic", name = "Vestri Dynamic Servers", version = "1.1.0")
public final class VestriDynamicServers {
    private static final Pattern SERVER_NAME = Pattern.compile("mcgame-[a-f0-9]{32}");
    private static final Pattern FRIENDLY_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}-[1-9][0-9]*");
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final ConsoleBridgeProtocol consoleBridge = new ConsoleBridgeProtocol();
    private PlayerServers playerServers;
    private PlayerManagement playerManagement;
    private static final MinecraftChannelIdentifier CONSOLE_CHANNEL = MinecraftChannelIdentifier.from(ConsoleBridgeProtocol.CHANNEL);

    public VestriDynamicServers(ProxyServer proxy, Logger logger) {
        this(proxy, logger, Path.of("plugins", "vestri-dynamic"));
    }

    @Inject
    public VestriDynamicServers(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void initialize(ProxyInitializeEvent event) {
        try {
            PluginDataDirectory.prepare(Path.of("."), dataDirectory);
        } catch (IOException failure) {
            logger.error("Plugin data migration failed; resolve file conflicts or permissions before restarting", failure);
            return;
        }
        try {
            playerManagement = new PlayerManagement(dataDirectory, logger);
            proxy.getScheduler().buildTask(this, () -> playerManagement.sweep(proxy))
                    .repeat(Duration.ofSeconds(5)).schedule();
        } catch (IOException failure) {
            logger.error("Player journal could not be loaded; new logins will be denied", failure);
        }
        try {
            if (MinecraftInstanceClient.createDefaultConfiguration(dataDirectory.resolve("vestri-dynamic-config.json"))) {
                logger.info("Created {}; set backendUrl to your frontend origin and add template API keys to enable lifecycle commands", dataDirectory.resolve("vestri-dynamic-config.json"));
            }
        } catch (IOException failure) {
            logger.error("Could not create plugin configuration; check plugin-folder permissions");
        }
        proxy.getChannelRegistrar().register(CONSOLE_CHANNEL);
        SpawnPlayerTransfer playerTransfer = new SpawnPlayerTransfer(proxy,
                (task, delay) -> proxy.getScheduler().buildTask(this, task).delay(delay).schedule());
        try {
                playerServers = new PlayerServers(proxy, dataDirectory.resolve("vestri-dynamic-config.json"),
                    new OwnedServerRegistry(dataDirectory.resolve("vestri-player-servers.json")), playerTransfer,
                    task -> proxy.getScheduler().buildTask(this, task).schedule(), logger);
            proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("vestriservers").plugin(this).build(), playerServers);
            proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("vestripause").plugin(this).build(), playerServers.pauseCommand());
            proxy.getScheduler().buildTask(this, playerServers::sweep).delay(Duration.ofSeconds(5))
                    .repeat(Duration.ofSeconds(5)).schedule();
        } catch (IOException failure) {
            logger.error("Player server management disabled: {}", failure.getMessage());
        }
        MinecraftLifecycleCommand lifecycle = new MinecraftLifecycleCommand(
            task -> proxy.getScheduler().buildTask(this, task).schedule(), dataDirectory.resolve("vestri-dynamic-config.json"),
            playerTransfer, playerServers);
        proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("vestridynamic").plugin(this).build(), new SimpleCommand() {
            @Override
            public boolean hasPermission(Invocation invocation) {
                return invocation.source() instanceof ConsoleCommandSource;
            }

            @Override
            public List<String> suggest(Invocation invocation) {
                return lifecycle.suggest(invocation);
            }

            @Override
            public void execute(Invocation invocation) {
                if (!hasPermission(invocation)) {
                    return;
                }
                if (invocation.arguments().length != 1 || !"sync".equals(invocation.arguments()[0])) {
                    lifecycle.execute(invocation);
                    return;
                }
                try {
                    synchronizeServers(dataDirectory.resolve("vestri-dynamic-servers.json"));
                    invocation.source().sendMessage(Component.text("VESTRI_DYNAMIC_SYNC_OK"));
                } catch (Exception failure) {
                    logger.error("Dynamic server synchronization failed", failure);
                    invocation.source().sendMessage(Component.text("VESTRI_DYNAMIC_SYNC_FAILED"));
                }
            }
        });
        if (Files.exists(dataDirectory.resolve("vestri-dynamic-servers.json"))) {
            try {
                synchronizeServers(dataDirectory.resolve("vestri-dynamic-servers.json"));
            } catch (Exception failure) {
                logger.error("Initial dynamic server synchronization failed", failure);
            }
        }
    }

    @Subscribe
    public EventTask beforeServerConnect(ServerPreConnectEvent event) {
        return EventTask.async(() -> {
            if (playerServers != null) {
                playerServers.beforeConnect(event);
            }
        });
    }

    @Subscribe
    public EventTask onServerConnected(ServerConnectedEvent event) {
        return EventTask.async(() -> {
            if (playerManagement != null) {
                playerManagement.connected(event.getPlayer());
            }
            if (playerServers != null) {
                playerServers.connected(event.getServer());
            }
        });
    }

    @Subscribe(order = PostOrder.LAST)
    public EventTask onPreLogin(PreLoginEvent event) {
        return EventTask.async(() -> {
            if (playerManagement == null) {
                event.setResult(PreLoginEvent.PreLoginComponentResult.denied(Component.text("Player management is unavailable. Please contact an administrator.")));
            } else {
                playerManagement.beforeLogin(event);
            }
        });
    }

    @Subscribe(order = PostOrder.LAST)
    public EventTask onLogin(LoginEvent event) {
        return EventTask.async(() -> {
            if (playerManagement != null) {
                playerManagement.login(event);
            }
        });
    }

    @Subscribe
    public EventTask onDisconnect(DisconnectEvent event) {
        return EventTask.async(() -> {
            if (playerManagement != null) {
                playerManagement.disconnected(event);
            }
        });
    }

    @Subscribe(order = PostOrder.LAST)
    public EventTask onKick(KickedFromServerEvent event) {
        return EventTask.async(() -> {
            if (playerManagement != null) {
                playerManagement.kicked(event);
            }
        });
    }

    @Subscribe
    public void shutdown(ProxyShutdownEvent event) {
        if (playerManagement != null) {
            try {
                playerManagement.shutdown();
            } catch (IOException failure) {
                logger.error("Could not save player journal during shutdown", failure);
            }
        }
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        forwardConsoleCommand(event, dataDirectory.resolve("vestri-dynamic-config.json"));
    }

    void forwardConsoleCommand(PluginMessageEvent event, Path configPath) {
        if (!CONSOLE_CHANNEL.equals(event.getIdentifier())) {
            return;
        }
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection backend)) {
            return;
        }
        try {
            if (Files.size(configPath) > 64 * 1024) {
                return;
            }
            JsonObject config = JsonParser.parseString(Files.readString(configPath, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject bridge = config.getAsJsonObject("consoleBridge");
            if (bridge == null || bridge.getAsJsonArray("allowedServers") == null) {
                return;
            }
            String source = backend.getServer().getServerInfo().getName();
            boolean allowed = false;
            for (JsonElement name : bridge.getAsJsonArray("allowedServers")) {
                if (source.equals(name.getAsString())) {
                    allowed = true;
                }
            }
            if (!allowed) {
                return;
            }
            String command = consoleBridge.verify(bridge.get("secret").getAsString(), event.getData(), System.currentTimeMillis());
            proxy.getCommandManager().executeAsync(proxy.getConsoleCommandSource(), command);
        } catch (IOException | RuntimeException failure) {
            logger.warn("Rejected Vestri backend console bridge request; check bridge configuration and authentication");
        }
    }

    synchronized void synchronizeServers(Path manifest) throws IOException {
        if (Files.size(manifest) > 1024 * 1024) {
            throw new IOException("Dynamic server manifest exceeds 1 MiB");
        }
        JsonObject servers = JsonParser.parseString(Files.readString(manifest, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("servers");
        if (servers == null || servers.size() > 100) {
            throw new IOException("Invalid dynamic server manifest");
        }
        Map<String, ServerInfo> desired = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : servers.entrySet()) {
            String id = entry.getKey();
            JsonObject target = entry.getValue().getAsJsonObject();
            String name = target.has("name") ? target.get("name").getAsString() : id;
            String host = target.get("host").getAsString();
            int port = target.get("port").getAsInt();
            if (!SERVER_NAME.matcher(id).matches() || !("vestri-" + id).equals(host) || port != 25565
                    || !(name.equals(id) || FRIENDLY_NAME.matcher(name).matches()) || desired.containsKey(name)) {
                throw new IOException("Invalid dynamic server address");
            }
            var existing = proxy.getServer(name);
            if (existing.isPresent() && !isDynamicTarget(existing.get().getServerInfo())) {
                throw new IOException("Dynamic name conflicts with an ordinary proxy server");
            }
            desired.put(name, new ServerInfo(name, InetSocketAddress.createUnresolved(host, port)));
        }
        for (var registered : proxy.getAllServers()) {
            String name = registered.getServerInfo().getName();
            if (isDynamicTarget(registered.getServerInfo()) && !desired.containsKey(name)) {
                proxy.unregisterServer(registered.getServerInfo());
            }
        }
        for (ServerInfo target : desired.values()) {
            var registered = proxy.getServer(target.getName());
            if (registered.isPresent() && !registered.get().getServerInfo().equals(target)) {
                proxy.unregisterServer(registered.get().getServerInfo());
                registered = java.util.Optional.empty();
            }
            if (registered.isEmpty()) {
                proxy.registerServer(target);
            }
        }
    }

    private static boolean isDynamicTarget(ServerInfo server) {
        String host = server.getAddress().getHostString();
        return server.getAddress().getPort() == 25565 && host.startsWith("vestri-")
                && SERVER_NAME.matcher(host.substring(7)).matches();
    }
}