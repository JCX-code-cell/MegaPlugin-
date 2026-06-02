package com.megaplugin.module;

import com.megaplugin.MegaPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Arrays;

public class MenuModule extends MegaModule {

    private static final String MENU_TITLE = "§8§l[ §6§l服务器菜单 §8§l]";

    public MenuModule(MegaPlugin plugin) {
        super(plugin);
    }

    @Override
    public void onEnable() {
        registerListener();
        var cmd = plugin.getCommand("menu");
        if (cmd != null) cmd.setExecutor(new MenuCmd());
    }

    private ItemStack createItem(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore.length > 0) meta.setLore(Arrays.asList(lore));
            item.setItemMeta(meta);
        }
        return item;
    }

    private void openMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, MENU_TITLE);

        // 第1行
        inv.setItem(10, createItem(Material.RED_BED, "§c§l家园系统", "§7点击打开家园菜单", "§7/home §8- 回到家园"));
        inv.setItem(12, createItem(Material.COMPASS, "§b§l地标传送", "§7点击打开地标GUI", "§7/warp §8- 可视化传送"));
        inv.setItem(14, createItem(Material.ENDER_PEARL, "§a§l玩家传送", "§7点击请求传送", "§7/tpa §8- 请求传送"));
        inv.setItem(16, createItem(Material.DIAMOND, "§e§l经济系统", "§7点击查看余额", "§7/bal §8- 查看余额"));

        // 第2行
        inv.setItem(11, createItem(Material.CHEST, "§6§l礼包领取", "§7点击领取礼包", "§7/kit §8- 领取礼包"));
        inv.setItem(13, createItem(Material.NETHER_STAR, "§d§l出生点", "§7点击回到出生点", "§7/spawn §8- 回到出生点"));
        inv.setItem(15, createItem(Material.ENCHANTED_GOLDEN_APPLE, "§4§l管理工具", "§7管理员功能", "§7/gmc /fly /god"));

        // 第3行
        inv.setItem(22, createItem(Material.EMERALD, "§2§l玩家市场", "§7点击打开交易市场", "§7/market §8- 浏览/购买/出售"));

        // 装饰边框
        ItemStack glass = createItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i : new int[]{0,1,2,3,4,5,6,7,8,9,17,18,19,20,21,22,23,24,25,26}) {
            if (inv.getItem(i) == null) inv.setItem(i, glass);
        }

        player.openInventory(inv);
    }

    private class MenuCmd implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (!(sender instanceof Player p)) { sender.sendMessage(msg("player-only")); return true; }
            openMenu(p);
            return true;
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!e.getView().getTitle().equals(MENU_TITLE)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        ItemStack item = e.getCurrentItem();
        if (item == null || item.getType() == Material.AIR) return;

        String name = item.getItemMeta() != null ? item.getItemMeta().getDisplayName() : "";
        switch (name) {
            case "§c§l家园系统" -> {
                p.closeInventory();
                p.performCommand("homes");
            }
            case "§b§l地标传送" -> {
                p.closeInventory();
                p.performCommand("warps");
            }
            case "§a§l玩家传送" -> {
                p.closeInventory();
                p.sendMessage(msg("prefix") + " §7请使用 §e/tpa <玩家> §7请求传送");
            }
            case "§e§l经济系统" -> {
                p.closeInventory();
                p.performCommand("bal");
            }
            case "§6§l礼包领取" -> {
                p.closeInventory();
                p.performCommand("kits");
            }
            case "§d§l出生点" -> {
                p.closeInventory();
                p.performCommand("spawn");
            }
            case "§4§l管理工具" -> {
                p.closeInventory();
                if (p.hasPermission("megaplugin.admin")) {
                    p.sendMessage(msg("prefix") + " §7管理命令: §e/gmc /gms /fly /god /heal /vanish /invsee");
                } else {
                    p.sendMessage(msg("no-permission"));
                }
            }
            case "§2§l玩家市场" -> {
                p.closeInventory();
                p.performCommand("market");
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent e) {
        if (e.getView().getTitle().equals(MENU_TITLE)) e.setCancelled(true);
    }
}
