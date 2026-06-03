package com.megaplugin.module;

import com.megaplugin.MegaPlugin;
import com.megaplugin.util.DataFile;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * 渐进式惩罚模块 — 跨重启累计违规，自动封禁
 */
public class PunishModule extends MegaModule {

    private final DataFile data = new DataFile(plugin, "punish_records.yml");
    private final Map<UUID, Map<String, Integer>> records = new HashMap<>();

    public PunishModule(MegaPlugin plugin) { super(plugin); }

    @Override
    public void onEnable() {
        for (String id : data.getConfig().getKeys(false)) {
            try {
                UUID uid = UUID.fromString(id);
                Map<String, Integer> rec = new HashMap<>();
                var sec = data.getConfig().getConfigurationSection(id);
                if (sec != null) for (String check : sec.getKeys(false))
                    rec.put(check, sec.getInt(check, 0));
                records.put(uid, rec);
            } catch (Exception ignored) {}
        }
        var c = plugin.getCommand("megapunish");
        if (c != null) c.setExecutor(new PunishCmd());
    }

    @Override
    public void onDisable() {
        save();
        super.onDisable();
    }

    private void save() {
        for (var e : records.entrySet())
            for (var ce : e.getValue().entrySet())
                data.getConfig().set(e.getKey() + "." + ce.getKey(), ce.getValue());
        data.save();
    }

    public int addVL(Player p, String type) {
        var rec = records.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>());
        int total = rec.merge(type, 1, Integer::sum);
        save();
        if (total % 5 == 0 || total == 3)
            p.sendMessage("§8[§c§l反作弊§8] §7" + type + " 累计 §4" + total + " §7次");
        checkPunish(p, total);
        return total;
    }

    public int getVL(UUID id, String type) {
        var rec = records.get(id);
        return rec != null ? rec.getOrDefault(type, 0) : 0;
    }

    public Map<String, Integer> getAllVL(UUID id) {
        return records.getOrDefault(id, Map.of());
    }

    public void resetVL(UUID id) {
        records.remove(id);
        data.getConfig().set(id.toString(), null);
        data.save();
    }

    private void checkPunish(Player p, int total) {
        String duration;
        Date expires;
        if (total >= 100) { duration = "永久"; expires = null; }
        else if (total >= 70) { duration = "30天"; expires = new Date(System.currentTimeMillis() + 30L * 86400000); }
        else if (total >= 50) { duration = "7天"; expires = new Date(System.currentTimeMillis() + 7L * 86400000); }
        else if (total >= 20) { duration = "24小时"; expires = new Date(System.currentTimeMillis() + 86400000L); }
        else if (total >= 10) { duration = "1小时"; expires = new Date(System.currentTimeMillis() + 3600000L); }
        else return;

        String reason = "作弊 | 累计" + total + "次 | " + duration;
        p.kick(Component.text("§c检测到作弊行为\n§7累计 §4" + total + " §7次\n§7封禁: §e" + duration));
        Bukkit.getServer().ban(p.getAddress().getAddress().getHostAddress(), reason, expires, "MegaPlugin");
        Bukkit.broadcast(Component.text("§8[§c§l反作弊§8] §e" + p.getName() + " §c因作弊被 §4" + duration + " §c封禁！"));
    }

    class PunishCmd implements CommandExecutor {
        public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!s.hasPermission("megaplugin.punish")) { s.sendMessage(msg("no-permission")); return true; }
            if (a.length < 1) { s.sendMessage("§c用法: /megapunish <玩家> <检测类型>"); return true; }
            Player p = Bukkit.getPlayer(a[0]);
            if (p == null) { s.sendMessage(msg("player-not-found")); return true; }
            String type = a.length >= 2 ? a[1] : "Reach";
            int total = addVL(p, type);
            s.sendMessage(msg("prefix") + " §e" + p.getName() + " §7" + type + " 累计: §c" + total);
            return true;
        }
    }
}
