# MineCP in-game test plan

A manual playthrough that touches every feature in `parity.md`. Each check is an action and what should happen. The exact numbers are in `parity.md` under the matching entry. Look them up there rather than copying them here, so this plan doesn't go stale.

Tick the boxes as you go. When something fails, note the step and what happened, and check the server log for `[MineCP]` errors.

## 0. Setup

- [ ] `./gradlew installDatapacks`, then start the server. The log shows the startup report with all five style packs loaded and no errors.
- [ ] Join with two accounts: **A** (the owner, an operator) and **B** (a second player, not an operator). Some steps need a third, **C**, for neutral and hostile tests.
- [ ] Clear the weather and set the time to day. Turn on `/gamerule keepInventory` so a death doesn't lose the test kit.
- [ ] Without the resource pack: items show vanilla looks with our names and lore. With the pack: they show the mod-like models (Optional resource pack).

## 1. Regression: the last review's fixes

Do these first, because they are committed but have never been run in game.

- [ ] **Smelter, full chest:** fill a Smeltery's hut chest so that only ore and one free slot are left. The smelter keeps the furnaces running and doesn't break more ore. The hut menu says the chest is full. Empty the chest and it goes back to breaking ore.
- [ ] **Attacker rights** (needs `pvp.enabled: true` and a war between A's and B's colonies; see section 13): as the attacker in the other colony, you can't place blocks, open huts or place huts, but you can open plain containers and graves. A hut's own chest stays closed.
- [ ] **Away colony:** with no owner or officer of colony B online for 2 hours, A's guards don't attack B's citizens. Use a short test by setting the clock or waiting.
- [ ] **Town Hall breaking message:** start breaking an enemy Town Hall several times within a minute. Its colony is told at most once a minute.
- [ ] **Postbox and stash** take about 1.5 s to break in a colony that isn't yours, not a minute.
- [ ] **Whirlwind in PvP:** a knight's whirlwind hit on an armored player does the same damage as on an unarmored one.
- [ ] **Kits:** place a Supply Camp and a Supply Ship in each style: medieval oak, spruce, birch, dark oak, and cavern, including the Nether ship and the cavern camp. Every rack in the placed camp is filled with the kit.
- [ ] **Compost list:** the Composter's menu lists items as "name (n)".
- [ ] **Tavern removed:** take a tavern down while visitors are inside or music is playing. Nothing is left stuck, and no errors appear in the log.

## 2. Founding a colony

- [ ] Craft a Supply Camp and a Supply Ship from their recipes. Place each on land and on water. The preview, the turning, and the refusal when there's no room all work as the Supply camp entry says.
- [ ] The camp's racks hold the start kit, which includes the Town Hall and the Build Tool.
- [ ] Place the Town Hall: the colony is founded, named, and its chunks are claimed. Build goggles show the border.
- [ ] Try founding a second colony too close to the first, and one as B inside A's border. Both are refused.
- [ ] `/colonies colony info` shows the colony. `/colonies colony claiminfo` on a border chunk names A's colony.
- [ ] `/colonies resetsupplies B` lets B take a camp again.

## 3. Citizens and growth

- [ ] The first 4 citizens move in one at a time at the Town Hall, on the timer. The Town Hall menu's citizen list shows them.
- [ ] Citizens have names and skins, and wander about. `/colonies citizens list` and `citizens info <n>` match what you see.
- [ ] Build a House. Citizens take beds there and go home at night. They sleep and come out in the morning (Day/night routine).
- [ ] Hunger: saturation drops through the day. Citizens eat from the restaurant or their hut, and complain when there's no food. Try `/colonies citizens modify <n> saturation = 0`.
- [ ] Leisure: in free time, citizens visit the tavern or library and sit or read.
- [ ] Happiness: the factors in the citizen menu change with housing, food, tools, security and the rest. Happiness has an effect, such as the cap on intelligence.
- [ ] Children: with a man and a woman and room, a child is born, grows up over the days, and takes a job.
- [ ] Mourning: kill a citizen with `citizens kill`. Its housemates mourn the next day.
- [ ] Health: a citizen loses at most 4 health per hit, heals over time, and is moved out when stuck in a wall.
- [ ] Death: a grave appears. Its inventory can be opened. It turns mossy, then goes away.
- [ ] `citizens walk`, `teleport`, `reload` and `trackPath` each do what they say.

## 4. Permissions and claims

- [ ] `/colonies member add B`, then move B through the ranks: officer, friend, neutral, hostile. At each rank, check that B can or can't break, place, open huts, open containers and use the hut menus. The rank table in the colony's permissions menu matches.
- [ ] Change one action for a rank in the permissions menu. The change applies at once and is still there after a restart.
- [ ] A hostile player in the colony is attacked by the guards. A neutral player isn't.
- [ ] Claims grow as huts are built and upgraded, by each building's claim radius. `reclaimchunks` and `chunkstatus` work, and `claim <owner> <range> true|false` adds or removes chunks.
- [ ] Explosions inside the border follow `protection.explosions` in `config.yml` (Permissions entry). Hut blocks, graves and decoration controllers survive them.

## 5. Builder and construction

- [ ] Build Tool: right-click to pick a hut or decoration, preview it, turn and mirror it, and place it. Construction tape marks the site.
- [ ] The builder asks for materials, using requests (section 6). It fetches them, builds in order (solid, then attached, then entities), uses its tools, and finishes. The Town Hall or hut menu shows its progress.
- [ ] Upgrade a hut to its top level, check the repair option, and take it down. Its block comes back.
- [ ] Decoration controller sign: right-click it to upgrade, repair or cancel. Breaking it follows the BREAK_HUTS rule.
- [ ] Waypoints are recorded for built huts.
- [ ] A hut upgrade needs a Builder's Hut at that level (a Builder's Hut can always take its own next level); there's no Town Hall cap. Tools are capped by the hut level.
- [ ] Repeat one build in each style. Blocks look right, with no missing or odd states. See the reports in `docs/structures/`.
- [ ] Every hut recipe in the Hut recipes entry crafts the right hut item.

## 6. Logistics

- [ ] Build a Warehouse with racks and assign a courier. Couriers carry items between huts and the warehouse.
- [ ] Requests: a hut's needs show in its menu. The warehouse delivers once it holds the item, and the Town Hall's request list shows what nobody can supply.
- [ ] Postbox: order an item and the courier brings it. Minimum stock settings keep the item topped up.
- [ ] Stash: items put in it are carried to the warehouse.
- [ ] Hut settings: change each setting and check that the worker follows it, the setting survives a restart, and changes need MANAGE_HUTS.
- [ ] Clipboard, resource scroll and colony map each show the right information.

## 7. Resource jobs

For each job: hire a worker, give it tools, and check that it works, asks for what it needs, and stores its output.

- [ ] Lumberjack, with the scepter's area and replanting.
- [ ] Farmer and fields, with the scarecrow and each crop.
- [ ] Miner, through levels and shafts.
- [ ] Quarrier, with the simple and medium quarry.
- [ ] Fisherman.
- [ ] Composter, using barrels and making compost.
- [ ] Plantation fields: each plant type.
- [ ] Flower Shop, with composted dirt.
- [ ] Beekeeper, with the hive tool.
- [ ] All five herders.
- [ ] Stable, with cavalry horses.
- [ ] Nether Mine: a trip, the supplies it asks for, the finds it brings back, and the food on its menu.

## 8. Crafter jobs

- [ ] Teach a crafter a recipe. The recipe limit follows the hut level. Recipes outside its kind are refused.
- [ ] Each crafter makes what its entry says: Cook, Sawmill, Stonemason, Stone Smeltery, Blacksmith, Smeltery, Mechanic, Bakery, Fletcher, Dyer, Glassblower, Crusher, Concrete Mixer, Sifter (with meshes), Alchemist and Enchanter. They use furnaces where the entry says so.

## 9. Education and research

- [ ] School: children sit at seats and take recess.
- [ ] Library: students study with paper, with books and with nothing. Intelligence rises, capped by happiness. The odds are the mod's, as decided.
- [ ] University: start research, check the costs are taken, and that research finishes after its time. Unlocked buildings and effects apply, for example a hut that research unlocks becomes craftable.

## 10. Military and raids

- [ ] Guard Tower: knight, ranger and druid each fight with their own attacks. The guard scepter sets patrol points and the rally banner gathers guards.
- [ ] Barracks and its towers, the archery, and the combat academy all train their workers.
- [ ] Gatehouse and gates: iron and wooden gates open for citizens. Raiders break wooden gates, as the Gates entry says.
- [ ] Raids: `/colonies colony raid A now` with each raid type, both land and ship. Raiders come, guards fight, buildings may be damaged, and `raidhistory` records it. Try `tonight` too.
- [ ] Mercenaries can be hired.

## 11. Health, sickness and the graveyard

- [ ] Sickness: a citizen falls ill and goes to the Hospital, and the healer cures it with the right item.
- [ ] Before a Hospital exists, give a sick citizen the cure item yourself through its inventory.
- [ ] Graveyard: the undertaker digs graves and can bring a citizen back. The Mystical Site raises the undertaker's cap.

## 12. Colony connections

- [ ] Colony signs: shift-right-click a level-1 gatehouse, then place signs along a walkable path to B's gatehouse.
  - A sign without a walkable path breaks and drops.
  - A sign placed too far from the last one is refused.
- [ ] Connect the two colonies, then on the Alliances page: request an alliance, accept it, start a feud and set them back to neutral. Both sides see the events.
- [ ] Travel between allied gatehouses. Non-members pay gold nuggets, and half the payment goes into the destination gatehouse's chest.
- [ ] Breaking a sign or a gatehouse breaks the chain and tells the managers.

## 13. PvP (set `pvp.enabled: true`, restart)

- [ ] Declare war by setting the other colony's owner hostile. The strength check holds: you can attack a colony at most twice as strong as yours.
- [ ] Enter the enemy's land with guards that follow or rally to you. You may break and place there, and the guards of both colonies fight.
- [ ] Break the enemy's Town Hall. Breaking takes time, the colony is warned, and finishing ends that colony.
- [ ] Set `pvp.enabled: false` again and check that none of the above works any more.

## 14. Advancements and commands

- [ ] Advancements: the MineColonies, production and military tabs appear. Founding, building and the rest unlock their advancements.
- [ ] Every `/colonies` subcommand runs with tab completion. As B, operator-only commands are refused.

## 15. Saving

- [ ] Restart the server mid-game, with builds, trips, research and raids in progress. Everything comes back as it was, except what the parity notes say isn't kept, such as the tavern's visitor timer.
- [ ] `/colonies forceunloadchunks`, then return to the colony. Citizens and huts come back and carry on working.
