package com.megaplugin.module;

import com.megaplugin.MegaPlugin;
import com.megaplugin.util.DataFile;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 渐进式惩罚模块
 * 跨重启累计违规，自动封禁
 */
public class PunishModule extends MegaModule {

    private final DataFile data;
    // UUID → (检测项 → 累计VL)
    private final Map<UUID, Map<String, Integer>> records = new HashMap<>();

    public PunishModule(MegaPlugin plugin) {
        super(plugin);
        data = new DataFile(plugin, "punish_records.yml");
    }

    @Override
    public void onEnable() {
        // 加载历史记录
        for (String uuidStr : data.getConfig().getKeys(false)) {
            try {
                UUID id = UUID.fromString(uuidStr);
                Map<String, Integer> rec = new HashMap<>();
                for (String check : data.getConfig().getConfigurationSection(uuidStr).getKeys(false)) {
                    rec.put(check, data.getConfig().getInt(uuidStr + "." + check));
                }
                records.put(id, rec);
            } catch (Exception ignored) {}
        }

        var cmd = plugin.getCommand("megapunish");
        if (cmd != null) cmd.setExecutor(new PunishCmd());
    }

    @Override
    public void onDisable() {
        saveAll();
    }

    private void saveAll() {
        for (var entry : records.entrySet()) {
            for (var checkEntry : entry.getValue().entrySet()) {
                data.getConfig().set(entry.getKey().toString() + "." + checkEntry.getKey(), checkEntry.getValue());
            }
        }
        data.save();
    }

    /** Grim 或其他检测触发此方法 */
    public int addVL(Player p, String checkType) {
        UUID id = p.getUniqueId();
        Map<String, Integer> rec = records.computeIfAbsent(id, k -> new HashMap<>());
        int total = rec.merge(checkType, 1, Integer::sum);
        saveAll();

        // 进度提醒
        if (total % 5 == 0 || total == 3) {
            p.sendMessage("§8[§c§l反作弊§8] §7你因 §c" + checkType + " §7被标记，累计违规 §4" + total + " §7次");
        }
        checkPunish(p, total);
        return total;
    }

    /** 查看玩家累计违规 */
    public int getVL(UUID id, String checkType) {
        Map<String, Integer> rec = records.get(id);
        return rec != null ? rec.getOrDefault(checkType, 0) : 0;
    }

    public Map<String, Integer> getAllVL(UUID id) {
        return records.getOrDefault(id, Collections.emptyMap());
    }

    /** 重置玩家违规 */
    public void resetVL(UUID id) {
        records.remove(id);
        data.getConfig().set(id.toString(), null);
        data.save();
    }

    /** 渐进式惩罚 */
    private void checkPunish(Player p, int total) {
        String name = p.getName();
        String reason = "§c检测到作弊行为";
        Date expires = null;
        String duration = "";

        if (total >= 100) {
            duration = "永久";
            expires = null; // permanent
        } else if (total >= 70) {
            duration = "30天";
            expires = new Date(System.currentTimeMillis() + 30L * 24 * 3600 * 1000);
        } else if (total >= 50) {
            duration = "7天";
            expires = new Date(System.currentTimeMillis() + 7L * 24 * 3600 * 1000);
        } else if (total >= 20) {
            duration = "24小时";
            expires = new Date(System.currentTimeMillis() + 24L * 3600 * 1000);
        } else if (total >= 10) {
            duration = "1小时";
            expires = new Date(System.currentTimeMillis() + 3600 * 1000);
        } else {
            return;
        }

        String kickMsg = reason + "\n§7累计违规 §4" + total + " §7次\n§7封禁时长: §e" + duration;
        p.kick(net.kyori.adventure.text.Component.text(kickMsg));

        // Ban
        String banReason = "作弊 | 累计" + total + "次 | 封禁" + duration;
        if (expires != null) {
            Bukkit.getBanList(BanList.Type.NAME).addBan(name, banReason, expires, "MegaPlugin");
        } else {
            Bukkit.getBanList(BanList.Type.NAME).addBan(name, banReason, null, "MegaPlugin");
        }
        Bukkit.getBanList(BanList.Type.IP).addBan(p.getAddress().getAddress().getHostAddress(),
                banReason, expires, "MegaPlugin");

        Bukkit.broadcast(net.kyori.adventure.text.Component.text(
                "§8[§c§l反作弊§8] §e" + name + " §c因作弊被 §4" + duration + " §c封禁！§7(累计 §4" + total + " §7次)"));
    }

    private class PunishCmd implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender s, Command c, String l, String[] args) {
            if (!s.hasPermission("megaplugin.admin")) { s.sendMessage(msg("no-permission")); return true; }

            if (args.length < 1) {
                s.sendMessage("§c用法: /megapunish <玩家> <检测类型>");
                return true;
            }

            Player p = Bukkit.getPlayer(args[0]);
            if (p == null) { s.sendMessage(msg("player-not-found")); return true; }

            String type = args.length >= 2 ? args[1] : "Reach";
            int total = addVL(p, type);
            s.sendMessage(msg("prefix") + " §e" + p.getName() + " §7" + type + " 违规累计: §c" + total);
            return true;
        }
    }
}
