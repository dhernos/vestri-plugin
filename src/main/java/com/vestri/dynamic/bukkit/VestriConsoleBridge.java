package com.vestri.dynamic.bukkit;

import com.vestri.dynamic.ConsoleBridgeProtocol;
import com.vestri.dynamic.LifecycleSuggestions;
import com.vestri.dynamic.PluginMessages;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public final class VestriConsoleBridge extends JavaPlugin {
    private BukkitAudiences audiences;

    @Override
    public void onEnable() {
        audiences = BukkitAudiences.create(this);
        saveDefaultConfig();
        getServer().getMessenger().registerOutgoingPluginChannel(this, ConsoleBridgeProtocol.CHANNEL);
    }

    @Override
    public void onDisable() {
        if (audiences != null) {
            audiences.close();
            audiences = null;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] arguments) {
        if (!(sender instanceof ConsoleCommandSender)) {
            return List.of();
        }
        reloadConfig();
        return LifecycleSuggestions.complete(arguments, getConfig().getStringList("template-names"), false);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] arguments) {
        if (!(sender instanceof ConsoleCommandSender)) {
            audiences.sender(sender).sendMessage(PluginMessages.text("This command is console-only.", NamedTextColor.RED));
            return true;
        }
        var carrier = getServer().getOnlinePlayers().stream().findFirst();
        if (carrier.isEmpty()) {
            sender.sendMessage("Vestri bridge requires a player connected to this backend.");
            return true;
        }
        try {
            reloadConfig();
            String forwarded = ConsoleBridgeProtocol.prepareCommand("vestridynamic " + String.join(" ", arguments));
            byte[] message = ConsoleBridgeProtocol.encode(getConfig().getString("bridge-secret", ""),
                    forwarded, System.currentTimeMillis(), UUID.randomUUID());
            sender.sendMessage("Forwarding: " + forwarded);
            carrier.get().sendPluginMessage(this, ConsoleBridgeProtocol.CHANNEL, message);
            sender.sendMessage("Vestri command sent to Velocity; results appear in the proxy console.");
        } catch (IOException | IllegalArgumentException failure) {
            sender.sendMessage("Vestri command not sent: " + failure.getMessage());
        }
        return true;
    }
}