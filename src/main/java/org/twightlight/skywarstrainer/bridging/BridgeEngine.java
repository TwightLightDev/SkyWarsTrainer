package org.twightlight.skywarstrainer.bridging;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.bridging.movement.BridgeMovementController;
import org.twightlight.skywarstrainer.bridging.movement.BridgeMovementDirective;
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
 * difficulty, personality, current situation, and the vertical relationship
 * between the bot and the destination. It delegates per-tick block placement
 * to the selected strategy, handling interruptions, block counting, and
 * state transitions.</p>
 *
 * <p>The engine supports three modes of bridging:</p>
 * <ul>
 *   <li><b>Flat bridging</b>: destination is at roughly the same Y level (±2 blocks).
 *       Uses standard strategies (Normal, Speed, Ninja, God, Moonwalk, Diagonal).</li>
 *   <li><b>Ascending bridging</b>: destination is significantly higher (>2 blocks above).
 *       Uses AscendingBridge to build a staircase upward, optionally transitioning to
 *       flat bridging once the target height is reached.</li>
 *   <li><b>Descending bridging</b>: destination is lower. The bot bridges flat and lets
 *       gravity handle the descent, or builds a gentle descending slope.</li>
 * </ul>
 *
 * <p>The engine runs when the bot's state machine is in the BRIDGING state.
 * It is ticked by the behavior tree's bridging action nodes.</p>
 *
 * <h3>Strategy Selection Logic:</h3>
 * <ol>
 *   <li>Check vertical difference between bot and destination</li>
 *   <li>If ascending (>2 blocks up): use AscendingBridge first, then switch to flat</li>
 *   <li>Read bot's bridgeMaxType from DifficultyProfile</li>
 *   <li>Filter available strategies to those at or below the max type</li>
 *   <li>Select the fastest strategy the bot can use reliably</li>
 *   <li>Apply personality modifiers (RUSHER prefers fastest, CAUTIOUS prefers safest)</li>
 * </ol>
 */
public class BridgeEngine {

    private final TrainerBot bot;

    /** All registered flat bridge strategies, ordered from slowest to fastest. */
    private final List<BridgeStrategy> flatStrategies;

    /** The ascending bridge strategy instance. */
    private final AscendingBridge ascendingStrategy;

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
     * Whether we are in the ascending phase of a multi-phase bridge.
     * After ascending completes, we switch to flat bridging for the remaining
     * horizontal distance.
     */
    private boolean inAscendingPhase;

    /** The height difference the bot needs to ascend. */
    private int heightDifference;

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
        this.flatStrategies = new ArrayList<>();
        this.ascendingStrategy = new AscendingBridge();
        this.bridging = false;
        this.activeStrategy = null;
        this.bridgeTicks = 0;
        this.maxBlocks = 64;
        this.inAscendingPhase = false;
        this.heightDifference = 0;

        // Register all flat bridge strategies in order from slowest to fastest
        flatStrategies.add(new NormalBridge());
        flatStrategies.add(new SpeedBridge());
        flatStrategies.add(new DiagonalBridge());
        flatStrategies.add(new NinjaBridge());
        flatStrategies.add(new MoonwalkBridge());
        flatStrategies.add(new GodBridge());
    }

    /**
     * Starts a bridge from the bot's current position toward the given destination.
     *
     * <p>Analyzes the vertical difference between the bot and destination to decide
     * whether to use ascending bridging, flat bridging, or a combination. Selects
     * the best strategy, calculates the direction, and initializes the strategy
     * for block-by-block construction.</p>
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
            // Destination is directly above/below — use a default forward direction
            double yawRad = Math.toRadians(botLoc.getYaw());
            bridgeDirection = new Vector(-Math.sin(yawRad), 0, Math.cos(yawRad));
        }
        bridgeDirection.normalize();

        // Calculate vertical difference
        this.heightDifference = destination.getBlockY() - botLoc.getBlockY();

        // Decide bridging mode based on height difference
        if (heightDifference > 2) {
            // Need to ascend — start with ascending bridge
            return startAscendingBridge(botLoc);
        } else {
            // Flat or descending — use standard flat bridge
            return startFlatBridge(botLoc);
        }
    }

    /**
     * Starts an ascending bridge sequence. The bot will build a staircase
     * upward until reaching the destination's Y level, then optionally
     * switch to flat bridging for remaining horizontal distance.
     *
     * @param botLoc the bot's current location
     * @return true if ascending bridge started successfully
     */
    private boolean startAscendingBridge(@Nonnull Location botLoc) {
        ascendingStrategy.initialize(bot, botLoc, bridgeDirection);
        ascendingStrategy.setTargetY(destination.getBlockY());

        activeStrategy = ascendingStrategy;
        inAscendingPhase = true;
        bridging = true;

        if (bot.getProfile().isDebugMode()) {
            bot.getPlugin().getLogger().info("[DEBUG] " + bot.getName()
                    + " starting ASCENDING bridge (+" + heightDifference + " blocks) toward "
                    + formatLoc(destination));
        }

        return true;
    }

    /**
     * Starts a flat bridge using the best available strategy.
     *
     * @param botLoc the bot's current location
     * @return true if flat bridge started successfully
     */
    private boolean startFlatBridge(@Nonnull Location botLoc) {
        boolean isDiagonal = isDiagonalDirection(bridgeDirection);
        activeStrategy = selectFlatStrategy(isDiagonal);
        if (activeStrategy == null) {
            bot.getPlugin().getLogger().warning("No suitable bridge strategy for " + bot.getName());
            return false;
        }

        activeStrategy.initialize(bot, botLoc, bridgeDirection);
        inAscendingPhase = false;
        bridging = true;

        if (bot.getProfile().isDebugMode()) {
            bot.getPlugin().getLogger().info("[DEBUG] " + bot.getName()
                    + " starting FLAT bridge with " + activeStrategy.getName()
                    + " toward " + formatLoc(destination)
                    + " (maxBlocks=" + this.maxBlocks + ")");
        }

        return true;
    }

    /**
     * Ticks one frame of the active bridge. Called by the behavior tree
     * each tick while in BRIDGING state.
     *
     * <p><b>Phase 7 Integration:</b> Now queries the BridgeMovementController
     * for a {@link org.twightlight.skywarstrainer.bridging.movement.BridgeMovementDirective}
     * before delegating to the active strategy. The directive advises sprint/sneak/jump
     * state and may request placement pauses (bait bridge) or side-block placement
     * (safety rail). The strategy remains authoritative — it reads the directive
     * and integrates what it can.</p>
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
        if (getTotalBlocksPlaced() >= maxBlocks) {
            stopBridge();
            return BridgeTickResult.COMPLETE;
        }

        // Check if we have blocks remaining
        Player player = bot.getPlayerEntity();
        if (player == null || !hasBlocksToPlace(player)) {
            stopBridge();
            return BridgeTickResult.OUT_OF_BLOCKS;
        }

        // Check if we've reached the destination
        Location botLoc = bot.getLocation();
        if (botLoc != null && destination != null) {
            double horizDist = MathUtil.horizontalDistance(botLoc, destination);
            double vertDist = Math.abs(botLoc.getY() - destination.getY());
            if (horizDist < 2.0 && vertDist < 2.0) {
                stopBridge();
                return BridgeTickResult.COMPLETE;
            }
        }

        // ═══ Phase 7: BridgeMovementController directive integration ═══
        // Query the movement controller for movement advice BEFORE strategy ticks.
        // The directive communicates sprint/sneak/jump requests and may pause
        // block placement (bait bridge) or request side blocks (safety rail).
        BridgeMovementController movementCtrl = bot.getBridgeMovementController();
        BridgeMovementDirective directive = null;
        if (movementCtrl != null) {
            directive = movementCtrl.computeDirective();

            // If the directive requests a placement pause (bait bridge waiting),
            // skip the strategy tick entirely — don't place any blocks this tick.
            if (directive.pausePlacement) {
                // Still apply movement state from the directive
                applyDirectiveMovement(directive);
                movementCtrl.postTick();
                return BridgeTickResult.IN_PROGRESS;
            }

            // Apply movement state BEFORE strategy runs (sprint, sneak, jump)
            applyDirectiveMovement(directive);
        }
        // ═══ End Phase 7 directive pre-processing ═══

        // Delegate to the active strategy
        try {
            BridgeStrategy.BridgeTickResult strategyResult = activeStrategy.tick(bot);

            // ═══ Phase 7: Post-strategy processing ═══
            if (movementCtrl != null) {
                // Notify movement controller that a block was placed (bait tracking)
                if (strategyResult == BridgeStrategy.BridgeTickResult.PLACED) {
                    movementCtrl.onBlockPlaced();
                }

                // Handle safety rail: if directive requests a side block, place it
                if (directive != null && directive.placeSideBlock) {
                    placeSafetyRailBlock(directive.sideBlockDirection);
                }

                // Post-tick for state updates (bait timer, jump bridge cooldown)
                movementCtrl.postTick();
            }
            // ═══ End Phase 7 post-processing ═══

            switch (strategyResult) {
                case PLACED:
                    return BridgeTickResult.PLACED;
                case FAILED:
                    if (inAscendingPhase) {
                        return handleAscendingFailure();
                    }
                    stopBridge();
                    return BridgeTickResult.FAILED;
                case COMPLETE:
                    if (inAscendingPhase) {
                        return transitionToFlatBridge();
                    }
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
     * Applies the movement directive's sprint/sneak/jump requests to the
     * MovementController. The strategy can override these if needed, since
     * it ticks AFTER this call. But for directives like JUMP_BRIDGE, the jump
     * must happen before the strategy tick to time correctly with block placement.
     *
     * @param directive the movement directive
     */
    private void applyDirectiveMovement(@Nonnull BridgeMovementDirective directive) {
        MovementController mc = bot.getMovementController();
        if (mc == null) return;

        if (directive.requestSprint) {
            mc.getSprintController().startSprinting();
        }
        if (directive.requestSneak) {
            mc.setSneaking(true);
        }
        if (directive.requestJump) {
            mc.getJumpController().jump();
        }
        if (!Float.isNaN(directive.pitchOverride)) {
            mc.setCurrentPitch(directive.pitchOverride);
        }
    }

    /**
     * Places a safety rail block on the side of the bridge.
     * Called when the BridgeMovementDirective requests a side block.
     *
     * @param sideDirection -1 for left, +1 for right
     */
    private void placeSafetyRailBlock(int sideDirection) {
        Player player = bot.getPlayerEntity();
        if (player == null) return;
        Location botLoc = bot.getLocation();
        if (botLoc == null || bridgeDirection == null) return;

        // Calculate the side direction perpendicular to bridge direction
        double sideX = -bridgeDirection.getZ() * sideDirection;
        double sideZ = bridgeDirection.getX() * sideDirection;

        // Place block 1 to the side, at feet level
        Location railLoc = botLoc.clone().add(sideX, 0, sideZ);
        org.bukkit.block.Block railBlock = railLoc.getBlock();
        if (railBlock.getType() == Material.AIR && hasBlocksToPlace(player)) {
            // Find a block material in inventory and place it
            Material[] buildMats = {
                    Material.COBBLESTONE, Material.STONE, Material.WOOL,
                    Material.WOOD, Material.SANDSTONE, Material.DIRT
            };
            for (Material mat : buildMats) {
                if (player.getInventory().contains(mat)) {
                    railBlock.setType(mat);
                    // Remove one block from inventory
                    org.bukkit.inventory.ItemStack[] contents = player.getInventory().getContents();
                    for (int i = 0; i < contents.length; i++) {
                        if (contents[i] != null && contents[i].getType() == mat) {
                            if (contents[i].getAmount() > 1) {
                                contents[i].setAmount(contents[i].getAmount() - 1);
                            } else {
                                player.getInventory().setItem(i, null);
                            }
                            break;
                        }
                    }
                    break;
                }
            }
        }
    }


    /**
     * Transitions from ascending bridge to flat bridge once the target height
     * has been reached. Calculates remaining horizontal distance and starts
     * a flat bridge to cover it.
     *
     * @return the bridge tick result for this transition tick
     */
    @Nonnull
    private BridgeTickResult transitionToFlatBridge() {
        Location botLoc = bot.getLocation();
        if (botLoc == null) {
            stopBridge();
            return BridgeTickResult.COMPLETE;
        }

        double remainingHorizDist = MathUtil.horizontalDistance(botLoc, destination);
        if (remainingHorizDist < 2.0) {
            // Close enough horizontally — we're done
            stopBridge();
            return BridgeTickResult.COMPLETE;
        }

        // Recalculate direction from current position
        bridgeDirection = new Vector(
                destination.getX() - botLoc.getX(),
                0,
                destination.getZ() - botLoc.getZ()
        ).normalize();

        // Switch to flat bridging
        boolean isDiagonal = isDiagonalDirection(bridgeDirection);
        activeStrategy = selectFlatStrategy(isDiagonal);
        if (activeStrategy == null) {
            stopBridge();
            return BridgeTickResult.COMPLETE;
        }

        activeStrategy.initialize(bot, botLoc, bridgeDirection);
        inAscendingPhase = false;

        if (bot.getProfile().isDebugMode()) {
            bot.getPlugin().getLogger().info("[DEBUG] " + bot.getName()
                    + " transitioned from ascending to flat bridge with "
                    + activeStrategy.getName() + " (" + String.format("%.1f", remainingHorizDist)
                    + " blocks remaining)");
        }

        return BridgeTickResult.IN_PROGRESS;
    }

    /**
     * Handles ascending bridge failure by attempting to switch to flat bridging
     * if possible, or failing the entire bridge operation.
     *
     * @return the bridge tick result
     */
    @Nonnull
    private BridgeTickResult handleAscendingFailure() {
        Location botLoc = bot.getLocation();
        if (botLoc == null) {
            stopBridge();
            return BridgeTickResult.FAILED;
        }

        // If we gained some height, try continuing with flat bridging
        double horizDist = MathUtil.horizontalDistance(botLoc, destination);
        if (horizDist > 2.0) {
            bridgeDirection = new Vector(
                    destination.getX() - botLoc.getX(),
                    0,
                    destination.getZ() - botLoc.getZ()
            ).normalize();

            boolean isDiagonal = isDiagonalDirection(bridgeDirection);
            BridgeStrategy flatStrategy = selectFlatStrategy(isDiagonal);
            if (flatStrategy != null) {
                activeStrategy = flatStrategy;
                activeStrategy.initialize(bot, botLoc, bridgeDirection);
                inAscendingPhase = false;

                if (bot.getProfile().isDebugMode()) {
                    bot.getPlugin().getLogger().info("[DEBUG] " + bot.getName()
                            + " ascending failed, falling back to flat bridge");
                }

                return BridgeTickResult.IN_PROGRESS;
            }
        }

        stopBridge();
        return BridgeTickResult.FAILED;
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
        inAscendingPhase = false;
        destination = null;
        bridgeDirection = null;
        bridgeTicks = 0;
        heightDifference = 0;

        // Stop sneaking when bridge ends
        MovementController mc = bot.getMovementController();
        if (mc != null) {
            mc.setSneaking(false);
            mc.setMovingBackward(false);
            mc.setMovingForward(false);
        }

        if (bot.getProfile().isDebugMode()) {
            bot.getPlugin().getLogger().info("[DEBUG] " + bot.getName() + " stopped bridging");
        }
    }

    /**
     * Selects the best flat bridge strategy for the current bot based on difficulty
     * and whether the bridge direction is diagonal.
     *
     * @param isDiagonal whether the bridge is at a diagonal angle
     * @return the selected strategy, or null if none available
     */
    @Nullable
    private BridgeStrategy selectFlatStrategy(boolean isDiagonal) {
        DifficultyProfile diff = bot.getDifficultyProfile();
        String maxType = diff.getBridgeMaxType();
        int maxLevel = BRIDGE_TYPE_LEVELS.getOrDefault(maxType, 0);

        // If diagonal, prefer diagonal bridge strategy if available
        if (isDiagonal) {
            for (BridgeStrategy strat : flatStrategies) {
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
        for (BridgeStrategy strat : flatStrategies) {
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

    /** @return true if currently in the ascending phase of a bridge */
    public boolean isAscending() { return inAscendingPhase; }

    /** @return the currently active strategy, or null */
    @Nullable
    public BridgeStrategy getActiveStrategy() { return activeStrategy; }

    /** @return the bridge destination, or null */
    @Nullable
    public Location getDestination() { return destination; }

    /**
     * Returns the total number of blocks placed across all phases (ascending + flat).
     *
     * @return total blocks placed
     */
    public int getTotalBlocksPlaced() {
        return activeStrategy != null ? activeStrategy.getBlocksPlaced() : 0;
    }

    /** @return the number of blocks placed by the current active strategy */
    public int getBlocksPlaced() {
        return activeStrategy != null ? activeStrategy.getBlocksPlaced() : 0;
    }

    /** @return ticks elapsed since bridge started */
    public int getBridgeTicks() { return bridgeTicks; }

    /** @return the bridge direction vector */
    @Nullable
    public Vector getBridgeDirection() { return bridgeDirection; }

    /** @return the height difference between bot and destination */
    public int getHeightDifference() { return heightDifference; }

    /** @return all registered flat strategies (read-only) */
    @Nonnull
    public List<BridgeStrategy> getStrategies() {
        return Collections.unmodifiableList(flatStrategies);
    }

    /**
     * Registers a custom bridge strategy.
     *
     * @param strategy the strategy to register
     */
    public void registerStrategy(@Nonnull BridgeStrategy strategy) {
        flatStrategies.add(strategy);
        // Re-sort by base speed
        flatStrategies.sort(Comparator.comparingDouble(BridgeStrategy::getBaseSpeed));
    }

    private static String formatLoc(Location loc) {
        return String.format("(%.1f, %.1f, %.1f)", loc.getX(), loc.getY(), loc.getZ());
    }

    /**
     * Returns whether the bridge engine is currently active (performing a bridge).
     * This is an alias for {@link #isBridging()} for API consistency with other engines.
     *
     * @return true if actively bridging
     */
    public boolean isActive() {
        return bridging;
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
