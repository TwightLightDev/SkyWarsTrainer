package org.twightlight.skywarstrainer.movement.strategies.approaches;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.bridging.BridgeEngine;
import org.twightlight.skywarstrainer.movement.strategies.ApproachContext;
import org.twightlight.skywarstrainer.movement.strategies.ApproachPath;
import org.twightlight.skywarstrainer.movement.strategies.ApproachStrategy;
import org.twightlight.skywarstrainer.movement.strategies.ApproachTickResult;
import org.twightlight.skywarstrainer.util.MathUtil;

import javax.annotation.Nonnull;
import java.util.Collections;

/**
 * Bridge straight toward target's island. Fastest approach, highest risk
 * (easiest to knock off bridge). Best when enemy is distracted or low HP.
 */
public class DirectRushApproach implements ApproachStrategy {

    private boolean bridgeStarted;
    private ApproachPath path;

    @Nonnull
    @Override
    public String getName() { return "DirectRush"; }

    @Override
    public boolean shouldUse(@Nonnull TrainerBot bot, @Nonnull LivingEntity target,
                             @Nonnull ApproachContext context) {
        // Always viable if we have blocks and distance > 4
        return context.availableBlocks >= 10 && context.distanceToTarget > 4;
    }

    @Nonnull
    @Override
    public ApproachPath calculateApproachPath(@Nonnull TrainerBot bot, @Nonnull LivingEntity target) {
        Location botLoc = bot.getLocation();
        Location targetLoc = target.getLocation();
        double dist = (botLoc != null && targetLoc != null) ? botLoc.distance(targetLoc) : 30;

        this.path = new ApproachPath(
                ApproachPath.ApproachType.DIRECT,
                Collections.singletonList(targetLoc != null ? targetLoc.clone() : botLoc),
                true, botLoc, targetLoc,
                dist / 4.0, // Estimated time based on ~4 blocks/sec bridge speed
                0.7 // High risk — straight line is easy to knock off
        );
        return path;
    }

    @Override
    @Nonnull
    public ApproachTickResult tick(@Nonnull TrainerBot bot) {
        BridgeEngine engine = bot.getBridgeEngine();
        if (engine == null) return ApproachTickResult.FAILED;

        // Start the bridge if not already started
        if (!bridgeStarted) {
            if (path == null || path.getBridgeEndPoint() == null) return ApproachTickResult.FAILED;
            boolean started = engine.startBridge(path.getBridgeEndPoint(),
                    bot.getBridgeEngine().getAvailableBlockCount());
            if (!started) return ApproachTickResult.FAILED;
            bridgeStarted = true;
            return ApproachTickResult.IN_PROGRESS;
        }

        // If bridge is active, let it tick (handled by BridgeEngine's own tick)
        if (engine.isActive()) {
            return ApproachTickResult.IN_PROGRESS;
        }

        // Bridge completed — check if we arrived
        Location botLoc = bot.getLocation();
        if (path != null && path.getBridgeEndPoint() != null && botLoc != null) {
            double dist = MathUtil.horizontalDistance(botLoc, path.getBridgeEndPoint());
            if (dist < 4.0) {
                return ApproachTickResult.ARRIVED;
            }
        }

        // Bridge ended but we didn't arrive
        return ApproachTickResult.FAILED;
    }

    @Override
    public double getPriority(@Nonnull TrainerBot bot) {
        double base = 0.5; // Moderate base priority

        // Personality biases
        if (bot.getProfile().hasPersonality("AGGRESSIVE")) base *= 1.5;
        if (bot.getProfile().hasPersonality("BERSERKER")) base *= 2.0;
        if (bot.getProfile().hasPersonality("RUSHER")) base *= 1.8;
        if (bot.getProfile().hasPersonality("CAUTIOUS")) base *= 0.5;

        return base;
    }

    @Override
    public void reset() {
        bridgeStarted = false;
        path = null;
    }
}
