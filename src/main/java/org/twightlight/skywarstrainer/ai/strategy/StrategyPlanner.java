package org.twightlight.skywarstrainer.ai.strategy;

import org.twightlight.skywarstrainer.ai.decision.DecisionContext;
import org.twightlight.skywarstrainer.ai.decision.DecisionEngine.BotAction;
import org.twightlight.skywarstrainer.ai.learning.LearningEngine;
import org.twightlight.skywarstrainer.ai.learning.MemoryBank;
import org.twightlight.skywarstrainer.ai.learning.StateEncoder;
import org.twightlight.skywarstrainer.awareness.GamePhaseTracker;
import org.twightlight.skywarstrainer.awareness.ThreatPredictor;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.util.DebugLogger;
import org.twightlight.skywarstrainer.util.MathUtil;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Strategy Planning System — operates on a longer time horizon than the DecisionEngine.
 *
 * <p>While the DecisionEngine evaluates "what should I do RIGHT NOW" every ~10 ticks,
 * the StrategyPlanner formulates a multi-step game plan spanning 30-120 seconds.
 * The plan biases the DecisionEngine's utility scores via action multipliers.</p>
 *
 * <p>Plans are NOT commands — they are advisory overlays. The DecisionEngine always
 * has the final say. If a plan says "prioritize looting" but an enemy is 3 blocks away,
 * the threat consideration will naturally override the plan's bias.</p>
 *
 * <p>Plan quality and complexity depend on the difficulty profile. BEGINNER bots
 * generate simplistic 1-phase plans; EXPERT bots generate detailed multi-phase plans
 * with conditional fallbacks, resource thresholds, and timing windows.</p>
 */
public class StrategyPlanner {

    /** Confidence threshold below which the plan is discarded and re-generated. */
    private static final double CONFIDENCE_THRESHOLD = 0.15;

    private final TrainerBot bot;

    /** The currently active strategy plan. May be null if planning is disabled. */
    private StrategyPlan activePlan;

    /** LLM-sourced multipliers that persist until the next LLM update. */
    private Map<BotAction, Double> llmMultipliers;

    // ADD THIS FIELD after lastLLMAdvice
    /** Confidence of the last LLM advice, used for blending in getActiveMultipliers(). */
    private double lastLLMConfidence;


    /** Description from last LLM advice for debug output. */
    private String lastLLMAdvice;

    /**
     * Creates a new StrategyPlanner for the given bot.
     *
     * @param bot the owning trainer bot
     */
    public StrategyPlanner(@Nonnull TrainerBot bot) {
        this.bot = bot;
        this.activePlan = null;
        this.llmMultipliers = new EnumMap<>(BotAction.class);
        this.lastLLMAdvice = null;
    }

    // ─── Tick ───────────────────────────────────────────────────

    /**
     * Called periodically from the TrainerBot tick loop (every ~100 ticks).
     * Checks plan validity and generates a new plan if needed.
     */
    public void tick() {
        DifficultyProfile diff = bot.getDifficultyProfile();
        if (diff == null || !diff.isStrategyPlanningEnabled()) {
            activePlan = null;
            return;
        }

        DecisionContext context = null;
        if (bot.getDecisionEngine() != null) {
            context = bot.getDecisionEngine().getContext();
        }
        if (context == null) return;

        // Update existing plan
        if (activePlan != null) {
            boolean stillValid = activePlan.update(context, diff.getPlanConfidenceDecayRate());
            if (!stillValid || activePlan.getConfidence() < CONFIDENCE_THRESHOLD) {
                DebugLogger.log(bot, "Strategy plan expired (conf=%.2f, completed=%s). Re-planning.",
                        activePlan.getConfidence(), activePlan.isCompleted());
                activePlan = null;
            }
        }

        // Generate new plan if needed
        if (activePlan == null) {
            activePlan = generatePlan(context);
            if (activePlan != null) {
                DebugLogger.log(bot, "New strategy plan: %s", activePlan.getPlanDescription());
            }
        }
    }

    // ─── Plan Generation ────────────────────────────────────────

    /**
     * Generates a multi-phase strategy plan based on the current game state.
     *
     * <p>The plan considers: game phase, equipment level, threats, learning data,
     * personality biases, and optionally LLM advice.</p>
     *
     * @param context the current decision context
     * @return a new StrategyPlan, or null if planning fails
     */
    @Nullable
    public StrategyPlan generatePlan(@Nonnull DecisionContext context) {
        DifficultyProfile diff = bot.getDifficultyProfile();
        int maxPhases = diff.getPlanComplexity();
        boolean hasFallbacks = diff.isPlanFallbackEnabled();

        List<StrategicPhase> phases = new ArrayList<>();
        StringBuilder description = new StringBuilder();

        // Determine the game situation
        GamePhaseTracker.GamePhase gamePhase = context.gamePhase;
        if (gamePhase == null) {
            gamePhase = GamePhaseTracker.GamePhase.EARLY;
        }

        double equipScore = context.equipmentScore;
        int enemies = context.visibleEnemyCount;
        int alive = context.alivePlayerCount;
        double health = context.healthFraction;
        boolean hasGoodGear = equipScore > 0.5;
        boolean hasGreatGear = equipScore > 0.75;
        boolean lowHealth = health < diff.getFleeHealthThreshold();
        int chests = context.unlootedChestCount;

        // ── Personality influence ──
        boolean isRusher = bot.getProfile().hasPersonality("RUSHER");
        boolean isDefensive = bot.getProfile().hasPersonality("CAMPER")
                || bot.getProfile().hasPersonality("STRATEGIST");
        boolean isAggressive = bot.getProfile().hasPersonality("AGGRESSIVE")
                || bot.getProfile().hasPersonality("BERSERKER");

        // ── Learning influence ──
        Map<BotAction, Double> learnedPreferences = getLearnedPreferences(context, diff);

        // ── Generate phases based on game phase ──
        switch (gamePhase) {
            case EARLY:
                generateEarlyGamePhases(phases, description, context, diff,
                        maxPhases, hasFallbacks, isRusher, isDefensive,
                        hasGoodGear, chests, learnedPreferences);
                break;
            case MID:
                generateMidGamePhases(phases, description, context, diff,
                        maxPhases, hasFallbacks, isAggressive, isDefensive,
                        hasGoodGear, hasGreatGear, enemies, alive, chests, learnedPreferences);
                break;
            case LATE:
                generateLateGamePhases(phases, description, context, diff,
                        maxPhases, hasFallbacks, isAggressive,
                        hasGoodGear, alive, learnedPreferences);
                break;
        }

        if (phases.isEmpty()) {
            return null;
        }

        // Trim to maxPhases
        while (phases.size() > maxPhases) {
            phases.remove(phases.size() - 1);
        }

        return new StrategyPlan(phases, bot.getLocalTickCount(), description.toString());
    }

    /**
     * Generates phases for the early game (looting, initial setup).
     */
    /**
     * Generates phases for the early game (looting, initial setup).
     */
    private void generateEarlyGamePhases(@Nonnull List<StrategicPhase> phases,
                                         @Nonnull StringBuilder description,
                                         @Nonnull DecisionContext context,
                                         @Nonnull DifficultyProfile diff,
                                         int maxPhases,
                                         boolean hasFallbacks,
                                         boolean isRusher,
                                         boolean isDefensive,
                                         boolean hasGoodGear,
                                         int chests,
                                         @Nonnull Map<BotAction, Double> learnedPreferences) {

        // Phase 1: Loot own island
        if (chests > 0 && !hasGoodGear) {
            Map<BotAction, Double> lootMultipliers = new EnumMap<>(BotAction.class);
            lootMultipliers.put(BotAction.LOOT_OWN_ISLAND, 2.5);
            lootMultipliers.put(BotAction.ORGANIZE_INVENTORY, 1.3);
            lootMultipliers.put(BotAction.FIGHT_NEAREST, 0.4);
            lootMultipliers.put(BotAction.HUNT_PLAYER, 0.2);
            applyLearnedInfluence(lootMultipliers, learnedPreferences, diff);

            StrategicPhase.PhaseCondition lootComplete = new StrategicPhase.PhaseCondition() {
                @Override
                public boolean test(@Nonnull DecisionContext ctx) {
                    return ctx.equipmentScore > 0.4 || ctx.unlootedChestCount == 0;
                }
            };

            StrategicPhase.PhaseCondition lootAbort = hasFallbacks
                    ? new StrategicPhase.PhaseCondition() {
                @Override
                public boolean test(@Nonnull DecisionContext ctx) {
                    return ctx.healthFraction < 0.3 && ctx.visibleEnemyCount > 0;
                }
            } : null;

            phases.add(new StrategicPhase("Loot Own Island",
                    "Gather initial equipment from own island chests",
                    lootMultipliers, lootComplete, lootAbort, 600));
            description.append("Loot own island");
        }

        // Phase 2: Bridge to mid or play safe (depends on personality)
        if (maxPhases >= 2) {
            if (isRusher) {
                Map<BotAction, Double> rushMidMultipliers = new EnumMap<>(BotAction.class);
                rushMidMultipliers.put(BotAction.BRIDGE_TO_MID, 2.0);
                rushMidMultipliers.put(BotAction.LOOT_MID, 1.8);
                rushMidMultipliers.put(BotAction.FIGHT_NEAREST, 1.2);
                applyLearnedInfluence(rushMidMultipliers, learnedPreferences, diff);

                phases.add(new StrategicPhase("Rush Mid",
                        "Bridge to mid island for better loot and control",
                        rushMidMultipliers,
                        new StrategicPhase.PhaseCondition() {
                            @Override
                            public boolean test(@Nonnull DecisionContext ctx) {
                                return ctx.onMidIsland;
                            }
                        },
                        hasFallbacks ? new StrategicPhase.PhaseCondition() {
                            @Override
                            public boolean test(@Nonnull DecisionContext ctx) {
                                return ctx.healthFraction < 0.25;
                            }
                        } : null,
                        800));
                description.append(" → Rush mid");
            } else if (isDefensive) {
                Map<BotAction, Double> defendMultipliers = new EnumMap<>(BotAction.class);
                defendMultipliers.put(BotAction.CAMP_POSITION, 1.8);
                defendMultipliers.put(BotAction.BUILD_FORTIFICATION, 1.5);
                defendMultipliers.put(BotAction.ENCHANT, 1.6);
                defendMultipliers.put(BotAction.HUNT_PLAYER, 0.3);
                applyLearnedInfluence(defendMultipliers, learnedPreferences, diff);

                phases.add(new StrategicPhase("Fortify Position",
                        "Build defenses and enchant gear before engaging",
                        defendMultipliers,
                        new StrategicPhase.PhaseCondition() {
                            @Override
                            public boolean test(@Nonnull DecisionContext ctx) {
                                return ctx.equipmentScore > 0.6;
                            }
                        }, null, 1000));
                description.append(" → Fortify position");
            } else {
                // Default: bridge to mid for enchanting
                Map<BotAction, Double> midMultipliers = new EnumMap<>(BotAction.class);
                midMultipliers.put(BotAction.BRIDGE_TO_MID, 1.6);
                midMultipliers.put(BotAction.ENCHANT, 1.5);
                midMultipliers.put(BotAction.LOOT_MID, 1.3);
                applyLearnedInfluence(midMultipliers, learnedPreferences, diff);

                phases.add(new StrategicPhase("Bridge to Mid",
                        "Bridge to mid for enchanting table and mid loot",
                        midMultipliers,
                        new StrategicPhase.PhaseCondition() {
                            @Override
                            public boolean test(@Nonnull DecisionContext ctx) {
                                return ctx.onMidIsland || ctx.bridgeToMidExists;
                            }
                        }, null, 800));
                description.append(" → Bridge to mid");
            }
        }

        if (maxPhases >= 3 && (diff.getDifficulty() == org.twightlight.skywarstrainer.config.DifficultyConfig.Difficulty.NIGHTMARE || maxPhases >= 5)) {
            Map<BotAction, Double> enchantRushMultipliers = new EnumMap<>(BotAction.class);
            enchantRushMultipliers.put(BotAction.ENCHANT, 2.5);
            enchantRushMultipliers.put(BotAction.ORGANIZE_INVENTORY, 1.8);
            enchantRushMultipliers.put(BotAction.LOOT_MID, 1.5);
            enchantRushMultipliers.put(BotAction.HEAL, 1.3);
            applyLearnedInfluence(enchantRushMultipliers, learnedPreferences, diff);

            phases.add(new StrategicPhase("Enchant & Optimize",
                    "Enchant all gear and optimize inventory for maximum combat power",
                    enchantRushMultipliers,
                    new StrategicPhase.PhaseCondition() {
                        @Override
                        public boolean test(@Nonnull DecisionContext ctx) {
                            return ctx.equipmentScore > 0.8;
                        }
                    }, null, 600));
            description.append(" → Enchant & Optimize");
        }
    }


    /**
     * Generates phases for the mid game (equipment gap decisions, hunting or defense).
     */
    /**
     * Generates phases for the mid game (equipment gap decisions, hunting or defense).
     */
    private void generateMidGamePhases(@Nonnull List<StrategicPhase> phases,
                                       @Nonnull StringBuilder description,
                                       @Nonnull DecisionContext context,
                                       @Nonnull DifficultyProfile diff,
                                       int maxPhases,
                                       boolean hasFallbacks,
                                       boolean isAggressive,
                                       boolean isDefensive,
                                       boolean hasGoodGear,
                                       boolean hasGreatGear,
                                       int enemies,
                                       int alive,
                                       int chests,
                                       @Nonnull Map<BotAction, Double> learnedPreferences) {

        // Decision: hunt, rotate for loot, or defend
        if (hasGoodGear && isAggressive) {
            // Aggressive mid-game: hunt weakest player
            Map<BotAction, Double> huntMultipliers = new EnumMap<>(BotAction.class);
            huntMultipliers.put(BotAction.HUNT_PLAYER, 2.0);
            huntMultipliers.put(BotAction.FIGHT_WEAKEST, 1.8);
            huntMultipliers.put(BotAction.FIGHT_NEAREST, 1.5);
            huntMultipliers.put(BotAction.BRIDGE_TO_PLAYER, 1.6);
            huntMultipliers.put(BotAction.LOOT_OWN_ISLAND, 0.3);
            applyLearnedInfluence(huntMultipliers, learnedPreferences, diff);

            StrategicPhase.PhaseCondition huntAbort = hasFallbacks
                    ? new StrategicPhase.PhaseCondition() {
                @Override
                public boolean test(@Nonnull DecisionContext ctx) {
                    return ctx.healthFraction < 0.3;
                }
            } : null;

            phases.add(new StrategicPhase("Hunt Players",
                    "Aggressively seek and eliminate opponents",
                    huntMultipliers,
                    new StrategicPhase.PhaseCondition() {
                        @Override
                        public boolean test(@Nonnull DecisionContext ctx) {
                            return ctx.alivePlayerCount <= 2;
                        }
                    }, huntAbort, 1200));
            description.append("Hunt players");

        } else if (!hasGoodGear && chests > 0) {
            // Under-geared: rotate for more loot
            Map<BotAction, Double> rotateMultipliers = new EnumMap<>(BotAction.class);
            rotateMultipliers.put(BotAction.LOOT_OTHER_ISLAND, 2.0);
            rotateMultipliers.put(BotAction.LOOT_MID, 1.8);
            rotateMultipliers.put(BotAction.BRIDGE_TO_MID, 1.4);
            rotateMultipliers.put(BotAction.ENCHANT, 1.3);
            rotateMultipliers.put(BotAction.FIGHT_NEAREST, 0.5);
            applyLearnedInfluence(rotateMultipliers, learnedPreferences, diff);

            phases.add(new StrategicPhase("Island Rotation",
                    "Rotate to other islands for better gear",
                    rotateMultipliers,
                    new StrategicPhase.PhaseCondition() {
                        @Override
                        public boolean test(@Nonnull DecisionContext ctx) {
                            return ctx.equipmentScore > 0.6;
                        }
                    }, null, 1000));
            description.append("Rotate for loot");

        } else if (isDefensive) {
            // Defensive mid-game: camp and wait
            Map<BotAction, Double> campMultipliers = new EnumMap<>(BotAction.class);
            campMultipliers.put(BotAction.CAMP_POSITION, 2.0);
            campMultipliers.put(BotAction.BUILD_FORTIFICATION, 1.5);
            campMultipliers.put(BotAction.BREAK_ENEMY_BRIDGE, 1.4);
            campMultipliers.put(BotAction.FIGHT_NEAREST, 1.2);
            campMultipliers.put(BotAction.HUNT_PLAYER, 0.3);
            applyLearnedInfluence(campMultipliers, learnedPreferences, diff);

            phases.add(new StrategicPhase("Defensive Camp",
                    "Hold position and wait for enemies to come",
                    campMultipliers,
                    new StrategicPhase.PhaseCondition() {
                        @Override
                        public boolean test(@Nonnull DecisionContext ctx) {
                            return ctx.alivePlayerCount <= 2 || ctx.timePressure > 0.8;
                        }
                    }, null, 1500));
            description.append("Defensive camp");

        } else {
            // Balanced mid-game: enchant then hunt
            Map<BotAction, Double> prepMultipliers = new EnumMap<>(BotAction.class);
            prepMultipliers.put(BotAction.ENCHANT, 1.8);
            prepMultipliers.put(BotAction.HEAL, 1.4);
            prepMultipliers.put(BotAction.EAT_FOOD, 1.3);
            prepMultipliers.put(BotAction.ORGANIZE_INVENTORY, 1.5);
            applyLearnedInfluence(prepMultipliers, learnedPreferences, diff);

            phases.add(new StrategicPhase("Prepare for Battle",
                    "Enchant gear and organize inventory",
                    prepMultipliers,
                    new StrategicPhase.PhaseCondition() {
                        @Override
                        public boolean test(@Nonnull DecisionContext ctx) {
                            return ctx.equipmentScore > 0.65 || ctx.timePressure > 0.6;
                        }
                    }, null, 600));
            description.append("Prepare");

            if (maxPhases >= 2) {
                Map<BotAction, Double> engageMultipliers = new EnumMap<>(BotAction.class);
                engageMultipliers.put(BotAction.HUNT_PLAYER, 1.8);
                engageMultipliers.put(BotAction.FIGHT_NEAREST, 1.5);
                engageMultipliers.put(BotAction.BRIDGE_TO_PLAYER, 1.4);
                applyLearnedInfluence(engageMultipliers, learnedPreferences, diff);

                phases.add(new StrategicPhase("Engage",
                        "Seek and fight opponents",
                        engageMultipliers,
                        new StrategicPhase.PhaseCondition() {
                            @Override
                            public boolean test(@Nonnull DecisionContext ctx) {
                                return ctx.alivePlayerCount <= 2;
                            }
                        },
                        hasFallbacks ? new StrategicPhase.PhaseCondition() {
                            @Override
                            public boolean test(@Nonnull DecisionContext ctx) {
                                return ctx.healthFraction < 0.25;
                            }
                        } : null, 1200));
                description.append(" → Engage");
            }
        }

        // Additional phase for high-complexity plans: Targeted Elimination
        if (maxPhases >= 4) {
            Map<BotAction, Double> eliminateMultipliers = new EnumMap<>(BotAction.class);
            eliminateMultipliers.put(BotAction.FIGHT_WEAKEST, 2.5);
            eliminateMultipliers.put(BotAction.HUNT_PLAYER, 2.0);
            eliminateMultipliers.put(BotAction.BRIDGE_TO_PLAYER, 1.8);
            eliminateMultipliers.put(BotAction.FIGHT_NEAREST, 1.3);
            eliminateMultipliers.put(BotAction.CAMP_POSITION, 0.2);
            applyLearnedInfluence(eliminateMultipliers, learnedPreferences, diff);

            phases.add(new StrategicPhase("Targeted Elimination",
                    "Identify and eliminate the weakest remaining opponent first",
                    eliminateMultipliers,
                    new StrategicPhase.PhaseCondition() {
                        @Override
                        public boolean test(@Nonnull DecisionContext ctx) {
                            return ctx.alivePlayerCount <= 2;
                        }
                    },
                    hasFallbacks ? new StrategicPhase.PhaseCondition() {
                        @Override
                        public boolean test(@Nonnull DecisionContext ctx) {
                            return ctx.healthFraction < 0.2;
                        }
                    } : null, 1000));
            description.append(" → Targeted Elimination");
        }

        // Healing phase if low health (added as first phase if applicable)
        if (context.healthFraction < 0.5 && maxPhases >= 2) {
            Map<BotAction, Double> healMultipliers = new EnumMap<>(BotAction.class);
            healMultipliers.put(BotAction.HEAL, 2.5);
            healMultipliers.put(BotAction.EAT_FOOD, 2.0);
            healMultipliers.put(BotAction.FLEE, 1.5);
            healMultipliers.put(BotAction.FIGHT_NEAREST, 0.3);
            applyLearnedInfluence(healMultipliers, learnedPreferences, diff);

            phases.add(0, new StrategicPhase("Heal First",
                    "Recover health before engaging",
                    healMultipliers,
                    new StrategicPhase.PhaseCondition() {
                        @Override
                        public boolean test(@Nonnull DecisionContext ctx) {
                            return ctx.healthFraction > 0.75;
                        }
                    }, null, 400));
            description.insert(0, "Heal → ");
        }
    }


    /**
     * Generates phases for the late game (forced engagement, final battles).
     */
    /**
     * Generates phases for the late game (forced engagement, final battles).
     */
    private void generateLateGamePhases(@Nonnull List<StrategicPhase> phases,
                                        @Nonnull StringBuilder description,
                                        @Nonnull DecisionContext context,
                                        @Nonnull DifficultyProfile diff,
                                        int maxPhases,
                                        boolean hasFallbacks,
                                        boolean isAggressive,
                                        boolean hasGoodGear,
                                        int alive,
                                        @Nonnull Map<BotAction, Double> learnedPreferences) {

        if (alive <= 3 && hasGoodGear) {
            // Third-party strategy: let remaining players fight, then clean up
            double thirdPartyTendency = diff.getThirdPartyTendency();
            if (alive == 3 && thirdPartyTendency > 0.5 && !isAggressive) {
                Map<BotAction, Double> waitMultipliers = new EnumMap<>(BotAction.class);
                waitMultipliers.put(BotAction.CAMP_POSITION, 1.8);
                waitMultipliers.put(BotAction.HEAL, 1.5);
                waitMultipliers.put(BotAction.EAT_FOOD, 1.4);
                waitMultipliers.put(BotAction.FIGHT_NEAREST, 0.6);
                applyLearnedInfluence(waitMultipliers, learnedPreferences, diff);

                phases.add(new StrategicPhase("Third Party Wait",
                        "Let other players fight, then engage the survivor",
                        waitMultipliers,
                        new StrategicPhase.PhaseCondition() {
                            @Override
                            public boolean test(@Nonnull DecisionContext ctx) {
                                return ctx.alivePlayerCount <= 2 || ctx.timePressure > 0.9;
                            }
                        }, null, 600));
                description.append("Third party wait");
            }
        }

        // Pre-Final Preparation phase for high-complexity plans (planComplexity >= 5)
        if (maxPhases >= 5) {
            Map<BotAction, Double> prepFinalMultipliers = new EnumMap<>(BotAction.class);
            prepFinalMultipliers.put(BotAction.HEAL, 2.5);
            prepFinalMultipliers.put(BotAction.EAT_FOOD, 2.0);
            prepFinalMultipliers.put(BotAction.DRINK_POTION, 2.0);
            prepFinalMultipliers.put(BotAction.ORGANIZE_INVENTORY, 1.5);
            prepFinalMultipliers.put(BotAction.FIGHT_NEAREST, 0.5);
            prepFinalMultipliers.put(BotAction.HUNT_PLAYER, 0.3);
            applyLearnedInfluence(prepFinalMultipliers, learnedPreferences, diff);

            phases.add(new StrategicPhase("Pre-Final Preparation",
                    "Heal to full, organize inventory, and use potions before the final engagement",
                    prepFinalMultipliers,
                    new StrategicPhase.PhaseCondition() {
                        @Override
                        public boolean test(@Nonnull DecisionContext ctx) {
                            return ctx.healthFraction > 0.9 || ctx.timePressure > 0.95;
                        }
                    }, null, 400));
            if (description.length() > 0) {
                description.append(" → ");
            }
            description.append("Pre-Final Prep");
        }

        // Final engagement phase (always present in late game)
        Map<BotAction, Double> finalMultipliers = new EnumMap<>(BotAction.class);
        finalMultipliers.put(BotAction.FIGHT_NEAREST, 2.0);
        finalMultipliers.put(BotAction.FIGHT_WEAKEST, 1.8);
        finalMultipliers.put(BotAction.HUNT_PLAYER, 2.0);
        finalMultipliers.put(BotAction.BRIDGE_TO_PLAYER, 1.8);
        finalMultipliers.put(BotAction.USE_ENDER_PEARL, 1.5);
        finalMultipliers.put(BotAction.CAMP_POSITION, 0.3);
        finalMultipliers.put(BotAction.LOOT_OWN_ISLAND, 0.1);
        applyLearnedInfluence(finalMultipliers, learnedPreferences, diff);

        phases.add(new StrategicPhase("Final Engagement",
                "Win the game — eliminate all remaining opponents",
                finalMultipliers, null,
                hasFallbacks ? new StrategicPhase.PhaseCondition() {
                    @Override
                    public boolean test(@Nonnull DecisionContext ctx) {
                        return ctx.healthFraction < 0.15;
                    }
                } : null, 2400));

        if (description.length() > 0) {
            description.append(" → ");
        }
        description.append("Final engagement");
    }


    // ─── Learning Data Consultation ─────────────────────────────

    /**
     * Queries the MemoryBank for Q-values to determine which actions historically
     * performed well in the current approximate state.
     *
     * @param context the current decision context
     * @param diff    the difficulty profile
     * @return a map of action → preference multiplier [0.5, 2.0]
     */
    @Nonnull
    private Map<BotAction, Double> getLearnedPreferences(@Nonnull DecisionContext context,
                                                         @Nonnull DifficultyProfile diff) {
        Map<BotAction, Double> prefs = new EnumMap<>(BotAction.class);
        double influence = diff.getPlanLearningInfluence();
        if (influence <= 0.0) return prefs;

        LearningEngine le = bot.getLearningEngine();
        if (le == null) return prefs;

        Map<BotAction, Double> adjustments = le.getWeightAdjustments();
        if (adjustments.isEmpty()) return prefs;

        // Scale adjustments by learning influence
        for (Map.Entry<BotAction, Double> entry : adjustments.entrySet()) {
            double rawMult = entry.getValue();
            // Blend toward 1.0 based on influence: result = 1.0 + influence * (rawMult - 1.0)
            double blended = 1.0 + influence * (rawMult - 1.0);
            prefs.put(entry.getKey(), MathUtil.clamp(blended, 0.5, 2.0));
        }

        return prefs;
    }

    /**
     * Applies learned preferences to a phase's action multipliers.
     * Multiplies existing multipliers with the learned preference.
     */
    private void applyLearnedInfluence(@Nonnull Map<BotAction, Double> multipliers,
                                       @Nonnull Map<BotAction, Double> learnedPreferences,
                                       @Nonnull DifficultyProfile diff) {
        if (learnedPreferences.isEmpty()) return;

        for (Map.Entry<BotAction, Double> entry : learnedPreferences.entrySet()) {
            BotAction action = entry.getKey();
            double learnedMult = entry.getValue();
            if (multipliers.containsKey(action)) {
                double current = multipliers.get(action);
                multipliers.put(action, current * learnedMult);
            }
        }
    }

    // ─── Significant Events ─────────────────────────────────────

    /**
     * Called when a significant event occurs that might warrant re-planning.
     *
     * @param eventType the event type (e.g., "kill", "death", "phase_change")
     */
    public void onSignificantEvent(@Nonnull String eventType) {
        if (activePlan == null) return;

        switch (eventType) {
            case "kill":
                // A kill is good — slightly boost confidence
                activePlan.setConfidence(Math.min(1.0, activePlan.getConfidence() + 0.1));
                break;
            case "death":
            case "player_death":
                // Player count changed — may need new plan
                activePlan.setConfidence(activePlan.getConfidence() * 0.6);
                break;
            case "phase_change":
                // Game phase changed — usually requires new plan
                activePlan.setConfidence(activePlan.getConfidence() * 0.3);
                break;
            case "health_critical":
                // Low health — force re-evaluation
                activePlan.setConfidence(0.0);
                break;
            default:
                // Minor event — small confidence reduction
                activePlan.setConfidence(activePlan.getConfidence() * 0.9);
                break;
        }
    }

    /**
     * Called when the LLM provides strategic advice. Parses the advice
     * and incorporates it into the current plan or stores it for the next plan.
     *
     * @param advice the parsed LLM advice as action multipliers
     */
    // REPLACE THIS METHOD in StrategyPlanner.java
    /**
     * Called when the LLM provides strategic advice. Parses the advice
     * and incorporates it into the current plan or stores it for the next plan.
     *
     * @param adviceDescription  the human-readable strategy description
     * @param adviceMultipliers  the action priority multipliers
     * @param confidence         the validated confidence score from LLMAdviceValidator [0.0, 1.0]
     */
    public void onLLMAdviceReceived(@Nullable String adviceDescription,
                                    @Nonnull Map<BotAction, Double> adviceMultipliers,
                                    double confidence) {
        this.lastLLMAdvice = adviceDescription;
        this.lastLLMConfidence = confidence;
        this.llmMultipliers.clear();
        this.llmMultipliers.putAll(adviceMultipliers);

        // If there's an active plan, slightly boost relevant multipliers
        if (activePlan != null && !adviceMultipliers.isEmpty()) {
            activePlan.setConfidence(Math.min(1.0, activePlan.getConfidence() + 0.15 * confidence));
        }

        DebugLogger.log(bot, "LLM advice received: %s (multipliers=%d, confidence=%.3f)",
                adviceDescription != null ? adviceDescription : "none",
                adviceMultipliers.size(), confidence);
    }


    // ─── Queries ────────────────────────────────────────────────

    /**
     * Returns the active action multipliers from the current plan phase,
     * combined with any LLM-sourced multipliers.
     *
     * <p>Called by DecisionEngine.evaluate() to apply strategy bias.</p>
     *
     * <p>LLM multipliers are blended with plan multipliers weighted by the
     * LLM confidence score. Low-confidence LLM advice barely nudges the plan;
     * high-confidence advice blends equally.</p>
     *
     * @return map of action → multiplier (1.0 = no change)
     */
    @Nonnull
    public Map<BotAction, Double> getActiveMultipliers() {
        Map<BotAction, Double> result = new EnumMap<>(BotAction.class);

        // Add plan multipliers
        if (activePlan != null) {
            result.putAll(activePlan.getActiveMultipliers());
        }

        // Blend in LLM multipliers weighted by confidence
        if (!llmMultipliers.isEmpty()) {
            double conf = lastLLMConfidence;
            for (Map.Entry<BotAction, Double> entry : llmMultipliers.entrySet()) {
                BotAction action = entry.getKey();
                double llmMult = entry.getValue();
                if (result.containsKey(action)) {
                    double planMult = result.get(action);
                    // Low confidence: almost entirely plan. High confidence: 50/50 blend.
                    result.put(action, planMult * (1.0 - conf * 0.5) + llmMult * conf * 0.5);
                } else {
                    // No plan multiplier — blend LLM toward neutral
                    result.put(action, 1.0 + conf * (llmMult - 1.0));
                }
            }
        }

        return result;
    }


    /** @return the currently active plan, or null */
    @Nullable
    public StrategyPlan getActivePlan() {
        return activePlan;
    }

    /** @return true if a plan is currently active */
    public boolean hasActivePlan() {
        return activePlan != null;
    }

    /** @return the last received LLM advice description, or null */
    @Nullable
    public String getLastLLMAdvice() {
        return lastLLMAdvice;
    }
}
