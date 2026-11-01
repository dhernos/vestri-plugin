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
import java.util.LinkedHashMap;
import java.util.Map;

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
        String name;
        Map<UUID, String> invited = new LinkedHashMap<>();
        boolean paused;
        State state;
        long idleSince;
        long joinUntil;
        long deleteAfter;

        MinecraftInstanceClient.SpawnedInstance target() {
            return new MinecraftInstanceClient.SpawnedInstance(serverId, slug,
                    name != null && name.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}-[1-9][0-9]*") ? name : null);
        }

        String displayName() {
            return name == null ? template : name;
        }

        boolean canJoin(UUID player, boolean admin) {
            return admin || owner.equals(player) || invited.containsKey(player);
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
                    if (entry.invited == null) {
                        entry.invited = new LinkedHashMap<>();
                    }
                    if (entry.name == null) {
                        String prefix = entry.template.replaceAll("[^A-Za-z0-9_-]", "").replaceAll("^[_-]+", "");
                        prefix = prefix.isEmpty() ? "Server" : prefix.substring(0, Math.min(prefix.length(), 48));
                        entry.name = prefix + "-saved-" + (entries.size() + 1);
                    }
                    if (entry.name != null && !entry.name.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,79}")) {
                        throw new IllegalArgumentException();
                    }
                    if (entry.invited.size() > 100 || entry.invited.entrySet().stream().anyMatch(invite -> invite.getKey() == null
                            || invite.getValue() == null || !invite.getValue().matches("[A-Za-z0-9_]{1,16}"))) {
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
        String prefix = template.replaceAll("[^A-Za-z0-9_-]", "").replaceAll("^[_-]+", "");
        if (prefix.isEmpty()) {
            prefix = "Server";
        }
        prefix = prefix.substring(0, Math.min(prefix.length(), 48));
        int sequence = 1;
        String candidate = prefix + "-pending-" + sequence;
        while (nameExists(candidate)) {
            candidate = prefix + "-pending-" + ++sequence;
        }
        entry.name = candidate;
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
        return select(owned(owner), id, "You do not own that server. Check your server list.");
    }

    synchronized List<Entry> accessible(UUID player, boolean admin) {
        return entries.stream().filter(entry -> entry.canJoin(player, admin)).toList();
    }

    synchronized Entry requireJoin(UUID player, String name, boolean admin) throws IOException {
        return select(accessible(player, admin), name, "You can only join your own servers or servers you were invited to.");
    }

    private boolean nameExists(String name) {
        return entries.stream().anyMatch(entry -> name.equalsIgnoreCase(entry.displayName()));
    }

    private static Entry select(List<Entry> candidates, String name, String error) throws IOException {
        List<Entry> selected = candidates.stream().filter(entry -> name.equalsIgnoreCase(entry.displayName())
                || name.equals(entry.serverId) || name.equals(entry.requestId.toString())).toList();
        if (selected.size() != 1) {
            throw new IOException(selected.isEmpty() ? error : "That server name is ambiguous. Contact an administrator.");
        }
        return selected.get(0);
    }

    synchronized void invite(Entry entry, UUID player, String username) throws IOException {
        if (entry.invited.size() >= 100 && !entry.invited.containsKey(player)) {
            throw new IOException("This server has reached its invitation limit.");
        }
        String previous = entry.invited.put(player, username);
        try {
            save();
        } catch (IOException failure) {
            if (previous == null) {
                entry.invited.remove(player);
            } else {
                entry.invited.put(player, previous);
            }
            throw failure;
        }
    }

    synchronized void revoke(Entry entry, String username) throws IOException {
        UUID player = entry.invited.entrySet().stream().filter(invite -> invite.getValue().equalsIgnoreCase(username))
                .map(Map.Entry::getKey).findFirst().orElseThrow(() -> new IOException("That player is not invited to this server."));
        String previous = entry.invited.remove(player);
        try {
            save();
        } catch (IOException failure) {
            entry.invited.put(player, previous);
            throw failure;
        }
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