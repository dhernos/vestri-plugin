package com.vestri.dynamic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.Comparator;
import java.util.HashSet;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

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
        return select(queue, teams, teamSize, minimum, maximum, existing, ThreadLocalRandom.current());
    }

    static Match select(List<Group> queue, List<String> teams, int teamSize, int minimum, int maximum,
                        RandomGenerator random) {
        return select(queue, teams, teamSize, minimum, maximum, Map.of(), random);
    }

    static Match select(List<Group> queue, List<String> teams, int teamSize, int minimum, int maximum,
                        Map<UUID, String> existing, RandomGenerator random) {
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
                for (UUID player : group.players) {
                    assignments.put(player, "");
                }
                selected.add(group);
                continue;
            }
            List<Group> candidate = new ArrayList<>(selected);
            candidate.add(group);
            Map<UUID, String> packed = pack(candidate, teams, teamSize, counts, random);
            if (packed != null) {
                assignments.clear();
                assignments.putAll(packed);
                selected.add(group);
            }
        }
        return assignments.size() < minimum ? null : new Match(List.copyOf(selected), assignments);
    }

    static Match select(List<Group> queue, MinigameSpec spec, int minimum, Map<UUID, String> existing) {
        return select(queue, spec, minimum, existing, ThreadLocalRandom.current());
    }

    static Match select(List<Group> queue, MinigameSpec spec, int minimum, Map<UUID, String> existing,
                        RandomGenerator random) {
        if (!spec.teamPolicy().equals("roles")) {
            return select(queue, spec.teams(), spec.teamSize(), minimum, spec.maxPlayers(), existing, random);
        }
        Match roster = select(queue, List.of(), 0, minimum, spec.maxPlayers(), existing, random);
        if (roster == null) {
            return null;
        }
        Map<String, Integer> remaining = new LinkedHashMap<>();
        spec.teamCounts().forEach((team, count) -> remaining.put(team,
                Math.max(0, count - (int) existing.values().stream().filter(team::equals).count())));
        if (remaining.values().stream().mapToInt(Integer::intValue).sum() > roster.teams.size()) {
            return null;
        }
        List<UUID> players = new ArrayList<>(roster.teams.keySet());
        shuffle(players, random);
        Map<UUID, String> result = new LinkedHashMap<>();
        int offset = 0;
        for (String team : spec.teams()) {
            for (int count = 0; count < remaining.getOrDefault(team, 0); count++) {
                result.put(players.get(offset++), team);
            }
        }
        while (offset < players.size()) {
            result.put(players.get(offset++), spec.remainderTeam());
        }
        return new Match(roster.groups, result);
    }

    private static <T> void shuffle(List<T> values, RandomGenerator random) {
        for (int index = values.size() - 1; index > 0; index--) {
            java.util.Collections.swap(values, index, random.nextInt(index + 1));
        }
    }

    private static Map<UUID, String> pack(List<Group> groups, List<String> teams, int capacity,
                                           int[] existing, RandomGenerator random) {
        List<Group> ordered = new ArrayList<>(groups);
        shuffle(ordered, random);
        ordered.sort(Comparator.comparingInt((Group group) -> group.players.size()).reversed());
        int[] counts = existing.clone();
        int[] placement = new int[ordered.size()];
        int[] budget = {200000};
        int total = java.util.Arrays.stream(existing).sum() + groups.stream().mapToInt(group -> group.players.size()).sum();
        if (!place(ordered, 0, counts, placement, capacity, capacity, total, random, budget)) {
            return null;
        }
        int spread = java.util.Arrays.stream(counts).max().orElse(0) - java.util.Arrays.stream(counts).min().orElse(0);
        for (int target = total % teams.size() == 0 ? 0 : 1; target < spread && budget[0] > 0; target++) {
            int[] trialCounts = existing.clone();
            int[] trial = new int[ordered.size()];
            if (place(ordered, 0, trialCounts, trial, capacity, target, total, random, budget)) {
                placement = trial;
                break;
            }
        }
        Map<UUID, String> assignments = new LinkedHashMap<>();
        for (int index = 0; index < ordered.size(); index++) {
            String team = teams.get(placement[index]);
            ordered.get(index).players.forEach(player -> assignments.put(player, team));
        }
        Map<UUID, String> fifo = new LinkedHashMap<>();
        groups.forEach(group -> group.players.forEach(player -> fifo.put(player, assignments.get(player))));
        return fifo;
    }

    private static boolean place(List<Group> groups, int offset, int[] counts, int[] placement,
                                 int capacity, int spread, int total, RandomGenerator random, int[] budget) {
        if (--budget[0] < 0) {
            return false;
        }
        int upper = Math.min(capacity, total / counts.length + spread);
        int lower = Math.max(0, (total + counts.length - 1) / counts.length - spread);
        if (java.util.Arrays.stream(counts).anyMatch(count -> count > upper)) {
            return false;
        }
        int remaining = groups.subList(offset, groups.size()).stream().mapToInt(group -> group.players.size()).sum();
        if (java.util.Arrays.stream(counts).map(count -> Math.max(0, lower - count)).sum() > remaining) {
            return false;
        }
        if (offset == groups.size()) {
            return java.util.Arrays.stream(counts).max().orElse(0) - java.util.Arrays.stream(counts).min().orElse(0) <= spread;
        }
        List<Integer> choices = new ArrayList<>();
        for (int index = 0; index < counts.length; index++) {
            choices.add(index);
        }
        shuffle(choices, random);
        choices.sort(Comparator.comparingInt(index -> counts[index]));
        Set<Integer> tried = new HashSet<>();
        int size = groups.get(offset).players.size();
        for (int team : choices) {
            if (counts[team] + size > upper || !tried.add(counts[team])) {
                continue;
            }
            counts[team] += size;
            placement[offset] = team;
            if (place(groups, offset + 1, counts, placement, capacity, spread, total, random, budget)) {
                return true;
            }
            counts[team] -= size;
        }
        return false;
    }
}