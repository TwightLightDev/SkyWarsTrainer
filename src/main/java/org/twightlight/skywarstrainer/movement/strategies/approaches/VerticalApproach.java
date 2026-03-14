package org.twightlight.skywarstrainer.movement.strategies.approaches;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.bridging.BridgeEngine;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.movement.strategies.ApproachContext;
import org.twightlight.skywarstrainer.movement.strategies.ApproachPath;
import org.twightlight.skywarstrainer.movement.strategies.ApproachStrategy;
import org.twightlight.skywarstrainer.movement.strategies.ApproachTickResult;
import org.twightlight.skywarstrainer.util.DebugLogger;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;
import java.util.Arrays;

/**
 * Gain height advantage before bridging to enemy. Builds a tower/staircase
 * on own island (3-8 blocks up), then bridges from the elevated position.
 */
public class VerticalApproach implements ApproachStrategy {

    private enum Phase { TOWER, BRIDGE, DONE }

    private Phase currentPhase;
    private int towerHeight;
    private ApproachPath path;
    private boolean bridgeStarted;

    @Nonnull
    @Override
    public String getName() { return "VerticalApproach"; }

    @Override
    public boolean shouldUse(@Nonnull TrainerBot bot, @Nonnull LivingEntity target,
                             @Nonnull ApproachContext context) {
        DifficultyProfile diff = bot.getDifficultyProfile();
        // Need blocks for tower + bridge, and sufficient skill
        return context.availableBlocks >= 25
                && context.distanceToTarget > 10
                && RandomUtil.chance(diff.getVerticalApproachTendency());
    }

    @Nonnull
    @Override
    public ApproachPath calculateApproachPath(@Nonnull TrainerBot bot, @Nonnull LivingEntity target) {
        Location botLoc = bot.getLocation();
        Location targetLoc = target.getLocation();
        towerHeight = RandomUtil.nextInt(3, 8);

        Location towerTop = botLoc != null ? botLoc.clone().add(0, towerHeight, 0) : null;

        this.path = new ApproachPath(
                ApproachPath.ApproachType.VERTICAL,
                Arrays.asList(towerTop, targetLoc),
                true, towerTop, targetLoc,
                (towerHeight * 0.5) + (botLoc != null && targetLoc != null
                        ? botLoc.distance(targetLoc) / 4.0 : 10),
                0.5
        );
        this.currentPhase = Phase.TOWER;
        this.bridgeStarted = false;
        return path;
    }

    @Override
    @Nonnull
    public ApproachTickResult tick(@Nonnull TrainerBot bot) {
        BridgeEngine engine = bot.getBridgeEngine();
        if (engine == null) return ApproachTickResult.FAILED;

        switch (currentPhase) {
            case TOWER:
                if (!bridgeStarted) {
                    // Bridge "upward" — the ascending bridge strategy handles towers
                    Location botLoc = bot.getLocation();
                    if (botLoc == null) return ApproachTickResult.FAILED;
                    Location towerDest = botLoc.clone().add(0, towerHeight, 0);
                    boolean started = engine.startBridge(towerDest, towerHeight + 5);
                    if (!started) return ApproachTickResult.FAILED;
                    bridgeStarted = true;
                }
                if (!engine.isActive()) {
                    currentPhase = Phase.BRIDGE;
                    bridgeStarted = false;
                    DebugLogger.log(bot, "VerticalApproach: tower complete (+%d), bridging to target",
                            towerHeight);
                }
                return ApproachTickResult.IN_PROGRESS;

            case BRIDGE:
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
        double base = 0.45;
        if (bot.getProfile().hasPersonality("SNIPER")) base *= 2.0;
        if (bot.getProfile().hasPersonality("STRATEGIC")) base *= 1.4;
        if (bot.getProfile().hasPersonality("CAMPER")) base *= 1.3;
        return base;
    }

    @Override
    public void reset() {
        currentPhase = Phase.TOWER;
        towerHeight = 5;
        path = null;
        bridgeStarted = false;
    }
}
