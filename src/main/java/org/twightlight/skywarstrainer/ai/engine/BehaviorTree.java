package org.twightlight.skywarstrainer.ai.engine;

import org.twightlight.skywarstrainer.bot.TrainerBot;

import javax.annotation.Nonnull;

/**
 * Container for a behavior tree rooted at a single {@link BehaviorNode}.
 *
 * <p>Calling {@link #tick(TrainerBot)} on the tree delegates to the root node and
 * returns its {@link NodeStatus}. If the root returns {@link NodeStatus#RUNNING},
 * the same branch continues execution on the next tick (the tree does not restart
 * from the root). If the root returns SUCCESS or FAILURE, the tree is automatically
 * reset so it starts fresh next call.</p>
 *
 * <p>Each {@link org.twightlight.skywarstrainer.ai.state.BotState} owns exactly one
 * BehaviorTree that governs its micro-actions.</p>
 */
public final class BehaviorTree {

    /** Human-readable identifier for debug output. */
    private final String treeName;

    /** The root node of this tree. All execution starts here. */
    private final BehaviorNode root;

    /** Status from the previous tick — used to decide whether to reset. */
    private NodeStatus lastStatus = NodeStatus.SUCCESS;

    /**
     * Creates a new behavior tree.
     *
     * @param treeName human-readable name (e.g., "LOOTING_BT", "FIGHTING_BT")
     * @param root     the root node; all other nodes are children of this
     */
    public BehaviorTree(@Nonnull String treeName, @Nonnull BehaviorNode root) {
        this.treeName = treeName;
        this.root     = root;
    }

    /**
     * Ticks the behavior tree once.
     *
     * <p>If the previous tick returned RUNNING, execution resumes where it left off
     * (each node tracks its own internal state). If the previous tick returned
     * SUCCESS or FAILURE, the tree is reset before ticking again.</p>
     *
     * @param bot the bot running this tree
     * @return the root node's status after this tick
     */
    @Nonnull
    public NodeStatus tick(@Nonnull TrainerBot bot) {
        // Reset after the tree completed (SUCCESS or FAILURE) so next call starts fresh
        if (lastStatus != NodeStatus.RUNNING) {
            reset(bot);
        }
        lastStatus = root.tick(bot);
        return lastStatus;
    }

    /**
     * Resets the entire tree (and all descendant nodes) to their initial state.
     * Called automatically when the tree finishes, and can be called manually
     * when the state machine switches states.
     *
     * @param bot the bot context
     */
    public void reset(@Nonnull TrainerBot bot) {
        root.reset(bot);
        lastStatus = NodeStatus.SUCCESS;
    }

    /**
     * Returns the last status returned by {@link #tick(TrainerBot)}.
     *
     * @return the last tick status
     */
    @Nonnull
    public NodeStatus getLastStatus() {
        return lastStatus;
    }

    /**
     * Returns the root node of this tree.
     *
     * @return the root node
     */
    @Nonnull
    public BehaviorNode getRoot() {
        return root;
    }

    /**
     * Returns the tree's debug name.
     *
     * @return the tree name
     */
    @Nonnull
    public String getTreeName() {
        return treeName;
    }

    @Override
    public String toString() {
        return "BehaviorTree[" + treeName + ", last=" + lastStatus + "]";
    }
}

