package com.megaplugin.module;

import com.megaplugin.MegaPlugin;
import com.megaplugin.util.Color;
import com.megaplugin.util.DataFile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.potion.*;
import org.bukkit.scheduler.BukkitRunnable;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;

/**
 * 认证模块 — 登录/注册/修改密码 + 冻结未登录玩家
 * 密码存储: PBKDF2WithHmacSHA256, 每用户独立 16 字节随机盐
 */
public class AuthModule extends MegaModule {

    private final DataFile data = new DataFile(plugin, "auth.yml");
    private final Map<UUID, String> passwords = new HashMap<>();
    private final Set<UUID> loggedIn = new HashSet<>();
    private final Map<UUID, Integer> loginAttempts = new HashMap<>();
    private final Map<UUID, Long> pendingLogin = new HashMap<>();
    private final Map<UUID, String> regStep1 = new HashMap<>();

    private static final int TIMEOUT = 60, MAX_ATTEMPTS = 5;
    private static final int PBKDF2_ITER = 120000; // OWASP 2023 推荐 ≥ 600k, 但兼顾性能取 120k
    private static final int SALT_LEN = 16;
    private static final SecureRandom RNG = new SecureRandom();

    public AuthModule(MegaPlugin plugin) { super(plugin); }

    @Override
    public void onEnable() {
        listen();
        plugin.getCommand("register").setExecutor(new RegisterCmd());
        plugin.getCommand("login").setExecutor(new LoginCmd());
        plugin.getCommand("changepassword").setExecutor(new ChangePwdCmd());

        for (String k : data.getConfig().getKeys(false)) {
            try {
                UUID id = UUID.fromString(k);
                String h = data.getConfig().getString(k);
                if (h != null) passwords.put(id, h);
            } catch (Exception ignored) {}
        }

        new BukkitRunnable() {
            public void run() {
                long now = System.currentTimeMillis();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (!loggedIn.contains(p.getUniqueId())) {
                        Long t = pendingLogin.get(p.getUniqueId());
                        if (t != null && now - t > TIMEOUT * 1000L)
                            p.kick(Component.text("§c登录超时！", NamedTextColor.RED));
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    @Override
    public void onDisable() {
        for (var e : passwords.entrySet()) data.getConfig().set(e.getKey().toString(), e.getValue());
        data.save();
        loggedIn.clear();
        regStep1.clear();
        super.onDisable();
    }

    public boolean isLoggedIn(Player p) { return loggedIn.contains(p.getUniqueId()); }

    /**
     * PBKDF2WithHmacSHA256, 每用户随机盐
     * 存储格式: hex_salt:hex_hash
     */
    private String hash(String pw) {
        try {
            byte[] salt = new byte[SALT_LEN];
            RNG.nextBytes(salt);
            var spec = new PBEKeySpec(pw.toCharArray(), salt, PBKDF2_ITER, 256);
            var factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] hash = factory.generateSecret(spec).getEncoded();
            return bytesToHex(salt) + ":" + bytesToHex(hash);
        } catch (Exception e) { return null; }
    }

    private boolean verify(String pw, String stored) {
        try {
            if (stored == null || !stored.contains(":")) return false;
            String[] parts = stored.split(":", 2);
            byte[] salt = hexToBytes(parts[0]);
            byte[] expected = hexToBytes(parts[1]);
            var spec = new PBEKeySpec(pw.toCharArray(), salt, PBKDF2_ITER, 256);
            var factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] actual = factory.generateSecret(spec).getEncoded();
            return MessageDigest.isEqual(actual, expected);
        } catch (Exception e) { return false; }
    }

    private static String bytesToHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2)
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                                 + Character.digit(hex.charAt(i + 1), 16));
        return data;
    }

    // ── Join / Quit ──
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        loggedIn.remove(p.getUniqueId());
        regStep1.remove(p.getUniqueId());
        pendingLogin.put(p.getUniqueId(), System.currentTimeMillis());
        p.setInvulnerable(true);
        p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, Integer.MAX_VALUE, 0, false, false));

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Player o : Bukkit.getOnlinePlayers())
                if (!o.equals(p)) { p.hidePlayer(plugin, o); o.hidePlayer(plugin, p); }
        }, 8L);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!loggedIn.contains(p.getUniqueId())) {
                if (passwords.containsKey(p.getUniqueId())) sendLogin(p);
                else sendRegister(p);
            }
        }, 15L);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        loggedIn.remove(id); pendingLogin.remove(id);
        loginAttempts.remove(id); regStep1.remove(id);
    }

    private void sendLogin(Player p) {
        p.sendMessage(Color.colorize("   §8§m                  §r §8[ §6§l登录验证 §8] §8§m                  "));
        p.sendMessage(Color.colorize("   §7欢迎回来，§e" + p.getName() + "§7！请输入密码。"));
        p.sendMessage(Color.colorize("   §7方式一：§f聊天框直接输入 §7方式二：§e/login <密码>"));
        p.sendMessage(Color.colorize("   §7剩余 §e" + TIMEOUT + "秒 §7| 最多 §e" + MAX_ATTEMPTS + "次"));
    }

    private void sendRegister(Player p) {
        p.sendMessage(Color.colorize("   §8§m                  §r §8[ §a§l注册账号 §8] §8§m                  "));
        p.sendMessage(Color.colorize("   §7欢迎首次来到服务器，§e" + p.getName() + "§7！"));
        p.sendMessage(Color.colorize("   §7聊天框输入 §e<密码> <确认密码> §7或 §e/register <密码> <确认密码>"));
        p.sendMessage(Color.colorize("   §7密码至少 §e4 §7个字符"));
    }

    // ── Chat auth ──
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        if (loggedIn.contains(p.getUniqueId())) return;
        e.setCancelled(true);
        String msg = e.getMessage().trim();
        if (msg.isEmpty()) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (loggedIn.contains(p.getUniqueId())) return;
            if (passwords.containsKey(p.getUniqueId())) doLogin(p, msg);
            else {
                String[] parts = msg.split("\\s+", 2);
                if (parts.length >= 2) doRegister(p, parts[0], parts[1]);
                else if (regStep1.containsKey(p.getUniqueId())) doRegisterStep2(p, msg);
                else p.sendMessage(Color.colorize(msg("prefix") + " §7请输入: §e<密码> <确认密码>"));
            }
        });
    }

    // ── Frozen state ──
    @EventHandler(priority = EventPriority.LOWEST) public void onCmd(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (!loggedIn.contains(p.getUniqueId())) {
            String c = e.getMessage().split(" ")[0].toLowerCase();
            if (!c.equals("/login") && !c.equals("/register") && !c.equals("/l") && !c.equals("/reg")) {
                e.setCancelled(true);
                p.sendMessage(Color.colorize(msg("prefix") + " §c请先完成登录！"));
            }
        }
    }
    @EventHandler(priority = EventPriority.LOWEST) public void onMove(PlayerMoveEvent e) {
        if (!loggedIn.contains(e.getPlayer().getUniqueId()) && (e.getFrom().getX() != e.getTo().getX() || e.getFrom().getY() != e.getTo().getY() || e.getFrom().getZ() != e.getTo().getZ())) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.LOWEST) public void onInteract(PlayerInteractEvent e) { if (!loggedIn.contains(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST) public void onInvOpen(InventoryOpenEvent e) { if (e.getPlayer() instanceof Player p && !loggedIn.contains(p.getUniqueId())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST) public void onInvClick(InventoryClickEvent e) { if (e.getWhoClicked() instanceof Player p && !loggedIn.contains(p.getUniqueId())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST) public void onDrop(PlayerDropItemEvent e) { if (!loggedIn.contains(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST) public void onPickup(PlayerAttemptPickupItemEvent e) { if (!loggedIn.contains(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST) public void onBreak(BlockBreakEvent e) { if (!loggedIn.contains(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST) public void onPlace(BlockPlaceEvent e) { if (!loggedIn.contains(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST) public void onDmg(EntityDamageEvent e) { if (e.getEntity() instanceof Player p && !loggedIn.contains(p.getUniqueId())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST) public void onDmg2(EntityDamageByEntityEvent e) { if (e.getDamager() instanceof Player p && !loggedIn.contains(p.getUniqueId())) e.setCancelled(true); }

    // ── Auth logic ──
    private void doLogin(Player p, String input) {
        int tries = loginAttempts.getOrDefault(p.getUniqueId(), 0);
        if (tries >= MAX_ATTEMPTS) { p.kick(Component.text("§c尝试次数过多！", NamedTextColor.RED)); return; }
        if (verify(input, passwords.get(p.getUniqueId()))) {
            loginPlayer(p);
            p.sendMessage(Color.colorize(msg("prefix") + " §a登录成功！欢迎回来 §e" + p.getName()));
        } else {
            loginAttempts.merge(p.getUniqueId(), 1, Integer::sum);
            p.sendMessage(Color.colorize(msg("prefix") + " §c密码错误！剩余 §e" + (MAX_ATTEMPTS - loginAttempts.get(p.getUniqueId())) + "§c 次"));
        }
    }

    private void doRegister(Player p, String pw1, String pw2) {
        regStep1.remove(p.getUniqueId());
        if (pw1.length() < 4) { p.sendMessage(Color.colorize(msg("prefix") + " §c密码至少4个字符！")); return; }
        if (!pw1.equals(pw2)) { p.sendMessage(Color.colorize(msg("prefix") + " §c两次密码不一致！")); return; }
        saveAndLogin(p, pw1, "§a注册成功！欢迎 §e" + p.getName());
    }

    private void doRegisterStep2(Player p, String confirm) {
        String pw1 = regStep1.remove(p.getUniqueId());
        if (pw1 == null) { p.sendMessage(Color.colorize(msg("prefix") + " §c注册超时！")); return; }
        if (!pw1.equals(confirm)) { p.sendMessage(Color.colorize(msg("prefix") + " §c两次密码不一致！")); return; }
        saveAndLogin(p, pw1, "§a注册成功！欢迎 §e" + p.getName());
    }

    private void saveAndLogin(Player p, String pw, String msg) {
        passwords.put(p.getUniqueId(), hash(pw));
        data.getConfig().set(p.getUniqueId().toString(), hash(pw));
        data.save();
        loginPlayer(p);
        p.sendMessage(Color.colorize(msg("prefix") + " " + msg));
    }

    private void loginPlayer(Player p) {
        loggedIn.add(p.getUniqueId());
        pendingLogin.remove(p.getUniqueId());
        loginAttempts.remove(p.getUniqueId());
        regStep1.remove(p.getUniqueId());
        p.setInvulnerable(false);
        p.removePotionEffect(PotionEffectType.BLINDNESS);
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Player o : Bukkit.getOnlinePlayers()) {
                p.showPlayer(plugin, o); o.showPlayer(plugin, p);
            }
        });
    }

    // ── Commands ──
    class RegisterCmd implements CommandExecutor {
        public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!(s instanceof Player p)) { s.sendMessage(msg("player-only")); return true; }
            if (passwords.containsKey(p.getUniqueId())) { p.sendMessage(msg("prefix") + " §c已注册！"); return true; }
            if (loggedIn.contains(p.getUniqueId())) { p.sendMessage(msg("prefix") + " §a已登录！"); return true; }
            if (a.length < 2) { p.sendMessage(msg("prefix") + " §c用法: /register <密码> <确认密码>"); return true; }
            doRegister(p, a[0], a[1]);
            return true;
        }
    }

    class LoginCmd implements CommandExecutor {
        public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!(s instanceof Player p)) { s.sendMessage(msg("player-only")); return true; }
            if (loggedIn.contains(p.getUniqueId())) { p.sendMessage(msg("prefix") + " §a已登录！"); return true; }
            if (!passwords.containsKey(p.getUniqueId())) { p.sendMessage(msg("prefix") + " §c未注册！"); return true; }
            if (a.length < 1) { p.sendMessage(msg("prefix") + " §c用法: /login <密码>"); return true; }
            doLogin(p, a[0]);
            return true;
        }
    }

    class ChangePwdCmd implements CommandExecutor {
        public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!(s instanceof Player p)) { s.sendMessage(msg("player-only")); return true; }
            if (!loggedIn.contains(p.getUniqueId())) { p.sendMessage(msg("prefix") + " §c请先登录！"); return true; }
            if (a.length < 2) { p.sendMessage(msg("prefix") + " §c用法: /changepassword <旧密码> <新密码>"); return true; }
            if (!verify(a[0], passwords.get(p.getUniqueId()))) { p.sendMessage(msg("prefix") + " §c旧密码错误！"); return true; }
            if (a[1].length() < 4) { p.sendMessage(msg("prefix") + " §c新密码至少4个字符！"); return true; }
            passwords.put(p.getUniqueId(), hash(a[1]));
            data.getConfig().set(p.getUniqueId().toString(), hash(a[1]));
            data.save();
            p.sendMessage(msg("prefix") + " §a密码已修改！");
            return true;
        }
    }
}
