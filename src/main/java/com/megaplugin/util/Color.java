package com.megaplugin.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * 统一颜色/文本工具 — 同时支持 Bukkit 遗留 & Adventure 组件。
 */
public final class Color {

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.legacySection();

    private Color() {}

    /** & → § 颜色码转换 (Bukkit 遗留风格) */
    public static String colorize(String text) {
        if (text == null) return "";
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', text);
    }

    /** 去除 & 颜色码 */
    public static String strip(String text) {
        if (text == null) return "";
        return text.replaceAll("&[0-9a-fk-orA-FK-OR]", "");
    }

    /** § → Adventure Component */
    public static Component toComponent(String legacy) {
        return LEGACY.deserialize(legacy);
    }
}
