package org.twightlight.skywarstrainer.ai.state;

/**
 * Enumeration of all macro-behavioral states a trainer bot can be in.
 *
 * <p>Each state corresponds to one BehaviorTree that governs the bot's
 * micro-actions while in that state. The {@link BotStateMachine} manages
 * transitions between states based on the Utility AI's decisions.</p>
 *
 * <h3>State descriptions</h3>
 * <ul>
 *   <li>{@link #IDLE}       — Waiting; doing nothing meaningful (grace period idle,
 *       or pre-game cage wait).</li>
 *   <li>{@link #LOOTING}    — Actively searching for and opening chests.</li>
 *   <li>{@link #FIGHTING}   — In active combat with a target entity.</li>
 *   <li>{@link #BRIDGING}   — Building a bridge toward a destination.</li>
 *   <li>{@link #FLEEING}    — Running away from a threat.</li>
 *   <li>{@link #ENCHANTING} — Using an enchanting table.</li>
 *   <li>{@link #HUNTING}    — Seeking out a specific target to fight.</li>
 *   <li>{@link #CAMPING}    — Fortifying a position and waiting for enemies.</li>
 *   <li>{@link #END_GAME}   — Game ending: victory celebration or death cleanup.</li>
 * </ul>
 */
public enum BotState {

    /** Bot is waiting (cage, grace period, or truly idle). No aggressive actions. */
    IDLE,

    /**
     * Bot is actively looting chests. Pathfinds to chests, opens/breaks them,
     * picks up items, and equips the best gear found.
     */
    LOOTING,

    /**
     * Bot is in active PvP combat. The CombatEngine drives all attack, strafe,
     * and survival logic while in this state.
     */
    FIGHTING,

    /**
     * Bot is building a bridge toward a destination (mid island, enemy island,
     * or escape route). The BridgeEngine drives block-placement logic.
     */
    BRIDGING,

    /**
     * Bot is fleeing from a threat. Sprints away, uses escape items (ender pearl,
     * water bucket, blocks), and tries to disengage.
     */
    FLEEING,

    /**
     * Bot is at an enchanting table, using experience levels to enchant weapons
     * and armor. Only occurs when the bot is safe and has sufficient levels.
     */
    ENCHANTING,

    /**
     * Bot is hunting a specific player. Pathfinds to them on the same island or
     * bridges to their island. Transitions to FIGHTING on contact.
     */
    HUNTING,

    /**
     * Bot has found a defensible position and is camping it. Builds fortifications,
     * watches for enemies, harasses with bow. Waits for enemies to come to it.
     */
    CAMPING,

    /**
     * Final phase state. Victory: jumps and chats. Defeat: death messages.
     * Triggers bot cleanup and despawn after a short delay.
     */
    END_GAME
}
