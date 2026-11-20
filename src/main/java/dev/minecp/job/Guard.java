package dev.minecp.job;

import dev.minecp.citizen.Colonist;
import dev.minecp.colony.Colony;
import dev.minecp.colony.Hut;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A knight at a Guard Tower (the mod's JobKnight, EntityAIMelee, MeleeCombatAI and AbstractEntityAIGuard): it patrols
 * between the colony's built huts, attacks the hostile mobs it sees with a sword, and wears the armor its tower level
 * allows. It doesn't sleep at night or shelter from rain, and it doesn't run when hurt.
 */
public final class Guard extends Job {
    // The mod's numbers (GuardConstants, AbstractBuildingGuards, BuildingGuardTower):
    private static final int ATTACK_DELAY = 32;        // KNIGHT_ATTACK_DELAY_BASE: ticks between hits (no skills: no faster)
    private static final double REACH = 2;             // MAX_DISTANCE_FOR_ATTACK
    private static final double BASE_DAMAGE = 3;       // BASE_PHYSICAL_DAMAGE, on top of the sword's
    private static final int VISION_BASE = 15, VISION_PER_LEVEL = 3; // BASE_VISION_RANGE + VISION_RANGE_PER_LEVEL x level
    private static final int Y_VISION = 3;
    private static final int CHASE = 80;               // MAX_PATROL_DERIVATION: how far from the tower it chases
    private static final int HP_KNIGHT = 15, HP_TOWER = 20, HP_PER_LEVEL = 2; // KNIGHT_HP_BONUS, BONUS_HP_SINGLE_GUARD, per level
    private static final int PATROL_WAIT = 100;        // GUARD_TASK_INTERVAL: ticks at a patrol point
    private static final int SEARCH_TICKS = 20;        // ours: how often it looks for a target
    // The armor each tower level allows (AbstractEntityAIFight.itemsNeeded): leather 0, gold 1, chain 2, iron 3, diamond 4, netherite 5.
    private static final int[][] ARMOR = {{0, 1}, {0, 2}, {0, 3}, {2, 4}, {3, 5}};
    private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private final Colony colony;
    private final Map<EquipmentSlot, ItemStack> armor = new EnumMap<>(EquipmentSlot.class); // worn; saved
    private LivingEntity target;
    private Hut patrolTo;
    private int attackIn, searchIn, waitAt;
    private Location chasing; // where its route to the target leads

    public Guard(Hut hut, Colony colony) {
        super(hut);
        this.colony = colony;
    }

    /** Health with the knight's and the tower's bonuses (the mod adds them as max health modifiers). */
    @Override
    public double maxHealth() {
        return Colonist.MAX_HEALTH + HP_KNIGHT + (hut.level >= 1 ? HP_TOWER + HP_PER_LEVEL * hut.level : 0);
    }

    /** What reaches it through its armor (vanilla's armor formula with each piece's points and toughness). */
    @Override
    public double afterArmor(double damage) {
        double points = 0, toughness = 0;
        for (ItemStack piece : armor.values()) { points += stat(piece, Attribute.ARMOR); toughness += stat(piece, Attribute.ARMOR_TOUGHNESS); }
        double cut = Math.min(20, Math.max(points / 5, points - damage / (2 + toughness / 4)));
        return damage * (1 - cut / 25);
    }

    /** Something hurt it: it fights back (a mob; players are left to permissions) instead of running. */
    @Override
    public boolean fightsBack(Entity source) {
        if (source instanceof LivingEntity l && !(source instanceof org.bukkit.entity.Player) && colony.colonistOf(source) == null && valid(l)) target = l;
        return true;
    }

    @Override
    public boolean sleepsAtNight() {
        return false; // on duty (the mod's guards take short naps instead)
    }

    @Override
    public boolean worksInRain() {
        return true;
    }

    @Override
    public List<Component> info(Colony c) {
        if (hut.level == 0) return List.of(Component.text("The knight starts once level 1 is built.", NamedTextColor.GRAY));
        int[] tier = ARMOR[Math.min(hut.level, Hut.MAX_LEVEL) - 1];
        return List.of(Component.text("Patrols the colony and fights the monsters it sees within " + vision() + " blocks. Wears "
                + armorName(tier[0]) + " to " + armorName(tier[1]) + " armor and " + toolLabel(Tool.SWORD) + ".", NamedTextColor.GRAY));
    }

    // ---- gear ---------------------------------------------------------------------------------------------------

    @Override
    public List<Want> wants() {
        List<Want> wants = new ArrayList<>();
        if (hut.level < 1) return wants;
        wantTool(wants, Tool.SWORD);
        for (EquipmentSlot slot : SLOTS) // the mod's guards ask for each missing piece; one the tower no longer allows counts as missing
            if (!fitsArmor(armor.get(slot), slot) && inChest(i -> fitsArmor(i, slot)) == 0)
                wants.add(new Want(slotName(slot) + " (" + armorName(tier()[0]) + " to " + armorName(tier()[1]) + ")", i -> fitsArmor(i, slot), 1));
        return wants;
    }

    @Override
    int keep(ItemStack item) {
        if (fits(Tool.SWORD, item, null)) return 1;
        for (EquipmentSlot slot : SLOTS) if (fitsArmor(item, slot)) return 1;
        return 0;
    }

    private int[] tier() {
        return ARMOR[Math.max(1, Math.min(hut.level, Hut.MAX_LEVEL)) - 1];
    }

    private boolean fitsArmor(ItemStack i, EquipmentSlot slot) {
        if (i == null || i.isEmpty() || i.getType().getEquipmentSlot() != slot || stat(i, Attribute.ARMOR) <= 0) return false;
        int t = armorTier(i.getType());
        return t >= tier()[0] && t <= tier()[1];
    }

    /**
     * Whether a trip to the tower would change its gear: a sword when it has none, a worn piece the tower level no
     * longer allows, or a better piece the tower allows in the chest.
     */
    private boolean gearWaiting() {
        if (!hasTool(Tool.SWORD, null) && inChest(i -> fits(Tool.SWORD, i, null)) > 0) return true;
        for (EquipmentSlot slot : SLOTS) {
            ItemStack worn = armor.get(slot);
            if (worn != null && !fitsArmor(worn, slot)) return true;
            if (inChest(i -> fitsArmor(i, slot) && better(i, worn)) > 0) return true;
        }
        return false;
    }

    private boolean better(ItemStack piece, ItemStack worn) {
        return worn == null || stat(piece, Attribute.ARMOR) > stat(worn, Attribute.ARMOR);
    }

    /** At the tower: takes a sword it lacks, puts back armor the tower no longer allows, and wears the best pieces allowed. */
    private void gearUp() {
        if (!hasTool(Tool.SWORD, null)) fetchTool(Tool.SWORD, null);
        Inventory chest = chest();
        if (chest == null) return;
        for (EquipmentSlot slot : SLOTS) {
            ItemStack worn = armor.get(slot);
            if (worn != null && !fitsArmor(worn, slot)) { putBack(chest, worn); armor.remove(slot); worn = null; } // the tower level changed
            for (int i = 0; i < chest.getSize(); i++) {
                ItemStack c = chest.getItem(i);
                if (!fitsArmor(c, slot) || !better(c, worn)) continue;
                armor.put(slot, c.asOne());
                c.subtract();
                chest.setItem(i, c.isEmpty() ? null : c);
                if (worn != null) putBack(chest, worn);
                worn = armor.get(slot);
            }
        }
    }

    /** Into the chest; what doesn't fit drops at the hut, so no armor is ever lost. */
    private void putBack(Inventory chest, ItemStack item) {
        for (ItemStack over : chest.addItem(item).values())
            hut.block.getWorld().dropItemNaturally(hut.block.getLocation().toCenterLocation(), over);
    }

    /** Shows its armor and sword on the body (it is spawned again when its chunk loads). */
    private void dress(Colonist w) {
        if (w.living() == null) return;
        EntityEquipment e = w.living().getEquipment();
        for (EquipmentSlot slot : SLOTS) {
            ItemStack want = armor.get(slot);
            if (!java.util.Objects.equals(e.getItem(slot).isEmpty() ? null : e.getItem(slot), want)) e.setItem(slot, want);
        }
        ItemStack sword = tools.get(Tool.SWORD);
        if (!java.util.Objects.equals(e.getItemInMainHand().isEmpty() ? null : e.getItemInMainHand(), sword)) w.hold(sword);
    }

    // ---- work ---------------------------------------------------------------------------------------------------

    @Override
    public void tick(Colonist w) {
        dress(w);
        if (hut.level < 1) {
            w.setStatus("Knight: idle");
            w.wander(hut.block.getLocation().toCenterLocation());
            return;
        }
        if (attackIn > 0) attackIn--;
        if (!valid(target)) target = null;
        if (target == null && --searchIn <= 0) { searchIn = SEARCH_TICKS; target = nearestEnemy(w); }
        if (target != null) { fight(w); return; }
        if (w.isMoving() || --cooldown > 0) return;

        if (gearWaiting()) {
            if (!atHut(w)) { w.setStatus("Knight: getting gear"); goToHut(w); return; }
            gearUp();
        }
        note = hasTool(Tool.SWORD, null) ? null : "No sword: it fights with its fists. Put " + toolLabel(Tool.SWORD) + " in the tower's chest.";
        patrol(w);
    }

    /** Walks between the colony's built huts at random (the mod's automatic patrol), waiting a while at each. */
    private void patrol(Colonist w) {
        w.setStatus("Knight: patrolling");
        if (patrolTo == null || !colony.huts.contains(patrolTo)) patrolTo = nextPatrolPoint();
        if (patrolTo == null) { w.wander(hut.block.getLocation().toCenterLocation()); return; }
        if (!w.isBeside(patrolTo.block)) { w.goBeside(patrolTo.block); return; }
        if (++waitAt < PATROL_WAIT / 20) { cooldown = 20; return; } // tick() counts down cooldown once per tick
        waitAt = 0;
        patrolTo = nextPatrolPoint();
    }

    private Hut nextPatrolPoint() {
        List<Hut> built = colony.huts.stream().filter(h -> h.level >= 1).toList();
        return built.isEmpty() ? null : built.get(ThreadLocalRandom.current().nextInt(built.size()));
    }

    private void fight(Colonist w) {
        w.setStatus("Knight: fighting");
        Location at = w.location(), there = target.getLocation();
        if (at.distance(there) > REACH) {
            // A new route only when it stopped or the target has moved away from where it was heading: each search costs time.
            if (!w.isMoving() || chasing == null || chasing.distance(there) > 2) { w.walk(there); chasing = there.clone(); }
            return;
        }
        chasing = null;
        w.stopWalking();
        if (attackIn > 0) return;
        attackIn = ATTACK_DELAY;
        w.swing();
        at.getWorld().playSound(at, Sound.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.NEUTRAL, 1, 1);
        target.damage(damage(), w.living());
        if (tools.containsKey(Tool.SWORD)) wear(Tool.SWORD, w);
    }

    /** The sword's attack damage (as the mod's SwordItem.getDamage) plus the mod's base 3; with no sword, the base alone. */
    private double damage() {
        ItemStack sword = tools.get(Tool.SWORD);
        return BASE_DAMAGE + (sword == null ? 0 : stat(sword, Attribute.ATTACK_DAMAGE));
    }

    private int vision() {
        return VISION_BASE + VISION_PER_LEVEL * hut.level;
    }

    /** Alive, on the colony's land, and not too far from the tower to chase (the mod: only the distance). */
    private boolean valid(LivingEntity e) {
        if (e == null || !e.isValid() || e.isDead() || !e.getWorld().equals(hut.block.getWorld())) return false;
        Location l = e.getLocation();
        return l.distance(hut.block.getLocation()) <= CHASE && colony.claims().contains(dev.minecp.colony.Claims.key(l.getBlockX() >> 4, l.getBlockZ() >> 4));
    }

    private LivingEntity nearestEnemy(Colonist w) {
        int r = vision();
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity e : w.location().getNearbyEntities(r, Y_VISION, r)) {
            if (!(e instanceof Enemy) || !(e instanceof LivingEntity l) || !valid(l)) continue;
            double d = e.getLocation().distanceSquared(w.location());
            if (d < bestD) { bestD = d; best = l; }
        }
        return best;
    }

    // ---- gear numbers --------------------------------------------------------------------------------------------

    /** An item's own value for an attribute when worn or held where it goes (vanilla's default modifiers). */
    private static double stat(ItemStack i, Attribute attribute) {
        if (i == null || i.isEmpty()) return 0;
        EquipmentSlot slot = i.getType().getEquipmentSlot();
        double n = 0;
        for (var m : i.getType().getDefaultAttributeModifiers(slot).get(attribute)) n += m.getAmount();
        return n;
    }

    /** The tower-level rule's armor tiers (the mod's armor levels): leather 0, gold 1, chainmail 2, iron 3, diamond 4, netherite 5. */
    private static int armorTier(Material m) {
        String n = m.name();
        return n.startsWith("LEATHER_") ? 0 : n.startsWith("GOLDEN_") || n.startsWith("COPPER_") ? 1 : n.startsWith("CHAINMAIL_") ? 2
                : n.startsWith("IRON_") ? 3 : n.startsWith("DIAMOND_") ? 4 : n.startsWith("NETHERITE_") ? 5 : -1; // copper (newer than the mod) as gold, as tools count it as stone
    }

    private static String armorName(int tier) {
        return new String[]{"leather", "gold", "chainmail", "iron", "diamond", "netherite"}[tier];
    }

    private static String slotName(EquipmentSlot s) {
        return switch (s) {
            case HEAD -> "a helmet";
            case CHEST -> "a chestplate";
            case LEGS -> "leggings";
            default -> "boots";
        };
    }

    // ---- saving and ending ---------------------------------------------------------------------------------------

    @Override
    public void save(org.bukkit.configuration.ConfigurationSection s, Colony c) {
        super.save(s, c);
        armor.forEach((slot, item) -> s.set("armor." + slot.name(), Base64.getEncoder().encodeToString(item.serializeAsBytes())));
    }

    @Override
    public void load(org.bukkit.configuration.ConfigurationSection s, Colony c) {
        super.load(s, c);
        var as = s.getConfigurationSection("armor");
        if (as != null) for (String slot : as.getKeys(false))
            armor.put(EquipmentSlot.valueOf(slot), ItemStack.deserializeBytes(Base64.getDecoder().decode(as.getString(slot))));
    }

    @Override
    public void dropCarried() {
        super.dropCarried();
        Location at = hut.block.getLocation().toCenterLocation();
        armor.values().forEach(i -> at.getWorld().dropItemNaturally(at, i));
        armor.clear();
    }

    @Override
    public List<ItemStack> takeCarried() {
        List<ItemStack> all = super.takeCarried();
        all.addAll(armor.values());
        armor.clear();
        return all;
    }
}
