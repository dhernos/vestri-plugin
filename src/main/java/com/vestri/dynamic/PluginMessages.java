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
        return Component.text("Vestri", NamedTextColor.AQUA)
            .append(Component.text(" > ", NamedTextColor.AQUA))
            .append(blue(content.replaceText(builder -> builder.match(IDENTIFIER).replacement("your server"))));
    }

    private static Component blue(Component content) {
        Component result = content.color(NamedTextColor.AQUA)
                .children(content.children().stream().map(PluginMessages::blue).toList());
        if (content.hoverEvent() != null && content.hoverEvent().value() instanceof Component hoverText) {
            result = result.hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(blue(hoverText)));
        }
        return result;
    }

    public static Component text(String text, NamedTextColor color) {
        return notice(Component.text(safeText(text), color));
    }

    public static String safeText(String text) {
        return IDENTIFIER.matcher(text).replaceAll("your server");
    }
}