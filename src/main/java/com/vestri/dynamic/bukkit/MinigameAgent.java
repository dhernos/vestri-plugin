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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
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
    private final RandomGenerator random;
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
    private Logger endLogger;
    private volatile String trustedEndLogger;
    private PreStartGuard preStartGuard;
    private final Map<UUID, JoinSession> joinSessions = new HashMap<>();

    private MinigameAgent(JavaPlugin plugin, Configuration config) throws IOException {
        this(plugin, config, plugin.getDataFolder().toPath().resolve("minigame-execution.json"));
    }

    MinigameAgent(JavaPlugin plugin, Configuration config, Path execution) throws IOException {
        this(plugin, config, execution, new Diagnostics(plugin == null
                ? Logger.getLogger(MinigameAgent.class.getName()) : plugin.getLogger(), System::nanoTime));
    }

    MinigameAgent(JavaPlugin plugin, Configuration config, Path execution, Diagnostics diagnostics) throws IOException {
        this(plugin, config, execution, diagnostics, RandomGenerator.getDefault());
    }

    MinigameAgent(JavaPlugin plugin, Configuration config, Path execution, Diagnostics diagnostics,
            RandomGenerator random) throws IOException {
        this.plugin = plugin;
        this.config = config;
        this.diagnostics = diagnostics;
        this.random = random;
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
        if (config.lobbyMode().equals("vestri")) {
            preStartGuard = new PreStartGuard(this::frozen);
            plugin.getServer().getPluginManager().registerEvents(preStartGuard, plugin);
        }
        mainTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1, 20);
        if (config.endPattern() != null) {
            configureLogSource(plugin.getServer().getPluginManager().getPlugins(),
                    plugin.getLogger(), plugin.getServer().getLogger());
            attachEndListener();
        }
        diagnostics.logger.info(statusSummary());
        if (config.lobbyMode().equals("vestri") && config.actions().isEmpty()
                && config.teamCommands().isEmpty() && config.startCommands().isEmpty()) {
            diagnostics.logger.warning("Managed minigame has no setup, team or start commands in this instance's saved configuration; startup will finish immediately once all players join. Template edits do not update existing instances.");
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

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (closed || config.onPlayerJoin().isEmpty()) {
            return;
        }
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        JoinSession previous = joinSessions.get(uuid);
        if (previous != null) {
            if (previous.player == player) {
                return;
            }
            previous.cancel();
        }
        JoinSession session = new JoinSession(player, uuid);
        joinSessions.put(uuid, session);
        scheduleJoin(session);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        JoinSession session = joinSessions.get(uuid);
        if (session != null && session.player == event.getPlayer()) {
            joinSessions.remove(uuid);
            session.cancel();
        }
    }

    private void scheduleJoin(JoinSession session) {
        session.task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> runJoin(session), 1);
    }

    private boolean currentSession(JoinSession session) {
        return !closed && joinSessions.get(session.uuid) == session && session.player.isOnline()
                && plugin.getServer().getPlayer(session.uuid) == session.player;
    }

    private void runJoin(JoinSession session) {
        session.task = null;
        if (session.started || !currentSession(session)) {
            return;
        }
        if (!allows(session.uuid)) {
            if (!terminal() && ++session.waitTicks < 100) {
                scheduleJoin(session);
            } else {
                session.started = true;
                rejectJoin(session, 0, "none");
            }
            return;
        }
        session.started = true;
        Member member = roster.stream().filter(candidate -> candidate.uuid().equals(session.uuid)).findFirst().orElseThrow();
        List<CommandInvocation> commands = new ArrayList<>();
        for (ExecutableCommand command : config.onPlayerJoin()) {
            addInvocation(commands, command.command(), command.sender(), member, member.team(), fence.matchId());
        }
        for (int index = 0; index < commands.size(); index++) {
            CommandInvocation invocation = commands.get(index);
            boolean accepted;
            try {
                accepted = currentSession(session) && allows(session.uuid)
                    && member.name().equals(session.player.getName())
                        && executeInvocation(invocation, Map.of(session.uuid, session.player));
            } catch (RuntimeException failure) {
                accepted = false;
            }
            if (!accepted) {
                rejectJoin(session, index + 1, invocation.sender());
                return;
            }
        }
        session.successful = true;
    }

    private void rejectJoin(JoinSession session, int index, String sender) {
        diagnostics.logger.warning("Minigame join action failed: index=" + index + ", sender=" + sender
                + ", player=" + session.uuid + "; remaining join commands aborted; disconnecting this session.");
        if (currentSession(session)) {
            session.player.kickPlayer("Minigame admission failed. Please reconnect.");
        }
    }

    private static final class JoinSession {
        private final Player player;
        private final UUID uuid;
        private BukkitTask task;
        private int waitTicks;
        private boolean started;
        private boolean successful;

        private JoinSession(Player player, UUID uuid) {
            this.player = player;
            this.uuid = uuid;
        }

        private void cancel() {
            if (task != null) {
                task.cancel();
                task = null;
            }
        }
    }

    boolean allows(UUID player) {
        return !closed && readySignalled && !terminal() && allowed.contains(player);
    }

    boolean frozen(UUID player) {
        return !closed && config.lobbyMode().equals("vestri") && !state.equals("running") && allowed.contains(player);
    }

    public String statusSummary() {
        long frozenPlayers = roster.stream().filter(member -> frozen(member.uuid())).count();
        return "Minigame status: state=" + state + ", ready=" + readySignalled
                + ", lobbyMode=" + config.lobbyMode() + ", matchId=" + fence.matchId()
                + ", roster=" + roster.size() + ", frozen=" + frozenPlayers
                + ", setupCommands=" + config.actions().size() + ", teamCommands=" + config.teamCommands().size()
                + ", startCommands=" + config.startCommands().size()
                + ", joinCommands=" + config.onPlayerJoin().size()
                + ", playerSenderCommands=" + playerSenderCommands(config)
                + ", serverSenderCommands=" + (config.actions().size() + config.teamCommands().size()
                    + config.startCommands().size() + config.onPlayerJoin().size() - playerSenderCommands(config))
                + ", endPattern=" + (config.endPattern() == null ? "absent" : "configured")
                + ", configuredLogger=" + config.loggerName() + ", trustedLogger=" + trustedEndLogger;
    }

    void configureLogSource(org.bukkit.plugin.Plugin[] installed, Logger own, Logger server) {
        trustedEndLogger = null;
        endLogger = null;
        String name = config.loggerName();
        if (name != null && !name.equals(own.getName()) && !name.equals(server.getName())
                && !Set.of("", "global", "minecraft", "bukkit", "server", "vestri", "vestridynamicservers")
                        .contains(name.toLowerCase(java.util.Locale.ROOT))) {
            for (var candidate : installed) {
                if (candidate.getLogger() != own && candidate.getLogger() != server
                        && name.equals(candidate.getLogger().getName())) {
                    trustedEndLogger = name;
                    endLogger = candidate.getLogger();
                    break;
                }
            }
        }
        if (config.endPattern() != null && trustedEndLogger == null) {
            diagnostics.logger.warning("Minigame automatic log end disabled: lifecycle.end.loggerName must exactly match an installed game plugin logger (not Vestri or the server). Explicit and empty-server end remain available.");
        }
    }

    void attachEndListener() {
        if (endLogger == null || logHandler != null) {
            return;
        }
        logHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                observeLog(record);
            }

            @Override
            public void flush() { }

            @Override
            public void close() { }
        };
        endLogger.addHandler(logHandler);
    }

    /**
     * The verified plugin logger is a trust boundary, not proof of a game outcome.
     * Player text logged through that plugin's own logger cannot be distinguished from its end signal.
     */
    public void observeLog(LogRecord record) {
        String message = record == null ? null : record.getMessage();
        if (trustedEndLogger != null && record != null && trustedEndLogger.equals(record.getLoggerName())
                && !config.permanent() && !closed && state.equals("running") && config.endPattern() != null && message != null
                && message.length() <= 4096 && config.endPattern().matcher(message).find()) {
            if (endRequested.compareAndSet(false, true)) {
                diagnostics.logger.info("Minigame end pattern matched trusted plugin logger=" + trustedEndLogger
                        + ", matchId=" + fence.matchId());
            }
        }
    }

    void observeLog(String message) {
        observeLog(new LogRecord(java.util.logging.Level.INFO, message));
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
        Map<UUID, Player> online = new HashMap<>();
        plugin.getServer().getOnlinePlayers().forEach(player -> online.put(player.getUniqueId(), player));
        applyInvocations(response, online.keySet(), invocation -> executeInvocation(invocation, online));
        report = statusReport(online.keySet());
    }

    boolean executeInvocation(CommandInvocation invocation, Map<UUID, Player> online) {
        if (closed || terminal() || plugin == null || !plugin.getServer().isPrimaryThread()) {
            return false;
        }
        if (invocation.player() == null) {
            return plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), invocation.command());
        }
        Player player = online.get(invocation.player());
        return player != null && allows(invocation.player()) && player.isOnline()
                && plugin.getServer().getPlayer(invocation.player()) == player
            && roster.stream().anyMatch(member -> member.uuid().equals(invocation.player())
                && member.name().equals(player.getName()))
                && player.performCommand(invocation.command());
    }

    String statusReport(Set<UUID> online) {
        return JSON.toJson(new Report(state, fence.matchId(), online.stream()
            .filter(uuid -> !joinSessions.containsKey(uuid) || joinSessions.get(uuid).successful)
            .map(UUID::toString).sorted().toList(),
            fence.rosterVersion()));
    }

    void apply(Control response, Set<UUID> online, Predicate<String> execute) {
        applyInvocations(response, online, invocation -> execute.test(invocation.command()));
    }

    void applyInvocations(Control response, Set<UUID> online, Predicate<CommandInvocation> execute) {
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
                boolean firstAssignment = allowed.isEmpty() && !fence.roster().isEmpty();
                roster = fence.roster();
                allowed = Set.copyOf(roster.stream().map(Member::uuid).toList());
                if (firstAssignment) {
                    diagnostics.logger.info("Minigame roster applied on Paper: " + statusSummary());
                }
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
                    || !online.containsAll(allowed)
                    || joinSessions.values().stream().anyMatch(session -> allowed.contains(session.uuid) && !session.successful)
                    || !fence.claimStartup(response.matchId())) {
                return;
            }
            state = "starting";
            List<CommandInvocation> commands = expandInvocations(config, roster, online, response.matchId(), random);
            diagnostics.logger.info("Minigame startup on Paper: matchId=" + response.matchId()
                    + ", players=" + roster.size() + ", expandedCommands=" + commands.size());
            boolean success = true;
            for (int commandIndex = 0; commandIndex < commands.size(); commandIndex++) {
                CommandInvocation invocation = commands.get(commandIndex);
                boolean accepted;
                try {
                    accepted = !closed && !terminal()
                            && (invocation.player() == null || online.contains(invocation.player()))
                            && execute.test(invocation);
                } catch (RuntimeException failure) {
                    accepted = false;
                }
                if (!accepted) {
                    diagnostics.logger.warning("Minigame Paper command rejected at index=" + (commandIndex + 1)
                            + ", sender=" + invocation.sender() + ", player=" + invocation.player()
                            + "; remaining startup commands aborted and players remain protected.");
                    success = false;
                    break;
                }
            }
            if (!terminal()) {
                state = success ? "running" : "failed";
                diagnostics.logger.info("Minigame startup completed on Paper: " + statusSummary());
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

    static String commandSender(JsonObject command) {
        String sender = command.has("sender") ? string(command.get("sender")) : "server";
        if (!Set.of("server", "player").contains(sender)) {
            throw new IllegalArgumentException("Command sender must be server or player");
        }
        return sender;
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
        joinSessions.values().forEach(JoinSession::cancel);
        joinSessions.clear();
        HandlerList.unregisterAll(this);
        if (preStartGuard != null) {
            HandlerList.unregisterAll(preStartGuard);
        }
        if (logHandler != null) {
            if (endLogger != null) {
                endLogger.removeHandler(logHandler);
            }
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
                String sender = commandSender(entry);
                validateCommand(command, forEach, teams, sender, false);
                actions.add(new Action(command, forEach, sender));
            });
        }
        List<TeamCommand> teamCommands = new ArrayList<>();
        JsonArray teamEntries = lifecycle.getAsJsonArray("teamCommands");
        if (teamEntries != null) {
            teamEntries.forEach(value -> {
                JsonObject entry = value.getAsJsonObject();
                String team = string(entry.get("team"));
                String command = string(entry.get("command"));
                if (!teams.contains(team)) {
                    throw new IllegalArgumentException("Unknown command team");
                }
                String sender = commandSender(entry);
                validateCommand(command, "player", teams, sender, false);
                teamCommands.add(new TeamCommand(team, command, sender));
            });
        }
        List<ExecutableCommand> startCommands = new ArrayList<>();
        JsonArray starts = lifecycle.getAsJsonArray("startCommands");
        if (starts != null) {
            starts.forEach(value -> startCommands.add(executableCommand(value, teams, false)));
        }
        List<ExecutableCommand> onPlayerJoin = new ArrayList<>();
        JsonArray joins = lifecycle.getAsJsonArray("onPlayerJoin");
        if (joins != null) {
            joins.forEach(value -> onPlayerJoin.add(executableCommand(value, teams, true)));
        }
        JsonObject end = lifecycle.getAsJsonObject("end");
        if (instanceMode.equals("permanent") && (!actions.isEmpty() || end == null || end.has("logPattern")
                || !explicitFalse(end, "deleteServer") || !explicitFalse(end, "whenEmpty"))) {
            throw new IllegalArgumentException("Invalid permanent native lifecycle");
        }
        String pattern = end != null && end.has("logPattern") ? end.get("logPattern").getAsString() : "";
        String loggerName = end != null && end.has("loggerName") ? string(end.get("loggerName")) : null;
        if (loggerName != null && !loggerName.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")) {
            throw new IllegalArgumentException("Invalid end logger name");
        }
        if (pattern.getBytes(StandardCharsets.UTF_8).length > 512 || pattern.contains("\n") || pattern.contains("\r")) {
            throw new IllegalArgumentException();
        }
        return new Configuration(URI.create(url.replaceAll("/$", "") + "/minigame/" + slug), token,
            List.copyOf(teams), trigger, List.copyOf(actions), pattern.isEmpty() ? null : Pattern.compile(pattern),
            lobbyMode, instanceMode, loggerName, List.copyOf(teamCommands), List.copyOf(startCommands),
            List.copyOf(onPlayerJoin));
    }

    private static ExecutableCommand executableCommand(com.google.gson.JsonElement value, List<String> teams,
            boolean joining) {
        String command;
        String sender = "server";
        if (!joining && value.isJsonPrimitive()) {
            command = string(value);
        } else {
            JsonObject entry = value.getAsJsonObject();
            if (entry.has("forEach")) {
                throw new IllegalArgumentException("Lifecycle command must run once");
            }
            command = string(entry.get("command"));
            sender = commandSender(entry);
        }
        validateCommand(command, "", teams, sender, joining);
        return new ExecutableCommand(command, sender);
    }

    private static void validateCommand(String command, String scope, List<String> teams) {
        validateCommand(command, scope, teams, "server", false);
    }

    private static void validateCommand(String command, String scope, List<String> teams, String sender,
            boolean joining) {
        if (command.isBlank() || command.getBytes(StandardCharsets.UTF_8).length > 1024
                || command.strip().startsWith("/") || command.chars().anyMatch(Character::isISOControl)
                || !Set.of("", "team", "player").contains(scope)
                || !Set.of("server", "player").contains(sender)
                || (teams.isEmpty() && scope.equals("team"))) {
            throw new IllegalArgumentException();
        }
        var placeholders = Pattern.compile("\\{([^{}]*)\\}").matcher(command);
        while (placeholders.find()) {
            String placeholder = placeholders.group(1);
            if (!Set.of("matchId", "team", "player", "uuid").contains(placeholder)
                        || (placeholder.equals("team") && (teams.isEmpty()
                            || (scope.isEmpty() && !sender.equals("player") && !joining)))
                        || (Set.of("player", "uuid").contains(placeholder) && !scope.equals("player")
                            && !sender.equals("player") && !joining)) {
                throw new IllegalArgumentException();
            }
        }
        String remaining = placeholders.replaceAll("");
        if (remaining.contains("{") || remaining.contains("}")) {
            throw new IllegalArgumentException();
        }
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

    static List<String> expand(Configuration config, List<Member> roster, UUID matchId) {
        List<String> commands = new ArrayList<>(expand(config.actions(), config.teams(), roster, matchId));
        for (TeamCommand action : config.teamCommands()) {
            for (Member member : roster) {
                if (action.team().equals(member.team())) {
                    commands.add(expand(action.command(), member.name(), member.uuid().toString(), member.team(), matchId));
                }
                if (commands.size() > 4096) {
                    throw new IllegalArgumentException("Too many lifecycle commands");
                }
            }
        }
        for (ExecutableCommand command : config.startCommands()) {
            commands.add(expand(command.command(), "", "", "", matchId));
        }
        if (commands.size() > 4096) {
            throw new IllegalArgumentException("Too many lifecycle commands");
        }
        return List.copyOf(commands);
    }

    static List<CommandInvocation> expandInvocations(Configuration config, List<Member> roster, Set<UUID> online,
            UUID matchId, RandomGenerator random) {
        validateRoster(roster, config.teams());
        List<CommandInvocation> commands = new ArrayList<>();
        for (Action action : config.actions()) {
            switch (action.forEach()) {
                case "player" -> {
                    for (Member member : roster) {
                        addInvocation(commands, action.command(), action.sender(), member, member.team(), matchId);
                    }
                }
                case "team" -> {
                    for (String team : config.teams()) {
                        List<Member> teamMembers = roster.stream().filter(candidate -> candidate.team().equals(team)).toList();
                        Member member = action.sender().equals("player")
                                ? selectMember(teamMembers, online, random) : null;
                        if (action.sender().equals("player") && !teamMembers.isEmpty() && member == null) {
                            throw new IllegalArgumentException("No online team sender");
                        }
                        if (!action.sender().equals("player") || member != null) {
                            addInvocation(commands, action.command(), action.sender(), member, team, matchId);
                        }
                    }
                }
                case "" -> {
                    Member member = action.sender().equals("player") ? selectMember(roster, online, random) : null;
                    addInvocation(commands, action.command(), action.sender(), member,
                            member == null ? "" : member.team(), matchId);
                }
                default -> throw new IllegalArgumentException("Invalid command scope");
            }
        }
        for (TeamCommand command : config.teamCommands()) {
            for (Member member : roster) {
                if (command.team().equals(member.team())) {
                    addInvocation(commands, command.command(), command.sender(), member, member.team(), matchId);
                }
            }
        }
        for (ExecutableCommand command : config.startCommands()) {
            Member member = command.sender().equals("player") ? selectMember(roster, online, random) : null;
            addInvocation(commands, command.command(), command.sender(), member,
                    member == null ? "" : member.team(), matchId);
        }
        return List.copyOf(commands);
    }

    private static Member selectMember(List<Member> members, Set<UUID> online, RandomGenerator random) {
        List<Member> candidates = members.stream().filter(member -> online.contains(member.uuid())).toList();
        return candidates.isEmpty() ? null : candidates.get(random.nextInt(candidates.size()));
    }

    private static void addInvocation(List<CommandInvocation> commands, String command, String sender,
            Member member, String team, UUID matchId) {
        if (!Set.of("server", "player").contains(sender) || (sender.equals("player") && member == null)
                || commands.size() >= 10_000) {
            throw new IllegalArgumentException("Invalid or oversized command plan");
        }
        commands.add(new CommandInvocation(expand(command, member == null ? "" : member.name(),
                member == null ? "" : member.uuid().toString(), team, matchId),
                sender.equals("player") ? member.uuid() : null));
    }

    private static long playerSenderCommands(Configuration config) {
        return config.actions().stream().filter(command -> command.sender().equals("player")).count()
                + config.teamCommands().stream().filter(command -> command.sender().equals("player")).count()
                + config.startCommands().stream().filter(command -> command.sender().equals("player")).count()
                + config.onPlayerJoin().stream().filter(command -> command.sender().equals("player")).count();
    }

    record CommandInvocation(String command, UUID player) {
        String sender() {
            return player == null ? "server" : "player";
        }
    }

    record ExecutableCommand(String command, String sender) {
        ExecutableCommand(String command) {
            this(command, "server");
        }
    }

    record Action(String command, String forEach, String sender) {
        Action(String command, String forEach) {
            this(command, forEach, "server");
        }
    }
    record TeamCommand(String team, String command, String sender) {
        TeamCommand(String team, String command) {
            this(team, command, "server");
        }
    }
    record Member(UUID uuid, String name, String team) { }
    record Control(String state, UUID matchId, List<Member> roster, int rosterVersion) {
        Control(String state, UUID matchId, List<Member> roster) {
            this(state, matchId, roster, 0);
        }
    }
    record Report(String state, UUID matchId, List<String> players, int rosterVersion) { }
    record Configuration(URI endpoint, String token, List<String> teams, String trigger,
            List<Action> actions, Pattern endPattern, String lobbyMode, String instanceMode, String loggerName,
            List<TeamCommand> teamCommands, List<ExecutableCommand> startCommands, List<ExecutableCommand> onPlayerJoin) {
        Configuration {
            if (!Set.of("temporary", "permanent").contains(instanceMode)
                || (lobbyMode.equals("plugin") && (!actions.isEmpty() || !teamCommands.isEmpty() || !startCommands.isEmpty()))
                || actions.size() + teamCommands.size() + startCommands.size() + onPlayerJoin.size() > 100
                || (loggerName != null && !loggerName.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}"))
                    || (instanceMode.equals("permanent") && (!lobbyMode.equals("plugin")
                            || !actions.isEmpty() || endPattern != null))) {
                throw new IllegalArgumentException("Invalid instance mode");
            }
        }

        Configuration(URI endpoint, String token, List<String> teams, String trigger,
                List<Action> actions, Pattern endPattern, String lobbyMode, String instanceMode, String loggerName,
                List<TeamCommand> teamCommands, List<String> startCommands) {
            this(endpoint, token, teams, trigger, actions, endPattern, lobbyMode, instanceMode, loggerName,
                    teamCommands, startCommands.stream().map(ExecutableCommand::new).toList(), List.of());
        }

        boolean permanent() {
            return instanceMode.equals("permanent");
        }

        Configuration(URI endpoint, String token, List<String> teams, String trigger,
                List<Action> actions, Pattern endPattern, String lobbyMode, String instanceMode) {
            this(endpoint, token, teams, trigger, actions, endPattern, lobbyMode, instanceMode, null, List.of(), List.of());
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