// ═══════════════════════════════════════════════════════════════════
// File: src/main/java/org/twightlight/skywarstrainer/bridging/strategies/DiagonalBridge.java
// ═══════════════════════════════════════════════════════════════════
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
import org.twightlight.skywarstrainer.util.PacketUtil;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Diagonal bridge: bridges at 45° angle for diagonal targets.
 *
 * <p>When the bridge destination is diagonal (not axis-aligned), this strategy
 * uses an alternating block placement pattern. Two blocks are placed per "step"
 * — one in the X direction and one in the Z direction — creating a staircase-like
 * diagonal path.</p>
 *
 * <p>Speed: ~3-5 blocks/sec depending on difficulty. Available at MEDIUM+ difficulty.
 * Uses sneaking for safety like NormalBridge, but with the diagonal pattern.</p>
 */
public class DiagonalBridge implements org.twightlight.skywarstrainer.bridging.strategies.BridgeStrategy {

    private enum Phase {
        WALKING_TO_EDGE,
        AIMING,
        PLACING_BLOCK_A,
        PLACING_BLOCK_B,
        STEPPING
    }

    private Phase currentPhase;
    private Vector bridgeDirection;
    private Location nextBlockPosA; // First block (X direction)
    private Location nextBlockPosB; // Second block (Z direction)
    private int blocksPlaced;
    private int phaseTicks;

    /** Whether to place block A or B next. Alternates for the staircase pattern. */
    private boolean placeA;

    /** Individual X and Z step vectors for diagonal movement. */
    private Vector stepX;
    private Vector stepZ;

    private static final Material[] BUILD_MATERIALS = {
            Material.COBBLESTONE, Material.STONE, Material.WOOL,
            Material.WOOD, Material.SANDSTONE, Material.DIRT,
            Material.SAND, Material.NETHERRACK
    };

    @Nonnull
    @Override
    public String getName() {
        return "DiagonalBridge";
    }

    @Nonnull
    @Override
    public String getRequiredBridgeType() {
        return "SPEED"; // Requires at least speed bridge skill level
    }

    @Override
    public double getBaseSpeed() {
        return 3.5;
    }

    @Override
    public void initialize(@Nonnull TrainerBot bot, @Nonnull Location start,
                           @Nonnull Vector direction) {
        this.bridgeDirection = direction.clone().setY(0).normalize();
        this.blocksPlaced = 0;
        this.currentPhase = Phase.WALKING_TO_EDGE;
        this.phaseTicks = 0;
        this.placeA = true;

        // Decompose diagonal direction into X and Z steps
        double absX = Math.abs(bridgeDirection.getX());
        double absZ = Math.abs(bridgeDirection.getZ());

        this.stepX = new Vector(Math.signum(bridgeDirection.getX()), 0, 0);
        this.stepZ = new Vector(0, 0, Math.signum(bridgeDirection.getZ()));

        // Calculate initial block positions
        Location baseBlock = start.getBlock().getLocation().clone().add(0.5, -1, 0.5);
        this.nextBlockPosA = baseBlock.clone().add(stepX);
        this.nextBlockPosB = baseBlock.clone().add(stepX).add(stepZ);

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

        if (RandomUtil.chance(diff.getBridgeFailRate() * 1.3)) {
            phaseTicks++;
            if (phaseTicks > 12) return BridgeTickResult.FAILED;
            return BridgeTickResult.MOVING;
        }

        Material blockType = findBuildBlock(player);
        if (blockType == null) return BridgeTickResult.FAILED;

        phaseTicks++;
        mc.setSneaking(true);

        switch (currentPhase) {
            case WALKING_TO_EDGE:
                return tickWalkToEdge(bot, mc);
            case AIMING:
                return tickAiming(bot, mc);
            case PLACING_BLOCK_A:
                return tickPlaceBlock(bot, mc, player, blockType, nextBlockPosA, Phase.PLACING_BLOCK_B);
            case PLACING_BLOCK_B:
                return tickPlaceBlock(bot, mc, player, blockType, nextBlockPosB, Phase.STEPPING);
            case STEPPING:
                return tickStepping(bot, mc, diff);
            default:
                return BridgeTickResult.FAILED;
        }
    }

    @Nonnull
    private BridgeTickResult tickWalkToEdge(@Nonnull TrainerBot bot,
                                            @Nonnull MovementController mc) {
        Location botLoc = bot.getLocation();
        if (botLoc == null) return BridgeTickResult.FAILED;

        float yaw = MathUtil.calculateYaw(botLoc, botLoc.clone().add(bridgeDirection));
        mc.setCurrentYaw(yaw + 180f);
        mc.setCurrentPitch(0f);

        Location target = botLoc.clone().add(bridgeDirection.clone().multiply(0.2));
        mc.setMoveTarget(target, MovementController.MovementAuthority.BRIDGE);

        if (phaseTicks > 10) {
            currentPhase = Phase.AIMING;
            phaseTicks = 0;
        }
        return BridgeTickResult.MOVING;
    }

    @Nonnull
    private BridgeTickResult tickAiming(@Nonnull TrainerBot bot,
                                        @Nonnull MovementController mc) {
        mc.setCurrentPitch(75f + (float) RandomUtil.gaussian(0, 2));

        if (phaseTicks >= 3) {
            currentPhase = Phase.PLACING_BLOCK_A;
            phaseTicks = 0;
        }
        return BridgeTickResult.MOVING;
    }

    @Nonnull
    private BridgeTickResult tickPlaceBlock(@Nonnull TrainerBot bot,
                                            @Nonnull MovementController mc,
                                            @Nonnull Player player,
                                            @Nonnull Material blockType,
                                            @Nonnull Location blockPos,
                                            @Nonnull Phase nextPhase) {
        Block target = blockPos.getBlock();

        if (target.getType() == Material.AIR) {
            if (hasAdjacentSolid(target)) {
                target.setType(blockType);
                removeOneBuildBlock(player, blockType);
                blocksPlaced++;
                PacketUtil.playArmSwing(player);
            } else {
                Block below = target.getRelative(BlockFace.DOWN);
                if (below.getType().isSolid()) {
                    target.setType(blockType);
                    removeOneBuildBlock(player, blockType);
                    blocksPlaced++;
                    PacketUtil.playArmSwing(player);
                }
            }
        }

        if (nextPhase == Phase.STEPPING) {
            // Advance both positions for the next diagonal step
            advanceNextBlocks();
        }

        currentPhase = nextPhase;
        phaseTicks = 0;
        return BridgeTickResult.PLACED;
    }

    @Nonnull
    private BridgeTickResult tickStepping(@Nonnull TrainerBot bot,
                                          @Nonnull MovementController mc,
                                          @Nonnull DifficultyProfile diff) {
        double ticksPerBlock = 20.0 / Math.max(1.0, diff.getBridgeSpeed());
        int steppingTicks = Math.max(2, (int) Math.round(ticksPerBlock * 0.4));

        if (phaseTicks >= steppingTicks) {
            currentPhase = Phase.WALKING_TO_EDGE;
            phaseTicks = 0;
        }
        return BridgeTickResult.MOVING;
    }

    private void advanceNextBlocks() {
        // Move both positions forward in the diagonal direction
        nextBlockPosA.add(stepX).add(stepZ);
        nextBlockPosB.add(stepX).add(stepZ);
    }

    private boolean hasAdjacentSolid(@Nonnull Block block) {
        for (BlockFace face : new BlockFace[]{BlockFace.UP, BlockFace.DOWN,
                BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            if (block.getRelative(face).getType().isSolid()) return true;
        }
        return false;
    }

    @Nullable
    private Material findBuildBlock(@Nonnull Player player) {
        for (Material mat : BUILD_MATERIALS) {
            if (player.getInventory().contains(mat)) return mat;
        }
        return null;
    }

    private void removeOneBuildBlock(@Nonnull Player player, @Nonnull Material material) {
        player.getInventory().removeItem(new ItemStack(material, 1));
    }

    @Override
    public void reset() {
        currentPhase = Phase.WALKING_TO_EDGE;
        phaseTicks = 0;
        blocksPlaced = 0;
        bridgeDirection = null;
        nextBlockPosA = null;
        nextBlockPosB = null;
        stepX = null;
        stepZ = null;
    }

    @Override
    public int getBlocksPlaced() {
        return blocksPlaced;
    }
}
