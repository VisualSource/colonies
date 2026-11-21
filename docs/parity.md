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
  - Not done: the scan tool, guard rally, map, teleport and explosion actions (their features don't exist yet), free blocks and positions (the mod's lists of blocks anyone may use), PvP mode, the "abandoned colony" rules, denial messages to the owner
- [x] Supply camp and supply ship starts (`ItemSupplyCampDeployer`, `ItemSupplyChestDeployer`, `SuppliesHandler`), the mod's numbers unless noted: the Supply Camp (a chest; the mod's recipe from 5 chests: plain or trapped chests, not hut items or racks, which are chests too) and Supply Ship (a boat; from 5 plain boats of any kind, not another Supply Ship) stack to 1. Right-click (main hand; from the off hand it does nothing, so it is never put down as a plain chest or boat): pick a style, then the build tool's preview opens 10 blocks ahead, on the highest block there (the camp on the ground, the ship's keel 3 below the water's surface; the mod starts one block above the clicked block), moved, turned and confirmed the same way. Confirming checks the mod's rules, a third of the footprint allowed to fail: the camp stands on solid ground with nothing on it; the ship's hull is in water for its 3 lowest layers with nothing in its way for 7 more. The levels (1 ground, 3 water) are the mod's defaults, which every style's supply blueprints use, so they aren't read from the blueprints' tag. Overworld only. It is placed at once with everything in it (vanilla's structure placement), the item used up and the placement recorded, once per player; in creative the item is still needed and used up, but earlier placements don't block (all as the mod's). It gives the MineColonies root advancement
  - Ours, stricter: no part may be inside a colony where the player can't place blocks (the mod counts those spots among the third); no chest or other container may be in the way (its items would be lost); the ship's 3 lowest layers must be mostly water (the mod checks only the hull's own blocks there, since the blueprints mark nearly all the rest as fluid, so a ship could sit on dry ground)
  - Its barrel holds the mod's starting kit from the blueprint: a Town Hall and a Build Tool (the converter keeps the mod's two items as tagged stand-ins that the placement swaps for ours), paper, logs, stone, cobblestone, food, potions and stone tools. The cavern camp and ship have no kit in their barrels: the player is given the Town Hall and Build Tool instead (ours)
  - Not done: the mod's story screen before the first placement, the Nether ship, free deployers in vanilla loot chests
- [ ] Colony flag and banner
- [ ] Colony connections (`core/colony/managers/ColonyConnectionManager.java`, `api/colony/connections/`): colony signs and gate houses link two colonies, with a diplomacy status of allies, neutral or hostile
- [ ] Commands: the mod's `/mc colony ...` and `/mc citizens ...` (MineCP's `/colony` has give-item, `build`, `info` and `movement` subcommands)
- [~] Advancements (the mod's `data/minecolonies/advancements/`): its MineColonies and production tabs, with the mod's tree, frames, triggers and vanilla icons (our own icons where the mod shows its hut blocks), in our own words. Loaded by the plugin at startup (`Advancements`), so no data pack is needed; a `/minecraft:reload` drops them until the next restart
  - Each is awarded when it happens to every online player the colony sends messages to (RECEIVE_MESSAGES), as the mod does: placing the town hall (and the tab's root, which in the mod is placing a supply camp), asking for the Builder's Hut, finishing huts and levels, 5/10/25/50/75 citizens, a citizen's first meal, teaching torches or a wooden door
  - Left out until their features exist: the military tab, mystical site, the undertaker's totem, rotten flesh, the hut guide, fulfilling a request from the request screen, composter, crusher, enchanter, florist, library, sifter, post box and stash, deep mine, max fields, crafting the supply camp

## Citizens

- [~] Citizens: names, skins, wandering, homes, jobs
  - Right-clicking a citizen or visitor stops it for 3 seconds, facing the player, with its work paused (the mod's `EntityCitizen.mobInteract`); not while it is on a ladder or running from danger
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
- [~] Colony growth: a new colony starts empty; its first 4 citizens (the mod's `initialCitizenAmount`) move in one at a time at the town hall on the mod's timer (`CitizenManager.onColonyTick`: checked every 500 ticks, the countdown drops by 500 plus 60 per town hall level, 30 s before the first and 60 s after each; about 50 s for the first, then about 75 s apart), half and half female and male, and are replaced the same way if they die. After them the colony grows only as in the mod: by children (below) and by recruiting the taverns' visitors (Visitors and the tavern). Housing is the mod's `getMaxCitizens`: the beds of built houses (1 per level) and taverns (4), at least 1 and at most 25 (the mod's limit before research); the starting citizens move in even without beds
  - No Town Hall "move in" setting to turn this off, no hiring modes, no research raising the 25 limit
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
  - Homeless citizens wait within 16 blocks of the town hall (the mod sends them to a tavern if there is one); a tavern's residents sleep in beds near its hut block, like a house's
  - No "all citizens are asleep" message, sleeping particles, off-to-bed sounds, or "home too far" complaint
  - No research that lets citizens work longer or in the rain
  - Citizens other than guards go home during a raid; sleep isn't checked by health or sickness yet (those come first in the mod); mourners skip work but sleep as usual
- [~] Health and death (`EntityCitizen` hurt/die/checkHeal, `GraveManager`, `TileEntityGrave`): 20 health; a hit takes at most 20% (4), so it takes several to kill; stuck in a wall it is moved out instead; a player's hit of more than 1 does nothing without the HURT_CITIZEN permission (the mod's `EntityCitizen.checkIfValidDamageSource`; see Permissions); void and `/kill` take all its health, past the cap; hurt by a mob or player it runs 15 blocks away from it; hurt by anything else but a fall (a block, drowning, starving) it moves 5 blocks from where it stands, as in the mod (`EntityCitizen.performMoveAway`: `walkAwayFrom(blockPosition(), 5)`, skipped for falls); a hurt sleeper wakes, as vanilla does, then goes back to bed; it heals every 5 seconds unless a mob or player hurt it in the last 5: 2 when full, 1 normally, saturation/20/2 below 6. On death the colony's members who get colony messages (RECEIVE_MESSAGES) are told where and why, and a grave is put on the nearest free ground within 10 blocks with everything it carried (none in lava: items drop); the grave decays after 10 minutes and is gone 10 minutes later, dropping its items; the timer only runs while its chunk is loaded (the mod's grave ticks as a block entity)
  - Hits from mobs, players and arrows knock it back as vanilla would (strength 0.4, away from what hit it; not in bed, on a seat or on a ladder); an arrow that hits it is used up, as one that hits a player. Its body is collidable so arrows can hit it, on a no-collision team so nothing pushes it
  - Hut blocks and graves survive explosions
  - Breaking the Town Hall drops what every worker carried and what every grave holds
  - The hidden legs mob takes the hits (mobs that hunt villagers, like zombies, go for it); the hits themselves are cancelled
  - No armour, sickness cap on healing, research, happiness or mourning effects, or calling guards for help (no guards yet)
  - Health shows in the town hall's citizen list
- [~] Graveyard and undertaker (`BuildingGraveyard`, `EntityAIWorkUndertaker`, `GraveManager`), the mod's numbers unless noted:
  - [x] The Graveyard hut (`mystic/graveyard`, 5 levels, default claims); recipe as the mod's (stone around a bone). Ours: no research needed to unlock it
  - [x] The undertaker digs up the colony's graves one at a time (nearest the hut first; one undertaker per grave; ours: only graves in its world whose chunk is loaded), with a shovel from its hut chest (requested like other tools): empties it (up to 27 stacks a trip; more means a trip to the hut and back), tries to bring the citizen back, else digs the grave up and buries the citizen in a free burial spot of the graveyard (picked at random), then takes what the grave held to its hut. A decayed (mossy) grave is dug up the same as a fresh one; a grave that decays away completely or is broken meanwhile is given up. Called away (night, a meal, rain), it walks back and starts that step again
  - [x] Bringing back: 0.5% per hut level, at most 2.5% (the mod's chance without research, skills, totems or a mystical site). The citizen comes back where the grave was with its name and skin, and its mourners stop mourning. Ours: as a new citizen otherwise (no job, home or happiness kept)
  - [x] Burial spots are the mod's named grave blocks in each level's blueprint: the converter puts a marker entity on each (with its facing) that the plugin reads, turned with the building. No free spot: "no room" and the body is lost, as in the mod. **Re-run `tools/convert_blueprints.py` for the spots to exist** in older converted packs
  - [x] Timings from the mod's effort counts with a skill of 10 (ours: no skills): emptying 2.5 s, bringing back 10 s, burying 10 s
  - [x] Hut menu: graves waiting, spots used, the chance, and who rests there. Advancements "Rest in Peace" (bury) and "Back From the Grave" (bring back), as the mod's citizen_bury and citizen_resurrect
  - Not done: totems of undying (research), sprinting to graves (research), longer grave decay (research), the mod's skills and their experience
- [~] Happiness (`CitizenHappinessHandler`, `HappinessConstants`, the mod's modifiers, weights and thresholds): 0 to 10, ten times the weighted mean of the factors that aren't neutral (1). Factors: school (weight 1; a child 0, as no child is a pupil yet), security (4: guards against everyone else, min(guards / (others × 2/3), 2), both counted from 1), social life (2: the share of citizens without a problem, each jobless adult, homeless or hungry citizen counting once per problem), food (3: from the last 10 foods eaten, kinds and good food against the house level), housing (3: 0 homeless, else house level / 3; × 0.75 after 7 days, × 0.5 after 14), work (2: 0.5 jobless, 2 at a hut above level 3; same 7/14 days), sleep (1.5: unhappy after 2 nights without lying down in a bed; guards never), got hurt (2: 0 for a day; not by fire or lightning), a citizen died (3: 0 for everyone for 3 days, not for a guard's death). Days count at nightfall. Shown in a citizen's own menu (right-click it), and in the Town Hall's Citizens page with the colony's average (5.5 with no citizens) and each factor averaged
  - Ours: the mod's "good food" is its own dishes; ours is any food that is made (cooked or crafted, like bread) rather than picked or caught
  - All factors neutral gives 10 (the mod divides by zero there); the mod's shared "death" modifier, which wears off faster the more citizens there are, is per citizen here
  - Its complaints (after 7 days homeless or jobless: on day 8, as the mod checks more than 7) and demands (after 14) are colony messages, not the mod's chat bubbles; no idle-at-job, sickness, mystical site or quest factors yet ("beat a raid" is in), no happiness research
  - It changes nothing yet: in the mod it only sets new citizens' skill levels and how fast they learn, and MineCP has no skills
- [ ] Sickness and the hospital
- [~] Mourning (`CitizenMournHandler`, `EntityAIMournCitizen`): when a citizen other than a guard dies, those who shared its home (not guards or undertakers) mourn it the whole next day, from one morning to the next (someone who dies while they mourn gets a day of their own after): they don't work and wander near its named grave if the undertaker buried it, else near the town hall. The death message adds "Close family and friends will mourn ... tomorrow", as the mod. Bringing the citizen back ends it
  - Ours: no families are kept, so only housemates mourn; everyone's mourning starts and ends at the same dawn (the mod: at each citizen's wake-up); mourners don't stare at other citizens. The "a citizen died" happiness factor (Happiness) is separate, as in the mod
- [~] Children and growing up (`ReproductionManager`, `EntityAICitizenChild`): each colony tick (500 ticks), with room in the colony, at least 2 citizens, a man and a woman among citizens and visitors, and a house or tavern with a spare bed, a child is born there (the mod's timer works out to every colony tick whenever there is room); a birth is announced only when the newborn is the colony's only child, as in the mod. Children are half size (the scale attribute), take no job, play near home, eat and sleep. Growing up: a shared pool gains 500 a colony tick while there are children, each child takes 2000 at a time; from 4000 a child grows up with a 1 in 71 chance each colony tick, and surely past 70000; then it takes a job
  - No parents, partners or families, no inherited skills (no skills); the school and library are under Education and research
  - The mod's pool also empties at 70000; ours doesn't
- [~] Visitors and the tavern (`TavernBuildingModule`, `EntityAIVisitor`, `RecruitmentInteraction`, `recruitment_items`): the Tavern (the mod's recipe, a barrel in the middle; levels 1 to 3) houses 4 citizens from level 1 and hosts up to 3 visitors per level. A built tavern's first visitor comes after 10000 ticks, the next after rand(3000) + (6000 / level) × citizens / beds. Each asks for an item of a rarity drawn around the middle of what the tavern's level allows (1 to 3, 6 or 9), 5 × (10 − rarity) + 0 to 2 of it, at most a stack. Visitors spend their stay as the mod's do: every 2.5 s an idle one picks 1 in 5 to sit on a free seat of its tavern for 150 to 275 s (2 in 5 at night), 1 in 5 to wander within 10 blocks for 50 to 100 s (15 s at night), else it stands; they leave after 150000 ticks (the mod's saturation running out). Seats are the tavern blueprint's sit tags, which the converter marks; the body rides an invisible display entity on the seat, so it is drawn sitting (the mod's SittingEntity). Right-click one to see what it asks; Recruit takes the items from your main inventory, matched by item alone as in the mod (any enchanted book will do; nothing in creative), and needs room in the colony's housing (the mod's check: citizens below the beds count, not a particular free bed); 3% of the time it runs off with them, as in the mod. A recruit becomes a citizen where it stands; a removed tavern's visitors leave. The tavern's menu lists its residents and visitors. Visitors have 20 health; a hit by a mob or player for more than 1 (a player's needs HURT_VISITOR, as in the mod; attacking one at all inside a colony first needs ATTACK_CITIZEN, as for citizens, which hostile players lack by default) makes it and the tavern's other visitors fight back: 10 damage a second from 2 blocks, for up to 30 seconds (`VisitorCitizen.hurt`, `EntityAIVisitor.doFight`). A visitor's death is told to the colony and its tavern gets no visitors for 30000 to 35000 ticks
  - Ours: rarity 7 of the mod's list is only its own foods, so it is rabbit stew, pumpkin pie and golden carrots; its foods in rarities 5 and 8 are left out
  - Medieval birch's taverns 2 and 3 have no sit tags in the pack: the converter borrows the other medieval styles' (same layout, a stair at each). A tavern without seats (cavern's alternative taverns 1 and 2) sends its visitors to stand beside the hut block instead (ours). Visitors fight back straight away (the mod's wandering visitor notices it was hurt within 10 s), don't heal and don't keep their health over a restart, can't be fed to stay longer, and don't wear boots showing their rarity; no skills, so no recruit levels; no gate house spawns, no tavern music, no custom visitors
  - The time to the next visitor isn't saved: after a restart each tavern waits 10000 ticks again
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
- [~] Hut settings (`SettingsModule`, `BuildingModules`' settings, `HiringMode`), the mod's defaults: a Settings button in each hut's menu (changes need MANAGE_HUTS), saved with the colony
  - Hiring mode on every worker hut, house and tavern: Default (follows the Town Hall), Automatic, Manual; homes also Locked (nobody moves in or out, no births there, and it counts only its residents towards the colony's room), as in the mod. Whenever the colony doesn't fill a hut or home itself (Manual, or Default with the Town Hall's switch off), its menu has Hire / Fire or Move in / Move out; hiring someone moves them from their old job. Automatic housing only moves the homeless in, as in the mod
  - Town Hall: citizens move in (off: no new citizens, children or visitors), auto hiring, auto housing, construction tape, entering/leaving messages (ours: shown as a title, not a chat message; not to the colony's owner and officers, as in the mod; walking straight into another colony shows one title naming both)
  - Builder: foundation fill block. Miner: fill block (holes, liquids, floors; the ladder's backing stays cobblestone, as in the mod) and max depth (it can only make the mine shallower). Lumberjack: replant. Smeltery: fewest ores a delivery may bring (at most 32, what it asks for at a time; 0 acts as 1). Herders: breeding; shepherd: shearing and dyeing; cowhand: milk and stew per milking, days between milkings (it keeps the mod's ceil(2 × amount ÷ days) buckets and bowls)
  - Block settings take the block in the player's hand (the mod has a block picker); number settings are typed, within our ranges
  - Not done, as their features don't exist yet: the lumberjack's restricted area, defoliating and shears, the farmer's fertilizing, the builder's work order mode and building patterns, crafters' recipe mode, the guard tower's tasks, retreat and trainees, the tavern's music

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
  - Cavern keeps each hut in "default", "alternative" and "megahall" folders: huts use "default" unless the build tool picks another; it has no mine shafts, so its miners dig the medieval oak ones
  - The other 18 styles aren't converted
  - Modded blocks approximated (see `docs/structures/medievaloak.md`)
- [~] Build tool (Structurize `ItemBuildTool`, `AbstractBlueprintManipulationWindow`; MineColonies `SurvivalHandler`): the Build Tool item is the tool (a stick with a tag, made with `/colony buildtool`). Right-click a block, pick a hut you carry; a preview of its level 1 building stays until confirmed or cancelled. Right-click again for the mod's controls in a dialog: move forward/back/left/right from where you face, up/down, rotate left/right, mirror (front to back, as the mod), confirm, cancel. Confirming checks the same rules as placing by hand, uses up the hut item, places the hut block and keeps the turn and mirror for every level
  - Preview is block displays at 90% size, only for the placing player (the mod draws see-through blocks); blocks walled in on all sides are skipped
  - The dialog covers the screen, so each button closes it to show the change
  - Browses the colony's style by its category folders, as the mod's build tool does (fundamentals, craftsmanship/storage, decorations/misc...), listed from the data packs' files: MineCP's huts in every version the pack has (the mod's alternatives: "altbuilder", cavern's "alternative" and "megahall" folders), usable from a hut item you carry; the hut remembers its version and upgrades follow it (the mod's blueprint path; the usual version in a style that lacks it). Builds without a hut under decorations, infrastructure and walls (not the miner's shafts, nor the supply camp and ship, which the mod places from their items) need no item: wholly inside a colony and with MANAGE_HUTS, they are queued for the builder, which builds them after the huts' work orders (the mod's decoration work orders); a leveled one at level 1. The Town Hall's "Builds" lists them with Cancel buttons
  - A build without a hut is placed by its corner (the mod: its blueprint's anchor); no decoration controller, so such builds aren't upgraded or repaired later; only the colony's style (the mod lets you pick any pack); huts MineCP doesn't have (barracks, school...) aren't listed
  - No ground-level offset: the hut block goes where you click (the converter drops the blueprints' ground tags)
  - The building must fit inside the colony's border (see Chunk claims)
  - The block at the hut spot is broken as a player break (protection plugins can refuse it); graves and blocks holding items are refused
- [ ] Scan tool
- [~] Hut recipes: MineColonies' shapes, with our Build Tool in place of the mod's
  - Only huts that are built so far have recipes

## Resource jobs

- [~] Lumberjack
  - Replants with the saplings that dropped from the tree; when none did, it fetches the right sapling from its hut chest and comes back to plant it (the mod's checkAndTransferFromHut). Its chest keeps a stack of each sapling for that (the mod's keepX); no sapling list to choose which trees to cut
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
- [~] Fisherman (`EntityAIWorkFisherman`, `Pond`), the mod's numbers unless noted; recipe as the mod's (a fishing rod):
  - [x] Ponds: water 5 by 5 and 2 deep (flowing water counts), within 100 blocks of the hut, more than 7 from a known one; it remembers up to 20 and picks one of them at random once it knows 20 or a search finds none. It stands on dry ground within 10 of the pond and 3 of water. Ours: the search checks every other column, nearest first, through loaded chunks only; the pond needs air above its middle and the spot to stand must be within 2 blocks of the water's height; no line-of-sight check or 30% early stop. After a search that finds nothing it uses the ponds it knows until a restart or none are left; with none it searches again once a minute
  - [x] Fishing with a rod from its hut chest (requested like other tools; better rods need hut levels): casting and reeling in each fail 3 times in 10 (the mod's roll, unskilled) with a 5 s pause; a bite after 1060 to 1300 ticks less 100 per lure speed (5, plus Lure) plus 20 to 80. 1 rod damage a catch. After 10 catches it takes them to the hut; after each catch a 5% chance to move to another pond
  - [x] Loot as the mod's table: junk 10, fish 85, treasure 5 only over ocean (no research), with Luck of the Sea's quality; the junk, fish and treasure are vanilla's tables, written out (no bamboo; treasure enchanted at level 30). Bonus from hut level 3: prismarine shard or crystals 25 in 1000 each, a sponge 1 in 1000 from level 4
  - Ours: no bobber (vanilla's needs a player): it swings, waits, and the water splashes when it reels in; it doesn't turn to face the water. No experience orbs or skills. Advancement "Gone Fishing" (the mod's build_fisherman)
- [ ] Composter
- [ ] Plantation
- [ ] Florist
- [ ] Beekeeper
- [~] Animal herders (`AbstractEntityAIHerder`, the five herders), the mod's numbers unless noted: chicken farmer (wheat seeds), cowhand (cows, mooshrooms and goats; wheat), shepherd (wheat), swineherd (carrots), rabbit hutch (carrots); recipes as the mod's (an egg, beef, shears, a porkchop, a rabbit). Advancement "The Whole Herd" for all five (the mod's build_all_herders)
  - [x] Works only the animals and dropped items inside its building's box (ours: 4 below to 8 above the hut block). Every 4 s: 1 in 10 picks up items lying there; else butchers with chance 0.5 × adults⁴ / (2 × hut level)⁴, never at 3 adults or fewer, the one farthest from the herd (under a roof first), with an axe from its chest; else breeds the one that can breed nearest the middle of those that can, with its nearest partner within 10, one breeding item each, then a minute's pause (20 s when no partner)
  - [x] Butchering hits as an unskilled citizen's: 1 a hit for the cowhand, shepherd and swineherd; 5 for the chicken and rabbit herders, landing 2 times in 21. 1 axe damage a hit
  - [x] Shepherd: shears adult sheep with shears from its chest, 1 wool (the mod's without Fortune), and dyes the sheep a random colour (hut level + 1)% of the time. Cowhand: one milk bucket (from a bucket) and one mushroom stew from a mooshroom (from a bowl) a day, the mod's default settings, keeping 2 buckets and 2 bowls; to the hut straight after
  - [x] Goes to the hut after 10 actions; keeps its tools, the breeding items (a stack) and the cowhand's buckets and bowls; asks for 16 breeding items when it has fewer than 2
  - Ours: drops picked up right after a kill (the mod waits for its 1 in 10 pick-up); a target it can't reach in 30 steps of walking is given up; the breeding pause isn't saved. Not done: feeding (only helps with skills in the mod), the hut settings (breeding, shearing, dyeing, milking amounts and days), Looting from research, skills and experience
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
- [~] Bakery (`BuildingBaker`, job "Baker"): knows the mod's own recipes for bread from level 1, cookies from 2, pumpkin pie from 3 and cake from 4 (its `crafterrecipes/baker` level limits) without being taught, and they don't count against its recipe limit; it can be taught crafting recipes its tags allow (anything made from wheat but bread, cake, cookies, pumpkin pie and packed mud); hut recipe as the mod's (wheat). Couriers bring it wheat and the rest from the warehouse, and the cook's and other huts' food requests can be crafted there
  - Ours: the mod first makes its own dough and batter items from wheat (and, from level 3, water and milk bottles that make more dough per wheat), then bakes them in a furnace; MineCP has no such items, so the baker uses vanilla's crafting recipes at its table, with no furnace
  - None of the mod's own foods (muffins, pizza, cheesecake, flatbread and so on)
- [ ] Other crafters: fletcher, dyer, glassblower (unlocked by research in the mod, which MineCP doesn't have), concrete mixer, crusher, sifter, alchemist, enchanter

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
- [~] Raids (`RaidManager`, `HordeRaidEvent`, `RaiderMobUtils`, `RaiderWalkAI`; land hordes only), the mod's numbers unless noted:
  - [x] Schedule, per colony, decided one nightfall ahead: no raid before the minimum nights (10) plus any extra, a sure raid past the average (14) + 2, else a 1 in (average − minimum) chance each nightfall. Only when raids are on, the world isn't peaceful and the owner or an officer is online. Settings in config.yml (`raids:`), with the mod's defaults and ranges
  - [x] Raid level: 5 per adult citizen, 5 + level² / 5 per built hut, times how full the colony's beds are; no raid below 75 (no skills or research to add, as in the mod)
  - [x] Difficulty starts at 7 (1 to 14): at the first nightfall after a raid, losing more than 15% of the beds' worth of citizens (a guard counts 1, anyone else 2) lowers it by one per 15% and adds 40% of the average nights; under 5% raises it by 1. Over 50% lost is a defeat: the raid ends and the next waits twice the average
  - [x] Raiders: 1 + level / 60 × (difficulty / 10 + 0.2) × (setting / 5) × (world difficulty / 2) × (1 + 5% per member online) × 0.85 to 1.15, at most the max setting + 1 (as the mod; ours: a typed `/colony raid now` amount is capped the same); one spawn point per 20, split among the points found; a tenth chiefs and three tenths archers (at least one of each)
  - [x] Spawn point: a random direction from the hut farthest that way, in 48-block steps through loaded chunks, clear of other colonies and 35 blocks plus a bonus per level from every built hut (guard tower 7, town hall 8, houses and taverns 4, others 2)
  - [x] Cultures by the biome at the colony's centre: desert mummies, jungle amazons, taiga norsemen, else barbarians two times in three and norsemen otherwise
  - [x] Camps: a campfire per 5 raiders, 3 to 7 blocks out; they camp 3 colony ticks (6 within 100 blocks of the centre) unless one is hurt; then they march on a hut the raiders share (another after max(6, raiders / 3) picks), attacking citizens and players they see within 16 blocks. Zombie raiders break doors on Normal and Hard (setting)
  - [x] Stats: health max(10, 10 + level / 40) × modifier, damage 2 + modifier × min(level / 400, 3), armor the modifier, speed 0.25 (0.30 from modifier 2.4), follow range 70; chiefs and the rest with the mod's own changes (barbarian chief ×1.5 health, 2× armor, +1 damage; pharaoh ×4.5; amazon chief 4× armor; shieldmaiden armor 15; norse chief 2× + 15...); 5 experience; the mod's loot tables
  - [x] End: a horde is beaten when under a tenth of it is left; the raid ends when every horde is, after 3 nightfalls, or 30 minutes after it began (the mod's despawn); leftovers and campfires go. "Only N raiders left!" at 5; raiders glow under 15% left. A raid with no citizen lost makes everyone happier for 3 days (the mod's "raidwithoutdeath"). A planned raid that can't start waits for the next nightfall, as in the mod. Ours: a raid that times out, runs out of nights or whose raiders unload ends with "the raiders have left", no reward and no change to the difficulty
  - [x] Citizens hide at home for the whole raid; guards keep fighting (they attack hostile mobs on the colony's land)
  - [x] Boss bar while you are in the colony's land: red, 10 notches, "<culture> - <direction>", raiders left of those that came. The illagers' raid horn sounds for members when a raid starts (the mod plays its own alert), the challenge-complete sound when it is beaten
  - [x] `/colony raid now [amount]` and `/colony raid tonight` (admins, the colony you stand in)
  - Raiders are vanilla mobs in plain vanilla armor (the resource pack stays optional), with stats set to the mod's; the armor and weapons are for looks only (their own points and damage don't count) and never drop:
    - Barbarians: melee zombie (stone axe, leather cap and tunic), archer skeleton (bow, leather), chief zombie (iron sword for the mod's chief sword, chainmail as in the mod)
    - Mummies: melee husk, archer skeleton, pharaoh skeleton (a Flame bow for the mod's fire-arrow scepter; gold helmet and chestplate)
    - Amazons: spearman zombie (stone spear), archer bogged, chief bogged (full leather)
    - Norsemen: shieldmaiden zombie (stone axe, shield, iron helmet and chestplate), archer stray, chief zombie (full iron)
  - Ours: spawn steps on water or lava are skipped (the mod sends ships there); raiders can't hurt each other and zombie raiders don't turn into drowned (vanilla behaviour switched off); the archers' arrows are vanilla's, not the mod's damage; vanilla mob drops are replaced by the mod's tables with vanilla items, and the ancient tome's share drops nothing
  - Raiders aren't saved: a restart, or their chunks unloading, removes them and the raid can end early (the mod respawns them). The raid itself isn't saved either
  - Not done: ship raids (pirates, norse ships, drowned pirates), sky raiders, the mod's random culture rolls, waypoints from its raider pathing (ours walks 32 blocks at a time), breaking blocks when stuck, the first 40 ticks' invulnerability, the player-hit and environment damage rules, the chief's speed aura, guards called to the raid's target, spies, raid history, the ancient tome, mercenaries, raider sounds, darkness and fog for some cultures, the Raider's Bane enchantment
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
- [~] Grave and named grave (graveyard): stand-ins are a stone brick wall (mossy once decayed), and a stone brick wall with a sign for the named grave. The grave opens like a chest; breaking it drops what is in it. Named graves are placed by the undertaker on the graveyard's burial spots: a sign in front of the wall (on top if the front is taken) with the first name, the rest of the name and the job
- ~~Iron gate and wooden gate (large gates)~~ (skipped)

### Items

- [~] Build tool (Structurize): the Build Tool (see Build tool above); `/colony build` for free builds
- [ ] Scan tool (Structurize)
- [x] Supply camp and supply ship deployers: a chest and an oak boat stand in for their models (see Supply camp above)
- [~] Clipboard (`ItemClipboard`, `WindowClipBoard`; the mod's recipe): paper with a tag stands in for its model. Right-click any hut of a colony to bind it; right-click anywhere else to list the colony's requests that wait for a player, nearest hut first. Needs ACCESS_HUTS in that colony
  - No cancelling requests, no "show all" toggle (it lists only what waits for a player), no count on the item's icon
- [~] Resource scroll (`ItemResourceScroll`, `WindowResourceList`; the mod's recipe): paper with a tag. Right-click a Builder's Hut to link it; right-click anywhere else to see the builder's current work, how much is supplied, and for each material what the builder has (carried or in its hut) of what is still needed, what you carry and what the warehouse holds, sorted and coloured as the mod's
  - Ours: the warehouse amount is read live; the mod saves a snapshot when you click the warehouse with the scroll. No "in delivery" amount
- [ ] Scepters: guard (patrol points), lumberjack (restricted area), beekeeper, permission
- [ ] Rally banner (guards follow the player)
- [~] Build goggles: they show colony borders while worn (see Town Hall above); not the buildings' blueprints in the world
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
