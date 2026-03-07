package org.twightlight.skywarstrainer.commands.subcommands;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.twightlight.skywarstrainer.SkyWarsTrainer;
import org.twightlight.skywarstrainer.bot.BotProfile;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.commands.CommandHandler;

import javax.annotation.Nonnull;

/**
 * /swt stats [name] — Shows bot statistics.
 */
public class StatsCommand implements SubCommand {

    private final SkyWarsTrainer plugin;

    public StatsCommand(@Nonnull SkyWarsTrainer plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(@Nonnull CommandSender sender, @Nonnull String[] args) {
        if (args.length < 1) {
            // Show stats for all bots
            sender.sendMessage(ChatColor.GOLD + "━━━ Bot Statistics ━━━");
            for (TrainerBot bot : plugin.getBotManager().getAllBots()) {
                BotProfile p = bot.getProfile();
                sender.sendMessage(ChatColor.YELLOW + bot.getName()
                        + ChatColor.GRAY + " — K:" + p.getKills() + " D:" + p.getDeaths()
                        + " KD:" + String.format("%.2f", p.getKDRatio())
                        + " W:" + p.getGamesWon() + "/" + p.getGamesPlayed()
                        + " (" + String.format("%.0f", p.getWinRate()) + "%)");
            }
            return;
        }

        TrainerBot bot = plugin.getBotManager().getBotByName(args[0]);
        if (bot == null) {
            sender.sendMessage(CommandHandler.getPrefix() + ChatColor.RED + "Bot not found: " + args[0]);
            return;
        }

        BotProfile p = bot.getProfile();
        sender.sendMessage(ChatColor.GOLD + "━━━ " + bot.getName() + " Statistics ━━━");
        sender.sendMessage(ChatColor.GRAY + "Difficulty: " + ChatColor.WHITE + p.getDifficulty().name());
        sender.sendMessage(ChatColor.GRAY + "Personalities: " + ChatColor.WHITE + p.getPersonalityNames());
        sender.sendMessage(ChatColor.GRAY + "Kills: " + ChatColor.GREEN + p.getKills());
        sender.sendMessage(ChatColor.GRAY + "Deaths: " + ChatColor.RED + p.getDeaths());
        sender.sendMessage(ChatColor.GRAY + "K/D Ratio: " + ChatColor.WHITE + String.format("%.2f", p.getKDRatio()));
        sender.sendMessage(ChatColor.GRAY + "Games: " + ChatColor.WHITE + p.getGamesPlayed()
                + " (Won: " + p.getGamesWon() + ")");
        sender.sendMessage(ChatColor.GRAY + "Win Rate: " + ChatColor.WHITE + String.format("%.1f%%", p.getWinRate()));
        sender.sendMessage(ChatColor.GOLD + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    @Override
    @Nonnull
    public String getPermission() {
        return "skywarstrainer.stats";
    }

    @Override
    @Nonnull
    public String getUsage() {
        return "/swt stats [botName]";
    }

    @Override
    @Nonnull
    public String getDescription() {
        return "Show bot statistics.";
    }
}
