package org.twightlight.skywarstrainer.commands.subcommands;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.twightlight.skywars.api.server.SkyWarsServer;
import org.twightlight.skywars.arena.Arena;
import org.twightlight.skywars.database.Database;
import org.twightlight.skywarstrainer.SkyWarsTrainer;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.commands.CommandHandler;
import org.twightlight.skywarstrainer.config.DifficultyConfig.Difficulty;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * /swt spawn <difficulty> [personality1,personality2,...] [name]
 * Spawns a trainer bot at the player's location.
 */
public class SpawnCommand implements org.twightlight.skywarstrainer.commands.subcommands.SubCommand {

    private final SkyWarsTrainer plugin;

    public SpawnCommand(@Nonnull SkyWarsTrainer plugin) {
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

        // [FIX 1.2] Resolve the arena from the player's current LostSkyWars game.
        // Passing null arena causes NPE in BotManager.spawnBot() and downstream.
        Arena<?> arena = resolveArena(player);
        if (arena == null) {
            sender.sendMessage(CommandHandler.getPrefix() + ChatColor.RED
                    + "You must be in a SkyWars game to spawn bots. "
                    + "Join an arena first, or ensure LostSkyWars is installed.");
            return;
        }

        TrainerBot bot = plugin.getBotManager().spawnBot(
                arena,
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

    /**
     * Attempts to resolve the SkyWars arena the player is currently in.
     * Uses the LostSkyWars API (SkyWarsServer) to find the player's game.
     *
     * @param player the player
     * @return the arena, or null if the player is not in one / LostSkyWars unavailable
     */
    @Nullable
    private Arena<?> resolveArena(@Nonnull Player player) {
        try {
            SkyWarsServer server = Database.getInstance().getAccount(player.getUniqueId()).getServer();
            if (server instanceof Arena) {
                return (Arena<?>) server;
            }
            return null;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to resolve arena for player " + player.getName() + ": " + e.getMessage());
            return null;
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
