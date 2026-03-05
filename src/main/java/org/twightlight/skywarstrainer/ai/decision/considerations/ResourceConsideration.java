package org.twightlight.skywarstrainer.ai.decision.considerations;

import org.twightlight.skywarstrainer.ai.decision.DecisionContext;
import org.twightlight.skywarstrainer.ai.decision.UtilityScorer;
import org.twightlight.skywarstrainer.util.MathUtil;

import javax.annotation.Nonnull;

/**
 * Evaluates the bot's resource levels (blocks, arrows, food, special items).
 *
 * <p>Returns a score representing how well-resourced the bot is. A high score
 * means the bot has plenty of supplies; a low score means it needs to gather more.
 * This biases toward LOOT when resources are low and toward FIGHT when stocked.</p>
 */
public final class ResourceConsideration implements UtilityScorer {

    @Override
    public double score(@Nonnull DecisionContext context) {
        double score = 0.0;

        // Blocks: critical for bridging and combat block-placing
        if (context.blockCount >= 64) {
            score += 0.25; // Well stocked
        } else if (context.blockCount >= 32) {
            score += 0.18;
        } else if (context.blockCount >= 10) {
            score += 0.10;
        } else if (context.blockCount > 0) {
            score += 0.05; // Dangerously low
        }

        // Arrows
        if (context.hasBow && context.arrowCount >= 16) {
            score += 0.15;
        } else if (context.hasBow && context.arrowCount >= 5) {
            score += 0.08;
        }

        // Food
        if (context.hasFood) {
            score += 0.1;
        }

        // Golden apple (big advantage in fights)
        if (context.hasGoldenApple) {
            score += 0.15;
        }

        // Ender pearl (escape or aggressive play)
        if (context.hasEnderPearl) {
            score += 0.1;
        }

        // Potions
        if (context.hasPotions) {
            score += 0.1;
        }

        // Sword is fundamental
        if (context.hasSword) {
            score += 0.15;
        }

        return MathUtil.clamp(score, 0.0, 1.0);
    }

    @Override
    public String getName() {
        return "ResourceConsideration";
    }
}

