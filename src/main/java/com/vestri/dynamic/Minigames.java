package com.vestri.dynamic;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

final class Minigames implements SimpleCommand, AutoCloseable {
    private static final Gson JSON = new GsonBuilder().create();
    record Member(String uuid, String name, String team) {}
    record Binding(String name, String serverId, String template, String token, String spawnRequestId, JsonObject specification) {}
    static final class Instance {
        String template;
        String requestId;
        String slug;
        String serverId;
        String state = "provisioning";
        String matchId;
        int rosterVersion;
        JsonObject specification;
        JsonObject lifecycleConfig;
        boolean lifecycleCredentialsUnverified;
        transient boolean lifecycleCredentialsWarned;
        List<Member> roster = List.of();
        List<Matchmaker.Group> groups = List.of();
        long created;
        long deadline;
        transient long heartbeat;
        transient long emptySince;
        boolean transferPending;
        List<UUID> pendingTransfers = List.of();
        transient boolean rosterJoined;
        transient int acknowledgedRosterVersion;
        transient boolean startupWarned;
        transient boolean requeue;
        transient boolean busy;
        transient long retryAfter;
        transient boolean externallyDeleted;
        transient Binding removedBinding;
        transient RegisteredServer retiredTarget;
        transient Set<String> joinedPlayers = new java.util.HashSet<>();
        List<UUID> reservationPlayers = List.of();
        transient Set<UUID> targetedLeaders = new java.util.HashSet<>();
    }
    private record PendingTargetJoin(MinecraftInstanceClient.SpawnedInstance target, Matchmaker.Group group,
                                     Consumer<String> output, long deadline) {}
    private final ProxyServer proxy;
    private final Parties parties;
    private final Path config;
    private final Path ledger;
    private final Logger logger;
    private final LongSupplier clock;
    private final Map<String, MinigameSpec> templates = new LinkedHashMap<>();
    private final Map<String, JsonObject> templateSpecifications = new LinkedHashMap<>();
    private final Map<String, Binding> bindings = new LinkedHashMap<>();
    private final Map<String, List<Matchmaker.Group>> queues = new LinkedHashMap<>();
    private final List<Instance> instances = new ArrayList<>();
    private final Map<UUID, PendingTargetJoin> pendingTargetJoins = new LinkedHashMap<>();
    private HttpServer control;
    private final ThreadPoolExecutor network = executor("vestri-minigame-lifecycle", 1, 100);
    private final ThreadPoolExecutor requests = executor("vestri-minigame-control", 4, 32);
    private boolean closed;
    private boolean persistenceFailed;
    private final long initialized;

    Minigames(ProxyServer proxy, Parties parties, Path directory, Logger logger) throws IOException {
        this(proxy, parties, directory, logger, System::currentTimeMillis);
    }

    Minigames(ProxyServer proxy, Parties parties, Path directory, Logger logger, LongSupplier clock) throws IOException {
        this.proxy = proxy;
        this.parties = parties;
        this.config = directory.resolve("vestri-dynamic-config.json");
        this.ledger = directory.resolve("vestri-minigames.json");
        this.logger = logger;
        this.clock = clock;
        this.initialized = clock.getAsLong();
        if (Files.exists(ledger)) {
            if (Files.size(ledger) > 1024 * 1024) {
                throw new IOException("Minigame ledger exceeds 1 MiB");
            }
            try {
                Instance[] saved = JSON.fromJson(Files.readString(ledger), Instance[].class);
                if (saved == null || saved.length > 100) {
                    throw new IllegalArgumentException();
                }
                for (Instance instance : saved) {
                    UUID.fromString(instance.requestId);
                    if (instance.template == null || instance.template.isBlank() || instance.created < 0) {
                        throw new IllegalArgumentException();
                    }
                    boolean permanent = instance.specification != null && MinigameSpec.parse(instance.specification).permanent();
                    if (instance.reservationPlayers == null) {
                        instance.reservationPlayers = List.of();
                    }
                    if (instance.pendingTransfers == null) {
                        instance.pendingTransfers = List.of();
                    }
                    if (!Set.of("provisioning", "ready").contains(instance.state)
                            && !(permanent && Set.of("running", "unavailable").contains(instance.state))) {
                        instance.state = permanent ? "unavailable" : "ending";
                    }
                    if (permanent && instance.state.equals("running")) {
                        if (instance.deadline == 0) {
                            instance.roster.forEach(member -> instance.joinedPlayers.add(member.uuid));
                        } else {
                            instance.roster.stream().filter(member -> !instance.reservationPlayers.contains(UUID.fromString(member.uuid)))
                                    .forEach(member -> instance.joinedPlayers.add(member.uuid));
                        }
                    }
                    instances.add(instance);
                }
            } catch (RuntimeException failure) {
                throw new IOException("Invalid minigame ledger; refusing to create duplicate instances", failure);
            }
        }
    }

    private static ThreadPoolExecutor executor(String name, int threads, int capacity) {
        return new ThreadPoolExecutor(threads, threads, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(capacity), task -> {
                    Thread thread = new Thread(task, name);
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    synchronized void synchronize(JsonObject manifest) throws IOException {
        Map<String, MinigameSpec> nextTemplates = new LinkedHashMap<>();
        Map<String, JsonObject> nextSpecifications = new LinkedHashMap<>();
        Map<String, Binding> nextBindings = new LinkedHashMap<>();
        Set<String> present = new java.util.HashSet<>();
        try {
            JsonObject games = manifest.getAsJsonObject("minigames");
            if (games != null) {
                if (games.size() > 100) {
                    throw new IllegalArgumentException();
                }
                games.entrySet().forEach(entry -> {
                    JsonObject specification = entry.getValue().getAsJsonObject();
                    nextTemplates.put(entry.getKey(), MinigameSpec.parse(specification));
                    nextSpecifications.put(entry.getKey(), specification.deepCopy());
                });
            }
            for (var entry : manifest.getAsJsonObject("servers").entrySet()) {
                JsonObject target = entry.getValue().getAsJsonObject();
                if (target.has("serverId")) {
                    present.add(UUID.fromString(target.get("serverId").getAsString()).toString());
                }
                if (!target.has("token")) {
                    continue;
                }
                String template = target.get("template").getAsString();
                String token = target.get("token").getAsString();
                String id = target.get("serverId").getAsString();
                UUID.fromString(id);
                if (!nextTemplates.containsKey(template) || !token.matches("[a-fA-F0-9]{64}")) {
                    throw new IllegalArgumentException();
                }
                String requestId = target.has("spawnRequestId") ? target.get("spawnRequestId").getAsString() : null;
                if (requestId != null) {
                    UUID.fromString(requestId);
                }
                JsonObject specification = target.has("spec") ? target.getAsJsonObject("spec").deepCopy() : nextSpecifications.get(template).deepCopy();
                MinigameSpec.parse(specification);
                nextBindings.put(entry.getKey(), new Binding(target.get("name").getAsString(), id, template, token, requestId, specification));
            }
        } catch (RuntimeException failure) {
            throw new IOException("Invalid minigame manifest", failure);
        }
        if (!nextTemplates.isEmpty() && control == null) {
            control = HttpServer.create(new InetSocketAddress("0.0.0.0", 25580), 16);
            control.setExecutor(requests);
            control.createContext("/minigame/", this::heartbeat);
            control.start();
        }
        if (manifest.has("authoritative") && manifest.get("authoritative").isJsonPrimitive()
                && manifest.get("authoritative").getAsJsonPrimitive().isBoolean()
                && manifest.get("authoritative").getAsBoolean()) {
            for (Instance instance : instances) {
                Binding previous = bindings.get(instance.slug);
                String id = instance.serverId != null ? instance.serverId : previous == null ? null : previous.serverId;
                if (id != null && !present.contains(id)) {
                    instance.serverId = id;
                    instance.externallyDeleted = true;
                    if (previous != null) {
                        instance.removedBinding = previous;
                        instance.retiredTarget = registeredTarget(instance.slug, previous).orElse(instance.retiredTarget);
                    }
                    end(instance, false);
                }
            }
        }
        templates.clear();
        templates.putAll(nextTemplates);
        templateSpecifications.clear();
        templateSpecifications.putAll(nextSpecifications);
        bindings.clear();
        bindings.putAll(nextBindings);
        for (var entry : bindings.entrySet()) {
            Instance pending = instances.stream().filter(instance -> entry.getKey().equals(instance.slug)
                    || entry.getValue().serverId.equals(instance.serverId)
                    || entry.getValue().spawnRequestId != null && entry.getValue().spawnRequestId.equals(instance.requestId)
                        && entry.getValue().template.equals(instance.template)).findFirst().orElse(null);
            if (pending != null) {
                pending.slug = entry.getKey();
                pending.serverId = entry.getValue().serverId;
            } else {
                Instance instance = new Instance();
                instance.template = entry.getValue().template;
                instance.requestId = entry.getValue().spawnRequestId == null ? UUID.randomUUID().toString() : entry.getValue().spawnRequestId;
                instance.slug = entry.getKey();
                instance.serverId = entry.getValue().serverId;
                instance.created = clock.getAsLong();
                instance.state = "provisioning";
                try {
                    captureLifecycleConfiguration(instance, true);
                } catch (IOException failure) {
                    instance.lifecycleCredentialsUnverified = true;
                }
                instances.add(instance);
            }
            Instance bound = instances.stream().filter(instance -> entry.getKey().equals(instance.slug)).findFirst().orElseThrow();
            bound.retiredTarget = registeredTarget(entry.getKey(), entry.getValue()).orElse(bound.retiredTarget);
            if (bound.specification == null) {
                bound.specification = entry.getValue().specification.deepCopy();
            }
            if (Set.of("ready", "provisioning").contains(bound.state)
                    && !compatible(bound.specification, nextSpecifications.get(bound.template))) {
                end(bound, false);
            }
            if (settings(bound).permanent()
                    && !compatible(bound.specification, nextSpecifications.get(bound.template))) {
                end(bound, false);
            }
        }
        save();
    }

    @Override
    public synchronized void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(Component.text("This command is player-only."));
            return;
        }
        try {
            UUID actor = player.getUniqueId();
            if (!parties.leader(actor).equals(actor)) {
                throw new IllegalArgumentException("Only the party leader can queue or leave.");
            }
            String[] arguments = invocation.arguments();
            if (arguments.length == 1 && "leave".equals(arguments[0])) {
                pendingTargetJoins.remove(actor);
                queues.values().forEach(queue -> queue.removeIf(group -> group.leader().equals(actor)));
                for (Instance instance : instances) {
                    if (instance.groups.stream().anyMatch(group -> group.leader().equals(actor))
                            && (Set.of("joining", "starting").contains(instance.state)
                                || instance.state.equals("running") && instance.deadline > 0)) {
                        end(instance, false);
                    }
                }
                player.sendMessage(Component.text("You left the minigame queue."));
                return;
            }
            if (arguments.length == 0 || arguments.length == 1 && "list".equals(arguments[0])) {
                player.sendMessage(Component.text("Minigames: " + String.join(", ", templates.keySet())));
                return;
            }
            if (arguments.length < 2 || !"join".equals(arguments[0])) {
                throw new IllegalArgumentException("Usage: /minigame <join <template>|leave|list>");
            }
            if (closed || persistenceFailed || control == null) {
                throw new IllegalArgumentException("Minigame matchmaking is unavailable.");
            }
            String requested = String.join(" ", java.util.Arrays.copyOfRange(arguments, 1, arguments.length));
            String template = templates.keySet().stream().filter(name -> name.equalsIgnoreCase(requested)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown minigame template."));
            MinigameSpec spec = templates.get(template);
            if (!configured(template)) {
                throw new IllegalArgumentException("This minigame needs its template API key configured by an administrator.");
            }
            List<UUID> members = parties.members(actor);
            if (!spec.teamfree() && members.size() > spec.teamSize() || members.size() > spec.maxPlayers()) {
                throw new IllegalArgumentException("Your party is too large for this minigame.");
            }
            for (UUID member : members) {
                Player online = proxy.getPlayer(member).orElseThrow(() -> new IllegalArgumentException("Every party member must be online."));
                if (!online.hasPermission("vestri.minigames") || queuedOrPlaying(member)) {
                    throw new IllegalArgumentException("A party member lacks permission or is already queued or playing.");
                }
            }
            queues.computeIfAbsent(template, ignored -> new ArrayList<>()).add(new Matchmaker.Group(actor, members));
            tell(members, "Queued for " + template + ".");
        } catch (IllegalArgumentException failure) {
            player.sendMessage(Component.text(failure.getMessage()));
        }
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source() instanceof Player && invocation.source().hasPermission("vestri.minigames");
    }

    @Override
    public synchronized List<String> suggest(Invocation invocation) {
        return invocation.arguments().length <= 1 ? List.of("join", "leave", "list") : List.copyOf(templates.keySet());
    }

    private boolean queuedOrPlaying(UUID player) {
        return queuedOrPlaying(player, null, null);
    }

    private boolean queuedOrPlaying(UUID player, Instance target, UUID waiter) {
        return queues.values().stream().flatMap(List::stream).anyMatch(group -> group.players().contains(player))
                || instances.stream().anyMatch(instance -> instance != target && !instance.state.equals("ending")
                    && instance.roster.stream().anyMatch(member -> member.uuid.equals(player.toString())))
                || pendingTargetJoins.entrySet().stream().anyMatch(entry -> !entry.getKey().equals(waiter)
                    && entry.getValue().group.players().contains(player));
    }

    synchronized boolean joinInstance(Player player, MinecraftInstanceClient.SpawnedInstance target, Consumer<String> output) {
        Binding binding = bindings.get(target.slug());
        Instance instance = instances.stream().filter(value -> target.serverId().equals(value.serverId)
                && target.slug().equals(value.slug)).findFirst().orElse(null);
        if (binding == null && instance == null) {
            return false;
        }
        try {
            if (closed || persistenceFailed || control == null) {
                throw new IllegalArgumentException("Minigame admission is unavailable.");
            }
            if (!player.hasPermission("vestri.minigames")) {
                throw new IllegalArgumentException("You need vestri.minigames permission to join this minigame.");
            }
            if (binding != null && !binding.serverId.equals(target.serverId())
                    || instance == null || instance.externallyDeleted || instance.state.equals("ending")) {
                throw new IllegalArgumentException("This specific minigame instance is unavailable.");
            }
            MinigameSpec spec = settings(instance);
            if (spec == null || !compatible(instance.specification, templateSpecifications.get(instance.template))) {
                throw new IllegalArgumentException("This specific minigame instance is unavailable.");
            }
            UUID actor = player.getUniqueId();
            boolean rostered = instance.roster.stream().anyMatch(member -> member.uuid.equals(actor.toString()));
            if (rostered) {
                if (instance.pendingTransfers.contains(actor) || !Set.of("joining", "starting", "running").contains(instance.state)
                        || binding == null || registeredTarget(target.slug(), binding).isEmpty()) {
                    output.accept("Awaiting the roster handshake for " + instance.template + ".");
                } else {
                    connect(instance, player, registeredTarget(target.slug(), binding).orElseThrow());
                }
                return true;
            }
            if (!parties.leader(actor).equals(actor)) {
                throw new IllegalArgumentException("Only the party leader can join a minigame instance.");
            }
            List<UUID> members = parties.members(actor);
            if (!spec.teamfree() && members.size() > spec.teamSize() || members.size() > spec.maxPlayers()) {
                throw new IllegalArgumentException("Your party is too large for this minigame.");
            }
            PendingTargetJoin pending = pendingTargetJoins.get(actor);
            if (pending != null && !pending.target.equals(target)) {
                throw new IllegalArgumentException("Your party is already waiting for another minigame instance; use /minigame leave first.");
            }
            for (UUID member : members) {
                Player online = proxy.getPlayer(member).orElseThrow(() -> new IllegalArgumentException("Every party member must be online."));
                if (!online.hasPermission("vestri.minigames") || queuedOrPlaying(member, instance, actor)) {
                    throw new IllegalArgumentException("A party member lacks permission or is already queued or playing elsewhere.");
                }
            }
            Matchmaker.Group group = new Matchmaker.Group(actor, members);
            if (!spec.nativeLobby()) {
                if (!configured(instance.template)) {
                    throw new IllegalArgumentException("This minigame needs its template API key configured by an administrator.");
                }
                queues.computeIfAbsent(instance.template, ignored -> new ArrayList<>()).add(group);
                output.accept("Waiting for minPlayers (" + spec.minPlayers() + ") in the normal " + instance.template
                        + " queue, not direct instance entry; use /minigame join " + instance.template + ".");
                return true;
            }
            List<UUID> additions = members.stream().filter(member -> instance.roster.stream()
                    .noneMatch(existing -> existing.uuid.equals(member.toString()))).toList();
            if (additions.size() != members.size()) {
                throw new IllegalArgumentException("A party member is already on this roster; reconnect individually or finish the current reservation first.");
            }
            if (pending == null) {
                pending = new PendingTargetJoin(target, group, output, clock.getAsLong() + 300000);
                pendingTargetJoins.put(actor, pending);
            }
            if (!admitTarget(instance, pending, clock.getAsLong())) {
                output.accept("Waiting for this specific instance to become ready and complete its roster handshake (up to 300s).");
            }
        } catch (IllegalArgumentException failure) {
            output.accept(failure.getMessage());
        }
        return true;
    }

    private boolean admitTarget(Instance instance, PendingTargetJoin pending, long now) {
        Binding binding = bindings.get(pending.target.slug());
        if (binding == null || !binding.serverId.equals(pending.target.serverId())
                || registeredTarget(pending.target.slug(), binding).isEmpty()
                || instance.heartbeat == 0 || now - instance.heartbeat >= 5000
                || !(instance.state.equals("ready") || instance.state.equals("running") && !instance.transferPending
                    && instance.rosterJoined && instance.deadline == 0
                    && occupants(instance).stream().map(Player::getUniqueId).collect(java.util.stream.Collectors.toSet())
                        .containsAll(assignments(instance).keySet()))) {
            return false;
        }
        MinigameSpec spec = settings(instance);
        List<Matchmaker.Group> selected = new ArrayList<>(List.of(pending.group));
        Matchmaker.Match match = Matchmaker.select(selected, spec.teams(), spec.teamSize(), 1, spec.maxPlayers(), assignments(instance));
        pendingTargetJoins.remove(pending.group.leader());
        if (match == null) {
            pending.output.accept("This specific minigame instance has insufficient capacity for your whole party.");
            return true;
        }
        instance.targetedLeaders.add(pending.group.leader());
        if (instance.state.equals("ready")) {
            reserve(instance, match, selected, now);
        } else {
            admit(instance, match, selected, now);
        }
        pending.output.accept("Reserved this specific minigame instance; awaiting the complete roster handshake before transfer.");
        return true;
    }

    synchronized void beforeConnect(ServerPreConnectEvent event) {
        RegisteredServer target = event.getResult().getServer().orElse(null);
        if (target == null) {
            return;
        }
        Binding binding = bindings.entrySet().stream().filter(entry -> entry.getValue().name.equals(target.getServerInfo().getName())
            && target.getServerInfo().getAddress().getPort() == 25565
            && target.getServerInfo().getAddress().getHostString().equals("vestri-" + entry.getKey()))
            .map(Map.Entry::getValue).findFirst().orElse(null);
        if (binding == null) {
            return;
        }
        Instance instance = instances.stream().filter(value -> binding.serverId.equals(value.serverId)).findFirst().orElse(null);
        if (instance != null && settings(instance).nativeLobby()
                && instance.roster.stream().noneMatch(member -> member.uuid.equals(event.getPlayer().getUniqueId().toString()))
                && event.getPlayer().getCurrentServer().isPresent()) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            joinInstance(event.getPlayer(), new MinecraftInstanceClient.SpawnedInstance(binding.serverId, instance.slug, binding.name),
                    message -> event.getPlayer().sendMessage(Component.text(message)));
            return;
        }
        if (instance == null || !Set.of("joining", "starting", "running").contains(instance.state)
            || !event.getPlayer().hasPermission("vestri.minigames")
            || settings(instance).permanent() && instance.acknowledgedRosterVersion != instance.rosterVersion
            || instance.pendingTransfers.contains(event.getPlayer().getUniqueId())
                || instance.roster.stream().noneMatch(member -> member.uuid.equals(event.getPlayer().getUniqueId().toString()))) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
                event.getPlayer().sendMessage(Component.text(!event.getPlayer().hasPermission("vestri.minigames")
                        ? "You need vestri.minigames permission to join this minigame."
                        : instance != null && instance.pendingTransfers.contains(event.getPlayer().getUniqueId())
                            ? "Awaiting the complete roster handshake before transfer."
                            : "This minigame instance is not available for direct entry; use /minigame join " + binding.template + "."));
        }
    }

    synchronized boolean isMatchServer(RegisteredServer server) {
        return bindings.values().stream().anyMatch(binding -> binding.name.equals(server.getServerInfo().getName()));
    }

    synchronized void disconnected(UUID player) {
        queues.values().forEach(queue -> queue.removeIf(group -> group.players().contains(player)));
        pendingTargetJoins.entrySet().removeIf(entry -> entry.getValue().group.players().contains(player));
    }

    synchronized void sweep() {
        if (closed || persistenceFailed || control == null && instances.stream().noneMatch(instance -> instance.externallyDeleted)) {
            return;
        }
        long now = clock.getAsLong();
        queues.values().forEach(queue -> queue.removeIf(group -> !validGroup(group)));
        for (Instance instance : List.copyOf(instances)) {
            MinigameSpec spec = settings(instance);
            if (instance.state.equals("provisioning") && instance.heartbeat == 0
                    && !instance.startupWarned && now - instance.created >= 30000) {
                instance.startupWarned = true;
                logger.warn("Minigame startup stalled template={} instanceId={} requestId={}: no agent report after 30s; check server startup and agent configuration, and verify proxy DNS and port 25580 reachability from Paper",
                        safeLog(instance.template), instance.serverId, instance.requestId);
            }
            if (!templates.containsKey(instance.template)) {
                end(instance, false);
            } else if (instance.state.equals("provisioning") && now - instance.created >= 300000) {
                end(instance, false);
                } else if ((Set.of("joining", "starting").contains(instance.state)
                    || Set.of("running", "unavailable").contains(instance.state) && instance.deadline > 0) && now >= instance.deadline) {
                if (spec != null && spec.permanent() && !instance.externallyDeleted) {
                    expireReservation(instance);
                } else {
                    end(instance, true);
                }
            } else if (Set.of("ready", "running").contains(instance.state)
                    && now - (instance.heartbeat == 0 ? Math.max(instance.created, initialized) : instance.heartbeat) > 30000) {
                end(instance, false);
            }
            if (instance.state.equals("running") && instance.deadline == 0 && spec != null && spec.whenEmpty()) {
                if (occupants(instance).isEmpty()) {
                    if (instance.emptySince == 0) {
                        instance.emptySince = now;
                    } else if (now - instance.emptySince >= spec.emptyGrace()) {
                        end(instance, false);
                    }
                } else {
                    instance.emptySince = 0;
                }
            }
            if (instance.state.equals("ending")) {
                evacuate(instance, spec);
                if (occupants(instance).isEmpty()) {
                    submit(instance, () -> cleanup(instance));
                }
            } else if (instance.state.equals("provisioning")) {
                submit(instance, () -> provision(instance));
            } else if (instance.state.equals("unavailable") && spec != null && spec.permanent()
                    && !instance.externallyDeleted && instance.heartbeat == 0 && instance.matchId == null
                    && templates.containsKey(instance.template)
                    && compatible(instance.specification, templateSpecifications.get(instance.template))) {
                submit(instance, () -> recoverStartup(instance));
            }
        }
        for (var entry : List.copyOf(pendingTargetJoins.entrySet())) {
            PendingTargetJoin pending = entry.getValue();
            Instance instance = instances.stream().filter(value -> pending.target.serverId().equals(value.serverId)
                    && pending.target.slug().equals(value.slug)).findFirst().orElse(null);
            if (now >= pending.deadline || !validGroup(pending.group) || instance == null || instance.externallyDeleted
                    || instance.state.equals("ending") || !templates.containsKey(instance.template)
                    || !compatible(instance.specification, templateSpecifications.get(instance.template))
                    || pending.group.players().stream().anyMatch(player -> queuedOrPlaying(player, instance, entry.getKey()))) {
                pendingTargetJoins.remove(entry.getKey());
                pending.output.accept("Specific-instance join expired or became unavailable; no other server was requested.");
            } else {
                admitTarget(instance, pending, now);
            }
        }
        for (var template : templates.entrySet()) {
            String name = template.getKey();
            MinigameSpec spec = template.getValue();
            List<Matchmaker.Group> queue = queues.computeIfAbsent(name, ignored -> new ArrayList<>());
            if (spec.nativeLobby()) {
                for (Instance nativeInstance : instances) {
                    if (name.equals(nativeInstance.template) && nativeInstance.state.equals("running")
                            && !nativeInstance.transferPending && nativeInstance.rosterJoined
                            && occupants(nativeInstance).stream().map(Player::getUniqueId).collect(java.util.stream.Collectors.toSet())
                                .containsAll(assignments(nativeInstance).keySet())
                            && now - nativeInstance.heartbeat < 5000
                            && compatible(nativeInstance.specification, templateSpecifications.get(name))) {
                        Matchmaker.Match additions = Matchmaker.select(queue, spec.teams(), spec.teamSize(), 1,
                                spec.maxPlayers(), assignments(nativeInstance));
                        if (additions != null) {
                            admit(nativeInstance, additions, queue, now);
                        }
                    }
                }
            }
            Instance available = instances.stream().filter(instance -> name.equals(instance.template) && instance.state.equals("ready")
                    && (instance.specification == null || compatible(instance.specification, templateSpecifications.get(name)))
                    && instance.heartbeat > 0 && now - instance.heartbeat < 5000).findFirst().orElse(null);
            Matchmaker.Match match = Matchmaker.select(queue, spec.teams(), spec.teamSize(), spec.nativeLobby() ? 1 : spec.minPlayers(), spec.maxPlayers());
            if (spec.nativeLobby() && instances.stream().anyMatch(instance -> name.equals(instance.template)
                    && Set.of("joining", "running").contains(instance.state)
                    && compatible(instance.specification, templateSpecifications.get(name))
                    && Matchmaker.select(queue, spec.teams(), spec.teamSize(), 1, spec.maxPlayers(), assignments(instance)) != null)) {
                match = null;
            }
            if (available != null && match != null) {
                reserve(available, match, queue, now);
            }
            long ready = instances.stream().filter(instance -> name.equals(instance.template)
                    && (spec.permanent() ? !instance.state.equals("ending")
                        : Set.of("ready", "provisioning").contains(instance.state))).count();
            long total = instances.stream().filter(instance -> name.equals(instance.template)).count();
                boolean waitingPermanent = spec.permanent() && instances.stream().anyMatch(instance -> name.equals(instance.template)
                    && Set.of("ready", "provisioning", "unavailable").contains(instance.state));
                long demand = match != null && available == null && !waitingPermanent ? spec.permanent() ? ready + 1 : 1 : 0;
                long wanted = Math.max(spec.readyServers(), demand);
            if (ready < wanted && total < spec.maxInstances() && instances.size() < 100 && configured(name)) {
                Instance instance = new Instance();
                instance.template = name;
                instance.requestId = UUID.randomUUID().toString();
                instance.created = now;
                instance.specification = templateSpecifications.get(name).deepCopy();
                try {
                    captureLifecycleConfiguration(instance, false);
                } catch (IOException failure) {
                    continue;
                }
                instances.add(instance);
                save();
                lifecycleLog("preparing", instance);
            }
        }
    }

    private boolean configured(String template) {
        try {
            return MinecraftInstanceClient.load(config).templates().contains(template.toLowerCase(java.util.Locale.ROOT));
        } catch (IOException failure) {
            return false;
        }
    }

    private void captureLifecycleConfiguration(Instance instance, boolean unverified) throws IOException {
        if (instance.lifecycleConfig == null) {
            instance.lifecycleConfig = MinecraftInstanceClient.snapshot(config, instance.template);
            instance.lifecycleCredentialsUnverified = unverified;
        }
    }

    private synchronized MinecraftInstanceClient lifecycleClient(Instance instance) throws IOException {
        if (instance.lifecycleConfig == null) {
            captureLifecycleConfiguration(instance, true);
        }
        if (instance.lifecycleCredentialsUnverified && !instance.lifecycleCredentialsWarned) {
            instance.lifecycleCredentialsWarned = true;
            logger.warn("Minigame original lifecycle credentials unavailable template={} instanceId={} requestId={}; current credentials pinned, authorization failures retain the record",
                    safeLog(instance.template), instance.serverId, instance.requestId);
        }
        JsonObject snapshot = instance.lifecycleConfig;
        if (snapshot.size() != 2 || !snapshot.has("templates") || !snapshot.get("templates").isJsonObject()
                || snapshot.getAsJsonObject("templates").size() != 1
                || !snapshot.getAsJsonObject("templates").has(instance.template)) {
            throw new IOException("Invalid minigame lifecycle configuration; retaining the record");
        }
        MinecraftInstanceClient client = new MinecraftInstanceClient(snapshot);
        save();
        if (persistenceFailed) {
            throw new IOException("Lifecycle credentials could not be persisted; retaining the record");
        }
        if (instance.lifecycleCredentialsUnverified && instance.serverId == null) {
            throw new IOException("Original preparation credentials unavailable; retaining pending quota for administrator recovery");
        }
        return client;
    }

    private Path privateLedgerTemporary() throws IOException {
        Path directory = ledger.getParent();
        boolean posix = Files.getFileStore(directory).supportsFileAttributeView("posix");
        if (posix) {
            Files.setPosixFilePermissions(directory, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            if (Files.exists(ledger)) {
                Files.setPosixFilePermissions(ledger, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            }
            return Files.createTempFile(directory, "vestri-minigames-", ".tmp",
                    java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                            java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
        }
        return Files.createTempFile(directory, "vestri-minigames-", ".tmp");
    }

    private MinigameSpec settings(Instance instance) {
        return instance.specification == null ? templates.get(instance.template) : MinigameSpec.parse(instance.specification);
    }

    private static boolean compatible(JsonObject instance, JsonObject template) {
        if (instance == null || template == null) {
            return false;
        }
        JsonObject saved = instance.deepCopy();
        JsonObject current = template.deepCopy();
        saved.remove("maxInstances");
        current.remove("maxInstances");
        saved.remove("readyServers");
        current.remove("readyServers");
        return saved.equals(current);
    }

    private boolean validGroup(Matchmaker.Group group) {
        return parties.leader(group.leader()).equals(group.leader())
                && parties.members(group.leader()).equals(group.players())
                && group.players().stream().allMatch(player -> proxy.getPlayer(player)
                    .map(online -> online.hasPermission("vestri.minigames")).orElse(false));
    }

    private void reserve(Instance instance, Matchmaker.Match match, List<Matchmaker.Group> queue, long now) {
        instance.matchId = UUID.randomUUID().toString();
        instance.state = "joining";
        admit(instance, match, queue, now);
        lifecycleLog("reserving", instance);
    }

    private Map<UUID, String> assignments(Instance instance) {
        Map<UUID, String> assignments = new LinkedHashMap<>();
        instance.roster.forEach(member -> assignments.put(UUID.fromString(member.uuid), member.team));
        return assignments;
    }

    private void admit(Instance instance, Matchmaker.Match match, List<Matchmaker.Group> queue, long now) {
        List<Member> roster = new ArrayList<>(instance.roster);
        match.teams().forEach((player, team) -> roster.add(new Member(player.toString(),
                proxy.getPlayer(player).orElseThrow().getUsername(), team)));
        instance.roster = List.copyOf(roster);
        List<Matchmaker.Group> groups = new ArrayList<>(instance.groups);
        groups.addAll(match.groups());
        instance.groups = List.copyOf(groups);
        instance.rosterVersion++;
        instance.pendingTransfers = List.copyOf(match.teams().keySet());
        instance.reservationPlayers = instance.pendingTransfers;
        instance.transferPending = true;
        instance.rosterJoined = false;
        instance.emptySince = 0;
        instance.deadline = now + (settings(instance).nativeLobby() ? settings(instance).joinTimeout() : 15000);
        save();
        if (!persistenceFailed) {
            queue.removeAll(match.groups());
            tell(instance.pendingTransfers, "Match found for " + instance.template + ".");
            if (settings(instance).nativeLobby()) {
                lifecycleLog("native admission", instance);
            }
        }
    }

    private void lifecycleLog(String event, Instance instance) {
        logger.info("Minigame {} template={} instanceId={} requestId={} rosterVersion={} players={}",
                event, safeLog(instance.template), instance.serverId, instance.requestId, instance.rosterVersion, instance.roster.size());
    }

    private static String safeLog(String message) {
        String sanitized = message.replaceAll("(?i)(?:vmc_)?[a-f0-9]{64}", "[redacted]")
                .replaceAll("[\\p{Cntrl}]", " ");
        return sanitized.substring(0, Math.min(512, sanitized.length()));
    }

    private synchronized void submit(Instance instance, Runnable action) {
        if (instance.busy || closed || persistenceFailed || clock.getAsLong() < instance.retryAfter) {
            return;
        }
        instance.busy = true;
        instance.retryAfter = clock.getAsLong() + 10000;
        try {
            network.execute(() -> {
                try {
                    action.run();
                } finally {
                    synchronized (this) {
                        instance.busy = false;
                    }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException failure) {
            instance.busy = false;
        }
    }

    private void provision(Instance instance) {
        try {
            MinecraftInstanceClient client = lifecycleClient(instance);
            MinecraftInstanceClient.SpawnedInstance target;
            synchronized (this) {
                if (!instance.state.equals("provisioning")) {
                    return;
                }
                Binding binding = bindings.get(instance.slug);
                if (instance.heartbeat > 0 || instance.serverId != null && (binding == null || binding.spawnRequestId == null)) {
                    return;
                }
            }
            target = client.spawnWithTarget(instance.template, UUID.fromString(instance.requestId));
            synchronized (this) {
                if (target != null) {
                    instance.slug = target.slug();
                    instance.serverId = target.serverId();
                }
                if (clock.getAsLong() - instance.created >= 300000) {
                    end(instance, false);
                }
                save();
            }
        } catch (IOException | InterruptedException | RuntimeException failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            logger.warn("Minigame preparation pending template={} requestId={} error={}: {}; the same request will be retried",
                    safeLog(instance.template), instance.requestId, failure.getClass().getSimpleName(), safeFailure(failure));
        }
    }

    static String safeFailure(Exception failure) {
        String message = failure.getMessage();
        if (message == null) {
            return "No error detail available";
        }
        return safeLog(message);
    }

    private void recoverStartup(Instance instance) {
        try {
            MinecraftInstanceClient client;
            String serverId;
            synchronized (this) {
                if (!instance.state.equals("unavailable") || instance.externallyDeleted || instance.heartbeat != 0) {
                    return;
                }
                instance.retryAfter = clock.getAsLong() + 60000;
                client = lifecycleClient(instance);
                serverId = instance.serverId;
            }
            if (serverId != null) {
                client.power(instance.template, UUID.fromString(serverId), true);
            } else {
                var target = client.spawnWithTarget(instance.template, UUID.fromString(instance.requestId));
                synchronized (this) {
                    if (target != null) {
                        instance.slug = target.slug();
                        instance.serverId = target.serverId();
                        save();
                    }
                }
            }
        } catch (IOException | InterruptedException | RuntimeException failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            logger.warn("Permanent minigame recovery pending template={} instanceId={} requestId={} error={}: {}; the same instance will be retried",
                    safeLog(instance.template), instance.serverId, instance.requestId,
                    failure.getClass().getSimpleName(), safeFailure(failure));
        }
    }

    private void cleanup(Instance instance) {
        try {
            synchronized (this) {
                if (!occupants(instance).isEmpty()) {
                    return;
                }
                if (instance.externallyDeleted) {
                    unregister(instance);
                    if (occupants(instance).isEmpty() && instances.remove(instance)) {
                        save();
                    }
                    return;
                }
            }
            MinecraftInstanceClient client = lifecycleClient(instance);
            String id;
            synchronized (this) {
                id = instance.serverId;
                Binding binding = bindings.get(instance.slug);
                if (binding != null) {
                    instance.removedBinding = binding;
                    instance.retiredTarget = registeredTarget(instance.slug, binding).orElse(instance.retiredTarget);
                }
            }
            if (id == null) {
                var target = client.findPreparation(instance.template, UUID.fromString(instance.requestId));
                if (target != null) {
                    id = target.serverId();
                    synchronized (this) {
                        instance.serverId = target.serverId();
                        instance.slug = target.slug();
                        save();
                    }
                }
            }
            if (id != null) {
                synchronized (this) {
                    if (instance.externallyDeleted) {
                        unregister(instance);
                        if (instances.remove(instance)) {
                            save();
                        }
                        return;
                    }
                }
                client.deleteIfPresent(instance.template, UUID.fromString(id));
            }
            synchronized (this) {
                unregister(instance);
                if (instances.remove(instance)) {
                    save();
                }
            }
        } catch (IOException | InterruptedException | RuntimeException failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            synchronized (this) {
                instance.retryAfter = clock.getAsLong() + 60000;
            }
            logger.warn("Minigame cleanup pending template={} instanceId={} requestId={} error={}: {}; deletion will be retried",
                    safeLog(instance.template), instance.serverId, instance.requestId,
                    failure.getClass().getSimpleName(), safeFailure(failure));
        }
    }

    private void end(Instance instance, boolean requeue) {
        MinigameSpec spec = settings(instance);
        if (!instance.externallyDeleted && spec != null && spec.permanent()) {
            if (!instance.state.equals("unavailable")) {
                instance.state = "unavailable";
                instance.rosterJoined = false;
                instance.acknowledgedRosterVersion = 0;
                save();
                lifecycleLog("unavailable", instance);
            }
            return;
        }
        if (instance.state.equals("ending")) {
            return;
        }
        instance.state = "ending";
        instance.requeue = requeue;
        save();
        lifecycleLog("end", instance);
        if (requeue && !persistenceFailed) {
            for (Matchmaker.Group group : instance.groups) {
                if (instance.targetedLeaders.contains(group.leader())) {
                    tell(group.players(), "Specific-instance join cancelled; no other server was requested.");
                    continue;
                }
                if (validGroup(group) && group.players().stream().noneMatch(this::queuedOrPlaying)) {
                    queues.computeIfAbsent(instance.template, ignored -> new ArrayList<>()).add(group);
                    tell(group.players(), "Match cancelled because not everyone joined; your party was requeued.");
                }
            }
        }
    }

    private void expireReservation(Instance instance) {
        Set<String> connected = occupants(instance).stream().map(player -> player.getUniqueId().toString())
                .collect(java.util.stream.Collectors.toSet());
        List<Matchmaker.Group> failed = instance.groups.stream().filter(group -> group.players().stream()
                .anyMatch(player -> (instance.reservationPlayers.isEmpty() || instance.reservationPlayers.contains(player))
                    && (!instance.joinedPlayers.contains(player.toString()) || !connected.contains(player.toString())))).toList();
        Set<String> removed = failed.stream().flatMap(group -> group.players().stream()).map(UUID::toString)
                .collect(java.util.stream.Collectors.toSet());
        evacuatePlayers(occupants(instance).stream().filter(player -> removed.contains(player.getUniqueId().toString())).toList(), settings(instance));
        removeRoster(instance, removed);
        instance.pendingTransfers = List.of();
        instance.reservationPlayers = List.of();
        instance.transferPending = false;
        instance.deadline = 0;
        if (!instance.state.equals("unavailable")) {
            instance.state = "running";
        }
        instance.rosterJoined = false;
        save();
        if (!persistenceFailed) {
            for (Matchmaker.Group group : failed) {
                if (instance.targetedLeaders.remove(group.leader())) {
                    tell(group.players(), "Specific-instance join cancelled; no other server was requested.");
                    continue;
                }
                if (validGroup(group) && group.players().stream().noneMatch(this::queuedOrPlaying)) {
                    queues.computeIfAbsent(instance.template, ignored -> new ArrayList<>()).add(group);
                    tell(group.players(), "Match cancelled because not everyone joined; your party was requeued.");
                }
            }
        }
    }

    private void removeRoster(Instance instance, Set<String> removed) {
        if (removed.isEmpty()) {
            return;
        }
        instance.roster = instance.roster.stream().filter(member -> !removed.contains(member.uuid)).toList();
        instance.groups = instance.groups.stream().filter(group -> group.players().stream()
                .noneMatch(player -> removed.contains(player.toString()))).toList();
        instance.joinedPlayers.removeAll(removed);
        instance.rosterVersion++;
        instance.rosterJoined = false;
        save();
        lifecycleLog("roster removal", instance);
    }

    private java.util.Optional<RegisteredServer> registeredTarget(String slug, Binding binding) {
        return proxy.getServer(binding.name).filter(server -> server.getServerInfo().getAddress().getPort() == 25565
                && server.getServerInfo().getAddress().getHostString().equals("vestri-" + slug));
    }

    private void unregister(Instance instance) {
        Binding binding = bindings.get(instance.slug);
        if (binding == null) {
            binding = instance.removedBinding;
        }
        if (binding != null && binding.serverId.equals(instance.serverId)) {
            registeredTarget(instance.slug, binding).ifPresent(server -> proxy.unregisterServer(server.getServerInfo()));
            bindings.remove(instance.slug, binding);
        }
    }

    private List<Player> occupants(Instance instance) {
        if (instance.retiredTarget != null) {
            return List.copyOf(instance.retiredTarget.getPlayersConnected());
        }
        Binding binding = bindings.get(instance.slug);
        if (binding == null) {
            binding = instance.removedBinding;
        }
        return binding == null ? List.of() : registeredTarget(instance.slug, binding).map(server -> List.copyOf(server.getPlayersConnected())).orElse(List.of());
    }

    private void evacuate(Instance instance, MinigameSpec spec) {
        evacuatePlayers(occupants(instance), spec);
    }

    private void evacuatePlayers(List<Player> players, MinigameSpec spec) {
        RegisteredServer fallback = spec == null ? null : proxy.getServer(spec.fallback()).orElse(null);
        for (Player player : players) {
            if (fallback == null || isMatchServer(fallback)) {
                player.disconnect(Component.text("The minigame has ended; the lobby is unavailable."));
            } else {
                player.createConnectionRequest(fallback).connect().thenAccept(result -> {
                    if (!result.isSuccessful()) {
                        player.disconnect(Component.text("The minigame has ended; could not return to the lobby."));
                    }
                });
            }
        }
    }

    private void tell(List<UUID> players, String message) {
        players.forEach(player -> proxy.getPlayer(player).ifPresent(online -> online.sendMessage(Component.text(message))));
    }

    private void connect(Instance instance, Player player, RegisteredServer server) {
        try {
            player.createConnectionRequest(server).connect().whenComplete((result, failure) -> {
                if (failure != null || result == null || !result.isSuccessful()) {
                    String reason = failure != null ? failure.getClass().getSimpleName()
                            : result == null ? "No connection result" : connectionReason(result);
                    logger.warn("Minigame transfer rejected template={} instanceId={} matchId={} player={} reason={}",
                            safeLog(instance.template), instance.serverId, instance.matchId,
                            player.getUniqueId(), safeLog(reason));
                    player.sendMessage(Component.text("Could not join the minigame. Check with an administrator; the match join timeout still applies."));
                } else {
                    logger.info("Minigame transfer connected template={} instanceId={} player={}",
                            safeLog(instance.template), instance.serverId, player.getUniqueId());
                }
            });
        } catch (RuntimeException failure) {
            logger.warn("Minigame transfer failed template={} instanceId={} player={} error={}: {}",
                    safeLog(instance.template), instance.serverId, player.getUniqueId(),
                    failure.getClass().getSimpleName(), safeFailure(failure));
        }
    }

    static String connectionReason(ConnectionRequestBuilder.Result result) {
        return result.getStatus().name() + result.getReasonComponent().map(reason -> ": " + reason).orElse("");
    }

    private void heartbeat(HttpExchange exchange) throws IOException {
        try {
            if (!exchange.getRequestMethod().equals("POST")) {
                respond(exchange, 405, "{}");
                return;
            }
            byte[] bytes = exchange.getRequestBody().readNBytes(65537);
            if (bytes.length > 65536) {
                respond(exchange, 413, "{}");
                return;
            }
            int code = 200;
            JsonObject response = new JsonObject();
            synchronized (this) {
                String slug = exchange.getRequestURI().getPath().substring("/minigame/".length());
                Binding binding = bindings.get(slug);
                String authorization = exchange.getRequestHeaders().getFirst("Authorization");
                if (binding == null || authorization == null || !MessageDigest.isEqual(
                        ("Bearer " + binding.token).getBytes(StandardCharsets.UTF_8), authorization.getBytes(StandardCharsets.UTF_8))) {
                    code = 403;
                } else {
                    Instance instance = instances.stream().filter(value -> binding.serverId.equals(value.serverId)).findFirst().orElse(null);
                    if (instance == null || persistenceFailed || closed) {
                        code = 503;
                    } else {
                        instance.slug = slug;
                        instance.serverId = binding.serverId;
                        JsonObject report = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
                        response = report(instance, report);
                    }
                }
            }
            respond(exchange, code, JSON.toJson(response));
        } catch (RuntimeException failure) {
            respond(exchange, 400, "{}");
            logger.warn("Rejected malformed minigame heartbeat");
        } finally {
            exchange.close();
        }
    }

    synchronized JsonObject report(Instance instance, JsonObject report) {
        long now = clock.getAsLong();
        String state = report.get("state").getAsString();
        if (!Set.of("joining", "ready", "running", "ended", "failed").contains(state)) {
            throw new IllegalArgumentException();
        }
        JsonArray online = report.getAsJsonArray("players");
        if (online == null || online.size() > 100) {
            throw new IllegalArgumentException();
        }
        Set<String> players = new java.util.HashSet<>();
        online.forEach(player -> players.add(UUID.fromString(player.getAsString()).toString()));
        String reportedMatch = report.has("matchId") && !report.get("matchId").isJsonNull() ? report.get("matchId").getAsString() : null;
        if (instance.matchId != null && reportedMatch != null && !instance.matchId.equals(reportedMatch)) {
            throw new IllegalArgumentException();
        }
        Integer reportedVersion = null;
        if (report.has("rosterVersion")) {
            var version = report.get("rosterVersion");
            if (!version.isJsonPrimitive() || !version.getAsJsonPrimitive().isNumber()) {
                throw new IllegalArgumentException();
            }
            reportedVersion = version.getAsBigDecimal().intValueExact();
            if (reportedVersion < 0 || reportedVersion > instance.rosterVersion) {
                throw new IllegalArgumentException();
            }
        }
        boolean nativeLobby = settings(instance).nativeLobby();
        boolean permanent = settings(instance).permanent();
        if (permanent && !instance.externallyDeleted && instance.deadline > 0 && now >= instance.deadline
                && Set.of("joining", "starting", "running", "unavailable").contains(instance.state)) {
            expireReservation(instance);
        }
        boolean acknowledged = instance.matchId != null && instance.matchId.equals(reportedMatch)
                && (!nativeLobby || reportedVersion != null && reportedVersion == instance.rosterVersion);
        instance.heartbeat = now;
        Binding liveBinding = bindings.get(instance.slug);
        if (liveBinding != null) {
            instance.retiredTarget = registeredTarget(instance.slug, liveBinding).orElse(instance.retiredTarget);
        }
        if (permanent && !instance.externallyDeleted && instance.state.equals("unavailable")
                && compatible(instance.specification, templateSpecifications.get(instance.template))) {
            if (instance.matchId == null && state.equals("ready") && reportedMatch == null && players.isEmpty()) {
                instance.state = "ready";
                save();
                lifecycleLog("recovered", instance);
            } else if (acknowledged && state.equals("running")) {
                instance.state = "running";
                save();
                lifecycleLog("recovered", instance);
            } else if (instance.matchId != null && state.equals("ready") && reportedMatch == null && players.isEmpty()) {
                instance.state = "joining";
                instance.acknowledgedRosterVersion = 0;
                instance.transferPending = true;
                if (instance.deadline == 0) {
                    instance.deadline = now + settings(instance).joinTimeout();
                }
                save();
                lifecycleLog("recovering roster", instance);
            }
        }
        if (permanent && instance.state.equals("running") && state.equals("ready") && reportedMatch == null && players.isEmpty()) {
            instance.state = "joining";
            instance.acknowledgedRosterVersion = 0;
            instance.transferPending = true;
            instance.rosterJoined = false;
            if (instance.deadline == 0) {
                instance.deadline = now + settings(instance).joinTimeout();
            }
            save();
            lifecycleLog("recovering roster", instance);
        }
        if (instance.matchId == null && reportedMatch != null) {
            end(instance, false);
        }
        if (instance.state.equals("provisioning") && state.equals("ready") && reportedMatch == null && players.isEmpty()) {
            instance.state = "ready";
            save();
            lifecycleLog("ready", instance);
        }
        if (state.equals("failed") || state.equals("ended")) {
            if (instance.matchId == null || instance.matchId.equals(reportedMatch)) {
                end(instance, false);
            }
        }
        if (acknowledged && Set.of("joining", "running").contains(state)) {
            instance.acknowledgedRosterVersion = instance.rosterVersion;
        }
        if (Set.of("joining", "running").contains(instance.state) && instance.transferPending && acknowledged
                && Set.of("joining", "running").contains(state)) {
            List<UUID> transfers = instance.pendingTransfers;
            instance.pendingTransfers = List.of();
            instance.transferPending = false;
            if (!permanent || instance.deadline == 0) {
                instance.deadline = now + settings(instance).joinTimeout();
            }
            if (nativeLobby && instance.state.equals("joining")) {
                instance.state = "running";
                lifecycleLog("start", instance);
            }
            save();
            Binding binding = bindings.get(instance.slug);
            if (!persistenceFailed && binding != null) {
                registeredTarget(instance.slug, binding).ifPresent(server -> transfers.forEach(member -> proxy.getPlayer(member)
                    .ifPresent(player -> connect(instance, player, server))));
            }
        }
        if (acknowledged) {
            Set<String> connected = occupants(instance).stream().map(player -> player.getUniqueId().toString())
                    .collect(java.util.stream.Collectors.toSet());
            if (permanent && instance.state.equals("running") && state.equals("running")) {
                if (!instance.transferPending && instance.deadline == 0) {
                    Set<String> departed = instance.joinedPlayers.stream()
                            .filter(player -> !players.contains(player) && !connected.contains(player))
                            .collect(java.util.stream.Collectors.toSet());
                    removeRoster(instance, departed);
                    if (!departed.isEmpty()) {
                        acknowledged = false;
                    }
                }
                instance.roster.stream().filter(member -> players.contains(member.uuid) && connected.contains(member.uuid))
                        .forEach(member -> instance.joinedPlayers.add(member.uuid));
            }
            boolean allJoined = instance.roster.stream().allMatch(member -> players.contains(member.uuid))
                    && occupants(instance).stream().map(player -> player.getUniqueId().toString()).collect(java.util.stream.Collectors.toSet())
                    .containsAll(instance.roster.stream().map(Member::uuid).toList());
            if (nativeLobby && instance.state.equals("running") && !instance.transferPending) {
                boolean previousJoined = instance.rosterJoined;
                long previousDeadline = instance.deadline;
                instance.rosterJoined = acknowledged && allJoined && state.equals("running");
                if (instance.rosterJoined) {
                    instance.deadline = 0;
                    instance.reservationPlayers = List.of();
                }
                if (previousJoined != instance.rosterJoined || previousDeadline != instance.deadline) {
                    save();
                }
            } else if (instance.state.equals("joining") && !instance.transferPending && allJoined) {
                instance.state = "starting";
                instance.deadline = now + 30000;
                save();
            } else if (instance.state.equals("starting") && state.equals("running") && allJoined) {
                instance.state = "running";
                instance.deadline = 0;
                save();
                lifecycleLog("start", instance);
            }
        }
        JsonObject response = new JsonObject();
        response.addProperty("state", instance.state.equals("provisioning") ? "ready" : instance.state);
        if (instance.matchId != null) {
            response.addProperty("matchId", instance.matchId);
            response.addProperty("rosterVersion", instance.rosterVersion);
            response.add("roster", JSON.toJsonTree(instance.roster));
        }
        return response;
    }

    private static void respond(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private void save() {
        try {
            Files.createDirectories(ledger.getParent());
            byte[] bytes = JSON.toJson(instances).getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 1024 * 1024 || instances.size() > 100) {
                throw new IOException("Minigame ledger exceeds its size or instance limit");
            }
            Path temporary = privateLedgerTemporary();
            try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    output.write(buffer);
                }
                output.force(true);
            }
            Files.move(temporary, ledger, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel directory = FileChannel.open(ledger.getParent(), StandardOpenOption.READ)) {
                directory.force(true);
            }
        } catch (IOException failure) {
            persistenceFailed = true;
            logger.error("Minigame ledger could not be saved; matchmaking disabled", failure);
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        pendingTargetJoins.clear();
        if (control != null) {
            control.stop(0);
        }
        network.shutdownNow();
        requests.shutdownNow();
    }
}