package com.megaplugin.module;

import com.megaplugin.MegaPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Arrays;

/**
 * 主菜单模块 — /menu + Shift+F 快捷打开
 */
public class MenuModule extends MegaModule {

    private static final String TITLE = "§8§l[ §6§lMegaMenu §8§l]";

    public MenuModule(MegaPlugin plugin) { super(plugin); }

    @Override
    public void onEnable() {
        listen();
        var c = plugin.getCommand("menu");
        if (c != null) c.setExecutor(new MenuCmd());
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (e.getPlayer().isSneaking()) {
            e.setCancelled(true);
            open(e.getPlayer());
        }
    }

    @EventHandler
    public void onGuiClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!e.getView().getTitle().equals(TITLE)) return;
        e.setCancelled(true);
        var item = e.getCurrentItem();
        if (item == null || !item.hasItemMeta()) return;
        var meta = item.getItemMeta();
        if (!meta.hasDisplayName()) return;
        p.closeInventory();
        String cmd = null;
        switch (meta.getDisplayName()) {
            case "§a§l家园" -> cmd = "home";
            case "§d§l地标" -> cmd = "warps";
            case "§e§l传送" -> cmd = "tpa";
            case "§b§l出生点" -> cmd = "spawn";
            case "§6§l市场" -> cmd = "market";
            case "§c§l随机传送" -> cmd = "rtp";
            case "§2§l礼包" -> cmd = "kit";
            case "§5§l领地" -> cmd = "claim";
            case "§7§l菜单" -> cmd = "menu";
        }
        if (cmd != null) p.performCommand(cmd);
    }

    void open(Player p) {
        Inventory inv = Bukkit.createInventory(null, 27, TITLE);
        add(inv, 10, Material.OAK_DOOR, "§a§l家园", "§7/sethome /home");
        add(inv, 11, Material.COMPASS, "§d§l地标", "§7/warp");
        add(inv, 12, Material.ENDER_PEARL, "§e§l传送", "§7/tpa /tpaccept");
        add(inv, 13, Material.BEDROCK, "§b§l出生点", "§7/spawn");
        add(inv, 14, Material.GOLD_INGOT, "§6§l市场", "§7/market");
        add(inv, 15, Material.NETHER_STAR, "§c§l随机传送", "§7/rtp");
        add(inv, 16, Material.CHEST, "§2§l礼包", "§7/kit");
        add(inv, 22, Material.GRASS_BLOCK, "§5§l领地", "§7/claim");
        for (int i = 0; i < 27; i++) if (inv.getItem(i) == null)
            inv.setItem(i, item(Material.GRAY_STAINED_GLASS_PANE, " "));
        p.openInventory(inv);
    }

    private void add(Inventory inv, int slot, Material m, String name, String... lore) {
        inv.setItem(slot, item(m, name, lore));
    }

    private ItemStack item(Material m, String name, String... lore) {
        ItemStack i = new ItemStack(m);
        ItemMeta meta = i.getItemMeta();
        if (meta != null) { meta.setDisplayName(name); meta.setLore(Arrays.asList(lore)); i.setItemMeta(meta); }
        return i;
    }

    class MenuCmd implements CommandExecutor {
        public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!(s instanceof Player p)) { s.sendMessage(msg("player-only")); return true; }
            open(p);
            return true;
        }
    }
}
