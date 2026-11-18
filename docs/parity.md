# MineColonies parity checklist

What MineCP has compared with MineColonies (`version/main`), and what is left. `[x]` done, `[~]` partly done (gaps listed under it), `[ ]` not started, ~~crossed out~~ skipped. Add notes under any item.

## Citizens

- [~] Citizens: names, skins, wandering, homes, jobs
  - No citizen skills or levels (in the mod they change work speed and quality)
  - No citizen inventory; workers use a simple carried list
  - No chat interactions or requests above citizens' heads; reasons are shown in the hut menus instead
  - Ladders: vanilla paths never climb, so citizens walk to a ladder within 12 blocks on their floor and go to where it meets the target's floor; with no such ladder, they teleport near the target. Old movement teleports along the ladder; new movement (`/colony movement new`, being tested) climbs and slides with vanilla physics. Routes are still vanilla's; our own async pathfinder is next (`docs/research/citizen-movement-system.md`)
- [~] Hunger and food (saturation numbers from the mod)
  - Work drain assumes the worker is always busy; the mod counts actions
  - No food quality tied to house level
  - No food variety (diet diversity)
  - Saturation doesn't affect health
- [~] Day/night routine (`CitizenAI`, `EntityAISleep`, `CitizenSleepHandler`): from day time 10600 citizens leave for home when the walk (6 ticks a block, height counted 1.5x, the miner's climb added) would get them there by nightfall at 12600; they sleep in a house bed and get up at dawn; a colony day counter goes up each morning; in rain only the cook and level 5 huts' workers work
  - Beds are found near the house's hut block (within 8 blocks), not from the building's registered beds
  - Homeless citizens wait at the town hall (the mod sends them to a tavern if there is one)
  - No "all citizens are asleep" message, sleeping particles, off-to-bed sounds, or "home too far" complaint
  - No research that lets citizens work longer or in the rain
  - Sleep isn't checked by health, sickness, raids or mourning yet (those come first in the mod)
- [ ] Health, death and the graveyard
- [ ] Happiness
- [ ] Sickness and the hospital
- [ ] Children, growing up, the school and the library
- [ ] Mourning

## Colony

- [~] Town Hall: founds the colony, levels gate other huts' levels, citizen list
  - No claimed area or colony border; huts just have to be within 64 blocks of the town hall
  - No permissions for friends (only the owner can use huts)
  - No colony events or statistics (advancements are listed below)
- [~] Colony growth: a citizen every 2 minutes while there is housing
  - Not checked against the mod's rules
- [ ] Raids (`core/colony/events/raid/RaidManager.java`)
  - [ ] Raid schedule: night raids, with a minimum and an average number of nights between them (server config settings in the mod)
  - [ ] Raids start only once the colony's building levels add up to 75 or more
  - [ ] Raid difficulty: starts at 7 and stays between 1 and 14; goes down when many citizens die in a raid (15% steps) and up otherwise; scales with the number of players
  - [ ] Number of raiders from colony size and difficulty (formula not checked yet)
  - [ ] Spawn point at least 35 blocks from any building, with a chat warning to colony members
  - [ ] Raider cultures picked by the colony's biome, with a small random chance to ignore the biome:
    - Barbarians (the default): melee, archer, chief
    - Egyptians (desert): mummy, archer mummy, pharaoh
    - Amazons (jungle): spearman, archer, chief
    - Norsemen (taiga): melee, archer, chief
    - Pirates: melee, archer, captain
    - Drowned pirates (ocean)
  - [ ] Ship raids: pirate, Norsemen and drowned pirate ships spawned as structures by water, with raider spawners on board
  - [ ] Ground hordes for the other cultures, with camps and a campfire
  - [ ] Raider AI: walk to the colony, melee and ranged attacks, target citizens, guards and players
  - [ ] Raid sounds (horn) and raider sounds
  - [ ] Raid history: raiders, citizens lost, difficulty; shown in the town hall
  - [ ] Citizens flee or hide during a raid
  - [ ] Raider-damage enchantment
  - [ ] Mercenaries hired from the town hall
  - [ ] Raid commands (start a raid, raid info, can a raider spawn, kill raiders)
  - Can't be ported cleanly: custom raider models and textures. Decided stand-in: plain vanilla mobs (not the Mannequin and hidden mob pair citizens use), dressed in culture gear and picked to match the biome (for example husks for Egyptians, drowned for drowned pirates). Which mob plays each raider is still to be chosen.
- [ ] Guards (see Worker huts)
- [ ] Research (university)
- [ ] Quests
- [ ] Visitors and the tavern
- [ ] Supply camp and supply ship starts
- [ ] Colony flag and banner
- [ ] Advancements (the mod's `data/minecolonies/advancements/`; its recipe-unlock advancements are left out)
  - Can port cleanly: our data pack holds the advancement files with vanilla item icons, each with an impossible criterion the plugin grants when it happens
  - MineColonies tab:
    - Place a town hall; build the town hall; town hall level 5
    - Start a builder; build the builder's hut; builder's hut level 2
    - Build a house for a citizen; build a guard tower; build a tavern; build the mystical site; mystical site level 5
    - Colony population 5, 10, 25, 50, 75
    - Citizen eats food for the first time; citizen eats rotten flesh
    - Bury a citizen; resurrect a citizen; undertaker's totem
    - Fulfil a request; read the hut guide
    - Add a recipe to a building (door, torch)
  - Production tab:
    - Build each of: lumberjack, farmer, miner, cook, warehouse, courier, baker, blacksmith, composter, crusher, enchanter, fisherman, florist, library, sawmill, sifter, stonemason, stone smeltery
    - Smeltery levels 1, 3 and 5
    - Build all the animal herders
    - Deep mine (the miner reaches a deep level), max fields (a farmer with all its fields)
    - Place a post box and a stash
  - Military tab:
    - Build archery, barracks, barracks tower, combat academy; build all barracks towers
    - Army of 8, 30 and 80 guards
  - Also: craft the supply camp or ship (in the vanilla tab)
  - Not checked: what exactly triggers deep mine, max fields and the undertaker's totem

## Building and items

- [~] Builder: upgrades huts from converted blueprints, uses tools, materials and storage racks
  - Not checked: does the mod only let a builder build huts up to its own level?
  - No decoration or free builds from a build tool; `/colony build` is the stand-in
  - No removal or repair work orders
  - Hut rotation comes from the build tool (for hut blocks placed by hand: the block's facing)
- [~] Blueprints: medieval oak converted to vanilla structures (`tools/convert_blueprints.py`)
  - Other styles not converted
  - Modded blocks approximated (see `docs/structures/medievaloak.md`)
- [~] Build tool (Structurize `ItemBuildTool`, `AbstractBlueprintManipulationWindow`; MineColonies `SurvivalHandler`): the Colony Token is the tool. Right-click a block, pick a hut you carry; a preview of its level 1 building stays until confirmed or cancelled. Right-click again for the mod's controls in a dialog: move forward/back/left/right from where you face, up/down, rotate left/right, mirror (front to back, as the mod), confirm, cancel. Confirming checks the same rules as placing by hand, uses up the hut item, places the hut block and keeps the turn and mirror for every level
  - Preview is block displays at 90% size, only for the placing player (the mod draws see-through blocks); blocks walled in on all sides are skipped
  - The dialog covers the screen, so each button closes it to show the change
  - Picks huts only: no style packs or categories to browse, no decorations (`/colony build` stays the stand-in), only the colony's style
  - No ground-level offset: the hut block goes where you click (the converter drops the blueprints' ground tags)
  - No check that the building stays inside the colony's claimed chunks (we have no claims); hut blocks must be within 64 blocks of the town hall
- [ ] Scan tool
- [~] Hut recipes: MineColonies' shapes, with the Colony Token in place of the build tool
  - Only huts that are built so far have recipes
- [~] Storage rack: own item, recipe, required by the builder, warehouse counts only racks
  - Breaks into a plain chest when blown up
  - No custom model yet (the block is tagged for one)

## Custom blocks and items

Everything the mod registers (`apiimp/initializer/ModBlocksInitializer.java` and `ModItemsInitializer.java`). The build tool, scan tool and decorative blocks come from the mod's companion mods (Structurize, Domum Ornamentum), not MineColonies itself. A vanilla client can't show new blocks or items, so each needs a stand-in: a tagged vanilla block or item, later with a custom model from a resource pack.

### Blocks

- [~] Hut blocks, one per building (50): stand-in is a tagged chest with our recipe; only built huts have one so far
- [~] Storage rack: tagged chest
- [~] Scarecrow (field): tagged hay bale
- ~~Plantation field~~ (skipped)
- [ ] Barrel (composter barrel)
- ~~Composted dirt~~ (skipped)
- ~~Farmland and flooded farmland (for the mod's crops; rice needs flooded farmland)~~ (skipped)
- ~~Crops (bell pepper, cabbage, chickpea, durum, eggplant, garlic, onion, soy bean, tomato, rice, butternut squash, corn, mint, nether pepper, peas)~~ (skipped)
- [ ] Simple and medium quarry (blocks that mark a quarry for the Quarrier; a large one exists but is turned off)
- [ ] Stash (a chest couriers empty to the warehouse): stand-in is a tagged chest
- [ ] Post box (players order items from the warehouse): stand-in is a tagged chest or barrel
- [ ] Waypoint (helps citizens' pathing)
- [ ] Colony sign
- [ ] Colony banner and wall banner (the colony flag): stand-ins are normal vanilla banners and wall banners
- [~] Construction tape (marks a build site): no block; four thin yellow block-display bars along the footprint at the hut block's floor, while the hut is unbuilt or has an upgrade queued or under way (the build tool's preview shows them too). They are straight at one height, where the mod's tape follows the ground
- ~~Decoration placeholder (blueprint filler)~~ (skipped)
- [ ] Grave and named grave (graveyard): stand-ins are a stone brick wall, and a stone brick wall with a sign for the named grave
- ~~Iron gate and wooden gate (large gates)~~ (skipped)

### Items

- [~] Build tool (Structurize): the Colony Token (see Build tool above); `/colony build` for free builds
- [ ] Scan tool (Structurize)
- [ ] Supply chest and supply camp deployers (colony starts)
- [ ] Clipboard (the colony's open requests)
- [ ] Resource scroll (a builder's material list, carried by the player)
- [ ] Scepters: guard (patrol points), lumberjack (restricted area), beekeeper, permission
- [ ] Rally banner (guards follow the player)
- [ ] Build goggles (show blueprints in the world)
- [ ] Scan analyzer
- [ ] Colony map
- [ ] Quest log
- [ ] Adventure token
- [ ] Ancient tome (enchanter)
- [ ] Magic potion
- [ ] Compost (fertiliser from the composter)
- [ ] Mistletoe
- [ ] Santa hat (seasonal)
- [ ] Assistant hammers: gold, iron, diamond
- [ ] Sifter meshes: string, flint, iron, diamond
- [ ] Scrolls: colony teleport, area teleport, buff, guard help, highlight
- [ ] Weapons: chief sword, iron scimitar, pharaoh scepter, fire arrow
  - Spear: use the vanilla spear
  - Stand-in for the rest: the equivalent vanilla weapon, with custom textures from an optional resource pack
- [ ] Armour: plate armour (helmet, chest, legs, boots), pirate outfits (hat, cap, top, chest, legs, leggings, shoes, boots)
  - Stand-in: the equivalent vanilla armour, with custom textures from an optional resource pack
- ~~Spawn eggs for every raider~~ (skipped)
- [ ] Large bottles: empty, water, milk, soy milk

### Foods (listed for completeness; not planned)

- Ingredients and doughs: bread dough, cookie dough, cake batter, raw pumpkin pie, muffin dough, manchet dough, raw noodle, butter, cornmeal, cream cheese, soy sauce, cheddar cheese, feta cheese, cooked rice, tofu, flatbread
- Breads: milky, sugary, golden, chorus, manchet bread, lembas scone, muffin
- Dishes: cheese ravioli, chicken broth, meat ravioli, mint jelly, mint tea, polenta, potato soup, veggie ravioli, yogurt, pottage, plain pasta, apple pie, plain cheesecake, baked salmon, egg drop soup, fish and chips, pierogi, veggie soup, yogurt with berries, borscht, hand pie, mint chocolate cheesecake, schnitzel, steak dinner, squash soup, cabochis, veggie quiche, lamb stew, fish dinner, pea soup, rice ball, mutton dinner, sushi roll, ramen, fried rice, corn chowder, tortillas, tomato pasta, cheese pizza, eggplant dolma, stuffed pita, mushroom pizza, spicy grilled chicken, pepper hummus, kebab, pita hummus, spicy eggplant, congee, kimchi, stew trencher, stuffed pepper, tacos

## Worker huts

- [ ] Tool tiers limited by the worker's own hut level (`IBuilding.getMaxEquipmentLevel`, `ItemStackUtils.verifyEquipmentLevel`); MineCP lets every worker use any tier now
  - Tool level is the tier's attack bonus: wood and gold 0, stone 1, iron 2, diamond 3, netherite 4
  - Highest allowed: hut level 0 or 1 stone, 2 iron, 3 diamond, 4 netherite, 5 (max) anything
  - Enchantments count: the tool's highest enchantment level minus 1 is added (an Efficiency III iron pickaxe counts as 4)
  - The same rule covers every worker's tools and the fisherman's rod; not tied to research or the builder

- [x] House (residence)
- [~] Lumberjack
  - Not checked against the mod: restricted area, which trees, mushroom and nether trees, stripping
- [~] Farmer and fields
  - Done: one action per field per colony day; each field cycles till, plant, harvest; a stage with nothing to do is skipped, and after 4 empty stages the field is done for the day (as in the mod)
  - Sugar cane and others are the plantation's, not done
  - No compost or fertiliser
- [~] Cook (restaurant)
  - No restaurant menu setting (which foods it serves)
  - Doesn't feed players
  - No Kitchen (cook assistant)
- [~] Warehouse and courier
  - No request screen
  - No sorting (the mod sorts from level 3)
  - No limit on couriers per warehouse level
  - Builder chest is emptied to the warehouse while no build is running
- [~] Miner
  - Walks to the ladder, then teleports along it to the level it's going to (our pathing can't climb)
  - Water and lava inside a tunnel's own area are removed, not walled off
  - No lucky extra ore
  - Picks the nearest tunnel junction, not a random one
  - No fill block or max depth settings
  - No ladder repair
- [ ] Quarrier (digs out simple and medium quarries)
- [ ] Fisherman
- [ ] Composter
- [ ] Plantation
- [ ] Florist
- [ ] Beekeeper
- [ ] Animal herders: chicken, cow (cowboy), sheep (shepherd), pig (swine herder), rabbit
- [ ] Stable
- [ ] Crafters (need recipes taught, as in the mod's request system): sawmill, stonemason, stone smeltery, smeltery, blacksmith, fletcher, dyer, glassblower, concrete mixer, crusher, sifter, mechanic, alchemist, enchanter, baker
- [ ] Nether worker
- [ ] Guards: guard tower, barracks and barracks tower, archery, combat academy, gate house
  - Guard types: knight, ranger, druid, cavalry, huscarl, marksman (not checked which ones research unlocks)
  - Patrol and follow modes, guard tasks, guard gear by building level, training at the archery and combat academy
  - Miner guard assignment (guards that protect the mine)
- [ ] Mystical site
