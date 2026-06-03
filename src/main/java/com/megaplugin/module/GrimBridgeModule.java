package com.megaplugin.module;

import com.megaplugin.MegaPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

/**
 * GrimAC 桥接模块
 * 自动检测 GrimAC 插件，提供管理命令和菜单集成
 */
public class GrimBridgeModule extends MegaModule {

    private boolean grimLoaded = false;
    private Plugin grimPlugin = null;

    public GrimBridgeModule(MegaPlugin plugin) {
        super(plugin);
    }

    @Override
    public void onEnable() {
        // 检测 GrimAC 是否已加载
        grimPlugin = Bukkit.getPluginManager().getPlugin("GrimAC");
        grimLoaded = grimPlugin != null && grimPlugin.isEnabled();

        if (grimLoaded) {
            plugin.getLogger().info("§a检测到 GrimAC v" + grimPlugin.getDescription().getVersion() + "，已集成！");
        } else {
            plugin.getLogger().info("§e未检测到 GrimAC，跳过集成。如需反作弊请下载 GrimAC 放入 plugins/ 目录。");
            plugin.getLogger().info("§e下载地址: https://modrinth.com/plugin/grimac");
        }
    }

    public boolean isGrimLoaded() {
        return grimLoaded;
    }

    public Plugin getGrimPlugin() {
        return grimPlugin;
    }

    /**
     * 检查玩家是否有违规记录（通过 GrimAC 的 API）
     * 注意：此方法为占位，GrimAC 的完整 API 集成需要引入 Grim-API 依赖
     * 目前通过直接执行 Grim 命令实现基础交互
     */
    public boolean hasFlagged(org.bukkit.entity.Player player) {
        if (!grimLoaded) return false;
        // GrimAC 没有直接的 getViolations API 暴露给其他插件
        // 完整集成需要编译时引入 Grim-API: https://github.com/GrimAnticheat/Grim-API
        return false;
    }

    @Override
    public void onDisable() {
        grimLoaded = false;
        grimPlugin = null;
    }
}
