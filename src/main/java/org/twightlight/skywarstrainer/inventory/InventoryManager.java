package org.twightlight.skywarstrainer.inventory;

import org.bukkit.entity.Player;
import org.twightlight.skywarstrainer.bot.TrainerBot;

import javax.annotation.Nonnull;

/**
 * Central inventory management for a trainer bot.
 *
 * [FIX] AUDIT_INTERVAL changed from 1 to 100. With AUDIT_INTERVAL=1,
 * performFullAudit() ran every single tick() call, which is expensive
 * and redundant since TrainerBot already gates inventory ticks with
 * inventoryAuditTimer (100 ticks). The internal interval should match
 * or be larger so that when tick() IS called, it doesn't always do a
 * full audit.
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

    /**
     * [FIX] Changed from 1 to 100. The TrainerBot already calls tick() only
     * every 100 ticks via inventoryAuditTimer, so having this at 1 meant
     * every gated call still did a full audit. Setting this to 100 means
     * that if tick() is called more frequently (e.g., from CONSUMING BT),
     * only the food/potion handlers run, not the expensive full audit.
     */
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
