package com.megaplugin.module;

import com.megaplugin.MegaPlugin;
import com.megaplugin.util.DataFile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.security.MessageDigest;
import java.util.*;

public class AuthModule extends MegaModule {

    private final DataFile authData;
    private final Map<UUID, String> passwords = new HashMap<>();
    private final Set<UUID> loggedIn = new HashSet<>();
    private final Map<UUID, Integer> loginAttempts = new HashMap<>();
    private final Map<UUID, Long> pendingLogin = new HashMap<>();
    private final Map<UUID, String> regTempPassword = new HashMap<>(); // step1 password
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
        var regCmd = plugin.getCommand("register");
        if (regCmd != null) regCmd.setExecutor(new RegisterCmd());
        var loginCmd = plugin.getCommand("login");
        if (loginCmd != null) loginCmd.setExecutor(new LoginCmd());
        var cpCmd = plugin.getCommand("changepassword");
        if (cpCmd != null) cpCmd.setExecutor(new ChangePwdCmd());

        for (String key : authData.getConfig().getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                String hash = authData.getConfig().getString(key);
                if (hash != null) passwords.put(uuid, hash);
            } catch (Exception ignored) {}
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (!loggedIn.contains(p.getUniqueId())) {
                        Long joinTime = pendingLogin.get(p.getUniqueId());
                        if (joinTime != null && (now - joinTime) > LOGIN_TIMEOUT * 1000L) {
                            p.kick(Component.text("§c登录超时！请在 " + LOGIN_TIMEOUT + " 秒内注册或登录。", NamedTextColor.RED));
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    @Override
    public void onDisable() {
        for (var entry : passwords.entrySet()) {
            authData.getConfig().set(entry.getKey().toString(), entry.getValue());
        }
        authData.save();
        loggedIn.clear();
        regTempPassword.clear();
    }

    public boolean isLoggedIn(Player player) {
        return loggedIn.contains(player.getUniqueId());
    }

    private String hashPassword(String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest((password + SALT).getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private void openLoginGui(Player p) {
        Inventory inv = Bukkit.createInventory(null, InventoryType.ANVIL, "§8🔒 请输入密码登录");
        inv.setItem(0, createPlaceholder("§7输入密码后点击右侧"));
        p.openInventory(inv);
    }

    private void openRegisterGui(Player p) {
        Inventory inv = Bukkit.createInventory(null, InventoryType.ANVIL, "§8🔑 请设置密码(4位以上)");
        inv.setItem(0, createPlaceholder("§7输入密码后点击右侧"));
        p.openInventory(inv);
    }

    private void openConfirmGui(Player p) {
        Inventory inv = Bukkit.createInventory(null, InventoryType.ANVIL, "§8🔐 请再次输入密码确认");
        inv.setItem(0, createPlaceholder("§7再次输入相同密码后点击右侧"));
        p.openInventory(inv);
    }

    private ItemStack createPlaceholder(String name) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void handleAnvilSubmit(Player p, String input) {
        if (input == null || input.isEmpty()) return;

        boolean isRegistered = passwords.containsKey(p.getUniqueId());

        if (isRegistered) {
            // --- LOGIN ---
            int attempts = loginAttempts.getOrDefault(p.getUniqueId(), 0);
            if (attempts >= MAX_ATTEMPTS) {
                p.kick(Component.text("§c登录失败次数过多，请重新加入。", NamedTextColor.RED));
                return;
            }
            String inputHash = hashPassword(input);
            if (inputHash != null && inputHash.equals(passwords.get(p.getUniqueId()))) {
                loginPlayer(p);
                p.closeInventory();
                p.sendMessage(msg("prefix") + " §a登录成功！欢迎回来 §e" + p.getName());
            } else {
                loginAttempts.merge(p.getUniqueId(), 1, Integer::sum);
                int remaining = MAX_ATTEMPTS - loginAttempts.get(p.getUniqueId());
                p.sendMessage(msg("prefix") + " §c密码错误！剩余 §e" + remaining + " §c次尝试");
                // Reopen GUI
                Bukkit.getScheduler().runTaskLater(plugin, () -> openLoginGui(p), 2L);
            }
        } else {
            // --- REGISTER ---
            if (!regTempPassword.containsKey(p.getUniqueId())) {
                // Step 1: save entered password, ask for confirmation
                if (input.length() < 4) {
                    p.sendMessage(msg("prefix") + " §c密码至少需要4个字符！");
                    Bukkit.getScheduler().runTaskLater(plugin, () -> openRegisterGui(p), 2L);
                    return;
                }
                regTempPassword.put(p.getUniqueId(), input);
                p.closeInventory();
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    p.sendMessage(msg("prefix") + " §e请再次输入相同的密码以确认");
                    openConfirmGui(p);
                }, 1L);
            } else {
                // Step 2: verify confirmation matches
                String firstPassword = regTempPassword.get(p.getUniqueId());
                regTempPassword.remove(p.getUniqueId());
                if (!input.equals(firstPassword)) {
                    p.sendMessage(msg("prefix") + " §c两次输入的密码不一致！请重新注册。");
                    Bukkit.getScheduler().runTaskLater(plugin, () -> openRegisterGui(p), 2L);
                    return;
                }
                passwords.put(p.getUniqueId(), hashPassword(input));
                authData.getConfig().set(p.getUniqueId().toString(), hashPassword(input));
                authData.save();
                loginPlayer(p);
                p.closeInventory();
                p.sendMessage(msg("prefix") + " §a注册成功！欢迎 §e" + p.getName() + " §a加入服务器！");
            }
        }
    }

    // ========== Anvil GUI Events ==========

    @EventHandler(priority = EventPriority.HIGH)
    public void onAnvilClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (loggedIn.contains(p.getUniqueId())) return;
        if (e.getInventory().getType() != InventoryType.ANVIL) return;

        // Prevent extracting items
        if (e.getRawSlot() != 2) {
            e.setCancelled(true);
            return;
        }

        // Slot 2 = result slot, player clicked to confirm
        AnvilInventory anvil = (AnvilInventory) e.getInventory();
        String input = anvil.getRenameText();
        e.setCancelled(true);

        if (input != null && !input.isEmpty()) {
            handleAnvilSubmit(p, input);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onAnvilClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        if (loggedIn.contains(p.getUniqueId())) return;

        // Reopen the appropriate GUI if player tries to close it
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!loggedIn.contains(p.getUniqueId())) {
                boolean isRegistered = passwords.containsKey(p.getUniqueId());
                if (regTempPassword.containsKey(p.getUniqueId())) {
                    openConfirmGui(p);
                } else if (isRegistered) {
                    openLoginGui(p);
                } else {
                    openRegisterGui(p);
                }
            }
        }, 2L);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onAnvilDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (loggedIn.contains(p.getUniqueId())) return;
        if (e.getInventory().getType() == InventoryType.ANVIL) e.setCancelled(true);
    }

    // ========== Other Event Handlers ==========

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        loggedIn.remove(p.getUniqueId());
        pendingLogin.put(p.getUniqueId(), System.currentTimeMillis());
        p.setInvulnerable(true);
        p.setAllowFlight(false);
        p.setFlySpeed(0.1f);

        p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, Integer.MAX_VALUE, 0, false, false));

        // Hide from all
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Player other : Bukkit.getOnlinePlayers()) {
                if (other.equals(p)) continue;
                p.hidePlayer(plugin, other);
                other.hidePlayer(plugin, p);
            }
        }, 5L);

        // Open GUI after short delay
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!loggedIn.contains(p.getUniqueId())) {
                if (passwords.containsKey(p.getUniqueId())) {
                    openLoginGui(p);
                } else {
                    openRegisterGui(p);
                }
            }
        }, 10L);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        loggedIn.remove(id);
        pendingLogin.remove(id);
        loginAttempts.remove(id);
        regTempPassword.remove(id);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMove(PlayerMoveEvent e) {
        if (!loggedIn.contains(e.getPlayer().getUniqueId())) {
            if (e.getFrom().getX() != e.getTo().getX() ||
                e.getFrom().getY() != e.getTo().getY() ||
                e.getFrom().getZ() != e.getTo().getZ()) {
                e.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        if (!loggedIn.contains(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (!loggedIn.contains(p.getUniqueId())) {
            String cmd = e.getMessage().split(" ")[0].toLowerCase();
            if (!cmd.equals("/login") && !cmd.equals("/register") && !cmd.equals("/l") && !cmd.equals("/reg")) {
                e.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent e) {
        if (!loggedIn.contains(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryOpen(InventoryOpenEvent e) {
        if (e.getPlayer() instanceof Player p && e.getInventory().getType() != InventoryType.ANVIL
            && !loggedIn.contains(p.getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p && e.getInventory().getType() != InventoryType.ANVIL
            && !loggedIn.contains(p.getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent e) {
        if (!loggedIn.contains(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickup(PlayerAttemptPickupItemEvent e) {
        if (!loggedIn.contains(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockBreak(BlockBreakEvent e) {
        if (!loggedIn.contains(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockPlace(BlockPlaceEvent e) {
        if (!loggedIn.contains(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && !loggedIn.contains(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamageOther(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p && !loggedIn.contains(p.getUniqueId())) e.setCancelled(true);
    }

    // ========== Commands ==========

    private class RegisterCmd implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (!(sender instanceof Player p)) { sender.sendMessage(msg("player-only")); return true; }
            if (passwords.containsKey(p.getUniqueId())) {
                p.sendMessage(msg("prefix") + " §c你已经注册过了！");
                return true;
            }
            if (args.length < 2) {
                openRegisterGui(p);
                return true;
            }
            if (args[0].length() < 4) {
                p.sendMessage(msg("prefix") + " §c密码至少需要4个字符！");
                return true;
            }
            if (!args[0].equals(args[1])) {
                p.sendMessage(msg("prefix") + " §c两次输入的密码不一致！");
                return true;
            }
            passwords.put(p.getUniqueId(), hashPassword(args[0]));
            authData.getConfig().set(p.getUniqueId().toString(), hashPassword(args[0]));
            authData.save();
            loginPlayer(p);
            p.closeInventory();
            p.sendMessage(msg("prefix") + " §a注册成功！欢迎 §e" + p.getName() + " §a加入服务器！");
            return true;
        }
    }

    private class LoginCmd implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (!(sender instanceof Player p)) { sender.sendMessage(msg("player-only")); return true; }
            if (loggedIn.contains(p.getUniqueId())) {
                p.sendMessage(msg("prefix") + " §a你已经登录了！");
                return true;
            }
            if (!passwords.containsKey(p.getUniqueId())) {
                p.sendMessage(msg("prefix") + " §c你还没有注册！");
                return true;
            }
            if (args.length < 1) {
                openLoginGui(p);
                return true;
            }
            handleAnvilSubmit(p, args[0]);
            return true;
        }
    }

    private class ChangePwdCmd implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (!(sender instanceof Player p)) { sender.sendMessage(msg("player-only")); return true; }
            if (!loggedIn.contains(p.getUniqueId())) {
                p.sendMessage(msg("prefix") + " §c请先登录！");
                return true;
            }
            if (!passwords.containsKey(p.getUniqueId())) {
                p.sendMessage(msg("prefix") + " §c你还没有注册！");
                return true;
            }
            if (args.length < 2) {
                p.sendMessage(msg("prefix") + " §c用法: /changepassword <旧密码> <新密码>");
                return true;
            }
            if (!hashPassword(args[0]).equals(passwords.get(p.getUniqueId()))) {
                p.sendMessage(msg("prefix") + " §c旧密码错误！");
                return true;
            }
            if (args[1].length() < 4) {
                p.sendMessage(msg("prefix") + " §c新密码至少需要4个字符！");
                return true;
            }
            passwords.put(p.getUniqueId(), hashPassword(args[1]));
            authData.getConfig().set(p.getUniqueId().toString(), hashPassword(args[1]));
            authData.save();
            p.sendMessage(msg("prefix") + " §a密码修改成功！");
            return true;
        }
    }

    private void loginPlayer(Player p) {
        loggedIn.add(p.getUniqueId());
        pendingLogin.remove(p.getUniqueId());
        loginAttempts.remove(p.getUniqueId());
        p.setInvulnerable(false);
        p.removePotionEffect(PotionEffectType.BLINDNESS);
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Player online : Bukkit.getOnlinePlayers()) {
                p.showPlayer(plugin, online);
                online.showPlayer(plugin, p);
            }
        });
    }
}
