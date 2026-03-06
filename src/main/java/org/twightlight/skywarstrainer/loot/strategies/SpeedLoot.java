package org.twightlight.skywarstrainer.loot.strategies;

import org.bukkit.Material;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.twightlight.skywarstrainer.awareness.GamePhaseTracker;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.loot.LootPriorityTable;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

/**
 * Speed looting: take ONLY essential items (weapon, armor, blocks, food).
 *
 * <p>Opens chest → takes only items rated as "essential" by LootPriorityTable →
 * skips junk → closes quickly. Fastest "take what you need" strategy.
 * Preferred by RUSHER, AGGRESSIVE.</p>
 */
public class SpeedLoot implements LootStrategy {

    private Chest chest;
    private boolean complete;
    private int tickCounter;
    private int nextItemTick;
    private List<Integer> essentialSlots;
    private int currentIndex;

    @Nonnull
    @Override
    public String getName() { return "SpeedLoot"; }

    @Override
    public void initialize(@Nonnull TrainerBot bot, @Nonnull Chest chest) {
        this.chest = chest;
        this.complete = false;
        this.tickCounter = 0;
        this.nextItemTick = 0;
        this.currentIndex = 0;

        // Identify only essential slots
        GamePhaseTracker.GamePhase phase = getPhase(bot);
        Inventory chestInv = chest.getInventory();
        essentialSlots = new ArrayList<>();

        for (int i = 0; i < chestInv.getSize(); i++) {
            ItemStack item = chestInv.getItem(i);
            if (item != null && LootPriorityTable.isEssential(item, phase)) {
                essentialSlots.add(i);
            }
        }
    }

    @Nonnull
    @Override
    public LootTickResult tick(@Nonnull TrainerBot bot) {
        if (complete) return LootTickResult.COMPLETE;
        Player player = bot.getPlayerEntity();
        if (player == null || chest == null) return LootTickResult.FAILED;

        tickCounter++;
        if (tickCounter < nextItemTick) return LootTickResult.IN_PROGRESS;

        if (currentIndex >= essentialSlots.size()) {
            complete = true;
            return LootTickResult.COMPLETE;
        }

        DifficultyProfile diff = bot.getDifficultyProfile();
        Inventory chestInv = chest.getInventory();
        int slot = essentialSlots.get(currentIndex);
        ItemStack item = chestInv.getItem(slot);

        if (item != null && item.getType() != Material.AIR && player.getInventory().firstEmpty() != -1) {
            player.getInventory().addItem(item.clone());
            chestInv.setItem(slot, null);
            // Speed loot is faster: 50-200ms per item
            double baseDelay = 125.0 / diff.getLootSpeedMultiplier();
            nextItemTick = tickCounter + Math.max(1, (int)(baseDelay / 50.0)) + RandomUtil.nextInt(0, 2);
            currentIndex++;
            return LootTickResult.ITEM_TAKEN;
        }

        currentIndex++;
        return LootTickResult.IN_PROGRESS;
    }

    @Override
    public void reset() {
        chest = null; complete = false; tickCounter = 0; nextItemTick = 0;
        essentialSlots = null; currentIndex = 0;
    }

    @Override
    public boolean isComplete() { return complete; }

    private GamePhaseTracker.GamePhase getPhase(@Nonnull TrainerBot bot) {
        GamePhaseTracker tracker = bot.getGamePhaseTracker();
        return tracker != null ? tracker.getPhase() : GamePhaseTracker.GamePhase.EARLY;
    }
}
