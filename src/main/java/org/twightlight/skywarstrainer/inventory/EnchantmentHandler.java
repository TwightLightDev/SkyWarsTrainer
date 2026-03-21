package org.twightlight.skywarstrainer.inventory;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;

/**
 * Handles enchanting table interactions. The bot walks to an enchanting table,
 * and applies the best available enchantment to its gear.
 */
public class EnchantmentHandler {

    private final TrainerBot bot;

    public EnchantmentHandler(@Nonnull TrainerBot bot) {
        this.bot = bot;
    }

    /**
     * Returns true if the bot should try to enchant (has levels and an unenchanted weapon/armor).
     *
     * @return true if enchanting is worthwhile
     */
    public boolean shouldEnchant() {
        Player player = bot.getPlayerEntity();
        if (player == null) return false;
        if (player.getLevel() < 1) return false;

        // Check if sword is unenchanted
        if (player.getInventory().getItem(0) != null) {
            Material mat = player.getInventory().getItem(0).getType();
            if (mat.name().endsWith("_SWORD") && player.getInventory().getItem(0).getEnchantments().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Applies a simulated enchantment to an item based on available XP levels.
     * Since NPCs can't interact with enchanting table GUIs, we directly add
     * appropriate enchantments based on the item type and available levels.
     *
     * @param item  the item to enchant
     * @param level the player's current XP level
     */
    public void applySimulatedEnchant(@Nonnull org.bukkit.inventory.ItemStack item, int level) {
        String typeName = item.getType().name();
        int tier = Math.min(3, Math.max(1, level / 5)); // tier 1-3 based on levels

        if (typeName.contains("SWORD")) {
            item.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.DAMAGE_ALL, tier); // Sharpness
            if (level >= 10 && RandomUtil.chance(0.5)) {
                item.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.FIRE_ASPECT, 1);
            }
            if (level >= 15 && RandomUtil.chance(0.3)) {
                item.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.KNOCKBACK, 1);
            }
        } else if (typeName.contains("BOW")) {
            item.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.ARROW_DAMAGE, tier); // Power
            if (level >= 10 && RandomUtil.chance(0.4)) {
                item.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.ARROW_KNOCKBACK, 1); // Punch
            }
        } else if (typeName.contains("HELMET") || typeName.contains("CHESTPLATE")
                || typeName.contains("LEGGINGS") || typeName.contains("BOOTS")) {
            item.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.PROTECTION_ENVIRONMENTAL, tier); // Protection
            if (typeName.contains("BOOTS") && level >= 8 && RandomUtil.chance(0.5)) {
                item.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.PROTECTION_FALL, tier); // Feather Falling
            }
        }
    }



}
