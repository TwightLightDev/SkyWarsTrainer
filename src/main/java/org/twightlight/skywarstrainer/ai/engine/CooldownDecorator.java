package org.twightlight.skywarstrainer.ai.engine;

import org.twightlight.skywarstrainer.bot.TrainerBot;

import javax.annotation.Nonnull;

/**
 * Decorator that prevents the child from executing more than once per N server ticks.
 *
 * <p>While on cooldown, this node returns FAILURE (it pretends the child failed, so
 * parent Selectors/Sequences skip it). Once the cooldown expires, the child runs
 * normally again.</p>
 *
 * <p>Cooldown is measured in server ticks (1/20 second each). A cooldown of 40 ticks
 * means the child can run at most once every 2 seconds.</p>
 */
public final class CooldownDecorator extends DecoratorNode {

    /** Cooldown duration in server ticks. */
    private final int cooldownTicks;
    /** Tick count of the last successful execution. -1 = never executed. */
    private long lastExecutedTick = -1L;

    /**
     * Creates a cooldown decorator.
     *
     * @param name          debug name
     * @param child         the child to rate-limit
     * @param cooldownTicks how many ticks must pass between executions
     */
    public CooldownDecorator(@Nonnull String name, @Nonnull BehaviorNode child,
                             int cooldownTicks) {
        super(name, child);
        this.cooldownTicks = cooldownTicks;
    }

    @Override
    @Nonnull
    public NodeStatus tick(@Nonnull TrainerBot bot) {
        long currentTick = bot.getLocalTickCount();
        if (lastExecutedTick >= 0L && (currentTick - lastExecutedTick) < cooldownTicks) {
            // Still on cooldown — pretend failure so parent skips this branch
            return NodeStatus.FAILURE;
        }

        NodeStatus status = child.tick(bot);
        if (status == NodeStatus.SUCCESS) {
            lastExecutedTick = currentTick;
        }
        return status;
    }

    @Override
    public void reset(@Nonnull TrainerBot bot) {
        // Do NOT reset lastExecutedTick here — cooldown should survive tree resets
        super.reset(bot);
    }

    /**
     * Forcefully resets the cooldown so the child can run immediately on next tick.
     * Useful for emergency overrides (e.g., player is about to die → force flee).
     */
    public void clearCooldown() {
        lastExecutedTick = -1L;
    }
}
