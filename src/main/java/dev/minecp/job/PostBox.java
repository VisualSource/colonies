package dev.minecp.job;

import dev.minecp.citizen.Colonist;
import dev.minecp.colony.Colony;
import dev.minecp.colony.Hut;
import dev.minecp.research.Effects;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.PotionMeta;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * The mod's postbox: players order items, which couriers bring from the warehouse (or crafters make) into its chest,
 * and it keeps a minimum stock of up to 5 items (its MinimumStockModule at its fixed level 1). Nobody works here.
 * Orders of the same item add up (the mod keeps one request per order).
 */
public final class PostBox extends Job {
    /** The mod's limit on one order (its quantity field allows up to 1000). */
    public static final int MAX_ORDER = 1000;
    /** Items it keeps in stock: the mod's 5 per building level, its level always 1. */
    private static final int STOCK_PER_LEVEL = 5;
    /** Items told apart by their data (which enchantment, which potion); any other item matches by type, worn or renamed. */
    private static final Set<Material> BY_DATA = Set.of(Material.ENCHANTED_BOOK, Material.POTION, Material.SPLASH_POTION,
            Material.LINGERING_POTION, Material.TIPPED_ARROW);

    /**
     * An order: {@code amount} more of {@code item}; {@code min} is the least a delivery may bring (1: "deliver what's
     * available"). {@code seen}: how many the chest held when last looked at; what it gains counts against the order.
     */
    public static final class Order {
        public final ItemStack item;
        public int amount;
        int min, seen;

        Order(ItemStack item, int amount, int min, int seen) {
            this.item = item;
            this.amount = amount;
            this.min = min;
            this.seen = seen;
        }
    }

    /** A minimum stock: keep {@code stacks} stacks of {@code item} in the chest. */
    public record Stock(ItemStack item, int stacks) {}

    public final List<Order> orders = new ArrayList<>();
    public final List<Stock> stock = new ArrayList<>();

    public PostBox(Hut hut, Colony colony) {
        super(hut, colony);
    }

    @Override
    public void tick(Colonist w) {}

    /**
     * What an order or stock of {@code held} is for: the item itself for {@link #BY_DATA}; one of MineCP's own items
     * (a mesh, compost, a rack, a hut) as a new one of its kind; else just its type.
     */
    public static ItemStack orderable(ItemStack held) {
        if (BY_DATA.contains(held.getType())) return held.asOne();
        if (ours(held).isEmpty()) return new ItemStack(held.getType());
        ItemStack fresh = held.asOne();
        fresh.editMeta(org.bukkit.inventory.meta.Damageable.class, d -> d.setDamage(0)); // a worn mesh orders a new one
        return fresh;
    }

    /**
     * Whether {@code i} fills an order for {@code wanted}: the same enchantment or potion for {@link #BY_DATA}; else the
     * same type and the same kind of MineCP item, or a plain one for a plain order (paper isn't a mesh, nor a mesh paper).
     */
    static boolean same(ItemStack wanted, ItemStack i) {
        if (i == null) return false;
        if (BY_DATA.contains(wanted.getType())) return wanted.isSimilar(i);
        return i.getType() == wanted.getType() && ours(i).equals(ours(wanted));
    }

    /** MineCP's tags on an item, with their text values ("minecp:mesh=IRON"), sorted; empty for a plain item. */
    private static String ours(ItemStack item) {
        var pdc = item.getPersistentDataContainer();
        return pdc.getKeys().stream().filter(k -> k.getNamespace().equals(dev.minecp.MineCP.NAMESPACE))
                .map(k -> k + "=" + (pdc.has(k, org.bukkit.persistence.PersistentDataType.STRING) ? pdc.get(k, org.bukkit.persistence.PersistentDataType.STRING) : ""))
                .sorted().reduce("", (a, b) -> a + b + ";");
    }

    /** Orders {@code amount} of {@code item} (from {@link #orderable}); {@code partial}: deliveries may bring what the warehouse has. */
    public void order(ItemStack item, int amount, boolean partial) {
        for (Order o : orders) if (same(o.item, item)) {
            o.amount += amount; // each order is at most MAX_ORDER, as the mod's; together they may be more
            o.min = partial || o.min == 1 ? 1 : o.min + amount; // a partial order lets the whole of it come in parts (ours)
            return;
        }
        orders.add(new Order(item, amount, partial ? 1 : amount, inChest(i -> same(item, i))));
    }

    public void cancel(Order o) {
        orders.remove(o);
    }

    /** How many items it can keep in stock: more with research (the mod's minimumStockSize). */
    public int maxStock() {
        return (int) (STOCK_PER_LEVEL * (1 + colony.research.effect(Effects.MINIMUM_STOCK)));
    }

    /** Keeps {@code stacks} stacks of {@code item}; replaces its earlier amount. @return false if all {@link #maxStock} are taken. */
    public boolean keepStocked(ItemStack item, int stacks) {
        stock.removeIf(s -> same(s.item, item));
        if (stock.size() >= maxStock()) return false;
        stock.add(new Stock(item, stacks));
        return true;
    }

    public void stopStocking(Stock s) {
        stock.remove(s);
    }

    /**
     * Its requests, read again each second. What the chest gained since then, from couriers or players, counts against
     * the orders first (ours: items taken out and put back count again). An item also on order isn't asked for as stock
     * meanwhile, so a delivery never counts for both.
     */
    @Override
    public List<Want> wants() {
        if (chest() != null) for (Order o : orders) { // no chest (broken meanwhile): nothing gained or lost
            int now = inChest(i -> same(o.item, i));
            if (now > o.seen) o.amount -= now - o.seen;
            o.seen = now;
        }
        orders.removeIf(o -> o.amount <= 0);
        List<Want> wants = new ArrayList<>();
        for (Order o : orders) wants.add(new Want(label(o.item), i -> same(o.item, i), o.amount, o.min));
        for (Stock s : stock) { // the mod's MinimumStack: up to a stack at a time, minimum 1
            if (orders.stream().anyMatch(o -> same(o.item, s.item))) continue;
            int max = s.item.getMaxStackSize(), missing = s.stacks * max - inChest(i -> same(s.item, i));
            if (missing > 0) wants.add(new Want(label(s.item) + " (stock)", i -> same(s.item, i), Math.min(max, missing), 1));
        }
        return wants;
    }

    /** Its name in requests and menus, which also tells requests apart: "enchanted book (sharpness 5)". */
    public static String label(ItemStack item) {
        if (!ours(item).isEmpty() && item.getItemMeta().hasItemName()) // MineCP's own item: its name ("Iron Sifter Mesh")
            return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().itemName());
        String name = name(item.getType());
        if (item.getItemMeta() instanceof EnchantmentStorageMeta m && m.hasStoredEnchants())
            return name + " (" + String.join(", ", m.getStoredEnchants().entrySet().stream()
                    .map(e -> e.getKey().getKey().getKey().replace('_', ' ') + " " + e.getValue()).sorted().toList()) + ")";
        if (item.getItemMeta() instanceof PotionMeta m && m.getBasePotionType() != null)
            return name + " (" + m.getBasePotionType().getKey().getKey().replace('_', ' ') + ")";
        return name;
    }

    @Override
    public void save(ConfigurationSection s, Colony c) {
        super.save(s, c);
        int i = 0;
        for (Order o : orders) {
            String k = "orders." + i++ + ".";
            s.set(k + "item", encode(o.item));
            s.set(k + "amount", o.amount);
            s.set(k + "min", o.min);
            s.set(k + "seen", o.seen);
        }
        i = 0;
        for (Stock st : stock) {
            s.set("stock." + i + ".item", encode(st.item));
            s.set("stock." + i++ + ".stacks", st.stacks);
        }
    }

    @Override
    public void load(ConfigurationSection s, Colony c) {
        super.load(s, c);
        var os = s.getConfigurationSection("orders");
        if (os != null) for (String k : os.getKeys(false))
            orders.add(new Order(decode(os.getString(k + ".item")), os.getInt(k + ".amount"), os.getInt(k + ".min"), os.getInt(k + ".seen")));
        var ss = s.getConfigurationSection("stock");
        if (ss != null) for (String k : ss.getKeys(false)) stock.add(new Stock(decode(ss.getString(k + ".item")), ss.getInt(k + ".stacks")));
    }

    private static String encode(ItemStack i) {
        return Base64.getEncoder().encodeToString(i.serializeAsBytes());
    }

    private static ItemStack decode(String s) {
        return ItemStack.deserializeBytes(Base64.getDecoder().decode(s));
    }
}
