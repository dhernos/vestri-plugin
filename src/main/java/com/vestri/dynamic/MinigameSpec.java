package com.vestri.dynamic;

import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

record MinigameSpec(List<String> teams, int teamSize, int minPlayers, int maxPlayers,
                    long joinTimeout, int readyServers, int maxInstances, boolean whenEmpty,
                    long emptyGrace, String fallback, String lobbyMode, boolean permanent) {
    boolean teamfree() {
        return teams.isEmpty();
    }

    boolean nativeLobby() {
        return lobbyMode.equals("plugin");
    }

    static MinigameSpec parse(JsonObject json) {
        List<String> teams = new ArrayList<>();
        if (json.has("teams") && !json.get("teams").isJsonNull()) {
            json.getAsJsonArray("teams").forEach(team -> teams.add(team.getAsString()));
        }
        int teamSize = json.has("teamSize") ? json.get("teamSize").getAsInt() : 0;
        String lobbyMode = json.has("lobbyMode") ? json.get("lobbyMode").getAsString() : "vestri";
        String instanceMode = json.has("instanceMode") ? json.get("instanceMode").getAsString() : "temporary";
        boolean permanent = instanceMode.equals("permanent");
        int minimum = json.get("minPlayers").getAsInt();
        int maximum = json.get("maxPlayers").getAsInt();
        int ready = json.get("readyServers").getAsInt();
        int limit = json.get("maxInstances").getAsInt();
        JsonObject end = json.getAsJsonObject("lifecycle").getAsJsonObject("end");
        String fallback = end.get("returnPlayersTo").getAsString();
        if (teams.size() > 100 || new HashSet<>(teams).size() != teams.size()
                || teams.stream().anyMatch(team -> !team.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))
            || teamSize < 0 || teamSize > 100 || !teams.isEmpty() && (teamSize < 1 || maximum > teams.size() * teamSize)
            || !List.of("vestri", "plugin").contains(lobbyMode)
            || !List.of("temporary", "permanent").contains(instanceMode)
            || permanent && (!lobbyMode.equals("plugin") || end.get("deleteServer").getAsBoolean()
                || end.get("whenEmpty").getAsBoolean())
            || minimum < 1 || minimum > maximum || maximum > 100
            || limit < 1 || limit > 100 || ready < 0 || ready > limit
                || !fallback.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}") || !permanent && !end.get("deleteServer").getAsBoolean()) {
            throw new IllegalArgumentException("Invalid minigame template settings");
        }
        return new MinigameSpec(List.copyOf(teams), teamSize, minimum, maximum,
                duration(json.get("joinTimeout").getAsString()), ready, limit,
                end.get("whenEmpty").getAsBoolean(), duration(end.get("emptyGrace").getAsString()), fallback, lobbyMode, permanent);
    }

    static long duration(String text) {
        var matcher = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)(ms|s|m|h)").matcher(text);
        BigDecimal milliseconds = BigDecimal.ZERO;
        int end = 0;
        while (matcher.find()) {
            if (matcher.start() != end) {
                throw new IllegalArgumentException("Invalid duration");
            }
            long multiplier = switch (matcher.group(2)) {
                case "ms" -> 1;
                case "s" -> 1000;
                case "m" -> 60000;
                default -> 3600000;
            };
            milliseconds = milliseconds.add(new BigDecimal(matcher.group(1)).multiply(BigDecimal.valueOf(multiplier)));
            end = matcher.end();
        }
        long result = milliseconds.longValueExact();
        if (end != text.length() || result < 1000 || result > 600000) {
            throw new IllegalArgumentException("Duration must be between 1s and 10m");
        }
        return result;
    }
}