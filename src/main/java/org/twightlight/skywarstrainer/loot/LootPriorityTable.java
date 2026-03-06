package org.twightlight.skywarstrainer.loot;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.twightlight.skywarstrainer.awareness.GamePhaseTracker;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.Map;

/**
 * Defines item priority rankings by game phase (early, mid, late).
 *
 * <p>The LootPriorityTable assigns a numeric value (0.0–10.0) to each item type,
 * with higher values indicating greater importance. These values drive loot
 * decisions: which items to take first, what to skip, and what to deny enemies.</p>
 *
 * <p>Priority shifts by game phase:
 * <ul>
 *   <li>Early: weapons and armor are critical; blocks are essential for bridging</li>
 *   <li>Mid: enchanted gear and golden apples become high priority</li>
 *   <li>Late: golden apples, ender pearls, and potions dominate</li>
 * </ul></p>
 */
public class LootPriorityTable {

    /** Early game priorities (first 60 seconds). */
    private static final Map<Material, Double> EARLY_PRIORITIES = new EnumMap<>(Material.class);
    /** Mid game priorities (60-180 seconds). */
    private static final Map<Material, Double> MID_PRIORITIES = new EnumMap<>(Material.class);
    /** Late game priorities (180+ seconds). */
    private static final Map<Material, Double> LATE_PRIORITIES = new EnumMap<>(Material.class);

    static {
        // ── Early Game ──
        // Swords
        EARLY_PRIORITIES.put(Material.DIAMOND_SWORD, 10.0);
        EARLY_PRIORITIES.put(Material.IRON_SWORD, 9.0);
        EARLY_PRIORITIES.put(Material.STONE_SWORD, 8.0);
        EARLY_PRIORITIES.put(Material.GOLD_SWORD, 7.0);
        EARLY_PRIORITIES.put(Material.WOOD_SWORD, 6.5);
        // Armor
        EARLY_PRIORITIES.put(Material.DIAMOND_HELMET, 9.0);
        EARLY_PRIORITIES.put(Material.DIAMOND_CHESTPLATE, 9.5);
        EARLY_PRIORITIES.put(Material.DIAMOND_LEGGINGS, 9.0);
        EARLY_PRIORITIES.put(Material.DIAMOND_BOOTS, 9.0);
        EARLY_PRIORITIES.put(Material.IRON_HELMET, 7.5);
        EARLY_PRIORITIES.put(Material.IRON_CHESTPLATE, 8.0);
        EARLY_PRIORITIES.put(Material.IRON_LEGGINGS, 7.5);
        EARLY_PRIORITIES.put(Material.IRON_BOOTS, 7.5);
        EARLY_PRIORITIES.put(Material.CHAINMAIL_HELMET, 6.5);
        EARLY_PRIORITIES.put(Material.CHAINMAIL_CHESTPLATE, 7.0);
        EARLY_PRIORITIES.put(Material.CHAINMAIL_LEGGINGS, 6.5);
        EARLY_PRIORITIES.put(Material.CHAINMAIL_BOOTS, 6.5);
        EARLY_PRIORITIES.put(Material.GOLD_HELMET, 5.5);
        EARLY_PRIORITIES.put(Material.GOLD_CHESTPLATE, 6.0);
        EARLY_PRIORITIES.put(Material.GOLD_LEGGINGS, 5.5);
        EARLY_PRIORITIES.put(Material.GOLD_BOOTS, 5.5);
        EARLY_PRIORITIES.put(Material.LEATHER_HELMET, 4.0);
        EARLY_PRIORITIES.put(Material.LEATHER_CHESTPLATE, 4.5);
        EARLY_PRIORITIES.put(Material.LEATHER_LEGGINGS, 4.0);
        EARLY_PRIORITIES.put(Material.LEATHER_BOOTS, 4.0);
        // Building blocks
        EARLY_PRIORITIES.put(Material.COBBLESTONE, 7.0);
        EARLY_PRIORITIES.put(Material.STONE, 7.0);
        EARLY_PRIORITIES.put(Material.WOOD, 6.5);
        EARLY_PRIORITIES.put(Material.WOOL, 6.5);
        EARLY_PRIORITIES.put(Material.SANDSTONE, 6.5);
        EARLY_PRIORITIES.put(Material.DIRT, 5.0);
        // Ranged
        EARLY_PRIORITIES.put(Material.BOW, 6.0);
        EARLY_PRIORITIES.put(Material.ARROW, 5.5);
        // Food
        EARLY_PRIORITIES.put(Material.GOLDEN_APPLE, 8.0);
        EARLY_PRIORITIES.put(Material.COOKED_BEEF, 5.0);
        EARLY_PRIORITIES.put(Material.COOKED_CHICKEN, 4.5);
        EARLY_PRIORITIES.put(Material.BREAD, 4.0);
        // Special items
        EARLY_PRIORITIES.put(Material.ENDER_PEARL, 6.0);
        EARLY_PRIORITIES.put(Material.SNOW_BALL, 4.0);
        EARLY_PRIORITIES.put(Material.EGG, 3.5);
        EARLY_PRIORITIES.put(Material.FISHING_ROD, 5.0);
        EARLY_PRIORITIES.put(Material.WATER_BUCKET, 5.5);
        EARLY_PRIORITIES.put(Material.FLINT_AND_STEEL, 3.0);
        EARLY_PRIORITIES.put(Material.LAVA_BUCKET, 4.0);
        // Tools
        EARLY_PRIORITIES.put(Material.DIAMOND_PICKAXE, 3.5);
        EARLY_PRIORITIES.put(Material.IRON_PICKAXE, 3.0);
        EARLY_PRIORITIES.put(Material.STONE_PICKAXE, 2.5);
        EARLY_PRIORITIES.put(Material.DIAMOND_AXE, 4.5);
        EARLY_PRIORITIES.put(Material.IRON_AXE, 3.5);

        // ── Mid Game ──
        MID_PRIORITIES.put(Material.DIAMOND_SWORD, 10.0);
        MID_PRIORITIES.put(Material.IRON_SWORD, 7.0);
        MID_PRIORITIES.put(Material.DIAMOND_CHESTPLATE, 9.5);
        MID_PRIORITIES.put(Material.DIAMOND_LEGGINGS, 9.0);
        MID_PRIORITIES.put(Material.DIAMOND_HELMET, 9.0);
        MID_PRIORITIES.put(Material.DIAMOND_BOOTS, 9.0);
        MID_PRIORITIES.put(Material.IRON_CHESTPLATE, 7.0);
        MID_PRIORITIES.put(Material.IRON_LEGGINGS, 6.5);
        MID_PRIORITIES.put(Material.IRON_HELMET, 6.5);
        MID_PRIORITIES.put(Material.IRON_BOOTS, 6.5);
        MID_PRIORITIES.put(Material.GOLDEN_APPLE, 9.0);
        MID_PRIORITIES.put(Material.ENDER_PEARL, 8.5);
        MID_PRIORITIES.put(Material.BOW, 7.5);
        MID_PRIORITIES.put(Material.ARROW, 6.5);
        MID_PRIORITIES.put(Material.COBBLESTONE, 5.5);
        MID_PRIORITIES.put(Material.FISHING_ROD, 6.0);
        MID_PRIORITIES.put(Material.WATER_BUCKET, 6.5);
        MID_PRIORITIES.put(Material.COOKED_BEEF, 4.5);
        MID_PRIORITIES.put(Material.BREAD, 3.5);
        MID_PRIORITIES.put(Material.SNOW_BALL, 4.0);
        MID_PRIORITIES.put(Material.EGG, 3.5);
        MID_PRIORITIES.put(Material.LAVA_BUCKET, 5.0);

        // ── Late Game ──
        LATE_PRIORITIES.put(Material.GOLDEN_APPLE, 10.0);
        LATE_PRIORITIES.put(Material.ENDER_PEARL, 9.5);
        LATE_PRIORITIES.put(Material.DIAMOND_SWORD, 8.0);
        LATE_PRIORITIES.put(Material.BOW, 7.5);
        LATE_PRIORITIES.put(Material.ARROW, 6.5);
        LATE_PRIORITIES.put(Material.DIAMOND_CHESTPLATE, 7.0);
        LATE_PRIORITIES.put(Material.DIAMOND_LEGGINGS, 7.0);
        LATE_PRIORITIES.put(Material.DIAMOND_HELMET, 7.0);
        LATE_PRIORITIES.put(Material.DIAMOND_BOOTS, 7.0);
        LATE_PRIORITIES.put(Material.COBBLESTONE, 5.0);
        LATE_PRIORITIES.put(Material.WATER_BUCKET, 7.0);
        LATE_PRIORITIES.put(Material.FISHING_ROD, 5.5);
        LATE_PRIORITIES.put(Material.COOKED_BEEF, 4.0);
        LATE_PRIORITIES.put(Material.SNOW_BALL, 4.5);
    }

    private LootPriorityTable() {}

    /**
     * Returns the priority value of an item for the given game phase.
     *
     * @param item  the item to evaluate
     * @param phase the current game phase
     * @return the priority value (0.0-10.0), or 0.5 default for unlisted items
     */
    public static double getPriority(@Nullable ItemStack item, @Nonnull GamePhaseTracker.GamePhase phase) {
        if (item == null || item.getType() == Material.AIR) return 0.0;

        Map<Material, Double> table;
        switch (phase) {
            case EARLY:
                table = EARLY_PRIORITIES;
                break;
            case MID:
                table = MID_PRIORITIES;
                break;
            case LATE:
                table = LATE_PRIORITIES;
                break;
            default:
                table = EARLY_PRIORITIES;
        }

        Double priority = table.get(item.getType());
        if (priority != null) {
            // Bonus for enchanted items
            if (item.getEnchantments() != null && !item.getEnchantments().isEmpty()) {
                priority += 1.0;
            }
            // Scale by stack size for consumables
            if (isStackable(item.getType())) {
                priority += Math.min(2.0, item.getAmount() / 32.0);
            }
            return Math.min(10.0, priority);
        }

        // Default priority for unlisted items
        if (item.getType().isBlock() && item.getType().isSolid()) return 3.0;
        if (item.getType().isEdible()) return 2.5;
        return 0.5;
    }

    /**
     * Returns true if the item is considered "junk" — low value items not worth taking.
     *
     * @param item  the item to check
     * @param phase the game phase
     * @return true if the item is junk
     */
    public static boolean isJunk(@Nullable ItemStack item, @Nonnull GamePhaseTracker.GamePhase phase) {
        return getPriority(item, phase) < 1.5;
    }

    /**
     * Returns true if the item is considered "essential" — must-take items.
     *
     * @param item  the item to check
     * @param phase the game phase
     * @return true if the item is essential
     */
    public static boolean isEssential(@Nullable ItemStack item, @Nonnull GamePhaseTracker.GamePhase phase) {
        return getPriority(item, phase) >= 6.0;
    }

    private static boolean isStackable(@Nonnull Material material) {
        return material.getMaxStackSize() > 1;
    }
}
