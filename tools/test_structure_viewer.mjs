// Checks tools/structure-viewer.html's NBT and report code. Run from the repo root: node tools/test_structure_viewer.mjs
// Needs python on the PATH: the NBT sample is written by tools/nbt.py, the same writer the converter uses.
import { readFileSync, mkdtempSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { gunzipSync } from "node:zlib";
import { tmpdir } from "node:os";
import { join } from "node:path";
import assert from "node:assert/strict";

const html = readFileSync("tools/structure-viewer.html", "utf8");
const code = html.slice(html.indexOf("/* nbt-start"), html.indexOf("/* nbt-end */"));
const { readNbt, writeNbt, parseState, parseReport, stateString, keepProps } =
  new Function(code + "; return { readNbt, writeNbt, parseState, parseReport, stateString, keepProps };")();

// 1. Round trip: a file from the converter's writer reads and writes back byte for byte (every tag type, an empty list).
const out = join(mkdtempSync(join(tmpdir(), "nbt-")), "sample.nbt");
const py = `
import sys; sys.path.insert(0, "tools"); import nbt
nbt.save(sys.argv[1], {
  "DataVersion": (nbt.INT, 4556), "size": (nbt.LIST, (nbt.INT, [3, 2, 1])),
  "palette": (nbt.LIST, (nbt.COMPOUND, [{"Name": (nbt.STRING, "minecraft:oak_stairs"),
      "Properties": (nbt.COMPOUND, {"facing": (nbt.STRING, "north"), "half": (nbt.STRING, "bottom")})},
      {"Name": (nbt.STRING, "minecraft:chest")}])),
  "blocks": (nbt.LIST, (nbt.COMPOUND, [{"pos": (nbt.LIST, (nbt.INT, [0, 0, 0])), "state": (nbt.INT, 0)},
      {"pos": (nbt.LIST, (nbt.INT, [2, 1, 0])), "state": (nbt.INT, 1), "nbt": (nbt.COMPOUND, {"id": (nbt.STRING, "minecraft:chest"),
        "PublicBukkitValues": (nbt.COMPOUND, {"minecp:hut": (nbt.BYTE, 1)})})}])),
  "entities": (nbt.LIST, (nbt.COMPOUND, [])),
  "odd": (nbt.COMPOUND, {"s": (nbt.SHORT, -2), "l": (nbt.LONG, -9007199254740993), "f": (nbt.FLOAT, 1.5), "d": (nbt.DOUBLE, -0.1),
      "ba": (nbt.BYTE_ARRAY, bytes([0, 255, 7])), "ia": (nbt.INT_ARRAY, [1, -1]), "la": (nbt.LONG_ARRAY, [2**40, -3]),
      "u": (nbt.STRING, "Ünïcødé ✓")}),
})`;
const r = spawnSync("python", ["-I", "-c", py, out], { encoding: "utf8" });
assert.equal(r.status, 0, r.stderr);
const raw = new Uint8Array(gunzipSync(readFileSync(out)));
const parsed = readNbt(raw);
assert.deepEqual(writeNbt(parsed), raw, "re-written NBT differs from the original bytes");
const palette = parsed.root.get("palette").v.items;
assert.equal(stateString(palette[0]), "minecraft:oak_stairs[facing=north,half=bottom]");
assert.equal(stateString(palette[1]), "minecraft:chest");
assert.equal(parsed.root.get("odd").v.get("l").v, -9007199254740993n);
assert.equal(parsed.root.get("odd").v.get("u").v, "Ünïcødé ✓");

// 2. Block states typed into the page.
assert.deepEqual(parseState("oak_stairs[facing=north, half=bottom]"),
  { name: "minecraft:oak_stairs", props: [["facing", "north"], ["half", "bottom"]], hasProps: true });
assert.deepEqual(parseState(" minecraft:White_Terracotta "), { name: "minecraft:white_terracotta", props: [], hasProps: false });
assert.equal(parseState("oak stairs"), null);
assert.equal(parseState("oak_stairs[facing]"), null);
assert.deepEqual(keepProps("minecraft:oak_planks", [["facing", "north"]], { "minecraft:oak_planks": {} }), []);
assert.deepEqual(keepProps("minecraft:oak_planks", [["facing", "north"]], null), [["facing", "north"]]);

// 3. The real report.
const report = parseReport(readFileSync("docs/structures/medievaloak.md", "utf8"));
assert.ok(!report.has("fixing a building by hand"));
const farmer4 = report.get("medievaloak/agriculture/horticulture/altfarmer4");
const framed = farmer4.find(g => g.from.startsWith("domum_ornamentum:framed"));
assert.equal(framed.count, 45);
assert.equal(framed.positions.length, 45);
assert.deepEqual(framed.positions[0], [3, 2, 6]);
assert.equal(framed.to, "minecraft:oak_planks");
const filler = farmer4.find(g => g.to === "minecraft:barrier");
assert.equal(filler.count, 126);
assert.equal(filler.positions.length, 0); // listed as a count only; the page finds them in the structure
for (const [key, groups] of report) for (const g of groups)
  if (g.positions.length) assert.equal(g.positions.length, g.count, `${key}: ${g.note}`);

console.log(`ok: NBT round trip, block states, report (${report.size} structures)`);
