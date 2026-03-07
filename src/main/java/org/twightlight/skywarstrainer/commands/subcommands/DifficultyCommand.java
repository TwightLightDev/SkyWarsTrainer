package org.twightlight.skywarstrainer.commands.subcommands;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.twightlight.skywarstrainer.SkyWarsTrainer;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.commands.CommandHandler;
import org.twightlight.skywarstrainer.config.DifficultyConfig.Difficulty;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;

import javax.annotation.Nonnull;

/**
 * /swt difficulty <name> <difficulty> — Changes a bot's difficulty.
 */
public class DifficultyCommand implements SubCommand {

    private final SkyWarsTrainer plugin;

    public DifficultyCommand(@Nonnull SkyWarsTrainer plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(@Nonnull CommandSender sender, @Nonnull String[] args) {
        if (args.length < 2) {
            sender.sendMessage(CommandHandler.getPrefix() + ChatColor.RED + "Usage: " + getUsage());
            return;
        }

        TrainerBot bot = plugin.getBotManager().getBotByName(args[0]);
        if (bot == null) {
            sender.sendMessage(CommandHandler.getPrefix() + ChatColor.RED + "Bot not found: " + args[0]);
            return;
        }

        Difficulty difficulty = Difficulty.fromString(args[1]);
        if (difficulty == null) {
            sender.sendMessage(CommandHandler.getPrefix() + ChatColor.RED + "Invalid difficulty: " + args[1]);
            sender.sendMessage(CommandHandler.getPrefix() + "Valid: BEGINNER, EASY, MEDIUM, HARD, EXPERT");
            return;
        }

        DifficultyProfile profile = plugin.getDifficultyConfig().getProfile(difficulty);
        bot.getProfile().setDifficulty(difficulty, profile);

        sender.sendMessage(CommandHandler.getPrefix() + ChatColor.GREEN + "Set "
                + ChatColor.YELLOW + bot.getName()
                + ChatColor.GREEN + "'s difficulty to "
                + ChatColor.YELLOW + difficulty.name());
    }

    @Override
    @Nonnull
    public String getPermission() {
        return "skywarstrainer.modify";
    }

    @Override
    @Nonnull
    public String getUsage() {
        return "/swt difficulty <botName> <difficulty>";
    }

    @Override
    @Nonnull
    public String getDescription() {
        return "Change a bot's difficulty level.";
    }
}
