// ═══════════════════════════════════════════════════════════════════
// File: src/main/java/org/twightlight/skywarstrainer/bridging/strategies/GodBridge.java
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
 * God bridge: walk FORWARD at 45° angle while placing blocks beneath.
 *
 * <p>The most advanced bridging technique. The bot walks forward (not backward)
 * with no sneaking at all, placing blocks at the precise moment the bot is at
 * the edge of the current block. Requires frame-perfect timing.</p>
 *
 * <p>Speed: ~8-10 blocks/sec. Available only at EXPERT difficulty.
 * Highest fail rate but fastest bridge. The bot walks at a 45° yaw offset
 * and looks down at ~76-80° pitch.</p>
 *
 * <h3>Per-block sequence:</h3>
 * <ol>
 *   <li>Walk forward at 45° offset angle</li>
 *   <li>At exact block boundary: place block beneath</li>
 *   <li>Continue walking — no pause</li>
 * </ol>
 */
public class GodBridge implements org.twightlight.skywarstrainer.bridging.strategies.BridgeStrategy {

    private enum Phase {
        WALKING,
        PLACING
    }

    private Phase currentPhase;
    private Vector bridgeDirection;
    private Location nextBlockPos;
    private int blocksPlaced;
    private int phaseTicks;
    private int totalTicks;

    /** The 45° offset yaw for god-bridge positioning. */
    private float offsetYaw;

    private static final Material[] BUILD_MATERIALS = {
            Material.COBBLESTONE, Material.STONE, Material.WOOL,
            Material.WOOD, Material.SANDSTONE, Material.DIRT,
            Material.SAND, Material.NETHERRACK
    };

    @Nonnull
    @Override
    public String getName() {
        return "GodBridge";
    }

    @Nonnull
    @Override
    public String getRequiredBridgeType() {
        return "GOD";
    }

    @Override
    public double getBaseSpeed() {
        return 9.0;
    }

    @Override
    public void initialize(@Nonnull TrainerBot bot, @Nonnull Location start,
                           @Nonnull Vector direction) {
        this.bridgeDirection = direction.clone().setY(0).normalize();
        this.blocksPlaced = 0;
        this.currentPhase = Phase.WALKING;
        this.phaseTicks = 0;
        this.totalTicks = 0;

        this.nextBlockPos = start.getBlock().getLocation().clone()
                .add(bridgeDirection.clone().multiply(1.0))
                .add(0.5, -1, 0.5);

        // Calculate the 45° offset yaw for god-bridge
        Location botLoc = bot.getLocation();
        if (botLoc != null) {
            float baseYaw = MathUtil.calculateYaw(botLoc, botLoc.clone().add(bridgeDirection));
            // Add 45° offset — god bridge requires this angled approach
            this.offsetYaw = baseYaw + 45f;
        } else {
            this.offsetYaw = 0f;
        }

        // No sneaking in god bridge — walk forward naturally
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
        totalTicks++;

        // God bridge has the highest fail rate — timing must be near-perfect
        if (RandomUtil.chance(diff.getBridgeFailRate() * 2.5)) {
            // Mistimed placement — might fall
            if (totalTicks > 5 && RandomUtil.chance(0.3)) {
                return BridgeTickResult.FAILED;
            }
            return BridgeTickResult.MOVING;
        }

        Material blockType = findBuildBlock(player);
        if (blockType == null) return BridgeTickResult.FAILED;

        phaseTicks++;

        // Set look direction: 45° offset yaw, ~78° pitch (looking down)
        mc.setCurrentYaw(offsetYaw);
        mc.setCurrentPitch(78f + (float) RandomUtil.gaussian(0, 1));

        // Always moving forward — no stopping in god bridge
        mc.setSneaking(false);
        mc.setMovingForward(true);

        // Place block every ~3-4 ticks (at the bot's bridge speed)
        double ticksPerBlock = 20.0 / Math.max(1.0, diff.getBridgeSpeed());
        int placementInterval = Math.max(2, (int) Math.round(ticksPerBlock));

        if (totalTicks % placementInterval == 0) {
            return performPlacement(bot, mc, player, blockType);
        }

        return BridgeTickResult.MOVING;
    }

    @Nonnull
    private BridgeTickResult performPlacement(@Nonnull TrainerBot bot,
                                              @Nonnull MovementController mc,
                                              @Nonnull Player player,
                                              @Nonnull Material blockType) {
        // Find the block directly beneath the player at foot level -1
        Location botLoc = bot.getLocation();
        if (botLoc == null) return BridgeTickResult.FAILED;

        Block footBlock = botLoc.getBlock().getRelative(BlockFace.DOWN);

        // If there's already a block here, check the next position
        if (footBlock.getType().isSolid()) {
            // Check one block ahead in the bridge direction
            Block aheadDown = botLoc.clone()
                    .add(bridgeDirection.clone().multiply(1.0))
                    .getBlock().getRelative(BlockFace.DOWN);
            if (aheadDown.getType() == Material.AIR && hasAdjacentSolid(aheadDown)) {
                aheadDown.setType(blockType);
                removeOneBuildBlock(player, blockType);
                blocksPlaced++;
                PacketUtil.playArmSwing(player);
                advanceNextBlock();
                return BridgeTickResult.PLACED;
            }
            return BridgeTickResult.MOVING;
        }

        // Place block at foot level
        if (hasAdjacentSolid(footBlock)) {
            footBlock.setType(blockType);
            removeOneBuildBlock(player, blockType);
            blocksPlaced++;
            PacketUtil.playArmSwing(player);
            advanceNextBlock();
            return BridgeTickResult.PLACED;
        }

        // No adjacent solid — likely about to fall
        return BridgeTickResult.FAILED;
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
        currentPhase = Phase.WALKING;
        phaseTicks = 0;
        totalTicks = 0;
        blocksPlaced = 0;
        bridgeDirection = null;
        nextBlockPos = null;
    }

    @Override
    public int getBlocksPlaced() {
        return blocksPlaced;
    }
}
