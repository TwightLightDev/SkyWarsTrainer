package org.twightlight.skywarstrainer.ai.engine;

/**
 * Return type for all behavior tree node executions.
 *
 * <ul>
 *   <li>{@link #SUCCESS} — the node completed successfully this tick.</li>
 *   <li>{@link #FAILURE} — the node failed or its condition was not met.</li>
 *   <li>{@link #RUNNING} — the node is mid-execution and needs more ticks.</li>
 * </ul>
 *
 * <p>Composite nodes propagate these statuses according to their logic
 * (Selector passes success up, Sequence passes failure up, etc.).</p>
 */
public enum NodeStatus {
    /** Node completed its task successfully this tick. */
    SUCCESS,
    /** Node failed; its condition was false or action could not execute. */
    FAILURE,
    /** Node is still executing; call tick() again next tick. */
    RUNNING
}

