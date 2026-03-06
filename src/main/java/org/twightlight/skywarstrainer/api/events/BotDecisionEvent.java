package org.twightlight.skywarstrainer.api.events;

import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.twightlight.skywarstrainer.ai.decision.DecisionEngine.BotAction;
import org.twightlight.skywarstrainer.bot.TrainerBot;

import javax.annotation.Nonnull;
import java.util.Map;

/**
 * Fired when a bot makes a decision. Cancellable — cancelling prevents the
 * state transition, and other plugins can override the chosen action.
 */
public class BotDecisionEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final TrainerBot bot;
    private BotAction chosenAction;
    private final Map<BotAction, Double> scores;
    private boolean cancelled;

    public BotDecisionEvent(@Nonnull TrainerBot bot, @Nonnull BotAction chosenAction,
                            @Nonnull Map<BotAction, Double> scores) {
        this.bot = bot;
        this.chosenAction = chosenAction;
        this.scores = scores;
        this.cancelled = false;
    }

    @Nonnull public TrainerBot getBot() { return bot; }

    @Nonnull public BotAction getChosenAction() { return chosenAction; }

    /** Override the bot's decision with a different action. */
    public void setChosenAction(@Nonnull BotAction action) { this.chosenAction = action; }

    @Nonnull public Map<BotAction, Double> getScores() { return scores; }

    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }

    @Override @Nonnull public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
