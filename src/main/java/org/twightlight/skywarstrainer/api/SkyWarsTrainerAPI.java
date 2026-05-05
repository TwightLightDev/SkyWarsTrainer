package org.twightlight.skywarstrainer.api;

import org.bukkit.Location;
import org.twightlight.skywarstrainer.SkyWarsTrainer;
import org.twightlight.skywarstrainer.ai.decision.UtilityScorer;
import org.twightlight.skywarstrainer.ai.personality.PersonalityProfile;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.bridging.strategies.BridgeStrategy;
import org.twightlight.skywarstrainer.combat.strategies.CombatStrategy;
import org.twightlight.skywarstrainer.config.DifficultyConfig.Difficulty;
import org.twightlight.skywarstrainer.loot.strategies.LootStrategy;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Public API for the SkyWarsTrainer plugin.
 *
 * <p>[FIX 3.3] Custom strategies and considerations are now stored in pending lists.
 * When a new bot is spawned, {@link #applyPendingRegistrations(TrainerBot)} is called
 * to apply all previously registered custom strategies to the new bot.</p>
 */
public class SkyWarsTrainerAPI {

    private static SkyWarsTrainerAPI instance;

    private final SkyWarsTrainer plugin;

    // [FIX 3.3] Pending custom registrations applied to newly spawned bots
    private final List<CombatStrategy> pendingCombatStrategies = new ArrayList<>();
    private final List<BridgeStrategy> pendingBridgeStrategies = new ArrayList<>();
    private final List<LootStrategy> pendingLootStrategies = new ArrayList<>();
    private final List<UtilityScorer> pendingConsiderations = new ArrayList<>();

    public SkyWarsTrainerAPI(@Nonnull SkyWarsTrainer plugin) {
        this.plugin = plugin;
        instance = this;
    }

    @Nullable
    public static SkyWarsTrainerAPI getInstance() {
        return instance;
    }

    public static void clearInstance() {
        instance = null;
    }

    // ─── Bot Lifecycle ──────────────────────────────────────────

    @Nullable
    public TrainerBot spawnBot(@Nonnull org.twightlight.skywars.arena.Arena<?> arena,
                               @Nonnull Location location,
                               @Nonnull Difficulty difficulty,
                               @Nonnull PersonalityProfile profile) {
        return plugin.getBotManager().spawnBot(
                arena, location, difficulty, profile.toNameList(), null);
    }

    @Nullable
    public TrainerBot spawnBot(@Nonnull org.twightlight.skywars.arena.Arena<?> arena,
                               @Nonnull Location location,
                               @Nonnull Difficulty difficulty,
                               @Nonnull PersonalityProfile profile,
                               @Nullable String name) {
        return plugin.getBotManager().spawnBot(
                arena, location, difficulty, profile.toNameList(), name);
    }

    public void removeBot(@Nonnull TrainerBot bot) {
        plugin.getBotManager().removeBot(bot);
    }

    @Nullable
    public TrainerBot getBotByName(@Nonnull String name) {
        return plugin.getBotManager().getBotByName(name);
    }

    @Nonnull
    public List<TrainerBot> getAllBots() {
        return plugin.getBotManager().getAllBots();
    }

    public int getBotCount() {
        return plugin.getBotManager().getActiveBotCount();
    }

    public boolean isBot(@Nonnull UUID entityUuid) {
        return plugin.getBotManager().isBot(entityUuid);
    }

    // ─── Custom Strategy Registration ───────────────────────────

    /**
     * Registers a custom combat strategy. Applied to all existing bots AND
     * stored for future bots.
     *
     * <p>[FIX 3.3] Now stores in pending list for bots spawned after registration.</p>
     */
    public void registerCustomCombatStrategy(@Nonnull CombatStrategy strategy) {
        pendingCombatStrategies.add(strategy);
        for (TrainerBot bot : plugin.getBotManager().getAllBots()) {
            if (bot.getCombatEngine() != null) {
                bot.getCombatEngine().getStrategies().add(strategy);
            }
        }
        plugin.getLogger().info("[API] Registered custom combat strategy: " + strategy.getName());
    }

    /**
     * Registers a custom bridge strategy. Applied to all existing bots AND
     * stored for future bots.
     *
     * <p>[FIX 3.3] Now stores in pending list for bots spawned after registration.</p>
     */
    public void registerCustomBridgeStrategy(@Nonnull BridgeStrategy strategy) {
        pendingBridgeStrategies.add(strategy);
        for (TrainerBot bot : plugin.getBotManager().getAllBots()) {
            if (bot.getBridgeEngine() != null) {
                bot.getBridgeEngine().registerStrategy(strategy);
            }
        }
        plugin.getLogger().info("[API] Registered custom bridge strategy: " + strategy.getName());
    }

    /**
     * Registers a custom loot strategy. Applied to all existing bots AND
     * stored for future bots.
     *
     * <p>[FIX 3.3] Now stores in pending list for bots spawned after registration.</p>
     */
    public void registerCustomLootStrategy(@Nonnull LootStrategy strategy) {
        pendingLootStrategies.add(strategy);
        for (TrainerBot bot : plugin.getBotManager().getAllBots()) {
            if (bot.getLootEngine() != null) {
                bot.getLootEngine().registerStrategy(strategy);
            }
        }
        plugin.getLogger().info("[API] Registered custom loot strategy: " + strategy.getName());
    }

    /**
     * Registers a custom utility consideration. Applied to all existing bots AND
     * stored for future bots.
     *
     * <p>[FIX 3.3] Now stores in pending list for bots spawned after registration.</p>
     */
    public void registerCustomConsideration(@Nonnull UtilityScorer consideration) {
        pendingConsiderations.add(consideration);
        for (TrainerBot bot : plugin.getBotManager().getAllBots()) {
            if (bot.getDecisionEngine() != null) {
                bot.getDecisionEngine().registerCustomConsideration(consideration);
            }
        }
        plugin.getLogger().info("[API] Registered custom consideration: " + consideration.getName());
    }

    /**
     * Applies all pending custom registrations to a newly spawned bot.
     * Called by BotManager.spawnBot() after the bot is fully initialized.
     *
     * @param bot the newly spawned bot
     */
    public void applyPendingRegistrations(@Nonnull TrainerBot bot) {
        for (CombatStrategy strategy : pendingCombatStrategies) {
            if (bot.getCombatEngine() != null) {
                bot.getCombatEngine().getStrategies().add(strategy);
            }
        }
        for (BridgeStrategy strategy : pendingBridgeStrategies) {
            if (bot.getBridgeEngine() != null) {
                bot.getBridgeEngine().registerStrategy(strategy);
            }
        }
        for (LootStrategy strategy : pendingLootStrategies) {
            if (bot.getLootEngine() != null) {
                bot.getLootEngine().registerStrategy(strategy);
            }
        }
        for (UtilityScorer consideration : pendingConsiderations) {
            if (bot.getDecisionEngine() != null) {
                bot.getDecisionEngine().registerCustomConsideration(consideration);
            }
        }
    }

    @Nonnull
    public SkyWarsTrainer getPlugin() {
        return plugin;
    }
}
