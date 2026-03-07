package org.twightlight.skywarstrainer.combat.counter;

import org.bukkit.entity.LivingEntity;
import org.twightlight.skywarstrainer.awareness.ThreatMap;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;

/**
 * Tracks and analyzes enemy behavior patterns over time. Maintains a per-enemy
 * {@link EnemyProfile} that is updated from ThreatMap observations, combat
 * interactions, and movement pattern analysis.
 *
 * <p>The analyzer is ticked every 20 ticks from the main bot tick loop. It
 * observes all visible enemies and updates their profiles. The quality of
 * analysis depends on the bot's {@code counterPlayIQ} parameter — lower
 * IQ bots produce less accurate profiles.</p>
 *
 * <p>Consumers (CombatEngine, ApproachEngine, DecisionEngine) can query
 * the analyzer for an enemy's profile and recommended counter modifiers.</p>
 */
public class EnemyBehaviorAnalyzer {

    private final TrainerBot bot;

    /** Per-enemy profiles. Key: player UUID. */
    private final Map<UUID, EnemyProfile> profiles;

    /** Cached counter modifiers per enemy. Invalidated when profile updates. */
    private final Map<UUID, CounterModifiers> cachedCounters;

    /** Ticks between cache invalidation. */
    private static final int CACHE_INVALIDATE_INTERVAL = 100; // 5 seconds
    private int cacheAge;

    /**
     * Creates a new EnemyBehaviorAnalyzer for the given bot.
     *
     * @param bot the owning bot
     */
    public EnemyBehaviorAnalyzer(@Nonnull TrainerBot bot) {
        this.bot = bot;
        this.profiles = new HashMap<>();
        this.cachedCounters = new HashMap<>();
        this.cacheAge = 0;
    }

    /**
     * Ticks the analyzer. Called every 20 ticks from TrainerBot.
     * Observes all visible enemies and updates their profiles.
     */
    public void tick() {
        ThreatMap threatMap = bot.getThreatMap();
        if (threatMap == null) return;

        DifficultyProfile diff = bot.getDifficultyProfile();
        double iq = diff.getCounterPlayIQ();

        // Low-IQ bots barely analyze
        if (iq < 0.1) return;

        long currentTick = bot.getLocalTickCount();

        // Update profiles from visible threats
        List<ThreatMap.ThreatEntry> visibleThreats = threatMap.getVisibleThreats();
        for (ThreatMap.ThreatEntry threat : visibleThreats) {
            updateFromThreatEntry(threat, currentTick, iq);
        }

        // Age the cache
        cacheAge++;
        if (cacheAge >= CACHE_INVALIDATE_INTERVAL) {
            cachedCounters.clear();
            cacheAge = 0;
        }

        // Clean up stale profiles (not seen in 600 ticks = 30 seconds)
        profiles.entrySet().removeIf(entry ->
                currentTick - entry.getValue().lastObservedTick > 600);
    }

    /**
     * Updates an enemy profile from a ThreatMap entry observation.
     */
    private void updateFromThreatEntry(@Nonnull ThreatMap.ThreatEntry threat,
                                       long currentTick, double iq) {
        if (threat.playerId == null) return;

        EnemyProfile profile = profiles.computeIfAbsent(
                threat.playerId, EnemyProfile::new);

        profile.lastObservedTick = currentTick;
        profile.observationCount++;

        // Analyze velocity: fast + toward bot = rusher
        if (threat.velocity != null) {
            double speed = threat.velocity.length();
            LivingEntity botEntity = bot.getLivingEntity();
            if (botEntity != null && threat.currentPosition != null) {
                double dx = botEntity.getLocation().getX() - threat.currentPosition.getX();
                double dz = botEntity.getLocation().getZ() - threat.currentPosition.getZ();
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist > 1.0) {
                    double dot = (dx / dist) * threat.velocity.getX()
                            + (dz / dist) * threat.velocity.getZ();
                    // Moving toward bot at speed
                    if (dot > 0.15 && speed > 0.2) {
                        profile.directApproachCount++;
                    }
                }
            }
        }

        // Infer combat style (needs enough observations)
        if (profile.observationCount > 5 && iq > 0.2) {
            inferCombatStyle(profile, iq);
        }

        // Estimate skill from hit ratios (with IQ noise)
        if (profile.hitsLandedOnBot + profile.hitsLandedOnEnemy > 3) {
            double rawSkill = (double) profile.hitsLandedOnBot /
                    (profile.hitsLandedOnBot + profile.hitsLandedOnEnemy);
            // Add noise inversely proportional to IQ
            double noise = (1.0 - iq) * (RandomUtil.nextDouble() - 0.5) * 0.3;
            profile.estimatedSkillLevel = Math.max(0.0, Math.min(1.0, rawSkill + noise));
        }
    }

    /**
     * Infers the combat style from accumulated observations.
     */
    private void inferCombatStyle(@Nonnull EnemyProfile profile, double iq) {
        // Count evidence for each style
        int aggressiveEvidence = profile.directApproachCount;
        int projectileEvidence = profile.usesProjectiles ? 3 : 0;
        int tricksterEvidence = (profile.usesBait ? 3 : 0) + (profile.usesRod ? 2 : 0);
        int passiveEvidence = profile.retreatsAtLowHP ? 3 : 0;

        // Noisy inference: lower IQ = more likely to misidentify
        if (iq < 0.5) {
            aggressiveEvidence += RandomUtil.nextInt(0, 3);
            projectileEvidence += RandomUtil.nextInt(0, 2);
        }

        // Pick the style with the most evidence
        int maxEvidence = 0;
        EnemyProfile.CombatStyle bestStyle = EnemyProfile.CombatStyle.BALANCED;

        if (aggressiveEvidence > maxEvidence) {
            maxEvidence = aggressiveEvidence;
            bestStyle = EnemyProfile.CombatStyle.AGGRESSIVE;
        }
        if (projectileEvidence > maxEvidence) {
            maxEvidence = projectileEvidence;
            bestStyle = EnemyProfile.CombatStyle.PROJECTILE;
        }
        if (tricksterEvidence > maxEvidence) {
            maxEvidence = tricksterEvidence;
            bestStyle = EnemyProfile.CombatStyle.TRICKSTER;
        }
        if (passiveEvidence > maxEvidence) {
            maxEvidence = passiveEvidence;
            bestStyle = EnemyProfile.CombatStyle.PASSIVE;
        }

        // Only update if we have minimum evidence threshold
        if (maxEvidence >= 2) {
            profile.observedCombatStyle = bestStyle;
        }
    }

    // ─── Query Methods ──────────────────────────────────────────

    /**
     * Returns the analyzed profile for a specific enemy, or null if unknown.
     *
     * @param playerId the enemy player UUID
     * @return the enemy profile, or null
     */
    @Nullable
    public EnemyProfile getEnemyProfile(@Nonnull UUID playerId) {
        return profiles.get(playerId);
    }

    /**
     * Returns counter modifiers for fighting a specific enemy. Uses cached
     * results when available.
     *
     * @param playerId the enemy player UUID
     * @return counter modifiers, or default neutral modifiers if enemy is unknown
     */
    @Nonnull
    public CounterModifiers getCounterModifiers(@Nonnull UUID playerId) {
        // Check cache first
        CounterModifiers cached = cachedCounters.get(playerId);
        if (cached != null) return cached;

        EnemyProfile profile = profiles.get(playerId);
        if (profile == null) return new CounterModifiers();

        CounterModifiers mods = CounterStrategySelector.selectCounter(bot, profile);
        cachedCounters.put(playerId, mods);
        return mods;
    }

    /**
     * Notifies the analyzer that the bot landed a hit on an enemy.
     *
     * @param enemyId the hit enemy's UUID
     */
    public void onHitLandedOnEnemy(@Nonnull UUID enemyId) {
        EnemyProfile profile = profiles.computeIfAbsent(enemyId, EnemyProfile::new);
        profile.hitsLandedOnEnemy++;
    }

    /**
     * Notifies the analyzer that an enemy landed a hit on the bot.
     *
     * @param attackerId the attacker's UUID
     */
    public void onHitReceivedFromEnemy(@Nonnull UUID attackerId) {
        EnemyProfile profile = profiles.computeIfAbsent(attackerId, EnemyProfile::new);
        profile.hitsLandedOnBot++;
    }

    /**
     * Notifies that an enemy used a projectile (bow, snowball, etc.).
     *
     * @param enemyId the enemy's UUID
     */
    public void onEnemyUsedProjectile(@Nonnull UUID enemyId) {
        EnemyProfile profile = profiles.computeIfAbsent(enemyId, EnemyProfile::new);
        profile.usesProjectiles = true;
    }

    /**
     * Notifies that an enemy performed a bait/fake retreat.
     *
     * @param enemyId the enemy's UUID
     */
    public void onEnemyBaited(@Nonnull UUID enemyId) {
        EnemyProfile profile = profiles.computeIfAbsent(enemyId, EnemyProfile::new);
        profile.usesBait = true;
    }

    /**
     * Notifies that an enemy used a fishing rod.
     *
     * @param enemyId the enemy's UUID
     */
    public void onEnemyUsedRod(@Nonnull UUID enemyId) {
        EnemyProfile profile = profiles.computeIfAbsent(enemyId, EnemyProfile::new);
        profile.usesRod = true;
    }

    /**
     * Notifies that an enemy threw an ender pearl.
     *
     * @param enemyId the enemy's UUID
     */
    public void onEnemyPearled(@Nonnull UUID enemyId) {
        EnemyProfile profile = profiles.computeIfAbsent(enemyId, EnemyProfile::new);
        profile.pearledRecently = true;
    }

    /**
     * Returns all tracked enemy profiles.
     *
     * @return unmodifiable map of all profiles
     */
    @Nonnull
    public Map<UUID, EnemyProfile> getAllProfiles() {
        return Collections.unmodifiableMap(profiles);
    }

    /**
     * Clears all enemy profiles and cached counters. Called on game reset.
     */
    public void reset() {
        profiles.clear();
        cachedCounters.clear();
        cacheAge = 0;
    }
}
