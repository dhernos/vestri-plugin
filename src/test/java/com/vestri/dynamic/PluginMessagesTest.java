package com.vestri.dynamic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

class PluginMessagesTest {
    @Test
    void preservesLiteralLanguageAndUsesBlueForAllMessageParts() {
        String text = "Dein Server ist bereit. <red>Survival-1</red>";
        Component message = PluginMessages.text(text, NamedTextColor.GREEN);
        assertEquals("Vestri > " + text, PlainTextComponentSerializer.plainText().serialize(message));
        assertEquals(NamedTextColor.AQUA, message.color());
        assertEquals(NamedTextColor.AQUA, message.children().get(0).color());
        assertEquals(NamedTextColor.AQUA, message.children().get(1).color());
    }

    @Test
    void overridesNestedStatusColors() {
        Component content = Component.text("Status", NamedTextColor.RED)
                .append(Component.text(" ready", NamedTextColor.GREEN)
                        .append(Component.text("!", NamedTextColor.WHITE)));
        Component formatted = PluginMessages.notice(content).children().get(1);
        assertEquals(NamedTextColor.AQUA, formatted.color());
        assertEquals(NamedTextColor.AQUA, formatted.children().get(0).color());
        assertEquals(NamedTextColor.AQUA, formatted.children().get(0).children().get(0).color());
    }

    @Test
    void hidesInternalIdentifiersButPreservesFriendlyNames() {
        assertEquals("your server your server your server Survival-1",
                PluginMessages.safeText("vestri-mcgame-" + "a".repeat(32) + " mcgame-" + "b".repeat(32)
                    + " 12345678-1234-1234-1234-123456789abc Survival-1"));
    }

    @Test
    void filtersNestedTextWithoutReplacingTranslationKeys() {
        Component content = Component.translatable("server.ready")
                .append(Component.text("mcgame-" + "a".repeat(32)));
        Component formatted = PluginMessages.notice(content).children().get(1);
        assertEquals("server.ready", ((net.kyori.adventure.text.TranslatableComponent) formatted).key());
        assertEquals("your server", PlainTextComponentSerializer.plainText().serialize(formatted.children().get(0)));
    }

    @Test
    void preservesInteractiveComponents() {
        Component join = Component.text("Join", NamedTextColor.GREEN)
                .clickEvent(ClickEvent.runCommand("/vestriservers join Survival-1"))
                .hoverEvent(HoverEvent.showText(Component.text("Join Survival-1")));
        Component formatted = PluginMessages.notice(join).children().get(1);
        assertEquals(join.clickEvent(), formatted.clickEvent());
        assertEquals(NamedTextColor.AQUA, formatted.color());
        assertEquals(HoverEvent.showText(Component.text("Join Survival-1", NamedTextColor.AQUA)), formatted.hoverEvent());
    }
}