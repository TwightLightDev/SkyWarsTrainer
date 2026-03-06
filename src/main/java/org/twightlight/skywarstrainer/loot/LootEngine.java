package org.twightlight.skywarstrainer.loot;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.twightlight.skywarstrainer.awareness.ChestLocator;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.loot.strategies.*;
import org.twightlight.skywarstrainer.movement.MovementController;
import org.twightlight.skywarstrainer.util.MathUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Manages all chest interactions and looting behavior for a single bot.
 *
 * <p>LootEngine is ticked by the LOOTING behavior tree. Each tick it finds
 * a chest, pathfinds to it, selects and executes a loot strategy based on
 * personality and difficulty, then triggers inventory equip.</p>
 */
public class LootEngine {

    private final TrainerBot bot;
    private final List<LootStrategy> strategies;
    private LootStrategy activeStrategy;
    private ChestLocator.ChestInfo targetChest;
    private LootPhase phase;
    private int lootTicks;
    private boolean strategyInitialized;

    private static final int MAX_LOOT_TICKS = 200;
    private static final double INTERACT_DISTANCE = 3.0;

    /**
     * Creates a new LootEngine for the given bot.
     *
     * @param bot the owning trainer bot
     */
    public LootEngine(@Nonnull TrainerBot bot) {
        this.bot = bot;
        this.strategies = new ArrayList<>();
        this.activeStrategy = null;
        this.targetChest = null;
        this.phase = LootPhase.IDLE;
        this.lootTicks = 0;
        this.strategyInitialized = false;

        strategies.add(new NormalLoot());
        strategies.add(new AggressiveLoot());
        strategies.add(new SpeedLoot());
        strategies.add(new FullLoot());
        strategies.add(new DenyLoot());
    }

    /**
     * Ticks the loot engine. Called each behavior tree tick during LOOTING state.
     */
    public void tick() {
        Player player = bot.getPlayerEntity();
        if (player == null) return;

        lootTicks++;

        if (lootTicks > MAX_LOOT_TICKS) {
            resetLoot();
            return;
        }

        switch (phase) {
            case IDLE:
                findNextChest();
                break;
            case MOVING_TO_CHEST:
                moveToChest(player);
                break;
            case LOOTING:
                executeLoot();
                break;
            case EQUIPPING:
                if (bot.getInventoryManager() != null) {
                    bot.getInventoryManager().quickEquip();
                }
                resetLoot();
                break;
        }
    }

    private void findNextChest() {
        ChestLocator locator = bot.getChestLocator();
        if (locator == null) return;

        ChestLocator.ChestInfo nearest = locator.getNearestUnlootedChest();
        if (nearest == null) return;

        targetChest = nearest;
        phase = LootPhase.MOVING_TO_CHEST;
        lootTicks = 0;
        activeStrategy = selectStrategy();

        if (bot.getProfile().isDebugMode()) {
            bot.getPlugin().getLogger().info("[DEBUG] " + bot.getName()
                    + " targeting chest at " + targetChest.location.getBlockX()
                    + "," + targetChest.location.getBlockY()
                    + "," + targetChest.location.getBlockZ()
                    + " using " + (activeStrategy != null ? activeStrategy.getName() : "null"));
        }
    }

    private void moveToChest(@Nonnull Player player) {
        if (targetChest == null) { resetLoot(); return; }

        Location botLoc = player.getLocation();
        Location chestLoc = targetChest.location;
        double distance = MathUtil.horizontalDistance(botLoc, chestLoc);

        if (distance <= INTERACT_DISTANCE) {
            phase = LootPhase.LOOTING;
            lootTicks = 0;
            MovementController mc = bot.getMovementController();
            if (mc != null) {
                mc.setLookTarget(chestLoc.clone().add(0.5, 0.5, 0.5));
                mc.setMoveTarget(null);
            }
            return;
        }

        MovementController mc = bot.getMovementController();
        if (mc != null) {
            mc.setMoveTarget(chestLoc);
            mc.setLookTarget(chestLoc.clone().add(0.5, 0.5, 0.5));
            if (distance > 8) {
                mc.getSprintController().startSprinting();
            } else {
                mc.getSprintController().stopSprinting();
            }
        }
    }

    private void executeLoot() {
        if (targetChest == null || activeStrategy == null) { resetLoot(); return; }

        Block block = targetChest.location.getBlock();
        if (block.getType() != Material.CHEST && block.getType() != Material.TRAPPED_CHEST) {
            if (bot.getChestLocator() != null) {
                bot.getChestLocator().markLooted(targetChest.location);
            }
            resetLoot();
            return;
        }

        // Initialize strategy if not yet done
        if (!strategyInitialized) {
            if (block.getState() instanceof Chest) {
                activeStrategy.initialize(bot, (Chest) block.getState());
                strategyInitialized = true;

                // Fire BotLootEvent
                org.bukkit.Bukkit.getPluginManager().callEvent(
                        new org.twightlight.skywarstrainer.api.events.BotLootEvent(
                                bot, targetChest.location, activeStrategy.getName()));
            }
        }

        try {
            LootStrategy.LootTickResult result = activeStrategy.tick(bot);
            switch (result) {
                case COMPLETE:
                    if (bot.getChestLocator() != null) {
                        bot.getChestLocator().markLooted(targetChest.location);
                    }
                    phase = LootPhase.EQUIPPING;
                    lootTicks = 0;
                    strategyInitialized = false;
                    break;
                case FAILED:
                    strategyInitialized = false;
                    resetLoot();
                    break;
                case ITEM_TAKEN:
                case IN_PROGRESS:
                    break;
            }
        } catch (Exception e) {
            bot.getPlugin().getLogger().log(Level.WARNING,
                    "Error executing loot strategy for " + bot.getName(), e);
            strategyInitialized = false;
            resetLoot();
        }
    }

    @Nonnull
    private LootStrategy selectStrategy() {
        List<String> personalities = bot.getProfile().getPersonalityNames();
        DifficultyProfile diff = bot.getDifficultyProfile();

        for (String personality : personalities) {
            switch (personality.toUpperCase()) {
                case "AGGRESSIVE":
                case "BERSERKER":
                    // At medium+ difficulty, aggressive/berserker bots break chests
                    if (diff.getDifficulty().ordinal() >=
                            org.twightlight.skywarstrainer.config.DifficultyConfig.Difficulty.MEDIUM.ordinal()) {
                        return findStrategy("AggressiveLoot");
                    }
                    return findStrategy("SpeedLoot");
                case "RUSHER":
                    return findStrategy("SpeedLoot");
                case "COLLECTOR":
                    return findStrategy("FullLoot");
                case "STRATEGIC":
                case "TRICKSTER":
                    return findStrategy("DenyLoot");
            }
        }
        return findStrategy("NormalLoot");
    }

    @Nonnull
    private LootStrategy findStrategy(@Nonnull String name) {
        for (LootStrategy strat : strategies) {
            if (strat.getName().equals(name)) return strat;
        }
        return strategies.get(0);
    }

    private void resetLoot() {
        phase = LootPhase.IDLE;
        targetChest = null;
        if (activeStrategy != null) {
            activeStrategy.reset();
        }
        activeStrategy = null;
        lootTicks = 0;
        strategyInitialized = false;
        MovementController mc = bot.getMovementController();
        if (mc != null) mc.setMoveTarget(null);
    }

    /** @return true if loot engine is in any active phase */
    public boolean isActive() { return phase != LootPhase.IDLE; }

    @Nonnull public LootPhase getPhase() { return phase; }
    @Nullable public ChestLocator.ChestInfo getTargetChest() { return targetChest; }
    @Nullable public LootStrategy getActiveStrategy() { return activeStrategy; }

    public void registerStrategy(@Nonnull LootStrategy strategy) {
        strategies.add(strategy);
    }

    /** Phases of the loot operation. */
    public enum LootPhase {
        IDLE, MOVING_TO_CHEST, LOOTING, EQUIPPING
    }
}
