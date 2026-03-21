package org.twightlight.skywarstrainer.combat.defense;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.twightlight.skywarstrainer.awareness.ThreatMap;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.inventory.InventoryEngine;
import org.twightlight.skywarstrainer.movement.MovementController;
import org.twightlight.skywarstrainer.util.DebugLogger;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;

/**
 * Defensive behavior that places a block barricade (wall) to defend a position.
 *
 * <p>The barricade is a 3-block-wide, 2-block-high wall facing the direction
 * enemies must approach from. A 1-block gap is left for the bot to shoot through.
 * The bot stands behind the wall and uses ranged weapons.</p>
 *
 * <p>Primarily used during CAMPING state, but can also be triggered defensively
 * when the bot decides to hold a position.</p>
 */
public class BlockBarricade implements DefensiveBehavior {

    private enum Phase {
        IDENTIFYING_DIRECTION,  // Determine which direction to build wall
        BUILDING_WALL,          // Placing blocks for the barricade
        DONE
    }

    private Phase phase;
    private boolean complete;
    private int ticksActive;
    private int blocksPlaced;

    /** Direction (yaw in degrees) the wall faces. */
    private float wallFacingYaw;

    /** Center of the wall base. */
    private Location wallCenter;

    /** Total blocks needed for a standard barricade (3 wide × 2 high = 6, minus 1 gap = 5). */
    private static final int BLOCKS_NEEDED = 5;

    /** Maximum ticks before giving up. */
    private static final int MAX_TICKS = 80;

    public BlockBarricade() {
        reset();
    }

    @Nonnull @Override
    public String getName() { return "BlockBarricade"; }

    @Nonnull @Override
    public DefensiveAction getActionType() { return DefensiveAction.BLOCK_BARRICADE; }

    @Override
    public boolean shouldActivate(@Nonnull TrainerBot bot) {
        DifficultyProfile diff = bot.getDifficultyProfile();
        double blockPlaceChance = diff.getBlockPlaceChance();

        // Only activate with reasonable probability
        if (!RandomUtil.chance(blockPlaceChance)) return false;

        // Need blocks in inventory
        InventoryEngine inv = bot.getInventoryEngine();
        if (inv == null || inv.getBlockCounter().getTotalBlocks() < BLOCKS_NEEDED) return false;

        // Need a visible threat to build wall against
        ThreatMap threatMap = bot.getThreatMap();
        if (threatMap == null || threatMap.getVisibleThreats().isEmpty()) return false;

        // BERSERKER doesn't build walls — charges in
        if (bot.getProfile().hasPersonality("BERSERKER")) return false;
        if (bot.getProfile().hasPersonality("RUSHER")) return false;

        return true;
    }

    @Override
    public double getPriority(@Nonnull TrainerBot bot) {
        double skill = bot.getDifficultyProfile().getBlockPlaceChance();
        double personalityMult = 1.0;

        if (bot.getProfile().hasPersonality("CAMPER")) personalityMult *= 2.5;
        if (bot.getProfile().hasPersonality("CAUTIOUS")) personalityMult *= 1.5;
        if (bot.getProfile().hasPersonality("STRATEGIC")) personalityMult *= 1.3;

        return 1.5 * skill * personalityMult;
    }

    @Override
    public void tick(@Nonnull TrainerBot bot) {
        ticksActive++;
        if (ticksActive > MAX_TICKS) {
            complete = true;
            return;
        }

        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity == null) {
            complete = true;
            return;
        }

        switch (phase) {
            case IDENTIFYING_DIRECTION:
                tickIdentifyDirection(bot, botEntity);
                break;
            case BUILDING_WALL:
                tickBuildWall(bot, botEntity);
                break;
            case DONE:
                complete = true;
                break;
        }
    }

    /**
     * Determines which direction enemies are approaching from, and sets up
     * the wall parameters.
     */
    private void tickIdentifyDirection(@Nonnull TrainerBot bot, @Nonnull LivingEntity botEntity) {
        ThreatMap threatMap = bot.getThreatMap();
        if (threatMap == null || threatMap.getVisibleThreats().isEmpty()) {
            complete = true;
            return;
        }

        ThreatMap.ThreatEntry nearest = threatMap.getNearestThreat();
        if (nearest == null || nearest.currentPosition == null) {
            complete = true;
            return;
        }

        Location botLoc = botEntity.getLocation();
        Location threatLoc = nearest.currentPosition;

        // Calculate yaw from bot toward threat
        double dx = threatLoc.getX() - botLoc.getX();
        double dz = threatLoc.getZ() - botLoc.getZ();
        wallFacingYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));

        // Wall center is 2 blocks in front of the bot, toward the threat
        double dirX = -Math.sin(Math.toRadians(wallFacingYaw));
        double dirZ = Math.cos(Math.toRadians(wallFacingYaw));
        wallCenter = botLoc.clone().add(dirX * 2, 0, dirZ * 2);

        phase = Phase.BUILDING_WALL;
        DebugLogger.log(bot, "BlockBarricade: building wall facing yaw=%.1f", wallFacingYaw);
    }

    /**
     * Places blocks for the barricade wall. 3 wide × 2 high with a gap.
     * Built over multiple ticks for realism.
     */
    private void tickBuildWall(@Nonnull TrainerBot bot, @Nonnull LivingEntity botEntity) {
        if (blocksPlaced >= BLOCKS_NEEDED) {
            phase = Phase.DONE;
            complete = true;
            DebugLogger.log(bot, "BlockBarricade: wall complete (%d blocks)", blocksPlaced);
            return;
        }

        InventoryEngine inv = bot.getInventoryEngine();
        if (inv == null || inv.getBlockCounter().getTotalBlocks() <= 0) {
            complete = true;
            return;
        }

        // Place one block per 3 ticks (simulating build speed)
        if (ticksActive % 3 != 0) return;

        // Calculate which block to place based on blocksPlaced counter
        // Layout (facing yaw=0, looking north):
        //   [B][G][B]  <- y+1 (top row: Block, Gap, Block)
        //   [B][B][B]  <- y+0 (bottom row: all blocks)
        // Where G is the shooting gap
        double perpX = Math.cos(Math.toRadians(wallFacingYaw));
        double perpZ = Math.sin(Math.toRadians(wallFacingYaw));

        int blockIndex = blocksPlaced;
        double offsetPerp;
        int offsetY;

        // Bottom row first (indices 0,1,2), then top row (3,4)
        if (blockIndex < 3) {
            // Bottom row: left, center, right
            offsetPerp = (blockIndex - 1); // -1, 0, 1
            offsetY = 0;
        } else {
            // Top row: left, right (skip center = gap)
            offsetPerp = (blockIndex == 3) ? -1 : 1;
            offsetY = 1;
        }

        Location blockLoc = wallCenter.clone().add(
                perpX * offsetPerp,
                offsetY,
                perpZ * offsetPerp
        );

        Block block = blockLoc.getBlock();
        if (block.getType() == Material.AIR) {
            block.setType(Material.COBBLESTONE);
            blocksPlaced++;

            // Look at where we're building
            MovementController mc = bot.getMovementController();
            if (mc != null) {
                mc.setLookTarget(blockLoc);
            }

            DebugLogger.log(bot, "BlockBarricade: placed block %d/%d", blocksPlaced, BLOCKS_NEEDED);
        } else {
            // Block position already occupied — skip
            blocksPlaced++;
        }
    }

    @Override
    public boolean isComplete() { return complete; }

    @Override
    public void reset() {
        phase = Phase.IDENTIFYING_DIRECTION;
        complete = false;
        ticksActive = 0;
        blocksPlaced = 0;
        wallFacingYaw = 0;
        wallCenter = null;
    }
}
