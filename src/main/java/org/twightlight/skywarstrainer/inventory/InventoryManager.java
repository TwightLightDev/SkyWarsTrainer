package org.twightlight.skywarstrainer.inventory;

import org.bukkit.entity.Player;
import org.twightlight.skywarstrainer.bot.TrainerBot;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Central inventory management for a trainer bot. Coordinates all inventory
 * subsystems: armor equipping, sword selection, hotbar organization,
 * enchantment handling, potion usage, food consumption, and block counting.
 *
 * <p>Runs periodically (every 100 ticks for full audit) and on-demand when
 * items are acquired or the bot needs to switch gear.</p>
 */
public class InventoryManager {

    private final TrainerBot bot;
    private final ArmorEquipper armorEquipper;
    private final SwordSelector swordSelector;
    private final HotbarOrganizer hotbarOrganizer;
    private final EnchantmentHandler enchantmentHandler;
    private final PotionHandler potionHandler;
    private final FoodHandler foodHandler;
    private final BlockCounter blockCounter;

    /** Ticks since last full inventory audit. */
    private int ticksSinceAudit;
    private static final int AUDIT_INTERVAL = 100;

    public InventoryManager(@Nonnull TrainerBot bot) {
        this.bot = bot;
        this.armorEquipper = new ArmorEquipper(bot);
        this.swordSelector = new SwordSelector(bot);
        this.hotbarOrganizer = new HotbarOrganizer(bot);
        this.enchantmentHandler = new EnchantmentHandler(bot);
        this.potionHandler = new PotionHandler(bot);
        this.foodHandler = new FoodHandler(bot);
        this.blockCounter = new BlockCounter(bot);
        this.ticksSinceAudit = 0;
    }

    /**
     * Ticks the inventory manager. Performs quick checks every tick
     * and full audits periodically.
     */
    public void tick() {
        Player player = bot.getPlayerEntity();
        if (player == null) return;

        ticksSinceAudit++;

        // Quick checks every tick: food and potion urgency
        foodHandler.tick();
        potionHandler.tick();

        // Full audit periodically
        if (ticksSinceAudit >= AUDIT_INTERVAL) {
            ticksSinceAudit = 0;
            performFullAudit(player);
        }
    }

    /**
     * Performs a full inventory audit: equip best armor, select best sword,
     * organize hotbar, count blocks.
     */
    public void performFullAudit(@Nonnull Player player) {
        armorEquipper.equipBestArmor(player);
        swordSelector.selectBestSword(player);
        hotbarOrganizer.organizeHotbar(player);
        blockCounter.update(player);
    }

    /**
     * Quick equip: immediately equip any new armor/weapons found (e.g., after looting).
     */
    public void quickEquip() {
        Player player = bot.getPlayerEntity();
        if (player == null) return;
        armorEquipper.equipBestArmor(player);
        swordSelector.selectBestSword(player);
    }

    @Nonnull public ArmorEquipper getArmorEquipper() { return armorEquipper; }
    @Nonnull public SwordSelector getSwordSelector() { return swordSelector; }
    @Nonnull public HotbarOrganizer getHotbarOrganizer() { return hotbarOrganizer; }
    @Nonnull public EnchantmentHandler getEnchantmentHandler() { return enchantmentHandler; }
    @Nonnull public PotionHandler getPotionHandler() { return potionHandler; }
    @Nonnull public FoodHandler getFoodHandler() { return foodHandler; }
    @Nonnull public BlockCounter getBlockCounter() { return blockCounter; }
}
