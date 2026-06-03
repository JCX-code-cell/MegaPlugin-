package com.megaplugin.module;

import com.megaplugin.MegaPlugin;
import com.megaplugin.util.DataFile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

public class ClaimModule extends MegaModule {

    private static final String GUI_TITLE     = "§8§l[ §a§l领地管理 §8§l]";
    private static final String TRUST_TITLE   = "§8§l[ §a§l信任管理 §8§l]";
    private static final String SETTINGS_TITLE= "§8§l[ §e§l领地设置 §8§l]";
    private static final int MAX_CLAIMS = 5;
    private static final int CLAIM_SIZE = 32;

    private final DataFile data;
    private final Map<UUID, Map<String, ClaimInfo>> claims = new HashMap<>();
    private final Map<UUID, Location[]> selections = new HashMap<>();
    private final Map<UUID, String> lastClickClaim = new HashMap<>();
    private final Map<UUID, String> currentClaim = new HashMap<>();
    private final Map<UUID, Long> lastParticleTime = new HashMap<>();

    // 设置开关默认值
    private static final Map<String, Boolean> DEFAULT_FLAGS = Map.of(
            "pvp",       false,
            "tnt",       false,
            "fire",      false,
            "mobDamage", false,
            "piston",    true,
            "waterFlow", true
    );

    public ClaimModule(MegaPlugin plugin) {
        super(plugin);
        data = new DataFile(plugin, "claims.yml");
    }

    /** 领地数据对象（含设置开关） */
    public static class ClaimInfo {
        public final String name, ownerName, world;
        public final UUID owner;
        public final int minX, minZ, maxX, maxZ;
        public final List<String> trusted;
        public final Map<String, Boolean> flags = new HashMap<>(DEFAULT_FLAGS);

        public ClaimInfo(String name, UUID owner, String ownerName, String world,
                         int minX, int minZ, int maxX, int maxZ, List<String> trusted) {
            this.name = name; this.owner = owner; this.ownerName = ownerName; this.world = world;
            this.minX = minX; this.minZ = minZ; this.maxX = maxX; this.maxZ = maxZ;
            this.trusted = trusted;
        }

        public boolean flag(String key) { return flags.getOrDefault(key, DEFAULT_FLAGS.getOrDefault(key, true)); }
        public void toggle(String key) { flags.put(key, !flag(key)); }
    }

    @Override
    public void onEnable() {
        registerListener();
        for (String uuidStr : data.getConfig().getKeys(false)) {
            try {
                UUID uid = UUID.fromString(uuidStr);
                Map<String, ClaimInfo> map = new HashMap<>();
                var sec = data.getConfig().getConfigurationSection(uuidStr);
                if (sec == null) continue;
                for (String key : sec.getKeys(false)) {
                    ClaimInfo ci = new ClaimInfo(
                            key, uid, sec.getString(key + ".ownerName", "?"),
                            sec.getString(key + ".world", "world"),
                            sec.getInt(key + ".minX"), sec.getInt(key + ".minZ"),
                            sec.getInt(key + ".maxX"), sec.getInt(key + ".maxZ"),
                            sec.getStringList(key + ".trusted")
                    );
                    // 加载自定义 flags
                    var fsec = sec.getConfigurationSection(key + ".flags");
                    if (fsec != null) {
                        for (String fk : fsec.getKeys(false)) ci.flags.put(fk, fsec.getBoolean(fk));
                    }
                    map.put(key, ci);
                }
                claims.put(uid, map);
            } catch (Exception ignored) {}
        }
        var cmd = plugin.getCommand("claim");
        if (cmd != null) { cmd.setExecutor(new ClaimCmd()); cmd.setTabCompleter(new ClaimTab()); }
    }

    @Override
    public void onDisable() { saveAll(); }

    private void saveAll() {
        for (var e : claims.entrySet()) {
            for (var ce : e.getValue().entrySet()) {
                ClaimInfo ci = ce.getValue();
                String path = e.getKey() + "." + ce.getKey();
                data.getConfig().set(path + ".ownerName", ci.ownerName);
                data.getConfig().set(path + ".world", ci.world);
                data.getConfig().set(path + ".minX", ci.minX);
                data.getConfig().set(path + ".minZ", ci.minZ);
                data.getConfig().set(path + ".maxX", ci.maxX);
                data.getConfig().set(path + ".maxZ", ci.maxZ);
                data.getConfig().set(path + ".trusted", ci.trusted);
                for (var fe : ci.flags.entrySet()) data.getConfig().set(path + ".flags." + fe.getKey(), fe.getValue());
            }
        }
        data.save();
    }

    // ── 检测工具 ──

    private ClaimInfo getClaimAt(Location loc) {
        for (var e1 : claims.entrySet()) {
            for (var e2 : e1.getValue().entrySet()) {
                ClaimInfo ci = e2.getValue();
                if (!ci.world.equals(loc.getWorld().getName())) continue;
                if (loc.getBlockX() >= ci.minX && loc.getBlockX() <= ci.maxX
                        && loc.getBlockZ() >= ci.minZ && loc.getBlockZ() <= ci.maxZ) return ci;
            }
        }
        return null;
    }

    private boolean canBuild(Player p, Location loc) {
        ClaimInfo ci = getClaimAt(loc);
        if (ci == null) return true;
        if (ci.owner.equals(p.getUniqueId())) return true;
        if (ci.trusted.contains(p.getUniqueId().toString())) return true;
        if (p.hasPermission("megaplugin.claim.admin")) return true;
        return false;
    }

    // ════════════════════════════════════════
    //  保护事件
    // ════════════════════════════════════════

    @EventHandler(priority = EventPriority.HIGH)
    public void onBreak(BlockBreakEvent e) {
        if (!canBuild(e.getPlayer(), e.getBlock().getLocation())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(msg("prefix") + " §c此地为他人领地，无法破坏！");
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlace(BlockPlaceEvent e) {
        if (!canBuild(e.getPlayer(), e.getBlock().getLocation())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(msg("prefix") + " §c此地为他人领地，无法放置！");
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getClickedBlock() == null) return;
        Player p = e.getPlayer();
        if (canBuild(p, e.getClickedBlock().getLocation())) return;
        Material m = e.getClickedBlock().getType();
        if (m.name().contains("CHEST") || m.name().contains("DOOR")
                || m.name().contains("GATE") || m.name().contains("BUTTON")
                || m.name().contains("LEVER") || m == Material.TRAPPED_CHEST
                || m == Material.BARREL || m.name().contains("SHULKER_BOX")) {
            e.setCancelled(true);
            p.sendMessage(msg("prefix") + " §c此地为他人领地！");
        }
    }

    // ── PVP ──
    @EventHandler(priority = EventPriority.HIGH)
    public void onPvp(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player) || !(e.getEntity() instanceof Player)) return;
        ClaimInfo ci = getClaimAt(e.getEntity().getLocation());
        if (ci != null && !ci.flag("pvp")) {
            e.setCancelled(true);
            ((Player) e.getDamager()).sendMessage(msg("prefix") + " §c此领地禁止 PVP！");
        }
    }

    // ── TNT / 爆炸 ──
    @EventHandler(priority = EventPriority.HIGH)
    public void onExplode(EntityExplodeEvent e) {
        ClaimInfo ci = getClaimAt(e.getLocation());
        if (ci != null && !ci.flag("tnt")) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockExplode(BlockExplodeEvent e) {
        ClaimInfo ci = getClaimAt(e.getBlock().getLocation());
        if (ci != null && !ci.flag("tnt")) e.setCancelled(true);
    }

    // ── 火焰 ──
    @EventHandler(priority = EventPriority.HIGH)
    public void onIgnite(BlockIgniteEvent e) {
        ClaimInfo ci = getClaimAt(e.getBlock().getLocation());
        if (ci != null && !ci.flag("fire")) e.setCancelled(true);
    }

    // ── 怪物伤害玩家 ──
    @EventHandler(priority = EventPriority.HIGH)
    public void onMobDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        if (e.getDamager() instanceof Player) return; // PVP 已处理
        if (!(e.getDamager() instanceof Monster)) return;
        ClaimInfo ci = getClaimAt(e.getEntity().getLocation());
        if (ci != null && !ci.flag("mobDamage")) e.setCancelled(true);
    }

    // ── 活塞 ──
    @EventHandler(priority = EventPriority.HIGH)
    public void onPiston(BlockPistonExtendEvent e) {
        for (Block b : e.getBlocks()) {
            ClaimInfo ci = getClaimAt(b.getLocation());
            if (ci != null && !ci.flag("piston")) { e.setCancelled(true); return; }
        }
    }
    @EventHandler(priority = EventPriority.HIGH)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        for (Block b : e.getBlocks()) {
            ClaimInfo ci = getClaimAt(b.getLocation());
            if (ci != null && !ci.flag("piston")) { e.setCancelled(true); return; }
        }
    }

    // ── 水流/岩浆流 ──
    @EventHandler(priority = EventPriority.HIGH)
    public void onFlow(BlockFromToEvent e) {
        if (e.getBlock().getType() != Material.WATER && e.getBlock().getType() != Material.LAVA) return;
        ClaimInfo ci = getClaimAt(e.getToBlock().getLocation());
        if (ci != null && !ci.flag("waterFlow")) e.setCancelled(true);
    }

    // ════════════════════════════════════════
    //  GUI
    // ════════════════════════════════════════

    public void openClaimGui(Player p) {
        Inventory inv = Bukkit.createInventory(null, 36, GUI_TITLE);
        int slot = 0;
        for (var e : claims.entrySet()) {
            for (var ce : e.getValue().entrySet()) {
                if (slot >= 27) break;
                ClaimInfo ci = ce.getValue();
                String ownerTag = ci.owner.equals(p.getUniqueId()) ? "§a(我的)" : "§7(" + ci.ownerName + ")";
                inv.setItem(slot++, createItem(Material.GRASS_BLOCK,
                        "§a§l" + ci.name,
                        "§7世界: §e" + ci.world,
                        "§7坐标: §f" + ci.minX + "~" + ci.maxX + " / " + ci.minZ + "~" + ci.maxZ,
                        "§7主人: §e" + ci.ownerName + " " + ownerTag,
                        "",
                        ci.owner.equals(p.getUniqueId()) ? "§c左键打开信任管理 §7| §e右键领地设置" : "§7被信任者才可建造"));
            }
        }
        if (slot == 0) {
            inv.setItem(13, createItem(Material.BARRIER, "§c暂无领地",
                    "§7使用 /claim create <名字> 创建领地", "§7手持 §e木斧 §7左右键选区"));
        }
        inv.setItem(31, createItem(Material.GOLDEN_AXE, "§e§l创建领地", "§7手持木斧选区后点击", "§7或使用 /claim create <名字>"));
        inv.setItem(35, createItem(Material.BARRIER, "§c§l关闭", "§7点击关闭"));
        ItemStack glass = createItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 27; i < 36; i++) { if (inv.getItem(i) == null) inv.setItem(i, glass); }
        for (int i : new int[]{0,1,2,3,4,5,6,7,8}) { if (inv.getItem(i) == null) inv.setItem(i, glass); }
        p.openInventory(inv);
    }

    private void openTrustGui(Player p, ClaimInfo ci) {
        Inventory inv = Bukkit.createInventory(null, 27, TRUST_TITLE);
        inv.setItem(0, createItem(Material.GRASS_BLOCK, "§a§l" + ci.name,
                "§7" + ci.minX + " ~ " + ci.maxX + " / " + ci.minZ + " ~ " + ci.maxZ));
        List<String> trusted = ci.trusted;
        for (int i = 0; i < trusted.size() && i < 26; i++) {
            String uuidStr = trusted.get(i);
            String name = Bukkit.getOfflinePlayer(UUID.fromString(uuidStr)).getName();
            if (name == null) name = uuidStr.substring(0, 8);
            inv.setItem(i + 1, createItem(Material.PLAYER_HEAD, "§e" + name, "§7点击移除信任"));
        }
        inv.setItem(25, createItem(Material.EMERALD, "§a§l添加信任", "§7使用 /claim trust <领地> <玩家>"));
        inv.setItem(26, createItem(Material.ARROW, "§c§l返回领地列表", "§7点击返回"));
        // 新增：设置按钮
        inv.setItem(24, createItem(Material.COMPARATOR, "§e§l领地设置", "§7点击开关 PVP / TNT / 火焰 等"));
        p.openInventory(inv);
    }

    private void openSettingsGui(Player p, ClaimInfo ci) {
        Inventory inv = Bukkit.createInventory(null, 27, SETTINGS_TITLE);
        inv.setItem(0, createItem(Material.GRASS_BLOCK, "§a§l" + ci.name, "§7当前领地设置"));
        inv.setItem(18, createItem(Material.ARROW, "§c§l返回信任管理", "§7点击返回"));
        inv.setItem(26, createItem(Material.BARRIER, "§c§l关闭", "§7点击关闭"));

        inv.setItem(2, createFlagItem(Material.DIAMOND_SWORD, "§c§lPVP", ci.flag("pvp"),
                "§7允许玩家在领地内互相攻击"));
        inv.setItem(4, createFlagItem(Material.TNT, "§e§lTNT爆炸", ci.flag("tnt"),
                "§7允许TNT、苦力怕等爆炸破坏方块"));
        inv.setItem(6, createFlagItem(Material.FLINT_AND_STEEL, "§6§l火焰蔓延", ci.flag("fire"),
                "§7允许火焰点燃和蔓延"));
        inv.setItem(11, createFlagItem(Material.ZOMBIE_HEAD, "§2§l怪物伤害", ci.flag("mobDamage"),
                "§7允许怪物攻击领地内的玩家"));
        inv.setItem(13, createFlagItem(Material.PISTON, "§b§l活塞推动", ci.flag("piston"),
                "§7允许活塞在领地内推动方块"));
        inv.setItem(15, createFlagItem(Material.WATER_BUCKET, "§3§l水流/岩浆", ci.flag("waterFlow"),
                "§7允许水流和岩浆在领地内流动"));

        ItemStack glass = createItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++) if (inv.getItem(i) == null) inv.setItem(i, glass);
        p.openInventory(inv);
    }

    private ItemStack createFlagItem(Material mat, String name, boolean on, String... lore) {
        String status = on ? "§a§l[ 开启 ]" : "§c§l[ 关闭 ]";
        String[] full = new String[lore.length + 2];
        System.arraycopy(lore, 0, full, 0, lore.length);
        full[lore.length] = "";
        full[lore.length + 1] = "§7当前: " + status + " §7(点击切换)";
        return createItem(mat, name, full);
    }

    @EventHandler
    public void onGuiClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        String t = e.getView().getTitle();
        if (!t.equals(GUI_TITLE) && !t.equals(TRUST_TITLE) && !t.equals(SETTINGS_TITLE)) return;
        e.setCancelled(true);
        ItemStack item = e.getCurrentItem();
        if (item == null || item.getType() == Material.AIR) return;
        ItemMeta m = item.getItemMeta();
        if (m == null || !m.hasDisplayName()) return;
        String name = m.getDisplayName();

        if (t.equals(GUI_TITLE)) {
            switch (name) {
                case "§e§l创建领地" -> {
                    p.closeInventory();
                    p.sendMessage(msg("prefix") + " §7手持 §e木斧 §7左右键选区后输入 §e/claim create <名字>");
                }
                case "§c§l关闭", "§c暂无领地" -> p.closeInventory();
                default -> {
                    String claimName = name.replace("§a§l", "");
                    Map<String, ClaimInfo> myClaims = claims.get(p.getUniqueId());
                    if (myClaims != null && myClaims.containsKey(claimName)) {
                        lastClickClaim.put(p.getUniqueId(), claimName);
                        if (e.isLeftClick()) {
                            openTrustGui(p, myClaims.get(claimName));
                        } else if (e.isRightClick()) {
                            openSettingsGui(p, myClaims.get(claimName));
                        }
                    }
                }
            }
        } else if (t.equals(TRUST_TITLE)) {
            if ("§c§l返回领地列表".equals(name)) { openClaimGui(p); return; }
            if ("§a§l添加信任".equals(name)) {
                p.closeInventory(); p.sendMessage(msg("prefix") + " §7使用 §e/claim trust <领地> <玩家>"); return;
            }
            if ("§e§l领地设置".equals(name)) {
                String cn = lastClickClaim.get(p.getUniqueId());
                if (cn != null) { var mc = claims.get(p.getUniqueId()); if (mc != null) openSettingsGui(p, mc.get(cn)); }
                return;
            }
            // 点击玩家名 → 移除信任（简化：直接关闭提示命令）
            if (name.startsWith("§e")) {
                p.closeInventory();
                String cn = lastClickClaim.get(p.getUniqueId());
                if (cn != null) p.sendMessage(msg("prefix") + " §7使用 §e/claim untrust " + cn + " <玩家> §7移除信任");
            }
        } else if (t.equals(SETTINGS_TITLE)) {
            if ("§c§l关闭".equals(name)) { p.closeInventory(); return; }
            if ("§c§l返回信任管理".equals(name)) {
                String cn = lastClickClaim.get(p.getUniqueId());
                if (cn != null) { var mc = claims.get(p.getUniqueId()); if (mc != null) openTrustGui(p, mc.get(cn)); }
                return;
            }
            String flagKey = switch (name) {
                case "§c§lPVP" -> "pvp";
                case "§e§lTNT爆炸" -> "tnt";
                case "§6§l火焰蔓延" -> "fire";
                case "§2§l怪物伤害" -> "mobDamage";
                case "§b§l活塞推动" -> "piston";
                case "§3§l水流/岩浆" -> "waterFlow";
                default -> null;
            };
            if (flagKey != null) {
                String cn = lastClickClaim.get(p.getUniqueId());
                if (cn != null) {
                    var mc = claims.get(p.getUniqueId());
                    if (mc != null) {
                        ClaimInfo ci = mc.get(cn);
                        ci.toggle(flagKey);
                        saveAll();
                        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
                        openSettingsGui(p, ci);
                    }
                }
            }
        }
    }

    @EventHandler
    public void onGuiDrag(InventoryDragEvent e) {
        String t = e.getView().getTitle();
        if (t.equals(GUI_TITLE) || t.equals(TRUST_TITLE) || t.equals(SETTINGS_TITLE)) e.setCancelled(true);
    }

    // ════════════════════════════════════════
    //  选区工具
    // ════════════════════════════════════════

    @EventHandler
    public void onSelect(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType() != Material.WOODEN_AXE) return;
        Block b = e.getClickedBlock();
        if (b == null) return;
        Location[] sel = selections.computeIfAbsent(p.getUniqueId(), k -> new Location[2]);
        if (e.getAction().name().contains("LEFT")) {
            sel[0] = b.getLocation();
            p.sendMessage(msg("prefix") + " §a位置1: §e" + b.getX() + "§7, §e" + b.getZ());
        } else if (e.getAction().name().contains("RIGHT")) {
            sel[1] = b.getLocation();
            p.sendMessage(msg("prefix") + " §a位置2: §e" + b.getX() + "§7, §e" + b.getZ());
            if (sel[0] != null) {
                int dx = Math.abs(sel[1].getBlockX() - sel[0].getBlockX()) + 1;
                int dz = Math.abs(sel[1].getBlockZ() - sel[0].getBlockZ()) + 1;
                p.sendMessage(msg("prefix") + " §7选区大小: §e" + dx + "x" + dz + " §7(最大 " + CLAIM_SIZE + "x" + CLAIM_SIZE + ")");
            }
        }
    }

    // ════════════════════════════════════════
    //  命令
    // ════════════════════════════════════════

    private class ClaimCmd implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!(s instanceof Player p)) { s.sendMessage(msg("player-only")); return true; }
            if (a.length == 0 || (a.length == 0 && l.equalsIgnoreCase("claim"))) { openClaimGui(p); return true; }
            switch (a[0].toLowerCase()) {
                case "create" -> {
                    if (a.length < 2) { p.sendMessage(msg("prefix") + " §c用法: /claim create <名字>"); return true; }
                    Location[] sel = selections.get(p.getUniqueId());
                    if (sel == null || sel[0] == null || sel[1] == null) { p.sendMessage(msg("prefix") + " §c请先手持 §e木斧 §c左右键选区！"); return true; }
                    int minX = Math.min(sel[0].getBlockX(), sel[1].getBlockX());
                    int maxX = Math.max(sel[0].getBlockX(), sel[1].getBlockX());
                    int minZ = Math.min(sel[0].getBlockZ(), sel[1].getBlockZ());
                    int maxZ = Math.max(sel[0].getBlockZ(), sel[1].getBlockZ());
                    if (maxX - minX > CLAIM_SIZE || maxZ - minZ > CLAIM_SIZE) { p.sendMessage(msg("prefix") + " §c选区不能超过 " + CLAIM_SIZE + "x" + CLAIM_SIZE + "！"); return true; }
                    Map<String, ClaimInfo> myC = claims.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>());
                    if (myC.size() >= (p.hasPermission("megaplugin.claim.admin") ? 20 : MAX_CLAIMS)) { p.sendMessage(msg("prefix") + " §c你只能拥有 " + MAX_CLAIMS + " 个领地！"); return true; }
                    World w = p.getWorld();
                    for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
                        ClaimInfo ex = getClaimAt(new Location(w, x, 0, z));
                        if (ex != null) { p.sendMessage(msg("prefix") + " §c此区域与领地 §e" + ex.name + " §c重叠！"); return true; }
                    }
                    String name = a[1];
                    ClaimInfo ci = new ClaimInfo(name, p.getUniqueId(), p.getName(), w.getName(), minX, minZ, maxX, maxZ, new ArrayList<>());
                    myC.put(name, ci); saveAll(); selections.remove(p.getUniqueId());
                    p.sendMessage(msg("prefix") + " §a领地 §e" + name + " §a创建成功！(" + (maxX-minX+1) + "x" + (maxZ-minZ+1) + ")");
                }
                case "trust" -> {
                    if (a.length < 3) { p.sendMessage(msg("prefix") + " §c用法: /claim trust <领地> <玩家>"); return true; }
                    Map<String, ClaimInfo> myC = claims.get(p.getUniqueId());
                    if (myC == null) { p.sendMessage(msg("prefix") + " §c你没有领地！"); return true; }
                    ClaimInfo ci = myC.get(a[1]);
                    if (ci == null) { p.sendMessage(msg("prefix") + " §c领地不存在！"); return true; }
                    Player target = Bukkit.getPlayer(a[2]);
                    if (target == null) { p.sendMessage(msg("player-not-found")); return true; }
                    if (!ci.trusted.contains(target.getUniqueId().toString())) {
                        ci.trusted.add(target.getUniqueId().toString()); saveAll();
                        p.sendMessage(msg("prefix") + " §a已将 §e" + target.getName() + " §a添加为信任玩家");
                        target.sendMessage(msg("prefix") + " §a你已被 §e" + p.getName() + " §a添加到领地 §e" + ci.name);
                    } else { p.sendMessage(msg("prefix") + " §e" + target.getName() + " §7已是信任玩家"); }
                }
                case "untrust" -> {
                    if (a.length < 3) { p.sendMessage(msg("prefix") + " §c用法: /claim untrust <领地> <玩家>"); return true; }
                    Map<String, ClaimInfo> myC = claims.get(p.getUniqueId());
                    if (myC == null) return true;
                    ClaimInfo ci = myC.get(a[1]);
                    if (ci == null) return true;
                    Player target = Bukkit.getPlayer(a[2]);
                    String uuid = target != null ? target.getUniqueId().toString() : a[2];
                    if (ci.trusted.remove(uuid)) { saveAll(); p.sendMessage(msg("prefix") + " §7已移除信任: §e" + (target != null ? target.getName() : a[2])); }
                }
                case "remove" -> {
                    if (a.length < 2) { p.sendMessage(msg("prefix") + " §c用法: /claim remove <领地>"); return true; }
                    Map<String, ClaimInfo> myC = claims.get(p.getUniqueId());
                    if (myC == null) return true;
                    if (myC.remove(a[1]) != null) { data.getConfig().set(p.getUniqueId().toString() + "." + a[1], null); saveAll(); p.sendMessage(msg("prefix") + " §c领地 §e" + a[1] + " §c已删除"); }
                }
                case "list" -> openClaimGui(p);
                default -> { p.sendMessage(msg("prefix") + " §7/claim create|trust|untrust|remove|list"); p.sendMessage(msg("prefix") + " §7手持 §e木斧 §7左右键选区，右键打开领地菜单"); }
            }
            return true;
        }
    }

    private class ClaimTab implements TabCompleter {
        @Override
        public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
            if (!(s instanceof Player p)) return Collections.emptyList();
            if (a.length == 1) return Arrays.asList("create", "trust", "untrust", "remove", "list").stream().filter(x -> x.startsWith(a[0].toLowerCase())).collect(Collectors.toList());
            if (a.length == 2 && (a[0].equalsIgnoreCase("trust") || a[0].equalsIgnoreCase("untrust") || a[0].equalsIgnoreCase("remove"))) {
                Map<String, ClaimInfo> myC = claims.get(p.getUniqueId());
                if (myC == null) return Collections.emptyList();
                return myC.keySet().stream().filter(x -> x.startsWith(a[1].toLowerCase())).collect(Collectors.toList());
            }
            if (a.length == 3 && (a[0].equalsIgnoreCase("trust") || a[0].equalsIgnoreCase("untrust"))) {
                return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(x -> x.toLowerCase().startsWith(a[2].toLowerCase())).collect(Collectors.toList());
            }
            return Collections.emptyList();
        }
    }

    private ItemStack createItem(Material mat, String name, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta m = item.getItemMeta();
        if (m != null) { m.setDisplayName(name); if (lore.length > 0) m.setLore(Arrays.asList(lore)); item.setItemMeta(m); }
        return item;
    }

    // ════════════════════════════════════════
    //  进出提示 + 粒子
    // ════════════════════════════════════════

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (e.getFrom().getBlockX() == e.getTo().getBlockX() && e.getFrom().getBlockZ() == e.getTo().getBlockZ()) return;
        if (p.getInventory().getItemInMainHand().getType() == Material.WOODEN_AXE) {
            Long last = lastParticleTime.get(p.getUniqueId());
            if (last == null || System.currentTimeMillis() - last > 500) {
                lastParticleTime.put(p.getUniqueId(), System.currentTimeMillis());
                ClaimInfo near = getClaimAt(p.getLocation());
                if (near != null) showClaimParticles(p, near);
            }
        }
        ClaimInfo at = getClaimAt(p.getLocation());
        String newName = at != null ? at.name : null;
        String oldName = currentClaim.get(p.getUniqueId());
        if (Objects.equals(oldName, newName)) return;
        currentClaim.put(p.getUniqueId(), newName);
        if (newName != null && oldName == null) {
            String ownerTag = at.owner.equals(p.getUniqueId()) ? "§a" : at.trusted.contains(p.getUniqueId().toString()) ? "§e" : "§c";
            p.showTitle(Title.title(
                    Component.text(at.name, NamedTextColor.GREEN),
                    Component.text(ownerTag + "主人: " + at.ownerName, NamedTextColor.GRAY),
                    Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(2), Duration.ofMillis(500))
            ));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.5f, 1.5f);
        } else if (newName == null && oldName != null) {
            p.showTitle(Title.title(
                    Component.empty().color(NamedTextColor.DARK_GRAY).append(text("§7离开领地")),
                    Component.text(""),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(1), Duration.ofMillis(300))
            ));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.3f, 0.8f);
        }
    }

    private void showClaimParticles(Player p, ClaimInfo ci) {
        World w = p.getWorld();
        if (!w.getName().equals(ci.world)) return;
        int y = p.getLocation().getBlockY();
        for (int x = ci.minX; x <= ci.maxX; x += 2) { spawnParticle(p, x, y, ci.minZ); spawnParticle(p, x, y, ci.maxZ); }
        for (int z = ci.minZ; z <= ci.maxZ; z += 2) { spawnParticle(p, ci.minX, y, z); spawnParticle(p, ci.maxX, y, z); }
        for (int dy = -1; dy <= 2; dy++) {
            spawnParticle(p, ci.minX, y + dy, ci.minZ); spawnParticle(p, ci.maxX, y + dy, ci.minZ);
            spawnParticle(p, ci.minX, y + dy, ci.maxZ); spawnParticle(p, ci.maxX, y + dy, ci.maxZ);
        }
    }
    private void spawnParticle(Player p, int x, int y, int z) {
        p.spawnParticle(Particle.END_ROD, x + 0.5, y + 0.5, z + 0.5, 1, 0, 0, 0, 0);
    }
    private net.kyori.adventure.text.Component text(String s) {
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(s);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        selections.remove(id); lastClickClaim.remove(id); currentClaim.remove(id); lastParticleTime.remove(id);
    }
}
