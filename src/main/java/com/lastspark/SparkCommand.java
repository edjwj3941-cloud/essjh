package com.lastspark;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /spark list | give <relic> [player] | reset <relic|all> | reload */
public final class SparkCommand implements CommandExecutor, TabCompleter {
    private final LastSparkPlugin plugin;
    private final ItemManager items;

    public SparkCommand(LastSparkPlugin plugin, ItemManager items) {
        this.plugin = plugin;
        this.items = items;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("lastspark.admin")) {
            Fx.msg(sender, "&cYou don't have permission to do that.");
            return true;
        }
        if (args.length == 0) {
            help(sender, label);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list", "status" -> list(sender);
            case "give" -> give(sender, label, args);
            case "reset" -> reset(sender, label, args);
            case "reload" -> {
                plugin.reloadConfig();
                Fx.msg(sender, "&aConfig reloaded. &7Relics forged from now on use the new settings; existing relics keep their old look.");
            }
            default -> help(sender, label);
        }
        return true;
    }

    private void help(CommandSender s, String label) {
        Fx.msg(s, "&5&lThe Last Spark &8- &7admin commands");
        Fx.msg(s, "&d/" + label + " list &7- which relics exist and where they were last seen");
        Fx.msg(s, "&d/" + label + " give <relic> [player] &7- forge a relic that doesn't exist yet");
        Fx.msg(s, "&d/" + label + " reset <relic|all> &7- free a relic (copies in the world turn to dust)");
        Fx.msg(s, "&d/" + label + " reload &7- reload config.yml");
    }

    private void list(CommandSender s) {
        Fx.msg(s, "&5&lRelics &8(&7" + ItemType.values().length + " total&8)");
        for (ItemType t : ItemType.values()) {
            String state = items.exists(t) ? "&cexists &8- &7last seen: " + items.lastSeen(t) : "&aavailable";
            s.sendMessage(Fx.color("&8[&7" + t.category().name().toLowerCase(Locale.ROOT) + "&8] &f" + t.id()
                    + " &8- ").append(Fx.color(state)));
        }
    }

    private void give(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            Fx.msg(sender, "&cUsage: /" + label + " give <relic> [player]");
            return;
        }
        ItemType t = ItemType.byId(args[1]);
        if (t == null) {
            Fx.msg(sender, "&cUnknown relic '" + args[1] + "'. Use /" + label + " list to see them all.");
            return;
        }

        Player target = null;
        if (args.length >= 3) target = Bukkit.getPlayerExact(args[2]);
        else if (sender instanceof Player p) target = p;
        if (target == null) {
            Fx.msg(sender, "&cSpecify an online player.");
            return;
        }

        ItemStack stack = items.tryCreate(t);
        if (stack == null) {
            Fx.msg(sender, "&c" + t.id() + " already exists (last seen: " + items.lastSeen(t)
                    + "). Use /" + label + " reset " + t.id() + " to free it first.");
            return;
        }
        for (ItemStack left : target.getInventory().addItem(stack).values()) {
            target.getWorld().dropItem(target.getLocation(), left);
        }
        Fx.msg(sender, "&aForged " + t.id() + " for " + target.getName() + ".");
    }

    private void reset(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            Fx.msg(sender, "&cUsage: /" + label + " reset <relic|all>");
            return;
        }
        if (args[1].equalsIgnoreCase("all")) {
            for (ItemType t : ItemType.values()) items.release(t);
            Fx.msg(sender, "&aAll relics freed.");
            return;
        }
        ItemType t = ItemType.byId(args[1]);
        if (t == null) {
            Fx.msg(sender, "&cUnknown relic '" + args[1] + "'.");
            return;
        }
        items.release(t);
        Fx.msg(sender, "&a" + t.id() + " freed. Any copy still in the world will crumble to dust.");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission("lastspark.admin")) return out;

        if (args.length == 1) {
            for (String s : List.of("list", "give", "reset", "reload")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            }
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("reset"))) {
            for (ItemType t : ItemType.values()) {
                if (t.id().startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(t.id());
            }
            if (args[0].equalsIgnoreCase("reset") && "all".startsWith(args[1].toLowerCase(Locale.ROOT))) out.add("all");
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[2].toLowerCase(Locale.ROOT))) out.add(p.getName());
            }
        }
        return out;
    }
}
