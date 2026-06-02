package com.megaplugin.module;

import com.megaplugin.MegaPlugin;
import com.megaplugin.util.DataFile;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

public class BindModule extends MegaModule {

    private final DataFile bindData;
    private final Map<UUID, Map<String, String>> binds = new HashMap<>();
    private final String bindLorePrefix = "§8[§d绑定§8] §7";

    public BindModule(MegaPlugin plugin) {
        super(plugin);
        bindData = new DataFile(plugin, "binds.yml");
    }

    @Override
    public void onEnable() {
        registerListener();
        var cmdBind = plugin.getCommand("bind");
        if (cmdBind != null) cmdBind.setExecutor(new BindCmd());
        var cmdUnbind = plugin.getCommand("unbind");
        if (cmdUnbind != null) cmdUnbind.setExecutor(new UnbindCmd());
        var cmdBinds = plugin.getCommand("binds");
        if (cmdBinds != null) cmdBinds.setExecutor(new BindsCmd());

        // Load binds
        for (String key : bindData.getConfig().getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                var section = bindData.getConfig().getConfigurationSection(key);
                if (section != null) {
                    Map<String, String> playerBinds = new HashMap<>();
                    for (String itemKey : section.getKeys(false)) {
                        playerBinds.put(itemKey, section.getString(itemKey));
                    }
                    if (!playerBinds.isEmpty()) binds.put(uuid, playerBinds);
                }
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void onDisable() {
        for (var entry : binds.entrySet()) {
            String path = entry.getKey().toString();
            for (var bind : entry.getValue().entrySet()) {
                bindData.getConfig().set(path + "." + bind.getKey(), bind.getValue());
            }
        }
        bindData.save();
    }

    private String getItemKey(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return null;
        return item.getType().name();
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        ItemStack item = e.getItem();
        String key = getItemKey(item);
        if (key == null) return;

        Map<String, String> playerBinds = binds.get(p.getUniqueId());
        if (playerBinds == null) return;
        String command = playerBinds.get(key);
        if (command == null) return;

        e.setCancelled(true);
        String cmd = command.startsWith("/") ? command.substring(1) : command;
        p.performCommand(cmd);
    }

    private void applyBindLore(ItemStack item, String command) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        List<String> lore = meta.hasLore() ? meta.getLore() : new ArrayList<>();
        if (lore == null) lore = new ArrayList<>();
        // Remove old bind lore
        lore.removeIf(line -> line.startsWith(bindLorePrefix));
        lore.add(bindLorePrefix + command);
        meta.setLore(lore);
        item.setItemMeta(meta);
    }

    private void removeBindLore(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasLore()) return;
        List<String> lore = meta.getLore();
        if (lore == null) return;
        lore.removeIf(line -> line.startsWith(bindLorePrefix));
        if (lore.isEmpty()) meta.setLore(null);
        else meta.setLore(lore);
        item.setItemMeta(meta);
    }

    private class BindCmd implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (!(sender instanceof Player p)) { sender.sendMessage(msg("player-only")); return true; }
            if (!p.hasPermission("megaplugin.bind")) { p.sendMessage(msg("no-permission")); return true; }
            if (args.length == 0) {
                p.sendMessage(msg("prefix") + " §c用法: /bind <命令>");
                p.sendMessage(msg("prefix") + " §7示例: §e/bind menu");
                return true;
            }
            ItemStack item = p.getInventory().getItemInMainHand();
            if (item.getType() == Material.AIR) {
                p.sendMessage(msg("prefix") + " §c你必须手持一个物品！");
                return true;
            }
            String command = String.join(" ", args);
            String key = getItemKey(item);
            binds.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>()).put(key, command);
            applyBindLore(item, command);
            p.sendMessage(msg("prefix") + " §a已将 §e" + item.getType().name() + " §a绑定到命令: §e/" + command);
            return true;
        }
    }

    private class UnbindCmd implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (!(sender instanceof Player p)) { sender.sendMessage(msg("player-only")); return true; }
            if (!p.hasPermission("megaplugin.bind")) { p.sendMessage(msg("no-permission")); return true; }
            ItemStack item = p.getInventory().getItemInMainHand();
            if (item.getType() == Material.AIR) {
                p.sendMessage(msg("prefix") + " §c你必须手持一个物品！");
                return true;
            }
            String key = getItemKey(item);
            Map<String, String> playerBinds = binds.get(p.getUniqueId());
            if (playerBinds != null && playerBinds.remove(key) != null) {
                removeBindLore(item);
                p.sendMessage(msg("prefix") + " §a已解除绑定 §e" + item.getType().name());
            } else {
                p.sendMessage(msg("prefix") + " §c该物品没有绑定命令！");
            }
            return true;
        }
    }

    private class BindsCmd implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (!(sender instanceof Player p)) { sender.sendMessage(msg("player-only")); return true; }
            Map<String, String> playerBinds = binds.get(p.getUniqueId());
            if (playerBinds == null || playerBinds.isEmpty()) {
                p.sendMessage(msg("prefix") + " §7你没有绑定任何物品。");
                p.sendMessage(msg("prefix") + " §7手持物品时使用 §e/bind <命令> §7来绑定。");
                return true;
            }
            p.sendMessage(msg("prefix") + " §6§l你的物品绑定列表:");
            for (var entry : playerBinds.entrySet()) {
                p.sendMessage(" §e- " + entry.getKey() + " §7-> §f/" + entry.getValue());
            }
            return true;
        }
    }
}
