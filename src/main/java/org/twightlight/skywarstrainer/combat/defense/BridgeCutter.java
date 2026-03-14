package org.twightlight.skywarstrainer.combat.defense;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.twightlight.skywarstrainer.awareness.ThreatMap;
import org.twightlight.skywarstrainer.awareness.VoidDetector;
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
 * Defensive behavior that detects enemy bridges approaching the bot's island
 * and breaks them to deny access.
 *
 * <p>Two modes of operation:
 * <ol>
 *   <li><b>Strand mode:</b> Wait until the enemy is 3-4 blocks out over void on
 *       the bridge, then break blocks behind them to strand them. Very effective
 *       but requires precise timing.</li>
 *   <li><b>Deny mode:</b> Break the first 2-3 blocks where the bridge connects to
 *       the bot's island, denying the connection before the enemy arrives.</li>
 * </ol></p>
 *
 * <p>Controlled by {@code bridgeCutSkill} in the difficulty profile. Higher skill
 * means faster breaking, better timing for strand mode, and smarter target
 * selection.</p>
 */
public class BridgeCutter implements DefensiveBehavior {

    /** Current phase of bridge cutting. */
    private enum Phase {
        MOVING_TO_BRIDGE,  // Walking to the bridge connection point
        BREAKING_BLOCKS,   // Actively breaking bridge blocks
        DONE
    }

    private Phase phase;
    private boolean complete;
    private int ticksActive;
    private Location targetBlockLocation;
    private List<Location> blocksToBreak;
    private int breakIndex;

    /** Maximum ticks before giving up. */
    private static final int MAX_TICKS = 120; // 6 seconds

    /** Maximum blocks to break per bridge cut action. */
    private static final int MAX_BLOCKS_TO_BREAK = 4;

    public BridgeCutter() {
        reset();
    }

    @Nonnull @Override
    public String getName() { return "BridgeCutter"; }

    @Nonnull @Override
    public DefensiveAction getActionType() { return DefensiveAction.BRIDGE_CUT; }

    /**
     * Activates when an enemy is bridging toward the bot. Detection: looks for
     * recently placed blocks in a line pointing toward the bot's island,
     * with an enemy standing on or near those blocks.
     */
    @Override
    public boolean shouldActivate(@Nonnull TrainerBot bot) {
        DifficultyProfile diff = bot.getDifficultyProfile();
        double skill = diff.getBridgeCutSkill();

        // Low-skill bots rarely cut bridges
        if (!RandomUtil.chance(skill)) return false;

        // Need to see an enemy approaching on a bridge
        ThreatMap threatMap = bot.getThreatMap();
        if (threatMap == null || threatMap.getVisibleThreats().isEmpty()) return false;

        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity == null) return false;
        Location botLoc = botEntity.getLocation();

        // Check each visible enemy: are they on a bridge over void heading toward us?
        for (ThreatMap.ThreatEntry threat : threatMap.getVisibleThreats()) {
            if (threat.currentPosition == null) continue;

            double dist = botLoc.distance(threat.currentPosition);
            // Only consider enemies at medium range (5-25 blocks) — on a bridge
            if (dist < 5.0 || dist > 25.0) continue;

            // Check if the enemy is over void (i.e., on a bridge)
            if (isOverVoid(bot, threat.currentPosition)) {
                // Check if they're heading toward us (velocity toward bot)
                if (threat.velocity != null) {
                    double dx = botLoc.getX() - threat.currentPosition.getX();
                    double dz = botLoc.getZ() - threat.currentPosition.getZ();
                    double dot = dx * threat.velocity.getX() + dz * threat.velocity.getZ();
                    // Positive dot means moving toward us
                    if (dot > 0.01) {
                        // Found a target — locate bridge blocks to break
                        blocksToBreak = findBridgeBlocks(bot, threat.currentPosition, botLoc);
                        if (blocksToBreak != null && !blocksToBreak.isEmpty()) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    @Override
    public double getPriority(@Nonnull TrainerBot bot) {
        double skill = bot.getDifficultyProfile().getBridgeCutSkill();
        double personalityMult = 1.0;

        if (bot.getProfile().hasPersonality("CAMPER")) personalityMult *= 2.0;
        if (bot.getProfile().hasPersonality("STRATEGIC")) personalityMult *= 1.5;
        if (bot.getProfile().hasPersonality("TRICKSTER")) personalityMult *= 1.8;

        // High priority — cutting a bridge can prevent an invasion
        return 3.0 * skill * personalityMult;
    }

    @Override
    public void tick(@Nonnull TrainerBot bot) {
        ticksActive++;
        if (ticksActive > MAX_TICKS) {
            // [FIX] Release DEFENSE authority
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
            case MOVING_TO_BRIDGE:
                tickMoveToBridge(bot, botEntity);
                break;
            case BREAKING_BLOCKS:
                tickBreakBlocks(bot, botEntity);
                break;
            case DONE:
                // [FIX] Release DEFENSE authority
                releaseMovementAuthority(bot);
                complete = true;
                break;
        }
    }

    // [FIX] Helper to release movement authority
    private void releaseMovementAuthority(@Nonnull TrainerBot bot) {
        MovementController mc = bot.getMovementController();
        if (mc != null) {
            mc.releaseAuthority(MovementController.MovementAuthority.DEFENSE);
        }
    }


    /**
     * Moves the bot toward the bridge blocks that need to be broken.
     */
    private void tickMoveToBridge(@Nonnull TrainerBot bot, @Nonnull LivingEntity botEntity) {
        if (blocksToBreak == null || blocksToBreak.isEmpty()) {
            complete = true;
            return;
        }

        Location target = blocksToBreak.get(0);
        double dist = botEntity.getLocation().distance(target);

        MovementController mc = bot.getMovementController();
        if (mc == null) {
            complete = true;
            return;
        }

        if (dist > 4.0) {
            // Need to walk closer
            mc.getSprintController().startSprinting();
            // [FIX-C1] Use DEFENSE authority for bridge-cutting movement
            mc.setMoveTarget(target, MovementController.MovementAuthority.DEFENSE);
            mc.setLookTarget(target);
        } else {
            // Close enough — start breaking
            phase = Phase.BREAKING_BLOCKS;
            breakIndex = 0;
            DebugLogger.log(bot, "BridgeCutter: in range, breaking %d blocks", blocksToBreak.size());
        }
    }

    /**
     * Breaks bridge blocks one at a time. Speed controlled by bridgeCutSkill.
     */
    private void tickBreakBlocks(@Nonnull TrainerBot bot, @Nonnull LivingEntity botEntity) {
        if (blocksToBreak == null || breakIndex >= blocksToBreak.size()) {
            phase = Phase.DONE;
            complete = true;
            return;
        }

        DifficultyProfile diff = bot.getDifficultyProfile();
        double skill = diff.getBridgeCutSkill();

        // Higher skill = break blocks faster (every N ticks)
        // Skill 0.0 → break every 10 ticks, skill 1.0 → break every 2 ticks
        int breakInterval = Math.max(2, (int) (10 - skill * 8));
        if (ticksActive % breakInterval != 0) return;

        Location blockLoc = blocksToBreak.get(breakIndex);
        Block block = blockLoc.getBlock();

        MovementController mc = bot.getMovementController();
        if (mc != null) {
            mc.setLookTarget(blockLoc);
        }

        // Break the block if it's a solid (bridge) block
        if (block.getType().isSolid() && !block.getType().equals(Material.BEDROCK)) {
            // Simulate block breaking — in Minecraft 1.8, NPCs break blocks instantly
            // when using the correct tool. We simulate a dig animation delay based on skill.
            block.setType(Material.AIR);
            DebugLogger.log(bot, "BridgeCutter: broke block at (%d,%d,%d)",
                    block.getX(), block.getY(), block.getZ());
            breakIndex++;
        } else {
            // Block already broken or not breakable — skip
            breakIndex++;
        }
    }

    /**
     * Checks if a location is over void (no solid blocks below within 10 blocks).
     */
    private boolean isOverVoid(@Nonnull TrainerBot bot, @Nonnull Location loc) {
        VoidDetector vd = bot.getVoidDetector();
        if (vd != null) {
            return vd.isVoidBelow(loc);
        }
        // Fallback: manual check
        for (int y = 0; y < 10; y++) {
            Block below = loc.clone().add(0, -1 - y, 0).getBlock();
            if (below.getType().isSolid()) return false;
        }
        return true;
    }

    /**
     * Finds bridge blocks between the enemy and the bot's island that can be broken.
     * Returns the blocks closest to the bot's side (deny mode) or behind the enemy
     * (strand mode) depending on the situation.
     *
     * @param bot      the bot
     * @param enemyLoc the enemy's current position
     * @param botLoc   the bot's current position
     * @return list of block locations to break, or null if none found
     */
    @Nullable
    private List<Location> findBridgeBlocks(@Nonnull TrainerBot bot,
                                            @Nonnull Location enemyLoc,
                                            @Nonnull Location botLoc) {
        List<Location> result = new ArrayList<>();

        // Direction from enemy to bot (the bridge direction)
        double dx = botLoc.getX() - enemyLoc.getX();
        double dz = botLoc.getZ() - enemyLoc.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0) return null;
        dx /= len;
        dz /= len;

        // Walk along the bridge direction from enemy toward bot, looking for solid blocks
        // at the enemy's Y level that are over void — these are bridge blocks.
        // We want to break blocks on the enemy's side (behind them = away from bot)
        // for strand mode, or near our island for deny mode.
        int enemyY = enemyLoc.getBlockY();

        // Deny mode: find blocks near our island (closer to bot)
        // Walk from bot toward enemy, find the first bridge blocks
        for (int step = 1; step <= 8; step++) {
            double checkX = botLoc.getX() - dx * step;
            double checkZ = botLoc.getZ() - dz * step;
            Location check = new Location(botLoc.getWorld(), checkX, enemyY, checkZ);
            Block block = check.getBlock();

            if (block.getType().isSolid() && isOverVoid(bot, check)) {
                result.add(check.clone());
                if (result.size() >= MAX_BLOCKS_TO_BREAK) break;
            }
        }

        return result.isEmpty() ? null : result;
    }

    @Override
    public boolean isComplete() { return complete; }

    @Override
    public void reset() {
        phase = Phase.MOVING_TO_BRIDGE;
        complete = false;
        ticksActive = 0;
        targetBlockLocation = null;
        blocksToBreak = null;
        breakIndex = 0;
    }
}
