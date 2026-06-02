package com.megaplugin.module;

import com.megaplugin.MegaPlugin;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

public class AdminModule extends MegaModule {

    public AdminModule(MegaPlugin plugin) {
        super(plugin);
    }

    @Override
    public void onEnable() {
        registerListener();
        register("gmc", new GamemodeCmd(GameMode.CREATIVE));
        register("gms", new GamemodeCmd(GameMode.SURVIVAL));
        register("gma", new GamemodeCmd(GameMode.ADVENTURE));
        register("gmsp", new GamemodeCmd(GameMode.SPECTATOR));
        register("fly", new FlyCmd());
        register("god", new GodCmd());
        register("heal", new HealCmd());
        register("feed", new FeedCmd());
        register("vanish", new VanishCmd());
        register("invsee", new InvseeCmd());
    }

    @SuppressWarnings("deprecation")
    private void register(String name, CommandExecutor executor) {
        var cmd = plugin.getCommand(name);
        if (cmd != null) {
            cmd.setExecutor(executor);
            if (executor instanceof TabCompleter t) cmd.setTabCompleter(t);
        }
    }

    private class GamemodeCmd implements CommandExecutor, TabCompleter {
        private final GameMode mode;

        GamemodeCmd(GameMode mode) { this.mode = mode; }

        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (!sender.hasPermission("megaplugin.admin")) { sender.sendMessage(msg("no-permission")); return true; }

            if (args.length > 0) {
                Player target = plugin.getServer().getPlayer(args[0]);
                if (target == null) { sender.sendMessage(msg("player-not-found")); return true; }
                target.setGameMode(mode);
                target.sendMessage(msg("prefix") + " §a游戏模式已设置为 §e" + mode.name());
                sender.sendMessage(msg("prefix") + " §a将 §e" + target.getName() + " §a的游戏模式设置为 §e" + mode.name());
            } else {
                if (!(sender instanceof Player p)) { sender.sendMessage(msg("player-only")); return true; }
                p.setGameMode(mode);
                p.sendMessage(msg("prefix") + " §a游戏模式已设置为 §e" + mode.name());
            }
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
            if (args.length == 1) {
                return plugin.getServer().getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(n -> n.toLowerCase().startsWith(args[0].toLowerCase()))
                        .collect(Collectors.toList());
            }
            return Collections.emptyList();
        }
    }

    private class FlyCmd implements CommandExecutor, TabCompleter {
        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (!sender.hasPermission("megaplugin.admin")) { sender.sendMessage(msg("no-permission")); return true; }

            if (args.length > 0) {
                Player target = plugin.getServer().getPlayer(args[0]);
                if (target == null) { sender.sendMessage(msg("player-not-found")); return true; }
                target.setAllowFlight(!target.getAllowFlight());
                String status = target.getAllowFlight() ? "§a已开启" : "§c已关闭";
                target.sendMessage(msg("prefix") + " §a飞行模式 " + status);
                sender.sendMessage(msg("prefix") + " §a飞行模式 " + status + " §a对 §e" + target.getName());
            } else {
                if (!(sender instanceof Player p)) { sender.sendMessage(msg("player-only")); return true; }
                p.setAllowFlight(!p.getAllowFlight());
                String status = p.getAllowFlight() ? "§a已开启" : "§c已关闭";
                p.sendMessage(msg("prefix") + " §a飞行模式 " + status);
            }
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
            if (args.length == 1) return getPlayerNames(args[0]);
            return Collections.emptyList();
        }
    }

    private class GodCmd implements CommandExecutor, TabCompleter {
        private final Set<UUID> godded = new HashSet<>();

        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (!sender.hasPermission("megaplugin.admin")) { sender.sendMessage(msg("no-permission")); return true; }

            if (args.length > 0) {
                Player target = plugin.getServer().getPlayer(args[0]);
                if (target == null) { sender.sendMessage(msg("player-not-found")); return true; }
                toggleGod(target, sender);
            } else {
                if (!(sender instanceof Player p)) { sender.sendMessage(msg("player-only")); return true; }
                toggleGod(p, sender);
            }
            return true;
        }

        private void toggleGod(Player target, CommandSender sender) {
            if (godded.contains(target.getUniqueId())) {
                godded.remove(target.getUniqueId());
                target.setInvulnerable(false);
                target.sendMessage(msg("prefix") + " §c无敌模式已关闭！");
                if (!target.equals(sender)) sender.sendMessage(msg("prefix") + " §c已关闭 §e" + target.getName() + " §c的无敌模式");
            } else {
                godded.add(target.getUniqueId());
                target.setInvulnerable(true);
                target.sendMessage(msg("prefix") + " §a无敌模式已开启！");
                if (!target.equals(sender)) sender.sendMessage(msg("prefix") + " §a已开启 §e" + target.getName() + " §a的无敌模式");
            }
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
            if (args.length == 1) return getPlayerNames(args[0]);
            return Collections.emptyList();
        }
    }

    private class HealCmd implements CommandExecutor, TabCompleter {
        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (!sender.hasPermission("megaplugin.admin")) { sender.sendMessage(msg("no-permission")); return true; }

            if (args.length > 0) {
                Player target = plugin.getServer().getPlayer(args[0]);
                if (target == null) { sender.sendMessage(msg("player-not-found")); return true; }
                target.setHealth(target.getMaxHealth());
                target.sendMessage(msg("prefix") + " §a你已被治愈！");
                sender.sendMessage(msg("prefix") + " §a治愈了 §e" + target.getName());
            } else {
                if (!(sender instanceof Player p)) { sender.sendMessage(msg("player-only")); return true; }
                p.setHealth(p.getMaxHealth());
                p.sendMessage(msg("prefix") + " §a你已被治愈！");
            }
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
            if (args.length == 1) return getPlayerNames(args[0]);
            return Collections.emptyList();
        }
    }

    private class FeedCmd implements CommandExecutor, TabCompleter {
        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (!sender.hasPermission("megaplugin.admin")) { sender.sendMessage(msg("no-permission")); return true; }

            if (args.length > 0) {
                Player target = plugin.getServer().getPlayer(args[0]);
                if (target == null) { sender.sendMessage(msg("player-not-found")); return true; }
                target.setFoodLevel(20);
                target.setSaturation(20);
                target.sendMessage(msg("prefix") + " §a你已被喂饱！");
                sender.sendMessage(msg("prefix") + " §a喂饱了 §e" + target.getName());
            } else {
                if (!(sender instanceof Player p)) { sender.sendMessage(msg("player-only")); return true; }
                p.setFoodLevel(20);
                p.setSaturation(20);
                p.sendMessage(msg("prefix") + " §a你已被喂饱！");
            }
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
            if (args.length == 1) return getPlayerNames(args[0]);
            return Collections.emptyList();
        }
    }

    private class VanishCmd implements CommandExecutor, TabCompleter {
        private final Set<UUID> vanished = new HashSet<>();

        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (!sender.hasPermission("megaplugin.admin")) { sender.sendMessage(msg("no-permission")); return true; }

            if (args.length > 0) {
                Player target = plugin.getServer().getPlayer(args[0]);
                if (target == null) { sender.sendMessage(msg("player-not-found")); return true; }
                toggleVanish(target, sender);
            } else {
                if (!(sender instanceof Player p)) { sender.sendMessage(msg("player-only")); return true; }
                toggleVanish(p, sender);
            }
            return true;
        }

        private void toggleVanish(Player target, CommandSender sender) {
            String name = target.getName();
            if (vanished.contains(target.getUniqueId())) {
                vanished.remove(target.getUniqueId());
                for (Player online : Bukkit.getOnlinePlayers()) online.showPlayer(plugin, target);
                target.sendMessage(msg("prefix") + " §7你现在可见！");
                if (!target.equals(sender)) sender.sendMessage(msg("prefix") + " §7" + name + " 现在可见。");
            } else {
                vanished.add(target.getUniqueId());
                for (Player online : Bukkit.getOnlinePlayers()) {
                    if (!online.hasPermission("megaplugin.admin")) online.hidePlayer(plugin, target);
                }
                target.sendMessage(msg("prefix") + " §7你现在隐身了！");
                if (!target.equals(sender)) sender.sendMessage(msg("prefix") + " §7" + name + " 现在隐身了。");
            }
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
            if (args.length == 1) return getPlayerNames(args[0]);
            return Collections.emptyList();
        }
    }

    private class InvseeCmd implements CommandExecutor, TabCompleter {
        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (!(sender instanceof Player p)) { sender.sendMessage(msg("player-only")); return true; }
            if (!p.hasPermission("megaplugin.admin")) { p.sendMessage(msg("no-permission")); return true; }
            if (args.length == 0) { p.sendMessage(msg("prefix") + " §c用法: /invsee <玩家>"); return true; }
            Player target = plugin.getServer().getPlayer(args[0]);
            if (target == null) { p.sendMessage(msg("player-not-found")); return true; }
            p.openInventory(target.getInventory());
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
            if (args.length == 1) return getPlayerNames(args[0]);
            return Collections.emptyList();
        }
    }

    private List<String> getPlayerNames(String prefix) {
        return plugin.getServer().getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(n -> n.toLowerCase().startsWith(prefix.toLowerCase()))
                .collect(Collectors.toList());
    }
}
