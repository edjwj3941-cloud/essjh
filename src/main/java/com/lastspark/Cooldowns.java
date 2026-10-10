package com.lastspark;

import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Per-player ability cooldowns plus a short "no fall damage" window used by leaps and the grapple. */
public final class Cooldowns {
    private final LastSparkPlugin plugin;
    private final Map<String, Long> until = new HashMap<>();
    private final Map<UUID, Long> noFall = new HashMap<>();

    public Cooldowns(LastSparkPlugin plugin) {
        this.plugin = plugin;
    }

    /** Cooldown length in seconds from config.yml (cooldowns.<key>), falling back to the given default. */
    public int seconds(String key, int def) {
        return plugin.getConfig().getInt("cooldowns." + key, def);
    }

    /** True if ready; otherwise tells the player how long is left. */
    public boolean ready(Player p, String key) {
        Long end = until.get(p.getUniqueId() + ":" + key);
        long now = System.currentTimeMillis();
        if (end == null || now >= end) return true;
        long left = (end - now + 999) / 1000;
        Fx.bar(p, "&c" + key.replace('_', ' ') + " ready in &f" + left + "s");
        return false;
    }

    public void start(Player p, String key, int seconds) {
        until.put(p.getUniqueId() + ":" + key, System.currentTimeMillis() + seconds * 1000L);
    }

    /** Checks the cooldown and starts it if the ability can be used right now. */
    public boolean tryUse(Player p, String key, int defaultSeconds) {
        if (!ready(p, key)) return false;
        start(p, key, seconds(key, defaultSeconds));
        return true;
    }

    public void grantNoFall(Player p, int seconds) {
        noFall.put(p.getUniqueId(), System.currentTimeMillis() + seconds * 1000L);
    }

    public boolean hasNoFall(Player p) {
        Long end = noFall.get(p.getUniqueId());
        return end != null && System.currentTimeMillis() < end;
    }
}
