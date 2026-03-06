package org.twightlight.skywarstrainer.api.events;

import org.bukkit.entity.LivingEntity;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.combat.strategies.CombatStrategy;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Fired when a bot engages in combat with a target.
 */
public class BotCombatEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final TrainerBot bot;
    private final LivingEntity target;
    private final CombatStrategy strategy;

    public BotCombatEvent(@Nonnull TrainerBot bot, @Nonnull LivingEntity target,
                          @Nullable CombatStrategy strategy) {
        this.bot = bot;
        this.target = target;
        this.strategy = strategy;
    }

    @Nonnull public TrainerBot getBot() { return bot; }
    @Nonnull public LivingEntity getTarget() { return target; }
    @Nullable public CombatStrategy getStrategy() { return strategy; }

    @Override @Nonnull public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
