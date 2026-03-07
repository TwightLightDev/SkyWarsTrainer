package org.twightlight.skywarstrainer.movement.strategies.approaches;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.Vector;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.bridging.BridgeEngine;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.movement.strategies.*;
import org.twightlight.skywarstrainer.util.DebugLogger;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;
import java.util.Arrays;

/**
 * Bridge at a 30-60° angle to the target instead of straight line.
 * Harder for enemy to knock bot off since projectiles must lead more.
 *
 * <p>Implementation:
 * 1. Calculate a point offset from the direct line to target
 * 2. Bridge to the offset point
 * 3. From offset point, bridge directly to target (shorter final approach)</p>
 */
public class DiagonalApproach implements ApproachStrategy {

    private enum Phase { TO_OFFSET, TO_TARGET, DONE }

    private Phase currentPhase;
    private Location offsetPoint;
    private ApproachPath path;
    private boolean bridgeStarted;

    @Nonnull
    @Override
    public String getName() { return "DiagonalApproach"; }

    @Override
    public boolean shouldUse(@Nonnull TrainerBot bot, @Nonnull LivingEntity target,
                             @Nonnull ApproachContext context) {
        DifficultyProfile diff = bot.getDifficultyProfile();
        // Need enough blocks for the longer route and sufficient skill
        return context.availableBlocks >= 20
                && context.distanceToTarget > 8
                && RandomUtil.chance(diff.getDiagonalApproachTendency());
    }

    @Nonnull
    @Override
    public ApproachPath calculateApproachPath(@Nonnull TrainerBot bot, @Nonnull LivingEntity target) {
        Location botLoc = bot.getLocation();
        Location targetLoc = target.getLocation();

        if (botLoc == null || targetLoc == null) {
            this.path = new ApproachPath(ApproachPath.ApproachType.DIAGONAL,
                    java.util.Collections.emptyList(), true, null, null, 999, 1.0);
            return path;
        }

        // Calculate offset point: perpendicular to the direct line, at 30-60° angle
        Vector direct = targetLoc.toVector().subtract(botLoc.toVector());
        double totalDist = direct.length();
        direct.normalize();

        // Perpendicular direction (rotate 90° in XZ plane)
        Vector perp = new Vector(-direct.getZ(), 0, direct.getX());
        double side = RandomUtil.nextBoolean() ? 1.0 : -1.0;

        // Offset distance: 30-60% of the total distance for a good angle
        double offsetDist = totalDist * RandomUtil.nextDouble(0.3, 0.6);
        // Halfway along the direct line + perpendicular offset
        double halfwayDist = totalDist * 0.5;

        offsetPoint = botLoc.clone().add(
                direct.getX() * halfwayDist + perp.getX() * offsetDist * side,
                0,
                direct.getZ() * halfwayDist + perp.getZ() * offsetDist * side
        );
        // Keep at same Y as bot
        offsetPoint.setY(botLoc.getY());

        this.path = new ApproachPath(
                ApproachPath.ApproachType.DIAGONAL,
                Arrays.asList(offsetPoint, targetLoc.clone()),
                true, botLoc, targetLoc,
                (totalDist * 1.4) / 4.0, // ~40% longer than direct
                0.4 // Lower risk than direct
        );

        this.currentPhase = Phase.TO_OFFSET;
        this.bridgeStarted = false;

        return path;
    }

    @Override
    @Nonnull
    public ApproachTickResult tick(@Nonnull TrainerBot bot) {
        BridgeEngine engine = bot.getBridgeEngine();
        if (engine == null) return ApproachTickResult.FAILED;
        Location botLoc = bot.getLocation();
        if (botLoc == null) return ApproachTickResult.FAILED;

        switch (currentPhase) {
            case TO_OFFSET:
                if (!bridgeStarted) {
                    if (offsetPoint == null) return ApproachTickResult.FAILED;
                    boolean started = engine.startBridge(offsetPoint,
                            engine.getAvailableBlockCount());
                    if (!started) return ApproachTickResult.FAILED;
                    bridgeStarted = true;
                }
                if (!engine.isActive()) {
                    // Phase 1 complete — transition to direct approach
                    currentPhase = Phase.TO_TARGET;
                    bridgeStarted = false;
                    DebugLogger.log(bot, "DiagonalApproach: offset reached, bridging to target");
                }
                return ApproachTickResult.IN_PROGRESS;

            case TO_TARGET:
                if (!bridgeStarted) {
                    if (path == null || path.getBridgeEndPoint() == null) return ApproachTickResult.FAILED;
                    boolean started = engine.startBridge(path.getBridgeEndPoint(),
                            engine.getAvailableBlockCount());
                    if (!started) return ApproachTickResult.FAILED;
                    bridgeStarted = true;
                }
                if (!engine.isActive()) {
                    currentPhase = Phase.DONE;
                    return ApproachTickResult.ARRIVED;
                }
                return ApproachTickResult.IN_PROGRESS;

            case DONE:
                return ApproachTickResult.ARRIVED;

            default:
                return ApproachTickResult.FAILED;
        }
    }

    @Override
    public double getPriority(@Nonnull TrainerBot bot) {
        double base = 0.6;
        if (bot.getProfile().hasPersonality("STRATEGIC")) base *= 1.5;
        if (bot.getProfile().hasPersonality("CAUTIOUS")) base *= 1.3;
        if (bot.getProfile().hasPersonality("AGGRESSIVE")) base *= 0.5;
        return base;
    }

    @Override
    public void reset() {
        currentPhase = Phase.TO_OFFSET;
        offsetPoint = null;
        path = null;
        bridgeStarted = false;
    }
}
