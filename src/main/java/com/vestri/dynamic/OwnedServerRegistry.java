package com.vestri.dynamic;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class OwnedServerRegistry {
    enum Type { PERMANENT, TEMPORARY }
    enum State { PROVISIONING, ACTIVE, STOPPING, STOPPED, PENDING_DELETION, STARTING, DELETING }

    static final class Entry {
        UUID owner;
        UUID requestId;
        String template;
        Type type;
        String serverId;
        String slug;
        boolean paused;
        State state;
        long idleSince;
        long joinUntil;
        long deleteAfter;

        MinecraftInstanceClient.SpawnedInstance target() {
            return new MinecraftInstanceClient.SpawnedInstance(serverId, slug);
        }
    }

    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;
    private final List<Entry> entries = new ArrayList<>();

    OwnedServerRegistry(Path file) throws IOException {
        this.file = file;
        if (Files.exists(file)) {
            if (Files.size(file) > 4 * 1024 * 1024) {
                throw new IOException("Owned server registry exceeds 4 MiB");
            }
            try {
                Entry[] saved = JSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Entry[].class);
                if (saved == null) {
                    throw new IllegalArgumentException();
                }
                for (Entry entry : saved) {
                    if (entry.owner == null || entry.requestId == null || entry.template == null
                            || entry.type == null || entry.state == null || entries.stream().anyMatch(
                                existing -> existing.requestId.equals(entry.requestId))) {
                        throw new IllegalArgumentException();
                    }
                    if (entry.serverId != null) {
                        entry.target();
                    } else if (entry.state != State.PROVISIONING) {
                        throw new IllegalArgumentException();
                    }
                    if (entry.state == State.PENDING_DELETION && entry.deleteAfter <= 0) {
                        throw new IllegalArgumentException();
                    }
                    entries.add(entry);
                }
            } catch (RuntimeException failure) {
                throw new IOException("Invalid owned server registry; restore it before enabling player servers");
            }
        }
    }

    synchronized List<Entry> all() {
        return List.copyOf(entries);
    }

    synchronized List<Entry> owned(UUID owner) {
        return entries.stream().filter(entry -> entry.owner.equals(owner)).toList();
    }

    synchronized Entry reserve(UUID owner, String template, Type type, int limit) throws IOException {
        return reserve(owner, template, type, limit, UUID.randomUUID());
    }

    synchronized Entry reserve(UUID owner, String template, Type type, int limit, UUID requestId) throws IOException {
        Entry existing = entries.stream().filter(entry -> entry.requestId.equals(requestId)).findFirst().orElse(null);
        if (existing != null) {
            if (!existing.owner.equals(owner) || !existing.template.equals(template) || existing.type != type) {
                throw new IOException("Request ID already belongs to a different owner, template, or server type.");
            }
            return existing;
        }
        if (owned(owner).stream().filter(entry -> entry.type == type).count() >= limit) {
            throw new IOException("Your " + type.name().toLowerCase(java.util.Locale.ROOT) + " server limit is " + limit + ".");
        }
        Entry entry = new Entry();
        entry.owner = owner;
        entry.template = template;
        entry.type = type;
        entry.requestId = requestId;
        entry.state = State.PROVISIONING;
        entries.add(entry);
        try {
            save();
        } catch (IOException failure) {
            entries.remove(entry);
            throw failure;
        }
        return entry;
    }

    synchronized Entry require(UUID owner, String id) throws IOException {
        return owned(owner).stream().filter(entry -> id.equals(entry.serverId) || id.equals(entry.requestId.toString()))
                .findFirst().orElseThrow(() -> new IOException("You do not own that server. Use /vestriservers list."));
    }

    synchronized void remove(Entry entry) throws IOException {
        int index = entries.indexOf(entry);
        if (index < 0) {
            return;
        }
        entries.remove(index);
        try {
            save();
        } catch (IOException failure) {
            entries.add(index, entry);
            throw failure;
        }
    }

    synchronized void save() throws IOException {
        Path pending = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(pending, JSON.toJson(entries) + "\n", StandardCharsets.UTF_8);
        Files.move(pending, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}