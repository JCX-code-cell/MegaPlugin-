package com.megaplugin.module;

import com.megaplugin.MegaPlugin;
import com.megaplugin.util.DataFile;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家市场模块 — /market GUI 浏览/上架/下架/购买
 */
public class MarketModule extends MegaModule {

    private static final String TITLE_MARKET = "§8§l[ §6§l玩家市场 §8§l]";
    private static final String TITLE_MY = "§8§l[ §b§l我的商品 §8§l]";
    private static final String TITLE_CONFIRM = "§8§l[ §e§l确认购买 §8§l]";
    private static final int PER_PAGE = 45, MAX_LISTINGS = 36;

    private final DataFile data = new DataFile(plugin, "market.yml");
    private final List<MarketItem> listings = new ArrayList<>();
    private final Map<UUID, Integer> browsingPage = new ConcurrentHashMap<>();
    private final Map<UUID, MarketItem> pendingBuy = new ConcurrentHashMap<>();

    public MarketModule(MegaPlugin plugin) { super(plugin); }

    @Override
    public void onEnable() {
        listen();
        var c = plugin.getCommand("market");
        if (c != null) c.setExecutor(new MarketCmd());

        var sec = data.getConfig().getConfigurationSection("items");
        if (sec != null) {
            for (String k : sec.getKeys(false)) {
                try {
                    var is = sec.getConfigurationSection(k);
                    if (is == null) continue;
                    listings.add(new MarketItem(
                            UUID.fromString(k),
                            UUID.fromString(is.getString("seller", "")),
                            is.getString("sellerName", "未知"),
                            is.getItemStack("item"),
                            is.getDouble("price", 0)));
                } catch (Exception ignored) {}
            }
        }

        // 定时自动保存 (每 5 分钟)
        Bukkit.getScheduler().runTaskTimer(plugin, this::save, 6000L, 6000L);
    }

    @Override
    public void onDisable() {
        save();
        super.onDisable();
    }

    private void save() {
        for (var mi : listings) {
            String p = "items." + mi.id;
            data.getConfig().set(p + ".seller", mi.seller.toString());
            data.getConfig().set(p + ".sellerName", mi.sellerName);
            data.getConfig().set(p + ".price", mi.price);
            data.getConfig().set(p + ".item", mi.item);
        }
        var sec = data.getConfig().getConfigurationSection("items");
        if (sec != null) {
            for (String k : sec.getKeys(false)) {
                try {
                    if (listings.stream().noneMatch(mi -> mi.id.equals(UUID.fromString(k))))
                        data.getConfig().set("items." + k, null);
                } catch (Exception ignored) {}
            }
        }
        data.save();
    }

    private record MarketItem(UUID id, UUID seller, String sellerName, ItemStack item, double price) {}

    private ItemStack button(Material m, String name, String... lore) {
        var i = new ItemStack(m);
        var meta = i.getItemMeta();
        if (meta != null) { meta.setDisplayName(name); meta.setLore(Arrays.asList(lore)); i.setItemMeta(meta); }
        return i;
    }

    private ItemStack glass() { return button(Material.BLACK_STAINED_GLASS_PANE, " "); }

    private void openMarket(Player p, int page) {
        var active = new ArrayList<>(listings);
        int tp = Math.max(1, (active.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.max(0, Math.min(page, tp - 1));
        browsingPage.put(p.getUniqueId(), page);

        Inventory inv = Bukkit.createInventory(null, 54, TITLE_MARKET + " §7第" + (page + 1) + "/" + tp + "页");
        int start = page * PER_PAGE, end = Math.min(start + PER_PAGE, active.size());
        for (int i = start; i < end; i++) {
            var mi = active.get(i);
            var display = mi.item.clone();
            var meta = display.getItemMeta();
            if (meta == null) meta = Bukkit.getItemFactory().getItemMeta(display.getType());
            var lore = meta.hasLore() ? meta.getLore() : new ArrayList<String>();
            if (lore == null) lore = new ArrayList<>();
            lore.addAll(List.of("", "§8---", "§e价格: §a$" + String.format("%.2f", mi.price),
                    "§7卖家: §f" + mi.sellerName, "§7点击购买", "§8---"));
            meta.setLore(lore);
            display.setItemMeta(meta);
            inv.setItem(i - start, display);
        }
        inv.setItem(45, button(Material.ARROW, "§e§l上一页"));
        inv.setItem(46, button(Material.BARRIER, "§c§l关闭"));
        inv.setItem(47, button(Material.CHEST, "§b§l我的商品"));
        inv.setItem(49, button(Material.GOLD_INGOT, "§6§l玩家市场", "§7共 " + active.size() + " 件"));
        inv.setItem(52, button(Material.EMERALD, "§a§l上架物品", "§7手持物品输入 /market sell <价格>"));
        inv.setItem(53, button(Material.ARROW, "§e§l下一页"));
        for (int i = 48; i < 54; i++) if (inv.getItem(i) == null) inv.setItem(i, glass());
        p.openInventory(inv);
    }

    private void openMy(Player p) {
        var mine = listings.stream().filter(mi -> mi.seller.equals(p.getUniqueId())).toList();
        int rows = Math.min(6, Math.max(1, (mine.size() + 8) / 9 + 1));
        Inventory inv = Bukkit.createInventory(null, rows * 9, TITLE_MY + " §7" + mine.size() + "件");
        for (int i = 0; i < mine.size(); i++) {
            var mi = mine.get(i);
            var display = mi.item.clone();
            var meta = display.getItemMeta();
            if (meta == null) meta = Bukkit.getItemFactory().getItemMeta(display.getType());
            var lore = meta.hasLore() ? meta.getLore() : new ArrayList<String>();
            if (lore == null) lore = new ArrayList<>();
            lore.addAll(List.of("", "§8---", "§e价格: §a$" + String.format("%.2f", mi.price), "§c§l点击下架", "§8---"));
            meta.setLore(lore);
            display.setItemMeta(meta);
            inv.setItem(i, display);
        }
        p.openInventory(inv);
    }

    private void openConfirm(Player p, MarketItem mi) {
        pendingBuy.put(p.getUniqueId(), mi);
        Inventory inv = Bukkit.createInventory(null, 27, TITLE_CONFIRM);
        inv.setItem(11, button(Material.GREEN_WOOL, "§a§l确认购买", "§7价格: §e$" + String.format("%.2f", mi.price)));
        inv.setItem(13, mi.item.clone());
        inv.setItem(15, button(Material.RED_WOOL, "§c§l取消"));
        for (int i = 0; i < 27; i++) if (inv.getItem(i) == null) inv.setItem(i, glass());
        p.openInventory(inv);
    }

    @EventHandler
    public void onGuiClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        String t = e.getView().getTitle();
        e.setCancelled(true);
        var item = e.getCurrentItem();
        if (item == null || item.getType() == Material.AIR) return;
        int slot = e.getRawSlot();

        if (t.startsWith(TITLE_MARKET)) {
            if (slot == 45) { openMarket(p, browsingPage.getOrDefault(p.getUniqueId(), 0) - 1); }
            else if (slot == 46) { p.closeInventory(); }
            else if (slot == 47) { openMy(p); }
            else if (slot == 53) { openMarket(p, browsingPage.getOrDefault(p.getUniqueId(), 0) + 1); }
            else if (slot < PER_PAGE) {
                int idx = browsingPage.getOrDefault(p.getUniqueId(), 0) * PER_PAGE + slot;
                if (idx < listings.size()) {
                    var mi = listings.get(idx);
                    if (mi.seller.equals(p.getUniqueId())) { p.sendMessage(msg("prefix") + " §c不能买自己的商品！"); return; }
                    openConfirm(p, mi);
                }
            }
        } else if (t.startsWith(TITLE_MY)) {
            var mine = listings.stream().filter(mi -> mi.seller.equals(p.getUniqueId())).toList();
            if (slot < mine.size()) {
                var mi = mine.get(slot);
                listings.remove(mi);
                var leftover = p.getInventory().addItem(mi.item);
                for (var left : leftover.values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
                p.sendMessage(msg("prefix") + " §a已下架，物品已返还！");
                p.closeInventory();
            }
        } else if (t.startsWith(TITLE_CONFIRM)) {
            var mi = pendingBuy.remove(p.getUniqueId());
            if (slot == 11 && mi != null && listings.contains(mi)) {
                var eco = plugin.economy();
                if (eco == null) { p.sendMessage(msg("prefix") + " §c经济系统未启用！"); p.closeInventory(); return; }
                if (!eco.has(p.getUniqueId(), mi.price)) { p.sendMessage(msg("prefix") + " §c余额不足！"); p.closeInventory(); return; }
                if (p.getInventory().firstEmpty() == -1) { p.sendMessage(msg("prefix") + " §c背包已满！"); p.closeInventory(); return; }

                eco.withdraw(p.getUniqueId(), mi.price);
                Player seller = Bukkit.getPlayer(mi.seller);
                if (seller != null && seller.isOnline()) eco.deposit(mi.seller, mi.price);
                else {
                    double pending = data.getConfig().getDouble("pending." + mi.seller, 0);
                    data.getConfig().set("pending." + mi.seller, pending + mi.price);
                    data.save();
                }
                listings.remove(mi);
                p.getInventory().addItem(mi.item.clone());
                p.sendMessage(msg("prefix") + " §a购买成功！花费 §e$" + String.format("%.2f", mi.price));
            }
            p.closeInventory();
        }
    }

    @EventHandler
    public void onGuiDrag(InventoryDragEvent e) {
        String t = e.getView().getTitle();
        if (t.startsWith(TITLE_MARKET) || t.startsWith(TITLE_MY) || t.startsWith(TITLE_CONFIRM))
            e.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getView().getTitle().startsWith(TITLE_CONFIRM))
            pendingBuy.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        pendingBuy.remove(id);
        browsingPage.remove(id);
    }

    class MarketCmd implements CommandExecutor {
        public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!(s instanceof Player p)) { s.sendMessage(msg("player-only")); return true; }
            if (!p.hasPermission("megaplugin.market")) { p.sendMessage(msg("no-permission")); return true; }
            if (a.length == 0) { openMarket(p, 0); return true; }

            switch (a[0].toLowerCase()) {
                case "sell" -> {
                    if (!p.hasPermission("megaplugin.market.sell")) { p.sendMessage(msg("no-permission")); return true; }
                    if (a.length < 2) { p.sendMessage(msg("prefix") + " §c用法: /market sell <价格>"); return true; }
                    double price;
                    try { price = Double.parseDouble(a[1]); } catch (Exception ex) { p.sendMessage(msg("invalid-number")); return true; }
                    if (price <= 0) { p.sendMessage(msg("prefix") + " §c价格必须大于0！"); return true; }
                    var hand = p.getInventory().getItemInMainHand();
                    if (hand.getType() == Material.AIR) { p.sendMessage(msg("prefix") + " §c请手持物品！"); return true; }
                    if (listings.stream().filter(mi -> mi.seller.equals(p.getUniqueId())).count() >= MAX_LISTINGS) {
                        p.sendMessage(msg("prefix") + " §c最多上架 " + MAX_LISTINGS + " 件！"); return true;
                    }
                    var sell = hand.clone();
                    sell.setAmount(1);
                    if (hand.getAmount() > 1) hand.setAmount(hand.getAmount() - 1);
                    else p.getInventory().setItemInMainHand(null);
                    listings.add(new MarketItem(UUID.randomUUID(), p.getUniqueId(), p.getName(), sell, price));
                    p.sendMessage(msg("prefix") + " §a上架成功！价格: §e$" + String.format("%.2f", price));
                }
                case "my" -> openMy(p);
                default -> p.sendMessage(msg("prefix") + " §c用法: /market | /market sell <价格> | /market my");
            }
            return true;
        }
    }
}
