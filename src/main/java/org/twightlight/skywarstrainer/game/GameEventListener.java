package org.twightlight.skywarstrainer.game;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.twightlight.skywars.api.event.game.SkyWarsChestRefillEvent;
import org.twightlight.skywars.api.event.game.SkyWarsDoomEvent;
import org.twightlight.skywars.api.event.game.SkyWarsGameEndEvent;
import org.twightlight.skywars.api.event.game.SkyWarsGameStartEvent;
import org.twightlight.skywars.api.event.player.SkyWarsPlayerDeathEvent;
import org.twightlight.skywars.api.event.player.SkyWarsPlayerJoinEvent;
import org.twightlight.skywars.api.event.player.SkyWarsPlayerQuitEvent;
import org.twightlight.skywars.api.server.SkyWarsServer;
import org.twightlight.skywars.arena.Arena;
import org.twightlight.skywarstrainer.SkyWarsTrainerPlugin;
import org.twightlight.skywarstrainer.ai.decision.DecisionEngine;
import org.twightlight.skywarstrainer.bot.BotManager;
import org.twightlight.skywarstrainer.bot.TrainerBot;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.logging.Level;

/**
 * Listens to both LostSkyWars events and standard Bukkit events to keep
 * bots synchronized with game state.
 *
 * <p>This listener handles LostSkyWars game lifecycle events (start, end, death,
 * join, quit, refill, doom), combat events (damage dealt to/from bots), and
 * interrupt triggers for the bot decision engine.</p>
 *
 * <p>All LostSkyWars event classes extend SkyWarsEvent which extends Bukkit Event.
 * Each event has getServer() returning SkyWarsServer. We cast to Arena&lt;?&gt; to
 * access full arena functionality.</p>
 */
public class GameEventListener implements Listener {

    private final SkyWarsTrainerPlugin plugin;

    /**
     * Creates a new GameEventListener.
     *
     * @param plugin the owning plugin
     */
    public GameEventListener(@Nonnull SkyWarsTrainerPlugin plugin) {
        this.plugin = plugin;
    }

    // ═════════════════════════════════════════════════════════════
    //  LostSkyWars Game Events
    // ═════════════════════════════════════════════════════════════

    /**
     * Called when a SkyWars game starts (cages open, game timer begins).
     * SkyWarsGameStartEvent.getServer() returns SkyWarsServer.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onGameStart(SkyWarsGameStartEvent event) {
        SkyWarsServer server = event.getServer();
        if (!(server instanceof Arena)) return;
        Arena<?> arena = (Arena<?>) server;

        List<TrainerBot> bots = plugin.getBotManager().getBotsInArena(arena);
        if (bots.isEmpty()) return;

        plugin.getLogger().info("[GameHook] Game started in " + arena.getServerName()
                + " with " + bots.size() + " bot(s).");

        for (TrainerBot bot : bots) {
            try {
                bot.getProfile().addGamePlayed();

                // Trigger decision engine interrupt to start AI
                DecisionEngine de = bot.getDecisionEngine();
                if (de != null) {
                    de.triggerInterrupt();
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                        "Error notifying bot of game start: " + bot.getName(), e);
            }
        }
    }

    /**
     * Called when a SkyWars game ends.
     * SkyWarsGameEndEvent has getServer(), getWinnerTeam(), hasWinner().
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onGameEnd(SkyWarsGameEndEvent event) {
        SkyWarsServer server = event.getServer();
        if (!(server instanceof Arena)) return;
        Arena<?> arena = (Arena<?>) server;

        List<TrainerBot> bots = plugin.getBotManager().getBotsInArena(arena);
        if (bots.isEmpty()) return;

        plugin.getLogger().info("[GameHook] Game ended in " + arena.getServerName()
                + ", cleaning up " + bots.size() + " bot(s).");

        for (TrainerBot bot : bots) {
            try {
                // Check if this bot won
                if (event.hasWinner() && event.getWinnerTeam() != null) {
                    Player botPlayer = bot.getPlayerEntity();
                    if (botPlayer != null && event.getWinnerTeam().hasMember(botPlayer)) {
                        bot.getProfile().addGameWon();
                    }
                }
                plugin.getBotManager().removeBot(bot);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                        "Error cleaning up bot on game end: " + bot.getName(), e);
            }
        }
    }

    /**
     * Called when a player dies in LostSkyWars.
     * SkyWarsPlayerDeathEvent has getServer(), getPlayer(), getKiller().
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(SkyWarsPlayerDeathEvent event) {
        SkyWarsServer server = event.getServer();
        if (!(server instanceof Arena)) return;
        Arena<?> arena = (Arena<?>) server;

        Player dead = event.getPlayer();
        Player killer = event.getKiller();
        BotManager botManager = plugin.getBotManager();

        // Check if the dead entity is a bot
        if (dead != null) {
            TrainerBot deadBot = botManager.getBotByEntityUuid(dead.getUniqueId());
            if (deadBot != null) {
                deadBot.getProfile().addDeath();
                if (plugin.getConfigManager().isDebugMode()) {
                    plugin.getLogger().info("[GameHook] Bot died: " + deadBot.getName()
                            + (killer != null ? " killed by " + killer.getName() : ""));
                }
            }
        }

        // Check if the killer is a bot
        if (killer != null) {
            TrainerBot killerBot = botManager.getBotByEntityUuid(killer.getUniqueId());
            if (killerBot != null) {
                killerBot.getProfile().addKill();
                Bukkit.getPluginManager().callEvent(
                        new org.twightlight.skywarstrainer.api.events.BotKillPlayerEvent(killerBot, dead));
            }
        }

        // Trigger interrupt for all bots in the arena (player count changed)
        for (TrainerBot bot : botManager.getBotsInArena(arena)) {
            DecisionEngine de = bot.getDecisionEngine();
            if (de != null) {
                de.triggerInterrupt();
            }
        }
    }

    /**
     * Called when a player joins a SkyWars arena.
     * SkyWarsPlayerJoinEvent has getServer(), getPlayer().
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(SkyWarsPlayerJoinEvent event) {
        SkyWarsServer server = event.getServer();
        if (!(server instanceof Arena)) return;
        Arena<?> arena = (Arena<?>) server;

        for (TrainerBot bot : plugin.getBotManager().getBotsInArena(arena)) {
            DecisionEngine de = bot.getDecisionEngine();
            if (de != null) {
                de.triggerInterrupt();
            }
        }
    }

    /**
     * Called when a player quits a SkyWars arena.
     * SkyWarsPlayerQuitEvent has getServer(), getPlayer().
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(SkyWarsPlayerQuitEvent event) {
        SkyWarsServer server = event.getServer();
        if (!(server instanceof Arena)) return;
        Arena<?> arena = (Arena<?>) server;

        for (TrainerBot bot : plugin.getBotManager().getBotsInArena(arena)) {
            DecisionEngine de = bot.getDecisionEngine();
            if (de != null) {
                de.triggerInterrupt();
            }
        }
    }

    /**
     * Called when chests are refilled.
     * SkyWarsChestRefillEvent has getServer().
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChestRefill(SkyWarsChestRefillEvent event) {
        SkyWarsServer server = event.getServer();
        if (!(server instanceof Arena)) return;
        Arena<?> arena = (Arena<?>) server;

        for (TrainerBot bot : plugin.getBotManager().getBotsInArena(arena)) {
            // Reset chest memory — chests have new loot
            if (bot.getChestLocator() != null) {
                bot.getChestLocator().markAllUnlooted();
            }
            DecisionEngine de = bot.getDecisionEngine();
            if (de != null) {
                de.triggerInterrupt();
            }
        }
    }

    /**
     * Called when doom event activates (deathmatch phase).
     * SkyWarsDoomEvent has getServer().
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDoom(SkyWarsDoomEvent event) {
        SkyWarsServer server = event.getServer();
        if (!(server instanceof Arena)) return;
        Arena<?> arena = (Arena<?>) server;

        for (TrainerBot bot : plugin.getBotManager().getBotsInArena(arena)) {
            DecisionEngine de = bot.getDecisionEngine();
            if (de != null) {
                de.triggerInterrupt();
            }
        }
    }

    // ═════════════════════════════════════════════════════════════
    //  Standard Bukkit Combat Events
    // ═════════════════════════════════════════════════════════════

    /**
     * Handles damage dealt to or by bots. Triggers combat interrupts.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        Entity victim = event.getEntity();
        BotManager botManager = plugin.getBotManager();

        // Resolve actual damager (for projectiles, get the shooter)
        Entity actualDamager = damager;
        if (damager instanceof Projectile) {
            Projectile projectile = (Projectile) damager;
            if (projectile.getShooter() instanceof Entity) {
                actualDamager = (Entity) projectile.getShooter();
            }
        }

        // Case 1: Bot was hit
        if (victim instanceof LivingEntity) {
            TrainerBot victimBot = botManager.getBotByEntityUuid(victim.getUniqueId());
            if (victimBot != null) {
                if (victimBot.getCombatEngine() != null) {
                    LivingEntity attackerEntity = (actualDamager instanceof LivingEntity)
                            ? (LivingEntity) actualDamager : null;
                    victimBot.getCombatEngine().onBotHit(attackerEntity, event.getDamage());
                }
                DecisionEngine de = victimBot.getDecisionEngine();
                if (de != null) {
                    de.triggerInterrupt();
                }
            }
        }

        // Case 2: Bot dealt damage
        if (actualDamager instanceof LivingEntity) {
            TrainerBot damagerBot = botManager.getBotByEntityUuid(actualDamager.getUniqueId());
            if (damagerBot != null && damagerBot.getCombatEngine() != null) {
                damagerBot.getCombatEngine().getComboTracker().onHitLanded();
            }
        }
    }

    /**
     * Handles any damage taken by bots (environmental: fire, lava, void, fall).
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof LivingEntity)) return;

        TrainerBot bot = plugin.getBotManager().getBotByEntityUuid(
                event.getEntity().getUniqueId());
        if (bot == null) return;

        DecisionEngine de = bot.getDecisionEngine();
        if (de != null) {
            de.triggerInterrupt();
        }
    }

    /**
     * Handles projectile hit events for bot-fired projectiles.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        Projectile projectile = event.getEntity();
        if (!(projectile.getShooter() instanceof Entity)) return;

        Entity shooter = (Entity) projectile.getShooter();
        TrainerBot bot = plugin.getBotManager().getBotByEntityUuid(shooter.getUniqueId());
        if (bot == null) return;

        if (bot.getCombatEngine() != null) {
            bot.getCombatEngine().getProjectileHandler().onProjectileHit(projectile);
        }
    }
}