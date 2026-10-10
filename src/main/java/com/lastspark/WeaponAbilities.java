package com.lastspark;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The six weapon relics.
 *
 * Thunderclap Mace  - a smash strikes lightning and blasts everything around the target
 * Wind Warden Maul  - right-click to leap skyward; the smash sends enemies flying
 * Soulreaver        - lifesteal, and kills grant Absorption
 * Executioner Axe   - brutal against weakened foes; right-click for a spinning cleave
 * Skyfall Bow       - arrows call down a volley from above
 * Shadowfang Dagger - right-click to blink behind a target; backstabs hit for 2.5x
 */
public final class WeaponAbilities implements Listener {
    private final LastSparkPlugin plugin;
    private final ItemManager items;
    private final Cooldowns cd;
    private final NamespacedKey skyfallKey;
    private final Map<UUID, Long> backstabReady = new HashMap<>();
    private boolean busy = false; // stops our own area damage from re-triggering these handlers

    public WeaponAbilities(LastSparkPlugin plugin, ItemManager items, Cooldowns cd) {
        this.plugin = plugin;
        this.items = items;
        this.cd = cd;
        this.skyfallKey = new NamespacedKey(plugin, "skyfall_arrow");
    }

    // ------------------------------------------------------------ melee hits

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (busy) return;
        if (!(e.getDamager() instanceof Player p)) return;
        if (e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!(e.getEntity() instanceof LivingEntity victim)) return;

        ItemType t = items.typeOf(p.getInventory().getItemInMainHand());
        if (t == null) return;

        switch (t) {
            case THUNDERCLAP_MACE -> thunderSmash(p, victim, e);
            case WIND_WARDEN_MAUL -> windSmash(p, victim, e);
            case SOULREAVER -> soulreaver(p, victim, e);
            case EXECUTIONER_AXE -> execute(p, victim, e);
            case SHADOWFANG_DAGGER -> backstab(p, victim, e);
            default -> { }
        }
    }

    private void thunderSmash(Player p, LivingEntity victim, EntityDamageByEntityEvent e) {
        if (p.getFallDistance() < 1.5f) return;
        if (!cd.tryUse(p, "thunder_smash", 3)) return;
        Location l = victim.getLocation();
        victim.getWorld().strikeLightningEffect(l);
        e.setDamage(e.getDamage() + 6.0);
        shockwave(p, victim, 4.0, 8.0, 0.9);
        Fx.particle(l.clone().add(0, 1, 0), Particle.ELECTRIC_SPARK, 80, 1.5, 1.0, 1.5, 0.4);
        Fx.sound(l, "entity.lightning_bolt.thunder", 1f, 1.2f);
        Fx.bar(p, "&e&lTHUNDERCLAP!");
    }

    private void windSmash(Player p, LivingEntity victim, EntityDamageByEntityEvent e) {
        if (p.getFallDistance() < 1.5f) return;
        if (!cd.tryUse(p, "maul_smash", 3)) return;
        Location l = victim.getLocation();
        e.setDamage(e.getDamage() + 4.0);
        shockwave(p, victim, 4.5, 5.0, 1.7);
        Fx.particle(l.clone().add(0, 0.5, 0), Particle.EXPLOSION, 3, 1.0, 0.2, 1.0, 0.0);
        Fx.particle(l.clone().add(0, 0.5, 0), Particle.CLOUD, 60, 1.5, 0.3, 1.5, 0.2);
        Fx.sound(l, "entity.generic.explode", 1f, 1.4f);
        Fx.bar(p, "&b&lWINDBREAKER!");
    }

    private void soulreaver(Player p, LivingEntity victim, EntityDamageByEntityEvent e) {
        double dmg = e.getFinalDamage();
        Fx.heal(p, Math.min(6.0, dmg * 0.25));
        Fx.particle(p.getLocation().add(0, 1.2, 0), Particle.SCULK_SOUL, 6, 0.3, 0.5, 0.3, 0.02);
        if (victim.getHealth() - dmg <= 0) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 200, 1));
            Fx.bar(p, "&3Soul harvested");
            Fx.sound(p.getLocation(), "entity.wither.ambient", 0.5f, 1.8f);
        }
    }

    private void execute(Player p, LivingEntity victim, EntityDamageByEntityEvent e) {
        if (victim.getHealth() / Fx.maxHealth(victim) <= 0.35) {
            e.setDamage(e.getDamage() * 1.75);
            Fx.particle(victim.getLocation().add(0, 1, 0), Particle.CRIT, 30, 0.4, 0.6, 0.4, 0.3);
            Fx.sound(victim.getLocation(), "entity.player.attack.crit", 1f, 0.7f);
            Fx.bar(p, "&4&lEXECUTED");
        }
    }

    private void backstab(Player p, LivingEntity victim, EntityDamageByEntityEvent e) {
        Vector a = p.getLocation().getDirection().setY(0);
        Vector v = victim.getLocation().getDirection().setY(0);
        if (a.lengthSquared() < 0.0001 || v.lengthSquared() < 0.0001) return;
        boolean ready = backstabReady.getOrDefault(p.getUniqueId(), 0L) > System.currentTimeMillis();
        boolean behind = a.normalize().dot(v.normalize()) > 0.45;
        if (behind || ready) {
            backstabReady.remove(p.getUniqueId());
            e.setDamage(e.getDamage() * 2.5);
            Fx.particle(victim.getLocation().add(0, 1, 0), Particle.SMOKE, 30, 0.3, 0.5, 0.3, 0.05);
            Fx.sound(victim.getLocation(), "entity.player.attack.crit", 1f, 1.3f);
            Fx.bar(p, "&8&lBACKSTAB");
        }
    }

    /** Damages and launches everything near the target (not the attacker or the target itself). */
    private void shockwave(Player p, LivingEntity center, double radius, double dmg, double knockback) {
        busy = true;
        try {
            for (Entity en : center.getNearbyEntities(radius, radius, radius)) {
                if (en == p || en instanceof ArmorStand || !(en instanceof LivingEntity le)) continue;
                le.damage(dmg, p);
                Vector push = le.getLocation().toVector().subtract(center.getLocation().toVector());
                if (push.lengthSquared() > 0.01) {
                    push.normalize().multiply(knockback).setY(0.45);
                } else {
                    push = new Vector(0, 0.45, 0);
                }
                le.setVelocity(push);
            }
        } finally {
            busy = false;
        }
    }

    // ------------------------------------------------------------ right-click abilities

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !Fx.isRightClick(e)) return;
        ItemType t = items.typeOf(e.getItem());
        if (t != ItemType.WIND_WARDEN_MAUL && t != ItemType.EXECUTIONER_AXE && t != ItemType.SHADOWFANG_DAGGER) return;
        if (Fx.blocksAbility(e)) return;

        e.setUseInteractedBlock(Event.Result.DENY); // no log stripping, etc.
        Player p = e.getPlayer();
        switch (t) {
            case WIND_WARDEN_MAUL -> galeLeap(p);
            case EXECUTIONER_AXE -> cleave(p);
            case SHADOWFANG_DAGGER -> shadowStep(p);
            default -> { }
        }
    }

    private void galeLeap(Player p) {
        if (!cd.tryUse(p, "maul_leap", 12)) return;
        Vector dir = p.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() > 0.0001) dir.normalize().multiply(0.7);
        dir.setY(1.15);
        p.setVelocity(dir);
        cd.grantNoFall(p, 8); // land your smash without breaking your legs
        Fx.particle(p.getLocation(), Particle.CLOUD, 40, 0.4, 0.1, 0.4, 0.1);
        Fx.sound(p.getLocation(), "entity.breeze.jump", 1f, 1f);
        Fx.bar(p, "&bGale Leap");
    }

    private void cleave(Player p) {
        if (!cd.tryUse(p, "axe_cleave", 10)) return;
        busy = true;
        try {
            for (Entity en : p.getNearbyEntities(3.5, 2.0, 3.5)) {
                if (en instanceof ArmorStand || !(en instanceof LivingEntity le)) continue;
                le.damage(9.0, p);
                Vector push = le.getLocation().toVector().subtract(p.getLocation().toVector());
                if (push.lengthSquared() > 0.01) {
                    push.normalize().multiply(0.9).setY(0.35);
                    le.setVelocity(push);
                }
            }
        } finally {
            busy = false;
        }
        Location c = p.getLocation().add(0, 1, 0);
        Fx.particle(c, Particle.SWEEP_ATTACK, 12, 1.8, 0.3, 1.8, 0.0);
        Fx.sound(c, "entity.player.attack.sweep", 1f, 0.6f);
        Fx.bar(p, "&cCleave");
    }

    private void shadowStep(Player p) {
        Location eye = p.getEyeLocation();
        RayTraceResult r = p.getWorld().rayTraceEntities(eye, eye.getDirection(), 14, 0.6,
                en -> en != p && en instanceof LivingEntity && !(en instanceof ArmorStand));
        if (r == null || r.getHitEntity() == null) {
            Fx.bar(p, "&7No target in sight");
            return;
        }
        LivingEntity target = (LivingEntity) r.getHitEntity();
        Vector facing = target.getLocation().getDirection().setY(0);
        if (facing.lengthSquared() < 0.0001) facing = new Vector(0, 0, 1);
        facing.normalize();

        Location dest = target.getLocation().clone().subtract(facing.clone().multiply(1.6));
        if (!dest.getBlock().isPassable() || !dest.clone().add(0, 1, 0).getBlock().isPassable()) {
            Fx.bar(p, "&7No room to slip behind them");
            return;
        }
        if (!cd.tryUse(p, "dagger_step", 12)) return;

        Fx.particle(p.getLocation().add(0, 1, 0), Particle.SMOKE, 40, 0.3, 0.6, 0.3, 0.05);
        dest.setDirection(facing); // look the same way the target does
        p.teleport(dest);
        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 60, 1));
        backstabReady.put(p.getUniqueId(), System.currentTimeMillis() + 4000L);
        Fx.particle(dest.clone().add(0, 1, 0), Particle.SMOKE, 40, 0.3, 0.6, 0.3, 0.05);
        Fx.sound(dest, "entity.enderman.teleport", 0.8f, 1.5f);
        Fx.bar(p, "&8Shadow Step - your next strike is a backstab");
    }

    // ------------------------------------------------------------ Skyfall Bow

    @EventHandler(ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        if (items.typeOf(e.getBow()) != ItemType.SKYFALL_BOW) return;
        if (e.getForce() < 0.7f) return;
        if (e.getProjectile() instanceof AbstractArrow) {
            e.getProjectile().getPersistentDataContainer().set(skyfallKey, PersistentDataType.BYTE, (byte) 1);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent e) {
        Projectile proj = e.getEntity();
        if (!proj.getPersistentDataContainer().has(skyfallKey, PersistentDataType.BYTE)) return;
        proj.getPersistentDataContainer().remove(skyfallKey);
        if (!(proj.getShooter() instanceof Player p)) return;
        if (!cd.tryUse(p, "skyfall", 6)) return;

        Location target;
        if (e.getHitEntity() != null) {
            target = e.getHitEntity().getLocation();
        } else if (e.getHitBlock() != null) {
            target = e.getHitBlock().getLocation().add(0.5, 1.0, 0.5);
        } else {
            target = proj.getLocation();
        }
        rainArrows(p, target);
    }

    private void rainArrows(Player shooter, Location target) {
        World w = target.getWorld();
        if (w == null) return;

        int height = 0;
        while (height < 14 && target.clone().add(0, height + 1, 0).getBlock().isPassable()) height++;
        if (height < 3) height = 3;

        int count = 8;
        for (int i = 0; i < count; i++) {
            double angle = (Math.PI * 2 / count) * i;
            Location from = target.clone().add(Math.cos(angle) * 2.5, height, Math.sin(angle) * 2.5);
            Vector dir = new Vector(target.getX() - from.getX(), -height, target.getZ() - from.getZ());
            if (dir.lengthSquared() < 0.0001) continue;
            dir.normalize();
            Arrow arrow = w.spawnArrow(from, dir, 2.2f, 0.4f);
            arrow.setShooter(shooter);
            arrow.setDamage(4.0);
            arrow.setCritical(true);
            arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
            Bukkit.getScheduler().runTaskLater(plugin, arrow::remove, 100L);
        }
        Fx.particle(target.clone().add(0, 0.5, 0), Particle.END_ROD, 40, 1.5, 0.2, 1.5, 0.05);
        Fx.sound(target, "entity.firework_rocket.blast", 1f, 0.8f);
        Fx.bar(shooter, "&f&lSKYFALL");
    }
}
