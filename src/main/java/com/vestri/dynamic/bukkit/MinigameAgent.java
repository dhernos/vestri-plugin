package com.vestri.dynamic.bukkit;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class MinigameAgent implements Listener, AutoCloseable {
    static final int MAX_BYTES = 65_536;
    private static final Gson JSON = new Gson();
    private final JavaPlugin plugin;
    private final Configuration config;
    private final MatchFence fence;
    private final Diagnostics diagnostics;
    private final AtomicReference<Control> incoming = new AtomicReference<>();
    private final AtomicBoolean endRequested = new AtomicBoolean();
    private final ScheduledExecutorService polling = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "vestri-minigame-poll");
        thread.setDaemon(true);
        return thread;
    });
    private final ThreadPoolExecutor httpExecutor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), task -> {
                Thread thread = new Thread(task, "vestri-minigame-http");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final HttpClient client;
    private volatile Set<UUID> allowed = Set.of();
    private volatile String report;
    private volatile boolean closed;
    private volatile boolean readySignalled;
    private volatile String state = "joining";
    private List<Member> roster = List.of();
    private ScheduledFuture<?> pollTask;
    private BukkitTask mainTask;
    private BukkitTask readyTask;
    private Handler logHandler;

    private MinigameAgent(JavaPlugin plugin, Configuration config) throws IOException {
        this(plugin, config, plugin.getDataFolder().toPath().resolve("minigame-execution.json"));
    }

    MinigameAgent(JavaPlugin plugin, Configuration config, Path execution) throws IOException {
        this(plugin, config, execution, new Diagnostics(plugin == null
                ? Logger.getLogger(MinigameAgent.class.getName()) : plugin.getLogger(), System::nanoTime));
    }

    MinigameAgent(JavaPlugin plugin, Configuration config, Path execution, Diagnostics diagnostics) throws IOException {
        this.plugin = plugin;
        this.config = config;
        this.diagnostics = diagnostics;
        fence = new MatchFence(execution, config.teams(), config.lobbyMode(), config.instanceMode(),
            config.permanent() ? credentialBinding(config) : null);
        if (fence.roster() != null) {
            roster = fence.roster();
            allowed = Set.copyOf(roster.stream().map(Member::uuid).toList());
        }
        if (fence.startupClaimed() && !config.permanent()) {
            state = "failed";
        }
        client = HttpClient.newBuilder().executor(httpExecutor).connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public static MinigameAgent start(JavaPlugin plugin) {
        Path path = plugin.getDataFolder().toPath().resolve("minigame.json");
        if (Files.notExists(path) && Files.notExists(path.resolveSibling("minigame-execution.json"))) {
            return null;
        }
        try {
            byte[] bytes;
            try (var input = Files.newInputStream(path)) {
                bytes = input.readNBytes(MAX_BYTES + 1);
            }
            if (bytes.length > MAX_BYTES) {
                throw new IllegalArgumentException();
            }
            Configuration config = configuration(new String(bytes, StandardCharsets.UTF_8));
            MinigameAgent agent = new MinigameAgent(plugin, config);
            plugin.getLogger().info(loadedMessage(config));
            agent.enable();
            return agent;
        } catch (IOException | RuntimeException failure) {
            plugin.getLogger().warning(Files.notExists(path)
                    ? "Minigame agent unavailable: missing minigame.json with an existing execution fence; player entry denied."
                    : "Minigame agent unavailable: invalid configuration or execution fence; player entry denied.");
            plugin.getServer().getPluginManager().registerEvents(new DenyEntry(), plugin);
            return null;
        }
    }

    private void enable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        mainTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1, 20);
        if (config.endPattern() != null) {
            logHandler = new Handler() {
                @Override
                public void publish(LogRecord record) {
                    if (record != null) {
                        observeLog(record.getMessage());
                    }
                }

                @Override
                public void flush() { }

                @Override
                public void close() { }
            };
            Logger.getLogger("").addHandler(logHandler);
        }
        pollTask = polling.scheduleAtFixedRate(this::poll, 0, 1, TimeUnit.SECONDS);
    }

    @EventHandler
    public void onServerLoad(ServerLoadEvent event) {
        if (config.trigger().equals("serverLoad") && readyTask == null) {
            readyTask = plugin.getServer().getScheduler().runTask(plugin, this::ready);
        }
    }

    @EventHandler
    public void onLogin(AsyncPlayerPreLoginEvent event) {
        if (!allows(event.getUniqueId())) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST, "You are not in this match's roster.");
        }
    }

    boolean allows(UUID player) {
        return !closed && readySignalled && !terminal() && allowed.contains(player);
    }

    void observeLog(String message) {
        if (!config.permanent() && !closed && state.equals("running") && config.endPattern() != null && message != null
                && message.length() <= 4096 && config.endPattern().matcher(message).find()) {
            endRequested.set(true);
        }
    }

    static final class DenyEntry implements Listener {
        @EventHandler
        public void onLogin(AsyncPlayerPreLoginEvent event) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST, "Minigame configuration is unavailable.");
        }
    }

    public void ready() {
        if (!closed && !readySignalled && !terminal()) {
            readySignalled = true;
            if (fence.matchId() == null) {
                state = "ready";
            } else if (config.lobbyMode().equals("plugin")) {
                state = "running";
            }
        }
    }

    public void end() {
        if (config.permanent()) {
            diagnostics.logger.info("Permanent native minigame end ignored; the instance remains active.");
            return;
        }
        if (!closed && !terminal()) {
            state = "ended";
        }
    }

    private boolean terminal() {
        return state.equals("ended") || state.equals("failed");
    }

    private void tick() {
        if (closed) {
            return;
        }
        Control response = incoming.getAndSet(null);
        Set<UUID> online = new HashSet<>();
        plugin.getServer().getOnlinePlayers().forEach(player -> online.add(player.getUniqueId()));
        apply(response, online, command -> plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), command));
        report = statusReport(online);
    }

    String statusReport(Set<UUID> online) {
        return JSON.toJson(new Report(state, fence.matchId(), online.stream().map(UUID::toString).sorted().toList(),
            fence.rosterVersion()));
    }

    void apply(Control response, Set<UUID> online, Predicate<String> execute) {
        if (endRequested.getAndSet(false)) {
            end();
        }
        if (closed || terminal() || response == null || response.matchId() == null) {
            return;
        }
        try {
            boolean nativeLobby = config.lobbyMode().equals("plugin");
            if (!(nativeLobby ? fence.updateNative(response.matchId(), response.roster(), response.rosterVersion())
                    : fence.accept(response.matchId(), response.roster()))) {
                return;
            }
            if (fence.roster() != null) {
                roster = fence.roster();
                allowed = Set.copyOf(roster.stream().map(Member::uuid).toList());
            }
            if (response.state().equals("ending")) {
                end();
                return;
            }
            if (nativeLobby) {
                state = readySignalled ? "running" : "joining";
                return;
            }
            if (!state.equals("running")) {
                state = "joining";
            }
            if (!readySignalled || !response.state().equals("starting") || roster.isEmpty()
                    || !online.containsAll(allowed) || !fence.claimStartup(response.matchId())) {
                return;
            }
            List<String> commands = expand(config.actions(), config.teams(), roster, response.matchId());
            boolean success = dispatch(commands, command -> !terminal() && execute.test(command));
            if (!terminal()) {
                state = success ? "running" : "failed";
            }
        } catch (RuntimeException failure) {
            state = "failed";
            diagnostics.logger.warning("Minigame assignment or startup actions failed.");
        }
    }

    private void poll() {
        Control response = poll(report);
        if (!closed && response != null) {
            incoming.set(response);
        }
    }

    Control poll(String snapshot) {
        if (closed || snapshot == null) {
            return null;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(config.endpoint()).timeout(Duration.ofSeconds(3))
                    .header("Authorization", "Bearer " + config.token()).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(snapshot)).build();
            HttpResponse<byte[]> response = exchange(client, request, Duration.ofSeconds(3));
            if (closed) {
                return null;
            }
            if (response.statusCode() != 200) {
                diagnostics.failure("HTTP status=" + response.statusCode());
                return null;
            }
            Control parsed;
            try {
                parsed = control(new String(response.body(), StandardCharsets.UTF_8), config.teams(), config.instanceMode());
            } catch (RuntimeException failure) {
                diagnostics.failure("invalid response class=" + failure.getClass().getSimpleName());
                return null;
            }
            diagnostics.recovered();
            return parsed;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            if (!closed) {
                diagnostics.failure("transport class=InterruptedException");
            }
        } catch (ExecutionException | TimeoutException | RuntimeException failure) {
            if (!closed) {
                Throwable cause = failure instanceof ExecutionException && failure.getCause() != null
                        ? failure.getCause() : failure;
                diagnostics.failure("transport class=" + cause.getClass().getSimpleName());
            }
        }
        return null;
    }

    static String loadedMessage(Configuration config) {
        String path = config.endpoint().getPath();
        String slug = path.substring(path.lastIndexOf('/') + 1);
        return "Minigame agent configured: slug=" + slug + ", lobbyMode=" + config.lobbyMode()
                + ", readiness=" + config.trigger();
    }

    static final class Diagnostics {
        private final Logger logger;
        private final LongSupplier clock;
        private boolean failing;
        private boolean warned;
        private long lastWarning;

        Diagnostics(Logger logger, LongSupplier clock) {
            this.logger = logger;
            this.clock = clock;
        }

        void failure(String detail) {
            failing = true;
            long now = clock.getAsLong();
            if (!warned || now - lastWarning >= TimeUnit.SECONDS.toNanos(30)) {
                warned = true;
                lastWarning = now;
                logger.warning("Minigame control unavailable: " + detail.substring(0, Math.min(detail.length(), 120)) + ".");
            }
        }

        void recovered() {
            if (failing) {
                failing = false;
                logger.info("Minigame control recovered.");
            }
        }
    }

    static HttpResponse<byte[]> exchange(HttpClient client, HttpRequest request, Duration timeout)
            throws InterruptedException, ExecutionException, TimeoutException {
        CompletableFuture<HttpResponse<byte[]>> future = client.sendAsync(request, info -> new BoundedBody(MAX_BYTES));
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } finally {
            if (!future.isDone()) {
                future.cancel(true);
            }
        }
    }

    static boolean dispatch(List<String> commands, Predicate<String> dispatch) {
        for (String command : commands) {
            if (!dispatch.test(command)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void close() {
        closed = true;
        if (pollTask != null) {
            pollTask.cancel(true);
        }
        polling.shutdownNow();
        httpExecutor.shutdownNow();
        if (mainTask != null) {
            mainTask.cancel();
        }
        if (readyTask != null) {
            readyTask.cancel();
        }
        HandlerList.unregisterAll(this);
        if (logHandler != null) {
            Logger.getLogger("").removeHandler(logHandler);
            logHandler.close();
        }
        incoming.set(null);
    }

    static Configuration configuration(String text) {
        JsonObject root = JsonParser.parseString(text).getAsJsonObject();
        String slug = root.get("slug").getAsString();
        String token = root.get("token").getAsString();
        String url = root.get("controlUrl").getAsString();
        if (!slug.matches("mcgame-[a-zA-Z0-9-]{1,100}") || !token.matches("[a-fA-F0-9]{64}")
                || !url.matches("http://vestri-[a-zA-Z0-9-]{1,100}:25580/?")) {
            throw new IllegalArgumentException();
        }
        JsonObject spec = root.getAsJsonObject("spec");
        String lobbyMode = spec.has("lobbyMode") ? string(spec.get("lobbyMode")) : "vestri";
        if (!Set.of("vestri", "plugin").contains(lobbyMode)) {
            throw new IllegalArgumentException();
        }
        String instanceMode = spec.has("instanceMode") ? string(spec.get("instanceMode")) : "temporary";
        if (!Set.of("temporary", "permanent").contains(instanceMode)
                || (instanceMode.equals("permanent") && !lobbyMode.equals("plugin"))) {
            throw new IllegalArgumentException();
        }
        List<String> teams = new ArrayList<>();
        JsonArray definitions = spec.has("teams") && !spec.get("teams").isJsonNull()
            ? spec.getAsJsonArray("teams") : null;
        if (definitions != null) {
            definitions.forEach(value -> teams.add(identifier(string(value))));
        }
        if (teams.size() > 100 || new HashSet<>(teams).size() != teams.size()) {
            throw new IllegalArgumentException();
        }
        JsonObject lifecycle = spec.getAsJsonObject("lifecycle");
        String trigger = lifecycle.getAsJsonObject("ready").get("trigger").getAsString();
        if (!Set.of("serverLoad", "explicit").contains(trigger)) {
            throw new IllegalArgumentException();
        }
        List<Action> actions = new ArrayList<>();
        JsonArray entries = lifecycle.getAsJsonArray("onAllJoined");
        if (entries != null) {
            entries.forEach(value -> {
                JsonObject entry = value.getAsJsonObject();
                String command = string(entry.get("command"));
                String forEach = entry.has("forEach") ? string(entry.get("forEach")) : "";
                if (command.isBlank() || command.getBytes(StandardCharsets.UTF_8).length > 1024
                        || command.strip().startsWith("/") || command.chars().anyMatch(Character::isISOControl)
                        || !Set.of("", "team", "player").contains(forEach)
                        || (teams.isEmpty() && forEach.equals("team"))) {
                    throw new IllegalArgumentException();
                }
                var placeholders = Pattern.compile("\\{([^{}]*)\\}").matcher(command);
                while (placeholders.find()) {
                    String placeholder = placeholders.group(1);
                    if (!Set.of("matchId", "team", "player", "uuid").contains(placeholder)
                            || (placeholder.equals("team") && (forEach.isEmpty() || teams.isEmpty()))
                            || (Set.of("player", "uuid").contains(placeholder) && !forEach.equals("player"))) {
                        throw new IllegalArgumentException();
                    }
                }
                String remaining = placeholders.replaceAll("");
                if (remaining.contains("{") || remaining.contains("}")) {
                    throw new IllegalArgumentException();
                }
                actions.add(new Action(command, forEach));
            });
        }
        if (actions.size() > 100) {
            throw new IllegalArgumentException();
        }
        JsonObject end = lifecycle.getAsJsonObject("end");
        if (instanceMode.equals("permanent") && (!actions.isEmpty() || end == null || end.has("logPattern")
                || !explicitFalse(end, "deleteServer") || !explicitFalse(end, "whenEmpty"))) {
            throw new IllegalArgumentException("Invalid permanent native lifecycle");
        }
        String pattern = end != null && end.has("logPattern") ? end.get("logPattern").getAsString() : "";
        if (pattern.getBytes(StandardCharsets.UTF_8).length > 512 || pattern.contains("\n") || pattern.contains("\r")) {
            throw new IllegalArgumentException();
        }
        return new Configuration(URI.create(url.replaceAll("/$", "") + "/minigame/" + slug), token,
            List.copyOf(teams), trigger, List.copyOf(actions), pattern.isEmpty() ? null : Pattern.compile(pattern), lobbyMode, instanceMode);
    }

    private static boolean explicitFalse(JsonObject object, String key) {
        var value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
                && !value.getAsBoolean();
    }

    private static String credentialBinding(Configuration config) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(
                    (config.endpoint() + "\n" + config.token()).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    static Control control(String text, List<String> teams) {
        return control(text, teams, "temporary");
    }

    static Control control(String text, List<String> teams, String instanceMode) {
        JsonObject root = JsonParser.parseString(text).getAsJsonObject();
        String state = root.get("state").getAsString();
        if (!Set.of("ready", "joining", "starting", "running", "ending").contains(state)
                && !(instanceMode.equals("permanent") && state.equals("unavailable"))) {
            throw new IllegalArgumentException();
        }
        UUID matchId = root.has("matchId") && !root.get("matchId").isJsonNull()
                ? uuid(root.get("matchId").getAsString()) : null;
        int rosterVersion = version(root);
        List<Member> members = null;
        if (root.has("roster") && !root.get("roster").isJsonNull()) {
            members = new ArrayList<>();
            for (var value : root.getAsJsonArray("roster")) {
                JsonObject member = value.getAsJsonObject();
                String name = string(member.get("name"));
                String team = member.has("team") && !member.get("team").isJsonNull()
                        ? string(member.get("team")) : "";
                members.add(new Member(uuid(member.get("uuid").getAsString()), name, team));
            }
            validateRoster(members, teams);
            if (matchId == null) {
                throw new IllegalArgumentException();
            }
            members = List.copyOf(members);
        }
        return new Control(state, matchId, members, rosterVersion);
    }

    private static int version(JsonObject root) {
        if (!root.has("rosterVersion")) {
            return 0;
        }
        var value = root.get("rosterVersion");
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                || !value.getAsString().matches("0|[1-9][0-9]*")) {
            throw new IllegalArgumentException("Invalid roster version");
        }
        return Integer.parseInt(value.getAsString());
    }

    private static void validateRoster(List<Member> members, List<String> teams) {
        Set<UUID> ids = new HashSet<>();
        if (members.size() > 512) {
            throw new IllegalArgumentException();
        }
        for (Member member : members) {
            if (member == null || member.uuid() == null || !ids.add(member.uuid()) || member.name() == null
                    || !member.name().matches("[A-Za-z0-9_]{1,16}") || member.team() == null
                    || (teams.isEmpty() ? !member.team().isEmpty() : !teams.contains(member.team()))) {
                throw new IllegalArgumentException();
            }
        }
    }

    private static UUID uuid(String text) {
        UUID value = UUID.fromString(text);
        if (!value.toString().equalsIgnoreCase(text)) {
            throw new IllegalArgumentException();
        }
        return value;
    }

    private static String identifier(String text) {
        if (!text.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException();
        }
        return text;
    }

    private static String string(com.google.gson.JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException();
        }
        return value.getAsString();
    }

    static List<String> expand(List<Action> actions, List<String> teams, List<Member> roster, UUID matchId) {
        List<String> result = new ArrayList<>();
        for (Action action : actions) {
            switch (action.forEach()) {
                case "team" -> teams.forEach(team -> result.add(expand(action.command(), "", "", team, matchId)));
                case "player" -> roster.forEach(member -> result.add(expand(action.command(), member.name(),
                        member.uuid().toString(), member.team(), matchId)));
                case "" -> result.add(expand(action.command(), "", "", "", matchId));
                default -> throw new IllegalArgumentException();
            }
            if (result.size() > 4096) {
                throw new IllegalArgumentException();
            }
        }
        return List.copyOf(result);
    }

    private static String expand(String command, String player, String uuid, String team, UUID matchId) {
        return command.replace("{player}", player).replace("{uuid}", uuid).replace("{team}", team)
                .replace("{matchId}", matchId.toString());
    }

    record Action(String command, String forEach) { }
    record Member(UUID uuid, String name, String team) { }
    record Control(String state, UUID matchId, List<Member> roster, int rosterVersion) {
        Control(String state, UUID matchId, List<Member> roster) {
            this(state, matchId, roster, 0);
        }
    }
    record Report(String state, UUID matchId, List<String> players, int rosterVersion) { }
    record Configuration(URI endpoint, String token, List<String> teams, String trigger,
            List<Action> actions, Pattern endPattern, String lobbyMode, String instanceMode) {
        Configuration {
            if (!Set.of("temporary", "permanent").contains(instanceMode)
                    || (instanceMode.equals("permanent") && (!lobbyMode.equals("plugin")
                            || !actions.isEmpty() || endPattern != null))) {
                throw new IllegalArgumentException("Invalid instance mode");
            }
        }

        boolean permanent() {
            return instanceMode.equals("permanent");
        }

        Configuration(URI endpoint, String token, List<String> teams, String trigger,
                List<Action> actions, Pattern endPattern, String lobbyMode) {
            this(endpoint, token, teams, trigger, actions, endPattern, lobbyMode, "temporary");
        }

        Configuration(URI endpoint, String token, List<String> teams, String trigger,
                List<Action> actions, Pattern endPattern) {
            this(endpoint, token, teams, trigger, actions, endPattern, "vestri");
        }
    }

    static final class MatchFence {
        private final Path path;
        private final List<String> teams;
        private final String lobbyMode;
        private final String instanceMode;
        private final String credentialBinding;
        private UUID matchId;
        private boolean startupClaimed;
        private List<Member> roster;
        private int rosterVersion;

        MatchFence(Path path, List<String> teams) throws IOException {
            this(path, teams, "vestri");
        }

        MatchFence(Path path, List<String> teams, String lobbyMode) throws IOException {
            this(path, teams, lobbyMode, "temporary", null);
        }

        MatchFence(Path path, List<String> teams, String lobbyMode, String instanceMode,
                String credentialBinding) throws IOException {
            this.path = path;
            this.teams = List.copyOf(teams);
            this.lobbyMode = lobbyMode;
            this.instanceMode = instanceMode;
            this.credentialBinding = credentialBinding;
            if (instanceMode.equals("permanent") && (!lobbyMode.equals("plugin") || credentialBinding == null)) {
                throw new IllegalArgumentException("Invalid permanent fence");
            }
            if (Files.exists(path)) {
                byte[] bytes;
                try (var input = Files.newInputStream(path)) {
                    bytes = input.readNBytes(MAX_BYTES + 1);
                }
                if (bytes.length > MAX_BYTES) {
                    throw new IOException("Execution fence exceeds size limit");
                }
                JsonObject saved = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
                if ((saved.has("instanceMode") && !instanceMode.equals(string(saved.get("instanceMode"))))
                    || (instanceMode.equals("permanent") && (!saved.has("credentialBinding")
                        || !credentialBinding.equals(string(saved.get("credentialBinding")))))) {
                    throw new IllegalArgumentException("Execution fence belongs to another instance");
                }
                var claimed = saved.get("startupClaimed");
                if (claimed == null || !claimed.isJsonPrimitive() || !claimed.getAsJsonPrimitive().isBoolean()) {
                    throw new IllegalArgumentException("Invalid execution claim");
                }
                saved.addProperty("state", "joining");
                Control restored = control(saved.toString(), teams);
                matchId = restored.matchId();
                roster = restored.roster();
                rosterVersion = restored.rosterVersion();
                startupClaimed = claimed.getAsBoolean();
                if (matchId == null || (startupClaimed && (roster == null
                        || (roster.isEmpty() && !lobbyMode.equals("plugin"))))
                        || (lobbyMode.equals("plugin") && (!startupClaimed || rosterVersion == 0))) {
                    throw new IllegalArgumentException("Invalid execution fence");
                }
            }
        }

        int rosterVersion() {
            return rosterVersion;
        }

        boolean startupClaimed() {
            return startupClaimed;
        }

        List<Member> roster() {
            return roster;
        }

        UUID matchId() {
            return matchId;
        }

        boolean accept(UUID candidate) {
            return accept(candidate, null);
        }

        boolean accept(UUID candidate, List<Member> members) {
            if (candidate == null || !lobbyMode.equals("vestri")) {
                return false;
            }
            if (matchId != null && !matchId.equals(candidate)) {
                return false;
            }
            if (roster != null && members != null && !roster.equals(members)) {
                return false;
            }
            if (matchId == null || (roster == null && members != null)) {
                persist(candidate, false, members);
                matchId = candidate;
                roster = members == null ? null : List.copyOf(members);
            }
            return true;
        }

        boolean updateNative(UUID candidate, List<Member> members, int version) {
            if (!lobbyMode.equals("plugin") || candidate == null || members == null || version < 1
                    || (matchId != null && !matchId.equals(candidate))) {
                return false;
            }
            validateRoster(members, teams);
            List<Member> updated = List.copyOf(members);
            if (roster != null) {
                if (version <= rosterVersion) {
                    if (instanceMode.equals("permanent") && version < rosterVersion) {
                        return false;
                    }
                    return roster.size() == updated.size() && updated.containsAll(roster);
                }
                if (instanceMode.equals("permanent") ? updated.stream().anyMatch(member -> roster.stream()
                        .anyMatch(previous -> previous.uuid().equals(member.uuid()) && !previous.equals(member)))
                        : !updated.containsAll(roster)) {
                    return false;
                }
            }
            persist(candidate, true, updated, version);
            matchId = candidate;
            roster = updated;
            rosterVersion = version;
            startupClaimed = true;
            return true;
        }

        boolean claimStartup(UUID candidate) {
            if (!accept(candidate) || startupClaimed || roster == null || roster.isEmpty()) {
                return false;
            }
            persist(matchId, true, roster);
            startupClaimed = true;
            return true;
        }

        private void persist(UUID candidate, boolean claimed, List<Member> members) {
            persist(candidate, claimed, members, rosterVersion);
        }

        private void persist(UUID candidate, boolean claimed, List<Member> members, int version) {
            Path temporary = null;
            try {
                Files.createDirectories(path.getParent());
                byte[] bytes = JSON.toJson(new Execution(candidate, claimed, members, version, instanceMode,
                    credentialBinding)).getBytes(StandardCharsets.UTF_8);
                if (bytes.length > MAX_BYTES) {
                    throw new IOException("Execution fence exceeds size limit");
                }
                temporary = Files.createTempFile(path.getParent(), "minigame-execution-", ".tmp");
                try (FileChannel file = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                    ByteBuffer buffer = ByteBuffer.wrap(bytes);
                    while (buffer.hasRemaining()) {
                        file.write(buffer);
                    }
                    file.force(true);
                }
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                try (FileChannel directory = FileChannel.open(path.getParent(), StandardOpenOption.READ)) {
                    directory.force(true);
                }
            } catch (IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            } finally {
                if (temporary != null) {
                    try {
                        Files.deleteIfExists(temporary);
                    } catch (IOException ignored) {
                    }
                }
            }
        }

        private record Execution(UUID matchId, boolean startupClaimed, List<Member> roster, int rosterVersion,
            String instanceMode, String credentialBinding) { }
    }

    static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;

        BoundedBody(int limit) {
            this.limit = limit;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > limit - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new IOException("Control response exceeds size limit"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable failure) {
            result.completeExceptionally(failure);
        }

        @Override
        public void onComplete() {
            result.complete(bytes.toByteArray());
        }
    }
}