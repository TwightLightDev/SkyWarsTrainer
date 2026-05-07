package org.twightlight.skywarstrainer.commands.subcommands;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.twightlight.skywars.api.server.SkyWarsServer;
import org.twightlight.skywars.arena.Arena;
import org.twightlight.skywars.database.Database;
import org.twightlight.skywarstrainer.SkyWarsTrainer;
import org.twightlight.skywarstrainer.bot.BotManager;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.commands.CommandHandler;
import org.twightlight.skywarstrainer.config.DifficultyConfig.Difficulty;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class SpawnCommand implements SubCommand {

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

        Difficulty difficulty = Difficulty.fromString(args[0]);
        if (difficulty == null) {
            sender.sendMessage(CommandHandler.getPrefix() + ChatColor.RED + "Invalid difficulty: " + args[0]);
            sender.sendMessage(CommandHandler.getPrefix() + "Valid: BEGINNER, EASY, MEDIUM, HARD, EXPERT");
            return;
        }

        List<String> personalities = Collections.emptyList();
        if (args.length >= 2 && !args[1].startsWith("\"")) {
            personalities = Arrays.asList(args[1].split(","));
        }

        String name = args.length >= 3 ? args[2] : null;

        Arena<?> arena = resolveArena(player);
        if (arena == null) {
            sender.sendMessage(CommandHandler.getPrefix() + ChatColor.RED
                    + "You must be in a SkyWars game to spawn bots. "
                    + "Join an arena first.");
            return;
        }

        // Soft-lock non-1-per-team modes — fail fast with a clear message.
        if (!BotManager.isSoloMode(arena)) {
            sender.sendMessage(CommandHandler.getPrefix() + ChatColor.RED
                    + "Bot spawning is only supported in 1-per-team modes (Solo / 1v1) right now. "
                    + "Team-mode support is coming in a later update.");
            return;
        }

        // Refuse if there are no team seats left.
        if (BotManager.countAvailableSeats(arena) <= 0) {
            sender.sendMessage(CommandHandler.getPrefix() + ChatColor.RED
                    + "Arena '" + arena.getServerName() + "' has no free team slots.");
            return;
        }

        TrainerBot bot = plugin.getBotManager().spawnBot(arena, difficulty, personalities, name);

        if (bot != null) {
            sender.sendMessage(CommandHandler.getPrefix() + ChatColor.GREEN + "Spawned bot: "
                    + ChatColor.YELLOW + bot.getName()
                    + ChatColor.GREEN + " [" + difficulty.name() + "]"
                    + (personalities.isEmpty() ? "" : " " + personalities));
        } else {
            sender.sendMessage(CommandHandler.getPrefix() + ChatColor.RED
                    + "Failed to spawn bot. Check console for details.");
        }
    }

    @Nullable
    private Arena<?> resolveArena(@Nonnull Player player) {
        try {
            SkyWarsServer server = Database.getInstance().getAccount(player.getUniqueId()).getServer();
            return server instanceof Arena ? (Arena<?>) server : null;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to resolve arena for player " + player.getName() + ": " + e.getMessage());
            return null;
        }
    }

    @Override @Nonnull public String getPermission()  { return "skywarstrainer.spawn"; }
    @Override @Nonnull public String getUsage()       { return "/swt spawn <difficulty> [personality1,personality2] [name]"; }
    @Override @Nonnull public String getDescription() { return "Spawn a trainer bot in the arena you're currently in."; }
}
