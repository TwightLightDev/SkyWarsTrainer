package org.twightlight.skywarstrainer.ai.decision.considerations;

import org.twightlight.skywarstrainer.ai.decision.DecisionContext;
import org.twightlight.skywarstrainer.ai.decision.UtilityScorer;

import javax.annotation.Nonnull;

/**
 * Evaluates behavior bias based on how many players are still alive.
 *
 * <p>Returns a score reflecting the "crowdedness" of the game:
 * <ul>
 *   <li>Many players alive (>6) → high score → encourages caution/looting</li>
 *   <li>Few players alive (2-3) → low score → encourages hunting/aggression</li>
 *   <li>1v1 → lowest score → maximum aggression encouraged</li>
 * </ul></p>
 *
 * <p>Actions interpret this differently:
 * <ul>
 *   <li>LOOT/CAMP: use the raw score (more players = more looting)</li>
 *   <li>HUNT/FIGHT: use (1 - score) (fewer players = more hunting)</li>
 * </ul></p>
 */
public final class PlayerCountConsideration implements UtilityScorer {

    @Override
    public double score(@Nonnull DecisionContext context) {
        int alive = context.alivePlayerCount;

        // Map alive count to [0, 1] where more players = higher score
        if (alive <= 1) return 0.0;     // Only bot remains (or 1v1)
        if (alive == 2) return 0.1;     // 1v1 — extremely aggressive
        if (alive <= 4) return 0.3;     // Small game — fairly aggressive
        if (alive <= 6) return 0.6;     // Medium — balanced
        if (alive <= 8) return 0.8;     // Full game — cautious
        return 1.0;                      // Very full (>8 players)
    }

    @Override
    public String getName() {
        return "PlayerCountConsideration";
    }
}

