package com.megaplugin.module;

import com.megaplugin.MegaPlugin;
import com.megaplugin.util.DataFile;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 物品绑定模块 — /bind /unbind /binds
 * 手持物品右键触发绑定命令
 */
public class BindModule extends MegaModule {

    private final DataFile data = new DataFile(plugin, "binds.yml");
    private final Map<UUID, Map<String, String>> binds = new ConcurrentHashMap<>();
    private static final String LORE_PREFIX = "§8[§d绑定§8] §7";

    public BindModule(MegaPlugin plugin) { super(plugin); }

    @Override
    public void onEnable() {
        listen();
        var b = plugin.getCommand("bind");
        if (b != null) b.setExecutor(new BindCmd());
        var u = plugin.getCommand("unbind");
        if (u != null) u.setExecutor(new UnbindCmd());
        var s = plugin.getCommand("binds");
        if (s != null) s.setExecutor(new BindsCmd());

        for (String k : data.getConfig().getKeys(false)) {
            try {
                UUID id = UUID.fromString(k);
                var sec = data.getConfig().getConfigurationSection(k);
                if (sec == null) continue;
                ConcurrentHashMap<String, String> map = new ConcurrentHashMap<>();
                for (String ik : sec.getKeys(false)) map.put(ik, sec.getString(ik));
                if (!map.isEmpty()) binds.put(id, map);
            } catch (Exception ignored) {}
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
        for (var e : binds.entrySet()) {
            String p = e.getKey().toString();
            for (var b : e.getValue().entrySet()) data.getConfig().set(p + "." + b.getKey(), b.getValue());
        }
        data.save();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { binds.remove(e.getPlayer().getUniqueId()); }

    private String key(ItemStack item) {
        return (item == null || item.getType() == Material.AIR) ? null : item.getType().name();
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        String k = key(e.getItem());
        if (k == null) return;
        var pb = binds.get(p.getUniqueId());
        if (pb == null) return;
        String cmd = pb.get(k);
        if (cmd == null) return;
        e.setCancelled(true);
        p.performCommand(cmd.startsWith("/") ? cmd.substring(1) : cmd);
    }

    private void applyLore(ItemStack item, String cmd) {
        var meta = item.getItemMeta();
        if (meta == null) return;
        var lore = meta.hasLore() ? meta.getLore() : new ArrayList<String>();
        if (lore == null) lore = new ArrayList<>();
        lore.removeIf(l -> l.startsWith(LORE_PREFIX));
        lore.add(LORE_PREFIX + cmd);
        meta.setLore(lore);
        item.setItemMeta(meta);
    }

    private void removeLore(ItemStack item) {
        var meta = item.getItemMeta();
        if (meta == null || !meta.hasLore()) return;
        var lore = meta.getLore();
        if (lore == null) return;
        lore.removeIf(l -> l.startsWith(LORE_PREFIX));
        meta.setLore(lore.isEmpty() ? null : lore);
        item.setItemMeta(meta);
    }

    class BindCmd implements CommandExecutor {
        public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!(s instanceof Player p)) { s.sendMessage(msg("player-only")); return true; }
            if (!p.hasPermission("megaplugin.bind")) { p.sendMessage(msg("no-permission")); return true; }
            if (a.length == 0) { p.sendMessage(msg("prefix") + " §c用法: /bind <命令>"); return true; }
            var item = p.getInventory().getItemInMainHand();
            if (item.getType() == Material.AIR) { p.sendMessage(msg("prefix") + " §c请手持物品！"); return true; }
            String cmd = String.join(" ", a);
            String k = key(item);
            binds.computeIfAbsent(p.getUniqueId(), x -> new ConcurrentHashMap<>()).put(k, cmd);
            applyLore(item, cmd);
            p.sendMessage(msg("prefix") + " §a已绑定 §e" + k + " §a→ §e/" + cmd);
            return true;
        }
    }

    class UnbindCmd implements CommandExecutor {
        public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!(s instanceof Player p)) { s.sendMessage(msg("player-only")); return true; }
            if (!p.hasPermission("megaplugin.bind")) { p.sendMessage(msg("no-permission")); return true; }
            var item = p.getInventory().getItemInMainHand();
            if (item.getType() == Material.AIR) { p.sendMessage(msg("prefix") + " §c请手持物品！"); return true; }
            var pb = binds.get(p.getUniqueId());
            if (pb != null && pb.remove(key(item)) != null) {
                removeLore(item);
                p.sendMessage(msg("prefix") + " §a已解除绑定！");
            } else { p.sendMessage(msg("prefix") + " §c该物品未绑定！"); }
            return true;
        }
    }

    class BindsCmd implements CommandExecutor {
        public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!(s instanceof Player p)) { s.sendMessage(msg("player-only")); return true; }
            var pb = binds.get(p.getUniqueId());
            if (pb == null || pb.isEmpty()) {
                p.sendMessage(msg("prefix") + " §7你还没有绑定任何物品。");
                return true;
            }
            p.sendMessage(msg("prefix") + " §6§l物品绑定列表:");
            for (var e : pb.entrySet())
                p.sendMessage(" §e- " + e.getKey() + " §7→ §f/" + e.getValue());
            return true;
        }
    }
}
