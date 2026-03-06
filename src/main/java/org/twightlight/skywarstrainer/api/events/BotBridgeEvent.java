package org.twightlight.skywarstrainer.api.events;

import org.bukkit.Location;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.twightlight.skywarstrainer.bot.TrainerBot;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Fired when a bot begins bridging.
 */
public class BotBridgeEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final TrainerBot bot;
    private final String strategyName;
    private final Location destination;

    public BotBridgeEvent(@Nonnull TrainerBot bot, @Nonnull String strategyName,
                          @Nullable Location destination) {
        this.bot = bot;
        this.strategyName = strategyName;
        this.destination = destination;
    }

    @Nonnull public TrainerBot getBot() { return bot; }
    @Nonnull public String getStrategyName() { return strategyName; }
    @Nullable public Location getDestination() { return destination; }

    @Override @Nonnull public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
