package com.vestri.dynamic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class Matchmaker {
    record Group(UUID leader, List<UUID> players) {
        Group {
            players = List.copyOf(players);
            if (players.isEmpty() || !players.contains(leader) || players.stream().distinct().count() != players.size()) {
                throw new IllegalArgumentException("Invalid queue group");
            }
        }
    }

    record Match(List<Group> groups, Map<UUID, String> teams) {}

    static Match select(List<Group> queue, List<String> teams, int teamSize, int minimum, int maximum) {
        return select(queue, teams, teamSize, minimum, maximum, Map.of());
    }

    static Match select(List<Group> queue, List<String> teams, int teamSize, int minimum, int maximum,
                        Map<UUID, String> existing) {
        int[] counts = new int[teams.size()];
        existing.values().forEach(team -> {
            int index = teams.indexOf(team);
            if (index >= 0) {
                counts[index]++;
            }
        });
        List<Group> selected = new ArrayList<>();
        Map<UUID, String> assignments = new LinkedHashMap<>();
        for (Group group : queue) {
            if (existing.size() + assignments.size() + group.players.size() > maximum
                    || group.players.stream().anyMatch(player -> assignments.containsKey(player) || existing.containsKey(player))) {
                continue;
            }
            if (teams.isEmpty()) {
                group.players.forEach(player -> assignments.put(player, ""));
                selected.add(group);
                continue;
            }
            int best = -1;
            for (int index = 0; index < counts.length; index++) {
                if (counts[index] + group.players.size() <= teamSize && (best < 0 || counts[index] < counts[best])) {
                    best = index;
                }
            }
            if (best >= 0) {
                counts[best] += group.players.size();
                String team = teams.get(best);
                group.players.forEach(player -> assignments.put(player, team));
                selected.add(group);
            }
        }
        return assignments.size() < minimum ? null : new Match(List.copyOf(selected), assignments);
    }
}