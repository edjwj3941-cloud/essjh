package com.lastspark;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

/** Small shared helpers: text, sounds, particles, health. */
public final class Fx {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private Fx() {}

    public static Component color(String s) {
        return LEGACY.deserialize(s);
    }

    /** Colored text with italics switched off (item names/lore are italic by default). */
    public static Component noItalic(String s) {
        return color(s).decoration(TextDecoration.ITALIC, false);
    }

    public static void msg(CommandSender who, String s) {
        who.sendMessage(color(s));
    }

    public static void bar(Player p, String s) {
        p.sendActionBar(color(s));
    }

    public static void sound(Location l, String key, float volume, float pitch) {
        if (l.getWorld() != null) l.getWorld().playSound(l, key, volume, pitch);
    }

    public static void particle(Location l, Particle type, int count, double dx, double dy, double dz, double speed) {
        if (l.getWorld() != null) l.getWorld().spawnParticle(type, l, count, dx, dy, dz, speed);
    }

    public static double maxHealth(LivingEntity e) {
        AttributeInstance a = e.getAttribute(Attribute.MAX_HEALTH);
        return a == null ? 20.0 : a.getValue();
    }

    public static void heal(LivingEntity e, double amount) {
        e.setHealth(Math.min(maxHealth(e), e.getHealth() + amount));
    }

    public static boolean isRightClick(PlayerInteractEvent e) {
        Action a = e.getAction();
        return a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK;
    }

    /** True when the click is really aimed at a chest/door/etc. and the item ability should stay out of the way. */
    public static boolean blocksAbility(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        return b != null && !e.getPlayer().isSneaking() && b.getType().isInteractable();
    }
}
