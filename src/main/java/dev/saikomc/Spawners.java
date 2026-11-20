package dev.saikomc;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.SpawnerSpawnEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Stackable "virtual" spawners (DonutSMP style): no mobs spawn, drops pile up inside the spawner.
 * Right-click a spawner to open its menu. Right-click it with the same spawner item to stack.
 */
public class Spawners implements Listener {
    static class Drop {
        Material mat;
        double min;
        double max;
        double chance;
    }

    static class SType {
        EntityType type;
        long price;
        double xp;
        List<Drop> drops = new ArrayList<>();
    }

    static class Spawner {
        String world;
        int x, y, z;
        EntityType type;
        int stack = 1;
        UUID owner;
        long xp;
        Map<Material, Long> storage = new LinkedHashMap<>();
    }

    static class SpawnerHolder implements InventoryHolder {
        Spawner s;
        Inventory inv;
        final Map<Integer, Material> slots = new HashMap<>();

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    static class SpawnerShopHolder implements InventoryHolder {
        final List<SType> list = new ArrayList<>();
        Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final SaikoMC p;
    private final File file;
    private final NamespacedKey typeKey;
    private final Random rnd = new Random();
    private final Map<String, Spawner> spawners = new HashMap<>();
    private final Map<EntityType, SType> types = new LinkedHashMap<>();
    private BukkitTask task;

    public Spawners(SaikoMC p) {
        this.p = p;
        this.file = new File(p.getDataFolder(), "spawners.yml");
        this.typeKey = new NamespacedKey(p, "spawner_type");
    }

    // ================= config / persistence =================

    public void loadTypes() {
        types.clear();
        ConfigurationSection root = p.getConfig().getConfigurationSection("spawners.types");
        if (root == null) return;
        for (String k : root.getKeys(false)) {
            EntityType et;
            try {
                et = EntityType.valueOf(k.toUpperCase());
            } catch (IllegalArgumentException ex) {
                p.getLogger().warning("config.yml: unknown spawner entity " + k);
                continue;
            }
            ConfigurationSection cs = root.getConfigurationSection(k);
            if (cs == null) continue;
            SType st = new SType();
            st.type = et;
            st.price = cs.getLong("price", 10000);
            st.xp = cs.getDouble("xp", 5);
            ConfigurationSection ds = cs.getConfigurationSection("drops");
            if (ds != null) {
                for (String m : ds.getKeys(false)) {
                    Material mat = Material.matchMaterial(m);
                    if (mat == null) {
                        p.getLogger().warning("config.yml: unknown drop " + m + " for " + k);
                        continue;
                    }
                    Drop d = new Drop();
                    d.mat = mat;
                    d.min = ds.getDouble(m + ".min", 1);
                    d.max = ds.getDouble(m + ".max", 1);
                    d.chance = ds.getDouble(m + ".chance", 1.0);
                    st.drops.add(d);
                }
            }
            types.put(et, st);
        }
    }

    public void load() {
        loadTypes();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = y.getConfigurationSection("spawners");
        if (sec == null) return;
        for (String k : sec.getKeys(false)) {
            try {
                ConfigurationSection c = sec.getConfigurationSection(k);
                if (c == null) continue;
                Spawner s = new Spawner();
                s.world = c.getString("world");
                s.x = c.getInt("x");
                s.y = c.getInt("y");
                s.z = c.getInt("z");
                s.type = EntityType.valueOf(c.getString("type", "ZOMBIE"));
                s.stack = Math.max(1, c.getInt("stack", 1));
                String o = c.getString("owner");
                s.owner = o == null ? null : UUID.fromString(o);
                s.xp = c.getLong("xp", 0);
                ConfigurationSection st = c.getConfigurationSection("storage");
                if (st != null) {
                    for (String m : st.getKeys(false)) {
                        Material mat = Material.matchMaterial(m);
                        if (mat != null && st.getLong(m) > 0) s.storage.put(mat, st.getLong(m));
                    }
                }
                if (s.world != null) spawners.put(key(s), s);
            } catch (IllegalArgumentException ex) {
                p.getLogger().warning("Skipping broken spawner entry " + k);
            }
        }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        int i = 0;
        for (Spawner s : spawners.values()) {
            String b = "spawners.s" + (i++);
            y.set(b + ".world", s.world);
            y.set(b + ".x", s.x);
            y.set(b + ".y", s.y);
            y.set(b + ".z", s.z);
            y.set(b + ".type", s.type.name());
            y.set(b + ".stack", s.stack);
            if (s.owner != null) y.set(b + ".owner", s.owner.toString());
            y.set(b + ".xp", s.xp);
            for (Map.Entry<Material, Long> e : s.storage.entrySet()) {
                y.set(b + ".storage." + e.getKey().name(), e.getValue());
            }
        }
        try {
            p.getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException ex) {
            p.getLogger().severe("Could not save spawners.yml: " + ex.getMessage());
        }
    }

    public void start() {
        stop();
        long ticks = Math.max(1, p.getConfig().getLong("spawners.cycle-seconds", 15)) * 20L;
        task = Bukkit.getScheduler().runTaskTimer(p, this::tick, ticks, ticks);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    // ================= helpers =================

    private String key(Block b) {
        return b.getWorld().getName() + ";" + b.getX() + ";" + b.getY() + ";" + b.getZ();
    }

    private String key(Spawner s) {
        return s.world + ";" + s.x + ";" + s.y + ";" + s.z;
    }

    public static String nice(EntityType t) {
        StringBuilder sb = new StringBuilder();
        for (String w : t.name().toLowerCase().split("_")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    public List<String> typeNames() {
        List<String> l = new ArrayList<>();
        for (EntityType t : types.keySet()) l.add(t.name().toLowerCase());
        return l;
    }

    public EntityType parseType(String s) {
        for (EntityType t : types.keySet()) {
            if (t.name().equalsIgnoreCase(s)) return t;
        }
        return null;
    }

    private long total(Spawner s) {
        long t = 0;
        for (long v : s.storage.values()) t += v;
        return t;
    }

    private long capacity(Spawner s) {
        return p.getConfig().getLong("spawners.storage-per-spawner", 1000) * s.stack;
    }

    /** Estimated money per hour for ONE spawner, using the shop sell prices. */
    private double incomePerHour(SType st) {
        double cycles = 3600.0 / Math.max(1, p.getConfig().getLong("spawners.cycle-seconds", 15));
        double mobs = p.getConfig().getInt("spawners.mobs-per-cycle", 4);
        double perMob = 0;
        for (Drop d : st.drops) {
            Long price = p.shop().price(d.mat);
            if (price != null) perMob += d.chance * (d.min + d.max) / 2.0 * price;
        }
        return cycles * mobs * perMob;
    }

    public ItemStack createItem(EntityType t, int amount) {
        ItemStack it = new ItemStack(Material.SPAWNER, amount);
        ItemMeta meta = it.getItemMeta();
        if (meta instanceof BlockStateMeta) {
            BlockStateMeta bsm = (BlockStateMeta) meta;
            BlockState bs = bsm.getBlockState();
            if (bs instanceof CreatureSpawner) {
                ((CreatureSpawner) bs).setSpawnedType(t);
                bsm.setBlockState(bs);
            }
        }
        meta.setDisplayName(p.color("&d&l" + nice(t) + " Spawner"));
        List<String> lore = new ArrayList<>();
        lore.add(p.color("&7Place it to start farming drops."));
        lore.add(p.color("&7Right-click a spawner with this to &fstack&7."));
        lore.add(p.color("&7Right-click a placed spawner to open it."));
        meta.setLore(lore);
        meta.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, t.name());
        it.setItemMeta(meta);
        return it;
    }

    private EntityType typeOf(ItemStack it) {
        if (it == null || it.getType() != Material.SPAWNER || !it.hasItemMeta()) return null;
        String s = it.getItemMeta().getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
        if (s == null) return null;
        try {
            return EntityType.valueOf(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void giveItem(Player pl, ItemStack item) {
        Map<Integer, ItemStack> left = pl.getInventory().addItem(item);
        for (ItemStack s : left.values()) pl.getWorld().dropItemNaturally(pl.getLocation(), s);
    }

    public void give(Player target, EntityType t, int amount) {
        int left = amount;
        while (left > 0) {
            int n = Math.min(64, left);
            giveItem(target, createItem(t, n));
            left -= n;
        }
        p.send(target, "&aYou received &f" + amount + "x " + nice(t) + " Spawner&a.");
    }

    // ================= production =================

    private void tick() {
        int mobsPer = p.getConfig().getInt("spawners.mobs-per-cycle", 4);
        Iterator<Spawner> it = spawners.values().iterator();
        while (it.hasNext()) {
            Spawner s = it.next();
            World w = Bukkit.getWorld(s.world);
            if (w == null || !w.isChunkLoaded(s.x >> 4, s.z >> 4)) continue;
            if (w.getBlockAt(s.x, s.y, s.z).getType() != Material.SPAWNER) {
                it.remove();
                continue;
            }
            SType st = types.get(s.type);
            if (st == null) continue;
            long cap = capacity(s);
            long stored = total(s);
            if (stored >= cap) continue;
            long mobs = (long) s.stack * mobsPer;
            s.xp += (long) Math.floor(mobs * st.xp + rnd.nextDouble());
            for (Drop d : st.drops) {
                double exp = mobs * d.chance * (d.min + d.max) / 2.0;
                long amt = (long) exp;
                if (rnd.nextDouble() < exp - amt) amt++;
                if (amt <= 0) continue;
                long room = cap - stored;
                if (room <= 0) break;
                amt = Math.min(amt, room);
                s.storage.merge(d.mat, amt, Long::sum);
                stored += amt;
            }
        }
    }

    // ================= world events =================

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (e.getBlockPlaced().getType() != Material.SPAWNER) return;
        EntityType t = typeOf(e.getItemInHand());
        if (t == null) return;
        Block b = e.getBlockPlaced();
        Spawner s = new Spawner();
        s.world = b.getWorld().getName();
        s.x = b.getX();
        s.y = b.getY();
        s.z = b.getZ();
        s.type = t;
        s.stack = 1;
        s.owner = e.getPlayer().getUniqueId();
        spawners.put(key(s), s);
        BlockState st = b.getState();
        if (st instanceof CreatureSpawner) {
            CreatureSpawner cs = (CreatureSpawner) st;
            cs.setSpawnedType(t);
            cs.update(true, false);
        }
        save();
        p.send(e.getPlayer(), "&a" + nice(t) + " spawner placed. &7Right-click it to collect drops, or right-click it with another one to stack.");
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpawn(SpawnerSpawnEvent e) {
        CreatureSpawner cs = e.getSpawner();
        if (cs != null && spawners.containsKey(key(cs.getBlock()))) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> b.getType() == Material.SPAWNER && spawners.containsKey(key(b)));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> b.getType() == Material.SPAWNER && spawners.containsKey(key(b)));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (b.getType() != Material.SPAWNER) return;
        Spawner s = spawners.get(key(b));
        if (s == null) return; // natural spawner: vanilla behaviour
        Player pl = e.getPlayer();
        if (p.getConfig().getBoolean("spawners.require-silk-touch", true)
                && !pl.getInventory().getItemInMainHand().containsEnchantment(Enchantment.SILK_TOUCH)) {
            e.setCancelled(true);
            p.send(pl, "&cYou need a &fSilk Touch &cpickaxe to break spawners.");
            return;
        }
        e.setDropItems(false);
        e.setExpToDrop(0);
        giveItem(pl, createItem(s.type, 1));
        if (s.stack > 1) {
            s.stack--;
            e.setCancelled(true);
            p.send(pl, "&aRemoved 1 spawner from the stack. &7(" + s.stack + " left)");
            save();
            return;
        }
        long money = sellStorage(pl, s);
        if (s.xp > 0) pl.giveExp((int) Math.min(Integer.MAX_VALUE, s.xp));
        spawners.remove(key(s));
        save();
        if (money > 0) p.send(pl, "&aStored drops were sold for &6" + p.fmt(money) + "&a.");
        p.send(pl, "&aSpawner picked up.");
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Block b = e.getClickedBlock();
        if (b == null || b.getType() != Material.SPAWNER) return;
        Spawner s = spawners.get(key(b));
        if (s == null) return;
        Player pl = e.getPlayer();
        ItemStack hand = e.getItem();

        if (hand != null && hand.getType().name().endsWith("_SPAWN_EGG")) {
            e.setCancelled(true);
            return;
        }
        EntityType held = typeOf(hand);
        if (held != null) {
            e.setCancelled(true);
            if (held != s.type) {
                p.send(pl, "&cThat's a different spawner type. You can only stack &f" + nice(s.type) + "&c.");
                return;
            }
            int max = p.getConfig().getInt("spawners.max-stack", 1000);
            if (s.stack >= max) {
                p.send(pl, "&cThis stack is full (&f" + max + "&c).");
                return;
            }
            int add = pl.isSneaking() ? hand.getAmount() : 1;
            add = Math.min(add, max - s.stack);
            s.stack += add;
            hand.setAmount(hand.getAmount() - add);
            save();
            p.send(pl, "&aStacked! This spawner is now &fx" + s.stack + "&a.");
            return;
        }
        if (pl.isSneaking() && hand != null && !hand.getType().isAir()) return; // let them place blocks
        e.setCancelled(true);
        openGui(pl, s);
    }

    // ================= spawner menu =================

    private void openGui(Player pl, Spawner s) {
        SpawnerHolder h = new SpawnerHolder();
        h.s = s;
        Inventory inv = Bukkit.createInventory(h, 54, p.color("&8" + nice(s.type) + " Spawner &7x" + s.stack));
        h.inv = inv;
        render(h);
        pl.openInventory(inv);
    }

    private void render(SpawnerHolder h) {
        Spawner s = h.s;
        Inventory inv = h.inv;
        inv.clear();
        h.slots.clear();

        List<Map.Entry<Material, Long>> list = new ArrayList<>(s.storage.entrySet());
        list.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        long worth = 0;
        for (Map.Entry<Material, Long> e : s.storage.entrySet()) {
            Long price = p.shop().price(e.getKey());
            if (price != null) worth += price * e.getValue();
        }
        for (int i = 0; i < 45 && i < list.size(); i++) {
            Material m = list.get(i).getKey();
            long count = list.get(i).getValue();
            ItemStack st = new ItemStack(m, (int) Math.max(1, Math.min(count, m.getMaxStackSize())));
            ItemMeta meta = st.getItemMeta();
            List<String> lore = new ArrayList<>();
            lore.add(p.color("&7Stored: &f" + String.format("%,d", count)));
            Long price = p.shop().price(m);
            if (price != null) lore.add(p.color("&7Worth: &6" + p.fmt(price * count)));
            lore.add("");
            lore.add(p.color("&eLeft-click: take 1 stack"));
            lore.add(p.color("&eShift+Left: take all"));
            meta.setLore(lore);
            meta.addItemFlags(ItemFlag.values());
            st.setItemMeta(meta);
            inv.setItem(i, st);
            h.slots.put(i, m);
        }

        SType def = types.get(s.type);
        ItemStack info = createItem(s.type, 1);
        ItemMeta im = info.getItemMeta();
        im.setDisplayName(p.color("&d&l" + nice(s.type) + " Spawner &7x" + s.stack));
        List<String> il = new ArrayList<>();
        il.add(p.color("&7Stack: &f" + s.stack));
        il.add(p.color("&7Storage: &f" + String.format("%,d", total(s)) + "&7/&f" + String.format("%,d", capacity(s))));
        if (def != null) il.add(p.color("&7Income: &6~" + p.fmt((long) (incomePerHour(def) * s.stack)) + "&7/hour"));
        il.add(p.color("&7Break with Silk Touch to remove 1."));
        im.setLore(il);
        info.setItemMeta(im);
        inv.setItem(45, info);

        ItemStack sell = new ItemStack(Material.GOLD_INGOT);
        ItemMeta sm = sell.getItemMeta();
        sm.setDisplayName(p.color("&6&lSell all drops"));
        List<String> sl = new ArrayList<>();
        sl.add(p.color("&7Worth: &6" + p.fmt(worth)));
        sm.setLore(sl);
        sell.setItemMeta(sm);
        inv.setItem(47, sell);

        ItemStack xp = new ItemStack(Material.EXPERIENCE_BOTTLE);
        ItemMeta xm = xp.getItemMeta();
        xm.setDisplayName(p.color("&a&lCollect XP"));
        List<String> xl = new ArrayList<>();
        xl.add(p.color("&7Stored XP: &f" + String.format("%,d", s.xp)));
        xm.setLore(xl);
        xp.setItemMeta(xm);
        inv.setItem(49, xp);
    }

    private long sellStorage(Player pl, Spawner s) {
        long total = 0;
        Iterator<Map.Entry<Material, Long>> it = s.storage.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Material, Long> e = it.next();
            Long price = p.shop().price(e.getKey());
            if (price == null) continue;
            total += price * e.getValue();
            it.remove();
        }
        if (total > 0) p.eco().add(pl.getUniqueId(), total);
        return total;
    }

    private void withdraw(Player pl, Spawner s, Material m, boolean all) {
        long have = s.storage.getOrDefault(m, 0L);
        if (have <= 0) return;
        int stackSize = m.getMaxStackSize();
        long want = all ? have : Math.min(have, stackSize);
        long given = 0;
        while (given < want) {
            int chunk = (int) Math.min(stackSize, want - given);
            Map<Integer, ItemStack> left = pl.getInventory().addItem(new ItemStack(m, chunk));
            int notGiven = 0;
            for (ItemStack l : left.values()) notGiven += l.getAmount();
            given += chunk - notGiven;
            if (notGiven > 0) break;
        }
        if (given <= 0) {
            p.send(pl, "&cYour inventory is full.");
            return;
        }
        long remain = have - given;
        if (remain <= 0) s.storage.remove(m);
        else s.storage.put(m, remain);
    }

    // ================= spawner shop =================

    public void openShop(Player pl) {
        SpawnerShopHolder h = new SpawnerShopHolder();
        h.list.addAll(types.values());
        int rows = Math.min(6, (h.list.size() / 9) + 2);
        Inventory inv = Bukkit.createInventory(h, rows * 9, p.color("&d&lSpawner Shop"));
        h.inv = inv;
        for (int i = 0; i < h.list.size() && i < (rows - 1) * 9; i++) {
            SType st = h.list.get(i);
            ItemStack it = createItem(st.type, 1);
            ItemMeta m = it.getItemMeta();
            List<String> lore = new ArrayList<>();
            lore.add("");
            lore.add(p.color("&7Price: &6" + p.fmt(st.price)));
            lore.add(p.color("&7Income: &a~" + p.fmt((long) incomePerHour(st)) + "&7/hour each"));
            lore.add(p.color("&7XP per mob: &f" + st.xp));
            lore.add("");
            lore.add(p.color("&eLeft-click: buy 1"));
            lore.add(p.color("&eRight-click: buy 5"));
            lore.add(p.color("&eShift+Left: buy 10"));
            m.setLore(lore);
            it.setItemMeta(m);
            inv.setItem(i, it);
        }
        ItemStack back = new ItemStack(Material.BARRIER);
        ItemMeta bm = back.getItemMeta();
        bm.setDisplayName(p.color("&cBack to shop"));
        back.setItemMeta(bm);
        inv.setItem(rows * 9 - 5, back);
        pl.openInventory(inv);
    }

    private void buy(Player pl, SType st, int qty) {
        long total = st.price * qty;
        if (!p.eco().take(pl.getUniqueId(), total)) {
            p.send(pl, "&cYou need &6" + p.fmt(total) + "&c.");
            return;
        }
        int left = qty;
        while (left > 0) {
            int n = Math.min(64, left);
            giveItem(pl, createItem(st.type, n));
            left -= n;
        }
        p.send(pl, "&aBought &f" + qty + "x " + nice(st.type) + " Spawner &afor &6" + p.fmt(total) + "&a.");
    }

    // ================= GUI clicks =================

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        InventoryHolder holder = top.getHolder();
        boolean mine = holder instanceof SpawnerHolder || holder instanceof SpawnerShopHolder;
        if (!mine) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player)) return;
        Player pl = (Player) e.getWhoClicked();
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= top.getSize()) return;

        if (holder instanceof SpawnerShopHolder) {
            SpawnerShopHolder h = (SpawnerShopHolder) holder;
            if (slot == top.getSize() - 5) {
                Bukkit.getScheduler().runTask(p, () -> p.shop().openMain(pl));
                return;
            }
            if (slot >= h.list.size()) return;
            SType st = h.list.get(slot);
            ClickType ct = e.getClick();
            if (ct == ClickType.LEFT) buy(pl, st, 1);
            else if (ct == ClickType.RIGHT) buy(pl, st, 5);
            else if (ct == ClickType.SHIFT_LEFT) buy(pl, st, 10);
            return;
        }

        SpawnerHolder h = (SpawnerHolder) holder;
        if (slot < 45) {
            Material m = h.slots.get(slot);
            if (m == null) return;
            withdraw(pl, h.s, m, e.getClick() == ClickType.SHIFT_LEFT);
            render(h);
        } else if (slot == 47) {
            long money = sellStorage(pl, h.s);
            if (money > 0) p.send(pl, "&aSold all drops for &6" + p.fmt(money) + "&a.");
            else p.send(pl, "&7Nothing to sell.");
            render(h);
        } else if (slot == 49) {
            if (h.s.xp > 0) {
                pl.giveExp((int) Math.min(Integer.MAX_VALUE, h.s.xp));
                p.send(pl, "&aCollected &f" + String.format("%,d", h.s.xp) + " &aXP.");
                h.s.xp = 0;
                render(h);
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        InventoryHolder h = e.getView().getTopInventory().getHolder();
        if (h instanceof SpawnerHolder || h instanceof SpawnerShopHolder) e.setCancelled(true);
    }
}
