package org.twightlight.skywarstrainer.combat;

import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;

import javax.annotation.Nonnull;

/**
 * Calculates and applies knockback following vanilla 1.8 mechanics.
 *
 * <p>In Minecraft 1.8, knockback is composed of:
 * <ul>
 *   <li>Base horizontal KB (0.4 blocks)</li>
 *   <li>Sprint bonus (additional forward KB when attacker is sprinting)</li>
 *   <li>Knockback enchantment bonus (+0.5 per level)</li>
 *   <li>Vertical component (0.4 base, crucial for combos)</li>
 * </ul></p>
 *
 * <p>This calculator also handles KB reduction for the BOT when it's hit:
 * the antiKBReduction parameter allows the bot to partially counter incoming
 * knockback by moving into the hit, simulating what skilled players do.</p>
 */
public class KnockbackCalculator {

    /** Base horizontal knockback magnitude (vanilla 1.8). */
    private static final double BASE_KB = 0.4;
    /** Vertical knockback component (vanilla 1.8). */
    private static final double VERTICAL_KB = 0.4;
    /** Sprint attack additional horizontal KB (vanilla 1.8). */
    private static final double SPRINT_KB_BONUS = 0.4;
    /** Per-level Knockback enchantment bonus. */
    private static final double ENCHANT_KB_PER_LEVEL = 0.5;

    private final TrainerBot bot;

    /**
     * Creates a KnockbackCalculator for the given bot.
     *
     * @param bot the owning bot
     */
    public KnockbackCalculator(@Nonnull TrainerBot bot) {
        this.bot = bot;
    }

    /**
     * Calculates the knockback vector that would be applied to a target when
     * the bot attacks it.
     *
     * @param target     the entity being knocked back
     * @param isSprinting whether the bot is sprint-attacking
     * @return the knockback velocity vector to apply to the target
     */
    @Nonnull
    public Vector calculateAttackKnockback(@Nonnull LivingEntity target, boolean isSprinting) {
        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity == null) return new Vector(0, 0, 0);

        // Direction from attacker to target (horizontal only)
        Vector direction = target.getLocation().toVector()
                .subtract(botEntity.getLocation().toVector());
        direction.setY(0);
        if (direction.lengthSquared() > 0) {
            direction.normalize();
        } else {
            // Fallback: use attacker's look direction
            direction = botEntity.getLocation().getDirection();
            direction.setY(0);
            if (direction.lengthSquared() > 0) direction.normalize();
        }

        // Base KB
        double horizontalKB = BASE_KB;

        // Sprint bonus
        if (isSprinting) {
            horizontalKB += SPRINT_KB_BONUS;
        }

        // Knockback enchantment
        Player player = bot.getPlayerEntity();
        if (player != null) {
            ItemStack weapon = player.getItemInHand();
            if (weapon != null && weapon.containsEnchantment(Enchantment.KNOCKBACK)) {
                int level = weapon.getEnchantmentLevel(Enchantment.KNOCKBACK);
                horizontalKB += level * ENCHANT_KB_PER_LEVEL;
            }
        }

        return direction.multiply(horizontalKB).setY(VERTICAL_KB);
    }

    /**
     * Reduces incoming knockback applied to the bot based on the antiKBReduction
     * parameter. Simulates a skilled player moving into a hit to reduce KB.
     *
     * @param incomingKB the knockback vector being applied to the bot
     * @return the reduced knockback vector
     */
    @Nonnull
    public Vector reduceIncomingKnockback(@Nonnull Vector incomingKB) {
        DifficultyProfile diff = bot.getDifficultyProfile();
        double reduction = diff.getAntiKBReduction();

        if (reduction <= 0.0) return incomingKB.clone();

        // Reduce horizontal components; vertical is harder to counter
        double reducedX = incomingKB.getX() * (1.0 - reduction);
        double reducedZ = incomingKB.getZ() * (1.0 - reduction);
        double reducedY = incomingKB.getY() * (1.0 - reduction * 0.5);

        return new Vector(reducedX, reducedY, reducedZ);
    }

    /**
     * Estimates whether a sprint-hit would produce enough KB to start a combo.
     *
     * @return true if the bot should sprint-reset for combo potential
     */
    public boolean shouldSprintForCombo() {
        DifficultyProfile diff = bot.getDifficultyProfile();
        return diff.getSprintResetChance() > 0
                && org.twightlight.skywarstrainer.util.RandomUtil.chance(diff.getSprintResetChance());
    }
}
