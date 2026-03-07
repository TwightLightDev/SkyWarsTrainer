package org.twightlight.skywarstrainer.movement.strategies;

/**
 * Result of a single approach strategy tick.
 */
public enum ApproachTickResult {
    /** Approach is still in progress. */
    IN_PROGRESS,
    /** Bot has arrived at the target. */
    ARRIVED,
    /** Approach failed (fell off, blocked, etc.). */
    FAILED,
    /** Approach was interrupted by an external event. */
    INTERRUPTED
}
