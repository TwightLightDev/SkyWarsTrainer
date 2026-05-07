package org.twightlight.skywarstrainer.bot;

import org.bukkit.entity.Player;
import org.twightlight.skywars.cosmetics.Cosmetic;
import org.twightlight.skywars.cosmetics.CosmeticServer;
import org.twightlight.skywars.cosmetics.CosmeticType;
import org.twightlight.skywars.player.Account;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * A RAM-only {@link Account} that backs a {@link TrainerBot}.
 *
 * <p><b>Lifecycle.</b>
 * <ol>
 *   <li>{@link BotManager#spawnBot} creates the Citizens NPC, then constructs
 *       a BotAccount with the NPC entity's UUID and the skin's display name.</li>
 *   <li>BotManager calls {@code Database.getInstance().cacheAccount(this)}.
 *       From this point {@code Database.getAccount(uuid)} returns the
 *       BotAccount instance.</li>
 *   <li>BotManager calls {@code arena.connect(this)} — LSW handles team
 *       picking, cage building, teleport, broadcast, scoreboard, the lot.</li>
 *   <li>On {@link TrainerBot#destroy()} BotManager calls
 *       {@code Database.getInstance().uncacheAccount(uuid)} and the BotAccount
 *       is gone.</li>
 * </ol></p>
 */
public class BotAccount extends Account {

    private final TrainerBot bot;

    public BotAccount(@Nonnull TrainerBot bot, @Nonnull UUID id, @Nonnull String name) {
        super(id, name, true);
        this.bot = bot;
        randomizeCosmetics();
    }

    private void randomizeCosmetics() {
        for (CosmeticType type : CosmeticType.values()) {
            String n = type.name();
            if (!n.startsWith("SKYWARS_")) continue;
            if (type == CosmeticType.SKYWARS_SYMBOL) continue;

            List<Cosmetic> pool = CosmeticServer.SKYWARS.getByType(type);
            if (pool == null || pool.isEmpty()) continue;

            int slots = Math.max(1, type.getSize());
            for (int slot = 1; slot <= slots; slot++) {
                Cosmetic pick = pool.get(RandomUtil.nextInt(0, pool.size()));
                if (pick == null) continue;

                pick.give(this, slot);

                setSelected(pick.getServer(), pick.getType(), slot, pick.getId());
            }
        }
    }

    @Override
    public void save() {}

    @Override
    @Nullable
    public Player getPlayer() {
        return bot != null ? bot.getPlayerEntity() : null;
    }

    @Override
    public void refreshPlayer() {}

    @Override
    public void refreshPlayers() {}

    @Override
    public void reloadScoreboard() {}

    @Nonnull
    public TrainerBot getBot() {
        return bot;
    }
}
