package org.twightlight.skywarstrainer.commands.subcommands;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.twightlight.skywarstrainer.SkyWarsTrainerPlugin;
import org.twightlight.skywarstrainer.commands.CommandHandler;

import javax.annotation.Nonnull;

/**
 * /swt remove <name|all> — Removes a specific bot or all bots.
 */
public class RemoveCommand implements SubCommand {

    private final SkyWarsTrainerPlugin plugin;

    public RemoveCommand(@Nonnull SkyWarsTrainerPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(@Nonnull CommandSender sender, @Nonnull String[] args) {
        if (args.length < 1) {
            sender.sendMessage(CommandHandler.getPrefix() + ChatColor.RED + "Usage: " + getUsage());
            return;
        }

        String target = args[0];

        if (target.equalsIgnoreCase("all")) {
            int count = plugin.getBotManager().removeAllBots();
            sender.sendMessage(CommandHandler.getPrefix() + ChatColor.GREEN + "Removed "
                    + ChatColor.YELLOW + count + ChatColor.GREEN + " bot(s).");
        } else {
            boolean removed = plugin.getBotManager().removeBot(target);
            if (removed) {
                sender.sendMessage(CommandHandler.getPrefix() + ChatColor.GREEN + "Removed bot: "
                        + ChatColor.YELLOW + target);
            } else {
                sender.sendMessage(CommandHandler.getPrefix() + ChatColor.RED + "Bot not found: " + target);
            }
        }
    }

    @Override
    @Nonnull
    public String getPermission() {
        return "skywarstrainer.remove";
    }

    @Override
    @Nonnull
    public String getUsage() {
        return "/swt remove <name|all>";
    }

    @Override
    @Nonnull
    public String getDescription() {
        return "Remove a specific bot or all bots.";
    }
}
