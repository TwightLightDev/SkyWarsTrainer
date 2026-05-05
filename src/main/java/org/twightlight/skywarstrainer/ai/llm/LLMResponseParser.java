package org.twightlight.skywarstrainer.ai.llm;

import org.twightlight.skywarstrainer.ai.decision.DecisionEngine.BotAction;
import org.twightlight.skywarstrainer.util.DebugLogger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.Map;

/**
 * Parses LLM text responses into actionable data for the StrategyPlanner.
 *
 * <p>Expected response format:</p>
 * <pre>
 * STRATEGY: Rush mid and enchant before hunting
 * PRIORITIES: BRIDGE_TO_MID>ENCHANT>HUNT_PLAYER>FIGHT_NEAREST
 * REASONING: Bot has moderate gear but no enchantments. Mid has enchanting table.
 * </pre>
 *
 * <p>The parser is fault-tolerant: malformed responses return empty/neutral results
 * rather than throwing exceptions.</p>
 */
public class LLMResponseParser {

    /**
     * Parsed result from an LLM response.
     */
    public static final class ParsedAdvice {
        /** Human-readable strategy description. May be empty. */
        @Nonnull
        public final String strategyDescription;

        /** Action priority multipliers derived from the PRIORITIES line. */
        @Nonnull
        public final Map<BotAction, Double> actionMultipliers;

        /** Brief reasoning. May be empty. */
        @Nonnull
        public final String reasoning;

        public ParsedAdvice(@Nonnull String strategyDescription,
                            @Nonnull Map<BotAction, Double> actionMultipliers,
                            @Nonnull String reasoning) {
            this.strategyDescription = strategyDescription;
            this.actionMultipliers = actionMultipliers;
            this.reasoning = reasoning;
        }
    }

    /**
     * Parses an LLM response string into actionable advice.
     *
     * @param response the raw LLM response text
     * @return the parsed advice, or null if the response is completely unparseable
     */
    @Nullable
    public static ParsedAdvice parse(@Nullable String response) {
        if (response == null || response.trim().isEmpty()) {
            return null;
        }

        String strategy = "";
        String priorities = "";
        String reasoning = "";

        String[] lines = response.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.toUpperCase().startsWith("STRATEGY:")) {
                strategy = trimmed.substring("STRATEGY:".length()).trim();
            } else if (trimmed.toUpperCase().startsWith("PRIORITIES:")) {
                priorities = trimmed.substring("PRIORITIES:".length()).trim();
            } else if (trimmed.toUpperCase().startsWith("REASONING:")) {
                reasoning = trimmed.substring("REASONING:".length()).trim();
            }
        }

        // Parse priorities into action multipliers
        Map<BotAction, Double> multipliers = parsePriorities(priorities);

        if (strategy.isEmpty() && multipliers.isEmpty()) {
            // Completely failed to parse — try best-effort extraction
            DebugLogger.logSystem("LLM response parse failed, attempting best-effort. Response: %s",
                    response.length() > 200 ? response.substring(0, 200) + "..." : response);
            return null;
        }

        return new ParsedAdvice(strategy, multipliers, reasoning);
    }

    /**
     * Parses a priority string like "FIGHT_NEAREST>HUNT_PLAYER>HEAL" into
     * action multipliers. The first action gets the highest multiplier.
     *
     * @param priorityString the priority string
     * @return map of action → multiplier
     */
    @Nonnull
    private static Map<BotAction, Double> parsePriorities(@Nonnull String priorityString) {
        Map<BotAction, Double> multipliers = new EnumMap<>(BotAction.class);
        if (priorityString.isEmpty()) return multipliers;

        String[] parts = priorityString.split(">");
        double multiplier = 2.0; // Highest priority action gets 2.0x
        double decrement = (parts.length > 1) ? (1.5 / parts.length) : 0.0;

        for (String part : parts) {
            String actionName = part.trim().toUpperCase().replace(" ", "_");
            try {
                BotAction action = BotAction.valueOf(actionName);
                multipliers.put(action, Math.max(0.5, multiplier));
                multiplier -= decrement;
            } catch (IllegalArgumentException e) {
                // Unknown action name — skip it
                DebugLogger.logSystem("LLM: Unknown action in priorities: '%s'", actionName);
            }
        }

        return multipliers;
    }
}
