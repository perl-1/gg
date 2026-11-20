package dev.saikomc;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

/** Optional integrations: LuckPerms (rank) and PlaceholderAPI (clan + any other placeholder). */
public class Hooks {
    private final SaikoMC p;
    private Method papiMethod;

    public Hooks(SaikoMC p) {
        this.p = p;
    }

    public boolean has(String plugin) {
        return Bukkit.getPluginManager().isPluginEnabled(plugin);
    }

    /** Runs PlaceholderAPI on the text if it is installed. */
    public String papi(Player pl, String text) {
        if (!has("PlaceholderAPI")) return text;
        try {
            if (papiMethod == null) {
                Class<?> c = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
                papiMethod = c.getMethod("setPlaceholders", Player.class, String.class);
            }
            return (String) papiMethod.invoke(null, pl, text);
        } catch (Throwable t) {
            return text;
        }
    }

    public String rank(Player pl) {
        String fallback = p.getConfig().getString("rank.fallback", "Member");
        if (!has("LuckPerms")) return fallback;
        try {
            String r = LuckHook.rank(pl, p.getConfig().getString("rank.mode", "group"));
            return (r == null || r.isBlank()) ? fallback : r;
        } catch (Throwable t) {
            return fallback;
        }
    }

    /** Tries every placeholder in clan.placeholder (a list or a single value); first real answer wins. */
    public String clan(Player pl) {
        String none = p.getConfig().getString("clan.none-text", "None");
        List<String> phs = p.getConfig().getStringList("clan.placeholder");
        if (phs.isEmpty()) {
            String one = p.getConfig().getString("clan.placeholder", "");
            if (one != null && !one.isBlank()) phs = Collections.singletonList(one);
        }
        for (String ph : phs) {
            String r = papi(pl, ph);
            if (r == null) continue;
            r = r.trim();
            if (r.isEmpty() || r.contains("%")) continue; // empty = no clan, % = placeholder not installed
            if (r.equalsIgnoreCase("none") || r.equalsIgnoreCase("null") || r.equalsIgnoreCase("false")) continue;
            return r;
        }
        return none;
    }
}
