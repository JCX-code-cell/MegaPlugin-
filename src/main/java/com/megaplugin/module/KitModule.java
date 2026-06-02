package com.megaplugin.module;

import com.megaplugin.MegaPlugin;
import com.megaplugin.util.DataFile;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.stream.Collectors;

public class KitModule extends MegaModule {

    private final DataFile kitData;
    private final DataFile cooldownData;
    private final Map<String, KitInfo> kits = new HashMap<>();
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();

    public KitModule(MegaPlugin plugin) {
        super(plugin);
        kitData = new DataFile(plugin, "kits.yml");
        cooldownData = new DataFile(plugin, "kit_cooldowns.yml");
    }

    @Override
    public void onEnable() {
        registerListener();
        register("kit", new KitCmd());
        register("kits", new KitsCmd());
        register("createkit", new CreatekitCmd());
        register("deletekit", new DeletekitCmd());

        // Load kits
        for (String kitName : kitData.getConfig().getKeys(false)) {
            try {
                var section = kitData.getConfig().getConfigurationSection(kitName);
                if (section != null) {
                    String name = section.getString("name", kitName);
                    int cooldown = section.getInt("cooldown", 0);
                    List<ItemStack> items = (List<ItemStack>) section.getList("items");
                    if (items != null) {
                        kits.put(kitName.toLowerCase(), new KitInfo(name, cooldown, items));
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to load kit: " + kitName);
            }
        }

        // Load cooldowns
        for (String key : cooldownData.getConfig().getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                var section = cooldownData.getConfig().getConfigurationSection(key);
                if (section != null) {
                    Map<String, Long> playerCd = new HashMap<>();
                    for (String kit : section.getKeys(false)) {
                        long time = section.getLong(kit, 0);
                        if (time > System.currentTimeMillis()) playerCd.put(kit, time);
                    }
                    if (!playerCd.isEmpty()) cooldowns.put(uuid, playerCd);
                }
            } catch (Exception ignored) {}
        }

        // Periodic cooldown save
        new BukkitRunnable() {
            @Override
            public void run() { saveCooldowns(); }
        }.runTaskTimer(plugin, 1200L, 1200L); // Every 60 seconds
    }

    @Override
    public void onDisable() {
        saveKits();
        saveCooldowns();
    }

    private void saveKits() {
        for (var entry : kits.entrySet()) {
            String path = entry.getKey();
            kitData.getConfig().set(path + ".name", entry.getValue().name);
            kitData.getConfig().set(path + ".cooldown", entry.getValue().cooldown);
            kitData.getConfig().set(path + ".items", entry.getValue().items);
        }
        kitData.save();
    }

    private void saveCooldowns() {
        for (var entry : cooldowns.entrySet()) {
            String path = entry.getKey().toString();
            for (var kitEntry : entry.getValue().entrySet()) {
                if (kitEntry.getValue() > System.currentTimeMillis()) {
                    cooldownData.getConfig().set(path + "." + kitEntry.getKey(), kitEntry.getValue());
                }
            }
        }
        cooldownData.save();
    }

    @SuppressWarnings("deprecation")
    private void register(String name, CommandExecutor executor) {
        var cmd = plugin.getCommand(name);
        if (cmd != null) {
            cmd.setExecutor(executor);
            if (executor instanceof TabCompleter t) cmd.setTabCompleter(t);
        }
    }

    private record KitInfo(String name, int cooldown, List<ItemStack> items) {}

    private class KitCmd implements CommandExecutor, TabCompleter {
        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (!(sender instanceof Player p)) { sender.sendMessage(msg("player-only")); return true; }
            if (!p.hasPermission("megaplugin.kit")) { p.sendMessage(msg("no-permission")); return true; }

            if (args.length == 0) {
                p.sendMessage(msg("prefix") + " §7可用礼包: §e" +
                        kits.keySet().stream().collect(Collectors.joining("§7, §e")));
                return true;
            }

            String kitName = args[0].toLowerCase();
            KitInfo kit = kits.get(kitName);
            if (kit == null) { p.sendMessage(msg("prefix") + " §c礼包不存在: " + args[0]); return true; }

            // Check cooldown
            Map<String, Long> playerCd = cooldowns.getOrDefault(p.getUniqueId(), Collections.emptyMap());
            if (playerCd.containsKey(kitName)) {
                long remaining = playerCd.get(kitName) - System.currentTimeMillis();
                if (remaining > 0) {
                    long seconds = remaining / 1000;
                    p.sendMessage(msg("prefix") + " §c冷却中！请等待 §e" + seconds + "秒 §c后才能再次领取此礼包。");
                    return true;
                }
            }

            // Give items
            PlayerInventory inv = p.getInventory();
            for (ItemStack item : kit.items) {
                ItemStack clone = item.clone();
                HashMap<Integer, ItemStack> leftover = inv.addItem(clone);
                if (!leftover.isEmpty()) {
                    for (ItemStack left : leftover.values()) {
                        p.getWorld().dropItemNaturally(p.getLocation(), left);
                    }
                }
            }

            // Set cooldown
            if (kit.cooldown > 0) {
                cooldowns.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>())
                        .put(kitName, System.currentTimeMillis() + kit.cooldown * 1000L);
            }

            p.sendMessage(msg("prefix") + " §a你领取了礼包: §e" + kit.name);
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
            if (args.length == 1) {
                return kits.keySet().stream()
                        .filter(k -> k.startsWith(args[0].toLowerCase()))
                        .collect(Collectors.toList());
            }
            return Collections.emptyList();
        }
    }

    private class KitsCmd implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (!sender.hasPermission("megaplugin.kit")) { sender.sendMessage(msg("no-permission")); return true; }
            if (kits.isEmpty()) {
                sender.sendMessage(msg("prefix") + " §7暂无可用礼包。");
            } else {
                List<String> kitList = new ArrayList<>();
                for (var entry : kits.entrySet()) {
                    String info = entry.getValue().name;
                    if (entry.getValue().cooldown > 0) info += " §7(" + entry.getValue().cooldown + "秒冷却)";
                    kitList.add(info);
                }
                sender.sendMessage(msg("prefix") + " §6可用礼包 §7("  + kits.size() + "):");
                for (String s : kitList) sender.sendMessage(" §e- " + s);
            }
            return true;
        }
    }

    private class CreatekitCmd implements CommandExecutor, TabCompleter {
        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (!(sender instanceof Player p)) { sender.sendMessage(msg("player-only")); return true; }
            if (!p.hasPermission("megaplugin.kit.admin")) { p.sendMessage(msg("no-permission")); return true; }
            if (args.length < 2) { p.sendMessage(msg("prefix") + " §c用法: /createkit <名字> <冷却秒数>"); return true; }

            String name = args[0];
            int cooldown;
            try { cooldown = Integer.parseInt(args[1]); } catch (NumberFormatException e) {
                p.sendMessage(msg("invalid-number")); return true;
            }
            if (cooldown < 0) cooldown = 0;

            List<ItemStack> items = new ArrayList<>();
            for (ItemStack item : p.getInventory().getContents()) {
                if (item != null && item.getType() != Material.AIR) items.add(item.clone());
            }
            for (ItemStack item : p.getInventory().getArmorContents()) {
                if (item != null && item.getType() != Material.AIR) items.add(item.clone());
            }
            if (p.getInventory().getItemInOffHand().getType() != Material.AIR) {
                items.add(p.getInventory().getItemInOffHand().clone());
            }

            kits.put(name.toLowerCase(), new KitInfo(name, cooldown, items));
            saveKits();
            p.sendMessage(msg("prefix") + " §a礼包 §e" + name + " §a已创建，包含 §e" + items.size() + " §a个物品 §7(" + cooldown + "秒冷却)。");
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
            if (args.length == 2) return Arrays.asList("0", "60", "300", "3600", "86400");
            return Collections.emptyList();
        }
    }

    private class DeletekitCmd implements CommandExecutor, TabCompleter {
        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (!sender.hasPermission("megaplugin.kit.admin")) { sender.sendMessage(msg("no-permission")); return true; }
            if (args.length == 0) { sender.sendMessage(msg("prefix") + " §c用法: /deletekit <名字>"); return true; }

            String name = args[0].toLowerCase();
            if (kits.remove(name) != null) {
                kitData.getConfig().set(name, null);
                kitData.save();
                sender.sendMessage(msg("prefix") + " §a礼包 §e" + args[0] + " §a已删除！");
            } else {
                sender.sendMessage(msg("prefix") + " §c礼包不存在: " + args[0]);
            }
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
            if (args.length == 1) {
                return kits.keySet().stream()
                        .filter(k -> k.startsWith(args[0].toLowerCase()))
                        .collect(Collectors.toList());
            }
            return Collections.emptyList();
        }
    }
}
