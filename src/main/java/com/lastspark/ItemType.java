package com.lastspark;

import org.bukkit.Material;

import java.util.Locale;

/** The 15 relics. Only the id, category and fallback material live here; everything else is in config.yml. */
public enum ItemType {
    // ---- Armor (4) ----
    ECLIPSE_CROWN("eclipse_crown", Category.ARMOR, Material.NETHERITE_HELMET),
    PHOENIX_PLATE("phoenix_plate", Category.ARMOR, Material.NETHERITE_CHESTPLATE),
    TEMPEST_LEGGINGS("tempest_leggings", Category.ARMOR, Material.NETHERITE_LEGGINGS),
    VOIDSTEP_BOOTS("voidstep_boots", Category.ARMOR, Material.NETHERITE_BOOTS),

    // ---- Weapons (6, two of them maces) ----
    THUNDERCLAP_MACE("thunderclap_mace", Category.WEAPON, Material.MACE),
    WIND_WARDEN_MAUL("wind_warden_maul", Category.WEAPON, Material.MACE),
    SOULREAVER("soulreaver", Category.WEAPON, Material.NETHERITE_SWORD),
    EXECUTIONER_AXE("executioner_axe", Category.WEAPON, Material.NETHERITE_AXE),
    SKYFALL_BOW("skyfall_bow", Category.WEAPON, Material.BOW),
    SHADOWFANG_DAGGER("shadowfang_dagger", Category.WEAPON, Material.DIAMOND_SWORD),

    // ---- Utility (5) ----
    LAST_SPARK("last_spark", Category.UTILITY, Material.NETHER_STAR),
    WAYSTONE_SHARD("waystone_shard", Category.UTILITY, Material.ECHO_SHARD),
    TETHER_HOOK("tether_hook", Category.UTILITY, Material.FISHING_ROD),
    LIFEBLOOM_CHARM("lifebloom_charm", Category.UTILITY, Material.HEART_OF_THE_SEA),
    QUARRY_PICKAXE("quarry_pickaxe", Category.UTILITY, Material.NETHERITE_PICKAXE);

    public enum Category { ARMOR, WEAPON, UTILITY }

    private final String id;
    private final Category category;
    private final Material fallback;

    ItemType(String id, Category category, Material fallback) {
        this.id = id;
        this.category = category;
        this.fallback = fallback;
    }

    public String id() { return id; }
    public Category category() { return category; }
    public Material fallback() { return fallback; }

    public static ItemType byId(String raw) {
        if (raw == null) return null;
        String s = raw.toLowerCase(Locale.ROOT).trim().replace(' ', '_').replace('-', '_');
        if (s.equals("spark") || s.equals("the_last_spark") || s.equals("thelastspark")) return LAST_SPARK;
        for (ItemType t : values()) {
            if (t.id.equals(s)) return t;
        }
        return null;
    }
}
