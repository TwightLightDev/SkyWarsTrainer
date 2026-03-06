package org.twightlight.skywarstrainer.api.events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.twightlight.skywarstrainer.bot.TrainerBot;

import javax.annotation.Nonnull;

/**
 * Fired when a trainer bot is despawned/destroyed.
 */
public class BotDespawnEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final TrainerBot bot;

    public BotDespawnEvent(@Nonnull TrainerBot bot) {
        this.bot = bot;
    }

    @Nonnull
    public TrainerBot getBot() { return bot; }

    @Override
    @Nonnull
    public HandlerList getHandlers() { return HANDLERS; }

    @Nonnull
    public static HandlerList getHandlerList() { return HANDLERS; }
}
