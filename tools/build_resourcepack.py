"""Builds MineCP's optional resource pack from MineColonies and Structurize jars you have.

Usage:
  python3 tools/build_resourcepack.py --minecolonies <minecolonies.jar> [--structurize <structurize.jar>]
                                      [--client <minecraft client jar>] -o <pack.zip>

The pack gives MineCP's items the mod's models: every hut, the storage rack, the Build Goggles (from the MineColonies
jar) and the Build Tool (Structurize's gold scepter). MineCP tags its items with a custom_model_data string
("minecp:hut/town_hall"); the pack replaces the vanilla definitions of the items they are made from (chest, bell, hay
bale, stick, leather helmet) with one that shows the mod's model for those strings and the vanilla model for anything
else. It copies the mod's item and block models and textures under their own namespaces.

The pack is each player's choice: without it they see the plain vanilla items, and the server doesn't care either way.
--client takes the vanilla fallbacks from your Minecraft client jar (.minecraft/versions/<version>/<version>.jar);
without it, built-in copies are used, which may lag behind a new Minecraft version.

The mod's art is "All Rights Reserved": keep the pack private, never commit it.
"""
import argparse
import json
import sys
import zipfile

# MineCP's custom_model_data string (Items.model, after "minecp:") -> the mod's item model.
MODELS = {
    "hut/town_hall": "minecolonies:item/blockhuttownhall",
    "hut/builder": "minecolonies:item/blockhutbuilder",
    "hut/house": "minecolonies:item/blockhutcitizen",
    "hut/lumberjack": "minecolonies:item/blockhutlumberjack",
    "hut/farmer": "minecolonies:item/blockhutfarmer",
    "hut/cook": "minecolonies:item/blockhutcook",
    "hut/warehouse": "minecolonies:item/blockhutwarehouse",
    "hut/courier": "minecolonies:item/blockhutdeliveryman",
    "hut/miner": "minecolonies:item/blockhutminer",
    "hut/sawmill": "minecolonies:item/blockhutsawmill",
    "hut/stonemason": "minecolonies:item/blockhutstonemason",
    "hut/stone_smeltery": "minecolonies:item/blockhutstonesmeltery",
    "hut/blacksmith": "minecolonies:item/blockhutblacksmith",
    "hut/smeltery": "minecolonies:item/blockhutsmeltery",
    "hut/mechanic": "minecolonies:item/blockhutmechanic",
    "hut/guard_tower": "minecolonies:item/blockhutguardtower",
    "hut/field": "minecolonies:item/blockhutfield",
    "rack": "minecolonies:item/blockminecoloniesrack",
    "build_goggles": "minecolonies:item/build_goggles",
    "build_tool": "structurize:item/sceptergold",
}
# The vanilla item each MineCP item is made from (Hut.Type.block, Rack, Items): its definition gets the override.
BASE = {"hut/town_hall": "bell", "hut/field": "hay_block", "build_goggles": "leather_helmet", "build_tool": "stick"}  # the rest: chest
# Vanilla's own definitions of those items, used as the fallback without --client (from Minecraft's assets/minecraft/items).
VANILLA = {
    "bell": {"type": "minecraft:model", "model": "minecraft:item/bell"},
    "hay_block": {"type": "minecraft:model", "model": "minecraft:block/hay_block"},
    "stick": {"type": "minecraft:model", "model": "minecraft:item/stick"},
    "leather_helmet": {"type": "minecraft:model", "model": "minecraft:item/leather_helmet",
                       "tints": [{"type": "minecraft:dye", "default": -6265536}]},
    "chest": {"type": "minecraft:select", "property": "minecraft:local_time", "pattern": "MM-dd",
              "cases": [{"when": ["12-24", "12-25", "12-26"], "model": {"type": "minecraft:special", "base": "minecraft:item/chest",
                                                                         "model": {"type": "minecraft:chest", "texture": "minecraft:christmas"}}}],
              "fallback": {"type": "minecraft:special", "base": "minecraft:item/chest",
                           "model": {"type": "minecraft:chest", "texture": "minecraft:normal"}}},
}
# What to copy from each jar: models and the textures they use.
COPY = ("models/", "textures/block/", "textures/item/")


def copy_assets(jar_path, namespace, out, seen):
    """Copies a jar's models and block and item textures for one namespace. Returns the model ids it holds."""
    models = set()
    with zipfile.ZipFile(jar_path) as jar:
        for name in jar.namelist():
            prefix = f"assets/{namespace}/"
            if not name.startswith(prefix) or name.endswith("/"):
                continue
            rest = name[len(prefix):]
            if not rest.startswith(COPY):
                continue
            if name not in seen:
                out.writestr(name, jar.read(name))
                seen.add(name)
            if rest.startswith("models/") and rest.endswith(".json"):
                models.add(f"{namespace}:{rest[len('models/'):-len('.json')]}")
    return models


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--minecolonies", required=True, help="the MineColonies mod jar")
    ap.add_argument("--structurize", help="the Structurize mod jar (for the Build Tool's model)")
    ap.add_argument("--client", help="the Minecraft client jar, for the vanilla item definitions")
    ap.add_argument("-o", "--out", required=True, help="the resource pack zip to write")
    ap.add_argument("--min-format", type=int, default=69, help="lowest resource pack format the pack declares")
    a = ap.parse_args()

    seen, have = set(), set()
    with zipfile.ZipFile(a.out, "w", zipfile.ZIP_DEFLATED) as out:
        have |= copy_assets(a.minecolonies, "minecolonies", out, seen)
        if a.structurize:
            have |= copy_assets(a.structurize, "structurize", out, seen)
        missing, cases = [], {}
        for item, model in MODELS.items():
            if model not in have:
                missing.append(f"{item} ({model})")
                continue  # left out: that item just keeps its vanilla look
            cases.setdefault(BASE.get(item, "chest"), []).append({"when": "minecp:" + item, "model": {"type": "minecraft:model", "model": model}})
        client = zipfile.ZipFile(a.client) if a.client else None
        for base, base_cases in cases.items():
            fallback = VANILLA[base]
            if client:
                fallback = json.loads(client.read(f"assets/minecraft/items/{base}.json"))["model"]
            out.writestr(f"assets/minecraft/items/{base}.json", json.dumps({"model": {
                "type": "minecraft:select", "property": "minecraft:custom_model_data", "index": 0,
                "cases": base_cases, "fallback": fallback}}, indent=2))
        out.writestr("pack.mcmeta", json.dumps({"pack": {
            "description": "MineCP models (private: made from your MineColonies jar)",
            "min_format": a.min_format, "max_format": 1000}}, indent=2))
    print(f"wrote {a.out}: {len(MODELS) - len(missing)} of {len(MODELS)} item models")
    if missing:
        print("not in the jars (those items keep their vanilla look):", file=sys.stderr)
        for m in missing:
            print("  " + m, file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
