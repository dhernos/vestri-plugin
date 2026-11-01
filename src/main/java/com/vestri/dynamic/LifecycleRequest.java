package com.vestri.dynamic;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

record LifecycleRequest(String action, String template, UUID requestId, UUID instanceId, String sendPlayer, String serverType) {
    static LifecycleRequest parse(String[] arguments) {
        if (arguments.length == 1 && arguments[0].equals("templates")) {
            return new LifecycleRequest("templates", "", null, null, null, null);
        }
        if (arguments.length < 2 || !List.of("spawn", "delete", "list").contains(arguments[0])) {
            throw new IllegalArgumentException("Invalid command");
        }
        String action = arguments[0];
        List<String> name = new ArrayList<>();
        UUID requestId = null;
        UUID instanceId = null;
        String sendPlayer = null;
        String serverType = null;
        boolean confirmed = false;
        for (int index = 1; index < arguments.length; index++) {
            String argument = arguments[index];
            if (argument.equals("--confirm") && action.equals("delete") && !confirmed) {
                confirmed = true;
            } else if (argument.equals("--request-id") && action.equals("spawn") && requestId == null && index + 1 < arguments.length) {
                requestId = parseUUID(arguments[++index]);
            } else if (argument.equals("--instance") && action.equals("delete") && instanceId == null && index + 1 < arguments.length) {
                instanceId = parseUUID(arguments[++index]);
            } else if (argument.equals("--send-player") && action.equals("spawn") && sendPlayer == null && index + 1 < arguments.length) {
                sendPlayer = arguments[++index];
                if (!sendPlayer.matches("[A-Za-z0-9_]{1,16}")) {
                    throw new IllegalArgumentException("Invalid player name");
                }
            } else if (argument.equals("--type") && action.equals("spawn") && serverType == null && index + 1 < arguments.length) {
                serverType = arguments[++index].toLowerCase(java.util.Locale.ROOT);
                if (!List.of("permanent", "temporary").contains(serverType)) {
                    throw new IllegalArgumentException("Invalid server type");
                }
            } else if (argument.startsWith("--")) {
                throw new IllegalArgumentException("Invalid option");
            } else {
                name.add(argument);
            }
        }
        String template = String.join(" ", name).trim();
        if (template.length() >= 2 && template.startsWith("\"") && template.endsWith("\"")) {
            template = template.substring(1, template.length() - 1).trim();
        }
        if (template.isEmpty() || (action.equals("delete") && !confirmed)) {
            throw new IllegalArgumentException("Template and deletion confirmation are required");
        }
        if (serverType != null && sendPlayer == null) {
            throw new IllegalArgumentException("Owned server spawning requires --send-player");
        }
        if (action.equals("spawn") && requestId == null) {
            requestId = UUID.randomUUID();
        }
        return new LifecycleRequest(action, template, requestId, instanceId, sendPlayer, serverType);
    }

    private static UUID parseUUID(String value) {
        UUID parsed = UUID.fromString(value);
        if (!parsed.toString().equalsIgnoreCase(value)) {
            throw new IllegalArgumentException("Invalid UUID");
        }
        return parsed;
    }
}