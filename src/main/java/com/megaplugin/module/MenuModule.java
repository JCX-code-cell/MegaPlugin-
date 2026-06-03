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
    private static final String ADMIN_TITLE = "§8§l[ §4§l管理工具 §8§l]";

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
        inv.setItem(15, createItem(Material.ENCHANTED_GOLDEN_APPLE, "§4§l管理工具", "§7点击进入子菜单", "§7分类管理命令"));

        // 第3行
        inv.setItem(22, createItem(Material.EMERALD, "§2§l玩家市场", "§7点击打开交易市场", "§7/market §8- 浏览/购买/出售"));

        // 装饰边框
        ItemStack glass = createItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i : new int[]{0,1,2,3,4,5,6,7,8,9,17,18,19,20,21,22,23,24,25,26}) {
            if (inv.getItem(i) == null) inv.setItem(i, glass);
        }

        player.openInventory(inv);
    }

    private void openAdminMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 36, ADMIN_TITLE);
        boolean admin = player.hasPermission("megaplugin.admin");

        // ── 第1行：游戏模式 ──
        inv.setItem(0, createItem(Material.GRASS_BLOCK, "§a§l创造模式", "§7/gmc §8- 切换到创造模式"));
        inv.setItem(1, createItem(Material.IRON_SWORD, "§7§l生存模式", "§7/gms §8- 切换到生存模式"));
        inv.setItem(2, createItem(Material.BOW, "§b§l冒险模式", "§7/gma §8- 切换到冒险模式"));
        inv.setItem(3, createItem(Material.GLASS, "§f§l观察者模式", "§7/gmsp §8- 切换到观察者模式"));

        // ── 第2行：玩家设置 ──
        inv.setItem(9, createItem(Material.FEATHER, "§b§l飞行模式", "§7/fly §8- 切换飞行"));
        inv.setItem(10, createItem(Material.BARRIER, "§c§l无敌模式", "§7/god §8- 切换无敌"));
        inv.setItem(11, createItem(Material.GOLDEN_APPLE, "§a§l治愈", "§7/heal §8- 恢复生命"));
        inv.setItem(12, createItem(Material.COOKED_BEEF, "§6§l喂饱", "§7/feed §8- 恢复饥饿值"));

        // ── 第3行：实用工具 ──
        inv.setItem(18, createItem(Material.ENDER_EYE, "§8§l隐身模式", "§7/vanish §8- 切换隐身"));
        inv.setItem(19, createItem(Material.CHEST_MINECART, "§e§l查看背包", "§7/invsee <玩家> §8- 查看"));
        inv.setItem(20, createItem(Material.BELL, "§d§l全服广播", "§7/broadcast §8- 发送公告"));

        // ── 第4行：传送工具 ──
        inv.setItem(27, createItem(Material.ENDER_PEARL, "§a§l传送", "§7/tp <玩家> §8- 传送到玩家"));
        inv.setItem(28, createItem(Material.CHORUS_FRUIT, "§b§l拉取玩家", "§7/tphere <玩家> §8- 拉到身边"));
        inv.setItem(29, createItem(Material.COMPASS, "§e§l返回", "§7/back §8- 回到之前位置"));

        // ── 返回按钮 ──
        inv.setItem(31, createItem(Material.ARROW, "§c§l返回主菜单", "§7点击返回服务器菜单"));
        inv.setItem(35, createItem(Material.BARRIER, "§c§l关闭菜单", "§7点击关闭"));

        // 装饰边框
        ItemStack glass = createItem(Material.RED_STAINED_GLASS_PANE, "§8§l管理工具");
        ItemStack border = createItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 36; i++) {
            if (inv.getItem(i) == null) {
                // 分类栏：行4, 8, 13, 17, 22, 26
                int row = i / 9;
                boolean isSeparator = (i % 9 == 8) || (i % 9 == 0 && row > 0 && i < 32);
                inv.setItem(i, isSeparator ? glass : border);
            }
        }

        // 无权限提示
        if (!admin) {
            for (int i = 0; i < 36; i++) {
                ItemStack it = inv.getItem(i);
                if (it != null && it.getType() != Material.BLACK_STAINED_GLASS_PANE
                        && it.getType() != Material.RED_STAINED_GLASS_PANE
                        && it.getType() != Material.ARROW && it.getType() != Material.BARRIER) {
                    inv.setItem(i, createItem(Material.GRAY_DYE, "§c§l无权限", "§7你没有权限使用此功能"));
                }
            }
        }

        player.openInventory(inv);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        String title = e.getView().getTitle();

        if (title.equals(MENU_TITLE)) {
            handleMainMenuClick(e, p);
        } else if (title.equals(ADMIN_TITLE)) {
            handleAdminMenuClick(e, p);
        }
    }

    private void handleMainMenuClick(InventoryClickEvent e, Player p) {
        e.setCancelled(true);
        ItemStack item = e.getCurrentItem();
        if (item == null || item.getType() == Material.AIR) return;
        String name = item.getItemMeta() != null ? item.getItemMeta().getDisplayName() : "";

        switch (name) {
            case "§c§l家园系统" -> { p.closeInventory(); p.performCommand("homes"); }
            case "§b§l地标传送" -> { p.closeInventory(); p.performCommand("warps"); }
            case "§a§l玩家传送" -> { p.closeInventory(); p.sendMessage(msg("prefix") + " §7请使用 §e/tpa <玩家> §7请求传送"); }
            case "§e§l经济系统" -> { p.closeInventory(); p.performCommand("bal"); }
            case "§6§l礼包领取" -> { p.closeInventory(); p.performCommand("kits"); }
            case "§d§l出生点" -> { p.closeInventory(); p.performCommand("spawn"); }
            case "§4§l管理工具" -> {
                e.setCancelled(true);
                if (p.hasPermission("megaplugin.admin")) {
                    openAdminMenu(p);
                } else {
                    p.closeInventory();
                    p.sendMessage(msg("no-permission"));
                }
            }
            case "§2§l玩家市场" -> { p.closeInventory(); p.performCommand("market"); }
        }
    }

    private void handleAdminMenuClick(InventoryClickEvent e, Player p) {
        e.setCancelled(true);
        ItemStack item = e.getCurrentItem();
        if (item == null || item.getType() == Material.AIR) return;
        if (!p.hasPermission("megaplugin.admin")) return;
        String name = item.getItemMeta() != null ? item.getItemMeta().getDisplayName() : "";

        switch (name) {
            // ── 游戏模式 ──
            case "§a§l创造模式" -> { p.closeInventory(); p.performCommand("gmc"); }
            case "§7§l生存模式" -> { p.closeInventory(); p.performCommand("gms"); }
            case "§b§l冒险模式" -> { p.closeInventory(); p.performCommand("gma"); }
            case "§f§l观察者模式" -> { p.closeInventory(); p.performCommand("gmsp"); }

            // ── 玩家设置 ──
            case "§b§l飞行模式" -> { p.closeInventory(); p.performCommand("fly"); }
            case "§c§l无敌模式" -> { p.closeInventory(); p.performCommand("god"); }
            case "§a§l治愈" -> { p.closeInventory(); p.performCommand("heal"); }
            case "§6§l喂饱" -> { p.closeInventory(); p.performCommand("feed"); }

            // ── 实用工具 ──
            case "§8§l隐身模式" -> { p.closeInventory(); p.performCommand("vanish"); }
            case "§e§l查看背包" -> { p.closeInventory(); p.sendMessage(msg("prefix") + " §7使用 §e/invsee <玩家> §7查看背包"); }
            case "§d§l全服广播" -> { p.closeInventory(); p.sendMessage(msg("prefix") + " §7使用 §e/bc <消息> §7发送全服广播"); }

            // ── 传送工具 ──
            case "§a§l传送" -> { p.closeInventory(); p.sendMessage(msg("prefix") + " §7使用 §e/tp <玩家> §7传送"); }
            case "§b§l拉取玩家" -> { p.closeInventory(); p.sendMessage(msg("prefix") + " §7使用 §e/tphere <玩家> §7拉取"); }
            case "§e§l返回" -> { p.closeInventory(); p.performCommand("back"); }

            // ── 导航 ──
            case "§c§l返回主菜单" -> { openMenu(p); }
            case "§c§l关闭菜单" -> { p.closeInventory(); }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent e) {
        String t = e.getView().getTitle();
        if (t.equals(MENU_TITLE) || t.equals(ADMIN_TITLE)) e.setCancelled(true);
    }

    private class MenuCmd implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (!(sender instanceof Player p)) { sender.sendMessage(msg("player-only")); return true; }
            openMenu(p);
            return true;
        }
    }
}
