package org.twightlight.skywarstrainer.movement.strategies.approaches;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.Vector;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.bridging.BridgeEngine;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.movement.MovementController;
import org.twightlight.skywarstrainer.movement.strategies.*;
import org.twightlight.skywarstrainer.util.DebugLogger;
import org.twightlight.skywarstrainer.util.MathUtil;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;
import java.util.Arrays;

/**
 * Build TWO bridge paths toward enemy to confuse their defense.
 * After building both partial bridges, commit to the one the enemy
 * is NOT watching.
 */
public class SplitPathApproach implements ApproachStrategy {

    private enum Phase { BRIDGE_A, RETURN, BRIDGE_B, EVALUATE, COMMIT, DONE }

    private Phase currentPhase;
    private Location bridgeAEnd;
    private Location bridgeBEnd;
    private Location originIsland; // Return point
    private ApproachPath path;
    private boolean bridgeStarted;
    private int evaluateTicks;

    @Nonnull
    @Override
    public String getName() { return "SplitPath"; }

    @Override
    public boolean shouldUse(@Nonnull TrainerBot bot, @Nonnull LivingEntity target,
                             @Nonnull ApproachContext context) {
        DifficultyProfile diff = bot.getDifficultyProfile();
        // Requires many blocks (2x normal)
        return context.availableBlocks >= 40
                && context.distanceToTarget > 12
                && RandomUtil.chance(diff.getSplitPathChance());
    }

    @Nonnull
    @Override
    public ApproachPath calculateApproachPath(@Nonnull TrainerBot bot, @Nonnull LivingEntity target) {
        Location botLoc = bot.getLocation();
        Location targetLoc = target.getLocation();
        if (botLoc == null || targetLoc == null) {
            return new ApproachPath(ApproachPath.ApproachType.SPLIT,
                    java.util.Collections.emptyList(), true, null, null, 999, 0.8);
        }

        this.originIsland = botLoc.clone();

        // Direct vector to target
        Vector direct = targetLoc.toVector().subtract(botLoc.toVector()).normalize();
        Vector perp = new Vector(-direct.getZ(), 0, direct.getX());
        double segmentLength = RandomUtil.nextInt(5, 8);

        // Bridge A: slightly left of direct
        bridgeAEnd = botLoc.clone().add(
                direct.getX() * segmentLength + perp.getX() * 2,
                0,
                direct.getZ() * segmentLength + perp.getZ() * 2
        );

        // Bridge B: 40-60° angle from Bridge A
        double angleOffset = RandomUtil.nextDouble(0.4, 0.6);
        bridgeBEnd = botLoc.clone().add(
                direct.getX() * segmentLength - perp.getX() * segmentLength * angleOffset,
                0,
                direct.getZ() * segmentLength - perp.getZ() * segmentLength * angleOffset
        );

        this.path = new ApproachPath(
                ApproachPath.ApproachType.SPLIT,
                Arrays.asList(bridgeAEnd, bridgeBEnd, targetLoc.clone()),
                true, botLoc, targetLoc,
                segmentLength * 2.5 / 4.0,
                0.5
        );

        this.currentPhase = Phase.BRIDGE_A;
        this.bridgeStarted = false;
        this.evaluateTicks = 0;

        return path;
    }

    @Override
    @Nonnull
    public ApproachTickResult tick(@Nonnull TrainerBot bot) {
        BridgeEngine engine = bot.getBridgeEngine();
        if (engine == null) return ApproachTickResult.FAILED;

        switch (currentPhase) {
            case BRIDGE_A:
                if (!bridgeStarted) {
                    boolean started = engine.startBridge(bridgeAEnd, 10);
                    if (!started) return ApproachTickResult.FAILED;
                    bridgeStarted = true;
                }
                if (!engine.isActive()) {
                    currentPhase = Phase.RETURN;
                    bridgeStarted = false;
                    DebugLogger.log(bot, "SplitPath: bridge A complete, returning to island");
                }
                return ApproachTickResult.IN_PROGRESS;

            case RETURN:
                // Walk back to origin island
                if (bot.getMovementController() != null && originIsland != null) {
                    bot.getMovementController().setMoveTarget(originIsland,
                            MovementController.MovementAuthority.HUNTING);
                    bot.getMovementController().getSprintController().startSprinting();
                }
                Location botLoc = bot.getLocation();
                if (botLoc != null && originIsland != null
                        && MathUtil.horizontalDistance(botLoc, originIsland) < 3) {
                    currentPhase = Phase.BRIDGE_B;
                    bridgeStarted = false;
                }
                return ApproachTickResult.IN_PROGRESS;

            case BRIDGE_B:
                if (!bridgeStarted) {
                    boolean started = engine.startBridge(bridgeBEnd, 10);
                    if (!started) return ApproachTickResult.FAILED;
                    bridgeStarted = true;
                }
                if (!engine.isActive()) {
                    currentPhase = Phase.EVALUATE;
                    bridgeStarted = false;
                    evaluateTicks = 20;
                    DebugLogger.log(bot, "SplitPath: bridge B complete, evaluating enemy position");
                }
                return ApproachTickResult.IN_PROGRESS;

            case EVALUATE:
                // Wait briefly and see which bridge the enemy is guarding
                evaluateTicks--;
                if (evaluateTicks <= 0) {
                    currentPhase = Phase.COMMIT;
                    bridgeStarted = false;
                }
                return ApproachTickResult.IN_PROGRESS;

            case COMMIT:
                // Choose which bridge to rush down (the one enemy isn't watching)
                // For simplicity, pick the end point and bridge to the target
                if (!bridgeStarted) {
                    if (path == null || path.getBridgeEndPoint() == null) return ApproachTickResult.FAILED;
                    // Commit via whichever path (random choice; ideally based on enemy facing)
                    Location commitPoint = RandomUtil.nextBoolean() ? bridgeAEnd : bridgeBEnd;
                    if (commitPoint != null) {
                        bot.getMovementController().setMoveTarget(commitPoint,
                                MovementController.MovementAuthority.HUNTING);
                    }
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
        double base = 0.35;
        if (bot.getProfile().hasPersonality("TRICKSTER")) base *= 2.0;
        if (bot.getProfile().hasPersonality("STRATEGIC")) base *= 1.5;
        return base;
    }

    @Override
    public void reset() {
        currentPhase = Phase.BRIDGE_A;
        bridgeStarted = false;
        evaluateTicks = 0;
        path = null;
    }
}
