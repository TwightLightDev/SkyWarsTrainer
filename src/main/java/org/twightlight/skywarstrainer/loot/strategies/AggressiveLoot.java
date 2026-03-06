package org.twightlight.skywarstrainer.loot.strategies;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.util.PacketUtil;

import javax.annotation.Nonnull;

/**
 * Aggressive looting: break the chest block to scatter all items as drops.
 *
 * <p>Sprint to chest → break chest with hand/axe → all items drop as entities →
 * walk over drops to pick them up. Faster for clearing everything and denies
 * the chest to enemies. Preferred by AGGRESSIVE, BERSERKER, RUSHER.</p>
 */
public class AggressiveLoot implements LootStrategy {

    private Chest chest;
    private boolean complete;
    private int tickCounter;
    private boolean chestBroken;

    @Nonnull
    @Override
    public String getName() { return "AggressiveLoot"; }

    @Override
    public void initialize(@Nonnull TrainerBot bot, @Nonnull Chest chest) {
        this.chest = chest;
        this.complete = false;
        this.tickCounter = 0;
        this.chestBroken = false;
    }

    @Nonnull
    @Override
    public LootTickResult tick(@Nonnull TrainerBot bot) {
        if (complete) return LootTickResult.COMPLETE;
        Player player = bot.getPlayerEntity();
        if (player == null || chest == null) return LootTickResult.FAILED;

        tickCounter++;

        if (!chestBroken) {
            // Break the chest — this drops all contents as items
            Block chestBlock = chest.getBlock();
            if (chestBlock.getType() == Material.CHEST || chestBlock.getType() == Material.TRAPPED_CHEST) {
                // Drop inventory contents before breaking
                Inventory inv = chest.getInventory();
                for (int i = 0; i < inv.getSize(); i++) {
                    ItemStack item = inv.getItem(i);
                    if (item != null && item.getType() != Material.AIR) {
                        chestBlock.getWorld().dropItemNaturally(chestBlock.getLocation().add(0.5, 0.5, 0.5), item);
                    }
                }
                inv.clear();
                chestBlock.setType(Material.AIR);
                PacketUtil.playArmSwing(player);
                chestBroken = true;
            } else {
                complete = true;
                return LootTickResult.FAILED;
            }
            return LootTickResult.IN_PROGRESS;
        }

        // After breaking, items are on the ground — the bot walks over them
        // to pick them up naturally via Minecraft's item pickup mechanics.
        // Wait a few ticks for pickup to complete.
        if (tickCounter > 30) {
            complete = true;
            return LootTickResult.COMPLETE;
        }

        return LootTickResult.IN_PROGRESS;
    }

    @Override
    public void reset() {
        chest = null;
        complete = false;
        tickCounter = 0;
        chestBroken = false;
    }

    @Override
    public boolean isComplete() { return complete; }
}
