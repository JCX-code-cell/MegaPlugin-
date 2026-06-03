package com.megaplugin.module;

import com.megaplugin.MegaPlugin;
import com.megaplugin.util.DataFile;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

/**
 * 地标传送模块 — /setwarp /warp /delwarp /warps + GUI
 */
public class WarpModule extends MegaModule {

    private final DataFile data = new DataFile(plugin, "warps.yml");
    private static final String TITLE = "§8§l[ §d§l地标传送 §8§l]";

    public WarpModule(MegaPlugin plugin) { super(plugin); }

    @Override
    public void onEnable() {
        listen();
        cmd("setwarp", new SetwarpCmd());
        cmd("warp", new WarpCmd());
        cmd("delwarp", new DelwarpCmd());
        cmd("warps", new WarpsCmd());
        Bukkit.getScheduler().runTaskTimer(plugin, data::save, 6000L, 6000L);
    }

    @Override
    public void onDisable() {
        data.save();
        super.onDisable();
    }

    private void cmd(String name, CommandExecutor exe) {
        var c = plugin.getCommand(name);
        if (c != null) {
            c.setExecutor(exe);
            if (exe instanceof TabCompleter t) c.setTabCompleter(t);
        }
    }

    private Set<String> keys() { return data.getConfig().getKeys(false); }

    class SetwarpCmd implements CommandExecutor {
        public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!(s instanceof Player p)) { s.sendMessage(msg("player-only")); return true; }
            if (!p.hasPermission("megaplugin.warp.admin")) { p.sendMessage(msg("no-permission")); return true; }
            if (a.length == 0) { p.sendMessage(msg("prefix") + " §c用法: /setwarp <名字>"); return true; }
            String n = a[0].toLowerCase();
            data.getConfig().set(n, p.getLocation());
            data.save();
            p.sendMessage(msg("prefix") + " §a地标 §e" + n + " §a已设置！");
            return true;
        }
    }

    class WarpCmd implements CommandExecutor, TabCompleter {
        public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!(s instanceof Player p)) { s.sendMessage(msg("player-only")); return true; }
            if (!p.hasPermission("megaplugin.warp")) { p.sendMessage(msg("no-permission")); return true; }
            if (a.length == 0) { openWarpGui(p); return true; }
            Location loc = data.getConfig().getLocation(a[0].toLowerCase());
            if (loc == null) { p.sendMessage(msg("prefix") + " §c地标不存在！"); return true; }
            p.teleport(loc);
            p.sendMessage(msg("prefix") + " §a已传送到 §e" + a[0]);
            return true;
        }
        public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
            if (a.length == 1)
                return keys().stream().filter(k -> k.startsWith(a[0].toLowerCase())).toList();
            return List.of();
        }
    }

    class DelwarpCmd implements CommandExecutor, TabCompleter {
        public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!p(s)) return true;
            Player p = (Player) s;
            if (!p.hasPermission("megaplugin.warp.admin")) { p.sendMessage(msg("no-permission")); return true; }
            if (a.length == 0) { p.sendMessage(msg("prefix") + " §c用法: /delwarp <名字>"); return true; }
            String n = a[0].toLowerCase();
            if (!keys().contains(n)) { p.sendMessage(msg("prefix") + " §c地标不存在！"); return true; }
            data.getConfig().set(n, null);
            data.save();
            p.sendMessage(msg("prefix") + " §c地标 §e" + n + " §c已删除！");
            return true;
        }
        public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
            if (a.length == 1)
                return keys().stream().filter(k -> k.startsWith(a[0].toLowerCase())).toList();
            return List.of();
        }
    }

    class WarpsCmd implements CommandExecutor {
        public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!(s instanceof Player p)) { s.sendMessage(msg("player-only")); return true; }
            openWarpGui(p);
            return true;
        }
    }

    private boolean p(CommandSender s) { return s instanceof Player; }

    // ── GUI ──
    private void openWarpGui(Player p) {
        var warps = new ArrayList<>(keys());
        int rows = Math.min(6, Math.max(1, (warps.size() + 8) / 9 + 1));
        Inventory inv = Bukkit.createInventory(null, rows * 9, TITLE);
        for (int i = 0; i < warps.size(); i++) {
            inv.setItem(i, item(Material.COMPASS, "§a§l" + warps.get(i),
                    "§7点击传送到此地标"));
        }
        for (int i = 0; i < inv.getSize(); i++)
            if (inv.getItem(i) == null) inv.setItem(i, glass());
        p.openInventory(inv);
    }

    @EventHandler
    public void onGuiClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!e.getView().getTitle().equals(TITLE)) return;
        e.setCancelled(true);
        var item = e.getCurrentItem();
        if (item == null || !item.hasItemMeta()) return;
        var meta = item.getItemMeta();
        if (!meta.hasDisplayName()) return;
        String name = meta.getDisplayName().replace("§a§l", "").toLowerCase();
        Location loc = data.getConfig().getLocation(name);
        if (loc == null) return;
        p.closeInventory();
        p.teleport(loc);
        p.sendMessage(msg("prefix") + " §a已传送到 §e" + name);
    }

    @EventHandler
    public void onGuiDrag(InventoryDragEvent e) {
        if (e.getView().getTitle().equals(TITLE)) e.setCancelled(true);
    }

    private ItemStack item(Material m, String name, String... lore) {
        ItemStack i = new ItemStack(m);
        ItemMeta meta = i.getItemMeta();
        if (meta != null) { meta.setDisplayName(name); meta.setLore(Arrays.asList(lore)); i.setItemMeta(meta); }
        return i;
    }

    private ItemStack glass() {
        return item(Material.GRAY_STAINED_GLASS_PANE, " ");
    }
}
