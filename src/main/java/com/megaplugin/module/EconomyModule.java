package com.megaplugin.module;

import com.megaplugin.MegaPlugin;
import com.megaplugin.util.DataFile;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 经济模块 — /bal /pay /eco /baltop
 * 内部以 long(分) 存储避免浮点精度问题，ConcurrentHashMap 线程安全。
 */
public class EconomyModule extends MegaModule {

    private final DataFile data = new DataFile(plugin, "economy.yml");
    private final Map<UUID, Long> balances = new ConcurrentHashMap<>(); // 余额单位: 分（1 元 = 100 分）
    private final Map<UUID, Long> payCooldowns = new ConcurrentHashMap<>(); // /pay 冷却 (ms)

    public EconomyModule(MegaPlugin plugin) { super(plugin); }

    @Override
    public void onEnable() {
        cmd("balance", new BalanceCmd());
        cmd("pay", new PayCmd());
        cmd("eco", new EcoCmd());
        cmd("baltop", new BaltopCmd());

        for (String k : data.getConfig().getKeys(false)) {
            try { balances.put(UUID.fromString(k), data.getConfig().getLong(k, 0)); }
            catch (Exception ignored) {}
        }

        // 定时自动保存 (每 5 分钟)
        Bukkit.getScheduler().runTaskTimer(plugin, this::saveData, 6000L, 6000L);
    }

    @Override
    public void onDisable() {
        saveData();
        super.onDisable();
    }

    private void saveData() {
        for (var e : balances.entrySet()) data.getConfig().set(e.getKey().toString(), e.getValue());
        data.save();
    }

    private void cmd(String name, CommandExecutor exe) {
        var c = plugin.getCommand(name);
        if (c != null) {
            c.setExecutor(exe);
            if (exe instanceof TabCompleter t) c.setTabCompleter(t);
        }
    }

    // ═══════ API (对外接口, 以元为单位) ═══════

    /** 获取余额(元) */
    public double bal(UUID id) {
        long cents = balances.getOrDefault(id, startingCents());
        return cents / 100.0;
    }

    /** 检查是否有足够的金额 */
    public boolean has(UUID id, double amount) {
        return cents(id) >= toCents(amount);
    }

    /** 存款 */
    public boolean deposit(UUID id, double amount) {
        if (amount <= 0) return false;
        balances.merge(id, toCents(amount), Long::sum);
        return true;
    }

    /** 取款 (余额不足返回 false) */
    public boolean withdraw(UUID id, double amount) {
        if (amount <= 0) return false;
        long c = toCents(amount), current = balances.getOrDefault(id, 0L);
        if (current < c) return false;
        balances.put(id, current - c);
        return true;
    }

    /** 转账 */
    public boolean transfer(UUID from, UUID to, double amount) {
        if (amount <= 0) return false;
        long c = toCents(amount);
        long current = balances.getOrDefault(from, 0L);
        if (current < c) return false;
        balances.put(from, current - c);
        balances.merge(to, c, Long::sum);
        return true;
    }

    // ═══════ 内部 ═══════

    private long cents(UUID id) { return balances.getOrDefault(id, startingCents()); }
    private long startingCents() { return toCents(plugin.getConfig().getDouble("economy.starting-balance", 100.0)); }

    /** 元 → 分, 四舍五入 */
    private static long toCents(double yuan) { return Math.round(yuan * 100.0); }

    /** 格式化显示 */
    private String fmt(double yuan) {
        return plugin.getConfig().getString("economy.currency-symbol", "$") + String.format("%.2f", yuan);
    }
    private String fmtCents(long cents) { return fmt(cents / 100.0); }

    @SuppressWarnings("deprecation")
    private Player player(String name) { return plugin.getServer().getPlayer(name); }
    @SuppressWarnings("deprecation")
    private OfflinePlayer offline(String name) {
        var off = plugin.getServer().getOfflinePlayer(name);
        return (off != null && off.hasPlayedBefore()) ? off : null;
    }

    // ── 命令 ──
    class BalanceCmd implements CommandExecutor {
        public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!(s instanceof Player p)) { s.sendMessage(msg("player-only")); return true; }
            if (!p.hasPermission("megaplugin.economy")) { p.sendMessage(msg("no-permission")); return true; }
            if (a.length > 0 && p.hasPermission("megaplugin.economy.admin")) {
                var t = offline(a[0]);
                if (t == null) { p.sendMessage(msg("player-not-found")); return true; }
                p.sendMessage(msg("prefix") + " §7" + (t.getName() != null ? t.getName() : a[0]) + " 余额: §e" + fmt(bal(t.getUniqueId())));
                return true;
            }
            p.sendMessage(msg("prefix") + " §7余额: §e" + fmt(bal(p.getUniqueId())));
            return true;
        }
    }

    class PayCmd implements CommandExecutor, TabCompleter {
        public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!(s instanceof Player p)) { s.sendMessage(msg("player-only")); return true; }
            if (!p.hasPermission("megaplugin.economy")) { p.sendMessage(msg("no-permission")); return true; }
            if (a.length < 2) { p.sendMessage(msg("prefix") + " §c用法: /pay <玩家> <金额>"); return true; }

            // 频率限制 (默认 2 秒)
            long cdMs = plugin.getConfig().getLong("economy.pay-cooldown", 2) * 1000L;
            long now = System.currentTimeMillis();
            Long last = payCooldowns.get(p.getUniqueId());
            if (last != null && now - last < cdMs) {
                long remain = (cdMs - (now - last)) / 1000;
                p.sendMessage(msg("prefix") + " §c操作太快！请等待 §e" + remain + "秒");
                return true;
            }

            Player t = player(a[0]);
            if (t == null || t == p) { p.sendMessage(msg("prefix") + " §c目标无效！"); return true; }
            double amount;
            try { amount = Double.parseDouble(a[1]); } catch (Exception ex) { p.sendMessage(msg("invalid-number")); return true; }
            if (amount <= 0) { p.sendMessage(msg("prefix") + " §c金额必须大于 0！"); return true; }
            if (!transfer(p.getUniqueId(), t.getUniqueId(), amount)) {
                p.sendMessage(msg("prefix") + " §c余额不足！当前: " + fmt(bal(p.getUniqueId())));
                return true;
            }
            payCooldowns.put(p.getUniqueId(), now);
            p.sendMessage(msg("prefix") + " §a已向 §e" + t.getName() + " §a支付 §e" + fmt(amount));
            t.sendMessage(msg("prefix") + " §a收到 §e" + fmt(amount) + " §a来自 §e" + p.getName());
            return true;
        }
        public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
            if (a.length == 1) return plugin.getServer().getOnlinePlayers().stream()
                    .filter(pl -> !pl.equals(s)).map(Player::getName)
                    .filter(n -> n.toLowerCase().startsWith(a[0].toLowerCase())).toList();
            return List.of();
        }
    }

    class EcoCmd implements CommandExecutor, TabCompleter {
        public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!s.hasPermission("megaplugin.economy.admin")) { s.sendMessage(msg("no-permission")); return true; }
            if (a.length < 3) { s.sendMessage(msg("prefix") + " §c用法: /eco <give|take|set> <玩家> <金额>"); return true; }
            var t = offline(a[1]);
            if (t == null) { s.sendMessage(msg("player-not-found")); return true; }
            double amount;
            try { amount = Double.parseDouble(a[2]); } catch (Exception ex) { s.sendMessage(msg("invalid-number")); return true; }
            if (amount <= 0 && !a[0].equalsIgnoreCase("set")) { s.sendMessage(msg("prefix") + " §c金额必须大于 0！"); return true; }
            UUID id = t.getUniqueId();
            String name = t.getName() != null ? t.getName() : a[1];
            switch (a[0].toLowerCase()) {
                case "give" -> { deposit(id, amount); s.sendMessage(msg("prefix") + " §a给予 " + name + " " + fmt(amount)); }
                case "take" -> { balances.put(id, Math.max(0, cents(id) - toCents(amount))); s.sendMessage(msg("prefix") + " §a扣除 " + name + " " + fmt(amount)); }
                case "set" -> { balances.put(id, Math.max(0, toCents(amount))); s.sendMessage(msg("prefix") + " §a设置 " + name + " 余额为 " + fmt(amount)); }
                default -> s.sendMessage(msg("prefix") + " §c用法: /eco <give|take|set> <玩家> <金额>");
            }
            return true;
        }
        public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
            if (a.length == 1) return List.of("give", "take", "set");
            if (a.length == 2) return plugin.getServer().getOnlinePlayers().stream()
                    .map(Player::getName).filter(n -> n.toLowerCase().startsWith(a[1].toLowerCase())).toList();
            return List.of();
        }
    }

    class BaltopCmd implements CommandExecutor {
        public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!s.hasPermission("megaplugin.economy")) { s.sendMessage(msg("no-permission")); return true; }
            int page = 1;
            if (a.length > 0) try { page = Integer.parseInt(a[0]); } catch (Exception ignored) {}

            var sorted = balances.entrySet().stream()
                    .sorted(Map.Entry.<UUID, Long>comparingByValue().reversed())
                    .collect(Collectors.toList());
            int pp = 10, tp = Math.max(1, (sorted.size() + pp - 1) / pp);
            page = Math.max(1, Math.min(page, tp));
            int start = (page - 1) * pp, end = Math.min(start + pp, sorted.size());

            s.sendMessage(msg("prefix") + " §6§l富豪榜 §7(第" + page + "/" + tp + "页)");
            for (int i = start; i < end; i++) {
                var e = sorted.get(i);
                String name = Bukkit.getOfflinePlayer(e.getKey()).getName();
                if (name == null) name = e.getKey().toString().substring(0, 8);
                s.sendMessage(" §e" + (i + 1) + ". §7" + name + " §f- §e" + fmtCents(e.getValue()));
            }
            if (sorted.isEmpty()) s.sendMessage(msg("prefix") + " §7暂无记录。");
            return true;
        }
    }
}
