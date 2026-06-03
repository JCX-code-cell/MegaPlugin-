package com.megaplugin.module;

import com.megaplugin.MegaPlugin;
import com.megaplugin.util.DataFile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
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

import java.security.MessageDigest;
import java.util.*;

public class AuthModule extends MegaModule {

    private final DataFile authData;
    private final Map<UUID, String> passwords = new HashMap<>();
    private final Set<UUID> loggedIn = new HashSet<>();
    private final Map<UUID, Integer> loginAttempts = new HashMap<>();
    private final Map<UUID, Long> pendingLogin = new HashMap<>();
    private final Map<UUID, String> regStep1 = new HashMap<>(); // store first password during registration
    private static final int LOGIN_TIMEOUT = 60;
    private static final int MAX_ATTEMPTS = 5;
    private static final String SALT = "MegaAuth2024!";

    public AuthModule(MegaPlugin plugin) {
        super(plugin);
        authData = new DataFile(plugin, "auth.yml");
    }

    @Override
    public void onEnable() {
        registerListener();
        plugin.getCommand("register").setExecutor(new RegisterCmd());
        plugin.getCommand("login").setExecutor(new LoginCmd());
        plugin.getCommand("changepassword").setExecutor(new ChangePwdCmd());

        // Load saved passwords
        for (String key : authData.getConfig().getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                String hash = authData.getConfig().getString(key);
                if (hash != null) passwords.put(uuid, hash);
            } catch (Exception ignored) {}
        }

        // Timeout checker
        new BukkitRunnable() {
            public void run() {
                long now = System.currentTimeMillis();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (!loggedIn.contains(p.getUniqueId())) {
                        Long t = pendingLogin.get(p.getUniqueId());
                        if (t != null && now - t > LOGIN_TIMEOUT * 1000L) {
                            p.kick(Component.text("§c登录超时！请在 " + LOGIN_TIMEOUT + " 秒内完成登录。", NamedTextColor.RED));
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    @Override
    public void onDisable() {
        passwords.forEach((k, v) -> authData.getConfig().set(k.toString(), v));
        authData.save();
        loggedIn.clear();
        regStep1.clear();
    }

    public boolean isLoggedIn(Player p) { return loggedIn.contains(p.getUniqueId()); }

    private String hash(String pw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] b = md.digest((pw + SALT).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) { return null; }
    }

    // === Join / Quit ===

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        loggedIn.remove(p.getUniqueId());
        regStep1.remove(p.getUniqueId());
        pendingLogin.put(p.getUniqueId(), System.currentTimeMillis());
        p.setInvulnerable(true);
        p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, Integer.MAX_VALUE, 0, false, false));

        // Hide all players bidirectionally
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Player o : Bukkit.getOnlinePlayers()) {
                if (o.equals(p)) continue;
                p.hidePlayer(plugin, o);
                o.hidePlayer(plugin, p);
            }
        }, 8L);

        // Show login/register prompt
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!loggedIn.contains(p.getUniqueId())) {
                if (passwords.containsKey(p.getUniqueId())) {
                    sendLoginPrompt(p);
                } else {
                    sendRegisterPrompt(p);
                }
            }
        }, 15L);
    }

    private void sendLoginPrompt(Player p) {
        p.sendMessage("");
        p.sendMessage(com.megaplugin.util.Color.colorize("   §8§m                  §r §8[ §6§l登录验证 §8] §8§m                  "));
        p.sendMessage("");
        p.sendMessage(com.megaplugin.util.Color.colorize("   §7欢迎回来，§e" + p.getName() + "§7！请输入密码登录。"));
        p.sendMessage("");
        p.sendMessage(com.megaplugin.util.Color.colorize("   §7方式一：§f在聊天框直接输入密码"));
        p.sendMessage(com.megaplugin.util.Color.colorize("   §7方式二：§f使用命令 §e/login <密码>"));
        p.sendMessage("");
        p.sendMessage(com.megaplugin.util.Color.colorize("   §7剩余时间：§e" + LOGIN_TIMEOUT + "秒 §7| 最大尝试：§e" + MAX_ATTEMPTS + "次"));
        p.sendMessage(com.megaplugin.util.Color.colorize("   §8§m                                                    "));
        p.sendMessage("");
    }

    private void sendRegisterPrompt(Player p) {
        p.sendMessage("");
        p.sendMessage(com.megaplugin.util.Color.colorize("   §8§m                  §r §8[ §a§l注册账号 §8] §8§m                  "));
        p.sendMessage("");
        p.sendMessage(com.megaplugin.util.Color.colorize("   §7欢迎首次来到服务器，§e" + p.getName() + "§7！"));
        p.sendMessage("");
        p.sendMessage(com.megaplugin.util.Color.colorize("   §7方式一：§f在聊天框输入 §e<密码> <确认密码>"));
        p.sendMessage(com.megaplugin.util.Color.colorize("   §7方式二：§f使用命令 §e/register <密码> <确认密码>"));
        p.sendMessage(com.megaplugin.util.Color.colorize("   §7密码至少 §e4 §7个字符"));
        p.sendMessage("");
        p.sendMessage(com.megaplugin.util.Color.colorize("   §8§m                                                    "));
        p.sendMessage("");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        loggedIn.remove(id);
        pendingLogin.remove(id);
        loginAttempts.remove(id);
        regStep1.remove(id);
    }

    // === Chat-based auth (player types password in chat) ===

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        if (loggedIn.contains(p.getUniqueId())) return;

        e.setCancelled(true);
        String msg = e.getMessage().trim();
        if (msg.isEmpty()) return;

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (loggedIn.contains(p.getUniqueId())) return; // already logged in via command

            if (passwords.containsKey(p.getUniqueId())) {
                // LOGIN via chat
                handleLogin(p, msg);
            } else {
                // REGISTER via chat: expect "password password"
                String[] parts = msg.split("\\s+", 2);
                if (parts.length >= 2) {
                    handleRegister(p, parts[0], parts[1]);
                } else if (regStep1.containsKey(p.getUniqueId())) {
                    // Second step of registration
                    handleRegisterStep2(p, msg);
                } else {
                    p.sendMessage(com.megaplugin.util.Color.colorize(msg("prefix") + " §7注册请在聊天中输入: §e<密码> <确认密码>"));
                    p.sendMessage(com.megaplugin.util.Color.colorize(msg("prefix") + " §7或使用命令: §e/register <密码> <确认密码>"));
                }
            }
        });
    }

    // === Command preprocess: allow only auth commands ===

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCmd(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (loggedIn.contains(p.getUniqueId())) return;
        String c = e.getMessage().split(" ")[0].toLowerCase();
        if (!c.equals("/login") && !c.equals("/register") && !c.equals("/l") && !c.equals("/reg")) {
            e.setCancelled(true);
            p.sendMessage(com.megaplugin.util.Color.colorize(msg("prefix") + " §c请先完成登录！"));
        }
    }

    // === Frozen state handlers ===

    @EventHandler(priority = EventPriority.LOWEST) public void onMove(PlayerMoveEvent e) {
        if (!loggedIn.contains(e.getPlayer().getUniqueId()) && moved(e)) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.LOWEST) public void onInteract(PlayerInteractEvent e) {
        if (!loggedIn.contains(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.LOWEST) public void onInvOpen(InventoryOpenEvent e) {
        if (e.getPlayer() instanceof Player p && !loggedIn.contains(p.getUniqueId())) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.LOWEST) public void onInvClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p && !loggedIn.contains(p.getUniqueId())) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.LOWEST) public void onDrop(PlayerDropItemEvent e) {
        if (!loggedIn.contains(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.LOWEST) public void onPickup(PlayerAttemptPickupItemEvent e) {
        if (!loggedIn.contains(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.LOWEST) public void onBreak(BlockBreakEvent e) {
        if (!loggedIn.contains(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.LOWEST) public void onPlace(BlockPlaceEvent e) {
        if (!loggedIn.contains(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.LOWEST) public void onDmg(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && !loggedIn.contains(p.getUniqueId())) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.LOWEST) public void onDmg2(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p && !loggedIn.contains(p.getUniqueId())) e.setCancelled(true);
    }

    private boolean moved(PlayerMoveEvent e) {
        return e.getFrom().getX() != e.getTo().getX() || e.getFrom().getY() != e.getTo().getY() || e.getFrom().getZ() != e.getTo().getZ();
    }

    // === Login / Register logic ===

    private void handleLogin(Player p, String input) {
        int tries = loginAttempts.getOrDefault(p.getUniqueId(), 0);
        if (tries >= MAX_ATTEMPTS) {
            p.kick(Component.text("§c登录失败次数过多，请重新加入。", NamedTextColor.RED));
            return;
        }
        String h = hash(input);
        if (h != null && h.equals(passwords.get(p.getUniqueId()))) {
            loginPlayer(p);
            p.sendMessage(com.megaplugin.util.Color.colorize(msg("prefix") + " §a登录成功！欢迎回来 §e" + p.getName()));
        } else {
            loginAttempts.merge(p.getUniqueId(), 1, Integer::sum);
            int rem = MAX_ATTEMPTS - loginAttempts.get(p.getUniqueId());
            p.sendMessage(com.megaplugin.util.Color.colorize(msg("prefix") + " §c密码错误！剩余 §e" + rem + " §c次机会"));
        }
    }

    private void handleRegister(Player p, String pw1, String pw2) {
        regStep1.remove(p.getUniqueId()); // clear any pending step
        if (pw1.length() < 4) {
            p.sendMessage(com.megaplugin.util.Color.colorize(msg("prefix") + " §c密码至少需要4个字符！"));
            return;
        }
        if (!pw1.equals(pw2)) {
            p.sendMessage(com.megaplugin.util.Color.colorize(msg("prefix") + " §c两次密码不一致！"));
            return;
        }
        passwords.put(p.getUniqueId(), hash(pw1));
        authData.getConfig().set(p.getUniqueId().toString(), hash(pw1));
        authData.save();
        loginPlayer(p);
        p.sendMessage(com.megaplugin.util.Color.colorize(msg("prefix") + " §a注册成功！欢迎 §e" + p.getName() + " §a加入服务器！"));
    }

    private void handleRegisterStep2(Player p, String confirm) {
        String pw1 = regStep1.remove(p.getUniqueId());
        if (pw1 == null) {
            p.sendMessage(com.megaplugin.util.Color.colorize(msg("prefix") + " §c注册超时，请重新输入: §e<密码> <确认密码>"));
            return;
        }
        if (!pw1.equals(confirm)) {
            p.sendMessage(com.megaplugin.util.Color.colorize(msg("prefix") + " §c两次密码不一致！请重新输入: §e<密码> <确认密码>"));
            return;
        }
        passwords.put(p.getUniqueId(), hash(pw1));
        authData.getConfig().set(p.getUniqueId().toString(), hash(pw1));
        authData.save();
        loginPlayer(p);
        p.sendMessage(com.megaplugin.util.Color.colorize(msg("prefix") + " §a注册成功！欢迎 §e" + p.getName() + " §a加入服务器！"));
    }

    private void loginPlayer(Player p) {
        loggedIn.add(p.getUniqueId());
        pendingLogin.remove(p.getUniqueId());
        loginAttempts.remove(p.getUniqueId());
        regStep1.remove(p.getUniqueId());
        p.setInvulnerable(false);
        p.removePotionEffect(PotionEffectType.BLINDNESS);
        // Restore visibility
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Player o : Bukkit.getOnlinePlayers()) {
                p.showPlayer(plugin, o);
                o.showPlayer(plugin, p);
            }
        });
    }

    // === Commands ===

    private class RegisterCmd implements CommandExecutor {
        public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!(s instanceof Player p)) { s.sendMessage(msg("player-only")); return true; }
            if (passwords.containsKey(p.getUniqueId())) { p.sendMessage(msg("prefix") + " §c你已经注册过了！"); return true; }
            if (loggedIn.contains(p.getUniqueId())) { p.sendMessage(msg("prefix") + " §a你已经登录了！"); return true; }
            if (a.length < 2) {
                p.sendMessage(msg("prefix") + " §c用法: /register <密码> <确认密码>");
                p.sendMessage(msg("prefix") + " §7或直接在聊天中输入: §e<密码> <确认密码>");
                return true;
            }
            handleRegister(p, a[0], a[1]);
            return true;
        }
    }

    private class LoginCmd implements CommandExecutor {
        public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!(s instanceof Player p)) { s.sendMessage(msg("player-only")); return true; }
            if (loggedIn.contains(p.getUniqueId())) { p.sendMessage(msg("prefix") + " §a你已经登录了！"); return true; }
            if (!passwords.containsKey(p.getUniqueId())) { p.sendMessage(msg("prefix") + " §c你还没有注册！"); return true; }
            if (a.length < 1) {
                p.sendMessage(msg("prefix") + " §c用法: /login <密码>");
                p.sendMessage(msg("prefix") + " §7或直接在聊天中输入密码");
                return true;
            }
            handleLogin(p, a[0]);
            return true;
        }
    }

    private class ChangePwdCmd implements CommandExecutor {
        public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!(s instanceof Player p)) { s.sendMessage(msg("player-only")); return true; }
            if (!loggedIn.contains(p.getUniqueId())) { p.sendMessage(msg("prefix") + " §c请先登录！"); return true; }
            if (!passwords.containsKey(p.getUniqueId())) { p.sendMessage(msg("prefix") + " §c你还没有注册！"); return true; }
            if (a.length < 2) { p.sendMessage(msg("prefix") + " §c用法: /changepassword <旧密码> <新密码>"); return true; }
            if (!hash(a[0]).equals(passwords.get(p.getUniqueId()))) { p.sendMessage(msg("prefix") + " §c旧密码错误！"); return true; }
            if (a[1].length() < 4) { p.sendMessage(msg("prefix") + " §c新密码至少需要4个字符！"); return true; }
            passwords.put(p.getUniqueId(), hash(a[1]));
            authData.getConfig().set(p.getUniqueId().toString(), hash(a[1]));
            authData.save();
            p.sendMessage(msg("prefix") + " §a密码修改成功！");
            return true;
        }
    }
}
