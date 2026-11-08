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
        JsonObject specification;
        List<Member> roster = List.of();
        List<Matchmaker.Group> groups = List.of();
        long created;
        long deadline;
        transient long heartbeat;
        transient long emptySince;
        transient boolean transferPending;
        transient boolean requeue;
        transient boolean busy;
        transient long retryAfter;
    }
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
                    if (!Set.of("provisioning", "ready").contains(instance.state)) {
                        instance.state = "ending";
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
                instance.state = instances.stream().anyMatch(value -> value.serverId == null
                    && value.template.equals(instance.template)) ? "ending" : "provisioning";
                instances.add(instance);
            }
            Instance bound = instances.stream().filter(instance -> entry.getKey().equals(instance.slug)).findFirst().orElseThrow();
            if (bound.specification == null) {
                bound.specification = entry.getValue().specification.deepCopy();
            }
            if (Set.of("ready", "provisioning").contains(bound.state)
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
                queues.values().forEach(queue -> queue.removeIf(group -> group.leader().equals(actor)));
                for (Instance instance : instances) {
                    if (instance.groups.stream().anyMatch(group -> group.leader().equals(actor))
                            && Set.of("joining", "starting").contains(instance.state)) {
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
            if (members.size() > spec.teamSize() || members.size() > spec.maxPlayers()) {
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
        return queues.values().stream().flatMap(List::stream).anyMatch(group -> group.players().contains(player))
                || instances.stream().anyMatch(instance -> !instance.state.equals("ending")
                    && instance.roster.stream().anyMatch(member -> member.uuid.equals(player.toString())));
    }

    synchronized void beforeConnect(ServerPreConnectEvent event) {
        RegisteredServer target = event.getResult().getServer().orElse(null);
        if (target == null) {
            return;
        }
        Binding binding = bindings.values().stream().filter(value -> value.name.equals(target.getServerInfo().getName())).findFirst().orElse(null);
        if (binding == null) {
            return;
        }
        Instance instance = instances.stream().filter(value -> binding.serverId.equals(value.serverId)).findFirst().orElse(null);
        if (instance == null || !Set.of("joining", "starting", "running").contains(instance.state)
                || instance.roster.stream().noneMatch(member -> member.uuid.equals(event.getPlayer().getUniqueId().toString()))) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
        }
    }

    synchronized boolean isMatchServer(RegisteredServer server) {
        return bindings.values().stream().anyMatch(binding -> binding.name.equals(server.getServerInfo().getName()));
    }

    synchronized void disconnected(UUID player) {
        queues.values().forEach(queue -> queue.removeIf(group -> group.players().contains(player)));
    }

    synchronized void sweep() {
        if (closed || persistenceFailed || control == null) {
            return;
        }
        long now = clock.getAsLong();
        queues.values().forEach(queue -> queue.removeIf(group -> !validGroup(group)));
        for (Instance instance : List.copyOf(instances)) {
            MinigameSpec spec = settings(instance);
            if (!templates.containsKey(instance.template)) {
                end(instance, false);
            } else if (instance.state.equals("provisioning") && now - instance.created >= 300000) {
                end(instance, false);
            } else if (Set.of("joining", "starting").contains(instance.state) && now >= instance.deadline) {
                end(instance, true);
            } else if (Set.of("ready", "running").contains(instance.state)
                    && now - (instance.heartbeat == 0 ? Math.max(instance.created, initialized) : instance.heartbeat) > 30000) {
                end(instance, false);
            }
            if (instance.state.equals("running") && spec != null && spec.whenEmpty()) {
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
            }
        }
        for (var template : templates.entrySet()) {
            String name = template.getKey();
            MinigameSpec spec = template.getValue();
            List<Matchmaker.Group> queue = queues.computeIfAbsent(name, ignored -> new ArrayList<>());
            Instance available = instances.stream().filter(instance -> name.equals(instance.template) && instance.state.equals("ready")
                    && instance.heartbeat > 0 && now - instance.heartbeat < 5000).findFirst().orElse(null);
            Matchmaker.Match match = Matchmaker.select(queue, spec.teams(), spec.teamSize(), spec.minPlayers(), spec.maxPlayers());
            if (available != null && match != null) {
                reserve(available, match, queue, now);
            }
            long ready = instances.stream().filter(instance -> name.equals(instance.template)
                    && Set.of("ready", "provisioning").contains(instance.state)).count();
            long total = instances.stream().filter(instance -> name.equals(instance.template)).count();
            int wanted = Math.max(spec.readyServers(), match != null && available == null ? 1 : 0);
            if (ready < wanted && total < spec.maxInstances() && instances.size() < 100 && configured(name)) {
                Instance instance = new Instance();
                instance.template = name;
                instance.requestId = UUID.randomUUID().toString();
                instance.created = now;
                instance.specification = templateSpecifications.get(name).deepCopy();
                instances.add(instance);
                save();
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
        instance.roster = match.teams().entrySet().stream().map(entry -> new Member(entry.getKey().toString(),
                proxy.getPlayer(entry.getKey()).orElseThrow().getUsername(), entry.getValue())).toList();
        instance.groups = match.groups();
        instance.state = "joining";
        instance.transferPending = true;
        instance.deadline = now + 15000;
        save();
        if (!persistenceFailed) {
            queue.removeAll(match.groups());
            tell(instance.roster.stream().map(member -> UUID.fromString(member.uuid)).toList(), "Match found for " + instance.template + ".");
        }
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
            MinecraftInstanceClient client = MinecraftInstanceClient.load(config);
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
        } catch (IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            logger.warn("Minigame preparation pending for {}; the same request will be retried", instance.template);
        }
    }

    private void cleanup(Instance instance) {
        try {
            MinecraftInstanceClient client = MinecraftInstanceClient.load(config);
            String id;
            synchronized (this) {
                id = instance.serverId;
            }
            if (id == null) {
                var target = client.findPreparation(instance.template, UUID.fromString(instance.requestId));
                if (target != null) {
                    id = target.serverId();
                }
            }
            if (id != null) {
                client.deleteIfPresent(instance.template, UUID.fromString(id));
            }
            synchronized (this) {
                instances.remove(instance);
                save();
            }
        } catch (IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            logger.warn("Minigame cleanup pending for {}; deletion will be retried", instance.template);
        }
    }

    private void end(Instance instance, boolean requeue) {
        if (instance.state.equals("ending")) {
            return;
        }
        instance.state = "ending";
        instance.requeue = requeue;
        save();
        if (requeue && !persistenceFailed) {
            for (Matchmaker.Group group : instance.groups) {
                if (validGroup(group) && group.players().stream().noneMatch(this::queuedOrPlaying)) {
                    queues.computeIfAbsent(instance.template, ignored -> new ArrayList<>()).add(group);
                    tell(group.players(), "Match cancelled because not everyone joined; your party was requeued.");
                }
            }
        }
    }

    private List<Player> occupants(Instance instance) {
        Binding binding = bindings.get(instance.slug);
        return binding == null ? List.of() : proxy.getServer(binding.name).map(server -> List.copyOf(server.getPlayersConnected())).orElse(List.of());
    }

    private void evacuate(Instance instance, MinigameSpec spec) {
        RegisteredServer fallback = spec == null ? null : proxy.getServer(spec.fallback()).orElse(null);
        for (Player player : occupants(instance)) {
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
        instance.heartbeat = now;
        if (instance.matchId == null && reportedMatch != null) {
            end(instance, false);
        }
        if (instance.state.equals("provisioning") && state.equals("ready") && reportedMatch == null && players.isEmpty()) {
            instance.state = "ready";
            save();
        }
        if (state.equals("failed") || state.equals("ended")) {
            if (instance.matchId == null || instance.matchId.equals(reportedMatch)) {
                end(instance, false);
            }
        }
        if (instance.state.equals("joining") && instance.transferPending && instance.matchId.equals(reportedMatch)) {
            instance.transferPending = false;
            instance.deadline = now + settings(instance).joinTimeout();
            save();
            Binding binding = bindings.get(instance.slug);
            if (!persistenceFailed && binding != null) {
                proxy.getServer(binding.name).ifPresent(server -> instance.roster.forEach(member -> proxy.getPlayer(UUID.fromString(member.uuid))
                    .ifPresent(player -> player.createConnectionRequest(server).connect())));
            }
        }
        if (instance.matchId != null && instance.matchId.equals(reportedMatch)) {
            boolean allJoined = instance.roster.stream().allMatch(member -> players.contains(member.uuid))
                    && occupants(instance).stream().map(player -> player.getUniqueId().toString()).collect(java.util.stream.Collectors.toSet())
                    .containsAll(instance.roster.stream().map(Member::uuid).toList());
            if (instance.state.equals("joining") && !instance.transferPending && allJoined) {
                instance.state = "starting";
                instance.deadline = now + 30000;
                save();
            } else if (instance.state.equals("starting") && state.equals("running") && allJoined) {
                instance.state = "running";
                save();
            }
        }
        JsonObject response = new JsonObject();
        response.addProperty("state", instance.state.equals("provisioning") ? "ready" : instance.state);
        if (instance.matchId != null) {
            response.addProperty("matchId", instance.matchId);
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
            Path temporary = ledger.resolveSibling(ledger.getFileName() + ".tmp");
            byte[] bytes = JSON.toJson(instances).getBytes(StandardCharsets.UTF_8);
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
        if (control != null) {
            control.stop(0);
        }
        network.shutdownNow();
        requests.shutdownNow();
    }
}