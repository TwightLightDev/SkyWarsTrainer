// ═══════════════════════════════════════════════════════════════════
// File: src/main/java/org/twightlight/skywarstrainer/bridging/strategies/MoonwalkBridge.java
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
 * Moonwalk bridge: walk backward without sneaking, briefly rotate to place.
 *
 * <p>The bot walks backward at full speed (no sneaking) and briefly rotates
 * 180° to look down at the edge for placement, then rotates back. This is
 * faster than sneaking but requires quick mouse flicks.</p>
 *
 * <p>Speed: ~6-8 blocks/sec. Available at HARD+ difficulty.
 * Relies on the player's momentum and precise timing.</p>
 */
public class MoonwalkBridge implements org.twightlight.skywarstrainer.bridging.strategies.BridgeStrategy {

    private enum Phase {
        WALKING_BACKWARD,
        ROTATING_TO_PLACE,
        PLACING,
        ROTATING_BACK,
        STEPPING
    }

    private Phase currentPhase;
    private Vector bridgeDirection;
    private Location nextBlockPos;
    private int blocksPlaced;
    private int phaseTicks;
    private float savedYaw;

    private static final Material[] BUILD_MATERIALS = {
            Material.COBBLESTONE, Material.STONE, Material.WOOL,
            Material.WOOD, Material.SANDSTONE, Material.DIRT,
            Material.SAND, Material.NETHERRACK
    };

    @Nonnull
    @Override
    public String getName() {
        return "MoonwalkBridge";
    }

    @Nonnull
    @Override
    public String getRequiredBridgeType() {
        return "MOONWALK";
    }

    @Override
    public double getBaseSpeed() {
        return 7.0;
    }

    @Override
    public void initialize(@Nonnull TrainerBot bot, @Nonnull Location start,
                           @Nonnull Vector direction) {
        this.bridgeDirection = direction.clone().setY(0).normalize();
        this.blocksPlaced = 0;
        this.currentPhase = Phase.WALKING_BACKWARD;
        this.phaseTicks = 0;
        this.savedYaw = 0f;

        this.nextBlockPos = start.getBlock().getLocation().clone()
                .add(bridgeDirection.clone().multiply(1.0))
                .add(0.5, -1, 0.5);

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

        if (RandomUtil.chance(diff.getBridgeFailRate() * 2.0)) {
            phaseTicks++;
            if (phaseTicks > 10) return BridgeTickResult.FAILED;
            return BridgeTickResult.MOVING;
        }

        Material blockType = findBuildBlock(player);
        if (blockType == null) return BridgeTickResult.FAILED;

        phaseTicks++;

        switch (currentPhase) {
            case WALKING_BACKWARD:
                return tickWalkBackward(bot, mc);
            case ROTATING_TO_PLACE:
                return tickRotateToPlace(bot, mc);
            case PLACING:
                return tickPlacing(bot, mc, player, blockType);
            case ROTATING_BACK:
                return tickRotateBack(bot, mc);
            case STEPPING:
                return tickStepping(bot, mc, diff);
            default:
                return BridgeTickResult.FAILED;
        }
    }

    @Nonnull
    private BridgeTickResult tickWalkBackward(@Nonnull TrainerBot bot,
                                              @Nonnull MovementController mc) {
        mc.setSneaking(false);
        Location botLoc = bot.getLocation();
        if (botLoc == null) return BridgeTickResult.FAILED;

        // Face TOWARD the bridge direction (walking backward = pressing S key)
        float yaw = MathUtil.calculateYaw(botLoc, botLoc.clone().add(bridgeDirection));
        mc.setCurrentYaw(yaw);
        mc.setCurrentPitch(0f);
        mc.setMovingBackward(true);

        double distToEdge = MathUtil.horizontalDistance(botLoc, nextBlockPos.clone().add(0, 1, 0));

        if (distToEdge < 1.5 || phaseTicks > 8) {
            savedYaw = mc.getCurrentYaw();
            currentPhase = Phase.ROTATING_TO_PLACE;
            phaseTicks = 0;
            mc.setMovingBackward(false);
        }
        return BridgeTickResult.MOVING;
    }

    @Nonnull
    private BridgeTickResult tickRotateToPlace(@Nonnull TrainerBot bot,
                                               @Nonnull MovementController mc) {
        // Quick 180° rotation to look down at the edge
        float targetYaw = savedYaw + 180f;
        mc.setCurrentYaw(targetYaw);
        mc.setCurrentPitch(76f);

        if (phaseTicks >= 1) {
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
            currentPhase = Phase.ROTATING_BACK;
            phaseTicks = 0;
            return BridgeTickResult.PLACED;
        }

        if (hasAdjacentSolid(target)) {
            target.setType(blockType);
            removeOneBuildBlock(player, blockType);
            blocksPlaced++;
            PacketUtil.playArmSwing(player);
            advanceNextBlock();
            currentPhase = Phase.ROTATING_BACK;
            phaseTicks = 0;
            return BridgeTickResult.PLACED;
        }

        Block below = target.getRelative(BlockFace.DOWN);
        if (below.getType().isSolid()) {
            target.setType(blockType);
            removeOneBuildBlock(player, blockType);
            blocksPlaced++;
            PacketUtil.playArmSwing(player);
            advanceNextBlock();
            currentPhase = Phase.ROTATING_BACK;
            phaseTicks = 0;
            return BridgeTickResult.PLACED;
        }

        return BridgeTickResult.FAILED;
    }

    @Nonnull
    private BridgeTickResult tickRotateBack(@Nonnull TrainerBot bot,
                                            @Nonnull MovementController mc) {
        // Rotate back to face the bridge direction
        mc.setCurrentYaw(savedYaw);
        mc.setCurrentPitch(0f);

        if (phaseTicks >= 1) {
            currentPhase = Phase.STEPPING;
            phaseTicks = 0;
        }
        return BridgeTickResult.MOVING;
    }

    @Nonnull
    private BridgeTickResult tickStepping(@Nonnull TrainerBot bot,
                                          @Nonnull MovementController mc,
                                          @Nonnull DifficultyProfile diff) {
        double ticksPerBlock = 20.0 / Math.max(1.0, diff.getBridgeSpeed());
        int steppingTicks = Math.max(1, (int) Math.round(ticksPerBlock * 0.2));

        if (phaseTicks >= steppingTicks) {
            currentPhase = Phase.WALKING_BACKWARD;
            phaseTicks = 0;
        }
        return BridgeTickResult.MOVING;
    }

    private void advanceNextBlock() {
        nextBlockPos.add(bridgeDirection.clone().multiply(1.0));
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
        currentPhase = Phase.WALKING_BACKWARD;
        phaseTicks = 0;
        blocksPlaced = 0;
        bridgeDirection = null;
        nextBlockPos = null;
    }

    @Override
    public int getBlocksPlaced() {
        return blocksPlaced;
    }
}
