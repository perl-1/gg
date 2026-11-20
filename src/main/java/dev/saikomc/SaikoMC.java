package dev.saikomc;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public class SaikoMC extends JavaPlugin {

    private final Board board = new Board(this);

    @Override
    public void onEnable() {
        saveDefaultConfig();
    }

    @Override
    public void onDisable() {
    }

    public Board board() { return board; }

    public String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    public String symbol() {
        return color(getConfig().getString("currency.symbol", "$"));
    }

    public String fmt(long v) {
        return symbol() + String.format("%,d", v);
    }

    public String shortNum(long v) {
        String[] suf = {"", "K", "M", "B", "T", "Q"};
        double d = v;
        int i = 0;
        while (d >= 1000 && i < suf.length - 1) {
            d /= 1000;
            i++;
        }
        String s = (i == 0 || d >= 100)
                ? String.valueOf((long) d)
                : String.format(java.util.Locale.US, "%.1f", d);
        if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
        return s + suf[i];
    }

    public String duration(long seconds) {
        long d = seconds / 86400;
        long h = (seconds % 86400) / 3600;
        long m = (seconds % 3600) / 60;
        if (d > 0) return d + "d " + h + "h " + m + "m";
        if (h > 0) return h + "h " + m + "m";
        return m + "m";
    }

    public void send(CommandSender to, String msg) {
        to.sendMessage(color(getConfig().getString(
                "prefix", "&b&lSaikoMC &8» &r") + msg));
    }

    public void broadcast(String msg) {
        Bukkit.broadcastMessage(color(getConfig().getString(
                "prefix", "&b&lSaikoMC &8» &r") + msg));
    }
}
