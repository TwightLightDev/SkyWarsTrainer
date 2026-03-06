package org.twightlight.skywarstrainer.ai.personality;

import org.twightlight.skywarstrainer.util.MathUtil;

import javax.annotation.Nonnull;
import java.util.*;

/**
 * Holds a set of personalities assigned to a bot and resolves their combined
 * weight modifiers.
 *
 * <p>When a bot has multiple personalities, their weight modifiers MULTIPLY.
 * For example: AGGRESSIVE (FIGHT ×1.8) + BERSERKER (FIGHT ×1.5) = FIGHT ×2.7.
 * Final multipliers are clamped to [0.01, 5.0].</p>
 *
 * <p>This class is the single source of truth for personality-based modifiers
 * that feed into the DecisionEngine, CombatEngine, and other subsystems.</p>
 */
public class PersonalityProfile {

    /**
     * The personalities assigned to this bot (1-3, no conflicts).
     */
    private final List<Personality> personalities;

    /**
     * Cached resolved modifiers. Key: modifier name, Value: combined multiplier.
     * Lazily computed on first access and invalidated when personalities change.
     */
    private Map<String, Double> resolvedModifiers;

    /**
     * Creates a PersonalityProfile with no personalities.
     */
    public PersonalityProfile() {
        this.personalities = new ArrayList<>(3);
        this.resolvedModifiers = null;
    }

    /**
     * Creates a PersonalityProfile with the given personalities.
     *
     * @param personalities the personalities to assign
     * @throws IllegalArgumentException if personalities conflict
     */
    public PersonalityProfile(@Nonnull Personality... personalities) {
        this();
        for (Personality p : personalities) {
            addPersonality(p);
        }
    }

    /**
     * Creates a PersonalityProfile from personality name strings.
     *
     * @param names list of personality names
     * @return a new profile, ignoring invalid/conflicting names
     */
    @Nonnull
    public static PersonalityProfile fromNames(@Nonnull List<String> names) {
        PersonalityProfile profile = new PersonalityProfile();
        for (String name : names) {
            Personality p = Personality.fromString(name);
            if (p != null && profile.canAdd(p)) {
                profile.addPersonalityUnchecked(p);
            }
        }
        return profile;
    }

    // ─── Personality Management ─────────────────────────────────

    /**
     * Adds a personality to this profile. Validates that it doesn't conflict
     * with existing personalities and that the max of 3 is not exceeded.
     *
     * @param personality the personality to add
     * @return true if added, false if conflicting, duplicate, or at capacity
     */
    public boolean addPersonality(@Nonnull Personality personality) {
        if (!canAdd(personality)) return false;
        return addPersonalityUnchecked(personality);
    }

    /**
     * Adds a personality without conflict checking.
     */
    private boolean addPersonalityUnchecked(@Nonnull Personality personality) {
        if (personalities.contains(personality)) return false;
        if (personalities.size() >= 3) return false;
        personalities.add(personality);
        resolvedModifiers = null; // Invalidate cache
        return true;
    }

    /**
     * Returns true if the given personality can be added (no conflicts, not duplicate,
     * not at capacity).
     *
     * @param personality the personality to check
     * @return true if it can be added
     */
    public boolean canAdd(@Nonnull Personality personality) {
        if (personalities.contains(personality)) return false;
        if (personalities.size() >= 3) return false;
        for (Personality existing : personalities) {
            if (PersonalityConflictTable.conflicts(existing, personality)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Removes a personality from this profile.
     *
     * @param personality the personality to remove
     * @return true if it was present and removed
     */
    public boolean removePersonality(@Nonnull Personality personality) {
        boolean removed = personalities.remove(personality);
        if (removed) resolvedModifiers = null;
        return removed;
    }

    /**
     * Replaces all personalities with the given set.
     *
     * @param newPersonalities the new personality set
     * @throws IllegalArgumentException if the set contains conflicts
     */
    public void setPersonalities(@Nonnull List<Personality> newPersonalities) {
        EnumSet<Personality> asSet = EnumSet.noneOf(Personality.class);
        asSet.addAll(newPersonalities);
        if (!PersonalityConflictTable.isValidCombination(asSet)) {
            throw new IllegalArgumentException("Personality set contains conflicts: " + newPersonalities);
        }
        personalities.clear();
        personalities.addAll(newPersonalities);
        resolvedModifiers = null;
    }

    /**
     * Returns the list of assigned personalities.
     *
     * @return unmodifiable list of personalities
     */
    @Nonnull
    public List<Personality> getPersonalities() {
        return Collections.unmodifiableList(personalities);
    }

    /**
     * Returns true if this profile has the given personality.
     *
     * @param personality the personality to check
     * @return true if present
     */
    public boolean hasPersonality(@Nonnull Personality personality) {
        return personalities.contains(personality);
    }

    /**
     * Returns the number of personalities assigned.
     *
     * @return personality count (0-3)
     */
    public int getPersonalityCount() {
        return personalities.size();
    }

    /**
     * Returns true if this profile has no personalities.
     *
     * @return true if empty
     */
    public boolean isEmpty() {
        return personalities.isEmpty();
    }

    // ─── Modifier Resolution ────────────────────────────────────

    /**
     * Returns the resolved (multiplicatively combined) modifier for a given key.
     * If no personality modifies this key, returns 1.0.
     *
     * @param key the modifier key (e.g., "FIGHT", "fleeHealthThreshold")
     * @return the combined multiplier, clamped to [0.01, 5.0]
     */
    public double getModifier(@Nonnull String key) {
        ensureResolved();
        return resolvedModifiers.getOrDefault(key, 1.0);
    }

    /**
     * Returns the full resolved modifier map.
     *
     * @return unmodifiable map of all resolved modifiers
     */
    @Nonnull
    public Map<String, Double> getResolvedModifiers() {
        ensureResolved();
        return Collections.unmodifiableMap(resolvedModifiers);
    }

    /**
     * Computes and caches the resolved modifier map if not already computed.
     * When multiple personalities modify the same key, their values multiply.
     */
    private void ensureResolved() {
        if (resolvedModifiers != null) return;

        resolvedModifiers = new LinkedHashMap<>();

        // Collect all unique keys
        Set<String> allKeys = new LinkedHashSet<>();
        for (Personality p : personalities) {
            allKeys.addAll(p.getModifiers().keySet());
        }

        // For each key, multiply all personality modifiers
        for (String key : allKeys) {
            double combined = 1.0;
            for (Personality p : personalities) {
                combined *= p.getModifier(key);
            }
            // Clamp to reasonable range
            combined = MathUtil.clamp(combined, 0.01, 5.0);
            resolvedModifiers.put(key, combined);
        }
    }

    /**
     * Returns the personality names as a comma-separated string.
     *
     * @return the personality names string
     */
    @Nonnull
    public String toDisplayString() {
        if (personalities.isEmpty()) return "None";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < personalities.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(personalities.get(i).getDisplayName());
        }
        return sb.toString();
    }

    /**
     * Returns the personality names as a list of strings.
     *
     * @return list of personality name strings
     */
    @Nonnull
    public List<String> toNameList() {
        List<String> names = new ArrayList<>(personalities.size());
        for (Personality p : personalities) {
            names.add(p.name());
        }
        return names;
    }

    @Override
    public String toString() {
        return "PersonalityProfile{" + toDisplayString()
                + ", modifiers=" + (resolvedModifiers != null ? resolvedModifiers.size() : "unresolved")
                + "}";
    }
}
