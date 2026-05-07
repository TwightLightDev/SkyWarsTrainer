package org.twightlight.skywarstrainer.commands.subcommands;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.twightlight.skywars.api.server.SkyWarsServer;
import org.twightlight.skywars.arena.Arena;
import org.twightlight.skywars.database.Database;
import org.twightlight.skywarstrainer.SkyWarsTrainer;
import org.twightlight.skywarstrainer.bot.BotManager;
import org.twightlight.skywarstrainer.commands.CommandHandler;
import org.twightlight.skywarstrainer.config.DifficultyConfig.Difficulty;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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
        Player player = (Player) sender;

        if (args.length < 2) {
            sender.sendMessage(CommandHandler.getPrefix() + ChatColor.RED + "Usage: " + getUsage());
            return;
        }

        int count;
        try {
            count = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            sender.sendMessage(CommandHandler.getPrefix() + ChatColor.RED + "Invalid count: " + args[0]);
            return;
        }
        if (count <= 0) {
            sender.sendMessage(CommandHandler.getPrefix() + ChatColor.RED + "Count must be > 0.");
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

        Arena<?> arena = resolveArena(player);
        if (arena == null) {
            sender.sendMessage(CommandHandler.getPrefix() + ChatColor.RED
                    + "You must be in a SkyWars game to fill with bots.");
            return;
        }

        if (!BotManager.isSoloMode(arena)) {
            sender.sendMessage(CommandHandler.getPrefix() + ChatColor.RED
                    + "Fill is only supported in 1-per-team modes (Solo / 1v1) right now. "
                    + "Team-mode support is coming in a later update.");
            return;
        }

        int free = BotManager.countAvailableSeats(arena);
        if (free <= 0) {
            sender.sendMessage(CommandHandler.getPrefix() + ChatColor.RED
                    + "Arena '" + arena.getServerName() + "' has no free team slots.");
            return;
        }
        if (free < count) {
            sender.sendMessage(CommandHandler.getPrefix() + ChatColor.YELLOW
                    + "Only " + free + " free seat(s) — will fill up to that.");
        }

        int spawned = plugin.getBotManager().fillWithBots(arena, count, difficulty, personalities);

        sender.sendMessage(CommandHandler.getPrefix() + ChatColor.GREEN + "Spawned "
                + ChatColor.YELLOW + spawned + "/" + count
                + ChatColor.GREEN + " bots [" + difficulty.name() + "]");
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

    @Override @Nonnull public String getPermission()  { return "skywarstrainer.fill"; }
    @Override @Nonnull public String getUsage()       { return "/swt fill <count> <difficulty> [personality1,personality2]"; }
    @Override @Nonnull public String getDescription() { return "Fill your current arena with bots (one per free team)."; }
}
