package com.megaplugin.util;

/**
 * Utility class for color code conversion.
 */
public final class Color {

    private Color() {}

    /**
     * Translates alternate color codes (&) to Minecraft color codes (§).
     */
    public static String colorize(String text) {
        if (text == null) return "";
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', text);
    }

    public static String strip(String text) {
        if (text == null) return "";
        return text.replaceAll("&[0-9a-fk-orA-FK-OR]", "");
    }
}
