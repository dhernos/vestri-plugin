package com.vestri.dynamic;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class ConsoleBridgeProtocol {
    public static final String CHANNEL = "vestri:console";
    private final Map<UUID, Long> seen = new HashMap<>();

    public static byte[] encode(String secret, String command, long timestamp, UUID nonce) throws IOException {
        validateCommand(command);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeLong(timestamp);
            output.writeUTF(nonce.toString());
            output.writeUTF(command);
            output.write(signature(secret, timestamp, nonce, command));
        }
        return bytes.toByteArray();
    }

    public synchronized String verify(String secret, byte[] payload, long now) throws IOException {
        if (payload.length > 2048) {
            throw new IOException("Bridge message too large");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            long timestamp = input.readLong();
            UUID nonce = UUID.fromString(input.readUTF());
            String command = input.readUTF();
            byte[] signature = input.readNBytes(32);
            if (timestamp < now - 30_000 || timestamp > now + 30_000 || input.available() != 0
                    || !MessageDigest.isEqual(signature, signature(secret, timestamp, nonce, command))) {
                throw new IOException("Invalid or expired bridge message");
            }
            validateCommand(command);
            seen.entrySet().removeIf(entry -> entry.getValue() < now - 30_000);
            if (seen.containsKey(nonce) || seen.size() >= 1024) {
                throw new IOException("Replayed bridge message or bridge rate limit reached");
            }
            seen.put(nonce, timestamp);
            return command;
        } catch (IllegalArgumentException failure) {
            throw new IOException("Invalid bridge message");
        }
    }

    public static void validateCommand(String command) {
        if (command.length() > 512 || command.chars().anyMatch(value -> value < 32 || value == 127)
                || !command.startsWith("vestridynamic ")) {
            throw new IllegalArgumentException("Only Vestri lifecycle commands can be forwarded");
        }
        LifecycleRequest.parse(command.substring("vestridynamic ".length()).trim().split("\\s+"));
    }

    public static String prepareCommand(String command) {
        validateCommand(command);
        String[] arguments = command.substring("vestridynamic ".length()).trim().split("\\s+");
        LifecycleRequest request = LifecycleRequest.parse(arguments);
        if (request.action().equals("spawn") && !java.util.Arrays.asList(arguments).contains("--request-id")) {
            return command + " --request-id " + request.requestId();
        }
        return command;
    }

    private static byte[] signature(String secret, long timestamp, UUID nonce, String command) throws IOException {
        if (!secret.matches("[a-fA-F0-9]{64}")) {
            throw new IOException("Configure a 64-character hexadecimal bridge secret");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(HexFormat.of().parseHex(secret), "HmacSHA256"));
            return mac.doFinal((timestamp + "\n" + nonce + "\n" + command).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException failure) {
            throw new IOException("Bridge authentication unavailable");
        }
    }
}