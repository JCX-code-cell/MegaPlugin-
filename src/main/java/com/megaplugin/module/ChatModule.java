package com.megaplugin.module;

import com.megaplugin.MegaPlugin;
import com.megaplugin.util.Color;
import com.megaplugin.util.DataFile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.*;

/**
 * 聊天工具模块 — /nick /ec /whois /bc
 */
public class ChatModule extends MegaModule {

    private final DataFile nickData = new DataFile(plugin, "nicknames.yml");

    public ChatModule(MegaPlugin plugin) { super(plugin); }

    @Override
    public void onEnable() {
        cmd("nick", new NickCmd());
        cmd("enderchest", new EnderChestCmd());
        cmd("whois", new WhoisCmd());
        cmd("broadcast", new BroadcastCmd());
    }

    @Override
    public void onDisable() {
        nickData.save();
        super.onDisable();
    }

    private void cmd(String name, CommandExecutor exe) {
        var c = plugin.getCommand(name);
        if (c != null) {
            c.setExecutor(exe);
            if (exe instanceof TabCompleter t) c.setTabCompleter(t);
        }
    }

    class NickCmd implements CommandExecutor, TabCompleter {
        public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!s.hasPermission("megaplugin.chat")) { s.sendMessage(msg("no-permission")); return true; }
            if (!(s instanceof Player p)) { s.sendMessage(msg("player-only")); return true; }

            // /nick <player> <name> (admin)
            if (a.length >= 2 && p.hasPermission("megaplugin.chat.admin")) {
                Player t = Bukkit.getPlayer(a[0]);
                if (t == null) { p.sendMessage(msg("player-not-found")); return true; }
                setNick(t, a[1]);
                p.sendMessage(msg("prefix") + " §a已设置 " + t.getName() + " 的昵称！");
                return true;
            }

            if (a.length == 0) { p.sendMessage(msg("prefix") + " §c用法: /nick <名字|reset>"); return true; }
            setNick(p, a[0]);
            return true;
        }

        private void setNick(Player p, String name) {
            if (name.equalsIgnoreCase("reset")) {
                p.displayName(null);
                p.playerListName(null);
                nickData.getConfig().set(p.getUniqueId().toString(), null);
                p.sendMessage(msg("prefix") + " §a昵称已重置！");
            } else {
                String colored = Color.colorize(name);
                p.displayName(Component.text(colored));
                p.playerListName(Component.text(colored));
                nickData.getConfig().set(p.getUniqueId().toString(), name);
                nickData.save();
                p.sendMessage(msg("prefix") + " §a昵称已设置为: " + colored);
            }
        }

        public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
            if (a.length == 1 && s.hasPermission("megaplugin.chat.admin"))
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName).filter(n -> n.toLowerCase().startsWith(a[0].toLowerCase())).toList();
            return List.of();
        }
    }

    class EnderChestCmd implements CommandExecutor, TabCompleter {
        public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!s.hasPermission("megaplugin.chat")) { s.sendMessage(msg("no-permission")); return true; }
            Player p;
            if (a.length > 0 && s.hasPermission("megaplugin.chat.admin")) {
                p = Bukkit.getPlayer(a[0]);
                if (p == null) { s.sendMessage(msg("player-not-found")); return true; }
            } else if (s instanceof Player pl) {
                p = pl;
            } else { s.sendMessage(msg("player-only")); return true; }
            p.openInventory(p.getEnderChest());
            return true;
        }
        public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
            if (a.length == 1 && s.hasPermission("megaplugin.chat.admin"))
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName).filter(n -> n.toLowerCase().startsWith(a[0].toLowerCase())).toList();
            return List.of();
        }
    }

    class WhoisCmd implements CommandExecutor, TabCompleter {
        public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!s.hasPermission("megaplugin.chat")) { s.sendMessage(msg("no-permission")); return true; }
            if (a.length == 0) { s.sendMessage(msg("prefix") + " §c用法: /whois <玩家>"); return true; }
            Player t = Bukkit.getPlayer(a[0]);
            if (t == null) { s.sendMessage(msg("player-not-found")); return true; }
            s.sendMessage("§8§m          §r §e§l" + t.getName() + " §8§m          ");
            s.sendMessage("§7UUID: §f" + t.getUniqueId());
            s.sendMessage("§7世界: §f" + t.getWorld().getName());
            s.sendMessage("§7坐标: §f" + t.getLocation().getBlockX() + " " + t.getLocation().getBlockY() + " " + t.getLocation().getBlockZ());
            s.sendMessage("§7血量: §f" + String.format("%.0f", t.getHealth()) + "/" + String.format("%.0f", t.getMaxHealth()));
            s.sendMessage("§7游戏模式: §f" + t.getGameMode());
            s.sendMessage("§8§m                              ");
            return true;
        }
        public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
            if (a.length == 1)
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName).filter(n -> n.toLowerCase().startsWith(a[0].toLowerCase())).toList();
            return List.of();
        }
    }

    class BroadcastCmd implements CommandExecutor {
        public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!s.hasPermission("megaplugin.chat.admin")) { s.sendMessage(msg("no-permission")); return true; }
            if (a.length == 0) { s.sendMessage(msg("prefix") + " §c用法: /bc <消息>"); return true; }
            String msg = Color.colorize(String.join(" ", a));
            var component = Color.toComponent(msg);
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.sendMessage("");
                p.sendMessage("§8§m                                              ");
                p.sendMessage(component);
                p.sendMessage("§8§m                                              ");
                p.showTitle(Title.title(Component.text("§6§l广播"),
                        component, Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(3), Duration.ofMillis(500))));
            }
            return true;
        }
    }
}
