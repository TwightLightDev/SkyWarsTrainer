package org.twightlight.skywarstrainer.ai.llm;

import org.twightlight.skywarstrainer.ai.decision.DecisionContext;
import org.twightlight.skywarstrainer.ai.strategy.StrategyPlan;
import org.twightlight.skywarstrainer.bot.BotProfile;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Builds structured prompts for LLM strategic advice requests.
 *
 * <p>The prompt includes a concise game state summary and asks the LLM to
 * respond in a structured format that can be parsed by {@link LLMResponseParser}.</p>
 */
public class LLMPromptBuilder {

    /**
     * Builds a strategy prompt from the current game state.
     *
     * @param ctx         the current decision context
     * @param currentPlan the current strategy plan (may be null)
     * @param profile     the bot's profile
     * @return the formatted prompt string
     */
    @Nonnull
    public static String buildStrategyPrompt(@Nonnull DecisionContext ctx,
                                             @Nullable StrategyPlan currentPlan,
                                             @Nonnull BotProfile profile) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("GAME STATE:\n");
        prompt.append(String.format("- Health: %.0f%% (%s)\n",
                ctx.healthFraction * 100,
                ctx.healthFraction > 0.7 ? "healthy" : ctx.healthFraction > 0.3 ? "wounded" : "critical"));
        prompt.append(String.format("- Equipment: %.0f%% (%s)\n",
                ctx.equipmentScore * 100,
                ctx.equipmentScore > 0.7 ? "well-equipped" : ctx.equipmentScore > 0.4 ? "moderate" : "under-geared"));
        prompt.append(String.format("- Enemies visible: %d, nearest: %.1f blocks away\n",
                ctx.visibleEnemyCount, ctx.nearestEnemyDistance));
        prompt.append(String.format("- Game phase: %s (progress: %.0f%%)\n",
                ctx.gamePhase != null ? ctx.gamePhase.name() : "UNKNOWN", ctx.gameProgress * 100));
        prompt.append(String.format("- Players alive: %d\n", ctx.alivePlayerCount));
        prompt.append(String.format("- Unlooted chests: %d\n", ctx.unlootedChestCount));
        prompt.append(String.format("- On mid island: %s\n", ctx.onMidIsland ? "yes" : "no"));

        // Equipment details
        prompt.append("- Has: ");
        if (ctx.hasSword) prompt.append("sword ");
        if (ctx.hasBow && ctx.arrowCount > 0) prompt.append("bow(").append(ctx.arrowCount).append(" arrows) ");
        if (ctx.hasEnderPearl) prompt.append("ender-pearl ");
        if (ctx.hasGoldenApple) prompt.append("golden-apple ");
        if (ctx.hasPotions) prompt.append("potions ");
        if (ctx.hasFood) prompt.append("food ");
        prompt.append(String.format("blocks(%d)", ctx.blockCount));
        prompt.append("\n");

        prompt.append(String.format("- Bot difficulty: %s\n", profile.getDifficulty().name()));

        // Personality info
        if (!profile.getPersonalityNames().isEmpty()) {
            prompt.append("- Personality: ");
            boolean first = true;
            for (String p : profile.getPersonalityNames()) {
                if (!first) prompt.append(", ");
                prompt.append(p);
                first = false;
            }
            prompt.append("\n");
        }

        // Current plan
        if (currentPlan != null) {
            prompt.append(String.format("- Current plan: %s (confidence: %.0f%%, phase %d/%d)\n",
                    currentPlan.getPlanDescription(),
                    currentPlan.getConfidence() * 100,
                    currentPlan.getCurrentPhaseIndex() + 1,
                    currentPlan.getPhaseCount()));
        } else {
            prompt.append("- No current plan.\n");
        }

        prompt.append("\nWhat strategy should the bot follow? Consider the game state and respond in the required format.");

        return prompt.toString();
    }
}
