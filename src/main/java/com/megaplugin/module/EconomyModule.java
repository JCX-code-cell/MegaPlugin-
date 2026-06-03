package com.megaplugin.module;

import com.megaplugin.MegaPlugin;
import com.megaplugin.util.DataFile;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

public class EconomyModule extends MegaModule {

    private final DataFile dataFile;
    private final Map<UUID, Double> balances = new HashMap<>();

    public EconomyModule(MegaPlugin plugin) {
        super(plugin);
        dataFile = new DataFile(plugin, "economy.yml");
    }

    @Override
    public void onEnable() {
        registerListener();
        plugin.getCommand("balance").setExecutor(new BalanceCmd());
        plugin.getCommand("pay").setExecutor(new PayCmd());
        plugin.getCommand("eco").setExecutor(new EcoCmd());
        plugin.getCommand("baltop").setExecutor(new BaltopCmd());

        // Load balances
        for (String key : dataFile.getConfig().getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                balances.put(uuid, dataFile.getConfig().getDouble(key, 0.0));
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void onDisable() {
        for (var entry : balances.entrySet()) {
            dataFile.getConfig().set(entry.getKey().toString(), entry.getValue());
        }
        dataFile.save();
    }

    public double getBalance(Player player) {
        return balances.getOrDefault(player.getUniqueId(),
                plugin.getConfig().getDouble("economy.starting-balance", 100.0));
    }

    public double getBalance(UUID uuid) {
        return balances.getOrDefault(uuid, 0.0);
    }

    public void setBalance(Player player, double amount) {
        balances.put(player.getUniqueId(), Math.max(0, Math.round(amount * 100.0) / 100.0));
    }
    public void setBalance(UUID uuid, double amount) {
        balances.put(uuid, Math.max(0, Math.round(amount * 100.0) / 100.0));
    }

    public boolean hasEnough(Player player, double amount) {
        return getBalance(player) >= amount;
    }
    public boolean hasEnough(UUID uuid, double amount) {
        return getBalance(uuid) >= amount;
    }

    public boolean deposit(Player player, double amount) {
        double current = getBalance(player);
        setBalance(player, current + amount);
        return true;
    }
    public boolean deposit(UUID uuid, double amount) {
        balances.put(uuid, Math.max(0, Math.round(getBalance(uuid) + amount) * 100.0 / 100.0));
        return true;
    }

    public boolean withdraw(Player player, double amount) {
        if (!hasEnough(player, amount)) return false;
        setBalance(player, getBalance(player) - amount);
        return true;
    }
    public boolean withdraw(UUID uuid, double amount) {
        if (!hasEnough(uuid, amount)) return false;
        setBalance(uuid, getBalance(uuid) - amount);
        return true;
    }

    public boolean transfer(Player from, Player to, double amount) {
        if (!withdraw(from, amount)) return false;
        deposit(to, amount);
        return true;
    }

    private String formatMoney(double amount) {
        String symbol = plugin.getConfig().getString("economy.currency-symbol", "$");
        return symbol + String.format("%.2f", amount);
    }

    @SuppressWarnings("deprecation")
    private Player findPlayer(String name) {
        return plugin.getServer().getPlayer(name);
    }

    @SuppressWarnings("deprecation")
    private OfflinePlayer findOffline(String name) {
        OfflinePlayer off = plugin.getServer().getOfflinePlayer(name);
        if (off != null && off.hasPlayedBefore()) return off;
        return null;
    }

    private class BalanceCmd implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (!(sender instanceof Player p)) { sender.sendMessage(msg("player-only")); return true; }
            if (!p.hasPermission("megaplugin.economy")) { p.sendMessage(msg("no-permission")); return true; }
            if (args.length > 0 && p.hasPermission("megaplugin.economy.admin")) {
                OfflinePlayer target = findOffline(args[0]);
                if (target == null) { p.sendMessage(msg("player-not-found")); return true; }
                double bal = getBalance(target.getUniqueId());
                p.sendMessage(msg("prefix") + " §7" + (target.getName() != null ? target.getName() : args[0]) + " 的余额: §e" + formatMoney(bal));
                return true;
            }
            p.sendMessage(msg("prefix") + " §7余额: §e" + formatMoney(getBalance(p)));
            return true;
        }
    }

    private class PayCmd implements CommandExecutor, TabCompleter {
        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (!(sender instanceof Player p)) { sender.sendMessage(msg("player-only")); return true; }
            if (!p.hasPermission("megaplugin.economy")) { p.sendMessage(msg("no-permission")); return true; }
            if (args.length < 2) { p.sendMessage(msg("prefix") + " §c用法: /pay <玩家> <金额>"); return true; }

            Player target = findPlayer(args[0]);
            if (target == null) { p.sendMessage(msg("player-not-found")); return true; }
            if (target.equals(p)) { p.sendMessage(msg("prefix") + " §c你不能给自己转账！"); return true; }

            double amount;
            try { amount = Double.parseDouble(args[1]); } catch (NumberFormatException e) {
                p.sendMessage(msg("invalid-number")); return true;
            }
            if (amount <= 0) { p.sendMessage(msg("prefix") + " §c金额必须大于0！"); return true; }

            if (!transfer(p, target, amount)) {
                p.sendMessage(msg("prefix") + " §c余额不足！当前余额: " + formatMoney(getBalance(p)));
                return true;
            }
            p.sendMessage(msg("prefix") + " §a你向 §e" + target.getName() + " §a支付了 §e" + formatMoney(amount));
            target.sendMessage(msg("prefix") + " §a你收到 §e" + formatMoney(amount) + " §a来自 §e" + p.getName());
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
            if (args.length == 1) {
                Player p = sender instanceof Player pl ? pl : null;
                return plugin.getServer().getOnlinePlayers().stream()
                        .filter(pl -> !pl.equals(p))
                        .map(Player::getName)
                        .filter(n -> n.toLowerCase().startsWith(args[0].toLowerCase()))
                        .collect(Collectors.toList());
            }
            return Collections.emptyList();
        }
    }

    private class EcoCmd implements CommandExecutor, TabCompleter {
        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (!sender.hasPermission("megaplugin.economy.admin")) { sender.sendMessage(msg("no-permission")); return true; }
            if (args.length < 3) { sender.sendMessage(msg("prefix") + " §c用法: /eco <give|take|set> <玩家> <金额>"); return true; }

            OfflinePlayer target = findOffline(args[1]);
            if (target == null) { sender.sendMessage(msg("player-not-found")); return true; }

            double amount;
            try { amount = Double.parseDouble(args[2]); } catch (NumberFormatException e) {
                sender.sendMessage(msg("invalid-number")); return true;
            }

            String action = args[0].toLowerCase();
            UUID uuid = target.getUniqueId();
            String name = target.getName() != null ? target.getName() : args[1];

            switch (action) {
                case "give" -> {
                    balances.put(uuid, getBalance(uuid) + amount);
                    sender.sendMessage(msg("prefix") + " §a给予 " + name + " " + formatMoney(amount));
                }
                case "take" -> {
                    balances.put(uuid, Math.max(0, getBalance(uuid) - amount));
                    sender.sendMessage(msg("prefix") + " §a扣除 " + name + " " + formatMoney(amount));
                }
                case "set" -> {
                    balances.put(uuid, Math.max(0, amount));
                    sender.sendMessage(msg("prefix") + " §a设置 " + name + " 的余额为 " + formatMoney(amount));
                }
                default -> sender.sendMessage(msg("prefix") + " §c用法: /eco <give|take|set> <玩家> <金额>");
            }
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
            if (args.length == 1) return Arrays.asList("give", "take", "set");
            if (args.length == 2) {
                return plugin.getServer().getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            }
            return Collections.emptyList();
        }
    }

    private class BaltopCmd implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (!sender.hasPermission("megaplugin.economy")) { sender.sendMessage(msg("no-permission")); return true; }

            int page = 1;
            if (args.length > 0) {
                try { page = Integer.parseInt(args[0]); } catch (NumberFormatException ignored) {}
            }

            List<Map.Entry<UUID, Double>> sorted = balances.entrySet().stream()
                    .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed())
                    .collect(Collectors.toList());

            int perPage = 10;
            int totalPages = Math.max(1, (sorted.size() + perPage - 1) / perPage);
            page = Math.max(1, Math.min(page, totalPages));
            int start = (page - 1) * perPage;
            int end = Math.min(start + perPage, sorted.size());

            sender.sendMessage(msg("prefix") + " §6§l富豪榜 §7(第 " + page + "/" + totalPages + " 页)");
            for (int i = start; i < end; i++) {
                var entry = sorted.get(i);
                String name = Bukkit.getOfflinePlayer(entry.getKey()).getName();
                if (name == null) name = entry.getKey().toString().substring(0, 8);
                sender.sendMessage(" §e" + (i + 1) + ". §7" + name + " §f- §e" + formatMoney(entry.getValue()));
            }

            if (sorted.isEmpty()) {
                sender.sendMessage(msg("prefix") + " §7暂无余额记录。");
            }
            return true;
        }
    }
}
