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
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.slf4j.Logger;

final class PlayerServers implements SimpleCommand {
    private static final long JOIN_WINDOW = Duration.ofMinutes(6).toMillis();
    private static final String USAGE = "/vestriservers list | spawn <permanent|temporary> <template> | "
            + "retry [server] | join <server> | delete <server> --confirm | invite <server> <player> | revoke <server> <player>";
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
        return invocation.source() instanceof Player player && player.hasPermission("vestri.servers")
                && allowed(player, invocation.arguments());
    }

    private static boolean allowed(Player player, String[] arguments) {
        String action = arguments.length == 0 ? "list" : arguments[0].toLowerCase(Locale.ROOT);
        if (action.equals("join")) {
            return canUseJoin(player);
        }
        if (action.equals("spawn")) {
            return arguments.length < 2
                    ? player.hasPermission("vestri.servers.spawn.permanent") || player.hasPermission("vestri.servers.spawn.temporary")
                    : List.of("permanent", "temporary").contains(arguments[1].toLowerCase(Locale.ROOT))
                        && player.hasPermission("vestri.servers.spawn." + arguments[1].toLowerCase(Locale.ROOT));
        }
        return player.hasPermission("vestri.servers." + action);
    }

    private static boolean canUseJoin(Player player) {
        return player.hasPermission("vestri.servers.join") || player.hasPermission("vestri.servers.join.admin");
    }

    private boolean adminJoin(Player player) {
        return player.hasPermission("vestri.servers.join.admin");
    }

    @Override
    public synchronized List<String> suggest(Invocation invocation) {
        if (!(invocation.source() instanceof Player player) || !player.hasPermission("vestri.servers")) {
            return List.of();
        }
        String[] arguments = invocation.arguments();
        String current = arguments.length == 0 ? "" : arguments[arguments.length - 1];
        if (arguments.length <= 1) {
            return matching(List.of("list", "spawn", "retry", "join", "delete", "invite", "revoke").stream()
                    .filter(action -> allowed(player, new String[]{action})).toList(), current);
        }
        if (!allowed(player, arguments.length == 2 && arguments[0].equals("spawn") ? new String[]{"spawn"} : arguments)) {
            return List.of();
        }
        if (arguments[0].equals("spawn")) {
            if (arguments.length == 2) {
                return matching(List.of("permanent", "temporary").stream()
                    .filter(type -> player.hasPermission("vestri.servers.spawn." + type)).toList(), current);
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
        if (arguments.length == 2 && List.of("retry", "join", "delete", "invite", "revoke").contains(arguments[0])) {
            var choices = arguments[0].equals("join") ? registry.accessible(player.getUniqueId(), adminJoin(player))
                    : registry.owned(player.getUniqueId());
            return matching(choices.stream().map(this::serverName).toList(), current);
        }
        if (arguments.length == 3 && arguments[0].equals("invite")) {
            return matching(proxy.getAllPlayers().stream().filter(other -> !other.getUniqueId().equals(player.getUniqueId()))
                    .map(Player::getUsername).toList(), current);
        }
        if (arguments.length == 3 && arguments[0].equals("revoke")) {
            try {
                return matching(List.copyOf(registry.require(player.getUniqueId(), arguments[1]).invited.values()), current);
            } catch (IOException failure) {
                return List.of();
            }
        }
        return arguments[0].equals("delete") && arguments.length == 3 ? matching(List.of("--confirm"), current) : List.of();
    }

    private static List<String> matching(List<String> choices, String prefix) {
        return choices.stream().filter(choice -> choice.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))).sorted().toList();
    }

    @Override
    public void execute(Invocation invocation) {
        if (!hasPermission(invocation)) {
            if (invocation.source() instanceof Player player) {
                error(player, "You do not have permission to use that command.");
            }
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
                return invocation.source() instanceof Player && invocation.source().hasPermission("vestri.servers")
                        && invocation.source().hasPermission("vestri.servers.pause");
            }

            @Override
            public List<String> suggest(Invocation invocation) {
                if (!hasPermission(invocation) || invocation.arguments().length > 1) {
                    return List.of();
                }
                String prefix = invocation.arguments().length == 0 ? "" : invocation.arguments()[0];
                return matching(registry.owned(((Player) invocation.source()).getUniqueId()).stream()
                        .filter(entry -> entry.type == OwnedServerRegistry.Type.TEMPORARY && entry.serverId != null)
                        .map(PlayerServers.this::serverName).toList(), prefix);
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
                        message(player, "/vestripause <server> (toggles pause)");
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
        if (!player.hasPermission("vestri.servers") || !allowed(player, arguments)) {
            throw new IOException("You do not have permission to use that command.");
        }
        boolean renamed = false;
        for (var entry : registry.all()) {
            String previous = entry.name;
            serverName(entry);
            renamed = renamed || !java.util.Objects.equals(previous, entry.name);
        }
        if (renamed) {
            registry.save();
        }
        if (arguments.length == 0 || (arguments.length == 1 && arguments[0].equals("list"))) {
            List<OwnedServerRegistry.Entry> accessible = registry.accessible(player.getUniqueId(), adminJoin(player));
            message(player, accessible.isEmpty() ? "No servers to show yet." : "Your available servers:");
            for (var entry : accessible) {
                String name = serverName(entry);
                String detail = entry.type.name().toLowerCase(Locale.ROOT) + " | " + stateLabel(entry)
                        + (entry.owner.equals(player.getUniqueId()) ? " | yours" : entry.invited.containsKey(player.getUniqueId()) ? " | invited" : " | admin")
                        + (entry.paused ? " | paused" : "")
                        + (entry.state == OwnedServerRegistry.State.PENDING_DELETION
                            ? " | kept for " + Math.max(0, (entry.deleteAfter - clock.getAsLong() + 999) / 1000) + "s" : "")
                        + (entry.state == OwnedServerRegistry.State.PROVISIONING && entry.preparationDeleteAfter > 0
                            ? " | cleanup in " + Math.max(0, (entry.preparationDeleteAfter - clock.getAsLong() + 999) / 1000) + "s" : "");
                Component row = Component.text(name, NamedTextColor.AQUA).append(Component.text("  " + detail, NamedTextColor.GRAY));
                if (canUseJoin(player) && entry.serverId != null) {
                    row = row.clickEvent(ClickEvent.runCommand("/vestriservers join " + name))
                            .hoverEvent(HoverEvent.showText(Component.text("Join " + name, NamedTextColor.GREEN)));
                }
                player.sendMessage(row);
                if (entry.owner.equals(player.getUniqueId()) && !entry.invited.isEmpty()) {
                    message(player, "Invited to " + name + ": " + String.join(", ", entry.invited.values()));
                }
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
            message(player, "Preparing " + serverName(entry) + ". We'll connect you when it's ready.");
            spawn(player, entry, client);
        } else if ((arguments.length == 1 || arguments.length == 2) && arguments[0].equals("retry")) {
            var pending = registry.owned(player.getUniqueId()).stream()
                    .filter(entry -> entry.state == OwnedServerRegistry.State.PROVISIONING).toList();
            if (arguments.length == 1 && pending.size() != 1) {
                throw new IOException("Choose a server from your list: /vestriservers retry <server>.");
            }
            var entry = arguments.length == 1 ? pending.get(0) : registry.require(player.getUniqueId(), arguments[1]);
            if (entry.state == OwnedServerRegistry.State.PROVISIONING) {
                spawn(player, entry, MinecraftInstanceClient.load(configuration));
            } else {
                if (!canUseJoin(player)) {
                    throw new IOException("You need join permission to reconnect to an existing server.");
                }
                join(player, entry, true);
            }
        } else if (arguments.length == 2 && arguments[0].equals("join")) {
            join(player, registry.requireJoin(player.getUniqueId(), arguments[1], adminJoin(player)), true);
        } else if (arguments.length == 3 && arguments[0].equals("invite")) {
            var entry = registry.require(player.getUniqueId(), arguments[1]);
            if (entry.type != OwnedServerRegistry.Type.PERMANENT || entry.serverId == null || entry.state == OwnedServerRegistry.State.DELETING) {
                throw new IOException("Invitations are available for created permanent servers only.");
            }
            Player guest = proxy.getPlayer(arguments[2]).filter(Player::isActive)
                    .orElseThrow(() -> new IOException("That player must be online to receive their first invitation."));
            if (guest.getUniqueId().equals(player.getUniqueId())) {
                throw new IOException("You already own this server.");
            }
            registry.invite(entry, guest.getUniqueId(), guest.getUsername());
            String name = serverName(entry);
            message(player, guest.getUsername() + " can now join " + name + ", even while you're offline.");
            guest.sendMessage(notice(Component.text(player.getUsername() + " invited you to ", NamedTextColor.WHITE)
                    .append(Component.text(name, NamedTextColor.AQUA))
                    .append(Component.text(". [Join]", NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.runCommand("/vestriservers join " + name))
                        .hoverEvent(HoverEvent.showText(Component.text("Join " + name))))));
        } else if (arguments.length == 3 && arguments[0].equals("revoke")) {
            var entry = registry.require(player.getUniqueId(), arguments[1]);
            registry.revoke(entry, arguments[2]);
            message(player, arguments[2] + " can no longer join " + serverName(entry) + ".");
        } else if (arguments.length == 3 && arguments[0].equals("delete") && arguments[2].equals("--confirm")) {
            var entry = registry.require(player.getUniqueId(), arguments[1]);
            if (occupied(entry)) {
                throw new IOException("Server is occupied. All players must leave before it can be deleted.");
            }
            delete(entry, MinecraftInstanceClient.load(configuration));
            message(player, serverName(entry) + " has been deleted.");
        } else {
            message(player, USAGE);
        }
    }

    private void spawn(Player player, OwnedServerRegistry.Entry entry, MinecraftInstanceClient client)
            throws IOException, InterruptedException {
        long timeout = Duration.ofSeconds(PlayerServerPolicy.load(configuration).preparationTimeoutSeconds()).toMillis();
        entry.preparationDeleteAfter = clock.getAsLong() + timeout;
        registry.save();
        MinecraftInstanceClient.SpawnedInstance instance;
        try {
            instance = client.spawnWithTarget(entry.template, entry.requestId);
        } catch (IOException | InterruptedException | RuntimeException failure) {
            entry.preparationDeleteAfter = clock.getAsLong() + timeout;
            try {
                registry.save();
            } catch (IOException persistenceFailure) {
                failure.addSuppressed(persistenceFailure);
            }
            throw failure;
        }
        entry.serverId = instance.serverId();
        entry.slug = instance.slug();
        if (instance.name() != null) {
            entry.name = instance.name();
        }
        entry.state = OwnedServerRegistry.State.ACTIVE;
        entry.preparationDeleteAfter = 0;
        entry.joinUntil = clock.getAsLong() + JOIN_WINDOW;
        registry.save();
        message(player, serverName(entry) + " is starting. Your connection will begin shortly.");
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
            message(player, "Preparing " + serverName(entry) + ". We'll connect you when it's ready.");
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
        message(player, entry.paused ? serverName(entry) + " is protected. It will sleep when empty and wake when you return."
            : serverName(entry) + " is no longer protected. The empty-server countdown is enabled.");
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
            message(player, "Removed " + serverName(entry) + " to make room for your new server.");
        }
        return registry.reserve(player.getUniqueId(), template, type, policy.limit(type), requestId);
    }

    private void join(Player player, OwnedServerRegistry.Entry entry) throws IOException, InterruptedException {
        join(player, entry, false);
    }

    private void join(Player player, OwnedServerRegistry.Entry entry, boolean enforceAccess) throws IOException, InterruptedException {
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
        message(player, "Connecting you to " + serverName(entry) + ".");
        transfer.start(player, entry.target(), logger::info, () -> !enforceAccess || player.hasPermission("vestri.servers")
            && canUseJoin(player) && registry.accessible(player.getUniqueId(), adminJoin(player)).contains(entry));
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
                if (entry.state == OwnedServerRegistry.State.PROVISIONING) {
                    if (entry.preparationDeleteAfter == 0) {
                        entry.preparationDeleteAfter = clock.getAsLong()
                                + Duration.ofSeconds(policy.preparationTimeoutSeconds()).toMillis();
                        registry.save();
                    } else if (clock.getAsLong() >= entry.preparationDeleteAfter && !occupied(entry)) {
                        delete(entry, MinecraftInstanceClient.load(configuration));
                        proxy.getPlayer(entry.owner).filter(Player::isActive).ifPresent(owner -> message(owner,
                                "Removed failed preparation " + serverName(entry) + "."));
                    }
                    continue;
                }
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
                            serverName(entry) + " is resting. You have " + policy.deletionGraceSeconds()
                            + " seconds to return: /vestriservers join " + serverName(entry)));
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
        if (entry.serverId == null) {
            var instance = client.findPreparation(entry.template, entry.requestId);
            if (instance == null) {
                registry.remove(entry);
                return;
            }
            entry.serverId = instance.serverId();
            entry.slug = instance.slug();
            if (instance.name() != null) {
                entry.name = instance.name();
            }
            registry.save();
        }
        if (occupied(entry)) {
            throw new IOException("Server is occupied. All players must leave before it can be deleted.");
        }
        entry.state = OwnedServerRegistry.State.DELETING;
        registry.save();
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
            logger.warn("Player server command failed: {}", failure.getMessage());
            String text = failure.getMessage();
            error(player, text != null && text.startsWith("Vestri API returned")
                    ? "The server request failed. Use /vestriservers list to retry or delete it, or contact a staff member."
                    : text == null ? "We couldn't complete that request. Please try again." : text);
        } catch (RuntimeException failure) {
            message(player, "Invalid server configuration or API response. Contact an administrator.");
        }
    }

    private static void message(Player player, String text) {
        player.sendMessage(notice(Component.text(safeMessage(text), NamedTextColor.WHITE)));
    }

    private static void error(Player player, String text) {
        player.sendMessage(notice(Component.text(safeMessage(text), NamedTextColor.RED)));
    }

    private static Component notice(Component content) {
        return Component.text("Vestri", NamedTextColor.DARK_AQUA)
                .append(Component.text(" > ", NamedTextColor.DARK_GRAY)).append(content);
    }

    private static String safeMessage(String text) {
        return text.replaceAll("(?i)[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}", "your server");
    }

    private String serverName(OwnedServerRegistry.Entry entry) {
        if (entry.slug != null) {
            var registered = proxy.getAllServers().stream().filter(server -> matches(entry, server)).findFirst();
            if (registered.isPresent()) {
                String name = registered.get().getServerInfo().getName();
                if (name.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}-[1-9][0-9]*")) {
                    entry.name = name;
                }
            }
        }
        return entry.displayName();
    }

    private static String stateLabel(OwnedServerRegistry.Entry entry) {
        return switch (entry.state) {
            case PROVISIONING -> "preparing";
            case ACTIVE -> "ready";
            case STARTING -> "starting";
            case STOPPING -> "shutting down";
            case STOPPED -> "sleeping";
            case PENDING_DELETION -> "waiting for your return";
            case DELETING -> "removing";
        };
    }
}