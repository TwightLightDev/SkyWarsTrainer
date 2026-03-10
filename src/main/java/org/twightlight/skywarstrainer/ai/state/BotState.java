package org.twightlight.skywarstrainer.ai.state;

/**
 * Enumeration of all macro-behavioral states a trainer bot can be in.
 *
 * <p>Each state corresponds to one BehaviorTree that governs the bot's
 * micro-actions while in that state. The {@link BotStateMachine} manages
 * transitions between states based on the Utility AI's decisions.</p>
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
     * Bot is consuming an item: eating food, drinking a potion, or eating a golden
     * apple for healing. Transitions back once consumption is complete.
     * Added to fix issues #11 and #13 where HEAL/EAT_FOOD/DRINK_POTION mapped
     * to IDLE and never actually triggered the food/potion handlers.
     */
    CONSUMING,

    /**
     * Bot is organizing its inventory: equipping best armor, selecting best sword,
     * reorganizing hotbar layout. Completes quickly then allows re-evaluation.
     * Added to fix issue #11 where ORGANIZE_INVENTORY mapped to IDLE.
     */
    ORGANIZING,

    /**
     * Final phase state. Victory: jumps and chats. Defeat: death messages.
     * Triggers bot cleanup and despawn after a short delay.
     */
    END_GAME
}
