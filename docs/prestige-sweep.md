# Prestige sweep: implementation notes

Design decision: pack repo ADR-0007 (diff against a snapshot taken at Run start). Research was done against NeoForge 1.21.1 and decompiled vanilla 1.21.1.

## Run start

1. Pick the Run area; force-load it plus the sweep margin (server config `runArea.sweepMarginChunks`, default 1: 5×5 around the default 3×3) with a ticket.
2. Wait until the 3×3 is **ticking** (`ServerChunkCache.isPositionTicking`), not just FULL: `postProcessGeneration()` (fence shapes, spring fluid ticks) runs in `ChunkMap.prepareTickingChunk`.
3. Snapshot, then teleport the player.

## Snapshot

- Per section: `section.getStates().copy()`, encoded with `PalettedContainer.codecRW(Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState())` (stores names, not registry ids). Skip biomes.
- Record UUIDs of entities already in the box (chest minecarts, item frames).
- One `SavedData`, overwritten each Run start and cleared after the sweep; must survive restarts mid-Run.
- Cover the 5×5: fluids (`FlowingFluid.canSpreadTo` has no border check), explosions and thrown items can reach past the border.

## Sweep

- Per section: if palette and raw `long[]` are identical, skip.
- Otherwise compare per block; `live.getBlock() == snap.getBlock()` counts as unchanged (ignores fence connections, crop age, redstone power, leaf distance).
- Act on a changed position only if it has EMC value > 0, a block entity, or a fluid source.
- Value: item form first (`asItem()` / clone stack) for full value incl. hand-made GT EMC; fall back to `getDrops`. ProjectE `IEMCProxy.getValue`. Fluids: bucket value minus empty bucket per 1000 mB, or a custom map for GT fluids.
- Block entities (all in the box): drain item and fluid handlers and add their EMC. **Skip** `RandomizableContainerBlockEntity` with `getLootTable() != null` (reading would roll loot). Remove the block only if its Block differs from the snapshot. Nested containers (shulkers, cells): test how ProjectE values them.
- Removal order: drain inventories, `removeBlockEntity`, then `setBlock(AIR, UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE)` to avoid neighbour cascades and container drops.
- Entities last: every `ItemEntity` in the box, plus value-holding entities not in the snapshot's UUID list.

## Costs (synthetic benchmark)

9 chunks: copy ~0.3 ms, diff ~0.1 ms typical / ~5 ms worst; ~0.5 MB raw, est. 50–300 KB gzipped on disk.

## Later, only if profiling demands

A `LevelChunk.setBlockState` mixin setting dirty-section bits as a pre-filter, never as the source of truth.

## Implementation (#29)

- `sweep.RunSnapshots.take(server, area)`: Run start, called by `controller.Prestige.run` after `RunAreas.beginRun`. Adds a region ticket, drives chunk loading on the server thread until the area plus margin (5×5 by default) is ticking (60 s timeout), copies every section, removes the ticket. The snapshot lives in one `SavedData` (`mcincremental_prestige_run_snapshot`), saved with the vanilla section codec.
- `sweep.PrestigeSweep.sweep(server)`: phases collect → drain all block entities (#30) → value → remove → entities (#31), then clears the snapshot. Returns a `sweep.EmcCredit`; `Prestige.run` credits it (see #30 below). It sweeps the Run area plus the margin the snapshot was taken with (`RunAreaConfig.sweepMarginChunks`, default 1).
- Fluid sources (#31): after remove, a pass over the changed positions credits each one still holding a valued fluid source (1000 mB into the EmcCredit) and removes it, e.g. lava leaked into the margin. It reads the live world, so anything an earlier phase already removed is not credited twice; since #30 the value phase already takes the valued sources it sees, so this pass is a backstop.
- Entities (#31): `RunSnapshots.take` records the UUIDs of every non-player entity in the snapshot's chunks (from `level.getAllEntities()`, while the chunks are held ticking: the level's entity getters only see accessible entity sections), saved with the snapshot. The sweep's last phase credits and discards every `ItemEntity` in those chunks, whenever it appeared; any other entity not in the UUID list is credited at its item form (`getPickResult`; an item frame's is its framed item, so the frame is valued by type) plus contents (`Container` slots, armor stand equipment, else the NeoForge entity item handler) and discarded if that is worth anything. Players and mobs are never swept (armor stands are), nor container entities with an unrolled loot table. A container entity is emptied before `discard()`: `DISCARDED` counts as destroyed, so a chest minecart or chest boat would spill its contents.
- The entity phase holds the swept chunks ticking (`RunSnapshots.whileTicking`) so entities still in memory are visible, but entities of a chunk unloaded to disk load asynchronously and are missed by that Prestige.
- No fast path for identical sections beyond skipping all-air live sections: the per-block compare costs a few ms for 9 chunks.
- ProjectE's `OreBlacklistMapper` (on by default, a ProjectE mapping config option) sets everything in `c:ores` to 0 EMC, overriding even `/projecte setemc`, so placed ores are never swept. GT ore EMC needs that mapper turned off in the pack.

## Inventories, tanks and fluids (#30)

- **EmcCredit** (`sweep.EmcCredit`): collects whole EMC (items and blocks at ProjectE's value × count) and fluid mB; `creditTo(server)` pays items through `WorldEmc.addEmc` and fluids through `WorldEmc.addFluidEmc` (exact fractions). `Prestige.run` adds every online player's ender chest to the sweep's EmcCredit, empties it, then credits once; `MissedPrestige` uses the same class for a returning player's inventory and ender chest, so there is one credit path and nothing is counted twice. The player's own inventory is wiped, not credited, in a Prestige (only a player who missed one is credited). Offline players' ender chests are credited when they rejoin (`MissedPrestige`).
- **Drain** (`sweep.SweptContents`), phase 2, before anything is removed: every block entity in the swept chunks, changed or not, plus every changed position without one (a lava cauldron is a fluid handler with no block entity). Only NeoForge capabilities (`Capabilities.ItemHandler.BLOCK`, `FluidHandler.BLOCK`, unsided then each side), so it covers any mod without depending on it. Per slot: `extractItem` until empty; whatever a handler won't hand out (a GT input bus or input hatch only accepts from outside, a furnace's fuel slot) is cleared with `IItemHandlerModifiable.setStackInSlot` and credited. Fluids with an EMC value are drained; fluids with none are left alone. What still can't be taken out is credited only if the block is removed (it goes with the block entity), counted once from the unsided handler.
- **Skipped**: `RandomizableContainerBlockEntity` with a loot table (reading it rolls the loot; the chest is kept untouched, or removed at its block value if placed during the Run) and `EmcBarrelBlockEntity` (its handlers are the world balance). Chests are read through their own block entity (`InvWrapper`), not the chest capability, which spans a double chest whose other half may be an unrolled loot chest.
- **Fluid sources**: a changed position whose fluid state is a source with an EMC value is credited 1000 mB of it and removed (also when waterlogged into a block, which is valued too). Water (no value) is ignored and kept. Flowing fluid isn't credited.
- **GregTech** (tested against the pinned GT jar, `GregTechSweepGameTests`): a formed Electric Blast Furnace is credited whole: every casing, coil, hatch and bus at its own item EMC, plus bus and hatch contents. Removal goes block entity first, so GT's `MetaMachineBlock.onRemove` never finds the machine and drops nothing, and a controller or part unloading only invalidates the structure.
- **GT recipe in progress**: GT takes a recipe's inputs out of the machine's slots and tanks when the recipe starts and records them in the machine's `RecipeLogic` (`getConsumedInputs()`; a multiblock's on its controller), which no NeoForge capability exposes. `sweep.gt.GtRecipesInProgress` reads that record through GT's API and credits it when the sweep removes the machine, for each recipe logic that is part way through a recipe (`getLastRecipe() != null` and not idle: working, waiting, or paused). GT clears the record only when the next recipe starts, so once a recipe has finished the record is stale (its inputs became the outputs, which are credited from the output slots) and is ignored. Only called when GregTech is installed (`ModList.isLoaded("gtceu")`); every GT type stays in `sweep.gt` and `emc.gt`. The outputs of the unfinished recipe were never made and aren't credited, so the sweep returns the batch as its inputs.
- **Test EMC values**: with GT loaded, ProjectE can no longer derive some vanilla values from recipes GT replaces (a diamond block from 9 diamonds, amethyst blocks, cauldrons, minecarts, item frames, armor stands, diamond armour), so `src/test/resources/data/mcincremental_prestige/pe_custom_conversions/gametest.json` pins those, and gives the GT blocks the tests use arbitrary values. The Pack will need EMC values for such items too.

## Accepted behaviours

- **A Prestige clears respawn points.** `RunAreas.teleport` resets every moved player's respawn point (`setRespawnPosition(OVERWORLD, null, ...)`), so they respawn at the new Run area's world spawn; a bed or respawn anchor from an earlier Run is forgotten, and one in the new area must be set again.
- **GregTech is a `compileOnly` and dev-runtime dependency**, needed by the GT recipe EMC mapper (#52) and for crediting a GT recipe in progress (#30). This replaces #30's constraint that GT be a test-only dependency. The mod still runs without GT: GT classes are only loaded by `emc.gt.GtRecipeEmcMapper` (which ProjectE loads only when `gtceu` is installed) and by `sweep.gt.GtRecipesInProgress` (called only when `gtceu` is loaded). GT is not published as a dependency (`localRuntime`).
- **Run areas fall back to ocean centres.** With `runArea.avoidOceans` on, a new Run area is re-picked while its centre is in an ocean; if all 1000 attempts (`RunAreas.MAX_ATTEMPTS`) are oceans, it takes an ocean-centred area anyway and logs a warning, rather than refusing the Prestige (the spawn point gets a stone block).

## Restarts (#32)

- The Run area and visited record (`mcincremental_prestige_run_areas`), the Run snapshot and the EMC balance are `SavedData` on the overworld; the world border is vanilla's, saved in `level.dat`. Nothing else is needed for a Run to survive a restart.
- `gametest.RunRestartGameTests.simulateRestart` stands in for a restart: `saveAllChunks` (forced), then removes every `mcincremental_prestige_*` key from the overworld's `DimensionDataStorage` cache (reflection) so the next read loads from disk, then re-applies the border from the level data. Vanilla's `readSavedData` logs and swallows load errors, so a broken `load` shows up as an empty snapshot (nothing swept), not an exception.
