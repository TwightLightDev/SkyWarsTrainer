package org.twightlight.skywarstrainer.movement.strategies;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.combat.counter.CounterModifiers;
import org.twightlight.skywarstrainer.combat.counter.EnemyBehaviorAnalyzer;
import org.twightlight.skywarstrainer.combat.counter.EnemyProfile;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Context snapshot for approach strategy evaluation. Captures the
 * information strategies need to determine viability and priority.
 *
 * <p><b>Phase 7 Integration:</b> Now includes counter modifiers and enemy
 * profile from the {@link EnemyBehaviorAnalyzer}. Approach strategies use
 * this to make counter-play-aware routing decisions (e.g., diagonal approach
 * against a sniper, direct rush against a camper who is distracted).</p>
 */
public class ApproachContext {

    /** Distance to target in blocks. */
    public double distanceToTarget;

    /** Height difference (positive = target is above). */
    public double heightDifference;

    /** Whether the target appears to be distracted (looting, bridging). */
    public boolean targetDistracted;

    /** Whether the target has a bow and is looking at bot. */
    public boolean targetHasBowAimed;

    /** Number of building blocks the bot has. */
    public int availableBlocks;

    /** Whether the bot has an ender pearl. */
    public boolean hasEnderPearl;

    /** Number of alive players in the game. */
    public int alivePlayerCount;

    /** Whether this is a 1v1 situation. */
    public boolean is1v1;

    /** Target's estimated HP fraction. */
    public double targetHealthEstimate;

    /** Bot's equipment score. */
    public double botEquipmentScore;

    // ═══ Phase 7: Counter-play awareness ═══

    /** Counter modifiers for the target (from EnemyBehaviorAnalyzer). */
    @Nonnull
    public CounterModifiers counterMods = new CounterModifiers();

    /** The enemy profile for the target, or null if unknown. */
    @Nullable
    public EnemyProfile enemyProfile;

    /** Whether multiple enemies are alive (for flanking priority). */
    public boolean multipleEnemies;

    /**
     * Populates this context from the bot and target state.
     *
     * @param bot    the bot
     * @param target the target entity
     * @return this context for chaining
     */
    @Nonnull
    public ApproachContext populate(@Nonnull TrainerBot bot, @Nonnull LivingEntity target) {
        Location botLoc = bot.getLocation();
        Location targetLoc = target.getLocation();

        if (botLoc != null && targetLoc != null) {
            distanceToTarget = botLoc.distance(targetLoc);
            heightDifference = targetLoc.getY() - botLoc.getY();
        }

        // Target distracted check
        if (target instanceof org.bukkit.entity.Player) {
            Location eyeLoc = ((org.bukkit.entity.Player) target).getEyeLocation();
            if (botLoc != null) {
                org.bukkit.util.Vector toBot = botLoc.toVector().subtract(eyeLoc.toVector()).normalize();
                org.bukkit.util.Vector lookDir = eyeLoc.getDirection();
                double dot = toBot.dot(lookDir);
                targetDistracted = dot < 0.3;
                targetHasBowAimed = dot > 0.8;
            }
        }

        // Resources
        if (bot.getBridgeEngine() != null) {
            availableBlocks = bot.getBridgeEngine().getAvailableBlockCount();
        }

        // Pearl check
        if (bot.getPlayerEntity() != null) {
            hasEnderPearl = bot.getPlayerEntity().getInventory()
                    .contains(org.bukkit.Material.ENDER_PEARL);
        }

        // Player count
        try {
            alivePlayerCount = bot.getArena().getAlive();
        } catch (Exception e) {
            alivePlayerCount = 8;
        }
        is1v1 = alivePlayerCount <= 2;
        multipleEnemies = alivePlayerCount > 2;

        // Target health
        targetHealthEstimate = target.getHealth() / target.getMaxHealth();

        // ═══ Phase 7: Counter-play data ═══
        EnemyBehaviorAnalyzer analyzer = bot.getEnemyAnalyzer();
        if (analyzer != null) {
            counterMods = analyzer.getCounterModifiers(target.getUniqueId());
            enemyProfile = analyzer.getEnemyProfile(target.getUniqueId());
        } else {
            counterMods = new CounterModifiers();
            enemyProfile = null;
        }

        return this;
    }
}
