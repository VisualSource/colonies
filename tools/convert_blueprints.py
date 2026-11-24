"""Converts a MineColonies/Structurize style pack (.blueprint files) into a data pack of vanilla structure .nbt files.

Usage:
  python3 tools/convert_blueprints.py <pack_dir> <datapack_dir> <report.md> [--blocks <blocks.json>] [--pack-name NAME]

<datapack_dir> is the data pack to (re)write, e.g. run/world/datapacks/minecp-medievaloak. Structures go to
data/minecp/structure/<pack>/..., so they are minecp:<pack>/<path>. Re-running overwrites the data pack freely:
hand-fixed copies saved with a structure block live in world/generated/minecp/structure and take priority over it.

<blocks.json> defaults to tools/blocks-26.3.json, trimmed from the vanilla block report
(java -DbundlerMainClass=net.minecraft.data.Main -jar server.jar --reports).
Non-vanilla blocks are swapped for the closest vanilla block and listed in the report so they can be fixed by hand.
"""
import argparse
import collections
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import nbt  # noqa: E402

VOID = None  # structure void: the builder leaves whatever is in the world there

# Domum Ornamentum shape -> vanilla block family made from the block's main material.
DOMUM_FAMILY = {
    "shingle": "stairs", "vanilla_stairs_compat": "stairs",
    "shingle_slab": "slab", "vanilla_slab_compat": "slab",
    "vanilla_fence_compat": "fence", "vanilla_wall_compat": "wall",
    "vanilla_fence_gate_compat": "fence_gate", "fancy_door": "door",
    "panel": "trapdoor", "fancy_trapdoors": "trapdoor", "vanilla_trapdoors_compat": "trapdoor",
    "plain": "block", "framed": "block", "side_framed": "block", "side_framed_horizontal": "block",
    "horizontal_plain": "block", "one_crossed_lr": "block", "one_crossed_rl": "block", "double_crossed": "block",
    "squarepillar": "block", "blockypillar": "block", "up_gated": "block", "down_gated": "block",
}
# Domum blocks with a fixed look, and MineColonies/Structurize blocks: name -> vanilla block (or VOID).
FIXED = {
    "domum_ornamentum:blockbarreldeco_standing": "minecraft:barrel",
    "domum_ornamentum:blockbarreldeco_onside": "minecraft:barrel",
    "domum_ornamentum:blockpaperwall": "minecraft:white_stained_glass_pane",
    "domum_ornamentum:brown_bricks": "minecraft:bricks",
    "domum_ornamentum:cream_bricks": "minecraft:smooth_sandstone",
    "domum_ornamentum:beige_bricks": "minecraft:sandstone",
    "domum_ornamentum:cobblestone_extra": "minecraft:cobblestone",
    "domum_ornamentum:fancy_light": "minecraft:lantern",
    "domum_ornamentum:center_light": "minecraft:lantern",
    "structurize:blocksubstitution": VOID,
    "structurize:blocksolidsubstitution": "minecraft:barrier",  # our builder: dirt only where not already solid
    "structurize:blockfluidsubstitution": VOID,
    "structurize:blocktagsubstitution": VOID,
    "minecolonies:blockminecoloniesrack": "minecraft:chest",
    "minecolonies:blockstash": "minecraft:chest",
    "minecolonies:colony_wall_banner": "minecraft:white_wall_banner",
    "minecolonies:decorationcontroller": VOID,
    "minecolonies:blockwaypoint": "minecraft:air",
    "minecolonies:blockminecoloniesnamedgrave": VOID,
    "minecolonies:gate_iron": "minecraft:iron_bars",
    "minecolonies:gate_wood": "minecraft:oak_fence",
    "minecolonies:composted_dirt": "minecraft:dirt",
    "minecolonies:barrel_block": "minecraft:composter",
    "minecolonies:blockhutfield": "minecraft:hay_block",
}
# Blocks swapped silently: they are markers with no look of their own, so there is nothing to fix.
SILENT = {"structurize:blocksubstitution", "minecolonies:blockwaypoint", "minecolonies:decorationcontroller"}
FIELD = "minecolonies:blockhutfield"  # the scarecrow, which marks a farm field
FIELD_TAG = "minecp_field"           # tag of the marker entity at its position (BuildOrder.FIELD_TAG)
GRAVE = "minecolonies:blockminecoloniesnamedgrave"  # a graveyard's burial spot, left as structure void
GRAVE_TAG = "minecp_grave"                           # tag of the marker entity there (BuildOrder.GRAVE_TAG), plus minecp_facing_<facing>
# Mod items in containers (only the supply camp and ship's starting kit has any): a tagged vanilla item the plugin
# swaps for its own on placement (Placement's STARTER key); any other mod item is left out.
STARTER_ITEMS = {"minecolonies:blockhuttownhall": ("minecraft:bell", "town_hall"),
                 "structurize:sceptergold": ("minecraft:stick", "build_tool")}
SIT_TAG = "minecp_sit"  # marker on a blueprint's "sit" tag positions (the tavern's chairs; BuildOrder.SIT_TAG)
PLANT_TAGS = ("sugar", "cactus", "bamboo", "work", "knight", "archer", "gate", "portal")  # a plantation's plots (the ground under each plant), other work spots, a gatehouse's guard and gate spots, a nether mine's portal: the mod's tags
PLANT_TAG = "minecp_plant_"  # + the tag: marker on each spot (BuildOrder.PLANT_TAG)
RACK = "minecolonies:blockminecoloniesrack"  # the mod's storage rack, built as a chest
RACK_TAG = "minecp:rack"                     # Paper data tag on that chest (BuildOrder.RACK_MARKER)
DATA_FORMAT = [121, 0]  # data pack format of Minecraft 26.3 (version.json pack_version.data_major/minor)
COUNT_ONLY ="structurize:blocksolidsubstitution"  # reported as a count, not coordinates
FAMILY_FALLBACK ={"stairs": "oak_stairs", "slab": "oak_slab", "fence": "oak_fence", "wall": "cobblestone_wall",
                   "fence_gate": "oak_fence_gate", "door": "oak_door", "trapdoor": "oak_trapdoor"}


def family_block(material, family, blocks):
    """'minecraft:stone_bricks' + 'stairs' -> 'minecraft:stone_brick_stairs'. Returns (name, exact)."""
    base = material.split(":", 1)[1]
    if family == "block":
        return "minecraft:" + base, True
    stems = [base]
    for suffix in ("_planks", "_block", "_log", "_wood", "_stem", "_hyphae"):
        if base.endswith(suffix):
            stems.append(base[: -len(suffix)].removeprefix("stripped_"))
    if base.endswith("s"):
        stems.append(base[:-1])  # bricks -> brick, tiles -> tile
    for stem in stems:
        if f"minecraft:{stem}_{family}" in blocks:
            return f"minecraft:{stem}_{family}", True
    return "minecraft:" + FAMILY_FALLBACK[family], False


def keep_props(name, props, blocks):
    allowed = blocks.get(name, {}).get("properties", {})
    return {k: v for k, v in props.items() if v in allowed.get(k, [])}


def convert_state(name, props, te, blocks):
    """Returns (vanilla_name or VOID, props, note or None)."""
    if name.startswith("minecraft:"):
        return name, props, None
    short = name.split(":", 1)[1]
    if name.startswith("domum_ornamentum:") and short in DOMUM_FAMILY:
        materials = list((te or {}).get("textureData", {}).values()) or ["minecraft:oak_planks"]
        family = DOMUM_FAMILY[short]
        target, exact = family_block(materials[0], family, blocks)
        if family == "slab":
            props = {**props, "type": props.get("type", "bottom") if short != "shingle_slab" else "bottom"}
        if family == "door":
            props = {**props, "half": {"lower": "lower", "upper": "upper"}.get(props.get("half"), "lower")}
        if family == "block" and short in ("plain", "horizontal_plain") and len(set(materials)) == 1:
            return target, props, None  # a plain block of one material is exactly that vanilla block
        mats = " + ".join(m.removeprefix("minecraft:") for m in materials)
        return target, props, f"{name} [{mats}] -> {target}" + ("" if exact else " (no such block in that material)")
    if name in FIXED:
        target = FIXED[name]
        if name == "domum_ornamentum:blockbarreldeco_standing":
            props = {"facing": "up"}
        if name == "domum_ornamentum:center_light":
            props = {"hanging": "true"}
        return target, props, None if name in SILENT else f"{name} -> {target or 'structure void'}"
    if short.startswith("blockhut"):
        return "minecraft:chest", props, f"{name} (hut block) -> minecraft:chest"
    return VOID, {}, f"{name} -> structure void (unknown block)"


def read_blocks(ints, sx, sy, sz):
    shorts = []
    for i in ints:
        shorts.append((i >> 16) & 0xFFFF)
        shorts.append(i & 0xFFFF)
    i = 0
    for y in range(sy):
        for z in range(sz):
            for x in range(sx):
                yield (x, y, z), shorts[i]
                i += 1


def marker(pos, tags):
    """A marker entity at a block, which the plugin reads (the builder never spawns structure entities)."""
    x, y, z = pos
    return {"blockPos": (nbt.LIST, (nbt.INT, [x, y, z])),
            "pos": (nbt.LIST, (nbt.DOUBLE, [x + 0.5, y + 0.5, z + 0.5])),
            "nbt": (nbt.COMPOUND, {"id": (nbt.STRING, "minecraft:marker"), "Tags": (nbt.LIST, (nbt.STRING, tags))})}


def starter_items(items, data_version):
    """A container's items with mod items swapped for tagged vanilla ones (STARTER_ITEMS) or left out."""
    kept = []
    for it in items[1][1]:
        item_id = it.get("id", (nbt.STRING, ""))[1]
        if item_id.startswith("minecraft:"):
            kept.append(it)
        elif item_id in STARTER_ITEMS:
            # The stand-in's "tag" reaches custom_data only through vanilla's upgrade from before 1.20.5 (3837).
            assert data_version < 3837, f"blueprint data version {data_version}: write the stand-in as components instead"
            vanilla, kind = STARTER_ITEMS[item_id]
            # The blueprints' item format (before 1.20.5): vanilla moves "tag" into the custom_data component on load,
            # where Paper reads PublicBukkitValues as the item's persistent data.
            kept.append({**it, "id": (nbt.STRING, vanilla), "tag": (nbt.COMPOUND, {"PublicBukkitValues": (nbt.COMPOUND, {
                "minecp:starter": (nbt.STRING, kind)})})})
    return (nbt.LIST, (items[1][0] if kept else nbt.COMPOUND, kept))


def tag_spots(root, wanted):
    """Positions of a blueprint's tags whose name passes wanted(name), relative to its corner, with that name."""
    spots = []
    for t_typed in root.get("tile_entities", (None, (None, [])))[1][1]:
        provider = nbt.plain((nbt.COMPOUND, t_typed)).get("blueprintDataProvider") or {}
        hx, hy, hz = (t_typed[k][1] for k in ("x", "y", "z"))
        for entry in provider.get("posTagMap", []):
            for n in entry.get("tagNameList", []):
                if wanted(n.get("tagName", "")):
                    p = entry["tagPos"]
                    spots.append(((hx + p["x"], hy + p["y"], hz + p["z"]), n["tagName"]))
                    break
    return spots


def sit_spots(root):
    """A blueprint's seats: its hut's "sit" tag positions ("sit", "sit_in", "sit_out", "sitting"), relative to the blueprint's corner."""
    return [pos for pos, _ in tag_spots(root, lambda n: n.startswith("sit"))]


def primary_offset(root):
    """Structurize's primary offset (optional_data.structurize.primary_offset) as (x, y, z), or None."""
    off = nbt.plain((nbt.COMPOUND, root.get("optional_data", (nbt.COMPOUND, {}))[1])).get("structurize", {}).get("primary_offset")
    return (off["x"], off["y"], off["z"]) if off else None


def convert(path, blocks, siblings=()):
    """siblings: the same blueprint in other style packs, to borrow seats from when this one has no tags."""
    root = nbt.load(path)
    data_version = root.get("mcversion", (nbt.INT, 3955))[1]
    sx, sy, sz = (root[k][1] for k in ("size_x", "size_y", "size_z"))
    palette = nbt.plain((nbt.LIST, root["palette"][1]))
    tes = {}
    if "tile_entities" in root:
        for t_typed in root["tile_entities"][1][1]:
            pos = tuple(t_typed[k][1] for k in ("x", "y", "z"))
            tes[pos] = t_typed
    out_palette, out_index, out_blocks, out_entities = [], {}, [], []
    # Structurize's primary offset is the building's own hut block; other hut blocks in it are other buildings' (a
    # barracks' towers). Without one, every hut block counts as the building's.
    primary = primary_offset(root)
    notes = collections.defaultdict(list)
    stairs = set()
    for pos, idx in read_blocks(root["blocks"][1], sx, sy, sz):
        state = palette[idx]
        if state["Name"].endswith("_stairs"):
            stairs.add(pos)
        te_typed = tes.get(pos)
        te = nbt.plain((nbt.COMPOUND, te_typed)) if te_typed else None
        name, props, note = convert_state(state["Name"], state.get("Properties", {}), te, blocks)
        if note:
            notes[note].append(pos)
        if state["Name"] == "minecolonies:composted_dirt":  # a florist's plant spot: dirt, marked as a work spot
            out_entities.append(marker(pos, [PLANT_TAG + "compost"]))
        if state["Name"] == GRAVE:  # the undertaker buries citizens here, facing this way
            facing = state.get("Properties", {}).get("facing", "north")
            out_entities.append(marker(pos, [GRAVE_TAG, "minecp_facing_" + facing]))
        if name is VOID:
            continue
        if not name.startswith("minecraft:") or (name not in blocks and not state["Name"].startswith("minecraft:")):
            notes[f"{name} missing in this version -> structure void"].append(pos)
            continue
        if not state["Name"].startswith("minecraft:"):
            props = keep_props(name, props, blocks)
        key = (name, tuple(sorted(props.items())))
        if key not in out_index:
            out_index[key] = len(out_palette)
            entry = {"Name": (nbt.STRING, name)}
            if props:
                entry["Properties"] = (nbt.COMPOUND, {k: (nbt.STRING, v) for k, v in props.items()})
            out_palette.append(entry)
        block = {"pos": (nbt.LIST, (nbt.INT, list(pos))), "state": (nbt.INT, out_index[key])}
        if te_typed and state["Name"].startswith("minecraft:"):  # keep vanilla block entity data (signs, banners)
            block["nbt"] = (nbt.COMPOUND, {k: starter_items(v, data_version) if k == "Items" else v for k, v in te_typed.items() if k not in ("x", "y", "z")})
        if state["Name"] == FIELD:
            # The scarecrow is a hay bale, which can't carry a data tag: mark it with a marker entity instead, which the
            # plugin reads to register the field.
            out_entities.append(marker(pos, [FIELD_TAG]))
        elif state["Name"].startswith("minecolonies:blockhut"):  # Paper data tag the plugin anchors builds on
            tag = {"minecp:hut": (nbt.BYTE, 1)} if primary is None or tuple(pos) == primary \
                else {"minecp:subhut": (nbt.STRING, state["Name"].split(":", 1)[1].removeprefix("blockhut"))}
            block["nbt"] = (nbt.COMPOUND, {"id": (nbt.STRING, "minecraft:chest"), "PublicBukkitValues": (nbt.COMPOUND, tag)})
        elif state["Name"] == RACK:  # a chest standing in for the mod's storage rack, tagged for a custom model later
            block["nbt"] = (nbt.COMPOUND, {"id": (nbt.STRING, "minecraft:chest"),
                                           "PublicBukkitValues": (nbt.COMPOUND, {RACK_TAG: (nbt.BYTE, 1)})})
        out_blocks.append(block)
    tagged = [b["nbt"][1]["PublicBukkitValues"][1] for b in out_blocks
              if "nbt" in b and "PublicBukkitValues" in b["nbt"][1]]
    if not any("minecp:hut" in t for t in tagged):  # the primary offset isn't on a hut block: every hut block counts
        for t in tagged:
            if t.pop("minecp:subhut", None) is not None:
                t["minecp:hut"] = (nbt.BYTE, 1)
    seats = sit_spots(root)
    for other in siblings:  # some packs' blueprints lost their tags (medieval birch's taverns 2 and 3): the same
        if seats:           # building in another style, where each of its seats is a stair here too
            break
        o = nbt.load(other)
        if tuple(o[k][1] for k in ("size_x", "size_y", "size_z")) == (sx, sy, sz):
            borrowed = sit_spots(o)
            if borrowed and all(s in stairs for s in borrowed):
                seats = borrowed
    for pos in seats:
        if all(0 <= c < m for c, m in zip(pos, (sx, sy, sz))):
            out_entities.append(marker(pos, [SIT_TAG]))
    for pos, name in tag_spots(root, lambda n: n in PLANT_TAGS):
        if all(0 <= c < m for c, m in zip(pos, (sx, sy, sz))):
            out_entities.append(marker(pos, [PLANT_TAG + name]))
    structure = {
        "DataVersion": (nbt.INT, data_version),  # vanilla upgrades old block names on load
        "size": (nbt.LIST, (nbt.INT, [sx, sy, sz])),
        "palette": (nbt.LIST, (nbt.COMPOUND, out_palette)),
        "blocks": (nbt.LIST, (nbt.COMPOUND, out_blocks)),
        "entities": (nbt.LIST, (nbt.COMPOUND, out_entities)),
    }
    entities = len(root["entities"][1][1]) if "entities" in root else 0
    return structure, (sx, sy, sz), notes, entities


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("pack_dir")
    ap.add_argument("datapack_dir")
    ap.add_argument("report")
    ap.add_argument("--blocks", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "blocks-26.3.json"))
    ap.add_argument("--pack-name", default=None, help="structure path prefix, defaults to the pack folder name")
    ap.add_argument("--version", default=None, help="the plugin version the pack is built for, shown in its description")
    a = ap.parse_args()
    blocks = json.load(open(a.blocks))
    pack = a.pack_name or os.path.basename(os.path.normpath(a.pack_dir))
    os.makedirs(a.datapack_dir, exist_ok=True)
    with open(os.path.join(a.datapack_dir, "pack.mcmeta"), "w") as f:
        json.dump({"pack": {"description": f"MineCP structures: {pack}" + (f" (Colonies {a.version})" if a.version else ""),
                            "min_format": DATA_FORMAT, "max_format": DATA_FORMAT}}, f, indent=2)
    files = sorted(os.path.relpath(os.path.join(d, f), a.pack_dir)
                   for d, _, fs in os.walk(a.pack_dir) for f in fs if f.endswith(".blueprint"))
    lines = [f"# {pack}: converted structures", "",
             "Generated by `tools/convert_blueprints.py`. Build with `/colony build " + pack + "/<path>`.", "",
             "## Fixing a building by hand", "",
             "1. Get a structure block (`/give @s structure_block`), place it, and set it to **Load** mode.",
             f"2. Enter the structure name (e.g. `minecp:{pack}/fundamentals/builder1`), press LOAD once to see the outline, again to place it.",
             "3. The structure's corner is the structure block position plus the offset (default `0 1 0`). "
             "Positions below are `x y z` from that corner.",
             "4. Replace the approximated blocks listed for that building.",
             "5. Switch the structure block to **Save** mode with the same name and size, and press SAVE.",
             "   It saves to `world/generated/minecp/structure/`, which takes priority over the data pack, "
             "so your fix is used and re-running the converter never touches it.",
             "6. Keep the barrier blocks in the ground: they mark foundation filler.", ""]
    written = 0
    for rel in files:
        name = rel[: -len(".blueprint")].lower()
        out = os.path.join(a.datapack_dir, "data", "minecp", "structure", pack, name + ".nbt")
        packs = os.path.dirname(os.path.normpath(a.pack_dir))
        siblings = sorted(os.path.join(packs, d, rel) for d in os.listdir(packs)
                          if os.path.join(packs, d) != os.path.normpath(a.pack_dir) and os.path.isfile(os.path.join(packs, d, rel)))
        structure, size, notes, entities = convert(os.path.join(a.pack_dir, rel), blocks, siblings)
        os.makedirs(os.path.dirname(out), exist_ok=True)
        nbt.save(out, structure)
        written += 1
        lines.append(f"## {pack}/{name}")
        lines.append(f"Size {size[0]}x{size[1]}x{size[2]}." + (f" {entities} entities (item frames etc.) not converted." if entities else ""))
        if not notes:
            lines.append("No approximations.")
        for note, positions in sorted(notes.items(), key=lambda n: -len(n[1])):
            if note.startswith(COUNT_ONLY):
                lines.append(f"- **{len(positions)}x** {note} (foundation filler: the builder puts dirt there only where the ground is not solid)")
                continue
            coords = " ".join(f"`{x} {y} {z}`" for x, y, z in positions)
            lines.append(f"- **{len(positions)}x** {note}: {coords}")
        lines.append("")
    os.makedirs(os.path.dirname(os.path.abspath(a.report)), exist_ok=True)
    with open(a.report, "w") as f:
        f.write("\n".join(lines))
    print(f"{written} structures written to {a.datapack_dir}, report: {a.report}")


if __name__ == "__main__":
    main()
