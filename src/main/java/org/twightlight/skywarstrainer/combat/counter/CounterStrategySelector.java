package org.twightlight.skywarstrainer.combat.counter;

import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.util.DebugLogger;

import javax.annotation.Nonnull;

/**
 * Given an {@link EnemyProfile}, selects the best counter-strategy and
 * produces {@link CounterModifiers} to apply during combat.
 *
 * <p>The selector implements five counter archetypes:
 * <ol>
 *   <li><b>CounterRusher:</b> Stay defensive, use projectiles at bridge, fight with positioning</li>
 *   <li><b>CounterCamper:</b> Diagonal/split approach, jump-bridge to dodge arrows</li>
 *   <li><b>CounterSniper:</b> Stay behind cover, sprint-jump, close distance fast</li>
 *   <li><b>CounterTrickster:</b> Don't chase near void, check for trap bridges</li>
 *   <li><b>CounterBridger:</b> Use projectiles to knock off bridge, bridge-cut defense</li>
 * </ol></p>
 *
 * <p>The quality of counter-selection depends on {@code counterPlayIQ} — lower
 * IQ bots may misread the enemy or apply weak counters.</p>
 */
public class CounterStrategySelector {

    /**
     * Selects counter modifiers for fighting the given enemy profile.
     *
     * @param bot          the bot
     * @param enemyProfile the observed enemy profile
     * @return the counter modifiers to apply
     */
    @Nonnull
    public static CounterModifiers selectCounter(@Nonnull TrainerBot bot,
                                                 @Nonnull EnemyProfile enemyProfile) {
        DifficultyProfile diff = bot.getDifficultyProfile();
        double iq = diff.getCounterPlayIQ();

        CounterModifiers mods = new CounterModifiers();

        // Low-IQ bots don't counter effectively — return neutral modifiers
        if (iq < 0.15) {
            return mods;
        }

        EnemyProfile.CombatStyle style = enemyProfile.observedCombatStyle;

        switch (style) {
            case AGGRESSIVE:
                applyCounterRusher(bot, enemyProfile, mods, iq);
                break;
            case PASSIVE:
            case PROJECTILE:
                applyCounterSniper(bot, enemyProfile, mods, iq);
                break;
            case TRICKSTER:
                applyCounterTrickster(bot, enemyProfile, mods, iq);
                break;
            case COMBO_HEAVY:
                applyCounterCombo(bot, enemyProfile, mods, iq);
                break;
            default:
                // UNKNOWN or BALANCED — apply mild general counters
                applyGeneralCounter(bot, enemyProfile, mods, iq);
                break;
        }

        // If enemy goes for edge knocks, always try to avoid void
        if (enemyProfile.goesForEdgeKnocks && iq > 0.3) {
            mods.avoidVoidEdge = true;
        }

        // If enemy uses bait, be suspicious
        if (enemyProfile.usesBait && iq > 0.3) {
            mods.watchForBait = true;
        }

        DebugLogger.log(bot, "CounterStrategy: vs %s → %s",
                style.name(), mods.describe());

        return mods;
    }

    /**
     * Counter-Rusher: enemy charges in aggressively.
     * Counter: stay on own island, prepare projectiles, fight with positioning.
     */
    private static void applyCounterRusher(@Nonnull TrainerBot bot,
                                           @Nonnull EnemyProfile enemy,
                                           @Nonnull CounterModifiers mods,
                                           double iq) {
        double antiRush = bot.getDifficultyProfile().getAntiRushReaction();

        // Stay defensive — don't rush toward a rusher
        mods.campUtilityMultiplier = 1.0 + antiRush;
        mods.fightUtilityMultiplier = 0.8; // Slightly less eager to fight

        // Use projectiles to punish their approach
        if (iq > 0.4) {
            mods.useProjectilesFirst = true;
        }

        // Keep comfortable range
        mods.preferredRange = 3.5;

        // If they rush on a bridge, cut it
        if (iq > 0.5) {
            mods.bridgeCut = true;
        }
    }

    /**
     * Counter-Sniper: enemy keeps distance and uses bow.
     * Counter: sprint-jump to close distance, use cover.
     */
    private static void applyCounterSniper(@Nonnull TrainerBot bot,
                                           @Nonnull EnemyProfile enemy,
                                           @Nonnull CounterModifiers mods,
                                           double iq) {
        // Close distance ASAP for melee
        mods.preferredRange = 2.0;
        mods.dodgeProjectiles = true;

        // Don't play passive — need to close the gap
        mods.fightUtilityMultiplier = 1.3;

        // Higher IQ: use blocks for cover during approach
        if (iq > 0.5) {
            mods.useProjectilesFirst = false; // Melee is better vs snipers
        }
    }

    /**
     * Counter-Trickster: enemy uses bait, rods near void, fake retreats.
     * Counter: don't chase near void, check bridges, follow slowly.
     */
    private static void applyCounterTrickster(@Nonnull TrainerBot bot,
                                              @Nonnull EnemyProfile enemy,
                                              @Nonnull CounterModifiers mods,
                                              double iq) {
        double baitDetect = bot.getDifficultyProfile().getBaitDetectionSkill();

        mods.avoidVoidEdge = true;
        mods.watchForBait = true;

        // Don't chase aggressively — tricksters bait you
        mods.fightUtilityMultiplier = 0.9;
        mods.preferredRange = 3.5; // Keep safe distance

        if (baitDetect > 0.5) {
            // High bait detection: play more cautiously
            mods.playPassive = true;
        }
    }

    /**
     * Counter-Combo: enemy is good at maintaining combos.
     * Counter: KB cancel, trade hits, don't let them chain.
     */
    private static void applyCounterCombo(@Nonnull TrainerBot bot,
                                          @Nonnull EnemyProfile enemy,
                                          @Nonnull CounterModifiers mods,
                                          double iq) {
        // Stay close to prevent KB-based combos
        mods.preferredRange = 2.5;

        // If outskilled, play passive and trade
        if (enemy.estimatedSkillLevel > 0.7 && iq > 0.5) {
            mods.playPassive = true;
        }
    }

    /**
     * General counter for unknown or balanced enemies.
     */
    private static void applyGeneralCounter(@Nonnull TrainerBot bot,
                                            @Nonnull EnemyProfile enemy,
                                            @Nonnull CounterModifiers mods,
                                            double iq) {
        // If enemy is significantly better skilled, play more carefully
        if (enemy.estimatedSkillLevel > 0.7 && iq > 0.4) {
            mods.useProjectilesFirst = true;
            mods.preferredRange = 3.5;
        }

        // If enemy retreats at low HP, be more aggressive
        if (enemy.retreatsAtLowHP && iq > 0.3) {
            mods.fightUtilityMultiplier = 1.2;
        }
    }
}
