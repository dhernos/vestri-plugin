package com.vestri.dynamic;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class MinigameSpecTest {
    @Test
    void proxyPreservesPaperLifecycleCommandsAcrossSerializationAndVariantResolution() {
    JsonObject json = spec();
    JsonObject lifecycle = json.getAsJsonObject("lifecycle");
    lifecycle.add("teamCommands", JsonParser.parseString(
        "[{\"team\":\"red\",\"command\":\"assign {player} {team}\"}]"));
    lifecycle.add("startCommands", JsonParser.parseString("[\"launch {matchId}\",\"final\"]"));
    lifecycle.getAsJsonObject("end").addProperty("loggerName", "GamePlugin");
    json.add("variants", JsonParser.parseString("{\"duel\":{\"maxPlayers\":2}}"));
    JsonObject snapshot = MinigameSpec.parse(json).rawJson();
    JsonObject serialized = JsonParser.parseString(new com.google.gson.Gson().toJson(snapshot)).getAsJsonObject();
    assertEquals(json, serialized);
    assertEquals(lifecycle, MinigameSpec.parse(MinigameSpec.variant(serialized, "duel"))
        .rawJson().getAsJsonObject("lifecycle"));
    JsonObject nativeSpec = serialized.deepCopy();
    nativeSpec.addProperty("lobbyMode", "plugin");
    MinigameSpec nativeParsed = MinigameSpec.parse(nativeSpec);
    assertTrue(nativeParsed.nativeLobby());
    assertEquals(lifecycle, nativeParsed.rawJson().getAsJsonObject("lifecycle"));
    lifecycle.getAsJsonArray("startCommands").set(0, new com.google.gson.JsonPrimitive("changed"));
    assertEquals("launch {matchId}", snapshot.getAsJsonObject("lifecycle")
        .getAsJsonArray("startCommands").get(0).getAsString());
    }

    @Test
    void rejectsUnsafeVariantsRoleShapesAndInvalidControlTypes() {
        JsonObject base = spec();
        for (String value : new String[]{"\"false\"", "0", "null", "{}"}) {
            JsonObject invalid = base.deepCopy();
            invalid.add("enabled", JsonParser.parseString(value));
            assertThrows(IllegalArgumentException.class, () -> MinigameSpec.parse(invalid));
        }
        for (String value : new String[]{"{\"../bad\":{}}", "{\"duel\":{\"enabled\":false}}",
                "{\"duel\":{\"lifecycle\":{}}}", "{\"duel\":{\"maxInstances\":100}}"}) {
            JsonObject invalid = base.deepCopy();
            invalid.add("variants", JsonParser.parseString(value));
            assertThrows(IllegalArgumentException.class, () -> MinigameSpec.parse(invalid));
        }
        JsonObject variants = new JsonObject();
        for (int index = 0; index < 21; index++) {
            variants.add("v" + index, new JsonObject());
        }
        base.add("variants", variants);
        assertThrows(IllegalArgumentException.class, () -> MinigameSpec.parse(base));
        base.remove("variants");
        base.add("teams", JsonParser.parseString("[\"runner\",\"hunter\"]"));
        base.addProperty("teamSize", 6);
        base.add("teamCounts", JsonParser.parseString("{\"runner\":1}"));
        assertThrows(IllegalArgumentException.class, () -> MinigameSpec.parse(base));
        base.addProperty("teamPolicy", "roles");
        assertThrows(IllegalArgumentException.class, () -> MinigameSpec.parse(base));
        base.addProperty("remainderTeam", "unknown");
        assertThrows(IllegalArgumentException.class, () -> MinigameSpec.parse(base));
        base.addProperty("remainderTeam", "hunter");
        assertEquals(1, MinigameSpec.parse(base).teamCounts().get("runner"));
        base.add("teamCounts", JsonParser.parseString("{\"runner\":1,\"hunter\":1}"));
        assertEquals(2, MinigameSpec.parse(base).teamCounts().size());
        base.add("teamCounts", JsonParser.parseString("{\"bad\":1}"));
        assertThrows(IllegalArgumentException.class, () -> MinigameSpec.parse(base));
    }

    @Test
    void enabledRolesAndVariantsResolveWithoutMutatingBase() {
        JsonObject json = spec();
        assertTrue(MinigameSpec.parse(json).enabled());
        json.addProperty("enabled", false);
        json.addProperty("teamSize", 16);
        json.add("teams", JsonParser.parseString("[\"runner\",\"hunter\"]"));
        json.addProperty("teamPolicy", "roles");
        json.add("teamCounts", JsonParser.parseString("{\"runner\":2}"));
        json.addProperty("remainderTeam", "hunter");
        assertFalse(MinigameSpec.parse(json).enabled());
        json.add("variants", JsonParser.parseString("{\"4v4v4v4\":{\"maxPlayers\":16}}"));
        JsonObject resolved = MinigameSpec.variant(json, "4v4v4v4");
        assertFalse(resolved.has("variants"));
        assertEquals(16, MinigameSpec.parse(resolved).maxPlayers());
        assertEquals(6, json.get("maxPlayers").getAsInt());
        assertEquals(json, MinigameSpec.parse(json).rawJson());
        json.addProperty("minPlayers", 1);
        assertThrows(IllegalArgumentException.class, () -> MinigameSpec.parse(json));
        json.addProperty("minPlayers", 2);
        json.addProperty("lobbyMode", "plugin");
        assertThrows(IllegalArgumentException.class, () -> MinigameSpec.parse(json));
    }

    private JsonObject spec() {
        return JsonParser.parseString("""
                {"minPlayers":2,"maxPlayers":6,"readyServers":0,"maxInstances":2,"joinTimeout":"10s",
                 "lifecycle":{"end":{"returnPlayersTo":"lobby","deleteServer":true,
                 "whenEmpty":false,"emptyGrace":"1s"}}}
                """).getAsJsonObject();
    }

    @Test
    void permanentRequiresPluginLobbyAndNonDeletingLifecycle() {
        JsonObject json = spec();
        assertFalse(MinigameSpec.parse(json).permanent());
        json.addProperty("instanceMode", "permanent");
        assertThrows(IllegalArgumentException.class, () -> MinigameSpec.parse(json));
        json.addProperty("lobbyMode", "plugin");
        assertThrows(IllegalArgumentException.class, () -> MinigameSpec.parse(json));
        JsonObject end = json.getAsJsonObject("lifecycle").getAsJsonObject("end");
        end.addProperty("deleteServer", false);
        assertTrue(MinigameSpec.parse(json).permanent());
        end.addProperty("whenEmpty", true);
        assertThrows(IllegalArgumentException.class, () -> MinigameSpec.parse(json));
        end.addProperty("whenEmpty", false);
        json.addProperty("instanceMode", "unknown");
        assertThrows(IllegalArgumentException.class, () -> MinigameSpec.parse(json));
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