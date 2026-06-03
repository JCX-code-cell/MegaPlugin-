package com.megaplugin.module;

import com.megaplugin.MegaPlugin;
import com.megaplugin.util.DataFile;
import org.bukkit.Location;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

/**
 * 出生点模块 — /spawn /setspawn
 */
public class SpawnModule extends MegaModule {

    private final DataFile data = new DataFile(plugin, "spawn.yml");

    public SpawnModule(MegaPlugin plugin) { super(plugin); }

    @Override
    public void onEnable() {
        cmd("spawn", new SpawnCmd());
        cmd("setspawn", new SetSpawnCmd());
    }

    @Override
    public void onDisable() {
        data.save();
        super.onDisable();
    }

    @SuppressWarnings("deprecation")
    private void cmd(String name, CommandExecutor exe) {
        var c = plugin.getCommand(name);
        if (c != null) c.setExecutor(exe);
    }

    class SpawnCmd implements CommandExecutor {
        public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!(s instanceof Player p)) { s.sendMessage(msg("player-only")); return true; }
            Location loc = data.getConfig().getLocation("spawn");
            if (loc == null) { p.sendMessage(msg("prefix") + " §c出生点未设置！"); return true; }
            p.teleport(loc);
            p.sendMessage(msg("prefix") + " §a已传送到出生点！");
            return true;
        }
    }

    class SetSpawnCmd implements CommandExecutor {
        public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!(s instanceof Player p)) { s.sendMessage(msg("player-only")); return true; }
            if (!p.hasPermission("megaplugin.spawn.set")) { p.sendMessage(msg("no-permission")); return true; }
            data.getConfig().set("spawn", p.getLocation());
            data.save();
            p.sendMessage(msg("prefix") + " §a出生点已设置！");
            return true;
        }
    }
}
