package com.megaplugin.module;

import com.megaplugin.MegaPlugin;
import com.megaplugin.util.DataFile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.*;
import java.util.stream.Collectors;

public class TeleportModule extends MegaModule {

    private final Map<UUID, TeleportRequest> requests = new HashMap<>();
    private final Map<UUID, Location> lastLocation = new HashMap<>();
    private final DataFile dataFile;

    public TeleportModule(MegaPlugin plugin) {
        super(plugin);
        dataFile = new DataFile(plugin, "tp_data.yml");
    }

    @Override
    public void onEnable() {
        registerListener();
        register("tpa", new TpaCmd());
        register("tpahere", new TpahereCmd());
        register("tpaccept", new TpacceptCmd());
        register("tpdeny", new TpdenyCmd());
        register("tp", new TpCmd());
        register("tphere", new TphereCmd());
        register("tpo", new TpoCmd());
        register("back", new BackCmd());

        // Restore saved last locations
        for (String key : dataFile.getConfig().getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                Location loc = dataFile.getConfig().getLocation(key);
                if (loc != null) lastLocation.put(uuid, loc);
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void onDisable() {
        for (var entry : lastLocation.entrySet()) {
            dataFile.getConfig().set(entry.getKey().toString(), entry.getValue());
        }
        dataFile.save();
    }

    @EventHandler
    public void onPlayerTeleport(PlayerTeleportEvent e) {
        // Don't save for plugin-internal teleports
        if (e.getCause() == PlayerTeleportEvent.TeleportCause.PLUGIN ||
            e.getCause() == PlayerTeleportEvent.TeleportCause.COMMAND ||
            e.getCause() == PlayerTeleportEvent.TeleportCause.UNKNOWN) {
            lastLocation.put(e.getPlayer().getUniqueId(), e.getFrom());
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        if (plugin.getConfig().getBoolean("teleport.back-on-death", true)) {
            lastLocation.put(e.getPlayer().getUniqueId(), e.getPlayer().getLocation());
        }
    }

    public void clearRequests(Player player) {
        requests.remove(player.getUniqueId());
        requests.entrySet().removeIf(e -> e.getValue().target().equals(player.getUniqueId()));
    }

    @SuppressWarnings("deprecation")
    private void register(String name, CommandExecutor executor) {
        var cmd = plugin.getCommand(name);
        if (cmd != null) {
            cmd.setExecutor(executor);
            if (executor instanceof TabCompleter t) cmd.setTabCompleter(t);
        }
    }

    private record TeleportRequest(UUID requester, UUID target, boolean here, long time) {}

    /** Send clickable accept/deny buttons to the target player */
    private void sendClickableRequest(Player requester, Player target, boolean here, int timeout) {
        String action = here ? "想让你传送到他身边" : "想要传送到你身边";
        Component msg = Component.text()
                .append(Component.text(com.megaplugin.util.Color.colorize(msg("prefix"))))
                .append(Component.text(requester.getName(), NamedTextColor.YELLOW))
                .append(Component.text(" " + action + "！ ", NamedTextColor.GREEN))
                .append(Component.text("  [", NamedTextColor.GRAY))
                .append(Component.text("接受", NamedTextColor.GREEN, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand("/tpaccept")))
                .append(Component.text("]  [", NamedTextColor.GRAY))
                .append(Component.text("拒绝", NamedTextColor.RED, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand("/tpdeny")))
                .append(Component.text("]  ", NamedTextColor.GRAY))
                .append(Component.text("(" + timeout + "秒后过期)", NamedTextColor.DARK_GRAY))
                .build();
        target.sendMessage(msg);
    }

    private class TpaCmd implements CommandExecutor, TabCompleter {
        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (!(sender instanceof Player p)) { sender.sendMessage(msg("player-only")); return true; }
            if (!p.hasPermission("megaplugin.tpa")) { p.sendMessage(msg("no-permission")); return true; }
            if (args.length == 0) { p.sendMessage(msg("prefix") + " §c用法: /tpa <玩家>"); return true; }
            Player target = plugin.getServer().getPlayer(args[0]);
            if (target == null) { p.sendMessage(msg("player-not-found")); return true; }
            if (target.equals(p)) { p.sendMessage(msg("prefix") + " §c你不能传送自己！"); return true; }

            int timeout = plugin.getConfig().getInt("teleport.request-timeout", 60);
            requests.put(target.getUniqueId(), new TeleportRequest(p.getUniqueId(), target.getUniqueId(), false, System.currentTimeMillis() + timeout * 1000L));

            p.sendMessage(msg("prefix") + " §a传送请求已发送给 §e" + target.getName());
            sendClickableRequest(p, target, false, timeout);
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
            if (args.length == 1) {
                return plugin.getServer().getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(n -> n.toLowerCase().startsWith(args[0].toLowerCase()))
                        .collect(Collectors.toList());
            }
            return Collections.emptyList();
        }
    }

    private class TpahereCmd implements CommandExecutor, TabCompleter {
        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (!(sender instanceof Player p)) { sender.sendMessage(msg("player-only")); return true; }
            if (!p.hasPermission("megaplugin.tpa")) { p.sendMessage(msg("no-permission")); return true; }
            if (args.length == 0) { p.sendMessage(msg("prefix") + " §c用法: /tpahere <玩家>"); return true; }
            Player target = plugin.getServer().getPlayer(args[0]);
            if (target == null) { p.sendMessage(msg("player-not-found")); return true; }
            if (target.equals(p)) { p.sendMessage(msg("prefix") + " §c你不能传送自己！"); return true; }

            int timeout = plugin.getConfig().getInt("teleport.request-timeout", 60);
            requests.put(target.getUniqueId(), new TeleportRequest(p.getUniqueId(), target.getUniqueId(), true, System.currentTimeMillis() + timeout * 1000L));

            p.sendMessage(msg("prefix") + " §a传送请求已发送给 §e" + target.getName());
            sendClickableRequest(p, target, true, timeout);
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
            if (args.length == 1) {
                return plugin.getServer().getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(n -> n.toLowerCase().startsWith(args[0].toLowerCase()))
                        .collect(Collectors.toList());
            }
            return Collections.emptyList();
        }
    }

    private class TpacceptCmd implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (!(sender instanceof Player p)) { sender.sendMessage(msg("player-only")); return true; }
            TeleportRequest req = requests.remove(p.getUniqueId());
            if (req == null || req.time() < System.currentTimeMillis()) {
                p.sendMessage(msg("prefix") + " §c没有待处理的传送请求！"); return true;
            }
            Player requester = plugin.getServer().getPlayer(req.requester());
            if (requester == null) { p.sendMessage(msg("prefix") + " §c请求者已离线！"); return true; }

            if (req.here()) {
                p.teleport(requester.getLocation());
                p.sendMessage(msg("prefix") + " §a已传送到 §e" + requester.getName());
                requester.sendMessage(msg("prefix") + " §e" + p.getName() + " §a接受了你的传送请求！");
            } else {
                requester.teleport(p.getLocation());
                requester.sendMessage(msg("prefix") + " §a已传送到 §e" + p.getName());
                p.sendMessage(msg("prefix") + " §a已接受来自 §e" + requester.getName() + " §a的传送");
            }
            return true;
        }
    }

    private class TpdenyCmd implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (!(sender instanceof Player p)) { sender.sendMessage(msg("player-only")); return true; }
            TeleportRequest req = requests.remove(p.getUniqueId());
            if (req == null || req.time() < System.currentTimeMillis()) {
                p.sendMessage(msg("prefix") + " §c没有待处理的传送请求！"); return true;
            }
            Player requester = plugin.getServer().getPlayer(req.requester());
            p.sendMessage(msg("prefix") + " §c传送请求已拒绝。");
            if (requester != null) requester.sendMessage(msg("prefix") + " §c" + p.getName() + " 拒绝了你的传送请求。");
            return true;
        }
    }

    private class TpCmd implements CommandExecutor, TabCompleter {
        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (!(sender instanceof Player p)) { sender.sendMessage(msg("player-only")); return true; }
            if (!p.hasPermission("megaplugin.tp")) { p.sendMessage(msg("no-permission")); return true; }
            if (args.length == 0) { p.sendMessage(msg("prefix") + " §c用法: /tp <玩家>"); return true; }
            Player target = plugin.getServer().getPlayer(args[0]);
            if (target == null) { p.sendMessage(msg("player-not-found")); return true; }
            p.teleport(target);
            p.sendMessage(msg("prefix") + " §a已传送到 §e" + target.getName());
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
            if (args.length == 1) {
                return plugin.getServer().getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(n -> n.toLowerCase().startsWith(args[0].toLowerCase()))
                        .collect(Collectors.toList());
            }
            return Collections.emptyList();
        }
    }

    private class TphereCmd implements CommandExecutor, TabCompleter {
        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (!(sender instanceof Player p)) { sender.sendMessage(msg("player-only")); return true; }
            if (!p.hasPermission("megaplugin.tp")) { p.sendMessage(msg("no-permission")); return true; }
            if (args.length == 0) { p.sendMessage(msg("prefix") + " §c用法: /tphere <玩家>"); return true; }
            Player target = plugin.getServer().getPlayer(args[0]);
            if (target == null) { p.sendMessage(msg("player-not-found")); return true; }
            target.teleport(p);
            p.sendMessage(msg("prefix") + " §a已将 §e" + target.getName() + " §a传送到你身边。");
            target.sendMessage(msg("prefix") + " §a你被传送到 §e" + p.getName());
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
            if (args.length == 1) {
                return plugin.getServer().getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(n -> n.toLowerCase().startsWith(args[0].toLowerCase()))
                        .collect(Collectors.toList());
            }
            return Collections.emptyList();
        }
    }

    private class TpoCmd implements CommandExecutor, TabCompleter {
        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (!(sender instanceof Player p)) { sender.sendMessage(msg("player-only")); return true; }
            if (!p.hasPermission("megaplugin.tp")) { p.sendMessage(msg("no-permission")); return true; }
            if (args.length == 0) { p.sendMessage(msg("prefix") + " §c用法: /tpo <玩家>"); return true; }
            Player target = plugin.getServer().getPlayer(args[0]);
            if (target == null) { p.sendMessage(msg("player-not-found")); return true; }
            p.teleport(target);
            p.sendMessage(msg("prefix") + " §a已强制传送到 §e" + target.getName());
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
            if (args.length == 1) {
                return plugin.getServer().getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(n -> n.toLowerCase().startsWith(args[0].toLowerCase()))
                        .collect(Collectors.toList());
            }
            return Collections.emptyList();
        }
    }

    private class BackCmd implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (!(sender instanceof Player p)) { sender.sendMessage(msg("player-only")); return true; }
            if (!p.hasPermission("megaplugin.back")) { p.sendMessage(msg("no-permission")); return true; }
            Location loc = lastLocation.get(p.getUniqueId());
            if (loc == null) { p.sendMessage(msg("prefix") + " §c没有找到之前的位置记录！"); return true; }
            p.teleport(loc);
            p.sendMessage(msg("prefix") + " §a已返回之前的位置。");
            return true;
        }
    }
}
