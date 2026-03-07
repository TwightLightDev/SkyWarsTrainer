package org.twightlight.skywarstrainer.strategy;

import org.bukkit.Location;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Data class representing a planned approach route to an enemy.
 */
public class ApproachPath {

    /** Waypoints along the approach route. */
    private final List<Location> waypoints;

    /** The type of approach. */
    private final ApproachType type;

    /** Whether this approach requires bridging over void. */
    private final boolean requiresBridging;

    /** Where bridging should start (null if no bridging needed). */
    private final Location bridgeStartPoint;

    /** Where bridging should end. */
    private final Location bridgeEndPoint;

    /** Estimated time to complete this approach in seconds. */
    private final double estimatedTimeSeconds;

    /** Risk score from 0.0 (safe) to 1.0 (very risky). */
    private final double riskScore;

    /**
     * The type of approach route.
     */
    public enum ApproachType {
        DIRECT, DIAGONAL, VERTICAL, SPLIT, FLANKING, PEARL
    }

    public ApproachPath(@Nonnull ApproachType type, @Nonnull List<Location> waypoints,
                        boolean requiresBridging, Location bridgeStartPoint,
                        Location bridgeEndPoint, double estimatedTimeSeconds,
                        double riskScore) {
        this.type = type;
        this.waypoints = Collections.unmodifiableList(new ArrayList<>(waypoints));
        this.requiresBridging = requiresBridging;
        this.bridgeStartPoint = bridgeStartPoint;
        this.bridgeEndPoint = bridgeEndPoint;
        this.estimatedTimeSeconds = estimatedTimeSeconds;
        this.riskScore = riskScore;
    }

    @Nonnull public ApproachType getType() { return type; }
    @Nonnull public List<Location> getWaypoints() { return waypoints; }
    public boolean requiresBridging() { return requiresBridging; }
    public Location getBridgeStartPoint() { return bridgeStartPoint; }
    public Location getBridgeEndPoint() { return bridgeEndPoint; }
    public double getEstimatedTimeSeconds() { return estimatedTimeSeconds; }
    public double getRiskScore() { return riskScore; }

    @Override
    public String toString() {
        return "ApproachPath{type=" + type + ", waypoints=" + waypoints.size()
                + ", risk=" + String.format("%.2f", riskScore)
                + ", time=" + String.format("%.1fs", estimatedTimeSeconds) + "}";
    }
}
