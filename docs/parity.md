# MineColonies parity checklist

What MineCP has compared with MineColonies (`version/main`), and what is left. `[x]` done, `[~]` partly done (gaps listed under it), `[ ]` not started, ~~crossed out~~ skipped. Add notes under any item.

## Core colony

- [~] Town Hall: founds the colony, levels gate other huts' levels, citizen list
  - Breaking the town hall disbands the colony, so only its owner may (ours: the mod lets BREAK_HUTS decide)
  - No colony events or statistics (advancements are listed below)
- [~] Chunk claims (`core/util/ChunkDataHelper.java`, each building's `getClaimRadius`): the claimed chunks are the colony's border. Numbers are the mod's server config defaults, set in `config.yml` (`claims`)
  - Founding claims every chunk within 4 of the town hall's chunk (`initialColonySize` 4)
  - A new town hall must be at least 128 blocks (`max(minColonyDistance 8, initialColonySize 4)` chunks) from every other town hall, and none of its first chunks may be claimed already (the mod's `isFarEnoughFromColonies`)
  - No claim reaches more than 20 chunks from the town hall's chunk (`maxColonySize` 20)
  - Each hut claims a square around its own chunk by its level, plus every chunk its built building covers; a removed hut gives them back. Radius: most huts 0 at level 0, 1 at levels 1-3, 2 at levels 4-5; town hall 0, 1, 1, 2, 3, 5; fields none
  - Any hut but a town hall goes inside a colony's border, its whole building too (checked for its level 1 building), and joins the colony that owns that ground, so an officer can place huts for someone else's colony (the mod's `SurvivalHandler` and `AbstractBlockHut.canPaste`)
  - Claims are worked out from the huts, not stored. Where two colonies' buildings claim the same chunk, the older colony keeps it (the mod: whichever claimed it first)
  - Build Goggles (the mod's recipe; a dyed leather helmet with a tag stands in for its model): while worn, colony borders within 4 chunks stand as see-through glass panes 40 blocks tall around you, lime for colonies you belong to and red for others, only for you; they follow as you walk (the mod draws the border with its own client renderer)
  - Not done: the goggles' other uses in the mod (showing buildings' outlines)
  - Guard tower, barracks and gate house radii wait for those buildings (guard tower 2, 3, 3, 4, 5; barracks 3, or 2 while a tower is below level 4; gate house 1, 1, 2)
  - Not done: keeping the colony's chunks loaded (`forceloadcolony`); claiming chunks with commands
- [~] Permissions (`core/colony/permissions/Permissions.java`, `ColonyPermissionEventHandler`): the mod's five ranks (owner, officer, friend, neutral for everyone else, hostile) with its default actions; each colony can change what each rank may do. The owner edits members and ranks from the town hall menu ("Permissions") or with `/colony member add|remove` and `/colony members`
  - Inside the border, other players' block breaking and placing, lighting fires, using doors and gates, containers and other blocks, buckets, dropping and picking up items, throwing potions, shooting arrows, using and attacking entities (hostile mobs excepted) and breaking item frames follow their rank; hut menus need ACCESS_HUTS, their buttons MANAGE_HUTS, placing huts PLACE_HUTS (build tool: MANAGE_HUTS), breaking them BREAK_HUTS; graves follow OPEN_CONTAINER and BREAK_BLOCKS wherever they are
  - Attacking a citizen while standing in the colony needs ATTACK_CITIZEN, and a player's hit of more than 1 needs HURT_CITIZEN (by default only hostile has it, so even the owner deals 1), as in the mod
  - Colony messages go to every online member with RECEIVE_MESSAGES (owner and officers by default)
  - A server operator in creative mode gets the mod's OP actions on top of their rank (the mod: permission level 2; ours: `minecp.admin`)
  - Explosions inside the border: `config.yml` `protection.explosions`, the mod's four choices; defaults to vanilla (everything is damaged) rather than the mod's "entities"; hut blocks and graves are never damaged
  - Only the five built-in ranks: no custom ranks yet
  - Players are added by name only if they have played on the server (the mod: its profile cache)
  - Not done: the scan tool, guard rally, map, visitor, teleport and explosion actions (their features don't exist yet), free blocks and positions (the mod's lists of blocks anyone may use), PvP mode, the "abandoned colony" rules, denial messages to the owner
- [ ] Supply camp and supply ship starts
- [ ] Colony flag and banner
- [ ] Colony connections (`core/colony/managers/ColonyConnectionManager.java`, `api/colony/connections/`): colony signs and gate houses link two colonies, with a diplomacy status of allies, neutral or hostile
- [ ] Commands: the mod's `/mc colony ...` and `/mc citizens ...` (MineCP's `/colony` has give-item, `build`, `info` and `movement` subcommands)
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

## Citizens

- [~] Citizens: names, skins, wandering, homes, jobs
  - No citizen skills or levels (in the mod they change work speed and quality)
  - No citizen inventory; workers use a simple carried list
  - No chat interactions or requests above citizens' heads; reasons are shown in the hut menus instead
  - Movement: our own route search (`Route`, from the mod's pathfinding rules and costs: ladders up and down, drops up to 10 blocks at dy³, jumps +2, doors and fence gates +2 (opened on the way and closed behind), path blocks cheaper, wading in shallow water, the mod's node limit by distance), followed by steering the legs mob directly; ladders are climbed and slid with vanilla physics; when stuck it searches again, then teleports 5 steps ahead (the mod's order); `/colony movement` lists each citizen's teleports, stuck events and search times
    - The search runs on the main thread (like vanilla's), not on a worker thread as in the mod: a warm 70-block detour takes ~7 ms, short trips ~1 ms
    - No swimming or diving (citizens wade only), no rails, no vines unless tagged climbable
    - Heuristic weight fixed at 2 (the mod starts at 2 and tunes it per citizen); path blocks cost 1/2 (the mod: 1/6)
    - Teleports near the goal when nothing reaches it (the mod waits at least 2 minutes first)
    - Moves diagonally on level ground, at √2 the cost of a straight step; a step through water costs 4 (not checked against the mod)
- [x] House (residence)
- [~] Colony growth: 4 citizens to start (the mod's default); then a citizen every 2 minutes while there is housing
  - Ours: the mod spawns only the starting citizens on a timer; later ones come from the tavern's visitors and from children
- [x] Tool tiers limited by the worker's own hut level (`IBuilding.getMaxEquipmentLevel`, `ItemStackUtils.verifyEquipmentLevel`): at most stone up to hut level 1, then the hut's level (iron 2, diamond 3, netherite 4), anything at level 5; a tool's tier is wood/gold 0, stone 1, iron 2, diamond 3, netherite 4, plus its highest enchantment level minus 1. Better tools stay in the chest (and couriers may take them away); requests say the best tier allowed; of the tools that will do, the lowest tier is taken, as in the mod
  - Copper tools (newer than the mod) count as stone
  - The same rule covers every worker's tools and the fisherman's rod; not tied to research or the builder
- [~] Hunger and food (saturation numbers from the mod)
  - Work drain assumes the worker is always busy; the mod counts actions
  - Saturation only drops in daytime and not while asleep, as in the mod (`EntityCitizen.decreaseIdleSaturation`)
  - No food quality tied to house level
  - No food variety (diet diversity)
  - Saturation sets how fast health comes back (see Health)
- [~] Day/night routine (`CitizenAI`, `EntityAISleep`, `CitizenSleepHandler`): from day time 10600 citizens leave for home when the walk (6 ticks a block, height counted 1.5x, the miner's climb added) would get them there by nightfall at 12600; they sleep in a house bed and get up at dawn; a colony day counter goes up each morning; in rain only the cook and level 5 huts' workers work
  - Beds are found near the house's hut block (within 8 blocks), not from the building's registered beds
  - Homeless citizens wait within 16 blocks of the town hall (the mod sends them to a tavern if there is one)
  - No "all citizens are asleep" message, sleeping particles, off-to-bed sounds, or "home too far" complaint
  - No research that lets citizens work longer or in the rain
  - Sleep isn't checked by health, sickness, raids or mourning yet (those come first in the mod)
- [~] Health and death (`EntityCitizen` hurt/die/checkHeal, `GraveManager`, `TileEntityGrave`): 20 health; a hit takes at most 20% (4), so it takes several to kill; stuck in a wall it is moved out instead; a player's hit of more than 1 does nothing without the HURT_CITIZEN permission (the mod's `EntityCitizen.checkIfValidDamageSource`; see Permissions); void and `/kill` take all its health, past the cap; hurt by a mob or player it runs 15 blocks away from it; hurt by anything else but a fall (a block, drowning, starving) it moves 5 blocks from where it stands, as in the mod (`EntityCitizen.performMoveAway`: `walkAwayFrom(blockPosition(), 5)`, skipped for falls); a hurt sleeper wakes, as vanilla does, then goes back to bed; it heals every 5 seconds unless a mob or player hurt it in the last 5: 2 when full, 1 normally, saturation/20/2 below 6. On death the colony's members are told where and why, and a grave is put on the nearest free ground within 10 blocks with everything it carried (none in lava: items drop); the grave decays after 10 minutes and is gone 10 minutes later, dropping its items; the timer only runs while its chunk is loaded (the mod's grave ticks as a block entity)
  - Hut blocks and graves survive explosions
  - Breaking the Town Hall drops what every worker carried and what every grave holds
  - The hidden legs mob takes the hits (mobs that hunt villagers, like zombies, go for it); the hits themselves are cancelled
  - No armour, sickness cap on healing, research, happiness or mourning effects, or calling guards for help (no guards yet)
  - Health shows in the town hall's citizen list
- [ ] Graveyard and undertaker
- [ ] Happiness
- [ ] Sickness and the hospital
- [ ] Mourning
- [ ] Children and growing up (the school and library are under Education and research)
- [ ] Visitors and the tavern
- [ ] Quests

## Logistics

- [~] Requests: huts list what they want (`Job.wants`) and couriers fill it from the warehouse
  - No request objects with a state (open, in progress, done, cancelled)
  - Players can't see or fill requests (no request screen, no clipboard)
  - Crafters can't fill requests by crafting; no request priorities
- [~] Warehouse and courier
  - No request screen
  - No sorting (the mod sorts from level 3)
  - No limit on couriers per warehouse level
  - A courier carries 1, 2, 4 or 8 stacks at hut levels 1 to 4, and any amount at level 5 (the mod's 2^(level-1), `EntityAIWorkDeliveryman`)
  - No minimum stock setting (`MinimumStockModule`: items a hut keeps topped up)
  - Builder chest is emptied to the warehouse while no build is running
- [~] Storage rack: own item, recipe, required by the builder, warehouse counts only racks
  - Breaks into a plain chest when blown up
  - No custom model yet (the block is tagged for one)
- [ ] Hut settings (`SettingsModule`, `core/colony/buildings/modules/settings/`): hiring mode and each hut's own settings

## Builder and construction

- [~] Builder: upgrades huts from converted blueprints, uses tools, materials and storage racks
  - Not checked: does the mod only let a builder build huts up to its own level?
  - No decoration or free builds from a build tool; `/colony build` (admins only: it builds anywhere, past protection plugins) is the stand-in
  - A chest or other container in the way is emptied into the hut chest before it is cleared (the miner spills it where it stood)
  - No removal, repair or move work orders
  - Hut rotation comes from the build tool (for hut blocks placed by hand: the block's facing)
- [~] Blueprints: medieval oak converted to vanilla structures (`tools/convert_blueprints.py`)
  - Other styles not converted
  - Modded blocks approximated (see `docs/structures/medievaloak.md`)
- [~] Build tool (Structurize `ItemBuildTool`, `AbstractBlueprintManipulationWindow`; MineColonies `SurvivalHandler`): the Build Tool item is the tool (a stick with a tag, made with `/colony buildtool`). Right-click a block, pick a hut you carry; a preview of its level 1 building stays until confirmed or cancelled. Right-click again for the mod's controls in a dialog: move forward/back/left/right from where you face, up/down, rotate left/right, mirror (front to back, as the mod), confirm, cancel. Confirming checks the same rules as placing by hand, uses up the hut item, places the hut block and keeps the turn and mirror for every level
  - Preview is block displays at 90% size, only for the placing player (the mod draws see-through blocks); blocks walled in on all sides are skipped
  - The dialog covers the screen, so each button closes it to show the change
  - Picks huts only: no style packs or categories to browse, no decorations (`/colony build` stays the stand-in), only the colony's style
  - No ground-level offset: the hut block goes where you click (the converter drops the blueprints' ground tags)
  - The building must fit inside the colony's border (see Chunk claims)
  - The block at the hut spot is broken as a player break (protection plugins can refuse it); graves and blocks holding items are refused
- [ ] Scan tool
- [~] Hut recipes: MineColonies' shapes, with our Build Tool in place of the mod's
  - Only huts that are built so far have recipes

## Resource jobs

- [~] Lumberjack
  - Not checked against the mod: restricted area, which trees, mushroom and nether trees, stripping
- [~] Farmer and fields
  - Done: one action per field per colony day; each field cycles till, plant, harvest; a stage with nothing to do is skipped, and after 4 empty stages the field is done for the day (as in the mod)
  - One field per Farmer's Hut level; the farmer waits max(4, 16 - 2 x level) ticks between actions (ours: the mod's delay depends on the worker's skill, which we don't have)
  - Sugar cane and others are the plantation's, not done
  - No compost or fertiliser
- [~] Miner
  - Climbs and slides down the shaft's ladder like any citizen route (vanilla ladder physics, about 4 blocks a second up)
  - Water and lava inside a tunnel's own area are removed, not walled off
  - No lucky extra ore
  - Picks the nearest tunnel junction, not a random one
  - No fill block or max depth settings
  - No ladder repair
  - Asks for whatever tool a block calls for, as the mod's workers do: an axe where a tunnel piece replaces a level's wooden posts (the mod's pieces share an edge row: a 9x9 level and 7x7 tunnels, 7 apart)
  - Waits max(4, 14 - 2 x level) ticks between blocks (ours: the mod's delay depends on the worker's skill)
  - Goes back to deliver once its loot (not the supplies it keeps for the work) reaches 256
- [ ] Quarrier (digs out simple and medium quarries)
- [ ] Fisherman
- [ ] Composter
- [ ] Plantation
- [ ] Florist
- [ ] Beekeeper
- [ ] Animal herders: chicken, cow (cowboy), sheep (shepherd), pig (swine herder), rabbit
- [ ] Stable
- [ ] Nether worker
  - Expedition log (`ExpeditionLogModule`, unlocked by research)

## Crafter jobs

- [~] Cook (restaurant)
  - No restaurant menu setting (which foods it serves)
  - Doesn't feed players
  - No Kitchen (cook assistant)
- [ ] Crafters (need recipes taught, as in the mod's request system): sawmill, stonemason, stone smeltery, smeltery, blacksmith, fletcher, dyer, glassblower, concrete mixer, crusher, sifter, mechanic, alchemist, enchanter, baker

## Education and research

- [ ] School and library
- [ ] Research (university)

## Military

- [ ] Guards: guard tower, barracks and barracks tower, archery, combat academy, gate house
  - Guard types: knight, ranger, druid, cavalry, huscarl, marksman (not checked which ones research unlocks)
  - Patrol and follow modes, guard tasks, guard gear by building level, training at the archery and combat academy
  - Miner guard assignment (guards that protect the mine)
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
  - Can't be ported cleanly: custom raider models and textures. Decided stand-in: plain vanilla mobs (not the Mannequin and hidden mob pair citizens use), dressed in culture gear and picked to match the biome. The gear is leather armour with custom attributes and a culture texture from the resource pack (the `equippable` component's `asset_id`); it can drop as loot, as raider gear does in the mod
  - Mob for each raider (proposed, not tried in game yet):
    - Barbarians: melee zombie, archer skeleton, chief vindicator
    - Egyptians: mummy husk, archer mummy skeleton, pharaoh husk
    - Amazons: spearman zombie with a vanilla spear, archer skeleton, chief vindicator
    - Norsemen: melee vindicator, archer stray, chief vindicator
    - Pirates: melee zombie, archer skeleton, captain vindicator
    - Drowned pirates: drowned in every role (tridents for ranged), the captain in captain gear
- [ ] PvP: players attacking a colony, with the guards they bring (`core/colony/pvp/AttackingPlayer.java`)

## Misc buildings

- [ ] Mystical site

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
- [ ] Decoration controller (`core/blocks/BlockDecorationController.java`: marks a placed decoration so builders can upgrade or repair it)
- [ ] Colony sign
- [ ] Colony banner and wall banner (the colony flag): stand-ins are normal vanilla banners and wall banners
- [~] Construction tape (marks a build site): no block; four thin yellow block-display bars along the footprint at the hut block's floor, while the hut is unbuilt or has an upgrade queued or under way (the build tool's preview shows them too). They are straight at one height, where the mod's tape follows the ground
- ~~Decoration placeholder (blueprint filler)~~ (skipped)
- [~] Grave and named grave (graveyard): stand-ins are a stone brick wall (mossy once decayed), and a stone brick wall with a sign for the named grave. The grave opens like a chest; breaking it drops what is in it. Named graves come with the graveyard
- ~~Iron gate and wooden gate (large gates)~~ (skipped)

### Items

- [~] Build tool (Structurize): the Build Tool (see Build tool above); `/colony build` for free builds
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
