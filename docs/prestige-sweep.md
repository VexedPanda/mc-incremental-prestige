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
- `sweep.PrestigeSweep.sweep(server)`: phases collect → drain all block entities (#30) → value → remove → entities (#31), then clears the snapshot. Returns the EMC; `Prestige.run` credits it via `WorldEmc.addEmc`. It sweeps the 3×3 only (`PrestigeSweep.MARGIN_CHUNKS = 0`) until #31.
- No fast path for identical sections beyond skipping all-air live sections: the per-block compare costs a few ms for 9 chunks.
- ProjectE's `OreBlacklistMapper` (on by default, a ProjectE mapping config option) sets everything in `c:ores` to 0 EMC, overriding even `/projecte setemc`, so placed ores are never swept. GT ore EMC needs that mapper turned off in the pack.
