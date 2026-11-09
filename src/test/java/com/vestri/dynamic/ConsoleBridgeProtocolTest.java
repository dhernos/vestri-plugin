package com.vestri.dynamic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.URLClassLoader;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConsoleBridgeProtocolTest {
    private final String secret = "a".repeat(64);
    private final long now = 1_000_000;

    @Test
    void addsStableSpawnRequestIdForBackendConsoleRetries() {
        String command = ConsoleBridgeProtocol.prepareCommand("vestridynamic spawn Bed Wars");
        org.junit.jupiter.api.Assertions.assertTrue(command.contains(" --request-id "));
        assertEquals(command, ConsoleBridgeProtocol.prepareCommand(command));
    }

    @Test
    void signsVariantAndPlayerWithStableRequestIdAndRejectsTampering() throws Exception {
        String original = "vestridynamic spawn Bed Wars --variant 4v4v4v4 --send-player Luis";
        String command = ConsoleBridgeProtocol.prepareCommand(original);
        org.junit.jupiter.api.Assertions.assertTrue(command.startsWith(original + " --request-id "));
        assertEquals(command, ConsoleBridgeProtocol.prepareCommand(command));
        byte[] payload = ConsoleBridgeProtocol.encode(secret, command, now, UUID.randomUUID());
        String verified = new ConsoleBridgeProtocol().verify(secret, payload, now);
        var parsed = LifecycleRequest.parse(verified.substring("vestridynamic ".length()).split("\\s+"));
        assertEquals("Bed Wars", parsed.template());
        assertEquals("4v4v4v4", parsed.variant());
        assertEquals("Luis", parsed.sendPlayer());
        assertEquals(command, verified);
        byte[] marker = "4v4v4v4".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int offset = java.util.Collections.indexOfSubList(
                java.util.stream.IntStream.range(0, payload.length).mapToObj(index -> payload[index]).toList(),
                java.util.stream.IntStream.range(0, marker.length).mapToObj(index -> marker[index]).toList());
        payload[offset] = '2';
        assertThrows(IOException.class, () -> new ConsoleBridgeProtocol().verify(secret, payload, now));
    }

    @Test
    void acceptsSyntacticallyValidUnknownVariantForBackendValidation() throws Exception {
        String command = "vestridynamic spawn Bed Wars --variant Unknown-Mode_2 --request-id " + UUID.randomUUID();
        assertEquals(command, ConsoleBridgeProtocol.prepareCommand(command));
        assertEquals(command, new ConsoleBridgeProtocol().verify(secret,
                ConsoleBridgeProtocol.encode(secret, command, now, UUID.randomUUID()), now));
    }

    @Test
    void rejectsVariantOutsideSpawnDuplicateFlagsAndUnsafeValues() {
        for (String command : new String[]{
                "vestridynamic list Bed Wars --variant solo",
                "vestridynamic templates --variant solo",
                "vestridynamic delete Bed Wars --confirm --variant solo",
                "vestridynamic spawn Bed Wars --variant solo --variant duo",
                "vestridynamic spawn Bed Wars --variant solo --send-player Luis --send-player Other",
                "vestridynamic spawn Bed Wars --variant solo --request-id " + UUID.randomUUID() + " --request-id " + UUID.randomUUID(),
                "vestridynamic spawn Bed Wars --variant solo --type temporary --send-player Luis",
                "vestridynamic spawn Bed Wars --variant mode;stop",
                "vestridynamic spawn Bed Wars --variant " + "a".repeat(65),
                "vestridynamic spawn Bed Wars --variant solo --arbitrary value"}) {
            assertThrows(IllegalArgumentException.class, () -> ConsoleBridgeProtocol.prepareCommand(command));
            assertThrows(IllegalArgumentException.class, () -> ConsoleBridgeProtocol.encode(secret, command, now, UUID.randomUUID()));
        }
    }

    @Test
    void authenticatesPlayerTargetAndPreservesItInRetryCommand() throws Exception {
        String command = ConsoleBridgeProtocol.prepareCommand("vestridynamic spawn Bed Wars --send-player Luis");
        assertEquals(command, ConsoleBridgeProtocol.prepareCommand(command));
        var parsed = LifecycleRequest.parse(command.substring("vestridynamic ".length()).split("\\s+"));
        assertEquals("Luis", parsed.sendPlayer());
        byte[] payload = ConsoleBridgeProtocol.encode(secret, command, now, UUID.randomUUID());
        assertEquals(command, new ConsoleBridgeProtocol().verify(secret, payload, now));
        assertThrows(IllegalArgumentException.class, () -> ConsoleBridgeProtocol.prepareCommand(
                "vestridynamic spawn Bed Wars --send-player {player}"));
    }

    @Test
    void signsWithoutVelocityOrBukkitOnClasspath() throws Exception {
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{ConsoleBridgeProtocol.class.getProtectionDomain().getCodeSource().getLocation()},
                ClassLoader.getPlatformClassLoader())) {
            Class<?> protocol = loader.loadClass("com.vestri.dynamic.ConsoleBridgeProtocol");
            byte[] payload = (byte[]) protocol.getMethod("encode", String.class, String.class, long.class, UUID.class)
                    .invoke(null, secret, "vestridynamic spawn Bed Wars", now, UUID.randomUUID());
            assertEquals("vestridynamic spawn Bed Wars", protocol.getMethod("verify", String.class, byte[].class, long.class)
                    .invoke(protocol.getConstructor().newInstance(), secret, payload, now));
        }
    }

    @Test
    void acceptsSignedLifecycleCommandOnce() throws Exception {
        ConsoleBridgeProtocol protocol = new ConsoleBridgeProtocol();
        String command = "vestridynamic spawn Bed Wars";
        byte[] payload = ConsoleBridgeProtocol.encode(secret, command, now, UUID.randomUUID());
        assertEquals(command, protocol.verify(secret, payload, now));
        assertThrows(IOException.class, () -> protocol.verify(secret, payload, now));
    }

    @Test
    void rejectsWrongSecretTamperingExpiryAndTrailingBytes() throws Exception {
        byte[] payload = ConsoleBridgeProtocol.encode(secret, "vestridynamic list Bed Wars", now, UUID.randomUUID());
        assertThrows(IOException.class, () -> new ConsoleBridgeProtocol().verify("b".repeat(64), payload, now));
        assertThrows(IOException.class, () -> new ConsoleBridgeProtocol().verify(secret, payload, now + 30_001));
        assertThrows(IOException.class, () -> new ConsoleBridgeProtocol().verify(secret, payload, now - 30_001));
        byte[] appended = java.util.Arrays.copyOf(payload, payload.length + 1);
        assertThrows(IOException.class, () -> new ConsoleBridgeProtocol().verify(secret, appended, now));
        payload[payload.length - 1] ^= 1;
        assertThrows(IOException.class, () -> new ConsoleBridgeProtocol().verify(secret, payload, now));
    }

    @Test
    void refusesArbitraryConsoleCommandsAndUnconfirmedDeletion() {
        for (String command : new String[]{"stop", "vestridynamic sync", "vestridynamic delete BedWars", "vestridynamic spawn BedWars\nstop"}) {
            assertThrows(IllegalArgumentException.class, () -> ConsoleBridgeProtocol.encode(secret, command, now, UUID.randomUUID()));
        }
    }
}