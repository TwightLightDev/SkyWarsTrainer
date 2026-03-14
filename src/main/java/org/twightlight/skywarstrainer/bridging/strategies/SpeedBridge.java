// ═══════════════════════════════════════════════════════════════════
// File: src/main/java/org/twightlight/skywarstrainer/bridging/strategies/SpeedBridge.java
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
 * Speed bridge: unsneak between placements for faster movement.
 *
 * <p>Like normal bridge but toggles sneak off during the walking phase for
 * faster traversal. The bot sneaks only at the block edge for placement,
 * then unsneaks to walk to the next edge.</p>
 *
 * <p>Speed: ~4-5 blocks/sec. Available at MEDIUM+ difficulty (bridgeMaxType >= SPEED).
 * Risk: can fall if sneak timing is wrong (bridgeFailRate applies).</p>
 *
 * <h3>Per-block sequence:</h3>
 * <ol>
 *   <li>Unsneak and walk backward toward block edge</li>
 *   <li>At edge: sneak</li>
 *   <li>Look down and place block</li>
 *   <li>Step back, unsneak, repeat</li>
 * </ol>
 */
public class SpeedBridge implements org.twightlight.skywarstrainer.bridging.strategies.BridgeStrategy {

    private enum Phase {
        WALKING_UNSNEAKED,
        SNEAKING_AT_EDGE,
        AIMING,
        PLACING,
        STEPPING
    }

    private Phase currentPhase;
    private Vector bridgeDirection;
    private Location nextBlockPos;
    private int blocksPlaced;
    private int phaseTicks;
    private Location startPos;

    private static final Material[] BUILD_MATERIALS = {
            Material.COBBLESTONE, Material.STONE, Material.WOOL,
            Material.WOOD, Material.SANDSTONE, Material.DIRT,
            Material.SAND, Material.NETHERRACK
    };

    @Nonnull
    @Override
    public String getName() {
        return "SpeedBridge";
    }

    @Nonnull
    @Override
    public String getRequiredBridgeType() {
        return "SPEED";
    }

    @Override
    public double getBaseSpeed() {
        return 4.5;
    }

    @Override
    public void initialize(@Nonnull TrainerBot bot, @Nonnull Location start,
                           @Nonnull Vector direction) {
        this.startPos = start.clone();
        this.bridgeDirection = direction.clone().setY(0).normalize();
        this.blocksPlaced = 0;
        this.currentPhase = Phase.WALKING_UNSNEAKED;
        this.phaseTicks = 0;

        this.nextBlockPos = start.getBlock().getLocation().clone()
                .add(bridgeDirection.clone().multiply(1.0))
                .add(0.5, -1, 0.5);

        // Start unsneaked for speed
        MovementController mc = bot.getMovementController();
        if (mc != null) {
            mc.setSneaking(false);
        }
    }

    @Nonnull
    @Override
    public BridgeTickResult tick(@Nonnull TrainerBot bot) {
        Player player = bot.getPlayerEntity();
        MovementController mc = bot.getMovementController();
        if (player == null || mc == null) return BridgeTickResult.FAILED;

        DifficultyProfile diff = bot.getDifficultyProfile();

        // Bridge fail check — simulate timing mistake
        if (RandomUtil.chance(diff.getBridgeFailRate())) {
            phaseTicks++;
            if (phaseTicks > 12) return BridgeTickResult.FAILED;
            return BridgeTickResult.MOVING;
        }

        Material blockType = findBuildBlock(player);
        if (blockType == null) return BridgeTickResult.FAILED;

        phaseTicks++;

        switch (currentPhase) {
            case WALKING_UNSNEAKED:
                return tickWalkUnsneaked(bot, mc);
            case SNEAKING_AT_EDGE:
                return tickSneakAtEdge(bot, mc);
            case AIMING:
                return tickAiming(bot, mc);
            case PLACING:
                return tickPlacing(bot, mc, player, blockType);
            case STEPPING:
                return tickStepping(bot, mc, diff);
            default:
                return BridgeTickResult.FAILED;
        }
    }

    @Nonnull
    private BridgeTickResult tickWalkUnsneaked(@Nonnull TrainerBot bot,
                                               @Nonnull MovementController mc) {
        mc.setSneaking(false); // Walk fast without sneaking
        Location botLoc = bot.getLocation();
        if (botLoc == null) return BridgeTickResult.FAILED;

        // Face opposite to bridge direction (walking backward)
        float yaw = MathUtil.calculateYaw(botLoc, botLoc.clone().add(bridgeDirection));
        mc.setCurrentYaw(yaw + 180f);
        mc.setCurrentPitch(0f);

        // Move backward toward edge
        Location edgeTarget = botLoc.clone().add(bridgeDirection.clone().multiply(0.25));
        mc.setMoveTarget(edgeTarget, MovementController.MovementAuthority.BRIDGE);

        double distToEdge = MathUtil.horizontalDistance(botLoc, nextBlockPos.clone().add(0, 1, 0));

        if (distToEdge < 1.8 || phaseTicks > 10) {
            currentPhase = Phase.SNEAKING_AT_EDGE;
            phaseTicks = 0;
        }

        return BridgeTickResult.MOVING;
    }

    @Nonnull
    private BridgeTickResult tickSneakAtEdge(@Nonnull TrainerBot bot,
                                             @Nonnull MovementController mc) {
        mc.setSneaking(true); // Sneak at edge for safety
        if (phaseTicks >= 2) { // Brief sneak hold
            currentPhase = Phase.AIMING;
            phaseTicks = 0;
        }
        return BridgeTickResult.MOVING;
    }

    @Nonnull
    private BridgeTickResult tickAiming(@Nonnull TrainerBot bot,
                                        @Nonnull MovementController mc) {
        float targetPitch = 76f + (float) RandomUtil.gaussian(0, 2);
        float currentPitch = mc.getCurrentPitch();
        DifficultyProfile diff = bot.getDifficultyProfile();
        double speed = diff.getAimSpeedDegPerTick();
        float newPitch = (float) MathUtil.exponentialSmooth(currentPitch, targetPitch,
                MathUtil.clamp(speed / 25.0, 0.4, 0.95));
        mc.setCurrentPitch(newPitch);

        if (Math.abs(newPitch - targetPitch) < 6.0 || phaseTicks > 5) {
            currentPhase = Phase.PLACING;
            phaseTicks = 0;
        }
        return BridgeTickResult.MOVING;
    }

    @Nonnull
    private BridgeTickResult tickPlacing(@Nonnull TrainerBot bot,
                                         @Nonnull MovementController mc,
                                         @Nonnull Player player,
                                         @Nonnull Material blockType) {
        Block target = nextBlockPos.getBlock();

        if (target.getType() != Material.AIR) {
            advanceNextBlock();
            currentPhase = Phase.STEPPING;
            phaseTicks = 0;
            return BridgeTickResult.PLACED;
        }

        if (!hasAdjacentSolid(target)) {
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
            return BridgeTickResult.FAILED;
        }

        target.setType(blockType);
        removeOneBuildBlock(player, blockType);
        blocksPlaced++;
        PacketUtil.playArmSwing(player);

        advanceNextBlock();
        currentPhase = Phase.STEPPING;
        phaseTicks = 0;

        return BridgeTickResult.PLACED;
    }

    @Nonnull
    private BridgeTickResult tickStepping(@Nonnull TrainerBot bot,
                                          @Nonnull MovementController mc,
                                          @Nonnull DifficultyProfile diff) {
        double ticksPerBlock = 20.0 / Math.max(1.0, diff.getBridgeSpeed());
        int steppingTicks = Math.max(1, (int) Math.round(ticksPerBlock * 0.3));

        if (phaseTicks >= steppingTicks) {
            currentPhase = Phase.WALKING_UNSNEAKED;
            phaseTicks = 0;
        }
        return BridgeTickResult.MOVING;
    }

    private void advanceNextBlock() {
        nextBlockPos.add(bridgeDirection.clone().multiply(1.0));
    }

    private boolean hasAdjacentSolid(@Nonnull Block block) {
        BlockFace[] faces = {BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH,
                BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
        for (BlockFace face : faces) {
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
        currentPhase = Phase.WALKING_UNSNEAKED;
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
