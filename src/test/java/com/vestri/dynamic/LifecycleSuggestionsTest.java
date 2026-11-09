package com.vestri.dynamic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class LifecycleSuggestionsTest {
    private final List<String> templates = List.of("BentoBox", "Bed Wars", "Survival");

    @Test
    void completesSubcommandsForEachPlatform() {
        assertEquals(List.of("spawn", "sync"), LifecycleSuggestions.complete(new String[]{"s"}, templates, true));
        assertEquals(List.of("spawn"), LifecycleSuggestions.complete(new String[]{"s"}, templates, false));
        assertEquals(List.of("templates", "list", "spawn", "delete", "sync"),
                LifecycleSuggestions.complete(new String[]{}, templates, true));
    }

    @Test
    void completesTemplatesCaseInsensitivelyAndPreservesCase() {
        assertEquals(List.of("BentoBox"), LifecycleSuggestions.complete(new String[]{"spawn", "ben"}, templates, true));
        assertEquals(List.of("Wars"), LifecycleSuggestions.complete(new String[]{"spawn", "Bed", "w"}, templates, true));
        assertEquals(List.of("Bed Wars", "BentoBox", "Survival"),
                LifecycleSuggestions.complete(new String[]{"delete", ""}, templates, true));
        assertEquals(List.of(), LifecycleSuggestions.complete(new String[]{"sync", ""}, templates, true));
    }

    @Test
    void completesRelevantOptionsWithoutSuggestingIdsOrDuplicateFlags() {
        assertEquals(List.of("--confirm"), LifecycleSuggestions.complete(new String[]{"delete", "BentoBox", "--c"}, templates, true));
        assertEquals(List.of("--request-id", "--send-player", "--type", "--variant"), LifecycleSuggestions.complete(new String[]{"spawn", "BentoBox", "--"}, templates, true));
        assertEquals(List.of(), LifecycleSuggestions.complete(new String[]{"spawn", "BentoBox", "--send-player", ""}, templates, true));
        assertEquals(List.of("--request-id", "--type", "--variant"), LifecycleSuggestions.complete(new String[]{"spawn", "BentoBox", "--send-player", "Luis", "--"}, templates, false));
        assertEquals(List.of("--send-player"), LifecycleSuggestions.complete(new String[]{"spawn", "BentoBox", "--s"}, templates, false));
        assertEquals(List.of(), LifecycleSuggestions.complete(new String[]{"delete", "BentoBox", "--instance", ""}, templates, true));
        assertEquals(List.of("--instance"), LifecycleSuggestions.complete(new String[]{"delete", "BentoBox", "--confirm", "--"}, templates, true));
    }

    @Test
    void suggestsVariantOnlyForUnownedSpawnWithoutInventingValues() {
        assertEquals(List.of("--variant"), LifecycleSuggestions.complete(new String[]{"spawn", "Bed", "Wars", "--v"}, templates, true));
        assertEquals(List.of(), LifecycleSuggestions.complete(new String[]{"spawn", "Bed Wars", "--variant", ""}, templates, true));
        assertEquals(List.of(), LifecycleSuggestions.complete(new String[]{"spawn", "Bed Wars", "--variant", "4v"}, templates, true));
        assertEquals(List.of("--request-id", "--send-player"), LifecycleSuggestions.complete(
                new String[]{"spawn", "Bed Wars", "--variant", "solo", "--"}, templates, true));
        for (String type : List.of("permanent", "temporary")) {
            assertEquals(List.of("--request-id", "--send-player"), LifecycleSuggestions.complete(
                    new String[]{"spawn", "Bed Wars", "--type", type, "--"}, templates, true));
        }
        for (String action : List.of("list", "delete", "templates")) {
            assertEquals(List.of(), LifecycleSuggestions.complete(new String[]{action, "Bed Wars", "--v"}, templates, true));
        }
    }
}