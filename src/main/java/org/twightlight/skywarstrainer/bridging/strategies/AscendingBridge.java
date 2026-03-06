package org.twightlight.skywarstrainer.bridging.strategies;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.movement.MovementController;
import org.twightlight.skywarstrainer.util.MathUtil;
import org.twightlight.skywarstrainer.util.NMSHelper;
import org.twightlight.skywarstrainer.util.PacketUtil;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Ascending bridge: builds a staircase pattern to reach higher terrain.
 *
 * <p>In SkyWars, the bot frequently needs to travel upward — from lower spawn
 * islands to a higher mid island, or to gain height advantage during combat.
 * This strategy creates an ascending staircase by alternating between horizontal
 * bridge blocks and pillar-up jump placements.</p>
 *
 * <p>The staircase pattern places one block forward, then jumps and places a
 * block under the bot's feet at the new elevated position, creating a smooth
 * ascending path. Each "step" gains 1 block of height and 1-2 blocks of
 * horizontal distance.</p>
 *
 * <p>Speed varies by difficulty: slower bots build safer wider staircases,
 * while expert bots can rapidly staircase with minimal wasted blocks.</p>
 *
 * <h3>Per-step sequence:</h3>
 * <ol>
 *   <li>Sneak → look at the forward block edge → place bridge block</li>
 *   <li>Walk forward onto the new block</li>
 *   <li>Jump → look straight down → place block under feet at peak</li>
 *   <li>Land on the elevated block → repeat</li>
 * </ol>
 *
 * <p>Available at all difficulty levels. Lower difficulties build slower and may
 * occasionally miss jump-place timing. Higher difficulties staircase rapidly.</p>
 */
public class AscendingBridge implements org.twightlight.skywarstrainer.bridging.strategies.BridgeStrategy {

    /**
     * Phases within a single ascending step.
     */
    private enum Phase {
        /** Place a block forward at the current Y level. */
        PLACE_FORWARD,
        /** Walk onto the newly placed forward block. */
        WALK_FORWARD,
        /** Jump and aim downward to place block under feet. */
        JUMP_PLACE,
        /** Wait for landing on the elevated block. */
        LANDING,
        /** Brief transition before next step. */
        STEPPING
    }

    private Phase currentPhase;

    /** Direction to bridge in (XZ normalized, Y=0). */
    private Vector bridgeDirection;

    /** The current position reference for block placement. */
    private Location currentStepBase;

    /** Total blocks placed this bridge sequence. */
    private int blocksPlaced;

    /** Ticks spent in the current phase. */
    private int phaseTicks;

    /** Total ticks since bridge started. */
    private int totalTicks;

    /** The target height (Y level) we're ascending toward. */
    private int targetY;

    /** Whether ascending is complete (reached target height or above). */
    private boolean ascendComplete;

    /** Materials considered as building blocks, in priority order. */
    private static final Material[] BUILD_MATERIALS = {
            Material.COBBLESTONE, Material.STONE, Material.WOOL,
            Material.WOOD, Material.SANDSTONE, Material.DIRT,
            Material.SAND, Material.NETHERRACK
    };

    @Nonnull
    @Override
    public String getName() {
        return "AscendingBridge";
    }

    @Nonnull
    @Override
    public String getRequiredBridgeType() {
        // Ascending bridge is available at all difficulty levels; the normal
        // bridge skill is sufficient since the jump-place timing is simpler
        // than speed or ninja bridging.
        return "NORMAL";
    }

    @Override
    public double getBaseSpeed() {
        // Ascending is slower than flat bridging due to the jump cycle.
        return 2.0;
    }

    @Override
    public void initialize(@Nonnull TrainerBot bot, @Nonnull Location start,
                           @Nonnull Vector direction) {
        this.bridgeDirection = direction.clone().setY(0);
        if (this.bridgeDirection.lengthSquared() > 0.001) {
            this.bridgeDirection.normalize();
        } else {
            // If direction is purely vertical, default to the bot's facing direction
            Location botLoc = bot.getLocation();
            if (botLoc != null) {
                double yawRad = Math.toRadians(botLoc.getYaw());
                this.bridgeDirection = new Vector(-Math.sin(yawRad), 0, Math.cos(yawRad)).normalize();
            } else {
                this.bridgeDirection = new Vector(1, 0, 0);
            }
        }

        this.currentStepBase = start.getBlock().getLocation().clone().add(0.5, 0, 0.5);
        this.blocksPlaced = 0;
        this.currentPhase = Phase.PLACE_FORWARD;
        this.phaseTicks = 0;
        this.totalTicks = 0;
        this.ascendComplete = false;

        // Target Y is determined by the destination's Y level.
        // BridgeEngine passes the destination; we calculate the target height
        // from the vertical difference.
        this.targetY = start.getBlockY() + 64; // Default high; overridden by setTargetY

        // Start sneaking for safety during placement
        MovementController mc = bot.getMovementController();
        if (mc != null) {
            mc.setSneaking(true);
        }
    }

    /**
     * Sets the target Y level to ascend toward.
     * Called by BridgeEngine after initialization when the destination's Y is known.
     *
     * @param targetY the target Y level
     */
    public void setTargetY(int targetY) {
        this.targetY = targetY;
    }

    @Nonnull
    @Override
    public BridgeTickResult tick(@Nonnull TrainerBot bot) {
        Player player = bot.getPlayerEntity();
        MovementController mc = bot.getMovementController();
        if (player == null || mc == null) return BridgeTickResult.FAILED;

        DifficultyProfile diff = bot.getDifficultyProfile();
        totalTicks++;
        phaseTicks++;

        // Check if we've reached the target height
        Location botLoc = bot.getLocation();
        if (botLoc != null && botLoc.getBlockY() >= targetY) {
            ascendComplete = true;
            return BridgeTickResult.COMPLETE;
        }

        // Bridge fail rate check — simulate timing mistakes
        if (RandomUtil.chance(diff.getBridgeFailRate())) {
            if (phaseTicks > 15) {
                return BridgeTickResult.FAILED;
            }
            return BridgeTickResult.MOVING;
        }

        // Check for blocks
        Material blockType = findBuildBlock(player);
        if (blockType == null) return BridgeTickResult.FAILED;

        switch (currentPhase) {
            case PLACE_FORWARD:
                return tickPlaceForward(bot, mc, player, blockType);
            case WALK_FORWARD:
                return tickWalkForward(bot, mc);
            case JUMP_PLACE:
                return tickJumpPlace(bot, mc, player, blockType);
            case LANDING:
                return tickLanding(bot, mc);
            case STEPPING:
                return tickStepping(bot, mc, diff);
            default:
                return BridgeTickResult.FAILED;
        }
    }

    /**
     * Places a block one step forward at the current Y level.
     * This creates the horizontal component of the staircase.
     */
    @Nonnull
    private BridgeTickResult tickPlaceForward(@Nonnull TrainerBot bot,
                                              @Nonnull MovementController mc,
                                              @Nonnull Player player,
                                              @Nonnull Material blockType) {
        mc.setSneaking(true);

        Location botLoc = bot.getLocation();
        if (botLoc == null) return BridgeTickResult.FAILED;

        // Look toward the bridge direction and slightly down for placement
        float yaw = MathUtil.calculateYaw(botLoc, botLoc.clone().add(bridgeDirection));
        mc.setCurrentYaw(yaw + 180f); // Face backward (normal bridge style)
        mc.setCurrentPitch(75f + (float) RandomUtil.gaussian(0, 2));

        // Calculate the forward block position at current step height
        Location forwardPos = currentStepBase.clone()
                .add(bridgeDirection.clone().multiply(1.0));
        forwardPos.setY(currentStepBase.getY() - 1); // Block level is feet-1

        Block forwardBlock = forwardPos.getBlock();
        if (forwardBlock.getType() == Material.AIR) {
            if (hasAdjacentSolid(forwardBlock)) {
                forwardBlock.setType(blockType);
                removeOneBuildBlock(player, blockType);
                blocksPlaced++;
                PacketUtil.playArmSwing(player);
            } else {
                // No adjacent solid block — try placing against the block below
                Block below = forwardBlock.getRelative(BlockFace.DOWN);
                if (below.getType().isSolid()) {
                    forwardBlock.setType(blockType);
                    removeOneBuildBlock(player, blockType);
                    blocksPlaced++;
                    PacketUtil.playArmSwing(player);
                } else {
                    // Cannot place — we need a base. Place a support block below first.
                    if (phaseTicks > 10) return BridgeTickResult.FAILED;
                    return BridgeTickResult.MOVING;
                }
            }
        }

        currentPhase = Phase.WALK_FORWARD;
        phaseTicks = 0;
        return BridgeTickResult.PLACED;
    }

    /**
     * Walks forward onto the newly placed block.
     */
    @Nonnull
    private BridgeTickResult tickWalkForward(@Nonnull TrainerBot bot,
                                             @Nonnull MovementController mc) {
        mc.setSneaking(false); // Unsneak to walk normally

        Location botLoc = bot.getLocation();
        if (botLoc == null) return BridgeTickResult.FAILED;

        // Walk forward toward the placed block
        Location target = currentStepBase.clone().add(bridgeDirection.clone().multiply(1.0));
        target.setY(currentStepBase.getY());

        float yaw = MathUtil.calculateYaw(botLoc, target);
        mc.setCurrentYaw(yaw);
        mc.setCurrentPitch(0f);
        mc.setMoveTarget(target);

        // Check if we've arrived on the forward block
        double dist = MathUtil.horizontalDistance(botLoc, target);
        if (dist < 0.5 || phaseTicks > 12) {
            // Update the step base to the new forward position
            currentStepBase.add(bridgeDirection.clone().multiply(1.0));

            currentPhase = Phase.JUMP_PLACE;
            phaseTicks = 0;
            mc.setMoveTarget(null);
        }

        return BridgeTickResult.MOVING;
    }

    /**
     * Jumps and places a block under the bot's feet at the jump peak,
     * elevating by one block.
     */
    @Nonnull
    private BridgeTickResult tickJumpPlace(@Nonnull TrainerBot bot,
                                           @Nonnull MovementController mc,
                                           @Nonnull Player player,
                                           @Nonnull Material blockType) {
        Location botLoc = bot.getLocation();
        if (botLoc == null) return BridgeTickResult.FAILED;

        if (phaseTicks == 1) {
            // Initiate jump
            mc.setSneaking(false);
            mc.getJumpController().jump();
            mc.setCurrentPitch(90f); // Look straight down for placement
            return BridgeTickResult.MOVING;
        }

        if (phaseTicks >= 3 && phaseTicks <= 6) {
            // At or near jump peak — attempt to place block under feet
            Block footBlock = botLoc.getBlock().getRelative(BlockFace.DOWN);

            if (footBlock.getType() == Material.AIR && hasAdjacentSolid(footBlock)) {
                footBlock.setType(blockType);
                removeOneBuildBlock(player, blockType);
                blocksPlaced++;
                PacketUtil.playArmSwing(player);

                // Update step base Y to the new elevated position
                currentStepBase.setY(currentStepBase.getY() + 1);

                currentPhase = Phase.LANDING;
                phaseTicks = 0;
                return BridgeTickResult.PLACED;
            }

            // If the block below feet is already solid (we landed on something),
            // update position and move on
            if (footBlock.getType().isSolid()) {
                currentStepBase.setY(botLoc.getBlockY());
                currentPhase = Phase.LANDING;
                phaseTicks = 0;
                return BridgeTickResult.MOVING;
            }
        }

        if (phaseTicks > 12) {
            // Jump-place failed — might have fallen or missed timing
            // Check if we're still on solid ground
            if (NMSHelper.isOnGround(bot.getLivingEntity())) {
                currentPhase = Phase.STEPPING;
                phaseTicks = 0;
                return BridgeTickResult.MOVING;
            }
            return BridgeTickResult.FAILED;
        }

        return BridgeTickResult.MOVING;
    }

    /**
     * Waits for the bot to land on the newly elevated block.
     */
    @Nonnull
    private BridgeTickResult tickLanding(@Nonnull TrainerBot bot,
                                         @Nonnull MovementController mc) {
        if (NMSHelper.isOnGround(bot.getLivingEntity()) || phaseTicks > 10) {
            mc.setCurrentPitch(0f); // Reset pitch from looking down
            currentPhase = Phase.STEPPING;
            phaseTicks = 0;
        }
        return BridgeTickResult.MOVING;
    }

    /**
     * Brief transition between ascending steps, controlled by bridge speed.
     */
    @Nonnull
    private BridgeTickResult tickStepping(@Nonnull TrainerBot bot,
                                          @Nonnull MovementController mc,
                                          @Nonnull DifficultyProfile diff) {
        double ticksPerBlock = 20.0 / Math.max(1.0, diff.getBridgeSpeed());
        int steppingTicks = Math.max(2, (int) Math.round(ticksPerBlock * 0.3));

        if (phaseTicks >= steppingTicks) {
            currentPhase = Phase.PLACE_FORWARD;
            phaseTicks = 0;
        }
        return BridgeTickResult.MOVING;
    }

    // ─── Utility Methods ────────────────────────────────────────

    /**
     * Checks if any face of the block has an adjacent solid block.
     */
    private boolean hasAdjacentSolid(@Nonnull Block block) {
        BlockFace[] faces = {BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH,
                BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
        for (BlockFace face : faces) {
            if (block.getRelative(face).getType().isSolid()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Finds the first available building block in the player's inventory.
     */
    @Nullable
    private Material findBuildBlock(@Nonnull Player player) {
        for (Material mat : BUILD_MATERIALS) {
            if (player.getInventory().contains(mat)) {
                return mat;
            }
        }
        return null;
    }

    /**
     * Removes one building block from the player's inventory.
     */
    private void removeOneBuildBlock(@Nonnull Player player, @Nonnull Material material) {
        player.getInventory().removeItem(new ItemStack(material, 1));
    }

    /** @return true if ascending is complete (reached target height) */
    public boolean isAscendComplete() {
        return ascendComplete;
    }

    @Override
    public void reset() {
        currentPhase = Phase.PLACE_FORWARD;
        phaseTicks = 0;
        totalTicks = 0;
        blocksPlaced = 0;
        bridgeDirection = null;
        currentStepBase = null;
        ascendComplete = false;
    }

    @Override
    public int getBlocksPlaced() {
        return blocksPlaced;
    }
}
