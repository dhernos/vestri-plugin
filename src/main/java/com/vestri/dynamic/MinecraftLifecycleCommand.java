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

final class MinecraftLifecycleCommand implements SimpleCommand {
    private static final String USAGE = "vestridynamic templates | list <template name> | "
            + "spawn <template name> [--request-id UUID] [--send-player name] [--type permanent|temporary] | delete <template name> [--instance UUID] --confirm | sync";
    private final Consumer<Runnable> schedule;
    private final Path configuration;
    private final SpawnPlayerTransfer transfer;
    private final PlayerServers playerServers;

    MinecraftLifecycleCommand(Consumer<Runnable> schedule, Path configuration) {
        this(schedule, configuration, null);
    }

    MinecraftLifecycleCommand(Consumer<Runnable> schedule, Path configuration, SpawnPlayerTransfer transfer) {
        this(schedule, configuration, transfer, null);
    }

    MinecraftLifecycleCommand(Consumer<Runnable> schedule, Path configuration, SpawnPlayerTransfer transfer, PlayerServers playerServers) {
        this.schedule = schedule;
        this.configuration = configuration;
        this.transfer = transfer;
        this.playerServers = playerServers;
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
            invocation.source().sendMessage(Component.text(USAGE));
            return;
        }
        Player selected = command.sendPlayer() == null || transfer == null ? null : transfer.findPlayer(command.sendPlayer());
        if (command.sendPlayer() != null && selected == null) {
            invocation.source().sendMessage(Component.text("Player is not connected to this proxy; no instance was spawned."));
            return;
        }
        if (command.action().equals("spawn")) {
            invocation.source().sendMessage(Component.text("Spawning " + command.template()
                    + "; requestId=" + command.requestId() + ". Retry with this ID if the request fails."));
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
                        if (command.serverType() != null) {
                            if (playerServers == null) {
                                throw new IOException("Player server management is unavailable; check the owned server registry.");
                            }
                            playerServers.spawnForConsole(selected, command);
                            yield "Owned " + command.serverType() + " server request processed for " + selected.getUsername() + ".";
                        }
                        if (selected == null) {
                            yield "Instance starting: " + client.spawn(command.template(), command.requestId());
                        }
                        var instance = client.spawnWithTarget(command.template(), command.requestId());
                        invocation.source().sendMessage(Component.text("Instance starting: " + instance.serverId()));
                        transfer.start(selected, instance, message -> invocation.source().sendMessage(Component.text(message)));
                        yield null;
                    }
                    case "delete" -> "Instance deleted: " + client.delete(command.template(), command.instanceId());
                    default -> throw new IllegalArgumentException("Unknown command");
                };
                if (result != null) {
                    invocation.source().sendMessage(Component.text(result));
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                invocation.source().sendMessage(Component.text("Vestri request interrupted; check instance state before retrying."));
            } catch (IOException failure) {
                invocation.source().sendMessage(Component.text("Vestri command failed: " + failure.getMessage()));
            } catch (RuntimeException failure) {
                invocation.source().sendMessage(Component.text("Vestri command failed: invalid configuration or API response."));
            }
        });
    }

    static LifecycleRequest parse(String[] arguments) {
        return LifecycleRequest.parse(arguments);
    }
}