package com.megaplugin.module;

import com.megaplugin.MegaPlugin;
import org.bukkit.event.Listener;

/**
 * Abstract base class for all MegaPlugin modules.
 * Named MegaModule to avoid conflict with java.lang.Module.
 */
public abstract class MegaModule implements Listener {

    protected final MegaPlugin plugin;

    public MegaModule(MegaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Called when the module is loaded. */
    public abstract void onEnable();

    /** Called when the module is disabled. */
    public void onDisable() {}

    /** Register this module as a Bukkit event listener. */
    protected void registerListener() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    /** Get a message from config with replacements. */
    protected String msg(String key) {
        String msg = plugin.getConfig().getString("messages." + key, key);
        return com.megaplugin.util.Color.colorize(
                msg.replace("{prefix}", plugin.getConfig().getString("messages.prefix", ""))
        );
    }

    protected String msg(String key, String... replacements) {
        String msg = msg(key);
        for (int i = 0; i < replacements.length; i += 2) {
            if (i + 1 < replacements.length) {
                msg = msg.replace(replacements[i], replacements[i + 1]);
            }
        }
        return msg;
    }
}
