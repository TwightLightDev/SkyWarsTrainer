// ═══════════════════════════════════════════════════════════════════
// File: src/main/java/org/twightlight/skywarstrainer/loot/LootPriorityTable.java
// ═══════════════════════════════════════════════════════════════════
package org.twightlight.skywarstrainer.loot;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.twightlight.skywarstrainer.awareness.GamePhaseTracker;

import javax.annotation.Nonnull;
import java.util.*;

/**
 * Defines item priority rankings for each game phase.
 *
 * <p>The loot system uses this table to decide which items to take first from
 * chests. Higher priority items are picked up before lower priority items.
 * The priorities shift as the game progresses — early game prioritizes basic
 * gear; late game prioritizes golden apples and ender pearls.</p>
 *
 * <p>Each item type is assigned a numeric priority score. Higher = more valuable.
 * The score is a floating point 0.0-10.0 to allow fine-grained ordering.</p>
 */
public class LootPriorityTable {

    /** Priority map per game phase. */
    private static final Map<GamePhaseTracker.GamePhase, Map<Material, Double>> PRIORITY_MAPS = new EnumMap<>(GamePhaseTracker.GamePhase.class);

    static {
        // ── EARLY GAME (first 60 seconds) ──
        Map<Material, Double> early = new HashMap<>();
        // Swords
        early.put(Material.DIAMOND_SWORD, 10.0);
        early.put(Material.IRON_SWORD, 9.5);
        early.put(Material.STONE_SWORD, 9.0);
        early.put(Material.GOLD_SWORD, 8.0);
        early.put(Material.WOOD_SWORD, 7.5);
        // Armor
        early.put(Material.DIAMOND_HELMET, 9.0);
        early.put(Material.DIAMOND_CHESTPLATE, 9.2);
        early.put(Material.DIAMOND_LEGGINGS, 9.1);
        early.put(Material.DIAMOND_BOOTS, 9.0);
        early.put(Material.IRON_HELMET, 8.0);
        early.put(Material.IRON_CHESTPLATE, 8.3);
        early.put(Material.IRON_LEGGINGS, 8.2);
        early.put(Material.IRON_BOOTS, 8.0);
        early.put(Material.CHAINMAIL_HELMET, 7.0);
        early.put(Material.CHAINMAIL_CHESTPLATE, 7.3);
        early.put(Material.CHAINMAIL_LEGGINGS, 7.2);
        early.put(Material.CHAINMAIL_BOOTS, 7.0);
        early.put(Material.GOLD_HELMET, 6.0);
        early.put(Material.GOLD_CHESTPLATE, 6.3);
        early.put(Material.GOLD_LEGGINGS, 6.2);
        early.put(Material.GOLD_BOOTS, 6.0);
        early.put(Material.LEATHER_HELMET, 5.0);
        early.put(Material.LEATHER_CHESTPLATE, 5.3);
        early.put(Material.LEATHER_LEGGINGS, 5.2);
        early.put(Material.LEATHER_BOOTS, 5.0);
        // Blocks
        early.put(Material.COBBLESTONE, 7.0);
        early.put(Material.STONE, 7.0);
        early.put(Material.WOOL, 6.8);
        early.put(Material.WOOD, 6.5);
        early.put(Material.SANDSTONE, 6.5);
        early.put(Material.DIRT, 5.5);
        // Bow & arrows
        early.put(Material.BOW, 7.0);
        early.put(Material.ARROW, 6.5);
        // Food
        early.put(Material.GOLDEN_APPLE, 7.5);
        early.put(Material.COOKED_BEEF, 5.0);
        early.put(Material.COOKED_CHICKEN, 4.8);
        early.put(Material.BREAD, 4.5);
        // Special
        early.put(Material.ENDER_PEARL, 6.5);
        early.put(Material.SNOW_BALL, 5.0);
        early.put(Material.EGG, 4.8);
        early.put(Material.FISHING_ROD, 6.0);
        // Potions handled separately by type
        early.put(Material.POTION, 5.5);
        // Enchanting
        early.put(Material.EXP_BOTTLE, 3.5);
        early.put(Material.INK_SACK, 3.0); // Lapis lazuli (data value 4)
        // Tools
        early.put(Material.DIAMOND_PICKAXE, 4.5);
        early.put(Material.IRON_PICKAXE, 4.0);
        early.put(Material.STONE_PICKAXE, 3.5);
        early.put(Material.DIAMOND_AXE, 5.0);
        early.put(Material.IRON_AXE, 4.5);
        early.put(Material.WATER_BUCKET, 5.5);
        early.put(Material.LAVA_BUCKET, 4.0);
        early.put(Material.FLINT_AND_STEEL, 3.5);
        early.put(Material.TNT, 4.0);
        PRIORITY_MAPS.put(GamePhaseTracker.GamePhase.EARLY, early);

        // ── MID GAME (60-180 seconds) ──
        Map<Material, Double> mid = new HashMap<>(early);
        mid.put(Material.DIAMOND_SWORD, 10.0);
        mid.put(Material.GOLDEN_APPLE, 9.5);
        mid.put(Material.ENDER_PEARL, 9.0);
        mid.put(Material.POTION, 8.0);
        mid.put(Material.BOW, 7.5);
        mid.put(Material.ARROW, 7.0);
        mid.put(Material.EXP_BOTTLE, 5.0);
        PRIORITY_MAPS.put(GamePhaseTracker.GamePhase.MID, mid);

        // ── LATE GAME (180+ seconds) ──
        Map<Material, Double> late = new HashMap<>(mid);
        late.put(Material.GOLDEN_APPLE, 10.0);
        late.put(Material.ENDER_PEARL, 9.8);
        late.put(Material.POTION, 9.5);
        late.put(Material.DIAMOND_SWORD, 9.0);
        late.put(Material.BOW, 8.0);
        late.put(Material.ARROW, 7.5);
        PRIORITY_MAPS.put(GamePhaseTracker.GamePhase.LATE, late);

        // ── DEATHMATCH ──
        Map<Material, Double> deathmatch = new HashMap<>(late);
        deathmatch.put(Material.GOLDEN_APPLE, 10.0);
        deathmatch.put(Material.ENDER_PEARL, 10.0);
        PRIORITY_MAPS.put(GamePhaseTracker.GamePhase.LATE, deathmatch);
    }

    private LootPriorityTable() {
        // Static utility class
    }

    /**
     * Returns the priority of an item for the given game phase.
     * Higher values = more valuable.
     *
     * @param item  the item to evaluate
     * @param phase the current game phase
     * @return the priority score (0.0-10.0), or 0.5 for unknown items
     */
    public static double getPriority(@Nonnull ItemStack item, @Nonnull GamePhaseTracker.GamePhase phase) {
        Map<Material, Double> priorities = PRIORITY_MAPS.get(phase);
        if (priorities == null) {
            priorities = PRIORITY_MAPS.get(GamePhaseTracker.GamePhase.EARLY);
        }

        Double priority = priorities.get(item.getType());
        if (priority != null) {
            // Bonus for enchanted items
            if (!item.getEnchantments().isEmpty()) {
                priority += 1.0;
            }
            // Bonus for larger stacks of blocks
            if (isBlock(item.getType()) && item.getAmount() > 16) {
                priority += 0.5;
            }
            return Math.min(priority, 10.0);
        }

        // Default priority for unknown items
        if (isBlock(item.getType())) return 2.0;
        if (item.getType().isEdible()) return 2.5;
        return 0.5;
    }

    /**
     * Returns whether an item is considered "essential" (worth picking up
     * even in speed-loot mode).
     *
     * @param item  the item to check
     * @param phase the current game phase
     * @return true if the item is essential
     */
    public static boolean isEssential(@Nonnull ItemStack item, @Nonnull GamePhaseTracker.GamePhase phase) {
        return getPriority(item, phase) >= 5.0;
    }

    /**
     * Returns whether an item is considered "junk" (can be discarded or ignored).
     *
     * @param item the item to check
     * @return true if the item is low-value junk
     */
    public static boolean isJunk(@Nonnull ItemStack item) {
        Material mat = item.getType();
        return mat == Material.SEEDS || mat == Material.WHEAT
                || mat == Material.STICK || mat == Material.STRING
                || mat == Material.FEATHER || mat == Material.PAPER
                || mat == Material.BOWL || mat == Material.ROTTEN_FLESH;
    }

    /**
     * Returns whether the material is a building block.
     *
     * @param material the material to check
     * @return true if it can be placed as a building block
     */
    public static boolean isBlock(@Nonnull Material material) {
        return material == Material.COBBLESTONE || material == Material.STONE
                || material == Material.WOOL || material == Material.WOOD
                || material == Material.SANDSTONE || material == Material.DIRT
                || material == Material.SAND || material == Material.NETHERRACK
                || material == Material.ENDER_STONE || material == Material.HARD_CLAY
                || material == Material.STAINED_CLAY;
    }

    /**
     * Returns whether the material is a weapon (sword).
     *
     * @param material the material to check
     * @return true if it's a sword
     */
    public static boolean isSword(@Nonnull Material material) {
        return material == Material.DIAMOND_SWORD || material == Material.IRON_SWORD
                || material == Material.STONE_SWORD || material == Material.GOLD_SWORD
                || material == Material.WOOD_SWORD;
    }

    /**
     * Returns whether the material is armor.
     *
     * @param material the material to check
     * @return true if it's an armor piece
     */
    public static boolean isArmor(@Nonnull Material material) {
        String name = material.name();
        return name.contains("HELMET") || name.contains("CHESTPLATE")
                || name.contains("LEGGINGS") || name.contains("BOOTS");
    }

    /**
     * Returns whether the material is a projectile.
     *
     * @param material the material to check
     * @return true if it's a throwable projectile
     */
    public static boolean isProjectile(@Nonnull Material material) {
        return material == Material.SNOW_BALL || material == Material.EGG
                || material == Material.ENDER_PEARL;
    }

    /**
     * Returns the numeric "quality" rating (0-10) of an item for chest memory.
     *
     * @param item the item
     * @return quality rating
     */
    public static int getQualityRating(@Nonnull ItemStack item) {
        if (isSword(item.getType())) {
            if (item.getType() == Material.DIAMOND_SWORD) return 10;
            if (item.getType() == Material.IRON_SWORD) return 7;
            return 5;
        }
        if (isArmor(item.getType())) {
            if (item.getType().name().startsWith("DIAMOND")) return 9;
            if (item.getType().name().startsWith("IRON")) return 7;
            return 4;
        }
        if (item.getType() == Material.GOLDEN_APPLE) return 8;
        if (item.getType() == Material.ENDER_PEARL) return 8;
        if (item.getType() == Material.BOW) return 6;
        return 2;
    }
}
