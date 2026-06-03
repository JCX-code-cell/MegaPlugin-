package com.megaplugin.module;

import com.megaplugin.MegaPlugin;
import org.bukkit.*;
import org.bukkit.block.BlockFace;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.*;

/**
 * 随机传送模块 — /rtp
 */
public class RTPModule extends MegaModule {

    private static final int MIN_RADIUS = 200, MAX_RADIUS = 20000, MAX_ATTEMPTS = 25;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Random random = new Random();

    public RTPModule(MegaPlugin plugin) { super(plugin); }

    @Override
    public void onEnable() {
        listen();
        var c = plugin.getCommand("rtp");
        if (c != null) c.setExecutor(new RtpCmd());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { cooldowns.remove(e.getPlayer().getUniqueId()); }

    private Location findSafe(World world) {
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            int x = random.nextInt(MAX_RADIUS * 2) - MAX_RADIUS;
            int z = random.nextInt(MAX_RADIUS * 2) - MAX_RADIUS;
            if (Math.abs(x) < MIN_RADIUS) x += (x >= 0 ? 1 : -1) * MIN_RADIUS;
            if (Math.abs(z) < MIN_RADIUS) z += (z >= 0 ? 1 : -1) * MIN_RADIUS;

            int y = world.getHighestBlockYAt(x, z);
            if (y <= 0) continue;
            Location loc = new Location(world, x + 0.5, y + 1, z + 0.5);
            Material g = loc.getBlock().getRelative(BlockFace.DOWN).getType();
            Material f = loc.getBlock().getType();
            Material h = loc.getBlock().getRelative(BlockFace.UP).getType();

            if (g == Material.LAVA || g == Material.MAGMA_BLOCK) continue;
            if (f == Material.LAVA || f == Material.WATER) continue;
            if (f != Material.AIR && f != Material.SHORT_GRASS && f != Material.TALL_GRASS) continue;
            if (!g.isSolid() || h.isSolid()) continue;
            String biome = world.getBiome(loc).name();
            if (biome.contains("OCEAN") || biome.contains("RIVER")) continue;
            return loc;
        }
        return null;
    }

    class RtpCmd implements CommandExecutor {
        public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!(s instanceof Player p)) { s.sendMessage(msg("player-only")); return true; }

            long cd = plugin.getConfig().getLong("rtp.cooldown", 30) * 1000L;
            Long last = cooldowns.get(p.getUniqueId());
            if (last != null && System.currentTimeMillis() - last < cd) {
                long remain = (cd - (System.currentTimeMillis() - last)) / 1000;
                p.sendMessage(msg("prefix") + " §c冷却中！剩余 §e" + remain + "秒");
                return true;
            }

            double cost = plugin.getConfig().getDouble("rtp.cost", 0);
            var eco = plugin.economy();
            if (eco != null && cost > 0 && !eco.has(p.getUniqueId(), cost)) {
                p.sendMessage(msg("prefix") + " §c余额不足！需要 §e" + cost);
                return true;
            }

            p.sendMessage(msg("prefix") + " §7寻找安全位置...");
            Location loc = findSafe(p.getWorld());
            if (loc == null) { p.sendMessage(msg("prefix") + " §c找不到安全位置！"); return true; }

            if (eco != null && cost > 0) eco.withdraw(p.getUniqueId(), cost);
            cooldowns.put(p.getUniqueId(), System.currentTimeMillis());
            p.sendMessage(msg("prefix") + " §a已传送到 §e" + loc.getBlockX() + " " + loc.getBlockY() + " " + loc.getBlockZ());
            p.teleport(loc);
            p.playSound(loc, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
            return true;
        }
    }
}
