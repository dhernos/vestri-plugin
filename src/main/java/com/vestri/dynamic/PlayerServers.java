package com.vestri.dynamic;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

final class PlayerServers implements SimpleCommand {
    private static final long JOIN_WINDOW = Duration.ofMinutes(6).toMillis();
    private static final String USAGE = "/vestriservers list | spawn <permanent|temporary> <template> | "
            + "retry <request UUID> | join <server UUID> | delete <server UUID> --confirm";
    private final ProxyServer proxy;
    private final Path configuration;
    private final OwnedServerRegistry registry;
    private final SpawnPlayerTransfer transfer;
    private final Consumer<Runnable> schedule;
    private final Logger logger;
    private final LongSupplier clock;
    private long retryAfter;

    PlayerServers(ProxyServer proxy, Path configuration, OwnedServerRegistry registry, SpawnPlayerTransfer transfer,
            Consumer<Runnable> schedule, Logger logger) {
        this(proxy, configuration, registry, transfer, schedule, logger, System::currentTimeMillis);
    }

    PlayerServers(ProxyServer proxy, Path configuration, OwnedServerRegistry registry, SpawnPlayerTransfer transfer,
            Consumer<Runnable> schedule, Logger logger, LongSupplier clock) {
        this.proxy = proxy;
        this.configuration = configuration;
        this.registry = registry;
        this.transfer = transfer;
        this.schedule = schedule;
        this.logger = logger;
        this.clock = clock;
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source() instanceof Player && invocation.source().hasPermission("vestri.servers");
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (!hasPermission(invocation)) {
            return List.of();
        }
        String[] arguments = invocation.arguments();
        String current = arguments.length == 0 ? "" : arguments[arguments.length - 1];
        if (arguments.length <= 1) {
            return matching(List.of("list", "spawn", "retry", "join", "delete"), current);
        }
        if (arguments[0].equals("spawn")) {
            if (arguments.length == 2) {
                return matching(List.of("permanent", "temporary"), current);
            }
            try {
                String prefix = String.join(" ", Arrays.copyOfRange(arguments, 2, arguments.length));
                String completed = arguments.length > 3
                        ? String.join(" ", Arrays.copyOfRange(arguments, 2, arguments.length - 1)) + " " : "";
                return MinecraftInstanceClient.load(configuration).templates().stream()
                        .filter(template -> template.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT)))
                        .map(template -> template.substring(completed.length())).toList();
            } catch (IOException failure) {
                return List.of();
            }
        }
        if (arguments.length == 2 && List.of("retry", "join", "delete").contains(arguments[0])) {
            return matching(registry.owned(((Player) invocation.source()).getUniqueId()).stream()
                    .map(entry -> entry.serverId == null ? entry.requestId.toString() : entry.serverId).toList(), current);
        }
        return arguments[0].equals("delete") && arguments.length == 3 ? matching(List.of("--confirm"), current) : List.of();
    }

    private static List<String> matching(List<String> choices, String prefix) {
        return choices.stream().filter(choice -> choice.startsWith(prefix)).toList();
    }

    @Override
    public void execute(Invocation invocation) {
        if (!hasPermission(invocation)) {
            return;
        }
        Player player = (Player) invocation.source();
        String[] arguments = invocation.arguments().clone();
        schedule.accept(() -> run(player, () -> command(player, arguments)));
    }

    SimpleCommand pauseCommand() {
        return new SimpleCommand() {
            @Override
            public boolean hasPermission(Invocation invocation) {
                return PlayerServers.this.hasPermission(invocation) && invocation.source().hasPermission("vestri.servers.pause");
            }

            @Override
            public List<String> suggest(Invocation invocation) {
                if (!hasPermission(invocation) || invocation.arguments().length > 1) {
                    return List.of();
                }
                String prefix = invocation.arguments().length == 0 ? "" : invocation.arguments()[0];
                return matching(registry.owned(((Player) invocation.source()).getUniqueId()).stream()
                        .filter(entry -> entry.type == OwnedServerRegistry.Type.TEMPORARY && entry.serverId != null)
                        .map(entry -> entry.serverId).toList(), prefix);
            }

            @Override
            public void execute(Invocation invocation) {
                if (!hasPermission(invocation)) {
                    return;
                }
                Player player = (Player) invocation.source();
                String[] arguments = invocation.arguments().clone();
                schedule.accept(() -> run(player, () -> {
                    if (arguments.length != 1) {
                        message(player, "/vestripause <server UUID> (toggles pause)");
                        return;
                    }
                    pause(player, arguments[0]);
                }));
            }
        };
    }

    private synchronized void command(Player player, String[] arguments) throws IOException, InterruptedException {
        if (!player.isActive()) {
            return;
        }
        if (arguments.length == 0 || (arguments.length == 1 && arguments[0].equals("list"))) {
            List<OwnedServerRegistry.Entry> owned = registry.owned(player.getUniqueId());
            message(player, owned.isEmpty() ? "You own no servers." : "Your servers:");
            for (var entry : owned) {
                String id = entry.serverId == null ? entry.requestId.toString() : entry.serverId;
                message(player, entry.template + " | " + entry.type.name().toLowerCase(Locale.ROOT)
                        + " | " + entry.state.name().toLowerCase(Locale.ROOT) + (entry.paused ? " | paused" : "")
                        + (entry.state == OwnedServerRegistry.State.PENDING_DELETION
                            ? " | deletes in " + Math.max(0, (entry.deleteAfter - clock.getAsLong() + 999) / 1000) + "s" : "")
                        + " | " + id);
            }
        } else if (arguments.length >= 3 && arguments[0].equals("spawn")) {
            PlayerServerPolicy policy = PlayerServerPolicy.load(configuration);
            if (!policy.enabled()) {
                throw new IOException("Player server creation is disabled.");
            }
            OwnedServerRegistry.Type type;
            try {
                type = OwnedServerRegistry.Type.valueOf(arguments[1].toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException failure) {
                throw new IOException("Server type must be permanent or temporary.");
            }
            MinecraftInstanceClient client = MinecraftInstanceClient.load(configuration);
            String template = String.join(" ", Arrays.copyOfRange(arguments, 2, arguments.length)).trim().toLowerCase(Locale.ROOT);
            if (!client.templates().contains(template)) {
                throw new IOException("Unknown template. Available: " + String.join(", ", client.templates()));
            }
            var entry = reserveForSpawn(player, template, type, policy, client, UUID.randomUUID());
            message(player, "Spawning server. Request ID: " + entry.requestId + ". Use /vestriservers retry with this ID after a failure.");
            spawn(player, entry, client);
        } else if (arguments.length == 2 && arguments[0].equals("retry")) {
            var entry = registry.require(player.getUniqueId(), arguments[1]);
            if (entry.state == OwnedServerRegistry.State.PROVISIONING) {
                spawn(player, entry, MinecraftInstanceClient.load(configuration));
            } else {
                join(player, entry);
            }
        } else if (arguments.length == 2 && arguments[0].equals("join")) {
            join(player, registry.require(player.getUniqueId(), arguments[1]));
        } else if (arguments.length == 3 && arguments[0].equals("delete") && arguments[2].equals("--confirm")) {
            var entry = registry.require(player.getUniqueId(), arguments[1]);
            if (entry.serverId == null) {
                throw new IOException("Spawn outcome is unknown. Retry the saved request ID before deleting.");
            }
            if (occupied(entry)) {
                throw new IOException("Server is occupied. All players must leave before it can be deleted.");
            }
            entry.state = OwnedServerRegistry.State.DELETING;
            registry.save();
            delete(entry, MinecraftInstanceClient.load(configuration));
            message(player, "Server deleted.");
        } else {
            message(player, USAGE);
        }
    }

    private void spawn(Player player, OwnedServerRegistry.Entry entry, MinecraftInstanceClient client)
            throws IOException, InterruptedException {
        var instance = client.spawnWithTarget(entry.template, entry.requestId);
        entry.serverId = instance.serverId();
        entry.slug = instance.slug();
        entry.state = OwnedServerRegistry.State.ACTIVE;
        entry.joinUntil = clock.getAsLong() + JOIN_WINDOW;
        registry.save();
        message(player, "Server created: " + entry.serverId);
        transfer.start(player, instance, logger::info);
    }

    synchronized void spawnForConsole(Player player, LifecycleRequest request) throws IOException, InterruptedException {
        PlayerServerPolicy policy = PlayerServerPolicy.load(configuration);
        if (!policy.enabled()) {
            throw new IOException("Player server creation is disabled.");
        }
        var type = OwnedServerRegistry.Type.valueOf(request.serverType().toUpperCase(Locale.ROOT));
        String template = request.template().trim().toLowerCase(Locale.ROOT);
        MinecraftInstanceClient client = MinecraftInstanceClient.load(configuration);
        if (!client.templates().contains(template)) {
            throw new IOException("Unknown configured template.");
        }
        var entry = reserveForSpawn(player, template, type, policy, client, request.requestId());
        if (entry.state == OwnedServerRegistry.State.PROVISIONING) {
            spawn(player, entry, client);
        } else {
            join(player, entry);
        }
    }

    private synchronized void pause(Player player, String id) throws IOException {
        var entry = registry.require(player.getUniqueId(), id);
        if (entry.type != OwnedServerRegistry.Type.TEMPORARY || entry.serverId == null
                || entry.state == OwnedServerRegistry.State.DELETING) {
            throw new IOException("Only created temporary servers can be paused.");
        }
        PlayerServerPolicy policy = PlayerServerPolicy.load(configuration);
        if (!entry.paused && (!policy.pauseEnabled() || registry.owned(player.getUniqueId()).stream()
                .filter(owned -> owned.paused).count() >= policy.pausedLimit())) {
            throw new IOException("Pausing is disabled or your paused-server limit has been reached.");
        }
        entry.paused = !entry.paused;
        if (entry.paused && entry.state == OwnedServerRegistry.State.PENDING_DELETION) {
            entry.state = OwnedServerRegistry.State.STOPPED;
            entry.deleteAfter = 0;
        }
        if (!entry.paused) {
            entry.joinUntil = 0;
        }
        entry.idleSince = 0;
        registry.save();
        message(player, entry.paused ? "Server paused. It will stop when empty and start on the next join."
            : "Server unpaused. Empty shutdown and delayed deletion are enabled.");
    }

    private OwnedServerRegistry.Entry reserveForSpawn(Player player, String template, OwnedServerRegistry.Type type,
            PlayerServerPolicy policy, MinecraftInstanceClient client, UUID requestId) throws IOException, InterruptedException {
        if (registry.all().stream().anyMatch(entry -> entry.requestId.equals(requestId))) {
            return registry.reserve(player.getUniqueId(), template, type, policy.limit(type), requestId);
        }
        List<OwnedServerRegistry.Entry> owned = registry.owned(player.getUniqueId());
        List<OwnedServerRegistry.Entry> pending = owned.stream().filter(entry -> entry.type == OwnedServerRegistry.Type.TEMPORARY
                && !entry.paused && (entry.state == OwnedServerRegistry.State.PENDING_DELETION
                    || entry.state == OwnedServerRegistry.State.DELETING && entry.deleteAfter > 0)
                && !occupied(entry)).toList();
        long remaining = owned.stream().filter(entry -> entry.type == type).count()
                - pending.stream().filter(entry -> entry.type == type).count();
        if (remaining >= policy.limit(type)) {
            throw new IOException("Your " + type.name().toLowerCase(Locale.ROOT) + " server limit is " + policy.limit(type) + ".");
        }
        for (var entry : pending) {
            entry.state = OwnedServerRegistry.State.DELETING;
            registry.save();
            delete(entry, client);
            message(player, "Deleted pending temporary server " + entry.serverId + " to create your new server.");
        }
        return registry.reserve(player.getUniqueId(), template, type, policy.limit(type), requestId);
    }

    private void join(Player player, OwnedServerRegistry.Entry entry) throws IOException, InterruptedException {
        if (entry.serverId == null || entry.state == OwnedServerRegistry.State.DELETING) {
            throw new IOException("Server is not available to join.");
        }
        if (entry.state == OwnedServerRegistry.State.PENDING_DELETION && clock.getAsLong() >= entry.deleteAfter) {
            throw new IOException("This server's rejoin window has expired.");
        }
        entry.joinUntil = clock.getAsLong() + JOIN_WINDOW;
        entry.idleSince = 0;
        entry.deleteAfter = 0;
        if (entry.state != OwnedServerRegistry.State.ACTIVE) {
            entry.state = OwnedServerRegistry.State.STARTING;
            registry.save();
            MinecraftInstanceClient.load(configuration).power(entry.template, UUID.fromString(entry.serverId), true);
            entry.state = OwnedServerRegistry.State.ACTIVE;
        }
        entry.joinUntil = clock.getAsLong() + JOIN_WINDOW;
        registry.save();
        transfer.start(player, entry.target(), logger::info);
    }

    synchronized void beforeConnect(ServerPreConnectEvent event) {
        RegisteredServer target = event.getResult().getServer().orElse(null);
        if (target == null) {
            return;
        }
        var entry = registry.all().stream().filter(owned -> matches(owned, target)).findFirst().orElse(null);
        if (entry == null) {
            return;
        }
        if (entry.state != OwnedServerRegistry.State.ACTIVE) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            run(event.getPlayer(), () -> join(event.getPlayer(), entry));
        } else {
            entry.joinUntil = clock.getAsLong() + JOIN_WINDOW;
            entry.idleSince = 0;
            try {
                registry.save();
            } catch (IOException failure) {
                event.setResult(ServerPreConnectEvent.ServerResult.denied());
                message(event.getPlayer(), "Could not save server join protection. Contact an administrator.");
                logger.warn("Could not persist player server join protection: {}", failure.getMessage());
            }
        }
    }

    synchronized void connected(RegisteredServer target) {
        var entry = registry.all().stream().filter(owned -> matches(owned, target)).findFirst().orElse(null);
        if (entry == null) {
            return;
        }
        if (entry.state == OwnedServerRegistry.State.PENDING_DELETION) {
            entry.state = OwnedServerRegistry.State.ACTIVE;
            entry.deleteAfter = 0;
        }
        entry.joinUntil = 0;
        entry.idleSince = 0;
        try {
            registry.save();
        } catch (IOException failure) {
            logger.warn("Could not persist player server connection: {}", failure.getMessage());
        }
    }

    synchronized void sweep() {
        if (clock.getAsLong() < retryAfter) {
            return;
        }
        try {
            PlayerServerPolicy policy = PlayerServerPolicy.load(configuration);
            for (var entry : registry.all()) {
                if (entry.serverId == null || entry.type == OwnedServerRegistry.Type.PERMANENT
                        && entry.state != OwnedServerRegistry.State.DELETING) {
                    continue;
                }
                if (occupied(entry)) {
                    entry.idleSince = 0;
                    entry.joinUntil = 0;
                    if (entry.state == OwnedServerRegistry.State.PENDING_DELETION) {
                        entry.state = OwnedServerRegistry.State.ACTIVE;
                        entry.deleteAfter = 0;
                        registry.save();
                    }
                    continue;
                }
                if (entry.state == OwnedServerRegistry.State.DELETING) {
                    delete(entry, MinecraftInstanceClient.load(configuration));
                    continue;
                }
                if (entry.state == OwnedServerRegistry.State.PENDING_DELETION) {
                    if (entry.paused) {
                        entry.state = OwnedServerRegistry.State.STOPPED;
                        entry.deleteAfter = 0;
                        registry.save();
                    } else if (clock.getAsLong() >= entry.deleteAfter) {
                        entry.state = OwnedServerRegistry.State.DELETING;
                        registry.save();
                        delete(entry, MinecraftInstanceClient.load(configuration));
                    }
                    continue;
                }
                if (entry.state == OwnedServerRegistry.State.STARTING) {
                    MinecraftInstanceClient.load(configuration).power(entry.template, UUID.fromString(entry.serverId), true);
                    entry.state = OwnedServerRegistry.State.ACTIVE;
                    entry.joinUntil = clock.getAsLong() + JOIN_WINDOW;
                    registry.save();
                }
                if (entry.paused && entry.state == OwnedServerRegistry.State.STOPPED) {
                    continue;
                }
                long now = clock.getAsLong();
                if (now < entry.joinUntil) {
                    continue;
                }
                if (entry.idleSince == 0) {
                    entry.idleSince = now;
                }
                if (now - entry.idleSince < Duration.ofSeconds(policy.emptyGraceSeconds()).toMillis()) {
                    continue;
                }
                MinecraftInstanceClient client = MinecraftInstanceClient.load(configuration);
                if (occupied(entry)) {
                    entry.idleSince = 0;
                    continue;
                }
                entry.state = OwnedServerRegistry.State.STOPPING;
                registry.save();
                client.power(entry.template, UUID.fromString(entry.serverId), false);
                entry.state = entry.paused ? OwnedServerRegistry.State.STOPPED : OwnedServerRegistry.State.PENDING_DELETION;
                entry.deleteAfter = entry.paused ? 0 : clock.getAsLong() + Duration.ofSeconds(policy.deletionGraceSeconds()).toMillis();
                registry.save();
                if (!entry.paused) {
                    proxy.getPlayer(entry.owner).filter(Player::isActive).ifPresent(owner -> message(owner,
                            "Temporary server " + entry.serverId + " has shut down. Rejoin within " + policy.deletionGraceSeconds()
                            + " seconds to keep it: /vestriservers join " + entry.serverId));
                }
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException failure) {
            retryAfter = clock.getAsLong() + Duration.ofMinutes(1).toMillis();
            logger.warn("Player server maintenance failed; retrying in one minute: {}", failure.getMessage());
        }
    }

    private void delete(OwnedServerRegistry.Entry entry, MinecraftInstanceClient client) throws IOException, InterruptedException {
        client.deleteIfPresent(entry.template, UUID.fromString(entry.serverId));
        registry.remove(entry);
    }

    private boolean occupied(OwnedServerRegistry.Entry entry) {
        return proxy.getAllServers().stream().filter(server -> matches(entry, server))
                .anyMatch(server -> !server.getPlayersConnected().isEmpty());
    }

    private static boolean matches(OwnedServerRegistry.Entry entry, RegisteredServer server) {
        var address = server.getServerInfo().getAddress();
        return entry.slug != null && address.getPort() == 25565 && address.getHostString().equals("vestri-" + entry.slug);
    }

    @FunctionalInterface
    private interface Operation {
        void run() throws IOException, InterruptedException;
    }

    private synchronized void run(Player player, Operation operation) {
        try {
            operation.run();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            message(player, "Request interrupted. Check /vestriservers list before retrying.");
        } catch (IOException failure) {
            message(player, failure.getMessage());
        } catch (RuntimeException failure) {
            message(player, "Invalid server configuration or API response. Contact an administrator.");
        }
    }

    private static void message(Player player, String text) {
        player.sendMessage(Component.text(text));
    }
}