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
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Normal shift-bridge: the safest and slowest bridging technique.
 *
 * <p>The bot sneaks (holds shift) and walks backward to the edge of the current
 * block, then looks down at the block edge to place a new block. It then steps
 * backward onto the new block and repeats. The bot holds shift the entire time,
 * making it impossible to fall off (shift prevents walking off edges).</p>
 *
 * <p>Speed: ~1.5-3 blocks/sec depending on loot speed multiplier and bridge speed.
 * Available at all difficulty levels. This is the fallback strategy when the bot's
 * difficulty doesn't support advanced techniques.</p>
 *
 * <h3>Per-block sequence:</h3>
 * <ol>
 *   <li>Enable sneaking</li>
 *   <li>Walk backward toward the bridge edge</li>
 *   <li>Look down at ~75° pitch toward the block face to place against</li>
 *   <li>Place block (right-click)</li>
 *   <li>Step backward onto the new block</li>
 *   <li>Repeat</li>
 * </ol>
 */
public class NormalBridge implements org.twightlight.skywarstrainer.bridging.strategies.BridgeStrategy {

    /**
     * Phases within a single block placement.
     */
    private enum Phase {
        /** Walking backward toward the current block's edge. */
        WALKING_TO_EDGE,
        /** Adjusting look angle downward for placement. */
        AIMING,
        /** Placing the block. */
        PLACING,
        /** Stepping backward onto the newly placed block. */
        STEPPING
    }

    private Phase currentPhase;

    /** Direction to bridge in (XZ normalized). */
    private Vector bridgeDirection;

    /** The position where the next block should be placed. */
    private Location nextBlockPos;

    /** Total blocks placed this bridge sequence. */
    private int blocksPlaced;

    /** Ticks spent in the current phase. */
    private int phaseTicks;

    /** The starting position of the bridge. */
    private Location startPos;

    /** Materials considered as building blocks, in priority order. */
    private static final Material[] BUILD_MATERIALS = {
            Material.COBBLESTONE, Material.STONE, Material.WOOL,
            Material.WOOD, Material.SANDSTONE, Material.DIRT,
            Material.SAND, Material.NETHERRACK
    };

    @Nonnull
    @Override
    public String getName() {
        return "NormalBridge";
    }

    @Nonnull
    @Override
    public String getRequiredBridgeType() {
        return "NORMAL";
    }

    @Override
    public double getBaseSpeed() {
        return 2.5; // ~2.5 blocks per second base
    }

    @Override
    public void initialize(@Nonnull TrainerBot bot, @Nonnull Location start,
                           @Nonnull Vector direction) {
        this.startPos = start.clone();
        this.bridgeDirection = direction.clone().setY(0).normalize();
        this.blocksPlaced = 0;
        this.currentPhase = Phase.WALKING_TO_EDGE;
        this.phaseTicks = 0;

        // Calculate the first block position (one block in the bridge direction
        // from the bot's current block)
        this.nextBlockPos = start.getBlock().getLocation().clone()
                .add(bridgeDirection.clone().multiply(1.0))
                .add(0.5, -1, 0.5); // Center of block, at foot level - 1

        // Start sneaking immediately
        MovementController mc = bot.getMovementController();
        if (mc != null) {
            mc.setSneaking(true);
        }
    }

    @Nonnull
    @Override
    public BridgeTickResult tick(@Nonnull TrainerBot bot) {
        Player player = bot.getPlayerEntity();
        MovementController mc = bot.getMovementController();
        if (player == null || mc == null) return BridgeTickResult.FAILED;

        DifficultyProfile diff = bot.getDifficultyProfile();

        // Ensure sneaking is active
        mc.setSneaking(true);

        // Check if we have blocks to place
        Material blockType = findBuildBlock(player);
        if (blockType == null) return BridgeTickResult.FAILED;

        // Bridge fail rate check
        if (RandomUtil.chance(diff.getBridgeFailRate())) {
            // Simulate a placement mistake — skip this tick
            phaseTicks++;
            if (phaseTicks > 10) {
                // Took too long — might have fallen
                return BridgeTickResult.FAILED;
            }
            return BridgeTickResult.MOVING;
        }

        phaseTicks++;

        switch (currentPhase) {
            case WALKING_TO_EDGE:
                return tickWalkToEdge(bot, mc);

            case AIMING:
                return tickAiming(bot, mc, player);

            case PLACING:
                return tickPlacing(bot, mc, player, blockType);

            case STEPPING:
                return tickStepping(bot, mc);

            default:
                return BridgeTickResult.FAILED;
        }
    }

    /**
     * Walk backward toward the edge of the current block.
     */
    @Nonnull
    private BridgeTickResult tickWalkToEdge(@Nonnull TrainerBot bot,
                                            @Nonnull MovementController mc) {
        // Calculate the edge position — the block edge in the bridge direction
        Location botLoc = bot.getLocation();
        if (botLoc == null) return BridgeTickResult.FAILED;

        // Look backward (opposite of bridge direction)
        Vector lookDir = bridgeDirection.clone().multiply(-1);
        float yaw = MathUtil.calculateYaw(botLoc, botLoc.clone().add(bridgeDirection));
        // We're walking backward, so face away from bridge direction
        mc.setCurrentYaw(yaw + 180f);
        mc.setCurrentPitch(0f);

        // Move backward (in bridge direction — since we're facing opposite, backward = forward in bridge dir)
        Location edgeTarget = botLoc.clone().add(bridgeDirection.clone().multiply(0.3));
        mc.setMoveTarget(edgeTarget);

        // Check if we're at the edge of the current block
        Block belowNext = nextBlockPos.getBlock();
        double distToEdge = MathUtil.horizontalDistance(botLoc, nextBlockPos.clone().add(0, 1, 0));

        // Transition when we're close enough to the edge to place
        if (distToEdge < 1.5 || phaseTicks > 15) {
            currentPhase = Phase.AIMING;
            phaseTicks = 0;
        }

        return BridgeTickResult.MOVING;
    }

    /**
     * Aim down at the block face where we'll place.
     */
    @Nonnull
    private BridgeTickResult tickAiming(@Nonnull TrainerBot bot,
                                        @Nonnull MovementController mc,
                                        @Nonnull Player player) {
        // Look down at roughly 75° pitch toward the block placement spot
        Location botLoc = bot.getLocation();
        if (botLoc == null) return BridgeTickResult.FAILED;

        float targetPitch = 75f + (float) RandomUtil.gaussian(0, 3);
        float currentPitch = mc.getCurrentPitch();

        // Smooth interpolation toward target pitch
        DifficultyProfile diff = bot.getDifficultyProfile();
        double speed = diff.getAimSpeedDegPerTick();
        float newPitch = (float) MathUtil.exponentialSmooth(currentPitch, targetPitch,
                MathUtil.clamp(speed / 30.0, 0.3, 0.9));
        mc.setCurrentPitch(newPitch);

        // Once pitch is close enough, place
        if (Math.abs(newPitch - targetPitch) < 5.0 || phaseTicks > 8) {
            currentPhase = Phase.PLACING;
            phaseTicks = 0;
        }

        return BridgeTickResult.MOVING;
    }

    /**
     * Place a block at the calculated position.
     */
    @Nonnull
    private BridgeTickResult tickPlacing(@Nonnull TrainerBot bot,
                                         @Nonnull MovementController mc,
                                         @Nonnull Player player,
                                         @Nonnull Material blockType) {
        // Place the block
        Block target = nextBlockPos.getBlock();

        if (target.getType() != Material.AIR) {
            // Block already occupied — advance to next position
            advanceNextBlock();
            currentPhase = Phase.STEPPING;
            phaseTicks = 0;
            return BridgeTickResult.PLACED;
        }

        // Check for an adjacent solid block to place against
        if (!hasAdjacentSolid(target)) {
            // No surface to place against — try below
            Block below = target.getRelative(BlockFace.DOWN);
            if (below.getType().isSolid()) {
                target.setType(blockType);
                removeOneBuildBlock(player, blockType);
                blocksPlaced++;
                advanceNextBlock();
                currentPhase = Phase.STEPPING;
                phaseTicks = 0;
                return BridgeTickResult.PLACED;
            }
            return BridgeTickResult.FAILED; // Can't place here
        }

        target.setType(blockType);
        removeOneBuildBlock(player, blockType);
        blocksPlaced++;

        // Play arm swing animation
        org.twightlight.skywarstrainer.util.PacketUtil.playArmSwing(player);

        advanceNextBlock();
        currentPhase = Phase.STEPPING;
        phaseTicks = 0;

        return BridgeTickResult.PLACED;
    }

    /**
     * Step onto the newly placed block.
     */
    @Nonnull
    private BridgeTickResult tickStepping(@Nonnull TrainerBot bot,
                                          @Nonnull MovementController mc) {
        // Calculate the speed in ticks-per-block from bridgeSpeed parameter
        DifficultyProfile diff = bot.getDifficultyProfile();
        double ticksPerBlock = 20.0 / Math.max(1.0, diff.getBridgeSpeed());
        int steppingTicks = Math.max(2, (int) Math.round(ticksPerBlock * 0.4));

        if (phaseTicks >= steppingTicks) {
            currentPhase = Phase.WALKING_TO_EDGE;
            phaseTicks = 0;
        }

        return BridgeTickResult.MOVING;
    }

    /**
     * Advances the next block position by one block in the bridge direction.
     */
    private void advanceNextBlock() {
        nextBlockPos.add(bridgeDirection.clone().multiply(1.0));
    }

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

    @Override
    public void reset() {
        currentPhase = Phase.WALKING_TO_EDGE;
        phaseTicks = 0;
        blocksPlaced = 0;
        bridgeDirection = null;
        nextBlockPos = null;
        startPos = null;
    }

    @Override
    public int getBlocksPlaced() {
        return blocksPlaced;
    }
}
