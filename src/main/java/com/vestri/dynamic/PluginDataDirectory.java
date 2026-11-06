package com.vestri.dynamic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

final class PluginDataDirectory {
    private static final List<String> LEGACY_FILES = List.of(
            "vestri-dynamic-config.json", "vestri-dynamic-servers.json", "vestri-player-servers.json",
            "vestri-players.json", "vestri-player-policy.json", "vestri-player-logs");

    private PluginDataDirectory() {
    }

    static void prepare(Path legacyDirectory, Path dataDirectory) throws IOException {
        Files.createDirectories(dataDirectory);
        for (String name : LEGACY_FILES) {
            Path source = legacyDirectory.resolve(name);
            if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            if (Files.isSymbolicLink(source)) {
                throw new IOException("Refusing to migrate symbolic link: " + source);
            }
            if (Files.exists(dataDirectory.resolve(name), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Both legacy and plugin-folder data exist for " + name + "; resolve the conflict before starting");
            }
        }
        for (String name : LEGACY_FILES) {
            Path source = legacyDirectory.resolve(name);
            if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                Files.move(source, dataDirectory.resolve(name));
            }
        }
    }
}