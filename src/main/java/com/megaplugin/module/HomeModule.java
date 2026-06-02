package com.megaplugin.module;

import com.megaplugin.MegaPlugin;
import com.megaplugin.util.DataFile;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

public class HomeModule extends MegaModule {

    private DataFile dataFile;

    public HomeModule(MegaPlugin plugin) {
        super(plugin);
    }

    @Override
    public void onEnable() {
        dataFile = new DataFile(plugin, "homes.yml");
        registerListener();
        registerCommand("sethome", new SethomeCmd());
        registerCommand("home", new HomeCmd());
        registerCommand("delhome", new DelhomeCmd());
        registerCommand("homes", new HomesCmd());
    }

    @Override
    public void onDisable() {
        dataFile.save();
    }

    public Location getHome(Player player, String name) {
        String path = player.getUniqueId() + "." + name;
        return dataFile.getConfig().getLocation(path);
    }

    public Location getHome(UUID uuid, String name) {
        return dataFile.getConfig().getLocation(uuid + "." + name);
    }

    public void setHome(Player player, String name, Location loc) {
        dataFile.getConfig().set(player.getUniqueId() + "." + name, loc);
        dataFile.save();
    }

    public void delHome(Player player, String name) {
        dataFile.getConfig().set(player.getUniqueId() + "." + name, null);
        dataFile.save();
    }

    public List<String> getHomes(Player player) {
        var section = dataFile.getConfig().getConfigurationSection(player.getUniqueId().toString());
        if (section == null) return Collections.emptyList();
        return new ArrayList<>(section.getKeys(false));
    }

    public boolean hasHome(Player player, String name) {
        return dataFile.getConfig().contains(player.getUniqueId() + "." + name);
    }

    @SuppressWarnings("deprecation")
    public Player resolvePlayer(String name) {
        return plugin.getServer().getPlayer(name);
    }

    @SuppressWarnings("deprecation")
    public UUID resolveUuid(String name) {
        Player p = plugin.getServer().getPlayer(name);
        if (p != null) return p.getUniqueId();
        // Try offline player
        var offline = plugin.getServer().getOfflinePlayer(name);
        if (offline != null && offline.hasPlayedBefore()) {
            return offline.getUniqueId();
        }
        return null;
    }

    private void registerCommand(String name, CommandExecutor executor) {
        var cmd = plugin.getCommand(name);
        if (cmd != null) {
            cmd.setExecutor(executor);
            if (executor instanceof TabCompleter t) cmd.setTabCompleter(t);
        }
    }

    private class SethomeCmd implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (!(sender instanceof Player p)) { sender.sendMessage(msg("player-only")); return true; }
            if (!p.hasPermission("megaplugin.home")) { p.sendMessage(msg("no-permission")); return true; }
            if (args.length == 0) { p.sendMessage(msg("prefix") + " §c用法: /sethome <名字>"); return true; }
            String name = args[0].toLowerCase();

            // Count homes
            int max = p.hasPermission("megaplugin.home.other") ? 50 : 10;
            List<String> homes = getHomes(p);
            if (homes.size() >= max && !hasHome(p, name)) {
                p.sendMessage(msg("prefix") + " §c你最多只能设置 " + max + " 个家！");
                return true;
            }

            setHome(p, name, p.getLocation());
            p.sendMessage(msg("prefix") + " §a家 §e" + name + " §a设置成功！");
            return true;
        }
    }

    private class HomeCmd implements CommandExecutor, TabCompleter {
        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (!(sender instanceof Player p)) { sender.sendMessage(msg("player-only")); return true; }
            if (!p.hasPermission("megaplugin.home")) { p.sendMessage(msg("no-permission")); return true; }
            List<String> homes = getHomes(p);
            if (homes.isEmpty()) { p.sendMessage(msg("prefix") + " §c你还没有家！使用 /sethome <名字> 来设置"); return true; }
            String name = args.length > 0 ? args[0].toLowerCase() : "home";
            Location loc = getHome(p, name);
            if (loc == null) { p.sendMessage(msg("prefix") + " §c家 §e" + name + " §c不存在！"); return true; }
            p.teleport(loc);
            p.sendMessage(msg("prefix") + " §a已传送到家 §e" + name);
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
            if (args.length == 1 && sender instanceof Player p) {
                return getHomes(p).stream()
                        .filter(h -> h.startsWith(args[0].toLowerCase()))
                        .collect(Collectors.toList());
            }
            return Collections.emptyList();
        }
    }

    private class DelhomeCmd implements CommandExecutor, TabCompleter {
        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (!(sender instanceof Player p)) { sender.sendMessage(msg("player-only")); return true; }
            if (!p.hasPermission("megaplugin.home")) { p.sendMessage(msg("no-permission")); return true; }
            if (args.length == 0) { p.sendMessage(msg("prefix") + " §c用法: /delhome <名字>"); return true; }
            String name = args[0].toLowerCase();
            if (!hasHome(p, name)) { p.sendMessage(msg("prefix") + " §c家 §e" + name + " §c不存在！"); return true; }
            delHome(p, name);
            p.sendMessage(msg("prefix") + " §a家 §e" + name + " §a已删除！");
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
            if (args.length == 1 && sender instanceof Player p) {
                return getHomes(p).stream()
                        .filter(h -> h.startsWith(args[0].toLowerCase()))
                        .collect(Collectors.toList());
            }
            return Collections.emptyList();
        }
    }

    private class HomesCmd implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (!(sender instanceof Player p)) { sender.sendMessage(msg("player-only")); return true; }
            if (!p.hasPermission("megaplugin.home")) { p.sendMessage(msg("no-permission")); return true; }

            if (args.length > 0 && p.hasPermission("megaplugin.home.other")) {
                UUID uuid = resolveUuid(args[0]);
                if (uuid == null) { p.sendMessage(msg("player-not-found")); return true; }
                var section = dataFile.getConfig().getConfigurationSection(uuid.toString());
                if (section == null || section.getKeys(false).isEmpty()) {
                    p.sendMessage(msg("prefix") + " §7" + args[0] + " 没有家。");
                } else {
                    p.sendMessage(msg("prefix") + " §7" + args[0] + " 的家: §e" + String.join("§7, §e", section.getKeys(false)));
                }
                return true;
            }

            List<String> homes = getHomes(p);
            if (homes.isEmpty()) {
                p.sendMessage(msg("prefix") + " §7你还没有家！使用 /sethome <名字> 来设置");
            } else {
                p.sendMessage(msg("prefix") + " §7你的家: §e" + String.join("§7, §e", homes));
            }
            return true;
        }
    }
}
