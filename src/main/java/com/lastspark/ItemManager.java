package com.lastspark;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the relic ItemStacks and owns the "only one of each exists" registry.
 *
 * Every relic carries a hidden id and a serial. The registry remembers the one valid serial per relic.
 * A relic with a different (or revoked) serial is stale and gets removed by the UniquenessGuard.
 */
public final class ItemManager {
    public enum Validity { VALID, STALE }

    private final LastSparkPlugin plugin;
    private final NamespacedKey idKey;
    private final NamespacedKey serialKey;
    private final File dataFile;
    private final Map<ItemType, String> serials = new EnumMap<>(ItemType.class);
    private final Map<ItemType, String> lastSeen = new EnumMap<>(ItemType.class);
    private final LinkedHashSet<String> revoked = new LinkedHashSet<>();

    public ItemManager(LastSparkPlugin plugin) {
        this.plugin = plugin;
        this.idKey = new NamespacedKey(plugin, "item_id");
        this.serialKey = new NamespacedKey(plugin, "serial");
        this.dataFile = new File(plugin.getDataFolder(), "data.yml");
        load();
    }

    // ------------------------------------------------------------ identification

    public ItemType typeOf(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) return null;
        String id = stack.getItemMeta().getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
        return id == null ? null : ItemType.byId(id);
    }

    public String serialOf(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) return null;
        return stack.getItemMeta().getPersistentDataContainer().get(serialKey, PersistentDataType.STRING);
    }

    // ------------------------------------------------------------ registry

    public boolean exists(ItemType t) {
        return serials.containsKey(t);
    }

    public String currentSerial(ItemType t) {
        return serials.get(t);
    }

    /** Creates the relic if (and only if) it does not exist yet. Returns null when it already exists. */
    public ItemStack tryCreate(ItemType t) {
        if (serials.containsKey(t)) return null;
        String serial = UUID.randomUUID().toString();
        serials.put(t, serial);
        save();
        return build(t, serial);
    }

    /** Frees a relic so it can be created again. Any leftover copies become stale and get purged. */
    public void release(ItemType t) {
        String old = serials.remove(t);
        if (old != null) {
            revoked.add(old);
            while (revoked.size() > 300) {
                Iterator<String> it = revoked.iterator();
                it.next();
                it.remove();
            }
        }
        lastSeen.remove(t);
        save();
    }

    public Validity check(ItemType t, String serial) {
        if (serial == null) return Validity.STALE;
        String cur = serials.get(t);
        if (cur == null) {
            // Registry has no record (e.g. data.yml was deleted): adopt the copy we found, unless it was revoked.
            if (revoked.contains(serial)) return Validity.STALE;
            serials.put(t, serial);
            save();
            return Validity.VALID;
        }
        return cur.equals(serial) ? Validity.VALID : Validity.STALE;
    }

    public void seen(ItemType t, String where) {
        lastSeen.put(t, where);
    }

    public String lastSeen(ItemType t) {
        return lastSeen.getOrDefault(t, "unknown");
    }

    // ------------------------------------------------------------ names

    public Component nameOf(ItemType t) {
        return Fx.color(cfgString(t, "name", t.id()));
    }

    public String plainName(ItemType t) {
        return PlainTextComponentSerializer.plainText().serialize(nameOf(t));
    }

    private String cfgString(ItemType t, String key, String def) {
        return plugin.getConfig().getString("items." + t.id() + "." + key, def);
    }

    // ------------------------------------------------------------ building

    public ItemStack build(ItemType t, String serial) {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("items." + t.id());

        Material mat = t.fallback();
        if (s != null) {
            Material m = Material.matchMaterial(s.getString("material", t.fallback().name()));
            if (m != null && m.isItem()) mat = m;
        }

        ItemStack stack = new ItemStack(mat);
        ItemMeta meta = stack.getItemMeta();

        meta.displayName(Fx.noItalic(cfgString(t, "name", t.id())));

        List<Component> lore = new ArrayList<>();
        if (s != null) {
            for (String line : s.getStringList("lore")) lore.add(Fx.noItalic(line));
        }
        meta.lore(lore);

        meta.setUnbreakable(true);
        meta.setEnchantmentGlintOverride(true);
        meta.setMaxStackSize(1);

        if (s != null) {
            for (String part : s.getString("enchantments", "").split(",")) {
                String[] kv = part.trim().split(":");
                if (kv.length != 2) continue;
                try {
                    Enchantment ench = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(kv[0].trim().toLowerCase()));
                    if (ench != null) meta.addEnchant(ench, Integer.parseInt(kv[1].trim()), true);
                } catch (NumberFormatException ignored) {
                    plugin.getLogger().warning("Bad enchantment entry '" + part + "' for " + t.id());
                }
            }

            String model = s.getString("item-model", "");
            if (model != null && !model.isBlank()) {
                NamespacedKey key = NamespacedKey.fromString(model.trim());
                if (key != null) meta.setItemModel(key);
            }

            int cmd = s.getInt("custom-model-data", 0);
            if (cmd > 0) meta.setCustomModelData(cmd);
        }

        meta.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, t.id());
        meta.getPersistentDataContainer().set(serialKey, PersistentDataType.STRING, serial);
        stack.setItemMeta(meta);
        return stack;
    }

    // ------------------------------------------------------------ persistence

    private void load() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        for (ItemType t : ItemType.values()) {
            String s = y.getString("serials." + t.id());
            if (s != null) serials.put(t, s);
        }
        revoked.addAll(y.getStringList("revoked"));
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<ItemType, String> e : serials.entrySet()) {
            y.set("serials." + e.getKey().id(), e.getValue());
        }
        y.set("revoked", new ArrayList<>(revoked));
        try {
            plugin.getDataFolder().mkdirs();
            y.save(dataFile);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save data.yml: " + ex.getMessage());
        }
    }
}
