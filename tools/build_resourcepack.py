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

Placed huts and storage racks stay vanilla chests, named for what they are ("Builder's Hut"). For players with
OptiFine or Entity Model Features, the pack also turns the mod's hut and rack block models into OptiFine CEM chest
models (assets/minecraft/optifine/cem/chest<n>.jem), picked by that name in chest.properties; other chests stay vanilla.
--cem-turn turns those models by quarter turns if they face the wrong way. Players with Better Block Entities must
turn off its chest optimization: it draws closed chests as plain block models, so EMF's models show only while open.

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
    "hut/fletcher": "minecolonies:item/blockhutfletcher",
    "hut/dyer": "minecolonies:item/blockhutdyer",
    "hut/glassblower": "minecolonies:item/blockhutglassblower",
    "hut/crusher": "minecolonies:item/blockhutcrusher",
    "hut/concrete_mixer": "minecolonies:item/blockhutconcretemixer",
    "hut/alchemist": "minecolonies:item/blockhutalchemist",
    "hut/enchanter": "minecolonies:item/blockhutenchanter",
    "ancient_tome": "minecolonies:item/ancienttome",
    "hut/sifter": "minecolonies:item/blockhutsifter",
    "hut/hospital": "minecolonies:item/blockhuthospital",
    "mesh/string": "minecolonies:item/sifter_mesh_string",
    "mesh/flint": "minecolonies:item/sifter_mesh_flint",
    "mesh/iron": "minecolonies:item/sifter_mesh_iron",
    "mesh/diamond": "minecolonies:item/sifter_mesh_diamond",
    "hut/tavern": "minecolonies:item/blockhuttavern",
    "hut/baker": "minecolonies:item/blockhutbaker",
    "hut/guard_tower": "minecolonies:item/blockhutguardtower",
    "hut/archery": "minecolonies:item/blockhutarchery",
    "hut/combat_academy": "minecolonies:item/blockhutcombatacademy",
    "hut/barracks": "minecolonies:item/blockhutbarracks",
    "hut/composter": "minecolonies:item/blockhutcomposter",
    "hut/plantation": "minecolonies:item/blockhutplantation",
    "hut/beekeeper": "minecolonies:item/blockhutbeekeeper",
    "hut/school": "minecolonies:item/blockhutschool",
    "hut/library": "minecolonies:item/blockhutlibrary",
    "compost": "minecolonies:item/compost",
    "mistletoe": "minecolonies:item/mistletoe",
    "magic_potion": "minecolonies:item/magicpotion",
    "hut/barracks_tower": "minecolonies:item/blockhutbarrackstower",
    "hut/graveyard": "minecolonies:item/blockhutgraveyard",
    "hut/fisherman": "minecolonies:item/blockhutfisherman",
    "hut/chicken_herder": "minecolonies:item/blockhutchickenherder",
    "hut/cowboy": "minecolonies:item/blockhutcowboy",
    "hut/shepherd": "minecolonies:item/blockhutshepherd",
    "hut/swineherd": "minecolonies:item/blockhutswineherder",
    "hut/rabbit_hutch": "minecolonies:item/blockhutrabbithutch",
    "hut/field": "minecolonies:item/blockhutfield",
    "hut/post_box": "minecolonies:item/blockpostbox",
    "hut/university": "minecolonies:item/blockhutuniversity",
    "hut/stash": "minecolonies:item/blockstash",
    "rack": "minecolonies:item/blockminecoloniesrack",
    "build_goggles": "minecolonies:item/build_goggles",
    "build_tool": "structurize:item/sceptergold",
    "clipboard": "minecolonies:item/clipboard",
    "resource_scroll": "minecolonies:item/resourcescroll",
    "guard_scepter": "minecolonies:item/scepterguard",
    "supply_camp": "minecolonies:item/supplycampdeployer",
    "supply_ship": "minecolonies:item/supplychestdeployer",
}
# The vanilla item each MineCP item is made from (Hut.Type.block, Rack, Items) or looks like (Alchemist): its definition gets the override.
BASE = {"hut/town_hall": "bell", "hut/field": "hay_block", "build_goggles": "leather_helmet", "build_tool": "stick",
        "clipboard": "paper", "resource_scroll": "paper", "supply_camp": "paper", "supply_ship": "paper",
        "mesh/string": "paper", "mesh/flint": "paper", "mesh/iron": "paper", "mesh/diamond": "paper", "compost": "bone_meal", "guard_scepter": "blaze_rod",
        "mistletoe": "lily_of_the_valley", "magic_potion": "dragon_breath", "ancient_tome": "enchanted_book"}  # the rest: chest
# Vanilla's own definitions of those items (Minecraft 26.3's assets/minecraft/items), the fallback without --client.
VANILLA = json.loads(r'''{"bell":{"type":"minecraft:model","model":"minecraft:item/bell"},"chest":{"type":"minecraft:select","cases":[{"model":{"type":"minecraft:special","base":"minecraft:item/chest","model":{"type":"minecraft:chest","texture":"minecraft:christmas"}},"when":["12-24","12-25","12-26"]}],"fallback":{"type":"minecraft:special","base":"minecraft:item/chest","model":{"type":"minecraft:chest","texture":"minecraft:normal"}},"pattern":"MM-dd","property":"minecraft:local_time"},"hay_block":{"type":"minecraft:model","model":"minecraft:block/hay_block"},"stick":{"type":"minecraft:model","model":"minecraft:item/stick"},"blaze_rod":{"type":"minecraft:model","model":"minecraft:item/blaze_rod"},"paper":{"type":"minecraft:model","model":"minecraft:item/paper"},"lily_of_the_valley":{"type":"minecraft:model","model":"minecraft:item/lily_of_the_valley"},"dragon_breath":{"type":"minecraft:model","model":"minecraft:item/dragon_breath"},"bone_meal":{"type":"minecraft:model","model":"minecraft:item/bone_meal"},"enchanted_book":{"type":"minecraft:model","model":"minecraft:item/enchanted_book"},"leather_helmet":{"type":"minecraft:select","cases":[{"model":{"type":"minecraft:model","model":"minecraft:item/leather_helmet_quartz_trim","tints":[{"type":"minecraft:dye","default":-6265536}]},"when":"minecraft:quartz"},{"model":{"type":"minecraft:model","model":"minecraft:item/leather_helmet_iron_trim","tints":[{"type":"minecraft:dye","default":-6265536}]},"when":"minecraft:iron"},{"model":{"type":"minecraft:model","model":"minecraft:item/leather_helmet_netherite_trim","tints":[{"type":"minecraft:dye","default":-6265536}]},"when":"minecraft:netherite"},{"model":{"type":"minecraft:model","model":"minecraft:item/leather_helmet_redstone_trim","tints":[{"type":"minecraft:dye","default":-6265536}]},"when":"minecraft:redstone"},{"model":{"type":"minecraft:model","model":"minecraft:item/leather_helmet_copper_trim","tints":[{"type":"minecraft:dye","default":-6265536}]},"when":"minecraft:copper"},{"model":{"type":"minecraft:model","model":"minecraft:item/leather_helmet_gold_trim","tints":[{"type":"minecraft:dye","default":-6265536}]},"when":"minecraft:gold"},{"model":{"type":"minecraft:model","model":"minecraft:item/leather_helmet_emerald_trim","tints":[{"type":"minecraft:dye","default":-6265536}]},"when":"minecraft:emerald"},{"model":{"type":"minecraft:model","model":"minecraft:item/leather_helmet_diamond_trim","tints":[{"type":"minecraft:dye","default":-6265536}]},"when":"minecraft:diamond"},{"model":{"type":"minecraft:model","model":"minecraft:item/leather_helmet_lapis_trim","tints":[{"type":"minecraft:dye","default":-6265536}]},"when":"minecraft:lapis"},{"model":{"type":"minecraft:model","model":"minecraft:item/leather_helmet_amethyst_trim","tints":[{"type":"minecraft:dye","default":-6265536}]},"when":"minecraft:amethyst"},{"model":{"type":"minecraft:model","model":"minecraft:item/leather_helmet_resin_trim","tints":[{"type":"minecraft:dye","default":-6265536}]},"when":"minecraft:resin"}],"fallback":{"type":"minecraft:model","model":"minecraft:item/leather_helmet","tints":[{"type":"minecraft:dye","default":-6265536}]},"property":"minecraft:trim_material"}}''')
# The name MineCP gives each placed hut chest (Hut.Type titles) and rack (Rack.tag) -> its MODELS entry: keep in step.
CHEST_NAMES = {
    "Builder's Hut": "hut/builder", "House": "hut/house", "Lumberjack's Hut": "hut/lumberjack", "Farmer's Hut": "hut/farmer",
    "Cook's Hut": "hut/cook", "Warehouse": "hut/warehouse", "Courier's Hut": "hut/courier", "Miner's Hut": "hut/miner",
    "Sawmill": "hut/sawmill", "Stonemason's Hut": "hut/stonemason", "Stone Smeltery": "hut/stone_smeltery",
    "Blacksmith's Hut": "hut/blacksmith", "Smeltery": "hut/smeltery", "Mechanic's Hut": "hut/mechanic",
    "Fletcher's Hut": "hut/fletcher", "Dyer's Hut": "hut/dyer", "Glassblower's Hut": "hut/glassblower", "Crusher": "hut/crusher", "Sifter": "hut/sifter", "Hospital": "hut/hospital", "Concrete Mixer": "hut/concrete_mixer", "Alchemist Tower": "hut/alchemist", "Enchanter's Tower": "hut/enchanter",
    "Guard Tower": "hut/guard_tower", "Archery": "hut/archery", "Combat Academy": "hut/combat_academy", "Barracks": "hut/barracks", "Barracks Tower": "hut/barracks_tower", "Composter's Hut": "hut/composter", "Plantation": "hut/plantation", "Beekeeper's Hut": "hut/beekeeper", "School": "hut/school", "Library": "hut/library", "Tavern": "hut/tavern", "Bakery": "hut/baker", "Storage Rack": "rack",
    "Graveyard": "hut/graveyard", "Fisherman's Hut": "hut/fisherman", "Chicken Farmer's Hut": "hut/chicken_herder",
    "Cowhand's Hut": "hut/cowboy", "Shepherd's Hut": "hut/shepherd", "Swineherd's Hut": "hut/swineherd",
    "Rabbit Hutch": "hut/rabbit_hutch", "Postbox": "hut/post_box", "University": "hut/university", "Stash": "hut/stash",
}
FACES = ["north", "east", "south", "west"]  # a quarter turn clockwise (seen from above) moves each to the next


def resolve(model_id, models, depth=0):
    """A block model's elements, each with the texture variables it sees, following parents (vanilla ones only if
    --client gave them) and Forge composite models ("loader": "forge:composite"), whose parts sit in "children", each
    with its own textures and elements (or parent)."""
    textures, seen = {}, set()
    while model_id and model_id not in seen and depth < 10:
        seen.add(model_id)
        m = models.get(model_id if ":" in model_id else "minecraft:" + model_id)
        if m is None:
            break
        for k, v in m.get("textures", {}).items():
            textures.setdefault(k, v)
        if "children" in m:
            out = []
            for child in m["children"].values():
                inner = [(e, {**textures, **t}) for e, t in resolve(child.get("parent"), models, depth + 1)] if "elements" not in child else []
                own = {**textures, **child.get("textures", {})}
                out += [(e, own) for e in child.get("elements", [])] + [(e, {**t, **child.get("textures", {})}) for e, t in inner]
            return out
        if "elements" in m:
            return [(e, textures) for e in m["elements"]]
        model_id = m.get("parent")
    return []


def flatten(model_id, models):
    """A Forge composite model as a plain one a vanilla client can draw: its children's elements together, each
    child's texture variables renamed so they can't clash. None if it isn't composite."""
    m = models.get(model_id)
    if m is None or "children" not in m:
        return None
    textures, elements, keys = dict(m.get("textures", {})), [], {}
    for e, tex in resolve(model_id, models):
        e = json.loads(json.dumps(e))
        for face in e.get("faces", {}).values():
            ref = texture_path(face.get("texture", ""), tex)
            if ref:
                ref = ref.replace(":textures/", ":", 1)
                if ref not in keys:
                    keys[ref] = "t" + str(len(keys))
                    textures[keys[ref]] = ref
                face["texture"] = "#" + keys[ref]
        elements.append(e)
    flat = {k: v for k, v in m.items() if k not in ("children", "loader", "elements", "textures")}
    flat["textures"], flat["elements"] = textures, elements
    return flat


def inherited(model_id, key, models):
    """The first value of {@code key} ("display", "gui_light") along a model's parents, or None."""
    for _ in range(10):
        m = models.get(model_id if model_id and ":" in model_id else "minecraft:" + (model_id or ""))
        if m is None:
            return None
        if key in m:
            return m[key]
        model_id = m.get("parent")
    return None


def one_atlas(item, model_id, models, jars, out, seen):
    """The model to give an item, using one texture atlas, as 26.3 insists: a model drawing textures from both the
    block atlas (textures/block/...) and the item atlas (textures/item/...) or from neither fails, and takes every
    other case of its item definition (all chests) down with it. Such a model is written out whole as
    minecp:item/<item>, its odd textures copied under textures/block/minecp/. @return (model id, None) or (None, why)."""
    elements = resolve(model_id, models)
    if not elements:
        return model_id, None  # a flat item (layer textures): item atlas only
    refs = {}
    for e, tex in elements:
        for face in e.get("faces", {}).values():
            ref = texture_path(face.get("texture", ""), tex)
            if ref:
                refs[ref] = None
    if all(r.split(":", 1)[1].startswith("textures/block/") for r in refs):
        return model_id, None
    keys, textures, out_elements = {}, {}, []
    for ref in refs:  # "ns:textures/item/x" -> "ns:block/minecp/item/x", copied into the pack
        ns, path = ref.split(":", 1)
        rest = path[len("textures/"):]
        target = rest if rest.startswith("block/") else "block/minecp/" + rest
        if not rest.startswith("block/"):
            jar = jars.get(ns)
            src = f"assets/{ns}/{path}.png"
            if jar is None or src not in jar.namelist():
                return None, f"texture {ns}:{rest} not found (give --client for vanilla ones)"
            for suffix in ("", ".mcmeta"):  # once, if two models share it
                name = f"assets/{ns}/textures/{target}.png{suffix}"
                if name not in seen and src + suffix in jar.namelist():
                    out.writestr(name, jar.read(src + suffix))
                    seen.add(name)
        keys[ref] = "t" + str(len(keys))
        textures[keys[ref]] = f"{ns}:{target}"
    textures["particle"] = next(iter(textures.values()))
    for e, tex in elements:
        e = json.loads(json.dumps(e))
        e["faces"] = {f: {**spec, "texture": "#" + keys[texture_path(spec.get("texture", ""), tex)]}
                      for f, spec in e.get("faces", {}).items() if texture_path(spec.get("texture", ""), tex)}
        out_elements.append(e)
    fixed = {"textures": textures, "elements": out_elements}
    for key in ("display", "gui_light", "ambientocclusion"):
        value = inherited(model_id, key, models)
        if value is not None:
            fixed[key] = value
    out.writestr(f"assets/minecp/models/item/{item}.json", json.dumps(fixed))
    return f"minecp:item/{item}", None


def texture_path(ref, textures):
    """"#side" -> "minecraft:textures/block/oak_planks", as CEM's "mod:folder/texture" form."""
    for _ in range(10):
        if not ref.startswith("#"):
            break
        ref = textures.get(ref[1:], "")
    if not ref:
        return None
    ns, path = ref.split(":", 1) if ":" in ref else ("minecraft", ref)
    return f"{ns}:textures/{path}"


def default_uv(face, f, t):
    """The UV a block model face gets without one (vanilla's), from the element's corners."""
    x1, y1, z1 = f
    x2, y2, z2 = t
    return {"down": [x1, 16 - z2, x2, 16 - z1], "up": [x1, z1, x2, z2], "north": [16 - x2, 16 - y2, 16 - x1, 16 - y1],
            "south": [x1, 16 - y2, x2, 16 - y1], "west": [z1, 16 - y2, z2, 16 - y1], "east": [16 - z2, 16 - y2, 16 - z1, 16 - y1]}[face]


def chest_model(elements, turns):
    """An OptiFine CEM chest model drawing a block model: every element a box of the chest's base part (block pixels,
    y up, the chest's front toward +z), one sub-part per texture; the lid and knob are left empty.
    shortcut: element rotations (a few tilted decorations) and face texture rotations are dropped; upgrade once a
    player with EMF has checked the result."""
    parts = {}
    for e, textures in elements:
        (x1, y1, z1), (x2, y2, z2) = e["from"], e["to"]
        corners = [(x1, z1), (x2, z2)]
        for _ in range(turns % 4):
            corners = [(16 - z, x) for x, z in corners]
        nx1, nx2 = sorted(c[0] for c in corners)
        nz1, nz2 = sorted(c[1] for c in corners)
        by_texture = {}
        for face, spec in e.get("faces", {}).items():
            tex = texture_path(spec.get("texture", ""), textures)
            if tex is None:
                continue
            name = face if face in ("up", "down") else FACES[(FACES.index(face) + turns) % 4]
            key = "uv" + name[0].upper() + name[1:]
            by_texture.setdefault(tex, {})[key] = spec.get("uv", default_uv(face, e["from"], e["to"]))
        for tex, faces in by_texture.items():
            parts.setdefault(tex, []).append({"coordinates": [nx1, y1, nz1, nx2 - nx1, y2 - y1, nz2 - nz1], **faces})
    base = {"part": "base", "id": "base", "attach": False, "textureSize": [16, 16],
            "submodels": [{"id": f"t{i}", "texture": tex, "textureSize": [16, 16], "boxes": boxes}
                          for i, (tex, boxes) in enumerate(parts.items())]}
    empty = lambda part: {"part": part, "id": part, "attach": False, "textureSize": [16, 16], "boxes": []}
    return {"textureSize": [16, 16], "models": [base, empty("lid"), empty("knob")]}


# What to copy from each jar: models and the textures they use.
COPY = ("models/", "textures/block/", "textures/item/")


def copy_assets(jar_path, namespace, out, seen, models_json):
    """Copies a jar's models and block and item textures for one namespace (models also into {@code models_json})."""
    models = set()
    with zipfile.ZipFile(jar_path) as jar:
        for name in jar.namelist():
            prefix = f"assets/{namespace}/"
            if not name.startswith(prefix) or name.endswith("/"):
                continue
            rest = name[len(prefix):]
            if not rest.startswith(COPY):
                continue
            composite = False
            if rest.startswith("models/") and rest.endswith(".json"):
                model_id = f"{namespace}:{rest[len('models/'):-len('.json')]}"
                models.add(model_id)
                try:
                    models_json[model_id] = json.loads(jar.read(name))
                    composite = "children" in models_json[model_id]  # written flattened later (main)
                except ValueError:
                    pass  # not plain JSON: copied as it is, left out of the CEM models
            if name not in seen and not composite:
                out.writestr(name, jar.read(name))
                seen.add(name)
    return models


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--minecolonies", required=True, help="the MineColonies mod jar")
    ap.add_argument("--structurize", help="the Structurize mod jar (for the Build Tool's model)")
    ap.add_argument("--client", help="the Minecraft client jar, for the vanilla item definitions")
    ap.add_argument("-o", "--out", required=True, help="the resource pack zip to write")
    ap.add_argument("--min-format", type=int, default=69, help="lowest resource pack format the pack declares")
    ap.add_argument("--cem-turn", type=int, default=1, help="quarter turns (clockwise from above) for the CEM chest models")
    a = ap.parse_args()

    seen, have, models_json = set(), set(), {}
    with zipfile.ZipFile(a.out, "w", zipfile.ZIP_DEFLATED) as out:
        have |= copy_assets(a.minecolonies, "minecolonies", out, seen, models_json)
        if a.structurize:
            have |= copy_assets(a.structurize, "structurize", out, seen, models_json)
        for model_id in list(models_json):  # Forge composite models (a vanilla client can't draw them), made plain
            flat = flatten(model_id, models_json)
            if flat is not None:
                ns, path = model_id.split(":", 1)
                out.writestr(f"assets/{ns}/models/{path}.json", json.dumps(flat))
        client = zipfile.ZipFile(a.client) if a.client else None
        if client:  # vanilla parents of the mod's models (read only, never copied)
            for name in client.namelist():
                if name.startswith("assets/minecraft/models/") and name.endswith(".json"):
                    models_json.setdefault("minecraft:" + name[len("assets/minecraft/models/"):-5], json.loads(client.read(name)))
        jars = {"minecolonies": zipfile.ZipFile(a.minecolonies)}
        if a.structurize:
            jars["structurize"] = zipfile.ZipFile(a.structurize)
        if client:
            jars["minecraft"] = client
        missing, cases = [], {}
        for item, model in MODELS.items():
            if model not in have:
                missing.append(f"{item} ({model})")
                continue  # left out: that item just keeps its vanilla look
            model, why = one_atlas(item, model, models_json, jars, out, seen)
            if model is None:
                missing.append(f"{item}: {why}")
                continue
            cases.setdefault(BASE.get(item, "chest"), []).append({"when": "minecp:" + item, "model": {"type": "minecraft:model", "model": model}})
        rules, n, no_chest = [], 2, []
        for chest_name, item in CHEST_NAMES.items():
            elements = resolve(MODELS[item], models_json)
            if not elements:
                no_chest.append(f"{chest_name} ({MODELS[item]} has no block model elements)")
                continue
            out.writestr(f"assets/minecraft/optifine/cem/chest{n}.jem", json.dumps(chest_model(elements, a.cem_turn), indent=1))
            rules.append(f"models.{n}={n}\nname.{n}={chest_name}")
            n += 1
        if rules:  # unnamed chests match no rule and keep the vanilla model
            out.writestr("assets/minecraft/optifine/cem/chest.properties", "# MineCP: hut and rack chests by name\n" + "\n".join(rules) + "\n")
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
    print(f"wrote {a.out}: {len(MODELS) - len(missing)} of {len(MODELS)} item models, {len(CHEST_NAMES) - len(no_chest)} of {len(CHEST_NAMES)} placed chest models")
    for m in no_chest:
        print("  placed, left vanilla: " + m, file=sys.stderr)
    if missing:
        print("left out (those items keep their vanilla look):", file=sys.stderr)
        for m in missing:
            print("  " + m, file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
