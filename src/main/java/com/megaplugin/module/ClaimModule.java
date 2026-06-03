package com.megaplugin.module;

import com.megaplugin.MegaPlugin;
import com.megaplugin.util.DataFile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
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
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.BoundingBox;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 领地系统 - 参照 Lands/Residence 设计
 * 支持: 区块领地、精细权限、子领地、领地地图、传送点、出售
 */
public class ClaimModule extends MegaModule {

    private static final String GUI_MAIN      = "§8§l[ §a§l我的领地 §8§l]";
    private static final String GUI_SETTINGS  = "§8§l[ §e§l领地设置 §8§l]";
    private static final String GUI_MEMBERS   = "§8§l[ §b§l成员管理 §8§l]";
    private static final String GUI_MAP       = "§8§l[ §d§l领地地图 §8§l]";
    private static final String GUI_BUY       = "§8§l[ §2§l领地商店 §8§l]";

    private static final int MAX_CLAIMS = 5;
    private static final int MAX_CLAIM_SIZE = 64; // 最大边长
    private static final int MAX_SUB_CLAIMS = 3;  // 每个领地子领地数

    private final DataFile data;
    private final Map<String, Claim> claims = new HashMap<>(); // claimId -> Claim
    private final Map<UUID, Location[]> selections = new HashMap<>();
    private final Map<UUID, String> lastClaim = new HashMap<>();
    private final Map<UUID, String> currentClaim = new HashMap<>();
    private final Map<UUID, Long> lastParticle = new HashMap<>();
    private final Map<UUID, Boolean> mapMode = new HashMap<>(); // 地图模式开关

    // ── 权限标志 ──
    public enum Flag {
        BUILD("建造", Material.GRASS_BLOCK, true),
        BREAK("破坏", Material.IRON_PICKAXE, true),
        INTERACT("交互", Material.OAK_DOOR, true),
        CONTAINER("容器", Material.CHEST, true),
        PVP("PVP", Material.DIAMOND_SWORD, false),
        PVE("怪物伤害", Material.ZOMBIE_HEAD, true),
        MOB_SPAWN("怪物生成", Material.SPAWNER, false),
        ANIMAL_SPAWN("动物生成", Material.WHEAT, true),
        FIRE("火焰", Material.FLINT_AND_STEEL, false),
        EXPLOSION("爆炸", Material.TNT, false),
        PISTON("活塞", Material.PISTON, true),
        WATER_FLOW("水流", Material.WATER_BUCKET, true),
        LAVA_FLOW("岩浆流", Material.LAVA_BUCKET, false),
        GROWTH("植物生长", Material.WHEAT_SEEDS, true),
        TRAMPLE("踩踏农田", Material.FARMLAND, false),
        DROP_ITEM("丢弃物品", Material.ARROW, true),
        PICKUP_ITEM("拾取物品", Material.HOPPER, true),
        USE_BED("使用床", Material.RED_BED, true),
        USE_ANVIL("使用铁砧", Material.ANVIL, true),
        USE_FISHING("钓鱼", Material.FISHING_ROD, true),
        USE_NOTE("音符盒", Material.NOTE_BLOCK, true),
        USE_PROJECTILE("投掷物", Material.BOW, true),
        USE_VEHICLE("骑乘载具", Material.SADDLE, true),
        USE_ENDER_PEARL("末影珍珠", Material.ENDER_PEARL, true),
        TELEPORT("允许传送进入", Material.ENDER_EYE, true),
        HURT_ANIMAL("伤害动物", Material.BEEF, false),
        FRAME("展示框", Material.ITEM_FRAME, true),
        ARMOR_STAND("盔甲架", Material.ARMOR_STAND, true),
        REDSTONE("红石", Material.REDSTONE, true),
        SIGN_EDIT("编辑告示牌", Material.OAK_SIGN, true),
        BUCKET("使用桶", Material.BUCKET, true),
        LEASH("拴绳", Material.LEAD, true),
        SHEAR("剪羊毛", Material.SHEARS, true),
        EAT_CAKE("吃蛋糕", Material.CAKE, true),
        ;

        public final String display;
        public final Material icon;
        public final boolean def;
        Flag(String display, Material icon, boolean def) { this.display = display; this.icon = icon; this.def = def; }
    }

    public enum Role { OWNER, ADMIN, MEMBER, BANNED }

    public static class Claim {
        public String id, name, world, enterMsg = "", leaveMsg = "";
        public UUID owner;
        public String ownerName;
        public int minX, minY = 0, minZ, maxX, maxY = 255, maxZ;
        public Location spawn; // 领地传送点
        public double price = 0; // 出售价格, 0=不出售
        public final Map<UUID, Role> members = new HashMap<>();
        public final Map<String, Boolean> flags = new HashMap<>();
        public final List<SubClaim> subClaims = new ArrayList<>();

        public Claim() {}
        public Claim(String id, String name, UUID owner, String ownerName, String world,
                     int minX, int minZ, int maxX, int maxZ) {
            this.id = id; this.name = name; this.owner = owner; this.ownerName = ownerName;
            this.world = world; this.minX = minX; this.minZ = minZ; this.maxX = maxX; this.maxZ = maxZ;
            for (Flag f : Flag.values()) flags.put(f.name(), f.def);
        }

        public boolean getFlag(Flag f) { return flags.getOrDefault(f.name(), f.def); }
        public void setFlag(Flag f, boolean v) { flags.put(f.name(), v); }
        public void toggle(Flag f) { setFlag(f, !getFlag(f)); }

        public boolean contains(Location loc) {
            return loc.getWorld().getName().equals(world)
                    && loc.getX() >= minX && loc.getX() <= maxX
                    && loc.getZ() >= minZ && loc.getZ() <= maxZ;
        }

        public Role getRole(UUID uid) {
            if (owner.equals(uid)) return Role.OWNER;
            return members.getOrDefault(uid, null);
        }

        public boolean can(UUID uid, Flag flag) {
            Role r = getRole(uid);
            if (r == Role.OWNER || r == Role.ADMIN) return true;
            if (r == Role.BANNED) return false;
            if (r == Role.MEMBER) {
                // MEMBER 受 flags 限制
                return getFlag(flag);
            }
            // 陌生人 - 只受公开 flags 限制
            return getFlag(flag);
        }
    }

    public static class SubClaim {
        public String name;
        public int minX, minZ, maxX, maxZ;
        public final Map<String, Boolean> flags = new HashMap<>();
        public SubClaim(String name, int minX, int minZ, int maxX, int maxZ) {
            this.name = name; this.minX = minX; this.minZ = minZ; this.maxX = maxX; this.maxZ = maxZ;
        }
    }

    public ClaimModule(MegaPlugin plugin) {
        super(plugin);
        data = new DataFile(plugin, "claims_v2.yml");
    }

    @Override
    public void onEnable() {
        registerListener();
        loadClaims();
        var cmd = plugin.getCommand("claim");
        if (cmd != null) { cmd.setExecutor(new ClaimCmd()); cmd.setTabCompleter(new ClaimTab()); }
    }

    @Override
    public void onDisable() { saveAll(); }

    private void loadClaims() {
        var cfg = data.getConfig();
        for (String key : cfg.getKeys(false)) {
            try {
                String path = key + ".";
                Claim c = new Claim();
                c.id = key;
                c.name = cfg.getString(key + ".name", key);
                c.owner = UUID.fromString(cfg.getString(key + ".owner", ""));
                c.ownerName = cfg.getString(key + ".ownerName", "?");
                c.world = cfg.getString(key + ".world", "world");
                c.minX = cfg.getInt(key + ".minX"); c.minZ = cfg.getInt(key + ".minZ");
                c.maxX = cfg.getInt(key + ".maxX"); c.maxZ = cfg.getInt(key + ".maxZ");
                c.minY = cfg.getInt(key + ".minY", 0); c.maxY = cfg.getInt(key + ".maxY", 255);
                c.enterMsg = cfg.getString(key + ".enterMsg", "");
                c.leaveMsg = cfg.getString(key + ".leaveMsg", "");
                c.price = cfg.getDouble(key + ".price", 0);
                if (cfg.contains(key + ".spawn")) {
                    var s = cfg.getConfigurationSection(key + ".spawn");
                    if (s != null) c.spawn = new Location(Bukkit.getWorld(s.getString("world", c.world)),
                            s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                            (float) s.getDouble("yaw", 0), (float) s.getDouble("pitch", 0));
                }
                // flags
                var fsec = cfg.getConfigurationSection(key + ".flags");
                if (fsec != null) for (String fk : fsec.getKeys(false)) c.flags.put(fk, fsec.getBoolean(fk));
                // members
                var msec = cfg.getConfigurationSection(key + ".members");
                if (msec != null) for (String mk : msec.getKeys(false)) try {
                    c.members.put(UUID.fromString(mk), Role.valueOf(msec.getString(mk, "MEMBER")));
                } catch (Exception ignored) {}
                claims.put(key, c);
            } catch (Exception e) { plugin.getLogger().warning("[Claim] 加载领地 " + key + " 失败: " + e.getMessage()); }
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
            if (c.spawn != null) {
                cfg.set(p + "spawn.world", c.spawn.getWorld().getName());
                cfg.set(p + "spawn.x", c.spawn.getX()); cfg.set(p + "spawn.y", c.spawn.getY()); cfg.set(p + "spawn.z", c.spawn.getZ());
                cfg.set(p + "spawn.yaw", c.spawn.getYaw()); cfg.set(p + "spawn.pitch", c.spawn.getPitch());
            }
            for (var e : c.flags.entrySet()) cfg.set(p + "flags." + e.getKey(), e.getValue());
            for (var e : c.members.entrySet()) cfg.set(p + "members." + e.getKey(), e.getValue().name());
        }
        data.save();
    }

    // ── 工具 ──

    private Claim getClaimAt(Location loc) {
        if (loc == null || loc.getWorld() == null) return null;
        for (Claim c : claims.values()) {
            if (c.contains(loc)) return c;
        }
        return null;
    }

    private List<Claim> getPlayerClaims(UUID uid) {
        return claims.values().stream().filter(c -> c.owner.equals(uid)).collect(Collectors.toList());
    }

    private boolean hasAnyClaim(UUID uid) {
        return claims.values().stream().anyMatch(c -> c.owner.equals(uid) || c.members.containsKey(uid));
    }

    private String nextId() {
        int i = 1;
        while (claims.containsKey("claim" + i)) i++;
        return "claim" + i;
    }

    // ════════════════════════════════════════
    //  事件保护
    // ════════════════════════════════════════

    // ── 建造 ──
    @EventHandler(priority = EventPriority.HIGH)
    public void onBreak(BlockBreakEvent e) {
        Claim c = getClaimAt(e.getBlock().getLocation());
        if (c == null) return;
        Player p = e.getPlayer();
        if (p.hasPermission("megaplugin.claim.admin")) return;
        Role r = c.getRole(p.getUniqueId());
        if (r == Role.OWNER || r == Role.ADMIN) return;
        if (r == Role.BANNED || !c.getFlag(Flag.BREAK)) {
            e.setCancelled(true);
            p.sendMessage(msg("prefix") + " §c此领地禁止破坏方块！");
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlace(BlockPlaceEvent e) {
        Claim c = getClaimAt(e.getBlock().getLocation());
        if (c == null) return;
        Player p = e.getPlayer();
        if (p.hasPermission("megaplugin.claim.admin")) return;
        Role r = c.getRole(p.getUniqueId());
        if (r == Role.OWNER || r == Role.ADMIN) return;
        if (r == Role.BANNED || !c.getFlag(Flag.BUILD)) {
            e.setCancelled(true);
            p.sendMessage(msg("prefix") + " §c此领地禁止放置方块！");
        }
    }

    // ── 交互 ──
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getClickedBlock() == null) return;
        Block b = e.getClickedBlock();
        Player p = e.getPlayer();
        Claim c = getClaimAt(b.getLocation());
        if (c == null) return;
        if (p.hasPermission("megaplugin.claim.admin")) return;
        Role r = c.getRole(p.getUniqueId());
        if (r == Role.OWNER || r == Role.ADMIN) return;
        if (r == Role.BANNED) { e.setCancelled(true); return; }

        Material m = b.getType();
        boolean isContainer = m.name().contains("CHEST") || m == Material.TRAPPED_CHEST || m == Material.BARREL
                || m.name().contains("SHULKER_BOX") || m == Material.HOPPER || m == Material.DISPENSER
                || m == Material.DROPPER || m == Material.FURNACE || m == Material.BLAST_FURNACE
                || m == Material.SMOKER || m == Material.BREWING_STAND || m == Material.JUKEBOX;
        boolean isInteractable = m.name().contains("DOOR") || m.name().contains("GATE") || m.name().contains("BUTTON")
                || m.name().contains("LEVER") || m.name().contains("PRESSURE_PLATE") || m == Material.NOTE_BLOCK
                || m == Material.ANVIL || m == Material.CHIPPED_ANVIL || m == Material.DAMAGED_ANVIL
                || m.name().contains("TRAPDOOR") || m == Material.BEACON || m == Material.LECTERN
                || m == Material.DAYLIGHT_DETECTOR || m == Material.REPEATER || m == Material.COMPARATOR
                || m == Material.CAKE || m.name().contains("CANDLE") || m == Material.FLOWER_POT;

        if (isContainer && !c.getFlag(Flag.CONTAINER)) {
            e.setCancelled(true); p.sendMessage(msg("prefix") + " §c此领地禁止打开容器！"); return;
        }
        if (isInteractable && !c.getFlag(Flag.INTERACT)) {
            e.setCancelled(true); p.sendMessage(msg("prefix") + " §c此领地禁止交互！"); return;
        }
        if (m == Material.FARMLAND && e.getAction() == org.bukkit.event.block.Action.PHYSICAL && !c.getFlag(Flag.TRAMPLE)) {
            e.setCancelled(true); return;
        }
    }

    // ── 物理交互 ──
    @EventHandler(priority = EventPriority.HIGH)
    public void onPhys(BlockPhysicsEvent e) {
        // 红石保护
    }

    // ── PVP ──
    @EventHandler(priority = EventPriority.HIGH)
    public void onPvp(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player) || !(e.getEntity() instanceof Player)) return;
        Claim c = getClaimAt(e.getEntity().getLocation());
        if (c != null && !c.getFlag(Flag.PVP)) {
            e.setCancelled(true);
            ((Player) e.getDamager()).sendMessage(msg("prefix") + " §c此领地禁止 PVP！");
        }
    }

    // ── PVE ──
    @EventHandler(priority = EventPriority.HIGH)
    public void onPve(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        if (e.getDamager() instanceof Player) return;
        if (!(e.getDamager() instanceof Monster) && !(e.getDamager() instanceof Projectile)) return;
        Claim c = getClaimAt(e.getEntity().getLocation());
        if (c != null && !c.getFlag(Flag.PVE)) e.setCancelled(true);
    }

    // ── 爆炸 ──
    @EventHandler(priority = EventPriority.HIGH)
    public void onExplode(EntityExplodeEvent e) {
        Claim c = getClaimAt(e.getLocation());
        if (c != null && !c.getFlag(Flag.EXPLOSION)) e.blockList().clear();
    }
    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockExplode(BlockExplodeEvent e) {
        Claim c = getClaimAt(e.getBlock().getLocation());
        if (c != null && !c.getFlag(Flag.EXPLOSION)) e.blockList().clear();
    }

    // ── 火焰 ──
    @EventHandler(priority = EventPriority.HIGH)
    public void onIgnite(BlockIgniteEvent e) {
        Claim c = getClaimAt(e.getBlock().getLocation());
        if (c != null && !c.getFlag(Flag.FIRE)) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH)
    public void onBurn(BlockBurnEvent e) {
        Claim c = getClaimAt(e.getBlock().getLocation());
        if (c != null && !c.getFlag(Flag.FIRE)) e.setCancelled(true);
    }

    // ── 生物生成 ──
    @EventHandler(priority = EventPriority.HIGH)
    public void onSpawn(CreatureSpawnEvent e) {
        Claim c = getClaimAt(e.getLocation());
        if (c == null) return;
        if (e.getEntity() instanceof Monster && !c.getFlag(Flag.MOB_SPAWN)) e.setCancelled(true);
        if ((e.getEntity() instanceof Animals || e.getEntity() instanceof Ambient) && !c.getFlag(Flag.ANIMAL_SPAWN)) e.setCancelled(true);
    }

    // ── 植物生长 ──
    @EventHandler(priority = EventPriority.HIGH)
    public void onGrow(BlockGrowEvent e) {
        Claim c = getClaimAt(e.getBlock().getLocation());
        if (c != null && !c.getFlag(Flag.GROWTH)) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH)
    public void onGrowStructure(StructureGrowEvent e) {
        Claim c = getClaimAt(e.getLocation());
        if (c != null && !c.getFlag(Flag.GROWTH)) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH)
    public void onSpread(BlockSpreadEvent e) {
        Claim c = getClaimAt(e.getBlock().getLocation());
        if (c != null && !c.getFlag(Flag.GROWTH)) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH)
    public void onForm(BlockFormEvent e) {
        Claim c = getClaimAt(e.getBlock().getLocation());
        if (c != null && !c.getFlag(Flag.GROWTH)) e.setCancelled(true);
    }

    // ── 物品 ──
    @EventHandler(priority = EventPriority.HIGH)
    public void onDrop(PlayerDropItemEvent e) {
        Claim c = getClaimAt(e.getPlayer().getLocation());
        if (c != null && !c.getFlag(Flag.DROP_ITEM)) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(msg("prefix") + " §c此领地禁止丢弃物品！");
        }
    }
    @EventHandler(priority = EventPriority.HIGH)
    public void onPickup(PlayerAttemptPickupItemEvent e) {
        Claim c = getClaimAt(e.getPlayer().getLocation());
        if (c != null && !c.getFlag(Flag.PICKUP_ITEM)) e.setCancelled(true);
    }

    // ── 投掷物 ──
    @EventHandler(priority = EventPriority.HIGH)
    public void onProjectile(ProjectileLaunchEvent e) {
        if (!(e.getEntity().getShooter() instanceof Player p)) return;
        Claim c = getClaimAt(p.getLocation());
        if (c != null && !c.getFlag(Flag.USE_PROJECTILE)) {
            e.setCancelled(true);
            p.sendMessage(msg("prefix") + " §c此领地禁止投掷！");
        }
    }
    @EventHandler(priority = EventPriority.HIGH)
    public void onSplash(PotionSplashEvent e) {
        if (!(e.getEntity().getShooter() instanceof Player p)) return;
        Claim c = getClaimAt(p.getLocation());
        if (c != null && !c.getFlag(Flag.USE_PROJECTILE)) {
            e.setCancelled(true);
            p.sendMessage(msg("prefix") + " §c此领地禁止投掷药水！");
        }
    }

    // ── 钓鱼 ──
    @EventHandler(priority = EventPriority.HIGH)
    public void onFish(PlayerFishEvent e) {
        Claim c = getClaimAt(e.getPlayer().getLocation());
        if (c != null && !c.getFlag(Flag.USE_FISHING)) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(msg("prefix") + " §c此领地禁止钓鱼！");
        }
    }

    // ── 床 ──
    @EventHandler(priority = EventPriority.HIGH)
    public void onBed(PlayerBedEnterEvent e) {
        Claim c = getClaimAt(e.getBed().getLocation());
        if (c != null && !c.getFlag(Flag.USE_BED) && !canBuild(e.getPlayer(), e.getBed().getLocation())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(msg("prefix") + " §c此领地禁止使用床！");
        }
    }

    // ── 骑乘 ──
    @EventHandler(priority = EventPriority.HIGH)
    public void onMount(EntityMountEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Claim c = getClaimAt(p.getLocation());
        if (c != null && !c.getFlag(Flag.USE_VEHICLE) && !canBuild(p, p.getLocation())) {
            e.setCancelled(true);
            p.sendMessage(msg("prefix") + " §c此领地禁止骑乘！");
        }
    }

    // ── 展示框/盔甲架 ──
    @EventHandler(priority = EventPriority.HIGH)
    public void onHangingBreak(HangingBreakByEntityEvent e) {
        if (!(e.getRemover() instanceof Player p)) return;
        Claim c = getClaimAt(e.getEntity().getLocation());
        if (c == null) return;
        Role r = c.getRole(p.getUniqueId());
        if (r == Role.OWNER || r == Role.ADMIN) return;
        if (!c.getFlag(Flag.FRAME)) { e.setCancelled(true); p.sendMessage(msg("prefix") + " §c此领地禁止破坏展示框！"); }
    }
    @EventHandler(priority = EventPriority.HIGH)
    public void onHangingPlace(HangingPlaceEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        Claim c = getClaimAt(e.getEntity().getLocation());
        if (c == null) return;
        Role r = c.getRole(p.getUniqueId());
        if (r == Role.OWNER || r == Role.ADMIN) return;
        if (!c.getFlag(Flag.FRAME)) { e.setCancelled(true); p.sendMessage(msg("prefix") + " §c此领地禁止放置展示框！"); }
    }
    @EventHandler(priority = EventPriority.HIGH)
    public void onArmorStand(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof ArmorStand)) return;
        if (!(e.getDamager() instanceof Player p)) return;
        Claim c = getClaimAt(e.getEntity().getLocation());
        if (c == null) return;
        Role r = c.getRole(p.getUniqueId());
        if (r == Role.OWNER || r == Role.ADMIN) return;
        if (!c.getFlag(Flag.ARMOR_STAND)) { e.setCancelled(true); p.sendMessage(msg("prefix") + " §c此领地禁止破坏盔甲架！"); }
    }
    @EventHandler(priority = EventPriority.HIGH)
    public void onArmorStandPlace(PlayerInteractEvent e) {
        if (e.getItem() == null || e.getItem().getType() != Material.ARMOR_STAND) return;
        Block b = e.getClickedBlock();
        if (b == null) return;
        Claim c = getClaimAt(b.getLocation());
        if (c == null) return;
        Player p = e.getPlayer();
        Role r = c.getRole(p.getUniqueId());
        if (r == Role.OWNER || r == Role.ADMIN) return;
        if (!c.getFlag(Flag.ARMOR_STAND)) { e.setCancelled(true); p.sendMessage(msg("prefix") + " §c此领地禁止放置盔甲架！"); }
    }

    // ── 动物伤害 ──
    @EventHandler(priority = EventPriority.HIGH)
    public void onAnimalHurt(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Animals) && !(e.getEntity() instanceof Villager)) return;
        Player p = null;
        if (e.getDamager() instanceof Player) p = (Player) e.getDamager();
        else if (e.getDamager() instanceof Projectile && ((Projectile) e.getDamager()).getShooter() instanceof Player)
            p = (Player) ((Projectile) e.getDamager()).getShooter();
        if (p == null) return;
        Claim c = getClaimAt(e.getEntity().getLocation());
        if (c == null) return;
        Role r = c.getRole(p.getUniqueId());
        if (r == Role.OWNER || r == Role.ADMIN) return;
        if (!c.getFlag(Flag.HURT_ANIMAL)) {
            e.setCancelled(true);
            p.sendMessage(msg("prefix") + " §c此领地禁止伤害动物！");
        }
    }

    // ── 活塞 ──
    @EventHandler(priority = EventPriority.HIGH)
    public void onPiston(BlockPistonExtendEvent e) {
        for (Block b : e.getBlocks()) {
            Claim c = getClaimAt(b.getLocation());
            if (c != null && !c.getFlag(Flag.PISTON)) { e.setCancelled(true); return; }
        }
    }
    @EventHandler(priority = EventPriority.HIGH)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        for (Block b : e.getBlocks()) {
            Claim c = getClaimAt(b.getLocation());
            if (c != null && !c.getFlag(Flag.PISTON)) { e.setCancelled(true); return; }
        }
    }

    // ── 液体 ──
    @EventHandler(priority = EventPriority.HIGH)
    public void onFlow(BlockFromToEvent e) {
        if (e.getBlock().getType() != Material.WATER && e.getBlock().getType() != Material.LAVA) return;
        Claim c = getClaimAt(e.getToBlock().getLocation());
        if (c == null) return;
        if (e.getBlock().getType() == Material.WATER && !c.getFlag(Flag.WATER_FLOW)) e.setCancelled(true);
        if (e.getBlock().getType() == Material.LAVA && !c.getFlag(Flag.LAVA_FLOW)) e.setCancelled(true);
    }

    // ── 传送 ──
    @EventHandler(priority = EventPriority.HIGH)
    public void onTp(PlayerTeleportEvent e) {
        if (e.getCause() != PlayerTeleportEvent.TeleportCause.PLUGIN
                && e.getCause() != PlayerTeleportEvent.TeleportCause.COMMAND
                && e.getCause() != PlayerTeleportEvent.TeleportCause.ENDER_PEARL) return;
        Claim c = getClaimAt(e.getTo());
        if (c == null) return;
        Player p = e.getPlayer();
        if (c.owner.equals(p.getUniqueId())) return;
        if (c.members.containsKey(p.getUniqueId()) && c.members.get(p.getUniqueId()) != Role.BANNED) return;
        if (!c.getFlag(Flag.TELEPORT)) {
            e.setCancelled(true);
            p.sendMessage(msg("prefix") + " §c此领地禁止传送到此！");
        }
    }

    // ── 末影珍珠 ──
    @EventHandler(priority = EventPriority.HIGH)
    public void onPearl(PlayerTeleportEvent e) {
        if (e.getCause() != PlayerTeleportEvent.TeleportCause.ENDER_PEARL) return;
        Claim c = getClaimAt(e.getTo());
        if (c == null) return;
        Player p = e.getPlayer();
        Role r = c.getRole(p.getUniqueId());
        if (r == Role.OWNER || r == Role.ADMIN || r == Role.MEMBER) return;
        if (!c.getFlag(Flag.USE_ENDER_PEARL)) {
            e.setCancelled(true);
            p.sendMessage(msg("prefix") + " §c此领地禁止使用末影珍珠！");
        }
    }

    // ── 载具破坏 ──
    @EventHandler(priority = EventPriority.HIGH)
    public void onVehicleDestroy(VehicleDestroyEvent e) {
        if (!(e.getAttacker() instanceof Player p)) return;
        Claim c = getClaimAt(e.getVehicle().getLocation());
        if (c == null) return;
        Role r = c.getRole(p.getUniqueId());
        if (r == Role.OWNER || r == Role.ADMIN) return;
        if (!c.getFlag(Flag.USE_VEHICLE)) {
            e.setCancelled(true);
            p.sendMessage(msg("prefix") + " §c此领地禁止破坏载具！");
        }
    }
    @EventHandler(priority = EventPriority.HIGH)
    public void onVehicleDamage(VehicleDamageEvent e) {
        if (!(e.getAttacker() instanceof Player p)) return;
        Claim c = getClaimAt(e.getVehicle().getLocation());
        if (c == null) return;
        Role r = c.getRole(p.getUniqueId());
        if (r == Role.OWNER || r == Role.ADMIN) return;
        if (!c.getFlag(Flag.USE_VEHICLE)) {
            e.setCancelled(true);
            p.sendMessage(msg("prefix") + " §c此领地禁止破坏载具！");
        }
    }

    // ── 水桶/岩浆桶 ──
    @EventHandler(priority = EventPriority.HIGH)
    public void onBucket(PlayerBucketEmptyEvent e) {
        Claim c = getClaimAt(e.getBlock().getLocation());
        if (c == null) return;
        Player p = e.getPlayer();
        Role r = c.getRole(p.getUniqueId());
        if (r == Role.OWNER || r == Role.ADMIN) return;
        if (!c.getFlag(Flag.BUCKET)) {
            e.setCancelled(true);
            p.sendMessage(msg("prefix") + " §c此领地禁止使用桶！");
        }
    }
    @EventHandler(priority = EventPriority.HIGH)
    public void onBucketFill(PlayerBucketFillEvent e) {
        Claim c = getClaimAt(e.getBlock().getLocation());
        if (c == null) return;
        Player p = e.getPlayer();
        Role r = c.getRole(p.getUniqueId());
        if (r == Role.OWNER || r == Role.ADMIN) return;
        if (!c.getFlag(Flag.BUCKET)) {
            e.setCancelled(true);
            p.sendMessage(msg("prefix") + " §c此领地禁止使用桶！");
        }
    }

    // ── 拴绳 ──
    @EventHandler(priority = EventPriority.HIGH)
    public void onLeash(PlayerLeashEntityEvent e) {
        Claim c = getClaimAt(e.getEntity().getLocation());
        if (c == null) return;
        Player p = e.getPlayer();
        Role r = c.getRole(p.getUniqueId());
        if (r == Role.OWNER || r == Role.ADMIN) return;
        if (!c.getFlag(Flag.LEASH)) {
            e.setCancelled(true);
            p.sendMessage(msg("prefix") + " §c此领地禁止使用拴绳！");
        }
    }

    // ── 剪羊毛 ──
    @EventHandler(priority = EventPriority.HIGH)
    public void onShear(PlayerShearEntityEvent e) {
        Claim c = getClaimAt(e.getEntity().getLocation());
        if (c == null) return;
        Player p = e.getPlayer();
        Role r = c.getRole(p.getUniqueId());
        if (r == Role.OWNER || r == Role.ADMIN) return;
        if (!c.getFlag(Flag.SHEAR)) {
            e.setCancelled(true);
            p.sendMessage(msg("prefix") + " §c此领地禁止剪羊毛！");
        }
    }

    // ── 吃蛋糕 ──
    @EventHandler(priority = EventPriority.HIGH)
    public void onCake(PlayerInteractEvent e) {
        if (e.getClickedBlock() == null || e.getClickedBlock().getType() != Material.CAKE) return;
        Claim c = getClaimAt(e.getClickedBlock().getLocation());
        if (c == null) return;
        Player p = e.getPlayer();
        Role r = c.getRole(p.getUniqueId());
        if (r == Role.OWNER || r == Role.ADMIN) return;
        if (!c.getFlag(Flag.EAT_CAKE)) {
            e.setCancelled(true);
            p.sendMessage(msg("prefix") + " §c此领地禁止吃蛋糕！");
        }
    }

    // ── 告示牌编辑 ──
    @EventHandler(priority = EventPriority.HIGH)
    public void onSign(SignChangeEvent e) {
        Claim c = getClaimAt(e.getBlock().getLocation());
        if (c == null) return;
        Player p = e.getPlayer();
        Role r = c.getRole(p.getUniqueId());
        if (r == Role.OWNER || r == Role.ADMIN) return;
        if (!c.getFlag(Flag.SIGN_EDIT)) {
            e.setCancelled(true);
            p.sendMessage(msg("prefix") + " §c此领地禁止编辑告示牌！");
        }
    }

    // ── 黑名单踢出 ──
    @EventHandler(priority = EventPriority.HIGH)
    public void onMoveBlacklist(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (e.getFrom().getBlockX() == e.getTo().getBlockX()
                && e.getFrom().getBlockZ() == e.getTo().getBlockZ()) return;
        Claim c = getClaimAt(e.getTo());
        if (c == null) return;
        if (c.owner.equals(p.getUniqueId())) return;
        Role r = c.getRole(p.getUniqueId());
        if (r == Role.BANNED) {
            e.setCancelled(true);
            p.sendMessage(msg("prefix") + " §c你被 §e" + c.ownerName + " §c禁止进入领地 §e" + c.name + "！");
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
        }
    }

    // ── 红石保护（简化版）──
    @EventHandler(priority = EventPriority.HIGH)
    public void onRedstone(BlockRedstoneEvent e) {
        // 红石事件不可取消，已通过交互保护
    }

    private boolean canBuild(Player p, Location loc) {
        Claim c = getClaimAt(loc);
        if (c == null) return true;
        Role r = c.getRole(p.getUniqueId());
        return r == Role.OWNER || r == Role.ADMIN || p.hasPermission("megaplugin.claim.admin");
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
            String status = c.price > 0 ? " §a[出售中 §e" + c.price + "§a]" : "";
            inv.setItem(slot++, createItem(Material.GRASS_BLOCK,
                    "§a§l" + c.name + status,
                    "§7坐标: §f" + c.minX + "," + c.minZ + " §7→ §f" + c.maxX + "," + c.maxZ,
                    "§7大小: §e" + (c.maxX - c.minX + 1) + "x" + (c.maxZ - c.minZ + 1),
                    "§7成员: §e" + c.members.size() + " §7| §7子领地: §e" + c.subClaims.size(),
                    "",
                    "§c左键 §7管理成员",
                    "§e右键 §7领地设置",
                    "§bShift+左键 §7设置传送点",
                    "§dShift+右键 §7领地地图"));
        }
        if (my.isEmpty()) {
            inv.setItem(13, createItem(Material.BARRIER, "§c暂无领地",
                    "§7使用 §e/claim create <名字> §7创建领地",
                    "§7手持 §e木斧 §7左右键选区"));
        }
        inv.setItem(48, createItem(Material.GOLDEN_AXE, "§e§l创建领地", "§7手持木斧选区后点击"));
        inv.setItem(49, createItem(Material.COMPASS, "§d§l领地地图", "§7查看周围领地分布"));
        inv.setItem(50, createItem(Material.EMERALD, "§2§l领地商店", "§7查看出售中的领地"));
        inv.setItem(53, createItem(Material.BARRIER, "§c§l关闭"));
        fillGlass(inv, 45, 54);
        p.openInventory(inv);
    }

    private void openSettingsGui(Player p, Claim c) {
        Inventory inv = Bukkit.createInventory(null, 54, GUI_SETTINGS);
        inv.setItem(0, createItem(Material.GRASS_BLOCK, "§a§l" + c.name, "§7点击开关权限"));
        inv.setItem(45, createItem(Material.ARROW, "§c§l返回"));
        inv.setItem(53, createItem(Material.BARRIER, "§c§l关闭"));

        int idx = 0;
        int[] slots = {2,3,4,5,6,7, 11,12,13,14,15,16,17, 20,21,22,23,24,25,26, 29,30,31,32,33,34,35, 38,39,40,41,42,43,44};
        for (Flag f : Flag.values()) {
            if (idx >= slots.length) break;
            boolean on = c.getFlag(f);
            String color = on ? "§a" : "§c";
            inv.setItem(slots[idx++], createItem(f.icon,
                    color + f.display,
                    "§7当前: " + (on ? "§a§l[开启]" : "§c§l[关闭]"),
                    "§7(点击切换)"));
        }
        // 额外功能按钮
        inv.setItem(47, createItem(Material.NAME_TAG, "§e§l重命名", "§7点击修改领地名称"));
        inv.setItem(48, createItem(Material.OAK_SIGN, "§b§l公告消息", "§7设置进出提示消息"));
        inv.setItem(49, createItem(Material.GOLD_INGOT, "§2§l出售领地", "§7设置出售价格 (0=不出售)"));
        inv.setItem(50, createItem(Material.ENDER_PEARL, "§d§l设置传送点", "§7设置领地内传送点"));

        fillGlass(inv, 0, 54);
        p.openInventory(inv);
    }

    private void openMemberGui(Player p, Claim c) {
        Inventory inv = Bukkit.createInventory(null, 54, GUI_MEMBERS);
        inv.setItem(0, createItem(Material.GRASS_BLOCK, "§a§l" + c.name, "§7成员管理"));
        inv.setItem(45, createItem(Material.ARROW, "§c§l返回"));
        inv.setItem(53, createItem(Material.BARRIER, "§c§l关闭"));
        inv.setItem(49, createItem(Material.EMERALD, "§a§l添加成员", "§7使用 /claim invite <领地> <玩家>"));

        int slot = 2;
        for (var e : c.members.entrySet()) {
            if (slot >= 45) break;
            String name = Bukkit.getOfflinePlayer(e.getKey()).getName();
            if (name == null) name = e.getKey().toString().substring(0, 8);
            String roleColor = e.getValue() == Role.ADMIN ? "§c" : "§e";
            inv.setItem(slot++, createItem(Material.PLAYER_HEAD,
                    roleColor + name,
                    "§7角色: " + roleColor + e.getValue().name(),
                    "§7点击降级/移除"));
        }
        fillGlass(inv, 0, 54);
        p.openInventory(inv);
    }

    private void openMapGui(Player p) {
        Inventory inv = Bukkit.createInventory(null, 54, GUI_MAP);
        Location loc = p.getLocation();
        int cx = loc.getBlockX() >> 4; // chunk x
        int cz = loc.getBlockZ() >> 4;

        for (int row = 0; row < 6; row++) {
            for (int col = 0; col < 9; col++) {
                int wx = (cx - 4 + col) << 4;
                int wz = (cz - 2 + row) << 4;
                Location check = new Location(loc.getWorld(), wx, loc.getY(), wz);
                Claim claim = getClaimAt(check);
                int slot = row * 9 + col;
                if (row == 2 && col == 4) {
                    inv.setItem(slot, createItem(Material.PLAYER_HEAD, "§a§l你", "§7当前位置"));
                } else if (claim != null) {
                    boolean isMine = claim.owner.equals(p.getUniqueId());
                    boolean isMember = claim.members.containsKey(p.getUniqueId());
                    String color = isMine ? "§a" : isMember ? "§e" : "§c";
                    inv.setItem(slot, createItem(isMine ? Material.LIME_WOOL : isMember ? Material.YELLOW_WOOL : Material.RED_WOOL,
                            color + claim.name,
                            "§7主人: §e" + claim.ownerName,
                            "§7左键查看信息"));
                } else {
                    inv.setItem(slot, createItem(Material.GREEN_STAINED_GLASS_PANE, "§7荒野", "§7无人占领"));
                }
            }
        }
        inv.setItem(49, createItem(Material.COMPASS, "§d§l刷新", "§7点击刷新地图"));
        inv.setItem(53, createItem(Material.BARRIER, "§c§l关闭"));
        p.openInventory(inv);
    }

    private void openBuyGui(Player p) {
        Inventory inv = Bukkit.createInventory(null, 54, GUI_BUY);
        int slot = 0;
        for (Claim c : claims.values()) {
            if (c.price <= 0) continue;
            if (slot >= 45) break;
            inv.setItem(slot++, createItem(Material.GRASS_BLOCK,
                    "§a§l" + c.name,
                    "§7主人: §e" + c.ownerName,
                    "§7大小: §e" + (c.maxX - c.minX + 1) + "x" + (c.maxZ - c.minZ + 1),
                    "§7价格: §6" + c.price,
                    "§7点击购买"));
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

        if (t.equals(GUI_MAIN)) {
            if ("§e§l创建领地".equals(name)) {
                p.closeInventory();
                p.sendMessage(msg("prefix") + " §7手持 §e木斧 §7左右键选区后输入 §e/claim create <名字>");
            } else if ("§d§l领地地图".equals(name)) {
                openMapGui(p);
            } else if ("§2§l领地商店".equals(name)) {
                openBuyGui(p);
            } else if ("§c§l关闭".equals(name) || "§c暂无领地".equals(name)) {
                p.closeInventory();
            } else if (name.startsWith("§a§l")) {
                String claimName = name.substring(4).split(" ")[0];
                Claim c = findClaimByName(p.getUniqueId(), claimName);
                if (c != null) {
                    lastClaim.put(p.getUniqueId(), c.id);
                    if (e.isShiftClick() && e.isLeftClick()) {
                        // 设置传送点
                        c.spawn = p.getLocation().clone();
                        saveAll();
                        p.sendMessage(msg("prefix") + " §a已设置领地 §e" + c.name + " §a的传送点！");
                        p.closeInventory();
                    } else if (e.isShiftClick() && e.isRightClick()) {
                        openMapGui(p);
                    } else if (e.isLeftClick()) {
                        openMemberGui(p, c);
                    } else if (e.isRightClick()) {
                        openSettingsGui(p, c);
                    }
                }
            }
        } else if (t.equals(GUI_SETTINGS)) {
            if ("§c§l返回".equals(name)) { openMainGui(p); return; }
            if ("§c§l关闭".equals(name)) { p.closeInventory(); return; }
            if ("§e§l重命名".equals(name)) { p.closeInventory(); p.sendMessage(msg("prefix") + " §7使用 §e/claim rename <新名字>"); return; }
            if ("§b§l公告消息".equals(name)) { p.closeInventory(); p.sendMessage(msg("prefix") + " §7使用 §e/claim setmsg <enter|leave> <消息>"); return; }
            if ("§2§l出售领地".equals(name)) { p.closeInventory(); p.sendMessage(msg("prefix") + " §7使用 §e/claim sell <价格> (0=取消出售)"); return; }
            if ("§d§l设置传送点".equals(name)) {
                String cid = lastClaim.get(p.getUniqueId());
                if (cid != null) { Claim c = claims.get(cid); if (c != null) { c.spawn = p.getLocation().clone(); saveAll(); p.sendMessage(msg("prefix") + " §a传送点已设置！"); } }
                p.closeInventory(); return;
            }
            // 权限开关
            String cid = lastClaim.get(p.getUniqueId());
            if (cid == null) return;
            Claim c = claims.get(cid);
            if (c == null) return;
            for (Flag f : Flag.values()) {
                if (name.contains(f.display)) {
                    c.toggle(f); saveAll();
                    p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
                    openSettingsGui(p, c);
                    return;
                }
            }
        } else if (t.equals(GUI_MEMBERS)) {
            if ("§c§l返回".equals(name)) { openMainGui(p); return; }
            if ("§c§l关闭".equals(name)) { p.closeInventory(); return; }
            if ("§a§l添加成员".equals(name)) { p.closeInventory(); p.sendMessage(msg("prefix") + " §7使用 §e/claim invite <玩家>"); return; }
            if (name.startsWith("§c") || name.startsWith("§e")) {
                p.closeInventory();
                p.sendMessage(msg("prefix") + " §7使用 §e/claim kick <玩家> §7移除成员");
            }
        } else if (t.equals(GUI_MAP)) {
            if ("§d§l刷新".equals(name)) { openMapGui(p); return; }
            if ("§c§l关闭".equals(name)) { p.closeInventory(); return; }
            if (name.startsWith("§a") || name.startsWith("§e") || name.startsWith("§c")) {
                String claimName = name.substring(2);
                for (Claim cl : claims.values()) {
                    if (cl.name.equalsIgnoreCase(claimName)) {
                        p.sendMessage("§8§m          §r §a§l领地信息 §8§m          ");
                        p.sendMessage(" §7名称: §e" + cl.name);
                        p.sendMessage(" §7主人: §e" + cl.ownerName);
                        p.sendMessage(" §7坐标: §f" + cl.minX + "," + cl.minZ + " §7→ §f" + cl.maxX + "," + cl.maxZ);
                        p.sendMessage(" §7成员: §e" + cl.members.size());
                        p.sendMessage("§8§m                                    ");
                        break;
                    }
                }
            }
        } else if (t.equals(GUI_BUY)) {
            if ("§c§l关闭".equals(name)) { p.closeInventory(); return; }
            if (name.startsWith("§a§l")) {
                String claimName = name.substring(4);
                for (Claim cl : claims.values()) {
                    if (cl.name.equalsIgnoreCase(claimName) && cl.price > 0) {
                        p.closeInventory();
                        p.sendMessage(msg("prefix") + " §7使用 §e/claim buy " + cl.name + " §7购买此领地");
                        break;
                    }
                }
            }
        }
    }

    @EventHandler
    public void onGuiDrag(InventoryDragEvent e) {
        String t = e.getView().getTitle();
        if (t.equals(GUI_MAIN) || t.equals(GUI_SETTINGS) || t.equals(GUI_MEMBERS)
                || t.equals(GUI_MAP) || t.equals(GUI_BUY)) e.setCancelled(true);
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
            p.spawnParticle(Particle.HAPPY_VILLAGER, b.getLocation().add(0.5, 1.2, 0.5), 5, 0.3, 0.3, 0.3, 0);
        } else if (e.getAction().name().contains("RIGHT")) {
            sel[1] = b.getLocation();
            p.sendMessage(msg("prefix") + " §a位置2: §e" + b.getX() + "§7, §e" + b.getZ());
            p.spawnParticle(Particle.HAPPY_VILLAGER, b.getLocation().add(0.5, 1.2, 0.5), 5, 0.3, 0.3, 0.3, 0);
            if (sel[0] != null) {
                int dx = Math.abs(sel[1].getBlockX() - sel[0].getBlockX()) + 1;
                int dz = Math.abs(sel[1].getBlockZ() - sel[0].getBlockZ()) + 1;
                p.sendMessage(msg("prefix") + " §7选区大小: §e" + dx + "x" + dz + " §7(最大 " + MAX_CLAIM_SIZE + "x" + MAX_CLAIM_SIZE + ")");
            }
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

        // 手持木斧可视化
        if (p.getInventory().getItemInMainHand().getType() == Material.WOODEN_AXE) {
            Long last = lastParticle.get(p.getUniqueId());
            if (last == null || System.currentTimeMillis() - last > 600) {
                lastParticle.put(p.getUniqueId(), System.currentTimeMillis());
                Claim near = getClaimAt(p.getLocation());
                if (near != null) showClaimParticles(p, near);
            }
        }

        // 进出提示
        Claim at = getClaimAt(e.getTo());
        String newName = at != null ? at.name : null;
        String oldName = currentClaim.get(p.getUniqueId());
        if (Objects.equals(oldName, newName)) return;
        currentClaim.put(p.getUniqueId(), newName);

        if (newName != null && oldName == null) {
            Role r = at.getRole(p.getUniqueId());
            String roleTag = r == Role.OWNER ? "§a[主人] " : r == Role.ADMIN ? "§c[管理] " : r == Role.MEMBER ? "§e[成员] " : "§7";
            if (!at.enterMsg.isEmpty()) {
                p.sendMessage(com.megaplugin.util.Color.colorize(at.enterMsg
                        .replace("%player%", p.getName()).replace("%owner%", at.ownerName).replace("%claim%", at.name)));
            }
            p.showTitle(Title.title(
                    Component.text(at.name, NamedTextColor.GREEN),
                    Component.text(roleTag + "主人: " + at.ownerName, NamedTextColor.GRAY),
                    Title.Times.times(Duration.ofMillis(400), Duration.ofSeconds(2), Duration.ofMillis(400))
            ));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.5f, 1.5f);
        } else if (newName == null && oldName != null) {
            Claim old = null;
            for (Claim cl : claims.values()) if (cl.name.equals(oldName)) { old = cl; break; }
            if (old != null && !old.leaveMsg.isEmpty()) {
                p.sendMessage(com.megaplugin.util.Color.colorize(old.leaveMsg
                        .replace("%player%", p.getName()).replace("%owner%", old.ownerName).replace("%claim%", old.name)));
            }
            p.showTitle(Title.title(
                    Component.empty().color(NamedTextColor.DARK_GRAY).append(text("§7离开领地")),
                    Component.text(""),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(1), Duration.ofMillis(300))
            ));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.3f, 0.8f);
        }
    }

    private void showClaimParticles(Player p, Claim c) {
        World w = p.getWorld();
        if (!w.getName().equals(c.world)) return;
        int y = p.getLocation().getBlockY();
        Particle pt = c.owner.equals(p.getUniqueId()) ? Particle.HAPPY_VILLAGER : c.members.containsKey(p.getUniqueId()) ? Particle.COMPOSTER : Particle.DRIPPING_LAVA;
        for (int x = c.minX; x <= c.maxX; x += 2) { spawnParticle(p, pt, x, y, c.minZ); spawnParticle(p, pt, x, y, c.maxZ); }
        for (int z = c.minZ; z <= c.maxZ; z += 2) { spawnParticle(p, pt, c.minX, y, z); spawnParticle(p, pt, c.maxX, y, z); }
        for (int dy = -1; dy <= 2; dy++) {
            spawnParticle(p, pt, c.minX, y + dy, c.minZ); spawnParticle(p, pt, c.maxX, y + dy, c.minZ);
            spawnParticle(p, pt, c.minX, y + dy, c.maxZ); spawnParticle(p, pt, c.maxX, y + dy, c.maxZ);
        }
    }
    private void spawnParticle(Player p, Particle pt, int x, int y, int z) {
        p.spawnParticle(pt, x + 0.5, y + 0.5, z + 0.5, 1, 0, 0, 0, 0);
    }
    private net.kyori.adventure.text.Component text(String s) {
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(s);
    }

    // ════════════════════════════════════════
    //  命令
    // ════════════════════════════════════════

    private class ClaimCmd implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!(s instanceof Player p)) { s.sendMessage(msg("player-only")); return true; }
            if (a.length == 0) { openMainGui(p); return true; }

            switch (a[0].toLowerCase()) {
                case "create" -> {
                    if (a.length < 2) { p.sendMessage(msg("prefix") + " §c用法: /claim create <名字>"); return true; }
                    Location[] sel = selections.get(p.getUniqueId());
                    if (sel == null || sel[0] == null || sel[1] == null) { p.sendMessage(msg("prefix") + " §c请先手持 §e木斧 §c左右键选区！"); return true; }
                    int minX = Math.min(sel[0].getBlockX(), sel[1].getBlockX());
                    int maxX = Math.max(sel[0].getBlockX(), sel[1].getBlockX());
                    int minZ = Math.min(sel[0].getBlockZ(), sel[1].getBlockZ());
                    int maxZ = Math.max(sel[0].getBlockZ(), sel[1].getBlockZ());
                    int dx = maxX - minX + 1, dz = maxZ - minZ + 1;
                    if (dx > MAX_CLAIM_SIZE || dz > MAX_CLAIM_SIZE) { p.sendMessage(msg("prefix") + " §c选区不能超过 " + MAX_CLAIM_SIZE + "x" + MAX_CLAIM_SIZE + "！"); return true; }
                    if (dx < 5 || dz < 5) { p.sendMessage(msg("prefix") + " §c选区最小 5x5！"); return true; }
                    List<Claim> my = getPlayerClaims(p.getUniqueId());
                    if (my.size() >= (p.hasPermission("megaplugin.claim.admin") ? 50 : MAX_CLAIMS)) { p.sendMessage(msg("prefix") + " §c你只能拥有 " + MAX_CLAIMS + " 个领地！"); return true; }
                    World w = p.getWorld();
                    for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
                        Claim ex = getClaimAt(new Location(w, x, 64, z));
                        if (ex != null) { p.sendMessage(msg("prefix") + " §c此区域与领地 §e" + ex.name + " §c重叠！"); return true; }
                    }
                    String name = a[1];
                    for (Claim cl : claims.values()) if (cl.name.equalsIgnoreCase(name)) { p.sendMessage(msg("prefix") + " §c领地名称已存在！"); return true; }
                    String id = nextId();
                    Claim cl = new Claim(id, name, p.getUniqueId(), p.getName(), w.getName(), minX, minZ, maxX, maxZ);
                    claims.put(id, cl); saveAll(); selections.remove(p.getUniqueId());
                    p.sendMessage(msg("prefix") + " §a领地 §e" + name + " §a创建成功！大小: §e" + dx + "x" + dz + " §a= §e" + (dx*dz) + " §a方块");
                    p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
                }
                case "invite" -> {
                    if (a.length < 2) { p.sendMessage(msg("prefix") + " §c用法: /claim invite <玩家> [领地]"); return true; }
                    Player target = Bukkit.getPlayer(a[1]);
                    if (target == null) { p.sendMessage(msg("player-not-found")); return true; }
                    Claim cl;
                    if (a.length >= 3) cl = findClaimByName(p.getUniqueId(), a[2]);
                    else cl = getClaimAt(p.getLocation());
                    if (cl == null || !cl.owner.equals(p.getUniqueId())) { p.sendMessage(msg("prefix") + " §c你必须站在自己的领地内或指定领地！"); return true; }
                    if (cl.members.containsKey(target.getUniqueId())) { p.sendMessage(msg("prefix") + " §e" + target.getName() + " §7已是成员！"); return true; }
                    cl.members.put(target.getUniqueId(), Role.MEMBER); saveAll();
                    p.sendMessage(msg("prefix") + " §a已将 §e" + target.getName() + " §a添加为成员");
                    target.sendMessage(msg("prefix") + " §a你已被 §e" + p.getName() + " §a邀请加入领地 §e" + cl.name);
                }
                case "promote" -> {
                    if (a.length < 2) { p.sendMessage(msg("prefix") + " §c用法: /claim promote <玩家> [领地]"); return true; }
                    Claim cl = a.length >= 3 ? findClaimByName(p.getUniqueId(), a[2]) : getClaimAt(p.getLocation());
                    if (cl == null || !cl.owner.equals(p.getUniqueId())) { p.sendMessage(msg("prefix") + " §c你没有权限！"); return true; }
                    Player target = Bukkit.getPlayer(a[1]);
                    if (target == null) { p.sendMessage(msg("player-not-found")); return true; }
                    if (!cl.members.containsKey(target.getUniqueId())) { p.sendMessage(msg("prefix") + " §c该玩家不是成员！"); return true; }
                    cl.members.put(target.getUniqueId(), Role.ADMIN); saveAll();
                    p.sendMessage(msg("prefix") + " §a已将 §e" + target.getName() + " §a提升为管理员");
                    target.sendMessage(msg("prefix") + " §a你已被提升为领地 §e" + cl.name + " §a的管理员");
                }
                case "kick" -> {
                    if (a.length < 2) { p.sendMessage(msg("prefix") + " §c用法: /claim kick <玩家> [领地]"); return true; }
                    Claim cl = a.length >= 3 ? findClaimByName(p.getUniqueId(), a[2]) : getClaimAt(p.getLocation());
                    if (cl == null || !cl.owner.equals(p.getUniqueId())) { p.sendMessage(msg("prefix") + " §c你没有权限！"); return true; }
                    Player target = Bukkit.getPlayer(a[1]);
                    UUID uuid = target != null ? target.getUniqueId() : null;
                    if (uuid == null) { p.sendMessage(msg("player-not-found")); return true; }
                    if (cl.members.remove(uuid) != null) { saveAll(); p.sendMessage(msg("prefix") + " §c已将 §e" + target.getName() + " §c移出领地"); }
                    else { p.sendMessage(msg("prefix") + " §c该玩家不是成员！"); }
                }
                case "ban" -> {
                    if (a.length < 2) { p.sendMessage(msg("prefix") + " §c用法: /claim ban <玩家> [领地]"); return true; }
                    Claim cl = a.length >= 3 ? findClaimByName(p.getUniqueId(), a[2]) : getClaimAt(p.getLocation());
                    if (cl == null || !cl.owner.equals(p.getUniqueId())) { p.sendMessage(msg("prefix") + " §c你没有权限！"); return true; }
                    Player target = Bukkit.getPlayer(a[1]);
                    if (target == null) { p.sendMessage(msg("player-not-found")); return true; }
                    if (cl.owner.equals(target.getUniqueId())) { p.sendMessage(msg("prefix") + " §c不能黑名单自己！"); return true; }
                    cl.members.put(target.getUniqueId(), Role.BANNED); saveAll();
                    p.sendMessage(msg("prefix") + " §c已将 §e" + target.getName() + " §c加入黑名单");
                    target.sendMessage(msg("prefix") + " §c你已被 §e" + p.getName() + " §c禁止进入领地 §e" + cl.name);
                }
                case "unban" -> {
                    if (a.length < 2) { p.sendMessage(msg("prefix") + " §c用法: /claim unban <玩家> [领地]"); return true; }
                    Claim cl = a.length >= 3 ? findClaimByName(p.getUniqueId(), a[2]) : getClaimAt(p.getLocation());
                    if (cl == null || !cl.owner.equals(p.getUniqueId())) { p.sendMessage(msg("prefix") + " §c你没有权限！"); return true; }
                    Player target = Bukkit.getPlayer(a[1]);
                    UUID uuid = target != null ? target.getUniqueId() : null;
                    if (uuid == null) { p.sendMessage(msg("player-not-found")); return true; }
                    Role r = cl.members.get(uuid);
                    if (r == Role.BANNED) { cl.members.remove(uuid); saveAll(); p.sendMessage(msg("prefix") + " §a已解除黑名单: §e" + target.getName()); }
                }
                case "remove", "delete" -> {
                    if (a.length < 2) { p.sendMessage(msg("prefix") + " §c用法: /claim remove <领地>"); return true; }
                    Claim cl = findClaimByName(p.getUniqueId(), a[1]);
                    if (cl == null) { p.sendMessage(msg("prefix") + " §c领地不存在！"); return true; }
                    claims.remove(cl.id);
                    data.getConfig().set(cl.id, null); saveAll();
                    p.sendMessage(msg("prefix") + " §c领地 §e" + cl.name + " §c已删除");
                }
                case "rename" -> {
                    if (a.length < 2) { p.sendMessage(msg("prefix") + " §c用法: /claim rename <新名字> [领地]"); return true; }
                    Claim cl = a.length >= 3 ? findClaimByName(p.getUniqueId(), a[2]) : getClaimAt(p.getLocation());
                    if (cl == null || !cl.owner.equals(p.getUniqueId())) { p.sendMessage(msg("prefix") + " §c你没有权限！"); return true; }
                    String newName = a[1];
                    for (Claim cx : claims.values()) if (cx != cl && cx.name.equalsIgnoreCase(newName)) { p.sendMessage(msg("prefix") + " §c名称已存在！"); return true; }
                    cl.name = newName; saveAll();
                    p.sendMessage(msg("prefix") + " §a领地已重命名为 §e" + newName);
                }
                case "setmsg" -> {
                    if (a.length < 3) { p.sendMessage(msg("prefix") + " §c用法: /claim setmsg <enter|leave> <消息> [领地]"); return true; }
                    Claim cl = a.length >= 4 ? findClaimByName(p.getUniqueId(), a[3]) : getClaimAt(p.getLocation());
                    if (cl == null || !cl.owner.equals(p.getUniqueId())) { p.sendMessage(msg("prefix") + " §c你没有权限！"); return true; }
                    String msg = String.join(" ", Arrays.copyOfRange(a, 2, a.length));
                    if (a[1].equalsIgnoreCase("enter")) cl.enterMsg = msg;
                    else if (a[1].equalsIgnoreCase("leave")) cl.leaveMsg = msg;
                    else { p.sendMessage(msg("prefix") + " §c类型必须是 enter 或 leave"); return true; }
                    saveAll();
                    p.sendMessage(msg("prefix") + " §a已设置 §e" + a[1] + " §a消息: §f" + msg);
                }
                case "sell" -> {
                    if (a.length < 2) { p.sendMessage(msg("prefix") + " §c用法: /claim sell <价格> [领地] (0=取消出售)"); return true; }
                    double price;
                    try { price = Double.parseDouble(a[1]); } catch (NumberFormatException ex) { p.sendMessage(msg("prefix") + " §c价格必须是数字！"); return true; }
                    Claim cl = a.length >= 3 ? findClaimByName(p.getUniqueId(), a[2]) : getClaimAt(p.getLocation());
                    if (cl == null || !cl.owner.equals(p.getUniqueId())) { p.sendMessage(msg("prefix") + " §c你没有权限！"); return true; }
                    cl.price = price; saveAll();
                    if (price > 0) p.sendMessage(msg("prefix") + " §a领地 §e" + cl.name + " §a已挂牌出售，价格: §6" + price);
                    else p.sendMessage(msg("prefix") + " §a领地 §e" + cl.name + " §a已取消出售");
                }
                case "buy" -> {
                    if (a.length < 2) { p.sendMessage(msg("prefix") + " §c用法: /claim buy <领地>"); return true; }
                    Claim cl = null;
                    for (Claim cx : claims.values()) if (cx.name.equalsIgnoreCase(a[1])) { cl = cx; break; }
                    if (cl == null) { p.sendMessage(msg("prefix") + " §c领地不存在！"); return true; }
                    if (cl.price <= 0) { p.sendMessage(msg("prefix") + " §c此领地不出售！"); return true; }
                    EconomyModule eco = plugin.getEconomyModule();
                    if (eco == null) { p.sendMessage(msg("prefix") + " §c经济系统未启用！"); return true; }
                    if (!eco.hasEnough(p.getUniqueId(), cl.price)) { p.sendMessage(msg("prefix") + " §c余额不足！需要 §e" + cl.price); return true; }
                    eco.withdraw(p, cl.price);
                    // 转账给原主人
                    Player oldOwner = Bukkit.getPlayer(cl.owner);
                    if (oldOwner != null) eco.deposit(oldOwner, cl.price);
                    else eco.deposit(cl.owner, cl.price);
                    cl.owner = p.getUniqueId();
                    cl.ownerName = p.getName();
                    cl.members.clear();
                    cl.price = 0;
                    saveAll();
                    p.sendMessage(msg("prefix") + " §a成功购买领地 §e" + cl.name + "！");
                    p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
                }
                case "tp", "home" -> {
                    Claim cl = a.length >= 2 ? findClaimByName(p.getUniqueId(), a[1]) : getClaimAt(p.getLocation());
                    if (cl == null) cl = getPlayerClaims(p.getUniqueId()).stream().findFirst().orElse(null);
                    if (cl == null) { p.sendMessage(msg("prefix") + " §c你没有领地！"); return true; }
                    if (cl.spawn != null) p.teleport(cl.spawn);
                    else p.teleport(new Location(Bukkit.getWorld(cl.world), (cl.minX + cl.maxX) / 2.0, 64, (cl.minZ + cl.maxZ) / 2.0));
                    p.sendMessage(msg("prefix") + " §a已传送到领地 §e" + cl.name);
                }
                case "setspawn" -> {
                    Claim cl = a.length >= 2 ? findClaimByName(p.getUniqueId(), a[1]) : getClaimAt(p.getLocation());
                    if (cl == null || !cl.owner.equals(p.getUniqueId())) { p.sendMessage(msg("prefix") + " §c你必须在自己的领地内！"); return true; }
                    cl.spawn = p.getLocation().clone(); saveAll();
                    p.sendMessage(msg("prefix") + " §a已设置领地 §e" + cl.name + " §a的传送点！");
                }
                case "list" -> openMainGui(p);
                case "map" -> openMapGui(p);
                case "shop" -> openBuyGui(p);
                default -> {
                    p.sendMessage(msg("prefix") + " §7/claim §e- 打开领地菜单");
                    p.sendMessage(msg("prefix") + " §7/claim create <名字> §e- 创建领地");
                    p.sendMessage(msg("prefix") + " §7/claim invite <玩家> [领地] §e- 邀请成员");
                    p.sendMessage(msg("prefix") + " §7/claim promote <玩家> [领地] §e- 提升管理员");
                    p.sendMessage(msg("prefix") + " §7/claim kick <玩家> [领地] §e- 踢出成员");
                    p.sendMessage(msg("prefix") + " §7/claim ban/unban <玩家> [领地] §e- 黑名单");
                    p.sendMessage(msg("prefix") + " §7/claim rename <新名字> [领地] §e- 重命名");
                    p.sendMessage(msg("prefix") + " §7/claim setmsg <enter|leave> <消息> [领地] §e- 设置公告");
                    p.sendMessage(msg("prefix") + " §7/claim sell <价格> [领地] §e- 出售领地");
                    p.sendMessage(msg("prefix") + " §7/claim buy <领地> §e- 购买领地");
                    p.sendMessage(msg("prefix") + " §7/claim tp [领地] §e- 传送到领地");
                    p.sendMessage(msg("prefix") + " §7/claim setspawn [领地] §e- 设置传送点");
                    p.sendMessage(msg("prefix") + " §7/claim map §e- 领地地图");
                }
            }
            return true;
        }
    }

    private Claim findClaimByName(UUID owner, String name) {
        for (Claim c : claims.values()) {
            if (c.name.equalsIgnoreCase(name) && c.owner.equals(owner)) return c;
        }
        // 如果没找到 owner's，尝试任意匹配（用于站在领地内的情况）
        for (Claim c : claims.values()) if (c.name.equalsIgnoreCase(name)) return c;
        return null;
    }

    private class ClaimTab implements TabCompleter {
        @Override
        public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
            if (!(s instanceof Player p)) return Collections.emptyList();
            List<String> cmds = Arrays.asList("create","invite","promote","kick","ban","unban","remove","rename","setmsg","sell","buy","tp","setspawn","list","map","shop");
            if (a.length == 1) return cmds.stream().filter(x -> x.startsWith(a[0].toLowerCase())).collect(Collectors.toList());
            if (a.length == 2 && (a[0].equalsIgnoreCase("invite") || a[0].equalsIgnoreCase("promote") || a[0].equalsIgnoreCase("kick") || a[0].equalsIgnoreCase("ban") || a[0].equalsIgnoreCase("unban"))) {
                return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(x -> x.toLowerCase().startsWith(a[1].toLowerCase())).collect(Collectors.toList());
            }
            if (a.length == 2 && (a[0].equalsIgnoreCase("remove") || a[0].equalsIgnoreCase("rename") || a[0].equalsIgnoreCase("sell") || a[0].equalsIgnoreCase("tp"))) {
                return getPlayerClaims(p.getUniqueId()).stream().map(cl -> cl.name).filter(x -> x.toLowerCase().startsWith(a[1].toLowerCase())).collect(Collectors.toList());
            }
            if (a.length == 2 && a[0].equalsIgnoreCase("buy")) {
                return claims.values().stream().filter(cl -> cl.price > 0).map(cl -> cl.name).filter(x -> x.toLowerCase().startsWith(a[1].toLowerCase())).collect(Collectors.toList());
            }
            if (a.length == 2 && a[0].equalsIgnoreCase("setmsg")) {
                return Arrays.asList("enter","leave").stream().filter(x -> x.startsWith(a[1].toLowerCase())).collect(Collectors.toList());
            }
            if (a.length == 3 && a[0].equalsIgnoreCase("setmsg")) {
                return getPlayerClaims(p.getUniqueId()).stream().map(cl -> cl.name).filter(x -> x.toLowerCase().startsWith(a[2].toLowerCase())).collect(Collectors.toList());
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

    private void fillGlass(Inventory inv, int from, int to) {
        ItemStack glass = createItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = from; i < to && i < inv.getSize(); i++) if (inv.getItem(i) == null) inv.setItem(i, glass);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        selections.remove(id); lastClaim.remove(id); currentClaim.remove(id); lastParticle.remove(id); mapMode.remove(id);
    }
}
