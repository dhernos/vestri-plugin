package com.vestri.dynamic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MatchmakerTest {
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
        assertEquals("red", result.teams().get(solo.leader()));
    }

    private Matchmaker.Group group(int size) {
        var players = java.util.stream.IntStream.range(0, size).mapToObj(index -> UUID.randomUUID()).toList();
        return new Matchmaker.Group(players.get(0), players);
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