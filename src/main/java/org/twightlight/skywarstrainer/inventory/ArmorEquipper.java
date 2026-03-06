package org.twightlight.skywarstrainer.inventory;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.twightlight.skywarstrainer.bot.TrainerBot;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Auto-equips the best armor found in the bot's inventory.
 * Priority: diamond > iron > chain > gold > leather.
 * Accounts for enchantments when comparing pieces.
 */
public class ArmorEquipper {

    private final TrainerBot bot;

    /** Armor material tier values for comparison. */
    private static final int DIAMOND_TIER = 5;
    private static final int IRON_TIER = 4;
    private static final int CHAIN_TIER = 3;
    private static final int GOLD_TIER = 2;
    private static final int LEATHER_TIER = 1;

    public ArmorEquipper(@Nonnull TrainerBot bot) {
        this.bot = bot;
    }

    /**
     * Scans inventory and equips the best armor in each slot.
     *
     * @param player the bot's player entity
     */
    public void equipBestArmor(@Nonnull Player player) {
        PlayerInventory inv = player.getInventory();
        equipSlot(inv, ArmorSlot.HELMET);
        equipSlot(inv, ArmorSlot.CHESTPLATE);
        equipSlot(inv, ArmorSlot.LEGGINGS);
        equipSlot(inv, ArmorSlot.BOOTS);
    }

    private void equipSlot(@Nonnull PlayerInventory inv, @Nonnull ArmorSlot slot) {
        ItemStack current = getEquipped(inv, slot);
        int currentScore = scoreArmor(current);

        // Search inventory for better armor
        ItemStack best = current;
        int bestScore = currentScore;
        int bestIndex = -1;

        for (int i = 0; i < 36; i++) {
            ItemStack item = inv.getItem(i);
            if (item == null) continue;
            if (!isArmorForSlot(item.getType(), slot)) continue;
            int score = scoreArmor(item);
            if (score > bestScore) {
                bestScore = score;
                best = item;
                bestIndex = i;
            }
        }

        // Equip if better found
        if (bestIndex >= 0 && best != null) {
            setEquipped(inv, slot, best);
            if (current != null && current.getType() != Material.AIR) {
                inv.setItem(bestIndex, current);
            } else {
                inv.setItem(bestIndex, null);
            }
        }
    }

    private int scoreArmor(@Nullable ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return 0;
        int tier = getArmorTier(item.getType());
        int enchantBonus = 0;
        if (item.getEnchantments() != null) {
            enchantBonus = item.getEnchantments().values().stream().mapToInt(Integer::intValue).sum();
        }
        return tier * 10 + enchantBonus;
    }

    private int getArmorTier(@Nonnull Material mat) {
        String name = mat.name();
        if (name.startsWith("DIAMOND_")) return DIAMOND_TIER;
        if (name.startsWith("IRON_")) return IRON_TIER;
        if (name.startsWith("CHAINMAIL_")) return CHAIN_TIER;
        if (name.startsWith("GOLD_")) return GOLD_TIER;
        if (name.startsWith("LEATHER_")) return LEATHER_TIER;
        return 0;
    }

    private boolean isArmorForSlot(@Nonnull Material mat, @Nonnull ArmorSlot slot) {
        String name = mat.name();
        switch (slot) {
            case HELMET: return name.endsWith("_HELMET");
            case CHESTPLATE: return name.endsWith("_CHESTPLATE");
            case LEGGINGS: return name.endsWith("_LEGGINGS");
            case BOOTS: return name.endsWith("_BOOTS");
        }
        return false;
    }

    @Nullable
    private ItemStack getEquipped(@Nonnull PlayerInventory inv, @Nonnull ArmorSlot slot) {
        switch (slot) {
            case HELMET: return inv.getHelmet();
            case CHESTPLATE: return inv.getChestplate();
            case LEGGINGS: return inv.getLeggings();
            case BOOTS: return inv.getBoots();
        }
        return null;
    }

    private void setEquipped(@Nonnull PlayerInventory inv, @Nonnull ArmorSlot slot, @Nonnull ItemStack item) {
        switch (slot) {
            case HELMET: inv.setHelmet(item); break;
            case CHESTPLATE: inv.setChestplate(item); break;
            case LEGGINGS: inv.setLeggings(item); break;
            case BOOTS: inv.setBoots(item); break;
        }
    }

    private enum ArmorSlot { HELMET, CHESTPLATE, LEGGINGS, BOOTS }
}
