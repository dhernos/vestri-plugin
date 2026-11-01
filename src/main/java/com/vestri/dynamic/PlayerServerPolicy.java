package com.vestri.dynamic;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

record PlayerServerPolicy(boolean enabled, int permanentLimit, int temporaryLimit,
    boolean pauseEnabled, int pausedLimit, int emptyGraceSeconds, int deletionGraceSeconds) {
    static PlayerServerPolicy load(Path file) throws IOException {
        if (Files.size(file) > 64 * 1024) {
            throw new IOException("Plugin configuration exceeds 64 KiB");
        }
        try {
            JsonObject config = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            JsonObject policy = config.getAsJsonObject("playerServers");
            if (policy == null) {
                return new PlayerServerPolicy(false, 1, 2, true, 1, 30, 300);
            }
            return new PlayerServerPolicy(bool(policy, "enabled", false), number(policy, "maxPermanentPerUser", 1, 0),
                    number(policy, "maxTemporaryPerUser", 2, 0), bool(policy, "pauseEnabled", true),
                    number(policy, "maxPausedPerUser", 1, 0), number(policy, "emptyGraceSeconds", 30, 1),
                    number(policy, "deletionGraceSeconds", 300, 1));
        } catch (RuntimeException failure) {
            throw new IOException("Invalid playerServers configuration");
        }
    }

    int limit(OwnedServerRegistry.Type type) {
        return type == OwnedServerRegistry.Type.PERMANENT ? permanentLimit : temporaryLimit;
    }

    private static boolean bool(JsonObject object, String name, boolean fallback) {
        if (!object.has(name)) {
            return fallback;
        }
        if (!object.get(name).isJsonPrimitive() || !object.getAsJsonPrimitive(name).isBoolean()) {
            throw new IllegalArgumentException();
        }
        return object.get(name).getAsBoolean();
    }

    private static int number(JsonObject object, String name, int fallback, int minimum) {
        if (!object.has(name)) {
            return fallback;
        }
        int value = object.get(name).getAsBigDecimal().intValueExact();
        if (value < minimum || value > 86400) {
            throw new IllegalArgumentException();
        }
        return value;
    }
}