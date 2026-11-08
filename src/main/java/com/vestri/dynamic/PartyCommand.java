package com.vestri.dynamic;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;

final class PartyCommand implements SimpleCommand {
    private final ProxyServer proxy;
    private final Parties parties;

    PartyCommand(ProxyServer proxy, Parties parties) {
        this.proxy = proxy;
        this.parties = parties;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(Component.text("This command is player-only."));
            return;
        }
        String[] arguments = invocation.arguments();
        UUID actor = player.getUniqueId();
        try {
            String action = arguments.length == 0 ? "list" : arguments[0];
            if (List.of("invite", "accept", "kick", "leader").contains(action)) {
                if (arguments.length != 2) {
                    throw new IllegalArgumentException("Usage: /party " + action + " <player>");
                }
                Player selected = proxy.getPlayer(arguments[1]).orElseThrow(
                        () -> new IllegalArgumentException("That player is not online."));
                switch (action) {
                    case "invite" -> {
                        parties.invite(actor, selected.getUniqueId(), System.currentTimeMillis());
                        selected.sendMessage(Component.text(player.getUsername() + " invited you. /party accept " + player.getUsername()));
                    }
                    case "accept" -> parties.accept(actor, selected.getUniqueId(), System.currentTimeMillis());
                    case "kick" -> parties.kick(actor, selected.getUniqueId());
                    case "leader" -> parties.transfer(actor, selected.getUniqueId());
                    default -> throw new IllegalArgumentException("Unknown party action.");
                }
                player.sendMessage(Component.text("Party updated."));
                if ("accept".equals(action)) {
                    selected.getCurrentServer().ifPresent(connection -> follow(player, connection.getServer()));
                }
            } else if (arguments.length <= 1 && "leave".equals(action)) {
                parties.leave(actor);
                player.sendMessage(Component.text("You left the party."));
            } else if (arguments.length <= 1 && "disband".equals(action)) {
                parties.disband(actor);
                player.sendMessage(Component.text("Party disbanded."));
            } else if (arguments.length <= 1 && "list".equals(action)) {
                player.sendMessage(Component.text("Party leader: " + name(parties.leader(actor)) + "; members: "
                        + String.join(", ", parties.members(actor).stream().map(this::name).toList())));
            } else {
                throw new IllegalArgumentException("Usage: /party <invite|accept|leave|kick|list|disband|leader>");
            }
        } catch (IllegalArgumentException failure) {
            player.sendMessage(Component.text(failure.getMessage()));
        }
    }

    void connected(Player player, RegisteredServer destination) {
        if (!parties.leader(player.getUniqueId()).equals(player.getUniqueId())) {
            return;
        }
        parties.members(player.getUniqueId()).stream().filter(member -> !member.equals(player.getUniqueId()))
                .forEach(member -> proxy.getPlayer(member).ifPresent(follower -> follow(follower, destination)));
    }

    private void follow(Player follower, RegisteredServer destination) {
        if (follower.getCurrentServer().map(connection -> connection.getServer().equals(destination)).orElse(false)) {
            return;
        }
        follower.createConnectionRequest(destination).connect().whenComplete((result, failure) -> {
            if (failure != null || !result.isSuccessful()) {
                follower.sendMessage(Component.text("Could not follow the party leader to " + destination.getServerInfo().getName() + "."));
            }
        });
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (invocation.arguments().length <= 1) {
            String prefix = invocation.arguments().length == 0 ? "" : invocation.arguments()[0];
            return List.of("invite", "accept", "leave", "kick", "list", "disband", "leader").stream()
                    .filter(action -> action.startsWith(prefix)).toList();
        }
        return proxy.getAllPlayers().stream().map(Player::getUsername)
                .filter(name -> name.toLowerCase(java.util.Locale.ROOT).startsWith(invocation.arguments()[1].toLowerCase(java.util.Locale.ROOT))).toList();
    }

    private String name(UUID player) {
        return proxy.getPlayer(player).map(Player::getUsername).orElse("offline member");
    }
}