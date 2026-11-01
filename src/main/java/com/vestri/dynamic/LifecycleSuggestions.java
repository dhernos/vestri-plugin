package com.vestri.dynamic;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class LifecycleSuggestions {
    public static List<String> complete(String[] arguments, List<String> templates, boolean includeSync) {
        if (arguments.length <= 1) {
            List<String> commands = new ArrayList<>(List.of("templates", "list", "spawn", "delete"));
            if (includeSync) {
                commands.add("sync");
            }
            String prefix = arguments.length == 0 ? "" : arguments[0];
            return commands.stream().filter(command -> startsWith(command, prefix)).toList();
        }
        String action = arguments[0];
        if (!List.of("spawn", "delete", "list").contains(action)) {
            return List.of();
        }
        String current = arguments[arguments.length - 1];
        String previous = arguments[arguments.length - 2];
        if (previous.equals("--type") && action.equals("spawn")) {
            return List.of("permanent", "temporary").stream().filter(type -> startsWith(type, current)).toList();
        }
        if (previous.equals("--instance") || previous.equals("--request-id") || previous.equals("--send-player")) {
            return List.of();
        }
        if (current.startsWith("--")) {
            List<String> options = action.equals("spawn") ? List.of("--request-id", "--send-player", "--type")
                    : action.equals("delete") ? List.of("--instance", "--confirm") : List.of();
            List<String> supplied = java.util.Arrays.asList(arguments).subList(0, arguments.length - 1);
            return options.stream().filter(option -> startsWith(option, current) && !supplied.contains(option)).toList();
        }
        if (java.util.Arrays.stream(arguments).skip(1).anyMatch(argument -> argument.startsWith("--"))) {
            return List.of();
        }
        String prefix = String.join(" ", java.util.Arrays.copyOfRange(arguments, 1, arguments.length));
        String completed = arguments.length > 2
                ? String.join(" ", java.util.Arrays.copyOfRange(arguments, 1, arguments.length - 1)) + " " : "";
        return templates.stream().filter(template -> startsWith(template, prefix))
                .map(template -> template.substring(completed.length())).sorted().toList();
    }

    private static boolean startsWith(String value, String prefix) {
        return value.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT));
    }
}