# Mc-incremental Prestige

The Prestige mod for [Mc-incremental](https://github.com/VexedPanda/Mc-incremental), a Minecraft modpack played as an incremental game. Each Run is confined to a small Run area of an ordinary world. A Prestige ends the Run, converts everything the players built into EMC, and starts a fresh Run somewhere new. The world keeps its Knowledge and EMC balance.

NeoForge 1.21.1. It requires [ProjectE](https://www.curseforge.com/minecraft/mc-mods/projecte); GregTech CEu is optional.

## Features

- **Run areas:** each Run is a 3×3-chunk area (configurable) held in by the vanilla world border. Areas are far apart, never centred on an ocean, and give a safe spawn. The first Run starts when the world is created.
- **Prestige:** start it with the Prestige item (crafted from one dirt, sand or cobblestone; use it, then sneak-use it within 5 s to confirm) or `/prestige` (op). The Prestige sweep converts everything players added to the area, at full EMC, then the players move to a new area:
  - blocks, including multiblocks;
  - machine, chest and tank contents, drained before any block is removed;
  - placed fluids, dropped items, minecarts and item frames;
  - ender chests;
  - every online player's inventory: main inventory, armour, offhand, crafting grid, the stack on the cursor and the slots of an open menu (wiped before the starting items are given).
- **Portals do nothing during a Run:** a Run stays in its dimension, so lighting a Nether portal frame fails and no portal (Nether, End, End gateway) moves players, mobs or items. Prestige's own teleport still works from any dimension.
- **World-global EMC and Knowledge:** shared by every player, with no owners. Players who were offline during a Prestige have their items converted to EMC and are moved when they next join.
- **EMC barrel:** a block linked to the world's EMC balance. It hands out one configured learned item or fluid, and turns anything piped in into EMC.
- **Starting items:** every Run, and every join, makes sure players hold the configured starting items (by default a Transmutation Tablet).
- **Singleplayer performance:** the simulation distance is capped to the Run area.
- **GregTech EMC values:** when GregTech is installed, a ProjectE EMC mapper values GT items from their GT machine recipes, recycling and decomposition included (inputs' cost, fluids at their `fluid_emc` values; an unlisted fluid, such as a molten metal, at the value its own GT recipes give it), so machines, circuits and parts can be transmuted, and the Prestige sweep converts them back into EMC. Each recipe's cost is split across all its outputs (chanced ones at their expected amount), so its outputs are worth no more than its inputs: outputs valued elsewhere keep their value and the rest goes to the outputs the recipe values, in fixed proportions found before ProjectE's mapping (see [`docs/gt-recipe-emc.md`](docs/gt-recipe-emc.md), including what still makes EMC). Leaf materials no recipe makes (sulfur, titanium, ...) still need a price in ProjectE's `custom_emc.json`. ProjectE's `config/ProjectE/mapping.toml` can turn it off (`mappers.GTRecipeMapper.enabled`).

Design notes: [`docs/prestige-sweep.md`](docs/prestige-sweep.md), [`docs/gt-recipe-emc.md`](docs/gt-recipe-emc.md), [`docs/emc-barrel.md`](docs/emc-barrel.md), [`docs/simulation-distance.md`](docs/simulation-distance.md), [`docs/run-dimension.md`](docs/run-dimension.md). The vocabulary and design decisions live in the pack repo's `GLOSSARY.md` and `docs/adr/`.

## Configuration

| File | Key | Default | Meaning |
|---|---|---|---|
| `config/mcincremental_prestige-common.toml` | `startingItems` | `projecte:transmutation_tablet` | Items every player is given at each Run start and join. Give each 0 EMC (ProjectE's `custom_emc.json`): a Prestige converts them with the rest of the inventory, then hands out new ones |
| `<world>/serverconfig/mcincremental_prestige-server.toml` | `runArea.radiusChunks` | 1 (3×3) | Run area radius in chunks |
| | `runArea.minDistanceChunks` | 64 | Minimum distance between Run areas |
| | `runArea.sweepMarginChunks` | 1 | Extra chunks around the area that the sweep covers |
| | `runArea.avoidOceans` | true | Skip Run areas centred on an ocean |

Fluid EMC values are data-driven: `data/<namespace>/fluid_emc/*.json` with `{"emc_per_1000_mb": {"minecraft:lava": 64}}`.

## Building and testing

```sh
./gradlew build                 # jar in build/libs/
./gradlew runGameTestServer     # NeoForge GameTests, including a GregTech multiblock sweep
```

`runGameTestServer` first copies `src/test/config/` into `run/config/` (the Pack's 0-EMC Transmutation Tablet, which only ProjectE's `custom_emc.json` can set).

Requires Java 21. ProjectE and the pinned GregTech jar are downloaded by the build. CI runs both commands on every push and PR.

## Releases

Run the manual **Release** workflow with a `version` (e.g. `v0.2.0`) and a `ref`. It tags the ref, builds the jar and attaches it to a GitHub release, which the pack then uses.

## License

Apache-2.0.
