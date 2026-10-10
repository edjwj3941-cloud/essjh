package com.lastspark;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.entity.Projectile;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * The four armor relics (always-on while worn) and the Quarry Pickaxe's held Haste.
 *
 * Eclipse Crown     - Night Vision, Water Breathing, dodges projectiles
 * Phoenix Plate     - Fire Resistance, once per 5 min a fatal hit is undone in a fire burst
 * Tempest Leggings  - Speed II, Jump Boost II, immune to lightning
 * Voidstep Boots    - no fall damage, the void throws you back to the last safe ground
 */
public final class PassiveAbilities implements Listener {
    private final LastSparkPlugin plugin;
    private final ItemManager items;
    private final Cooldowns cd;
    private final Random random = new Random();
    private final Map<UUID, Location> safeSpots = new HashMap<>();
    private final Set<UUID> crownWearers = new HashSet<>();

    public PassiveAbilities(LastSparkPlugin plugin, ItemManager items, Cooldowns cd) {
        this.plugin = plugin;
        this.items = items;
        this.cd = cd;
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 10L);
    }

    private void buff(Player p, PotionEffectType type, int ticks, int amplifier) {
        p.addPotionEffect(new PotionEffect(type, ticks, amplifier, true, false, true));
    }

    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerInventory inv = p.getInventory();

            if (items.typeOf(inv.getHelmet()) == ItemType.ECLIPSE_CROWN) {
                buff(p, PotionEffectType.NIGHT_VISION, 320, 0);
                buff(p, PotionEffectType.WATER_BREATHING, 60, 0);
                crownWearers.add(p.getUniqueId());
            } else if (crownWearers.remove(p.getUniqueId())) {
                p.removePotionEffect(PotionEffectType.NIGHT_VISION);
                p.removePotionEffect(PotionEffectType.WATER_BREATHING);
            }

            if (items.typeOf(inv.getChestplate()) == ItemType.PHOENIX_PLATE) {
                buff(p, PotionEffectType.FIRE_RESISTANCE, 60, 0);
            }

            if (items.typeOf(inv.getLeggings()) == ItemType.TEMPEST_LEGGINGS) {
                buff(p, PotionEffectType.SPEED, 60, 1);
                buff(p, PotionEffectType.JUMP_BOOST, 60, 1);
            }

            if (items.typeOf(inv.getBoots()) == ItemType.VOIDSTEP_BOOTS) {
                Location l = p.getLocation();
                if (!p.isFlying() && l.getY() > p.getWorld().getMinHeight()
                        && l.clone().subtract(0, 0.2, 0).getBlock().getType().isSolid()) {
                    safeSpots.put(p.getUniqueId(), l.clone());
                }
            }

            if (items.typeOf(inv.getItemInMainHand()) == ItemType.QUARRY_PICKAXE) {
                buff(p, PotionEffectType.HASTE, 60, 1);
            }
        }
    }

    // ------------------------------------------------------------ damage hooks

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        PlayerInventory inv = p.getInventory();
        EntityDamageEvent.DamageCause cause = e.getCause();

        boolean boots = items.typeOf(inv.getBoots()) == ItemType.VOIDSTEP_BOOTS;
        boolean leggings = items.typeOf(inv.getLeggings()) == ItemType.TEMPEST_LEGGINGS;
        boolean plate = items.typeOf(inv.getChestplate()) == ItemType.PHOENIX_PLATE;

        if (cause == EntityDamageEvent.DamageCause.FALL && (boots || cd.hasNoFall(p))) {
            e.setCancelled(true);
            return;
        }
        if (cause == EntityDamageEvent.DamageCause.LIGHTNING && leggings) {
            e.setCancelled(true);
            return;
        }
        if (cause == EntityDamageEvent.DamageCause.VOID && boots) {
            voidRescue(p, e);
            return;
        }
        if (plate && p.getHealth() - e.getFinalDamage() <= 0) {
            phoenixRebirth(p, e);
        }
    }

    /** Eclipse Crown: a chance to simply not be hit by arrows, tridents and other projectiles. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onProjectile(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (!(e.getDamager() instanceof Projectile)) return;
        if (items.typeOf(p.getInventory().getHelmet()) != ItemType.ECLIPSE_CROWN) return;
        double chance = plugin.getConfig().getDouble("abilities.crown-dodge-chance", 0.30);
        if (random.nextDouble() < chance) {
            e.setCancelled(true);
            Fx.particle(p.getLocation().add(0, 1.2, 0), Particle.WITCH, 25, 0.4, 0.6, 0.4, 0.05);
            Fx.sound(p.getLocation(), "entity.enderman.teleport", 0.6f, 1.6f);
            Fx.bar(p, "&5The eclipse swallows the shot");
        }
    }

    private void voidRescue(Player p, EntityDamageEvent e) {
        if (!cd.tryUse(p, "voidstep", 10)) return;
        e.setCancelled(true);
        Location safe = safeSpots.get(p.getUniqueId());
        if (safe == null || safe.getWorld() == null || !safe.getWorld().equals(p.getWorld())) {
            safe = p.getWorld().getSpawnLocation();
        }
        p.setFallDistance(0f);
        p.setVelocity(new Vector(0, 0, 0));
        p.teleport(safe);
        Fx.particle(safe.clone().add(0, 1, 0), Particle.REVERSE_PORTAL, 40, 0.4, 0.8, 0.4, 0.1);
        Fx.sound(safe, "entity.enderman.teleport", 1f, 0.8f);
        Fx.bar(p, "&8The Voidstep Boots pull you back from the abyss");
    }

    private void phoenixRebirth(Player p, EntityDamageEvent e) {
        if (!cd.tryUse(p, "phoenix", 300)) return;
        e.setCancelled(true);

        p.setHealth(Math.max(4.0, Fx.maxHealth(p) * 0.5));
        p.setFireTicks(0);
        p.setNoDamageTicks(40);
        p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 200, 2));
        p.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 200, 2));
        p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 100, 2));

        for (Entity en : p.getNearbyEntities(5, 5, 5)) {
            if (!(en instanceof LivingEntity le) || en instanceof ArmorStand) continue;
            le.setFireTicks(120);
            le.damage(6.0);
            Vector kb = le.getLocation().toVector().subtract(p.getLocation().toVector());
            if (kb.lengthSquared() > 0.01) {
                kb.normalize().multiply(1.2).setY(0.5);
                le.setVelocity(kb);
            }
        }

        Location l = p.getLocation().add(0, 1, 0);
        Fx.particle(l, Particle.FLAME, 120, 1.5, 1.2, 1.5, 0.15);
        Fx.particle(l, Particle.LAVA, 40, 1.0, 1.0, 1.0, 0.0);
        Fx.sound(l, "item.totem.use", 1f, 1f);
        Fx.sound(l, "entity.blaze.shoot", 1f, 0.7f);
        Fx.msg(p, "&6&lThe Phoenix Plate burns bright - you rise again!");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        safeSpots.remove(e.getPlayer().getUniqueId());
        crownWearers.remove(e.getPlayer().getUniqueId());
    }
}
