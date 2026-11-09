package com.vestri.dynamic;

import com.google.gson.JsonParser;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.LoggerFactory;

final class MinecraftLifecycleCommand implements SimpleCommand {
    private static final String USAGE = "vestridynamic templates | list <template name> | "
            + "spawn <template name> [--request-id UUID] [--send-player name] [--variant name | --type permanent|temporary] | delete <template name> [--instance UUID] --confirm | sync";
    private final Consumer<Runnable> schedule;
    private final Path configuration;
    private final SpawnPlayerTransfer transfer;
    private final PlayerServers playerServers;
    private final Minigames minigames;

    MinecraftLifecycleCommand(Consumer<Runnable> schedule, Path configuration) {
        this(schedule, configuration, null);
    }

    MinecraftLifecycleCommand(Consumer<Runnable> schedule, Path configuration, SpawnPlayerTransfer transfer) {
        this(schedule, configuration, transfer, null);
    }

    MinecraftLifecycleCommand(Consumer<Runnable> schedule, Path configuration, SpawnPlayerTransfer transfer, PlayerServers playerServers) {
        this(schedule, configuration, transfer, playerServers, null);
    }

    MinecraftLifecycleCommand(Consumer<Runnable> schedule, Path configuration, SpawnPlayerTransfer transfer,
            PlayerServers playerServers, Minigames minigames) {
        this.schedule = schedule;
        this.configuration = configuration;
        this.transfer = transfer;
        this.playerServers = playerServers;
        this.minigames = minigames;
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source() instanceof ConsoleCommandSource;
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (!hasPermission(invocation)) {
            return List.of();
        }
        List<String> templates = List.of();
        try {
            if (Files.size(configuration) <= 64 * 1024) {
                var config = JsonParser.parseString(Files.readString(configuration, StandardCharsets.UTF_8)).getAsJsonObject();
                var configured = config.getAsJsonObject("templates");
                if (configured != null) {
                    templates = List.copyOf(configured.keySet());
                }
            }
        } catch (IOException | RuntimeException failure) {
            templates = List.of();
        }
        return LifecycleSuggestions.complete(invocation.arguments(), templates, true);
    }

    @Override
    public void execute(Invocation invocation) {
        if (!hasPermission(invocation)) {
            return;
        }
        LifecycleRequest command;
        try {
            command = parse(invocation.arguments());
        } catch (IllegalArgumentException failure) {
            invocation.source().sendMessage(Component.text(USAGE, NamedTextColor.AQUA));
            return;
        }
        Player selected = command.sendPlayer() == null || transfer == null ? null : transfer.findPlayer(command.sendPlayer());
        if (command.sendPlayer() != null && selected == null) {
            invocation.source().sendMessage(Component.text("Player is not connected to this proxy; no instance was spawned.", NamedTextColor.AQUA));
            return;
        }
        if (command.action().equals("spawn")) {
            invocation.source().sendMessage(Component.text("Preparing " + command.template() + "; recovering transient failures automatically.", NamedTextColor.AQUA));
            LoggerFactory.getLogger(MinecraftLifecycleCommand.class).info("Vestri spawn diagnostic: template={}, requestId={}",
                command.template(), command.requestId());
        }
        schedule.accept(() -> {
            try {
                MinecraftInstanceClient client = MinecraftInstanceClient.load(configuration);
                String result = switch (command.action()) {
                    case "templates" -> "Configured templates: " + String.join(", ", client.templates());
                    case "list" -> {
                        List<String> ids = client.instances(command.template());
                        yield "Instances for " + command.template() + ": " + (ids.isEmpty() ? "none" : String.join(", ", ids));
                    }
                    case "spawn" -> {
                        if (selected != null && minigames == null && isMinigameTemplate(command.template())) {
                            throw new IOException("Minigame management is unavailable; no instance was spawned.");
                        }
                        if (command.serverType() != null) {
                            if (playerServers == null) {
                                throw new IOException("Player server management is unavailable; check the owned server registry.");
                            }
                            playerServers.spawnForConsole(selected, command);
                            yield "Owned " + command.serverType() + " server request processed for " + selected.getUsername() + ".";
                        }
                        if (selected == null) {
                            var instance = client.spawnWithTarget(command.template(), command.requestId(), command.variant());
                            yield "Server starting: " + (instance.name() == null ? command.template() : instance.name());
                        }
                        var instance = client.spawnWithTarget(command.template(), command.requestId(), command.variant());
                        invocation.source().sendMessage(Component.text("Server starting: " + (instance.name() == null ? command.template() : instance.name()), NamedTextColor.AQUA));
                        Consumer<String> output = message -> invocation.source().sendMessage(Component.text(message, NamedTextColor.AQUA));
                        if (minigames == null || !minigames.joinInstance(selected, instance, output)) {
                            transfer.start(selected, instance, output);
                        }
                        yield null;
                    }
                    case "delete" -> "Instance deleted: " + client.delete(command.template(), command.instanceId());
                    default -> throw new IllegalArgumentException("Unknown command");
                };
                if (result != null) {
                    invocation.source().sendMessage(Component.text(result, NamedTextColor.AQUA));
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                invocation.source().sendMessage(Component.text("Vestri request interrupted; check instance state before retrying.", NamedTextColor.AQUA));
            } catch (IOException failure) {
                invocation.source().sendMessage(Component.text("Vestri command failed: " + failure.getMessage(), NamedTextColor.AQUA));
                if (selected != null && selected.isActive()) {
                        selected.sendMessage(PluginMessages.text("We couldn't finish preparing your server. Use /vestriservers list to retry or delete it by name, or contact a staff member.", NamedTextColor.RED));
                }
            } catch (RuntimeException failure) {
                invocation.source().sendMessage(Component.text("Vestri command failed: invalid configuration or API response.", NamedTextColor.AQUA));
            }
        });
    }

    private boolean isMinigameTemplate(String template) throws IOException {
        Path manifest = configuration.resolveSibling("vestri-dynamic-servers.json");
        if (!Files.exists(manifest)) {
            return false;
        }
        if (Files.size(manifest) > 1024 * 1024) {
            throw new IOException("Dynamic server manifest exceeds 1 MiB");
        }
        var games = JsonParser.parseString(Files.readString(manifest, StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonObject("minigames");
        return games != null && games.keySet().stream().anyMatch(name -> name.trim().equalsIgnoreCase(template.trim()));
    }

    static LifecycleRequest parse(String[] arguments) {
        return LifecycleRequest.parse(arguments);
    }
}