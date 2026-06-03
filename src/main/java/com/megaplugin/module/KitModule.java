package com.megaplugin.module;

import com.megaplugin.MegaPlugin;
import com.megaplugin.util.DataFile;
import org.bukkit.Material;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 礼包模块 — /kit /kits /createkit /deletekit
 */
public class KitModule extends MegaModule {

    private final DataFile kitData = new DataFile(plugin, "kits.yml");
    private final DataFile cdData = new DataFile(plugin, "kit_cooldowns.yml");
    private final Map<String, KitInfo> kits = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Long>> cooldowns = new ConcurrentHashMap<>();

    public KitModule(MegaPlugin plugin) { super(plugin); }

    @Override
    public void onEnable() {
        listen();
        cmd("kit", new KitCmd());
        cmd("kits", new KitsCmd());
        cmd("createkit", new CreatekitCmd());
        cmd("deletekit", new DeletekitCmd());

        // 加载礼包
        for (String name : kitData.getConfig().getKeys(false)) {
            try {
                var sec = kitData.getConfig().getConfigurationSection(name);
                if (sec == null) continue;
                String display = sec.getString("name", name);
                int cd = sec.getInt("cooldown", 0);
                @SuppressWarnings("unchecked")
                var items = (List<ItemStack>) sec.getList("items");
                if (items != null) kits.put(name.toLowerCase(), new KitInfo(display, cd, items));
            } catch (Exception e) {
                plugin.getLogger().warning("[Kit] 加载失败: " + name);
            }
        }

        // 加载冷却
        for (String k : cdData.getConfig().getKeys(false)) {
            try {
                UUID id = UUID.fromString(k);
                var sec = cdData.getConfig().getConfigurationSection(k);
                if (sec == null) continue;
                ConcurrentHashMap<String, Long> map = new ConcurrentHashMap<>();
                for (String kit : sec.getKeys(false)) {
                    long t = sec.getLong(kit, 0);
                    if (t > System.currentTimeMillis()) map.put(kit, t);
                }
                if (!map.isEmpty()) cooldowns.put(id, map);
            } catch (Exception ignored) {}
        }

        new BukkitRunnable() { public void run() { saveCooldowns(); } }
                .runTaskTimer(plugin, 1200L, 1200L);
    }

    @Override
    public void onDisable() {
        saveKits();
        saveCooldowns();
        super.onDisable();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { cooldowns.remove(e.getPlayer().getUniqueId()); }

    private void saveKits() {
        for (var e : kits.entrySet()) {
            String p = e.getKey();
            kitData.getConfig().set(p + ".name", e.getValue().name);
            kitData.getConfig().set(p + ".cooldown", e.getValue().cooldown);
            kitData.getConfig().set(p + ".items", e.getValue().items);
        }
        kitData.save();
    }

    private void saveCooldowns() {
        for (var e : cooldowns.entrySet()) {
            String p = e.getKey().toString();
            for (var ke : e.getValue().entrySet())
                if (ke.getValue() > System.currentTimeMillis())
                    cdData.getConfig().set(p + "." + ke.getKey(), ke.getValue());
        }
        cdData.save();
    }

    private void cmd(String name, CommandExecutor exe) {
        var c = plugin.getCommand(name);
        if (c != null) {
            c.setExecutor(exe);
            if (exe instanceof TabCompleter t) c.setTabCompleter(t);
        }
    }

    private record KitInfo(String name, int cooldown, List<ItemStack> items) {}

    class KitCmd implements CommandExecutor, TabCompleter {
        public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!(s instanceof Player p)) { s.sendMessage(msg("player-only")); return true; }
            if (!p.hasPermission("megaplugin.kit")) { p.sendMessage(msg("no-permission")); return true; }
            if (a.length == 0) {
                p.sendMessage(msg("prefix") + " §7可用礼包: §e" +
                        String.join("§7, §e", kits.keySet()));
                return true;
            }
            String name = a[0].toLowerCase();
            var kit = kits.get(name);
            if (kit == null) { p.sendMessage(msg("prefix") + " §c礼包不存在！"); return true; }

            // 冷却检查
            var cd = cooldowns.getOrDefault(p.getUniqueId(), Map.of());
            if (cd.containsKey(name) && cd.get(name) > System.currentTimeMillis()) {
                long sec = (cd.get(name) - System.currentTimeMillis()) / 1000;
                p.sendMessage(msg("prefix") + " §c冷却中！剩余 §e" + sec + "秒");
                return true;
            }

            for (var item : kit.items) {
                var leftover = p.getInventory().addItem(item.clone());
                for (var left : leftover.values())
                    p.getWorld().dropItemNaturally(p.getLocation(), left);
            }

            if (kit.cooldown > 0)
                cooldowns.computeIfAbsent(p.getUniqueId(), k -> new ConcurrentHashMap<>())
                        .put(name, System.currentTimeMillis() + kit.cooldown * 1000L);

            p.sendMessage(msg("prefix") + " §a已领取礼包 §e" + kit.name);
            return true;
        }
        public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
            if (a.length == 1) return kits.keySet().stream()
                    .filter(k -> k.startsWith(a[0].toLowerCase())).toList();
            return List.of();
        }
    }

    class KitsCmd implements CommandExecutor {
        public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!s.hasPermission("megaplugin.kit")) { s.sendMessage(msg("no-permission")); return true; }
            if (kits.isEmpty()) { s.sendMessage(msg("prefix") + " §7暂无礼包。"); return true; }
            s.sendMessage(msg("prefix") + " §6礼包列表 (" + kits.size() + "):");
            for (var e : kits.entrySet()) {
                String info = e.getValue().name;
                if (e.getValue().cooldown > 0) info += " §7(" + e.getValue().cooldown + "秒冷却)";
                s.sendMessage(" §e- " + info);
            }
            return true;
        }
    }

    class CreatekitCmd implements CommandExecutor, TabCompleter {
        public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!(s instanceof Player p)) { s.sendMessage(msg("player-only")); return true; }
            if (!p.hasPermission("megaplugin.kit.admin")) { p.sendMessage(msg("no-permission")); return true; }
            if (a.length < 2) { p.sendMessage(msg("prefix") + " §c用法: /createkit <名字> <冷却秒>"); return true; }
            int cd;
            try { cd = Integer.parseInt(a[1]); } catch (Exception ex) { p.sendMessage(msg("invalid-number")); return true; }
            if (cd < 0) cd = 0;
            List<ItemStack> items = new ArrayList<>();
            for (var item : p.getInventory().getContents())
                if (item != null && item.getType() != Material.AIR) items.add(item.clone());
            for (var item : p.getInventory().getArmorContents())
                if (item != null && item.getType() != Material.AIR) items.add(item.clone());
            if (p.getInventory().getItemInOffHand().getType() != Material.AIR)
                items.add(p.getInventory().getItemInOffHand().clone());
            kits.put(a[0].toLowerCase(), new KitInfo(a[0], cd, items));
            saveKits();
            p.sendMessage(msg("prefix") + " §a礼包已创建！§e" + items.size() + "§a件物品 §7(" + cd + "秒冷却)");
            return true;
        }
        public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
            if (a.length == 2) return List.of("0", "60", "300", "3600", "86400");
            return List.of();
        }
    }

    class DeletekitCmd implements CommandExecutor, TabCompleter {
        public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!s.hasPermission("megaplugin.kit.admin")) { s.sendMessage(msg("no-permission")); return true; }
            if (a.length == 0) { s.sendMessage(msg("prefix") + " §c用法: /deletekit <名字>"); return true; }
            String n = a[0].toLowerCase();
            if (kits.remove(n) != null) {
                kitData.getConfig().set(n, null); kitData.save();
                s.sendMessage(msg("prefix") + " §a礼包已删除！");
            } else { s.sendMessage(msg("prefix") + " §c礼包不存在！"); }
            return true;
        }
        public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
            if (a.length == 1) return kits.keySet().stream()
                    .filter(k -> k.startsWith(a[0].toLowerCase())).toList();
            return List.of();
        }
    }
}
