package org.twightlight.skywarstrainer.commands.subcommands;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.twightlight.skywarstrainer.SkyWarsTrainerPlugin;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.commands.CommandHandler;

import javax.annotation.Nonnull;

/**
 * /swt debug <name> — Toggles debug mode for a bot.
 */
public class DebugCommand implements SubCommand {

    private final SkyWarsTrainerPlugin plugin;

    public DebugCommand(@Nonnull SkyWarsTrainerPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(@Nonnull CommandSender sender, @Nonnull String[] args) {
        if (args.length < 1) {
            sender.sendMessage(CommandHandler.getPrefix() + ChatColor.RED + "Usage: " + getUsage());
            return;
        }

        TrainerBot bot = plugin.getBotManager().getBotByName(args[0]);
        if (bot == null) {
            sender.sendMessage(CommandHandler.getPrefix() + ChatColor.RED + "Bot not found: " + args[0]);
            return;
        }

        boolean newState = !bot.getProfile().isDebugMode();
        bot.getProfile().setDebugMode(newState);

        sender.sendMessage(CommandHandler.getPrefix() + "Debug mode for "
                + ChatColor.YELLOW + bot.getName()
                + ChatColor.RESET + ": " + (newState ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF"));
    }

    @Override
    @Nonnull
    public String getPermission() {
        return "skywarstrainer.debug";
    }

    @Override
    @Nonnull
    public String getUsage() {
        return "/swt debug <botName>";
    }

    @Override
    @Nonnull
    public String getDescription() {
        return "Toggle debug mode for a bot.";
    }
}
