package com.lastspark;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Enforces "only one of each relic in the world".
 *
 * - Relics are only ever minted through ItemManager.tryCreate, which refuses if one already exists.
 * - Dropped relics are invulnerable and never despawn, so they can't be burned, blown up or timed out.
 * - If a relic is truly destroyed (falls into the void), its slot is freed so it can be re-forged.
 * - Every few seconds, and whenever something suspicious happens, online inventories, open containers
 *   and loaded dropped items are scanned. Exact clones (creative middle-click, dupe glitches) and stale
 *   copies are deleted.
 */
public final class UniquenessGuard implements Listener {
    private static final Set<String> LOSS_CAUSES = Set.of("OUT_OF_WORLD", "DESPAWN", "DEATH");

    private final LastSparkPlugin plugin;
    private final ItemManager items;
    private boolean scanQueued = false;

    public UniquenessGuard(LastSparkPlugin plugin, ItemManager items) {
        this.plugin = plugin;
        this.items = items;
    }

    public void start() {
        long interval = Math.max(20L, plugin.getConfig().getLong("scan-interval-ticks", 100L));
        Bukkit.getScheduler().runTaskTimer(plugin, this::scan, interval, interval);
    }

    private void queueScan() {
        if (scanQueued) return;
        scanQueued = true;
        Bukkit.getScheduler().runTask(plugin, () -> {
            scanQueued = false;
            scan();
        });
    }

    // ------------------------------------------------------------ keeping dropped relics alive

    @EventHandler
    public void onItemSpawn(ItemSpawnEvent e) {
        Item entity = e.getEntity();
        if (items.typeOf(entity.getItemStack()) != null) {
            entity.setInvulnerable(true);
            entity.setGlowing(true);
        }
    }

    @EventHandler
    public void onItemDespawn(ItemDespawnEvent e) {
        if (items.typeOf(e.getEntity().getItemStack()) != null) e.setCancelled(true);
    }

    @EventHandler
    public void onItemDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Item item && items.typeOf(item.getItemStack()) != null) {
            e.setCancelled(true);
        }
    }

    /** A relic that is genuinely gone (void, forced despawn) frees its slot so it can be forged again. */
    @EventHandler
    public void onEntityRemove(EntityRemoveEvent e) {
        if (!(e.getEntity() instanceof Item item)) return;
        ItemStack stack = item.getItemStack();
        ItemType t = items.typeOf(stack);
        if (t == null) return;
        if (!LOSS_CAUSES.contains(e.getCause().name())) return;
        String serial = items.serialOf(stack);
        if (serial != null && serial.equals(items.currentSerial(t))) {
            items.release(t);
            plugin.getLogger().info(t.id() + " was destroyed (" + e.getCause().name() + ") and can be forged again.");
        }
    }

    // ------------------------------------------------------------ no crafting with relics

    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent e) {
        for (ItemStack s : e.getInventory().getMatrix()) {
            if (items.typeOf(s) != null) {
                e.getInventory().setResult(null);
                return;
            }
        }
    }

    // ------------------------------------------------------------ scan triggers

    @EventHandler public void onJoin(PlayerJoinEvent e) { queueScan(); }
    @EventHandler public void onOpen(InventoryOpenEvent e) { queueScan(); }
    @EventHandler public void onCreative(InventoryCreativeEvent e) { queueScan(); }
    @EventHandler public void onEntitiesLoad(EntitiesLoadEvent e) { queueScan(); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player) queueScan();
    }

    // ------------------------------------------------------------ the scan

    private void scan() {
        Map<ItemType, String> found = new EnumMap<>(ItemType.class);
        Set<String> scannedContainers = new HashSet<>();

        for (Player p : Bukkit.getOnlinePlayers()) {
            scanInventory(p.getInventory(), p.getName(), found, p);
            scanInventory(p.getEnderChest(), p.getName() + "'s ender chest", found, p);

            ItemStack cursor = p.getItemOnCursor();
            if (items.typeOf(cursor) != null && shouldRemove(cursor, p.getName(), found, p)) {
                p.setItemOnCursor(null);
            }

            Inventory top = p.getOpenInventory().getTopInventory();
            InventoryType type = top.getType();
            if (type == InventoryType.CRAFTING || type == InventoryType.PLAYER
                    || type == InventoryType.ENDER_CHEST || type == InventoryType.CREATIVE) {
                continue;
            }
            String key = containerKey(top);
            if (key != null && scannedContainers.add(key)) {
                scanInventory(top, "a container", found, p);
            }
        }

        for (World w : Bukkit.getWorlds()) {
            for (Item drop : w.getEntitiesByClass(Item.class)) {
                ItemStack stack = drop.getItemStack();
                if (items.typeOf(stack) == null) continue;
                Location l = drop.getLocation();
                String where = "on the ground in " + w.getName() + " at "
                        + l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ();
                if (shouldRemove(stack, where, found, null)) drop.remove();
            }
        }
    }

    private void scanInventory(Inventory inv, String where, Map<ItemType, String> found, Player notify) {
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack s = contents[i];
            if (s == null || items.typeOf(s) == null) continue;
            if (shouldRemove(s, where, found, notify)) inv.setItem(i, null);
        }
    }

    /** Returns true if this stack is a stale or duplicate relic that must be deleted. */
    private boolean shouldRemove(ItemStack s, String where, Map<ItemType, String> found, Player notify) {
        ItemType t = items.typeOf(s);
        if (t == null) return false;

        String reason = null;
        if (items.check(t, items.serialOf(s)) == ItemManager.Validity.STALE) {
            reason = "stale";
        } else if (found.containsKey(t)) {
            reason = "duplicate of the copy held by " + found.get(t);
        }

        if (reason == null) {
            found.put(t, where);
            items.seen(t, where);
            return false;
        }

        plugin.getLogger().info("Removed " + reason + " " + t.id() + " (" + where + ")");
        if (notify != null) {
            notify.sendMessage(Fx.color("&7A counterfeit ").append(items.nameOf(t)).append(Fx.color(" &7crumbles to dust.")));
        }
        return true;
    }

    /** A stable identity for a container so two viewers of the same chest are only scanned once. */
    private String containerKey(Inventory inv) {
        InventoryHolder holder = inv.getHolder();
        if (holder instanceof Entity en) return "e:" + en.getUniqueId();
        Location l = inv.getLocation();
        if (l == null || l.getWorld() == null) return null;
        return "b:" + l.getWorld().getName() + ":" + l.getBlockX() + ":" + l.getBlockY() + ":" + l.getBlockZ();
    }
}
