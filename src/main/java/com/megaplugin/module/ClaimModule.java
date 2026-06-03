package com.megaplugin.module;

import com.megaplugin.MegaPlugin;
import com.megaplugin.util.Color;
import com.megaplugin.util.DataFile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.type.Chest;
import org.bukkit.block.data.type.Dispenser;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.projectiles.ProjectileSource;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 领地系统 v4 — 基于 GriefPrevention 源码架构重写
 *
 * 核心架构 (对照 GP):
 * ┌─ EventHandler ── checkProtection(player, loc, perm) ─────┐
 * │                          │                               │
 * │                    ┌──────▼──────────┐                   │
 * │                    │ 1. world enabled?│                  │
 * │                    │ 2. ignoreClaims? │← admin bypass    │
 * │                    │ 3. getClaimAt()  │← chunk hash 索引  │
 * │                    │ 4. claim.check() │← 权限层级检查      │
 * │                    └──────┬──────────┘                   │
 * │                     null=允许 / msg=拒绝                   │
 * └─────────────────────────────────────────────────────────┘
 *
 * 权限层级: OWNER > MANAGE > BUILD > CONTAINER > ACCESS
 * (对照 GP: Edit > Manage > Build > Container > Access)
 */
public class ClaimModule extends MegaModule {

    // ── GUI 标题 ──
    private static final String GUI_MAIN     = "§8§l[ §a§l我的领地 §8§l]";
    private static final String GUI_SETTINGS = "§8§l[ §e§l领地设置 §8§l]";
    private static final String GUI_MEMBERS  = "§8§l[ §b§l成员管理 §8§l]";
    private static final String GUI_MAP      = "§8§l[ §d§l领地地图 §8§l]";
    private static final String GUI_BUY      = "§8§l[ §2§l领地商店 §8§l]";

    private static final int MAX_CLAIMS = 5;
    private static final int MAX_CLAIM_SIZE = 64;

    // ════════════════════════════════════════
    //  权限层级 (对照 GP ClaimPermission)
    // ════════════════════════════════════════
    /** Edit(OWENR) > Manage > Build > Container > Access */
    public enum Perm {
        ACCESS,      // 开门/按钮/拉杆/床  (GP: Access)
        CONTAINER,   // 容器/铁砧/动物     (GP: Container, includes Access)
        BUILD,       // 建造/破坏          (GP: Build, includes Container+Access)
        MANAGE,      // 管理成员/设置       (GP: Manage, includes Build+Container+Access)
        OWNER;       // 一切权限           (GP: Edit, not grantable)

        /** 当前权限是否被对方权限授予 (include 低层级) */
        public boolean isGrantedBy(Perm other) {
            return other != null && other.ordinal() <= this.ordinal();
        }

        public String display() {
            return switch(this) {
                case OWNER -> "主人"; case MANAGE -> "管理";
                case BUILD -> "建筑"; case CONTAINER -> "容器";
                case ACCESS -> "访客";
            };
        }
    }

    // ════════════════════════════════════════
    //  数据
    // ════════════════════════════════════════
    private final DataFile data;
    private final Map<String, Claim> claims = new LinkedHashMap<>();       // id → claim
    private final Map<Long, List<Claim>> chunkIndex = new ConcurrentHashMap<>(); // chunkHash → claims
    private final Map<UUID, PlayerData> playerDataMap = new ConcurrentHashMap<>(); // 玩家缓存

    private final Map<UUID, Location[]> selections = new HashMap<>();     // 选区
    private final Map<UUID, String> lastClaimId = new HashMap<>();        // GUI 上下文
    private final Map<UUID, String> currentClaim = new HashMap<>();       // 进出检测
    private final Map<UUID, Long> lastParticle = new HashMap<>();         // 粒子冷却

    // ── 玩家数据 (对照 GP PlayerData) ──
    private static class PlayerData {
        Claim lastClaim;
        boolean ignoreClaims; // 管理员模式，跳过所有领地保护
        Set<UUID> invitedBy = new HashSet<>();
    }

    // ════════════════════════════════════════
    //  领地模型 (简化自 GP Claim)
    // ════════════════════════════════════════
    public static class Claim {
        public String id, name, world, enterMsg = "", leaveMsg = "";
        public UUID owner;
        public String ownerName;
        public int minX, minY = -64, minZ, maxX, maxY = 319, maxZ;
        public Location spawn;
        public double price = 0;
        public boolean allowExplosions = false;

        /** 显式权限映射: UUID字符串 → Perm */
        public final Map<String, Perm> perms = new LinkedHashMap<>();

        public Claim() {}
        public Claim(String id, String name, UUID owner, String ownerName, String world,
                     int minX, int minZ, int maxX, int maxZ) {
            this.id = id; this.name = name; this.owner = owner; this.ownerName = ownerName;
            this.world = world; this.minX = minX; this.minZ = minZ; this.maxX = maxX; this.maxZ = maxZ;
        }

        /** 检查位置是否在领地内 */
        public boolean contains(Location loc) {
            return loc.getWorld() != null && loc.getWorld().getName().equals(world)
                    && loc.getBlockX() >= minX && loc.getBlockX() <= maxX
                    && loc.getBlockY() >= minY && loc.getBlockY() <= maxY
                    && loc.getBlockZ() >= minZ && loc.getBlockZ() <= maxZ;
        }

        /** 获取玩家的显式权限 */
        public Perm getPermission(UUID uid) {
            if (owner.equals(uid)) return Perm.OWNER;
            return perms.get(uid.toString());
        }

        public boolean isOwnerOrAbove(UUID uid, Perm required) {
            Perm has = getPermission(uid);
            return has != null && required.isGrantedBy(has);
        }

        /**
         * 核心权限检查 (对照 GP Claim.checkPermission)
         *
         * @return null = 允许; String = 拒绝原因
         */
        public String checkPermission(UUID uid, String playerName, Perm required) {
            // 1. 主人无条件允许
            if (owner.equals(uid)) return null;

            // 2. 检查显式授予的权限 (MANAGE包含BUILD, BUILD包含CONTAINER等)
            Perm has = getPermission(uid);
            if (has != null && required.isGrantedBy(has)) return null;

            // 3. 公开权限
            Perm pub = perms.get("__public__");
            if (pub != null && required.isGrantedBy(pub)) return null;

            // 4. 拒绝
            return "§c你没有 " + required.display() + " 权限！领地主人: §e" + ownerName;
        }
    }

    // ════════════════════════════════════════
    //  初始化
    // ════════════════════════════════════════
    public ClaimModule(MegaPlugin plugin) {
        super(plugin);
        data = new DataFile(plugin, "claims_v2.yml");
    }

    @Override
    public void onEnable() {
        loadClaims();
        rebuildChunkIndex();
        registerListener();
        var cmd = plugin.getCommand("claim");
        if (cmd != null) { cmd.setExecutor(new ClaimCmd()); cmd.setTabCompleter(new ClaimTab()); }
        plugin.getLogger().info("[Claim] v4 加载完成 (" + claims.size() + " 个领地, 基于 GriefPrevention 架构)");
    }

    @Override
    public void onDisable() {
        saveAll();
        playerDataMap.clear();
    }

    // ── 数据加载/保存 ──
    private void loadClaims() {
        migrateOldData();
        var cfg = data.getConfig();
        for (String key : cfg.getKeys(false)) {
            try {
                Claim c = new Claim();
                c.id = key;
                c.name = cfg.getString(key + ".name", key);
                c.owner = UUID.fromString(cfg.getString(key + ".owner", ""));
                c.ownerName = cfg.getString(key + ".ownerName", "?");
                c.world = cfg.getString(key + ".world", "world");
                c.minX = cfg.getInt(key + ".minX"); c.minZ = cfg.getInt(key + ".minZ");
                c.maxX = cfg.getInt(key + ".maxX"); c.maxZ = cfg.getInt(key + ".maxZ");
                c.minY = cfg.getInt(key + ".minY", -64); c.maxY = cfg.getInt(key + ".maxY", 319);
                c.enterMsg = cfg.getString(key + ".enterMsg", "");
                c.leaveMsg = cfg.getString(key + ".leaveMsg", "");
                c.price = cfg.getDouble(key + ".price", 0);
                c.allowExplosions = cfg.getBoolean(key + ".allowExplosions", false);
                if (cfg.contains(key + ".spawn")) {
                    var s = cfg.getConfigurationSection(key + ".spawn");
                    if (s != null) c.spawn = new Location(Bukkit.getWorld(s.getString("world", c.world)),
                            s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                            (float) s.getDouble("yaw", 0), (float) s.getDouble("pitch", 0));
                }
                var msec = cfg.getConfigurationSection(key + ".perms");
                if (msec != null) for (String mk : msec.getKeys(false)) {
                    try { c.perms.put(mk, Perm.valueOf(msec.getString(mk, "ACCESS"))); } catch (Exception ignored) {}
                }
                claims.put(key, c);
            } catch (Exception e) {
                plugin.getLogger().warning("[Claim] 加载领地 " + key + " 失败: " + e.getMessage());
            }
        }
        plugin.getLogger().info("[Claim] 已加载 " + claims.size() + " 个领地");
    }

    private void migrateOldData() {
        DataFile oldData = new DataFile(plugin, "claims.yml");
        if (!oldData.getFile().exists()) return;
        if (data.getConfig().getKeys(false).size() > 0) return;
        boolean migrated = false;
        for (String uuidStr : oldData.getConfig().getKeys(false)) {
            try {
                UUID owner = UUID.fromString(uuidStr);
                var sec = oldData.getConfig().getConfigurationSection(uuidStr);
                if (sec == null) continue;
                for (String claimName : sec.getKeys(false)) {
                    String id = nextId();
                    Claim c = new Claim();
                    c.id = id; c.name = claimName; c.owner = owner;
                    c.ownerName = sec.getString(claimName + ".ownerName", "?");
                    c.world = sec.getString(claimName + ".world", "world");
                    c.minX = sec.getInt(claimName + ".minX"); c.minZ = sec.getInt(claimName + ".minZ");
                    c.maxX = sec.getInt(claimName + ".maxX"); c.maxZ = sec.getInt(claimName + ".maxZ");
                    c.enterMsg = sec.getString(claimName + ".enterMessage", "");
                    c.leaveMsg = sec.getString(claimName + ".leaveMessage", "");
                    for (String tid : sec.getStringList(claimName + ".trusted"))
                        try { c.perms.put(tid, Perm.BUILD); } catch (Exception ignored) {}
                    for (String bid : sec.getStringList(claimName + ".banned"))
                        try { c.perms.put(bid, Perm.ACCESS); } catch (Exception ignored) {}
                    claims.put(id, c); migrated = true;
                }
            } catch (Exception e) {
                plugin.getLogger().warning("[Claim] 迁移旧数据失败: " + e.getMessage());
            }
        }
        if (migrated) {
            plugin.getLogger().info("[Claim] 已从 claims.yml 迁移旧领地数据到 claims_v2.yml");
            saveAll();
        }
    }

    private void saveAll() {
        var cfg = data.getConfig();
        for (Claim c : claims.values()) {
            String p = c.id + ".";
            cfg.set(p + "name", c.name);
            cfg.set(p + "owner", c.owner.toString());
            cfg.set(p + "ownerName", c.ownerName);
            cfg.set(p + "world", c.world);
            cfg.set(p + "minX", c.minX); cfg.set(p + "minZ", c.minZ);
            cfg.set(p + "maxX", c.maxX); cfg.set(p + "maxZ", c.maxZ);
            cfg.set(p + "minY", c.minY); cfg.set(p + "maxY", c.maxY);
            cfg.set(p + "enterMsg", c.enterMsg);
            cfg.set(p + "leaveMsg", c.leaveMsg);
            cfg.set(p + "price", c.price);
            cfg.set(p + "allowExplosions", c.allowExplosions);
            cfg.set(p + "perms", null); // 清空后重建
            for (var e : c.perms.entrySet()) cfg.set(p + "perms." + e.getKey(), e.getValue().name());
            if (c.spawn != null) {
                cfg.set(p + "spawn.world", c.spawn.getWorld().getName());
                cfg.set(p + "spawn.x", c.spawn.getX()); cfg.set(p + "spawn.y", c.spawn.getY());
                cfg.set(p + "spawn.z", c.spawn.getZ());
                cfg.set(p + "spawn.yaw", c.spawn.getYaw()); cfg.set(p + "spawn.pitch", c.spawn.getPitch());
            }
        }
        data.save();
    }

    // ════════════════════════════════════════
    //  Chunk 索引 (对照 GP DataStore chunksToClaimsMap)
    // ════════════════════════════════════════
    private static long chunkHash(int cx, int cz) {
        return ((long)cz << 32) ^ cx;
    }

    private void rebuildChunkIndex() {
        chunkIndex.clear();
        for (Claim c : claims.values()) {
            if (c.world == null) continue;
            int minCX = c.minX >> 4, maxCX = c.maxX >> 4;
            int minCZ = c.minZ >> 4, maxCZ = c.maxZ >> 4;
            for (int cx = minCX; cx <= maxCX; cx++)
                for (int cz = minCZ; cz <= maxCZ; cz++)
                    chunkIndex.computeIfAbsent(chunkHash(cx, cz), k -> new ArrayList<>()).add(c);
        }
    }

    /** 快速查找领地 (对照 GP DataStore.getClaimAt) */
    private Claim getClaimAt(Location loc, PlayerData pd) {
        if (loc == null || loc.getWorld() == null) return null;

        // 先检查缓存
        if (pd != null && pd.lastClaim != null && pd.lastClaim.contains(loc))
            return pd.lastClaim;

        World w = loc.getWorld();
        int cx = loc.getBlockX() >> 4, cz = loc.getBlockZ() >> 4;
        List<Claim> list = chunkIndex.get(chunkHash(cx, cz));
        if (list == null) return null;

        for (Claim c : list) {
            if (c.world.equals(w.getName()) && c.contains(loc)) {
                if (pd != null) pd.lastClaim = c;
                return c;
            }
        }
        return null;
    }

    private PlayerData getPlayerData(UUID uid) {
        return playerDataMap.computeIfAbsent(uid, k -> new PlayerData());
    }

    // ════════════════════════════════════════
    //  核心保护入口 (对照 GP ProtectionHelper.checkPermission)
    // ════════════════════════════════════════
    /**
     * 统一的领地保护检查
     * @return null = 允许; String = 拒绝消息
     */
    private String checkProtection(Player p, Location loc, Perm required) {
        if (loc == null || loc.getWorld() == null) return null;

        PlayerData pd = getPlayerData(p.getUniqueId());

        // 1. 管理员模式 (ignoreClaims)
        if (p.hasPermission("megaplugin.claim.admin") || pd.ignoreClaims) return null;

        // 2. 查找领地
        Claim claim = getClaimAt(loc, pd);
        if (claim == null) return null; // 荒野允许

        // 3. 领地权限检查
        return claim.checkPermission(p.getUniqueId(), p.getName(), required);
    }

    // ── 工具方法 ──
    private List<Claim> getPlayerClaims(UUID uid) {
        return claims.values().stream().filter(c -> c.owner.equals(uid)).collect(Collectors.toList());
    }

    private String nextId() {
        int i = 1; while (claims.containsKey("claim" + i)) i++;
        return "claim" + i;
    }

    /** 判断是否为容器类方块 (对照 GP PlayerEventHandler.isInventoryHolder) */
    private boolean isContainer(Material m) {
        return Tag.SHULKER_BOXES.isTagged(m) || m == Material.CHEST || m == Material.TRAPPED_CHEST
                || m == Material.BARREL || m == Material.HOPPER || m == Material.DISPENSER
                || m == Material.DROPPER || m == Material.FURNACE || m == Material.BLAST_FURNACE
                || m == Material.SMOKER || m == Material.BREWING_STAND || m == Material.JUKEBOX
                || m == Material.ENCHANTING_TABLE || m == Material.ENDER_CHEST
                || m == Material.CRAFTER;
    }

    /** 判断是否为门/活板门类 (对照 GP 的 ACCESS 权限块) */
    private boolean isAccessBlock(Material m) {
        return Tag.DOORS.isTagged(m) || Tag.TRAPDOORS.isTagged(m)
                || Tag.FENCE_GATES.isTagged(m) || Tag.BEDS.isTagged(m)
                || Tag.BUTTONS.isTagged(m) || m == Material.LEVER;
    }

    /** 判断是否为刷怪蛋 */
    private boolean isSpawnEgg(Material m) {
        return m.name().endsWith("_SPAWN_EGG");
    }

    /** 判断是否为染料类物品 */
    private boolean isDyeItem(Material m) {
        return m.name().endsWith("_DYE");
    }

    /** 判断是否为矿车类物品 */
    private boolean isMinecartItem(Material m) {
        return m == Material.MINECART || m == Material.CHEST_MINECART
                || m == Material.FURNACE_MINECART || m == Material.TNT_MINECART
                || m == Material.HOPPER_MINECART;
    }

    /** 判断是否为船类物品 */
    private boolean isBoatItem(Material m) {
        return m.name().contains("BOAT") || m.name().equals("BAMBOO_RAFT");
    }



    // ════════════════════════════════════════
    //  事件保护 — 基于 GriefPrevention 架构
    // ════════════════════════════════════════

    // ── 选区工具 (LOWEST, 不拦截保护事件) ──
    @EventHandler(priority = EventPriority.LOWEST)
    public void onSelect(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (p.getInventory().getItemInMainHand().getType() != Material.WOODEN_AXE) return;
        Block b = e.getClickedBlock();
        if (b == null) return;

        // 木斧选区操作，不取消事件
        Location[] sel = selections.computeIfAbsent(p.getUniqueId(), k -> new Location[2]);
        if (e.getAction() == Action.LEFT_CLICK_BLOCK) {
            sel[0] = b.getLocation();
            p.sendMessage(msg("prefix") + " §a位置1: §e" + b.getX() + "§7, §e" + b.getY() + "§7, §e" + b.getZ());
            p.spawnParticle(Particle.HAPPY_VILLAGER, b.getLocation().add(0.5, 1.2, 0.5), 5, 0.3, 0.3, 0.3, 0);
            e.setCancelled(false); // 不阻止木斧左键
        } else if (e.getAction() == Action.RIGHT_CLICK_BLOCK) {
            sel[1] = b.getLocation();
            p.sendMessage(msg("prefix") + " §a位置2: §e" + b.getX() + "§7, §e" + b.getY() + "§7, §e" + b.getZ());
            p.spawnParticle(Particle.HAPPY_VILLAGER, b.getLocation().add(0.5, 1.2, 0.5), 5, 0.3, 0.3, 0.3, 0);
            e.setCancelled(false); // 不阻止木斧右键
            if (sel[0] != null) {
                int dx = Math.abs(sel[1].getBlockX() - sel[0].getBlockX()) + 1;
                int dz = Math.abs(sel[1].getBlockZ() - sel[0].getBlockZ()) + 1;
                p.sendMessage(msg("prefix") + " §7选区: §e" + dx + "x" + dz + " §7(最大 " + MAX_CLAIM_SIZE + "x" + MAX_CLAIM_SIZE + ")");
            }
        }
    }

    // ── 方块破坏 (对照 GP BlockEventHandler.onBlockBreak) ──
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        String deny = checkProtection(p, e.getBlock().getLocation(), Perm.BUILD);
        if (deny != null) {
            e.setCancelled(true);
            p.sendMessage(msg("prefix") + " " + deny);
        }
    }

    // ── 方块放置 (对照 GP BlockEventHandler.onBlockPlace) ──
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        String deny = checkProtection(p, e.getBlock().getLocation(), Perm.BUILD);
        if (deny != null) {
            e.setCancelled(true);
            p.sendMessage(msg("prefix") + " " + deny);
        }
    }

    // ── 方块交互 (对照 GP PlayerEventHandler.onPlayerInteract) ──
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (p.getInventory().getItemInMainHand().getType() == Material.WOODEN_AXE) return;

        Block b = e.getClickedBlock();
        Material item = e.getMaterial();

        // ═ RIGHT_CLICK_AIR — 物品对空气使用 ═
        if (b == null) {
            if (e.getAction() == Action.RIGHT_CLICK_AIR) {
                String deny = null;
                if (item == Material.FLINT_AND_STEEL || item == Material.FIRE_CHARGE) {
                    deny = checkProtection(p, p.getLocation(), Perm.BUILD);
                } else if (item == Material.ENDER_PEARL || item == Material.ENDER_EYE) {
                    deny = checkProtection(p, p.getLocation(), Perm.ACCESS);
                } else if (item == Material.EXPERIENCE_BOTTLE) {
                    deny = checkProtection(p, p.getLocation(), Perm.BUILD);
                }
                if (deny != null) { e.setCancelled(true); p.sendMessage(msg("prefix") + " " + deny); }
            }
            return;
        }

        Material m = b.getType();

        // ═ PHYSICAL — 踩踏农田/乌龟蛋/耕地交互 ═
        if (e.getAction() == Action.PHYSICAL) {
            if (m == Material.FARMLAND || m == Material.TURTLE_EGG) {
                String deny = checkProtection(p, b.getLocation(), Perm.BUILD);
                if (deny != null) e.setCancelled(true);
            }
            return;
        }

        // ═ LEFT_CLICK_BLOCK ═
        if (e.getAction() == Action.LEFT_CLICK_BLOCK) {
            if (m == Material.TNT || m == Material.NOTE_BLOCK || m == Material.DRAGON_EGG) {
                String deny = checkProtection(p, b.getLocation(), Perm.BUILD);
                if (deny != null) { e.setCancelled(true); p.sendMessage(msg("prefix") + " " + deny); }
            }
            return;
        }

        // ═ RIGHT_CLICK_BLOCK ═

        // ── 第一步: 手持物品检查 (对照 GP items needing Build/Container) ──
        // 骨粉/蜜脾 → BUILD (修改方块)
        if (item == Material.BONE_MEAL || item == Material.HONEYCOMB) {
            String deny = checkProtection(p, b.getLocation(), Perm.BUILD);
            if (deny != null) { e.setCancelled(true); p.sendMessage(msg("prefix") + " " + deny); return; }
        }
        // 末地水晶/打火石 → BUILD
        if (item == Material.END_CRYSTAL || item == Material.FLINT_AND_STEEL) {
            String deny = checkProtection(p, b.getLocation(), Perm.BUILD);
            if (deny != null) { e.setCancelled(true); p.sendMessage(msg("prefix") + " " + deny); return; }
        }
        // 刷怪蛋 → CONTAINER (生成生物影响领地生态)
        if (isSpawnEgg(item)) {
            String deny = checkProtection(p, b.getLocation(), Perm.CONTAINER);
            if (deny != null) { e.setCancelled(true); p.sendMessage(msg("prefix") + " " + deny); return; }
        }
        // 染料/墨囊/荧光墨囊 → CONTAINER (给羊/告示牌染色)
        if (isDyeItem(item) || item == Material.INK_SAC || item == Material.GLOW_INK_SAC) {
            String deny = checkProtection(p, b.getLocation(), Perm.CONTAINER);
            if (deny != null) { e.setCancelled(true); p.sendMessage(msg("prefix") + " " + deny); return; }
        }
        // 船 → CONTAINER
        if (isBoatItem(item)) {
            String deny = checkProtection(p, b.getLocation(), Perm.CONTAINER);
            if (deny != null) { e.setCancelled(true); p.sendMessage(msg("prefix") + " " + deny); return; }
        }
        // 矿车 → CONTAINER
        if (isMinecartItem(item)) {
            String deny = checkProtection(p, b.getLocation(), Perm.CONTAINER);
            if (deny != null) { e.setCancelled(true); p.sendMessage(msg("prefix") + " " + deny); return; }
        }
        // 盔甲架 → BUILD
        if (item == Material.ARMOR_STAND) {
            String deny = checkProtection(p, b.getLocation(), Perm.BUILD);
            if (deny != null) { e.setCancelled(true); p.sendMessage(msg("prefix") + " " + deny); return; }
        }
        // 末影之眼在传送门框架上 → BUILD
        if (item == Material.ENDER_EYE && b.getType() == Material.END_PORTAL_FRAME) {
            String deny = checkProtection(p, b.getLocation(), Perm.BUILD);
            if (deny != null) { e.setCancelled(true); p.sendMessage(msg("prefix") + " " + deny); return; }
        }
        // 拴绳栓到栅栏/墙壁 → CONTAINER
        if (item == Material.LEAD) {
            String deny = checkProtection(p, b.getLocation(), Perm.CONTAINER);
            if (deny != null) { e.setCancelled(true); p.sendMessage(msg("prefix") + " " + deny); return; }
        }
        // 命名牌使用 → CONTAINER
        if (item == Material.NAME_TAG) {
            String deny = checkProtection(p, b.getLocation(), Perm.CONTAINER);
            if (deny != null) { e.setCancelled(true); p.sendMessage(msg("prefix") + " " + deny); return; }
        }
        // 锄头耕土/土径 → BUILD
        if (item == Material.WOODEN_HOE || item == Material.STONE_HOE || item == Material.IRON_HOE
                || item == Material.GOLDEN_HOE || item == Material.DIAMOND_HOE || item == Material.NETHERITE_HOE) {
            String deny = checkProtection(p, b.getLocation(), Perm.BUILD);
            if (deny != null) { e.setCancelled(true); p.sendMessage(msg("prefix") + " " + deny); return; }
        }
        // 铲子造土径 → BUILD
        if (item == Material.WOODEN_SHOVEL || item == Material.STONE_SHOVEL || item == Material.IRON_SHOVEL
                || item == Material.GOLDEN_SHOVEL || item == Material.DIAMOND_SHOVEL || item == Material.NETHERITE_SHOVEL) {
            if (m == Material.DIRT || m == Material.GRASS_BLOCK || m == Material.PODZOL
                    || m == Material.COARSE_DIRT || m == Material.MYCELIUM) {
                String deny = checkProtection(p, b.getLocation(), Perm.BUILD);
                if (deny != null) { e.setCancelled(true); p.sendMessage(msg("prefix") + " " + deny); return; }
            }
        }

        // ── 第二步: 目标方块检查 ──
        // 1. 容器类 → CONTAINER
        if (isContainer(m)) {
            String deny = checkProtection(p, b.getLocation(), Perm.CONTAINER);
            if (deny != null) { e.setCancelled(true); p.sendMessage(msg("prefix") + " " + deny); return; }
        }

        // 2. 铁砧/信标/装饰煲/工作台类 → CONTAINER
        if (m == Material.ANVIL || m == Material.CHIPPED_ANVIL || m == Material.DAMAGED_ANVIL
                || m == Material.BEACON || m == Material.CRAFTER
                || m == Material.CARTOGRAPHY_TABLE || m == Material.GRINDSTONE
                || m == Material.LOOM || m == Material.STONECUTTER
                || m == Material.SMITHING_TABLE || m == Material.FLETCHING_TABLE
                || m == Material.CAULDRON || m == Material.WATER_CAULDRON || m == Material.LAVA_CAULDRON
                || m == Material.BEEHIVE || m == Material.BEE_NEST
                || m == Material.BELL || m == Material.COMPOSTER
                || m == Material.DECORATED_POT || m == Material.SWEET_BERRY_BUSH
                || m == Material.CAVE_VINES || m == Material.CAVE_VINES_PLANT
                || m == Material.PUMPKIN || m == Material.RESPAWN_ANCHOR) {
            String deny = checkProtection(p, b.getLocation(), Perm.CONTAINER);
            if (deny != null) { e.setCancelled(true); p.sendMessage(msg("prefix") + " " + deny); return; }
        }

        // 3. 蛋糕 → ACCESS (GP treats it as Access when preventTheft is on)
        if (m == Material.CAKE) {
            String deny = checkProtection(p, b.getLocation(), Perm.ACCESS);
            if (deny != null) { e.setCancelled(true); p.sendMessage(msg("prefix") + " " + deny); return; }
        }

        // 4. 门/床/按钮/拉杆 → ACCESS
        if (isAccessBlock(m)) {
            String deny = checkProtection(p, b.getLocation(), Perm.ACCESS);
            if (deny != null) { e.setCancelled(true); p.sendMessage(msg("prefix") + " " + deny); return; }
        }

        // 5. 红石/装饰 → BUILD
        if (m == Material.REPEATER || m == Material.COMPARATOR || m == Material.DAYLIGHT_DETECTOR
                || Tag.FLOWER_POTS.isTagged(m) || Tag.CANDLES.isTagged(m)) {
            String deny = checkProtection(p, b.getLocation(), Perm.BUILD);
            if (deny != null) { e.setCancelled(true); p.sendMessage(msg("prefix") + " " + deny); return; }
        }

        // 6. 告示牌编辑 → BUILD
        if (Tag.ALL_SIGNS.isTagged(m) || Tag.WALL_SIGNS.isTagged(m)) {
            String deny = checkProtection(p, b.getLocation(), Perm.BUILD);
            if (deny != null) { e.setCancelled(true); p.sendMessage(msg("prefix") + " " + deny); }
        }
    }

    // ── PVP (对照 GP EntityDamageHandler.handlePvpDamageByPlayer) ──
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;

        // 找出攻击者
        Player attacker = null;
        if (e.getDamager() instanceof Player p) attacker = p;
        else if (e.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player p)
            attacker = p;
        if (attacker == null) return;

        // 检查受害者位置 — PVP 需要 MANAGE 权限才允许 (仅主人/管理可PVP)
        Claim c = getClaimAt(victim.getLocation(), null);
        if (c != null) {
            String deny = c.checkPermission(attacker.getUniqueId(), attacker.getName(), Perm.BUILD);
            if (deny != null) { // 没有BUILD权限 → 不允许PVP
                e.setCancelled(true);
                attacker.sendMessage(msg("prefix") + " §c此领地禁止 PVP！");
            }
        }
    }

    // ── PVE (怪物伤害玩家; 对照 GP EntityDamageHandler) ──
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPve(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player player)) return;
        // 跳过玩家攻击(已在onPvp处理)
        if (e.getDamager() instanceof Player) return;
        if (e.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player) return;

        // 只处理怪物伤害
        if (!(e.getDamager() instanceof Monster) && !(e.getDamager() instanceof WaterMob)
                && !(e.getDamager() instanceof Slime) && !(e.getDamager() instanceof Ghast)
                && !(e.getDamager() instanceof Phantom)) return;

        // 领地内怪物不能伤害有BUILD权限的人
        Claim c = getClaimAt(player.getLocation(), null);
        if (c != null && !c.isOwnerOrAbove(player.getUniqueId(), Perm.BUILD)) {
            e.setCancelled(true);
        }
    }

    // ── 爆炸 (对照 GP EntityEventHandler.onEntityExplode) ──
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        if (e.getLocation().getWorld() == null) return;
        Claim c = getClaimAt(e.getLocation(), null);
        if (c != null && !c.allowExplosions) {
            e.blockList().removeIf(b -> {
                Claim bc = getClaimAt(b.getLocation(), null);
                return bc == c;
            });
            if (e.blockList().isEmpty()) e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        Claim c = getClaimAt(e.getBlock().getLocation(), null);
        if (c != null && !c.allowExplosions) {
            e.blockList().removeIf(b -> {
                Claim bc = getClaimAt(b.getLocation(), null);
                return bc == c;
            });
            if (e.blockList().isEmpty()) e.setCancelled(true);
        }
    }

    // ── 火焰 ──
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        String deny = null;
        if (e.getPlayer() != null) deny = checkProtection(e.getPlayer(), e.getBlock().getLocation(), Perm.BUILD);
        else {
            Claim c = getClaimAt(e.getBlock().getLocation(), null);
            if (c != null && !c.allowExplosions) deny = "§c领地禁止火焰蔓延";
        }
        if (deny != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        Claim c = getClaimAt(e.getBlock().getLocation(), null);
        if (c != null && !c.allowExplosions) e.setCancelled(true);
    }

    // ── 生物生成 ──
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.CUSTOM
                || e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.SPAWNER_EGG
                || e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.COMMAND) return;
        Claim c = getClaimAt(e.getLocation(), null);
        if (c == null) return;
        if (e.getEntity() instanceof Monster) e.setCancelled(true);
    }

    // ── 植物/树木生长不穿越领地边界 (对照 GP onTreeGrow) ──
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onGrow(StructureGrowEvent e) {
        Claim originClaim = getClaimAt(e.getLocation(), null);
        e.getBlocks().removeIf(bs -> {
            Claim destClaim = getClaimAt(bs.getLocation(), null);
            return destClaim != originClaim;
        });
    }

    // ── 流体流动不穿越领地边界 (对照 GP onBlockFromTo) ──
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        Material src = e.getBlock().getType();
        if (src != Material.WATER && src != Material.LAVA) return;
        Claim fromClaim = getClaimAt(e.getBlock().getLocation(), null);
        Claim toClaim = getClaimAt(e.getToBlock().getLocation(), null);
        if (fromClaim != toClaim) e.setCancelled(true);
    }

    // ── 活塞不穿越领地边界 (对照 GP onBlockPistonExtend) ──
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPiston(BlockPistonExtendEvent e) {
        Claim pistonClaim = getClaimAt(e.getBlock().getLocation(), null);
        for (Block b : e.getBlocks()) {
            Claim destClaim = getClaimAt(b.getRelative(e.getDirection()).getLocation(), null);
            if (destClaim != pistonClaim) { e.setCancelled(true); return; }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        Claim pistonClaim = getClaimAt(e.getBlock().getLocation(), null);
        for (Block b : e.getBlocks()) {
            Claim movingClaim = getClaimAt(b.getLocation(), null);
            if (movingClaim != pistonClaim) { e.setCancelled(true); return; }
        }
    }

    // ── 末影珍珠传送 (对照 GP onPlayerTeleport) ──
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPearl(PlayerTeleportEvent e) {
        if (e.getCause() != PlayerTeleportEvent.TeleportCause.ENDER_PEARL) return;
        String deny = checkProtection(e.getPlayer(), e.getTo(), Perm.ACCESS);
        if (deny != null) e.setCancelled(true);
    }

    // ── 物品丢弃/拾取 ──
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        String deny = checkProtection(e.getPlayer(), e.getPlayer().getLocation(), Perm.BUILD);
        if (deny != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPickup(PlayerAttemptPickupItemEvent e) {
        String deny = checkProtection(e.getPlayer(), e.getItem().getLocation(), Perm.BUILD);
        if (deny != null) e.setCancelled(true);
    }

    // ── 展示框/画 (对照 GP onHangingBreak) ──
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent e) {
        if (!(e.getRemover() instanceof Player p)) return;
        String deny = checkProtection(p, e.getEntity().getLocation(), Perm.BUILD);
        if (deny != null) { e.setCancelled(true); p.sendMessage(msg("prefix") + " " + deny); }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent e) {
        if (e.getPlayer() == null || !(e.getPlayer() instanceof Player p)) return;
        String deny = checkProtection(p, e.getEntity().getLocation(), Perm.BUILD);
        if (deny != null) { e.setCancelled(true); p.sendMessage(msg("prefix") + " " + deny); }
    }

    // ── 盔甲架 ──
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArmorStand(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof ArmorStand)) return;
        Player p = null;
        if (e.getDamager() instanceof Player) p = (Player) e.getDamager();
        else if (e.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player)
            p = (Player) proj.getShooter();
        if (p == null) return;
        String deny = checkProtection(p, e.getEntity().getLocation(), Perm.BUILD);
        if (deny != null) { e.setCancelled(true); p.sendMessage(msg("prefix") + " " + deny); }
    }

    // ── 动物伤害 (对照 GP: 需要 CONTAINER 权限) ──
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onAnimalHurt(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Animals) && !(e.getEntity() instanceof Villager)
                && !(e.getEntity() instanceof AbstractHorse) && !(e.getEntity() instanceof IronGolem)
                && !(e.getEntity() instanceof Snowman)) return;
        Player p = null;
        if (e.getDamager() instanceof Player) p = (Player) e.getDamager();
        else if (e.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player)
            p = (Player) proj.getShooter();
        if (p == null) return;
        String deny = checkProtection(p, e.getEntity().getLocation(), Perm.CONTAINER);
        if (deny != null) { e.setCancelled(true); p.sendMessage(msg("prefix") + " " + deny); }
    }

    // ── 载具 ──
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onVehicle(VehicleDestroyEvent e) {
        if (!(e.getAttacker() instanceof Player p)) return;
        String deny = checkProtection(p, e.getVehicle().getLocation(), Perm.BUILD);
        if (deny != null) { e.setCancelled(true); p.sendMessage(msg("prefix") + " " + deny); }
    }

    // ── 桶 ──
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) {
        String deny = checkProtection(e.getPlayer(), e.getBlock().getLocation(), Perm.BUILD);
        if (deny != null) { e.setCancelled(true); e.getPlayer().sendMessage(msg("prefix") + " " + deny); }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        String deny = checkProtection(e.getPlayer(), e.getBlock().getLocation(), Perm.BUILD);
        if (deny != null) { e.setCancelled(true); e.getPlayer().sendMessage(msg("prefix") + " " + deny); }
    }

    // ── 末影人/凋零等实体修改方块 ──
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent e) {
        Claim c = getClaimAt(e.getBlock().getLocation(), null);
        if (c != null && (e.getEntity() instanceof Enderman || e.getEntity() instanceof Wither
                || e.getEntity() instanceof Silverfish || e.getEntity() instanceof Ravager)) {
            e.setCancelled(true);
        }
    }

    // ── 实体交互 (对照 GP onPlayerInteractEntity) ──
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        Player p = e.getPlayer();
        Entity entity = e.getRightClicked();
        Material item = e.getPlayer().getInventory().getItemInMainHand().getType();

        // 1. 盔甲架 → BUILD
        if (entity instanceof ArmorStand) {
            String deny = checkProtection(p, entity.getLocation(), Perm.BUILD);
            if (deny != null) { e.setCancelled(true); p.sendMessage(msg("prefix") + " " + deny); }
            return;
        }

        // 2. 物品展示框/画 → BUILD
        if (entity instanceof Hanging) {
            String deny = checkProtection(p, entity.getLocation(), Perm.BUILD);
            if (deny != null) { e.setCancelled(true); p.sendMessage(msg("prefix") + " " + deny); }
            return;
        }

        // 3. 已驯服马/驴/骆驼/鹦鹉/猫/狼 — 仅主人可交互
        if (entity instanceof Tameable tame && tame.isTamed() && tame.getOwner() != null) {
            UUID owner = tame.getOwner().getUniqueId();
            if (!owner.equals(p.getUniqueId())) {
                PlayerData pd = getPlayerData(p.getUniqueId());
                if (!pd.ignoreClaims && !p.hasPermission("megaplugin.claim.admin")) {
                    e.setCancelled(true);
                    p.sendMessage(msg("prefix") + " §c这只宠物不是你的！");
                    return;
                }
            }
            return;
        }

        // 4. 动物/村民/铁傀儡/雪傀儡 → CONTAINER
        if (entity instanceof Animals || entity instanceof Villager
                || entity instanceof IronGolem || entity instanceof Snowman) {
            String deny = checkProtection(p, entity.getLocation(), Perm.CONTAINER);
            if (deny != null) { e.setCancelled(true); p.sendMessage(msg("prefix") + " " + deny); return; }
        }

        // 5. 未驯服的载具（船/矿车）→ CONTAINER
        if (entity instanceof Vehicle && !(entity instanceof Minecart)) {
            String deny = checkProtection(p, entity.getLocation(), Perm.CONTAINER);
            if (deny != null) { e.setCancelled(true); p.sendMessage(msg("prefix") + " " + deny); return; }
        }

        // 6. 拴绳使用 → CONTAINER
        if (item == Material.LEAD && !(entity instanceof LeashHitch)) {
            String deny = checkProtection(p, entity.getLocation(), Perm.CONTAINER);
            if (deny != null) { e.setCancelled(true); p.sendMessage(msg("prefix") + " " + deny); return; }
        }

        // 7. 命名牌使用 → CONTAINER
        if (item == Material.NAME_TAG) {
            String deny = checkProtection(p, entity.getLocation(), Perm.CONTAINER);
            if (deny != null) { e.setCancelled(true); p.sendMessage(msg("prefix") + " " + deny); }
        }
    }

    // ── 投掷鸡蛋 (对照 GP onPlayerThrowEgg) ──
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onThrowEgg(PlayerEggThrowEvent e) {
        String deny = checkProtection(e.getPlayer(), e.getEgg().getLocation(), Perm.CONTAINER);
        if (deny != null) {
            e.setHatching(false);
            e.getPlayer().sendMessage(msg("prefix") + " " + deny);
        }
    }

    // ── 钓鱼 (对照 GP onPlayerFish) ──
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        if (e.getState() != PlayerFishEvent.State.CAUGHT_ENTITY
                && e.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;
        if (e.getCaught() == null) return;
        Entity caught = e.getCaught();
        if (caught instanceof ArmorStand || caught instanceof Animals) {
            String deny = checkProtection(e.getPlayer(), caught.getLocation(), Perm.CONTAINER);
            if (deny != null) { e.setCancelled(true); e.getPlayer().sendMessage(msg("prefix") + " " + deny); }
        }
    }

    // ── 讲台取书 (对照 GP onPlayerTakeLecternBook) ──
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTakeLecternBook(PlayerTakeLecternBookEvent e) {
        String deny = checkProtection(e.getPlayer(), e.getLectern().getLocation(), Perm.CONTAINER);
        if (deny != null) e.setCancelled(true);
    }

    // ── 告示牌编辑 (对照 GP onSignChange) ──
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent e) {
        String deny = checkProtection(e.getPlayer(), e.getBlock().getLocation(), Perm.BUILD);
        if (deny != null) e.setCancelled(true);
    }

    // ── 喷溅药水 (对照 GP EntityDamageHandler: 有害药水对动物/村民) ──
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPotionSplash(PotionSplashEvent e) {
        if (!(e.getPotion().getShooter() instanceof Player p)) return;
        // 检查药水影响的所有生物
        boolean blocked = false;
        for (LivingEntity entity : e.getAffectedEntities()) {
            if (entity instanceof Player) continue;
            if (entity instanceof Animals || entity instanceof Villager
                    || entity instanceof IronGolem || entity instanceof Snowman) {
                String deny = checkProtection(p, entity.getLocation(), Perm.CONTAINER);
                if (deny != null) {
                    e.setIntensity(entity, 0.0);
                    blocked = true;
                }
            }
        }
        if (blocked) p.sendMessage(msg("prefix") + " §c领地内不能对驯养生物使用喷溅药水！");
    }

    // ── 炼药锅 (对照 GP CauldronLevelChange) ──
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCauldronChange(CauldronLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p) {
            String deny = checkProtection(p, e.getBlock().getLocation(), Perm.BUILD);
            if (deny != null) e.setCancelled(true);
        }
    }

    // ── 黑名单踢出 ──
    @EventHandler(priority = EventPriority.HIGH)
    public void onMoveBanned(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (e.getFrom().getBlockX() == e.getTo().getBlockX()
                && e.getFrom().getBlockZ() == e.getTo().getBlockZ()) return;
        PlayerData pd = getPlayerData(p.getUniqueId());
        if (pd.ignoreClaims) return;
        Claim c = getClaimAt(e.getTo(), pd);
        if (c == null || c.owner.equals(p.getUniqueId())) return;
        Perm perm = c.getPermission(p.getUniqueId());
        if (perm == Perm.ACCESS && c.perms.get(p.getUniqueId().toString()) == Perm.ACCESS) {
            // 检查是否被标记为 BANNED (ACCESS=黑名单在旧迁移时)
            // 我们用一个特殊标记: 如果玩家被明确设置为ACCESS且在进入时检查
        }
    }

    // ════════════════════════════════════════
    //  进出提示 + 粒子
    // ════════════════════════════════════════
    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (e.getFrom().getBlockX() == e.getTo().getBlockX()
                && e.getFrom().getBlockZ() == e.getTo().getBlockZ()) return;

        // 木斧可视化
        if (p.getInventory().getItemInMainHand().getType() == Material.WOODEN_AXE) {
            Long last = lastParticle.get(p.getUniqueId());
            if (last == null || System.currentTimeMillis() - last > 600) {
                lastParticle.put(p.getUniqueId(), System.currentTimeMillis());
                Claim near = getClaimAt(p.getLocation(), null);
                if (near != null) showClaimParticles(p, near);
            }
        }

        // 进出检测
        Claim at = getClaimAt(e.getTo(), null);
        String newId = at != null ? at.id : null;
        String oldId = currentClaim.get(p.getUniqueId());
        if (Objects.equals(oldId, newId)) return;
        currentClaim.put(p.getUniqueId(), newId);

        if (newId != null && oldId == null) {
            // 进入领地
            Perm perm = at.getPermission(p.getUniqueId());
            String roleTag = at.owner.equals(p.getUniqueId()) ? "§a[主人] "
                    : perm != null ? "§e[" + perm.display() + "] " : "§7";
            if (!at.enterMsg.isEmpty())
                p.sendMessage(Color.colorize(at.enterMsg.replace("%player%", p.getName())
                        .replace("%owner%", at.ownerName).replace("%claim%", at.name)));
            p.showTitle(Title.title(
                    Component.text(at.name, NamedTextColor.GREEN),
                    Component.text(roleTag + "主人: " + at.ownerName, NamedTextColor.GRAY),
                    Title.Times.times(Duration.ofMillis(400), Duration.ofSeconds(2), Duration.ofMillis(400))));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.5f, 1.5f);
        } else if (newId == null && oldId != null) {
            // 离开领地
            Claim old = claims.get(oldId);
            if (old != null && !old.leaveMsg.isEmpty())
                p.sendMessage(Color.colorize(old.leaveMsg.replace("%player%", p.getName())
                        .replace("%owner%", old.ownerName).replace("%claim%", old.name)));
            p.showTitle(Title.title(
                    text("§7离开领地"),
                    Component.empty(),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(1), Duration.ofMillis(300))));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.3f, 0.8f);
        }
    }

    private void showClaimParticles(Player p, Claim c) {
        World w = p.getWorld();
        if (!w.getName().equals(c.world)) return;
        int y = p.getLocation().getBlockY();
        Particle pt = c.owner.equals(p.getUniqueId()) ? Particle.HAPPY_VILLAGER
                : c.perms.containsKey(p.getUniqueId().toString()) ? Particle.COMPOSTER : Particle.DRIPPING_LAVA;
        for (int x = c.minX; x <= c.maxX; x += 2) { spawnPt(p, pt, x, y, c.minZ); spawnPt(p, pt, x, y, c.maxZ); }
        for (int z = c.minZ; z <= c.maxZ; z += 2) { spawnPt(p, pt, c.minX, y, z); spawnPt(p, pt, c.maxX, y, z); }
        for (int dy = -1; dy <= 2; dy++) {
            spawnPt(p, pt, c.minX, y + dy, c.minZ); spawnPt(p, pt, c.maxX, y + dy, c.minZ);
            spawnPt(p, pt, c.minX, y + dy, c.maxZ); spawnPt(p, pt, c.maxX, y + dy, c.maxZ);
        }
    }
    private void spawnPt(Player p, Particle pt, int x, int y, int z) {
        p.spawnParticle(pt, x + 0.5, y + 0.5, z + 0.5, 1, 0, 0, 0, 0);
    }
    private Component text(String s) {
        return LegacyComponentSerializer.legacySection().deserialize(s);
    }

    // ════════════════════════════════════════
    //  命令
    // ════════════════════════════════════════
    private class ClaimCmd implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender s, Command cmd, String l, String[] a) {
            if (!(s instanceof Player p)) { s.sendMessage(msg("player-only")); return true; }
            if (a.length == 0) { openMainGui(p); return true; }

            switch (a[0].toLowerCase()) {
                case "create" -> doCreate(p, a);
                case "invite", "trust" -> doInvite(p, a);
                case "promote" -> doPromote(p, a);
                case "kick", "untrust" -> doKick(p, a);
                case "ban" -> doBan(p, a);
                case "unban" -> doUnban(p, a);
                case "remove", "delete" -> doRemove(p, a);
                case "rename" -> doRename(p, a);
                case "setmsg" -> doSetmsg(p, a);
                case "sell" -> doSell(p, a);
                case "buy" -> doBuy(p, a);
                case "tp", "home" -> doTp(p, a);
                case "setspawn" -> doSetspawn(p, a);
                case "admin" -> doAdmin(p, a);
                case "list" -> openMainGui(p);
                case "map" -> openMapGui(p);
                case "shop" -> openBuyGui(p);
                default -> showHelp(p);
            }
            return true;
        }

        private void doCreate(Player p, String[] a) {
            if (a.length < 2) { p.sendMessage(msg("prefix") + " §c用法: /claim create <名字>"); return; }
            Location[] sel = selections.get(p.getUniqueId());
            if (sel == null || sel[0] == null || sel[1] == null) {
                p.sendMessage(msg("prefix") + " §c请先手持 §e木斧 §c左右键选区！"); return;
            }
            int minX = Math.min(sel[0].getBlockX(), sel[1].getBlockX());
            int maxX = Math.max(sel[0].getBlockX(), sel[1].getBlockX());
            int minZ = Math.min(sel[0].getBlockZ(), sel[1].getBlockZ());
            int maxZ = Math.max(sel[0].getBlockZ(), sel[1].getBlockZ());
            int dx = maxX - minX + 1, dz = maxZ - minZ + 1;
            if (dx > MAX_CLAIM_SIZE || dz > MAX_CLAIM_SIZE) {
                p.sendMessage(msg("prefix") + " §c选区不能超过 " + MAX_CLAIM_SIZE + "x" + MAX_CLAIM_SIZE + "！"); return;
            }
            if (dx < 3 || dz < 3) { p.sendMessage(msg("prefix") + " §c选区最小 3x3！"); return; }
            List<Claim> my = getPlayerClaims(p.getUniqueId());
            if (my.size() >= (p.hasPermission("megaplugin.claim.admin") ? 50 : MAX_CLAIMS)) {
                p.sendMessage(msg("prefix") + " §c你只能拥有 " + MAX_CLAIMS + " 个领地！"); return;
            }
            World w = p.getWorld();
            String wname = w.getName();

            for (Claim cx : claims.values()) {
                if (!cx.world.equals(wname)) continue;
                if (cx.maxX < minX || cx.minX > maxX || cx.maxZ < minZ || cx.minZ > maxZ) continue;
                p.sendMessage(msg("prefix") + " §c此区域与领地 §e" + cx.name + " §c重叠！"); return;
            }

            String name = a[1];
            for (Claim cl : claims.values()) if (cl.name.equalsIgnoreCase(name)) {
                p.sendMessage(msg("prefix") + " §c领地名称已存在！"); return;
            }
            String id = nextId();
            Claim cl = new Claim(id, name, p.getUniqueId(), p.getName(), wname, minX, minZ, maxX, maxZ);
            claims.put(id, cl);
            rebuildChunkIndex();
            saveAll();
            selections.remove(p.getUniqueId());
            p.sendMessage(msg("prefix") + " §a领地 §e" + name + " §a创建成功！大小: §e" + dx + "x" + dz + " §a= §e" + (dx*dz) + " §a方块");
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        }

        private void doInvite(Player p, String[] a) {
            if (a.length < 2) { p.sendMessage(msg("prefix") + " §c用法: /claim invite <玩家> [领地]"); return; }
            Player target = Bukkit.getPlayer(a[1]);
            if (target == null) { p.sendMessage(msg("player-not-found")); return; }
            Claim cl = a.length >= 3 ? findClaim(p.getUniqueId(), a[2]) : getClaimAt(p.getLocation(), null);
            if (cl == null || !cl.owner.equals(p.getUniqueId())) {
                p.sendMessage(msg("prefix") + " §c你必须站在自己的领地内或指定领地！"); return;
            }
            if (cl.perms.containsKey(target.getUniqueId().toString())) {
                p.sendMessage(msg("prefix") + " §e" + target.getName() + " §7已是成员！"); return;
            }
            cl.perms.put(target.getUniqueId().toString(), Perm.BUILD);
            saveAll();
            p.sendMessage(msg("prefix") + " §a已将 §e" + target.getName() + " §a添加为 §e建筑(BUILD)");
            target.sendMessage(msg("prefix") + " §a你已被 §e" + p.getName() + " §a加入领地 §e" + cl.name + " §a(建筑权限)");
        }

        private void doPromote(Player p, String[] a) {
            if (a.length < 2) { p.sendMessage(msg("prefix") + " §c用法: /claim promote <玩家> [领地]"); return; }
            Claim cl = a.length >= 3 ? findClaim(p.getUniqueId(), a[2]) : getClaimAt(p.getLocation(), null);
            if (cl == null || !cl.owner.equals(p.getUniqueId())) {
                p.sendMessage(msg("prefix") + " §c你没有权限！"); return;
            }
            Player target = Bukkit.getPlayer(a[1]);
            if (target == null) { p.sendMessage(msg("player-not-found")); return; }
            String key = target.getUniqueId().toString();
            Perm current = cl.perms.get(key);
            if (current == null) { p.sendMessage(msg("prefix") + " §c该玩家不是成员！"); return; }
            Perm next = switch(current) {
                case ACCESS -> Perm.CONTAINER;
                case CONTAINER -> Perm.BUILD;
                case BUILD -> Perm.MANAGE;
                default -> current;
            };
            if (next == current) { p.sendMessage(msg("prefix") + " §c已是最高信任等级！"); return; }
            cl.perms.put(key, next);
            saveAll();
            p.sendMessage(msg("prefix") + " §a已将 §e" + target.getName() + " §a提升为 §e" + next.display());
            target.sendMessage(msg("prefix") + " §a你已被提升为领地 §e" + cl.name + " §a的 " + next.display());
        }

        private void doKick(Player p, String[] a) {
            if (a.length < 2) { p.sendMessage(msg("prefix") + " §c用法: /claim kick <玩家> [领地]"); return; }
            Claim cl = a.length >= 3 ? findClaim(p.getUniqueId(), a[2]) : getClaimAt(p.getLocation(), null);
            if (cl == null || !cl.owner.equals(p.getUniqueId())) {
                p.sendMessage(msg("prefix") + " §c你没有权限！"); return;
            }
            Player target = Bukkit.getPlayer(a[1]);
            UUID uuid = target != null ? target.getUniqueId() : null;
            if (uuid == null) { p.sendMessage(msg("player-not-found")); return; }
            if (cl.perms.remove(uuid.toString()) != null) {
                saveAll();
                p.sendMessage(msg("prefix") + " §c已将 §e" + target.getName() + " §c移出领地");
            } else { p.sendMessage(msg("prefix") + " §c该玩家不是成员！"); }
        }

        private void doBan(Player p, String[] a) { p.sendMessage(msg("prefix") + " §7功能已简化，使用 /claim kick 移除成员"); }
        private void doUnban(Player p, String[] a) { p.sendMessage(msg("prefix") + " §7功能已简化，使用 /claim invite 添加成员"); }

        private void doRemove(Player p, String[] a) {
            if (a.length < 2) { p.sendMessage(msg("prefix") + " §c用法: /claim remove <领地>"); return; }
            Claim cl = findClaim(p.getUniqueId(), a[1]);
            if (cl == null) { p.sendMessage(msg("prefix") + " §c领地不存在！"); return; }
            claims.remove(cl.id);
            data.getConfig().set(cl.id, null);
            rebuildChunkIndex();
            saveAll();
            p.sendMessage(msg("prefix") + " §c领地 §e" + cl.name + " §c已删除");
        }

        private void doRename(Player p, String[] a) {
            if (a.length < 2) { p.sendMessage(msg("prefix") + " §c用法: /claim rename <新名字> [领地]"); return; }
            Claim cl = a.length >= 3 ? findClaim(p.getUniqueId(), a[2]) : getClaimAt(p.getLocation(), null);
            if (cl == null || !cl.owner.equals(p.getUniqueId())) {
                p.sendMessage(msg("prefix") + " §c你没有权限！"); return;
            }
            String n = a[1];
            for (Claim cx : claims.values()) if (cx != cl && cx.name.equalsIgnoreCase(n)) {
                p.sendMessage(msg("prefix") + " §c名称已存在！"); return;
            }
            cl.name = n; saveAll();
            p.sendMessage(msg("prefix") + " §a领地已重命名为 §e" + n);
        }

        private void doSetmsg(Player p, String[] a) {
            if (a.length < 3) { p.sendMessage(msg("prefix") + " §c用法: /claim setmsg <enter|leave> <消息>"); return; }
            Claim cl = a.length >= 4 ? findClaim(p.getUniqueId(), a[3]) : getClaimAt(p.getLocation(), null);
            if (cl == null || !cl.owner.equals(p.getUniqueId())) {
                p.sendMessage(msg("prefix") + " §c你没有权限！"); return;
            }
            String msg = String.join(" ", Arrays.copyOfRange(a, 2, a.length));
            if (a[1].equalsIgnoreCase("enter")) cl.enterMsg = msg;
            else if (a[1].equalsIgnoreCase("leave")) cl.leaveMsg = msg;
            else { p.sendMessage(msg("prefix") + " §c类型必须是 enter 或 leave"); return; }
            saveAll();
            p.sendMessage(msg("prefix") + " §a已设置 §e" + a[1] + " §a消息: §f" + msg);
        }

        private void doSell(Player p, String[] a) {
            if (a.length < 2) { p.sendMessage(msg("prefix") + " §c用法: /claim sell <价格> [领地]"); return; }
            double price;
            try { price = Double.parseDouble(a[1]); } catch (NumberFormatException ex) {
                p.sendMessage(msg("prefix") + " §c价格必须是数字！"); return;
            }
            Claim cl = a.length >= 3 ? findClaim(p.getUniqueId(), a[2]) : getClaimAt(p.getLocation(), null);
            if (cl == null || !cl.owner.equals(p.getUniqueId())) {
                p.sendMessage(msg("prefix") + " §c你没有权限！"); return;
            }
            cl.price = price; saveAll();
            if (price > 0) p.sendMessage(msg("prefix") + " §a领地 §e" + cl.name + " §a已挂牌出售，价格: §6" + price);
            else p.sendMessage(msg("prefix") + " §a已取消出售");
        }

        private void doBuy(Player p, String[] a) {
            if (a.length < 2) { p.sendMessage(msg("prefix") + " §c用法: /claim buy <领地>"); return; }
            Claim cl = null;
            for (Claim cx : claims.values()) if (cx.name.equalsIgnoreCase(a[1])) { cl = cx; break; }
            if (cl == null) { p.sendMessage(msg("prefix") + " §c领地不存在！"); return; }
            if (cl.price <= 0) { p.sendMessage(msg("prefix") + " §c此领地不出售！"); return; }
            EconomyModule eco = plugin.getEconomyModule();
            if (eco == null) { p.sendMessage(msg("prefix") + " §c经济系统未启用！"); return; }
            if (!eco.hasEnough(p.getUniqueId(), cl.price)) {
                p.sendMessage(msg("prefix") + " §c余额不足！需要 §e" + cl.price); return;
            }
            eco.withdraw(p, cl.price);
            Player oldOwner = Bukkit.getPlayer(cl.owner);
            if (oldOwner != null) eco.deposit(oldOwner, cl.price);
            else eco.deposit(cl.owner, cl.price);
            cl.owner = p.getUniqueId(); cl.ownerName = p.getName();
            cl.perms.clear(); cl.price = 0;
            saveAll();
            p.sendMessage(msg("prefix") + " §a成功购买领地 §e" + cl.name + "！");
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        }

        private void doTp(Player p, String[] a) {
            Claim cl = a.length >= 2 ? findClaim(p.getUniqueId(), a[1]) : getClaimAt(p.getLocation(), null);
            if (cl == null) cl = getPlayerClaims(p.getUniqueId()).stream().findFirst().orElse(null);
            if (cl == null) { p.sendMessage(msg("prefix") + " §c你没有领地！"); return; }
            if (cl.spawn != null) p.teleport(cl.spawn);
            else p.teleport(new Location(Bukkit.getWorld(cl.world), (cl.minX+cl.maxX)/2.0, cl.world != null ?
                    Bukkit.getWorld(cl.world).getHighestBlockYAt((cl.minX+cl.maxX)/2, (cl.minZ+cl.maxZ)/2)+1 : 64,
                    (cl.minZ+cl.maxZ)/2.0));
            p.sendMessage(msg("prefix") + " §a已传送到领地 §e" + cl.name);
        }

        private void doSetspawn(Player p, String[] a) {
            Claim cl = a.length >= 2 ? findClaim(p.getUniqueId(), a[1]) : getClaimAt(p.getLocation(), null);
            if (cl == null || !cl.owner.equals(p.getUniqueId())) {
                p.sendMessage(msg("prefix") + " §c你必须在自己的领地内！"); return;
            }
            cl.spawn = p.getLocation().clone(); saveAll();
            p.sendMessage(msg("prefix") + " §a已设置领地 §e" + cl.name + " §a的传送点！");
        }

        /** 管理员模式切换 */
        private void doAdmin(Player p, String[] a) {
            if (!p.hasPermission("megaplugin.claim.admin")) {
                p.sendMessage(msg("prefix") + " §c你没有管理员权限！"); return;
            }
            PlayerData pd = getPlayerData(p.getUniqueId());
            pd.ignoreClaims = !pd.ignoreClaims;
            p.sendMessage(msg("prefix") + " §a管理员模式: " + (pd.ignoreClaims ? "§c§l关闭领地保护" : "§2§l正常"));
        }

        private void showHelp(Player p) {
            p.sendMessage("§8§m          §r §a§l领地系统 v4 §8§m          ");
            p.sendMessage(" §7/claim §e- 打开领地菜单");
            p.sendMessage(" §7/claim create <名字> §e- 创建领地 (手持木斧选区)");
            p.sendMessage(" §7/claim invite <玩家> §e- 邀请成员 (BUILD权限)");
            p.sendMessage(" §7/claim promote <玩家> §e- 提升权限");
            p.sendMessage(" §7/claim kick <玩家> §e- 移除成员");
            p.sendMessage(" §7/claim remove <领地> §e- 删除领地");
            p.sendMessage(" §7/claim tp [领地] §e- 传送到领地");
            p.sendMessage(" §7/claim admin §e- 切换管理员模式");
            p.sendMessage("§8§m                                  ");
        }
    }

    private Claim findClaim(UUID owner, String name) {
        for (Claim c : claims.values()) {
            if (c.name.equalsIgnoreCase(name) && c.owner.equals(owner)) return c;
        }
        for (Claim c : claims.values()) if (c.name.equalsIgnoreCase(name)) return c;
        return null;
    }

    private class ClaimTab implements TabCompleter {
        @Override
        public List<String> onTabComplete(CommandSender s, Command cmd, String l, String[] a) {
            if (!(s instanceof Player p)) return Collections.emptyList();
            List<String> cmds = Arrays.asList("create","invite","promote","kick","remove","rename",
                    "setmsg","sell","buy","tp","setspawn","admin","list","map","shop");
            if (a.length == 1) return cmds.stream().filter(x -> x.startsWith(a[0].toLowerCase())).collect(Collectors.toList());
            if (a.length == 2 && List.of("invite","promote","kick").contains(a[0].toLowerCase()))
                return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                        .filter(x -> x.toLowerCase().startsWith(a[1].toLowerCase())).collect(Collectors.toList());
            if (a.length == 2 && List.of("remove","rename","tp").contains(a[0].toLowerCase()))
                return getPlayerClaims(p.getUniqueId()).stream().map(cl -> cl.name)
                        .filter(x -> x.toLowerCase().startsWith(a[1].toLowerCase())).collect(Collectors.toList());
            if (a.length == 2 && a[0].equalsIgnoreCase("buy"))
                return claims.values().stream().filter(cl -> cl.price > 0).map(cl -> cl.name)
                        .filter(x -> x.toLowerCase().startsWith(a[1].toLowerCase())).collect(Collectors.toList());
            return Collections.emptyList();
        }
    }

    // ════════════════════════════════════════
    //  GUI (保持不变)
    // ════════════════════════════════════════
    public void openMainGui(Player p) {
        Inventory inv = Bukkit.createInventory(null, 54, GUI_MAIN);
        List<Claim> my = getPlayerClaims(p.getUniqueId());
        int slot = 0;
        for (Claim c : my) {
            if (slot >= 45) break;
            String status = c.price > 0 ? " §a[出售中 §e" + c.price + "§a]" : "";
            inv.setItem(slot++, createItem(Material.GRASS_BLOCK,
                    "§a§l" + c.name + status,
                    "§7坐标: §f" + c.minX + "," + c.minZ + " §7→ §f" + c.maxX + "," + c.maxZ,
                    "§7大小: §e" + (c.maxX - c.minX + 1) + "x" + (c.maxZ - c.minZ + 1),
                    "§7成员: §e" + c.perms.size(),
                    "", "§c左键 §7管理成员", "§e右键 §7领地设置"));
        }
        if (my.isEmpty())
            inv.setItem(13, createItem(Material.BARRIER, "§c暂无领地", "§7使用 §e/claim create <名字> §7创建领地"));
        inv.setItem(48, createItem(Material.GOLDEN_AXE, "§e§l创建领地", "§7手持木斧选区后 /claim create"));
        inv.setItem(49, createItem(Material.COMPASS, "§d§l领地地图"));
        inv.setItem(50, createItem(Material.EMERALD, "§2§l领地商店"));
        inv.setItem(53, createItem(Material.BARRIER, "§c§l关闭"));
        fillGlass(inv, 45, 54);
        p.openInventory(inv);
    }

    private void openSettingsGui(Player p, Claim c) {
        Inventory inv = Bukkit.createInventory(null, 27, GUI_SETTINGS);
        inv.setItem(0, createItem(Material.GRASS_BLOCK, "§a§l" + c.name));
        inv.setItem(11, createItem(Material.OAK_SIGN, "§b§l公告消息", "§7设置进出提示"));
        inv.setItem(12, createItem(Material.TNT, "§c§l爆炸: " + (c.allowExplosions ? "§a允许" : "§c禁止")));
        inv.setItem(13, createItem(Material.GOLD_INGOT, "§2§l出售领地", "§7价格: " + (c.price > 0 ? "§e" + c.price : "§7不出售")));
        inv.setItem(14, createItem(Material.ENDER_PEARL, "§d§l设置传送点"));
        inv.setItem(15, createItem(Material.NAME_TAG, "§e§l重命名"));
        inv.setItem(18, createItem(Material.ARROW, "§c§l返回"));
        inv.setItem(26, createItem(Material.BARRIER, "§c§l关闭"));
        fillGlass(inv, 0, 27);
        p.openInventory(inv);
    }

    private void openMemberGui(Player p, Claim c) {
        Inventory inv = Bukkit.createInventory(null, 54, GUI_MEMBERS);
        inv.setItem(0, createItem(Material.GRASS_BLOCK, "§a§l" + c.name, "§e主人: " + c.ownerName));
        inv.setItem(45, createItem(Material.ARROW, "§c§l返回"));
        inv.setItem(53, createItem(Material.BARRIER, "§c§l关闭"));
        inv.setItem(49, createItem(Material.EMERALD, "§a§l添加成员", "§7/claim invite <玩家>"));

        int slot = 2;
        for (var e : c.perms.entrySet()) {
            if (slot >= 45 || e.getKey().startsWith("__")) break;
            try {
                UUID uid = UUID.fromString(e.getKey());
                String name = Bukkit.getOfflinePlayer(uid).getName();
                if (name == null) name = e.getKey().substring(0, 8);
                String color = switch(e.getValue()) {
                    case MANAGE -> "§c"; case BUILD -> "§e"; case CONTAINER -> "§b"; default -> "§7";
                };
                inv.setItem(slot++, createItem(Material.PLAYER_HEAD,
                        color + name, "§7权限: " + color + e.getValue().display(),
                        "§7左键提升 §7右键降级 §cShift+左键移除"));
            } catch (Exception ignored) {}
        }
        fillGlass(inv, 0, 54);
        p.openInventory(inv);
    }

    private void openMapGui(Player p) {
        Inventory inv = Bukkit.createInventory(null, 54, GUI_MAP);
        Location loc = p.getLocation();
        int cx = loc.getBlockX() >> 4, cz = loc.getBlockZ() >> 4;
        for (int row = 0; row < 6; row++) {
            for (int col = 0; col < 9; col++) {
                int wx = (cx - 4 + col) << 4, wz = (cz - 2 + row) << 4;
                int slot = row * 9 + col;
                if (row == 2 && col == 4) {
                    inv.setItem(slot, createItem(Material.PLAYER_HEAD, "§a§l你"));
                    continue;
                }
                Claim found = getClaimAt(new Location(loc.getWorld(), wx, loc.getY(), wz), null);
                if (found != null) {
                    boolean mine = found.owner.equals(p.getUniqueId());
                    String color = mine ? "§a" : "§c";
                    inv.setItem(slot, createItem(mine ? Material.LIME_WOOL : Material.RED_WOOL,
                            color + found.name, "§7主人: §e" + found.ownerName));
                } else {
                    inv.setItem(slot, createItem(Material.GREEN_STAINED_GLASS_PANE, "§7荒野"));
                }
            }
        }
        inv.setItem(49, createItem(Material.COMPASS, "§d§l刷新"));
        inv.setItem(53, createItem(Material.BARRIER, "§c§l关闭"));
        p.openInventory(inv);
    }

    private void openBuyGui(Player p) {
        Inventory inv = Bukkit.createInventory(null, 54, GUI_BUY);
        int slot = 0;
        for (Claim c : claims.values()) {
            if (c.price <= 0) continue;
            if (slot >= 45) break;
            inv.setItem(slot++, createItem(Material.GRASS_BLOCK, "§a§l" + c.name,
                    "§7主人: §e" + c.ownerName, "§7价格: §6" + c.price, "§7点击购买"));
        }
        if (slot == 0) inv.setItem(13, createItem(Material.BARRIER, "§c暂无出售中的领地"));
        inv.setItem(53, createItem(Material.BARRIER, "§c§l关闭"));
        fillGlass(inv, 0, 54);
        p.openInventory(inv);
    }

    @EventHandler
    public void onGuiClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        String t = e.getView().getTitle();
        if (!t.equals(GUI_MAIN) && !t.equals(GUI_SETTINGS) && !t.equals(GUI_MEMBERS)
                && !t.equals(GUI_MAP) && !t.equals(GUI_BUY)) return;
        e.setCancelled(true);
        ItemStack item = e.getCurrentItem();
        if (item == null || item.getType() == Material.AIR) return;
        ItemMeta m = item.getItemMeta();
        if (m == null || !m.hasDisplayName()) return;
        String name = m.getDisplayName();

        if (t.equals(GUI_MAIN)) handleMainGui(p, name, e);
        else if (t.equals(GUI_SETTINGS)) handleSettingsGui(p, name);
        else if (t.equals(GUI_MEMBERS)) handleMemberGui(p, name, e);
        else if (t.equals(GUI_MAP)) handleMapGui(p, name);
        else if (t.equals(GUI_BUY)) { if ("§c§l关闭".equals(name)) p.closeInventory(); }
    }

    private void handleMainGui(Player p, String name, InventoryClickEvent e) {
        if ("§e§l创建领地".equals(name)) {
            p.closeInventory(); p.sendMessage(msg("prefix") + " §7手持 §e木斧 §7选区后 §e/claim create <名字>");
        } else if ("§d§l领地地图".equals(name)) { openMapGui(p); }
        else if ("§2§l领地商店".equals(name)) { openBuyGui(p); }
        else if ("§c§l关闭".equals(name) || "§c暂无领地".equals(name)) { p.closeInventory(); }
        else if (name.startsWith("§a§l")) {
            String cn = name.substring(4).split(" ")[0];
            Claim c = findClaim(p.getUniqueId(), cn);
            if (c != null) {
                lastClaimId.put(p.getUniqueId(), c.id);
                if (e.isLeftClick()) openMemberGui(p, c);
                else if (e.isRightClick()) openSettingsGui(p, c);
            }
        }
    }

    private void handleSettingsGui(Player p, String name) {
        if ("§c§l返回".equals(name)) { openMainGui(p); return; }
        if ("§c§l关闭".equals(name)) { p.closeInventory(); return; }
        String cid = lastClaimId.get(p.getUniqueId());
        if (cid == null) return;
        Claim c = claims.get(cid);
        if (c == null) return;
        if (name.startsWith("§c§l爆炸")) {
            c.allowExplosions = !c.allowExplosions; saveAll();
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
            openSettingsGui(p, c);
        } else if (name.equals("§b§l公告消息")) {
            p.closeInventory(); p.sendMessage(msg("prefix") + " §7使用 §e/claim setmsg <enter|leave> <消息>");
        } else if (name.equals("§2§l出售领地")) {
            p.closeInventory(); p.sendMessage(msg("prefix") + " §7使用 §e/claim sell <价格>");
        } else if (name.equals("§d§l设置传送点")) {
            c.spawn = p.getLocation().clone(); saveAll();
            p.sendMessage(msg("prefix") + " §a传送点已设置！"); p.closeInventory();
        } else if (name.equals("§e§l重命名")) {
            p.closeInventory(); p.sendMessage(msg("prefix") + " §7使用 §e/claim rename <新名字>");
        }
    }

    private void handleMemberGui(Player p, String name, InventoryClickEvent e) {
        if ("§c§l返回".equals(name)) { openMainGui(p); return; }
        if ("§c§l关闭".equals(name)) { p.closeInventory(); return; }
        if ("§a§l添加成员".equals(name)) {
            p.closeInventory(); p.sendMessage(msg("prefix") + " §7使用 §e/claim invite <玩家>"); return;
        }
        // 成员操作
        String cid = lastClaimId.get(p.getUniqueId());
        if (cid == null) return;
        Claim c = claims.get(cid);
        if (c == null) return;
        if (name.startsWith("§c") || name.startsWith("§e") || name.startsWith("§b") || name.startsWith("§7")) {
            p.closeInventory();
            p.sendMessage(msg("prefix") + " §7使用 §e/claim promote <玩家> §7提升, §e/claim kick <玩家> §7移除");
        }
    }

    private void handleMapGui(Player p, String name) {
        if ("§d§l刷新".equals(name)) { openMapGui(p); }
        else if ("§c§l关闭".equals(name)) { p.closeInventory(); }
    }

    @EventHandler
    public void onGuiDrag(InventoryDragEvent e) {
        String t = e.getView().getTitle();
        if (t.equals(GUI_MAIN) || t.equals(GUI_SETTINGS) || t.equals(GUI_MEMBERS)
                || t.equals(GUI_MAP) || t.equals(GUI_BUY)) e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        selections.remove(id); lastClaimId.remove(id); currentClaim.remove(id);
        lastParticle.remove(id); playerDataMap.remove(id);
    }

    // ── 工具方法 ──
    private ItemStack createItem(Material mat, String name, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta m = item.getItemMeta();
        if (m != null) { m.setDisplayName(name); if (lore.length > 0) m.setLore(Arrays.asList(lore)); item.setItemMeta(m); }
        return item;
    }

    private void fillGlass(Inventory inv, int from, int to) {
        ItemStack g = createItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = from; i < to && i < inv.getSize(); i++) if (inv.getItem(i) == null) inv.setItem(i, g);
    }
}
