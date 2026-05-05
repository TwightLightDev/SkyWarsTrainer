package org.twightlight.skywarstrainer.commands.subcommands;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.twightlight.skywars.api.server.SkyWarsServer;
import org.twightlight.skywars.arena.Arena;
import org.twightlight.skywars.database.Database;
import org.twightlight.skywarstrainer.SkyWarsTrainer;
import org.twightlight.skywarstrainer.commands.CommandHandler;
import org.twightlight.skywarstrainer.config.DifficultyConfig.Difficulty;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * /swt fill <count> <difficulty> [personalities...] — Fill a game with bots.
 */
public class FillCommand implements SubCommand {

    private final SkyWarsTrainer plugin;

    public FillCommand(@Nonnull SkyWarsTrainer plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(@Nonnull CommandSender sender, @Nonnull String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(CommandHandler.getPrefix() + ChatColor.RED + "This command can only be used by players.");
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(CommandHandler.getPrefix() + ChatColor.RED + "Usage: " + getUsage());
            return;
        }

        Player player = (Player) sender;

        int count;
        try {
            count = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            sender.sendMessage(CommandHandler.getPrefix() + ChatColor.RED + "Invalid count: " + args[0]);
            return;
        }

        Difficulty difficulty = Difficulty.fromString(args[1]);
        if (difficulty == null) {
            sender.sendMessage(CommandHandler.getPrefix() + ChatColor.RED + "Invalid difficulty: " + args[1]);
            return;
        }

        List<String> personalities = Collections.emptyList();
        if (args.length >= 3) {
            personalities = Arrays.asList(args[2].split(","));
        }

        // [FIX 1.2] Resolve the arena from the player's current LostSkyWars game.
        Arena<?> arena = resolveArena(player);
        if (arena == null) {
            sender.sendMessage(CommandHandler.getPrefix() + ChatColor.RED
                    + "You must be in a SkyWars game to fill with bots. "
                    + "Join an arena first, or ensure LostSkyWars is installed.");
            return;
        }

        int spawned = plugin.getBotManager().fillWithBots(
                arena, player.getLocation(), count, difficulty, personalities);

        sender.sendMessage(CommandHandler.getPrefix() + ChatColor.GREEN + "Spawned "
                + ChatColor.YELLOW + spawned + "/" + count
                + ChatColor.GREEN + " bots [" + difficulty.name() + "]");
    }

    /**
     * Attempts to resolve the SkyWars arena the player is currently in.
     *
     * @param player the player
     * @return the arena, or null if not in one / LostSkyWars unavailable
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
        return "skywarstrainer.fill";
    }

    @Override
    @Nonnull
    public String getUsage() {
        return "/swt fill <count> <difficulty> [personality1,personality2]";
    }

    @Override
    @Nonnull
    public String getDescription() {
        return "Fill a game with the specified number of bots.";
    }
}
