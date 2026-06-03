package com.megaplugin.module;

import com.megaplugin.MegaPlugin;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;

/**
 * GrimAC 反作弊桥接模块 — /gb 代理 GrimAC 命令
 */
public class GrimBridgeModule extends MegaModule {

    public GrimBridgeModule(MegaPlugin plugin) { super(plugin); }

    @Override
    public void onEnable() {
        var c = plugin.getCommand("grimbridge");
        if (c != null) c.setExecutor(new GrimCmd());
    }

    class GrimCmd implements CommandExecutor, TabCompleter {
        public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!s.hasPermission("megaplugin.admin")) { s.sendMessage(msg("no-permission")); return true; }
            Plugin grim = plugin.getServer().getPluginManager().getPlugin("GrimAC");
            if (grim == null) { s.sendMessage(msg("prefix") + " §cGrimAC 未安装！"); return true; }

            if (a.length == 0) {
                s.sendMessage(msg("prefix") + " §e/gb status §7查看状态");
                s.sendMessage(msg("prefix") + " §e/gb alerts §7切换警报");
                s.sendMessage(msg("prefix") + " §e/gb verbose §7切换详细信息");
                return true;
            }
            // 代理给 GrimAC 的 grim 命令
            String grimCmd = "grim " + String.join(" ", a);
            if (s instanceof Player p) p.performCommand(grimCmd);
            else plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), grimCmd);
            return true;
        }

        public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
            if (a.length == 1)
                return List.of("status", "reload", "alerts", "verbose", "info", "help", "debug")
                        .stream().filter(x -> x.startsWith(a[0].toLowerCase())).toList();
            return List.of();
        }
    }
}
