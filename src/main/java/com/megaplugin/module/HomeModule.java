package com.megaplugin.module;

import com.megaplugin.MegaPlugin;
import com.megaplugin.util.DataFile;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * 家园模块 — /sethome /home /delhome /homes
 */
public class HomeModule extends MegaModule {

    private final DataFile data;

    public HomeModule(MegaPlugin plugin) {
        super(plugin);
        data = new DataFile(plugin, "homes.yml");
    }

    @Override
    public void onEnable() {
        cmd("sethome", new SethomeCmd());
        cmd("home", new HomeCmd());
        cmd("delhome", new DelhomeCmd());
        cmd("homes", new HomesCmd());
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

    // ── 数据访问 ──
    private String path(Player p, String name) { return p.getUniqueId() + "." + name; }
    private Location get(Player p, String name) { return data.getConfig().getLocation(path(p, name)); }
    private void set(Player p, String name, Location loc) { data.getConfig().set(path(p, name), loc); data.save(); }
    private void del(Player p, String name) { data.getConfig().set(path(p, name), null); data.save(); }
    private boolean has(Player p, String name) { return data.getConfig().contains(path(p, name)); }
    private List<String> list(Player p) {
        var sec = data.getConfig().getConfigurationSection(p.getUniqueId().toString());
        return sec == null ? List.of() : new ArrayList<>(sec.getKeys(false));
    }

    class SethomeCmd implements CommandExecutor {
        public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!(s instanceof Player p)) { s.sendMessage(msg("player-only")); return true; }
            if (!p.hasPermission("megaplugin.home")) { p.sendMessage(msg("no-permission")); return true; }
            if (a.length == 0) { p.sendMessage(msg("prefix") + " §c用法: /sethome <名字>"); return true; }
            String n = a[0].toLowerCase();
            int max = p.hasPermission("megaplugin.home.other") ? 50 : 10;
            if (list(p).size() >= max && !has(p, n)) {
                p.sendMessage(msg("prefix") + " §c你最多只能设置 " + max + " 个家！"); return true;
            }
            set(p, n, p.getLocation());
            p.sendMessage(msg("prefix") + " §a家 §e" + n + " §a设置成功！");
            return true;
        }
    }

    class HomeCmd implements CommandExecutor, TabCompleter {
        public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!(s instanceof Player p)) { s.sendMessage(msg("player-only")); return true; }
            if (!p.hasPermission("megaplugin.home")) { p.sendMessage(msg("no-permission")); return true; }
            if (list(p).isEmpty()) { p.sendMessage(msg("prefix") + " §c你还没有家！"); return true; }
            String n = a.length > 0 ? a[0].toLowerCase() : "home";
            Location loc = get(p, n);
            if (loc == null) { p.sendMessage(msg("prefix") + " §c家不存在！"); return true; }
            p.teleport(loc);
            p.sendMessage(msg("prefix") + " §a已传送到家 §e" + n);
            return true;
        }
        public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
            if (a.length == 1 && s instanceof Player p)
                return list(p).stream().filter(h -> h.startsWith(a[0].toLowerCase())).toList();
            return List.of();
        }
    }

    class DelhomeCmd implements CommandExecutor, TabCompleter {
        public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!(s instanceof Player p)) { s.sendMessage(msg("player-only")); return true; }
            if (!p.hasPermission("megaplugin.home")) { p.sendMessage(msg("no-permission")); return true; }
            if (a.length == 0) { p.sendMessage(msg("prefix") + " §c用法: /delhome <名字>"); return true; }
            String n = a[0].toLowerCase();
            if (!has(p, n)) { p.sendMessage(msg("prefix") + " §c家不存在！"); return true; }
            del(p, n);
            p.sendMessage(msg("prefix") + " §a家已删除！");
            return true;
        }
        public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
            if (a.length == 1 && s instanceof Player p)
                return list(p).stream().filter(h -> h.startsWith(a[0].toLowerCase())).toList();
            return List.of();
        }
    }

    class HomesCmd implements CommandExecutor {
        @SuppressWarnings("deprecation")
        public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!(s instanceof Player p)) { s.sendMessage(msg("player-only")); return true; }
            if (!p.hasPermission("megaplugin.home")) { p.sendMessage(msg("no-permission")); return true; }

            if (a.length > 0) {
                if (!p.hasPermission("megaplugin.home.other")) { p.sendMessage(msg("no-permission")); return true; }
                var off = plugin.getServer().getOfflinePlayer(a[0]);
                if (off == null || !off.hasPlayedBefore()) { p.sendMessage(msg("player-not-found")); return true; }
                var sec = data.getConfig().getConfigurationSection(off.getUniqueId().toString());
                if (sec == null || sec.getKeys(false).isEmpty()) {
                    p.sendMessage(msg("prefix") + " §7" + a[0] + " 没有家。");
                } else {
                    p.sendMessage(msg("prefix") + " §7" + a[0] + " 的家: §e" + String.join("§7, §e", sec.getKeys(false)));
                }
                return true;
            }

            var homes = list(p);
            p.sendMessage(homes.isEmpty()
                    ? msg("prefix") + " §7你还没有家！"
                    : msg("prefix") + " §7你的家: §e" + String.join("§7, §e", homes));
            return true;
        }
    }
}
