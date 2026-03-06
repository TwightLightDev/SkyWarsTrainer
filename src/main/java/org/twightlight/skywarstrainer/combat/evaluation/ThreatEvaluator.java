package org.twightlight.skywarstrainer.combat.evaluation;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.twightlight.skywarstrainer.awareness.ThreatMap;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.util.MathUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Evaluates and scores each visible enemy to determine the best combat target.
 *
 * <p>The ThreatEvaluator considers multiple factors per enemy:
 * <ul>
 *   <li><b>Distance:</b> Closer enemies are higher threat (they can attack sooner)</li>
 *   <li><b>Weapon:</b> Diamond sword > iron > stone > wood (visible held item)</li>
 *   <li><b>Armor:</b> Full diamond armor = high threat; leather/none = low</li>
 *   <li><b>Action:</b> Enemy attacking the bot = highest threat;
 *       bridging = medium; idle = lower</li>
 *   <li><b>Health:</b> Low HP enemy = opportunity (target for finish), not threat</li>
 *   <li><b>Count:</b> Multiple enemies nearby amplifies the overall threat level</li>
 * </ul></p>
 *
 * <p>The evaluator returns a composite score per enemy. The CombatEngine selects
 * the highest-scoring enemy as the primary target. Score interpretation depends
 * on context: the same score may represent "high threat to avoid" (for flee
 * decisions) or "best target to attack" (for fight decisions).</p>
 *
 * <p>The quality of evaluation scales with the bot's {@code decisionQuality}
 * parameter. Lower quality adds noise to the scores, causing the bot to
 * occasionally pick suboptimal targets — simulating poor judgment.</p>
 */
public class ThreatEvaluator {

    private final TrainerBot bot;

    // ── Scoring Weights ──

    /** Weight for distance component in the threat score. */
    private static final double WEIGHT_DISTANCE = 3.0;

    /** Weight for weapon quality component. */
    private static final double WEIGHT_WEAPON = 2.0;

    /** Weight for armor quality component. */
    private static final double WEIGHT_ARMOR = 2.5;

    /** Weight for current action (attacking vs. passive). */
    private static final double WEIGHT_ACTION = 4.0;

    /** Weight for low-HP opportunity bonus. */
    private static final double WEIGHT_OPPORTUNITY = 3.0;

    /** Weight for multi-enemy threat penalty. */
    private static final double WEIGHT_MULTI_ENEMY = 1.5;

    /**
     * Creates a new ThreatEvaluator for the given bot.
     *
     * @param bot the owning trainer bot
     */
    public ThreatEvaluator(@Nonnull TrainerBot bot) {
        this.bot = bot;
    }

    /**
     * Evaluates a single threat entry and returns a composite score.
     *
     * <p>Higher scores indicate more important targets — either because they are
     * dangerous threats that must be dealt with, or because they are weak
     * opportunities for a kill. The caller (CombatEngine) uses this to select
     * the primary target.</p>
     *
     * @param entry the threat entry to evaluate
     * @return a composite threat/priority score (higher = more important target)
     */
    public double evaluateThreat(@Nonnull ThreatMap.ThreatEntry entry) {
        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity == null || entry.currentPosition == null) return 0.0;

        DifficultyProfile diff = bot.getDifficultyProfile();
        Location botLoc = botEntity.getLocation();

        double score = 0.0;

        // ── 1. Distance Score ──────────────────────────────────
        // Closer enemies are higher priority targets.
        double distance = entry.distanceTo(botLoc);
        double maxRange = diff.getAwarenessRadius();
        double distanceScore = MathUtil.invertedLinearCurve(distance, 0, maxRange);
        score += distanceScore * WEIGHT_DISTANCE;

        // ── 2. Weapon Assessment ───────────────────────────────
        // Evaluate the weapon the enemy is holding (if visible).
        double weaponScore = evaluateEnemyWeapon(entry);
        score += weaponScore * WEIGHT_WEAPON;

        // ── 3. Armor Assessment ────────────────────────────────
        // Evaluate the visible armor the enemy is wearing.
        double armorScore = evaluateEnemyArmor(entry);
        score += armorScore * WEIGHT_ARMOR;

        // ── 4. Action Assessment ───────────────────────────────
        // Is the enemy attacking the bot, moving toward the bot, bridging, or idle?
        double actionScore = evaluateEnemyAction(entry, botLoc);
        score += actionScore * WEIGHT_ACTION;

        // ── 5. Opportunity Assessment ──────────────────────────
        // Low-HP enemies are juicy targets — bonus for killable enemies.
        double opportunityScore = evaluateOpportunity(entry);
        score += opportunityScore * WEIGHT_OPPORTUNITY;

        // ── 6. Multi-Enemy Context ─────────────────────────────
        // If multiple enemies are visible, boost the nearest threat
        // (focus the closest one rather than splitting attention).
        ThreatMap threatMap = bot.getThreatMap();
        if (threatMap != null) {
            int enemyCount = threatMap.getVisibleEnemyCount();
            if (enemyCount > 1 && distanceScore > 0.5) {
                // Bonus for being the closest target when outnumbered
                score += (distanceScore * 0.5) * WEIGHT_MULTI_ENEMY;
            }
        }

        // ── 7. Decision Quality Noise ──────────────────────────
        // Add random noise based on decision quality. Lower quality = more noise
        // = more suboptimal target selection.
        double noiseRange = (1.0 - diff.getDecisionQuality()) * 3.0;
        double noise = org.twightlight.skywarstrainer.util.RandomUtil.nextDouble(-noiseRange, noiseRange);
        score += noise;

        return Math.max(0.0, score);
    }

    /**
     * Evaluates the enemy's held weapon quality on a 0.0-1.0 scale.
     *
     * <p>At higher decision quality, the bot can assess weapon tier from the
     * visible held item. At lower quality, this is less precise.</p>
     *
     * @param entry the threat entry
     * @return weapon quality score from 0.0 (unarmed) to 1.0 (diamond sword)
     */
    private double evaluateEnemyWeapon(@Nonnull ThreatMap.ThreatEntry entry) {
        // Try to resolve the entity from the UUID
        Player enemyPlayer = resolvePlayer(entry);
        if (enemyPlayer == null) {
            return 0.3; // Unknown — assume moderate
        }

        DifficultyProfile diff = bot.getDifficultyProfile();

        // At low decision quality, the bot can't accurately assess enemy gear
        if (diff.getDecisionQuality() < 0.4) {
            return 0.3 + org.twightlight.skywarstrainer.util.RandomUtil.nextDouble(0, 0.4);
        }

        ItemStack heldItem = enemyPlayer.getItemInHand();
        if (heldItem == null || heldItem.getType() == Material.AIR) {
            return 0.05; // Unarmed
        }

        Material type = heldItem.getType();
        if (type == Material.DIAMOND_SWORD) return 1.0;
        if (type == Material.IRON_SWORD) return 0.75;
        if (type == Material.STONE_SWORD) return 0.5;
        if (type == Material.GOLD_SWORD) return 0.35;
        if (type == Material.WOOD_SWORD) return 0.25;

        // Axes can deal decent damage
        if (type.name().contains("AXE")) return 0.4;

        // Bow
        if (type == Material.BOW) return 0.6;

        // Other items (not a weapon)
        return 0.1;
    }

    /**
     * Evaluates the enemy's visible armor quality on a 0.0-1.0 scale.
     *
     * <p>Diamond full set = 1.0, no armor = 0.0. The score represents how
     * DIFFICULT this enemy will be to kill, not how threatening they are
     * (that's determined by the composite score interpretation).</p>
     *
     * @param entry the threat entry
     * @return armor quality score from 0.0 (naked) to 1.0 (full diamond)
     */
    private double evaluateEnemyArmor(@Nonnull ThreatMap.ThreatEntry entry) {
        Player enemyPlayer = resolvePlayer(entry);
        if (enemyPlayer == null) {
            return 0.3; // Unknown — assume moderate
        }

        DifficultyProfile diff = bot.getDifficultyProfile();
        if (diff.getDecisionQuality() < 0.4) {
            return 0.3 + org.twightlight.skywarstrainer.util.RandomUtil.nextDouble(0, 0.4);
        }

        PlayerInventory inv = enemyPlayer.getInventory();
        double totalScore = 0.0;
        int pieces = 0;

        // Evaluate each armor piece
        totalScore += scoreArmorPiece(inv.getHelmet());
        totalScore += scoreArmorPiece(inv.getChestplate());
        totalScore += scoreArmorPiece(inv.getLeggings());
        totalScore += scoreArmorPiece(inv.getBoots());
        pieces = 4;

        return MathUtil.clamp(totalScore / pieces, 0.0, 1.0);
    }

    /**
     * Scores a single armor piece by material tier.
     *
     * @param item the armor item, or null if empty
     * @return a score from 0.0 to 1.0
     */
    private double scoreArmorPiece(@Nullable ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return 0.0;

        String typeName = item.getType().name();
        if (typeName.startsWith("DIAMOND_")) return 1.0;
        if (typeName.startsWith("IRON_")) return 0.7;
        if (typeName.startsWith("CHAINMAIL_")) return 0.55;
        if (typeName.startsWith("GOLD_")) return 0.4;
        if (typeName.startsWith("LEATHER_")) return 0.2;
        return 0.0;
    }

    /**
     * Evaluates the enemy's current action/behavior to determine how
     * threatening or vulnerable they are.
     *
     * <p>Score breakdown:
     * <ul>
     *   <li>Moving toward the bot = high threat (approaching for attack)</li>
     *   <li>Moving away = lower threat (fleeing or unaware)</li>
     *   <li>Low speed / stationary = could be camping, moderate threat</li>
     * </ul></p>
     *
     * @param entry  the threat entry
     * @param botLoc the bot's current location
     * @return action threat score from 0.0 to 1.0
     */
    private double evaluateEnemyAction(@Nonnull ThreatMap.ThreatEntry entry,
                                       @Nonnull Location botLoc) {
        if (entry.currentPosition == null || entry.velocity == null) return 0.3;

        // Check if the enemy is moving toward the bot
        org.bukkit.util.Vector toBot = MathUtil.directionTo(entry.currentPosition, botLoc);
        org.bukkit.util.Vector velocity = entry.velocity.clone();

        if (velocity.lengthSquared() < 0.001) {
            // Stationary — moderate threat (could be camping or AFK)
            return 0.3;
        }

        velocity.normalize();
        double dotProduct = toBot.dot(velocity);

        // dotProduct > 0: moving toward bot (threatening)
        // dotProduct < 0: moving away from bot (less threatening)
        // Map from [-1, 1] to [0, 1]
        double approachScore = (dotProduct + 1.0) / 2.0;

        // Speed factor — faster approach = more threatening
        double speed = entry.getHorizontalSpeed();
        double speedFactor = MathUtil.clamp(speed / 0.3, 0.0, 1.0); // 0.3 b/t ≈ sprint speed

        return approachScore * 0.7 + speedFactor * 0.3;
    }

    /**
     * Evaluates whether the enemy represents a kill opportunity.
     * Low-HP enemies get a high opportunity score — they should be targeted
     * for a quick finish.
     *
     * @param entry the threat entry
     * @return opportunity score from 0.0 (full HP) to 1.0 (almost dead)
     */
    private double evaluateOpportunity(@Nonnull ThreatMap.ThreatEntry entry) {
        Player enemyPlayer = resolvePlayer(entry);
        if (enemyPlayer == null) return 0.0;

        double healthFraction = enemyPlayer.getHealth() / enemyPlayer.getMaxHealth();

        // Inverse sigmoid: lower HP = higher opportunity
        // 50% HP → 0.5 score; 20% HP → ~0.9 score; 80% HP → ~0.1 score
        return MathUtil.sigmoid(1.0 - healthFraction, 8.0, 0.5);
    }

    /**
     * Resolves a ThreatEntry to a Player entity from the current world.
     *
     * @param entry the threat entry
     * @return the Player, or null if not found/not online
     */
    @Nullable
    private Player resolvePlayer(@Nonnull ThreatMap.ThreatEntry entry) {
        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity == null) return null;

        // Search nearby entities for the matching UUID
        double searchRadius = bot.getDifficultyProfile().getAwarenessRadius();
        for (Entity entity : botEntity.getNearbyEntities(searchRadius, searchRadius, searchRadius)) {
            if (entity.getUniqueId().equals(entry.playerId) && entity instanceof Player) {
                return (Player) entity;
            }
        }
        return null;
    }

    /**
     * Evaluates the overall threat level from all visible enemies.
     * Returns a composite score indicating how dangerous the bot's current
     * situation is.
     *
     * @return overall threat level from 0.0 (safe) to 1.0+ (extremely dangerous)
     */
    public double evaluateOverallThreat() {
        ThreatMap threatMap = bot.getThreatMap();
        if (threatMap == null) return 0.0;

        java.util.List<ThreatMap.ThreatEntry> visible = threatMap.getVisibleThreats();
        if (visible.isEmpty()) return 0.0;

        double totalThreat = 0.0;
        for (ThreatMap.ThreatEntry entry : visible) {
            totalThreat += evaluateThreat(entry);
        }

        // Normalize: single enemy's max score is roughly WEIGHT_SUM (~16).
        // Multiple enemies compound the threat.
        double maxSingleThreat = WEIGHT_DISTANCE + WEIGHT_WEAPON + WEIGHT_ARMOR
                + WEIGHT_ACTION + WEIGHT_OPPORTUNITY + WEIGHT_MULTI_ENEMY;

        return MathUtil.clamp(totalThreat / maxSingleThreat, 0.0, 2.0);
    }

    /**
     * Returns whether the bot should prefer to flee given the current threat
     * assessment. This is a high-level recommendation, not a command.
     *
     * @return true if the threat level suggests fleeing
     */
    public boolean shouldRecommendFlee() {
        double overallThreat = evaluateOverallThreat();
        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity == null) return false;

        double healthFraction = botEntity.getHealth() / botEntity.getMaxHealth();
        DifficultyProfile diff = bot.getDifficultyProfile();

        // Recommend flee if: low HP AND high threat
        return healthFraction < diff.getFleeHealthThreshold() && overallThreat > 0.6;
    }
}
