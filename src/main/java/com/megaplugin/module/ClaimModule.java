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
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.projectiles.ProjectileSource;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 领地系统 v5 — 基于 Land 插件源码重构
 *
 * 核心架构 (对照 Land):
 * ┌─ EventHandler ── checkFlag(player, loc, flag) ─────────┐
 * │                          │                              │
 * │                   ┌──────▼──────────┐                   │
 * │                   │ 1. ignoreClaims?│← admin bypass     │
 * │                   │ 2. getClaimAt() │← chunk hash 索引   │
 * │                   │ 3. claim.checkFlag() ← Flag 检查    │
 * │                   └──────┬──────────┘                   │
 * │                  null=允许 / msg=拒绝                    │
 * └────────────────────────────────────────────────────────┘
 *
 * 权限模型 (Land 风格):
 *   主人 → 全部允许
 *   成员 → 每人独立的 Flag 集合 (PlayerSetting)
 *   访客 → 领地默认 Flag 集合 (defaultSetting)
 *
 * Flag 枚举: 15 个细粒度行为标志
 * OtherFlag 枚举: 6 个领地级环境设置
 */
public class ClaimModule extends MegaModule {

    // ── GUI 标题 ──
    private static final String GUI_MAIN     = "§8§l[ §a§l我的领地 §8§l]";
    private static final String GUI_SETTINGS = "§8§l[ §e§l领地设置 §8§l]";
    private static final String GUI_MEMBERS  = "§8§l[ §b§l成员管理 §8§l]";
    private static final String GUI_MAP      = "§8§l[ §d§l领地地图 §8§l]";
    private static final String GUI_BUY      = "§8§l[ §2§l领地商店 §8§l]";
    private static final String GUI_FLAGS    = "§8§l[ §6§l成员权限 §8§l]";
    private static final String GUI_EXPAND  = "§8§l[ §e§l扩展领地 §8§l]";
    private static final String GUI_SHRINK  = "§8§l[ §c§l收缩领地 §8§l]";
    private static final String GUI_SUB     = "§8§l[ §6§l子领地管理 §8§l]";
    private static final String GUI_CONFIRM = "§8§l[ §c§l确认操作 §8§l]";

    // ── 聊天输入状态 ──
    private final Map<UUID, String> chatExpandClaim = new ConcurrentHashMap<>();   // player -> claimId (等待输入扩展格数)
    private final Map<UUID, String> chatShrinkClaim = new ConcurrentHashMap<>();   // player -> claimId (等待输入收缩格数)
    private final Map<UUID, String> chatTransferClaim = new ConcurrentHashMap<>(); // player -> claimId (等待输入转让目标)
    private final Map<UUID, String> chatSearch = new ConcurrentHashMap<>();        // player -> (占位, 等待输入搜索关键词)

    // ── 红石频率限制 ──
    private final Map<Long, Integer> redstoneCount = new ConcurrentHashMap<>(); // chunkKey -> count
    private static final int REDSTONE_MAX_PER_SEC = 60;

    private static final int MAX_CLAIMS = 5;
    private static final int MAX_CLAIM_SIZE = 64;

    // ════════════════════════════════════════
    //  Flag 枚举 — 对照 Land LandSetting
    // ════════════════════════════════════════
    public enum Flag {
        PLACE("放置方块",         Material.GRASS_BLOCK),
        BREAK("破坏方块",         Material.DIAMOND_PICKAXE),
        CONTAINER("使用箱子",     Material.CHEST),
        FURNACE("使用熔炉",       Material.FURNACE),
        BUCKET("使用桶",          Material.WATER_BUCKET),
        IGNITE("使用打火石",      Material.FLINT_AND_STEEL),
        FRAME("展示框/画",        Material.ITEM_FRAME),
        SIGN("编辑告示牌",        Material.OAK_SIGN),
        DOOR("门/开关/床",        Material.OAK_DOOR),
        PVP("玩家对战",           Material.DIAMOND_SWORD),
        DAMAGE_ENTITY("伤害生物", Material.BONE),
        DROP("丢弃物品",          Material.FEATHER),
        MOVE("进入领地",          Material.LEATHER_BOOTS),
        TELEPORT("传送领地",      Material.ENDER_PEARL),
        ITEM_USE("使用物品",      Material.BONE_MEAL),
        FLY("领地飞行",           Material.FEATHER),
        RIDE("骑乘坐骑",          Material.SADDLE),
        SHEAR("剪毛/挤奶",        Material.SHEARS);

        public final String name;
        public final Material icon;

        Flag(String name, Material icon) {
            this.name = name;
            this.icon = icon;
        }

        /** 获取访客默认 Flag: MOVE+TELEPORT+DOOR+CONTAINER (对照 Land 的 defaultSetting) */
        public static Set<Flag> defaultVisitorFlags() {
            return EnumSet.of(MOVE, TELEPORT, DOOR, CONTAINER);
        }

        /** 获取成员默认 Flag: 全部允许 (对照 Land 的 PlayerDefaultSetting) */
        public static Set<Flag> defaultMemberFlags() {
            return EnumSet.complementOf(EnumSet.noneOf(Flag.class));
        }

        /** 映射旧 Perm → Flag 集合 (数据迁移用) */
        public static Set<Flag> fromOldPerm(String permName) {
            return switch (permName) {
                case "OWNER"   -> EnumSet.allOf(Flag.class);
                case "MANAGE"  -> EnumSet.allOf(Flag.class);
                case "BUILD"   -> EnumSet.of(PLACE, BREAK, CONTAINER, FURNACE, BUCKET, IGNITE,
                                              FRAME, SIGN, DOOR, ITEM_USE, MOVE, TELEPORT, FLY, RIDE, SHEAR);
                case "CONTAINER" -> EnumSet.of(CONTAINER, FURNACE, FRAME, DOOR, MOVE, TELEPORT);
                case "ACCESS"  -> EnumSet.of(MOVE, TELEPORT, DOOR, CONTAINER);
                default -> EnumSet.of(MOVE, TELEPORT, DOOR, CONTAINER);
            };
        }
    }

    // ════════════════════════════════════════
    //  OtherFlag 枚举 — 对照 Land OtherLandSetting
    // ════════════════════════════════════════
    public enum OtherFlag {
        EXPLOSION("爆炸保护",       Material.TNT),
        MOB_SPAWN("怪物生成",       Material.ZOMBIE_HEAD),
        WATER_FLOW("液体流动",      Material.WATER_BUCKET),
        WATER_OUT("外部液体禁入",   Material.LAVA_BUCKET),
        FIRE_SPREAD("火蔓延",       Material.CAMPFIRE),
        PISTON("外部活塞禁入",      Material.PISTON),
        BLOCK_UPDATE("方块更新",    Material.CRAFTING_TABLE);

        public final String name;
        public final Material icon;
        OtherFlag(String name, Material icon) { this.name = name; this.icon = icon; }
    }

    // ── 邀请数据 ──
    private static class ClaimInvite {
        final Claim claim;
        final UUID inviter;
        final String inviterName;
        final UUID invitee;
        final String inviteeName;
        final long expireTime;
        ClaimInvite(Claim c, UUID inviter, String inviterName, UUID invitee, String inviteeName) {
            this.claim = c; this.inviter = inviter; this.inviterName = inviterName;
            this.invitee = invitee; this.inviteeName = inviteeName;
            this.expireTime = System.currentTimeMillis() + 60000;
        }
        boolean isExpired() { return System.currentTimeMillis() > expireTime; }
    }

    // ── 传送任务 ──
    private static class TpTask {
        final UUID player;
        final Location dest;
        final int taskId;
        long lastWarn;
        TpTask(UUID p, Location d, int id) { player = p; dest = d; taskId = id; lastWarn = System.currentTimeMillis(); }
    }

    // ════════════════════════════════════════
    //  数据存储
    // ════════════════════════════════════════
    private final DataFile data;
    private final Map<String, Claim> claims = new LinkedHashMap<>();
    private final Map<Long, List<Claim>> chunkIndex = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerData> playerDataMap = new ConcurrentHashMap<>();

    private final Map<UUID, Location[]> selections = new HashMap<>();
    private final Map<UUID, String> lastClaimId = new HashMap<>();
    private final Map<UUID, UUID> editingMember = new HashMap<>();   // 当前编辑的成员UUID
    private final Map<UUID, String> currentClaim = new HashMap<>();
    private final Map<UUID, Long> lastParticle = new HashMap<>();

    // ── 邀请系统 (Land 风格: 60秒超时) ──
    private final Map<UUID, ClaimInvite> invites = new ConcurrentHashMap<>();
    // ── 传送系统 (延迟+冷却) ──
    private final Map<UUID, TpTask> tpTasks = new ConcurrentHashMap<>();
    private final Map<UUID, Long> tpCooldowns = new ConcurrentHashMap<>();

    // ── 玩家数据 ──
    private static class PlayerData {
        Claim lastClaim;
        boolean ignoreClaims;
    }

    // ════════════════════════════════════════
    //  领地模型 (Land 风格)
    // ════════════════════════════════════════
    public static class Claim {
        public String id, name, world, enterMsg = "", leaveMsg = "";
        public UUID owner;
        public String ownerName;
        public int minX, minY = -64, minZ, maxX, maxY = 319, maxZ;
        public Location spawn;
        public double price = 0;

        /** 访客默认权限 (对照 Land defaultSetting): 默认仅 MOVE+TELEPORT */
        public final Set<Flag> defaultFlags = Flag.defaultVisitorFlags();

        /** 主人权限: 默认全部 Flag (可通过 GUI 自行关闭) */
        public final Set<Flag> ownerFlags = EnumSet.allOf(Flag.class);

        /** 成员权限: UUID → 各自的 Flag 集合 (对照 Land MemberSetting) */
        public final Map<UUID, Set<Flag>> memberFlags = new LinkedHashMap<>();

        /** 成员名称缓存: UUID → 名字 */
        public final Map<UUID, String> memberNames = new LinkedHashMap<>();

        /** 领地级环境设置 (对照 Land LandOtherSet) */
        public final Map<OtherFlag, Boolean> otherFlags = new LinkedHashMap<>();

        /** 子领地列表 (Land 风格: 主领地内嵌套) */
        public final List<Claim> subClaims = new ArrayList<>();
        /** 父领地ID (子领地指向父领地, null=主领地) */
        public String parentId = null;
        /** 出售标语 */
        public String sellMessage = "";
        /** 创建时间 */
        public long createTime = System.currentTimeMillis();

        // ── 租赁系统 ──
        /** 出租价格 (0=不出租) */
        public double rentPrice = 0;
        /** 租赁天数 (一个周期) */
        public int rentDays = 7;
        /** 当前租客UUID */
        public UUID rentedTo = null;
        /** 当前租客名 */
        public String rentedToName = null;
        /** 租约到期时间 (epoch ms, 0=未出租) */
        public long rentEndTime = 0;

        /** 领地标志牌位置 (可选, 每个领地一个) */
        public Location signLocation = null;

        public boolean isRented() { return rentedTo != null && rentEndTime > System.currentTimeMillis(); }

        public boolean isSubClaim() { return parentId != null; }

        public Claim() {
            for (OtherFlag of : OtherFlag.values()) otherFlags.put(of, true);
        }

        public Claim(String id, String name, UUID owner, String ownerName, String world,
                     int minX, int minZ, int maxX, int maxZ) {
            this();
            this.id = id; this.name = name; this.owner = owner; this.ownerName = ownerName;
            this.world = world; this.minX = minX; this.minZ = minZ; this.maxX = maxX; this.maxZ = maxZ;
        }

        public boolean contains(Location loc) {
            return loc.getWorld() != null && loc.getWorld().getName().equals(world)
                    && loc.getBlockX() >= minX && loc.getBlockX() <= maxX
                    && loc.getBlockY() >= minY && loc.getBlockY() <= maxY
                    && loc.getBlockZ() >= minZ && loc.getBlockZ() <= maxZ;
        }

        // ── 核心权限检查 (对照 Land LandData.hasPermission) ──

        /**
         * 检查玩家是否有某个 Flag 权限
         * 流程: 主人 ownerFlags → 成员个人 Flag → 访客默认 Flag
         * @return true=允许
         */
        public boolean checkFlag(UUID uid, Flag flag) {
            // 1. 主人 — 检查 ownerFlags (默认全开，可自行关闭)
            if (owner.equals(uid)) return ownerFlags.contains(flag);

            // 2. 检查成员的独立 Flag 集合
            Set<Flag> mf = memberFlags.get(uid);
            if (mf != null) return mf.contains(flag);

            // 3. 访客默认权限
            return defaultFlags.contains(flag);
        }

        /** 获取拒绝消息 */
        public String denyMessage(UUID uid, String playerName, Flag flag) {
            if (owner.equals(uid)) {
                if (!ownerFlags.contains(flag))
                    return "§c你关闭了自己的 " + flag.name + " 权限！";
                return null;
            }
            Set<Flag> mf = memberFlags.get(uid);
            if (mf != null && !mf.contains(flag))
                return "§c你没有 " + flag.name + " 权限！领地主人: §e" + ownerName;
            if (mf == null && !defaultFlags.contains(flag))
                return "§c访客没有 " + flag.name + " 权限！领地主人: §e" + ownerName;
            return "§c你没有 " + flag.name + " 权限！领地主人: §e" + ownerName;
        }

        /** 添加成员 (对照 Land addMember) */
        public void addMember(UUID uid, String name) {
            memberFlags.put(uid, Flag.defaultMemberFlags());
            memberNames.put(uid, name);
        }

        /** 移除成员 (对照 Land removeMember) */
        public boolean removeMember(UUID uid) {
            memberNames.remove(uid);
            return memberFlags.remove(uid) != null;
        }

        /** 切换成员的某个 Flag */
        public void toggleMemberFlag(UUID uid, Flag flag) {
            Set<Flag> mf = memberFlags.get(uid);
            if (mf == null) return;
            if (mf.contains(flag)) mf.remove(flag);
            else mf.add(flag);
        }

        /** 切换主人的某个 Flag (默认全开，可自行关闭) */
        public void toggleOwnerFlag(Flag flag) {
            if (ownerFlags.contains(flag)) ownerFlags.remove(flag);
            else ownerFlags.add(flag);
        }

        /** 检查成员是否存在 */
        public boolean isMember(UUID uid) {
            return memberFlags.containsKey(uid);
        }

        /** 切换领地环境设置 */
        public void toggleOtherFlag(OtherFlag of) {
            otherFlags.put(of, !otherFlags.getOrDefault(of, true));
        }
    }

    // ════════════════════════════════════════
    //  初始化
    // ════════════════════════════════════════
    public ClaimModule(MegaPlugin plugin) {
        super(plugin);
        data = new DataFile(plugin, "claims_v3.yml");
    }

    @Override
    public void onEnable() {
        loadClaims();
        rebuildChunkIndex();
        registerListener();
        var cmd = plugin.getCommand("claim");
        if (cmd != null) { cmd.setExecutor(new ClaimCmd()); cmd.setTabCompleter(new ClaimTab()); }
        // 自动保存定时器 (每5分钟, 对照 Land AutoSaveLandTask)
        Bukkit.getScheduler().runTaskTimer(plugin, this::saveAll,
                20L * 60 * 5, 20L * 60 * 5);
        // 租赁到期检查 (每60秒)
        Bukkit.getScheduler().runTaskTimer(plugin, this::checkRentExpiry,
                20L * 60, 20L * 60);
        // 红石频率重置 (每秒)
        Bukkit.getScheduler().runTaskTimer(plugin, this::resetRedstoneClock,
                20L, 20L);
        plugin.getLogger().info("[Claim] v5 加载完成 (" + claims.size() + " 个领地, 18Flag+7环境设置+Land全功能+租赁+红石限制)");
    }

    @Override
    public void onDisable() {
        saveAll();
        playerDataMap.clear();
    }

    // ── 数据加载/保存 (YAML 格式适配) ──
    @SuppressWarnings("unchecked")
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

                // 加载 defaultFlags (访客默认)
                c.defaultFlags.clear();
                if (cfg.contains(key + ".defaultFlags")) {
                    for (String fn : cfg.getStringList(key + ".defaultFlags")) {
                        try { c.defaultFlags.add(Flag.valueOf(fn)); } catch (Exception ignored) {}
                    }
                }

                // 加载 ownerFlags (主人权限，默认全开)
                c.ownerFlags.clear();
                if (cfg.contains(key + ".ownerFlags")) {
                    for (String fn : cfg.getStringList(key + ".ownerFlags")) {
                        try { c.ownerFlags.add(Flag.valueOf(fn)); } catch (Exception ignored) {}
                    }
                } else {
                    c.ownerFlags.addAll(EnumSet.allOf(Flag.class));
                }

                // 加载 memberFlags (每个成员的 Flag 集合)
                if (cfg.contains(key + ".memberFlags")) {
                    var msec = cfg.getConfigurationSection(key + ".memberFlags");
                    if (msec != null) {
                        for (String uuidStr : msec.getKeys(false)) {
                            try {
                                UUID muid = UUID.fromString(uuidStr);
                                List<String> flagNames = cfg.getStringList(key + ".memberFlags." + uuidStr);
                                Set<Flag> flags = EnumSet.noneOf(Flag.class);
                                for (String fn : flagNames) {
                                    try { flags.add(Flag.valueOf(fn)); } catch (Exception ignored) {}
                                }
                                c.memberFlags.put(muid, flags);
                                // 尝试读取名字
                                String mn = cfg.getString(key + ".memberNames." + uuidStr);
                                if (mn != null) c.memberNames.put(muid, mn);
                            } catch (Exception ignored) {}
                        }
                    }
                }

                // 加载 otherFlags (环境设置)
                if (cfg.contains(key + ".otherFlags")) {
                    var osec = cfg.getConfigurationSection(key + ".otherFlags");
                    if (osec != null) {
                        for (OtherFlag of : OtherFlag.values()) {
                            c.otherFlags.put(of, cfg.getBoolean(key + ".otherFlags." + of.name, true));
                        }
                    }
                }

                // 加载传送点
                if (cfg.contains(key + ".spawn")) {
                    var s = cfg.getConfigurationSection(key + ".spawn");
                    if (s != null) c.spawn = new Location(Bukkit.getWorld(s.getString("world", c.world)),
                            s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                            (float) s.getDouble("yaw", 0), (float) s.getDouble("pitch", 0));
                }

                // 加载新字段
                c.sellMessage = cfg.getString(key + ".sellMessage", "");
                c.createTime = cfg.getLong(key + ".createTime", System.currentTimeMillis());
                c.parentId = cfg.getString(key + ".parentId", null);

                // 加载租赁数据
                c.rentPrice = cfg.getDouble(key + ".rentPrice", 0);
                c.rentDays = cfg.getInt(key + ".rentDays", 7);
                String rented = cfg.getString(key + ".rentedTo");
                if (rented != null && !rented.isEmpty()) {
                    try { c.rentedTo = UUID.fromString(rented); } catch (Exception ignored) {}
                }
                c.rentedToName = cfg.getString(key + ".rentedToName");
                c.rentEndTime = cfg.getLong(key + ".rentEndTime", 0);

                // 加载标志牌位置
                if (cfg.contains(key + ".sign.world")) {
                    World signWorld = Bukkit.getWorld(cfg.getString(key + ".sign.world", "world"));
                    if (signWorld != null) {
                        c.signLocation = new Location(signWorld,
                                cfg.getInt(key + ".sign.x"),
                                cfg.getInt(key + ".sign.y"),
                                cfg.getInt(key + ".sign.z"));
                    }
                }

                claims.put(key, c);
            } catch (Exception e) {
                plugin.getLogger().warning("[Claim] 加载领地 " + key + " 失败: " + e.getMessage());
            }
        }

        // 第二遍：重建子领地层级关系
        for (Claim c : claims.values()) {
            if (cfg.contains(c.id + ".subClaims")) {
                for (String subId : cfg.getStringList(c.id + ".subClaims")) {
                    Claim sub = claims.get(subId);
                    if (sub != null) {
                        sub.parentId = c.id;
                        c.subClaims.add(sub);
                    }
                }
            }
        }

        plugin.getLogger().info("[Claim] 已加载 " + claims.size() + " 个领地");
    }

    /** 旧数据迁移: claims.yml + claims_v2.yml → claims_v3.yml */
    private void migrateOldData() {
        // 如果 v3 已有数据，跳过
        if (data.getConfig().getKeys(false).size() > 0) return;

        boolean migrated = false;

        // 尝试从 claims_v2.yml 迁移
        DataFile oldData = new DataFile(plugin, "claims_v2.yml");
        if (oldData.getFile().exists()) {
            var oldCfg = oldData.getConfig();
            for (String key : oldCfg.getKeys(false)) {
                try {
                    Claim c = new Claim();
                    c.id = key;
                    c.name = oldCfg.getString(key + ".name", key);
                    c.owner = UUID.fromString(oldCfg.getString(key + ".owner", ""));
                    c.ownerName = oldCfg.getString(key + ".ownerName", "?");
                    c.world = oldCfg.getString(key + ".world", "world");
                    c.minX = oldCfg.getInt(key + ".minX"); c.minZ = oldCfg.getInt(key + ".minZ");
                    c.maxX = oldCfg.getInt(key + ".maxX"); c.maxZ = oldCfg.getInt(key + ".maxZ");
                    c.minY = oldCfg.getInt(key + ".minY", -64); c.maxY = oldCfg.getInt(key + ".maxY", 319);
                    c.enterMsg = oldCfg.getString(key + ".enterMsg", "");
                    c.leaveMsg = oldCfg.getString(key + ".leaveMsg", "");
                    c.price = oldCfg.getDouble(key + ".price", 0);
                    // 迁移旧权限
                    var permSec = oldCfg.getConfigurationSection(key + ".perms");
                    if (permSec != null) {
                        for (String pk : permSec.getKeys(false)) {
                            if (pk.equals("__public__")) {
                                String permName = permSec.getString(pk, "ACCESS");
                                c.defaultFlags.clear();
                                c.defaultFlags.addAll(Flag.fromOldPerm(permName));
                            } else {
                                try {
                                    UUID muid = UUID.fromString(pk);
                                    String permName = permSec.getString(pk, "ACCESS");
                                    c.memberFlags.put(muid, Flag.fromOldPerm(permName));
                                } catch (Exception ignored) {}
                            }
                        }
                    }
                    // 迁移旧开关 → 新环境标志
                    if (oldCfg.contains(key + ".allowExplosions"))
                        c.otherFlags.put(OtherFlag.EXPLOSION, oldCfg.getBoolean(key + ".allowExplosions"));
                    if (oldCfg.contains(key + ".allowMonsters"))
                        c.otherFlags.put(OtherFlag.MOB_SPAWN, oldCfg.getBoolean(key + ".allowMonsters"));
                    // spawn
                    if (oldCfg.contains(key + ".spawn")) {
                        var s = oldCfg.getConfigurationSection(key + ".spawn");
                        if (s != null) c.spawn = new Location(Bukkit.getWorld(s.getString("world", c.world)),
                                s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                                (float) s.getDouble("yaw", 0), (float) s.getDouble("pitch", 0));
                    }
                    claims.put(key, c);
                    migrated = true;
                } catch (Exception e) {
                    plugin.getLogger().warning("[Claim] 迁移 v2 数据失败: " + key + " " + e.getMessage());
                }
            }
        }

        // 再从旧 claims.yml 迁移
        DataFile veryOld = new DataFile(plugin, "claims.yml");
        if (veryOld.getFile().exists()) {
            var voCfg = veryOld.getConfig();
            for (String uidStr : voCfg.getKeys(false)) {
                try {
                    UUID uid = UUID.fromString(uidStr);
                    var sec = voCfg.getConfigurationSection(uidStr);
                    if (sec == null) continue;
                    for (String cn : sec.getKeys(false)) {
                        if (cn.equals("ownerName") || cn.equals("uuid")) continue;
                        boolean alreadyExists = claims.values().stream()
                                .anyMatch(cl -> cl.owner.equals(uid) && cl.name.equalsIgnoreCase(cn));
                        if (alreadyExists) continue;
                        String nid = nextId();
                        Claim c = new Claim();
                        c.id = nid; c.name = cn; c.owner = uid;
                        c.ownerName = sec.getString(cn + ".ownerName", "?");
                        c.world = sec.getString(cn + ".world", "world");
                        c.minX = sec.getInt(cn + ".minX"); c.minZ = sec.getInt(cn + ".minZ");
                        c.maxX = sec.getInt(cn + ".maxX"); c.maxZ = sec.getInt(cn + ".maxZ");
                        c.enterMsg = sec.getString(cn + ".enterMessage", "");
                        c.leaveMsg = sec.getString(cn + ".leaveMessage", "");
                        for (String tid : sec.getStringList(cn + ".trusted"))
                            c.memberFlags.put(UUID.fromString(tid), Flag.defaultMemberFlags());
                        claims.put(nid, c);
                        migrated = true;
                    }
                } catch (Exception ignored) {}
            }
        }

        if (migrated) {
            plugin.getLogger().info("[Claim] 已从旧格式迁移领地为 v3 (Land架构)");
            saveAll();
        }
    }

    private void saveAll() {
        var cfg = data.getConfig();
        // 先清空所有旧数据
        for (String key : cfg.getKeys(false)) cfg.set(key, null);

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
            cfg.set(p + "sellMessage", c.sellMessage);
            cfg.set(p + "createTime", c.createTime);
            // 子领地/父领地
            if (c.parentId != null) cfg.set(p + "parentId", c.parentId);
            if (!c.subClaims.isEmpty()) {
                List<String> subIds = new ArrayList<>();
                for (Claim sub : c.subClaims) subIds.add(sub.id);
                cfg.set(p + "subClaims", subIds);
            }

            // defaultFlags
            List<String> dfList = c.defaultFlags.stream().map(Enum::name).collect(Collectors.toList());
            cfg.set(p + "defaultFlags", dfList);

            // ownerFlags
            List<String> ofList = c.ownerFlags.stream().map(Enum::name).collect(Collectors.toList());
            cfg.set(p + "ownerFlags", ofList);

            // memberFlags
            cfg.set(p + "memberFlags", null);
            cfg.set(p + "memberNames", null);
            for (var e : c.memberFlags.entrySet()) {
                List<String> fl = e.getValue().stream().map(Enum::name).collect(Collectors.toList());
                cfg.set(p + "memberFlags." + e.getKey().toString(), fl);
                String mn = c.memberNames.get(e.getKey());
                if (mn != null) cfg.set(p + "memberNames." + e.getKey().toString(), mn);
            }

            // otherFlags
            cfg.set(p + "otherFlags", null);
            for (var oe : c.otherFlags.entrySet())
                cfg.set(p + "otherFlags." + oe.getKey().name, oe.getValue());

            if (c.spawn != null) {
                cfg.set(p + "spawn.world", c.spawn.getWorld().getName());
                cfg.set(p + "spawn.x", c.spawn.getX()); cfg.set(p + "spawn.y", c.spawn.getY());
                cfg.set(p + "spawn.z", c.spawn.getZ());
                cfg.set(p + "spawn.yaw", c.spawn.getYaw()); cfg.set(p + "spawn.pitch", c.spawn.getPitch());
            }

            // 租赁数据
            if (c.rentPrice > 0) cfg.set(p + "rentPrice", c.rentPrice);
            cfg.set(p + "rentDays", c.rentDays);
            if (c.rentedTo != null) cfg.set(p + "rentedTo", c.rentedTo.toString());
            if (c.rentedToName != null) cfg.set(p + "rentedToName", c.rentedToName);
            if (c.rentEndTime > 0) cfg.set(p + "rentEndTime", c.rentEndTime);

            // 标志牌
            if (c.signLocation != null) {
                cfg.set(p + "sign.world", c.signLocation.getWorld().getName());
                cfg.set(p + "sign.x", c.signLocation.getBlockX());
                cfg.set(p + "sign.y", c.signLocation.getBlockY());
                cfg.set(p + "sign.z", c.signLocation.getBlockZ());
            }
        }
        data.save();
    }

    // ════════════════════════════════════════
    //  Chunk 索引
    // ════════════════════════════════════════
    private static long chunkHash(int cx, int cz) {
        return ((long) cz << 32) ^ cx;
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

    private Claim getClaimAt(Location loc, PlayerData pd) {
        if (loc == null || loc.getWorld() == null) return null;
        if (pd != null && pd.lastClaim != null && pd.lastClaim.contains(loc))
            return pd.lastClaim;
        World w = loc.getWorld();
        int cx = loc.getBlockX() >> 4, cz = loc.getBlockZ() >> 4;
        List<Claim> list = chunkIndex.get(chunkHash(cx, cz));
        if (list == null) return null;
        for (Claim c : list) {
            if (c.world.equals(w.getName()) && c.contains(loc)) {
                // 子领地优先: 如果位置在子领地内，返回子领地
                for (Claim sub : c.subClaims) {
                    if (sub.contains(loc)) {
                        if (pd != null) pd.lastClaim = sub;
                        return sub;
                    }
                }
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
    //  核心保护入口 — Flag 检查
    // ════════════════════════════════════════

    /**
     * 统一的 Flag 保护检查
     * @return null = 允许; String = 拒绝消息
     */
    private String checkFlag(Player p, Location loc, Flag flag) {
        if (loc == null || loc.getWorld() == null) return null;
        PlayerData pd = getPlayerData(p.getUniqueId());
        if (pd.ignoreClaims) return null;

        Claim claim = getClaimAt(loc, pd);
        if (claim == null) return null;

        // 子领地: 父领地主人有全部权限 (对照 Land LandSubData)
        if (claim.isSubClaim()) {
            Claim parent = claims.get(claim.parentId);
            if (parent != null && parent.owner.equals(p.getUniqueId())) return null;
        }

        if (!claim.checkFlag(p.getUniqueId(), flag))
            return claim.denyMessage(p.getUniqueId(), p.getName(), flag);
        return null;
    }

    /** 检查玩家是否有 Flag (不发送消息) */
    private boolean hasFlag(Player p, Location loc, Flag flag) {
        if (loc == null || loc.getWorld() == null) return true;
        PlayerData pd = getPlayerData(p.getUniqueId());
        if (pd.ignoreClaims) return true;
        Claim claim = getClaimAt(loc, pd);
        if (claim == null) return true;
        // 子领地父主人全部允许
        if (claim.isSubClaim()) {
            Claim parent = claims.get(claim.parentId);
            if (parent != null && parent.owner.equals(p.getUniqueId())) return true;
        }
        return claim.checkFlag(p.getUniqueId(), flag);
    }

    /** 检查领地环境设置 */
    private boolean isOtherFlagOpen(Location loc, OtherFlag of) {
        if (loc == null || loc.getWorld() == null) return true;
        Claim c = getClaimAt(loc, null);
        if (c == null) return true;
        return c.otherFlags.getOrDefault(of, true);
    }

    // ── 工具方法 ──
    private List<Claim> getPlayerClaims(UUID uid) {
        return claims.values().stream().filter(c -> c.owner.equals(uid)).collect(Collectors.toList());
    }

    private Claim findClaim(UUID owner, String name) {
        for (Claim c : claims.values()) {
            if (c.name.equalsIgnoreCase(name) && c.owner.equals(owner)) return c;
        }
        for (Claim c : claims.values()) if (c.name.equalsIgnoreCase(name)) return c;
        return null;
    }

    private String nextId() {
        int i = 1; while (claims.containsKey("claim" + i)) i++;
        return "claim" + i;
    }

    private boolean isContainer(Material m) {
        return Tag.SHULKER_BOXES.isTagged(m) || m == Material.CHEST || m == Material.TRAPPED_CHEST
                || m == Material.BARREL || m == Material.HOPPER || m == Material.DISPENSER
                || m == Material.DROPPER || m == Material.ENDER_CHEST
                || m == Material.JUKEBOX || m == Material.BEEHIVE || m == Material.BEE_NEST
                || m == Material.DECORATED_POT;
    }

    private boolean isFurnace(Material m) {
        return m == Material.FURNACE || m == Material.BLAST_FURNACE
                || m == Material.SMOKER || m == Material.BREWING_STAND;
    }

    private boolean isDoorBlock(Material m) {
        return Tag.DOORS.isTagged(m) || Tag.TRAPDOORS.isTagged(m)
                || Tag.FENCE_GATES.isTagged(m) || Tag.BEDS.isTagged(m)
                || Tag.BUTTONS.isTagged(m) || m == Material.LEVER;
    }

    private boolean isSpawnEgg(Material m) {
        return m.name().endsWith("_SPAWN_EGG");
    }

    private boolean isDyeItem(Material m) {
        return m.name().endsWith("_DYE");
    }

    private boolean isMinecartItem(Material m) {
        return m == Material.MINECART || m == Material.CHEST_MINECART
                || m == Material.FURNACE_MINECART || m == Material.TNT_MINECART
                || m == Material.HOPPER_MINECART;
    }

    private boolean isBoatItem(Material m) {
        return m.name().contains("BOAT") || m.name().equals("BAMBOO_RAFT");
    }

    private boolean isHoeItem(Material m) {
        return m == Material.WOODEN_HOE || m == Material.STONE_HOE || m == Material.IRON_HOE
                || m == Material.GOLDEN_HOE || m == Material.DIAMOND_HOE || m == Material.NETHERITE_HOE;
    }

    private boolean isShovelItem(Material m) {
        return m == Material.WOODEN_SHOVEL || m == Material.STONE_SHOVEL || m == Material.IRON_SHOVEL
                || m == Material.GOLDEN_SHOVEL || m == Material.DIAMOND_SHOVEL || m == Material.NETHERITE_SHOVEL;
    }

    // ════════════════════════════════════════
    //  事件保护 — 基于 Land + 细粒度 Flag
    // ════════════════════════════════════════

    // ── 选区工具 ──
    @EventHandler(priority = EventPriority.LOWEST)
    public void onSelect(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (p.getInventory().getItemInMainHand().getType() != Material.WOODEN_AXE) return;
        Block b = e.getClickedBlock();
        if (b == null) return;
        Location[] sel = selections.computeIfAbsent(p.getUniqueId(), k -> new Location[2]);
        if (e.getAction() == Action.LEFT_CLICK_BLOCK) {
            sel[0] = b.getLocation();
            p.sendMessage(msg("prefix") + " §a位置1: §e" + b.getX() + "§7, §e" + b.getY() + "§7, §e" + b.getZ());
            p.spawnParticle(Particle.HAPPY_VILLAGER, b.getLocation().add(0.5, 1.2, 0.5), 5, 0.3, 0.3, 0.3, 0);
        } else if (e.getAction() == Action.RIGHT_CLICK_BLOCK) {
            sel[1] = b.getLocation();
            p.sendMessage(msg("prefix") + " §a位置2: §e" + b.getX() + "§7, §e" + b.getY() + "§7, §e" + b.getZ());
            p.spawnParticle(Particle.HAPPY_VILLAGER, b.getLocation().add(0.5, 1.2, 0.5), 5, 0.3, 0.3, 0.3, 0);
            if (sel[0] != null) {
                int dx = Math.abs(sel[1].getBlockX() - sel[0].getBlockX()) + 1;
                int dz = Math.abs(sel[1].getBlockZ() - sel[0].getBlockZ()) + 1;
                p.sendMessage(msg("prefix") + " §7选区: §e" + dx + "x" + dz + " §7(最大 " + MAX_CLAIM_SIZE + ")");
            }
        }
    }

    // ── 方块破坏 (Flag.BREAK) ──
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        String deny = checkFlag(p, e.getBlock().getLocation(), Flag.BREAK);
        if (deny != null) { e.setCancelled(true); p.sendMessage(msg("prefix") + " " + deny); }
    }

    // ── 方块放置 (Flag.PLACE) ──
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        String deny = checkFlag(p, e.getBlock().getLocation(), Flag.PLACE);
        if (deny != null) { e.setCancelled(true); p.sendMessage(msg("prefix") + " " + deny); }
    }

    // ── 方块交互 (对照 Land onUseChest + onTouchLand) ──
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (p.getInventory().getItemInMainHand().getType() == Material.WOODEN_AXE) return;
        Block b = e.getClickedBlock();
        Material item = e.getMaterial();

        // ═ RIGHT_CLICK_AIR — 手持物品对空气使用 ═
        if (b == null) {
            if (e.getAction() == Action.RIGHT_CLICK_AIR) {
                String deny = null;
                if (item == Material.FLINT_AND_STEEL || item == Material.FIRE_CHARGE)
                    deny = checkFlag(p, p.getLocation(), Flag.IGNITE);
                else if (item == Material.ENDER_PEARL || item == Material.ENDER_EYE)
                    deny = checkFlag(p, p.getLocation(), Flag.TELEPORT);
                else if (item == Material.EXPERIENCE_BOTTLE)
                    deny = checkFlag(p, p.getLocation(), Flag.ITEM_USE);
                if (deny != null) { e.setCancelled(true); p.sendMessage(msg("prefix") + " " + deny); }
            }
            return;
        }

        Material m = b.getType();

        // ═ PHYSICAL — 踩踏农田 ═
        if (e.getAction() == Action.PHYSICAL) {
            if (m == Material.FARMLAND || m == Material.TURTLE_EGG) {
                String deny = checkFlag(p, b.getLocation(), Flag.PLACE);
                if (deny != null) e.setCancelled(true);
            }
            return;
        }

        // ═ LEFT_CLICK_BLOCK ═
        if (e.getAction() == Action.LEFT_CLICK_BLOCK) {
            if (m == Material.TNT || m == Material.NOTE_BLOCK || m == Material.DRAGON_EGG) {
                String deny = checkFlag(p, b.getLocation(), Flag.BREAK);
                if (deny != null) { e.setCancelled(true); p.sendMessage(msg("prefix") + " " + deny); }
            }
            return;
        }

        // ═ RIGHT_CLICK_BLOCK — 对照 Land onUseChest 分 Flag 检查 ═
        // 1. 容器类 (CONTAINER)
        if (isContainer(m)) {
            String deny = checkFlag(p, b.getLocation(), Flag.CONTAINER);
            if (deny != null) { e.setCancelled(true); p.sendMessage(msg("prefix") + " " + deny); return; }
        }
        // 2. 熔炉类 (FURNACE) — Land 的 FRAME
        if (isFurnace(m) || m == Material.ENCHANTING_TABLE || m == Material.ANVIL
                || m == Material.CHIPPED_ANVIL || m == Material.DAMAGED_ANVIL
                || m == Material.BEACON || m == Material.CRAFTER
                || m == Material.CARTOGRAPHY_TABLE || m == Material.GRINDSTONE
                || m == Material.LOOM || m == Material.STONECUTTER
                || m == Material.SMITHING_TABLE || m == Material.FLETCHING_TABLE
                || m == Material.RESPAWN_ANCHOR) {
            String deny = checkFlag(p, b.getLocation(), Flag.FURNACE);
            if (deny != null) { e.setCancelled(true); p.sendMessage(msg("prefix") + " " + deny); return; }
        }
        // 3. 门/开关/床 (DOOR)
        if (isDoorBlock(m) || m == Material.CAKE) {
            String deny = checkFlag(p, b.getLocation(), Flag.DOOR);
            if (deny != null) { e.setCancelled(true); p.sendMessage(msg("prefix") + " " + deny); return; }
        }
        // 4. 告示牌编辑 (SIGN)
        if (Tag.ALL_SIGNS.isTagged(m) || Tag.WALL_SIGNS.isTagged(m)) {
            String deny = checkFlag(p, b.getLocation(), Flag.SIGN);
            if (deny != null) { e.setCancelled(true); p.sendMessage(msg("prefix") + " " + deny); return; }
        }
        // 5. 炼药锅/堆肥桶/钟/浆果等交互方块 (FURNACE 级别)
        if (m == Material.CAULDRON || m == Material.WATER_CAULDRON || m == Material.LAVA_CAULDRON
                || m == Material.BELL || m == Material.COMPOSTER
                || m == Material.SWEET_BERRY_BUSH || m == Material.CAVE_VINES || m == Material.CAVE_VINES_PLANT
                || m == Material.PUMPKIN) {
            String deny = checkFlag(p, b.getLocation(), Flag.FURNACE);
            if (deny != null) { e.setCancelled(true); p.sendMessage(msg("prefix") + " " + deny); return; }
        }
        // 6. 红石/装饰 (PLACE 标志)
        if (m == Material.REPEATER || m == Material.COMPARATOR || m == Material.DAYLIGHT_DETECTOR
                || Tag.FLOWER_POTS.isTagged(m) || Tag.CANDLES.isTagged(m)) {
            String deny = checkFlag(p, b.getLocation(), Flag.PLACE);
            if (deny != null) { e.setCancelled(true); p.sendMessage(msg("prefix") + " " + deny); return; }
        }

        // 7. 手持物品检查 — 对照 Land onUseChest: 桶/打火石/展示框
        if (item == Material.WATER_BUCKET || item == Material.LAVA_BUCKET
                || item == Material.BUCKET || item == Material.COD_BUCKET
                || item == Material.SALMON_BUCKET || item == Material.PUFFERFISH_BUCKET
                || item == Material.TROPICAL_FISH_BUCKET || item == Material.AXOLOTL_BUCKET
                || item == Material.TADPOLE_BUCKET || item == Material.POWDER_SNOW_BUCKET) {
            String deny = checkFlag(p, b.getLocation(), Flag.BUCKET);
            if (deny != null) { e.setCancelled(true); p.sendMessage(msg("prefix") + " " + deny); return; }
        }
        if (item == Material.FLINT_AND_STEEL || item == Material.FIRE_CHARGE) {
            String deny = checkFlag(p, b.getLocation(), Flag.IGNITE);
            if (deny != null) { e.setCancelled(true); p.sendMessage(msg("prefix") + " " + deny); return; }
        }
        // ITEM_USE: 骨粉/蜜脾/末地水晶/刷怪蛋/染料/船/矿车/盔甲架/拴绳/命名牌/锄头/铲子
        if (item == Material.BONE_MEAL || item == Material.HONEYCOMB
                || item == Material.END_CRYSTAL || item == Material.ARMOR_STAND
                || item == Material.LEAD || item == Material.NAME_TAG
                || isSpawnEgg(item) || isDyeItem(item) || isBoatItem(item) || isMinecartItem(item)
                || isHoeItem(item)
                || (item == Material.INK_SAC || item == Material.GLOW_INK_SAC)) {
            String deny = checkFlag(p, b.getLocation(), Flag.ITEM_USE);
            if (deny != null) { e.setCancelled(true); p.sendMessage(msg("prefix") + " " + deny); return; }
        }
        // 铲子 + 土块 = 造土径
        if (isShovelItem(item) && (m == Material.DIRT || m == Material.GRASS_BLOCK
                || m == Material.PODZOL || m == Material.COARSE_DIRT || m == Material.MYCELIUM)) {
            String deny = checkFlag(p, b.getLocation(), Flag.ITEM_USE);
            if (deny != null) { e.setCancelled(true); p.sendMessage(msg("prefix") + " " + deny); return; }
        }
        // 末影之眼在传送门框架上
        if (item == Material.ENDER_EYE && m == Material.END_PORTAL_FRAME) {
            String deny = checkFlag(p, b.getLocation(), Flag.ITEM_USE);
            if (deny != null) { e.setCancelled(true); p.sendMessage(msg("prefix") + " " + deny); return; }
        }
    }

    // ── PVP (Flag.PVP) ──
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player attacker = null;
        if (e.getDamager() instanceof Player player) attacker = player;
        else if (e.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player player)
            attacker = player;
        if (attacker == null) return;

        Claim c = getClaimAt(victim.getLocation(), null);
        if (c != null && !c.checkFlag(attacker.getUniqueId(), Flag.PVP)) {
            e.setCancelled(true);
            attacker.sendMessage(msg("prefix") + " §c此领地禁止 PVP！");
        }
    }

    // ── 怪物伤害玩家 ──
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPve(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player player)) return;
        if (e.getDamager() instanceof Player) return;
        if (e.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player) return;
        if (!(e.getDamager() instanceof Monster) && !(e.getDamager() instanceof WaterMob)
                && !(e.getDamager() instanceof Slime) && !(e.getDamager() instanceof Ghast)
                && !(e.getDamager() instanceof Phantom)) return;
        Claim c = getClaimAt(player.getLocation(), null);
        if (c != null && !c.checkFlag(player.getUniqueId(), Flag.DAMAGE_ENTITY)) {
            e.setCancelled(true);
        }
    }

    // ── 实体交互 (Flag.FRAME / DAMAGE_ENTITY) ──
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        Player p = e.getPlayer();
        Entity entity = e.getRightClicked();
        Material item = p.getInventory().getItemInMainHand().getType();

        // 1. 盔甲架 (ITEM_USE)
        if (entity instanceof ArmorStand) {
            String deny = checkFlag(p, entity.getLocation(), Flag.ITEM_USE);
            if (deny != null) { e.setCancelled(true); p.sendMessage(msg("prefix") + " " + deny); }
            return;
        }
        // 2. 展示框/画 (FRAME) — 对照 Land SHOW_ITEM
        if (entity instanceof Hanging) {
            String deny = checkFlag(p, entity.getLocation(), Flag.FRAME);
            if (deny != null) { e.setCancelled(true); p.sendMessage(msg("prefix") + " " + deny); }
            return;
        }
        // 3. 已驯服宠物 — 仅主人可交互
        if (entity instanceof Tameable tame && tame.isTamed() && tame.getOwner() != null) {
            if (!tame.getOwner().getUniqueId().equals(p.getUniqueId())) {
                PlayerData pd = getPlayerData(p.getUniqueId());
                if (!pd.ignoreClaims) {
                    e.setCancelled(true);
                    p.sendMessage(msg("prefix") + " §c这只宠物不是你的！");
                }
            }
            return;
        }
        // 4. 动物/村民/铁傀儡/雪傀儡 (DAMAGE_ENTITY — 但跳过挤奶/剪毛等 SHEAR 操作)
        if ((entity instanceof Animals && !(entity instanceof Cow && item == Material.BUCKET)
                && !(entity instanceof MushroomCow && item == Material.SHEARS))
                || entity instanceof Villager
                || entity instanceof IronGolem || entity instanceof Snowman) {
            String deny = checkFlag(p, entity.getLocation(), Flag.DAMAGE_ENTITY);
            if (deny != null) { e.setCancelled(true); p.sendMessage(msg("prefix") + " " + deny); }
            return;
        }
        // 5. 未驯服载具 (RIDE)
        if (entity instanceof Vehicle && !(entity instanceof Minecart)) {
            String deny = checkFlag(p, entity.getLocation(), Flag.RIDE);
            if (deny != null) { e.setCancelled(true); p.sendMessage(msg("prefix") + " " + deny); }
            return;
        }
        // 6. 拴绳使用 (ITEM_USE)
        if (item == Material.LEAD && !(entity instanceof LeashHitch)) {
            String deny = checkFlag(p, entity.getLocation(), Flag.ITEM_USE);
            if (deny != null) { e.setCancelled(true); p.sendMessage(msg("prefix") + " " + deny); }
            return;
        }
        // 7. 命名牌 (ITEM_USE)
        if (item == Material.NAME_TAG) {
            String deny = checkFlag(p, entity.getLocation(), Flag.ITEM_USE);
            if (deny != null) { e.setCancelled(true); p.sendMessage(msg("prefix") + " " + deny); }
        }
    }

    // ── 爆炸 — 环境设置 EXPLOSION ──
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        if (!isOtherFlagOpen(e.getLocation(), OtherFlag.EXPLOSION)) {
            e.blockList().removeIf(b -> !isOtherFlagOpen(b.getLocation(), OtherFlag.EXPLOSION));
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (!isOtherFlagOpen(e.getBlock().getLocation(), OtherFlag.EXPLOSION)) {
            e.blockList().removeIf(b -> !isOtherFlagOpen(b.getLocation(), OtherFlag.EXPLOSION));
        }
    }

    // ── 火焰 — 环境设置 FIRE_SPREAD ──
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        if (e.getPlayer() != null) {
            String deny = checkFlag(e.getPlayer(), e.getBlock().getLocation(), Flag.IGNITE);
            if (deny != null) { e.setCancelled(true); return; }
        }
        if (!isOtherFlagOpen(e.getBlock().getLocation(), OtherFlag.FIRE_SPREAD))
            e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        if (!isOtherFlagOpen(e.getBlock().getLocation(), OtherFlag.FIRE_SPREAD))
            e.setCancelled(true);
    }

    // ── 生物生成 — 环境设置 MOB_SPAWN ──
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.CUSTOM
                || e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.SPAWNER_EGG
                || e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.COMMAND) return;
        Claim c = getClaimAt(e.getLocation(), null);
        if (c == null) return;
        if ((e.getEntity() instanceof Monster || e.getEntity() instanceof Animals)
                && !c.otherFlags.getOrDefault(OtherFlag.MOB_SPAWN, true))
            e.setCancelled(true);
    }

    // ── 红石频率限制 (防高频红石卡服) ──
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRedstone(BlockRedstoneEvent e) {
        long chunkKey = (long)e.getBlock().getChunk().getX() << 32 | (e.getBlock().getChunk().getZ() & 0xFFFFFFFFL);
        int count = redstoneCount.merge(chunkKey, 1, Integer::sum);
        if (count > REDSTONE_MAX_PER_SEC) {
            e.setNewCurrent(e.getOldCurrent()); // 还原红石信号
        }
    }

    private void resetRedstoneClock() { redstoneCount.clear(); }

    // ── 租赁到期检查 ──
    private void checkRentExpiry() {
        long now = System.currentTimeMillis();
        for (Claim c : claims.values()) {
            if (c.rentedTo == null || c.rentEndTime <= 0 || c.rentEndTime > now) continue;
            // 租约到期，驱逐租客
            Player renter = Bukkit.getPlayer(c.rentedTo);
            if (renter != null)
                renter.sendMessage(msg("prefix") + " §c你对领地 §e" + c.name + " §c的租约已到期！");
            Player owner = Bukkit.getPlayer(c.owner);
            if (owner != null)
                owner.sendMessage(msg("prefix") + " §e领地 §e" + c.name + " §e的租约已到期，租客被自动移除");
            c.removeMember(c.rentedTo);
            c.rentedTo = null; c.rentedToName = null; c.rentEndTime = 0;
        }
    }

    // ── 植物/树木生长 ──
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onGrow(StructureGrowEvent e) {
        Claim originClaim = getClaimAt(e.getLocation(), null);
        e.getBlocks().removeIf(bs -> {
            Claim destClaim = getClaimAt(bs.getLocation(), null);
            return destClaim != originClaim;
        });
    }

    // ── 流体流动 — 环境设置 WATER_FLOW ──
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        Material src = e.getBlock().getType();
        if (src != Material.WATER && src != Material.LAVA) return;
        Claim fromClaim = getClaimAt(e.getBlock().getLocation(), null);
        Claim toClaim = getClaimAt(e.getToBlock().getLocation(), null);
        if (fromClaim != toClaim) e.setCancelled(true);
        else if (toClaim != null && !toClaim.otherFlags.getOrDefault(OtherFlag.WATER_FLOW, true))
            e.setCancelled(true);
    }

    // ── 活塞 — 环境设置 PISTON ──
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

    // ── 末影珍珠传送 (Flag.TELEPORT) ──
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPearl(PlayerTeleportEvent e) {
        if (e.getCause() != PlayerTeleportEvent.TeleportCause.ENDER_PEARL) return;
        String deny = checkFlag(e.getPlayer(), e.getTo(), Flag.TELEPORT);
        if (deny != null) e.setCancelled(true);
    }

    // ── 物品丢弃 (Flag.DROP) ──
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        String deny = checkFlag(e.getPlayer(), e.getPlayer().getLocation(), Flag.DROP);
        if (deny != null) e.setCancelled(true);
    }

    // ── 物品拾取 ──
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPickup(PlayerAttemptPickupItemEvent e) {
        String deny = checkFlag(e.getPlayer(), e.getItem().getLocation(), Flag.DROP);
        if (deny != null) e.setCancelled(true);
    }

    // ── 展示框/画破坏 (Flag.FRAME) ──
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent e) {
        if (!(e.getRemover() instanceof Player p)) return;
        String deny = checkFlag(p, e.getEntity().getLocation(), Flag.FRAME);
        if (deny != null) { e.setCancelled(true); p.sendMessage(msg("prefix") + " " + deny); }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        String deny = checkFlag(p, e.getEntity().getLocation(), Flag.PLACE);
        if (deny != null) { e.setCancelled(true); p.sendMessage(msg("prefix") + " " + deny); }
    }

    // ── 盔甲架伤害 (Flag.ITEM_USE) ──
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArmorStand(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof ArmorStand)) return;
        Player p = null;
        if (e.getDamager() instanceof Player player) p = player;
        else if (e.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player player)
            p = player;
        if (p == null) return;
        String deny = checkFlag(p, e.getEntity().getLocation(), Flag.ITEM_USE);
        if (deny != null) { e.setCancelled(true); p.sendMessage(msg("prefix") + " " + deny); }
    }

    // ── 动物/村民伤害 (Flag.DAMAGE_ENTITY) ──
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onAnimalHurt(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Animals) && !(e.getEntity() instanceof Villager)
                && !(e.getEntity() instanceof AbstractHorse) && !(e.getEntity() instanceof IronGolem)
                && !(e.getEntity() instanceof Snowman)) return;
        Player p = null;
        if (e.getDamager() instanceof Player player) p = player;
        else if (e.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player player)
            p = player;
        if (p == null) return;
        String deny = checkFlag(p, e.getEntity().getLocation(), Flag.DAMAGE_ENTITY);
        if (deny != null) { e.setCancelled(true); p.sendMessage(msg("prefix") + " " + deny); }
    }

    // ── 载具破坏 (Flag.BREAK) ──
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onVehicle(VehicleDestroyEvent e) {
        if (!(e.getAttacker() instanceof Player p)) return;
        String deny = checkFlag(p, e.getVehicle().getLocation(), Flag.BREAK);
        if (deny != null) { e.setCancelled(true); p.sendMessage(msg("prefix") + " " + deny); }
    }

    // ── 桶 (Flag.BUCKET) ──
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) {
        String deny = checkFlag(e.getPlayer(), e.getBlock().getLocation(), Flag.BUCKET);
        if (deny != null) { e.setCancelled(true); e.getPlayer().sendMessage(msg("prefix") + " " + deny); }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        String deny = checkFlag(e.getPlayer(), e.getBlock().getLocation(), Flag.BUCKET);
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

    // ── 投掷鸡蛋 ──
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onThrowEgg(PlayerEggThrowEvent e) {
        String deny = checkFlag(e.getPlayer(), e.getEgg().getLocation(), Flag.ITEM_USE);
        if (deny != null) { e.setHatching(false); e.getPlayer().sendMessage(msg("prefix") + " " + deny); }
    }

    // ── 钓鱼 ──
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        if (e.getState() != PlayerFishEvent.State.CAUGHT_ENTITY
                && e.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;
        if (e.getCaught() == null) return;
        Entity caught = e.getCaught();
        if (caught instanceof ArmorStand || caught instanceof Animals) {
            String deny = checkFlag(e.getPlayer(), caught.getLocation(), Flag.DAMAGE_ENTITY);
            if (deny != null) { e.setCancelled(true); e.getPlayer().sendMessage(msg("prefix") + " " + deny); }
        }
    }

    // ── 讲台取书 ──
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTakeLecternBook(PlayerTakeLecternBookEvent e) {
        String deny = checkFlag(e.getPlayer(), e.getLectern().getLocation(), Flag.CONTAINER);
        if (deny != null) e.setCancelled(true);
    }

    // ── 领地飞行 (Flag.FLY) — 创造/OP 飞行 ──
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFlyToggle(PlayerToggleFlightEvent e) {
        if (!e.isFlying()) return;
        String deny = checkFlag(e.getPlayer(), e.getPlayer().getLocation(), Flag.FLY);
        if (deny != null) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(msg("prefix") + " " + deny);
        }
    }

    // ── 鞘翅滑翔 (Flag.FLY) — 生存飞行最常用 ──
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onElytraGlide(EntityToggleGlideEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (!e.isGliding()) return;
        String deny = checkFlag(p, p.getLocation(), Flag.FLY);
        if (deny != null) {
            e.setCancelled(true);
            p.sendMessage(msg("prefix") + " " + deny);
        }
    }

    // ── 骑乘坐骑 (Flag.RIDE) — 上马/猪/船/矿车等 ──
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onMount(EntityMountEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        String deny = checkFlag(p, e.getMount().getLocation(), Flag.RIDE);
        if (deny != null) {
            e.setCancelled(true);
            p.sendMessage(msg("prefix") + " " + deny);
        }
    }

    // ── 剪羊毛 (Flag.SHEAR) ──
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onShear(PlayerShearEntityEvent e) {
        String deny = checkFlag(e.getPlayer(), e.getEntity().getLocation(), Flag.SHEAR);
        if (deny != null) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(msg("prefix") + " " + deny);
        }
    }

    // ── 挤奶 (Flag.SHEAR — 奶牛) ──
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onMilkCow(PlayerInteractEntityEvent e) {
        Player p = e.getPlayer();
        if (p.getInventory().getItemInMainHand().getType() != Material.BUCKET) return;
        if (!(e.getRightClicked() instanceof Cow)) return;
        String deny = checkFlag(p, e.getRightClicked().getLocation(), Flag.SHEAR);
        if (deny != null) {
            e.setCancelled(true);
            p.sendMessage(msg("prefix") + " " + deny);
        }
    }

    // ── 剪蘑菇 (Flag.SHEAR — 哞菇) ──
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onShearMushroom(PlayerInteractEntityEvent e) {
        Player p = e.getPlayer();
        if (p.getInventory().getItemInMainHand().getType() != Material.SHEARS) return;
        if (!(e.getRightClicked() instanceof MushroomCow)) return;
        String deny = checkFlag(p, e.getRightClicked().getLocation(), Flag.SHEAR);
        if (deny != null) {
            e.setCancelled(true);
            p.sendMessage(msg("prefix") + " " + deny);
        }
    }

    // ── 告示牌编辑 (Flag.SIGN) ──
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent e) {
        String deny = checkFlag(e.getPlayer(), e.getBlock().getLocation(), Flag.SIGN);
        if (deny != null) e.setCancelled(true);
    }

    // ── 喷溅药水 ──
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPotionSplash(PotionSplashEvent e) {
        if (!(e.getPotion().getShooter() instanceof Player p)) return;
        boolean blocked = false;
        for (LivingEntity entity : e.getAffectedEntities()) {
            if (entity instanceof Player) continue;
            if (entity instanceof Animals || entity instanceof Villager
                    || entity instanceof IronGolem || entity instanceof Snowman) {
                String deny = checkFlag(p, entity.getLocation(), Flag.DAMAGE_ENTITY);
                if (deny != null) { e.setIntensity(entity, 0.0); blocked = true; }
            }
        }
        if (blocked) p.sendMessage(msg("prefix") + " §c领地内不能对生物使用喷溅药水！");
    }

    // ── 炼药锅 ──
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCauldronChange(CauldronLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p) {
            String deny = checkFlag(p, e.getBlock().getLocation(), Flag.FURNACE);
            if (deny != null) e.setCancelled(true);
        }
    }

    // ════════════════════════════════════════
    //  进出提示 + 粒子 + Flag.MOVE 检查
    // ════════════════════════════════════════
    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (e.getFrom().getBlockX() == e.getTo().getBlockX()
                && e.getFrom().getBlockZ() == e.getTo().getBlockZ()) return;

        // ── 传送取消: 倒计时期间移动超过1格取消 ──
        TpTask tpTask = tpTasks.get(p.getUniqueId());
        if (tpTask != null) {
            Location from = tpTask.dest; // dest 存的是原始位置
            if (Math.abs(e.getTo().getBlockX() - from.getBlockX()) > 1
                    || Math.abs(e.getTo().getBlockZ() - from.getBlockZ()) > 1
                    || Math.abs(e.getTo().getBlockY() - from.getBlockY()) > 1) {
                Bukkit.getScheduler().cancelTask(tpTask.taskId);
                tpTasks.remove(p.getUniqueId());
                p.sendMessage(msg("prefix") + " §c传送已取消！(你移动了)");
            }
        }

        // 木斧可视化
        if (p.getInventory().getItemInMainHand().getType() == Material.WOODEN_AXE) {
            Long last = lastParticle.get(p.getUniqueId());
            if (last == null || System.currentTimeMillis() - last > 600) {
                lastParticle.put(p.getUniqueId(), System.currentTimeMillis());
                Claim near = getClaimAt(p.getLocation(), null);
                if (near != null) showClaimParticles(p, near);
            }
        }

        // MOVE 权限检查 — 对照 Land onMove
        PlayerData pd = getPlayerData(p.getUniqueId());
        if (!pd.ignoreClaims) {
            Claim toClaim = getClaimAt(e.getTo(), pd);
            if (toClaim != null && !toClaim.checkFlag(p.getUniqueId(), Flag.MOVE)) {
                e.setCancelled(true);
                return;
            }
            // FLY 检查: 飞入禁飞领地强制落地
            if (toClaim != null && !toClaim.checkFlag(p.getUniqueId(), Flag.FLY)
                    && (p.isFlying() || p.isGliding())) {
                p.setFlying(false);
                p.setGliding(false);
                p.sendMessage(msg("prefix") + " §c此领地禁止飞行！已强制落地");
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
            Set<Flag> mf = at.memberFlags.get(p.getUniqueId());
            String roleTag = at.owner.equals(p.getUniqueId()) ? "§a[主人] "
                    : mf != null ? "§e[成员] " : "§7";
            if (!at.enterMsg.isEmpty())
                p.sendMessage(Color.colorize(at.enterMsg.replace("%player%", p.getName())
                        .replace("%owner%", at.ownerName).replace("%claim%", at.name)));
            p.showTitle(Title.title(
                    Component.text(at.name, NamedTextColor.GREEN),
                    Component.text(roleTag + "主人: " + at.ownerName, NamedTextColor.GRAY),
                    Title.Times.times(Duration.ofMillis(400), Duration.ofSeconds(2), Duration.ofMillis(400))));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.5f, 1.5f);
        } else if (newId == null && oldId != null) {
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
                : c.memberFlags.containsKey(p.getUniqueId()) ? Particle.COMPOSTER : Particle.DRIPPING_LAVA;
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
                case "subcreate", "sub" -> doSubCreate(p, a);
                case "expand" -> doExpand(p, a);
                case "shrink", "contract" -> doShrink(p, a);
                case "invite", "trust" -> doInvite(p, a);
                case "accept" -> doAccept(p, a);
                case "deny", "reject" -> doDeny(p, a);
                case "invites" -> doInvites(p);
                case "kick", "untrust" -> doKick(p, a);
                case "remove", "delete" -> doRemove(p, a);
                case "give", "transfer" -> doGive(p, a);
                case "rename" -> doRename(p, a);
                case "setmsg" -> doSetmsg(p, a);
                case "sell" -> doSell(p, a);
                case "buy" -> doBuy(p, a);
                case "tp", "home" -> doTp(p, a);
                case "setspawn" -> doSetspawn(p, a);
                case "admin" -> doAdmin(p, a);
                case "list" -> openMainGui(p);
                case "all" -> doAll(p, a);
                case "map" -> openMapGui(p);
                case "shop" -> openBuyGui(p);
                case "screen", "search" -> doScreen(p, a);
                case "flag" -> doFlag(p, a);
                case "info" -> doInfo(p);
                case "rent" -> doRent(p, a);
                case "unrent" -> doUnrent(p);
                case "lease" -> doLease(p, a);
                case "evict" -> doEvict(p, a);
                case "setsign" -> doSetSign(p);
                default -> showHelp(p);
            }
            return true;
        }

        // ── /claim flag <领地> <成员> <Flag名称> ──
        private void doFlag(Player p, String[] a) {
            if (a.length < 3) {
                p.sendMessage(msg("prefix") + " §c用法: /claim flag <领地> <成员> <Flag名称>");
                p.sendMessage(msg("prefix") + " §7可用Flag: " +
                        Arrays.stream(Flag.values()).map(f -> f.name()).collect(Collectors.joining(", ")));
                return;
            }
            Claim c = findClaim(p.getUniqueId(), a[1]);
            if (c == null) { p.sendMessage(msg("prefix") + " §c领地不存在！"); return; }
            if (!c.owner.equals(p.getUniqueId())) {
                p.sendMessage(msg("prefix") + " §c你不是领地主人！"); return;
            }

            // 查找成员
            UUID targetUid = null;
            String targetName = a[2];
            Player targetPlayer = Bukkit.getPlayer(a[2]);
            if (targetPlayer != null) {
                targetUid = targetPlayer.getUniqueId();
                targetName = targetPlayer.getName();
            } else {
                for (var entry : c.memberNames.entrySet()) {
                    if (entry.getValue().equalsIgnoreCase(a[2])) {
                        targetUid = entry.getKey(); targetName = entry.getValue(); break;
                    }
                }
                if (targetUid == null) {
                    for (UUID uid : c.memberFlags.keySet()) {
                        if (uid.toString().equalsIgnoreCase(a[2]) || uid.toString().startsWith(a[2])) {
                            targetUid = uid; targetName = c.memberNames.getOrDefault(uid, uid.toString().substring(0, 8));
                            break;
                        }
                    }
                }
            }
            if (targetUid == null || !c.isMember(targetUid)) {
                p.sendMessage(msg("prefix") + " §c该玩家不是领地成员！"); return;
            }

            // 查找 Flag
            Flag flag = null;
            for (Flag f : Flag.values()) {
                if (f.name().equalsIgnoreCase(a[3])) { flag = f; break; }
            }
            if (flag == null) {
                p.sendMessage(msg("prefix") + " §c未知Flag: " + a[3]);
                return;
            }

            c.toggleMemberFlag(targetUid, flag);
            boolean has = c.memberFlags.get(targetUid).contains(flag);
            saveAll();
            p.sendMessage(msg("prefix") + " §a已将 §e" + targetName + " §a的 §e" + flag.name
                    + " §a设置为: " + (has ? "§2§l✔ 允许" : "§c§l✘ 禁止"));
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
            p.sendMessage(msg("prefix") + " §a领地 §e" + name + " §a创建成功！大小: §e" + dx + "x" + dz);
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
            if (target.getUniqueId().equals(p.getUniqueId())) {
                p.sendMessage(msg("prefix") + " §c不能邀请自己！"); return;
            }
            if (cl.isMember(target.getUniqueId())) {
                p.sendMessage(msg("prefix") + " §e" + target.getName() + " §7已是成员！"); return;
            }
            // 移除旧的邀请
            invites.remove(target.getUniqueId());
            // 创建邀请 (Land风格: 60秒超时)
            ClaimInvite inv = new ClaimInvite(cl, p.getUniqueId(), p.getName(), target.getUniqueId(), target.getName());
            invites.put(target.getUniqueId(), inv);
            // 发送消息
            p.sendMessage(msg("prefix") + " §a已向 §e" + target.getName() + " §a发送领地 §e" + cl.name + " §a的邀请！§7(60秒有效)");
            target.sendMessage("§8§m                                           ");
            target.sendMessage(msg("prefix") + " §e" + p.getName() + " §a邀请你加入领地 §e§l" + cl.name);
            target.sendMessage("  §a  接受: §e/claim accept " + p.getName());
            target.sendMessage("  §c  拒绝: §e/claim deny " + p.getName());
            target.sendMessage("  §7  邀请将在 60 秒后过期");
            target.sendMessage("§8§m                                           ");
            target.playSound(target.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1.2f);
        }

        private void doKick(Player p, String[] a) {
            if (a.length < 2) { p.sendMessage(msg("prefix") + " §c用法: /claim kick <玩家> [领地]"); return; }
            Claim cl = a.length >= 3 ? findClaim(p.getUniqueId(), a[2]) : getClaimAt(p.getLocation(), null);
            if (cl == null || !cl.owner.equals(p.getUniqueId())) {
                p.sendMessage(msg("prefix") + " §c你没有权限！"); return;
            }
            Player target = Bukkit.getPlayer(a[1]);
            UUID uuid = target != null ? target.getUniqueId() : null;
            if (uuid == null) {
                // 尝试通过名字查找
                for (var e : cl.memberNames.entrySet()) {
                    if (e.getValue().equalsIgnoreCase(a[1])) { uuid = e.getKey(); break; }
                }
            }
            if (uuid == null) { p.sendMessage(msg("player-not-found")); return; }
            if (cl.removeMember(uuid)) {
                saveAll();
                p.sendMessage(msg("prefix") + " §c已将 §e" + (target != null ? target.getName() : a[1]) + " §c移出领地");
            } else { p.sendMessage(msg("prefix") + " §c该玩家不是成员！"); }
        }

        private void doRemove(Player p, String[] a) {
            if (a.length < 2) { p.sendMessage(msg("prefix") + " §c用法: /claim remove <领地>"); return; }
            Claim cl = findClaim(p.getUniqueId(), a[1]);
            if (cl == null) { p.sendMessage(msg("prefix") + " §c领地不存在！"); return; }
            if (!cl.owner.equals(p.getUniqueId()) && !p.hasPermission("megaplugin.claim.admin")) {
                p.sendMessage(msg("prefix") + " §c你不是领地主人！"); return;
            }

            // 递归删除子领地
            List<Claim> toRemove = new ArrayList<>();
            collectAllSubClaims(cl, toRemove);
            // 如果是子领地，从父领地中移除
            if (cl.isSubClaim()) {
                Claim parent = claims.get(cl.parentId);
                if (parent != null) parent.subClaims.remove(cl);
            }

            for (Claim sub : toRemove) {
                claims.remove(sub.id);
                data.getConfig().set(sub.id, null);
            }
            claims.remove(cl.id);
            data.getConfig().set(cl.id, null);
            rebuildChunkIndex();
            saveAll();
            p.sendMessage(msg("prefix") + " §c领地 §e" + cl.name + " §c已删除" +
                    (toRemove.isEmpty() ? "" : " §7(含 " + toRemove.size() + " 个子领地)"));
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
            cl.memberFlags.clear(); cl.memberNames.clear(); cl.price = 0;
            saveAll();
            p.sendMessage(msg("prefix") + " §a成功购买领地 §e" + cl.name + "！");
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        }

        private void doTp(Player p, String[] a) {
            Claim cl = a.length >= 2 ? findClaim(p.getUniqueId(), a[1]) : getClaimAt(p.getLocation(), null);
            if (cl == null) cl = getPlayerClaims(p.getUniqueId()).stream().findFirst().orElse(null);
            if (cl == null) { p.sendMessage(msg("prefix") + " §c你没有领地！"); return; }
            final Claim claim = cl; // effectively final for lambda
            final String claimName = claim.name;

            // ── 传送权限检查 ──
            if (!claim.owner.equals(p.getUniqueId()) && !claim.isMember(p.getUniqueId())) {
                PlayerData pd = getPlayerData(p.getUniqueId());
                if (!pd.ignoreClaims && !claim.defaultFlags.contains(Flag.TELEPORT)) {
                    p.sendMessage(msg("prefix") + " §c此领地禁止传送！"); return;
                }
            }

            // ── 冷却检查 (30秒) ──
            Long cd = tpCooldowns.get(p.getUniqueId());
            if (cd != null && System.currentTimeMillis() - cd < 30000) {
                int remain = (int)(30 - (System.currentTimeMillis() - cd) / 1000);
                p.sendMessage(msg("prefix") + " §c传送冷却中！请等待 §e" + remain + " §c秒"); return;
            }

            // 目标位置
            Location dest;
            if (claim.spawn != null) dest = claim.spawn.clone();
            else {
                World w = Bukkit.getWorld(claim.world);
                dest = new Location(w, (claim.minX + claim.maxX) / 2.0 + 0.5,
                        w != null ? w.getHighestBlockYAt((claim.minX + claim.maxX) / 2, (claim.minZ + claim.maxZ) / 2) + 1 : 64,
                        (claim.minZ + claim.maxZ) / 2.0 + 0.5);
            }

            // ── 取消旧传送任务 ──
            TpTask old = tpTasks.get(p.getUniqueId());
            if (old != null) Bukkit.getScheduler().cancelTask(old.taskId);

            // ── 创建倒计时传送 ──
            final int TP_DELAY = 5; // 5秒倒计时
            Location finalDest = dest.clone();
            final int[] count = {TP_DELAY};
            final Location origin = p.getLocation().clone();

            int taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
                TpTask cur = tpTasks.get(p.getUniqueId());
                if (cur == null || !p.isOnline()) return;
                count[0]--;
                if (count[0] <= 0) {
                    Bukkit.getScheduler().cancelTask(cur.taskId);
                    tpTasks.remove(p.getUniqueId());
                    tpCooldowns.put(p.getUniqueId(), System.currentTimeMillis());
                    p.teleport(finalDest);
                    p.sendMessage(msg("prefix") + " §a已传送到领地 §e" + claimName);
                    p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
                } else {
                    p.sendMessage(msg("prefix") + " §7传送倒计时: §e" + count[0] + " §7秒... (请不要移动)");
                }
            }, 0L, 20L);

            TpTask task = new TpTask(p.getUniqueId(), origin, taskId);
            tpTasks.put(p.getUniqueId(), task);
            p.sendMessage(msg("prefix") + " §a传送将在 §e" + TP_DELAY + " §a秒后执行，请不要移动！");
        }

        private void doSetspawn(Player p, String[] a) {
            Claim cl = a.length >= 2 ? findClaim(p.getUniqueId(), a[1]) : getClaimAt(p.getLocation(), null);
            if (cl == null || !cl.owner.equals(p.getUniqueId())) {
                p.sendMessage(msg("prefix") + " §c你必须在自己的领地内！"); return;
            }
            cl.spawn = p.getLocation().clone(); saveAll();
            p.sendMessage(msg("prefix") + " §a已设置领地 §e" + cl.name + " §a的传送点！");
        }

        private void doAdmin(Player p, String[] a) {
            if (!p.hasPermission("megaplugin.claim.admin")) {
                p.sendMessage(msg("prefix") + " §c你没有管理员权限！"); return;
            }
            PlayerData pd = getPlayerData(p.getUniqueId());
            pd.ignoreClaims = !pd.ignoreClaims;
            p.sendMessage(msg("prefix") + " §a管理员模式: " + (pd.ignoreClaims ? "§c§l关闭领地保护" : "§2§l正常"));
        }

        // ── /claim info — 调试: 查看当前位置的领地状态和权限 ──
        private void doInfo(Player p) {
            PlayerData pd = getPlayerData(p.getUniqueId());
            Claim c = getClaimAt(p.getLocation(), null);
            p.sendMessage("§8§m          §r §e§l领地信息 §8§m          ");
            if (c == null) {
                p.sendMessage("§7你当前不在任何领地内");
            } else {
                p.sendMessage("§7领地: §a" + c.name + " §8[" + c.id + "]");
                p.sendMessage("§7主人: §e" + c.ownerName + " §7世界: §e" + c.world);
                p.sendMessage("§7范围: §f" + c.minX + "," + c.minZ + " §7~ §f" + c.maxX + "," + c.maxZ);
                p.sendMessage("§7大小: §e" + (c.maxX - c.minX + 1) + "x" + (c.maxZ - c.minZ + 1));
                boolean isOwner = c.owner.equals(p.getUniqueId());
                boolean isMember = c.memberFlags.containsKey(p.getUniqueId());
                String ownerTag = isOwner ? ("§a§l主人(" + c.ownerFlags.size() + "/" + Flag.values().length + " Flag)") : "";
                p.sendMessage("§7身份: " + (isOwner ? ownerTag : isMember ? "§e成员" : "§7访客"));
                p.sendMessage("§7管理员模式: " + (pd.ignoreClaims ? "§c§l已绕过" : "§2正常"));
                Set<Flag> myFlags = isOwner ? c.ownerFlags : (isMember ? c.memberFlags.get(p.getUniqueId()) : c.defaultFlags);
                p.sendMessage("§7你的Flag权限: §e" + (myFlags != null ? myFlags.size() : 0) + "/" + Flag.values().length);
                StringBuilder sb = new StringBuilder();
                if (myFlags != null) {
                    for (Flag f : Flag.values()) {
                        boolean has = myFlags.contains(f);
                        sb.append(has ? "§a" : "§c").append(f.name).append(" ");
                    }
                }
                p.sendMessage("§7" + sb.toString().trim());
                // 环境设置
                p.sendMessage("§7环境设置: §b");
                for (OtherFlag of : OtherFlag.values()) {
                    boolean on = c.otherFlags.getOrDefault(of, true);
                    p.sendMessage("  " + (on ? "§a" : "§c") + of.name + (on ? " ✔" : " ✘"));
                }
            }
            p.sendMessage("§8§m                              ");
        }

        // ── /claim shrink <数值> ── 根据朝向收缩领地 ──
        private void doShrink(Player p, String[] a) {
            if (a.length < 2) { p.sendMessage(msg("prefix") + " §7用法: /claim shrink <格数>"); return; }
            int amount;
            try { amount = Integer.parseInt(a[1]); } catch (Exception ex) {
                p.sendMessage(msg("prefix") + " §c格数必须是整数！"); return;
            }
            if (amount <= 0 || amount > 16) {
                p.sendMessage(msg("prefix") + " §c收缩格数必须在 1~16 之间！"); return;
            }
            Claim c = getClaimAt(p.getLocation(), null);
            if (c == null || !c.owner.equals(p.getUniqueId())) {
                p.sendMessage(msg("prefix") + " §c你必须站在自己的领地内！"); return;
            }
            if (c.isSubClaim() || !c.subClaims.isEmpty()) {
                p.sendMessage(msg("prefix") + " §c有子领地的领地不能收缩！"); return;
            }
            if (c.price > 0) { p.sendMessage(msg("prefix") + " §c出售中的领地不能收缩！"); return; }
            if (c.isRented()) { p.sendMessage(msg("prefix") + " §c出租中的领地不能收缩！"); return; }

            float pitch = p.getLocation().getPitch();
            float yaw = p.getLocation().getYaw();
            int oldMinX = c.minX, oldMinZ = c.minZ, oldMaxX = c.maxX, oldMaxZ = c.maxZ;

            if (pitch >= 50) {
                if (c.maxY - amount < c.minY + 2) { p.sendMessage(msg("prefix") + " §c收缩后高度不足(最小3格)！"); return; }
                c.maxY -= amount;
            } else if (pitch <= -50) {
                if (c.minY + amount > c.maxY - 2) { p.sendMessage(msg("prefix") + " §c收缩后高度不足(最小3格)！"); return; }
                c.minY += amount;
            } else {
                yaw = (yaw + 360) % 360;
                if (yaw >= 45 && yaw < 135) {
                    if (c.maxX - c.minX - amount < 2) { p.sendMessage(msg("prefix") + " §c收缩后宽度不足(最小3格)！"); return; }
                    c.minX += amount; // 收缩西边界(缩小minX=东移)
                } else if (yaw >= 135 && yaw < 225) {
                    if (c.maxZ - c.minZ - amount < 2) { p.sendMessage(msg("prefix") + " §c收缩后宽度不足(最小3格)！"); return; }
                    c.minZ += amount; // 收缩北边界
                } else if (yaw >= 225 && yaw < 315) {
                    if (c.maxX - c.minX - amount < 2) { p.sendMessage(msg("prefix") + " §c收缩后宽度不足(最小3格)！"); return; }
                    c.maxX -= amount; // 收缩东边界
                } else {
                    if (c.maxZ - c.minZ - amount < 2) { p.sendMessage(msg("prefix") + " §c收缩后宽度不足(最小3格)！"); return; }
                    c.maxZ -= amount; // 收缩南边界
                }
            }

            // 检查玩家是否还在领地内
            Location pl = p.getLocation();
            if (pl.getBlockX() < c.minX || pl.getBlockX() > c.maxX
                    || pl.getBlockZ() < c.minZ || pl.getBlockZ() > c.maxZ) {
                c.minX = oldMinX; c.minZ = oldMinZ; c.maxX = oldMaxX; c.maxZ = oldMaxZ;
                p.sendMessage(msg("prefix") + " §c收缩后你不在领地内！请站在领地内部再试。"); return;
            }

            rebuildChunkIndex(); saveAll();
            int newSize = (c.maxX - c.minX + 1) * (c.maxZ - c.minZ + 1);
            p.sendMessage(msg("prefix") + " §a领地已收缩 " + amount + " 格！当前大小: §e" + newSize + " §a格");
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1.2f);
        }

        // ── /claim rent <价格> [天数] ── 设置领地出租 ──
        private void doRent(Player p, String[] a) {
            if (a.length < 2) { p.sendMessage(msg("prefix") + " §7用法: /claim rent <价格> [天数=7]"); return; }
            double price;
            try { price = Double.parseDouble(a[1]); } catch (Exception ex) {
                p.sendMessage(msg("prefix") + " §c价格必须是数字！"); return;
            }
            int days = 7;
            if (a.length >= 3) {
                try { days = Integer.parseInt(a[2]); } catch (Exception ex) {
                    p.sendMessage(msg("prefix") + " §c天数必须是整数！"); return;
                }
            }
            if (price < 0) { p.sendMessage(msg("prefix") + " §c价格不能为负数！"); return; }
            if (days < 1 || days > 90) { p.sendMessage(msg("prefix") + " §c天数必须在 1~90 之间！"); return; }

            Claim c = getClaimAt(p.getLocation(), null);
            if (c == null || !c.owner.equals(p.getUniqueId())) {
                p.sendMessage(msg("prefix") + " §c你必须站在自己的领地内！"); return;
            }
            if (c.isSubClaim()) { p.sendMessage(msg("prefix") + " §c子领地不能出租！"); return; }
            if (c.price > 0) { p.sendMessage(msg("prefix") + " §c出售中的领地不能出租，请先取消出售！"); return; }

            c.rentPrice = price;
            c.rentDays = days;
            c.rentedTo = null;
            c.rentedToName = null;
            c.rentEndTime = 0;
            saveAll();
            if (price == 0) {
                p.sendMessage(msg("prefix") + " §7已取消领地 §e" + c.name + " §7的出租");
            } else {
                p.sendMessage(msg("prefix") + " §a领地 §e" + c.name + " §a已挂牌出租！价格: §e" + price + " §a/ §e" + days + "§a天");
            }
        }

        // ── /claim unrent ── 取消出租 ──
        private void doUnrent(Player p) {
            Claim c = getClaimAt(p.getLocation(), null);
            if (c == null || !c.owner.equals(p.getUniqueId())) {
                p.sendMessage(msg("prefix") + " §c你必须站在自己的领地内！"); return;
            }
            if (c.rentPrice == 0) { p.sendMessage(msg("prefix") + " §7该领地未出租！"); return; }
            c.rentPrice = 0;
            if (c.isRented()) {
                Player renter = Bukkit.getPlayer(c.rentedTo);
                if (renter != null) renter.sendMessage(msg("prefix") + " §c领地 §e" + c.name + " §c已被主人收回，租赁提前终止！");
                c.rentedTo = null; c.rentedToName = null; c.rentEndTime = 0;
            }
            saveAll();
            p.sendMessage(msg("prefix") + " §7已取消领地 §e" + c.name + " §7的出租");
        }

        // ── /claim lease <领地名> ── 租用领地 ──
        private void doLease(Player p, String[] a) {
            if (a.length < 2) { p.sendMessage(msg("prefix") + " §7用法: /claim lease <领地名>"); return; }
            Claim c = null;
            for (Claim cl : claims.values()) {
                if (cl.name.equalsIgnoreCase(a[1]) && cl.rentPrice > 0 && !cl.isSubClaim()) { c = cl; break; }
            }
            if (c == null) { p.sendMessage(msg("prefix") + " §c找不到可租用的领地！"); return; }
            if (c.owner.equals(p.getUniqueId())) { p.sendMessage(msg("prefix") + " §c不能租用自己的领地！"); return; }
            if (c.isRented()) { p.sendMessage(msg("prefix") + " §c该领地已被租用，到期时间: §e" + formatTime(c.rentEndTime)); return; }

            EconomyModule eco = plugin.getEconomyModule();
            if (eco == null) { p.sendMessage(msg("prefix") + " §c经济系统未启用！"); return; }
            if (!eco.hasEnough(p.getUniqueId(), c.rentPrice)) {
                p.sendMessage(msg("prefix") + " §c余额不足！需要 §e" + c.rentPrice); return;
            }
            eco.withdraw(p, c.rentPrice);
            Player owner = Bukkit.getPlayer(c.owner);
            if (owner != null) eco.deposit(owner, c.rentPrice);
            else eco.deposit(c.owner, c.rentPrice);

            c.rentedTo = p.getUniqueId();
            c.rentedToName = p.getName();
            c.rentEndTime = System.currentTimeMillis() + c.rentDays * 86400000L;
            // 租客成为成员
            c.addMember(p.getUniqueId(), p.getName());
            saveAll();

            p.sendMessage(msg("prefix") + " §a成功租用领地 §e" + c.name + "§a！");
            p.sendMessage("§7价格: §e" + c.rentPrice + " §7天数: §e" + c.rentDays + " §7到期: §e" + formatTime(c.rentEndTime));
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
            if (owner != null)
                owner.sendMessage(msg("prefix") + " §a" + p.getName() + " 租用了你的领地 §e" + c.name);
        }

        // ── /claim evict [领地名] ── 驱逐租客 ──
        private void doEvict(Player p, String[] a) {
            Claim c;
            if (a.length >= 2) {
                c = findClaim(p.getUniqueId(), a[1]);
            } else {
                c = getClaimAt(p.getLocation(), null);
            }
            if (c == null || !c.owner.equals(p.getUniqueId())) {
                p.sendMessage(msg("prefix") + " §c这不是你的领地！"); return;
            }
            if (!c.isRented()) { p.sendMessage(msg("prefix") + " §7该领地没有租客！"); return; }
            Player renter = Bukkit.getPlayer(c.rentedTo);
            if (renter != null) renter.sendMessage(msg("prefix") + " §c你已被驱逐出领地 §e" + c.name);
            c.removeMember(c.rentedTo);
            c.rentedTo = null; c.rentedToName = null; c.rentEndTime = 0;
            saveAll();
            p.sendMessage(msg("prefix") + " §a已驱逐租客，领地 §e" + c.name + " §a现在空闲");
        }

        // ── /claim setsign ── 设置领地标志牌 ──
        private void doSetSign(Player p) {
            Block target = p.getTargetBlockExact(5);
            if (target == null || !(target.getState() instanceof org.bukkit.block.Sign)) {
                p.sendMessage(msg("prefix") + " §c请看向一个告示牌！"); return;
            }
            Claim c = getClaimAt(target.getLocation(), null);
            if (c == null || !c.owner.equals(p.getUniqueId())) {
                p.sendMessage(msg("prefix") + " §c你只能在自己的领地内设置标志牌！"); return;
            }
            c.signLocation = target.getLocation().clone();
            org.bukkit.block.Sign sign = (org.bukkit.block.Sign) target.getState();
            sign.setLine(0, "§6§l[领地]");
            sign.setLine(1, "§a" + c.name.substring(0, Math.min(c.name.length(), 15)));
            sign.setLine(2, "§e" + c.ownerName);
            sign.setLine(3, "§7" + (c.maxX - c.minX + 1) + "x" + (c.maxZ - c.minZ + 1));
            sign.update();
            saveAll();
            p.sendMessage(msg("prefix") + " §a领地标志牌已设置！");
        }

        private String formatTime(long epochMs) {
            if (epochMs <= 0) return "未知";
            long diff = epochMs - System.currentTimeMillis();
            if (diff <= 0) return "已过期";
            long days = diff / 86400000L;
            long hours = (diff % 86400000L) / 3600000L;
            if (days > 0) return days + "天" + hours + "小时";
            return hours + "小时";
        }

        // ── 接受邀请后添加成员 (对照 Land addPlayer → addMember) ──
        private void inviteAccept(Player p, Claim c) {
            int myClaims = (int) claims.values().stream()
                    .filter(cl -> cl.owner.equals(p.getUniqueId()) && !cl.isSubClaim()).count();
            if (myClaims >= (p.hasPermission("megaplugin.claim.admin") ? 50 : MAX_CLAIMS)) {
                p.sendMessage(msg("prefix") + " §c你的领地已达上限，无法接受邀请！"); return;
            }
            if (c.isMember(p.getUniqueId())) {
                p.sendMessage(msg("prefix") + " §e你已经是该领地的成员！"); return;
            }
            c.addMember(p.getUniqueId(), p.getName());
            saveAll();
            p.sendMessage(msg("prefix") + " §a你已成功加入领地 §e" + c.name + "§a！");
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        }

        // ── /claim accept <玩家> ──
        private void doAccept(Player p, String[] a) {
            if (a.length < 2) { p.sendMessage(msg("prefix") + " §7用法: /claim accept <玩家名>"); return; }
            ClaimInvite inv = invites.get(p.getUniqueId());
            if (inv == null || inv.isExpired()) {
                if (inv != null) invites.remove(p.getUniqueId());
                p.sendMessage(msg("prefix") + " §c没有待处理的邀请！"); return;
            }
            if (!inv.inviterName.equalsIgnoreCase(a[1])) {
                p.sendMessage(msg("prefix") + " §c没有来自 §e" + a[1] + " §c的邀请！§7(你有来自 §e" + inv.inviterName + " §7的邀请)"); return;
            }
            invites.remove(p.getUniqueId());
            inviteAccept(p, inv.claim);
            Player inviter = Bukkit.getPlayer(inv.inviter);
            if (inviter != null) inviter.sendMessage(msg("prefix") + " §a" + p.getName() + " 接受了领地 §e" + inv.claim.name + " §a的邀请！");
        }

        // ── /claim deny <玩家> ──
        private void doDeny(Player p, String[] a) {
            if (a.length < 2) { p.sendMessage(msg("prefix") + " §7用法: /claim deny <玩家名>"); return; }
            ClaimInvite inv = invites.get(p.getUniqueId());
            if (inv == null || inv.isExpired()) {
                if (inv != null) invites.remove(p.getUniqueId());
                p.sendMessage(msg("prefix") + " §c没有待处理的邀请！"); return;
            }
            if (!inv.inviterName.equalsIgnoreCase(a[1])) {
                p.sendMessage(msg("prefix") + " §c没有来自 §e" + a[1] + " §c的邀请！"); return;
            }
            invites.remove(p.getUniqueId());
            p.sendMessage(msg("prefix") + " §c已拒绝来自 §e" + inv.inviterName + " §c的领地邀请");
            Player inviter = Bukkit.getPlayer(inv.inviter);
            if (inviter != null) inviter.sendMessage(msg("prefix") + " §c" + p.getName() + " 拒绝了领地 §e" + inv.claim.name + " §c的邀请");
        }

        // ── /claim invites ──
        private void doInvites(Player p) {
            ClaimInvite inv = invites.get(p.getUniqueId());
            if (inv == null || inv.isExpired()) {
                if (inv != null) invites.remove(p.getUniqueId());
                p.sendMessage(msg("prefix") + " §7没有待处理的领地邀请"); return;
            }
            int sec = Math.max(0, (int)((inv.expireTime - System.currentTimeMillis()) / 1000));
            p.sendMessage(msg("prefix") + " §e" + inv.inviterName + " §7邀请你加入领地: §a" + inv.claim.name + " §7(剩余 §c" + sec + "秒§7)");
            p.sendMessage(" §a接受: /claim accept " + inv.inviterName + "  §c拒绝: /claim deny " + inv.inviterName);
        }

        // ── /claim give <玩家> [领地] ── 转让领地
        private void doGive(Player p, String[] a) {
            if (a.length < 2) { p.sendMessage(msg("prefix") + " §7用法: /claim give <玩家名> [领地名]"); return; }
            Player target = Bukkit.getPlayer(a[1]);
            if (target == null || !target.isOnline()) { p.sendMessage(msg("prefix") + " §c该玩家不在线！"); return; }
            Claim c;
            if (a.length >= 3) {
                c = findClaim(p.getUniqueId(), a[2]);
            } else {
                c = getClaimAt(p.getLocation(), null);
            }
            if (c == null || !c.owner.equals(p.getUniqueId()) || c.isSubClaim()) {
                p.sendMessage(msg("prefix") + " §c领地不存在或不是你的主领地！"); return;
            }
            if (c.price > 0) { p.sendMessage(msg("prefix") + " §c寄售中的领地不能转让！"); return; }
            int targetCount = (int) claims.values().stream().filter(cl -> cl.owner.equals(target.getUniqueId()) && !cl.isSubClaim()).count();
            if (targetCount >= MAX_CLAIMS) { p.sendMessage(msg("prefix") + " §c对方领地已达上限(" + MAX_CLAIMS + "个)！"); return; }
            // 执行转让
            c.owner = target.getUniqueId();
            c.ownerName = target.getName();
            // 子领地也转让
            for (Claim sub : c.subClaims) { sub.owner = target.getUniqueId(); sub.ownerName = target.getName(); }
            saveAll();
            p.sendMessage(msg("prefix") + " §a已将领地 §e" + c.name + " §a转让给 §e" + target.getName());
            target.sendMessage(msg("prefix") + " §a" + p.getName() + " 将领地 §e" + c.name + " §a转让给了你！");
        }

        // ── /claim subcreate <名字> ── 创建子领地
        private void doSubCreate(Player p, String[] a) {
            Claim parent = getClaimAt(p.getLocation(), null);
            if (parent == null || !parent.owner.equals(p.getUniqueId()) || parent.isSubClaim()) {
                p.sendMessage(msg("prefix") + " §c你必须站在自己的主领地内！"); return;
            }
            if (a.length < 2) { p.sendMessage(msg("prefix") + " §7用法: /claim subcreate <名字>"); return; }
            if (parent.subClaims.size() >= 3) { p.sendMessage(msg("prefix") + " §c子领地最多3个！"); return; }
            Location[] sel = selections.get(p.getUniqueId());
            if (sel == null || sel[0] == null || sel[1] == null) {
                p.sendMessage(msg("prefix") + " §c请先用木斧选区！"); return;
            }
            int minX = Math.min(sel[0].getBlockX(), sel[1].getBlockX());
            int maxX = Math.max(sel[0].getBlockX(), sel[1].getBlockX());
            int minZ = Math.min(sel[0].getBlockZ(), sel[1].getBlockZ());
            int maxZ = Math.max(sel[0].getBlockZ(), sel[1].getBlockZ());
            // 必须在主领地内
            if (minX < parent.minX || maxX > parent.maxX || minZ < parent.minZ || maxZ > parent.maxZ) {
                p.sendMessage(msg("prefix") + " §c子领地必须在主领地范围内！"); return;
            }
            // 检查重叠
            for (Claim other : parent.subClaims) {
                if (!(maxX < other.minX || minX > other.maxX || maxZ < other.minZ || minZ > other.maxZ)) {
                    p.sendMessage(msg("prefix") + " §c与现有子领地重叠！"); return;
                }
            }
            Claim sub = new Claim(nextId(), a[1], p.getUniqueId(), p.getName(), parent.world, minX, minZ, maxX, maxZ);
            sub.parentId = parent.id;
            sub.minY = parent.minY; sub.maxY = parent.maxY;
            parent.subClaims.add(sub);
            claims.put(sub.id, sub);
            rebuildChunkIndex();
            saveAll();
            p.sendMessage(msg("prefix") + " §a子领地 §e" + sub.name + " §a已创建！");
        }

        // ── /claim all [页码] ── 查看所有领地
        private void doAll(Player p, String[] a) {
            int page = 1;
            if (a.length >= 2) { try { page = Integer.parseInt(a[1]); } catch (Exception ignored) {} }
            List<Claim> all = claims.values().stream().filter(c -> !c.isSubClaim()).toList();
            int total = (all.size() + 9) / 10;
            if (page < 1) page = 1; if (page > total && total > 0) page = total;
            p.sendMessage("§8§m          §r §e§l全部领地 §7(第" + page + "/" + total + "页) §8§m          ");
            int start = (page - 1) * 10;
            int end = Math.min(start + 10, all.size());
            for (int i = start; i < end; i++) {
                Claim c = all.get(i);
                String sellTag = c.price > 0 ? " §a[出售中 §6" + c.price + "§a]" : "";
                p.sendMessage(" §a" + c.name + sellTag + " §7主人: §e" + c.ownerName + " §7成员: §f" + c.memberFlags.size() + " §7子领地: §f" + c.subClaims.size());
            }
            p.sendMessage("§7使用 /claim all <页码> 翻页");
        }

        // ── /claim expand <数值> ── 根据朝向扩展领地
        private void doExpand(Player p, String[] a) {
            if (a.length < 2) { p.sendMessage(msg("prefix") + " §7用法: /claim expand <格数>"); return; }
            int amount;
            try { amount = Integer.parseInt(a[1]); } catch (Exception ex) {
                p.sendMessage(msg("prefix") + " §c格数必须是整数！"); return;
            }
            if (amount <= 0 || amount > 16) {
                p.sendMessage(msg("prefix") + " §c扩展格数必须在 1~16 之间！"); return;
            }
            Claim c = getClaimAt(p.getLocation(), null);
            if (c == null || !c.owner.equals(p.getUniqueId())) {
                p.sendMessage(msg("prefix") + " §c你必须站在自己的领地内！"); return;
            }
            if (c.isSubClaim() || !c.subClaims.isEmpty()) {
                p.sendMessage(msg("prefix") + " §c有子领地的领地不能扩展！"); return;
            }
            if (c.price > 0) { p.sendMessage(msg("prefix") + " §c出售中的领地不能扩展！"); return; }

            // 判断朝向 (Land 风格: 俯仰角 + 偏航角)
            float pitch = p.getLocation().getPitch();
            float yaw = p.getLocation().getYaw();
            int oldMinX = c.minX, oldMinZ = c.minZ, oldMaxX = c.maxX, oldMaxZ = c.maxZ;

            if (pitch >= 50) {
                // 向上看 → 向上扩展
                if (c.maxY + amount > 319) { p.sendMessage(msg("prefix") + " §c已达到世界高度上限！"); return; }
                c.maxY += amount;
            } else if (pitch <= -50) {
                // 向下看 → 向下扩展
                if (c.minY - amount < -64) { p.sendMessage(msg("prefix") + " §c已达到世界深度下限！"); return; }
                c.minY -= amount;
            } else {
                // 水平方向扩展
                yaw = (yaw + 360) % 360;
                if (yaw >= 45 && yaw < 135) {
                    c.minX -= amount; // 朝西
                } else if (yaw >= 135 && yaw < 225) {
                    c.minZ -= amount; // 朝北
                } else if (yaw >= 225 && yaw < 315) {
                    c.maxX += amount; // 朝东
                } else {
                    c.maxZ += amount; // 朝南
                }
            }

            // 重叠检查
            for (Claim other : claims.values()) {
                if (other == c || other.world == null || !other.world.equals(c.world)) continue;
                if (other.maxX < c.minX || other.minX > c.maxX
                        || other.maxZ < c.minZ || other.minZ > c.maxZ) continue;
                // 恢复并报错
                c.minX = oldMinX; c.minZ = oldMinZ; c.maxX = oldMaxX; c.maxZ = oldMaxZ;
                p.sendMessage(msg("prefix") + " §c扩展后与领地 §e" + other.name + " §c重叠！"); return;
            }

            rebuildChunkIndex();
            saveAll();
            int newSize = (c.maxX - c.minX + 1) * (c.maxZ - c.minZ + 1);
            p.sendMessage(msg("prefix") + " §a领地已扩展！当前大小: §e" + newSize + " §a格");
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.5f);
        }

        // ── /claim screen [关键词] ── 搜索领地
        private void doScreen(Player p, String[] a) {
            String keyword = a.length >= 2 ? String.join(" ", Arrays.copyOfRange(a, 1, a.length)).toLowerCase() : "";
            List<Claim> results = claims.values().stream()
                    .filter(c -> keyword.isEmpty()
                            || c.name.toLowerCase().contains(keyword)
                            || c.ownerName.toLowerCase().contains(keyword)
                            || c.id.toLowerCase().contains(keyword))
                    .sorted((c1, c2) -> {
                        // 自己的领地优先
                        boolean m1 = c1.owner.equals(p.getUniqueId());
                        boolean m2 = c2.owner.equals(p.getUniqueId());
                        if (m1 != m2) return m1 ? -1 : 1;
                        return c1.name.compareToIgnoreCase(c2.name);
                    })
                    .limit(20)
                    .toList();

            p.sendMessage("§8§m          §r §d§l领地搜索 §8§m          ");
            if (keyword.isEmpty()) {
                p.sendMessage("§7显示全部领地 (最多20个)");
            } else {
                p.sendMessage("§7搜索关键词: §f" + keyword + " §7找到 §e" + results.size() + " §7个");
            }
            if (results.isEmpty()) {
                p.sendMessage("§c没有找到匹配的领地");
            } else {
                for (Claim c : results) {
                    String ownerTag = c.owner.equals(p.getUniqueId()) ? " §a[我的]" : "";
                    String subTag = c.isSubClaim() ? " §7[子领地]" : "";
                    String sellTag = c.price > 0 ? " §6[出售中]" : "";
                    String size = (c.maxX - c.minX + 1) + "x" + (c.maxZ - c.minZ + 1);
                    p.sendMessage(" §a" + c.name + subTag + sellTag + ownerTag +
                            " §7- §e" + c.ownerName + " §7| §f" + size +
                            " §7| §f" + c.memberFlags.size() + "§7成员");
                }
            }
            p.sendMessage("§7使用 §e/claim screen <关键词> §7搜索");
            p.sendMessage("§8§m                                  ");
        }

        private void showHelp(Player p) {
            p.sendMessage("§8§m          §r §a§l领地系统 v5 §8§m          ");
            p.sendMessage(" §7/claim §e- 打开领地菜单");
            p.sendMessage(" §7/claim create <名字> §e- 创建领地");
            p.sendMessage(" §7/claim subcreate <名字> §e- 创建子领地");
            p.sendMessage(" §7/claim expand <格数> §e- 扩展领地");
            p.sendMessage(" §7/claim shrink <格数> §e- 收缩领地");
            p.sendMessage(" §7/claim invite <玩家> §e- 邀请成员(60秒有效)");
            p.sendMessage(" §7/claim accept <玩家> §e- 接受邀请");
            p.sendMessage(" §7/claim deny <玩家> §e- 拒绝邀请");
            p.sendMessage(" §7/claim kick <玩家> §e- 移除成员");
            p.sendMessage(" §7/claim give <玩家> §e- 转让领地");
            p.sendMessage(" §7/claim remove <领地> §e- 删除领地");
            p.sendMessage(" §7/claim rent <价格> [天] §e- 出租领地");
            p.sendMessage(" §7/claim unrent §e- 取消出租");
            p.sendMessage(" §7/claim lease <领地> §e- 租用领地");
            p.sendMessage(" §7/claim evict [领地] §e- 驱逐租客");
            p.sendMessage(" §7/claim setsign §e- 设置领地标志牌");
            p.sendMessage(" §7/claim tp [领地] §e- 传送(5秒倒计时)");
            p.sendMessage(" §7/claim all [页码] §e- 查看所有领地");
            p.sendMessage(" §7/claim screen [搜索] §e- 搜索领地");
            p.sendMessage(" §7/claim info §e- 查看领地信息");
            p.sendMessage("§8§m                                  ");
        }
    }

    private class ClaimTab implements TabCompleter {
        @Override
        public List<String> onTabComplete(CommandSender s, Command cmd, String l, String[] a) {
            if (!(s instanceof Player p)) return Collections.emptyList();
            List<String> cmds = Arrays.asList("create", "subcreate", "expand", "shrink", "invite", "accept", "deny",
                    "invites", "kick", "remove", "give", "rename", "setmsg", "sell", "buy", "tp",
                    "setspawn", "admin", "list", "all", "map", "shop", "screen", "flag", "info",
                    "rent", "unrent", "lease", "evict", "setsign");
            if (a.length == 1) return cmds.stream().filter(x -> x.startsWith(a[0].toLowerCase())).collect(Collectors.toList());

            // invite/kick/give/accept/deny 第二个参数: 在线玩家
            if (a.length == 2 && List.of("invite", "kick", "give", "accept", "deny").contains(a[0].toLowerCase()))
                return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                        .filter(x -> x.toLowerCase().startsWith(a[1].toLowerCase())).collect(Collectors.toList());
            // remove/rename/tp/flag 第二个参数: 自己的领地
            if (a.length == 2 && List.of("remove", "rename", "tp", "flag").contains(a[0].toLowerCase()))
                return getPlayerClaims(p.getUniqueId()).stream().map(cl -> cl.name)
                        .filter(x -> x.toLowerCase().startsWith(a[1].toLowerCase())).collect(Collectors.toList());
            // setmsg 第二个参数: enter/leave
            if (a.length == 2 && a[0].equalsIgnoreCase("setmsg"))
                return List.of("enter", "leave").stream()
                        .filter(x -> x.startsWith(a[1].toLowerCase())).collect(Collectors.toList());
            // give/invite/kick 第三个参数: 自己的领地
            if (a.length == 3 && List.of("invite", "kick", "give").contains(a[0].toLowerCase()))
                return getPlayerClaims(p.getUniqueId()).stream().map(cl -> cl.name)
                        .filter(x -> x.toLowerCase().startsWith(a[2].toLowerCase())).collect(Collectors.toList());
            if (a.length == 3 && a[0].equalsIgnoreCase("flag")) {
                Claim c = findClaim(p.getUniqueId(), a[1]);
                if (c != null) return c.memberNames.values().stream()
                        .filter(x -> x.toLowerCase().startsWith(a[2].toLowerCase())).collect(Collectors.toList());
            }
            if (a.length == 4 && a[0].equalsIgnoreCase("flag"))
                return Arrays.stream(Flag.values()).map(Enum::name)
                        .filter(x -> x.toLowerCase().startsWith(a[3].toLowerCase())).collect(Collectors.toList());
            if (a.length == 2 && a[0].equalsIgnoreCase("buy"))
                return claims.values().stream().filter(cl -> cl.price > 0).map(cl -> cl.name)
                        .filter(x -> x.toLowerCase().startsWith(a[1].toLowerCase())).collect(Collectors.toList());
            return Collections.emptyList();
        }
    }

    // ════════════════════════════════════════
    //  GUI
    // ════════════════════════════════════════
    public void openMainGui(Player p) {
        Inventory inv = Bukkit.createInventory(null, 54, GUI_MAIN);
        List<Claim> my = getPlayerClaims(p.getUniqueId());
        int slot = 0;
        for (Claim c : my) {
            if (slot >= 45) break;
            String subTag = !c.subClaims.isEmpty() ? " §7[子:" + c.subClaims.size() + "]" : "";
            String status = c.price > 0 ? " §a[售 §e" + c.price + "§a]" : "";
            inv.setItem(slot++, createItem(Material.GRASS_BLOCK,
                    "§a§l" + c.name + subTag + status,
                    "§7坐标: §f" + c.minX + "," + c.minZ + " §7→ §f" + c.maxX + "," + c.maxZ,
                    "§7大小: §e" + (c.maxX - c.minX + 1) + "x" + (c.maxZ - c.minZ + 1),
                    "§7成员: §e" + c.memberFlags.size() + "  §7子领地: §e" + c.subClaims.size(),
                    "", "§c左键 §7管理成员", "§e右键 §7领地设置"));
        }
        if (my.isEmpty())
            inv.setItem(13, createItem(Material.BARRIER, "§c暂无领地", "§7使用 §e/claim create <名字> §7创建领地"));
        inv.setItem(47, createItem(Material.GOLDEN_AXE, "§e§l创建领地", "§7手持木斧选区后 /claim create"));
        inv.setItem(48, createItem(Material.COMPASS, "§d§l领地地图"));
        inv.setItem(49, createItem(Material.OAK_SIGN, "§b§l全部领地", "§7查看服务器所有领地", "§7/claim all"));
        inv.setItem(50, createItem(Material.SPYGLASS, "§5§l搜索领地", "§7在聊天栏输入关键词搜索", "§7点击开始搜索"));
        inv.setItem(51, createItem(Material.EMERALD, "§2§l领地商店"));
        inv.setItem(53, createItem(Material.BARRIER, "§c§l关闭"));
        fillGlass(inv, 45, 54);
        p.openInventory(inv);
    }

    private void openSettingsGui(Player p, Claim c) {
        Inventory inv = Bukkit.createInventory(null, 54, GUI_SETTINGS);
        // 标题行
        String subInfo = c.isSubClaim() ? " §7[子领地]" : (!c.subClaims.isEmpty() ? " §7[含" + c.subClaims.size() + "子领地]" : "");
        inv.setItem(4, createItem(Material.GRASS_BLOCK, "§a§l" + c.name + subInfo,
                "§7大小: §e" + (c.maxX - c.minX + 1) + "x" + (c.maxZ - c.minZ + 1),
                "§7坐标: §f" + c.minX + "," + c.minZ + " ~ " + c.maxX + "," + c.maxZ));

        // ── 环境设置 (OtherFlag) ── 第一行 7个
        int[] envSlots = {10, 11, 12, 13, 14, 15, 16};
        OtherFlag[] ofs = OtherFlag.values();
        for (int i = 0; i < ofs.length && i < envSlots.length; i++) {
            OtherFlag of = ofs[i];
            boolean on = c.otherFlags.getOrDefault(of, true);
            inv.setItem(envSlots[i], createItem(of.icon,
                    (on ? "§a" : "§c") + "§l" + of.name + (on ? " ✔" : " ✘"),
                    "§7点击切换"));
        }

        // ── 第二行：访客权限 + 公告 + 出售 ──
        inv.setItem(20, createItem(Material.PLAYER_HEAD, "§b§l访客默认权限",
                "§7点击设置访客默认Flag"));
        inv.setItem(22, createItem(Material.OAK_SIGN, "§b§l公告消息",
                "§7/claim setmsg enter|leave <消息>"));
        inv.setItem(24, createItem(Material.GOLD_INGOT, "§2§l出售领地",
                "§7价格: " + (c.price > 0 ? "§e" + c.price : "§7不出售"),
                "§7/claim sell <价格>"));

        // ── 第三行：操作区 ──
        inv.setItem(28, createItem(Material.ENDER_PEARL, "§d§l设置传送点",
                "§7点击设置当前位置", "§7传送: 5秒倒计时+30秒冷却"));
        inv.setItem(30, createItem(Material.SHEARS, "§e§l扩展领地",
                "§7向朝向方向扩展领地", "§7点击选择扩展格数"));
        inv.setItem(32, createItem(Material.HOPPER, "§c§l收缩领地",
                "§7向朝向方向收缩领地", "§7点击选择收缩格数", "§7最小3x3格"));
        inv.setItem(34, createItem(Material.NAME_TAG, "§e§l重命名领地",
                "§7/claim rename <新名字>"));

        // ── 第四行：子领地 + 租赁 + 转让 + 删除 ──
        if (!c.isSubClaim()) {
            inv.setItem(37, createItem(Material.OAK_DOOR, "§6§l子领地管理",
                    "§7已有: §e" + c.subClaims.size() + " §7个(最多3)",
                    "§7点击管理子领地"));
        }
        // 出租/租赁按钮
        if (c.isRented()) {
            inv.setItem(39, createItem(Material.CLOCK, "§3§l租赁信息",
                    "§7租客: §e" + c.rentedToName,
                    "§7到期: §e" + formatTimeLong(c.rentEndTime),
                    "§c点击驱逐租客"));
        } else if (c.rentPrice > 0) {
            inv.setItem(39, createItem(Material.EMERALD, "§2§l出租中",
                    "§7租金: §e" + c.rentPrice + " §7/ §e" + c.rentDays + "§7天",
                    "§7点击取消出租"));
        } else if (c.price > 0) {
            inv.setItem(39, createItem(Material.GOLD_INGOT, "§2§l出售中",
                    "§7价格: §e" + c.price,
                    "§7点击取消出售或设置出租"));
        } else {
            inv.setItem(39, createItem(Material.GOLD_NUGGET, "§2§l出租/出售",
                    "§7/claim rent <价格> [天数]",
                    "§7/claim sell <价格>"));
        }
        inv.setItem(41, createItem(Material.TOTEM_OF_UNDYING, "§5§l转让领地",
                "§7点击输入目标玩家名",
                "§c⚠ 转让后主人才会变更"));
        inv.setItem(43, createItem(Material.LAVA_BUCKET, "§c§l删除领地",
                "§7点击确认删除领地",
                "§c⚠ 不可恢复！"));

        inv.setItem(49, createItem(Material.ARROW, "§c§l返回"));
        inv.setItem(53, createItem(Material.BARRIER, "§c§l关闭"));
        fillGlass(inv, 0, 54);
        p.openInventory(inv);
    }

    /** 访客默认权限 GUI */
    private void openDefaultFlagsGui(Player p, Claim c) {
        Inventory inv = Bukkit.createInventory(null, 54, "§8§l[ §b§l访客默认权限 §8§l]");
        Flag[] flags = Flag.values();
        for (int i = 0; i < flags.length && i < 36; i++) {
            Flag f = flags[i];
            boolean on = c.defaultFlags.contains(f);
            inv.setItem(10 + i, createItem(f.icon,
                    (on ? "§a§l" : "§c§l") + f.name + (on ? " ✔" : " ✘"),
                    "§7当前: " + (on ? "§a允许" : "§c禁止"),
                    "§7点击切换访客默认权限"));
        }
        inv.setItem(45, createItem(Material.ARROW, "§c§l返回设置"));
        inv.setItem(49, createItem(Material.PAPER, "§e§l说明",
                "§7访客(非成员)默认权限",
                "§7当前允许: " + c.defaultFlags.stream().map(f -> f.name).collect(Collectors.joining(", "))));
        inv.setItem(53, createItem(Material.BARRIER, "§c§l关闭"));
        fillGlass(inv, 0, 54);
        p.openInventory(inv);
    }

    private void openMemberGui(Player p, Claim c) {
        Inventory inv = Bukkit.createInventory(null, 54, GUI_MEMBERS);
        inv.setItem(0, createItem(Material.GRASS_BLOCK, "§a§l" + c.name, "§e主人: " + c.ownerName));
        inv.setItem(45, createItem(Material.ARROW, "§c§l返回"));
        inv.setItem(53, createItem(Material.BARRIER, "§c§l关闭"));
        inv.setItem(47, createItem(Material.EMERALD, "§a§l添加成员",
                "§7/claim invite <玩家>", "§7玩家需60秒内接受"));
        inv.setItem(48, createItem(Material.BOOK, "§b§l待处理邀请",
                "§7查看你收到的邀请",
                "§7/claim invites"));

        int slot = 2;

        // ── 主人权限：第一条 (仅主人本人可见) ──
        if (c.owner.equals(p.getUniqueId())) {
            int enabledCount = c.ownerFlags.size();
            List<String> ownerLore = new ArrayList<>();
            ownerLore.add("§7已启用Flag: §e" + enabledCount + "/" + Flag.values().length);
            List<String> flagLines = new ArrayList<>();
            for (Flag f : Flag.values()) {
                flagLines.add((c.ownerFlags.contains(f) ? "§a" : "§c") + f.name);
            }
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < flagLines.size(); i++) {
                if (i > 0 && i % 5 == 0) { ownerLore.add("§7" + line); line = new StringBuilder(); }
                if (line.length() > 0) line.append(" ");
                line.append(flagLines.get(i));
            }
            if (line.length() > 0) ownerLore.add("§7" + line);
            ownerLore.add("");
            ownerLore.add("§a左键 §7编辑自己的权限");
            ownerLore.add("§c警告: 关闭后你将无法进行对应操作！");

            inv.setItem(slot++, createItemHead(c.ownerName, "§a§l" + c.ownerName + " §7[主人]", ownerLore));
        }

        int count = 0;
        for (var e : c.memberFlags.entrySet()) {
            if (count >= 42) break;
            UUID uid = e.getKey();
            String name = c.memberNames.getOrDefault(uid, uid.toString().substring(0, 8));
            Set<Flag> flags = e.getValue();
            int enabledCount = flags.size();

            List<String> lore = new ArrayList<>();
            lore.add("§7已启用Flag: §e" + enabledCount + "/" + Flag.values().length);
            List<String> flagLines = new ArrayList<>();
            for (Flag f : Flag.values()) {
                flagLines.add((flags.contains(f) ? "§a" : "§c") + f.name);
            }
            // 每行显示多个Flag
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < flagLines.size(); i++) {
                if (i > 0 && i % 5 == 0) { lore.add("§7" + line); line = new StringBuilder(); }
                if (line.length() > 0) line.append(" ");
                line.append(flagLines.get(i));
            }
            if (line.length() > 0) lore.add("§7" + line);
            lore.add("");
            lore.add("§e左键 §7查看/编辑权限");
            lore.add("§cShift+左键 §7移除成员");

            inv.setItem(slot++, createItemHead(name, "§e§l" + name, lore));
            count++;
        }

        if (c.memberFlags.isEmpty() && c.owner.equals(p.getUniqueId()) && slot <= 3) {
            inv.setItem(22, createItem(Material.BARRIER, "§7暂无成员", "§7使用 §e/claim invite <玩家>"));
        } else if (c.memberFlags.isEmpty() && !c.owner.equals(p.getUniqueId())) {
            inv.setItem(22, createItem(Material.BARRIER, "§7暂无成员"));
        }

        fillGlass(inv, 0, 54);
        p.openInventory(inv);
    }

    /** 主人的权限编辑 GUI */
    private void openOwnerFlagsGui(Player p, Claim c) {
        editingMember.put(p.getUniqueId(), c.owner);  // 追踪正在编辑主人自己
        Inventory inv = Bukkit.createInventory(null, 54, GUI_FLAGS);
        inv.setItem(4, createItem(Material.PLAYER_HEAD, "§a§l" + c.ownerName + " §7[主人权限]"));

        Flag[] allFlags = Flag.values();
        for (int i = 0; i < allFlags.length && i < 36; i++) {
            Flag f = allFlags[i];
            boolean on = c.ownerFlags.contains(f);
            inv.setItem(9 + i, createItem(f.icon,
                    (on ? "§a§l" : "§c§l") + f.name + (on ? " ✔" : " ✘"),
                    "§7当前: " + (on ? "§a允许" : "§c禁止"),
                    "§7点击切换主人权限",
                    "§c⚠ 关闭后将限制你自己的操作！"));
        }

        inv.setItem(45, createItem(Material.ARROW, "§c§l返回成员列表"));
        inv.setItem(49, createItem(Material.PAPER, "§e§l警告",
                "§c关闭 Flag 后你将无法",
                "§c在自己的领地内进行对应操作！",
                "§7随时可通过此界面重新开启"));
        inv.setItem(53, createItem(Material.BARRIER, "§c§l关闭"));
        fillGlass(inv, 0, 54);
        p.openInventory(inv);
    }

    /** 成员权限详情 GUI — 显示所有 Flag 开关 */
    private void openMemberFlagsGui(Player p, Claim c, UUID memberUid) {
        editingMember.put(p.getUniqueId(), memberUid);  // 追踪当前编辑的成员
        String memberName = c.memberNames.getOrDefault(memberUid, memberUid.toString().substring(0, 8));
        Inventory inv = Bukkit.createInventory(null, 54, GUI_FLAGS);
        inv.setItem(4, createItem(Material.PLAYER_HEAD, "§e§l" + memberName + " §7的权限"));

        Set<Flag> flags = c.memberFlags.get(memberUid);
        if (flags == null) { p.closeInventory(); return; }

        Flag[] allFlags = Flag.values();
        for (int i = 0; i < allFlags.length && i < 36; i++) {
            Flag f = allFlags[i];
            boolean on = flags.contains(f);
            inv.setItem(9 + i, createItem(f.icon,
                    (on ? "§a§l" : "§c§l") + f.name + (on ? " ✔" : " ✘"),
                    "§7当前: " + (on ? "§a允许" : "§c禁止"),
                    "§7点击切换"));
        }

        inv.setItem(45, createItem(Material.ARROW, "§c§l返回成员列表"));
        inv.setItem(53, createItem(Material.BARRIER, "§c§l关闭"));
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

    // ════════════════════════════════════════
    //  GUI 点击处理
    // ════════════════════════════════════════
    @EventHandler
    public void onGuiClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        String t = e.getView().getTitle();
        ItemStack item = e.getCurrentItem();
        if (item == null || item.getType() == Material.AIR) return;
        ItemMeta m = item.getItemMeta();
        if (m == null || !m.hasDisplayName()) return;
        String name = m.getDisplayName();

        if (t.equals(GUI_MAIN)) { e.setCancelled(true); handleMainGui(p, name, e); }
        else if (t.equals(GUI_SETTINGS)) { e.setCancelled(true); handleSettingsGui(p, name); }
        else if (t.equals(GUI_MEMBERS)) { e.setCancelled(true); handleMemberGui(p, name, e); }
        else if (t.equals(GUI_MAP)) { e.setCancelled(true); handleMapGui(p, name); }
        else if (t.equals(GUI_BUY)) { e.setCancelled(true); handleBuyGui(p, name); }
        else if (t.equals(GUI_FLAGS)) { e.setCancelled(true); handleFlagsGui(p, name); }
        else if (t.equals("§8§l[ §b§l访客默认权限 §8§l]")) { e.setCancelled(true); handleDefaultFlagsGui(p, name); }
        else if (t.equals(GUI_EXPAND)) { e.setCancelled(true); handleExpandGui(p, name); }
        else if (t.equals(GUI_SHRINK)) { e.setCancelled(true); handleShrinkGui(p, name); }
        else if (t.equals(GUI_SUB)) { e.setCancelled(true); handleSubClaimsGui(p, name, e); }
        else if (t.equals(GUI_CONFIRM)) { e.setCancelled(true); handleConfirmGui(p, name); }
    }

    private void handleMainGui(Player p, String name, InventoryClickEvent e) {
        if ("§e§l创建领地".equals(name)) {
            p.closeInventory(); p.sendMessage(msg("prefix") + " §7手持 §e木斧 §7选区后 §e/claim create <名字>");
        } else if ("§d§l领地地图".equals(name)) { openMapGui(p); }
        else if ("§b§l全部领地".equals(name)) {
            p.closeInventory(); p.performCommand("claim all");
        } else if ("§5§l搜索领地".equals(name)) {
            p.closeInventory();
            chatSearch.put(p.getUniqueId(), "");
            p.sendMessage("§8§m                              ");
            p.sendMessage(msg("prefix") + " §5§l搜索领地");
            p.sendMessage("§7在聊天栏输入 §e关键词 §7(留空=全部) §7搜索领地");
            p.sendMessage("§7输入 §c取消 §7取消搜索");
            p.sendMessage("§8§m                              ");
        } else if ("§2§l领地商店".equals(name)) { openBuyGui(p); }
        else if ("§c§l关闭".equals(name) || "§c暂无领地".equals(name)) { p.closeInventory(); }
        else if (name.startsWith("§a§l")) {
            // 解析领地名称: 去除颜色码前缀和出售/子领地状态后缀
            String raw = name.substring(4); // 去除 "§a§l"
            String cn = raw.split(" §7\\[")[0].split(" §a\\[")[0]; // 去除 " §7[子:N]" 或 " §a[售 ...]"
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

        // ── 主人验证: 只有主人能修改领地设置 ──
        if (!c.owner.equals(p.getUniqueId())) {
            p.sendMessage(msg("prefix") + " §c只有领地主人才能修改设置！");
            return;
        }

        // 环境设置开关 (精确匹配)
        String cleanName = ChatColor.stripColor(name);
        if (cleanName.endsWith(" ✔") || cleanName.endsWith(" ✘")) {
            cleanName = cleanName.substring(0, cleanName.length() - 2);
        }
        for (OtherFlag of : OtherFlag.values()) {
            if (of.name.equals(cleanName)) {
                c.toggleOtherFlag(of);
                saveAll();
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
                openSettingsGui(p, c);
                return;
            }
        }

        if (name.equals("§b§l访客默认权限")) {
            openDefaultFlagsGui(p, c);
        } else if (name.equals("§b§l公告消息")) {
            p.closeInventory(); p.sendMessage(msg("prefix") + " §7使用 §e/claim setmsg <enter|leave> <消息>");
        } else if (name.equals("§2§l出售领地")) {
            p.closeInventory(); p.sendMessage(msg("prefix") + " §7使用 §e/claim sell <价格>");
        } else if (name.equals("§d§l设置传送点")) {
            c.spawn = p.getLocation().clone(); saveAll();
            p.sendMessage(msg("prefix") + " §a传送点已设置！"); p.closeInventory();
        } else if (name.equals("§e§l扩展领地")) {
            openExpandGui(p, c);
        } else if (name.equals("§c§l收缩领地")) {
            openShrinkGui(p, c);
        } else if (name.equals("§e§l重命名领地")) {
            p.closeInventory(); p.sendMessage(msg("prefix") + " §7使用 §e/claim rename <新名字>");
        } else if (name.equals("§6§l子领地管理")) {
            openSubClaimsGui(p, c);
        } else if (name.equals("§3§l租赁信息")) {
            // 点击驱逐租客
            if (c.isRented()) {
                Player renter = Bukkit.getPlayer(c.rentedTo);
                if (renter != null) renter.sendMessage(msg("prefix") + " §c你已被驱逐出领地 §e" + c.name);
                c.removeMember(c.rentedTo);
                c.rentedTo = null; c.rentedToName = null; c.rentEndTime = 0;
                saveAll();
                p.sendMessage(msg("prefix") + " §a已驱逐租客！");
                openSettingsGui(p, c);
            }
        } else if (name.equals("§2§l出租中")) {
            // 取消出租
            c.rentPrice = 0;
            if (c.isRented()) {
                Player renter = Bukkit.getPlayer(c.rentedTo);
                if (renter != null) renter.sendMessage(msg("prefix") + " §c领地 §e" + c.name + " §c已被主人收回！");
                c.removeMember(c.rentedTo);
                c.rentedTo = null; c.rentedToName = null; c.rentEndTime = 0;
            }
            saveAll();
            p.sendMessage(msg("prefix") + " §7已取消出租");
            openSettingsGui(p, c);
        } else if (name.equals("§2§l出售中")) {
            // 取消出售
            c.price = 0; saveAll();
            p.sendMessage(msg("prefix") + " §7已取消出售");
            openSettingsGui(p, c);
        } else if (name.equals("§2§l出租/出售")) {
            p.closeInventory();
            p.sendMessage(msg("prefix") + " §7出租: §e/claim rent <价格> [天数] §7出售: §e/claim sell <价格>");
        } else if (name.equals("§5§l转让领地")) {
            p.closeInventory();
            chatTransferClaim.put(p.getUniqueId(), c.id);
            p.sendMessage("§8§m                              ");
            p.sendMessage(msg("prefix") + " §5§l转让领地: §e" + c.name);
            p.sendMessage("§7请在 §f30秒内 §7在聊天栏输入要转让给的 §e玩家名");
            p.sendMessage("§7输入 §c取消 §7取消转让");
            p.sendMessage("§8§m                              ");
        } else if (name.equals("§c§l删除领地")) {
            openConfirmGui(p, c, "delete");
        }
    }

    /** 访客默认权限 GUI 处理 */
    private void handleDefaultFlagsGui(Player p, String name) {
        if ("§c§l关闭".equals(name)) { p.closeInventory(); return; }
        if ("§c§l返回设置".equals(name)) {
            String cid = lastClaimId.get(p.getUniqueId());
            if (cid != null) { Claim c = claims.get(cid); if (c != null) openSettingsGui(p, c); }
            return;
        }
        String cid = lastClaimId.get(p.getUniqueId());
        if (cid == null) return;
        Claim c = claims.get(cid);
        if (c == null) return;

        // ── 主人验证: 只有主人能修改访客默认权限 ──
        if (!c.owner.equals(p.getUniqueId())) {
            p.sendMessage(msg("prefix") + " §c只有领地主人才能修改访客默认权限！");
            return;
        }

        // ── 精确匹配 Flag: 用 Flag.name 精确匹配而非 startsWith, 避免前缀歧义 ──
        // 去除颜色码和 ✔/✘ 后缀, 保留纯 Flag 名称进行比较
        String cleanName = ChatColor.stripColor(name);
        if (cleanName.endsWith(" ✔") || cleanName.endsWith(" ✘")) {
            cleanName = cleanName.substring(0, cleanName.length() - 2);
        }
        for (Flag f : Flag.values()) {
            if (f.name.equals(cleanName)) {
                if (c.defaultFlags.contains(f)) {
                    c.defaultFlags.remove(f);
                    plugin.getLogger().info("[Claim] 主人 " + p.getName() + " 关闭了领地 " + c.name + " 的访客默认Flag: " + f.name);
                } else {
                    c.defaultFlags.add(f);
                    plugin.getLogger().info("[Claim] 主人 " + p.getName() + " 启用了领地 " + c.name + " 的访客默认Flag: " + f.name);
                }
                saveAll();
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
                openDefaultFlagsGui(p, c);
                return;
            }
        }
    }

    private void handleMemberGui(Player p, String name, InventoryClickEvent e) {
        if ("§c§l返回".equals(name)) { openMainGui(p); return; }
        if ("§c§l关闭".equals(name)) { p.closeInventory(); return; }
        if ("§a§l添加成员".equals(name)) {
            p.closeInventory(); p.sendMessage(msg("prefix") + " §7使用 §e/claim invite <玩家>"); return;
        }
        if ("§b§l待处理邀请".equals(name)) {
            p.closeInventory(); p.performCommand("claim invites"); return;
        }
        if ("§7暂无成员".equals(name)) return;
        String cid = lastClaimId.get(p.getUniqueId());
        if (cid == null) return;
        Claim c = claims.get(cid);
        if (c == null) return;

        // ── 主人条目点击 (包含 "[主人]" 标记) ──
        if (name.startsWith("§a§l") && name.contains("§7[主人]")) {
            if (e.isLeftClick()) {
                editingMember.put(p.getUniqueId(), c.owner); // 标记编辑主人自己
                openOwnerFlagsGui(p, c);
                return;
            }
        }

        // 成员列表点击 — 查找成员
        if (name.startsWith("§e§l")) {
            String memberName = name.substring(4);
            for (var entry : c.memberNames.entrySet()) {
                if (entry.getValue().equals(memberName)) {
                    if (e.isShiftClick()) {
                        // Shift+左键 = 移除
                        c.removeMember(entry.getKey());
                        saveAll();
                        openMemberGui(p, c);
                        return;
                    } else if (e.isLeftClick()) {
                        // 左键 = 编辑权限
                        openMemberFlagsGui(p, c, entry.getKey());
                        return;
                    }
                }
            }
        }
    }

    /** 成员 Flag 编辑 GUI (同时处理主人和成员) */
    private void handleFlagsGui(Player p, String name) {
        if ("§c§l关闭".equals(name)) { p.closeInventory(); return; }
        if ("§c§l返回成员列表".equals(name)) {
            String cid = lastClaimId.get(p.getUniqueId());
            if (cid != null) { Claim c = claims.get(cid); if (c != null) openMemberGui(p, c); }
            return;
        }
        String cid = lastClaimId.get(p.getUniqueId());
        if (cid == null) return;
        Claim c = claims.get(cid);
        if (c == null) return;

        // 使用 editingMember 精确识别正在编辑的人
        UUID targetUid = editingMember.get(p.getUniqueId());
        if (targetUid == null) return;

        // 判断是主人还是成员
        boolean isOwner = targetUid.equals(c.owner);
        Set<Flag> flags = isOwner ? c.ownerFlags : c.memberFlags.get(targetUid);
        if (flags == null) return;

        // ── 精确匹配 Flag (用 ChatColor.stripColor + Flag.name) ──
        String cleanName = ChatColor.stripColor(name);
        if (cleanName.endsWith(" ✔") || cleanName.endsWith(" ✘")) {
            cleanName = cleanName.substring(0, cleanName.length() - 2);
        }
        for (Flag f : Flag.values()) {
            if (f.name.equals(cleanName)) {
                if (isOwner) {
                    c.toggleOwnerFlag(f);
                    saveAll();
                    p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
                    openOwnerFlagsGui(p, c);
                } else {
                    c.toggleMemberFlag(targetUid, f);
                    saveAll();
                    p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
                    openMemberFlagsGui(p, c, targetUid);
                }
                return;
            }
        }
    }

    private void handleMapGui(Player p, String name) {
        if ("§d§l刷新".equals(name)) { openMapGui(p); }
        else if ("§c§l关闭".equals(name)) { p.closeInventory(); }
    }

    private void handleBuyGui(Player p, String name) {
        if ("§c§l关闭".equals(name)) { p.closeInventory(); return; }
        if ("§c暂无出售中的领地".equals(name)) return;
        // 点击出售中的领地 — 解析名称购买
        if (name.startsWith("§a§l")) {
            String raw = name.substring(4);
            String cn = raw.split(" §a\\[")[0]; // 去除附属标签
            for (Claim cl : claims.values()) {
                if (cl.name.equals(cn) && cl.price > 0) {
                    p.closeInventory();
                    // 委托给 doBuy 逻辑
                    EconomyModule eco = plugin.getEconomyModule();
                    if (eco == null) { p.sendMessage(msg("prefix") + " §c经济系统未启用！"); return; }
                    if (cl.owner.equals(p.getUniqueId())) {
                        p.sendMessage(msg("prefix") + " §c不能购买自己的领地！"); return;
                    }
                    if (!eco.hasEnough(p.getUniqueId(), cl.price)) {
                        p.sendMessage(msg("prefix") + " §c余额不足！需要 §e" + cl.price); return;
                    }
                    eco.withdraw(p, cl.price);
                    Player oldOwner = Bukkit.getPlayer(cl.owner);
                    if (oldOwner != null) eco.deposit(oldOwner, cl.price);
                    else eco.deposit(cl.owner, cl.price);
                    p.sendMessage(msg("prefix") + " §a成功购买领地 §e" + cl.name + "！§a价格: §e" + cl.price);
                    cl.owner = p.getUniqueId(); cl.ownerName = p.getName();
                    cl.memberFlags.clear(); cl.memberNames.clear(); cl.price = 0;
                    saveAll();
                    p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
                    return;
                }
            }
        }
    }

    // ════════════════════════════════════════
    //  扩展领地 GUI
    // ════════════════════════════════════════
    private void openExpandGui(Player p, Claim c) {
        if (c.isSubClaim() || !c.subClaims.isEmpty()) {
            p.sendMessage(msg("prefix") + " §c有子领地的领地不能扩展！");
            openSettingsGui(p, c); return;
        }
        if (c.price > 0) {
            p.sendMessage(msg("prefix") + " §c出售中的领地不能扩展！");
            openSettingsGui(p, c); return;
        }
        Inventory inv = Bukkit.createInventory(null, 27, GUI_EXPAND);

        String info = "§7大小: §e" + (c.maxX - c.minX + 1) + "x" + (c.maxZ - c.minZ + 1)
                + " §7| 方向: §e" + getFacingDir(p);
        inv.setItem(4, createItem(Material.GRASS_BLOCK, "§a§l" + c.name,
                info, "§7点击下方按钮选择扩展格数", "§7或在聊天栏输入数字"));

        int[] amounts = {1, 2, 3, 4, 5, 8, 10, 16};
        int[] slots = {10, 11, 12, 13, 14, 15, 16};
        for (int i = 0; i < amounts.length && i < slots.length; i++) {
            inv.setItem(slots[i], createItem(Material.LIME_STAINED_GLASS_PANE,
                    "§a§l+ " + amounts[i] + " §7格",
                    "§7向 §e" + getFacingDir(p) + " §7方向扩展"));
        }

        inv.setItem(22, createItem(Material.OAK_SIGN, "§e§l自定义格数",
                "§7点击后在聊天栏输入数字", "§7范围: 1~16"));
        inv.setItem(18, createItem(Material.ARROW, "§c§l返回设置"));
        inv.setItem(26, createItem(Material.BARRIER, "§c§l关闭"));
        fillGlass(inv, 0, 27);
        p.openInventory(inv);
    }

    private String getFacingDir(Player p) {
        float pitch = p.getLocation().getPitch();
        if (pitch >= 50) return "上";
        if (pitch <= -50) return "下";
        float yaw = (p.getLocation().getYaw() + 360) % 360;
        if (yaw >= 45 && yaw < 135) return "西";
        if (yaw >= 135 && yaw < 225) return "北";
        if (yaw >= 225 && yaw < 315) return "东";
        return "南";
    }

    private void handleExpandGui(Player p, String name) {
        if ("§c§l返回设置".equals(name)) {
            String cid = lastClaimId.get(p.getUniqueId());
            if (cid != null) { Claim c = claims.get(cid); if (c != null) openSettingsGui(p, c); }
            return;
        }
        if ("§c§l关闭".equals(name)) { p.closeInventory(); return; }
        String cid = lastClaimId.get(p.getUniqueId());
        if (cid == null) return;
        Claim c = claims.get(cid);
        if (c == null || !c.owner.equals(p.getUniqueId())) return;

        // 预设格数按钮
        if (name.startsWith("§a§l+ ")) {
            String num = ChatColor.stripColor(name).replace("+ ", "").replace(" 格", "").trim();
            try {
                int amount = Integer.parseInt(num);
                p.closeInventory();
                doExpandDirect(p, c, amount);
            } catch (Exception ignored) {}
            return;
        }

        // 自定义格数
        if ("§e§l自定义格数".equals(name)) {
            p.closeInventory();
            chatExpandClaim.put(p.getUniqueId(), c.id);
            p.sendMessage("§8§m                              ");
            p.sendMessage(msg("prefix") + " §e§l扩展领地: §a" + c.name
                    + " §7方向: §e" + getFacingDir(p));
            p.sendMessage("§7请在 §f30秒内 §7在聊天栏输入扩展格数 §7(1~16)");
            p.sendMessage("§7输入 §c取消 §7取消扩展");
            p.sendMessage("§8§m                              ");
        }
    }

    // ════════════════════════════════════════
    //  收缩领地 GUI
    // ════════════════════════════════════════
    private void openShrinkGui(Player p, Claim c) {
        if (c.isSubClaim() || !c.subClaims.isEmpty()) {
            p.sendMessage(msg("prefix") + " §c有子领地的领地不能收缩！");
            openSettingsGui(p, c); return;
        }
        if (c.price > 0) {
            p.sendMessage(msg("prefix") + " §c出售中的领地不能收缩！");
            openSettingsGui(p, c); return;
        }
        if (c.isRented()) {
            p.sendMessage(msg("prefix") + " §c出租中的领地不能收缩！");
            openSettingsGui(p, c); return;
        }
        Inventory inv = Bukkit.createInventory(null, 27, GUI_SHRINK);

        int w = c.maxX - c.minX + 1;
        int h = c.maxZ - c.minZ + 1;
        String info = "§7大小: §e" + w + "x" + h
                + " §7| 方向: §e" + getFacingDir(p)
                + " §7| 最小: §c3x3";
        inv.setItem(4, createItem(Material.GRASS_BLOCK, "§a§l" + c.name,
                info, "§7点击下方按钮选择收缩格数", "§7或在聊天栏输入数字"));

        int[] amounts = {1, 2, 3, 4, 5, 8, 10, 16};
        int[] slots = {10, 11, 12, 13, 14, 15, 16};
        for (int i = 0; i < amounts.length && i < slots.length; i++) {
            inv.setItem(slots[i], createItem(Material.RED_STAINED_GLASS_PANE,
                    "§c§l- " + amounts[i] + " §7格",
                    "§7向 §e" + getFacingDir(p) + " §7方向收缩"));
        }

        inv.setItem(22, createItem(Material.OAK_SIGN, "§e§l自定义格数",
                "§7点击后在聊天栏输入数字", "§7范围: 1~16"));
        inv.setItem(18, createItem(Material.ARROW, "§c§l返回设置"));
        inv.setItem(26, createItem(Material.BARRIER, "§c§l关闭"));
        fillGlass(inv, 0, 27);
        p.openInventory(inv);
    }

    private void handleShrinkGui(Player p, String name) {
        if ("§c§l返回设置".equals(name)) {
            String cid = lastClaimId.get(p.getUniqueId());
            if (cid != null) { Claim c = claims.get(cid); if (c != null) openSettingsGui(p, c); }
            return;
        }
        if ("§c§l关闭".equals(name)) { p.closeInventory(); return; }
        String cid = lastClaimId.get(p.getUniqueId());
        if (cid == null) return;
        Claim c = claims.get(cid);
        if (c == null || !c.owner.equals(p.getUniqueId())) return;

        // 预设格数按钮
        if (name.startsWith("§c§l- ")) {
            String num = ChatColor.stripColor(name).replace("- ", "").replace(" 格", "").trim();
            try {
                int amount = Integer.parseInt(num);
                p.closeInventory();
                doShrinkDirect(p, c, amount);
            } catch (Exception ignored) {}
            return;
        }

        // 自定义格数
        if ("§e§l自定义格数".equals(name)) {
            p.closeInventory();
            chatShrinkClaim.put(p.getUniqueId(), c.id);
            p.sendMessage("§8§m                              ");
            p.sendMessage(msg("prefix") + " §c§l收缩领地: §a" + c.name
                    + " §7方向: §e" + getFacingDir(p));
            p.sendMessage("§7请在 §f30秒内 §7在聊天栏输入收缩格数 §7(1~16)");
            p.sendMessage("§7输入 §c取消 §7取消收缩");
            p.sendMessage("§8§m                              ");
        }
    }

    // ════════════════════════════════════════
    //  子领地管理 GUI
    // ════════════════════════════════════════
    private void openSubClaimsGui(Player p, Claim c) {
        if (c.isSubClaim()) {
            p.sendMessage(msg("prefix") + " §c子领地不能创建子领地！");
            openSettingsGui(p, c); return;
        }
        Inventory inv = Bukkit.createInventory(null, 27, GUI_SUB);
        inv.setItem(4, createItem(Material.GRASS_BLOCK, "§a§l" + c.name,
                "§7子领地: §e" + c.subClaims.size() + "/3"));

        if (c.subClaims.isEmpty()) {
            inv.setItem(13, createItem(Material.PAPER, "§7暂无子领地",
                    "§7在主领地内用木斧选区后",
                    "§7使用 §e/claim subcreate <名字> §7创建"));
        } else {
            int slot = 10;
            for (int i = 0; i < c.subClaims.size() && slot < 17; i++) {
                Claim sub = c.subClaims.get(i);
                inv.setItem(slot++, createItem(Material.OAK_DOOR,
                        "§e§l" + sub.name,
                        "§7大小: §f" + (sub.maxX - sub.minX + 1) + "x" + (sub.maxZ - sub.minZ + 1),
                        "§7坐标: §f" + sub.minX + "," + sub.minZ + " ~ " + sub.maxX + "," + sub.maxZ,
                        "",
                        "§cShift+左键 §7删除此子领地"));
            }
        }

        inv.setItem(18, createItem(Material.ARROW, "§c§l返回设置"));
        inv.setItem(26, createItem(Material.BARRIER, "§c§l关闭"));
        fillGlass(inv, 0, 27);
        p.openInventory(inv);
    }

    private void handleSubClaimsGui(Player p, String name, InventoryClickEvent e) {
        if ("§c§l返回设置".equals(name)) {
            String cid = lastClaimId.get(p.getUniqueId());
            if (cid != null) { Claim c = claims.get(cid); if (c != null) openSettingsGui(p, c); }
            return;
        }
        if ("§c§l关闭".equals(name)) { p.closeInventory(); return; }
        if ("§7暂无子领地".equals(name)) return;

        String cid = lastClaimId.get(p.getUniqueId());
        if (cid == null) return;
        Claim c = claims.get(cid);
        if (c == null || !c.owner.equals(p.getUniqueId())) return;

        // 点击子领地项 → Shift+左键删除
        if (name.startsWith("§e§l") && e.isShiftClick()) {
            String subName = ChatColor.stripColor(name).substring(0); // name after §e§l
            subName = name.substring(4); // strip "§e§l"
            for (int i = 0; i < c.subClaims.size(); i++) {
                Claim sub = c.subClaims.get(i);
                if (sub.name.equals(subName)) {
                    c.subClaims.remove(i);
                    claims.remove(sub.id);
                    rebuildChunkIndex();
                    saveAll();
                    p.sendMessage(msg("prefix") + " §c子领地 §e" + subName + " §c已删除！");
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1f);
                    openSubClaimsGui(p, c);
                    return;
                }
            }
        }
    }

    // ════════════════════════════════════════
    //  确认删除 GUI
    // ════════════════════════════════════════
    private void openConfirmGui(Player p, Claim c, String action) {
        Inventory inv = Bukkit.createInventory(null, 27, GUI_CONFIRM);
        inv.setItem(4, createItem(Material.GRASS_BLOCK, "§a§l" + c.name,
                "§7大小: §e" + (c.maxX - c.minX + 1) + "x" + (c.maxZ - c.minZ + 1),
                "§7世界: §f" + c.world));

        if ("delete".equals(action)) {
            inv.setItem(12, createItem(Material.LIME_CONCRETE, "§a§l确认删除",
                    "§c⚠ 将领地 §e" + c.name + " §c彻底删除！",
                    "§c⚠ 此操作不可恢复！"));
            inv.setItem(14, createItem(Material.RED_CONCRETE, "§c§l取消", "§7返回设置菜单"));
        }

        inv.setItem(26, createItem(Material.BARRIER, "§c§l关闭"));
        fillGlass(inv, 0, 27);
        p.openInventory(inv);
    }

    private void handleConfirmGui(Player p, String name) {
        if ("§c§l关闭".equals(name) || "§c§l取消".equals(name)) {
            String cid = lastClaimId.get(p.getUniqueId());
            if (cid != null) { Claim c = claims.get(cid); if (c != null) openSettingsGui(p, c); }
            else p.closeInventory();
            return;
        }
        if ("§a§l确认删除".equals(name)) {
            String cid = lastClaimId.get(p.getUniqueId());
            if (cid == null) { p.closeInventory(); return; }
            Claim c = claims.get(cid);
            if (c == null || !c.owner.equals(p.getUniqueId())) { p.closeInventory(); return; }

            p.closeInventory();
            // 递归删除子领地
            List<Claim> toRemove = new ArrayList<>();
            collectAllSubClaims(c, toRemove);
            if (c.isSubClaim()) {
                Claim parent = claims.get(c.parentId);
                if (parent != null) parent.subClaims.remove(c);
            }
            toRemove.add(c);
            for (Claim rc : toRemove) claims.remove(rc.id);
            rebuildChunkIndex();
            saveAll();
            p.sendMessage(msg("prefix") + " §c领地 §e" + c.name + " §c已删除"
                    + (toRemove.size() > 1 ? " §7(含 " + (toRemove.size() - 1) + " 个子领地)" : ""));
            p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_BREAK, 1f, 1f);
        }
    }

    @EventHandler
    public void onGuiDrag(InventoryDragEvent e) {
        String t = e.getView().getTitle();
        if (t.startsWith("§8§l[ ")) e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        selections.remove(id); lastClaimId.remove(id); editingMember.remove(id);
        currentClaim.remove(id); lastParticle.remove(id); playerDataMap.remove(id);
        chatExpandClaim.remove(id); chatShrinkClaim.remove(id); chatTransferClaim.remove(id); chatSearch.remove(id);

        // 清理传送任务
        TpTask tpTask = tpTasks.remove(id);
        if (tpTask != null) Bukkit.getScheduler().cancelTask(tpTask.taskId);
        tpCooldowns.remove(id);

        // 清理过期邀请 (发给此玩家的 和 此玩家发出的)
        invites.remove(id);
        invites.values().removeIf(inv -> inv.inviter.equals(id));
    }

    // ── 聊天输入处理（扩展/转让/搜索）──
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChatInput(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();

        // 转让领地 → 输入目标玩家名
        String transferCid = chatTransferClaim.remove(id);
        if (transferCid != null) {
            e.setCancelled(true);
            if (e.getMessage().trim().equals("取消")) {
                p.sendMessage(msg("prefix") + " §7转让已取消。"); return;
            }
            Claim c = claims.get(transferCid);
            if (c == null || !c.owner.equals(id)) {
                p.sendMessage(msg("prefix") + " §c领地不存在或你已不是主人！"); return;
            }
            String targetName = e.getMessage().trim();
            Player target = Bukkit.getPlayer(targetName);
            if (target == null || !target.isOnline()) {
                p.sendMessage(msg("prefix") + " §c玩家 §e" + targetName + " §c不在线！"); return;
            }
            if (target.getUniqueId().equals(id)) {
                p.sendMessage(msg("prefix") + " §c不能转让给自己！"); return;
            }
            c.owner = target.getUniqueId();
            c.ownerName = target.getName();
            for (Claim sub : c.subClaims) { sub.owner = target.getUniqueId(); sub.ownerName = target.getName(); }
            saveAll();
            p.sendMessage(msg("prefix") + " §a已将领地 §e" + c.name + " §a转让给 §e" + target.getName());
            target.sendMessage(msg("prefix") + " §a" + p.getName() + " 将领地 §e" + c.name + " §a转让给了你！");
            return;
        }

        // 扩展领地 → 输入格数
        String expandCid = chatExpandClaim.remove(id);
        if (expandCid != null) {
            e.setCancelled(true);
            if (e.getMessage().trim().equals("取消")) {
                p.sendMessage(msg("prefix") + " §7扩展已取消。"); return;
            }
            Claim c = claims.get(expandCid);
            if (c == null || !c.owner.equals(id)) {
                p.sendMessage(msg("prefix") + " §c领地不存在或你已不是主人！"); return;
            }
            int amount;
            try { amount = Integer.parseInt(e.getMessage().trim()); } catch (Exception ex) {
                p.sendMessage(msg("prefix") + " §c请输入有效数字！扩展已取消。"); return;
            }
            if (amount <= 0 || amount > 16) {
                p.sendMessage(msg("prefix") + " §c扩展格数必须在 1~16 之间！扩展已取消。"); return;
            }
            doExpandDirect(p, c, amount);
            return;
        }

        // 收缩领地 → 输入格数
        String shrinkCid = chatShrinkClaim.remove(id);
        if (shrinkCid != null) {
            e.setCancelled(true);
            if (e.getMessage().trim().equals("取消")) {
                p.sendMessage(msg("prefix") + " §7收缩已取消。"); return;
            }
            Claim c = claims.get(shrinkCid);
            if (c == null || !c.owner.equals(id)) {
                p.sendMessage(msg("prefix") + " §c领地不存在或你已不是主人！"); return;
            }
            int amount;
            try { amount = Integer.parseInt(e.getMessage().trim()); } catch (Exception ex) {
                p.sendMessage(msg("prefix") + " §c请输入有效数字！收缩已取消。"); return;
            }
            if (amount <= 0 || amount > 16) {
                p.sendMessage(msg("prefix") + " §c收缩格数必须在 1~16 之间！收缩已取消。"); return;
            }
            doShrinkDirect(p, c, amount);
            return;
        }

        // 搜索领地 → 输入关键词
        String searchPlaceholder = chatSearch.remove(id);
        if (searchPlaceholder != null) {
            e.setCancelled(true);
            if (e.getMessage().trim().equals("取消")) {
                p.sendMessage(msg("prefix") + " §7搜索已取消。"); return;
            }
            String keyword = e.getMessage().trim();
            doScreenDirect(p, keyword);
        }
    }

    // ── 直接扩展（从GUI/聊天调用，复用方向判断逻辑）──
    private void doExpandDirect(Player p, Claim c, int amount) {
        if (c == null || !c.owner.equals(p.getUniqueId())) {
            p.sendMessage(msg("prefix") + " §c你必须站在自己的领地内！"); return;
        }
        if (c.isSubClaim() || !c.subClaims.isEmpty()) {
            p.sendMessage(msg("prefix") + " §c有子领地的领地不能扩展！"); return;
        }
        if (c.price > 0) { p.sendMessage(msg("prefix") + " §c出售中的领地不能扩展！"); return; }

        float pitch = p.getLocation().getPitch();
        float yaw = p.getLocation().getYaw();
        int oldMinX = c.minX, oldMinZ = c.minZ, oldMaxX = c.maxX, oldMaxZ = c.maxZ;
        String dir;

        if (pitch >= 50) {
            if (c.maxY + amount > 319) { p.sendMessage(msg("prefix") + " §c已达到世界高度上限！"); return; }
            c.maxY += amount; dir = "上";
        } else if (pitch <= -50) {
            if (c.minY - amount < -64) { p.sendMessage(msg("prefix") + " §c已达到世界深度下限！"); return; }
            c.minY -= amount; dir = "下";
        } else {
            yaw = (yaw + 360) % 360;
            if (yaw >= 45 && yaw < 135) { c.minX -= amount; dir = "西"; }
            else if (yaw >= 135 && yaw < 225) { c.minZ -= amount; dir = "北"; }
            else if (yaw >= 225 && yaw < 315) { c.maxX += amount; dir = "东"; }
            else { c.maxZ += amount; dir = "南"; }
        }

        // 重叠检查
        for (Claim other : claims.values()) {
            if (other == c || other.world == null || !other.world.equals(c.world)) continue;
            if (other.maxX < c.minX || other.minX > c.maxX
                    || other.maxZ < c.minZ || other.minZ > c.maxZ) continue;
            c.minX = oldMinX; c.minZ = oldMinZ; c.maxX = oldMaxX; c.maxZ = oldMaxZ;
            p.sendMessage(msg("prefix") + " §c扩展后与领地 §e" + other.name + " §c重叠！"); return;
        }

        rebuildChunkIndex(); saveAll();
        int newSize = (c.maxX - c.minX + 1) * (c.maxZ - c.minZ + 1);
        p.sendMessage(msg("prefix") + " §a领地已向§e" + dir + "§a扩展 " + amount + " 格！当前大小: §e" + newSize + " §a格");
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.5f);
    }

    // ── 直接收缩（从GUI/聊天调用，复用方向判断逻辑）──
    private void doShrinkDirect(Player p, Claim c, int amount) {
        if (c == null || !c.owner.equals(p.getUniqueId())) {
            p.sendMessage(msg("prefix") + " §c你必须站在自己的领地内！"); return;
        }
        if (c.isSubClaim() || !c.subClaims.isEmpty()) {
            p.sendMessage(msg("prefix") + " §c有子领地的领地不能收缩！"); return;
        }
        if (c.price > 0) { p.sendMessage(msg("prefix") + " §c出售中的领地不能收缩！"); return; }
        if (c.isRented()) { p.sendMessage(msg("prefix") + " §c出租中的领地不能收缩！"); return; }

        float pitch = p.getLocation().getPitch();
        float yaw = p.getLocation().getYaw();
        int oldMinX = c.minX, oldMinZ = c.minZ, oldMaxX = c.maxX, oldMaxZ = c.maxZ;
        String dir;

        if (pitch >= 50) {
            if (c.maxY - amount < c.minY + 2) { p.sendMessage(msg("prefix") + " §c收缩后高度不足(最小3格)！"); return; }
            c.maxY -= amount; dir = "上";
        } else if (pitch <= -50) {
            if (c.minY + amount > c.maxY - 2) { p.sendMessage(msg("prefix") + " §c收缩后高度不足(最小3格)！"); return; }
            c.minY += amount; dir = "下";
        } else {
            yaw = (yaw + 360) % 360;
            if (yaw >= 45 && yaw < 135) {
                if (c.maxX - c.minX - amount < 2) { p.sendMessage(msg("prefix") + " §c收缩后宽度不足(最小3格)！"); return; }
                c.minX += amount; dir = "西";
            } else if (yaw >= 135 && yaw < 225) {
                if (c.maxZ - c.minZ - amount < 2) { p.sendMessage(msg("prefix") + " §c收缩后宽度不足(最小3格)！"); return; }
                c.minZ += amount; dir = "北";
            } else if (yaw >= 225 && yaw < 315) {
                if (c.maxX - c.minX - amount < 2) { p.sendMessage(msg("prefix") + " §c收缩后宽度不足(最小3格)！"); return; }
                c.maxX -= amount; dir = "东";
            } else {
                if (c.maxZ - c.minZ - amount < 2) { p.sendMessage(msg("prefix") + " §c收缩后宽度不足(最小3格)！"); return; }
                c.maxZ -= amount; dir = "南";
            }
        }

        // 检查玩家是否还在收缩后的领地内
        Location pl = p.getLocation();
        if (pl.getBlockX() < c.minX || pl.getBlockX() > c.maxX
                || pl.getBlockZ() < c.minZ || pl.getBlockZ() > c.maxZ) {
            c.minX = oldMinX; c.minZ = oldMinZ; c.maxX = oldMaxX; c.maxZ = oldMaxZ;
            p.sendMessage(msg("prefix") + " §c收缩后你不在领地内！请站在领地内部再试。"); return;
        }

        rebuildChunkIndex(); saveAll();
        int newSize = (c.maxX - c.minX + 1) * (c.maxZ - c.minZ + 1);
        p.sendMessage(msg("prefix") + " §a领地已向§e" + dir + "§a收缩 " + amount + " 格！当前大小: §e" + newSize + " §a格");
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1.2f);
    }

    // ── 直接搜索（从GUI/聊天调用）──
    private void doScreenDirect(Player p, String keyword) {
        List<Claim> results = claims.values().stream()
                .filter(c -> keyword.isEmpty()
                        || c.name.toLowerCase().contains(keyword)
                        || c.ownerName.toLowerCase().contains(keyword)
                        || c.id.toLowerCase().contains(keyword))
                .sorted((c1, c2) -> {
                    boolean m1 = c1.owner.equals(p.getUniqueId());
                    boolean m2 = c2.owner.equals(p.getUniqueId());
                    if (m1 != m2) return m1 ? -1 : 1;
                    return c1.name.compareToIgnoreCase(c2.name);
                })
                .limit(20).toList();

        p.sendMessage("§8§m          §r §d§l领地搜索 §8§m          ");
        if (keyword.isEmpty()) {
            p.sendMessage("§7显示全部领地 (最多20个)");
        } else {
            p.sendMessage("§7搜索关键词: §f" + keyword + " §7找到 §e" + results.size() + " §7个");
        }
        if (results.isEmpty()) {
            p.sendMessage("§c没有找到匹配的领地");
        } else {
            for (Claim c : results) {
                String ownerTag = c.owner.equals(p.getUniqueId()) ? " §a[我的]" : "";
                String subTag = c.isSubClaim() ? " §7[子领地]" : "";
                String sellTag = c.price > 0 ? " §6[出售中]" : "";
                String size = (c.maxX - c.minX + 1) + "x" + (c.maxZ - c.minZ + 1);
                p.sendMessage(" §a" + c.name + subTag + sellTag + ownerTag +
                        " §7- §e" + c.ownerName + " §7| §f" + size +
                        " §7| §f" + c.memberFlags.size() + "§7成员");
            }
        }
    }

    // ── 工具方法 ──
    private String formatTimeLong(long epochMs) {
        if (epochMs <= 0) return "未设置";
        long diff = epochMs - System.currentTimeMillis();
        if (diff <= 0) return "已过期";
        long days = diff / 86400000L;
        long hours = (diff % 86400000L) / 3600000L;
        if (days > 0) return days + "天" + hours + "小时";
        return hours + "小时" + (diff % 3600000L) / 60000L + "分钟";
    }

    private ItemStack createItem(Material mat, String name, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta m = item.getItemMeta();
        if (m != null) { m.setDisplayName(name); if (lore.length > 0) m.setLore(Arrays.asList(lore)); item.setItemMeta(m); }
        return item;
    }

    private ItemStack createItemHead(String playerName, String name, List<String> lore) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta m = item.getItemMeta();
        if (m != null) { m.setDisplayName(name); m.setLore(lore); item.setItemMeta(m); }
        return item;
    }

    private void fillGlass(Inventory inv, int from, int to) {
        ItemStack g = createItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = from; i < to && i < inv.getSize(); i++) if (inv.getItem(i) == null) inv.setItem(i, g);
    }

    /** 递归收集所有子领地 (用于删除) */
    private void collectAllSubClaims(Claim claim, List<Claim> out) {
        for (Claim sub : claim.subClaims) {
            out.add(sub);
            collectAllSubClaims(sub, out);
        }
    }
}
