# MineCP

A Paper 26.3 plugin (plus a data pack of converted structures) that recreates MineColonies for a private server, with no client mods. Citizens are Mannequins moved by a hidden mob. The goal is feature parity with the mod: `docs/parity.md` tracks what is done and what is left.

## Before implementing a feature

1. Read the mod's source for that feature first (`ldtteam/minecolonies`, branch `version/main`; `gh api repos/ldtteam/minecolonies/contents/<path>?ref=version/main`). Find the building, its job's AI (`core/entity/ai/workers/...`), its job (`core/colony/jobs/...`) and its recipe (`src/datagen/generated/minecolonies/data/minecolonies/recipes/`).
2. Gather every constant and rule that shapes behaviour: ranges, timings, limits per building level, thresholds, item amounts, the order things happen in. Use the mod's numbers, not guesses. Say which numbers came from the mod and which didn't.
3. Note what cannot be ported cleanly to a plugin with a vanilla client: custom blocks, items, screens, entity AI, rendering. Decide on the closest stand-in, and record the difference in `docs/parity.md` under that feature.
4. Use the mod as a reference only: write our own code, never copy its code or assets. The one exception is its blueprints, converted with `tools/convert_blueprints.py`.

## Working on the code

- Check builds with `./gradlew compileJava`. Don't run `build` or `jar`: the user's `./gradlew runServer` loads `build/libs/Colonies-<version>.jar`, and overwriting it breaks their running server.
- Unit tests for code that needs no server (`src/test`): `./gradlew test`. It doesn't touch the jar.
- For headless tests, pack a separate jar from `build/classes` and `build/resources` into a scratch test server, run with a temporary self-test class behind `-Dminecp.selftest=true`, then remove the test code.
- Source packages under `dev.minecp`: the plugin itself (`MineCP`, `Commands`, `Items`, `Saves`), `colony` (colony, huts, health, day and night, saving), `citizen` (citizens, movement, food), `job` (jobs and tools), `logistics` (warehouse, racks), `build` (build orders, build tool, tape), `menu` (dialogs). Make a member `public` only when another package uses it.
- Adding a hut: one entry in `Hut.Type` (title, block, structure path, command, item lore, recipe centre, job factory, one per colony), plus a `Job` subclass for a worker hut. It overrides `tick`, and as needed `wants`/`keep` (couriers), `info`/`materials` (hut menu), `save`/`load` (its own state) and `stow`. Add its command to the usage line in `plugin.yml`.
- Don't write migration code for old saves, items or placed blocks. The plugin is still in development and data is wiped between tests.
- Structures live in the data pack at `data/minecp/structure/<style>/...`, under the `minecp:` namespace.
