# MineColonies for vanilla clients: feasibility research

Researched 2026-11-14. Every claim has a source number from [Sources](#sources). Anything marked **UNVERIFIED** I could not confirm from a primary source.

## TL;DR

- **MineColonies is stuck on MC 1.20.1 and 1.21.1.** The latest Java release is **26.3** (Sept 2026). I found no public branch or PR porting MineColonies past 1.21.1 [1][2][3].
- **Feasible, but only as a from-scratch reimplementation.** MineColonies cannot be "ported" to vanilla clients. About 2,000 Java files, its GUIs (BlockUI), citizen renderer, custom blocks and every building blueprint depend on client-side mods (Structurize, BlockUI, Domum Ornamentum) [3][4][5].
- **Recommended stack:** a **Paper 26.3 plugin** with a **server-sent resource pack**.
  - Mannequins are the visible colonists.
  - Pathing comes from Citizens, or from your own "hidden pathfinder mob" driver.
  - Inventory GUIs and Dialogs replace BlockUI screens.
  - Display entities plus `item_model` stand in for custom blocks.
  - FAWE/WorldEdit handles schematics.
- **A datapack alone is not realistic.** It has no pathfinding API, no real data structures, and step-by-step building is painful.
- **Mannequins work as bodies but have no AI.** The 25w36a changelog says they have "no movement AI" [6]. In the API a Mannequin is a `LivingEntity`, not a `Mob`, so Paper's `Pathfinder` cannot drive it [7][8]. **Citizens already supports Mannequin NPCs on 26.1–26.3** with its own navigation [9]. That is the cheapest way to get walking colonists.
- **MVP** (town hall, builder, woodcutter or farmer, warehouse/courier-lite, food): about **2–4 months** of evenings for one experienced plugin dev. Full MineColonies parity is a multi-year team effort.
- **Licensing:**
  - Code is GPL-3.0, so you may port or derive from it only if your plugin is GPL-3.0 [1].
  - MineColonies' `assets/` folder (textures, models, sounds) is marked **"All Rights Reserved"**, so you must not reuse it [10].
  - Blueprints live outside `assets/` and fall under the repo's GPL. They still need conversion because they use modded blocks.

## 1. MineColonies' current state

| Item | Finding | Source |
|---|---|---|
| Default branch | `version/main` targets **MC 1.20.1 / Forge 47** | [2] |
| Other active branch | `version/1.21` targets **1.21.1** (NeoForge), `minecraft_range=[1.21,1.22)`, last commit 2026-11-11 | [2] |
| Releases | Snapshot builds still ship for 1.20.1 and 1.21.1 only (e.g. v1.21.1-1.1.1420, 2026-11-11) | [3] |
| Port in progress? | No branch past `version/1.21`. A stale `port1.21` branch was last touched 2024-12. No open PR mentions 1.21.2+ or 26.x. | [2][3] |
| Latest Minecraft | **26.3** release (2026-09-15). Latest snapshot **26.4-snapshot-8** (2026-11-10). Versions became year.drop in Dec 2025. | [11][12] |

Conclusion: MineColonies is 1.21.1 or nothing, and 1.21.1 is about five drops behind current. Waiting for an official port is not a plan.

## 2. Feature inventory: what needs a client mod vs. what works server-side

Codebase scale on `version/1.21` [4]:
- ~2,065 Java files
- 107 GUI classes plus BlockUI XML layouts
- 47 worker buildings and 44 job classes
- 327 research JSONs
- ~9,450 `.blueprint` files across 25+ style packs
- 5 raider cultures (barbarian, pirate, Egyptian, Norse, Amazon)
- A custom pathfinding package

| Feature | How MineColonies does it (client-mod parts) | Vanilla-client replacement | Difficulty |
|---|---|---|---|
| Colonists | Custom `EntityCitizen` with its own model and renderer (`RenderBipedCitizen`, Male/FemaleCitizenModel) [4] | **Mannequin** (player model, skin via profile, armor and held items) [6][7]. Name and job go in `description`. | Medium (movement is the hard part, §3) |
| Citizen pathfinding/AI | Large custom pathfinding (`core/entity/pathfinding/*`) and per-job AI state machines [4] | Citizens navigator, or a hidden vanilla `Mob` running Paper `Pathfinder.moveTo` with the Mannequin following [8][9]. Job AI becomes plugin state machines. | High |
| Town hall / hut blocks | Custom blocks and block entities (`blockhut*`) [4][5] | A vanilla block (e.g. barrel or lectern) tagged in plugin storage, plus an `item_display` using a custom `item_model` for the look [13][14] | Low–Medium |
| Builder with blueprints | Structurize (client mod) shows previews and handles placement; blueprints are gzipped NBT with a palette and `required_mods` [5][15] | Plugin reads Sponge `.schem` via WorldEdit/FAWE (supports 26.3 [16]). Builder places blocks one by one from the clipboard. Preview uses `block_display` ghosts [14]. | Medium–High |
| Building/colony GUIs | BlockUI (client mod) XML windows [4][17] | Chest inventory menus (resource-pack font tricks for textured backgrounds). **Dialogs** (1.21.6+) for forms with text, checkbox, option and slider inputs [18]; Paper Dialog API since 1.21.7 [19]. | Medium |
| Request system / warehouse / couriers | Server logic (`api/colony/requestsystem`) [4] | Pure server logic, reimplemented in the plugin | High (most complex non-visual part) |
| Happiness, food, housing, saturation | Server logic | Plugin logic. Feedback through `description`, scoreboards or boss bars. | Medium |
| Research tree | Data JSONs and a custom GUI [4] | Data files plus a paginated inventory or `dialog_list` [18] | Medium |
| Raids | Custom raider entities and models (`client/model/raiders`) [4] | Vanilla pillagers or zombies with equipment, or Mannequin raiders; Paper `Pathfinder` works on real mobs [8] | Medium |
| Custom items (clipboard, scepter, supply ship/camp) | Custom items with models | Vanilla items with `item_model` / `custom_model_data` from the resource pack; `consumable`/`equippable` components where useful [13][20] | Low |
| Decorative blocks (Domum Ornamentum) | Retexturable modded blocks | **Not reproducible** as real blocks. Map them to the nearest vanilla block. | n/a: drop the feature |
| Sounds and voice lines | Custom sounds | Resource-pack sounds (assets are ARR, so record your own [10]) | Low |

**Hard limits for a vanilla client:**
- No new block models. The only options are retexturing existing block states or using display entities.
- No new entity models beyond what display entities and Mannequins can fake.
- No custom screens beyond chest-style menus, Dialogs, books, signs and anvils.
- A resource pack can be forced at join with `require-resource-pack` (max 250 MiB) [21], or sent per player through Paper's `Player#addResourcePack` [22].

## 3. Mannequin specifics

Facts:
- **Added:** snapshot 25w36a, released in **1.21.9**. Java only. Spawned only by `/summon` or API; no spawn egg [6][23].
- **NBT fields** [23]:
  - `profile`: a player name or texture/cape/model, so any skin.
  - `hidden_layers`, `main_hand`.
  - `pose`: `standing`, `crouching`, `swimming`, `fall_flying` or `sleeping`.
  - `immovable`: can't be pushed.
  - `description`: text below the name.
  - `hide_description`.
- **Paper API** (`org.bukkit.entity.Mannequin`, paper-api 26.3) [7]:
  - extends **`LivingEntity` (not `Mob`)**
  - `get/setProfile(ResolvableProfile)`, `setSkinParts`, `setImmovable`, `setDescription(Component)`, `setMainHand`, `getEquipment()`
  - pose through `Entity#setPose` within `validPoses()`
- **Combat and equipment:** 20 HP, 0.6×1.8 hitbox, holds items and wears armor, cannot pick up items, no drops. Falls and takes fall damage (it plays the big-fall sound) [23].
- **No AI:** the changelog says it "has no movement AI" [6]. Mob tags such as `NoAI`, `PersistenceRequired` and `CanPickUpLoot` do not apply [23].

Ways to move a Mannequin:

1. **Citizens Mannequin NPCs (recommended starting point).**
   - Citizens 2 (active, last commit 2026-11-01) has NMS modules `v26_1_R1`, `v26_2_R1` and `v26_3_R1`.
   - It has a `MannequinController` that subclasses the vanilla Mannequin and adds a forwarded Mob AI, so it can navigate (`tickAI()`, `travel()`) [9].
   - It works through `CitizensAPI` (navigator, waypoints, traits).
   - Downside: a heavy dependency that is licensed **OSL-3.0** (see §6).
2. **Hidden pathfinder mob plus a following Mannequin.**
   - Spawn an invisible, silent, invulnerable vanilla `Mob` (e.g. a villager or zombie with no goals).
   - Hide it from every player with `Player#hideEntity` [22], and drive it with Paper `mob.getPathfinder().moveTo(loc, speed)` [8].
   - Each tick, move the Mannequin to the mob's position and yaw with velocity or teleport.
   - Alternative: use `findPath()` for nodes only and step the Mannequin along them yourself.
3. **Mannequin as a passenger of the hidden mob.** Simplest to code, but a player-model rider will probably **render in the sitting pose**. **UNVERIFIED**: test before relying on it.
4. **Your own NMS subclass**, as Citizens does. Most control, but you have to fix it again on every MC drop.

Animation:
- Walking: the vanilla client usually animates legs from position changes of any living entity. So moving a Mannequin smoothly (velocity or small relative moves) should show a walk cycle, and Citizens Mannequin NPCs are reported to walk [9]. **UNVERIFIED** for teleport-based movement: prototype first.
- Arm swing: use `LivingEntity#swingMainHand` (standard Bukkit API, **UNVERIFIED** on Mannequin specifically).
- Anything beyond those five poses and the swing needs display-entity rigs.

Performance:
- Approach 2 doubles the entity count (one mob and one Mannequin per colonist) and adds pathfinding cost on the server.
- Display entities are sent to every client in range and count toward the client entity total [14].
- 20–50 colonists per colony is fine. Hundreds per server needs pathfinding budgets: stagger path requests and pause AI in unloaded chunks. MineColonies itself needed an "entity fix" plugin when run on Spigot hybrids [24].

## 4. Plugin vs. datapack

| Need | Datapack | Paper plugin |
|---|---|---|
| Colonist pathfinding | None. You'd hand-roll A* in functions (community packs exist [25]), which is slow and fragile. | `Pathfinder` API on mobs [8], or Citizens [9] |
| Persistent complex state (requests, inventories, research) | Command storage NBT and scoreboards only | Java objects plus files or SQLite |
| Building over time | Possible (`place template` / per-tick `setblock` functions) but painful for rotation, materials and resumption | Straightforward with WorldEdit/FAWE clipboards [16] |
| GUIs | Dialogs [18], books, chest tricks | Same, plus full inventory event handling [19] |
| Custom content | Data-driven registries such as dialogs, enchantments, loot, recipes, structures [26] | Same, through bootstrap and registries |
| Mannequins | `/summon` works [23] | Full API [7] |

Verdict: build a **Paper plugin plus a resource pack**. A datapack is fine as a supporting layer (dialogs, recipes, loot) but cannot carry the AI or the economy.
- Spigot: I did not verify that Spigot exposes a Mannequin API. Paper's API is confirmed [7].
- Purpur is a Paper fork, so a Paper plugin runs on it.

Existing projects to reuse or study:

| Project | Status | Use |
|---|---|---|
| **Citizens2** | Active, supports 26.1–26.3 and Mannequin NPCs, OSL-3.0 [9] | NPC bodies and navigation |
| **WorldEdit / FastAsyncWorldEdit** | Active. FAWE 2.16.2 (2026-11-01) has adapters through 26.3 [16]. | Schematic I/O and placement |
| **TownyCivs** | "Inspired by minecolonies". Targets Paper/Folia 1.19.4, needs Towny, last push 2023-08, MIT [27] | Design reference only; abandoned |
| **FancyNpcs** | Packet-based NPCs. Last push 2025-07, MIT [28]. Its movement addon walks straight lines only [29]. | Not suitable for pathing |
| **EntityUtils** | Paper NPC with A* and animations [30] | **UNVERIFIED** version support |

I found **no maintained MineColonies-equivalent Paper plugin**.

## 5. Schematics: Structurize vs. Sponge `.schem`

- **Structurize `.blueprint` format:**
  - Gzipped NBT with `version`, `size_x/y/z`, a `palette` of block states, an int `blocks` array, tile entities, `required_mods` and `name` [15].
  - Style packs ship a `pack.json` (name, authors, `mods: [structurize, minecolonies]`) [5].
- **Reuse problem:** sampled blueprints (`medievaloak/fundamentals/altbuilder1`, `altcook1`) include these non-vanilla palette entries [5]:
  - `domum_ornamentum:*` (shingles, `materially_retexturable`, barrel decos)
  - `structurize:blocksubstitution` / `blocksolidsubstitution` placeholders
  - `minecolonies:blockhut*` / `colonybuilding`
- **Conversion is still feasible.** A one-off converter can:
  - map Domum blocks to their main vanilla material (texture data sits in block-entity NBT; **UNVERIFIED** detail)
  - replace substitution blocks with air or "keep existing"
  - replace hut blocks with your marker block
  - write Sponge `.schem` for FAWE/WorldEdit
- **The cost is looks:** Domum shingle roofs become plain stairs and slabs. Hand-building or commissioning vanilla-only schematic packs may look better.

## 6. Licensing

| Thing | License | Implication |
|---|---|---|
| MineColonies code | GPL-3.0 [1]. Contributors sign a CLA licensing to LDT and recipients [31]. | Porting or translating code (pathfinding, request system) makes your plugin a derivative, so it must be **GPL-3.0** with source available. Clean-room reimplementation from behavior avoids this. |
| MineColonies `assets/` (textures, models, sounds, GUI art) | **"All Rights Reserved"** [10] | **Do not reuse** in your resource pack. Make or commission your own. |
| Blueprints (`src/main/resources/blueprints/...`) | No separate license file found, so the repo's GPL-3.0 presumably applies. Authors are credited in `pack.json` [5]. | Reuse is probably OK in a GPL-3.0 project with attribution. **UNVERIFIED**: ask LDT on Discord before redistributing. |
| Structurize, BlockUI, Domum Ornamentum | GPL-3.0 [32] | Same as the code |
| Citizens2 / CitizensAPI | OSL-3.0 [9] | The FSF lists OSL as GPL-incompatible [33]. A GPL-3.0 plugin that hard-depends on Citizens is a grey area. Use Citizens only if your plugin is not derived from MineColonies code, or write your own movement. |
| WorldEdit / FAWE | GPL-3.0 family [16] | Compatible with a GPL plugin |
| Private use | Unaffected | Running it privately for a friend triggers no GPL distribution obligations. All of this matters only if you publish it. |

## 7. Feasibility verdict

**Feasible for a "MineColonies-lite"; full parity is not.** Every core mechanic can be expressed with a vanilla client. Fidelity loss falls mainly on custom blocks, GUI polish and entity models.

### MVP scope
1. **Colony and town hall:** place a "town hall" item, which creates a claim. Inventory GUI for colony info and hiring. Citizens spawn in houses as Mannequins with random skins.
2. **Builder:** a hut block plus a build-order GUI. The builder walks to site, takes materials from its hut chest, and places blocks layer by layer from a `.schem`. Build preview uses ghost `block_display`. Two or three upgrade levels.
3. **Two jobs:** woodcutter (fell trees, replant) and farmer (till, plant, harvest). Both deposit to the hut chest.
4. **Lite logistics:** the builder lists missing materials. One courier moves items from a warehouse chest network.
5. **Food and happiness:** a simple food meter. Unhappy or starving colonists stop working.
6. **Persistence:** colonies, citizens and buildings saved to disk. Survives restart and chunk unloads.

Rough effort for one experienced plugin dev, part-time:

| Component | Estimate |
|---|---|
| Entity layer (Mannequin, movement, skins, nametags) | 2–3 weeks |
| Builder plus schematics | 3–4 weeks |
| Two jobs | 2–3 weeks |
| GUIs, persistence, resource pack | 3–4 weeks |
| **Total** | **≈ 2–4 months** |

Raids, research, guards, 40+ more jobs and the full request system would each add months.

### Biggest risks
1. **Colonist movement quality.** Mannequins have no AI [6] and aren't `Mob`s [7]. Getting smooth, door-opening, ladder-climbing walks that animate correctly is the main technical risk, so prototype it first. Citizens [9] reduces the risk.
2. **NMS churn.** Mojang ships about 4 drops a year now [11][12]. Anything beyond the Paper API, including Citizens' NMS modules, breaks on every drop. Stay on the Paper API where possible.
3. **Builder AI complexity:** material accounting, block ordering (supports before torches, doors and redstone), resuming after restarts.
4. **Content volume:** schematics and art. MineColonies' art is ARR [10], and its blueprints need lossy conversion [5].
5. **Performance** with many pathing entities (§3).

### Recommended stack
- **Server:** Paper 26.3 (API confirmed for Mannequin, Pathfinder and Dialogs [7][8][19]).
- **Plugin:** Java 21+ with Paper API.
  - Optional Citizens2 for movement (mind the license) [9].
  - FAWE or WorldEdit for schematics [16].
- **Resource pack:** forced through `require-resource-pack` [21]. Holds `item_model` definitions for hut/tool items and display-entity props [13][20], GUI font textures, and sounds.
- **Data:** plugin-owned storage (YAML/JSON/SQLite) keyed by colony ID. Use entity PDC only for tagging.
- **First spike (1 weekend):**
  - One Mannequin that walks to a clicked block using a hidden pathfinder mob.
  - Check the walking animation and the passenger pose (the UNVERIFIED items above).
  - Try the same with a Citizens Mannequin NPC, then pick the approach.

## Sources

1. MineColonies repo, license GPL-3.0 — https://github.com/ldtteam/minecolonies (LICENSE: https://github.com/ldtteam/minecolonies/blob/version/main/LICENSE)
2. MineColonies `gradle.properties` on `version/main` (1.20.1) and `version/1.21` (1.21.1); branch list — https://github.com/ldtteam/minecolonies/blob/version/main/gradle.properties , https://github.com/ldtteam/minecolonies/blob/version/1.21/gradle.properties , https://github.com/ldtteam/minecolonies/branches/all
3. MineColonies releases (1.20.1 and 1.21.1 only) — https://github.com/ldtteam/minecolonies/releases ; open PRs — https://github.com/ldtteam/minecolonies/pulls
4. MineColonies source tree, `version/1.21` (render/model classes, `core/entity/pathfinding`, `core/colony/events/raid`, `api/colony/requestsystem`, researches) — https://github.com/ldtteam/minecolonies/tree/version/1.21/src
5. MineColonies blueprints/style packs (e.g. Medieval Oak `pack.json`, `fundamentals/*.blueprint`) — https://github.com/ldtteam/minecolonies/tree/version/1.21/src/main/resources/blueprints/minecolonies/medievaloak
6. Java Edition 25w36a changelog ("has no movement AI", "function as living entities, like armor stands") — https://minecraft.wiki/w/Java_Edition_25w36a ; official post — https://www.minecraft.net/en-us/article/minecraft-snapshot-25w36a (timed out on fetch; cited via wiki)
7. Paper Javadoc `Mannequin` (paper-api 26.3) — https://jd.papermc.io/paper/org/bukkit/entity/Mannequin.html
8. Paper Javadoc `Pathfinder` — https://jd.papermc.io/paper/com/destroystokyo/paper/entity/Pathfinder.html
9. Citizens2 repo (OSL-3.0; modules v26_1_R1..v26_3_R1; `MannequinController`, `MannequinTrait`; "Fix mannequin npc" commit 2026-10-21) — https://github.com/CitizensDev/Citizens2 , https://github.com/CitizensDev/Citizens2/blob/master/v26_3_R1/src/main/java/net/citizensnpcs/nms/v26_3_R1/entity/MannequinController.java ; CitizensAPI (OSL-3.0) — https://github.com/CitizensDev/CitizensAPI
10. MineColonies assets license ("All Rights Reserved") — https://github.com/ldtteam/minecolonies/blob/version/1.21/src/main/resources/assets/minecolonies/LICENSE
11. Mojang version manifest (latest release 26.3, snapshot 26.4-snapshot-8) — https://piston-meta.mojang.com/mc/game/version_manifest_v2.json
12. Java Edition (versioning scheme change) — https://minecraft.wiki/w/Java_Edition
13. Data component format (`item_model`, `equippable`) — https://minecraft.wiki/w/Data_component_format
14. Display entities — https://minecraft.wiki/w/Display
15. Structurize `BlueprintUtil.java` (blueprint NBT format) — https://github.com/ldtteam/Structurize/blob/version/main/src/main/java/com/ldtteam/structurize/blueprints/v1/BlueprintUtil.java
16. FastAsyncWorldEdit (2.16.2, adapters through 26.3) — https://github.com/IntellectualSites/FastAsyncWorldEdit ; WorldEdit — https://github.com/EngineHub/WorldEdit
17. BlockUI — https://github.com/ldtteam/BlockUI
18. Dialog (added 1.21.6 / 25w20a) — https://minecraft.wiki/w/Dialog
19. Paper Dialog API docs — https://docs.papermc.io/paper/dev/dialogs/
20. Java Edition 1.21.2 (`item_model`, `equippable`, `consumable` added) — https://minecraft.wiki/w/Java_Edition_1.21.2
21. server.properties (`resource-pack`, `require-resource-pack`) — https://minecraft.wiki/w/Server.properties
22. Paper Javadoc `Player` (`hideEntity`, `addResourcePack`) — https://jd.papermc.io/paper/org/bukkit/entity/Player.html
23. Mannequin — https://minecraft.wiki/w/Mannequin
24. MinecoloniesEntityFix — https://www.curseforge.com/minecraft/bukkit-plugins/minecoloniesentityfix-reshenie-problemy-zamorozki
25. AI Pathfinder (datapack; recommends Mannequin base on 1.21.9+) — https://modrinth.com/mod/pathfinding
26. Data pack — https://minecraft.wiki/w/Data_pack
27. TownyCivs — https://github.com/NeumimTo/TownyCivs
28. FancyNpcs — https://github.com/FancyMcPlugins/FancyNpcs
29. Fancy NPC Movement Addon — https://modrinth.com/plugin/fancy-npc-movement-addon
30. EntityUtils — https://github.com/Yoursole1/EntityUtils
31. LDTTeam CLA — https://github.com/ldtteam/minecolonies/blob/version/main/CLA.md
32. Structurize / BlockUI / Domum Ornamentum (GPL-3.0) — https://github.com/ldtteam/Structurize , https://github.com/ldtteam/BlockUI , https://github.com/ldtteam/domum-ornamentum
33. FSF license list (OSL marked GPL-incompatible) — https://www.gnu.org/licenses/license-list.html (rate-limited on fetch, so not re-verified this session)
