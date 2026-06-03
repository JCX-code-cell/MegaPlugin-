package com.megaplugin;

import com.megaplugin.module.*;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

public final class MegaPlugin extends JavaPlugin {

    private final List<MegaModule> modules = new ArrayList<>();

    // Module instances for easy access
    private TeleportModule teleportModule;
    private EconomyModule economyModule;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // Register modules
        registerModule(new HomeModule(this));
        registerModule(new WarpModule(this));
        teleportModule = registerModule(new TeleportModule(this));
        registerModule(new SpawnModule(this));
        economyModule = registerModule(new EconomyModule(this));
        registerModule(new AdminModule(this));
        registerModule(new ChatModule(this));
        registerModule(new KitModule(this));
        registerModule(new MenuModule(this));
        registerModule(new BindModule(this));
        registerModule(new MarketModule(this));
        registerModule(new AuthModule(this));
        registerModule(new AntiCheatModule(this));
        registerModule(new RTPModule(this));

        getLogger().info("MegaPlugin v1.0.0 enabled! " + modules.size() + " modules loaded.");
    }

    @Override
    public void onDisable() {
        for (MegaModule module : modules) {
            try {
                module.onDisable();
            } catch (Exception e) {
                getLogger().warning("Error disabling module: " + e.getMessage());
            }
        }
        modules.clear();
        getLogger().info("MegaPlugin disabled.");
    }

    private <T extends MegaModule> T registerModule(T module) {
        modules.add(module);
        module.onEnable();
        return module;
    }

    public TeleportModule getTeleportModule() {
        return teleportModule;
    }

    public EconomyModule getEconomyModule() {
        return economyModule;
    }
}
