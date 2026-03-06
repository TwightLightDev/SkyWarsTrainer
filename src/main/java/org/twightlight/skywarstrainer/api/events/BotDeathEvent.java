package org.twightlight.skywarstrainer.api.events;

import org.bukkit.entity.Entity;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.twightlight.skywarstrainer.bot.TrainerBot;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Fired when a bot dies.
 */
public class BotDeathEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final TrainerBot bot;
    private final Entity killer;

    public BotDeathEvent(@Nonnull TrainerBot bot, @Nullable Entity killer) {
        this.bot = bot;
        this.killer = killer;
    }

    @Nonnull public TrainerBot getBot() { return bot; }
    @Nullable public Entity getKiller() { return killer; }

    @Override @Nonnull public HandlerList getHandlers() { return HANDLERS; }
    @Nonnull public static HandlerList getHandlerList() { return HANDLERS; }
}
