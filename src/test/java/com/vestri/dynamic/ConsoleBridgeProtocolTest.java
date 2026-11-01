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