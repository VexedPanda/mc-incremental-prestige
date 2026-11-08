# Mc-incremental Prestige

The Prestige mod for [Mc-incremental](https://github.com/VexedPanda/Mc-incremental), a Minecraft modpack played as an incremental game. Each Run is confined to a small Run area of an ordinary world. A Prestige ends the Run, converts everything the players built into EMC, and starts a fresh Run somewhere new. The world keeps its Knowledge and EMC balance.

NeoForge 1.21.1. It requires [ProjectE](https://www.curseforge.com/minecraft/mc-mods/projecte); GregTech CEu is optional.

## Features

- **Run areas:** each Run is a 3×3-chunk area (configurable) held in by the vanilla world border. Areas are far apart, never centred on an ocean, and give a safe spawn. The first Run starts when the world is created.
- **Prestige:** start it with the Prestige item (crafted from one dirt, sand or cobblestone; use it, then sneak-use it within 5 s to confirm) or `/prestige` (op). The Prestige sweep converts everything players added to the area, at full EMC, then 3 Candidate areas are offered on a selection screen (see below):
  - blocks, including multiblocks;
  - machine, chest and tank contents, drained before any block is removed;
  - placed fluids, dropped items, minecarts and item frames;
  - ender chests;
  - every online player's inventory: main inventory, armour, offhand, crafting grid, the stack on the cursor and the slots of an open menu (wiped before the starting items are given).
- **Candidate areas:** after the sweep, every player sees a selection screen with one row of 3 cards per dimension; clicking a card, or `/prestige choose <1-3>` (op), chooses where the next Run goes. The first choice starts the Run there for everyone, with the starting items. Each candidate is listed with a preview read from its pre-generated chunks: the biome at its middle, its GT ore veins (with the ores found) and its structures, on the screen and in chat. The screen closes for everyone once a choice is made, and reopens if closed before. Until then every player is frozen and invulnerable, including players who join, and Prestiging again re-rolls the 3 candidates without sweeping again. The choice survives a restart. See [`docs/candidate-areas.md`](docs/candidate-areas.md).
- **Portals do nothing during a Run:** a Run stays in its dimension, so lighting a Nether portal frame fails and no portal (Nether, End, End gateway) moves players, mobs or items. Prestige's own teleport still works from any dimension.
- **World-global EMC and Knowledge:** shared by every player, with no owners. Players who were offline during a Prestige have their items converted to EMC when they next join, and are moved into the new Run (by the choice, if they join before it).
- **Discovery:** a resource with EMC enters the world's Knowledge on its own, with no Transmutation Table step, and everyone is told in chat: when a player picks it up, crafts, smelts or trades for it, when an EMC intake or the Prestige sweep converts it, or when a scan every 30 s finds it in a player's inventory or in a container or tank in the Run area's loaded chunks. Anything with no EMC (water) is never discovered.
- **EMC barrel:** a block linked to the world's EMC balance. It hands out one configured learned item or fluid, paid for from the balance; it only outputs.
- **EMC intake:** the way into EMC. Any item or fluid with an EMC value that is piped in, or right-clicked in (a whole held stack, or a bucket emptied), is converted at its full value into the world's balance and discovered. Items and fluids with no value (water) are refused and stay in the pipe. Crafted from a hopper and a redstone dust.
- **Starting items:** every Run, and every join, makes sure players hold the configured starting items (by default a Transmutation Tablet).
- **Singleplayer performance:** the simulation distance is capped to the Run area.
- **GregTech EMC values:** when GregTech is installed, a ProjectE EMC mapper values GT items from their GT machine recipes, recycling and decomposition included (inputs' cost, fluids at their `fluid_emc` values; an unlisted fluid, such as a molten metal, at the value its own GT recipes give it), so machines, circuits and parts can be transmuted, and the Prestige sweep converts them back into EMC. Each recipe's cost is split across all its outputs (chanced ones at their expected amount), so its outputs are worth no more than its inputs: outputs valued elsewhere keep their value and the rest goes to the outputs the recipe values, in fixed proportions found before ProjectE's mapping (see [`docs/gt-recipe-emc.md`](docs/gt-recipe-emc.md), including what still makes EMC). Leaf materials no recipe makes (sulfur, titanium, ...) still need a price in ProjectE's `custom_emc.json`. A recipe's EU and machine time are added to its cost at built-in per-tier rates (time: 4 EMC per machine-second at LV, doubling each tier), and crafting-table recipes that use a GT tool are mapped too, with the tool not consumed; a machine route cheaper than such a craft (the lathe making a rod and small dusts) then sets the price, and so does a recipe making an output for less than another route values it at (desulfurizing a fuel that a distillery prices high). ProjectE's `config/ProjectE/mapping.toml` can turn it off (`mappers.GTRecipeMapper.enabled`).

Design notes: [`docs/prestige-sweep.md`](docs/prestige-sweep.md), [`docs/candidate-areas.md`](docs/candidate-areas.md), [`docs/gt-recipe-emc.md`](docs/gt-recipe-emc.md), [`docs/emc-barrel.md`](docs/emc-barrel.md), [`docs/emc-intake.md`](docs/emc-intake.md), [`docs/discovery.md`](docs/discovery.md), [`docs/simulation-distance.md`](docs/simulation-distance.md), [`docs/run-dimension.md`](docs/run-dimension.md). The vocabulary and design decisions live in the pack repo's `GLOSSARY.md` and `docs/adr/`.

## Configuration

| File | Key | Default | Meaning |
|---|---|---|---|
| `config/mcincremental_prestige-common.toml` | `startingItems` | `projecte:transmutation_tablet` | Items every player is given at each Run start and join. Give each 0 EMC (ProjectE's `custom_emc.json`): a Prestige converts them with the rest of the inventory, then hands out new ones |
| `<world>/serverconfig/mcincremental_prestige-server.toml` | `runArea.radiusChunks` | 1 (3×3) | Run area radius in chunks |
| | `runArea.minDistanceChunks` | 64 | Minimum distance between Run areas, and between Candidate areas |
| | `runArea.sweepMarginChunks` | 1 | Extra chunks around the area that the sweep covers |
| | `runArea.avoidOceans` | true | Skip Candidate areas centred on an ocean |

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
