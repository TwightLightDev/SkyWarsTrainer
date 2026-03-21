package org.twightlight.skywarstrainer.combat;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.twightlight.skywarstrainer.awareness.ThreatMap;
import org.twightlight.skywarstrainer.awareness.VoidDetector;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.combat.counter.EnemyBehaviorAnalyzer;
import org.twightlight.skywarstrainer.combat.engagement.EngagementEngine;
import org.twightlight.skywarstrainer.combat.engagement.EngagementPattern;
import org.twightlight.skywarstrainer.combat.evaluation.EngagementScorer;
import org.twightlight.skywarstrainer.combat.evaluation.ThreatEvaluator;
import org.twightlight.skywarstrainer.combat.strategies.*;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.movement.MovementController;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
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

    /** Centralized ranged combat handler bridging projectiles + utility items. */
    private final RangedCombatHandler rangedCombatHandler;

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

    private final EngagementEngine patternManager;

    /** How often (in ticks) to re-evaluate the target. */
    private static final int TARGET_EVAL_INTERVAL = 10;

    // ─── Fast-Reflect Fields ────────────────────────────────────
    /** Ticks remaining for the fast-reflect priority window after being hit. */
    private int reflectWindowTicks;

    /** Whether a reflect counter-attack has been queued this window. */
    private boolean reflectQueued;

    /** Maximum ticks after being hit where a reflect counter-attack is attempted. */
    private static final int REFLECT_WINDOW = 3;


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
        this.rangedCombatHandler = new RangedCombatHandler(bot, projectileHandler);
        this.threatEvaluator = new ThreatEvaluator(bot);
        this.engagementScorer = new EngagementScorer(bot);
        this.patternManager = new EngagementEngine(bot);
        this.strategies = new ArrayList<>();
        this.activeStrategy = null;
        this.currentTarget = null;
        this.active = false;
        this.targetEvalCounter = 0;
        this.reflectWindowTicks = 0;
        this.reflectQueued = false;

        registerStrategies();
    }

    /**
     * Registers all combat strategy implementations. Strategies are evaluated
     * each tick by priority — the highest-priority applicable strategy is executed.
     *
     * <p>Includes the new {@link UtilityItemStrategy} for lava, flint & steel,
     * TNT, cobweb, and water bucket combat usage.</p>
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
        strategies.add(new UtilityItemStrategy());
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

            org.bukkit.Bukkit.getPluginManager().callEvent(
                    new org.twightlight.skywarstrainer.api.events.BotCombatEvent(bot, target, null));
        }

        comboTracker.reset();
        reflectWindowTicks = 0;
        reflectQueued = false;
        for (CombatStrategy strategy : strategies) {
            strategy.reset();
        }

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
        reflectWindowTicks = 0;
        reflectQueued = false;
        MovementController mc = bot.getMovementController();
        if (mc != null) {
            mc.getStrafeController().stopStrafing();
            mc.releaseAuthority(MovementController.MovementAuthority.COMBAT);
        }

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
     *   <li>Tick projectile cooldowns</li>
     *   <li>Score applicable strategies</li>
     *   <li>Execute top strategy</li>
     *   <li>Handle melee click attempts</li>
     *   <li>Attempt ranged actions at distance</li>
     *   <li>Survival checks</li>
     *   <li>Update combo tracker</li>
     * </ol></p>
     */
    public void tick() {
        if (!active) return;

        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity == null || botEntity.isDead()) { disengage(); return; }

        // Step 1: Validate target
        if (!isTargetValid()) {
            currentTarget = selectBestTarget();
            if (currentTarget == null) { disengage(); return; }
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

        // Step 4.5: Tick projectile cooldowns every tick
        projectileHandler.tick();

        // ═══ Phase 7: Engagement Pattern Check (BEFORE normal strategies) ═══
        if (patternManager.tickActivePattern(bot, currentTarget)) {
            comboTracker.tick();
            return;
        }

        EngagementPattern pattern = patternManager.evaluatePatterns(bot, currentTarget);
        if (pattern != null) {
            patternManager.activate(pattern);
            comboTracker.tick();
            handlePositioning(botEntity, range);
            return;
        }
        // ═══ End Phase 7 pattern check ═══

        // Step 5 & 6: Select and execute best strategy
        CombatStrategy bestStrategy = selectBestStrategy();
        if (bestStrategy != null) {
            if (activeStrategy != bestStrategy) activeStrategy = bestStrategy;
            try {
                bestStrategy.execute(bot);
            } catch (Exception e) {
                bot.getPlugin().getLogger().log(Level.WARNING,
                        "Error executing strategy " + bestStrategy.getName(), e);
            }
        }

        // ─── Fast-Reflect Counter-Attack ───────────────────────
        if (reflectWindowTicks > 0) {
            reflectWindowTicks--;
            if (reflectQueued && range <= 3.5) {
                MovementController mc = bot.getMovementController();
                if (mc != null && mc.getSprintController().isSprinting()) {
                    mc.getSprintController().performSprintReset();
                }
                boolean reflectHit = clickController.tryClick();
                if (reflectHit) {
                    comboTracker.onHitLanded();
                    applyAttackKnockback(currentTarget);
                    EnemyBehaviorAnalyzer analyzer = bot.getEnemyAnalyzer();
                    if (analyzer != null) {
                        analyzer.onHitLandedOnEnemy(currentTarget.getUniqueId());
                    }
                    reflectQueued = false;
                }
            }
            if (reflectWindowTicks <= 0) {
                reflectQueued = false;
            }
        }

        // Step 7: Normal melee attack
        if (range <= 3.0) {
            boolean hit = clickController.tryClick();
            if (hit) {
                comboTracker.onHitLanded();
                applyAttackKnockback(currentTarget);
                EnemyBehaviorAnalyzer analyzer = bot.getEnemyAnalyzer();
                if (analyzer != null) {
                    analyzer.onHitLandedOnEnemy(currentTarget.getUniqueId());
                }
            }
        }

        // Step 7.5: Ranged action at distance (only if no active melee strategy
        // is currently handling ranged — avoids double-shooting with ProjectilePvPStrategy)
        if (range > 3.0 && (activeStrategy == null
                || (!activeStrategy.getName().equals("ProjectilePvP")
                && !activeStrategy.getName().equals("RodCombo")
                && !activeStrategy.getName().equals("UtilityItem")))) {
            rangedCombatHandler.tryBestRangedAction(currentTarget);
        }

        // Step 8-10: Survival, combo, positioning
        performSurvivalChecks(botEntity, range);
        comboTracker.tick();
        handlePositioning(botEntity, range);
    }


    /**
     * Checks whether the current target is still a valid combat target.
     */
    private boolean isTargetValid() {
        if (currentTarget == null) return false;
        if (currentTarget.isDead()) return false;

        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity == null) return false;

        double maxRange = bot.getDifficultyProfile().getAwarenessRadius();
        double distance = botEntity.getLocation().distance(currentTarget.getLocation());
        return distance <= maxRange;
    }

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

    private void applyAttackKnockback(@Nonnull LivingEntity target) {
        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity == null) return;

        MovementController mc = bot.getMovementController();
        boolean sprinting = mc != null && mc.getSprintController().isSprinting();

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

    private void performSurvivalChecks(@Nonnull LivingEntity botEntity, double range) {
        DifficultyProfile diff = bot.getDifficultyProfile();
        double healthFraction = botEntity.getHealth() / botEntity.getMaxHealth();

        VoidDetector voidDetector = bot.getVoidDetector();
        if (voidDetector != null && voidDetector.isOnEdge()) {
            MovementController mc = bot.getMovementController();
            if (mc != null) {
                Float safeDir = voidDetector.getSafeDirection();
                if (safeDir != null) {
                    double safeYawRad = Math.toRadians(safeDir);
                    Location botLoc = botEntity.getLocation();
                    Location safeTarget = botLoc.clone().add(
                            -Math.sin(safeYawRad) * 3, 0, Math.cos(safeYawRad) * 3);
                    mc.setMoveTarget(safeTarget, MovementController.MovementAuthority.COMBAT);
                }
            }
        }

        // Water bucket MLG check: if falling during combat
        if (botEntity.getVelocity().getY() < -0.5 && diff.getWaterBucketMLG() > 0.1) {
            if (bot.getInventoryEngine() != null) {
                bot.getInventoryEngine().getUtilityItemHandler().tryWaterBucketMLG();
            }
        }
    }

    private void handlePositioning(@Nonnull LivingEntity botEntity, double range) {
        MovementController mc = bot.getMovementController();
        if (mc == null) return;

        if (range > 3.5 && range <= 15) {
            mc.getSprintController().startSprinting();
            mc.setLookTarget(currentTarget.getLocation().add(0, 1.0, 0));
            mc.setMoveTarget(currentTarget.getLocation(), MovementController.MovementAuthority.COMBAT);
        } else if (range <= 3.5) {
            mc.setLookTarget(currentTarget.getLocation().add(0, 1.0, 0));
            mc.setMoveTarget(null, MovementController.MovementAuthority.COMBAT);
        } else {
            mc.setLookTarget(currentTarget.getLocation().add(0, 1.0, 0));
            mc.setMoveTarget(currentTarget.getLocation(), MovementController.MovementAuthority.COMBAT);
            mc.getSprintController().startSprinting();
        }
    }


    /**
     * Notifies the combat engine that the bot was hit.
     */
    public void onBotHit(@Nullable LivingEntity attacker) {
        comboTracker.onHitReceived();

        if (attacker != null) {
            LivingEntity botEntity = bot.getLivingEntity();
            if (botEntity != null) {
                org.bukkit.util.Vector currentVel = botEntity.getVelocity();
                org.bukkit.util.Vector reducedVel = knockbackCalculator.reduceIncomingKnockback(currentVel);
                botEntity.setVelocity(reducedVel);

                DifficultyProfile diff = bot.getDifficultyProfile();
                double reflectSkill = diff.getSprintResetChance();
                if (reflectSkill >= 0.4 && active && currentTarget != null) {
                    double reflectChance = Math.min(1.0, reflectSkill * 1.2);
                    if (org.twightlight.skywarstrainer.util.RandomUtil.chance(reflectChance)) {
                        reflectWindowTicks = REFLECT_WINDOW;
                        reflectQueued = true;

                        MovementController mc = bot.getMovementController();
                        if (mc != null) {
                            mc.getSprintController().startSprinting();
                            double counterForce = reflectSkill * 0.25;
                            org.bukkit.util.Vector toAttacker = attacker.getLocation().toVector()
                                    .subtract(botEntity.getLocation().toVector());
                            toAttacker.setY(0);
                            if (toAttacker.lengthSquared() > 0.001) {
                                toAttacker.normalize().multiply(counterForce);
                                org.bukkit.util.Vector newVel = botEntity.getVelocity().add(toAttacker);
                                botEntity.setVelocity(newVel);
                            }
                        }
                    }
                }
            }
            EnemyBehaviorAnalyzer analyzer = bot.getEnemyAnalyzer();
            if (analyzer != null) {
                analyzer.onHitReceivedFromEnemy(attacker.getUniqueId());
            }
        }

        if (attacker != null && active && currentTarget != null
                && !currentTarget.equals(attacker)) {
            targetEvalCounter = TARGET_EVAL_INTERVAL;
        }
    }

    public boolean shouldFlee() {
        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity == null) return true;

        DifficultyProfile diff = bot.getDifficultyProfile();
        double healthFraction = botEntity.getHealth() / botEntity.getMaxHealth();
        return healthFraction < diff.getFleeHealthThreshold();
    }

    public double getEngagementScore() {
        if (!active || currentTarget == null) return 0.0;
        return engagementScorer.score(currentTarget);
    }

    // ─── Accessors ──────────────────────────────────────────────

    @Nonnull public AimController getAimController() { return aimController; }
    @Nonnull public ClickController getClickController() { return clickController; }
    @Nonnull public KnockbackCalculator getKnockbackCalculator() { return knockbackCalculator; }
    @Nonnull public ComboTracker getComboTracker() { return comboTracker; }
    @Nonnull public ProjectileHandler getProjectileHandler() { return projectileHandler; }
    @Nonnull public RangedCombatHandler getRangedCombatHandler() { return rangedCombatHandler; }
    @Nonnull public ThreatEvaluator getThreatEvaluator() { return threatEvaluator; }
    @Nonnull public EngagementScorer getEngagementScorer() { return engagementScorer; }
    @Nullable public LivingEntity getCurrentTarget() { return currentTarget; }
    @Nullable public CombatStrategy getActiveStrategy() { return activeStrategy; }
    public boolean isActive() { return active; }
    @Nonnull public List<CombatStrategy> getStrategies() { return strategies; }
    @Nonnull public EngagementEngine getPatternManager() { return patternManager; }
}
