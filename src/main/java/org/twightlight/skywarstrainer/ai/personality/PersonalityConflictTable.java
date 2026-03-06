package org.twightlight.skywarstrainer.ai.personality;

import javax.annotation.Nonnull;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Defines which personality pairs conflict and cannot coexist on the same bot.
 *
 * <p>Conflict rules (from Section 4):
 * <ul>
 *   <li>AGGRESSIVE conflicts with PASSIVE, CAMPER</li>
 *   <li>PASSIVE conflicts with AGGRESSIVE, RUSHER</li>
 *   <li>RUSHER conflicts with PASSIVE, CAMPER, COLLECTOR</li>
 *   <li>CAMPER conflicts with AGGRESSIVE, RUSHER</li>
 *   <li>BERSERKER conflicts with PASSIVE, STRATEGIC, SNIPER, CAUTIOUS</li>
 *   <li>CAUTIOUS conflicts with BERSERKER, RUSHER</li>
 *   <li>COLLECTOR conflicts with RUSHER</li>
 *   <li>All other combinations are allowed</li>
 * </ul></p>
 */
public final class PersonalityConflictTable {

    /**
     * Map of each personality to its set of conflicting personalities.
     * Symmetric: if A conflicts with B, then B conflicts with A.
     */
    private static final Map<Personality, Set<Personality>> CONFLICTS;

    static {
        CONFLICTS = new EnumMap<>(Personality.class);

        // Initialize empty sets for all personalities
        for (Personality p : Personality.values()) {
            CONFLICTS.put(p, EnumSet.noneOf(Personality.class));
        }

        // Define conflicts (bidirectional)
        addConflict(Personality.AGGRESSIVE, Personality.PASSIVE);
        addConflict(Personality.AGGRESSIVE, Personality.CAMPER);
        addConflict(Personality.PASSIVE, Personality.RUSHER);
        addConflict(Personality.RUSHER, Personality.CAMPER);
        addConflict(Personality.RUSHER, Personality.COLLECTOR);
        addConflict(Personality.BERSERKER, Personality.PASSIVE);
        addConflict(Personality.BERSERKER, Personality.STRATEGIC);
        addConflict(Personality.BERSERKER, Personality.SNIPER);
        addConflict(Personality.BERSERKER, Personality.CAUTIOUS);
        addConflict(Personality.CAUTIOUS, Personality.RUSHER);
    }

    private PersonalityConflictTable() {
        // Static utility class
    }

    /**
     * Adds a bidirectional conflict between two personalities.
     */
    private static void addConflict(@Nonnull Personality a, @Nonnull Personality b) {
        CONFLICTS.get(a).add(b);
        CONFLICTS.get(b).add(a);
    }

    /**
     * Returns true if two personalities conflict and cannot coexist.
     *
     * @param a first personality
     * @param b second personality
     * @return true if they conflict
     */
    public static boolean conflicts(@Nonnull Personality a, @Nonnull Personality b) {
        if (a == b) return false; // Same personality doesn't conflict with itself
        Set<Personality> conflictsA = CONFLICTS.get(a);
        return conflictsA != null && conflictsA.contains(b);
    }

    /**
     * Returns the set of personalities that conflict with the given personality.
     *
     * @param personality the personality to check
     * @return unmodifiable set of conflicting personalities
     */
    @Nonnull
    public static Set<Personality> getConflicts(@Nonnull Personality personality) {
        Set<Personality> set = CONFLICTS.get(personality);
        return set != null ? java.util.Collections.unmodifiableSet(set) : EnumSet.noneOf(Personality.class);
    }

    /**
     * Validates a set of personalities for conflicts. Returns true if
     * there are no conflicts among any pair.
     *
     * @param personalities the set of personalities to validate
     * @return true if no conflicts exist
     */
    public static boolean isValidCombination(@Nonnull Set<Personality> personalities) {
        Personality[] array = personalities.toArray(new Personality[0]);
        for (int i = 0; i < array.length; i++) {
            for (int j = i + 1; j < array.length; j++) {
                if (conflicts(array[i], array[j])) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Validates a list of personality names. Returns true if there are no conflicts.
     *
     * @param personalityNames the personality names to validate
     * @return true if no conflicts exist
     */
    public static boolean isValidCombination(@Nonnull java.util.List<String> personalityNames) {
        EnumSet<Personality> set = EnumSet.noneOf(Personality.class);
        for (String name : personalityNames) {
            Personality p = Personality.fromString(name);
            if (p != null) {
                set.add(p);
            }
        }
        return isValidCombination(set);
    }
}
