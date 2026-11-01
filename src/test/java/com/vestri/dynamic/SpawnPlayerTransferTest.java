package com.vestri.dynamic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import org.mockito.ArgumentCaptor;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.proxy.server.ServerPing;
import java.net.InetSocketAddress;
import java.net.ConnectException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SpawnPlayerTransferTest {
    private final ProxyServer proxy = mock(ProxyServer.class);
    private final Player player = mock(Player.class);
    private final RegisteredServer target = mock(RegisteredServer.class);
    private final List<Runnable> scheduled = new ArrayList<>();
    private final List<Duration> delays = new ArrayList<>();
    private final List<String> messages = new ArrayList<>();
    private final AtomicLong clock = new AtomicLong();
    private final MinecraftInstanceClient.SpawnedInstance instance = new MinecraftInstanceClient.SpawnedInstance(
            UUID.randomUUID().toString(), "mcgame-" + "a".repeat(32));
    private final SpawnPlayerTransfer transfer = new SpawnPlayerTransfer(proxy, (task, delay) -> {
        assertTrue(delay.compareTo(Duration.ofSeconds(2)) <= 0);
        delays.add(delay);
        scheduled.add(task);
    }, clock::get, Duration.ofMinutes(5));

    @BeforeEach
    void setUp() {
        UUID playerId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getUsername()).thenReturn("Luis");
        when(player.isActive()).thenReturn(true);
        when(proxy.getPlayer(playerId)).thenReturn(Optional.of(player));
        when(proxy.getPlayer("Luis")).thenReturn(Optional.of(player));
        when(target.getServerInfo()).thenReturn(new ServerInfo("BedWars-2",
                InetSocketAddress.createUnresolved("vestri-" + instance.slug(), 25565)));
        when(proxy.getAllServers()).thenReturn(List.of(target));
    }

    private CompletableFuture<ConnectionRequestBuilder.Result> prepareConnection() {
        var connection = mock(ConnectionRequestBuilder.class);
        var result = new CompletableFuture<ConnectionRequestBuilder.Result>();
        when(player.createConnectionRequest(target)).thenReturn(connection);
        when(connection.connect()).thenReturn(result);
        return result;
    }

    private ServerPing readyPing() {
        return ServerPing.builder().version(new ServerPing.Version(767, "1.21"))
                .description(Component.text("Ready")).build();
    }

    private void finishWarmup() {
        beginCountdown();
        finishCountdown();
    }

    private void beginCountdown() {
        for (int attempt = 0; attempt < 3; attempt++) {
            clock.addAndGet(Duration.ofSeconds(2).toNanos());
            scheduled.remove(0).run();
        }
    }

    private void finishCountdown() {
        for (int attempt = 0; attempt < 5; attempt++) {
            clock.addAndGet(Duration.ofSeconds(1).toNanos());
            scheduled.remove(0).run();
        }
    }

    @Test
    void waitsForRegistrationAndPingBeforeConnectingToExactInstance() {
        var ping = new CompletableFuture<ServerPing>();
        when(target.ping()).thenReturn(ping);
        var connection = prepareConnection();
        when(proxy.getAllServers()).thenReturn(List.of());
        transfer.start(player, instance, messages::add);
        verify(player, never()).createConnectionRequest(any());
        when(proxy.getAllServers()).thenReturn(List.of(target));
        scheduled.remove(0).run();
        verify(player, never()).createConnectionRequest(any());
        ping.complete(readyPing());
        verify(player, never()).createConnectionRequest(any());
        finishWarmup();
        verify(player).createConnectionRequest(target);
        var result = mock(ConnectionRequestBuilder.Result.class);
        when(result.isSuccessful()).thenReturn(true);
        connection.complete(result);
        assertTrue(messages.get(messages.size() - 1).contains("Connected Luis to BedWars-2"));
        assertEquals(0, scheduled.size());
        var chat = ArgumentCaptor.forClass(Component.class);
        verify(player, times(7)).sendMessage(chat.capture());
        var texts = chat.getAllValues().stream().map(PlainTextComponentSerializer.plainText()::serialize).toList();
        assertEquals(List.of("Your server is starting. We'll connect you when it's ready.",
            "Teleporting in 5...", "Teleporting in 4...", "Teleporting in 3...",
            "Teleporting in 2...", "Teleporting in 1...", "Connected to BedWars-2."), texts);
        assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1),
            Duration.ofSeconds(1), Duration.ofSeconds(1)), delays.subList(delays.size() - 5, delays.size()));
        assertTrue(texts.stream().noneMatch(text -> text.contains(instance.serverId()) || text.contains(instance.slug())));
        assertTrue(messages.stream().allMatch(text -> text.contains(instance.serverId())));
    }

    @Test
    void retriesFailedPingsAndTimesOutWithoutConnecting() {
        when(target.ping()).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("starting")));
        transfer.start(player, instance, messages::add);
        assertEquals(1, scheduled.size());
        clock.set(Duration.ofMinutes(5).toNanos());
        scheduled.remove(0).run();
        assertTrue(messages.get(messages.size() - 1).contains("timed out"));
        verify(player, never()).createConnectionRequest(any());
        assertEquals(0, scheduled.size());
    }

    @Test
    void cancelsAfterDisconnectAndDoesNotTransferReconnectedSession() {
        var ping = new CompletableFuture<ServerPing>();
        when(target.ping()).thenReturn(ping);
        transfer.start(player, instance, messages::add);
        var reconnected = mock(Player.class);
        when(proxy.getPlayer(player.getUniqueId())).thenReturn(Optional.of(reconnected));
        ping.complete(readyPing());
        verify(player, never()).createConnectionRequest(any());
        assertTrue(messages.get(messages.size() - 1).contains("cancelled"));
    }

    @Test
    void doesNotConnectWhenRegistrationChangesDuringPing() {
        var ping = new CompletableFuture<ServerPing>();
        when(target.ping()).thenReturn(ping);
        transfer.start(player, instance, messages::add);
        when(proxy.getAllServers()).thenReturn(List.of());
        ping.complete(readyPing());
        verify(player, never()).createConnectionRequest(any());
        assertEquals(1, scheduled.size());
    }

    @Test
    void doesNotPingOrdinaryServersOrGuessByFriendlyName() {
        var ordinary = mock(RegisteredServer.class);
        when(ordinary.getServerInfo()).thenReturn(new ServerInfo("BedWars-2", new InetSocketAddress("127.0.0.1", 25565)));
        when(proxy.getAllServers()).thenReturn(List.of(ordinary));
        transfer.start(player, instance, messages::add);
        verify(ordinary, never()).ping();
        verify(player, never()).createConnectionRequest(any());
    }

    @Test
    void reportsRejectedConnectionInsteadOfClaimingSuccess() {
        var ping = readyPing();
        when(target.ping()).thenReturn(CompletableFuture.completedFuture(ping));
        var connection = prepareConnection();
        transfer.start(player, instance, messages::add);
        finishWarmup();
        connection.complete(mock(ConnectionRequestBuilder.Result.class));
        assertTrue(messages.get(messages.size() - 1).contains("connection failed"));
        assertEquals(0, scheduled.size());
    }

    @Test
    void resetsWarmupWhenPingFails() {
        when(target.ping()).thenReturn(CompletableFuture.completedFuture(readyPing()));
        prepareConnection();
        transfer.start(player, instance, messages::add);
        clock.addAndGet(Duration.ofSeconds(4).toNanos());
        when(target.ping()).thenReturn(CompletableFuture.failedFuture(new ConnectException("starting")));
        scheduled.remove(0).run();
        when(target.ping()).thenReturn(CompletableFuture.completedFuture(readyPing()));
        clock.addAndGet(Duration.ofSeconds(2).toNanos());
        scheduled.remove(0).run();
        verify(player, never()).createConnectionRequest(any());
        finishWarmup();
        verify(player).createConnectionRequest(target);
    }

    @Test
    void retriesRefusedConnectionAfterAnotherStableReadinessWindow() {
        when(target.ping()).thenReturn(CompletableFuture.completedFuture(readyPing()));
        var connection = prepareConnection();
        transfer.start(player, instance, messages::add);
        finishWarmup();
        connection.completeExceptionally(new java.util.concurrent.CompletionException(new ConnectException("refused")));
        assertEquals(1, scheduled.size());
        assertTrue(messages.get(messages.size() - 1).contains("retrying"));
        verify(player, times(6)).sendMessage(any(Component.class));
        var retried = prepareConnection();
        clock.addAndGet(Duration.ofSeconds(2).toNanos());
        scheduled.remove(0).run();
        finishWarmup();
        var result = mock(ConnectionRequestBuilder.Result.class);
        when(result.isSuccessful()).thenReturn(true);
        retried.complete(result);
        assertTrue(messages.get(messages.size() - 1).contains("Connected"));
        assertEquals(0, scheduled.size());
    }

    @Test
    void restartsCountdownAfterFailedPingWithoutConnectingEarly() {
        when(target.ping()).thenReturn(CompletableFuture.completedFuture(readyPing()));
        prepareConnection();
        transfer.start(player, instance, messages::add);
        beginCountdown();
        when(target.ping()).thenReturn(CompletableFuture.failedFuture(new ConnectException("not ready")));
        clock.addAndGet(Duration.ofSeconds(1).toNanos());
        scheduled.remove(0).run();
        verify(player, never()).createConnectionRequest(any());
        when(target.ping()).thenReturn(CompletableFuture.completedFuture(readyPing()));
        clock.addAndGet(Duration.ofSeconds(2).toNanos());
        scheduled.remove(0).run();
        beginCountdown();
        verify(player, never()).createConnectionRequest(any());
        verify(player, times(2)).sendMessage(Component.text("Teleporting in 5...", net.kyori.adventure.text.format.NamedTextColor.AQUA));
        finishCountdown();
        verify(player).createConnectionRequest(target);
    }

    @Test
    void cancelsCountdownWhenPlayerDisconnects() {
        when(target.ping()).thenReturn(CompletableFuture.completedFuture(readyPing()));
        transfer.start(player, instance, messages::add);
        beginCountdown();
        when(player.isActive()).thenReturn(false);
        clock.addAndGet(Duration.ofSeconds(1).toNanos());
        scheduled.remove(0).run();
        verify(player, never()).createConnectionRequest(any());
        verify(player, times(2)).sendMessage(any(Component.class));
        assertEquals(0, scheduled.size());
        assertTrue(messages.get(messages.size() - 1).contains("cancelled"));
    }

    @Test
    void stopsAfterDeadlineEvenIfPingEventuallySucceeds() {
        var ping = new CompletableFuture<ServerPing>();
        when(target.ping()).thenReturn(ping);
        transfer.start(player, instance, messages::add);
        clock.set(Duration.ofMinutes(5).toNanos());
        ping.complete(readyPing());
        verify(player, never()).createConnectionRequest(any());
        assertTrue(messages.get(messages.size() - 1).contains("timed out"));
    }
}