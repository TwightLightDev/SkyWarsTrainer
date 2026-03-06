package org.twightlight.skywarstrainer.api.events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.twightlight.skywarstrainer.ai.state.BotState;
import org.twightlight.skywarstrainer.bot.TrainerBot;

import javax.annotation.Nonnull;

/**
 * Fired when a bot changes its macro-behavioral state.
 */
public class BotStateChangeEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final TrainerBot bot;
    private final BotState oldState;
    private final BotState newState;

    public BotStateChangeEvent(@Nonnull TrainerBot bot, @Nonnull BotState oldState,
                               @Nonnull BotState newState) {
        this.bot = bot;
        this.oldState = oldState;
        this.newState = newState;
    }

    @Nonnull public TrainerBot getBot() { return bot; }
    @Nonnull public BotState getOldState() { return oldState; }
    @Nonnull public BotState getNewState() { return newState; }

    @Override @Nonnull public HandlerList getHandlers() { return HANDLERS; }
    @Nonnull public static HandlerList getHandlerList() { return HANDLERS; }
}
