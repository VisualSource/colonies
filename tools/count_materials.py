"""Totals the blocks a style's huts cost, as items, to see which crafters and recipes matter most.

Usage:
  python3 tools/count_materials.py [--style medievaloak] [--levels 1 2 3] [--top 60] [hut paths ...]

Hut paths are relative to the style folder (e.g. fundamentals/builder); by default the core huts. Each level's
structure is counted in full, so the totals are an upper bound on what upgrading through those levels costs.
"""
import argparse
import collections
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import nbt  # noqa: E402

CORE = ["fundamentals/townhall", "fundamentals/builder", "fundamentals/residence", "craftsmanship/storage/warehouse",
        "craftsmanship/storage/deliveryman", "fundamentals/lumberjack", "agriculture/horticulture/farmer",
        "fundamentals/cook", "fundamentals/miner"]
FREE = {"air", "cave_air", "structure_void", "barrier", "fire"}  # barrier: foundation filler, laid only where needed


def item(block):
    """The item a block costs (as the builder charges it, roughly)."""
    n = block.split(":")[1]
    n = re.sub(r"wall_(torch|sign|banner|head)", r"\1", n).replace("_wall_hanging_sign", "_hanging_sign").replace("potted_", "")
    return {"redstone_wire": "redstone", "farmland": "dirt", "dirt_path": "dirt", "grass_block": "dirt",
            "water": "water_bucket", "lava": "lava_bucket"}.get(n, n)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("huts", nargs="*", default=CORE)
    ap.add_argument("--style", default="medievaloak")
    ap.add_argument("--levels", nargs="+", type=int, default=[1, 2, 3])
    ap.add_argument("--top", type=int, default=60)
    a = ap.parse_args()
    root = os.path.join("run/world/datapacks", "minecp-" + a.style, "data/minecp/structure", a.style)
    total = collections.Counter()
    for hut in a.huts:
        for level in a.levels:
            path = os.path.join(root, f"{hut}{level}.nbt")
            if not os.path.exists(path):
                print(f"missing: {path}", file=sys.stderr)
                continue
            s = {k: nbt.plain(v) for k, v in nbt.load(path).items()}
            palette = s["palette"] if "palette" in s else s["palettes"][0]
            for b in s["blocks"]:
                state = palette[b["state"]]
                name, props = state["Name"], state.get("Properties", {})
                if name.split(":")[1] in FREE or props.get("part") == "head" or ("door" in name and props.get("half") == "upper"):
                    continue  # the other half of a bed or door is paid by the first
                total[item(name)] += 2 if props.get("type") == "double" else 1
    for name, n in total.most_common(a.top):
        print(f"{n:6} {name}")


if __name__ == "__main__":
    main()
