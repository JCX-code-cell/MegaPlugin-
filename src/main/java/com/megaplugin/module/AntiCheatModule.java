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
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

public class AntiCheatModule extends MegaModule {

    // ── 配置 ──
    private boolean enabled = true;
    private final double MAX_SPEED = 0.7;       // 最大水平速度（方块/ tick）
    private final double MAX_REACH = 4.5;        // 最大攻击距离（方块）
    private final int MAX_CPS = 18;              // 最大每秒点击次数
    private final int MAX_VIOLATIONS_BEFORE_KICK = 40;

    // ── 违规记录 ──
    private final Map<UUID, Map<String, Integer>> violations = new HashMap<>();
    // ── 自动点击检测 ──
    private final Map<UUID, long[]> clickTimes = new HashMap<>();
    // ── KillAura 检测 ──
    private final Map<UUID, Long> lastHitTime = new HashMap<>();
    private final Map<UUID, String> lastHitEntity = new HashMap<>();
    // ── 飞行检测 ──
    private final Map<UUID, Integer> airTicks = new HashMap<>();
    private final Map<UUID, Double> lastY = new HashMap<>();
    // ── 速度检测 ──
    private final Map<UUID, Location> lastMove = new HashMap<>();

    private static final List<String> ALERT_TYPES = Arrays.asList("飞行", "速度", "距离", "连点", "杀戮");

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

    // ── 工具方法 ──

    private int addViolation(Player p, String type) {
        Map<String, Integer> pv = violations.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>());
        int v = pv.merge(type, 1, Integer::sum);
        String display = type;
        if (v == 1 || v % 5 == 0) {
            alertAdmins(p, display, v);
        }
        if (v >= MAX_VIOLATIONS_BEFORE_KICK) {
            p.kick(Component.text("§c检测到作弊行为 §e" + display + " §c(违规 " + v + " 次)\n§7请在交流群反馈误报。", NamedTextColor.RED));
            Bukkit.getConsoleSender().sendMessage(com.megaplugin.util.Color.colorize(
                    msg("prefix") + " §c已踢出 §e" + p.getName() + " §c违规 §e" + display + " §c次数 §e" + v));
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

    /** Check if anti-cheat should skip this player */
    private boolean shouldSkip(Player p) {
        if (!enabled) return true;
        if (p.hasPermission("megaplugin.anticheat.bypass")) return true;
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return true;
        if (p.isFlying() && p.getAllowFlight()) return true;
        return false;
    }

    // ════════════════════════════════════════
    //  1. 飞行检测
    // ════════════════════════════════════════

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (shouldSkip(p)) {
            airTicks.remove(p.getUniqueId());
            lastY.remove(p.getUniqueId());
            return;
        }

        Location from = e.getFrom();
        Location to = e.getTo();

        // ── 1a. 飞行检测 ──
        boolean onGround = p.isOnGround();
        Material below = p.getLocation().subtract(0, 0.1, 0).getBlock().getType();
        boolean nearGround = onGround || below != Material.AIR || p.isInWater() || p.isClimbing();

        int at = airTicks.getOrDefault(p.getUniqueId(), 0);
        if (nearGround) {
            airTicks.put(p.getUniqueId(), 0);
        } else {
            at++;
            airTicks.put(p.getUniqueId(), at);

            // 在空中超过 15 tick (0.75秒) 且没有下降
            if (at > 15) {
                double deltaY = to.getY() - from.getY();
                if (deltaY > -0.1 && deltaY < 0.1 && p.getVelocity().getY() > -0.5) {
                    e.setCancelled(true);
                    addViolation(p, "飞行");
                }
            }
        }

        // ── 1b. 速度检测 ──
        Location last = lastMove.get(p.getUniqueId());
        if (last != null && !nearGround) {
            double dx = to.getX() - last.getX();
            double dz = to.getZ() - last.getZ();
            double speed = Math.sqrt(dx * dx + dz * dz);
            if (speed > MAX_SPEED) {
                e.setCancelled(true);
                addViolation(p, "速度");
            }
        }
        lastMove.put(p.getUniqueId(), to.clone());
        lastY.put(p.getUniqueId(), to.getY());
    }

    // ════════════════════════════════════════
    //  2. 距离检测 (Reach / KillAura)
    // ════════════════════════════════════════

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        if (!(e.getEntity() instanceof Player target)) return;
        if (shouldSkip(p)) return;

        double dist = p.getLocation().distance(target.getLocation());
        long now = System.currentTimeMillis();

        // ── 2a. 攻击距离检测 ──
        if (dist > MAX_REACH) {
            e.setCancelled(true);
            addViolation(p, "距离");
            return;
        }

        // ── 2b. KillAura 检测 ──
        String entityKey = target.getUniqueId().toString();
        Long lastHit = lastHitTime.get(p.getUniqueId());
        String lastEntity = lastHitEntity.get(p.getUniqueId());

        if (lastHit != null && now - lastHit < 50) { // 50ms 内攻击两个不同目标
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
    //  3. 自动点击检测
    // ════════════════════════════════════════

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAttackCps(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        if (shouldSkip(p)) return;

        long now = System.currentTimeMillis();
        long[] times = clickTimes.computeIfAbsent(p.getUniqueId(), k -> new long[20]);
        // 循环队列：左移并插入最新
        System.arraycopy(times, 0, times, 1, times.length - 1);
        times[0] = now;

        // 统计过去 1 秒内的点击
        int cps = 0;
        for (long t : times) {
            if (now - t <= 1000) cps++;
        }

        if (cps > MAX_CPS) {
            e.setCancelled(true);
            addViolation(p, "连点");
        }
    }

    // ════════════════════════════════════════
    //  4. 清理数据
    // ════════════════════════════════════════

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        violations.remove(id);
        clickTimes.remove(id);
        lastHitTime.remove(id);
        lastHitEntity.remove(id);
        airTicks.remove(id);
        lastY.remove(id);
        lastMove.remove(id);
    }

    // ════════════════════════════════════════
    //  /anticheat 命令
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
                case "toggle" -> {
                    enabled = !enabled;
                    sender.sendMessage(msg("prefix") + " §7反作弊 " + (enabled ? "§a已启用" : "§c已禁用"));
                }
                case "status" -> {
                    sender.sendMessage(msg("prefix") + " §8[§c§l反作弊状态§8]");
                    sender.sendMessage(" §7状态: " + (enabled ? "§a启用" : "§c禁用"));
                    sender.sendMessage(" §7当前监控玩家: §e" + airTicks.size());
                    sender.sendMessage(" §7总违规记录: §e" + violations.size() + " 人");
                }
                case "reset" -> {
                    if (args.length >= 2) {
                        Player target = Bukkit.getPlayer(args[1]);
                        if (target != null) {
                            violations.remove(target.getUniqueId());
                            clickTimes.remove(target.getUniqueId());
                            airTicks.remove(target.getUniqueId());
                            sender.sendMessage(msg("prefix") + " §a已重置 §e" + target.getName() + " §a的所有违规记录");
                        } else {
                            sender.sendMessage(msg("player-not-found"));
                        }
                    } else {
                        violations.clear();
                        clickTimes.clear();
                        airTicks.clear();
                        sender.sendMessage(msg("prefix") + " §a已重置所有违规记录");
                    }
                }
                case "check" -> {
                    if (args.length < 2) { sender.sendMessage(msg("prefix") + " §c用法: /ac check <玩家>"); return true; }
                    Player target = Bukkit.getPlayer(args[1]);
                    if (target == null) { sender.sendMessage(msg("player-not-found")); return true; }
                    Map<String, Integer> pv = violations.get(target.getUniqueId());
                    sender.sendMessage("§8[§c§l反作弊§8] §e" + target.getName() + " §7违规记录:");
                    if (pv == null || pv.isEmpty()) {
                        sender.sendMessage(" §7无违规记录");
                    } else {
                        for (var entry : pv.entrySet()) {
                            sender.sendMessage(" §c" + entry.getKey() + " §7x§4" + entry.getValue());
                        }
                    }
                }
                default -> sender.sendMessage(msg("prefix") + " §c未知参数，使用 /ac 查看帮助");
            }
            return true;
        }
    }

    private class ACheatTab implements TabCompleter {
        @Override
        public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
            if (!sender.hasPermission("megaplugin.admin")) return Collections.emptyList();
            if (args.length == 1) {
                return Arrays.asList("toggle", "status", "reset", "check").stream()
                        .filter(s -> s.startsWith(args[0].toLowerCase()))
                        .collect(Collectors.toList());
            }
            if (args.length == 2 && (args[0].equalsIgnoreCase("reset") || args[0].equalsIgnoreCase("check"))) {
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            }
            return Collections.emptyList();
        }
    }
}
