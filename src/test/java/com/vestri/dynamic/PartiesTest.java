package com.vestri.dynamic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PartiesTest {
    @Test
    void acceptanceAndLeadershipTransferKeepGroupTogether() {
        Parties parties = new Parties();
        UUID leader = UUID.randomUUID();
        UUID guest = UUID.randomUUID();
        parties.invite(leader, guest, 0);
        parties.accept(guest, leader, 1);
        assertEquals(leader, parties.leader(guest));
        assertEquals(2, parties.members(guest).size());
        parties.leave(leader);
        assertEquals(guest, parties.leader(guest));
        assertEquals(java.util.List.of(guest), parties.members(guest));
    }

    @Test
    void expiredInvitesAndNonLeaderInvitationsAreRejected() {
        Parties parties = new Parties();
        UUID leader = UUID.randomUUID();
        UUID guest = UUID.randomUUID();
        parties.invite(leader, guest, 0);
        assertThrows(IllegalArgumentException.class, () -> parties.accept(guest, leader, 120000));
        parties.accept(guest, leader, 1);
        assertThrows(IllegalArgumentException.class, () -> parties.invite(guest, UUID.randomUUID(), 2));
        parties.disband(leader);
        assertEquals(guest, parties.leader(guest));
        assertEquals(1, parties.members(leader).size());
    }
}