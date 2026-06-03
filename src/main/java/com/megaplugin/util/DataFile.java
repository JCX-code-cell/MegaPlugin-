package com.megaplugin.util;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;

/**
 * YAML 数据文件封装 — 自动创建、加载、保存。
 */
public class DataFile {

    private final Plugin plugin;
    private final File file;
    private YamlConfiguration config;

    public DataFile(Plugin plugin, String fileName) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), fileName);
        load();
    }

    public void load() {
        if (!file.exists()) {
            file.getParentFile().mkdirs();
            try {
                file.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().warning("[DataFile] 无法创建 " + file.getName());
            }
        }
        config = YamlConfiguration.loadConfiguration(file);
    }

    public YamlConfiguration getConfig() { return config; }

    public File getFile() { return file; }

    public void save() {
        try { config.save(file); }
        catch (IOException e) { plugin.getLogger().warning("[DataFile] 无法保存 " + file.getName()); }
    }

    public void reload() { config = YamlConfiguration.loadConfiguration(file); }
}
