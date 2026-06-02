package com.megaplugin.module;

import com.megaplugin.MegaPlugin;
import com.megaplugin.util.DataFile;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class SpawnModule extends MegaModule {

    private Location spawnLocation;
    private final DataFile dataFile;

    public SpawnModule(MegaPlugin plugin) {
        super(plugin);
        dataFile = new DataFile(plugin, "spawn.yml");
    }

    @Override
    public void onEnable() {
        registerListener();
        plugin.getCommand("spawn").setExecutor(new SpawnCmd());
        plugin.getCommand("setspawn").setExecutor(new SetspawnCmd());

        // Load saved spawn
        spawnLocation = dataFile.getConfig().getLocation("spawn");
    }

    public Location getSpawn() {
        if (spawnLocation == null) {
            return plugin.getServer().getWorlds().get(0).getSpawnLocation();
        }
        return spawnLocation;
    }

    private class SpawnCmd implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (!(sender instanceof Player p)) { sender.sendMessage(msg("player-only")); return true; }
            if (!p.hasPermission("megaplugin.spawn")) { p.sendMessage(msg("no-permission")); return true; }
            p.teleport(getSpawn());
            p.sendMessage(msg("prefix") + " §a已传送到出生点！");
            return true;
        }
    }

    private class SetspawnCmd implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (!(sender instanceof Player p)) { sender.sendMessage(msg("player-only")); return true; }
            if (!p.hasPermission("megaplugin.spawn.set")) { p.sendMessage(msg("no-permission")); return true; }
            spawnLocation = p.getLocation();
            dataFile.getConfig().set("spawn", spawnLocation);
            dataFile.save();
            p.sendMessage(msg("prefix") + " §a出生点已设置！");
            return true;
        }
    }
}
