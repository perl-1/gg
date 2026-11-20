package dev.saikomc;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** /ah - auction house with search, sorting, confirm menu, expiry and expired-item claiming. */
public class AuctionHouse implements Listener {
    static class Listing {
        int id;
        UUID seller;
        String sellerName;
        long price;
        long expires;
        ItemStack item;
    }

    enum Mode { ALL, MINE, SEARCH, EXPIRED, CONFIRM }

    enum Sort { NEWEST, PRICE_LOW, PRICE_HIGH }

    static class AHHolder implements InventoryHolder {
        Mode mode = Mode.ALL;
        Mode prev = Mode.ALL;
        Sort sort = Sort.NEWEST;
        String filter;
        int page;
        int pages = 1;
        final Map<Integer, Integer> slots = new HashMap<>();
        Listing confirm;
        Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final SaikoMC p;
    private final File file;
    private final Map<Integer, Listing> listings = new LinkedHashMap<>();
    private final Map<UUID, List<ItemStack>> expired = new HashMap<>();
    private int nextId = 1;
    private BukkitTask task;

    public AuctionHouse(SaikoMC p) {
        this.p = p;
        this.file = new File(p.getDataFolder(), "auctions.yml");
    }

    // ================= persistence =================

    private long durationMs() {
        return p.getConfig().getLong("auction.duration-hours", 48) * 3_600_000L;
    }

    public void load() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        nextId = y.getInt("next", 1);
        ConfigurationSection s = y.getConfigurationSection("listings");
        if (s != null) {
            for (String k : s.getKeys(false)) {
                try {
                    Listing l = new Listing();
                    l.id = Integer.parseInt(k);
                    l.seller = UUID.fromString(s.getString(k + ".seller", ""));
                    l.sellerName = s.getString(k + ".name", "?");
                    l.price = s.getLong(k + ".price");
                    l.expires = s.getLong(k + ".expires", System.currentTimeMillis() + durationMs());
                    l.item = s.getItemStack(k + ".item");
                    if (l.item != null) listings.put(l.id, l);
                } catch (IllegalArgumentException ex) {
                    p.getLogger().warning("Skipping broken auction listing " + k);
                }
            }
        }
        ConfigurationSection ex = y.getConfigurationSection("expired");
        if (ex != null) {
            for (String k : ex.getKeys(false)) {
                try {
                    UUID id = UUID.fromString(k);
                    List<ItemStack> list = new ArrayList<>();
                    List<?> raw = ex.getList(k);
                    if (raw != null) {
                        for (Object o : raw) if (o instanceof ItemStack) list.add((ItemStack) o);
                    }
                    if (!list.isEmpty()) expired.put(id, list);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("next", nextId);
        for (Listing l : listings.values()) {
            String b = "listings." + l.id;
            y.set(b + ".seller", l.seller.toString());
            y.set(b + ".name", l.sellerName);
            y.set(b + ".price", l.price);
            y.set(b + ".expires", l.expires);
            y.set(b + ".item", l.item);
        }
        for (Map.Entry<UUID, List<ItemStack>> e : expired.entrySet()) {
            if (!e.getValue().isEmpty()) y.set("expired." + e.getKey(), e.getValue());
        }
        try {
            p.getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException ex) {
            p.getLogger().severe("Could not save auctions.yml: " + ex.getMessage());
        }
    }

    public void start() {
        stop();
        task = Bukkit.getScheduler().runTaskTimer(p, this::expireCheck, 1200L, 1200L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void expireCheck() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        Iterator<Listing> it = listings.values().iterator();
        while (it.hasNext()) {
            Listing l = it.next();
            if (l.expires <= now) {
                it.remove();
                expired.computeIfAbsent(l.seller, k -> new ArrayList<>()).add(l.item);
                changed = true;
                Player s = Bukkit.getPlayer(l.seller);
                if (s != null) p.send(s, "&eOne of your auction listings expired. Claim it with &f/ah expired&e.");
            }
        }
        if (changed) save();
    }

    // ================= selling =================

    public void sell(Player pl, long price, int amount) {
        ItemStack hand = pl.getInventory().getItemInMainHand();
        if (hand == null || hand.getType().isAir()) {
            p.send(pl, "&cHold the item you want to list.");
            return;
        }
        if (price > 1_000_000_000_000L) {
            p.send(pl, "&cThat price is too high.");
            return;
        }
        int max = p.getConfig().getInt("auction.max-listings-per-player", 10);
        long mine = listings.values().stream().filter(l -> l.seller.equals(pl.getUniqueId())).count();
        if (mine >= max) {
            p.send(pl, "&cYou can only have &f" + max + " &clistings at once.");
            return;
        }
        if (amount <= 0 || amount > hand.getAmount()) amount = hand.getAmount();

        Listing l = new Listing();
        l.id = nextId++;
        l.seller = pl.getUniqueId();
        l.sellerName = pl.getName();
        l.price = price;
        l.expires = System.currentTimeMillis() + durationMs();
        l.item = hand.clone();
        l.item.setAmount(amount);
        if (amount >= hand.getAmount()) pl.getInventory().setItemInMainHand(null);
        else hand.setAmount(hand.getAmount() - amount);
        listings.put(l.id, l);
        save();
        p.send(pl, "&aListed &f" + amount + "x " + ShopManager.pretty(l.item.getType()) + " &afor &6" + p.fmt(price)
                + " &7(expires in " + (durationMs() / 3_600_000L) + "h). &8/ah mine");
    }

    // ================= GUI =================

    private String time(long ms) {
        long m = Math.max(0, ms) / 60000;
        long h = m / 60;
        m = m % 60;
        if (h >= 24) return (h / 24) + "d " + (h % 24) + "h";
        return h > 0 ? h + "h " + m + "m" : m + "m";
    }

    private ItemStack icon(Material mat, String name, String... lore) {
        ItemStack st = new ItemStack(mat);
        ItemMeta m = st.getItemMeta();
        m.setDisplayName(p.color(name));
        List<String> l = new ArrayList<>();
        for (String s : lore) l.add(p.color(s));
        m.setLore(l);
        st.setItemMeta(m);
        return st;
    }

    private boolean matches(Listing l, String filter) {
        String f = filter.toLowerCase();
        if (l.item.getType().name().toLowerCase().replace('_', ' ').contains(f.replace('_', ' '))) return true;
        if (l.sellerName.toLowerCase().contains(f)) return true;
        ItemMeta m = l.item.getItemMeta();
        if (m != null && m.hasDisplayName()
                && ChatColor.stripColor(m.getDisplayName()).toLowerCase().contains(f)) return true;
        for (Enchantment en : l.item.getEnchantments().keySet()) {
            if (en.getKey().getKey().replace('_', ' ').contains(f.replace('_', ' '))) return true;
        }
        if (m instanceof EnchantmentStorageMeta) {
            for (Enchantment en : ((EnchantmentStorageMeta) m).getStoredEnchants().keySet()) {
                if (en.getKey().getKey().replace('_', ' ').contains(f.replace('_', ' '))) return true;
            }
        }
        return false;
    }

    public void open(Player pl, Mode mode, String filter, Sort sort, int page) {
        if (mode == Mode.EXPIRED) {
            openExpired(pl);
            return;
        }
        long now = System.currentTimeMillis();
        List<Listing> list = new ArrayList<>();
        for (Listing l : listings.values()) {
            if (l.expires <= now) continue;
            if (mode == Mode.MINE && !l.seller.equals(pl.getUniqueId())) continue;
            if (mode == Mode.SEARCH && filter != null && !matches(l, filter)) continue;
            list.add(l);
        }
        switch (sort) {
            case PRICE_LOW:
                list.sort((a, b) -> Long.compare(a.price, b.price));
                break;
            case PRICE_HIGH:
                list.sort((a, b) -> Long.compare(b.price, a.price));
                break;
            default:
                list.sort((a, b) -> Integer.compare(b.id, a.id));
                break;
        }

        int pages = Math.max(1, (int) Math.ceil(list.size() / 45.0));
        page = Math.max(0, Math.min(page, pages - 1));
        AHHolder h = new AHHolder();
        h.mode = mode;
        h.sort = sort;
        h.filter = filter;
        h.page = page;
        h.pages = pages;
        String label = mode == Mode.MINE ? "My listings" : mode == Mode.SEARCH ? "Search: " + filter : "Auction House";
        Inventory inv = Bukkit.createInventory(h, 54, p.color("&6&l" + label + " &8(" + (page + 1) + "/" + pages + ")"));
        h.inv = inv;

        for (int i = 0; i < 45; i++) {
            int idx = page * 45 + i;
            if (idx >= list.size()) break;
            Listing l = list.get(idx);
            ItemStack d = l.item.clone();
            ItemMeta m = d.getItemMeta();
            List<String> lore = m.hasLore() ? new ArrayList<>(m.getLore()) : new ArrayList<>();
            lore.add("");
            lore.add(p.color("&7Seller: &f" + l.sellerName));
            lore.add(p.color("&7Price: &6" + p.fmt(l.price)));
            if (d.getAmount() > 1) lore.add(p.color("&7Per item: &6" + p.fmt(l.price / d.getAmount())));
            lore.add(p.color("&7Expires in: &f" + time(l.expires - now)));
            lore.add(p.color(l.seller.equals(pl.getUniqueId()) ? "&eClick to take back" : "&aClick to buy"));
            m.setLore(lore);
            d.setItemMeta(m);
            inv.setItem(i, d);
            h.slots.put(i, l.id);
        }

        int expiredCount = expired.getOrDefault(pl.getUniqueId(), new ArrayList<>()).size();
        if (page > 0) inv.setItem(45, icon(Material.ARROW, "&ePrevious page"));
        inv.setItem(47, icon(Material.CHEST, mode == Mode.MINE ? "&eShow all listings" : "&eMy listings",
                "&7Click to switch"));
        inv.setItem(48, icon(Material.ENDER_CHEST, "&dExpired items &7(" + expiredCount + ")", "&7Click to claim"));
        inv.setItem(49, icon(Material.PAPER, "&7" + list.size() + " listings",
                "&7Balance: &6" + p.fmt(p.eco().get(pl.getUniqueId())), "&8/ah sell <price> to list your item"));
        inv.setItem(50, icon(Material.HOPPER, "&eSort: &f" + sort.name().replace('_', ' ').toLowerCase(),
                "&7Click to change"));
        inv.setItem(51, icon(Material.OAK_SIGN, "&eSearch", "&7Use &f/ah search <text>"));
        if (page < pages - 1) inv.setItem(53, icon(Material.ARROW, "&eNext page"));
        pl.openInventory(inv);
    }

    private void openConfirm(Player pl, AHHolder from, Listing l) {
        AHHolder h = new AHHolder();
        h.mode = Mode.CONFIRM;
        h.prev = from.mode;
        h.filter = from.filter;
        h.sort = from.sort;
        h.page = from.page;
        h.confirm = l;
        Inventory inv = Bukkit.createInventory(h, 27, p.color("&8Confirm purchase"));
        h.inv = inv;
        ItemStack d = l.item.clone();
        ItemMeta m = d.getItemMeta();
        List<String> lore = m.hasLore() ? new ArrayList<>(m.getLore()) : new ArrayList<>();
        lore.add("");
        lore.add(p.color("&7Seller: &f" + l.sellerName));
        lore.add(p.color("&7Price: &6" + p.fmt(l.price)));
        m.setLore(lore);
        d.setItemMeta(m);
        inv.setItem(13, d);
        inv.setItem(11, icon(Material.LIME_CONCRETE, "&a&lCONFIRM", "&7Pay &6" + p.fmt(l.price)));
        inv.setItem(15, icon(Material.RED_CONCRETE, "&c&lCANCEL"));
        pl.openInventory(inv);
    }

    private void openExpired(Player pl) {
        AHHolder h = new AHHolder();
        h.mode = Mode.EXPIRED;
        Inventory inv = Bukkit.createInventory(h, 54, p.color("&6&lExpired items"));
        h.inv = inv;
        List<ItemStack> list = expired.getOrDefault(pl.getUniqueId(), new ArrayList<>());
        for (int i = 0; i < 45 && i < list.size(); i++) {
            ItemStack d = list.get(i).clone();
            ItemMeta m = d.getItemMeta();
            List<String> lore = m.hasLore() ? new ArrayList<>(m.getLore()) : new ArrayList<>();
            lore.add("");
            lore.add(p.color("&eClick to claim"));
            m.setLore(lore);
            d.setItemMeta(m);
            inv.setItem(i, d);
            h.slots.put(i, i);
        }
        inv.setItem(47, icon(Material.HOPPER, "&aClaim all"));
        inv.setItem(49, icon(Material.BARRIER, "&cBack"));
        pl.openInventory(inv);
    }

    // ================= clicks =================

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof AHHolder)) return;
        AHHolder h = (AHHolder) top.getHolder();
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player)) return;
        Player pl = (Player) e.getWhoClicked();
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= top.getSize()) return;

        if (h.mode == Mode.CONFIRM) {
            if (slot == 11) purchase(pl, h.confirm);
            if (slot == 11 || slot == 15) {
                Bukkit.getScheduler().runTask(p, () -> open(pl, h.prev, h.filter, h.sort, h.page));
            }
            return;
        }
        if (h.mode == Mode.EXPIRED) {
            clickExpired(pl, h, slot);
            return;
        }

        if (slot == 45 && h.page > 0) {
            Bukkit.getScheduler().runTask(p, () -> open(pl, h.mode, h.filter, h.sort, h.page - 1));
        } else if (slot == 53 && h.page < h.pages - 1) {
            Bukkit.getScheduler().runTask(p, () -> open(pl, h.mode, h.filter, h.sort, h.page + 1));
        } else if (slot == 47) {
            Mode next = h.mode == Mode.MINE ? Mode.ALL : Mode.MINE;
            Bukkit.getScheduler().runTask(p, () -> open(pl, next, null, h.sort, 0));
        } else if (slot == 48) {
            Bukkit.getScheduler().runTask(p, () -> openExpired(pl));
        } else if (slot == 50) {
            Sort next = Sort.values()[(h.sort.ordinal() + 1) % Sort.values().length];
            Bukkit.getScheduler().runTask(p, () -> open(pl, h.mode, h.filter, next, 0));
        } else if (slot == 51) {
            p.send(pl, "&7Use &f/ah search <text> &7to search by item, enchantment or seller.");
        } else if (slot < 45) {
            Integer id = h.slots.get(slot);
            if (id == null) return;
            Listing l = listings.get(id);
            if (l == null) {
                Bukkit.getScheduler().runTask(p, () -> open(pl, h.mode, h.filter, h.sort, h.page));
                return;
            }
            if (l.seller.equals(pl.getUniqueId())) {
                listings.remove(id);
                give(pl, l.item.clone());
                save();
                p.send(pl, "&eListing removed and item returned.");
                Bukkit.getScheduler().runTask(p, () -> open(pl, h.mode, h.filter, h.sort, h.page));
            } else {
                Bukkit.getScheduler().runTask(p, () -> openConfirm(pl, h, l));
            }
        }
    }

    private void clickExpired(Player pl, AHHolder h, int slot) {
        List<ItemStack> list = expired.get(pl.getUniqueId());
        if (slot == 49) {
            Bukkit.getScheduler().runTask(p, () -> open(pl, Mode.ALL, null, Sort.NEWEST, 0));
            return;
        }
        if (list == null || list.isEmpty()) return;
        if (slot == 47) {
            for (ItemStack it : list) give(pl, it.clone());
            list.clear();
            expired.remove(pl.getUniqueId());
            save();
            p.send(pl, "&aClaimed all expired items.");
            Bukkit.getScheduler().runTask(p, () -> openExpired(pl));
            return;
        }
        Integer idx = h.slots.get(slot);
        if (idx == null || idx >= list.size()) return;
        give(pl, list.remove((int) idx).clone());
        if (list.isEmpty()) expired.remove(pl.getUniqueId());
        save();
        Bukkit.getScheduler().runTask(p, () -> openExpired(pl));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof AHHolder) e.setCancelled(true);
    }

    private void purchase(Player pl, Listing l) {
        if (listings.get(l.id) != l || l.expires <= System.currentTimeMillis()) {
            p.send(pl, "&cThat listing is no longer available.");
            return;
        }
        if (l.seller.equals(pl.getUniqueId())) {
            p.send(pl, "&cThat's your own listing.");
            return;
        }
        if (!p.eco().take(pl.getUniqueId(), l.price)) {
            p.send(pl, "&cYou need &6" + p.fmt(l.price) + "&c.");
            return;
        }
        listings.remove(l.id);
        long tax = l.price * p.getConfig().getLong("auction.tax-percent", 5) / 100;
        p.eco().add(l.seller, l.price - tax);
        give(pl, l.item.clone());
        save();
        p.send(pl, "&aYou bought &f" + l.item.getAmount() + "x " + ShopManager.pretty(l.item.getType())
                + " &afor &6" + p.fmt(l.price) + "&a.");
        Player seller = Bukkit.getPlayer(l.seller);
        if (seller != null) {
            p.send(seller, "&e" + pl.getName() + " &abought your &f" + ShopManager.pretty(l.item.getType())
                    + " &aand you received &6" + p.fmt(l.price - tax) + " &7(tax " + p.fmt(tax) + ")");
        }
    }

    private void give(Player pl, ItemStack item) {
        Map<Integer, ItemStack> left = pl.getInventory().addItem(item);
        for (ItemStack s : left.values()) {
            pl.getWorld().dropItemNaturally(pl.getLocation(), s);
        }
    }

    public int expiredCount(UUID id) {
        return expired.getOrDefault(id, new ArrayList<>()).size();
    }
}
