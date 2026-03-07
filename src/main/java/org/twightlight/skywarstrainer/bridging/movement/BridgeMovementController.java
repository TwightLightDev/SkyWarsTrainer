package org.twightlight.skywarstrainer.bridging.movement;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.twightlight.skywarstrainer.awareness.ThreatMap;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.bridging.strategies.BridgeStrategy;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.movement.MovementController;
import org.twightlight.skywarstrainer.util.DebugLogger;
import org.twightlight.skywarstrainer.util.MathUtil;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Sits between BridgeEngine and MovementController to add a movement
 * behavior layer on top of bridge strategies.
 *
 * <p>The bridge strategy controls WHAT blocks are placed; this controller
 * controls HOW the bot physically moves while placing them. It manages
 * jump bridging, stair climbing, bait bridging, and safety rail placement.</p>
 */
public class BridgeMovementController {

    private final TrainerBot bot;

    /** The currently active movement type. */
    private BridgeMovementType activeMovementType;

    /** The selector used to pick the best movement type. */
    private final BridgeMovementSelector selector;

    // ── Bait Bridge State ──
    private int baitBlocksPlaced;
    private int baitWaitTicks;
    private boolean baitWaiting;
    private boolean baitTriggered;

    // ── Jump Bridge State ──
    private boolean jumpBridgeJumping;
    private int jumpBridgeCooldown;

    // ── Safety Rail State ──
    private int blocksSinceLastRail;
    private static final int RAIL_INTERVAL_MIN = 3;
    private static final int RAIL_INTERVAL_MAX = 5;
    private int nextRailAt;

    // ── Stair Climb State ──
    private int stairsBuilt;
    private int targetStairs;

    /**
     * Creates a new BridgeMovementController for the given bot.
     *
     * @param bot the owning trainer bot
     */
    public BridgeMovementController(@Nonnull TrainerBot bot) {
        this.bot = bot;
        this.selector = new BridgeMovementSelector();
        this.activeMovementType = BridgeMovementType.SAFE_SNEAK;
        reset();
    }

    /**
     * Selects the best movement type for the current situation. Called when
     * a new bridge starts or when conditions change significantly.
     *
     * @param destination the bridge destination
     */
    public void selectMovement(@Nullable Location destination) {
        this.activeMovementType = selector.selectMovement(bot, destination);
        reset();
        DebugLogger.log(bot, "Bridge movement selected: %s", activeMovementType.name());
    }

    /**
     * Pre-processes movement before the bridge strategy ticks.
     * Called every bridge tick BEFORE activeStrategy.tick().
     *
     * <p>This can modify the bot's movement state (sneaking, sprinting,
     * jumping) which the bridge strategy then operates within.</p>
     *
     * @param strategy the active bridge strategy
     */
    public void preProcess(@Nonnull BridgeStrategy strategy) {
        if (activeMovementType == null) return;

        MovementController mc = bot.getMovementController();
        if (mc == null) return;

        switch (activeMovementType) {
            case JUMP_BRIDGE:
                preProcessJumpBridge(mc);
                break;
            case STAIR_CLIMB:
                preProcessStairClimb(mc);
                break;
            case BAIT_BRIDGE:
                preProcessBaitBridge(mc);
                break;
            case SPEED_SPRINT:
                preProcessSpeedSprint(mc);
                break;
            case SAFETY_RAIL:
            case SAFE_SNEAK:
            default:
                // No pre-processing needed; let strategy handle normally
                break;
        }
    }

    /**
     * Post-processes movement after the bridge strategy ticks.
     * Called every bridge tick AFTER activeStrategy.tick().
     *
     * <p>Handles safety rail placement, jump bridge landing, and
     * other overlay behaviors that happen after normal block placement.</p>
     *
     * @param strategy the active bridge strategy
     */
    public void postProcess(@Nonnull BridgeStrategy strategy) {
        if (activeMovementType == null) return;

        switch (activeMovementType) {
            case SAFETY_RAIL:
                postProcessSafetyRail(strategy);
                break;
            case JUMP_BRIDGE:
                postProcessJumpBridge();
                break;
            case BAIT_BRIDGE:
                postProcessBaitBridge();
                break;
            default:
                break;
        }
    }

    // ─── Jump Bridge ────────────────────────────────────────────

    /**
     * Pre-processes jump bridge movement: sprint toward bridge direction,
     * jump at block edge, aim downward mid-air for placement.
     */
    private void preProcessJumpBridge(@Nonnull MovementController mc) {
        if (jumpBridgeCooldown > 0) {
            jumpBridgeCooldown--;
            return;
        }

        LivingEntity entity = bot.getLivingEntity();
        if (entity == null) return;

        // Sprint forward
        mc.getSprintController().startSprinting();
        mc.setSneaking(false);

        // Check if at block edge — if so, jump
        Location botLoc = entity.getLocation();
        Block below = botLoc.clone().add(0, -1, 0).getBlock();
        Block aheadBelow = botLoc.clone().add(
                mc.getForwardDirection().getX() * 0.6, -1,
                mc.getForwardDirection().getZ() * 0.6
        ).getBlock();

        if (below.getType() != Material.AIR && aheadBelow.getType() == Material.AIR) {
            // At edge — jump and set cooldown
            mc.getJumpController().jump();
            jumpBridgeJumping = true;
            jumpBridgeCooldown = 8; // 8 ticks between jump bridge cycles

            // Apply fail rate check
            DifficultyProfile diff = bot.getDifficultyProfile();
            double failRate = diff.getBridgeFailRate() * BridgeMovementType.JUMP_BRIDGE.getFailRateMultiplier();
            if (RandomUtil.chance(failRate)) {
                // Simulated miss: don't place the block (strategy will handle the miss)
                DebugLogger.log(bot, "Jump bridge: simulated placement miss");
            }
        }
    }

    private void postProcessJumpBridge() {
        LivingEntity entity = bot.getLivingEntity();
        if (entity == null) return;

        // If mid-air after jump bridge, aim downward for placement
        if (jumpBridgeJumping && entity.getVelocity().getY() < 0) {
            // Descending — look down to place block
            MovementController mc = bot.getMovementController();
            if (mc != null) {
                mc.setCurrentPitch(75.0f); // Look sharply down
            }
            jumpBridgeJumping = false;
        }
    }

    // ─── Stair Climb ────────────────────────────────────────────

    /**
     * Pre-processes stair climb: jump and look down to place block under feet.
     */
    private void preProcessStairClimb(@Nonnull MovementController mc) {
        LivingEntity entity = bot.getLivingEntity();
        if (entity == null) return;

        // Check if bot is on the ground
        if (entity.isOnGround() && stairsBuilt < targetStairs) {
            DifficultyProfile diff = bot.getDifficultyProfile();

            // Skill check: can we successfully place under feet?
            if (RandomUtil.chance(diff.getStairBridgeSkill())) {
                mc.getJumpController().jump();
                // The bridge strategy's tick will handle the actual block placement
                // We set the pitch to look down so placement goes under feet
                mc.setCurrentPitch(90.0f); // Straight down
                stairsBuilt++;
            } else {
                // Failed stair attempt — minor delay
                DebugLogger.log(bot, "Stair climb: missed step %d/%d", stairsBuilt, targetStairs);
            }
        }
    }

    // ─── Bait Bridge ────────────────────────────────────────────

    /**
     * Pre-processes bait bridge: place a few blocks, then stop and wait.
     */
    private void preProcessBaitBridge(@Nonnull MovementController mc) {
        if (baitWaiting) {
            // Waiting phase — freeze movement, watch for enemy response
            mc.setSneaking(true);
            mc.setMovingForward(false);
            mc.setMovingBackward(false);
            return;
        }

        // If we've placed enough bait blocks, switch to waiting
        if (baitBlocksPlaced >= RandomUtil.nextInt(1, 3) && !baitTriggered) {
            baitWaiting = true;
            baitWaitTicks = RandomUtil.nextInt(20, 40);
            DebugLogger.log(bot, "Bait bridge: placed %d blocks, waiting %d ticks",
                    baitBlocksPlaced, baitWaitTicks);
        }
    }

    /**
     * Post-processes bait bridge: count placed blocks and manage wait timer.
     */
    private void postProcessBaitBridge() {
        if (baitWaiting) {
            baitWaitTicks--;

            // Check if enemy committed (started running toward bot on bridge)
            ThreatMap threatMap = bot.getThreatMap();
            if (threatMap != null) {
                ThreatMap.ThreatEntry nearest = threatMap.getNearestThreat();
                if (nearest != null && nearest.currentPosition != null) {
                    Location botLoc = bot.getLocation();
                    if (botLoc != null) {
                        double dist = botLoc.distance(nearest.currentPosition);
                        // If enemy is approaching on the bridge (close + moving toward us)
                        if (dist < 10 && nearest.getHorizontalSpeed() > 0.1) {
                            DebugLogger.log(bot, "Bait bridge: enemy committed! Distance: %.1f", dist);
                            baitTriggered = true;
                            baitWaiting = false;
                            // The behavior tree will handle the response (fight or retreat)
                            return;
                        }
                    }
                }
            }

            // If wait timer expired, resume normal bridging
            if (baitWaitTicks <= 0) {
                baitWaiting = false;
                baitTriggered = false;
                DebugLogger.log(bot, "Bait bridge: enemy didn't commit, resuming bridge");
                // Switch to normal movement so bridging continues
                activeMovementType = BridgeMovementType.SAFE_SNEAK;
            }
        }
    }

    // ─── Speed Sprint ───────────────────────────────────────────

    /**
     * Pre-processes speed sprint: sprint between placements, sneak only
     * right at the block edge for placement timing.
     */
    private void preProcessSpeedSprint(@Nonnull MovementController mc) {
        LivingEntity entity = bot.getLivingEntity();
        if (entity == null) return;

        Location botLoc = entity.getLocation();
        // Check distance to block edge
        double xFrac = botLoc.getX() - Math.floor(botLoc.getX());
        double zFrac = botLoc.getZ() - Math.floor(botLoc.getZ());

        // Near block edge (last 0.3 of block) → sneak for placement
        boolean nearEdge = (xFrac > 0.7 || xFrac < 0.3) || (zFrac > 0.7 || zFrac < 0.3);

        if (nearEdge) {
            mc.setSneaking(true);
            mc.getSprintController().stopSprinting();
        } else {
            mc.setSneaking(false);
            mc.getSprintController().startSprinting();
        }
    }

    // ─── Safety Rail ────────────────────────────────────────────

    /**
     * Post-processes safety rail: after normal block placement, periodically
     * place a block on the side of the bridge for protection.
     */
    private void postProcessSafetyRail(@Nonnull BridgeStrategy strategy) {
        blocksSinceLastRail++;

        if (blocksSinceLastRail >= nextRailAt) {
            blocksSinceLastRail = 0;
            nextRailAt = RandomUtil.nextInt(RAIL_INTERVAL_MIN, RAIL_INTERVAL_MAX);

            // Place a block on one side of the bridge
            Player player = bot.getPlayerEntity();
            if (player == null) return;

            Location botLoc = bot.getLocation();
            if (botLoc == null) return;

            MovementController mc = bot.getMovementController();
            if (mc == null) return;

            // Choose left or right side randomly
            org.bukkit.util.Vector right = mc.getRightDirection();
            double side = RandomUtil.nextBoolean() ? 1.0 : -1.0;

            Location railLoc = botLoc.clone().add(
                    right.getX() * side, 0, right.getZ() * side
            );

            Block railBlock = railLoc.getBlock();
            if (railBlock.getType() == Material.AIR) {
                // Find a buildable material in inventory
                Material buildMat = findBuildMaterial(player);
                if (buildMat != null) {
                    railBlock.setType(buildMat);
                    DebugLogger.log(bot, "Safety rail placed at (%d, %d, %d)",
                            railBlock.getX(), railBlock.getY(), railBlock.getZ());
                }
            }
        }
    }

    /**
     * Finds the first available building material in the player's inventory.
     */
    @Nullable
    private Material findBuildMaterial(@Nonnull Player player) {
        Material[] buildMats = {
                Material.COBBLESTONE, Material.STONE, Material.WOOL,
                Material.WOOD, Material.SANDSTONE, Material.DIRT
        };
        for (Material mat : buildMats) {
            if (player.getInventory().contains(mat)) return mat;
        }
        return null;
    }

    // ─── State Management ───────────────────────────────────────

    /**
     * Resets all movement state. Called when bridge starts or ends.
     */
    public void reset() {
        baitBlocksPlaced = 0;
        baitWaitTicks = 0;
        baitWaiting = false;
        baitTriggered = false;
        jumpBridgeJumping = false;
        jumpBridgeCooldown = 0;
        blocksSinceLastRail = 0;
        nextRailAt = RandomUtil.nextInt(RAIL_INTERVAL_MIN, RAIL_INTERVAL_MAX);
        stairsBuilt = 0;
        targetStairs = RandomUtil.nextInt(3, 8);
    }

    /**
     * Notifies the controller that a block was placed by the strategy.
     * Used for bait bridge counting and safety rail interval tracking.
     */
    public void onBlockPlaced() {
        if (activeMovementType == BridgeMovementType.BAIT_BRIDGE) {
            baitBlocksPlaced++;
        }
    }

    // ─── Accessors ──────────────────────────────────────────────

    /** @return the currently active movement type */
    @Nonnull
    public BridgeMovementType getActiveMovementType() {
        return activeMovementType != null ? activeMovementType : BridgeMovementType.SAFE_SNEAK;
    }

    /** @return true if the bait bridge is in waiting mode */
    public boolean isBaitWaiting() { return baitWaiting; }

    /** @return true if the bait bridge was triggered (enemy committed) */
    public boolean isBaitTriggered() { return baitTriggered; }

    /** @return the selector used for movement type selection */
    @Nonnull
    public BridgeMovementSelector getSelector() { return selector; }
}
