package org.twightlight.skywarstrainer.bridging.movement;

import org.bukkit.Location;
import org.twightlight.skywarstrainer.awareness.ThreatMap;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Selects the best BridgeMovementType based on the bot's difficulty profile,
 * personality, current threat level, terrain, and available resources.
 *
 * <p>The selection is probabilistic: each movement type's associated difficulty
 * parameter represents the CHANCE that the bot will use that technique when
 * it considers it appropriate. So a bot with jumpBridgeChance=0.5 will attempt
 * jump bridging 50% of the time when jump bridging would be viable.</p>
 */
public class BridgeMovementSelector {

    /**
     * Selects the best bridge movement type for the current situation.
     *
     * @param bot         the bot
     * @param destination the bridge destination (may be null)
     * @return the selected movement type
     */
    @Nonnull
    public BridgeMovementType selectMovement(@Nonnull TrainerBot bot, @Nullable Location destination) {
        DifficultyProfile diff = bot.getDifficultyProfile();
        Location botLoc = bot.getLocation();

        // Analyze situation
        boolean threatsNearby = hasThreatsNearby(bot);
        boolean arrowsIncoming = hasArrowsIncoming(bot);
        boolean needsHeight = needsHeightGain(botLoc, destination);
        boolean lowBlocks = isLowOnBlocks(bot);
        boolean hasEnemyOnBridge = hasEnemyOnApproachPath(bot);

        // ── Priority 1: Stair Climb (destination is above) ──
        if (needsHeight && RandomUtil.chance(diff.getStairBridgeSkill())) {
            return BridgeMovementType.STAIR_CLIMB;
        }

        // ── Priority 2: Bait Bridge (trickster personality or high skill) ──
        if (threatsNearby && !lowBlocks) {
            double baitChance = diff.getFakeBridgeChance()
                    * bot.getProfile().getPersonalityProfile().getModifier("creativePlayFrequency");
            // TRICKSTER always tries bait first
            if (bot.getProfile().hasPersonality("TRICKSTER")) {
                baitChance *= 2.0;
            }
            if (RandomUtil.chance(baitChance)) {
                return BridgeMovementType.BAIT_BRIDGE;
            }
        }

        // ── Priority 3: Jump Bridge (speed or arrow evasion) ──
        if (!lowBlocks) {
            double jumpChance = diff.getJumpBridgeChance();
            // Increase chance if arrows are incoming (evasive bridging)
            if (arrowsIncoming) jumpChance *= 1.5;
            // RUSHER and AGGRESSIVE prefer this
            if (bot.getProfile().hasPersonality("RUSHER")) jumpChance *= 1.5;
            if (bot.getProfile().hasPersonality("AGGRESSIVE")) jumpChance *= 1.3;
            // CAUTIOUS never uses jump bridge
            if (bot.getProfile().hasPersonality("CAUTIOUS")) jumpChance = 0.0;

            if (RandomUtil.chance(jumpChance)) {
                return BridgeMovementType.JUMP_BRIDGE;
            }
        }

        // ── Priority 4: Safety Rail (cautious play or under fire) ──
        {
            double railChance = diff.getBridgeSafetyRailChance();
            // CAUTIOUS always does this
            if (bot.getProfile().hasPersonality("CAUTIOUS")) railChance *= 2.0;
            // RUSHER never does
            if (bot.getProfile().hasPersonality("RUSHER")) railChance = 0.0;
            // Higher chance if threats are nearby
            if (threatsNearby) railChance *= 1.5;

            if (RandomUtil.chance(railChance)) {
                return BridgeMovementType.SAFETY_RAIL;
            }
        }

        // ── Priority 5: Speed Sprint (moderate speed boost) ──
        // Available at SPEED bridge level or above
        String maxType = diff.getBridgeMaxType();
        if (!maxType.equals("NORMAL")) {
            // 40% base chance if the bot can speed bridge
            if (RandomUtil.chance(0.4)) {
                return BridgeMovementType.SPEED_SPRINT;
            }
        }

        // ── Default: Safe Sneak ──
        return BridgeMovementType.SAFE_SNEAK;
    }

    /**
     * Checks if there are visible threats within 30 blocks.
     */
    private boolean hasThreatsNearby(@Nonnull TrainerBot bot) {
        ThreatMap tm = bot.getThreatMap();
        if (tm == null) return false;
        return tm.getVisibleEnemyCount() > 0;
    }

    /**
     * Heuristic: checks if arrows/projectiles might be incoming.
     * In 1.8.8 we can't easily detect mid-flight arrows, so we
     * check if an enemy has a bow and is looking at us.
     */
    private boolean hasArrowsIncoming(@Nonnull TrainerBot bot) {
        ThreatMap tm = bot.getThreatMap();
        if (tm == null) return false;
        ThreatMap.ThreatEntry nearest = tm.getNearestThreat();
        if (nearest == null || nearest.currentPosition == null) return false;
        Location botLoc = bot.getLocation();
        if (botLoc == null) return false;
        // Simple heuristic: if nearest enemy is 10-30 blocks away, they might be sniping
        double dist = botLoc.distance(nearest.currentPosition);
        return dist > 10 && dist < 30;
    }

    /**
     * Checks if the destination is significantly higher than the bot.
     */
    private boolean needsHeightGain(@Nullable Location botLoc, @Nullable Location destination) {
        if (botLoc == null || destination == null) return false;
        return destination.getY() - botLoc.getY() > 2;
    }

    /**
     * Checks if the bot has fewer than 20 blocks.
     */
    private boolean isLowOnBlocks(@Nonnull TrainerBot bot) {
        if (bot.getBridgeEngine() == null) return true;
        return bot.getBridgeEngine().getAvailableBlockCount() < 20;
    }

    /**
     * Checks if an enemy appears to be on the bridge approach path.
     */
    private boolean hasEnemyOnApproachPath(@Nonnull TrainerBot bot) {
        // Simplified: if an enemy is within 15 blocks and roughly in front
        ThreatMap tm = bot.getThreatMap();
        if (tm == null) return false;
        return tm.getVisibleEnemyCount() > 0;
    }
}
