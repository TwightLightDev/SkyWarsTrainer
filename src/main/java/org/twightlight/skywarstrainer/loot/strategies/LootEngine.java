package org.twightlight.skywarstrainer.loot;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.twightlight.skywarstrainer.awareness.ChestLocator;
import org.twightlight.skywarstrainer.awareness.GamePhaseTracker;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.loot.strategies.*;
import org.twightlight.skywarstrainer.movement.MovementController;
import org.twightlight.skywarstrainer.util.MathUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * Manages all chest looting interactions for a single bot.
 *
 * <p>LootEngine selects the appropriate loot strategy based on personality and
 * difficulty, handles pathfinding to chests, and delegates per-tick item
 * collection to the active strategy. It integrates with ChestMemory to
 * remember which chests have been looted.</p>
 */
public class LootEngine {

    private final TrainerBot bot;
    private final ChestMemory chestMemory;

    /** The currently active loot strategy. */
    private LootStrategy activeStrategy;

    /** Whether the engine is currently looting a chest. */
    private boolean looting;

    /** The chest currently being looted. */
    private Chest currentChest;

    /** Location of the current target chest. */
    private Location targetChestLocation;

    /** Whether the bot has reached the chest and opened it. */
    private boolean atChest;

    /** Tick counter for timeout. */
    private int lootTicks;
    private static final int MAX_LOOT_TICKS = 200; // 10 seconds timeout

    // Pre-instantiated strategies for reuse
    private final NormalLoot normalLoot = new NormalLoot();
    private final AggressiveLoot aggressiveLoot = new AggressiveLoot();
    private final SpeedLoot speedLoot = new SpeedLoot();
    private final FullLoot fullLoot = new FullLoot();
    private final DenyLoot denyLoot = new DenyLoot();

    /**
     * Creates a new LootEngine for the given bot.
     *
     * @param bot the owning trainer bot
     */
    public LootEngine(@Nonnull TrainerBot bot) {
        this.bot = bot;
        this.chestMemory = new ChestMemory();
        this.looting = false;
        this.atChest = false;
        this.lootTicks = 0;
    }

    /**
     * Starts looting the nearest unlooted chest.
     *
     * @return true if a chest was found and looting started
     */
    public boolean startLootingNearestChest() {
        ChestLocator locator = bot.getChestLocator();
        List<Location> chestLocation = locator.getAllChests().stream().map((info) -> {
            return info.location;
        }).collect(Collectors.toList());
        Location botLoc = bot.getLocation();
        if (locator == null || botLoc == null) return false;

        // Find the nearest unlooted chest
        Location nearest = null;
        double nearestDist = Double.MAX_VALUE;

        for (Location chestLoc : chestLocation) {
            if (chestMemory.isLooted(chestLoc)) continue;
            double dist = botLoc.distanceSquared(chestLoc);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = chestLoc;
            }
        }

        if (nearest == null) return false;
        return startLooting(nearest);
    }

    /**
     * Starts looting a specific chest at the given location.
     *
     * @param chestLocation the chest block location
     * @return true if looting started successfully
     */
    public boolean startLooting(@Nonnull Location chestLocation) {
        Block block = chestLocation.getBlock();
        if (block.getType() != Material.CHEST && block.getType() != Material.TRAPPED_CHEST) {
            return false;
        }

        BlockState state = block.getState();
        if (!(state instanceof Chest)) return false;

        this.currentChest = (Chest) state;
        this.targetChestLocation = chestLocation.clone();
        this.atChest = false;
        this.looting = true;
        this.lootTicks = 0;

        // Select strategy based on personality
        this.activeStrategy = selectStrategy();

        // Start walking toward the chest
        MovementController mc = bot.getMovementController();
        if (mc != null) {
            mc.setSprintJumpEnabled(false); // Don't sprint-jump to a nearby chest
            mc.setMoveTarget(chestLocation.clone().add(0.5, 0, 0.5));
        }

        return true;
    }

    /**
     * Ticks the loot engine. Called by the behavior tree during LOOTING state.
     *
     * @return the result of this tick
     */
    @Nonnull
    public LootTickResult tick() {
        if (!looting || activeStrategy == null) return LootTickResult.NOT_LOOTING;

        lootTicks++;
        if (lootTicks > MAX_LOOT_TICKS) {
            stopLooting();
            return LootTickResult.TIMEOUT;
        }

        // Phase 1: Walk to chest
        if (!atChest) {
            return tickApproach();
        }

        // Phase 2: Loot the chest
        try {
            LootStrategy.LootTickResult result = activeStrategy.tick(bot);
            switch (result) {
                case ITEM_TAKEN:
                    return LootTickResult.ITEM_TAKEN;
                case IN_PROGRESS:
                    return LootTickResult.IN_PROGRESS;
                case COMPLETE:
                    chestMemory.markLooted(targetChestLocation, 5.0);
                    stopLooting();
                    return LootTickResult.COMPLETE;
                case FAILED:
                    stopLooting();
                    return LootTickResult.FAILED;
                default:
                    return LootTickResult.IN_PROGRESS;
            }
        } catch (Exception e) {
            bot.getPlugin().getLogger().log(Level.WARNING,
                    "Error in loot tick for " + bot.getName(), e);
            stopLooting();
            return LootTickResult.FAILED;
        }
    }

    /**
     * Approaches the target chest, checking if we're close enough to start looting.
     */
    @Nonnull
    private LootTickResult tickApproach() {
        Location botLoc = bot.getLocation();
        if (botLoc == null) return LootTickResult.FAILED;

        double dist = MathUtil.horizontalDistance(botLoc, targetChestLocation);
        if (dist < 2.5) {
            // Close enough — initialize loot strategy
            atChest = true;
            MovementController mc = bot.getMovementController();
            if (mc != null) {
                mc.stopAll();
                mc.setFrozen(true); // Stand still while looting
                mc.setLookTarget(targetChestLocation.clone().add(0.5, 0.5, 0.5));
            }
            activeStrategy.initialize(bot, currentChest);
        }
        return LootTickResult.IN_PROGRESS;
    }

    /**
     * Stops the current looting operation.
     */
    public void stopLooting() {
        if (activeStrategy != null) activeStrategy.reset();
        activeStrategy = null;
        looting = false;
        atChest = false;
        currentChest = null;
        targetChestLocation = null;
        lootTicks = 0;

        MovementController mc = bot.getMovementController();
        if (mc != null) {
            mc.setFrozen(false);
            mc.setSprintJumpEnabled(true);
        }
    }

    /**
     * Selects the best loot strategy based on the bot's personality.
     */
    @Nonnull
    private LootStrategy selectStrategy() {
        if (bot.getProfile().hasPersonality("AGGRESSIVE") || bot.getProfile().hasPersonality("BERSERKER")) {
            return aggressiveLoot;
        }
        if (bot.getProfile().hasPersonality("RUSHER")) {
            return speedLoot;
        }
        if (bot.getProfile().hasPersonality("COLLECTOR")) {
            return fullLoot;
        }
        if (bot.getProfile().hasPersonality("STRATEGIC") || bot.getProfile().hasPersonality("TRICKSTER")) {
            return denyLoot;
        }
        return normalLoot;
    }

    // ─── Accessors ──────────────────────────────────────────────

    /** @return true if currently looting */
    public boolean isLooting() { return looting; }

    /** @return the chest memory for this bot */
    @Nonnull
    public ChestMemory getChestMemory() { return chestMemory; }

    /** @return the current target chest location, or null */
    @Nullable
    public Location getTargetChestLocation() { return targetChestLocation; }

    /**
     * Result of a loot engine tick.
     */
    public enum LootTickResult {
        ITEM_TAKEN, IN_PROGRESS, COMPLETE, FAILED, TIMEOUT, NOT_LOOTING
    }
}
