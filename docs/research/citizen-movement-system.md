# Citizen movement: our own pathfinder plus one NMS class

Researched 2026-11-17. Every claim has a source number from [Sources](#sources). Anything marked **UNVERIFIED** I could not confirm from a primary source. "Jar" means I read the bytecode of the Paper server jar in `run/versions/26.3/paper-26.3.jar` (Paper 26.3 build 253, Mojang-mapped) with `javap -p -c` [6]. "Mod" means MineColonies source on `version/main` [9].

## TL;DR

- **paperweight-userdev works on 26.3 and stays small.** Plugin `io.papermc.paperweight.userdev` **2.0.0-beta.24** needs Gradle 9.7.1+ (the repo has 9.9.1). Add `paperweight.paperDevBundle("26.3.build.253-stable")` and drop the `paper-api` line [1][2][4].
  - The default output is already Mojang-mapped (`MOJANG_PRODUCTION`), and paperweight stamps `paperweight-mappings-namespace: mojang` into the jar manifest [3].
  - Reobf jars don't load on Paper 26.1+ [1].
  - run-paper 3.1.0 detects userdev, takes the plain `jar` output for MC ≥ 1.20.5, and defaults `runServer`'s version to the bundle's [5].
- **The vanilla pieces we need are public and easy to drive** (jar):
  - `MoveControl.setWantedPosition(x,y,z,speed)` covers walking.
  - `JumpControl.jump()` covers jumping.
  - `new Path(List<Node>, BlockPos, boolean)` and `PathNavigation.moveTo(Path, double)` let us hand vanilla our own nodes. The mod does exactly this [9e].
  - **Traps:**
    - `MoveControl` falls back to `WAIT` after one tick, so we must set the target every tick.
    - It ignores new targets while in `JUMPING` until the mob lands. On a ladder it never lands, so it keeps its stale forward input. That is very likely our "spins at the top of the ladder" bug.
- **Ladders work through vanilla physics, no teleporting needed** (jar):
  - A `LivingEntity` on a `#climbable` block gets y-speed **0.2** per tick when `horizontalCollision || jumping`.
  - It slides down at most **0.15** per tick otherwise.
  - Sneaking only stops the slide for `Player`s.
  - So: push into the ladder or set the jump flag to go up; stop pushing to go down.
- **The Paper API alone can't drive this well:**
  - `Pathfinder.moveTo(PathResult)` takes only vanilla-made paths.
  - There is no MoveControl API.
  - `LivingEntity#setJumping` is overwritten every tick by `JumpControl.tick()` before `travel()` runs (jar).
  - `Mannequin` is not a `HumanEntity`, so `sleep()` isn't available [7].
- **Off-thread reads:** `Chunk#getChunkSnapshot(false,false,false,false)` gives a "thread-safe read-only snapshot" [8]. It copies only the block `PalettedContainer` of each non-empty section (jar). `ChunkSnapshot#getBlockData` is then safe off-thread.
  - Collision shapes are not in the snapshot. `BlockData#getCollisionShape(Location)` passes the live `ServerLevel` (jar), so calling it off-thread is **UNVERIFIED**. Cache shapes per `BlockData`.
- **MineColonies' design:**
  - **One** daemon worker thread runs A* (`ThreadPoolExecutor(1,1)`, queue 10,000).
  - It reads world blocks through **live `LevelChunk` references** (no copy). Unloaded chunks read as air.
  - The search is capped at `MAX_NODES = 8000` (×2 with extensions) and uses a Manhattan heuristic with ×2 weight that adapts over time.
  - Costs: base 1, jump +2, drop +1·|dy|³, swim +4 (+24 to enter), toggle door/gate +2, non-ladder climbable +3, path blocks ×1/6, max drop 10.
  - The stuck handler escalates over ~10 s steps: skip a node, repath, walk away, skip 5 nodes by teleport, and finally teleport near the goal after a timeout of ≥ 2 min [9].
- **Licensing:** code is GPL-3.0 [15a]. `src/main/resources/assets/minecolonies/LICENSE` on `version/main` reads, in full, **"All Rights Reserved"** [15b]. Textures, models and sounds can't be reused **even if MineCP goes GPL-3.0**.

## 1. paperweight-userdev on 26.3

| Item | Finding | Source |
|---|---|---|
| Plugin id / version | `id("io.papermc.paperweight.userdev") version "2.0.0-beta.24"` (released 2026-09-25, latest) | [1][2] |
| Gradle | beta.24 "require Gradle 9.7.1 or newer". Repo wrapper is 9.9.1. | [2] |
| Dev bundle | `paperweight.paperDevBundle("26.3.build.+")` in the docs. Maven has 26.3 builds up to `26.3.build.253-stable` (2026-11-15), and `26.3.build.249-stable` also exists. Pin the build that matches the server (253). | [1][4] |
| Paper API dependency | Remove `compileOnly("io.papermc.paper:paper-api:…")`: the bundle includes it | [1] |
| Mappings at runtime | Paper ships a Mojang-mapped runtime since 1.20.5. From 26.1 on, reobfuscation no longer works and reobf plugins won't load. | [1] |
| `reobfArtifactConfiguration` | Defaults to `ReobfArtifactConfiguration.MOJANG_PRODUCTION` (the `jar` output is the production jar; `reobfJar` writes a side `-reobf.jar` we never use). Nothing to set. | [3a][3b] |
| Manifest | userdev adds `paperweight-mappings-namespace: mojang` to `jar` (and `shadowJar`). This matters because MineCP uses `plugin.yml`: Bukkit plugins are assumed Spigot-mapped unless the manifest says otherwise. | [1][3c][3d] |
| run-paper interaction | With userdev applied, run-paper sets `runServer.version` from `paperweight.minecraftVersion`. For MC ≥ 1.20.5 it uses the normal `jar` output, not `reobfJar`. It also registers a `runDevBundleServer` task. `runServer { minecraftVersion("26.3") }` can stay or go. | [5] |
| First build | The first `compileJava` runs `paperweightUserdevSetup` (decompile and patch), which is slow once and then cached. **UNVERIFIED** timing. | [1] |

Minimal `build.gradle.kts` change (not applied: research only):

```kotlin
plugins {
    java
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.24"
    id("xyz.jpenilla.run-paper") version "3.1.0"
}
dependencies {
    paperweight.paperDevBundle("26.3.build.253-stable")
}
```

Our `compileJava`-only rule (AGENTS.md) still holds. userdev doesn't hook `jar` into `compileJava`.

## 2. Vanilla internals on 26.3 (from the jar)

### Controls and navigation
- `Mob` exposes public `getMoveControl()`, `getJumpControl()`, `getNavigation()` and `getLookControl()`. The fields `moveControl`, `jumpControl` and `navigation` are `protected` [6].
- **Tick order in `Mob.serverAiStep()`**: goals → `navigation.tick()` → `customServerAiStep` → `moveControl.tick()` → `lookControl.tick()` → `jumpControl.tick()`. `LivingEntity.aiStep()` calls `serverAiStep()` **before** it reads `jumping` and calls `travel()` [6].
- **`MoveControl`** (`MIN_SPEED_SQR = 2.5e-7`, `MAX_TURN = 90`) [6]:
  - `setWantedPosition(x,y,z,speed)` stores the target and sets `operation = MOVE_TO` **unless the current operation is `JUMPING`**.
  - `setWait()` sets `WAIT`. Calling `setWait()` then `setWantedPosition()` forces `MOVE_TO`.
  - `tick()` in `MOVE_TO`:
    - Sets `WAIT` first, so one call moves the mob for one tick.
    - If the distance² is below 2.5e-7, it sets `zza = 0`.
    - Otherwise it turns yaw toward the target (at most 90° per tick) and calls `setSpeed(speed × MOVEMENT_SPEED)`.
    - **It jumps** (`jumpControl.jump()`, `operation = JUMPING`) if `dy > maxUpStep && dx²+dz² < max(1, bbWidth)`, or if the block at the mob's feet has a collision shape whose top is above the mob's y and is not `#doors` or `#fences`. A ladder has a thin collision shape, so a mob standing in a ladder block jumps.
  - `tick()` in `JUMPING`: keeps setting speed. It returns to `WAIT` only when `onGround()`, or when in liquid and affected by fluids.
- **`JumpControl.tick()`** is `mob.setJumping(jump); jump = false;` [6]. This is why `LivingEntity#setJumping` from the API gets overwritten: `jumpControl.tick()` runs inside `serverAiStep()`, before `aiStep` reads `jumping`.
- **`PathNavigation`** [6]:
  - Constants: `MAX_TIME_RECOMPUTE = 20`, `STUCK_CHECK_INTERVAL = 100`, `STUCK_THRESHOLD_DISTANCE_FACTOR = 0.25`.
  - `moveTo(Path, double)` returns false for null or done paths. Otherwise it stores the path, calls `trimPath()`, sets the speed and resets the stuck check.
  - `tick()`:
    - If `canUpdatePath()`, it calls `followThePath()`.
    - Otherwise, if the mob is above the next node, in the same x/z block and not on the ground, it advances. That is how vanilla follows a path *down* a shaft.
    - It then calls `moveControl.setWantedPosition(next.x, getGroundY(next), next.z, speed)` **every tick**.
  - `followThePath()`:
    - `maxDistanceToWaypoint = bbWidth > 0.75 ? bbWidth/2 : 0.75 − bbWidth/2`. For a 0.6-wide mob that is 0.45 (trader width **UNVERIFIED**).
    - It advances when |dx|, |dz| < that and |dy| < `getMaxVerticalDistanceToWaypoint()` = 1.0.
    - It can also cut corners via `canMoveDirectly`.
  - `doStuckDetection` runs every 100 ticks. If the mob moved less than `max(speed,1)·100·0.25`² it sets `isStuck` and calls `stop()`.
- **`GroundPathNavigation.canUpdatePath()`** is `onGround() || isInLiquid() || isPassenger()` [6]. On a ladder the mob is airborne, so vanilla never steps along nodes going *up*.
- **`Path`** is `public final`. It has a public constructor `Path(List<Node>, BlockPos target, boolean reached)`, plus `getNextNodeIndex`/`setNextNodeIndex`, `advance()`, `getNode(i)` and `getNextEntityPos(Entity)` [6]. **`Node`** has a public constructor `Node(int,int,int)` and public `type`/`costMalus` fields. So we *can* build vanilla paths from our nodes. The mod does it with `PathPointExtended extends Node` [9d][9e].
- **Paper's `Pathfinder`** wraps only `createPath(x,y,z,range)` / `createPath(Entity,range)`, `moveTo(Path,double)`, `getPath()`, `stop()` and the `NodeEvaluator` door/float flags [6]. Its `PathResult` is an interface with no constructor from points [7]. Custom paths therefore need NMS.

### Climbing (from the jar)
- `LivingEntity.onClimbable()` is true for `#climbable` blocks, or for an open trapdoor above a matching ladder (`trapdoorUsableAsLadder`) [6].
- `handleOnClimbable(v)` clamps x/z to ±0.15 and y to ≥ −0.15, and resets fall distance. Setting y to 0 when sneaking applies **only to `Player`** (an `instanceof Player` check after `isSuppressingSlidingDownLadder()`). So a mob on a ladder slides down at most 0.15 blocks per tick [6].
- `handleRelativeFrictionAndCalculateMovement`: after `move()`, if `(horizontalCollision || jumping) && onClimbable()`, the y-speed becomes `CLIMBING_VERTICAL_SPEED = 0.2` [6]. In practice:
  - Going up: either walk into the wall the ladder hangs on, or keep `jumping` set every tick (it has to be done through `JumpControl` because of the tick order).
  - Going down: stay centred in the ladder column without pushing into the wall.
- Other constants: `BASE_JUMP_POWER = 0.42`, `INPUT_FRICTION = 0.98` [6].

### Doors
- A `WanderingTrader` registers no `OpenDoorGoal`. Its goals are Float, UseItem ×2, TradeWithPlayer, AvoidEntity ×7, Panic, LookAtTradingPlayer, WanderToPosition, MoveTowardsRestriction, WaterAvoidingRandomStroll, Interact and LookAtPlayer, and we remove them all anyway. `setCanOpenDoors(true)` only changes the node evaluator [6]. We keep opening doors ourselves, as `Colonist.doors()` does now [16].
- `PathType` includes `WALKABLE_DOOR`, `DOOR_WOOD_CLOSED`, `DOOR_IRON_CLOSED`, `DOOR_OPEN`, `TRAPDOOR`, `FENCE`, `WATER` and `RAIL`, but **no climbable type**. Vanilla ground paths have no ladder nodes [6].

### Paper API without NMS
- `com.destroystokyo.paper.entity.ai`: `Goal`, `GoalKey`, `GoalType`, `MobGoals`, `VanillaGoal`. A custom `Goal` ticks inside the goal selector, before `navigation.tick()` and `moveControl.tick()`. That is a good hook for timing, but it can't reach MoveControl [6][7].
- `Mob#lookAt(...)`, `Entity#setVelocity`, `Entity#setSneaking` and `LivingEntity#setJumping`/`isClimbing` exist [7]. `setVelocity(0, 0.2, 0)` on a ladder is an **API-only fallback** for climbing.
- There is no MoveControl or JumpControl API, and no sleep API for non-humans [7].

## 3. Off-main-thread world reads with the Paper API

- `Chunk#getChunkSnapshot(includeMaxBlockY, includeBiome, includeBiomeTempRain, includeLightData)` is documented as "Capture thread-safe read-only snapshot of chunk data". `ChunkSnapshot` is "a static, thread-safe snapshot" [8].
- **Cost** (jar): `CraftChunk` loops over the chunk's sections and calls `PalettedContainer.copy()` on the block states of each section that is not `hasOnlyAir()`. It copies light arrays, biomes and the heightmap only when the corresponding flag is set. `getChunkSnapshot()` with no arguments passes `includeLightData = true` [6][7]. **Use `(false,false,false,false)`**.
  - That copies about 24 sections of palette plus packed longs in the overworld. My estimate is tens of KB and well under a millisecond per chunk (**UNVERIFIED**, needs a benchmark).
  - Take snapshots on the main thread. `getChunkSnapshot` reads the live chunk. The javadoc doesn't say "main thread only", so that requirement is **UNVERIFIED**, but it is the safe default.
- `ChunkSnapshot#getBlockData(x,y,z)` reads the copied container and wraps the state as `CraftBlockData` (jar), so it is safe off-thread. It also offers `getBlockType`, `isSectionEmpty` and `getHighestBlockYAt` [6][7].
- **Collision shapes:** the snapshot gives only `BlockData`.
  - `BlockData#getCollisionShape(Location)` calls `state.getCollisionShape(craftWorld.getHandle(), pos)` (jar). `org.bukkit.util.VoxelShape` exposes `getBoundingBoxes()` and `overlaps()`.
  - For nearly all vanilla blocks the shape depends only on the state, plus a position offset for bamboo or dripstone. Calling it off-thread with a fixed location is likely fine, but **UNVERIFIED**.
  - Safer plan: a `ConcurrentHashMap<BlockData, Shape>` storing `{minY, maxY, fullXZ}`. Fill it lazily (off-thread calls accepted as a risk), or fill misses from the main thread and repath.
  - `Block#isPassable()` needs a live `Block`, so it can't be used off-thread [7].
- How to classify from `BlockData` (mirroring the mod's `SurfaceType` and `isPassable` rules [9b][9h]):
  - Collision `maxY ≤ 0.1` (carpet, pressure plate, 1-layer snow, rails): passable.
  - `maxY > 1.0` (fences, walls: 1.5): never stand on.
  - Slab, stairs, snow layers > 1: stand height = `y + maxY`.
  - A jump is allowed if the target top minus the current floor top is `< 1.3` (`MAX_JUMP_HEIGHT`).
  - `Openable` (doors, gates, trapdoors) is handled by state.
  - `Tag.CLIMBABLE` marks ladder nodes.
  - `Waterlogged` / `Material.WATER` marks swimming.
- **Alternatives:**
  - `World#getChunkAtAsync(...)` loads or generates chunks asynchronously but still hands back a `Chunk` on the main thread [7]. Use it only to make sure chunks are loaded, never for reads.
  - Live `Block` reads off-thread are unsafe.
  - The mod reads live `LevelChunk`s off-thread [9g]. That is racy, and Paper doesn't support it through the API.

## 4. MineColonies' pathfinding (`core/entity/pathfinding`, GPL-3.0; reference only)

### Threading and world cache
- `Pathfinding.java`: one `ThreadPoolExecutor(1, 1, 10s, ArrayBlockingQueue(10000))`, using daemon threads named "Minecolonies Pathfinding Worker #n". `PathResult.startJob()` submits the job (a `Callable<Path>`) and keeps the `Future`. The navigator polls `isDone()` each tick [9a][9i].
- `world/ChunkCache`, built on the main thread:
  - It stores **references** to the live `LevelChunk`s (via `chunkMap.visibleChunkMap` → `getFullChunkFuture().getNow`) for chunks that are entity-ticking.
  - It covers the box start..end ± (32 + manhattan/2). For range jobs it is ±1.3·range.
  - Missing chunks return `AIR`, which counts as a drop, not a floor [9g].
  - `CachingBlockLookup` adds a small per-node cache [9].

### Search (`pathjobs/AbstractPathJob`, `PathJobMoveToLocation`)
- **A\*** with a `PriorityQueue<MNode>` and an `Int2ObjectOpenHashMap` keyed by packed xyz. The heuristic is Manhattan distance × `heuristicMod`, which starts at 2 or at `1 + navigator.getAvgHeuristicModifier()`. That average is retuned after each path: `heuristicAvg += (costPerDist − heuristicAvg)/(50/(1+len/30))`. The heuristic is also re-evaluated mid-search when it overestimates [9c][9e].
- **Node limits:**
  - `MAX_NODES = 8000`.
  - For point-to-point jobs: `maxNodes = min(8000, 300 + 16·d + 2·d·r²)`, where `d = |dx|+|dy|+|dz|` and `r = min(20, 3 + d/20)`, plus `range`, times the config `pathNodeLimitMultiplier` (default 1, range 1–4).
  - The limit grows by `maxCost²·2`.
  - On hitting the limit, `PathJobMoveToLocation` may add 1000 (20 % chance when still < 10 blocks from start) or 500 (if a better node was found in the last 200), up to `2·MAX_NODES` [9c][9f][13].
- **Expansion order** (`visitNode`):
  - Ladder nodes try up and down.
  - "Corner" (air) nodes only go down.
  - Other nodes go down if passable, then N/E/S/W, skipping the direction they came from.
  - `getGroundHeight` resolves the real y: step up ≤ 1.3, drop, or swim [9c].
- **Destination:** exact block, or within √2 when the goal itself can't be stood in. The end node must stand on `WALKABLE`. Once the goal is reached, the job explores `extraNodes = 4` more. If the goal can't be reached it returns the best partial node, with liquid +30 and in-shape +10 penalties [9f].
- **Output:** `new Path(Arrays.asList(PathPointExtended[]), target, reaches)`. Corner nodes are dropped. Ladder points carry `onLadder` and `ladderFacing` (from the ladder's `FACING` or the vine's side; default `DOWN`) [9c][9d].

### Node rules (exact)
- **Walkable surface** (`SurfaceType`) [9h]:
  - Dangerous blocks → `NOT_PASSABLE`: fire, campfire, magma, sweet berry bush, powder snow, lava cauldron, plus the `dangerousBlocks` tag [9b].
  - Fence, wall or bamboo → `NOT_PASSABLE`.
  - Fence gate or door → `DROPABLE`.
  - Closed trapdoor → `WALKABLE`.
  - Shape maxY > 1.0 → `NOT_PASSABLE`.
  - Water → `WALKABLE`; lava → `NOT_PASSABLE`.
  - Signs and vines → `DROPABLE`.
  - Full-ish collision (≥ 0.85 wide in x and z), snow > 1 layer, carpet or lily pad → `WALKABLE`.
  - Everything else → `DROPABLE`.
- **Head and body room:** 2 blocks measured with collision shapes. When stepping from the parent, 1.75 of clearance is enough [9c].
- **Jump:** only from a non-ladder, non-swimming parent. The target top minus the parent's floor top must be `< MAX_JUMP_HEIGHT = 1.3`. A bottom stair can be climbed from its front side [9c][10].
- **Drop:** none from a ladder parent. It scans `i = 2..10` (`canDrop = true`; `false` → only 2) for a `WALKABLE` floor, and fails on any non-air block in between [9c].
- **Ladders:**
  - `LadderBlock` and the `freeClimbBlocks` tag always count.
  - Other `#climbable` blocks count only with `canClimbAdvanced`. A vine also needs a good floor block or leaves behind it.
  - `canClimbAdvanced` comes from the research effect `VINES` [9b][11].
- **Doors, gates, trapdoors** (`isPassable`):
  - Doors are enterable if `enterDoors`; citizens set this to `true` [11].
  - Fence gates are enterable if `enterGates` (default `true`).
  - Pressure plates, signs and banners are always enterable.
  - Trapdoors depend on facing: enterable from the side they face away from; when facing the mover, only if coming from above.
  - `canLeaveBlock` blocks leaving through an open trapdoor's face [9c].
- **Rails:** only if `canUseRails` (research `RAILS`) and the path has ≥ `minimumrailstopath = 8` rail nodes. The navigator then spawns a minecart and pushes it [9c][9e][11][13].
- **Water:** `canSwim` is true for citizens [9e][11]. With `walkUnderWater` false, nodes with liquid at head height are rejected. Diving (water above) costs extra [9c].

### Costs (`PathingOptions` defaults, `computeCost`) [9c][9j]

| Constant | Value | Rule |
|---|---|---|
| base | 1 + rand·`randomnessFactor` (0.1) | every step |
| `onPathCost` | ×1/6 | floor in tag `minecolonies:pathblocks` (concrete, stone bricks, planks, wooden slabs, carpets, polished stones…) [14] |
| `onRailCost` | ×1/10 | on rails |
| `jumpCost` | +2 | dy > 0, except ladder→ladder or ±1 onto stairs |
| `dropCost` | +1·\|dy\|³ | dy < 0 (same exceptions) |
| `caveAirCost` | +3 | `cave_air` |
| `badDirectionCost` | +5 each | entering or leaving a block (or its floor) across its horizontal `FACING` |
| `traverseToggleAbleCost` | +2 | state has `OPEN` (door, gate, trapdoor) |
| `walkInShapesCost` | +1 | target has collision and isn't a path block |
| shingles | +3 | mod block (n/a for us) |
| `railsExitCost` | +4 | leaving rails |
| `nonLadderClimbableCost` | +3 | entering a non-`LadderBlock` climbable |
| `swimCostEnter` / `swimCost` | +24 / +4 | first swim node / each swim node |
| `divingCost` | +4 | water above |
| `MAX_COST` | 25 | caps `maxCost` used to grow the node limit |
| `turnPenalty` | 0 | off by default |

Random-walk jobs override these to toggle 1, jump 1, drop 1 and `canDrop = false` [9e].

### Following (`navigation/MinecoloniesAdvancedPathNavigate`, `MovementHandler`)
- It replaces the entity's `moveControl` with `MovementHandler`. `canUpdatePath()` always returns true. `getTempMobPos()` is the real position. `createPath` returns null (only async). Vanilla `doStuckDetection` and `recomputePath` do nothing [9e].
- **Per tick:**
  1. Every 10 ticks: `stuckHandler.checkStuck`.
  2. Wait while the job is computing, then `moveTo(path)`.
  3. `handleLadders()` (below).
  4. 1 tick in 20, check the floor: path blocks set speed ×`ON_PATH_SPEED_MULTIPLIER = 1.3`.
  5. Rails.
  6. Custom `followThePath()`:
     - `maxDistanceToWaypoint = 0.5`.
     - It looks ahead 4 nodes and advances if |dx|, |dz| < 0.5 − 0.1·|dy| and |dy| ≤ 1.
     - If ≥ 2 blocks from both the current and the next node, it rewinds to a node within 1.
  7. `setWantedPosition` toward `getNextEntityPos` with `getSmartGroundY`, which aims at the top of the collision shape in or below the node; doors, gates and trapdoors keep the raw y.
  8. Speed drops to 0.6 on y-changing turns where perpendicular momentum is larger [9e].
- **Ladder handling:**
  - Entering (previous node not a ladder, or the same y): walk to the ladder block centre plus 0.1 toward `ladderFacing`, at speed 0.4, with `xxa = zza = 0`. Advance when within 0.2 in x and z.
  - Climbing: advance when within 0.5 horizontally and past the node's y. `doLadderMovement()` handles the rest:
    - Up (facing N/S/E/W/UP): target = next node + (0, 1, ±0.8 into the wall) at speed 0.5. If not inside a `LadderBlock` (vines), set `deltaMovement = (0, 0.1, 0)`.
    - Down (facing `DOWN`): speed 0, sneak, target at speed 0.2, `yya = −0.5`.
  - The bottom node of a downward ladder isn't advanced until `y − node.y < 0.001` [9e].
- **`MovementHandler`:**
  - Same as vanilla `MOVE_TO`, but it jumps if `(dy > stepHeight || dy > 0 && !onGround) && dx²+dz² < max(1, width)`, or on a shape that isn't a door, fence, fence gate **or ladder**.
  - In water it adds `speed·((dy+0.3)/dist)·0.1` to the y velocity.
  - `JUMPING` ends when on the ground or in liquid.
  - `setWantedPosition` re-enters `MOVE_TO` if the target changed or after more than 20 jumping ticks. This fixes the vanilla stuck-in-`JUMPING` trap [9k].
- **Doors** are opened by the goal `EntityAIInteractToggleAble` (fence gate, trapdoor, door), not by the navigator [11][12]:
  - It checks the next `LENGTH_TO_CHECK = 2` path nodes and a height of 2 or more.
  - It opens within distance² `MIN_DISTANCE = 4` and closes beyond distance² `MAX_DISTANCE = 5`.
  - It runs again after random 0–40+ ticks, or after a 10-tick delay on horizontal collision.

### Stuck handler (`navigation/PathingStuckHandler`) [9l]
- Citizens use `createStuckHandler().withTeleportOnFullStuck().withTeleportSteps(5)` [11]. They take no damage, break no blocks and place no ladders. The navigator's own default is `withTakeDamageOnStuck(0.2f).withTeleportSteps(6).withTeleportOnFullStuck()` [9e].
- `checkStuck` runs every 10 ticks. "Stuck" means: no path for two checks in a row, or a path whose `nextNodeIndex` didn't change.
  - Progress of more than 5 nodes resets the stuck level.
  - Being within `MIN_TARGET_DIST = 3` of the goal resets everything.
- Escalation (each action has a delay, starting at `delayBeforeActions = 100` ticks, decremented by 10 per check):
  1. `stuckLevel` starts at −5. For levels −5..−1, skip ahead one node (delay 30).
  2. At level 0: dismount and `recalc()` (delay 200).
  3. Then each level increments, with a 1-in-6 chance of dropping back 2 (minimum 1):
     - **Level 1, or 3–8 at 50 %** (when it can't break or place): walk 20–39 blocks in a rotating direction, pausing `range·TICKS_PER_BLOCK (7)` ticks.
     - **Level 2:** **teleport** `teleportRange` (5) nodes ahead on the path (delay 200).
     - **Levels 3–5:** place ladders or leaf bridges (if enabled).
     - **Levels 6–8:** break blocks (if enabled).
     - **Level 9:** `completeStuckAction`.
- **Global timeout:** while the destination is unchanged, `globalTimeout += 10` per check. Once it passes `MIN_TP_DELAY = 120·20` ticks **and** `timePerBlockDistance (200) × max(10, manhattan distance)`, `completeStuckAction` runs. With `canTeleportGoal`, it teleports to a spot within 10 of the goal with a `WALKABLE` floor and two `DROPABLE` blocks above. Optional damage and block breaking follow, then `stop()` [9l].

## 5. Licensing

- Repo `LICENSE` on `version/main`: GNU GPL v3 [15a].
- `src/main/resources/assets/minecolonies/LICENSE` on `version/main`: the entire file is "All Rights Reserved" [15b].
- If MineCP adopts GPL-3.0, it may derive from MineColonies **code**. AGENTS.md still says to write our own code, so this doc only describes rules and numbers. Textures, models, sounds and lang files under `assets/` stay ARR and **can't be reused under any license choice**. Blueprints are outside `assets/` and fall under the repo's GPL (see feasibility doc).

## 6. Proposed design for MineCP

### Modules
- **`path/Searcher`**: pure Paper API, runs async.
  - Input: start, goal and options, plus a `Snapshots` view (a `Map<chunkKey, ChunkSnapshot>`) and the shared shape cache.
  - Output: `List<Step(x, y, z, Kind)>`, `reached`, `visited`.
  - No Bukkit calls except `ChunkSnapshot#getBlockData` and `BlockData`/`Tag` reads.
  - Easy to unit-test with fake snapshots.
- **`path/Snapshots`**, main thread:
  - Takes `getChunkSnapshot(false,false,false,false)` for the box around start and goal (the mod's box: ±(32 + manhattan/2)), and only for loaded chunks.
  - Caches each snapshot per chunk for N ticks (e.g. 40) and drops it on `BlockPlace`/`BlockBreak`/`BlockPhysics` in that chunk.
  - Missing chunk = no floor, as in the mod.
- **`Nms`** (the only NMS class; uses paperweight):
  - `drive(Mob legs, x, y, z, speed)`: `setWait()` + `setWantedPosition`.
  - `jump(legs)`: `getJumpControl().jump()`.
  - `stopVanilla(legs)`: `getNavigation().stop()`.
  - `sleep(LivingEntity, Block)` / `wake(LivingEntity)`: `startSleeping(BlockPos)` / `stopSleeping()`. This moves the reflection out of `Colonist.sleepHandle` [16].
  - It doesn't need `Path`/`Node` at all. Handing a vanilla `Path` to `PathNavigation.moveTo` would bring back `canUpdatePath`'s ladder blindness and vanilla's 100-tick stuck stop. Keep that as plan B only.
- **`Colonist`** keeps the Mannequin-follows-legs loop and gains a `Follower`, which holds the steps and an index. `walk`, `walkBeside`, `approach`, `goBeside`, `viaLadder`, `ladders()` and `Miner.climb` collapse into "request a path, follow it".

### Node kinds and starting costs (mod values [9c][9j], adapted)

| Kind | Rule | Cost |
|---|---|---|
| WALK | floor `WALKABLE`, 2 blocks of room by shape | 1 (+0.1 random); ×1/6 on path blocks |
| STEP/JUMP | rise < 1.3 by shape tops (slab, stair 0.5 counts as a step) | +2 (none for ±1 onto stairs) |
| DROP | fall 2..10 onto `WALKABLE`, clear in between | +1·dy³ |
| LADDER | `Tag.CLIMBABLE`; ladders always, vines/others behind a research flag | ladder→ladder: 1; enter non-ladder climbable +3 |
| DOOR / GATE / TRAPDOOR | wooden `Openable` passable by facing rules; iron doors blocked | +2 |
| SWIM | water at feet; head must be air (no diving at first) | +24 enter, +4 per node |
| wrong-facing block | across a stair/door `FACING` | +5 |

- Limits: `MAX_NODES = 8000` and the mod's corridor formula.
- Destination slack is √2 when the goal can't be stood in.
- Rails: skip for now and record in `docs/parity.md`.

### Executor, tick by tick (main thread, in `Colonist.tick()` or a Paper `Goal` on the legs)
1. Call `Nms.stopVanilla` once, when the follower takes over.
2. Advance like the mod: look ahead 4 steps and advance while |dx|, |dz| < 0.5 − 0.1·|dy| and |dy| ≤ 1. If ≥ 2 blocks from both the current and next step, rewind to a step within 1.
3. Ordinary step: `Nms.drive(target.x+.5, groundY(target), target.z+.5, WALK_SPEED)` every tick. `groundY` is the shape-top rule (doors, gates and trapdoors keep raw y). MoveControl's own jump rule handles steps.
4. Doors: before entering a DOOR step within 2 blocks, open it (existing `doors()` code). Close it once more than √5 behind.
5. Ladder up:
   - Centre on the ladder column (within 0.2, offset 0.1 toward its `FACING`, speed 0.4).
   - Then each tick, `drive` toward the column + 0.8 into the wall at the next step's y + 1, speed 0.5, **and** `Nms.jump()`. Pushing into the wall gives `horizontalCollision`, the jump flag covers vines and open ladders, and vanilla lifts 0.2 per tick.
   - Advance when y ≥ step.y. At the top, the next WALK step pulls it off.
6. Ladder down: centre in the column and `drive` to the column centre at speed 0.2 without pushing into the wall. Vanilla slides it down at 0.15 per tick. Hold the bottom step until `y − step.y < 0.001`.
7. Only climb by moving the mob directly if physics fails: `setVelocity(0, 0.2, 0)` (API) for an open trapdoor at the top, a missing wall, or the like.

### Stuck escalation before teleport (mod order, shortened)

Check every 10 ticks. "No progress" means the step index didn't change and the mob moved less than 0.5 blocks.

1. After 100 ticks: skip one step, up to 5 times, every 30 ticks.
2. Repath from the current position.
3. Walk 20–39 blocks in a rotating direction, then repath.
4. Teleport 5 steps ahead along the path. That beats `teleportNear` because it stays on the route.
5. `teleportNear(goal)`. Also do this when the global timeout passes max(2400, 200 × distance) ticks.

No damage and no block breaking for citizens, matching the mod's citizen setup [11].

### Thread handoff
- **Main thread:**
  1. `Snapshots.collect(box)`.
  2. Build an immutable `Request`.
  3. Submit it to one daemon `Executors.newSingleThreadExecutor()` (the mod uses one thread [9a]), with a bounded queue and one request per colonist (cancel the old one).
- **Worker:** runs A* on the snapshots and checks `Thread.interrupted()`.
- **Back on main:** `Bukkit.getScheduler().runTask(plugin, () -> colonist.accept(result, requestId))`. Drop the result if the request id is stale or the colonist despawned.
- Keep the mod's backoff on failure: pause 10 ticks, then +10 per failure, +50 if the node limit was hit [9e].
- Shut the executor down in `onDisable`.

### Risks
- **NMS breaks on MC updates.** About 4 drops a year. The `Nms` surface is small (`MoveControl`, `JumpControl`, `PathNavigation.stop`, `start/stopSleeping`), and names are Mojang-stable but can change. Re-run `javap` on each new jar.
- **Snapshot cost.** A long path (100 blocks) needs about 7×7 to 10×10 chunks. Cache snapshots and only re-snapshot dirty chunks. Measure on the scratch server (**UNVERIFIED** numbers).
- **Stale data.**
  - Blocks change mid-search or mid-walk. The executor must re-check the next 2 steps against live blocks on the main thread, and repath when a step became solid.
  - A chunk can unload mid-search. The snapshot stays valid, but the path may lead into an unloaded chunk where the legs freeze. Cut the path at the first step in a chunk that isn't entity-ticking, and repath on arrival.
- **Shape cache off-thread.** `getCollisionShape(Location)` touches `ServerLevel` (**UNVERIFIED** safety). If it misbehaves, fill the cache on the main thread for each new `BlockData`.
- **Entity activation range.** The legs must stay "active" for `serverAiStep` to run `moveControl`. Keep the current potion-effect trick [16].
- **Licensing.** Reimplement from these rules; never paste mod code (AGENTS.md).

## Sources

1. PaperMC docs, paperweight-userdev — https://docs.papermc.io/paper/dev/userdev/
2. paperweight releases (v2.0.0-beta.24, 2026-09-25; "require Gradle 9.7.1 or newer") — https://github.com/PaperMC/paperweight/releases/tag/v2.0.0-beta.24
3. paperweight source: (a) `ReobfArtifactConfiguration.kt` — https://github.com/PaperMC/paperweight/blob/main/paperweight-userdev/src/main/kotlin/io/papermc/paperweight/userdev/ReobfArtifactConfiguration.kt ; (b) `PaperweightUserExtension.kt` (default `MOJANG_PRODUCTION`) — https://github.com/PaperMC/paperweight/blob/main/paperweight-userdev/src/main/kotlin/io/papermc/paperweight/userdev/PaperweightUserExtension.kt ; (c) `PaperweightUser.kt` (`decorateJarManifests`) — https://github.com/PaperMC/paperweight/blob/main/paperweight-userdev/src/main/kotlin/io/papermc/paperweight/userdev/PaperweightUser.kt ; (d) `constants.kt` (`MAPPINGS_NAMESPACE_MANIFEST_KEY = "paperweight-mappings-namespace"`, `DEOBF_NAMESPACE = "mojang"`) — https://github.com/PaperMC/paperweight/blob/main/paperweight-lib/src/main/kotlin/io/papermc/paperweight/util/constants/constants.kt
4. Paper Maven, dev-bundle metadata (latest `26.3.build.253-stable`) — https://repo.papermc.io/repository/maven-public/io/papermc/paper/dev-bundle/maven-metadata.xml
5. run-task / run-paper `RunPaperPlugin.kt` (paperweight compat) — https://github.com/jpenilla/run-task/blob/master/plugin/src/main/kotlin/xyz/jpenilla/runpaper/RunPaperPlugin.kt
6. Paper 26.3 server jar (`Implementation-Version: 26.3-253-63e41db`), local `run/versions/26.3/paper-26.3.jar`, inspected with `javap -p -c -constants`: `MoveControl`, `MoveControl$Operation`, `JumpControl`, `PathNavigation`, `GroundPathNavigation`, `Path`, `Node`, `PathType`, `LivingEntity`, `Entity`, `Mob`, `WanderingTrader`, `OpenDoorGoal`, `CraftBlockData`, `CraftChunk`, `CraftChunkSnapshot`, `PaperPathfinder`
7. paper-api `26.3.build.249-stable` jar (local Gradle cache), inspected with `javap`: `Pathfinder`, `Pathfinder$PathResult`, `ChunkSnapshot`, `Chunk`, `World`, `BlockData`, `Block`, `VoxelShape`, `Mob`, `LivingEntity`, `Entity`, `HumanEntity`, `Mannequin`, `com.destroystokyo.paper.entity.ai.*`
8. Paper Javadoc: `ChunkSnapshot` — https://jd.papermc.io/paper/org/bukkit/ChunkSnapshot.html ; `Chunk#getChunkSnapshot` — https://jd.papermc.io/paper/org/bukkit/Chunk.html
9. MineColonies `version/main`, `src/main/java/com/minecolonies/core/entity/pathfinding/` — https://github.com/ldtteam/minecolonies/tree/version/main/src/main/java/com/minecolonies/core/entity/pathfinding : (a) `Pathfinding.java`; (b) `PathfindingUtils.java`; (c) `pathjobs/AbstractPathJob.java`; (d) `PathPointExtended.java`; (e) `navigation/MinecoloniesAdvancedPathNavigate.java`; (f) `pathjobs/PathJobMoveToLocation.java`; (g) `world/ChunkCache.java`; (h) `SurfaceType.java`; (i) `pathresults/PathResult.java`; (j) `PathingOptions.java`; (k) `navigation/MovementHandler.java`; (l) `navigation/PathingStuckHandler.java`
10. MineColonies `api/util/constant/PathingConstants.java` (`MAX_JUMP_HEIGHT = 1.3`, `HALF_A_BLOCK = 0.5`) — https://github.com/ldtteam/minecolonies/blob/version/main/src/main/java/com/minecolonies/api/util/constant/PathingConstants.java
11. MineColonies citizen setup: `api/entity/citizen/AbstractEntityCitizen.java` (stuck handler, doors) — https://github.com/ldtteam/minecolonies/blob/version/main/src/main/java/com/minecolonies/api/entity/citizen/AbstractEntityCitizen.java ; `core/entity/citizen/EntityCitizen.java` (goals; `canClimbVines`/`canPathOnRails` via research `VINES`/`RAILS`) — https://github.com/ldtteam/minecolonies/blob/version/main/src/main/java/com/minecolonies/core/entity/citizen/EntityCitizen.java ; `core/colony/CitizenData.java` — https://github.com/ldtteam/minecolonies/blob/version/main/src/main/java/com/minecolonies/core/colony/CitizenData.java
12. MineColonies `core/entity/ai/minimal/EntityAIInteractToggleAble.java` — https://github.com/ldtteam/minecolonies/blob/version/main/src/main/java/com/minecolonies/core/entity/ai/minimal/EntityAIInteractToggleAble.java
13. MineColonies `api/configuration/ServerConfiguration.java` (`minimumrailstopath` 8, `pathNodeLimitMultiplier` 1) — https://github.com/ldtteam/minecolonies/blob/version/main/src/main/java/com/minecolonies/api/configuration/ServerConfiguration.java
14. MineColonies tag `pathblocks.json` — https://github.com/ldtteam/minecolonies/blob/version/main/src/datagen/generated/minecolonies/data/minecolonies/tags/blocks/pathblocks.json
15. (a) MineColonies `LICENSE` (GPL-3.0) — https://github.com/ldtteam/minecolonies/blob/version/main/LICENSE ; (b) `src/main/resources/assets/minecolonies/LICENSE` ("All Rights Reserved") — https://github.com/ldtteam/minecolonies/blob/version/main/src/main/resources/assets/minecolonies/LICENSE
16. MineCP source: `src/main/java/dev/minecp/citizen/Colonist.java`, `src/main/java/dev/minecp/job/Miner.java`, `build.gradle.kts`
