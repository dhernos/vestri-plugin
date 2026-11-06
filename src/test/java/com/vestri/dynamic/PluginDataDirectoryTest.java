package com.vestri.dynamic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginDataDirectoryTest {
    @TempDir
    Path directory;

    @Test
    void movesExistingConfigStateAndArchivesAndAllowsRestart() throws Exception {
        Path data = directory.resolve("plugins/vestri-dynamic");
        Files.writeString(directory.resolve("vestri-dynamic-config.json"), "secret-config");
        Files.writeString(directory.resolve("vestri-player-servers.json"), "owned-servers");
        Files.createDirectories(directory.resolve("vestri-player-logs"));
        Files.writeString(directory.resolve("vestri-player-logs/2026-11-06.jsonl"), "event");
        Files.writeString(directory.resolve("server.jar"), "unrelated");
        PluginDataDirectory.prepare(directory, data);
        PluginDataDirectory.prepare(directory, data);
        assertEquals("secret-config", Files.readString(data.resolve("vestri-dynamic-config.json")));
        assertEquals("owned-servers", Files.readString(data.resolve("vestri-player-servers.json")));
        assertEquals("event", Files.readString(data.resolve("vestri-player-logs/2026-11-06.jsonl")));
        assertFalse(Files.exists(directory.resolve("vestri-dynamic-config.json")));
        assertFalse(Files.exists(directory.resolve("vestri-player-logs")));
        assertTrue(Files.exists(directory.resolve("server.jar")));
    }

    @Test
    void rejectsConflictsBeforeMovingAnyFiles() throws Exception {
        Path data = directory.resolve("plugins/vestri-dynamic");
        Files.createDirectories(data);
        Files.writeString(directory.resolve("vestri-dynamic-config.json"), "legacy");
        Files.writeString(directory.resolve("vestri-players.json"), "old-journal");
        Files.writeString(data.resolve("vestri-players.json"), "new-journal");
        assertThrows(IOException.class, () -> PluginDataDirectory.prepare(directory, data));
        assertEquals("new-journal", Files.readString(data.resolve("vestri-players.json")));
        assertEquals("legacy", Files.readString(directory.resolve("vestri-dynamic-config.json")));
    }

    @Test
    void rejectsLegacySymlinks() throws Exception {
        Path outside = directory.resolve("outside.json");
        Files.writeString(outside, "private");
        Files.createSymbolicLink(directory.resolve("vestri-dynamic-config.json"), outside);
        assertThrows(IOException.class, () -> PluginDataDirectory.prepare(directory, directory.resolve("plugins/vestri-dynamic")));
        assertEquals("private", Files.readString(outside));
    }
}