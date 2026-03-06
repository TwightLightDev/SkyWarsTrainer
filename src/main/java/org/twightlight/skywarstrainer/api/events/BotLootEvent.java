package org.twightlight.skywarstrainer.api.events;

import org.bukkit.Location;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.twightlight.skywarstrainer.bot.TrainerBot;

import javax.annotation.Nonnull;

/**
 * Fired when a bot begins looting a chest.
 */
public class BotLootEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final TrainerBot bot;
    private final Location chestLocation;
    private final String strategyName;

    public BotLootEvent(@Nonnull TrainerBot bot, @Nonnull Location chestLocation,
                        @Nonnull String strategyName) {
        this.bot = bot;
        this.chestLocation = chestLocation;
        this.strategyName = strategyName;
    }

    @Nonnull public TrainerBot getBot() { return bot; }
    @Nonnull public Location getChestLocation() { return chestLocation; }
    @Nonnull public String getStrategyName() { return strategyName; }

    @Override @Nonnull public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
