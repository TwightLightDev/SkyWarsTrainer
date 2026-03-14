package org.twightlight.skywarstrainer.inventory;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;

/**
 * Arranges the hotbar like a real player based on hotbarOrganization skill.
 *
 * <p>Target layout:
 * Slot 0: Sword, Slot 1: Bow/Rod/Water, Slot 2: Blocks, Slot 3: Golden Apple/Food,
 * Slot 4: Projectiles, Slot 5: Ender Pearl, Slot 6: Blocks (secondary),
 * Slot 7: Pickaxe/Axe, Slot 8: Misc</p>
 */
public class HotbarOrganizer {

    private final TrainerBot bot;

    public HotbarOrganizer(@Nonnull TrainerBot bot) {
        this.bot = bot;
    }

    /**
     * Organizes the hotbar according to the standard layout.
     * Quality of organization depends on hotbarOrganization parameter.
     *
     * @param player the bot's player entity
     */
    public void organizeHotbar(@Nonnull Player player) {
        DifficultyProfile diff = bot.getDifficultyProfile();
        double quality = diff.getHotbarOrganization();

        // At low quality, skip organization most of the time
        if (!RandomUtil.chance(quality)) return;

        PlayerInventory inv = player.getInventory();

        // Slot 1: Bow, Fishing Rod, or Water Bucket
        moveToSlot(inv, 1, this::isSecondaryItem);
        // Slot 2: Primary blocks
        moveToSlot(inv, 2, this::isBuildingBlock);
        // Slot 3: Golden Apple or Food
        moveToSlot(inv, 3, this::isFood);
        // Slot 4: Projectiles
        moveToSlot(inv, 4, this::isProjectile);
        // Slot 5: Ender Pearl
        moveToSlot(inv, 5, m -> m == Material.ENDER_PEARL);
        // Slot 7: Tool
        moveToSlot(inv, 7, this::isTool);
    }

    private void moveToSlot(@Nonnull PlayerInventory inv, int targetSlot,
                            @Nonnull java.util.function.Predicate<Material> matcher) {
        ItemStack current = inv.getItem(targetSlot);
        if (current != null && matcher.test(current.getType())) return; // Already has correct item

        // Find matching item in inventory (slots 9-35 first, then other hotbar slots)
        for (int i = 9; i < 36; i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && matcher.test(item.getType())) {
                inv.setItem(i, current);
                inv.setItem(targetSlot, item);
                return;
            }
        }
    }

    private boolean isSecondaryItem(@Nonnull Material mat) {
        return mat == Material.BOW || mat == Material.FISHING_ROD || mat == Material.WATER_BUCKET;
    }

    private boolean isBuildingBlock(@Nonnull Material mat) {
        return mat == Material.COBBLESTONE || mat == Material.STONE || mat == Material.WOOL
                || mat == Material.WOOD || mat == Material.SANDSTONE || mat == Material.DIRT;
    }

    private boolean isFood(@Nonnull Material mat) {
        return mat == Material.GOLDEN_APPLE || mat == Material.COOKED_BEEF
                || mat == Material.COOKED_CHICKEN || mat == Material.BREAD;
    }

    private boolean isProjectile(@Nonnull Material mat) {
        return mat == Material.SNOW_BALL || mat == Material.EGG;
    }

    private boolean isTool(@Nonnull Material mat) {
        String name = mat.name();
        return name.endsWith("_PICKAXE") || name.endsWith("_AXE");
    }
}
