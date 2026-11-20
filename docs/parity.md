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
  - Any hut but a town hall goes inside a colony's border, its whole building too (checked for its level 1 building; an upgrade is refused if its next level would reach into another colony's chunks, while unclaimed land is fine), and joins the colony that owns that ground, so an officer can place huts for someone else's colony (the mod's `SurvivalHandler` and `AbstractBlockHut.canPaste`)
  - Claims are worked out from the huts, not stored. As in the mod, a chunk stays with the colony that claimed it first, and a colony's first chunks are never given up. After a restart, though, chunks two colonies' buildings both claim go to the older colony (we don't save who claimed first)
  - Build Goggles (the mod's recipe; a dyed leather helmet with a tag stands in for its model): while worn, colony borders within 4 chunks stand as see-through glass panes 40 blocks tall around you, lime for colonies you belong to and red for others, only for you; they follow as you walk (the mod draws the border with its own client renderer)
  - Not done: the goggles' other uses in the mod (showing buildings' outlines)
  - Guard tower, barracks and gate house radii wait for those buildings (guard tower 2, 3, 3, 4, 5; barracks 3, or 2 while a tower is below level 4; gate house 1, 1, 2)
  - Not done: keeping the colony's chunks loaded (`forceloadcolony`); claiming chunks with commands
- [~] Permissions (`core/colony/permissions/Permissions.java`, `ColonyPermissionEventHandler`): the mod's five ranks (owner, officer, friend, neutral for everyone else, hostile) with its default actions; each colony can change what each rank may do. The owner edits members and ranks from the town hall menu ("Permissions") or with `/colony member add|remove` and `/colony members`
  - Inside the border, other players' block breaking and placing, lighting fires, using doors and gates, containers and other blocks, buckets, dropping and picking up items, throwing potions, shooting arrows, using and attacking entities (hostile mobs excepted) and breaking item frames and armor stands follow their rank (either hand); hut menus need ACCESS_HUTS, their buttons MANAGE_HUTS, placing huts PLACE_HUTS (build tool: MANAGE_HUTS), breaking them BREAK_HUTS; graves follow OPEN_CONTAINER and BREAK_BLOCKS wherever they are
  - Pistons, flowing water and lava, and fire (spreading, lighting or burning) can't cross from one owner's land into another colony's chunks, so nobody can push, pull, flood or burn in from just outside the border (ours: the mod relies on its event handler for players only)
  - Attacking a citizen while standing in the colony needs ATTACK_CITIZEN, and so does attacking one that stands in the colony from outside it (ours: the mod judges only where the attacker stands, so a neutral player one block outside can hit citizens); and a player's hit of more than 1 needs HURT_CITIZEN (by default only hostile has it, so even the owner deals 1), as in the mod
  - Colony messages go to every online member with RECEIVE_MESSAGES (owner and officers by default)
  - A server operator in creative mode gets the mod's OP actions on top of their rank (the mod: permission level 2; ours: `minecp.admin`)
  - Explosions inside the border: `config.yml` `protection.explosions`, the mod's four choices; defaults to vanilla (everything is damaged) rather than the mod's "entities"; hut blocks and graves are never damaged
  - Neutral can never be given EDIT_PERMISSIONS, and a rank can't take EDIT_PERMISSIONS away from itself, as in the mod
  - `/colony members` shows the member list only to members (and admins)
  - Only the five built-in ranks: no custom ranks yet
  - Players are added by name only if they have played on the server (the mod: its profile cache)
  - Not done: the scan tool, guard rally, map, visitor, teleport and explosion actions (their features don't exist yet), free blocks and positions (the mod's lists of blocks anyone may use), PvP mode, the "abandoned colony" rules, denial messages to the owner
- [ ] Supply camp and supply ship starts
- [ ] Colony flag and banner
- [ ] Colony connections (`core/colony/managers/ColonyConnectionManager.java`, `api/colony/connections/`): colony signs and gate houses link two colonies, with a diplomacy status of allies, neutral or hostile
- [ ] Commands: the mod's `/mc colony ...` and `/mc citizens ...` (MineCP's `/colony` has give-item, `build`, `info` and `movement` subcommands)
- [~] Advancements (the mod's `data/minecolonies/advancements/`): its MineColonies and production tabs, with the mod's tree, frames, triggers and vanilla icons (our own icons where the mod shows its hut blocks), in our own words. Loaded by the plugin at startup (`Advancements`), so no data pack is needed; a `/minecraft:reload` drops them until the next restart
  - Each is awarded when it happens to every online player the colony sends messages to (RECEIVE_MESSAGES), as the mod does: placing the town hall (and the tab's root, which in the mod is placing a supply camp), asking for the Builder's Hut, finishing huts and levels, 5/10/25/50/75 citizens, a citizen's first meal, teaching torches or a wooden door
  - Left out until their features exist: the military tab, tavern, mystical site, graves and resurrection, the undertaker's totem, rotten flesh, the hut guide, fulfilling a request from the request screen, baker, composter, crusher, enchanter, fisherman, florist, library, sifter, herders, post box and stash, deep mine, max fields, crafting the supply camp
  - "Room to Grow" (a house) hangs under the Builder's Hut (the mod: under the tavern)

## Citizens

- [~] Citizens: names, skins, wandering, homes, jobs
  - A hut takes a worker once level 1 is built, as in the mod (`IBuilding.canAssignCitizens`); only the Builder's Hut hires at level 0, so its builder can build it
  - No citizen skills or levels (in the mod they change work speed and quality)
  - No citizen inventory; workers use a simple carried list
  - No chat interactions or requests above citizens' heads; reasons are shown in the hut menus instead
  - Movement: our own route search (`Route`, from the mod's pathfinding rules and costs: ladders up and down, drops up to 10 blocks at dy³, jumps +2, doors and fence gates +2 (opened on the way and closed behind), path blocks cheaper, wading in shallow water, the mod's node limit by distance), followed by steering the legs mob directly; ladders are climbed and slid with vanilla physics; when stuck (3 seconds without getting half a block further across the ground; hopping in place doesn't count) it searches again, then teleports 5 steps ahead (the mod's order); doors (as they are when open) and open trapdoors are thin panels on one side of their block, read from their collision shape: routes pass along a panel, never step, jump or cut a corner across one (ours: the mod's pathfinding treats them as passable or not); `/colony movement` lists each citizen's teleports, stuck events and search times
    - The search runs on the main thread (like vanilla's), not on a worker thread as in the mod: a warm 70-block detour takes ~7 ms, short trips ~1 ms
    - No swimming or diving (citizens wade only), no rails, no vines unless tagged climbable
    - Heuristic weight fixed at 2 (the mod starts at 2 and tunes it per citizen); path blocks cost 1/2 (the mod: 1/6)
    - Teleports near the goal when nothing reaches it (the mod waits at least 2 minutes first)
    - Moves diagonally on level ground, at √2 the cost of a straight step; a step through water costs 4 (not checked against the mod)
- [x] House (residence)
- [~] Colony growth: a new colony starts empty; its first 4 citizens (the mod's `initialCitizenAmount`) move in one at a time at the town hall on the mod's timer (`CitizenManager.onColonyTick`: checked every 500 ticks, the countdown drops by 500 plus 60 per town hall level, 30 s before the first and 60 s after each; about 50 s for the first, then about 75 s apart), half and half female and male, and are replaced the same way if they die; then a citizen every 2 minutes while there is housing
  - No Town Hall "move in" setting to turn this off
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
- [~] Health and death (`EntityCitizen` hurt/die/checkHeal, `GraveManager`, `TileEntityGrave`): 20 health; a hit takes at most 20% (4), so it takes several to kill; stuck in a wall it is moved out instead; a player's hit of more than 1 does nothing without the HURT_CITIZEN permission (the mod's `EntityCitizen.checkIfValidDamageSource`; see Permissions); void and `/kill` take all its health, past the cap; hurt by a mob or player it runs 15 blocks away from it; hurt by anything else but a fall (a block, drowning, starving) it moves 5 blocks from where it stands, as in the mod (`EntityCitizen.performMoveAway`: `walkAwayFrom(blockPosition(), 5)`, skipped for falls); a hurt sleeper wakes, as vanilla does, then goes back to bed; it heals every 5 seconds unless a mob or player hurt it in the last 5: 2 when full, 1 normally, saturation/20/2 below 6. On death the colony's members who get colony messages (RECEIVE_MESSAGES) are told where and why, and a grave is put on the nearest free ground within 10 blocks with everything it carried (none in lava: items drop); the grave decays after 10 minutes and is gone 10 minutes later, dropping its items; the timer only runs while its chunk is loaded (the mod's grave ticks as a block entity)
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

- [~] Requests (the mod's request system, `api/colony/requestsystem`): what each hut wants (`Job.wants`) becomes a request, worked out again each second. As the mod's resolvers: the warehouse takes a request once it holds at least the request's minimum, and a courier brings what it has; if that isn't all of it, or the warehouse has less than the minimum, a crafter that knows a recipe makes the rest (the mod's child request for the remainder); else it waits for a player. Minimums as the mod's requesters: the builder's and miner's materials, seeds, food and fuel 1; a crafter's ingredients the whole amount; tools 1. Couriers fetch only the warehouse's part of each request, so the warehouse never also sends what a crafter is making. A crafter keeps a request it took until its share is made and handed over, even if the warehouse fills up meanwhile, and across restarts (requests a crafter holds are saved). No crafter takes a request whose recipe uses up something a crafter further up the chain is making (the mod's crafting-cycle check). What couriers are carrying to a hut counts toward its requests, so nothing is sent twice. A crafter keeps the ingredients its requests need; extra ingredients go to the warehouse
  - Four states shown to players (in the warehouse, being crafted at a hut, on its way, waiting for a player) instead of the mod's thirteen (created, assigned, in progress, completed, cancelled...)
  - The Town Hall's "Requests" button lists every open request; each hut's menu has its own "Requests" button (a list of its own: the builder can wait for dozens of things). Players fill one by putting the items in that hut's chest (the mod: its request screen, and the clipboard, which comes later)
  - What a crafter makes waits in its chest until a courier takes it straight to the hut that asked (the mod's delivery requests); couriers do this first, and without a warehouse too (a load that doesn't fit waits until the hut has room). What couriers are carrying counts toward a request, so crafters stop once enough is made or on its way
  - The cook's raw food request (ours: the mod's cook gets what it cooks through smelting requests) uses a minimum of 1
  - No request priorities, no cancelling or overruling a request, no "follow-up" requests
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
  - Where it works from, as the mod's builder (`walkToConstructionSite`): in place when the block is within 5 blocks across (height doesn't count); else the nearest spot a path reaches within 4 of it (straight-line; the mod counts dx+dy+dz), inside the build or out; where none gets that close, from as close as it got (within 12). Only from farther off does it head for the ground outside the build
  - A chest or other container in the way is emptied into the hut chest before it is cleared (the miner spills it where it stood)
  - Removal work order: "Remove building" in a built hut's menu (MANAGE_HUTS; not the Town Hall or Builder's Hut, which are removed by breaking them) queues taking it down; the builder clears every block still matching the hut's current level (top down, drops into its hut chest, foundation filler left), then the hut is removed and its item and chest contents go to the warehouse, else the Builder's Hut chest (ours: the mod's builder carries them; only what fits in neither drops, at the Builder's Hut, since nobody may be there when it finishes). It uses the colony's current style, so a building from a style since changed mostly stays
  - Water and lava in a blueprint cost a water or lava bucket each, and the empty bucket comes back (as Structurize's item for a fluid block, used "bucket-aware" by the mod's builder); the Sawmill's water wheel channel needs about 126
  - No repair or move work orders
  - Hut rotation comes from the build tool (for hut blocks placed by hand: the block's facing)
- [~] Blueprints: medieval oak, spruce, birch and dark oak, and cavern, converted to vanilla structures (`tools/convert_blueprints.py`, one data pack each: `run/world/datapacks/minecp-<style>`; reports in `docs/structures/`)
  - The colony's style is picked in the Town Hall menu ("Style", MANAGE_HUTS) from the installed packs; new buildings and upgrades use it. The mod picks it with the build tool when placing the town hall, and changes it in the town hall's settings
  - Cavern keeps each hut in "default", "alternative" and "megahall" folders: huts use "default" (no choosing between them); it has no mine shafts, so its miners dig the medieval oak ones
  - The other 18 styles aren't converted
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
- [~] Crafters (`AbstractEntityAICrafting`, `AbstractCraftingBuildingModule`): taught crafting-table recipes of their kind, up to 2^level x 5 (the mod's limit with its extra-recipe multiplier of 5; no research). They take the requests they have a recipe for, ask for the ingredients like any hut, and craft one batch in 3 hits, 10 ticks apart (the mod's HIT_DELAY; its hits per craft fall from 30 with no skill to 3 for a typical citizen, and we have no skills)
  - Teaching: "Teach a recipe" in the crafter's hut menu opens a crafting table window; lay the recipe out (with real items) and click the result to teach it; nothing is used up. "Recipes" lists them, each with a Forget button (the mod's teaching screen takes ghost items)
  - Smelters learn furnace recipes from a list in the hut menu (the mod: a furnace teaching screen) and smelt them in the furnaces of their building (inside its outline, up to 4 blocks above or below the hut block, so neighbouring huts never share furnaces; the Cook's furnaces and smokers likewise), with fuel from their chest; they ask for fuel (minimum 1) whenever they have smelting to do and less than 8 (ours: 16 on hand)
  - Which recipes each crafter takes follows the mod's crafter tags and rule (`CraftingUtils.isRecipeCompatibleBasedOnTags`): an excluded product refuses, a listed product allows, then any excluded ingredient refuses and any listed ingredient allows. The tags are written out in `CraftRules` as vanilla items; Forge tags become the vanilla items in them, and Domum Ornamentum items are left out
  - A crafter never takes its own hut's request (ours: a smelter would otherwise take its own fuel request)
  - No brewing or the mod's special crafting types yet; no recipe settings (priority, max stock), no disabling a recipe, no pre-taught recipes from data packs; 2x2 and 3x3 recipes both allowed at every level
- [~] Sawmill (`BuildingSawmill`, job "Carpenter"): its tags first (planks, wooden slabs and stairs, boats; logs and cactus in; nothing the Mechanic makes, and nothing with ingots, stone, redstone or string), then the mod's ratio: wood ingredients (planks, logs, and items with "wood" in a tag: sticks, wooden slabs and so on) over every ingredient that isn't planks or logs, above 0.75; hut recipe as the mod's (three wooden axes across the middle)
  - MineCP's own recipes (huts, racks, goggles) can't be taught to any crafter
- [~] Stonemason (`BuildingStonemason`): its tags only: stone, cobblestone, sandstone, bricks and their stairs, slabs and walls (also the furnace, from cobblestone, as in the mod); hut recipe as the mod's (three stone bricks across the middle)
- [~] Stone Smeltery (`BuildingStoneSmeltery`, job "Stone Smelter"): furnace recipes by its tags: stone, smooth stone, bricks, terracotta, cracked stone bricks, charcoal and so on. Glass is the mod's Glassblower's, not here; hut recipe as the mod's (stone bricks, furnace, stone bricks)
- [~] Blacksmith (`BuildingBlacksmith`): nothing with leather; any tool, weapon or armor; else its tags (ingots, nuggets, shears, iron bars and so on); hut recipe as the mod's (three iron ingots)
- [~] Smeltery (`BuildingSmeltery`, job "Smelter"): knows every ore and raw-metal furnace recipe without teaching, asks for any ore (minimum 1; ours: 32 at a time, once below 16) and smelts what it is brought, as well as the ingots huts ask for; ingots it wasn't asked for go to the warehouse. No ore breaking (the mod's extra nuggets), no "warehouse minimum" setting; hut recipe as the mod's (iron ingot)
- [~] Mechanic (`BuildingMechanic`): its tags (torches, lanterns, rails, buttons, pressure plates, pistons, storage blocks and so on), else minecarts and hoppers; hut recipe as the mod's (redstone block)
  - Torches are the Mechanic's in the mod (not the Sawmill's), and the core huts' levels 1-3 use about 270 of them
- [ ] Other crafters: fletcher, dyer, glassblower, concrete mixer, crusher, sifter, alchemist, enchanter, baker

## Education and research

- [ ] School and library
- [ ] Research (university)

## Military

- [~] Guard Tower (`BuildingGuardTower`, one knight: `JobKnight`, `EntityAIMelee`, `MeleeCombatAI`, `AbstractEntityAIGuard`); hut recipe as the mod's (a bow); claims 2, 3, 3, 4, 5 chunks by level
  - The knight patrols between the colony's built huts at random (the mod's automatic patrol), waiting 5 s at each, and attacks the hostile mobs it sees within 15 + 3 per tower level blocks (3 up or down), chasing them up to 80 blocks from the tower; it fights back when hurt instead of fleeing
  - Hits every 32 ticks (`KNIGHT_ATTACK_DELAY_BASE`; no skills, so never faster) within 2 blocks, for the sword's damage plus 3 (`BASE_PHYSICAL_DAMAGE`); the sword wears down
  - Health 20 + 15 (knight) + 20 (single-guard tower) + 2 per tower level, as the mod's modifiers
  - Asks for a sword (tier limited by tower level, like every worker's tools) and each armor piece it lacks; armor by tower level as the mod's gear lists: leather to gold at level 1, to chainmail at 2, to iron at 3, chainmail to diamond at 4, iron to netherite at 5. Armor shows on the citizen and cuts damage by vanilla's formula
  - Stays on duty at night (the mod's guards take short naps instead) and in rain
  - Not done: patrol modes and manual patrol points, guard/follow tasks, the rally banner, retreating to heal at low health, shields (a research in the mod), sweep attacks and knockback, experience
- [ ] Other guards: barracks and barracks tower, archery, combat academy, gate house
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

- [~] Optional resource pack, each player's choice: MineCP's items always carry a `custom_model_data` string (`minecp:hut/<type>`, `minecp:rack`, `minecp:build_goggles`, `minecp:build_tool`) beside their names, lore and tags, which shows nothing without the pack. `tools/build_resourcepack.py` builds a private pack from your own MineColonies (and Structurize) jars that overrides the vanilla chest, bell, hay bale, stick and leather helmet definitions to show the mod's models for those strings (vanilla for everything else). The server doesn't know or care who has it
  - Placed huts and storage racks stay vanilla chests, named for what they are ("Builder's Hut", "Storage Rack"): their inventory title for everyone. The plugin sends that name to clients (a vanilla server never does) so the pack's OptiFine / Entity Model Features chest models (`optifine/cem/chest<n>.jem`, chosen by name in `chest.properties`, converted from the mod's block models) can show the mod's hut and rack; vanilla clients see a plain chest. The Town Hall (a bell) and fields (hay bales) have no such model
  - The converted models drop element rotations (a few tilted decorations) and face texture rotations; double racks use the vanilla model

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
