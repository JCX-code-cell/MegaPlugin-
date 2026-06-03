package com.megaplugin.module;

import com.megaplugin.MegaPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * GrimAC 桥接模块
 * 自动检测 GrimAC，提供快捷管理命令，与 MegaPlugin 深度集成
 */
public class GrimBridgeModule extends MegaModule {

    private boolean grimLoaded = false;
    private Plugin grimPlugin = null;

    public GrimBridgeModule(MegaPlugin plugin) {
        super(plugin);
    }

    @Override
    public void onEnable() {
        registerListener();
        grimPlugin = Bukkit.getPluginManager().getPlugin("GrimAC");
        grimLoaded = grimPlugin != null && grimPlugin.isEnabled();

        if (grimLoaded) {
            plugin.getLogger().info("§a[GrimBridge] 检测到 GrimAC v" + grimPlugin.getDescription().getVersion() + "，已集成！");
        } else {
            plugin.getLogger().warning("[GrimBridge] 未检测到 GrimAC，请下载最新版放入 plugins/ 目录");
            plugin.getLogger().warning("[GrimBridge] 下载: https://modrinth.com/plugin/grimac");
        }

        // 注册桥接命令
        var cmd = plugin.getCommand("grimbridge");
        if (cmd != null) {
            cmd.setExecutor(new GrimBridgeCmd());
            cmd.setTabCompleter(new GrimBridgeTab());
        }
    }

    public boolean isGrimLoaded() {
        return grimLoaded;
    }

    public String getGrimVersion() {
        return grimLoaded ? grimPlugin.getDescription().getVersion() : "未检测到";
    }

    @Override
    public void onDisable() {
        grimLoaded = false;
        grimPlugin = null;
    }

    // ════════════════════════════════════════
    //  /gb 命令
    // ════════════════════════════════════════

    private class GrimBridgeCmd implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command c, String l, String[] args) {
            if (!sender.hasPermission("megaplugin.admin")) {
                sender.sendMessage(msg("no-permission"));
                return true;
            }

            if (!grimLoaded) {
                sender.sendMessage(msg("prefix") + " §cGrimAC 未加载！请下载 GrimAC 放入 plugins/ 目录");
                sender.sendMessage(msg("prefix") + " §7下载: §ehttps://modrinth.com/plugin/grimac");
                return true;
            }

            if (args.length == 0) {
                sender.sendMessage("§8§m           §r §c§lGrimAC 管理 §8§m           ");
                sender.sendMessage(" §7当前版本: §e" + grimPlugin.getDescription().getVersion());
                sender.sendMessage(" §7状态: §a运行中");
                sender.sendMessage(" §7命令:");
                sender.sendMessage(" §e/gb status  §7- 查看 Grim 运行状态");
                sender.sendMessage(" §e/gb reload  §7- 重载 Grim 配置");
                sender.sendMessage(" §e/gb alerts  §7- 开关作弊警报");
                sender.sendMessage(" §e/gb verbose §7- 开关详细警报");
                sender.sendMessage(" §e/gb info    §7- 查看玩家信息");
                sender.sendMessage("§8§m                                    ");
                return true;
            }

            if (!(sender instanceof Player p)) {
                sender.sendMessage(msg("prefix") + " §c此命令只能由玩家执行！");
                return true;
            }

            switch (args[0].toLowerCase()) {
                case "status" -> {
                    p.performCommand("grim");
                }
                case "reload" -> {
                    p.performCommand("grim reload");
                    p.sendMessage(msg("prefix") + " §7Grim 配置已重载");
                }
                case "alerts" -> {
                    p.performCommand("grim alerts");
                    p.sendMessage(msg("prefix") + " §7已切换 Grim 警报开关");
                }
                case "verbose" -> {
                    p.performCommand("grim verbose");
                    p.sendMessage(msg("prefix") + " §7已切换详细信息开关");
                }
                case "info" -> {
                    if (args.length < 2) {
                        p.sendMessage(msg("prefix") + " §c用法: /gb info <玩家>");
                        return true;
                    }
                    p.performCommand("grim " + args[1]);
                }
                default -> {
                    // 未知参数 → 转发给 /grim
                    String grimCmd = "grim " + String.join(" ", args);
                    p.performCommand(grimCmd);
                }
            }
            return true;
        }
    }

    private class GrimBridgeTab implements TabCompleter {
        @Override
        public List<String> onTabComplete(CommandSender sender, Command c, String a, String[] args) {
            if (!sender.hasPermission("megaplugin.admin")) return Collections.emptyList();
            if (args.length == 1) {
                return Arrays.asList("status", "reload", "alerts", "verbose", "info").stream()
                        .filter(s -> s.startsWith(args[0].toLowerCase()))
                        .collect(Collectors.toList());
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("info")) {
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            }
            return Collections.emptyList();
        }
    }
}
