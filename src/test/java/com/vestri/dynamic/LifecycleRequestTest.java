package com.vestri.dynamic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class LifecycleRequestTest {
    @Test
    void parsesMultiwordTemplateVariantPlayerAndRetryId() {
        UUID request = UUID.randomUUID();
        var parsed = LifecycleRequest.parse(new String[]{"spawn", "Bed", "Wars", "--variant", "4v4v4v4",
                "--send-player", "Luis", "--request-id", request.toString()});
        assertEquals("Bed Wars", parsed.template());
        assertEquals("4v4v4v4", parsed.variant());
        assertEquals("Luis", parsed.sendPlayer());
        assertEquals(request, parsed.requestId());
    }

    @Test
    void preservesEmptyVariantForLegacyCallsAndCommands() {
        assertEquals("", new LifecycleRequest("spawn", "Bed Wars", UUID.randomUUID(), null, null, null).variant());
        assertEquals("", LifecycleRequest.parse(new String[]{"spawn", "Bed Wars"}).variant());
        assertEquals("", LifecycleRequest.parse(new String[]{"templates"}).variant());
    }

    @Test
    void acceptsSafeUnknownVariantWithoutLocalConfigurationOrAliases() {
        for (String variant : new String[]{"Unknown-Mode_2", "a".repeat(64)}) {
            assertEquals(variant, LifecycleRequest.parse(new String[]{"spawn", "Bed Wars", "--variant", variant}).variant());
        }
    }

    @Test
    void rejectsMissingDuplicateUnsafeReadonlyAndOwnedVariants() {
        for (String variant : new String[]{"", "a".repeat(65), "mode;stop", "mode name", "../mode", "mode\nstop", "{mode}", "--type"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> LifecycleRequest.parse(new String[]{"spawn", "Bed Wars", "--variant", variant}));
        }
        for (String[] arguments : new String[][]{
                {"spawn", "Bed Wars", "--variant"},
                {"spawn", "Bed Wars", "--variant", "solo", "--variant", "duo"},
                {"list", "Bed Wars", "--variant", "solo"},
                {"templates", "--variant", "solo"},
                {"delete", "Bed Wars", "--confirm", "--variant", "solo"},
                {"spawn", "Bed Wars", "--variant", "solo", "--type", "temporary", "--send-player", "Luis"},
                {"spawn", "Bed Wars", "--type", "permanent", "--send-player", "Luis", "--variant", "solo"}}) {
            assertThrows(IllegalArgumentException.class, () -> LifecycleRequest.parse(arguments));
        }
    }
}