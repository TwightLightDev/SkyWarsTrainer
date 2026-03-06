package org.twightlight.skywarstrainer.api.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.twightlight.skywarstrainer.bot.TrainerBot;

import javax.annotation.Nonnull;

/**
 * Fired when a bot kills a player.
 */
public class BotKillPlayerEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final TrainerBot bot;
    private final Player victim;

    public BotKillPlayerEvent(@Nonnull TrainerBot bot, @Nonnull Player victim) {
        this.bot = bot;
        this.victim = victim;
    }

    @Nonnull public TrainerBot getBot() { return bot; }
    @Nonnull public Player getVictim() { return victim; }

    @Override @Nonnull public HandlerList getHandlers() { return HANDLERS; }
    @Nonnull public static HandlerList getHandlerList() { return HANDLERS; }
}
