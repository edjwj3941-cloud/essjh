package com.lastspark;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * The five utility relics.
 *
 * The Last Spark   - right-click consumes someone nearby and forges you one of the other 14 relics
 *                    (random, or sneak + right-click to pick which one)
 * Waystone Shard   - sneak + right-click binds a waypoint to the shard, right-click to teleport there
 * Tether Hook      - a fishing rod that grapples you to wherever the hook lands
 * Lifebloom Charm  - heals and cleanses you and nearby allies
 * Quarry Pickaxe   - mines a 3x3 area (sneak to mine a single block)
 */
public final class UtilityAbilities implements Listener {
    private static final List<ItemType> SPARK_POOL = new ArrayList<>();

    static {
        for (ItemType t : ItemType.values()) {
            if (t != ItemType.LAST_SPARK) SPARK_POOL.add(t);
        }
    }

    private final LastSparkPlugin plugin;
    private final ItemManager items;
    private final Cooldowns cd;
    private final Random random = new Random();
    private final NamespacedKey waypointKey;

    private final Map<UUID, Integer> selection = new HashMap<>();   // -1 = random
    private final Map<UUID, String> consumed = new HashMap<>();      // victim -> killer name (for the death message)
    private final Set<UUID> channeling = new HashSet<>();
    private boolean breaking = false;

    public UtilityAbilities(LastSparkPlugin plugin, ItemManager items, Cooldowns cd) {
        this.plugin = plugin;
        this.items = items;
        this.cd = cd;
        this.waypointKey = new NamespacedKey(plugin, "waypoint");
    }

    // ------------------------------------------------------------ right-click dispatch

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !Fx.isRightClick(e)) return;
        ItemType t = items.typeOf(e.getItem());
        if (t != ItemType.LAST_SPARK && t != ItemType.WAYSTONE_SHARD && t != ItemType.LIFEBLOOM_CHARM) return;
        if (Fx.blocksAbility(e)) return;

        e.setUseInteractedBlock(Event.Result.DENY);
        e.setUseItemInHand(Event.Result.DENY);
        Player p = e.getPlayer();
        ItemStack hand = e.getItem();

        switch (t) {
            case LAST_SPARK -> {
                if (p.isSneaking()) cycleSelection(p);
                else useSpark(p);
            }
            case WAYSTONE_SHARD -> {
                if (p.isSneaking()) setWaypoint(p, hand);
                else startTeleport(p, hand);
            }
            case LIFEBLOOM_CHARM -> lifebloom(p);
            default -> { }
        }
    }

    // ============================================================ THE LAST SPARK

    private void cycleSelection(Player p) {
        int cur = selection.getOrDefault(p.getUniqueId(), -1) + 1;
        if (cur >= SPARK_POOL.size()) cur = -1;
        selection.put(p.getUniqueId(), cur);

        if (cur == -1) {
            Fx.bar(p, "&5The Last Spark will forge: &dA random relic");
        } else {
            ItemType t = SPARK_POOL.get(cur);
            Component line = Fx.color("&5The Last Spark will forge: ").append(items.nameOf(t));
            if (items.exists(t)) line = line.append(Fx.color(" &c(already exists)"));
            p.sendActionBar(line);
        }
        Fx.sound(p.getLocation(), "block.note_block.chime", 0.6f, 1.4f);
    }

    private void useSpark(Player p) {
        if (!cd.ready(p, "spark")) return;

        LivingEntity victim = findVictim(p);
        if (victim == null) {
            Fx.bar(p, "&5No one is close enough to consume");
            return;
        }

        List<ItemType> available = new ArrayList<>();
        for (ItemType t : SPARK_POOL) {
            if (!items.exists(t)) available.add(t);
        }
        if (available.isEmpty()) {
            Fx.bar(p, "&cEvery relic already exists in the world");
            return;
        }

        int sel = selection.getOrDefault(p.getUniqueId(), -1);
        ItemType chosen;
        if (sel >= 0 && sel < SPARK_POOL.size()) {
            chosen = SPARK_POOL.get(sel);
            if (items.exists(chosen)) {
                Fx.bar(p, "&cThat relic already exists. Pick another, or go back to random");
                return;
            }
        } else {
            chosen = available.get(random.nextInt(available.size()));
        }

        ItemStack reward = items.tryCreate(chosen);
        if (reward == null) return; // lost a race with another creation; nothing is consumed

        cd.start(p, "spark", cd.seconds("spark", 30));
        consume(p, victim);

        Map<Integer, ItemStack> leftover = p.getInventory().addItem(reward);
        for (ItemStack extra : leftover.values()) {
            p.getWorld().dropItem(p.getLocation(), extra);
        }

        if (plugin.getConfig().getBoolean("spark.broadcast", true)) {
            Component msg = Fx.color("&5&lThe Last Spark &8| &d" + p.getName() + " &7consumed &d"
                    + victim.getName() + " &7and claimed ").append(items.nameOf(chosen)).append(Fx.color("&7!"));
            Bukkit.broadcast(msg);
        } else {
            p.sendMessage(Fx.color("&5The Spark forges ").append(items.nameOf(chosen)).append(Fx.color(" &5for you.")));
        }
    }

    private LivingEntity findVictim(Player p) {
        FileConfiguration cfg = plugin.getConfig();
        double r = cfg.getDouble("spark.radius", 6.0);
        boolean allowPlayers = cfg.getBoolean("spark.target-players", true);
        boolean allowMobs = cfg.getBoolean("spark.target-mobs", true);
        boolean preferPlayers = cfg.getBoolean("spark.prefer-players", true);

        LivingEntity bestPlayer = null;
        LivingEntity bestMob = null;
        double bestPlayerDist = Double.MAX_VALUE;
        double bestMobDist = Double.MAX_VALUE;

        for (Entity en : p.getNearbyEntities(r, r, r)) {
            if (!(en instanceof LivingEntity le) || en instanceof ArmorStand) continue;
            if (le.isDead() || !le.isValid()) continue;
            double d = le.getLocation().distanceSquared(p.getLocation());
            if (d > r * r) continue;

            if (le instanceof Player other) {
                if (!allowPlayers) continue;
                GameMode gm = other.getGameMode();
                if (gm == GameMode.SPECTATOR || gm == GameMode.CREATIVE) continue;
                if (d < bestPlayerDist) {
                    bestPlayerDist = d;
                    bestPlayer = le;
                }
            } else {
                if (!allowMobs) continue;
                if (le instanceof Tameable pet && pet.isTamed() && p.equals(pet.getOwner())) continue;
                if (d < bestMobDist) {
                    bestMobDist = d;
                    bestMob = le;
                }
            }
        }

        if (preferPlayers) return bestPlayer != null ? bestPlayer : bestMob;
        if (bestPlayer == null) return bestMob;
        if (bestMob == null) return bestPlayer;
        return bestPlayerDist <= bestMobDist ? bestPlayer : bestMob;
    }

    private void consume(Player killer, LivingEntity victim) {
        Location from = victim.getLocation().add(0, 1, 0);
        Location to = killer.getLocation().add(0, 1, 0);
        World w = from.getWorld();

        if (w != null) {
            w.strikeLightningEffect(victim.getLocation());
            Vector line = to.toVector().subtract(from.toVector());
            double len = line.length();
            if (len > 0.01) {
                Vector step = line.clone().normalize().multiply(0.5);
                Location pt = from.clone();
                for (double d = 0; d < len; d += 0.5) {
                    w.spawnParticle(Particle.SOUL_FIRE_FLAME, pt, 2, 0.05, 0.05, 0.05, 0.01);
                    pt.add(step);
                }
            }
            w.spawnParticle(Particle.SCULK_SOUL, from, 40, 0.5, 0.8, 0.5, 0.05);
            w.spawnParticle(Particle.END_ROD, to, 30, 0.5, 0.8, 0.5, 0.1);
        }
        Fx.sound(from, "entity.wither.spawn", 0.7f, 1.5f);
        Fx.sound(to, "block.beacon.activate", 1f, 1.2f);

        if (victim instanceof Player vp) consumed.put(vp.getUniqueId(), killer.getName());
        victim.setHealth(0.0);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        String killer = consumed.remove(e.getEntity().getUniqueId());
        if (killer != null) {
            e.deathMessage(Component.text(
                    e.getEntity().getName() + " was consumed by " + killer + "'s Last Spark",
                    NamedTextColor.DARK_PURPLE));
        }
    }

    // ============================================================ WAYSTONE SHARD

    private void setWaypoint(Player p, ItemStack hand) {
        Location l = p.getLocation();
        if (l.getWorld() == null) return;
        String enc = String.format(Locale.ROOT, "%s;%.2f;%.2f;%.2f;%.1f;%.1f",
                l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch());
        ItemStack copy = hand.clone();
        copy.editMeta(m -> m.getPersistentDataContainer().set(waypointKey, PersistentDataType.STRING, enc));
        p.getInventory().setItemInMainHand(copy);
        Fx.particle(l.clone().add(0, 1, 0), Particle.ENCHANT, 50, 0.5, 0.8, 0.5, 0.5);
        Fx.sound(l, "block.amethyst_block.resonate", 1f, 1.2f);
        Fx.bar(p, "&bWaypoint bound to the shard");
    }

    private Location readWaypoint(ItemStack hand) {
        if (hand == null || !hand.hasItemMeta()) return null;
        String enc = hand.getItemMeta().getPersistentDataContainer().get(waypointKey, PersistentDataType.STRING);
        if (enc == null) return null;
        String[] parts = enc.split(";");
        if (parts.length != 6) return null;
        World w = Bukkit.getWorld(parts[0]);
        if (w == null) return null;
        try {
            return new Location(w, Double.parseDouble(parts[1]), Double.parseDouble(parts[2]),
                    Double.parseDouble(parts[3]), Float.parseFloat(parts[4]), Float.parseFloat(parts[5]));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private void startTeleport(Player p, ItemStack hand) {
        Location dest = readWaypoint(hand);
        if (dest == null) {
            Fx.bar(p, "&cNo waypoint set. &7Sneak + right-click to bind one");
            return;
        }
        if (!cd.ready(p, "waystone")) return;
        if (!channeling.add(p.getUniqueId())) return;

        int secs = Math.max(1, plugin.getConfig().getInt("abilities.waystone-channel-seconds", 3));
        Location start = p.getLocation().clone();
        Fx.bar(p, "&bChanneling... stand still for " + secs + "s");

        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                if (!p.isOnline() || p.isDead() || !p.getWorld().equals(start.getWorld())
                        || p.getLocation().distanceSquared(start) > 1.5) {
                    channeling.remove(p.getUniqueId());
                    if (p.isOnline()) Fx.bar(p, "&cTeleport interrupted");
                    cancel();
                    return;
                }
                ticks += 5;
                Fx.particle(p.getLocation().add(0, 1, 0), Particle.PORTAL, 25, 0.4, 0.8, 0.4, 0.3);
                if (ticks >= secs * 20) {
                    channeling.remove(p.getUniqueId());
                    p.teleport(dest);
                    cd.start(p, "waystone", cd.seconds("waystone", 30));
                    Fx.particle(dest.clone().add(0, 1, 0), Particle.REVERSE_PORTAL, 60, 0.4, 0.8, 0.4, 0.2);
                    Fx.sound(dest, "entity.enderman.teleport", 1f, 1f);
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 5L, 5L);
    }

    // ============================================================ LIFEBLOOM CHARM

    private void lifebloom(Player p) {
        if (!cd.tryUse(p, "lifebloom", 90)) return;
        double amount = plugin.getConfig().getDouble("abilities.lifebloom-heal", 10.0);

        List<Player> targets = new ArrayList<>();
        targets.add(p);
        for (Entity en : p.getNearbyEntities(10, 6, 10)) {
            if (en instanceof Player other && other.getGameMode() != GameMode.SPECTATOR) targets.add(other);
        }

        for (Player t : targets) {
            Fx.heal(t, amount);
            t.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 160, 1));
            t.removePotionEffect(PotionEffectType.POISON);
            t.removePotionEffect(PotionEffectType.WITHER);
            t.removePotionEffect(PotionEffectType.SLOWNESS);
            t.removePotionEffect(PotionEffectType.WEAKNESS);
            t.removePotionEffect(PotionEffectType.BLINDNESS);
            t.removePotionEffect(PotionEffectType.NAUSEA);
            t.removePotionEffect(PotionEffectType.HUNGER);
            Fx.particle(t.getLocation().add(0, 1.2, 0), Particle.HEART, 12, 0.5, 0.6, 0.5, 0.0);
            if (t != p) Fx.bar(t, "&d" + p.getName() + "'s Lifebloom Charm mends you");
        }
        Fx.sound(p.getLocation(), "block.beacon.power_select", 1f, 1.5f);
        Fx.bar(p, "&dLifebloom - healed " + targets.size() + (targets.size() == 1 ? " person" : " people"));
    }

    // ============================================================ TETHER HOOK

    @EventHandler
    public void onFish(PlayerFishEvent e) {
        Player p = e.getPlayer();
        boolean holding = items.typeOf(p.getInventory().getItemInMainHand()) == ItemType.TETHER_HOOK
                || items.typeOf(p.getInventory().getItemInOffHand()) == ItemType.TETHER_HOOK;
        if (!holding) return;

        PlayerFishEvent.State state = e.getState();
        if (state == PlayerFishEvent.State.IN_GROUND) {
            Location hook = e.getHook().getLocation();
            Vector to = hook.toVector().subtract(p.getLocation().toVector());
            double dist = to.length();
            if (dist < 2.0 || dist > 60.0) return;
            if (!cd.tryUse(p, "hook", 3)) return;

            Vector pull = to.normalize().multiply(Math.min(2.8, 0.6 + dist * 0.09));
            pull.setY(pull.getY() + 0.35);
            p.setVelocity(pull);
            cd.grantNoFall(p, 4);
            e.getHook().remove();
            Fx.sound(p.getLocation(), "entity.fishing_bobber.retrieve", 1f, 0.6f);
            Fx.particle(p.getLocation().add(0, 1, 0), Particle.CRIT, 15, 0.3, 0.3, 0.3, 0.2);
        } else if (state == PlayerFishEvent.State.CAUGHT_ENTITY && e.getCaught() != null) {
            if (!cd.tryUse(p, "hook", 3)) return;
            Entity caught = e.getCaught();
            Vector yank = p.getLocation().toVector().subtract(caught.getLocation().toVector());
            if (yank.lengthSquared() > 0.01) {
                yank.normalize().multiply(1.4).setY(0.5);
                caught.setVelocity(yank);
            }
            Fx.sound(p.getLocation(), "entity.fishing_bobber.retrieve", 1f, 0.8f);
        }
    }

    // ============================================================ QUARRY PICKAXE

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (breaking) return;
        Player p = e.getPlayer();
        if (p.isSneaking()) return;
        ItemStack tool = p.getInventory().getItemInMainHand();
        if (items.typeOf(tool) != ItemType.QUARRY_PICKAXE) return;

        RayTraceResult r = p.rayTraceBlocks(7.0);
        if (r == null || r.getHitBlockFace() == null) return;
        BlockFace face = r.getHitBlockFace();
        Block origin = e.getBlock();

        for (int a = -1; a <= 1; a++) {
            for (int b = -1; b <= 1; b++) {
                if (a == 0 && b == 0) continue;
                Block target;
                switch (face) {
                    case UP, DOWN -> target = origin.getRelative(a, 0, b);
                    case NORTH, SOUTH -> target = origin.getRelative(a, b, 0);
                    default -> target = origin.getRelative(0, a, b);
                }
                if (canQuarry(target)) breakExtra(p, target, tool);
            }
        }
    }

    private boolean canQuarry(Block b) {
        Material m = b.getType();
        if (b.isEmpty() || b.isLiquid()) return false;
        float hardness = m.getHardness();
        if (hardness < 0 || hardness > 50) return false;
        if (m == Material.SPAWNER) return false;
        return !(b.getState() instanceof Container);
    }

    private void breakExtra(Player p, Block b, ItemStack tool) {
        BlockBreakEvent ev = new BlockBreakEvent(b, p);
        breaking = true;
        try {
            Bukkit.getPluginManager().callEvent(ev); // lets protection plugins say no
        } finally {
            breaking = false;
        }
        if (ev.isCancelled()) return;
        if (ev.isDropItems()) b.breakNaturally(tool);
        else b.setType(Material.AIR);
    }

    // ============================================================ housekeeping

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        selection.remove(e.getPlayer().getUniqueId());
        channeling.remove(e.getPlayer().getUniqueId());
    }
}
