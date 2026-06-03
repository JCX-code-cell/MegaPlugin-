package com.megaplugin.module;

import com.megaplugin.MegaPlugin;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.*;
import java.util.stream.Collectors;

public class RTPModule extends MegaModule {

    private static final int MIN_RADIUS = 200;    // 最小区块 (200)
    private static final int MAX_RADIUS = 20000;  // 最大区块 (20000)
    private static final int MAX_ATTEMPTS = 25;   // 找安全位置尝试次数
    private static final long COOLDOWN_MS = 30000; // 冷却时间 30秒
    private static final double COST = 0.0;        // 费用（可配置）

    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Random random = new Random();

    public RTPModule(MegaPlugin plugin) {
        super(plugin);
    }

    @Override
    public void onEnable() {
        registerListener();
        var cmd = plugin.getCommand("rtp");
        if (cmd != null) {
            cmd.setExecutor(new RtpCmd());
            cmd.setTabCompleter(new RtpTab());
        }
    }

    /** 在范围内寻找安全位置 */
    private Location findSafeLocation(World world) {
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            int x = random.nextInt(MAX_RADIUS * 2) - MAX_RADIUS;
            int z = random.nextInt(MAX_RADIUS * 2) - MAX_RADIUS;
            if (Math.abs(x) < MIN_RADIUS) x += (x >= 0 ? 1 : -1) * MIN_RADIUS;
            if (Math.abs(z) < MIN_RADIUS) z += (z >= 0 ? 1 : -1) * MIN_RADIUS;

            int y = world.getHighestBlockYAt(x, z);
            if (y <= 0) continue; // 虚空

            Location loc = new Location(world, x + 0.5, y + 1, z + 0.5);

            // 安全检查
            Block ground = loc.getBlock().getRelative(BlockFace.DOWN);
            Block feet = loc.getBlock();
            Block head = loc.getBlock().getRelative(BlockFace.UP);
            Material gType = ground.getType();
            Material fType = feet.getType();
            Material hType = head.getType();

            // 不能在岩浆/水里，必须有固体地面，头顶不能是固体
            if (gType == Material.LAVA || gType == Material.MAGMA_BLOCK) continue;
            if (fType == Material.LAVA || fType == Material.WATER) continue;
            if (fType != Material.AIR && fType != Material.SHORT_GRASS && fType != Material.TALL_GRASS) continue;
            if (!gType.isSolid()) continue;
            if (hType.isSolid()) continue;

            // 不在水中
            if (world.getBiome(loc).name().contains("OCEAN") || world.getBiome(loc).name().contains("RIVER"))
                continue;

            return loc;
        }
        return null;
    }

    private String formatCooldown(long ms) {
        long s = ms / 1000;
        return s >= 60 ? (s / 60) + "分" + (s % 60) + "秒" : s + "秒";
    }

    private class RtpCmd implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (!(sender instanceof Player p)) { sender.sendMessage(msg("player-only")); return true; }

            // 冷却检查
            Long lastUse = cooldowns.get(p.getUniqueId());
            if (lastUse != null) {
                long remaining = COOLDOWN_MS - (System.currentTimeMillis() - lastUse);
                if (remaining > 0) {
                    p.sendMessage(msg("prefix") + " §c随机传送冷却中，请等待 §e" + formatCooldown(remaining));
                    return true;
                }
            }

            // 费用检查
            var eco = plugin.getEconomyModule();
            if (eco != null && COST > 0) {
                if (eco.getBalance(p) < COST) {
                    p.sendMessage(msg("prefix") + " §c余额不足！随机传送需要 §e" + COST + " 元");
                    return true;
                }
            }

            p.sendMessage(msg("prefix") + " §7正在寻找安全位置...");
            Location loc = findSafeLocation(p.getWorld());

            if (loc == null) {
                p.sendMessage(msg("prefix") + " §c找不到合适的位置，请稍后再试！");
                return true;
            }

            // 扣费
            if (eco != null && COST > 0) {
                eco.withdraw(p, COST);
                p.sendMessage(msg("prefix") + " §7已扣除传送费用 §e" + COST + " 元");
            }

            cooldowns.put(p.getUniqueId(), System.currentTimeMillis());

            // 传送前告知坐标
            p.sendMessage(msg("prefix") + " §a已将你随机传送到 §e" + loc.getBlockX() + " §7/ §e" + loc.getBlockY() + " §7/ §e" + loc.getBlockZ());
            p.teleport(loc, PlayerTeleportEvent.TeleportCause.PLUGIN);
            p.playSound(loc, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
            return true;
        }
    }

    private class RtpTab implements TabCompleter {
        @Override
        public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
            return Collections.emptyList();
        }
    }
}
