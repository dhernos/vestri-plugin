package com.vestri.dynamic;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OwnedServerRegistryTest {
    @TempDir Path directory;

    @Test
    void reservationsPersistAndLimitsIncludePendingAndPausedServers() throws Exception {
        Path file = directory.resolve("owned.json");
        UUID owner = UUID.randomUUID();
        var registry = new OwnedServerRegistry(file);
        var entry = registry.reserve(owner, "Survival", OwnedServerRegistry.Type.TEMPORARY, 1);
        entry.paused = true;
        registry.save();
        var restored = new OwnedServerRegistry(file);
        assertEquals(entry.requestId, restored.owned(owner).get(0).requestId);
        assertTrue(restored.owned(owner).get(0).paused);
        assertThrows(IOException.class, () -> restored.reserve(owner, "Other", OwnedServerRegistry.Type.TEMPORARY, 1));
        restored.reserve(owner, "Survival", OwnedServerRegistry.Type.PERMANENT, 1);
        restored.reserve(UUID.randomUUID(), "Survival", OwnedServerRegistry.Type.TEMPORARY, 1);
        assertThrows(IOException.class, () -> restored.require(UUID.randomUUID(), entry.requestId.toString()));
        restored.remove(restored.require(owner, entry.requestId.toString()));
        assertEquals(1, new OwnedServerRegistry(file).owned(owner).size());
    }

    @Test
    void corruptRegistryFailsClosedAndMissingPolicyDisablesPlayerCreation() throws Exception {
        Path file = directory.resolve("owned.json");
        Files.writeString(file, "{}");
        assertThrows(IOException.class, () -> new OwnedServerRegistry(file));
        Path config = directory.resolve("config.json");
        Files.writeString(config, "{}");
        assertFalse(PlayerServerPolicy.load(config).enabled());
        assertEquals(300, PlayerServerPolicy.load(config).deletionGraceSeconds());
        Files.writeString(config, "{\"playerServers\":{\"maxTemporaryPerUser\":-1}}");
        assertThrows(IOException.class, () -> PlayerServerPolicy.load(config));
        Files.writeString(config, "{\"playerServers\":{\"enabled\":true,\"maxTemporaryPerUser\":0}}");
        assertEquals(0, PlayerServerPolicy.load(config).temporaryLimit());
    }

    @Test
    void deletionWindowIsConfigurableAndPendingEntriesRequireADeadline() throws Exception {
        Path config = directory.resolve("config.json");
        Files.writeString(config, "{\"playerServers\":{\"deletionGraceSeconds\":120}}");
        assertEquals(120, PlayerServerPolicy.load(config).deletionGraceSeconds());
        Files.writeString(config, "{\"playerServers\":{\"deletionGraceSeconds\":0}}");
        assertThrows(IOException.class, () -> PlayerServerPolicy.load(config));
        Path file = directory.resolve("owned.json");
        var registry = new OwnedServerRegistry(file);
        var entry = registry.reserve(UUID.randomUUID(), "survival", OwnedServerRegistry.Type.TEMPORARY, 1);
        entry.serverId = UUID.randomUUID().toString();
        entry.slug = "mcgame-" + "a".repeat(32);
        entry.state = OwnedServerRegistry.State.PENDING_DELETION;
        registry.save();
        assertThrows(IOException.class, () -> new OwnedServerRegistry(file));
    }
}