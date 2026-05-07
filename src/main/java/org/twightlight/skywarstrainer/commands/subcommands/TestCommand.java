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

/**
 * /swt test &lt;combat|bridge|loot|flee&gt; [difficulty] — Spawns a single
 * pre-configured bot into the sender's arena. There is no per-test location;
 * Arena.connect places the bot in a free team's cage.
 */
public class TestCommand implements SubCommand {

    private final SkyWarsTrainer plugin;

    public TestCommand(@Nonnull SkyWarsTrainer plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(@Nonnull CommandSender sender, @Nonnull String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(CommandHandler.getPrefix() + ChatColor.RED + "Players only.");
            return;
        }
        Player player = (Player) sender;

        if (args.length < 1) {
            sender.sendMessage(CommandHandler.getPrefix() + ChatColor.RED + "Usage: " + getUsage());
            sender.sendMessage(CommandHandler.getPrefix() + "Test types: combat, bridge, loot, flee");
            return;
        }

        Arena<?> arena = resolveArena(player);
        if (arena == null) {
            sender.sendMessage(CommandHandler.getPrefix() + ChatColor.RED
                    + "Test bots must be spawned from inside an arena. Join one first.");
            return;
        }
        if (!BotManager.isSoloMode(arena)) {
            sender.sendMessage(CommandHandler.getPrefix() + ChatColor.RED
                    + "Test bots only support 1-per-team modes (Solo / 1v1) right now.");
            return;
        }
        if (BotManager.countAvailableSeats(arena) <= 0) {
            sender.sendMessage(CommandHandler.getPrefix() + ChatColor.RED
                    + "Arena '" + arena.getServerName() + "' has no free team slots.");
            return;
        }

        String testType = args[0].toLowerCase();
        Difficulty difficulty = args.length >= 2 ? Difficulty.fromString(args[1]) : Difficulty.MEDIUM;
        if (difficulty == null) difficulty = Difficulty.MEDIUM;

        switch (testType) {
            case "combat":
                runTest(sender, arena, difficulty, Collections.singletonList("AGGRESSIVE"),
                        "CombatTest", "Spawned combat test bot. Fight!");
                break;
            case "bridge":
                runTest(sender, arena, difficulty, Collections.singletonList("RUSHER"),
                        "BridgeTest", "Spawned bridge test bot. Observe bridging.");
                break;
            case "loot":
                runTest(sender, arena, difficulty, Collections.singletonList("COLLECTOR"),
                        "LootTest", "Spawned loot test bot. Watch looting.");
                break;
            case "flee": {
                TrainerBot bot = plugin.getBotManager().spawnBot(arena, difficulty,
                        Collections.singletonList("PASSIVE"), "FleeTest");
                if (bot != null && bot.getLivingEntity() != null) {
                    bot.getLivingEntity().setHealth(4.0);
                    sender.sendMessage(CommandHandler.getPrefix() + ChatColor.GREEN
                            + "Spawned flee test bot at low HP (" + difficulty.name() + "). Chase it!");
                } else if (bot == null) {
                    sender.sendMessage(CommandHandler.getPrefix() + ChatColor.RED + "Failed to spawn test bot.");
                }
                break;
            }
            default:
                sender.sendMessage(CommandHandler.getPrefix() + ChatColor.RED + "Unknown test type: " + testType);
                sender.sendMessage(CommandHandler.getPrefix() + "Valid: combat, bridge, loot, flee");
        }
    }

    private void runTest(@Nonnull CommandSender sender, @Nonnull Arena<?> arena,
                         @Nonnull Difficulty difficulty, @Nonnull List<String> personalities,
                         @Nonnull String name, @Nonnull String successMessage) {
        TrainerBot bot = plugin.getBotManager().spawnBot(arena, difficulty, personalities, name);
        if (bot != null) {
            sender.sendMessage(CommandHandler.getPrefix() + ChatColor.GREEN
                    + successMessage + " (" + difficulty.name() + ")");
        } else {
            sender.sendMessage(CommandHandler.getPrefix() + ChatColor.RED + "Failed to spawn test bot.");
        }
    }

    @Nullable
    private Arena<?> resolveArena(@Nonnull Player player) {
        try {
            SkyWarsServer server = Database.getInstance().getAccount(player.getUniqueId()).getServer();
            return server instanceof Arena ? (Arena<?>) server : null;
        } catch (Exception e) {
            return null;
        }
    }

    @Override @Nonnull public String getPermission()  { return "skywarstrainer.debug"; }
    @Override @Nonnull public String getUsage()       { return "/swt test <combat|bridge|loot|flee> [difficulty]"; }
    @Override @Nonnull public String getDescription() { return "Spawn a pre-configured test bot in your current arena."; }

    /** Suppress unused warning for personalities param when used via Arrays.asList in callers. */
    @SuppressWarnings("unused") private static final Object _arrays_keepalive = Arrays.asList(0);
}
