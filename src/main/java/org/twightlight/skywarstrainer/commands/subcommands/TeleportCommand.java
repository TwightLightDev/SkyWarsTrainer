package org.twightlight.skywarstrainer.commands.subcommands;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.twightlight.skywarstrainer.SkyWarsTrainerPlugin;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.commands.CommandHandler;

import javax.annotation.Nonnull;

/**
 * /swt teleport <name> — Teleport to a bot.
 */
public class TeleportCommand implements SubCommand {

    private final SkyWarsTrainerPlugin plugin;

    public TeleportCommand(@Nonnull SkyWarsTrainerPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(@Nonnull CommandSender sender, @Nonnull String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(CommandHandler.getPrefix() + ChatColor.RED + "This command can only be used by players.");
            return;
        }

        if (args.length < 1) {
            sender.sendMessage(CommandHandler.getPrefix() + ChatColor.RED + "Usage: " + getUsage());
            return;
        }

        TrainerBot bot = plugin.getBotManager().getBotByName(args[0]);
        if (bot == null) {
            sender.sendMessage(CommandHandler.getPrefix() + ChatColor.RED + "Bot not found: " + args[0]);
            return;
        }

        Location loc = bot.getLocation();
        if (loc == null) {
            sender.sendMessage(CommandHandler.getPrefix() + ChatColor.RED + "Bot location unavailable.");
            return;
        }

        ((Player) sender).teleport(loc);
        sender.sendMessage(CommandHandler.getPrefix() + ChatColor.GREEN + "Teleported to "
                + ChatColor.YELLOW + bot.getName());
    }

    @Override
    @Nonnull
    public String getPermission() {
        return "skywarstrainer.teleport";
    }

    @Override
    @Nonnull
    public String getUsage() {
        return "/swt teleport <botName>";
    }

    @Override
    @Nonnull
    public String getDescription() {
        return "Teleport to a bot.";
    }
}
