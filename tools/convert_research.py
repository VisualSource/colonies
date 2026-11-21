"""Converts MineColonies' research tree (its data/minecolonies/researches files) into Colonies' research.json.

Usage:
  python3 tools/convert_research.py <researches_dir_or_mod_jar> [-o src/main/resources/research.json]

<researches_dir_or_mod_jar> is the mod jar, or a folder holding its researches data (in the mod's repository:
src/datagen/generated/minecolonies/data/minecolonies/researches). Only the mod's numbers and tree are kept: branches,
depths, parents, costs, building requirements and each effect's strength. Names are our own (NAMES below); the plugin
writes the effects' descriptions itself (Studies.describe). Re-run it when the mod's tree changes.
"""
import argparse
import json
import os
import sys
import zipfile

PREFIX = "data/minecolonies/researches/"

# The mod's items that Colonies has as its own items, by their custom_model_data name (Items).
OUR_ITEMS = {"minecolonies:blockminecoloniesrack": "minecp:rack", "minecolonies:clipboard": "minecp:clipboard"}
# The mod's item tags that aren't vanilla's, as vanilla items (ours: its concrete tag is the sixteen concrete blocks).
COLOURS = ["white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray", "cyan", "purple",
           "blue", "brown", "green", "red", "black"]
OUR_TAGS = {"minecolonies:concrete": ["minecraft:%s_concrete" % c for c in COLOURS]}

# Our names for the researches and branches (the mod's are its own text).
BRANCHES = {"civilian": "Civilian", "combat": "Combat", "technology": "Technology", "unlockable": "Unlockables"}
NAMES = {
    # civilian
    "academic": "Academic", "agile": "Agile", "air": "Fresh Air", "ambition": "Ambition", "athlete": "Athlete",
    "bachelor": "Bachelor's Degree", "bandages": "Bandages", "bandaid": "Plasters", "beanstalk": "Beanstalk",
    "bookworm": "Bookworm", "cast": "Plaster Cast", "circus": "Circus", "city": "City", "compress": "Compress",
    "diligent": "Diligent", "epicure": "Epicure", "festival": "Festival", "firstaid": "First Aid",
    "firstaid2": "First Aid II", "fortitude": "Fortitude", "glutton": "Glutton", "gorger": "Gorger",
    "gourmand": "Gourmand", "gravedecaybonus1": "Slow Decay", "gravedecaybonus2": "Slower Decay", "growth": "Growth",
    "guardianangel": "Guardian Angel", "guardianangel2": "Guardian Angel II", "hamlet": "Hamlet", "haste": "Haste",
    "healingcream": "Healing Cream", "higherlearning": "Higher Learning", "hormones": "Hormones",
    "indefatigability": "Indefatigability", "keen": "Keen Mind", "lifesaver": "Lifesaver", "lifesaver2": "Lifesaver II",
    "masks": "Masks", "master": "Master's Degree", "moq": "Minimum Order", "morebooks": "More Books",
    "nightowl": "Night Owl", "nightowl2": "Night Owl II", "nimble": "Nimble", "nurture": "Nurture", "opera": "Opera",
    "outpost": "Outpost", "phd": "Doctorate", "puberty": "Puberty", "rails": "Rails", "raisingthedead": "Raising the Dead",
    "reflective": "Reflective", "remembrance": "Remembrance", "resilience": "Resilience", "resistance": "Resistance",
    "resurrectchance1": "Second Chance", "resurrectchance2": "Second Chance II", "scholarly": "Scholarly",
    "spectacle": "Spectacle", "stamina": "Stamina", "studious": "Studious", "stuffer": "Stuffer", "swift": "Swift",
    "theater": "Theatre", "undertakeremergency": "Undertaker on Call", "vaccines": "Vaccines", "village": "Village",
    "vines": "Vines", "vitality": "Vitality",
    # combat
    "accuracy": "Accuracy", "advancedbarding": "Advanced Barding", "agilearcher": "Agile Archer",
    "arrowpierce": "Piercing Arrows", "arrowuse": "Arrow Supply", "avoid": "Avoid", "avoidance": "Avoidance",
    "barding": "Barding", "boiledleather": "Boiled Leather", "bucephalus": "Warhorse",
    "captainoftheguard": "Captain of the Guard", "captaintraining": "Captain Training", "cleave": "Cleave",
    "coffee": "Coffee", "deadlyaim": "Deadly Aim", "diamondskin": "Diamond Skin", "dodge": "Dodge",
    "druidpotion": "Druid's Potion", "duelist": "Duelist", "evade": "Evade", "evasion": "Evasion",
    "exerciseregimen": "Exercise Regimen", "fear": "Fear", "feint": "Feint", "flee": "Flee", "fullretreat": "Full Retreat",
    "healthdiet": "Healthy Diet", "hotfoot": "Hot Foot", "improvedbarding": "Improved Barding",
    "improvedbows": "Improved Bows", "improveddodge": "Improved Dodge", "improvedevasion": "Improved Evasion",
    "improvedleather": "Improved Leather", "improvedswords": "Improved Swords", "ironarmor": "Iron Armor",
    "ironskin": "Iron Skin", "ivermectin": "Ivermectin", "knighttraining": "Knight Training",
    "masterbarding": "Master Barding", "masterbowman": "Master Bowman", "masterswordsman": "Master Swordsman",
    "mightycleave": "Mighty Cleave", "mountedcombat": "Mounted Combat", "multishot": "Multishot", "parry": "Parry",
    "penetratingshot": "Penetrating Shot", "piercingshot": "Piercing Shot", "platearmor": "Plate Armor",
    "powerattack": "Power Attack", "preciseshot": "Precise Shot", "provost": "Provost", "quickdraw": "Quick Draw",
    "rapidshot": "Rapid Shot", "regeneration": "Regeneration", "retreat": "Retreat", "riposte": "Riposte",
    "savagestrike": "Savage Strike", "slicedanddecided": "Sliced and Diced", "squiretraining": "Squire Training",
    "standard": "Standard Bearer", "steelarmor": "Steel Armor", "tactictraining": "Tactic Training", "taunt": "Taunt",
    "telescope": "Telescope", "thathitthemark": "On Target", "trickshot": "Trick Shot", "whirlwind": "Whirlwind",
    "woundingshot": "Wounding Shot",
    # technology
    "ability": "Ability", "alchemist": "Alchemy", "amazingveins": "Amazing Veins", "assistanthammers": "Assistant Hammers",
    "biodegradable": "Biodegradable", "bonemeal": "Bone Meal", "buildermodes": "Builder Modes", "capacity": "Capacity",
    "cheatsheet": "Cheat Sheet", "compost": "Compost", "croprotation": "Crop Rotation", "deeppockets": "Deep Pockets",
    "depthknowledge": "Knowledge of the Depths", "diamondcoated": "Diamond Coated", "dung": "Dung",
    "enhanced_gates1": "Sturdy Gates", "enhanced_gates2": "Sturdier Gates", "exoticfruits": "Exotic Fruits",
    "fertilizer": "Fertilizer", "flowerpower": "Flower Power", "fullstock": "Full Stock", "gargamel": "Potion Brewing",
    "gildedhammer": "Gilded Hammer", "goodveins": "Good Veins", "greenrevolution": "Green Revolution",
    "hardened": "Hardened", "heavilyloaded": "Heavily Loaded", "heavymachinery": "Heavy Machinery",
    "hittingiron": "Hitting Iron", "honeypot": "Honey Pot", "hot": "Hot", "hotboots": "Hot Boots",
    "isthisredstone": "Is This Redstone?", "junglemaster": "Jungle Master", "knowtheend": "Knowledge of the End",
    "letitgrow": "Let It Grow", "lightning": "Lightning", "loaded": "Loaded", "madness": "Madness",
    "magiccompost": "Magic Compost", "memoryaid": "Memory Aid", "morescrolls": "More Scrolls", "motherlode": "Motherlode",
    "netherlog": "Nether Log", "oceanheart": "Ocean Heart", "opennether": "Open the Nether", "pavetheroad": "Pave the Road",
    "podzolchance": "Podzol", "podzolchance2": "More Podzol", "rainbowheaven": "Rainbow Heaven", "rainman": "Rain Man",
    "recipebook": "Recipe Book", "redstonepowered": "Redstone Powered", "reinforced": "Reinforced",
    "richveins": "Rich Veins", "rockingroll": "Rock and Roll", "rtm": "Read the Manual",
    "seemsautomatic": "Seems Automatic", "sieving": "Sieving", "skilledbutcher": "Skilled Butcher", "skills": "Skills",
    "softshoes": "Soft Shoes", "space": "Space", "steelbracing": "Steel Bracing", "stonecake": "Stone Cake",
    "stringwork": "String Work", "strong": "Strong", "takingdivinglessons": "Diving Lessons", "theflintstones": "Flint and Stone",
    "thoselungs": "Those Lungs", "tools": "Tools", "veinminer": "Vein Miner", "warehousemaster": "Warehouse Master",
    "whatisthisspeed": "What Is This Speed?", "whatyaneed": "What You Need", "woodwork": "Woodwork",
    # unlockable
    "diamondmesh": "Diamond Mesh", "flintmesh": "Flint Mesh", "ironmesh": "Iron Mesh", "stringmesh": "String Mesh",
}


def read(source):
    """{relative path: parsed JSON} for every file under the researches folder."""
    out = {}
    if zipfile.is_zipfile(source):
        with zipfile.ZipFile(source) as z:
            for n in z.namelist():
                if n.startswith(PREFIX) and n.endswith(".json"):
                    out[n[len(PREFIX):]] = json.loads(z.read(n))
    else:
        for d, _, fs in os.walk(source):
            for f in fs:
                if f.endswith(".json"):
                    p = os.path.join(d, f)
                    out[os.path.relpath(p, source).replace(os.sep, "/")] = json.load(open(p))
    return out


def local(rid):
    """minecolonies:civilian/stamina -> civilian/stamina."""
    return rid.split(":", 1)[1]


def convert(files):
    effects = {local("minecolonies:" + p[:-5]): f for p, f in files.items() if p.startswith("effects/")}
    branches, researches = [], []
    for p, f in sorted(files.items()):
        if "/" not in p:  # a branch: civilian.json
            bid = p[:-5]
            branches.append({"id": bid, "name": BRANCHES[bid], "baseTime": f.get("base-time", 1.0),
                             "hidden": f.get("hidden", False), "sort": f.get("sortOrder", 0)})
    for p, f in sorted(files.items()):
        if "/" not in p or p.startswith("effects/"):
            continue
        rid = p[:-5]
        costs = []
        for c in f.get("costs", []):
            if c["type"].endswith("item_simple"):
                items = [OUR_ITEMS.get(c["item"], c["item"])]
                costs.append({"items": items, "count": c["quantity"]})
            elif c["type"].endswith("item_tag"):
                t = c["tag"]
                costs.append({"items": OUR_TAGS[t], "count": c["quantity"]} if t in OUR_TAGS else {"tag": t, "count": c["quantity"]})
            else:
                sys.exit("unknown cost type in %s: %s" % (p, c["type"]))
        reqs = []
        for r in f.get("requirements", []):
            kind = r["type"].split(":")[1]
            if kind not in ("building", "single-building", "alternate-building"):
                sys.exit("unknown requirement type in %s: %s" % (p, r["type"]))  # the plugin reads building requirements only
            buildings = [r["building"]] if "building" in r else r["alternate-buildings"]  # alternates: any one of them, as summed levels
            reqs.append({"buildings": [b.split(":")[1] for b in buildings], "level": r["level"], "single": kind == "single-building"})
        effs = []
        for e in f["effects"]:
            data = effects[local(e["id"])]
            levels = data.get("levels", [1.0])
            effs.append({"id": local(e["id"]).split("/")[-1], "strength": levels[min(e.get("level", 1), len(levels)) - 1]})
        name = rid.split("/")[1]
        if name not in NAMES:
            sys.exit("no name for research " + rid)
        researches.append({
            "id": rid, "branch": local(f["branch"]), "name": NAMES[name], "depth": f["researchLevel"],
            "parent": local(f["parentResearch"]) if "parentResearch" in f else None,
            "sort": f.get("sortOrder", 1000), "onlyChild": f.get("exclusiveChildResearch", False),
            "noReset": f.get("no-reset", False),  # autostart, instant, hidden: only the Unlockables use them (not done)
            "costs": costs, "requirements": reqs, "effects": effs,
        })
    return {"branches": branches, "researches": researches}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("source")
    ap.add_argument("-o", "--out", default=os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                                                         "src", "main", "resources", "research.json"))
    a = ap.parse_args()
    tree = convert(read(a.source))
    with open(a.out, "w") as fh:
        json.dump(tree, fh, indent=1)
        fh.write("\n")
    print("%d branches, %d researches -> %s" % (len(tree["branches"]), len(tree["researches"]), a.out))


if __name__ == "__main__":
    main()
