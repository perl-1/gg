package dev.saikomc;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Server shop: category menu -> paged item list -> amount chooser. Prices live in shop.yml. */
public class ShopManager implements Listener {
    static class ShopItem {
        Material mat;
        long buy;
        long sell;
    }

    static class Category {
        String id;
        String name;
        Material icon;
        List<ShopItem> items = new ArrayList<>();
    }

    enum View { MAIN, CATEGORY, CHOOSER }

    static class ShopHolder implements InventoryHolder {
        View view;
        String cat;
        int page;
        int pages = 1;
        ShopItem item;
        int qty = 1;
        Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    public static final int MAX_QTY = 2304; // 36 stacks
    private static final int PER_PAGE = 45;

    private final SaikoMC p;
    private final Map<String, Category> cats = new LinkedHashMap<>();
    private final Map<Material, Long> sellPrices = new HashMap<>();
    private final Map<Material, ShopItem> byMat = new HashMap<>();
    private String title = "&6&lShop";

    public ShopManager(SaikoMC p) {
        this.p = p;
    }

    // ================= loading =================

    public void load() {
        cats.clear();
        sellPrices.clear();
        byMat.clear();
        File f = new File(p.getDataFolder(), "shop.yml");
        if (!f.exists()) p.saveResource("shop.yml", false);
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        title = y.getString("title", "&6&lShop");
        ConfigurationSection root = y.getConfigurationSection("categories");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection cs = root.getConfigurationSection(id);
            if (cs == null) continue;
            Category c = new Category();
            c.id = id;
            c.name = cs.getString("name", "&f" + id);
            Material icon = Material.matchMaterial(cs.getString("icon", "CHEST"));
            c.icon = icon == null ? Material.CHEST : icon;
            ConfigurationSection items = cs.getConfigurationSection("items");
            if (items != null) {
                for (String key : items.getKeys(false)) {
                    Material m = Material.matchMaterial(key);
                    if (m == null || !m.isItem()) {
                        p.getLogger().warning("shop.yml: unknown material " + key + " (skipped)");
                        continue;
                    }
                    ShopItem it = new ShopItem();
                    it.mat = m;
                    it.buy = items.getLong(key + ".buy", 0);
                    it.sell = items.getLong(key + ".sell", 0);
                    c.items.add(it);
                    byMat.put(m, it);
                    if (it.sell > 0) sellPrices.put(m, it.sell);
                }
            }
            cats.put(id, c);
        }
        p.getLogger().info("Shop loaded: " + cats.size() + " categories, " + byMat.size() + " items.");
    }

    public Long price(Material m) {
        return sellPrices.get(m);
    }

    public List<String> sellableNames() {
        List<String> l = new ArrayList<>();
        for (Material m : sellPrices.keySet()) l.add(m.name().toLowerCase());
        Collections.sort(l);
        return l;
    }

    public List<String> buyableNames() {
        List<String> l = new ArrayList<>();
        for (ShopItem i : byMat.values()) if (i.buy > 0) l.add(i.mat.name().toLowerCase());
        Collections.sort(l);
        return l;
    }

    public List<String> categoryIds() {
        return new ArrayList<>(cats.keySet());
    }

    public static String pretty(Material m) {
        StringBuilder sb = new StringBuilder();
        for (String w : m.name().toLowerCase().split("_")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    // ================= GUI: main menu =================

    private boolean spawnersEnabled() {
        return p.getConfig().getBoolean("spawners.enabled", true);
    }

    public void openMain(Player pl) {
        ShopHolder h = new ShopHolder();
        h.view = View.MAIN;
        int n = cats.size() + (spawnersEnabled() ? 1 : 0);
        int size = Math.min(54, Math.max(9, ((n + 8) / 9) * 9));
        Inventory inv = Bukkit.createInventory(h, size, p.color(title));
        h.inv = inv;
        int i = 0;
        for (Category c : cats.values()) {
            if (i >= size) break;
            inv.setItem(i++, icon(c.icon, c.name, "&7Click to browse", "&8" + c.items.size() + " items"));
        }
        if (spawnersEnabled() && i < size) {
            inv.setItem(i, icon(Material.SPAWNER, "&d&lSpawners", "&7Buy mob spawners", "&8Right-click them to collect drops"));
        }
        pl.openInventory(inv);
    }

    private ItemStack icon(Material mat, String name, String... lore) {
        ItemStack st = new ItemStack(mat);
        ItemMeta m = st.getItemMeta();
        m.setDisplayName(p.color(name));
        List<String> l = new ArrayList<>();
        for (String s : lore) l.add(p.color(s));
        m.setLore(l);
        m.addItemFlags(ItemFlag.values());
        st.setItemMeta(m);
        return st;
    }

    // ================= GUI: category =================

    public boolean openCategoryByName(Player pl, String name) {
        for (Category c : cats.values()) {
            if (c.id.equalsIgnoreCase(name)) {
                openCategory(pl, c, 0);
                return true;
            }
        }
        for (Category c : cats.values()) {
            if (org.bukkit.ChatColor.stripColor(p.color(c.name)).toLowerCase().contains(name.toLowerCase())) {
                openCategory(pl, c, 0);
                return true;
            }
        }
        return false;
    }

    public void openCategory(Player pl, Category c, int page) {
        int pages = Math.max(1, (int) Math.ceil(c.items.size() / (double) PER_PAGE));
        page = Math.max(0, Math.min(page, pages - 1));
        ShopHolder h = new ShopHolder();
        h.view = View.CATEGORY;
        h.cat = c.id;
        h.page = page;
        h.pages = pages;
        Inventory inv = Bukkit.createInventory(h, 54,
                p.color(c.name + " &8(" + (page + 1) + "/" + pages + ")"));
        h.inv = inv;
        for (int i = 0; i < PER_PAGE; i++) {
            int idx = page * PER_PAGE + i;
            if (idx >= c.items.size()) break;
            ShopItem it = c.items.get(idx);
            ItemStack st = new ItemStack(it.mat);
            ItemMeta m = st.getItemMeta();
            List<String> lore = new ArrayList<>();
            lore.add("");
            if (it.buy > 0) lore.add(p.color("&aBuy: &6" + p.fmt(it.buy)));
            else lore.add(p.color("&8Not for sale"));
            if (it.sell > 0) lore.add(p.color("&cSell: &6" + p.fmt(it.sell)));
            else lore.add(p.color("&8Can't be sold"));
            lore.add("");
            lore.add(p.color("&eClick to choose an amount"));
            m.setLore(lore);
            m.addItemFlags(ItemFlag.values());
            st.setItemMeta(m);
            inv.setItem(i, st);
        }
        inv.setItem(45, icon(Material.GOLD_NUGGET, "&6Balance: " + p.fmt(p.eco().get(pl.getUniqueId()))));
        if (page > 0) inv.setItem(48, icon(Material.ARROW, "&ePrevious page"));
        inv.setItem(49, icon(Material.BARRIER, "&cBack"));
        if (page < pages - 1) inv.setItem(50, icon(Material.ARROW, "&eNext page"));
        pl.openInventory(inv);
    }

    // ================= GUI: amount chooser =================

    public void openChooser(Player pl, Category c, int page, ShopItem it, int qty) {
        ShopHolder h = new ShopHolder();
        h.view = View.CHOOSER;
        h.cat = c.id;
        h.page = page;
        h.item = it;
        h.qty = qty;
        Inventory inv = Bukkit.createInventory(h, 54, p.color("&8Choose amount - " + pretty(it.mat)));
        h.inv = inv;
        buildChooser(pl, h);
        pl.openInventory(inv);
    }

    private ItemStack amountButton(Material mat, String name, int shown) {
        ItemStack st = new ItemStack(mat, Math.max(1, Math.min(64, shown)));
        ItemMeta m = st.getItemMeta();
        m.setDisplayName(p.color(name));
        st.setItemMeta(m);
        return st;
    }

    private void buildChooser(Player pl, ShopHolder h) {
        Inventory inv = h.inv;
        inv.clear();
        ShopItem it = h.item;
        int q = Math.max(1, Math.min(MAX_QTY, h.qty));
        h.qty = q;
        int have = count(pl, it.mat);

        ItemStack show = new ItemStack(it.mat, Math.min(q, 64));
        ItemMeta sm = show.getItemMeta();
        List<String> lore = new ArrayList<>();
        lore.add(p.color("&7Quantity: &e" + q + " &8(" + stacks(q) + ")"));
        lore.add("");
        if (it.buy > 0) lore.add(p.color("&aBuy total: &6" + p.fmt(it.buy * q)));
        if (it.sell > 0) lore.add(p.color("&cSell total: &6" + p.fmt(it.sell * q)));
        lore.add(p.color("&7You have: &f" + have));
        sm.setLore(lore);
        sm.addItemFlags(ItemFlag.values());
        show.setItemMeta(sm);
        inv.setItem(22, show);

        inv.setItem(19, amountButton(Material.RED_STAINED_GLASS_PANE, "&c-64", 64));
        inv.setItem(20, amountButton(Material.RED_STAINED_GLASS_PANE, "&c-8", 8));
        inv.setItem(21, amountButton(Material.RED_STAINED_GLASS_PANE, "&c-1", 1));
        inv.setItem(23, amountButton(Material.LIME_STAINED_GLASS_PANE, "&a+1", 1));
        inv.setItem(24, amountButton(Material.LIME_STAINED_GLASS_PANE, "&a+8", 8));
        inv.setItem(25, amountButton(Material.LIME_STAINED_GLASS_PANE, "&a+64", 64));

        inv.setItem(29, amountButton(Material.PAPER, "&eSet to 1", 1));
        inv.setItem(30, amountButton(Material.PAPER, "&eSet to 16", 16));
        inv.setItem(31, amountButton(Material.PAPER, "&eSet to 32", 32));
        inv.setItem(32, amountButton(Material.PAPER, "&eSet to 64", 64));
        inv.setItem(33, amountButton(Material.PAPER, "&eSet to 576 &7(9 stacks)", 64));

        if (it.buy > 0) {
            inv.setItem(38, icon(Material.LIME_CONCRETE, "&a&lBUY " + q, "&7Cost: &6" + p.fmt(it.buy * q),
                    "&7Balance: &6" + p.fmt(p.eco().get(pl.getUniqueId()))));
        } else {
            inv.setItem(38, icon(Material.GRAY_CONCRETE, "&8Can't be bought"));
        }
        if (it.sell > 0) {
            inv.setItem(40, icon(Material.ORANGE_CONCRETE, "&6&lSELL ALL (" + have + ")",
                    "&7You get: &6" + p.fmt(it.sell * have)));
            inv.setItem(42, icon(Material.RED_CONCRETE, "&c&lSELL " + q, "&7You get: &6" + p.fmt(it.sell * q),
                    "&7You have: &f" + have));
        } else {
            inv.setItem(40, icon(Material.GRAY_CONCRETE, "&8Can't be sold"));
            inv.setItem(42, icon(Material.GRAY_CONCRETE, "&8Can't be sold"));
        }
        inv.setItem(45, icon(Material.GOLD_NUGGET, "&6Balance: " + p.fmt(p.eco().get(pl.getUniqueId()))));
        inv.setItem(49, icon(Material.BARRIER, "&cBack"));

        ItemStack filler = icon(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null) inv.setItem(i, filler);
        }
    }

    private String stacks(int q) {
        return (q / 64) + " stacks + " + (q % 64);
    }

    // ================= click handling =================

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof ShopHolder)) return;
        ShopHolder h = (ShopHolder) top.getHolder();
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player)) return;
        Player pl = (Player) e.getWhoClicked();
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= top.getSize()) return;

        switch (h.view) {
            case MAIN:
                clickMain(pl, slot);
                break;
            case CATEGORY:
                clickCategory(pl, h, slot);
                break;
            default:
                clickChooser(pl, h, slot);
                break;
        }
    }

    private void clickMain(Player pl, int slot) {
        List<Category> list = new ArrayList<>(cats.values());
        if (slot < list.size()) {
            Category c = list.get(slot);
            Bukkit.getScheduler().runTask(p, () -> openCategory(pl, c, 0));
        } else if (slot == list.size() && spawnersEnabled()) {
            Bukkit.getScheduler().runTask(p, () -> p.spawners().openShop(pl));
        }
    }

    private void clickCategory(Player pl, ShopHolder h, int slot) {
        Category c = cats.get(h.cat);
        if (c == null) return;
        if (slot == 49) {
            Bukkit.getScheduler().runTask(p, () -> openMain(pl));
        } else if (slot == 48 && h.page > 0) {
            Bukkit.getScheduler().runTask(p, () -> openCategory(pl, c, h.page - 1));
        } else if (slot == 50 && h.page < h.pages - 1) {
            Bukkit.getScheduler().runTask(p, () -> openCategory(pl, c, h.page + 1));
        } else if (slot < PER_PAGE) {
            int idx = h.page * PER_PAGE + slot;
            if (idx < c.items.size()) {
                ShopItem it = c.items.get(idx);
                Bukkit.getScheduler().runTask(p, () -> openChooser(pl, c, h.page, it, 1));
            }
        }
    }

    private void clickChooser(Player pl, ShopHolder h, int slot) {
        switch (slot) {
            case 19: h.qty -= 64; break;
            case 20: h.qty -= 8; break;
            case 21: h.qty -= 1; break;
            case 23: h.qty += 1; break;
            case 24: h.qty += 8; break;
            case 25: h.qty += 64; break;
            case 29: h.qty = 1; break;
            case 30: h.qty = 16; break;
            case 31: h.qty = 32; break;
            case 32: h.qty = 64; break;
            case 33: h.qty = 576; break;
            case 38:
                buy(pl, h.item, h.qty);
                break;
            case 40:
                sellItem(pl, h.item.mat, Integer.MAX_VALUE);
                break;
            case 42:
                sellItem(pl, h.item.mat, h.qty);
                break;
            case 49: {
                Category c = cats.get(h.cat);
                if (c != null) Bukkit.getScheduler().runTask(p, () -> openCategory(pl, c, h.page));
                return;
            }
            default:
                return;
        }
        buildChooser(pl, h);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof ShopHolder) e.setCancelled(true);
    }

    // ================= buying =================

    private void buy(Player pl, ShopItem it, int qty) {
        if (it.buy <= 0) {
            p.send(pl, "&cThis item can't be bought.");
            return;
        }
        qty = Math.max(1, Math.min(MAX_QTY, qty));
        long total = it.buy * qty;
        if (!p.eco().take(pl.getUniqueId(), total)) {
            p.send(pl, "&cYou need &6" + p.fmt(total) + " &cfor that.");
            return;
        }
        Map<Integer, ItemStack> left = pl.getInventory().addItem(new ItemStack(it.mat, qty));
        int notGiven = 0;
        for (ItemStack l : left.values()) notGiven += l.getAmount();
        if (notGiven > 0) p.eco().add(pl.getUniqueId(), it.buy * notGiven);
        int given = qty - notGiven;
        if (given > 0) {
            p.send(pl, "&aBought &f" + given + "x " + pretty(it.mat) + " &afor &6" + p.fmt(it.buy * given) + "&a.");
        } else {
            p.send(pl, "&cYour inventory is full.");
        }
    }

    /** /buy <item> [amount] */
    public void buyCommand(Player pl, Material m, int qty) {
        ShopItem it = byMat.get(m);
        if (it == null || it.buy <= 0) {
            p.send(pl, "&cThat item isn't for sale.");
            return;
        }
        buy(pl, it, qty);
    }

    // ================= selling =================

    public void sellItem(Player pl, Material m, int amount) {
        Long price = sellPrices.get(m);
        if (price == null) {
            p.send(pl, "&cThe server doesn't buy " + pretty(m) + ".");
            return;
        }
        int have = count(pl, m);
        if (have == 0) {
            p.send(pl, "&cYou don't have any " + pretty(m) + ".");
            return;
        }
        int n = Math.min(have, amount);
        removePlain(pl, m, n);
        long total = price * n;
        p.eco().add(pl.getUniqueId(), total);
        p.send(pl, "&aSold &f" + n + "x " + pretty(m) + " &afor &6" + p.fmt(total) + "&a.");
    }

    public void sellHand(Player pl) {
        ItemStack hand = pl.getInventory().getItemInMainHand();
        if (hand == null || hand.getType().isAir()) {
            p.send(pl, "&cHold the item you want to sell.");
            return;
        }
        Long price = sellPrices.get(hand.getType());
        if (price == null || hand.hasItemMeta()) {
            p.send(pl, "&cThat item can't be sold to the server.");
            return;
        }
        long total = price * hand.getAmount();
        int amt = hand.getAmount();
        Material type = hand.getType();
        pl.getInventory().setItemInMainHand(null);
        p.eco().add(pl.getUniqueId(), total);
        p.send(pl, "&aSold &f" + amt + "x " + pretty(type) + " &afor &6" + p.fmt(total) + "&a.");
    }

    public void sellAll(Player pl) {
        ItemStack[] contents = pl.getInventory().getStorageContents();
        long total = 0;
        int items = 0;
        for (int i = 0; i < contents.length; i++) {
            ItemStack s = contents[i];
            if (s == null || s.hasItemMeta()) continue;
            Long price = sellPrices.get(s.getType());
            if (price == null) continue;
            total += price * s.getAmount();
            items += s.getAmount();
            contents[i] = null;
        }
        if (items == 0) {
            p.send(pl, "&cNothing sellable in your inventory.");
            return;
        }
        pl.getInventory().setStorageContents(contents);
        p.eco().add(pl.getUniqueId(), total);
        p.send(pl, "&aSold &f" + items + " &aitems for &6" + p.fmt(total) + "&a.");
    }

    /** /worth [item] */
    public void worth(Player pl, Material m) {
        if (m == null) {
            ItemStack hand = pl.getInventory().getItemInMainHand();
            if (hand == null || hand.getType().isAir()) {
                p.send(pl, "&cHold an item or use &f/worth <item>&c.");
                return;
            }
            m = hand.getType();
            Long price = sellPrices.get(m);
            if (price == null || hand.hasItemMeta()) {
                p.send(pl, "&7The server doesn't buy that item.");
                return;
            }
            p.send(pl, "&f" + pretty(m) + " &7sells for &6" + p.fmt(price) + " &7each (&6"
                    + p.fmt(price * hand.getAmount()) + " &7for your stack).");
            return;
        }
        ShopItem it = byMat.get(m);
        if (it == null) {
            p.send(pl, "&7" + pretty(m) + " isn't in the shop.");
            return;
        }
        p.send(pl, "&f" + pretty(m) + "&7: buy &a" + (it.buy > 0 ? p.fmt(it.buy) : "n/a")
                + " &7| sell &c" + (it.sell > 0 ? p.fmt(it.sell) : "n/a"));
    }

    // ================= helpers =================

    private boolean plain(ItemStack s, Material m) {
        return s != null && s.getType() == m && !s.hasItemMeta();
    }

    private int count(Player pl, Material m) {
        int n = 0;
        for (ItemStack s : pl.getInventory().getStorageContents()) {
            if (plain(s, m)) n += s.getAmount();
        }
        return n;
    }

    private void removePlain(Player pl, Material m, int n) {
        ItemStack[] c = pl.getInventory().getStorageContents();
        for (int i = 0; i < c.length && n > 0; i++) {
            ItemStack s = c[i];
            if (plain(s, m)) {
                int t = Math.min(n, s.getAmount());
                n -= t;
                if (t == s.getAmount()) c[i] = null;
                else s.setAmount(s.getAmount() - t);
            }
        }
        pl.getInventory().setStorageContents(c);
    }
}
