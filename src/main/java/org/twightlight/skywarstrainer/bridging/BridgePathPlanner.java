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
 *   <li>Vertical distance — ascending bridges cost roughly 2x blocks per height gained</li>
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

        int blocksNeeded = estimateBlocksNeeded(botLoc, targetEdge);

        return new BridgePlan(targetEdge, blocksNeeded, BridgeGoal.TO_MID,
                estimateRisk(targetEdge), estimateHeightDiff(botLoc, targetEdge));
    }

    /**
     * Plans a bridge toward the nearest visible player.
     */
    @Nullable
    private BridgePlan planBridgeToNearestPlayer(@Nonnull Location botLoc) {
        ThreatMap threatMap = bot.getThreatMap();
        if (threatMap == null) return null;

        // Get the nearest enemy — uses the new getNearestEnemy() method
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

        int blocksNeeded = estimateBlocksNeeded(botLoc, bridgeTarget);
        return new BridgePlan(bridgeTarget, blocksNeeded, BridgeGoal.TO_PLAYER,
                estimateRisk(bridgeTarget), estimateHeightDiff(botLoc, bridgeTarget));
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

        int blocksNeeded = estimateBlocksNeeded(botLoc, targetEdge);

        return new BridgePlan(targetEdge, blocksNeeded, BridgeGoal.TO_ISLAND,
                estimateRisk(targetEdge), estimateHeightDiff(botLoc, targetEdge));
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
            return new BridgePlan(target, 20, BridgeGoal.ESCAPE, 0.3, 0);
        }

        // Bridge in the direction opposite to the threat
        Location threatLoc = threat.getLocation();
        double dx = botLoc.getX() - threatLoc.getX();
        double dz = botLoc.getZ() - threatLoc.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.1) dist = 1.0;

        Location target = botLoc.clone().add(
                (dx / dist) * 25.0, 0, (dz / dist) * 25.0);

        return new BridgePlan(target, 25, BridgeGoal.ESCAPE, estimateRisk(target), 0);
    }

    /**
     * Estimates the total blocks needed to bridge from one location to another,
     * accounting for both horizontal distance and vertical ascent.
     *
     * <p>Ascending requires roughly 2 blocks per block of height gained
     * (one forward block + one pillar block per step).</p>
     *
     * @param from the starting location
     * @param to   the destination location
     * @return the estimated number of blocks needed
     */
    private int estimateBlocksNeeded(@Nonnull Location from, @Nonnull Location to) {
        double horizDist = MathUtil.horizontalDistance(from, to);
        int heightDiff = to.getBlockY() - from.getBlockY();

        int flatBlocks = (int) Math.ceil(horizDist) + 2; // +2 safety margin

        if (heightDiff > 0) {
            // Ascending: ~2 blocks per height level (1 forward + 1 pillar), plus the
            // remaining horizontal distance after ascending
            int ascendBlocks = heightDiff * 2;
            // The horizontal distance covered during ascent (~1 block per step)
            double remainingHorizDist = Math.max(0, horizDist - heightDiff);
            int remainingFlatBlocks = (int) Math.ceil(remainingHorizDist) + 2;
            return ascendBlocks + remainingFlatBlocks;
        }

        return flatBlocks;
    }

    /**
     * Estimates the height difference between two locations.
     *
     * @param from the starting location
     * @param to   the destination
     * @return the height difference (positive = ascending, negative = descending)
     */
    private int estimateHeightDiff(@Nonnull Location from, @Nonnull Location to) {
        return to.getBlockY() - from.getBlockY();
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
        Location flank = from.clone().add(newDx * 0.66, 0, newDz * 0.66);
        // Preserve target's Y for height-aware planning
        flank.setY(target.getY());
        return flank;
    }

    /**
     * Finds the nearest edge point of an island to the given location.
     */
    @Nullable
    private Location findNearestEdgePoint(@Nonnull IslandGraph.Island island, @Nonnull Location from) {
        // Use the island's center as a simple approximation.
        // The island center preserves the Y level which is important for ascending bridges.
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
            Player nearestEnemyToTarget = threatMap.getNearestEnemyTo(target);
            if (nearestEnemyToTarget != null) {
                double enemyDist = target.distance(nearestEnemyToTarget.getLocation());
                // Closer enemy = higher risk
                risk += MathUtil.clamp(1.0 - (enemyDist / 30.0), 0.0, 0.5);
            }

            int enemyCount = threatMap.getVisibleEnemyCount();
            risk += MathUtil.clamp(enemyCount * 0.15, 0.0, 0.4);
        }

        // Higher risk for longer bridges (more exposed)
        Location botLoc = bot.getLocation();
        if (botLoc != null) {
            double distance = MathUtil.horizontalDistance(botLoc, target);
            risk += MathUtil.clamp(distance / 60.0, 0.0, 0.3);

            // Additional risk for ascending bridges (takes longer, more vulnerable)
            int heightDiff = target.getBlockY() - botLoc.getBlockY();
            if (heightDiff > 0) {
                risk += MathUtil.clamp(heightDiff / 20.0, 0.0, 0.2);
            }
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
        /** Estimated blocks needed to reach the target (including ascent). */
        public final int estimatedBlocks;
        /** The goal type. */
        public final BridgeGoal goal;
        /** Risk assessment (0.0 = safe, 1.0 = very risky). */
        public final double risk;
        /** The estimated height difference (positive = ascending). */
        public final int heightDifference;

        /**
         * Creates a BridgePlan with all metadata.
         *
         * @param target          the target location
         * @param estimatedBlocks blocks needed to reach target
         * @param goal            the bridging goal
         * @param risk            risk assessment [0.0, 1.0]
         * @param heightDifference height difference (positive = ascending)
         */
        public BridgePlan(@Nonnull Location target, int estimatedBlocks,
                          @Nonnull BridgeGoal goal, double risk, int heightDifference) {
            this.target = target;
            this.estimatedBlocks = estimatedBlocks;
            this.goal = goal;
            this.risk = risk;
            this.heightDifference = heightDifference;
        }

        /**
         * Returns true if this plan requires ascending (destination is higher).
         *
         * @return true if height difference > 2
         */
        public boolean requiresAscent() {
            return heightDifference > 2;
        }

        @Override
        public String toString() {
            return "BridgePlan{goal=" + goal + ", blocks=" + estimatedBlocks
                    + ", risk=" + String.format("%.2f", risk)
                    + ", heightDiff=" + heightDifference + "}";
        }
    }
}
