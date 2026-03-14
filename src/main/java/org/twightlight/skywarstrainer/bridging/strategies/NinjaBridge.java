// ═══════════════════════════════════════════════════════════════════
// File: src/main/java/org/twightlight/skywarstrainer/bridging/strategies/NinjaBridge.java
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
 * Ninja bridge: sneak at edge, unsneak, immediately re-sneak and place.
 *
 * <p>The "lean off the edge" technique. The bot walks backward to the edge,
 * unsneaks for one tick to extend over the edge, then re-sneaks and places
 * the block beneath. This is faster than speed bridge because the placement
 * happens immediately on the re-sneak rather than waiting to walk to the edge.</p>
 *
 * <p>Speed: ~5-7 blocks/sec. Available at HARD+ difficulty (bridgeMaxType >= NINJA).
 * Higher fail rate than speed bridge due to tighter timing.</p>
 */
public class NinjaBridge implements org.twightlight.skywarstrainer.bridging.strategies.BridgeStrategy {

    private enum Phase {
        WALK_TO_EDGE,
        UNSNEAK_LEAN,
        RESNEAK_PLACE,
        STEPPING
    }

    private Phase currentPhase;
    private Vector bridgeDirection;
    private Location nextBlockPos;
    private int blocksPlaced;
    private int phaseTicks;

    private static final Material[] BUILD_MATERIALS = {
            Material.COBBLESTONE, Material.STONE, Material.WOOL,
            Material.WOOD, Material.SANDSTONE, Material.DIRT,
            Material.SAND, Material.NETHERRACK
    };

    @Nonnull
    @Override
    public String getName() {
        return "NinjaBridge";
    }

    @Nonnull
    @Override
    public String getRequiredBridgeType() {
        return "NINJA";
    }

    @Override
    public double getBaseSpeed() {
        return 6.0;
    }

    @Override
    public void initialize(@Nonnull TrainerBot bot, @Nonnull Location start,
                           @Nonnull Vector direction) {
        this.bridgeDirection = direction.clone().setY(0).normalize();
        this.blocksPlaced = 0;
        this.currentPhase = Phase.WALK_TO_EDGE;
        this.phaseTicks = 0;
        this.nextBlockPos = start.getBlock().getLocation().clone()
                .add(bridgeDirection.clone().multiply(1.0))
                .add(0.5, -1, 0.5);

        MovementController mc = bot.getMovementController();
        if (mc != null) mc.setSneaking(true);
    }

    @Nonnull
    @Override
    public BridgeTickResult tick(@Nonnull TrainerBot bot) {
        Player player = bot.getPlayerEntity();
        MovementController mc = bot.getMovementController();
        if (player == null || mc == null) return BridgeTickResult.FAILED;

        DifficultyProfile diff = bot.getDifficultyProfile();

        // Higher fail rate for ninja bridge
        if (RandomUtil.chance(diff.getBridgeFailRate() * 1.5)) {
            phaseTicks++;
            if (phaseTicks > 8) return BridgeTickResult.FAILED;
            return BridgeTickResult.MOVING;
        }

        Material blockType = findBuildBlock(player);
        if (blockType == null) return BridgeTickResult.FAILED;

        phaseTicks++;

        switch (currentPhase) {
            case WALK_TO_EDGE:
                return tickWalkToEdge(bot, mc);
            case UNSNEAK_LEAN:
                return tickUnsneakLean(bot, mc);
            case RESNEAK_PLACE:
                return tickResneakPlace(bot, mc, player, blockType);
            case STEPPING:
                return tickStepping(bot, mc, diff);
            default:
                return BridgeTickResult.FAILED;
        }
    }

    @Nonnull
    private BridgeTickResult tickWalkToEdge(@Nonnull TrainerBot bot,
                                            @Nonnull MovementController mc) {
        mc.setSneaking(true);
        Location botLoc = bot.getLocation();
        if (botLoc == null) return BridgeTickResult.FAILED;

        // Face away from bridge direction (walking backward)
        float yaw = MathUtil.calculateYaw(botLoc, botLoc.clone().add(bridgeDirection));
        mc.setCurrentYaw(yaw + 180f);
        mc.setCurrentPitch(76f);

        Location edgeTarget = botLoc.clone().add(bridgeDirection.clone().multiply(0.2));
        mc.setMoveTarget(edgeTarget, MovementController.MovementAuthority.BRIDGE);

        double distToEdge = MathUtil.horizontalDistance(botLoc, nextBlockPos.clone().add(0, 1, 0));

        if (distToEdge < 1.4 || phaseTicks > 8) {
            currentPhase = Phase.UNSNEAK_LEAN;
            phaseTicks = 0;
        }
        return BridgeTickResult.MOVING;
    }

    @Nonnull
    private BridgeTickResult tickUnsneakLean(@Nonnull TrainerBot bot,
                                             @Nonnull MovementController mc) {
        // Unsneak for exactly 1 tick to lean over the edge
        mc.setSneaking(false);

        if (phaseTicks >= 1) {
            currentPhase = Phase.RESNEAK_PLACE;
            phaseTicks = 0;
        }
        return BridgeTickResult.MOVING;
    }

    @Nonnull
    private BridgeTickResult tickResneakPlace(@Nonnull TrainerBot bot,
                                              @Nonnull MovementController mc,
                                              @Nonnull Player player,
                                              @Nonnull Material blockType) {
        // Re-sneak immediately and place
        mc.setSneaking(true);

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
                PacketUtil.playArmSwing(player);
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
        int steppingTicks = Math.max(1, (int) Math.round(ticksPerBlock * 0.25));

        if (phaseTicks >= steppingTicks) {
            currentPhase = Phase.WALK_TO_EDGE;
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
        currentPhase = Phase.WALK_TO_EDGE;
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
