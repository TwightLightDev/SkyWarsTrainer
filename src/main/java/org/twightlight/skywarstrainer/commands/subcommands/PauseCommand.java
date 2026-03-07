package org.twightlight.skywarstrainer.commands.subcommands;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.twightlight.skywarstrainer.SkyWarsTrainer;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.commands.CommandHandler;

import javax.annotation.Nonnull;

/**
 * /swt pause <name|all> — Pause/unpause bot AI.
 */
public class PauseCommand implements SubCommand {

    private final SkyWarsTrainer plugin;

    public PauseCommand(@Nonnull SkyWarsTrainer plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(@Nonnull CommandSender sender, @Nonnull String[] args) {
        if (args.length < 1) {
            sender.sendMessage(CommandHandler.getPrefix() + ChatColor.RED + "Usage: " + getUsage());
            return;
        }

        if (args[0].equalsIgnoreCase("all")) {
            boolean anyPaused = plugin.getBotManager().getAllBots().stream()
                    .anyMatch(b -> b.getProfile().isPaused());
            boolean newState = !anyPaused;
            for (TrainerBot bot : plugin.getBotManager().getAllBots()) {
                bot.getProfile().setPaused(newState);
            }
            sender.sendMessage(CommandHandler.getPrefix() + ChatColor.GREEN
                    + (newState ? "Paused" : "Resumed") + " all bots.");
        } else {
            TrainerBot bot = plugin.getBotManager().getBotByName(args[0]);
            if (bot == null) {
                sender.sendMessage(CommandHandler.getPrefix() + ChatColor.RED + "Bot not found: " + args[0]);
                return;
            }
            boolean newState = !bot.getProfile().isPaused();
            bot.getProfile().setPaused(newState);
            sender.sendMessage(CommandHandler.getPrefix() + ChatColor.GREEN
                    + bot.getName() + " is now "
                    + (newState ? ChatColor.YELLOW + "PAUSED" : ChatColor.GREEN + "ACTIVE"));
        }
    }

    @Override
    @Nonnull
    public String getPermission() {
        return "skywarstrainer.control";
    }

    @Override
    @Nonnull
    public String getUsage() {
        return "/swt pause <botName|all>";
    }

    @Override
    @Nonnull
    public String getDescription() {
        return "Pause or resume bot AI.";
    }
}
