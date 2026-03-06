package org.twightlight.skywarstrainer.commands.subcommands;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.twightlight.skywarstrainer.SkyWarsTrainerPlugin;
import org.twightlight.skywarstrainer.commands.CommandHandler;

import javax.annotation.Nonnull;
import java.util.Map;

/**
 * /swt help — Shows all available commands.
 */
public class HelpCommand implements SubCommand {

    private final SkyWarsTrainerPlugin plugin;
    private final Map<String, SubCommand> subCommands;

    public HelpCommand(@Nonnull SkyWarsTrainerPlugin plugin, @Nonnull Map<String, SubCommand> subCommands) {
        this.plugin = plugin;
        this.subCommands = subCommands;
    }

    @Override
    public void execute(@Nonnull CommandSender sender, @Nonnull String[] args) {
        sender.sendMessage(ChatColor.GOLD + "━━━ SkyWarsTrainer Commands ━━━");
        for (Map.Entry<String, SubCommand> entry : subCommands.entrySet()) {
            String name = entry.getKey();
            SubCommand sub = entry.getValue();
            // Skip aliases
            if (name.equals("tp")) continue;
            sender.sendMessage(ChatColor.YELLOW + sub.getUsage()
                    + ChatColor.GRAY + " — " + sub.getDescription());
        }
        sender.sendMessage(ChatColor.GOLD + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    @Override
    @Nonnull
    public String getPermission() {
        return "skywarstrainer.spawn";
    }

    @Override
    @Nonnull
    public String getUsage() {
        return "/swt help";
    }

    @Override
    @Nonnull
    public String getDescription() {
        return "Show all available commands.";
    }
}
