package org.twightlight.skywarstrainer.bot;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.trait.Equipment;

import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.twightlight.skywarstrainer.SkyWarsTrainerPlugin;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.util.PacketUtil;
import org.twightlight.skywarstrainer.util.TickTimer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;
import java.util.logging.Level;

/**
 * The core wrapper around a Citizens NPC that represents a single trainer bot.
 *
 * <p>TrainerBot is the central object tying together the NPC entity, the bot's
 * profile (difficulty, personalities, stats), and later all AI subsystems
 * (decision engine, combat, movement, etc.). Each TrainerBot has exactly one
 * Citizens NPC and one BotProfile.</p>
 *
 * <p>The bot's AI tick loop runs through a custom Citizens {@link Trait}
 * ({@link SkyWarsTrainerTrait}), whose {@code run()} method is called every
 * server tick by Citizens. This trait delegates to TrainerBot's {@link #tick()}
 * method where the staggered tick budget is managed.</p>
 *
 * <p>Phase 1 provides: NPC creation/destruction, profile management, skin
 * application, basic tick loop skeleton, entity access, and pause/debug flags.
 * Later phases add combat, movement, decisions, etc. as subsystem fields.</p>
 */
public class TrainerBot {

    private final SkyWarsTrainerPlugin plugin;

    /** The unique identifier for this bot instance. */
    private final UUID botId;

    /** The Citizens NPC backing this bot. */
    private NPC npc;

    /** The bot's profile containing difficulty, personalities, and stats. */
    private final BotProfile profile;

    /** The skin applied to the NPC. */
    private final BotSkin skin;

    /** Whether this bot has been fully initialized and is ready to tick. */
    private boolean initialized;

    /** Whether this bot has been destroyed and should no longer be used. */
    private boolean destroyed;

    /**
     * Global tick counter local to this bot. Incremented each call to tick().
     * Used for staggered subsystem scheduling.
     */
    private long localTickCount;

    /**
     * Mistake injection timer. Periodically triggers an intentional mistake
     * to make the bot feel human at lower difficulties.
     */
    private TickTimer mistakeTimer;

    /**
     * The stagger offset for this bot within the tick loop. If staggering is
     * enabled, this bot only runs expensive logic on ticks where
     * (globalTick % staggerGroupSize == staggerOffset).
     */
    private int staggerOffset;

    /**
     * Creates a new TrainerBot. Does NOT spawn the NPC yet — call {@link #spawn(Location)}
     * to create and spawn the NPC in the world.
     *
     * @param plugin  the plugin instance
     * @param profile the bot's profile
     * @param skin    the skin to apply
     */
    public TrainerBot(@Nonnull SkyWarsTrainerPlugin plugin, @Nonnull BotProfile profile, @Nonnull BotSkin skin) {
        this.plugin = plugin;
        this.botId = UUID.randomUUID();
        this.profile = profile;
        this.skin = skin;
        this.initialized = false;
        this.destroyed = false;
        this.localTickCount = 0;
        this.staggerOffset = 0;

        // Initialize the mistake timer based on difficulty
        int mistakeIntervalTicks = profile.getDifficultyProfile().getMistakeIntervalTicks();
        this.mistakeTimer = new TickTimer(mistakeIntervalTicks, mistakeIntervalTicks / 4);
    }

    // ─── Lifecycle ──────────────────────────────────────────────

    /**
     * Spawns the bot's NPC at the given location.
     *
     * <p>This creates a Citizens NPC with the PLAYER entity type, applies the
     * skin, registers the custom trait for tick processing, and spawns the
     * entity in the world.</p>
     *
     * @param location the location to spawn at
     * @return true if spawning succeeded
     */
    public boolean spawn(@Nonnull Location location) {
        if (destroyed) {
            plugin.getLogger().warning("Cannot spawn a destroyed bot: " + skin.getDisplayName());
            return false;
        }

        try {
            // Create the NPC via Citizens API
            npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, skin.getDisplayName());

            // Apply skin: Citizens uses a SkinTrait internally
            // We set the skin by fetching the texture from the skin username
            if (plugin.getConfigManager().isSkinsEnabled()) {
                /*
                 * Citizens2 SkinTrait: calling npc.data().set(NPC.PLAYER_SKIN_UUID_METADATA, skinName)
                 * tells Citizens to fetch and apply the skin from that username.
                 * The actual texture data is cached by Citizens.
                 */
                npc.data().set(NPC.Metadata.PLAYER_SKIN_UUID, skin.getSkinName());
                npc.data().set("player-skin-name", skin.getSkinName());
            }

            // Configure NPC properties
            npc.setProtected(false); // Bots should be damageable
            npc.data().set(NPC.Metadata.DEFAULT_PROTECTED, false);

            // Register our custom trait for tick processing
            npc.addTrait(new SkyWarsTrainerTrait(this));

            // Spawn the NPC at the location
            npc.spawn(location);

            initialized = true;

            if (plugin.getConfigManager().isDebugMode()) {
                plugin.getLogger().info("[DEBUG] Bot spawned: " + skin.getDisplayName()
                        + " at " + formatLocation(location)
                        + " (" + profile.getDifficulty().name() + ")");
            }

            return true;

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to spawn bot: " + skin.getDisplayName(), e);
            // Clean up partial creation
            if (npc != null) {
                npc.destroy();
                npc = null;
            }
            return false;
        }
    }

    /**
     * Destroys this bot: despawns and deregisters the NPC, clears references.
     * After this call, the bot object should not be used.
     */
    public void destroy() {
        if (destroyed) return;
        destroyed = true;
        initialized = false;

        if (npc != null) {
            if (npc.isSpawned()) {
                npc.despawn();
            }
            npc.destroy();
            npc = null;
        }

        if (plugin.getConfigManager().isDebugMode()) {
            plugin.getLogger().info("[DEBUG] Bot destroyed: " + skin.getDisplayName());
        }
    }

    /**
     * Returns true if this bot is alive (spawned, not destroyed, and the NPC
     * entity exists and is alive).
     *
     * @return true if the bot is alive and active
     */
    public boolean isAlive() {
        if (destroyed || !initialized || npc == null || !npc.isSpawned()) {
            return false;
        }
        LivingEntity entity = getLivingEntity();
        return entity != null && !entity.isDead();
    }

    // ─── Tick Loop ──────────────────────────────────────────────

    /**
     * Main tick method called every server tick by the Citizens Trait.
     * This is the bot's heartbeat.
     *
     * <p>In Phase 1, this only increments the tick counter and handles
     * mistake injection. Later phases add subsystem ticking:
     * movement (every tick), combat (every 1-2 ticks), behavior tree
     * (every 2-4 ticks), decisions (every 10-20 ticks), etc.</p>
     */
    public void tick() {
        if (!initialized || destroyed || profile.isPaused()) {
            return;
        }

        if (!isAlive()) {
            return;
        }

        localTickCount++;

        // ── Mistake injection: periodically trigger a human-like error ──
        if (mistakeTimer.tick()) {
            injectMistake();
        }

        /*
         * Phase 2+ will add subsystem ticking here:
         * - movementController.tick()     (every tick)
         * - combatEngine.tick()           (every 1-2 ticks)
         * - behaviorTree.tick()           (every 2-4 ticks)
         * - decisionEngine.evaluate()     (every N ticks, or on interrupt)
         * - mapScanner.tick()             (every 40-60 ticks)
         * - inventoryManager.tick()       (every 100 ticks)
         */
    }

    /**
     * Injects a random intentional mistake to make the bot feel human.
     * The type of mistake and its impact scale with difficulty — lower
     * difficulty bots make more impactful mistakes.
     *
     * <p>Mistake types:
     * <ul>
     *   <li>Look the wrong way briefly</li>
     *   <li>Stop moving for a moment</li>
     *   <li>Switch to wrong hotbar slot</li>
     * </ul></p>
     */
    private void injectMistake() {
        if (!isAlive()) return;

        LivingEntity entity = getLivingEntity();
        if (entity == null) return;

        // For Phase 1, we just do a cosmetic head jitter as a placeholder
        // Full mistake injection is implemented when combat/movement systems exist
        double noiseYaw = (Math.random() - 0.5) * 30.0;
        double noisePitch = (Math.random() - 0.5) * 15.0;

        Location loc = entity.getLocation();
        loc.setYaw(loc.getYaw() + (float) noiseYaw);
        loc.setPitch(loc.getPitch() + (float) noisePitch);

        PacketUtil.sendHeadRotation(entity, loc.getYaw());

        if (profile.isDebugMode()) {
            plugin.getLogger().info("[DEBUG] " + getName() + " mistake injected (head jitter)");
        }
    }

    // ─── Accessors ──────────────────────────────────────────────

    /**
     * Returns the unique bot instance ID.
     *
     * @return the bot UUID
     */
    @Nonnull
    public UUID getBotId() {
        return botId;
    }

    /**
     * Returns the Citizens NPC.
     *
     * @return the NPC, or null if not yet spawned or destroyed
     */
    @Nullable
    public NPC getNpc() {
        return npc;
    }

    /**
     * Returns the NPC's entity as a LivingEntity.
     *
     * @return the living entity, or null if not spawned
     */
    @Nullable
    public LivingEntity getLivingEntity() {
        if (npc == null || !npc.isSpawned()) return null;
        if (npc.getEntity() instanceof LivingEntity) {
            return (LivingEntity) npc.getEntity();
        }
        return null;
    }

    /**
     * Returns the NPC's entity as a Player (since NPC type is PLAYER).
     *
     * @return the player entity, or null if not spawned
     */
    @Nullable
    public Player getPlayerEntity() {
        if (npc == null || !npc.isSpawned()) return null;
        if (npc.getEntity() instanceof Player) {
            return (Player) npc.getEntity();
        }
        return null;
    }

    /**
     * Returns the bot's current location.
     *
     * @return the location, or null if not spawned
     */
    @Nullable
    public Location getLocation() {
        LivingEntity entity = getLivingEntity();
        return entity != null ? entity.getLocation() : null;
    }

    /**
     * Returns the bot's display name.
     *
     * @return the name
     */
    @Nonnull
    public String getName() {
        return skin.getDisplayName();
    }

    /**
     * Returns the bot's profile.
     *
     * @return the profile
     */
    @Nonnull
    public BotProfile getProfile() {
        return profile;
    }

    /**
     * Returns the bot's skin information.
     *
     * @return the skin
     */
    @Nonnull
    public BotSkin getSkin() {
        return skin;
    }

    /**
     * Returns the difficulty profile (shorthand for profile.getDifficultyProfile()).
     *
     * @return the difficulty profile
     */
    @Nonnull
    public DifficultyProfile getDifficultyProfile() {
        return profile.getDifficultyProfile();
    }

    /** @return true if the bot has been initialized */
    public boolean isInitialized() {
        return initialized;
    }

    /** @return true if the bot has been destroyed */
    public boolean isDestroyed() {
        return destroyed;
    }

    /** @return the local tick count */
    public long getLocalTickCount() {
        return localTickCount;
    }

    /** @return the stagger offset */
    public int getStaggerOffset() {
        return staggerOffset;
    }

    /** @param offset the stagger offset for tick distribution */
    public void setStaggerOffset(int offset) {
        this.staggerOffset = offset;
    }

    /**
     * Helper to format a location for debug logging.
     */
    private static String formatLocation(Location loc) {
        return String.format("(%.1f, %.1f, %.1f in %s)",
                loc.getX(), loc.getY(), loc.getZ(),
                loc.getWorld() != null ? loc.getWorld().getName() : "null");
    }

    @Override
    public String toString() {
        return "TrainerBot{name='" + getName() + "', id=" + botId
                + ", alive=" + isAlive()
                + ", " + profile.getDifficulty().name() + "}";
    }

    // ═════════════════════════════════════════════════════════════
    //  INNER: SkyWarsTrainerTrait
    // ═════════════════════════════════════════════════════════════

    /**
     * Citizens Trait that hooks into the per-tick NPC update cycle.
     *
     * <p>Citizens calls {@link Trait#run()} every server tick for each spawned NPC.
     * This trait delegates to the owning TrainerBot's tick method, which is where
     * all AI logic is driven.</p>
     *
     * <p>Using a Trait rather than a separate Bukkit scheduled task ensures:
     * <ol>
     *   <li>Ticking is automatically tied to the NPC's lifecycle (no orphaned tasks).</li>
     *   <li>Citizens handles the scheduling — no extra task management needed.</li>
     *   <li>The trait has direct access to the NPC object.</li>
     * </ol></p>
     */
    public static class SkyWarsTrainerTrait extends Trait {

        /** The trait name registered with Citizens. */
        public static final String TRAIT_NAME = "skywarstrainer";

        /** Reference to the owning bot. May be null if trait was loaded from persistence. */
        private TrainerBot ownerBot;

        /**
         * Creates a new trait for the given bot.
         *
         * @param bot the owning TrainerBot
         */
        public SkyWarsTrainerTrait(@Nonnull TrainerBot bot) {
            super(TRAIT_NAME);
            this.ownerBot = bot;
        }

        /**
         * Default constructor required by Citizens for trait persistence/reload.
         * A trait created this way has no owner bot and will not tick.
         */
        public SkyWarsTrainerTrait() {
            super(TRAIT_NAME);
            this.ownerBot = null;
        }

        /**
         * Called every server tick by Citizens. Delegates to the bot's tick loop.
         */
        @Override
        public void run() {
            if (ownerBot != null && !ownerBot.isDestroyed()) {
                ownerBot.tick();
            }
        }

        /**
         * Called when the NPC is spawned. Can be used for initialization.
         */
        @Override
        public void onSpawn() {
            // Nothing extra needed in Phase 1
        }

        /**
         * Called when the NPC is despawned. Can be used for cleanup.
         */
        @Override
        public void onDespawn() {
            // Nothing extra needed in Phase 1
        }

        /**
         * Called when the NPC is removed/destroyed.
         */
        @Override
        public void onRemove() {
            ownerBot = null;
        }

        /**
         * Returns the owning bot, if any.
         *
         * @return the owner bot, or null
         */
        @Nullable
        public TrainerBot getOwnerBot() {
            return ownerBot;
        }
    }
}
