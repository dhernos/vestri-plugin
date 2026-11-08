package com.vestri.dynamic;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class MinigameSpecTest {
    private JsonObject spec() {
        return JsonParser.parseString("""
                {"minPlayers":2,"maxPlayers":6,"readyServers":0,"maxInstances":2,"joinTimeout":"10s",
                 "lifecycle":{"end":{"returnPlayersTo":"lobby","deleteServer":true,
                 "whenEmpty":false,"emptyGrace":"1s"}}}
                """).getAsJsonObject();
    }

    @Test
    void absentNullAndEmptyTeamsAreTeamfreeAndDefaultToVestri() {
        JsonObject json = spec();
        for (String teams : new String[] {null, "null", "[]"}) {
            if (teams != null) {
                json.add("teams", JsonParser.parseString(teams));
            }
            MinigameSpec parsed = MinigameSpec.parse(json);
            assertTrue(parsed.teamfree());
            assertEquals(0, parsed.teamSize());
            assertEquals("vestri", parsed.lobbyMode());
            assertFalse(parsed.nativeLobby());
        }
        json.addProperty("teamSize", 0);
        json.addProperty("lobbyMode", "plugin");
        assertTrue(MinigameSpec.parse(json).nativeLobby());
        json.addProperty("teamSize", 1);
        assertTrue(MinigameSpec.parse(json).teamfree());
    }

    @Test
    void nonemptyTeamsRetainSizeIdentifierUniquenessAndCapacityConstraints() {
        JsonObject json = spec();
        json.add("teams", JsonParser.parseString("[\"red\",\"blue\"]"));
        assertThrows(IllegalArgumentException.class, () -> MinigameSpec.parse(json));
        json.addProperty("teamSize", 0);
        assertThrows(IllegalArgumentException.class, () -> MinigameSpec.parse(json));
        json.addProperty("teamSize", 2);
        assertThrows(IllegalArgumentException.class, () -> MinigameSpec.parse(json));
        json.addProperty("teamSize", 3);
        assertFalse(MinigameSpec.parse(json).teamfree());
        for (String teams : new String[] {"[\"red\",\"red\"]", "[\"bad team\",\"blue\"]"}) {
            json.add("teams", JsonParser.parseString(teams));
            assertThrows(IllegalArgumentException.class, () -> MinigameSpec.parse(json));
        }
    }

    @Test
    void rejectsUnknownLobbyModesAndInvalidTeamfreeSizes() {
        JsonObject json = spec();
        for (String mode : new String[] {"native", "Plugin", "", "null"}) {
            json.addProperty("lobbyMode", mode);
            assertThrows(IllegalArgumentException.class, () -> MinigameSpec.parse(json));
        }
        json.remove("lobbyMode");
        for (int size : new int[] {-1, 101}) {
            json.addProperty("teamSize", size);
            assertThrows(IllegalArgumentException.class, () -> MinigameSpec.parse(json));
        }
    }
}