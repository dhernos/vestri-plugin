package com.vestri.dynamic;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class Parties {
    private record Invitation(UUID leader, long expires) {}
    private final Map<UUID, LinkedHashSet<UUID>> groups = new LinkedHashMap<>();
    private final Map<UUID, UUID> leaders = new LinkedHashMap<>();
    private final Map<UUID, Invitation> invitations = new LinkedHashMap<>();

    public synchronized UUID leader(UUID player) {
        return leaders.getOrDefault(player, player);
    }

    public synchronized List<UUID> members(UUID player) {
        var group = groups.get(leader(player));
        return group == null ? List.of(player) : List.copyOf(group);
    }

    public synchronized void invite(UUID inviter, UUID guest, long now) {
        if (inviter.equals(guest) || leaders.containsKey(guest)) {
            throw new IllegalArgumentException("That player is already in a party or is yourself.");
        }
        requireLeader(inviter);
        var group = groups.computeIfAbsent(inviter, ignored -> new LinkedHashSet<>(List.of(inviter)));
        if (group.size() >= 100) {
            throw new IllegalArgumentException("The party is full.");
        }
        leaders.put(inviter, inviter);
        invitations.entrySet().removeIf(entry -> entry.getValue().expires <= now);
        invitations.put(guest, new Invitation(inviter, now + Duration.ofMinutes(2).toMillis()));
    }

    public synchronized void accept(UUID guest, UUID inviter, long now) {
        var invitation = invitations.get(guest);
        if (invitation == null || !invitation.leader.equals(inviter) || invitation.expires <= now
                || !groups.containsKey(inviter)) {
            throw new IllegalArgumentException("No valid invitation from that player.");
        }
        if (leaders.containsKey(guest) || groups.get(inviter).size() >= 100) {
            throw new IllegalArgumentException("Already in a party, or the party is full.");
        }
        groups.get(inviter).add(guest);
        leaders.put(guest, inviter);
        invitations.remove(guest);
    }

    public synchronized void leave(UUID player) {
        UUID previousLeader = leaders.remove(player);
        if (previousLeader == null) {
            return;
        }
        var group = groups.get(previousLeader);
        group.remove(player);
        if (group.isEmpty()) {
            groups.remove(previousLeader);
            invitations.entrySet().removeIf(entry -> entry.getValue().leader.equals(previousLeader));
        } else if (previousLeader.equals(player)) {
            transfer(previousLeader, group.iterator().next());
        }
    }

    public synchronized void transfer(UUID player, UUID nextLeader) {
        requireLeader(player);
        var group = groups.get(player);
        if (group == null || !group.contains(nextLeader)) {
            throw new IllegalArgumentException("That player is not in your party.");
        }
        groups.remove(player);
        groups.put(nextLeader, group);
        group.forEach(member -> leaders.put(member, nextLeader));
        invitations.entrySet().removeIf(entry -> entry.getValue().leader.equals(player));
    }

    public synchronized void kick(UUID player, UUID guest) {
        requireLeader(player);
        if (guest.equals(player) || !members(player).contains(guest)) {
            throw new IllegalArgumentException("That player is not another party member.");
        }
        leave(guest);
    }

    public synchronized void disband(UUID player) {
        requireLeader(player);
        members(player).forEach(leaders::remove);
        groups.remove(player);
        invitations.entrySet().removeIf(entry -> entry.getValue().leader.equals(player));
    }

    private void requireLeader(UUID player) {
        if (!leader(player).equals(player)) {
            throw new IllegalArgumentException("Only the party leader can do that.");
        }
    }
}