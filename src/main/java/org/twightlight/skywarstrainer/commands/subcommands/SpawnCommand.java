package org.twightlight.skywarstrainer.commands.subcommands;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.twightlight.skywarstrainer.SkyWarsTrainerPlugin;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.commands.CommandHandler;
import org.twightlight.skywarstrainer.config.DifficultyConfig.Difficulty;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * /swt spawn <difficulty> [personality1,personality2,...] [name]
 * Spawns a trainer bot at the player's location.
 */
public class SpawnCommand implements org.twightlight.skywarstrainer.commands.subcommands.SubCommand {

    private final SkyWarsTrainerPlugin plugin;

    public SpawnCommand(@Nonnull SkyWarsTrainerPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(@Nonnull CommandSender sender, @Nonnull String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(CommandHandler.getPrefix() + ChatColor.RED + "This command can only be used by players.");
            return;
        }

        Player player = (Player) sender;

        if (args.length < 1) {
            sender.sendMessage(CommandHandler.getPrefix() + ChatColor.RED + "Usage: " + getUsage());
            return;
        }

        // Parse difficulty
        Difficulty difficulty = Difficulty.fromString(args[0]);
        if (difficulty == null) {
            sender.sendMessage(CommandHandler.getPrefix() + ChatColor.RED + "Invalid difficulty: " + args[0]);
            sender.sendMessage(CommandHandler.getPrefix() + "Valid: BEGINNER, EASY, MEDIUM, HARD, EXPERT");
            return;
        }

        // Parse personalities (optional)
        List<String> personalities = Collections.emptyList();
        if (args.length >= 2) {
            String personalityArg = args[1];
            if (!personalityArg.startsWith("\"")) {
                // Comma-separated personality list
                personalities = Arrays.asList(personalityArg.split(","));
            }
        }

        // Parse name (optional)
        String name = null;
        if (args.length >= 3) {
            name = args[2];
        }

        // Spawn the bot — note: arena parameter needs to be resolved from the player's current game
        // For testing, we pass null arena and the spawn method handles it
        TrainerBot bot = plugin.getBotManager().spawnBot(
                null, // Arena resolved from context
                player.getLocation(),
                difficulty,
                personalities,
                name
        );

        if (bot != null) {
            sender.sendMessage(CommandHandler.getPrefix() + ChatColor.GREEN + "Spawned bot: "
                    + ChatColor.YELLOW + bot.getName()
                    + ChatColor.GREEN + " [" + difficulty.name() + "]"
                    + (personalities.isEmpty() ? "" : " " + personalities));
        } else {
            sender.sendMessage(CommandHandler.getPrefix() + ChatColor.RED + "Failed to spawn bot. Check console for details.");
        }
    }

    @Override
    @Nonnull
    public String getPermission() {
        return "skywarstrainer.spawn";
    }

    @Override
    @Nonnull
    public String getUsage() {
        return "/swt spawn <difficulty> [personality1,personality2] [name]";
    }

    @Override
    @Nonnull
    public String getDescription() {
        return "Spawn a trainer bot with the given difficulty and personalities.";
    }
}
