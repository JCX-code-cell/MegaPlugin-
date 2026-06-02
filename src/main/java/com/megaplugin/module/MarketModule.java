package com.megaplugin.module;

import com.megaplugin.MegaPlugin;
import com.megaplugin.util.DataFile;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

public class MarketModule extends MegaModule {

    private static final String MARKET_TITLE = "§8§l[ §6§l玩家市场 §8§l] ";
    private static final String MY_ITEMS_TITLE = "§8§l[ §b§l我的商品 §8§l] ";
    private static final String CONFIRM_TITLE = "§8§l[ §e§l确认购买 §8§l] ";
    private static final int ITEMS_PER_PAGE = 45;
    private static final int MAX_LISTINGS = 36;

    private final DataFile marketData;
    private final List<MarketItem> listings = new ArrayList<>();
    private final Map<UUID, Integer> browsingPage = new HashMap<>();
    private final Map<UUID, MarketItem> pendingBuy = new HashMap<>();

    public MarketModule(MegaPlugin plugin) {
        super(plugin);
        marketData = new DataFile(plugin, "market.yml");
    }

    @Override
    public void onEnable() {
        registerListener();
        var cmd = plugin.getCommand("market");
        if (cmd != null) cmd.setExecutor(new MarketCmd());

        // Load listings
        var itemsSection = marketData.getConfig().getConfigurationSection("items");
        if (itemsSection != null) {
            for (String key : itemsSection.getKeys(false)) {
                try {
                    var sec = itemsSection.getConfigurationSection(key);
                    if (sec == null) continue;
                    UUID id = UUID.fromString(key);
                    UUID seller = UUID.fromString(sec.getString("seller", ""));
                    String sellerName = sec.getString("sellerName", "未知");
                    double price = sec.getDouble("price", 0);
                    ItemStack item = sec.getItemStack("item");
                    if (item != null && seller != null) {
                        listings.add(new MarketItem(id, seller, sellerName, item, price));
                    }
                } catch (Exception ignored) {}
            }
        }
    }

    @Override
    public void onDisable() {
        for (int i = 0; i < listings.size(); i++) {
            MarketItem mi = listings.get(i);
            String path = "items." + mi.id.toString();
            marketData.getConfig().set(path + ".seller", mi.seller.toString());
            marketData.getConfig().set(path + ".sellerName", mi.sellerName);
            marketData.getConfig().set(path + ".price", mi.price);
            marketData.getConfig().set(path + ".item", mi.item);
        }
        // Clean removed items
        var section = marketData.getConfig().getConfigurationSection("items");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                try {
                    UUID id = UUID.fromString(key);
                    if (listings.stream().noneMatch(mi -> mi.id.equals(id))) {
                        marketData.getConfig().set("items." + key, null);
                    }
                } catch (Exception ignored) {}
            }
        }
        marketData.save();
    }

    private record MarketItem(UUID id, UUID seller, String sellerName, ItemStack item, double price) {}

    private ItemStack createButton(Material mat, String name, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore.length > 0) meta.setLore(Arrays.asList(lore));
            item.setItemMeta(meta);
        }
        return item;
    }

    private void openMarket(Player player, int page) {
        List<MarketItem> active = new ArrayList<>(listings);
        int totalPages = Math.max(1, (active.size() + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE);
        page = Math.max(0, Math.min(page, totalPages - 1));
        browsingPage.put(player.getUniqueId(), page);

        Inventory inv = Bukkit.createInventory(null, 54, MARKET_TITLE + "§7第 " + (page + 1) + "/" + totalPages + " 页");

        int start = page * ITEMS_PER_PAGE;
        int end = Math.min(start + ITEMS_PER_PAGE, active.size());
        for (int i = start; i < end; i++) {
            MarketItem mi = active.get(i);
            ItemStack display = mi.item.clone();
            ItemMeta meta = display.getItemMeta();
            if (meta == null) meta = Bukkit.getItemFactory().getItemMeta(display.getType());
            List<String> lore = meta.hasLore() ? meta.getLore() : new ArrayList<>();
            if (lore == null) lore = new ArrayList<>();
            lore.add("");
            lore.add("§8----------------");
            lore.add("§e价格: §a$" + String.format("%.2f", mi.price));
            lore.add("§7卖家: §f" + mi.sellerName);
            lore.add("§7点击购买");
            lore.add("§8----------------");
            meta.setLore(lore);
            display.setItemMeta(meta);
            inv.setItem(i - start, display);
        }

        // Navigation buttons
        inv.setItem(45, createButton(Material.ARROW, "§e§l上一页", "§7点击返回上一页"));
        inv.setItem(46, createButton(Material.BARRIER, "§c§l关闭", "§7点击关闭市场"));
        inv.setItem(47, createButton(Material.CHEST, "§b§l我的商品", "§7查看我上架的商品"));
        inv.setItem(49, createButton(Material.GOLD_INGOT, "§6§l玩家市场", "§7共 " + active.size() + " 件商品"));
        inv.setItem(52, createButton(Material.EMERALD, "§a§l上架物品", "§7手持物品输入 /market sell <价格>"));
        inv.setItem(53, createButton(Material.ARROW, "§e§l下一页", "§7点击前往下一页"));

        // Fill empty slots with glass
        ItemStack glass = createButton(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 48; i < 54; i++) {
            if (inv.getItem(i) == null) inv.setItem(i, glass);
        }

        player.openInventory(inv);
    }

    private void openMyItems(Player player) {
        List<MarketItem> myItems = new ArrayList<>();
        for (MarketItem mi : listings) {
            if (mi.seller.equals(player.getUniqueId())) myItems.add(mi);
        }

        int rows = Math.min(6, Math.max(1, (myItems.size() + 8) / 9 + 1));
        Inventory inv = Bukkit.createInventory(null, rows * 9, MY_ITEMS_TITLE + "§7" + myItems.size() + " 件");

        for (int i = 0; i < myItems.size(); i++) {
            MarketItem mi = myItems.get(i);
            ItemStack display = mi.item.clone();
            ItemMeta meta = display.getItemMeta();
            if (meta == null) meta = Bukkit.getItemFactory().getItemMeta(display.getType());
            List<String> lore = meta.hasLore() ? meta.getLore() : new ArrayList<>();
            if (lore == null) lore = new ArrayList<>();
            lore.add("");
            lore.add("§8----------------");
            lore.add("§e价格: §a$" + String.format("%.2f", mi.price));
            lore.add("§c§l点击下架");
            lore.add("§8----------------");
            meta.setLore(lore);
            display.setItemMeta(meta);
            inv.setItem(i, display);
        }

        player.openInventory(inv);
    }

    private void openConfirm(Player player, MarketItem mi) {
        pendingBuy.put(player.getUniqueId(), mi);
        Inventory inv = Bukkit.createInventory(null, 27, CONFIRM_TITLE + mi.item.getType().name());

        inv.setItem(11, createButton(Material.GREEN_WOOL, "§a§l确认购买", "§7价格: §e$" + String.format("%.2f", mi.price)));
        inv.setItem(13, mi.item.clone());
        inv.setItem(15, createButton(Material.RED_WOOL, "§c§l取消", "§7点击取消购买"));

        ItemStack glass = createButton(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++) if (inv.getItem(i) == null) inv.setItem(i, glass);

        player.openInventory(inv);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        String title = e.getView().getTitle();

        if (title.startsWith(MARKET_TITLE)) {
            e.setCancelled(true);
            ItemStack item = e.getCurrentItem();
            if (item == null || item.getType() == Material.AIR) return;
            int slot = e.getRawSlot();

            if (slot == 45) { // Prev page
                int page = browsingPage.getOrDefault(p.getUniqueId(), 0) - 1;
                openMarket(p, page);
            } else if (slot == 46) { // Close
                p.closeInventory();
            } else if (slot == 47) { // My items
                openMyItems(p);
            } else if (slot == 53) { // Next page
                int page = browsingPage.getOrDefault(p.getUniqueId(), 0) + 1;
                openMarket(p, page);
            } else if (slot < ITEMS_PER_PAGE) {
                int page = browsingPage.getOrDefault(p.getUniqueId(), 0);
                int index = page * ITEMS_PER_PAGE + slot;
                if (index < listings.size()) {
                    MarketItem mi = listings.get(index);
                    if (mi.seller.equals(p.getUniqueId())) {
                        p.sendMessage(msg("prefix") + " §c你不能购买自己的商品！");
                        return;
                    }
                    openConfirm(p, mi);
                }
            }
            return;
        }

        if (title.startsWith(MY_ITEMS_TITLE)) {
            e.setCancelled(true);
            ItemStack item = e.getCurrentItem();
            if (item == null || item.getType() == Material.AIR) return;
            int slot = e.getRawSlot();

            List<MarketItem> myItems = new ArrayList<>();
            for (MarketItem mi : listings) {
                if (mi.seller.equals(p.getUniqueId())) myItems.add(mi);
            }
            if (slot < myItems.size()) {
                MarketItem mi = myItems.get(slot);
                listings.remove(mi);
                HashMap<Integer, ItemStack> leftover = p.getInventory().addItem(mi.item);
                if (!leftover.isEmpty()) {
                    for (ItemStack left : leftover.values()) {
                        p.getWorld().dropItemNaturally(p.getLocation(), left);
                    }
                }
                p.sendMessage(msg("prefix") + " §a商品已下架，物品已返还！");
                p.closeInventory();
            }
            return;
        }

        if (title.startsWith(CONFIRM_TITLE)) {
            e.setCancelled(true);
            ItemStack item = e.getCurrentItem();
            if (item == null || item.getType() == Material.AIR) return;
            int slot = e.getRawSlot();
            MarketItem mi = pendingBuy.remove(p.getUniqueId());

            if (slot == 11 && mi != null) { // Confirm
                if (!listings.contains(mi)) {
                    p.sendMessage(msg("prefix") + " §c该商品已售出或下架！");
                    p.closeInventory();
                    return;
                }
                double bal = plugin.getEconomyModule().getBalance(p);
                if (bal < mi.price) {
                    p.sendMessage(msg("prefix") + " §c余额不足！需要 §e$" + String.format("%.2f", mi.price) + " §c，你只有 §e$" + String.format("%.2f", bal));
                    p.closeInventory();
                    return;
                }
                if (p.getInventory().firstEmpty() == -1) {
                    p.sendMessage(msg("prefix") + " §c背包已满！请先清理背包。");
                    p.closeInventory();
                    return;
                }

                // Transaction
                plugin.getEconomyModule().withdraw(p, mi.price);
                Player seller = Bukkit.getPlayer(mi.seller);
                if (seller != null && seller.isOnline()) {
                    plugin.getEconomyModule().deposit(seller, mi.price);
                    seller.sendMessage(msg("prefix") + " §a你的商品 §e" + mi.item.getType().name() + "x" + mi.item.getAmount() + " §a已售出！收入 §e$" + String.format("%.2f", mi.price));
                } else {
                    // Offline seller: store in pending
                    double pending = marketData.getConfig().getDouble("pending." + mi.seller.toString(), 0);
                    marketData.getConfig().set("pending." + mi.seller.toString(), pending + mi.price);
                    marketData.save();
                }

                listings.remove(mi);
                p.getInventory().addItem(mi.item.clone());
                p.sendMessage(msg("prefix") + " §a购买成功！花费 §e$" + String.format("%.2f", mi.price));
                p.closeInventory();
            } else { // Cancel
                p.closeInventory();
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent e) {
        String title = e.getView().getTitle();
        if (title.startsWith(MARKET_TITLE) || title.startsWith(MY_ITEMS_TITLE) || title.startsWith(CONFIRM_TITLE)) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent e) {
        if (e.getView().getTitle().startsWith(CONFIRM_TITLE)) {
            pendingBuy.remove(e.getPlayer().getUniqueId());
        }
    }

    private class MarketCmd implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (!(sender instanceof Player p)) { sender.sendMessage(msg("player-only")); return true; }
            if (!p.hasPermission("megaplugin.market")) { p.sendMessage(msg("no-permission")); return true; }

            if (args.length == 0) {
                openMarket(p, 0);
                return true;
            }

            String sub = args[0].toLowerCase();
            switch (sub) {
                case "sell" -> {
                    if (!p.hasPermission("megaplugin.market.sell")) { p.sendMessage(msg("no-permission")); return true; }
                    if (args.length < 2) { p.sendMessage(msg("prefix") + " §cUsage: /market sell <price>"); return true; }
                    double price;
                    try { price = Double.parseDouble(args[1]); } catch (NumberFormatException e) {
                        p.sendMessage(msg("invalid-number")); return true;
                    }
                    if (price <= 0) { p.sendMessage(msg("prefix") + " §c价格必须大于0！"); return true; }

                    ItemStack hand = p.getInventory().getItemInMainHand();
                    if (hand.getType() == Material.AIR) {
                        p.sendMessage(msg("prefix") + " §c你必须手持一个物品！"); return true;
                    }

                    // Count my listings
                    long myCount = listings.stream().filter(mi -> mi.seller.equals(p.getUniqueId())).count();
                    if (myCount >= MAX_LISTINGS) {
                        p.sendMessage(msg("prefix") + " §c你最多只能上架 " + MAX_LISTINGS + " 件商品！"); return true;
                    }

                    ItemStack toSell = hand.clone();
                    toSell.setAmount(1);
                    if (hand.getAmount() > 1) {
                        hand.setAmount(hand.getAmount() - 1);
                    } else {
                        p.getInventory().setItemInMainHand(null);
                    }

                    MarketItem mi = new MarketItem(UUID.randomUUID(), p.getUniqueId(), p.getName(), toSell, price);
                    listings.add(mi);
                    p.sendMessage(msg("prefix") + " §a上架成功！§e" + toSell.getType().name() + " §a价格: §e$" + String.format("%.2f", price));
                }
                case "my" -> openMyItems(p);
                default -> p.sendMessage(msg("prefix") + " §cUsage: /market | /market sell <price> | /market my");
            }
            return true;
        }
    }
}
