package dev.minecp.colony;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.BannerPatternLayers;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.block.Banner;
import org.bukkit.block.Block;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.banner.PatternType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BannerMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A colony's flag (the mod's colonyFlag): a base colour and up to 6 patterns, white to begin with. Shown on colony
 * banners, stood in for by white banners tagged {@link #KEY}, and on knights' shields.
 */
public final class Flag {
    /** Tag on colony banners: on an item and in a blueprint (written by the converter) empty, on a placed one its colony's owner. */
    public static final NamespacedKey KEY = NamespacedKey.fromString("minecp:colony_banner");

    private DyeColor base = DyeColor.WHITE;
    private List<Pattern> patterns = List.of();

    /** The flag as a banner item. */
    public ItemStack banner() {
        return banner(base, patterns);
    }

    public static ItemStack banner(DyeColor base, List<Pattern> patterns) {
        ItemStack item = new ItemStack(Material.valueOf(base + "_BANNER"));
        item.editMeta(BannerMeta.class, m -> m.setPatterns(patterns));
        return item;
    }

    /** Takes over the design of a banner item. @return whether it is another one than before. */
    public boolean set(ItemStack banner) {
        DyeColor newBase = base(banner);
        List<Pattern> newPatterns = patterns(banner);
        if (newBase == base && newPatterns.equals(patterns)) return false;
        base = newBase;
        patterns = newPatterns;
        return true;
    }

    public static DyeColor base(ItemStack banner) {
        return DyeColor.valueOf(banner.getType().name().replace("_BANNER", ""));
    }

    public static List<Pattern> patterns(ItemStack banner) {
        return List.copyOf(((BannerMeta) banner.getItemMeta()).getPatterns());
    }

    // ---- colony banners --------------------------------------------------------------------------------------------

    /** The colony banner item: it shows the flag once placed. */
    public static ItemStack item() {
        ItemStack item = new ItemStack(Material.WHITE_BANNER);
        item.editMeta(m -> {
            m.itemName(Component.text("Colony Banner", NamedTextColor.GOLD));
            m.lore(List.of(Component.text("Shows the colony's flag once placed", NamedTextColor.GRAY)));
            m.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, "");
        });
        return item;
    }

    public static boolean is(ItemStack item) {
        return item != null && item.getPersistentDataContainer().has(KEY);
    }

    public static boolean is(Block b) {
        return Tag.BANNERS.isTagged(b.getType()) && b.getState(false) instanceof Banner f && f.getPersistentDataContainer().has(KEY);
    }

    /** The owner of the colony a placed colony banner belongs to, or null. */
    public static UUID owner(Block b) {
        if (!is(b)) return null;
        String id = ((Banner) b.getState(false)).getPersistentDataContainer().get(KEY, PersistentDataType.STRING);
        return id == null || id.isEmpty() ? null : UUID.fromString(id);
    }

    /** Makes a placed banner one of {@code colony}'s, showing its flag; with none, a colony banner of none yet. */
    public static void tag(Block b, Colony colony) {
        if (!(b.getState() instanceof Banner f)) return;
        f.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, colony == null ? "" : colony.owner.toString());
        f.update(true, false);
        if (colony != null) colony.flag.paint(b);
    }

    /** Shows the flag on a placed banner. The block stays white and the base colour is a pattern, so a builder still finds the block it placed. */
    public void paint(Block b) {
        if (!(b.getState() instanceof Banner f)) return;
        List<Pattern> all = new ArrayList<>();
        if (base != DyeColor.WHITE) all.add(new Pattern(base, PatternType.BASE));
        all.addAll(patterns);
        if (f.getPatterns().equals(all)) return;
        f.setPatterns(all);
        f.update(true, false);
    }

    /** Shows the flag on a shield. */
    public void paint(ItemStack shield) {
        BannerPatternLayers had = shield.getData(DataComponentTypes.BANNER_PATTERNS);
        if (shield.getData(DataComponentTypes.BASE_COLOR) == base && (had == null ? List.of() : had.patterns()).equals(patterns)) return;
        shield.setData(DataComponentTypes.BASE_COLOR, base);
        shield.setData(DataComponentTypes.BANNER_PATTERNS, BannerPatternLayers.bannerPatternLayers(patterns));
    }

    // ---- saving ----------------------------------------------------------------------------------------------------

    void save(ConfigurationSection s) {
        s.set("base", base.name());
        s.set("patterns", patterns.stream().map(p -> p.getColor() + " " + registry().getKeyOrThrow(p.getPattern())).toList());
    }

    void load(ConfigurationSection s) {
        base = DyeColor.valueOf(s.getString("base", base.name()));
        List<Pattern> read = new ArrayList<>();
        for (String one : s.getStringList("patterns")) {
            String[] p = one.split(" ");
            PatternType type = registry().get(NamespacedKey.fromString(p[1]));
            if (type != null) read.add(new Pattern(DyeColor.valueOf(p[0]), type)); // null: a data pack's pattern that is gone
        }
        patterns = List.copyOf(read);
    }

    private static org.bukkit.Registry<PatternType> registry() {
        return RegistryAccess.registryAccess().getRegistry(RegistryKey.BANNER_PATTERN);
    }
}
