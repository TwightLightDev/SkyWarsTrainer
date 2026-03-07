package org.twightlight.skywarstrainer.strategy.approaches;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.twightlight.skywarstrainer.awareness.IslandGraph;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.bridging.BridgeEngine;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.strategy.*;
import org.twightlight.skywarstrainer.util.DebugLogger;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.List;

/**
 * Instead of bridging directly to enemy's island, bridge to an ADJACENT
 * empty island first, then approach from the side. Uses IslandGraph to
 * find flanking routes.
 */
public class FlankingApproach implements ApproachStrategy {

    private enum Phase { TO_FLANK_ISLAND, TO_TARGET, DONE }

    private Phase currentPhase;
    private Location flankIslandCenter;
    private ApproachPath path;
    private boolean bridgeStarted;

    @Nonnull
    @Override
    public String getName() { return "FlankingApproach"; }

    @Override
    public boolean shouldUse(@Nonnull TrainerBot bot, @Nonnull LivingEntity target,
                             @Nonnull ApproachContext context) {
        DifficultyProfile diff = bot.getDifficultyProfile();
        // Need IslandGraph to find flanking routes
        IslandGraph graph = bot.getIslandGraph();
        if (graph == null) return false;
        return context.availableBlocks >= 30
                && context.distanceToTarget > 15
                && RandomUtil.chance(diff.getDiagonalApproachTendency() * 0.8);
    }

    @Nonnull
    @Override
    public ApproachPath calculateApproachPath(@Nonnull TrainerBot bot, @Nonnull LivingEntity target) {
        Location botLoc = bot.getLocation();
        Location targetLoc = target.getLocation();
        IslandGraph graph = bot.getIslandGraph();

        // Find an uncontested island near the target
        if (graph != null && targetLoc != null) {
            IslandGraph.Island targetIsland = graph.getIslandAt(targetLoc);
            if (targetIsland != null) {
                // Look for adjacent islands that aren't the target's island
                List<IslandGraph.Island> allIslands = graph.getIslands();
                IslandGraph.Island bestFlank = null;
                double bestDist = Double.MAX_VALUE;

                for (IslandGraph.Island island : allIslands) {
                    if (island.equals(targetIsland)) continue;
                    if (island.center == null) continue;
                    double distFromTarget = island.center.distance(targetLoc);
                    if (distFromTarget < bestDist && distFromTarget > 5 && distFromTarget < 30) {
                        bestFlank = island;
                        bestDist = distFromTarget;
                    }
                }

                if (bestFlank != null) {
                    flankIslandCenter = bestFlank.center.clone();
                }
            }
        }

        // Fallback if no flank island found
        if (flankIslandCenter == null && botLoc != null && targetLoc != null) {
            // Create a synthetic flank point
            org.bukkit.util.Vector direct = targetLoc.toVector().subtract(botLoc.toVector()).normalize();
            org.bukkit.util.Vector perp = new org.bukkit.util.Vector(-direct.getZ(), 0, direct.getX());
            double side = RandomUtil.nextBoolean() ? 15.0 : -15.0;
            flankIslandCenter = targetLoc.clone().add(perp.getX() * side, 0, perp.getZ() * side);
        }

        this.path = new ApproachPath(
                ApproachPath.ApproachType.FLANKING,
                Arrays.asList(flankIslandCenter, targetLoc),
                true, botLoc, targetLoc,
                45.0 / 4.0, // Longer route
                0.35 // Lower risk — unexpected direction
        );
        this.currentPhase = Phase.TO_FLANK_ISLAND;
        this.bridgeStarted = false;

        return path;
    }

    @Override
    @Nonnull
    public ApproachTickResult tick(@Nonnull TrainerBot bot) {
        BridgeEngine engine = bot.getBridgeEngine();
        if (engine == null) return ApproachTickResult.FAILED;

        switch (currentPhase) {
            case TO_FLANK_ISLAND:
                if (!bridgeStarted) {
                    if (flankIslandCenter == null) return ApproachTickResult.FAILED;
                    boolean started = engine.startBridge(flankIslandCenter,
                            engine.getAvailableBlockCount() / 2);
                    if (!started) return ApproachTickResult.FAILED;
                    bridgeStarted = true;
                }
                if (!engine.isActive()) {
                    currentPhase = Phase.TO_TARGET;
                    bridgeStarted = false;
                    DebugLogger.log(bot, "FlankingApproach: flank island reached, approaching target");
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
        double base = 0.4;
        if (bot.getProfile().hasPersonality("STRATEGIC")) base *= 1.5;
        if (bot.getProfile().hasPersonality("TRICKSTER")) base *= 1.3;
        return base;
    }

    @Override
    public void reset() {
        currentPhase = Phase.TO_FLANK_ISLAND;
        flankIslandCenter = null;
        path = null;
        bridgeStarted = false;
    }
}
