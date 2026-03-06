package org.twightlight.skywarstrainer.combat;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.twightlight.skywarstrainer.awareness.ThreatMap;
import org.twightlight.skywarstrainer.awareness.VoidDetector;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.combat.evaluation.EngagementScorer;
import org.twightlight.skywarstrainer.combat.evaluation.ThreatEvaluator;
import org.twightlight.skywarstrainer.combat.strategies.*;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.movement.MovementController;
import org.twightlight.skywarstrainer.util.MathUtil;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;

/**
 * The main combat orchestrator running each tick during the FIGHTING state.
 *
 * <p>CombatEngine coordinates all combat subsystems:
 * <ol>
 *   <li>Updates the current target via {@link ThreatEvaluator}</li>
 *   <li>Updates aim toward target via {@link AimController}</li>
 *   <li>Determines range to target</li>
 *   <li>Selects applicable strategies based on range + personality + difficulty</li>
 *   <li>Scores each strategy via {@link CombatStrategy#getPriority(TrainerBot)}</li>
 *   <li>Executes the top-priority strategy</li>
 *   <li>Runs survival checks (HP, food, position)</li>
 *   <li>Updates {@link ComboTracker} (consecutive hits landed/received)</li>
 * </ol></p>
 *
 * <p>The engine does NOT decide whether the bot should be fighting — that is
 * the DecisionEngine's job. Once the state machine enters FIGHTING, this
 * engine handles all the tactical moment-to-moment combat behavior.</p>
 */
public class CombatEngine {

    private final TrainerBot bot;

    /** Aim controller for simulating head movement toward the target. */
    private final AimController aimController;

    /** Click controller for simulating melee attacks with realistic CPS. */
    private final ClickController clickController;

    /** Knockback calculator following vanilla 1.8 mechanics. */
    private final KnockbackCalculator knockbackCalculator;

    /** Combo tracker for tracking consecutive hits landed and received. */
    private final ComboTracker comboTracker;

    /** Projectile handler for ranged weapon usage (bow, rod, snowball, egg, pearl). */
    private final ProjectileHandler projectileHandler;

    /** Evaluates enemies to determine the best target. */
    private final ThreatEvaluator threatEvaluator;

    /** Scores whether the bot should continue engaging or disengage. */
    private final EngagementScorer engagementScorer;

    /** All registered combat strategies, evaluated each tick for priority. */
    private final List<CombatStrategy> strategies;

    /** The currently active combat strategy being executed. */
    private CombatStrategy activeStrategy;

    /** The current combat target entity (resolved from ThreatMap each evaluation). */
    private LivingEntity currentTarget;

    /** Whether the combat engine is currently active (running). */
    private boolean active;

    /** Tick counter for periodic re-evaluation of target selection. */
    private int targetEvalCounter;

    /** How often (in ticks) to re-evaluate the target. */
    private static final int TARGET_EVAL_INTERVAL = 10;

    /**
     * Creates a new CombatEngine for the given bot. Initializes all combat
     * subsystems and registers all strategy implementations.
     *
     * @param bot the owning trainer bot
     */
    public CombatEngine(@Nonnull TrainerBot bot) {
        this.bot = bot;
        this.aimController = new AimController(bot);
        this.clickController = new ClickController(bot, aimController);
        this.knockbackCalculator = new KnockbackCalculator(bot);
        this.comboTracker = new ComboTracker(bot);
        this.projectileHandler = new ProjectileHandler(bot, aimController);
        this.threatEvaluator = new ThreatEvaluator(bot);
        this.engagementScorer = new EngagementScorer(bot);
        this.strategies = new ArrayList<>();
        this.activeStrategy = null;
        this.currentTarget = null;
        this.active = false;
        this.targetEvalCounter = 0;

        registerStrategies();
    }

    /**
     * Registers all combat strategy implementations. Strategies are evaluated
     * each tick by priority — the highest-priority applicable strategy is executed.
     */
    private void registerStrategies() {
        strategies.add(new StrafeStrategy());
        strategies.add(new WTapStrategy());
        strategies.add(new SprintResetStrategy());
        strategies.add(new BlockHitStrategy());
        strategies.add(new BlockPlacePVPStrategy());
        strategies.add(new RodComboStrategy());
        strategies.add(new TradeHitStrategy());
        strategies.add(new FleeStrategy());
        strategies.add(new ProjectilePvPStrategy());
    }

    /**
     * Activates the combat engine with an initial target. Called when the bot
     * transitions to the FIGHTING state.
     *
     * @param target the initial target to fight, or null to auto-select
     */
    public void engage(@Nullable LivingEntity target) {
        this.active = true;
        this.currentTarget = target;
        this.targetEvalCounter = 0;

        if (target != null) {
            aimController.setTarget(target);
            clickController.rollNewEngagement();
            // In engage(), after setting up the target, fire the event:

            org.bukkit.Bukkit.getPluginManager().callEvent(
                    new org.twightlight.skywarstrainer.api.events.BotCombatEvent(bot, target, null));
        

        }

        comboTracker.reset();

        // Reset all strategies for the new engagement
        for (CombatStrategy strategy : strategies) {
            strategy.reset();
        }

        // Start strafing via the movement controller
        MovementController mc = bot.getMovementController();
        if (mc != null) {
            mc.getStrafeController().startStrafing();
        }
    }

    /**
     * Deactivates the combat engine. Called when the bot exits the FIGHTING state
     * (e.g., target dies, bot flees, combat ends).
     */
    public void disengage() {
        this.active = false;
        this.currentTarget = null;
        this.activeStrategy = null;

        aimController.setTarget(null);
        comboTracker.reset();

        // Stop combat-specific movement
        MovementController mc = bot.getMovementController();
        if (mc != null) {
            mc.getStrafeController().stopStrafing();
        }

        // Reset all strategies
        for (CombatStrategy strategy : strategies) {
            strategy.reset();
        }
    }

    /**
     * Main tick method called every server tick during FIGHTING state.
     *
     * <p>Processing pipeline:
     * <ol>
     *   <li>Validate current target (still alive, in range?)</li>
     *   <li>Re-evaluate target selection periodically</li>
     *   <li>Update aim toward target</li>
     *   <li>Calculate range to target</li>
     *   <li>Score applicable strategies</li>
     *   <li>Execute top strategy</li>
     *   <li>Handle melee click attempts</li>
     *   <li>Survival checks</li>
     *   <li>Update combo tracker</li>
     * </ol></p>
     */
    public void tick() {
        if (!active) return;

        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity == null || botEntity.isDead()) {
            disengage();
            return;
        }

        // Step 1: Validate target
        if (!isTargetValid()) {
            // Try to find a new target
            currentTarget = selectBestTarget();
            if (currentTarget == null) {
                // No valid targets — disengage combat
                disengage();
                return;
            }
            aimController.setTarget(currentTarget);
            clickController.rollNewEngagement();
        }

        // Step 2: Periodic target re-evaluation
        targetEvalCounter++;
        if (targetEvalCounter >= TARGET_EVAL_INTERVAL) {
            targetEvalCounter = 0;
            LivingEntity betterTarget = selectBestTarget();
            if (betterTarget != null && !betterTarget.equals(currentTarget)) {
                currentTarget = betterTarget;
                aimController.setTarget(currentTarget);
                clickController.rollNewEngagement();
            }
        }

        // Step 3: Update aim
        aimController.tick();

        // Step 4: Calculate range
        double range = botEntity.getLocation().distance(currentTarget.getLocation());

        // Step 5 & 6: Select and execute best strategy
        CombatStrategy bestStrategy = selectBestStrategy();
        if (bestStrategy != null) {
            if (activeStrategy != bestStrategy) {
                activeStrategy = bestStrategy;
            }
            try {
                bestStrategy.execute(bot);
            } catch (Exception e) {
                bot.getPlugin().getLogger().log(Level.WARNING,
                        "Error executing combat strategy " + bestStrategy.getName()
                                + " for bot " + bot.getName(), e);
            }
        }

        // Step 7: Attempt melee attack if in range
        if (range <= 3.0) {
            boolean hit = clickController.tryClick();
            if (hit) {
                comboTracker.onHitLanded();
                applyAttackKnockback(currentTarget);
            }
        }

        // Step 8: Survival checks
        performSurvivalChecks(botEntity, range);

        // Step 9: Update combo tracker
        comboTracker.tick();

        // Step 10: Approach or maintain range
        handlePositioning(botEntity, range);
    }

    /**
     * Checks whether the current target is still a valid combat target.
     *
     * @return true if the target exists, is alive, and within awareness radius
     */
    private boolean isTargetValid() {
        if (currentTarget == null) return false;
        if (currentTarget.isDead()) return false;

        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity == null) return false;

        // Check if still within awareness radius
        double maxRange = bot.getDifficultyProfile().getAwarenessRadius();
        double distance = botEntity.getLocation().distance(currentTarget.getLocation());
        return distance <= maxRange;
    }

    /**
     * Selects the best target from all visible threats using the ThreatEvaluator.
     *
     * @return the best target to attack, or null if none available
     */
    @Nullable
    private LivingEntity selectBestTarget() {
        ThreatMap threatMap = bot.getThreatMap();
        if (threatMap == null) return null;

        List<ThreatMap.ThreatEntry> visibleThreats = threatMap.getVisibleThreats();
        if (visibleThreats.isEmpty()) return null;

        ThreatMap.ThreatEntry bestEntry = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (ThreatMap.ThreatEntry entry : visibleThreats) {
            double score = threatEvaluator.evaluateThreat(entry);
            if (score > bestScore) {
                bestScore = score;
                bestEntry = entry;
            }
        }

        if (bestEntry == null) return null;

        // Resolve the ThreatEntry to a LivingEntity
        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity == null) return null;

        for (org.bukkit.entity.Entity entity : botEntity.getNearbyEntities(
                bot.getDifficultyProfile().getAwarenessRadius(),
                bot.getDifficultyProfile().getAwarenessRadius(),
                bot.getDifficultyProfile().getAwarenessRadius())) {
            if (entity.getUniqueId().equals(bestEntry.playerId) && entity instanceof LivingEntity) {
                return (LivingEntity) entity;
            }
        }

        return null;
    }

    /**
     * Evaluates all registered strategies and selects the one with the highest
     * priority that is currently activatable.
     *
     * @return the best strategy, or null if none applicable
     */
    @Nullable
    private CombatStrategy selectBestStrategy() {
        CombatStrategy best = null;
        double bestPriority = Double.NEGATIVE_INFINITY;

        for (CombatStrategy strategy : strategies) {
            if (!strategy.shouldActivate(bot)) continue;

            double priority = strategy.getPriority(bot);
            if (priority > bestPriority) {
                bestPriority = priority;
                best = strategy;
            }
        }

        return best;
    }

    /**
     * Applies attack knockback to the target when the bot lands a hit.
     * Uses the KnockbackCalculator for vanilla 1.8 KB mechanics.
     *
     * @param target the entity that was hit
     */
    private void applyAttackKnockback(@Nonnull LivingEntity target) {
        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity == null) return;

        MovementController mc = bot.getMovementController();
        boolean sprinting = mc != null && mc.getSprintController().isSprinting();

        // Get knockback enchantment level from held weapon
        int kbLevel = 0;
        Player player = bot.getPlayerEntity();
        if (player != null) {
            ItemStack weapon = player.getItemInHand();
            if (weapon != null && weapon.containsEnchantment(
                    org.bukkit.enchantments.Enchantment.KNOCKBACK)) {
                kbLevel = weapon.getEnchantmentLevel(
                        org.bukkit.enchantments.Enchantment.KNOCKBACK);
            }
        }

        org.twightlight.skywarstrainer.util.NMSHelper.applyKnockback(
                target, botEntity, sprinting, kbLevel, 0.0);
    }

    /**
     * Performs survival checks during combat: health assessment, positioning
     * near void edges, and eating golden apples when needed.
     *
     * @param botEntity the bot's living entity
     * @param range     distance to current target
     */
    private void performSurvivalChecks(@Nonnull LivingEntity botEntity, double range) {
        DifficultyProfile diff = bot.getDifficultyProfile();
        double healthFraction = botEntity.getHealth() / botEntity.getMaxHealth();

        // Check if near void edge during combat — reposition away
        VoidDetector voidDetector = bot.getVoidDetector();
        if (voidDetector != null && voidDetector.isOnEdge()) {
            MovementController mc = bot.getMovementController();
            if (mc != null) {
                Float safeDir = voidDetector.getSafeDirection();
                if (safeDir != null) {
                    // Move away from void edge
                    double safeYawRad = Math.toRadians(safeDir);
                    Location botLoc = botEntity.getLocation();
                    Location safeTarget = botLoc.clone().add(
                            -Math.sin(safeYawRad) * 3, 0, Math.cos(safeYawRad) * 3);
                    mc.setMoveTarget(safeTarget);
                }
            }
        }
    }

    /**
     * Handles combat positioning: approach when too far, maintain optimal
     * range, and enable strafing during melee.
     *
     * @param botEntity the bot entity
     * @param range     current distance to target
     */
    private void handlePositioning(@Nonnull LivingEntity botEntity, double range) {
        MovementController mc = bot.getMovementController();
        if (mc == null) return;

        if (range > 3.5 && range <= 15) {
            // Approach target for melee — sprint toward them
            mc.getSprintController().startSprinting();
            mc.setLookTarget(currentTarget.getLocation().add(0, 1.0, 0));
            mc.setMoveTarget(currentTarget.getLocation());
        } else if (range <= 3.5) {
            // In melee range — strafe and look at target
            mc.setLookTarget(currentTarget.getLocation().add(0, 1.0, 0));
            mc.setMoveTarget(null); // Strafing handles lateral movement
        } else {
            // Too far — if ranged equipped, hold position; otherwise approach
            mc.setLookTarget(currentTarget.getLocation().add(0, 1.0, 0));
            mc.setMoveTarget(currentTarget.getLocation());
            mc.getSprintController().startSprinting();
        }
    }

    /**
     * Notifies the combat engine that the bot was hit. Used for:
     * combo tracking, KB reduction, and interrupt-based re-evaluation.
     *
     * @param attacker the entity that hit the bot, or null if unknown
     * @param damage   the damage dealt
     */
    public void onBotHit(@Nullable LivingEntity attacker, double damage) {
        comboTracker.onHitReceived();

        // Apply KB reduction based on difficulty
        if (attacker != null) {
            LivingEntity botEntity = bot.getLivingEntity();
            if (botEntity != null) {
                org.bukkit.util.Vector currentVel = botEntity.getVelocity();
                org.bukkit.util.Vector reducedVel = knockbackCalculator.reduceIncomingKnockback(currentVel);
                botEntity.setVelocity(reducedVel);
            }
        }

        // If not currently targeting the attacker, consider switching
        if (attacker != null && active && currentTarget != null
                && !currentTarget.equals(attacker)) {
            // Threat from new source — may need to retarget
            targetEvalCounter = TARGET_EVAL_INTERVAL; // Force re-evaluation next tick
        }
    }

    /**
     * Returns whether the bot should flee based on current combat state.
     * This is checked by the decision engine for interrupt-based state transitions.
     *
     * @return true if the bot should transition to FLEEING state
     */
    public boolean shouldFlee() {
        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity == null) return true;

        DifficultyProfile diff = bot.getDifficultyProfile();
        double healthFraction = botEntity.getHealth() / botEntity.getMaxHealth();
        return healthFraction < diff.getFleeHealthThreshold();
    }

    /**
     * Returns the engagement score — a measure of how favorable the current
     * combat situation is for the bot. Used by the decision engine.
     *
     * @return score from 0.0 (terrible) to 1.0 (dominant)
     */
    public double getEngagementScore() {
        if (!active || currentTarget == null) return 0.0;
        return engagementScorer.score(currentTarget);
    }

    // ─── Accessors ──────────────────────────────────────────────

    /** @return the aim controller */
    @Nonnull
    public AimController getAimController() { return aimController; }

    /** @return the click controller */
    @Nonnull
    public ClickController getClickController() { return clickController; }

    /** @return the knockback calculator */
    @Nonnull
    public KnockbackCalculator getKnockbackCalculator() { return knockbackCalculator; }

    /** @return the combo tracker */
    @Nonnull
    public ComboTracker getComboTracker() { return comboTracker; }

    /** @return the projectile handler */
    @Nonnull
    public ProjectileHandler getProjectileHandler() { return projectileHandler; }

    /** @return the threat evaluator */
    @Nonnull
    public ThreatEvaluator getThreatEvaluator() { return threatEvaluator; }

    /** @return the engagement scorer */
    @Nonnull
    public EngagementScorer getEngagementScorer() { return engagementScorer; }

    /** @return the current combat target, or null if none */
    @Nullable
    public LivingEntity getCurrentTarget() { return currentTarget; }

    /** @return the currently active combat strategy, or null */
    @Nullable
    public CombatStrategy getActiveStrategy() { return activeStrategy; }

    /** @return true if the combat engine is active */
    public boolean isActive() { return active; }

    /** @return all registered combat strategies */
    @Nonnull
    public List<CombatStrategy> getStrategies() { return strategies; }
}

