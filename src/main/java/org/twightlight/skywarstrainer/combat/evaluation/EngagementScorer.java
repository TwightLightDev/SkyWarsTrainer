package org.twightlight.skywarstrainer.combat.evaluation;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.twightlight.skywarstrainer.awareness.ThreatMap;
import org.twightlight.skywarstrainer.awareness.VoidDetector;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.util.MathUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Scores how favorable the current combat engagement is for the bot.
 *
 * <p>The EngagementScorer produces a value from 0.0 (terrible situation, should
 * disengage) to 1.0 (dominant position, should press the advantage). This score
 * is used by the DecisionEngine when deciding whether to continue fighting or
 * switch to a different action (flee, loot, etc.).</p>
 *
 * <p>Factors considered:
 * <ul>
 *   <li><b>Health advantage:</b> Bot HP vs. enemy HP ratio</li>
 *   <li><b>Equipment advantage:</b> Bot's gear quality vs. enemy's visible gear</li>
 *   <li><b>Position advantage:</b> High ground, distance from void, cover</li>
 *   <li><b>Combo state:</b> Landing a combo = high engagement score;
 *       being comboed = low score</li>
 *   <li><b>Numerical advantage:</b> 1v1 is neutral; 1v2 is bad; having
 *       teammates nearby is good</li>
 *   <li><b>Resource advantage:</b> Golden apples, potions, blocks available</li>
 * </ul></p>
 *
 * <p>The quality of assessment scales with {@code decisionQuality}. Low quality
 * bots may misjudge engagement favorability, leading to overcommitting to bad
 * fights or retreating from winnable ones.</p>
 */
public class EngagementScorer {

    private final TrainerBot bot;

    /**
     * Creates a new EngagementScorer for the given bot.
     *
     * @param bot the owning trainer bot
     */
    public EngagementScorer(@Nonnull TrainerBot bot) {
        this.bot = bot;
    }

    /**
     * Scores the current engagement against a specific target.
     *
     * @param target the combat target to evaluate against
     * @return a score from 0.0 (terrible) to 1.0 (dominant)
     */
    public double score(@Nonnull LivingEntity target) {
        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity == null || botEntity.isDead()) return 0.0;
        if (target.isDead()) return 1.0; // Target is dead — we won

        DifficultyProfile diff = bot.getDifficultyProfile();

        double totalScore = 0.0;
        double totalWeight = 0.0;

        // ── 1. Health Advantage (weight: 3.0) ──────────────────
        double healthScore = scoreHealthAdvantage(botEntity, target);
        totalScore += healthScore * 3.0;
        totalWeight += 3.0;

        // ── 2. Equipment Advantage (weight: 2.5) ───────────────
        double equipScore = scoreEquipmentAdvantage(botEntity, target);
        totalScore += equipScore * 2.5;
        totalWeight += 2.5;

        // ── 3. Position Advantage (weight: 2.0) ────────────────
        double positionScore = scorePositionAdvantage(botEntity, target);
        totalScore += positionScore * 2.0;
        totalWeight += 2.0;

        // ── 4. Combo State (weight: 1.5) ───────────────────────
        double comboScore = scoreComboState();
        totalScore += comboScore * 1.5;
        totalWeight += 1.5;

        // ── 5. Numerical Advantage (weight: 2.0) ───────────────
        double numericalScore = scoreNumericalAdvantage();
        totalScore += numericalScore * 2.0;
        totalWeight += 2.0;

        // ── 6. Resource Advantage (weight: 1.0) ────────────────
        double resourceScore = scoreResourceAdvantage(botEntity);
        totalScore += resourceScore * 1.0;
        totalWeight += 1.0;

        // Normalize to [0, 1]
        double normalizedScore = totalWeight > 0 ? totalScore / totalWeight : 0.5;

        // Apply decision quality noise
        double noiseRange = (1.0 - diff.getDecisionQuality()) * 0.2;
        double noise = org.twightlight.skywarstrainer.util.RandomUtil.nextDouble(-noiseRange, noiseRange);
        normalizedScore += noise;

        return MathUtil.clamp(normalizedScore, 0.0, 1.0);
    }

    /**
     * Scores the bot's health advantage relative to the target.
     * 0.0 = bot almost dead, target full HP.
     * 0.5 = equal HP.
     * 1.0 = bot full HP, target almost dead.
     *
     * @param botEntity the bot
     * @param target    the enemy
     * @return health advantage score [0, 1]
     */
    private double scoreHealthAdvantage(@Nonnull LivingEntity botEntity,
                                        @Nonnull LivingEntity target) {
        double botHpFraction = botEntity.getHealth() / botEntity.getMaxHealth();
        double targetHpFraction = target.getHealth() / target.getMaxHealth();

        // Difference: positive means bot has more HP
        double hpDiff = botHpFraction - targetHpFraction;

        // Map [-1, 1] to [0, 1]
        return MathUtil.clamp((hpDiff + 1.0) / 2.0, 0.0, 1.0);
    }

    /**
     * Scores the bot's equipment advantage relative to the target.
     * Compares held weapon tier and visible armor tier.
     *
     * @param botEntity the bot
     * @param target    the enemy
     * @return equipment advantage score [0, 1]
     */
    private double scoreEquipmentAdvantage(@Nonnull LivingEntity botEntity,
                                           @Nonnull LivingEntity target) {
        double botGearScore = evaluateEntityGear(botEntity);
        double targetGearScore = evaluateEntityGear(target);

        double gearDiff = botGearScore - targetGearScore;

        // Map [-1, 1] to [0, 1]
        return MathUtil.clamp((gearDiff + 1.0) / 2.0, 0.0, 1.0);
    }

    /**
     * Evaluates an entity's overall gear quality as a 0.0-1.0 score.
     *
     * @param entity the entity to evaluate
     * @return gear quality score
     */
    private double evaluateEntityGear(@Nonnull LivingEntity entity) {
        if (!(entity instanceof Player)) return 0.3;
        Player player = (Player) entity;

        double weaponScore = 0.0;
        ItemStack hand = player.getItemInHand();
        if (hand != null) {
            weaponScore = scoreWeapon(hand.getType());
        }

        double armorScore = 0.0;
        PlayerInventory inv = player.getInventory();
        armorScore += scoreArmorMaterial(inv.getHelmet());
        armorScore += scoreArmorMaterial(inv.getChestplate());
        armorScore += scoreArmorMaterial(inv.getLeggings());
        armorScore += scoreArmorMaterial(inv.getBoots());
        armorScore /= 4.0;

        // Weight: armor 60%, weapon 40% (armor has more impact on survivability)
        return armorScore * 0.6 + weaponScore * 0.4;
    }

    /**
     * Scores a weapon material on a 0.0-1.0 scale.
     *
     * @param type the material type
     * @return weapon score
     */
    private double scoreWeapon(@Nullable Material type) {
        if (type == null) return 0.0;
        if (type == Material.DIAMOND_SWORD) return 1.0;
        if (type == Material.IRON_SWORD) return 0.75;
        if (type == Material.STONE_SWORD) return 0.5;
        if (type == Material.GOLD_SWORD) return 0.35;
        if (type == Material.WOOD_SWORD) return 0.2;
        if (type.name().contains("AXE")) return 0.4;
        if (type == Material.BOW) return 0.55;
        return 0.05;
    }

    /**
     * Scores an armor piece by its material tier.
     *
     * @param item the armor item, or null
     * @return armor piece score [0, 1]
     */
    private double scoreArmorMaterial(@Nullable ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return 0.0;
        String name = item.getType().name();
        if (name.startsWith("DIAMOND_")) return 1.0;
        if (name.startsWith("IRON_")) return 0.7;
        if (name.startsWith("CHAINMAIL_")) return 0.55;
        if (name.startsWith("GOLD_")) return 0.4;
        if (name.startsWith("LEATHER_")) return 0.2;
        return 0.0;
    }

    /**
     * Scores the bot's positional advantage: high ground, distance from void,
     * cover from obstacles.
     *
     * @param botEntity the bot
     * @param target    the enemy
     * @return position advantage score [0, 1]
     */
    private double scorePositionAdvantage(@Nonnull LivingEntity botEntity,
                                          @Nonnull LivingEntity target) {
        double score = 0.5; // Neutral baseline

        // Height advantage: being higher is better in 1.8 PvP (better reach angles)
        double heightDiff = botEntity.getLocation().getY() - target.getLocation().getY();
        if (heightDiff > 0.5) {
            score += MathUtil.clamp(heightDiff / 5.0, 0.0, 0.2); // Up to +0.2 for 5+ blocks higher
        } else if (heightDiff < -0.5) {
            score -= MathUtil.clamp(-heightDiff / 5.0, 0.0, 0.2); // Penalty for being lower
        }

        // Void proximity penalty: being near void reduces engagement score
        VoidDetector voidDetector = bot.getVoidDetector();
        if (voidDetector != null) {
            if (voidDetector.isOnEdge()) {
                score -= 0.2; // Dangerous position
            } else if (voidDetector.isNearVoidEdge()) {
                score -= 0.1; // Somewhat risky
            }
        }

        // Check if the enemy is near void (advantage for us — we can push them off)
        // Simple heuristic: check blocks around enemy's feet
        Location targetLoc = target.getLocation();
        if (targetLoc.getWorld() != null) {
            int airCount = 0;
            int[][] offsets = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int[] off : offsets) {
                Location check = targetLoc.clone().add(off[0], -1, off[1]);
                if (check.getBlock().getType() == Material.AIR) {
                    airCount++;
                }
            }
            if (airCount >= 2) {
                score += 0.15; // Enemy is on a narrow platform — advantage for us
            }
        }

        return MathUtil.clamp(score, 0.0, 1.0);
    }

    /**
     * Scores the current combo state. Landing a combo = good engagement;
     * being comboed = bad engagement.
     *
     * @return combo state score [0, 1]
     */
    private double scoreComboState() {
        // Access combo tracker through the combat engine
        // If not available (combat engine not yet ticking), return neutral
        if (bot.getCombatEngine() == null) return 0.5;

        org.twightlight.skywarstrainer.combat.ComboTracker tracker =
                bot.getCombatEngine().getComboTracker();

        if (tracker.isInCombo()) {
            // We're comboing the enemy — great position
            int landed = tracker.getHitsLanded();
            return MathUtil.clamp(0.6 + landed * 0.05, 0.6, 0.9);
        }

        if (tracker.isBeingComboed()) {
            // Enemy is comboing us — bad position
            int received = tracker.getHitsReceived();
            return MathUtil.clamp(0.4 - received * 0.05, 0.1, 0.4);
        }

        return 0.5; // Neutral — no active combo either way
    }

    /**
     * Scores the numerical advantage: 1v1 is neutral, 1v2+ is bad.
     *
     * @return numerical advantage score [0, 1]
     */
    private double scoreNumericalAdvantage() {
        ThreatMap threatMap = bot.getThreatMap();
        if (threatMap == null) return 0.5;

        int enemyCount = threatMap.getVisibleEnemyCount();

        if (enemyCount <= 0) return 1.0; // No visible enemies — safe
        if (enemyCount == 1) return 0.5; // 1v1 — neutral
        if (enemyCount == 2) return 0.25; // 1v2 — disadvantage
        return 0.1; // 1v3+ — severe disadvantage
    }

    /**
     * Scores the bot's resource availability for sustained combat.
     * Golden apples, potions, and blocks contribute to combat sustainability.
     *
     * @param botEntity the bot entity
     * @return resource advantage score [0, 1]
     */
    private double scoreResourceAdvantage(@Nonnull LivingEntity botEntity) {
        if (!(botEntity instanceof Player)) return 0.3;
        Player player = (Player) botEntity;

        double score = 0.0;

        // Golden apples
        int gapples = countMaterial(player, Material.GOLDEN_APPLE);
        score += MathUtil.clamp(gapples * 0.15, 0.0, 0.3);

        // Blocks for clutching/building
        int blocks = countBuildingBlocks(player);
        score += MathUtil.clamp(blocks / 64.0 * 0.2, 0.0, 0.2);

        // Ender pearls for escape
        int pearls = countMaterial(player, Material.ENDER_PEARL);
        if (pearls > 0) score += 0.1;

        // Food
        int food = countFood(player);
        score += MathUtil.clamp(food / 10.0 * 0.1, 0.0, 0.1);

        // Projectiles
        int projectiles = countMaterial(player, Material.SNOW_BALL)
                + countMaterial(player, Material.EGG);
        if (projectiles > 0) score += 0.05;

        // Bow + arrows
        if (player.getInventory().contains(Material.BOW)
                && player.getInventory().contains(Material.ARROW)) {
            score += 0.1;
        }

        // Potions (any beneficial potion)
        int potions = countPotions(player);
        score += MathUtil.clamp(potions * 0.05, 0.0, 0.15);

        return MathUtil.clamp(score, 0.0, 1.0);
    }

    /**
     * Counts items of a specific material in the player's inventory.
     *
     * @param player   the player
     * @param material the material to count
     * @return the total item count
     */
    private int countMaterial(@Nonnull Player player, @Nonnull Material material) {
        int count = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && stack.getType() == material) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    /**
     * Counts building blocks in the player's inventory.
     *
     * @param player the player
     * @return total building block count
     */
    private int countBuildingBlocks(@Nonnull Player player) {
        int count = 0;
        Material[] buildBlocks = {
                Material.COBBLESTONE, Material.STONE, Material.WOOL,
                Material.WOOD, Material.SANDSTONE, Material.DIRT,
                Material.SAND, Material.NETHERRACK
        };
        for (Material mat : buildBlocks) {
            count += countMaterial(player, mat);
        }
        return count;
    }

    /**
     * Counts food items in the player's inventory.
     *
     * @param player the player
     * @return total food item count
     */
    private int countFood(@Nonnull Player player) {
        int count = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && stack.getType().isEdible()) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    /**
     * Counts potion items in the player's inventory.
     *
     * @param player the player
     * @return total potion count
     */
    private int countPotions(@Nonnull Player player) {
        int count = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && stack.getType() == Material.POTION) {
                count += stack.getAmount();
            }
        }
        return count;
    }
}
