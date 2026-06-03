package com.megaplugin.module;

import com.megaplugin.MegaPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

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

    private void runCmd(Player p, String cmd) {
        p.closeInventory();
        Bukkit.getScheduler().runTaskLater(plugin, () -> p.performCommand(cmd), 1L);
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
        Inventory inv = Bukkit.createInventory(null, 36, MENU_TITLE);

        inv.setItem(11, createItem(Material.RED_BED, "§c§l家园系统", "§7点击打开家园菜单", "§7/home §8- 回到家园"));
        inv.setItem(13, createItem(Material.COMPASS, "§b§l地标传送", "§7点击打开地标GUI", "§7/warp §8- 可视化传送"));
        inv.setItem(15, createItem(Material.ENDER_PEARL, "§a§l玩家传送", "§7点击请求传送", "§7/tpa §8- 请求传送"));

        inv.setItem(20, createItem(Material.DIAMOND, "§e§l经济系统", "§7点击查看余额", "§7/bal §8- 查看余额"));
        inv.setItem(22, createItem(Material.CHEST, "§6§l礼包领取", "§7点击领取礼包", "§7/kit §8- 领取礼包"));
        inv.setItem(24, createItem(Material.NETHER_STAR, "§d§l出生点", "§7点击回到出生点", "§7/spawn §8- 回到出生点"));

        inv.setItem(29, createItem(Material.FILLED_MAP, "§3§l随机传送", "§7点击随机传送", "§7/rtp §8- 冒险探索"));
        inv.setItem(31, createItem(Material.EMERALD, "§2§l玩家市场", "§7点击打开交易市场", "§7/market §8- 浏览/购买/出售"));
        inv.setItem(33, createItem(Material.GRASS_BLOCK, "§a§l领地系统", "§7点击打开领地管理", "§7/claim §8- 创建/管理领地"));

        ItemStack glass = createItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i : new int[]{0,1,2,3,4,5,6,7,8,9,10,12,14,16,17,18,19,21,23,25,26,27,28,30,32,34,35}) {
            if (inv.getItem(i) == null) inv.setItem(i, glass);
        }
        player.openInventory(inv);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!e.getView().getTitle().equals(MENU_TITLE)) return;
        e.setCancelled(true);
        ItemStack item = e.getCurrentItem();
        if (item == null || item.getType() == Material.AIR) return;
        String name = item.getItemMeta() != null ? item.getItemMeta().getDisplayName() : "";

        switch (name) {
            case "§c§l家园系统" -> runCmd(p, "homes");
            case "§b§l地标传送" -> runCmd(p, "warps");
            case "§a§l玩家传送" -> { p.closeInventory(); p.sendMessage(msg("prefix") + " §7请使用 §e/tpa <玩家> §7请求传送"); }
            case "§e§l经济系统" -> runCmd(p, "bal");
            case "§6§l礼包领取" -> runCmd(p, "kits");
            case "§d§l出生点" -> runCmd(p, "spawn");
            case "§3§l随机传送" -> runCmd(p, "rtp");
            case "§2§l玩家市场" -> runCmd(p, "market");
            case "§a§l领地系统" -> runCmd(p, "claim");
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent e) {
        if (e.getView().getTitle().equals(MENU_TITLE)) e.setCancelled(true);
    }

    /** Shift+F (sneak + swap hands) → 打开菜单 */
    @EventHandler
    public void onSwapHands(PlayerSwapHandItemsEvent e) {
        Player p = e.getPlayer();
        if (p.isSneaking()) {
            e.setCancelled(true);
            openMenu(p);
        }
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
