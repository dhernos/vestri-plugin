package com.vestri.dynamic;

import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public final class PluginMessages {
    private static final Pattern IDENTIFIER = Pattern.compile(
            "(?i)(?:vestri-)?mcgame-[a-f0-9]{32}|[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");

    private PluginMessages() {
    }

    public static Component notice(Component content) {
        return Component.text("Vestri", NamedTextColor.DARK_AQUA)
            .append(Component.text(" > ", NamedTextColor.DARK_GRAY))
            .append(content.replaceText(builder -> builder.match(IDENTIFIER).replacement("your server")));
    }

    public static Component text(String text, NamedTextColor color) {
        return notice(Component.text(safeText(text), color));
    }

    public static String safeText(String text) {
        return IDENTIFIER.matcher(text).replaceAll("your server");
    }
}