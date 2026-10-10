package com.lastspark;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class LastSparkPlugin extends JavaPlugin {
    private ItemManager items;
    private Cooldowns cooldowns;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.items = new ItemManager(this);
        this.cooldowns = new Cooldowns(this);

        UniquenessGuard guard = new UniquenessGuard(this, items);
        PassiveAbilities passive = new PassiveAbilities(this, items, cooldowns);

        getServer().getPluginManager().registerEvents(guard, this);
        getServer().getPluginManager().registerEvents(passive, this);
        getServer().getPluginManager().registerEvents(new WeaponAbilities(this, items, cooldowns), this);
        getServer().getPluginManager().registerEvents(new UtilityAbilities(this, items, cooldowns), this);

        SparkCommand command = new SparkCommand(this, items);
        PluginCommand pc = getCommand("spark");
        if (pc != null) {
            pc.setExecutor(command);
            pc.setTabCompleter(command);
        }

        guard.start();
        passive.start();
        getLogger().info("The Last Spark is lit. 15 relics registered.");
    }

    @Override
    public void onDisable() {
        if (items != null) items.save();
    }

    public ItemManager items() { return items; }
    public Cooldowns cooldowns() { return cooldowns; }
}
