package com.megaplugin.module;

import com.megaplugin.MegaPlugin;
import com.megaplugin.util.DataFile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

public class ChatModule extends MegaModule {

    private final DataFile nickData;
    private final Map<UUID, String> nicknames = new HashMap<>();

    public ChatModule(MegaPlugin plugin) {
        super(plugin);
        nickData = new DataFile(plugin, "nicknames.yml");
    }

    @Override
    public void onEnable() {
        registerListener();
        register("nick", new NickCmd());
        register("enderchest", new EnderchestCmd());
        register("whois", new WhoisCmd());
        register("broadcast", new BroadcastCmd());

        // Load nicknames
        for (String key : nickData.getConfig().getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                String nick = nickData.getConfig().getString(key);
                if (nick != null) nicknames.put(uuid, nick);
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void onDisable() {
        for (var entry : nicknames.entrySet()) {
            nickData.getConfig().set(entry.getKey().toString(), entry.getValue());
        }
        nickData.save();
    }

    public String getNickname(Player player) {
        return nicknames.get(player.getUniqueId());
    }

    public void setNickname(Player player, String nick) {
        nicknames.put(player.getUniqueId(), nick);
        player.displayName(net.kyori.adventure.text.Component.text(
                com.megaplugin.util.Color.colorize(nick)));
        player.playerListName(net.kyori.adventure.text.Component.text(
                com.megaplugin.util.Color.colorize(nick)));
    }

    public void removeNickname(Player player) {
        nicknames.remove(player.getUniqueId());
        player.displayName(null);
        player.playerListName(null);
    }

    @SuppressWarnings("deprecation")
    private void register(String name, CommandExecutor executor) {
        var cmd = plugin.getCommand(name);
        if (cmd != null) {
            cmd.setExecutor(executor);
            if (executor instanceof TabCompleter t) cmd.setTabCompleter(t);
        }
    }

    private class NickCmd implements CommandExecutor, TabCompleter {
        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (!sender.hasPermission("megaplugin.chat")) { sender.sendMessage(msg("no-permission")); return true; }

            if (args.length == 0) {
                sender.sendMessage(msg("prefix") + " §c用法: /nick <名字|reset> 或 /nick <玩家> <名字|reset>");
                return true;
            }

            // Admin mode: /nick <player> <name|reset>
            if (args.length >= 2 && sender.hasPermission("megaplugin.chat.admin")) {
                Player target = plugin.getServer().getPlayer(args[0]);
                if (target == null) { sender.sendMessage(msg("player-not-found")); return true; }
                handleNick(sender, target, args[1]);
                return true;
            }

            // Self mode
            if (!(sender instanceof Player p)) { sender.sendMessage(msg("player-only")); return true; }
            handleNick(sender, p, args[0]);
            return true;
        }

        private void handleNick(CommandSender sender, Player target, String nick) {
            if (nick.equalsIgnoreCase("reset") || nick.equalsIgnoreCase("off")) {
                removeNickname(target);
                target.sendMessage(msg("prefix") + " §a你的昵称已重置。");
                if (!target.equals(sender)) sender.sendMessage(msg("prefix") + " §a重置了 §e" + target.getName() + " §a的昵称。");
            } else {
                // Limit nickname length
                if (nick.length() > 32) {
                    sender.sendMessage(msg("prefix") + " §c昵称太长！最多32个字符。");
                    return;
                }
                setNickname(target, nick);
                target.sendMessage(msg("prefix") + " §a你的昵称现在是: §r" +
                        com.megaplugin.util.Color.colorize(nick));
                if (!target.equals(sender))
                    sender.sendMessage(msg("prefix") + " §a设置 §e" + target.getName() + " §a的昵称为: §r" +
                            com.megaplugin.util.Color.colorize(nick));
            }
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
            if (args.length == 1) {
                List<String> opts = new ArrayList<>();
                opts.add("reset");
                if (sender.hasPermission("megaplugin.chat.admin")) {
                    opts.addAll(plugin.getServer().getOnlinePlayers().stream()
                            .map(Player::getName)
                            .collect(Collectors.toList()));
                }
                return opts.stream()
                        .filter(n -> n.toLowerCase().startsWith(args[0].toLowerCase()))
                        .collect(Collectors.toList());
            }
            if (args.length == 2 && sender.hasPermission("megaplugin.chat.admin")) {
                return Arrays.asList("reset");
            }
            return Collections.emptyList();
        }
    }

    private class EnderchestCmd implements CommandExecutor, TabCompleter {
        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (!(sender instanceof Player p)) { sender.sendMessage(msg("player-only")); return true; }
            if (!p.hasPermission("megaplugin.chat")) { p.sendMessage(msg("no-permission")); return true; }

            if (args.length > 0 && p.hasPermission("megaplugin.chat.admin")) {
                Player target = plugin.getServer().getPlayer(args[0]);
                if (target == null) { p.sendMessage(msg("player-not-found")); return true; }
                p.openInventory(target.getEnderChest());
                p.sendMessage(msg("prefix") + " §a打开 §e" + target.getName() + " §a的末影箱。");
            } else {
                p.openInventory(p.getEnderChest());
            }
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
            if (args.length == 1 && sender.hasPermission("megaplugin.chat.admin")) {
                return plugin.getServer().getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(n -> n.toLowerCase().startsWith(args[0].toLowerCase()))
                        .collect(Collectors.toList());
            }
            return Collections.emptyList();
        }
    }

    private class WhoisCmd implements CommandExecutor, TabCompleter {
        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (!sender.hasPermission("megaplugin.chat")) { sender.sendMessage(msg("no-permission")); return true; }
            if (args.length == 0) { sender.sendMessage(msg("prefix") + " §cUsage: /whois <player>"); return true; }

            Player target = plugin.getServer().getPlayer(args[0]);
            if (target == null) {
                // Try offline
                OfflinePlayer off = Bukkit.getOfflinePlayer(args[0]);
                if (off == null || !off.hasPlayedBefore()) {
                    sender.sendMessage(msg("player-not-found")); return true;
                }
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
                sender.sendMessage(msg("prefix") + " §6=== §e" + off.getName() + " §7(离线) §6===");
                sender.sendMessage(" §7UUID: §f" + off.getUniqueId());
                if (off.getLastSeen() > 0) sender.sendMessage(" §7最后在线: §f" + sdf.format(new Date(off.getLastSeen())));
                if (off.getFirstPlayed() > 0) sender.sendMessage(" §7首次游玩: §f" + sdf.format(new Date(off.getFirstPlayed())));
                return true;
            }

            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
            sender.sendMessage(msg("prefix") + " §6=== §e" + target.getName() + " §6===");
            sender.sendMessage(" §7UUID: §f" + target.getUniqueId());
            sender.sendMessage(" §7生命值: §c" + String.format("%.1f", target.getHealth()) + "/" + target.getMaxHealth());
            sender.sendMessage(" §7饥饿值: §6" + target.getFoodLevel() + "/20");
            sender.sendMessage(" §7游戏模式: §b" + target.getGameMode().name());
            sender.sendMessage(" §7世界: §a" + target.getWorld().getName());
            Location loc = target.getLocation();
            sender.sendMessage(" §7位置: §f" + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ());
            sender.sendMessage(" §7IP: §7" + target.getAddress().getAddress().getHostAddress());
            if (sender.hasPermission("megaplugin.economy")) {
                double bal = plugin.getEconomyModule().getBalance(target);
                sender.sendMessage(" §7余额: §e" + plugin.getEconomyModule().getBalance(target));
            }
            sender.sendMessage(" §7游戏时长: §f" + (target.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20 / 60) + " 分钟");
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

    private class BroadcastCmd implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (!sender.hasPermission("megaplugin.chat.admin")) { sender.sendMessage(msg("no-permission")); return true; }
            if (args.length == 0) { sender.sendMessage(msg("prefix") + " §c用法: /broadcast <消息>"); return true; }

            String message = String.join(" ", args);
            var serializer = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand();

            // 全屏大号 Title 显示，持续 7 秒
            Component title = serializer.deserialize("&6&l" + message);
            String senderName = sender instanceof Player p ? p.getName() : "管理员";
            Component subtitle = serializer.deserialize("&7- &e" + senderName + " &7-");
            Title t = Title.title(title, subtitle,
                    Title.Times.times(Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(1)));

            for (Player p : Bukkit.getOnlinePlayers()) {
                p.showTitle(t);
            }

            // 同时在聊天栏发送备份
            Bukkit.broadcast(Component.text(""));
            Bukkit.broadcast(serializer.deserialize("&8&m           &r &6&l全服公告 &8&m           "));
            Bukkit.broadcast(Component.text(""));
            Bukkit.broadcast(serializer.deserialize("  &r&6&l" + message));
            Bukkit.broadcast(Component.text(""));
            Bukkit.broadcast(Component.text("§8§m                                    "));
            return true;
        }
    }
}
