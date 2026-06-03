package com.megaplugin.module;

import com.megaplugin.MegaPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.*;
import java.util.stream.Collectors;

public class AntiCheatModule extends MegaModule {

    private boolean enabled = true;

    // ── 检测阈值 ──
    private static final double MAX_SPEED = 0.55;      // 最大水平速度 (tick)
    private static final double MAX_REACH = 4.0;        // 最大攻击距离
    private static final int MAX_CPS = 15;               // 最大每秒点击
    private static final int MAX_AIR_TICKS = 8;          // 最大空中 tick
    private static final int MAX_VIOLATIONS = 40;

    // ── 数据 ──
    private final Map<UUID, Map<String, Integer>> violations = new HashMap<>();
    private final Map<UUID, long[]> clickTimes = new HashMap<>();
    private final Map<UUID, Long> lastHitTime = new HashMap<>();
    private final Map<UUID, String> lastHitEntity = new HashMap<>();
    private final Map<UUID, Integer> airTicks = new HashMap<>();
    private final Map<UUID, Location> lastMove = new HashMap<>();
    private final Map<UUID, Double> lastMoveDelta = new HashMap<>();

    public AntiCheatModule(MegaPlugin plugin) {
        super(plugin);
    }

    @Override
    public void onEnable() {
        registerListener();
        var cmd = plugin.getCommand("anticheat");
        if (cmd != null) {
            cmd.setExecutor(new ACheatCmd());
            cmd.setTabCompleter(new ACheatTab());
        }
    }

    private int addViolation(Player p, String type) {
        Map<String, Integer> pv = violations.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>());
        int v = pv.merge(type, 1, Integer::sum);
        if (v == 1 || v % 5 == 0) {
            alertAdmins(p, type, v);
        }
        if (v >= MAX_VIOLATIONS) {
            p.kick(Component.text("§c检测到作弊行为 §e" + type + " §c(违规 " + v + " 次)\n§7请在交流群反馈误报。", NamedTextColor.RED));
            Bukkit.getConsoleSender().sendMessage(com.megaplugin.util.Color.colorize(
                    msg("prefix") + " §c已踢出 §e" + p.getName() + " §c违规 §e" + type + " §c次数 §e" + v));
        }
        return v;
    }

    private void alertAdmins(Player target, String type, int vl) {
        String msg = "§8[§c§l反作弊§8] §e" + target.getName() + " §7可能使用 §c" + type + " §7(违规 x" + vl + ")";
        Component comp = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand()
                .deserialize(msg);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("megaplugin.admin")) {
                p.sendMessage(comp);
            }
        }
        Bukkit.getConsoleSender().sendMessage(com.megaplugin.util.Color.colorize(msg));
    }

    /** 关键修复：只有真正有权限飞行的才跳过，飞行作弊不跳过 */
    private boolean shouldSkip(Player p) {
        if (!enabled) return true;
        if (p.hasPermission("megaplugin.anticheat.bypass")) return true;
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return true;
        // ⚠ 修复前: p.isFlying() && p.getAllowFlight()  → 飞行挂直接跳过
        // ⚠ 修复后: 只有服务器认可的飞行权限才跳过
        if (p.getAllowFlight() && p.isFlying()) return true; // 开了 /fly 的管理员
        return false;
    }

    // ════════════════════════════════════════
    //  1. 飞行 + 速度检测
    // ════════════════════════════════════════

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (shouldSkip(p)) {
            airTicks.remove(p.getUniqueId());
            lastMove.remove(p.getUniqueId());
            return;
        }

        Location from = e.getFrom();
        Location to = e.getTo();

        // ── 飞行检测 ──
        boolean onGround = p.isOnGround();
        boolean reallyNearGround = onGround
                || p.getLocation().subtract(0, 0.1, 0).getBlock().getType() != Material.AIR
                || p.isInWater() || p.isClimbing();

        int at = airTicks.getOrDefault(p.getUniqueId(), 0);
        if (reallyNearGround) {
            airTicks.put(p.getUniqueId(), 0);
        } else {
            at++;
            airTicks.put(p.getUniqueId(), at);

            // 空中超过 8tick(0.4秒) 且下降太慢 → 飞行
            if (at > MAX_AIR_TICKS) {
                double deltaY = to.getY() - from.getY();
                if (deltaY > -0.3) { // 正常下落应该 < -0.3
                    e.setCancelled(true);
                    addViolation(p, "飞行");
                }
            }
        }

        // ── 速度检测（不论地面还是空中）──
        Location last = lastMove.get(p.getUniqueId());
        if (last != null) {
            double dx = to.getX() - last.getX();
            double dz = to.getZ() - last.getZ();
            double speed = Math.sqrt(dx * dx + dz * dz);
            // 地面最高约 0.28，疾跑约 0.35，这里限制 0.55
            if (speed > MAX_SPEED) {
                e.setCancelled(true);
                addViolation(p, "速度");
            }
        }
        lastMove.put(p.getUniqueId(), to.clone());
    }

    // ════════════════════════════════════════
    //  2. 攻击距离 + 杀戮光环检测
    // ════════════════════════════════════════

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        if (shouldSkip(p)) return;

        long now = System.currentTimeMillis();

        // ── 攻击距离（对所有实体包括怪物）──
        double dist = p.getLocation().distance(e.getEntity().getLocation());
        if (dist > MAX_REACH) {
            e.setCancelled(true);
            addViolation(p, "距离");
            return;
        }

        // ── 杀戮光环 / 多实体 ──
        String entityKey = e.getEntity().getUniqueId().toString();
        Long lastHit = lastHitTime.get(p.getUniqueId());
        String lastEntity = lastHitEntity.get(p.getUniqueId());
        if (lastHit != null && now - lastHit < 50) {
            if (lastEntity != null && !lastEntity.equals(entityKey)) {
                e.setCancelled(true);
                addViolation(p, "杀戮");
                return;
            }
        }
        lastHitTime.put(p.getUniqueId(), now);
        lastHitEntity.put(p.getUniqueId(), entityKey);
    }

    // ════════════════════════════════════════
    //  3. 连点检测
    // ════════════════════════════════════════

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAttackCps(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        if (shouldSkip(p)) return;

        long now = System.currentTimeMillis();
        long[] times = clickTimes.computeIfAbsent(p.getUniqueId(), k -> new long[20]);
        System.arraycopy(times, 0, times, 1, times.length - 1);
        times[0] = now;

        int cps = 0;
        for (long t : times) {
            if (now - t <= 1000) cps++;
        }
        if (cps > MAX_CPS) {
            e.setCancelled(true);
            addViolation(p, "连点");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        violations.remove(id);
        clickTimes.remove(id);
        lastHitTime.remove(id);
        lastHitEntity.remove(id);
        airTicks.remove(id);
        lastMove.remove(id);
    }

    // ════════════════════════════════════════
    //  /ac 命令
    // ════════════════════════════════════════

    private class ACheatCmd implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (!sender.hasPermission("megaplugin.admin")) { sender.sendMessage(msg("no-permission")); return true; }

            if (args.length == 0) {
                sender.sendMessage("§8§m           §r §c§l反作弊管理 §8§m           ");
                sender.sendMessage(" §7状态: " + (enabled ? "§a已启用" : "§c已禁用"));
                sender.sendMessage(" §7命令:");
                sender.sendMessage(" §e/ac toggle §7- 开关反作弊");
                sender.sendMessage(" §e/ac status §7- 查看状态");
                sender.sendMessage(" §e/ac reset [玩家] §7- 重置违规");
                sender.sendMessage(" §e/ac check <玩家> §7- 查看违规");
                sender.sendMessage("§8§m                                    ");
                return true;
            }

            switch (args[0].toLowerCase()) {
                case "toggle" -> { enabled = !enabled; sender.sendMessage(msg("prefix") + " §7反作弊 " + (enabled ? "§a已启用" : "§c已禁用")); }
                case "status" -> sender.sendMessage(msg("prefix") + " §7状态: " + (enabled ? "§a启用" : "§c禁用") + " §7| 监控: §e" + airTicks.size() + " §7| 违规: §e" + violations.size());
                case "reset" -> {
                    if (args.length >= 2) {
                        Player t = Bukkit.getPlayer(args[1]);
                        if (t != null) { violations.remove(t.getUniqueId()); clickTimes.remove(t.getUniqueId()); airTicks.remove(t.getUniqueId()); sender.sendMessage(msg("prefix") + " §a已重置 §e" + t.getName()); }
                        else { sender.sendMessage(msg("player-not-found")); }
                    } else { violations.clear(); clickTimes.clear(); airTicks.clear(); sender.sendMessage(msg("prefix") + " §a已重置全部"); }
                }
                case "check" -> {
                    if (args.length < 2) { sender.sendMessage(msg("prefix") + " §c用法: /ac check <玩家>"); return true; }
                    Player t = Bukkit.getPlayer(args[1]);
                    if (t == null) { sender.sendMessage(msg("player-not-found")); return true; }
                    Map<String, Integer> pv = violations.get(t.getUniqueId());
                    sender.sendMessage("§8[§c§l反作弊§8] §e" + t.getName() + ": " + (pv == null || pv.isEmpty() ? "§7无违规" : ""));
                    if (pv != null) for (var en : pv.entrySet()) sender.sendMessage(" §c" + en.getKey() + " x" + en.getValue());
                }
                default -> sender.sendMessage(msg("prefix") + " §c未知参数");
            }
            return true;
        }
    }

    private class ACheatTab implements TabCompleter {
        @Override
        public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
            if (!sender.hasPermission("megaplugin.admin")) return Collections.emptyList();
            if (args.length == 1) return Arrays.asList("toggle", "status", "reset", "check").stream().filter(s -> s.startsWith(args[0].toLowerCase())).collect(Collectors.toList());
            if (args.length == 2 && (args[0].equalsIgnoreCase("reset") || args[0].equalsIgnoreCase("check"))) return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase())).collect(Collectors.toList());
            return Collections.emptyList();
        }
    }
}
