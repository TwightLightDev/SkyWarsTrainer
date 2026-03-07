package org.twightlight.skywarstrainer.commands.subcommands;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.twightlight.skywarstrainer.SkyWarsTrainer;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.commands.CommandHandler;
import org.twightlight.skywarstrainer.config.DifficultyConfig.Difficulty;

import javax.annotation.Nonnull;
import java.util.Arrays;

/**
 * /swt test <combat|bridge|loot|flee> — Spawns a bot in a test scenario.
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

        if (args.length < 1) {
            sender.sendMessage(CommandHandler.getPrefix() + ChatColor.RED + "Usage: " + getUsage());
            sender.sendMessage(CommandHandler.getPrefix() + "Test types: combat, bridge, loot, flee");
            return;
        }

        Player player = (Player) sender;
        String testType = args[0].toLowerCase();
        Difficulty difficulty = args.length >= 2 ? Difficulty.fromString(args[1]) : Difficulty.MEDIUM;
        if (difficulty == null) difficulty = Difficulty.MEDIUM;

        switch (testType) {
            case "combat":
                testCombat(player, difficulty);
                break;
            case "bridge":
                testBridge(player, difficulty);
                break;
            case "loot":
                testLoot(player, difficulty);
                break;
            case "flee":
                testFlee(player, difficulty);
                break;
            default:
                sender.sendMessage(CommandHandler.getPrefix() + ChatColor.RED
                        + "Unknown test type: " + testType);
                sender.sendMessage(CommandHandler.getPrefix() + "Valid: combat, bridge, loot, flee");
        }
    }

    private void testCombat(Player player, Difficulty difficulty) {
        Location spawnLoc = player.getLocation().add(3, 0, 0);
        TrainerBot bot = plugin.getBotManager().spawnBot(
                null, spawnLoc, difficulty,
                Arrays.asList("AGGRESSIVE"), "CombatTest");

        if (bot != null) {
            player.sendMessage(CommandHandler.getPrefix() + ChatColor.GREEN
                    + "Spawned combat test bot (" + difficulty.name() + "). Fight!");
        }
    }

    private void testBridge(Player player, Difficulty difficulty) {
        Location spawnLoc = player.getLocation();
        TrainerBot bot = plugin.getBotManager().spawnBot(
                null, spawnLoc, difficulty,
                Arrays.asList("RUSHER"), "BridgeTest");

        if (bot != null) {
            player.sendMessage(CommandHandler.getPrefix() + ChatColor.GREEN
                    + "Spawned bridge test bot (" + difficulty.name() + "). Observe bridging.");
        }
    }

    private void testLoot(Player player, Difficulty difficulty) {
        Location spawnLoc = player.getLocation();
        TrainerBot bot = plugin.getBotManager().spawnBot(
                null, spawnLoc, difficulty,
                Arrays.asList("COLLECTOR"), "LootTest");

        if (bot != null) {
            player.sendMessage(CommandHandler.getPrefix() + ChatColor.GREEN
                    + "Spawned loot test bot (" + difficulty.name() + "). Watch looting.");
        }
    }

    private void testFlee(Player player, Difficulty difficulty) {
        Location spawnLoc = player.getLocation().add(3, 0, 0);
        TrainerBot bot = plugin.getBotManager().spawnBot(
                null, spawnLoc, difficulty,
                Arrays.asList("PASSIVE"), "FleeTest");

        if (bot != null && bot.getLivingEntity() != null) {
            // Set bot to low HP to trigger flee behavior
            bot.getLivingEntity().setHealth(4.0);
            player.sendMessage(CommandHandler.getPrefix() + ChatColor.GREEN
                    + "Spawned flee test bot at low HP (" + difficulty.name() + "). Chase it!");
        }
    }

    @Override
    @Nonnull
    public String getPermission() {
        return "skywarstrainer.debug";
    }

    @Override
    @Nonnull
    public String getUsage() {
        return "/swt test <combat|bridge|loot|flee> [difficulty]";
    }

    @Override
    @Nonnull
    public String getDescription() {
        return "Spawn a bot in a test scenario.";
    }
}
