package org.twightlight.skywarstrainer.combat.defense;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.twightlight.skywarstrainer.awareness.IslandGraph;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.movement.MovementController;
import org.twightlight.skywarstrainer.util.DebugLogger;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Defensive behavior that partially breaks an existing bridge to create a trap.
 *
 * <p>The bot identifies a bridge leading to its island, walks to the MIDDLE
 * of that bridge, and breaks 1-2 blocks to create a gap. The gap is not
 * easily visible from a distance, so enemies running across at full speed
 * will fall through into the void.</p>
 *
 * <p>This is a high-IQ play that requires the TRICKSTER or STRATEGIC
 * personality to use frequently. The {@code fakeBridgeChance} difficulty
 * parameter also influences this behavior.</p>
 */
public class BridgeTrap implements DefensiveBehavior {

    private enum Phase {
        FINDING_BRIDGE,    // Locate a bridge to trap
        MOVING_TO_MIDDLE,  // Walk to the middle of the bridge
        BREAKING_BLOCKS,   // Break blocks in the middle
        RETREATING,        // Walk back to safety
        DONE
    }

    private Phase phase;
    private boolean complete;
    private int ticksActive;

    /** Location of the blocks to break (the trap gap). */
    private List<Location> trapBlocks;

    /** The midpoint of the bridge we're trapping. */
    private Location bridgeMidpoint;

    /** Safe location to retreat to after setting the trap. */
    private Location safeRetreatLocation;

    private int breakIndex;

    private static final int MAX_TICKS = 160; // 8 seconds

    public BridgeTrap() {
        reset();
    }

    @Nonnull @Override
    public String getName() { return "BridgeTrap"; }

    @Nonnull @Override
    public DefensiveAction getActionType() { return DefensiveAction.BRIDGE_TRAP; }

    @Override
    public boolean shouldActivate(@Nonnull TrainerBot bot) {
        DifficultyProfile diff = bot.getDifficultyProfile();
        double chance = diff.getFakeBridgeChance();

        if (!RandomUtil.chance(chance)) return false;

        // Need island graph to find bridges
        IslandGraph islandGraph = bot.getIslandGraph();
        if (islandGraph == null) return false;

        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity == null) return false;

        // Find a bridge connected to our island
        IslandGraph.Island botIsland = islandGraph.getIslandAt(botEntity.getLocation());
        if (botIsland == null) return false;

        List<IslandGraph.Bridge> bridges = islandGraph.getBridgesFrom(botIsland);
        if (bridges == null || bridges.isEmpty()) return false;

        // Look for a bridge that has blocks in the middle we can break
        for (IslandGraph.Bridge bridge : bridges) {
            List<Location> middleBlocks = findMiddleBlocks(bot, bridge);
            if (middleBlocks != null && !middleBlocks.isEmpty()) {
                trapBlocks = middleBlocks;
                bridgeMidpoint = middleBlocks.get(0);
                safeRetreatLocation = botEntity.getLocation().clone();
                return true;
            }
        }

        return false;
    }

    @Override
    public double getPriority(@Nonnull TrainerBot bot) {
        double chance = bot.getDifficultyProfile().getFakeBridgeChance();
        double personalityMult = 1.0;

        if (bot.getProfile().hasPersonality("TRICKSTER")) personalityMult *= 3.0;
        if (bot.getProfile().hasPersonality("STRATEGIC")) personalityMult *= 1.5;

        return 1.8 * chance * personalityMult;
    }

    @Override
    public void tick(@Nonnull TrainerBot bot) {
        ticksActive++;
        if (ticksActive > MAX_TICKS) {
            releaseMovementAuthority(bot);
            complete = true;
            return;
        }

        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity == null) {
            releaseMovementAuthority(bot);
            complete = true;
            return;
        }

        switch (phase) {
            case FINDING_BRIDGE:
                // Already found during shouldActivate
                phase = Phase.MOVING_TO_MIDDLE;
                break;
            case MOVING_TO_MIDDLE:
                tickMoveToMiddle(bot, botEntity);
                break;
            case BREAKING_BLOCKS:
                tickBreak(bot, botEntity);
                break;
            case RETREATING:
                tickRetreat(bot, botEntity);
                break;
            case DONE:
                complete = true;
                break;
        }
    }

    private void tickMoveToMiddle(@Nonnull TrainerBot bot, @Nonnull LivingEntity botEntity) {
        if (bridgeMidpoint == null) {
            releaseMovementAuthority(bot);
            complete = true;
            return;
        }
        MovementController mc = bot.getMovementController();
        if (mc == null) {
            releaseMovementAuthority(bot);
            complete = true;
            return;
        }

        double dist = botEntity.getLocation().distance(bridgeMidpoint);
        if (dist > 3.0) {
            mc.setMoveTarget(bridgeMidpoint, MovementController.MovementAuthority.DEFENSE);
            mc.setSneaking(true); // Sneak on bridge for safety
        } else {
            // Close enough to break
            phase = Phase.BREAKING_BLOCKS;
            breakIndex = 0;
            mc.setSneaking(false);
            DebugLogger.log(bot, "BridgeTrap: reached middle, breaking %d blocks",
                    trapBlocks != null ? trapBlocks.size() : 0);
        }
    }

    private void tickBreak(@Nonnull TrainerBot bot, @Nonnull LivingEntity botEntity) {
        if (trapBlocks == null || breakIndex >= trapBlocks.size()) {
            phase = Phase.RETREATING;
            DebugLogger.log(bot, "BridgeTrap: trap set, retreating");
            return;
        }

        // Break one block every 4 ticks
        if (ticksActive % 4 != 0) return;

        Location blockLoc = trapBlocks.get(breakIndex);
        Block block = blockLoc.getBlock();

        MovementController mc = bot.getMovementController();
        if (mc != null) {
            mc.setLookTarget(blockLoc);
        }

        if (block.getType().isSolid() && !block.getType().equals(Material.BEDROCK)) {
            block.setType(Material.AIR);
            DebugLogger.log(bot, "BridgeTrap: broke trap block at (%d,%d,%d)",
                    block.getX(), block.getY(), block.getZ());
        }
        breakIndex++;
    }

    private void tickRetreat(@Nonnull TrainerBot bot, @Nonnull LivingEntity botEntity) {
        if (safeRetreatLocation == null) {
            releaseMovementAuthority(bot);
            complete = true;
            return;
        }
        MovementController mc = bot.getMovementController();
        if (mc == null) {
            releaseMovementAuthority(bot);
            complete = true;
            return;
        }

        double dist = botEntity.getLocation().distance(safeRetreatLocation);
        if (dist > 2.0) {
            mc.setMoveTarget(safeRetreatLocation, MovementController.MovementAuthority.DEFENSE);
            mc.getSprintController().startSprinting();
        } else {
            phase = Phase.DONE;
            releaseMovementAuthority(bot);
            complete = true;
            DebugLogger.log(bot, "BridgeTrap: retreated safely, trap is set");
        }
    }

    /**
     * Finds blocks in the middle of a bridge that can be broken to create a trap.
     */
    @Nullable
    private List<Location> findMiddleBlocks(@Nonnull TrainerBot bot, @Nonnull IslandGraph.Bridge bridge) {
        List<Location> bridgeBlocks = bridge.getBlockLocations();
        if (bridgeBlocks == null || bridgeBlocks.size() < 5) return null;

        // Get blocks in the middle 30% of the bridge
        int start = (int) (bridgeBlocks.size() * 0.35);
        int end = (int) (bridgeBlocks.size() * 0.65);

        List<Location> middleBlocks = new ArrayList<>();
        for (int i = start; i <= end && i < bridgeBlocks.size(); i++) {
            Location loc = bridgeBlocks.get(i);
            Block block = loc.getBlock();
            if (block.getType().isSolid()) {
                middleBlocks.add(loc);
                if (middleBlocks.size() >= 2) break; // Only break 1-2 blocks
            }
        }

        return middleBlocks.isEmpty() ? null : middleBlocks;
    }

    private void releaseMovementAuthority(@Nonnull TrainerBot bot) {
        MovementController mc = bot.getMovementController();
        if (mc != null) {
            mc.releaseAuthority(MovementController.MovementAuthority.DEFENSE);
        }
    }

    @Override
    public boolean isComplete() { return complete; }

    @Override
    public void reset() {
        phase = Phase.FINDING_BRIDGE;
        complete = false;
        ticksActive = 0;
        trapBlocks = null;
        bridgeMidpoint = null;
        safeRetreatLocation = null;
        breakIndex = 0;
    }
}
