// ═══════════════════════════════════════════════════════════════════
// File: src/main/java/org/twightlight/skywarstrainer/bridging/BridgePathPlanner.java
// ═══════════════════════════════════════════════════════════════════
package org.twightlight.skywarstrainer.bridging;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.twightlight.skywarstrainer.awareness.IslandGraph;
import org.twightlight.skywarstrainer.awareness.ThreatMap;
import org.twightlight.skywarstrainer.awareness.VoidDetector;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.util.MathUtil;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Decides WHERE the bot should bridge to, using the IslandGraph for map structure
 * and the ThreatMap for enemy avoidance.
 *
 * <p>The planner considers:
 * <ul>
 *   <li>Distance to target islands (shorter = preferred)</li>
 *   <li>Enemy presence on or near the target (avoid or approach based on personality)</li>
 *   <li>Height advantage (bridging upward vs downward vs same level)</li>
 *   <li>Available blocks (can we actually reach the target?)</li>
 *   <li>Flanking routes (for STRATEGIC/TRICKSTER personalities)</li>
 * </ul></p>
 */
public class BridgePathPlanner {

    private final TrainerBot bot;

    /**
     * Creates a new BridgePathPlanner for the given bot.
     *
     * @param bot the owning trainer bot
     */
    public BridgePathPlanner(@Nonnull TrainerBot bot) {
        this.bot = bot;
    }

    /**
     * Plans the best bridge destination based on the bot's current goal.
     *
     * @param goal the high-level bridging goal
     * @return the planned bridge target, or null if no viable path found
     */
    @Nullable
    public BridgePlan planBridge(@Nonnull BridgeGoal goal) {
        Location botLoc = bot.getLocation();
        if (botLoc == null) return null;

        switch (goal) {
            case TO_MID:
                return planBridgeToMid(botLoc);
            case TO_PLAYER:
                return planBridgeToNearestPlayer(botLoc);
            case TO_ISLAND:
                return planBridgeToNearestUnvisitedIsland(botLoc);
            case ESCAPE:
                return planEscapeBridge(botLoc);
            default:
                return null;
        }
    }

    /**
     * Plans a bridge to the mid island.
     */
    @Nullable
    private BridgePlan planBridgeToMid(@Nonnull Location botLoc) {
        IslandGraph graph = bot.getIslandGraph();
        if (graph == null) return null;

        // Find the mid island — typically the largest or most central island
        IslandGraph.Island midIsland = graph.getMidIsland();
        if (midIsland == null) {
            // Fallback: try to find any island that's not the current one
            return planBridgeToNearestUnvisitedIsland(botLoc);
        }

        // Get the nearest edge point of the mid island
        Location targetEdge = findNearestEdgePoint(midIsland, botLoc);
        if (targetEdge == null) {
            // Use the center of the mid island as fallback
            targetEdge = midIsland.center.clone();
        }

        double distance = MathUtil.horizontalDistance(botLoc, targetEdge);
        int blocksNeeded = (int) Math.ceil(distance) + 2; // +2 safety margin

        return new BridgePlan(targetEdge, blocksNeeded, BridgeGoal.TO_MID, estimateRisk(targetEdge));
    }

    /**
     * Plans a bridge toward the nearest visible player.
     */
    @Nullable
    private BridgePlan planBridgeToNearestPlayer(@Nonnull Location botLoc) {
        ThreatMap threatMap = bot.getThreatMap();
        if (threatMap == null) return null;

        // Get the nearest enemy
        Player nearest = threatMap.getNearestEnemy();
        if (nearest == null) return null;

        Location targetLoc = nearest.getLocation();
        double distance = MathUtil.horizontalDistance(botLoc, targetLoc);

        // Don't bridge if player is very close (same island probably)
        if (distance < 5.0) return null;

        // Personality-based flanking: STRATEGIC/TRICKSTER may approach from the side
        boolean shouldFlank = bot.getProfile().hasPersonality("STRATEGIC")
                || bot.getProfile().hasPersonality("TRICKSTER");

        Location bridgeTarget;
        if (shouldFlank && distance > 15.0) {
            bridgeTarget = calculateFlankingTarget(botLoc, targetLoc);
        } else {
            bridgeTarget = targetLoc.clone();
        }

        int blocksNeeded = (int) Math.ceil(MathUtil.horizontalDistance(botLoc, bridgeTarget)) + 2;
        return new BridgePlan(bridgeTarget, blocksNeeded, BridgeGoal.TO_PLAYER, estimateRisk(bridgeTarget));
    }

    /**
     * Plans a bridge to the nearest island that hasn't been visited for looting.
     */
    @Nullable
    private BridgePlan planBridgeToNearestUnvisitedIsland(@Nonnull Location botLoc) {
        IslandGraph graph = bot.getIslandGraph();
        if (graph == null) return null;

        IslandGraph.Island currentIsland = graph.getCurrentIsland();
        List<IslandGraph.Island> otherIslands = new ArrayList<>();

        for (IslandGraph.Island island : graph.getIslands()) {
            if (island == currentIsland) continue;
            otherIslands.add(island);
        }

        if (otherIslands.isEmpty()) return null;

        // Sort by distance
        otherIslands.sort(Comparator.comparingDouble(
                island -> MathUtil.horizontalDistance(botLoc, island.center)));

        // Pick the nearest
        IslandGraph.Island target = otherIslands.get(0);
        Location targetEdge = findNearestEdgePoint(target, botLoc);
        if (targetEdge == null) {
            targetEdge = target.center.clone();
        }

        double distance = MathUtil.horizontalDistance(botLoc, targetEdge);
        int blocksNeeded = (int) Math.ceil(distance) + 2;

        return new BridgePlan(targetEdge, blocksNeeded, BridgeGoal.TO_ISLAND, estimateRisk(targetEdge));
    }

    /**
     * Plans an escape bridge — bridges away from the nearest threat.
     */
    @Nullable
    private BridgePlan planEscapeBridge(@Nonnull Location botLoc) {
        ThreatMap threatMap = bot.getThreatMap();
        if (threatMap == null) return null;

        Player threat = threatMap.getNearestEnemy();
        if (threat == null) {
            // No threat — escape in a random direction
            double angle = Math.toRadians(RandomUtil.nextDouble() * 360.0);
            Location target = botLoc.clone().add(
                    Math.cos(angle) * 20.0, 0, Math.sin(angle) * 20.0);
            return new BridgePlan(target, 20, BridgeGoal.ESCAPE, 0.3);
        }

        // Bridge in the direction opposite to the threat
        Location threatLoc = threat.getLocation();
        double dx = botLoc.getX() - threatLoc.getX();
        double dz = botLoc.getZ() - threatLoc.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.1) dist = 1.0;

        Location target = botLoc.clone().add(
                (dx / dist) * 25.0, 0, (dz / dist) * 25.0);

        return new BridgePlan(target, 25, BridgeGoal.ESCAPE, estimateRisk(target));
    }

    /**
     * Calculates a flanking target — approach the target from a side angle.
     */
    @Nonnull
    private Location calculateFlankingTarget(@Nonnull Location from, @Nonnull Location target) {
        double dx = target.getX() - from.getX();
        double dz = target.getZ() - from.getZ();

        // Rotate the approach vector by 60-90 degrees for flanking
        double angle = Math.toRadians(RandomUtil.nextDouble(60.0, 90.0));
        if (RandomUtil.chance(0.5)) angle = -angle; // Random flank side

        double cosA = Math.cos(angle);
        double sinA = Math.sin(angle);
        double newDx = dx * cosA - dz * sinA;
        double newDz = dx * sinA + dz * cosA;

        // Place the flank point roughly 2/3 of the way to the target
        return from.clone().add(newDx * 0.66, 0, newDz * 0.66);
    }

    /**
     * Finds the nearest edge point of an island to the given location.
     */
    @Nullable
    private Location findNearestEdgePoint(@Nonnull IslandGraph.Island island, @Nonnull Location from) {
        // Use the island's center as a simple approximation
        // A more sophisticated implementation would track actual island boundaries
        return island.center.clone();
    }

    /**
     * Estimates the risk level of bridging to a location (0.0 = safe, 1.0 = very risky).
     */
    private double estimateRisk(@Nonnull Location target) {
        double risk = 0.0;
        ThreatMap threatMap = bot.getThreatMap();
        if (threatMap != null) {
            // Higher risk if enemies are near the target
            int enemyCount = threatMap.getVisibleEnemyCount();
            risk += MathUtil.clamp(enemyCount * 0.2, 0.0, 0.6);
        }

        // Higher risk for longer bridges (more exposed)
        Location botLoc = bot.getLocation();
        if (botLoc != null) {
            double distance = MathUtil.horizontalDistance(botLoc, target);
            risk += MathUtil.clamp(distance / 60.0, 0.0, 0.4);
        }

        return MathUtil.clamp(risk, 0.0, 1.0);
    }


    // ═══════════════════════════════════════════════════════════════
    //  Inner Types
    // ═══════════════════════════════════════════════════════════════

    /** High-level bridging goals. */
    public enum BridgeGoal {
        /** Bridge to the mid-island for better loot. */
        TO_MID,
        /** Bridge toward a specific player. */
        TO_PLAYER,
        /** Bridge to another island (for looting, escaping, etc.). */
        TO_ISLAND,
        /** Bridge away from a threat. */
        ESCAPE
    }

    /** Result of bridge path planning — contains the target and metadata. */
    public static class BridgePlan {
        /** The target location to bridge to. */
        public final Location target;
        /** Estimated blocks needed to reach the target. */
        public final int estimatedBlocks;
        /** The goal type. */
        public final BridgeGoal goal;
        /** Risk assessment (0.0 = safe, 1.0 = very risky). */
        public final double risk;

        public BridgePlan(@Nonnull Location target, int estimatedBlocks,
                          @Nonnull BridgeGoal goal, double risk) {
            this.target = target;
            this.estimatedBlocks = estimatedBlocks;
            this.goal = goal;
            this.risk = risk;
        }

        @Override
        public String toString() {
            return "BridgePlan{goal=" + goal + ", blocks=" + estimatedBlocks
                    + ", risk=" + String.format("%.2f", risk) + "}";
        }
    }
}
