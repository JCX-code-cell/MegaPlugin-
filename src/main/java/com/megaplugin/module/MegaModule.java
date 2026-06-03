package com.megaplugin.module;

import com.megaplugin.MegaPlugin;
import com.megaplugin.util.Color;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;

/**
 * 模块基类 — 统一生命周期、事件注册、消息管理。
 * 所有子模块只需实现 onEnable() 注册命令/监听器。
 * onDisable() 默认清理所有事件监听器。
 */
public abstract class MegaModule implements Listener {

    protected final MegaPlugin plugin;

    public MegaModule(MegaPlugin plugin) { this.plugin = plugin; }

    public abstract void onEnable();

    public void onDisable() {
        HandlerList.unregisterAll(this);
    }

    /** 向 Bukkit 注册本模块的事件监听器 */
    protected void listen() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    /** 从 config.yml 的 messages 段获取消息 */
    protected String msg(String key) {
        String prefix = plugin.getConfig().getString("messages.prefix", "");
        String msg = plugin.getConfig().getString("messages." + key, key);
        return Color.colorize(msg.replace("{prefix}", prefix));
    }

    /** 带键值对替换的消息 */
    protected String msg(String key, String... replacements) {
        String m = msg(key);
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            m = m.replace(replacements[i], replacements[i + 1]);
        }
        return m;
    }
}
