// ═══════════════════════════════════════════════════════════════════
// File: src/main/java/org/twightlight/skywarstrainer/bridging/BridgeEngine.java
// ═══════════════════════════════════════════════════════════════════
package org.twightlight.skywarstrainer.bridging;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.bridging.strategies.*;
import org.twightlight.skywarstrainer.bridging.strategies.BridgeStrategy;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.movement.MovementController;
import org.twightlight.skywarstrainer.util.MathUtil;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.logging.Level;

/**
 * Manages all bridge construction for a single bot.
 *
 * <p>BridgeEngine selects the appropriate bridge strategy based on the bot's
 * difficulty, personality, and current situation. It then delegates per-tick
 * block placement to the selected strategy, handling interruptions, block
 * counting, and state transitions.</p>
 *
 * <p>The engine runs when the bot's state machine is in the BRIDGING state.
 * It is ticked by the behavior tree's bridging action nodes.</p>
 *
 * <h3>Strategy Selection Logic:</h3>
 * <ol>
 *   <li>Read bot's bridgeMaxType from DifficultyProfile</li>
 *   <li>Filter available strategies to those at or below the max type</li>
 *   <li>Select the fastest strategy the bot can use reliably</li>
 *   <li>Apply personality modifiers (RUSHER prefers fastest, CAUTIOUS prefers safest)</li>
 * </ol>
 */
public class BridgeEngine {

    private final TrainerBot bot;

    /** All registered bridge strategies, ordered from slowest to fastest. */
    private final List<BridgeStrategy> strategies;

    /** The currently active bridge strategy, or null if not bridging. */
    private BridgeStrategy activeStrategy;

    /** Whether the engine is currently performing a bridge. */
    private boolean bridging;

    /** The destination location the bot is bridging toward. */
    private Location destination;

    /** The direction vector for the current bridge (XZ normalized). */
    private Vector bridgeDirection;

    /** Maximum blocks to place before stopping (prevents infinite bridges). */
    private int maxBlocks;

    /** Tick counter since bridge started, for timeout purposes. */
    private int bridgeTicks;

    /** Maximum ticks before we abandon the bridge attempt. */
    private static final int MAX_BRIDGE_TICKS = 600; // 30 seconds

    /**
     * Strategy type hierarchy for difficulty gating.
     * Maps bridge type names to their required skill level (ordinal).
     * Higher ordinal = more advanced technique.
     */
    private static final Map<String, Integer> BRIDGE_TYPE_LEVELS = new LinkedHashMap<>();
    static {
        BRIDGE_TYPE_LEVELS.put("NORMAL", 0);
        BRIDGE_TYPE_LEVELS.put("SPEED", 1);
        BRIDGE_TYPE_LEVELS.put("DIAGONAL", 1);
        BRIDGE_TYPE_LEVELS.put("NINJA", 2);
        BRIDGE_TYPE_LEVELS.put("MOONWALK", 3);
        BRIDGE_TYPE_LEVELS.put("GOD", 4);
    }

    /**
     * Creates a new BridgeEngine for the given bot.
     *
     * @param bot the owning trainer bot
     */
    public BridgeEngine(@Nonnull TrainerBot bot) {
        this.bot = bot;
        this.strategies = new ArrayList<>();
        this.bridging = false;
        this.activeStrategy = null;
        this.bridgeTicks = 0;
        this.maxBlocks = 64;

        // Register all bridge strategies in order from slowest to fastest
        strategies.add(new NormalBridge());
        strategies.add(new SpeedBridge());
        strategies.add(new DiagonalBridge());
        strategies.add(new NinjaBridge());
        strategies.add(new MoonwalkBridge());
        strategies.add(new GodBridge());
    }

    /**
     * Starts a bridge from the bot's current position toward the given destination.
     *
     * <p>Selects the best strategy, calculates the direction, and initializes
     * the strategy for block-by-block construction.</p>
     *
     * @param destination the target location to bridge toward
     * @param maxBlocks   the maximum number of blocks to place (0 = unlimited up to 64)
     * @return true if bridging started successfully, false if unable to bridge
     */
    public boolean startBridge(@Nonnull Location destination, int maxBlocks) {
        Player player = bot.getPlayerEntity();
        Location botLoc = bot.getLocation();
        if (player == null || botLoc == null) return false;

        // Check we have blocks to place
        if (!hasBlocksToPlace(player)) {
            if (bot.getProfile().isDebugMode()) {
                bot.getPlugin().getLogger().info("[DEBUG] " + bot.getName()
                        + " cannot bridge: no blocks available");
            }
            return false;
        }

        this.destination = destination.clone();
        this.maxBlocks = maxBlocks > 0 ? maxBlocks : 64;
        this.bridgeTicks = 0;

        // Calculate bridge direction (XZ plane, normalized)
        this.bridgeDirection = new Vector(
                destination.getX() - botLoc.getX(),
                0,
                destination.getZ() - botLoc.getZ()
        );
        if (bridgeDirection.lengthSquared() < 0.01) {
            // Destination is too close horizontally
            return false;
        }
        bridgeDirection.normalize();

        // Check if this is a diagonal bridge (angle not aligned to axis)
        boolean isDiagonal = isDiagonalDirection(bridgeDirection);

        // Select the best strategy for this bot
        activeStrategy = selectStrategy(isDiagonal);
        if (activeStrategy == null) {
            bot.getPlugin().getLogger().warning("No suitable bridge strategy for " + bot.getName());
            return false;
        }

        // Initialize the strategy
        activeStrategy.initialize(bot, botLoc, bridgeDirection);
        bridging = true;

        if (bot.getProfile().isDebugMode()) {
            bot.getPlugin().getLogger().info("[DEBUG] " + bot.getName()
                    + " starting bridge with " + activeStrategy.getName()
                    + " toward " + formatLoc(destination)
                    + " (maxBlocks=" + this.maxBlocks + ")");
        }

        return true;
    }

    /**
     * Ticks one frame of the active bridge. Called by the behavior tree
     * each tick while in BRIDGING state.
     *
     * @return the result of this bridge tick
     */
    @Nonnull
    public BridgeTickResult tick() {
        if (!bridging || activeStrategy == null) {
            return BridgeTickResult.NOT_BRIDGING;
        }

        bridgeTicks++;

        // Timeout check
        if (bridgeTicks > MAX_BRIDGE_TICKS) {
            stopBridge();
            return BridgeTickResult.TIMEOUT;
        }

        // Check if we've placed enough blocks
        if (activeStrategy.getBlocksPlaced() >= maxBlocks) {
            stopBridge();
            return BridgeTickResult.COMPLETE;
        }

        // Check if we have blocks remaining
        Player player = bot.getPlayerEntity();
        if (player == null || !hasBlocksToPlace(player)) {
            stopBridge();
            return BridgeTickResult.OUT_OF_BLOCKS;
        }

        // Check if we've reached the destination (close enough horizontally)
        Location botLoc = bot.getLocation();
        if (botLoc != null && destination != null) {
            double dist = MathUtil.horizontalDistance(botLoc, destination);
            if (dist < 2.0) {
                stopBridge();
                return BridgeTickResult.COMPLETE;
            }
        }

        // Delegate to the active strategy
        try {
            BridgeStrategy.BridgeTickResult strategyResult = activeStrategy.tick(bot);

            switch (strategyResult) {
                case PLACED:
                    return BridgeTickResult.PLACED;
                case MOVING:
                    return BridgeTickResult.IN_PROGRESS;
                case FAILED:
                    stopBridge();
                    return BridgeTickResult.FAILED;
                case COMPLETE:
                    stopBridge();
                    return BridgeTickResult.COMPLETE;
                default:
                    return BridgeTickResult.IN_PROGRESS;
            }
        } catch (Exception e) {
            bot.getPlugin().getLogger().log(Level.WARNING,
                    "Error in bridge tick for " + bot.getName(), e);
            stopBridge();
            return BridgeTickResult.FAILED;
        }
    }

    /**
     * Stops the current bridge, resetting strategy state and sneaking.
     */
    public void stopBridge() {
        if (activeStrategy != null) {
            activeStrategy.reset();
        }
        activeStrategy = null;
        bridging = false;
        destination = null;
        bridgeDirection = null;
        bridgeTicks = 0;

        // Stop sneaking when bridge ends
        MovementController mc = bot.getMovementController();
        if (mc != null) {
            mc.setSneaking(false);
        }

        if (bot.getProfile().isDebugMode()) {
            bot.getPlugin().getLogger().info("[DEBUG] " + bot.getName() + " stopped bridging");
        }
    }

    /**
     * Selects the best bridge strategy for the current bot based on difficulty
     * and whether the bridge direction is diagonal.
     *
     * @param isDiagonal whether the bridge is at a diagonal angle
     * @return the selected strategy, or null if none available
     */
    @Nullable
    private BridgeStrategy selectStrategy(boolean isDiagonal) {
        DifficultyProfile diff = bot.getDifficultyProfile();
        String maxType = diff.getBridgeMaxType();
        int maxLevel = BRIDGE_TYPE_LEVELS.getOrDefault(maxType, 0);

        // If diagonal, prefer diagonal bridge strategy if available
        if (isDiagonal) {
            for (BridgeStrategy strat : strategies) {
                if (strat.getName().equals("DiagonalBridge")) {
                    String reqType = strat.getRequiredBridgeType();
                    int reqLevel = BRIDGE_TYPE_LEVELS.getOrDefault(reqType, 0);
                    if (reqLevel <= maxLevel) {
                        return strat;
                    }
                }
            }
        }

        // Filter strategies by what the bot can handle
        List<BridgeStrategy> available = new ArrayList<>();
        for (BridgeStrategy strat : strategies) {
            if (strat.getName().equals("DiagonalBridge") && !isDiagonal) continue;
            String reqType = strat.getRequiredBridgeType();
            int reqLevel = BRIDGE_TYPE_LEVELS.getOrDefault(reqType, 0);
            if (reqLevel <= maxLevel) {
                available.add(strat);
            }
        }

        if (available.isEmpty()) return null;

        // Personality-based selection
        boolean prefersSpeed = bot.getProfile().hasPersonality("RUSHER")
                || bot.getProfile().hasPersonality("AGGRESSIVE");
        boolean prefersSafety = bot.getProfile().hasPersonality("CAUTIOUS")
                || bot.getProfile().hasPersonality("PASSIVE");

        if (prefersSpeed) {
            // Pick the fastest available strategy
            return available.get(available.size() - 1);
        } else if (prefersSafety) {
            // Pick the safest (slowest) strategy
            return available.get(0);
        } else {
            // Pick one in the upper half of available strategies for variety
            int idx = available.size() > 2
                    ? RandomUtil.nextInt(available.size() / 2, available.size())
                    : available.size() - 1;
            return available.get(Math.min(idx, available.size() - 1));
        }
    }

    /**
     * Determines if the bridge direction is diagonal (not axis-aligned).
     * A direction is diagonal if neither its X nor Z component dominates
     * by more than 2:1 ratio.
     *
     * @param direction the bridge direction vector (XZ normalized)
     * @return true if the direction is diagonal
     */
    private boolean isDiagonalDirection(@Nonnull Vector direction) {
        double absX = Math.abs(direction.getX());
        double absZ = Math.abs(direction.getZ());
        if (absX < 0.01 || absZ < 0.01) return false;
        double ratio = Math.max(absX, absZ) / Math.min(absX, absZ);
        return ratio < 2.0; // Within 2:1 ratio = roughly diagonal
    }

    /**
     * Checks if the bot has any placeable blocks in their inventory.
     *
     * @param player the bot's player entity
     * @return true if blocks are available
     */
    private boolean hasBlocksToPlace(@Nonnull Player player) {
        Material[] buildMats = {
                Material.COBBLESTONE, Material.STONE, Material.WOOL,
                Material.WOOD, Material.SANDSTONE, Material.DIRT,
                Material.SAND, Material.NETHERRACK, Material.ENDER_STONE,
                Material.HARD_CLAY, Material.STAINED_CLAY
        };
        for (Material mat : buildMats) {
            if (player.getInventory().contains(mat)) return true;
        }
        return false;
    }

    /**
     * Returns the count of placeable blocks in the bot's inventory.
     *
     * @return the total block count, or 0 if player entity unavailable
     */
    public int getAvailableBlockCount() {
        Player player = bot.getPlayerEntity();
        if (player == null) return 0;

        Material[] buildMats = {
                Material.COBBLESTONE, Material.STONE, Material.WOOL,
                Material.WOOD, Material.SANDSTONE, Material.DIRT,
                Material.SAND, Material.NETHERRACK, Material.ENDER_STONE,
                Material.HARD_CLAY, Material.STAINED_CLAY
        };
        int total = 0;
        for (Material mat : buildMats) {
            for (org.bukkit.inventory.ItemStack stack : player.getInventory().getContents()) {
                if (stack != null && stack.getType() == mat) {
                    total += stack.getAmount();
                }
            }
        }
        return total;
    }

    // ─── Accessors ──────────────────────────────────────────────

    /** @return true if the engine is currently bridging */
    public boolean isBridging() { return bridging; }

    /** @return the currently active strategy, or null */
    @Nullable
    public BridgeStrategy getActiveStrategy() { return activeStrategy; }

    /** @return the bridge destination, or null */
    @Nullable
    public Location getDestination() { return destination; }

    /** @return the number of blocks placed in the current bridge */
    public int getBlocksPlaced() {
        return activeStrategy != null ? activeStrategy.getBlocksPlaced() : 0;
    }

    /** @return ticks elapsed since bridge started */
    public int getBridgeTicks() { return bridgeTicks; }

    /** @return the bridge direction vector */
    @Nullable
    public Vector getBridgeDirection() { return bridgeDirection; }

    /** @return all registered strategies (read-only) */
    @Nonnull
    public List<BridgeStrategy> getStrategies() {
        return Collections.unmodifiableList(strategies);
    }

    /**
     * Registers a custom bridge strategy.
     *
     * @param strategy the strategy to register
     */
    public void registerStrategy(@Nonnull BridgeStrategy strategy) {
        strategies.add(strategy);
        // Re-sort by base speed
        strategies.sort(Comparator.comparingDouble(BridgeStrategy::getBaseSpeed));
    }

    private static String formatLoc(Location loc) {
        return String.format("(%.1f, %.1f, %.1f)", loc.getX(), loc.getY(), loc.getZ());
    }

    // ═══════════════════════════════════════════════════════════════
    //  Bridge tick result enum for the engine level
    // ═══════════════════════════════════════════════════════════════

    /**
     * Result of a bridge engine tick. Distinct from the strategy-level result
     * to include engine-level conditions like timeout and out-of-blocks.
     */
    public enum BridgeTickResult {
        /** A block was placed this tick. */
        PLACED,
        /** Bridge is in progress (moving/aiming). */
        IN_PROGRESS,
        /** Bridge completed — reached destination. */
        COMPLETE,
        /** Bridge failed (fell off, error). */
        FAILED,
        /** Ran out of blocks. */
        OUT_OF_BLOCKS,
        /** Bridge timed out (too many ticks). */
        TIMEOUT,
        /** Engine is not currently bridging. */
        NOT_BRIDGING
    }
}
