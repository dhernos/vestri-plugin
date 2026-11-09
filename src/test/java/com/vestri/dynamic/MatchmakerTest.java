package com.vestri.dynamic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MatchmakerTest {
    @Test
    void balancesEveryConfiguredTeamAndRepacksAdversarialParties() {
        var teams = List.of("red", "blue", "green", "yellow");
        var solos = java.util.stream.IntStream.range(0, 12).mapToObj(index -> group(1)).toList();
        for (int seed = 0; seed < 20; seed++) {
            var balanced = Matchmaker.select(solos, teams, 4, 12, 16, new java.util.Random(seed));
            assertNotNull(balanced);
            assertEquals(solos, balanced.groups());
            assertEquals(12, balanced.teams().size());
            for (String team : teams) {
                assertEquals(3, balanced.teams().values().stream().filter(team::equals).count());
            }
            assertEquals(balanced.teams(), Matchmaker.select(solos, teams, 4, 12, 16,
                    new java.util.Random(seed)).teams());
        }
        var parties = List.of(group(3), group(3), group(2), group(2), group(2));
        var match = Matchmaker.select(parties, List.of("red", "blue"), 6, 12, 12, new java.util.Random(3));
        assertNotNull(match);
        assertEquals(parties, match.groups());
        for (var party : parties) {
            assertEquals(1, party.players().stream().map(match.teams()::get).distinct().count());
        }
    }

    @Test
    void adversarialPartiesFillAllFourTeamsWithoutSplittingOrReorderingTheQueue() {
        var teams = List.of("red", "blue", "green", "yellow");
        var parties = List.of(group(3), group(3), group(2), group(2), group(2), group(2), group(1), group(1));
        var fifo = parties.stream().flatMap(party -> party.players().stream()).toList();
        for (int seed = 0; seed < 20; seed++) {
            var result = Matchmaker.select(parties, teams, 4, 16, 16, new java.util.Random(seed));
            assertNotNull(result);
            assertEquals(parties, result.groups());
            assertEquals(fifo, List.copyOf(result.teams().keySet()));
            for (String team : teams) {
                assertEquals(4, result.teams().values().stream().filter(team::equals).count());
            }
            for (var party : parties) {
                assertEquals(1, party.players().stream().map(result.teams()::get).distinct().count());
            }
            assertEquals(result.teams(), Matchmaker.select(parties, teams, 4, 16, 16,
                    new java.util.Random(seed)).teams());
        }
    }

    @Test
    void aFullPartyKeepsItsTeamAndOtherPlayersSpreadAcrossAllRemainingTeams() {
        var party = group(4);
        var groups = new java.util.ArrayList<Matchmaker.Group>();
        groups.add(party);
        java.util.stream.IntStream.range(0, 8).forEach(index -> groups.add(group(1)));
        var teams = List.of("a", "b", "c", "d");
        for (int seed = 0; seed < 20; seed++) {
            var result = Matchmaker.select(groups, teams, 4, 12, 16, new java.util.Random(seed));
            assertEquals(groups, result.groups());
            assertEquals(1, party.players().stream().map(result.teams()::get).distinct().count());
            var sizes = teams.stream().map(team -> result.teams().values().stream().filter(team::equals).count()).sorted().toList();
            assertEquals(List.of(2L, 3L, 3L, 4L), sizes);
        }
    }

    @Test
    void teamfreePartiesIgnoreTeamSizeButRemainAtomicWithinCapacity() {
        var party = group(3);
        var blocked = group(2);
        var solo = group(1);
        var result = Matchmaker.select(List.of(party, blocked, solo), List.of(), 0, 1, 4);
        assertEquals(List.of(party, solo), result.groups());
        assertEquals(4, result.teams().size());
        assertTrue(result.teams().values().stream().allMatch(String::isEmpty));
        assertNull(Matchmaker.select(List.of(party), List.of(), 0, 4, 4));
    }

    @Test
    void additionsRespectExistingTeamCountsAndTotalCapacity() {
        var existing = group(2);
        var party = group(2);
        var solo = group(1);
        var assignments = java.util.Map.of(existing.players().get(0), "red", existing.players().get(1), "blue");
        var result = Matchmaker.select(List.of(existing, party, solo), List.of("red", "blue"), 2, 1, 4, assignments);
        assertEquals(List.of(solo), result.groups());
        assertTrue(List.of("red", "blue").contains(result.teams().get(solo.leader())));
    }

    private Matchmaker.Group group(int size) {
        var players = java.util.stream.IntStream.range(0, size).mapToObj(index -> UUID.randomUUID()).toList();
        return new Matchmaker.Group(players.get(0), players);
    }

    @Test
    void rolesShuffleIndividualsDeferMissingFixedSlotsAndPreserveExistingAssignments() {
        var json = com.google.gson.JsonParser.parseString("""
                {"teams":["runner","hunter"],"teamSize":8,"teamPolicy":"roles","teamCounts":{"runner":2},
                 "remainderTeam":"hunter","minPlayers":2,"maxPlayers":8,"readyServers":0,"maxInstances":2,
                 "joinTimeout":"10s","lifecycle":{"end":{"returnPlayersTo":"lobby","deleteServer":true,
                 "whenEmpty":false,"emptyGrace":"1s"}}}
                """).getAsJsonObject();
        var spec = MinigameSpec.parse(json);
        var party = group(5);
        var result = Matchmaker.select(List.of(party), spec, 2, java.util.Map.of(), new java.util.Random(8));
        assertEquals(2, result.teams().values().stream().filter("runner"::equals).count());
        assertEquals(3, result.teams().values().stream().filter("hunter"::equals).count());
        assertEquals(2, party.players().stream().map(result.teams()::get).distinct().count());
        assertEquals(result.teams(), Matchmaker.select(List.of(party), spec, 2, java.util.Map.of(), new java.util.Random(8)).teams());
        assertNull(Matchmaker.select(List.of(group(1)), spec, 1, java.util.Map.of(), new java.util.Random(8)));
        var addition = group(2);
        var appended = Matchmaker.select(List.of(addition), spec, 1, result.teams(), new java.util.Random(10));
        assertTrue(appended.teams().values().stream().allMatch("hunter"::equals));
        var oneRunner = java.util.Map.of(UUID.randomUUID(), "runner");
        appended = Matchmaker.select(List.of(addition), spec, 1, oneRunner, new java.util.Random(10));
        assertEquals(1, appended.teams().values().stream().filter("runner"::equals).count());
        assertEquals(1, appended.teams().values().stream().filter("hunter"::equals).count());
    }

    @Test
    void partiesStayTogetherAndSoloPlayersFillTeams() {
        var party = group(3);
        var second = group(2);
        var solo = group(1);
        var result = Matchmaker.select(List.of(party, second, solo), List.of("red", "blue"), 3, 6, 6);
        assertNotNull(result);
        assertEquals(6, result.teams().size());
        assertEquals(1, party.players().stream().map(result.teams()::get).distinct().count());
        assertEquals(result.teams().get(second.leader()), result.teams().get(solo.leader()));
    }

    @Test
    void insufficientCompatiblePlayersDoNotReserveAndBlockedPartyDoesNotBlockOthers() {
        var first = group(3);
        var blocked = group(3);
        var third = group(2);
        assertNull(Matchmaker.select(List.of(first, blocked, third), List.of("red"), 4, 4, 4));
        var result = Matchmaker.select(List.of(first, blocked, third, group(1)), List.of("red", "blue"), 3, 6, 6);
        assertNotNull(result);
        assertEquals(6, result.teams().size());
        assertEquals(2, result.groups().size());
    }
}