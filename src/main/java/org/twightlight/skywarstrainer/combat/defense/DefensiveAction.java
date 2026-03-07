package org.twightlight.skywarstrainer.combat.defense;

/**
 * Enumeration of all defensive action types.
 */
public enum DefensiveAction {
    /** Break enemy bridge blocks to deny approach. */
    BRIDGE_CUT,
    /** Throw projectiles to deny area/bridge approach. */
    PROJECTILE_ZONE,
    /** Run away, heal, then re-engage. */
    RETREAT_HEAL,
    /** Place blocks to create a defensive wall. */
    BLOCK_BARRICADE,
    /** Place water/lava for area denial. */
    WATER_LAVA_DENIAL,
    /** Partially break a bridge to create a trap. */
    BRIDGE_TRAP
}

