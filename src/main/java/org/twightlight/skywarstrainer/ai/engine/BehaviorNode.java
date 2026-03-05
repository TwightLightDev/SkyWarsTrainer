package org.twightlight.skywarstrainer.ai.engine;

import org.twightlight.skywarstrainer.bot.TrainerBot;

import javax.annotation.Nonnull;

/**
 * Abstract base class for all behavior-tree nodes.
 *
 * <p>Every node in the tree receives a {@link TrainerBot} as its execution context.
 * This gives every node full access to the bot's subsystems (movement, awareness,
 * inventory, etc.) without needing a separate "blackboard" object.</p>
 *
 * <p>Subclasses implement {@link #tick(TrainerBot)} to perform their logic and
 * return {@link NodeStatus#SUCCESS}, {@link NodeStatus#FAILURE}, or
 * {@link NodeStatus#RUNNING} for multi-tick operations.</p>
 *
 * <p>Nodes may optionally override {@link #reset()} to clear their internal state
 * when the tree is restarted (e.g., after a state transition).</p>
 */
public abstract class BehaviorNode {

    /** Human-readable name for debugging and logging. */
    protected final String name;

    /**
     * Constructs a node with the given debug name.
     *
     * @param name the node's debug name (should be unique within a tree)
     */
    protected BehaviorNode(@Nonnull String name) {
        this.name = name;
    }

    /**
     * Executes this node for one tick and returns its status.
     *
     * @param bot the bot whose AI is running this tree
     * @return the execution result for this tick
     */
    @Nonnull
    public abstract NodeStatus tick(@Nonnull TrainerBot bot);

    /**
     * Resets the node's internal execution state.
     *
     * <p>Called by the parent tree when it is reset (e.g., state machine changes state
     * mid-execution). Default implementation is a no-op; composite and decorator nodes
     * should override to reset their children/child.</p>
     *
     * @param bot the bot context (may be needed for cleanup)
     */
    public void reset(@Nonnull TrainerBot bot) {
        // Default: no internal state to clear
    }

    /**
     * Returns this node's debug name.
     *
     * @return the node name
     */
    @Nonnull
    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[" + name + "]";
    }
}

