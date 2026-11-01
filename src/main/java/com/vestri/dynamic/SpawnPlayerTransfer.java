package com.vestri.dynamic;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

final class SpawnPlayerTransfer {
    private final ProxyServer proxy;
    private final BiConsumer<Runnable, Duration> schedule;
    private final LongSupplier clock;
    private final Duration timeout;

    SpawnPlayerTransfer(ProxyServer proxy, BiConsumer<Runnable, Duration> schedule) {
        this(proxy, schedule, System::nanoTime, Duration.ofMinutes(5));
    }

    SpawnPlayerTransfer(ProxyServer proxy, BiConsumer<Runnable, Duration> schedule, LongSupplier clock, Duration timeout) {
        this.proxy = proxy;
        this.schedule = schedule;
        this.clock = clock;
        this.timeout = timeout;
    }

    Player findPlayer(String name) {
        return proxy.getPlayer(name).filter(Player::isActive).orElse(null);
    }

    void start(Player player, MinecraftInstanceClient.SpawnedInstance instance, Consumer<String> output) {
        new WaitingTransfer(player, instance, output).poll();
    }

    private final class WaitingTransfer {
        private final Player player;
        private final MinecraftInstanceClient.SpawnedInstance instance;
        private final Consumer<String> output;
        private final long deadline = clock.getAsLong() + timeout.toNanos();
        private final AtomicBoolean finished = new AtomicBoolean();
        private boolean announced;
        private RegisteredServer stableTarget;
        private long readySince;
        private int countdown;

        WaitingTransfer(Player player, MinecraftInstanceClient.SpawnedInstance instance, Consumer<String> output) {
            this.player = player;
            this.instance = instance;
            this.output = output;
        }

        private boolean active() {
            return player.isActive() && proxy.getPlayer(player.getUniqueId()).orElse(null) == player;
        }

        private boolean canContinue() {
            if (finished.get()) {
                return false;
            }
            if (!active()) {
                finish("Transfer cancelled: the player disconnected. The instance was not deleted.", null);
                return false;
            }
            if (remaining() <= 0) {
                finish("Transfer timed out waiting for Minecraft readiness or connection. The instance was not deleted; check the panel.",
                    Component.text("Your server is taking longer than expected. Please contact a staff member.", NamedTextColor.YELLOW));
                return false;
            }
            return true;
        }

        private long remaining() {
            return deadline - clock.getAsLong();
        }

        void poll() {
            if (!canContinue()) {
                return;
            }
            if (!announced) {
                announced = true;
                report("Waiting for Minecraft to become ready for " + player.getUsername() + ".",
                    Component.text("Your server is starting. We'll connect you when it's ready.", NamedTextColor.AQUA));
            }
            RegisteredServer target = resolve();
            if (target == null) {
                stableTarget = null;
                retry();
                return;
            }
            try {
                target.ping().orTimeout(Math.max(1, Math.min(remaining(), Duration.ofSeconds(5).toNanos())), TimeUnit.NANOSECONDS)
                        .whenComplete((ping, failure) -> {
                            if (!canContinue()) {
                                return;
                            }
                            if (failure != null || ping == null || resolve() != target) {
                                stableTarget = null;
                                retry();
                                return;
                            }
                            if (stableTarget != target) {
                                stableTarget = target;
                                readySince = clock.getAsLong();
                                countdown = 5;
                            }
                            if (clock.getAsLong() - readySince < Duration.ofSeconds(6).toNanos()) {
                                retry();
                                return;
                            }
                            if (countdown > 0) {
                                player.sendMessage(Component.text("Teleporting in " + countdown + "...", NamedTextColor.AQUA));
                                countdown--;
                                retry();
                                return;
                            }
                            connect(target);
                        });
            } catch (RuntimeException failure) {
                stableTarget = null;
                retry();
            }
        }

        private RegisteredServer resolve() {
            var matches = proxy.getAllServers().stream().filter(server -> {
                var address = server.getServerInfo().getAddress();
                return address.getPort() == 25565 && address.getHostString().equals("vestri-" + instance.slug());
            }).toList();
            return matches.size() == 1 ? matches.get(0) : null;
        }

        private void retry() {
            if (canContinue()) {
                long interval = stableTarget != null && clock.getAsLong() - readySince >= Duration.ofSeconds(6).toNanos()
                        ? Duration.ofSeconds(1).toNanos() : Duration.ofSeconds(2).toNanos();
                schedule.accept(this::poll, Duration.ofNanos(Math.min(remaining(), interval)));
            }
        }

        private void connect(RegisteredServer target) {
            if (!canContinue()) {
                return;
            }
            try {
                player.createConnectionRequest(target).connect()
                        .orTimeout(Math.max(1, Math.min(remaining(), Duration.ofSeconds(10).toNanos())), TimeUnit.NANOSECONDS)
                        .whenComplete((result, failure) -> {
                            if (!canContinue()) {
                                return;
                            }
                            if (retryable(failure)) {
                                stableTarget = null;
                                output.accept("Instance " + instance.serverId() + ": connection not yet available; retrying readiness checks.");
                                retry();
                            } else if (failure != null || result == null || !result.isSuccessful()) {
                                finish("Minecraft answered the ping, but the player connection failed. Check server access rules and proxy forwarding settings.",
                                    Component.text("We couldn't connect you to your server. Please contact a staff member.", NamedTextColor.RED));
                            } else {
                                finish("Connected " + player.getUsername() + " to " + target.getServerInfo().getName() + ".",
                                    Component.text("Connected to " + target.getServerInfo().getName() + ".", NamedTextColor.GREEN));
                            }
                        });
            } catch (RuntimeException failure) {
                if (retryable(failure)) {
                    stableTarget = null;
                    retry();
                } else {
                        finish("Player connection failed. Check server access rules and proxy forwarding settings.",
                            Component.text("We couldn't connect you to your server. Please contact a staff member.", NamedTextColor.RED));
                }
            }
        }

        private boolean retryable(Throwable failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConnectException || cause instanceof NoRouteToHostException) {
                    return true;
                }
            }
            return false;
        }

        private void report(String message, Component playerMessage) {
            String text = "Instance " + instance.serverId() + ": " + message;
            output.accept(text);
            if (playerMessage != null && active()) {
                player.sendMessage(playerMessage);
            }
        }

        private void finish(String message, Component playerMessage) {
            if (finished.compareAndSet(false, true)) {
            report(message, playerMessage);
            }
        }
    }
}