package com.megaplugin.module;

import com.megaplugin.MegaPlugin;
import com.megaplugin.util.Color;
import com.megaplugin.util.DataFile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 传送模块 — /tpa /tpahere /tpaccept /tpdeny /tp /tphere /tpo /back
 */
public class TeleportModule extends MegaModule {

    private final Map<UUID, TeleportRequest> requests = new ConcurrentHashMap<>();
    private final Map<UUID, Location> lastLocation = new ConcurrentHashMap<>();
    private final DataFile data = new DataFile(plugin, "tp_data.yml");

    public TeleportModule(MegaPlugin plugin) { super(plugin); }

    @Override
    public void onEnable() {
        listen();
        cmd("tpa", new TpaCmd());
        cmd("tpahere", new TpahereCmd());
        cmd("tpaccept", new TpacceptCmd());
        cmd("tpdeny", new TpdenyCmd());
        cmd("tp", new TpCmd());
        cmd("tphere", new TphereCmd());
        cmd("tpo", new TpoCmd());
        cmd("back", new BackCmd());

        for (String k : data.getConfig().getKeys(false)) {
            try {
                UUID id = UUID.fromString(k);
                Location loc = data.getConfig().getLocation(k);
                if (loc != null) lastLocation.put(id, loc);
            } catch (Exception ignored) {}
        }

        // 定时自动保存 (每 5 分钟)
        Bukkit.getScheduler().runTaskTimer(plugin, this::saveData, 6000L, 6000L);
    }

    @Override
    public void onDisable() {
        saveData();
        super.onDisable();
    }

    private void saveData() {
        for (var e : lastLocation.entrySet())
            data.getConfig().set(e.getKey().toString(), e.getValue());
        data.save();
    }

    private void cmd(String name, CommandExecutor exe) {
        var c = plugin.getCommand(name);
        if (c != null) {
            c.setExecutor(exe);
            if (exe instanceof TabCompleter t) c.setTabCompleter(t);
        }
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent e) {
        if (e.getCause() == PlayerTeleportEvent.TeleportCause.PLUGIN ||
            e.getCause() == PlayerTeleportEvent.TeleportCause.COMMAND ||
            e.getCause() == PlayerTeleportEvent.TeleportCause.UNKNOWN)
            lastLocation.put(e.getPlayer().getUniqueId(), e.getFrom());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        if (plugin.getConfig().getBoolean("teleport.back-on-death", true))
            lastLocation.put(e.getPlayer().getUniqueId(), e.getPlayer().getLocation());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        requests.remove(id);
        requests.values().removeIf(r -> r.target.equals(id));
    }

    public void clearRequests(Player player) {
        UUID id = player.getUniqueId();
        requests.remove(id);
        requests.values().removeIf(r -> r.target.equals(id));
    }

    private record TeleportRequest(UUID requester, UUID target, boolean here, long expire) {}

    private void sendClickable(Player from, Player to, boolean here) {
        int timeout = plugin.getConfig().getInt("teleport.request-timeout", 60);
        String act = here ? "想让你传送到他身边" : "想要传送到你身边";
        to.sendMessage(Component.text()
                .append(Color.toComponent(Color.colorize(msg("prefix"))))
                .append(Component.text(from.getName(), NamedTextColor.YELLOW))
                .append(Component.text(" " + act + "！ ", NamedTextColor.GREEN))
                .append(Component.text("[接受]", NamedTextColor.GREEN, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand("/tpaccept")))
                .append(Component.text(" [拒绝]", NamedTextColor.RED, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand("/tpdeny")))
                .append(Component.text(" (" + timeout + "秒)", NamedTextColor.DARK_GRAY))
                .build());
    }

    // ── 命令内部类 ──
    class TpaCmd implements CommandExecutor, TabCompleter {
        public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!(s instanceof Player p)) { s.sendMessage(msg("player-only")); return true; }
            if (!p.hasPermission("megaplugin.tpa")) { p.sendMessage(msg("no-permission")); return true; }
            if (a.length == 0) { p.sendMessage(msg("prefix") + " §c用法: /tpa <玩家>"); return true; }
            Player t = plugin.getServer().getPlayer(a[0]);
            if (t == null || t == p) { p.sendMessage(msg("prefix") + " §c目标无效！"); return true; }
            int timeout = plugin.getConfig().getInt("teleport.request-timeout", 60);
            requests.put(t.getUniqueId(), new TeleportRequest(p.getUniqueId(), t.getUniqueId(), false, System.currentTimeMillis() + timeout * 1000L));
            p.sendMessage(msg("prefix") + " §a请求已发送给 §e" + t.getName());
            sendClickable(p, t, false);
            return true;
        }
        public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
            return tabPlayers(s, a);
        }
    }

    class TpahereCmd implements CommandExecutor, TabCompleter {
        public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!(s instanceof Player p)) { s.sendMessage(msg("player-only")); return true; }
            if (!p.hasPermission("megaplugin.tpa")) { p.sendMessage(msg("no-permission")); return true; }
            if (a.length == 0) { p.sendMessage(msg("prefix") + " §c用法: /tpahere <玩家>"); return true; }
            Player t = plugin.getServer().getPlayer(a[0]);
            if (t == null || t == p) { p.sendMessage(msg("prefix") + " §c目标无效！"); return true; }
            int timeout = plugin.getConfig().getInt("teleport.request-timeout", 60);
            requests.put(t.getUniqueId(), new TeleportRequest(p.getUniqueId(), t.getUniqueId(), true, System.currentTimeMillis() + timeout * 1000L));
            p.sendMessage(msg("prefix") + " §a请求已发送给 §e" + t.getName());
            sendClickable(p, t, true);
            return true;
        }
        public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
            return tabPlayers(s, a);
        }
    }

    class TpacceptCmd implements CommandExecutor {
        public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!(s instanceof Player p)) { s.sendMessage(msg("player-only")); return true; }
            var req = requests.remove(p.getUniqueId());
            if (req == null || System.currentTimeMillis() > req.expire) {
                p.sendMessage(msg("prefix") + " §c没有待处理的请求！"); return true;
            }
            Player r = plugin.getServer().getPlayer(req.requester);
            if (r == null) { p.sendMessage(msg("prefix") + " §c请求者已离线！"); return true; }
            if (req.here) { p.teleport(r); p.sendMessage(msg("prefix") + " §a已传送到 §e" + r.getName()); }
            else { r.teleport(p); r.sendMessage(msg("prefix") + " §a已传送到 §e" + p.getName()); }
            return true;
        }
    }

    class TpdenyCmd implements CommandExecutor {
        public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!(s instanceof Player p)) { s.sendMessage(msg("player-only")); return true; }
            var req = requests.remove(p.getUniqueId());
            if (req == null) { p.sendMessage(msg("prefix") + " §c没有待处理的请求！"); return true; }
            Player r = plugin.getServer().getPlayer(req.requester);
            if (r != null) r.sendMessage(msg("prefix") + " §c" + p.getName() + " 拒绝了你的请求。");
            p.sendMessage(msg("prefix") + " §c已拒绝。");
            return true;
        }
    }

    class TpCmd implements CommandExecutor, TabCompleter {
        public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!(s instanceof Player p)) { s.sendMessage(msg("player-only")); return true; }
            if (!p.hasPermission("megaplugin.tp")) { p.sendMessage(msg("no-permission")); return true; }
            if (a.length == 0) { p.sendMessage(msg("prefix") + " §c用法: /tp <玩家>"); return true; }
            Player t = plugin.getServer().getPlayer(a[0]);
            if (t == null) { p.sendMessage(msg("player-not-found")); return true; }
            p.teleport(t);
            return true;
        }
        public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
            return tabPlayers(s, a);
        }
    }

    class TphereCmd implements CommandExecutor, TabCompleter {
        public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!(s instanceof Player p)) { s.sendMessage(msg("player-only")); return true; }
            if (!p.hasPermission("megaplugin.tp")) { p.sendMessage(msg("no-permission")); return true; }
            if (a.length == 0) { p.sendMessage(msg("prefix") + " §c用法: /tphere <玩家>"); return true; }
            Player t = plugin.getServer().getPlayer(a[0]);
            if (t == null) { p.sendMessage(msg("player-not-found")); return true; }
            t.teleport(p);
            p.sendMessage(msg("prefix") + " §a已将 §e" + t.getName() + " §a传送过来");
            return true;
        }
        public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
            return tabPlayers(s, a);
        }
    }

    class TpoCmd implements CommandExecutor, TabCompleter {
        public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!(s instanceof Player p)) { s.sendMessage(msg("player-only")); return true; }
            if (!p.hasPermission("megaplugin.tp")) { p.sendMessage(msg("no-permission")); return true; }
            if (a.length == 0) { p.sendMessage(msg("prefix") + " §c用法: /tpo <玩家>"); return true; }
            Player t = plugin.getServer().getPlayer(a[0]);
            if (t == null) { p.sendMessage(msg("player-not-found")); return true; }
            p.teleport(t);
            return true;
        }
        public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
            return tabPlayers(s, a);
        }
    }

    class BackCmd implements CommandExecutor {
        public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!(s instanceof Player p)) { s.sendMessage(msg("player-only")); return true; }
            if (!p.hasPermission("megaplugin.back")) { p.sendMessage(msg("no-permission")); return true; }
            Location loc = lastLocation.get(p.getUniqueId());
            if (loc == null) { p.sendMessage(msg("prefix") + " §c没有可返回的位置！"); return true; }
            p.teleport(loc);
            p.sendMessage(msg("prefix") + " §a已返回！");
            return true;
        }
    }

    private List<String> tabPlayers(CommandSender s, String[] a) {
        if (a.length == 1)
            return plugin.getServer().getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(n -> n.toLowerCase().startsWith(a[0].toLowerCase()))
                    .toList();
        return List.of();
    }
}
