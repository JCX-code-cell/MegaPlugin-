package com.megaplugin.module;

import com.megaplugin.MegaPlugin;
import com.megaplugin.util.DataFile;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;
import java.util.stream.Collectors;

public class WarpModule extends MegaModule {

    private static final String WARP_GUI_TITLE = "§8§l[ §b§l地标传送 §8§l]";
    private DataFile dataFile;

    public WarpModule(MegaPlugin plugin) {
        super(plugin);
    }

    @Override
    public void onEnable() {
        dataFile = new DataFile(plugin, "warps.yml");
        registerListener();
        register("setwarp", new SetwarpCmd());
        register("warp", new WarpCmd());
        register("delwarp", new DelwarpCmd());
        register("warps", new WarpsCmd());
    }

    @Override
    public void onDisable() {
        dataFile.save();
    }

    public Location getWarp(String name) {
        return dataFile.getConfig().getLocation(name);
    }

    public void setWarp(String name, Location loc) {
        dataFile.getConfig().set(name, loc);
        dataFile.save();
    }

    public void delWarp(String name) {
        dataFile.getConfig().set(name, null);
        dataFile.save();
    }

    public Set<String> getWarps() {
        var section = dataFile.getConfig().getKeys(false);
        return section != null ? section : Collections.emptySet();
    }

    private void openWarpGui(Player player) {
        List<String> warpList = new ArrayList<>(getWarps());
        int size = Math.min(54, ((warpList.size() + 8) / 9 + 1) * 9);
        if (size < 9) size = 9;
        Inventory inv = Bukkit.createInventory(null, size, WARP_GUI_TITLE);

        // Material rotation for visual variety
        Material[] icons = {
            Material.GRASS_BLOCK, Material.OAK_LOG, Material.STONE, Material.SAND,
            Material.NETHERRACK, Material.END_STONE, Material.DIAMOND_BLOCK,
            Material.GOLD_BLOCK, Material.EMERALD_BLOCK, Material.REDSTONE_BLOCK,
            Material.LAPIS_BLOCK, Material.IRON_BLOCK, Material.BOOKSHELF,
            Material.CRAFTING_TABLE, Material.FURNACE, Material.CHEST,
            Material.BEACON, Material.ENDER_CHEST, Material.ENCHANTING_TABLE
        };

        for (int i = 0; i < warpList.size(); i++) {
            String name = warpList.get(i);
            Location loc = getWarp(name);
            Material mat = icons[i % icons.length];
            ItemStack item = new ItemStack(mat);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName("§e§l" + name);
                List<String> lore = new ArrayList<>();
                if (loc != null) {
                    lore.add("§7世界: §f" + loc.getWorld().getName());
                    lore.add("§7坐标: §f" + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ());
                }
                lore.add("");
                lore.add("§a§l点击传送");
                meta.setLore(lore);
                item.setItemMeta(meta);
            }
            inv.setItem(i, item);
        }

        // Fill empty with glass
        ItemStack glass = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta gm = glass.getItemMeta();
        if (gm != null) { gm.setDisplayName(" "); glass.setItemMeta(gm); }
        for (int i = 0; i < size; i++) if (inv.getItem(i) == null) inv.setItem(i, glass);

        player.openInventory(inv);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!e.getView().getTitle().equals(WARP_GUI_TITLE)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        ItemStack item = e.getCurrentItem();
        if (item == null || item.getType() == Material.AIR || item.getType() == Material.BLACK_STAINED_GLASS_PANE) return;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        String display = meta.getDisplayName();
        if (!display.startsWith("§e§l")) return;
        String warpName = display.substring(4);

        Location loc = getWarp(warpName);
        if (loc == null) {
            p.sendMessage(msg("prefix") + " §c地标不存在！");
            return;
        }
        p.closeInventory();
        p.teleport(loc);
        p.sendMessage(msg("prefix") + " §a已传送到地标 §e" + warpName);
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent e) {
        if (e.getView().getTitle().equals(WARP_GUI_TITLE)) e.setCancelled(true);
    }

    @SuppressWarnings("deprecation")
    private void register(String name, CommandExecutor executor) {
        var cmd = plugin.getCommand(name);
        if (cmd != null) {
            cmd.setExecutor(executor);
            if (executor instanceof TabCompleter t) cmd.setTabCompleter(t);
        }
    }

    private class SetwarpCmd implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (!(sender instanceof Player p)) { sender.sendMessage(msg("player-only")); return true; }
            if (!p.hasPermission("megaplugin.warp.admin")) { p.sendMessage(msg("no-permission")); return true; }
            if (args.length == 0) { p.sendMessage(msg("prefix") + " §c用法: /setwarp <名字>"); return true; }
            String name = args[0].toLowerCase();
            setWarp(name, p.getLocation());
            p.sendMessage(msg("prefix") + " §a地标 §e" + name + " §a设置成功！");
            return true;
        }
    }

    private class WarpCmd implements CommandExecutor, TabCompleter {
        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (!(sender instanceof Player p)) { sender.sendMessage(msg("player-only")); return true; }
            if (!p.hasPermission("megaplugin.warp")) { p.sendMessage(msg("no-permission")); return true; }
            if (args.length == 0) {
                openWarpGui(p);
                return true;
            }
            String name = args[0].toLowerCase();
            Location loc = getWarp(name);
            if (loc == null) { p.sendMessage(msg("prefix") + " §c地标 §e" + name + " §c不存在！"); return true; }
            p.teleport(loc);
            p.sendMessage(msg("prefix") + " §a已传送到地标 §e" + name);
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
            if (args.length == 1) {
                return getWarps().stream()
                        .filter(w -> w.startsWith(args[0].toLowerCase()))
                        .collect(Collectors.toList());
            }
            return Collections.emptyList();
        }
    }

    private class DelwarpCmd implements CommandExecutor, TabCompleter {
        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (!sender.hasPermission("megaplugin.warp.admin")) { sender.sendMessage(msg("no-permission")); return true; }
            if (args.length == 0) { sender.sendMessage(msg("prefix") + " §c用法: /delwarp <名字>"); return true; }
            String name = args[0].toLowerCase();
            if (getWarp(name) == null) { sender.sendMessage(msg("prefix") + " §c地标 §e" + name + " §c不存在！"); return true; }
            delWarp(name);
            sender.sendMessage(msg("prefix") + " §a地标 §e" + name + " §a已删除！");
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
            if (args.length == 1) {
                return getWarps().stream()
                        .filter(w -> w.startsWith(args[0].toLowerCase()))
                        .collect(Collectors.toList());
            }
            return Collections.emptyList();
        }
    }

    private class WarpsCmd implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (!sender.hasPermission("megaplugin.warp")) { sender.sendMessage(msg("no-permission")); return true; }
            if (sender instanceof Player p) {
                openWarpGui(p);
                return true;
            }
            Set<String> warps = getWarps();
            if (warps.isEmpty()) {
                sender.sendMessage(msg("prefix") + " §7没有设置任何地标！使用 /setwarp <名字>");
            } else {
                sender.sendMessage(msg("prefix") + " §7地标列表 (§e" + warps.size() + "§7): §e" + String.join("§7, §e", warps));
            }
            return true;
        }
    }

    private boolean hasPermission(CommandSender sender, String perm) {
        return sender.hasPermission(perm);
    }
}
