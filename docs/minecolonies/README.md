# MineColonies source index

The mod's source is cloned at `minecolonies/` (gitignored, branch `version/main`). Read it there instead of through `gh api`.

- First time: `git clone --depth 1 -b version/main https://github.com/ldtteam/minecolonies.git minecolonies`
- Refresh the clone and these indexes: `git -C minecolonies pull && python3 tools/index_minecolonies.py`

## Files here

| File | Use it to |
|---|---|
| [huts.md](huts.md) | Go from a hut to its building class, job, AI, AI base classes, hut recipe, crafter recipes and modules. Generated from the mod's registries. |
| [constants.md](constants.md) | Get the numbers: every `static final` constant (with its javadoc line) of each hut's building, job and AI, the shared AI and building bases, the systems below and `api/util/constant/*`. Generated. |
| [classes.md](classes.md) | Find a class by package, with the first sentence of its javadoc. Generated. |

Grep these before you open source files: `grep -n -A12 '^## farmer' docs/minecolonies/huts.md`, `grep -n -A30 '^## EntityAIWorkFarmer' docs/minecolonies/constants.md`.

Limits: constants that are only literals inside methods, or values from `ServerConfiguration` (defined with `builder.define...`, read through `MineColonies.getConfig().getServer()`), are not in constants.md. Read the AI's state machine (`registerTargets` / `new AITarget(...)`) for the order things happen in.

## Colony-wide systems

Paths are under `minecolonies/src/main/java/com/minecolonies/` unless they start with `data/`, which means `minecolonies/src/main/resources/data/minecolonies/` or the generated copy at `minecolonies/src/datagen/generated/minecolonies/data/minecolonies/`.

| System | Where to start |
|---|---|
| Colony object, tick, upkeep | `core/colony/Colony.java`, `core/colony/ColonyManager.java` |
| Claims, chunk ownership | `core/colony/ColonyManager.java` (claim methods), `core/commands/colonycommands/CommandColonyChunks.java` |
| Permissions, ranks | `core/colony/permissions/Permissions.java`, `core/colony/permissions/ColonyPermissionEventHandler.java` |
| Settings (per colony) | `core/colony/buildings/modules/settings/`, `api/colony/buildings/modules/settings/` |
| Citizen data, spawning, max citizens | `core/colony/CitizenData.java`, `core/colony/managers/CitizenManager.java` |
| Citizen entity | `core/entity/citizen/EntityCitizen.java`, handlers in `core/entity/citizen/citizenhandlers/` |
| Daily routine (eat, sleep, sick, wander, mourn) | `core/entity/ai/minimal/` (`EntityAIEatTask`, `EntityAISleep`, `EntityAISickTask`, `EntityAICitizenWander`, `EntityAIMournCitizen`) |
| Food, saturation, food diversity | `core/entity/citizen/citizenhandlers/CitizenFoodHandler.java`, `api/util/FoodUtils.java` |
| Happiness | `core/entity/citizen/citizenhandlers/CitizenHappinessHandler.java`, `api/entity/citizen/happiness/` |
| Skills and XP | `CitizenSkillHandler.java`, `CitizenExperienceHandler.java` |
| Sickness | `CitizenDiseaseHandler.java`, `data/colony/diseases/` |
| Children, reproduction | `core/colony/managers/ReproductionManager.java` |
| Visitors, tavern recruiting | `core/colony/managers/VisitorManager.java`, `core/colony/buildings/modules/TavernBuildingModule.java`, `core/entity/ai/visitor/EntityAIVisitor.java`, `data/colony/recruitment_items/`, `data/visitors/` |
| Citizen names | `data/citizennames/`, `core/datalistener/CitizenNameListener.java` |
| Interactions (citizen chat, requests to the player) | `api/colony/interactionhandling/`, `core/colony/interactionhandling/` |
| Graves, graveyard | `core/colony/managers/GraveManager.java`, `BuildingGraveyard`, `JobUndertaker` |
| Raids (barbarians, pirates, …) | `core/colony/events/raid/` (`RaidManager`, `HordeRaidEvent`, `AbstractShipRaidEvent`, one folder per raider type), raider mobs in `core/entity/mobs/raider/` |
| Mercenaries | `core/entity/mobs/EntityMercenary.java`, `EntityMercenaryAI.java` |
| Colony events log | `api/colony/colonyEvents/descriptions/`, `core/colony/managers/EventDescriptionManager.java` |
| Statistics | `core/colony/managers/StatisticsManager.java` |
| Quests | `core/quests/`, `data/colony/quests/` |
| Request system | `core/colony/requestsystem/` (`management/manager/StandardRequestManager.java`, `resolvers/`, `requests/`), `api/colony/requestsystem/` |
| Deliveries, warehouse | `resolvers/DeliveryRequestResolver.java`, `resolvers/WarehouseRequestResolver.java`, `BuildingWareHouse`, `JobDeliveryman` |
| Racks | `core/tileentities/TileEntityRack.java` |
| Crafting recipes for workers | `core/colony/crafting/CustomRecipeManager.java`, `api/crafting/RecipeStorage.java`, `data/crafterrecipes/<job>/` |
| Composting | `api/crafting/CompostRecipe.java`, `BuildingComposter`, `JobComposter` |
| Work orders, builder | `core/colony/workorders/` (`WorkManager`, `WorkOrderBuilding`), `core/entity/ai/workers/AbstractEntityAIStructure.java` |
| Structures, styles | `core/structures/`, blueprints in `minecolonies/src/main/resources/blueprints/` |
| Hut blocks and their placement | `core/blocks/huts/`, `core/tileentities/TileEntityColonyBuilding.java`, `core/placementhandlers/` |
| Fields (farm, plantation) | `core/colony/buildingextensions/` |
| Supply ship and camp | `core/items/ItemSupplyChestDeployer.java`, `ItemSupplyCampDeployer.java` |
| Other items | `core/items/` (clipboard, resource scroll, rally banner, colony teleport scrolls, build goggles, scan analyzer) |
| Research | `core/research/` (`GlobalResearchTree`, `LocalResearch`), `core/colony/managers/ResearchManager.java`, `core/datalistener/ResearchListener.java`, `data/researches/` |
| Research effects (what a research unlocks) | `api/research/util/ResearchConstants.java`, `data/researches/effects/` |
| Advancements | `api/advancements/`, `data/advancements/` |
| Pathfinding | `core/entity/pathfinding/` |
| Server config (all tunables with defaults) | `api/configuration/ServerConfiguration.java` |
| Commands | `core/commands/` |
| English strings | `minecolonies/src/main/resources/assets/minecolonies/lang/manual_en_us.json` (the rest of `en_us.json` is generated at build time, not in the repo) |
