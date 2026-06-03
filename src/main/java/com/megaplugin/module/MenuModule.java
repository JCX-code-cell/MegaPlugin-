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
    private static final String ADMIN_TITLE = "§8§l[ §4§l管理工具 §8§l]";
    private static final String BC_INPUT_TITLE = "§8§l[ §d§l输入广播消息 §8§l]";
    private static final String INVSEE_INPUT_TITLE = "§8§l[ §e§l输入玩家名 §8§l]";

    /** Players waiting for anvil input: UUID → action type */
    private final Map<UUID, String> pendingInput = new HashMap<>();

    public MenuModule(MegaPlugin plugin) {
        super(plugin);
    }

    @Override
    public void onEnable() {
        registerListener();
        var cmd = plugin.getCommand("menu");
        if (cmd != null) cmd.setExecutor(new MenuCmd());
    }

    /** Execute a command after closing inventory with a 1-tick delay */
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

    /** Open an anvil GUI for text input */
    private void openAnvilInput(Player p, String title, String hint, String action) {
        Inventory inv = Bukkit.createInventory(null, InventoryType.ANVIL, title);
        ItemStack paper = new ItemStack(Material.PAPER);
        ItemMeta m = paper.getItemMeta();
        if (m != null) { m.setDisplayName(hint); paper.setItemMeta(m); }
        inv.setItem(0, paper);
        pendingInput.put(p.getUniqueId(), action);
        p.openInventory(inv);
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
        inv.setItem(15, createItem(Material.ENCHANTED_GOLDEN_APPLE, "§4§l管理工具", "§7点击进入子菜单", "§7飞行 / 治愈 / 广播 / 背包"));

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
        Inventory inv = Bukkit.createInventory(null, 27, ADMIN_TITLE);
        boolean admin = player.hasPermission("megaplugin.admin");

        // ── 第1行：玩家设置 ──
        inv.setItem(11, createItem(Material.FEATHER, "§b§l飞行模式", "§7点击切换飞行模式"));
        inv.setItem(13, createItem(Material.GOLDEN_APPLE, "§a§l治愈", "§7点击恢复满生命"));
        inv.setItem(15, createItem(Material.COOKED_BEEF, "§6§l喂饱", "§7点击恢复饥饿值"));

        // ── 第2行：实用工具 ──
        inv.setItem(10, createItem(Material.BELL, "§d§l全服广播", "§7点击打开输入框", "§7输入广播内容发送全屏公告"));
        inv.setItem(16, createItem(Material.CHEST_MINECART, "§e§l查看背包", "§7点击打开输入框", "§7输入玩家名查看背包"));

        // ── 第3行：导航 ──
        inv.setItem(22, createItem(Material.ARROW, "§c§l返回主菜单", "§7点击返回服务器菜单"));
        inv.setItem(26, createItem(Material.BARRIER, "§c§l关闭菜单", "§7点击关闭"));

        // 装饰边框
        ItemStack border = createItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++) {
            if (inv.getItem(i) == null) inv.setItem(i, border);
        }

        // 无权限提示
        if (!admin) {
            for (int i = 0; i < 27; i++) {
                ItemStack it = inv.getItem(i);
                if (it.getType() != Material.BLACK_STAINED_GLASS_PANE
                        && it.getType() != Material.ARROW && it.getType() != Material.BARRIER) {
                    inv.setItem(i, createItem(Material.GRAY_DYE, "§c§l无权限", "§7你没有权限使用此功能"));
                }
            }
        }
        player.openInventory(inv);
    }

    /** Send broadcast as full-screen title */
    private void doBroadcast(Player sender, String msg) {
        var serializer = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand();
        Component title = serializer.deserialize("&6&l" + msg);
        Component subtitle = serializer.deserialize("&7- &e" + sender.getName() + " &7-");
        Title t = Title.title(title, subtitle,
                Title.Times.times(Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(1)));
        for (Player p : Bukkit.getOnlinePlayers()) p.showTitle(t);
        // Chat backup
        Bukkit.broadcast(Component.text(""));
        Bukkit.broadcast(serializer.deserialize("&8&m           &r &6&l全服公告 &8&m           "));
        Bukkit.broadcast(Component.text(""));
        Bukkit.broadcast(serializer.deserialize("  &r&6&l" + msg));
        Bukkit.broadcast(Component.text(""));
        Bukkit.broadcast(Component.text("§8§m                                    "));
        sender.sendMessage(msg("prefix") + " §a广播已发送！");
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        String title = e.getView().getTitle();

        if (title.equals(MENU_TITLE)) {
            handleMainMenuClick(e, p);
        } else if (title.equals(ADMIN_TITLE)) {
            handleAdminMenuClick(e, p);
        } else if (title.equals(BC_INPUT_TITLE) || title.equals(INVSEE_INPUT_TITLE)) {
            handleAnvilInputClick(e, p, title);
        }
    }

    private void handleMainMenuClick(InventoryClickEvent e, Player p) {
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
            case "§4§l管理工具" -> {
                e.setCancelled(true);
                if (p.hasPermission("megaplugin.admin")) {
                    openAdminMenu(p);
                } else {
                    p.closeInventory();
                    p.sendMessage(msg("no-permission"));
                }
            }
            case "§2§l玩家市场" -> runCmd(p, "market");
        }
    }

    private void handleAdminMenuClick(InventoryClickEvent e, Player p) {
        e.setCancelled(true);
        ItemStack item = e.getCurrentItem();
        if (item == null || item.getType() == Material.AIR) return;
        if (!p.hasPermission("megaplugin.admin")) return;
        String name = item.getItemMeta() != null ? item.getItemMeta().getDisplayName() : "";

        switch (name) {
            case "§b§l飞行模式" -> runCmd(p, "fly");
            case "§a§l治愈" -> runCmd(p, "heal");
            case "§6§l喂饱" -> runCmd(p, "feed");

            // ── 打开铁砧输入框 ──
            case "§d§l全服广播" -> openAnvilInput(p, BC_INPUT_TITLE, "请输入广播消息...", "broadcast");
            case "§e§l查看背包" -> openAnvilInput(p, INVSEE_INPUT_TITLE, "输入玩家名...", "invsee");

            case "§c§l返回主菜单" -> openMenu(p);
            case "§c§l关闭菜单" -> p.closeInventory();
        }
    }

    @SuppressWarnings("deprecation")
    private void handleAnvilInputClick(InventoryClickEvent e, Player p, String title) {
        e.setCancelled(true);
        if (e.getRawSlot() != 2) return; // only result slot

        // Read text from result slot item's display name
        ItemStack result = e.getCurrentItem();
        if (result == null || !result.hasItemMeta()) return;
        ItemMeta meta = result.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) return;
        String input = meta.getDisplayName();
        if (input == null || input.isBlank()) return;

        String action = pendingInput.remove(p.getUniqueId());
        if (action == null) return;

        p.closeInventory();

        if ("broadcast".equals(action)) {
            doBroadcast(p, input);
        } else if ("invsee".equals(action)) {
            Player target = Bukkit.getPlayer(input);
            if (target == null) {
                p.sendMessage(msg("prefix") + " §c玩家 §e" + input + " §c不在线！");
                return;
            }
            p.openInventory(target.getInventory());
            p.sendMessage(msg("prefix") + " §a查看 §e" + target.getName() + " §a的背包。");
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent e) {
        String t = e.getView().getTitle();
        if (t.equals(MENU_TITLE) || t.equals(ADMIN_TITLE)
                || t.equals(BC_INPUT_TITLE) || t.equals(INVSEE_INPUT_TITLE)) {
            e.setCancelled(true);
        }
    }

    /** Clean up pending input when player closes the anvil */
    @EventHandler
    public void onInventoryClose(org.bukkit.event.inventory.InventoryCloseEvent e) {
        if (e.getPlayer() instanceof Player p) {
            String t = e.getView().getTitle();
            if (t.equals(BC_INPUT_TITLE) || t.equals(INVSEE_INPUT_TITLE)) {
                pendingInput.remove(p.getUniqueId());
            }
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
