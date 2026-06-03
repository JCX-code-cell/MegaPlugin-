package com.megaplugin;

import com.megaplugin.module.*;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

public final class MegaPlugin extends JavaPlugin {

    private final List<MegaModule> modules = new ArrayList<>();

    private EconomyModule economyModule;
    private TeleportModule teleportModule;
    private PunishModule punishModule;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        register(teleportModule   = new TeleportModule(this));
        register(economyModule    = new EconomyModule(this));
        register(punishModule     = new PunishModule(this));
        register(new SpawnModule(this));
        register(new HomeModule(this));
        register(new WarpModule(this));
        register(new AdminModule(this));
        register(new ChatModule(this));
        register(new KitModule(this));
        register(new MenuModule(this));
        register(new BindModule(this));
        register(new MarketModule(this));
        register(new AuthModule(this));
        register(new RTPModule(this));
        register(new GrimBridgeModule(this));
        register(new ClaimModule(this));

        getLogger().info("[MegaPlugin] v" + getDescription().getVersion() +
                " 已加载 " + modules.size() + " 个模块");
    }

    @Override
    public void onDisable() {
        for (MegaModule m : modules) {
            try { m.onDisable(); }
            catch (Exception e) { getLogger().warning("[MegaPlugin] 模块卸载异常: " + e.getMessage()); }
        }
        modules.clear();
    }

    private <T extends MegaModule> T register(T module) {
        modules.add(module);
        module.onEnable();
        return module;
    }

    public EconomyModule economy() { return economyModule; }
    public TeleportModule teleport() { return teleportModule; }
    public PunishModule punish() { return punishModule; }

    // 兼容旧 API (逐步移除)
    /** @deprecated use economy() */
    @Deprecated public EconomyModule getEconomyModule() { return economyModule; }
    /** @deprecated use teleport() */
    @Deprecated public TeleportModule getTeleportModule() { return teleportModule; }
    /** @deprecated use punish() */
    @Deprecated public PunishModule getPunishModule() { return punishModule; }
}
