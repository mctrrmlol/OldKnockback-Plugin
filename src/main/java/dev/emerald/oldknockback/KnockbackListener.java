package dev.emerald.oldknockback;

import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class KnockbackListener implements Listener {

    private final OldKnockback plugin;

    // Tracks the last explosion location and type so we can identify
    // what caused damage in EntityDamageEvent (crystal vs anchor vs tnt)
    private final Map<UUID, ExplosionInfo> pendingExplosions = new HashMap<>();

    public KnockbackListener(OldKnockback plugin) {
        this.plugin = plugin;
    }

    // ─────────────────────────────────────────────────────────────
    //  MELEE + PROJECTILE + GENERIC EXPLOSION (EntityDamageByEntity)
    // ─────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        FileConfiguration config = plugin.getConfig();
        Entity damager = event.getDamager();
        Entity victim = event.getEntity();
        if (!(victim instanceof LivingEntity livingVictim)) return;

        // Melee
        if (damager instanceof Player attacker
                && event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK) {
            applyOldMeleeKnockback(attacker, livingVictim, config);
            return;
        }

        // Projectile
        if (damager instanceof Projectile projectile
                && config.getBoolean("projectile.enabled", true)
                && projectile.getShooter() instanceof Player) {
            applyOldProjectileKnockback(projectile, livingVictim, config);
            return;
        }

        // End Crystal explosion (damager is the EnderCrystal entity)
        if (damager instanceof EnderCrystal crystal
                && config.getBoolean("crystal.enabled", true)) {
            applyExplosionKnockback(
                crystal.getLocation(),
                livingVictim,
                config.getDouble("crystal.horizontal", 1.2),
                config.getDouble("crystal.vertical", 0.5),
                config.getDouble("crystal.max-horizontal", 2.0),
                config.getDouble("crystal.range", 12.0)
            );
            return;
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  ENTITY DAMAGE — catches Respawn Anchor & TNT explosions
    //  (these come as EntityDamageEvent, not EntityDamageByEntity)
    // ─────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof LivingEntity victim)) return;
        FileConfiguration config = plugin.getConfig();

        // Respawn Anchor fires BLOCK_EXPLOSION
        if (event.getCause() == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION
                && config.getBoolean("anchor.enabled", true)) {

            // Find the nearest pending anchor explosion for this victim
            ExplosionInfo info = pendingExplosions.get(victim.getUniqueId());
            if (info != null && info.type == ExplosionType.ANCHOR) {
                pendingExplosions.remove(victim.getUniqueId());
                applyExplosionKnockback(
                    info.location,
                    victim,
                    config.getDouble("anchor.horizontal", 1.0),
                    config.getDouble("anchor.vertical", 0.45),
                    config.getDouble("anchor.max-horizontal", 1.8),
                    config.getDouble("anchor.range", 5.0)
                );
                return;
            }

            // Fallback: no tracked anchor, just normalize vanilla explosion
            normalizeExplosionVelocity(victim,
                config.getDouble("anchor.max-horizontal", 1.8),
                config.getDouble("anchor.vertical", 0.45));
            return;
        }

        // TNT / general ENTITY_EXPLOSION
        if (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                && config.getBoolean("explosion.enabled", true)) {
            normalizeExplosionVelocity(victim,
                config.getDouble("explosion.max-horizontal", 1.2),
                config.getDouble("explosion.vertical", 0.5));
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  ENTITY EXPLODE — fires when block/entity actually explodes.
    //  We track anchor explosions here before damage is applied.
    // ─────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        FileConfiguration config = plugin.getConfig();

        // Respawn Anchor
        if (event.getEntity() != null && event.getEntity().getType() == EntityType.TNT) {
            // Regular TNT — handled above via ENTITY_EXPLOSION cause
            return;
        }

        // Tag all nearby players so the damage handler knows the source location
        if (config.getBoolean("anchor.enabled", true)) {
            double range = config.getDouble("anchor.range", 5.0);
            Location explosionLoc = event.getLocation();
            for (Entity nearby : event.getEntity().getNearbyEntities(range, range, range)) {
                if (nearby instanceof LivingEntity) {
                    pendingExplosions.put(nearby.getUniqueId(),
                        new ExplosionInfo(explosionLoc, ExplosionType.ANCHOR));
                }
            }
            // Clean up pending after 2 ticks (damage fires immediately after)
            plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> pendingExplosions.clear(), 2L);
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  CORE KNOCKBACK METHODS
    // ─────────────────────────────────────────────────────────────

    /**
     * Classic pre-1.9 melee knockback formula.
     */
    private void applyOldMeleeKnockback(Player attacker, LivingEntity victim, FileConfiguration config) {
        double horizontal = config.getDouble("knockback.horizontal", 0.4);
        double vertical = config.getDouble("knockback.vertical", 0.4);
        double sprintBonus = config.getDouble("knockback.sprint-bonus", 0.9);
        double verticalLimit = config.getDouble("knockback.vertical-limit", 0.1);
        double friction = config.getDouble("knockback.friction", 2.0);

        double dx = victim.getLocation().getX() - attacker.getLocation().getX();
        double dz = victim.getLocation().getZ() - attacker.getLocation().getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);

        if (dist == 0) {
            dx = -Math.sin(Math.toRadians(attacker.getLocation().getYaw()));
            dz = Math.cos(Math.toRadians(attacker.getLocation().getYaw()));
            dist = Math.sqrt(dx * dx + dz * dz);
        }

        dx /= dist;
        dz /= dist;

        Vector vel = victim.getVelocity();
        vel.setX(vel.getX() / friction + dx * horizontal);
        vel.setZ(vel.getZ() / friction + dz * horizontal);
        vel.setY(victim.isOnGround() ? vertical : vel.getY() + verticalLimit);

        if (attacker.isSprinting()) {
            vel.setX(vel.getX() + dx * (sprintBonus - horizontal));
            vel.setZ(vel.getZ() + dz * (sprintBonus - horizontal));
        }

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> victim.setVelocity(vel), 1L);
    }

    /**
     * Old-style projectile knockback — pushed in travel direction.
     */
    private void applyOldProjectileKnockback(Projectile projectile, LivingEntity victim, FileConfiguration config) {
        double horizontal = config.getDouble("projectile.horizontal", 0.4);
        double vertical = config.getDouble("projectile.vertical", 0.4);

        Vector dir = projectile.getVelocity().normalize();
        Vector vel = victim.getVelocity();
        vel.setX(vel.getX() / 2.0 + dir.getX() * horizontal);
        vel.setZ(vel.getZ() / 2.0 + dir.getZ() * horizontal);
        vel.setY(vertical);

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> victim.setVelocity(vel), 1L);
    }

    /**
     * Crystal/Anchor explosion knockback — calculated from explosion origin.
     * Distance-scaled: closer = more KB, like old behavior.
     */
    private void applyExplosionKnockback(Location origin, LivingEntity victim,
                                          double baseHorizontal, double baseVertical,
                                          double maxHorizontal, double range) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            double dx = victim.getLocation().getX() - origin.getX();
            double dz = victim.getLocation().getZ() - origin.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);

            // Closer to explosion = more knockback (old crystal pvp feel)
            double scale = dist == 0 ? 1.0 : Math.max(0.1, 1.0 - (dist / range));

            double kbH = Math.min(baseHorizontal * scale, maxHorizontal);
            double kbV = baseVertical * scale;

            Vector vel = new Vector();
            if (dist > 0) {
                vel.setX((dx / dist) * kbH);
                vel.setZ((dz / dist) * kbH);
            }

            // Only apply vertical if player is on the ground OR the force is strong enough.
            // This prevents the tiny upward pop when at the same Y level as the crystal.
            Vector current = victim.getVelocity();
            if (victim instanceof Player p && p.isOnGround()) {
                vel.setY(kbV);
            } else if (kbV > 0.15) {
                // Strong enough blast — override vertical
                vel.setY(Math.max(current.getY(), kbV));
            } else {
                // Weak/same-level hit — keep current Y so no jump occurs
                vel.setY(current.getY());
            }

            victim.setVelocity(vel);
        }, 1L);
    }

    /**
     * Normalizes vanilla explosion velocity to old-style capped values.
     * Used as a fallback for TNT and untracked explosions.
     */
    private void normalizeExplosionVelocity(LivingEntity victim, double maxHorizontal, double maxVertical) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Vector vel = victim.getVelocity();
            double mag = Math.sqrt(vel.getX() * vel.getX() + vel.getZ() * vel.getZ());
            if (mag > 0) {
                double scale = Math.min(mag, maxHorizontal);
                vel.setX((vel.getX() / mag) * scale);
                vel.setZ((vel.getZ() / mag) * scale);
            }
            vel.setY(Math.min(vel.getY(), maxVertical));
            victim.setVelocity(vel);
        }, 1L);
    }

    // ─────────────────────────────────────────────────────────────
    //  FISHING ROD
    // ─────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onFishingRodPull(PlayerFishEvent event) {
        if (!plugin.getConfig().getBoolean("fishing-rod.enabled", true)) return;
        if (event.getState() != PlayerFishEvent.State.CAUGHT_ENTITY) return;
        if (!(event.getCaught() instanceof LivingEntity target)) return;

        double pull = plugin.getConfig().getDouble("fishing-rod.pull", 0.4);
        Player fisher = event.getPlayer();

        Vector direction = fisher.getLocation().toVector()
                .subtract(target.getLocation().toVector())
                .normalize()
                .multiply(pull);
        direction.setY(direction.getY() + 0.2);
        target.setVelocity(direction);
    }

    // ─────────────────────────────────────────────────────────────
    //  HELPERS
    // ─────────────────────────────────────────────────────────────

    private enum ExplosionType { CRYSTAL, ANCHOR, TNT }

    private static class ExplosionInfo {
        final Location location;
        final ExplosionType type;
        ExplosionInfo(Location location, ExplosionType type) {
            this.location = location;
            this.type = type;
        }
    }
}
