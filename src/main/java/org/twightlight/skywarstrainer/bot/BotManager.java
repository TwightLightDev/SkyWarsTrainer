package org.twightlight.skywarstrainer.bot;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.twightlight.skywars.api.server.SkyWarsTeam;
import org.twightlight.skywars.arena.Arena;
import org.twightlight.skywars.arena.ui.enums.SkyWarsMode;
import org.twightlight.skywars.database.Database;
import org.twightlight.skywarstrainer.SkyWarsTrainer;
import org.twightlight.skywarstrainer.ai.personality.Personality;
import org.twightlight.skywarstrainer.ai.personality.PersonalityConflictTable;
import org.twightlight.skywarstrainer.config.DifficultyConfig.Difficulty;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.util.DebugLogger;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * Lifecycle manager for trainer bots. The single entry point external code
 * (commands, API, tests) uses to spawn and remove bots.
 *
 * <p><b>What spawn does</b> (re-designed — no more passing arbitrary locations
 * around, no more "11 bots in a single block"):</p>
 * <ol>
 *   <li>Validates the arena's mode is 1-per-team (Solo / 1v1 modes only).
 *       Other modes are soft-locked until the team-update lands.</li>
 *   <li>Creates the Citizens NPC at the arena world's origin (a throwaway
 *       cache location). The NPC is invisible to players for the few ticks
 *       between creation and Arena.connect's teleport.</li>
 *   <li>Builds a {@link BotAccount} (which, in its own constructor, randomizes
 *       all cosmetic slots).</li>
 *   <li>Calls {@code Database.getInstance().cacheAccount(botAccount)} — from
 *       now on every {@code Database.getAccount(npcUuid)} resolves to it.</li>
 *   <li>Calls {@code arena.connect(botAccount)} — LSW picks the team, builds
 *       the cage, teleports the NPC, broadcasts the join, sets up cosmetics,
 *       all of it. There is nothing for us to re-implement.</li>
 * </ol>
 *
 * <p><b>What destroy does:</b></p>
 * <ol>
 *   <li>Calls {@code arena.disconnect(botAccount)} so LSW can clean up the
 *       team / cage / scoreboard slot.</li>
 *   <li>Calls {@code Database.getInstance().uncacheAccount(npcUuid)} —
 *       BotAccount is gone, no SQL row was ever written.</li>
 *   <li>Despawns and destroys the Citizens NPC.</li>
 * </ol>
 */
public class BotManager {

    private final SkyWarsTrainer plugin;

    /**
     * Active bots keyed by their internal bot ID (NOT the NPC entity UUID).
     */
    private final Map<UUID, TrainerBot> activeBots;

    /**
     * Index of bots by display name for quick command lookups.
     */
    private final Map<String, TrainerBot> botsByName;

    /**
     * Counter for assigning stagger offsets to new bots.
     */
    private int staggerCounter;

    public BotManager(@Nonnull SkyWarsTrainer plugin) {
        this.plugin = plugin;
        this.activeBots = new ConcurrentHashMap<>();
        this.botsByName = new ConcurrentHashMap<>();
        this.staggerCounter = 0;
    }

    // ─── Spawning ───────────────────────────────────────────────

    /**
     * Spawns a single trainer bot into {@code arena}. Arena.connect handles
     * cage placement, team picking, teleport, broadcast, kit, cosmetics.
     *
     * @param arena         the arena to join (must be in a 1-per-team mode)
     * @param difficulty    difficulty preset
     * @param personalities optional personality names (if empty, randomized)
     * @param name          optional display name (if null, generated from skin)
     * @return the spawned TrainerBot, or null if spawning was refused or failed
     */
    @Nullable
    public TrainerBot spawnBot(@Nonnull Arena<?> arena,
                               @Nonnull Difficulty difficulty,
                               @Nonnull List<String> personalities,
                               @Nullable String name) {
        // ── 0. Mode soft-lock ──────────────────────────────────────
        if (!isSoloMode(arena)) {
            plugin.getLogger().warning("Refusing to spawn bot in arena '"
                    + arena.getServerName() + "': mode "
                    + (arena.getMode() != null ? arena.getMode().name() : "?")
                    + " has team size "
                    + (arena.getMode() != null ? arena.getMode().getTeamSize() : -1)
                    + ", bots only support 1-per-team modes for now.");
            return null;
        }

        // ── 1. Capacity ────────────────────────────────────────────
        int maxBots = plugin.getConfigManager().getMaxBotsPerGame();
        if (getBotsInArena(arena).size() >= maxBots) {
            plugin.getLogger().warning("Cannot spawn bot: maximum bot limit reached (" + maxBots + ").");
            return null;
        }

        // Refuse if there's no room left in the arena (no team can fit one more).
        if (countAvailableSeats(arena) <= 0) {
            plugin.getLogger().warning("Cannot spawn bot in arena '" + arena.getServerName()
                    + "': no available team seats.");
            return null;
        }

        // ── 2. Profile + skin ──────────────────────────────────────
        DifficultyProfile difficultyProfile = plugin.getDifficultyConfig().getProfile(difficulty);
        BotProfile profile = new BotProfile(difficulty, difficultyProfile);

        List<String> resolvedPersonalities = personalities;
        if (resolvedPersonalities == null || resolvedPersonalities.isEmpty()) {
            resolvedPersonalities = generateRandomPersonalities();
        }
        for (String personality : resolvedPersonalities) {
            profile.addPersonality(personality);
        }

        BotSkin skin;
        if (name != null && !name.isEmpty()) {
            skin = BotSkin.withName(plugin, name, difficulty, resolvedPersonalities);
        } else {
            skin = BotSkin.generateRandom(plugin, difficulty, resolvedPersonalities);
        }

        String displayName = skin.getDisplayName();
        if (botsByName.containsKey(displayName.toLowerCase())) {
            displayName = displayName + RandomUtil.nextInt(10, 99);
            skin = new BotSkin(skin.getSkinName(), displayName);
        }

        // ── 3. Bot wrapper + Citizens NPC ──────────────────────────
        TrainerBot bot = new TrainerBot(plugin, arena, profile, skin);
        bot.setStaggerOffset(staggerCounter);
        staggerCounter++;
        if (staggerCounter >= Integer.MAX_VALUE / 2) staggerCounter = 0;

        // Spawn the NPC at a throwaway cache location in the arena world.
        // Arena.connect will teleport it to the team cage in the same tick.
        Location cacheLocation = pickCacheLocation(arena);
        if (cacheLocation == null) {
            plugin.getLogger().warning("Cannot spawn bot in arena '" + arena.getServerName()
                    + "': arena world is null.");
            return null;
        }

        if (!bot.spawn(cacheLocation)) {
            plugin.getLogger().warning("Failed to spawn bot NPC: " + displayName);
            return null;
        }

        UUID npcUuid = bot.getNpcUuid();
        if (npcUuid == null) {
            plugin.getLogger().warning("NPC entity has no UUID after spawn — aborting: " + displayName);
            bot.destroy();
            return null;
        }

        BotAccount botAccount = new BotAccount(bot, npcUuid, displayName);
        bot.setBotAccount(botAccount);

        Database.getInstance().cacheAccount(botAccount);

        try {
            arena.connect(botAccount);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Arena.connect threw for bot " + displayName, e);
            Database.getInstance().uncacheAccount(npcUuid);
            bot.destroy();
            return null;
        }

        if (botAccount.getServer() == null || !botAccount.getServer().equals(arena)) {
            plugin.getLogger().warning("Arena.connect did not seat bot " + displayName
                    + " (probably the arena rejected the join). Cleaning up.");
            Database.getInstance().uncacheAccount(npcUuid);
            bot.destroy();
            return null;
        }
        org.twightlight.skywarstrainer.api.events.BotSpawnEvent spawnEvent =
                new org.twightlight.skywarstrainer.api.events.BotSpawnEvent(bot, bot.getLocation());
        Bukkit.getPluginManager().callEvent(spawnEvent);
        if (spawnEvent.isCancelled()) {
            // The event listener vetoed — undo everything we just did.
            try {
                arena.disconnect(botAccount);
            } catch (Exception ignored) {
            }
            Database.getInstance().uncacheAccount(npcUuid);
            bot.destroy();
            return null;
        }

        SkyWarsTrainer pluginRef = SkyWarsTrainer.getInstance();
        if (pluginRef != null && pluginRef.getApi() != null) {
            pluginRef.getApi().applyPendingRegistrations(bot);
        }

        activeBots.put(bot.getBotId(), bot);
        botsByName.put(displayName.toLowerCase(), bot);

        plugin.getLogger().info("Spawned bot: " + displayName
                + " [" + difficulty.name() + "] "
                + (resolvedPersonalities.isEmpty() ? "(no personalities)" : resolvedPersonalities)
                + " in arena " + arena.getServerName());

        return bot;
    }

    /**
     * Convenience: spawn a single bot with the configured default difficulty.
     */
    @Nullable
    public TrainerBot spawnBot(@Nonnull Arena<?> arena) {
        String defaultDiff = plugin.getConfigManager().getDefaultDifficulty();
        Difficulty difficulty = Difficulty.fromString(defaultDiff);
        if (difficulty == null) difficulty = Difficulty.MEDIUM;
        return spawnBot(arena, difficulty, Collections.emptyList(), null);
    }

    // ─── Removal ────────────────────────────────────────────────

    public boolean removeBot(@Nonnull TrainerBot bot) {
        TrainerBot removed = activeBots.remove(bot.getBotId());
        if (removed != null) {
            botsByName.remove(removed.getName().toLowerCase());
            removed.destroy();
            return true;
        }
        return false;
    }

    public boolean removeBot(@Nonnull String name) {
        TrainerBot bot = botsByName.get(name.toLowerCase());
        return bot != null && removeBot(bot);
    }

    public int removeAllBots() {
        int count = activeBots.size();
        for (TrainerBot bot : new ArrayList<>(activeBots.values())) {
            removeBot(bot);
        }
        return count;
    }

    public void maintenance() {
        cleanupDeadBots();
    }

    // ─── Tick distribution (kept for future non-Citizens mode) ──

    @Deprecated
    public void tickAll(long startNanos, long maxMsPerTick) {
        if (activeBots.isEmpty()) return;
        long budgetNanos = maxMsPerTick * 1_000_000L;
        cleanupDeadBots();
        for (TrainerBot bot : activeBots.values()) {
            if (System.nanoTime() - startNanos > budgetNanos) {
                DebugLogger.logSystem("Tick budget exceeded, deferring remaining bots.");
                break;
            }
            if (bot.isDestroyed() || !bot.isInitialized()) continue;
            try {
                bot.tick();
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Error ticking bot " + bot.getName(), e);
            }
        }
    }

    private void cleanupDeadBots() {
        List<TrainerBot> deadBots = new ArrayList<>();
        for (Map.Entry<UUID, TrainerBot> entry : activeBots.entrySet()) {
            TrainerBot bot = entry.getValue();
            if (bot.isDestroyed()) {
                deadBots.add(bot);
                continue;
            }
            if (bot.isInitialized() && !bot.isAlive()) {
                plugin.getLogger().info("Bot died: " + bot.getName());
                deadBots.add(bot);
            }
        }
        for (TrainerBot deadBot : deadBots) {
            activeBots.remove(deadBot.getBotId());
            botsByName.remove(deadBot.getName().toLowerCase());
            if (!deadBot.isDestroyed()) {
                deadBot.destroy();
            }
        }
    }

    // ─── Queries ────────────────────────────────────────────────

    @Nonnull
    public List<TrainerBot> getBotsInArena(@Nonnull Arena<?> arena) {
        return activeBots.values().stream()
                .filter(bot -> !bot.isDestroyed() && bot.getArena().equals(arena))
                .collect(Collectors.toList());
    }

    @Nonnull
    public List<TrainerBot> getBotsInArena(@Nonnull String arenaServerName) {
        return activeBots.values().stream()
                .filter(bot -> !bot.isDestroyed()
                        && bot.getArena() != null
                        && bot.getArena().getServerName() != null
                        && bot.getArena().getServerName().equals(arenaServerName))
                .collect(Collectors.toList());
    }

    @Nullable
    public TrainerBot getBotByName(@Nonnull String name) {
        return botsByName.get(name.toLowerCase());
    }

    @Nullable
    public TrainerBot getBotById(@Nonnull UUID id) {
        return activeBots.get(id);
    }

    @Nullable
    public TrainerBot getBotByEntityUUID(@Nonnull UUID entityUuid) {
        for (TrainerBot bot : activeBots.values()) {
            if (bot.getLivingEntity() != null
                    && bot.getLivingEntity().getUniqueId().equals(entityUuid)) {
                return bot;
            }
        }
        return null;
    }

    public boolean isBot(@Nonnull UUID entityUuid) {
        return getBotByEntityUUID(entityUuid) != null;
    }

    @Nonnull
    public List<TrainerBot> getAllBots() {
        return Collections.unmodifiableList(new ArrayList<>(activeBots.values()));
    }

    public int getActiveBotCount() {
        return activeBots.size();
    }

    @Nonnull
    public List<String> getBotNames() {
        return new ArrayList<>(botsByName.keySet());
    }

    // ─── Fill ───────────────────────────────────────────────────

    /**
     * Spawns up to {@code count} bots into the arena, stopping early if the
     * arena's free seats run out. There is no per-bot location parameter —
     * each bot is connected via {@code Arena.connect}, so each lands in its
     * own cage at its own team's spawn. (No more "11 bots in one block".)
     *
     * @return number of bots actually spawned
     */
    public int fillWithBots(@Nonnull Arena<?> arena, int count,
                            @Nonnull Difficulty difficulty,
                            @Nonnull List<String> personalities) {
        if (!isSoloMode(arena)) {
            plugin.getLogger().warning("Refusing fill in arena '" + arena.getServerName()
                    + "': only 1-per-team modes are supported for now.");
            return 0;
        }

        int spawned = 0;
        for (int i = 0; i < count; i++) {
            if (countAvailableSeats(arena) <= 0) {
                plugin.getLogger().info("Fill stopped: arena " + arena.getServerName() + " is full.");
                break;
            }
            TrainerBot bot = spawnBot(arena, difficulty, personalities, null);
            if (bot != null) spawned++;
        }
        plugin.getLogger().info("Filled arena " + arena.getServerName()
                + " with " + spawned + "/" + count + " bots.");
        return spawned;
    }

    @Nonnull
    public List<String> generateRandomPersonalities() {
        String[] allPersonalities = {
                "AGGRESSIVE", "PASSIVE", "RUSHER", "CAMPER", "STRATEGIC",
                "COLLECTOR", "BERSERKER", "SNIPER", "TRICKSTER", "CAUTIOUS",
                "CLUTCH_MASTER", "TEAMWORK"
        };

        int count = plugin.getConfigManager().getDefaultPersonalityCount();
        count = Math.max(1, Math.min(3, count));

        List<String> result = new ArrayList<>();
        int attempts = 0;
        while (result.size() < count && attempts < 50) {
            attempts++;
            String candidate = RandomUtil.randomElement(allPersonalities);
            if (result.contains(candidate)) continue;

            boolean conflicts = false;
            for (String existing : result) {
                if (PersonalityConflictTable.conflicts(
                        Personality.valueOf(existing), Personality.valueOf(candidate))) {
                    conflicts = true;
                    break;
                }
            }
            if (!conflicts) {
                result.add(candidate);
            }
        }

        if (result.isEmpty()) result.add("STRATEGIC");
        return result;
    }

    // ─── Helpers ────────────────────────────────────────────────

    /**
     * @return true if the arena is in a mode where each team holds 1 player
     * (i.e. SkyWarsMode.SOLO). Other modes are soft-locked until the
     * team-update lands.
     */
    public static boolean isSoloMode(@Nonnull Arena<?> arena) {
        SkyWarsMode mode = arena.getMode();
        return mode != null && mode.getTeamSize() == 1;
    }

    /**
     * Counts how many team seats are still joinable in the arena. We use the
     * existing {@code SkyWarsTeam.canJoin(int)} contract — same one
     * {@code Arena.getAvailableTeam(player)} uses — so we never disagree with
     * LSW on capacity.
     */
    public static int countAvailableSeats(@Nonnull Arena<?> arena) {
        int seats = 0;
        for (SkyWarsTeam team : arena.getTeams()) {
            if (team.canJoin(1)) seats++;
        }
        return seats;
    }

    /**
     * Returns a throwaway location to give to {@code NPC.spawn}. The NPC is
     * teleported to its team's cage location by {@code Arena.connect} during
     * the same tick, so this only matters for the few ticks of overlap.
     *
     * <p>We use the arena world's spawn — guaranteed to be loaded and inside
     * the arena world bounds.</p>
     */
    @Nullable
    private static Location pickCacheLocation(@Nonnull Arena<?> arena) {
        if (arena.getWorld() == null) return null;
        return arena.getWorld().getSpawnLocation();
    }
}
