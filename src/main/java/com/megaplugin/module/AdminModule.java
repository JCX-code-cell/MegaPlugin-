package com.megaplugin.module;

import com.megaplugin.MegaPlugin;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.*;
import org.bukkit.entity.Damageable;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * 管理工具模块 — /gmc /gms /gma /gmsp /fly /god /heal /feed /vanish /invsee
 */
public class AdminModule extends MegaModule {

    public AdminModule(MegaPlugin plugin) { super(plugin); }

    @Override
    public void onEnable() {
        cmd("gmc",     p -> mode(p, GameMode.CREATIVE,   "megaplugin.admin"));
        cmd("gms",     p -> mode(p, GameMode.SURVIVAL,   "megaplugin.admin"));
        cmd("gma",     p -> mode(p, GameMode.ADVENTURE,  "megaplugin.admin"));
        cmd("gmsp",    p -> mode(p, GameMode.SPECTATOR,  "megaplugin.admin"));
        cmd("fly",     new FlyCmd());
        cmd("god",     new GodCmd());
        cmd("heal",    new HealCmd());
        cmd("feed",    new FeedCmd());
        cmd("vanish",  new VanishCmd());
        cmd("invsee",  new InvseeCmd());
    }

    private void cmd(String name, CommandExecutor exe) {
        var c = plugin.getCommand(name);
        if (c != null) {
            c.setExecutor(exe);
            if (exe instanceof TabCompleter t) c.setTabCompleter(t);
        }
    }

    // 简化的单玩家操作 lambda
    private void mode(Player p, GameMode gm, String perm) {
        if (!p.hasPermission(perm)) { p.sendMessage(msg("no-permission")); return; }
        p.setGameMode(gm);
        p.sendMessage(msg("prefix") + " §a已切换为 " + gm.name());
    }

    // ── 命令 ──
    class FlyCmd implements CommandExecutor, TabCompleter {
        public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!s.hasPermission("megaplugin.admin")) { s.sendMessage(msg("no-permission")); return true; }
            Player t = a.length > 0 ? Bukkit.getPlayer(a[0]) : (s instanceof Player p ? p : null);
            if (t == null) { s.sendMessage(msg("player-not-found")); return true; }
            t.setAllowFlight(!t.getAllowFlight());
            t.sendMessage(msg("prefix") + " §a飞行: " + (t.getAllowFlight() ? "§2开启" : "§c关闭"));
            return true;
        }
        public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
            return tabPlayers(a);
        }
    }

    class GodCmd implements CommandExecutor, TabCompleter {
        private final Set<UUID> gods = new HashSet<>();
        public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!s.hasPermission("megaplugin.admin")) { s.sendMessage(msg("no-permission")); return true; }
            Player t = a.length > 0 ? Bukkit.getPlayer(a[0]) : (s instanceof Player p ? p : null);
            if (t == null) { s.sendMessage(msg("player-not-found")); return true; }
            boolean on = !gods.contains(t.getUniqueId());
            if (on) gods.add(t.getUniqueId()); else gods.remove(t.getUniqueId());
            t.setInvulnerable(on);
            t.sendMessage(msg("prefix") + " §a无敌: " + (on ? "§2开启" : "§c关闭"));
            return true;
        }
        public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
            return tabPlayers(a);
        }
    }

    class HealCmd implements CommandExecutor, TabCompleter {
        public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!s.hasPermission("megaplugin.admin")) { s.sendMessage(msg("no-permission")); return true; }
            Player t = a.length > 0 ? Bukkit.getPlayer(a[0]) : (s instanceof Player p ? p : null);
            if (t == null) { s.sendMessage(msg("player-not-found")); return true; }
            if (t instanceof Damageable d) d.setHealth(d.getMaxHealth());
            t.setFoodLevel(20);
            t.setFireTicks(0);
            t.sendMessage(msg("prefix") + " §a已治疗！");
            return true;
        }
        public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
            return tabPlayers(a);
        }
    }

    class FeedCmd implements CommandExecutor, TabCompleter {
        public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!s.hasPermission("megaplugin.admin")) { s.sendMessage(msg("no-permission")); return true; }
            Player t = a.length > 0 ? Bukkit.getPlayer(a[0]) : (s instanceof Player p ? p : null);
            if (t == null) { s.sendMessage(msg("player-not-found")); return true; }
            t.setFoodLevel(20);
            t.sendMessage(msg("prefix") + " §a已喂饱！");
            return true;
        }
        public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
            return tabPlayers(a);
        }
    }

    class VanishCmd implements CommandExecutor, TabCompleter {
        private final Set<UUID> vanished = new HashSet<>();
        public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!s.hasPermission("megaplugin.admin")) { s.sendMessage(msg("no-permission")); return true; }
            Player t = a.length > 0 ? Bukkit.getPlayer(a[0]) : (s instanceof Player p ? p : null);
            if (t == null) { s.sendMessage(msg("player-not-found")); return true; }
            boolean on = !vanished.contains(t.getUniqueId());
            if (on) vanished.add(t.getUniqueId()); else vanished.remove(t.getUniqueId());
            for (Player o : Bukkit.getOnlinePlayers()) {
                if (on) o.hidePlayer(plugin, t); else o.showPlayer(plugin, t);
            }
            t.sendMessage(msg("prefix") + " §a隐身: " + (on ? "§2开启" : "§c关闭"));
            return true;
        }
        public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
            return tabPlayers(a);
        }
    }

    class InvseeCmd implements CommandExecutor, TabCompleter {
        public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!(s instanceof Player p)) { s.sendMessage(msg("player-only")); return true; }
            if (!p.hasPermission("megaplugin.admin")) { p.sendMessage(msg("no-permission")); return true; }
            if (a.length == 0) { p.sendMessage(msg("prefix") + " §c用法: /invsee <玩家>"); return true; }
            Player t = Bukkit.getPlayer(a[0]);
            if (t == null) { p.sendMessage(msg("player-not-found")); return true; }
            p.openInventory(t.getInventory());
            return true;
        }
        public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
            return tabPlayers(a);
        }
    }

    private List<String> tabPlayers(String[] a) {
        if (a.length == 1)
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName).filter(n -> n.toLowerCase().startsWith(a[0].toLowerCase())).toList();
        return List.of();
    }
}
