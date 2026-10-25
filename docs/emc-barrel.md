# EMC barrel

Block `mcincremental_prestige:emc_barrel` (pack `GLOSSARY.md`: **EMC barrel**; spec VexedPanda/Mc-incremental#23, ticket #35). It belongs to the world, not a player (pack ADR-0004): every read and write goes through `emc.WorldEmc`, so it works with no player online.

## Behaviour

- **Configure:** right-click it holding an item. Only an item the world knows (Knowledge) that has an EMC value is accepted; the held item is not used up. Right-click with an empty hand shows the current setting. The setting is saved in the block entity (`item`, as a ProjectE `ItemInfo`) and is lost if the block is broken.
- **Item handler** (`Capabilities.ItemHandler.BLOCK`, any side, one slot), see `barrel.EmcBarrelItemHandler`:
  - Slot 0 shows the configured item, as many as the world balance pays for, up to a stack.
  - Extraction hands out up to that many and debits each one's EMC value from the world balance. Simulated extraction debits nothing. When the balance can't pay for one, it returns nothing. An item the world no longer knows is not handed out.
  - Insertion of any item with an EMC value (not only the configured one) credits its EMC value times the count and learns it. Items with no EMC value are refused and returned whole (`isItemValid` is false), never voided.
  - Both directions use ProjectE's EMC value, not its sell value, so insert-then-extract nets zero whatever `covalenceLoss` is set to.

## Recipe (placeholder)

`data/mcincremental_prestige/recipe/emc_barrel.json`: shapeless, one barrel + one redstone dust. Deliberately cheap until PoC 1 play-testing sets a real cost; the pack can override it with a datapack recipe of the same id.

## Prestige sweep caveat

The sweep drains block-entity item handlers. The barrel's handler never runs dry while the balance lasts (each extraction debits the balance and the sweep would credit it straight back), so the sweep must **skip the EMC barrel's handlers** (`instanceof EmcBarrelBlockEntity`) and only value the block itself.

## Fluids (#36)

Extension points, all marked in the code:

- `EmcBarrelBlockEntity`: store a configured fluid beside the item under NBT key `fluid` (`FLUID_KEY_RESERVED`); configuring one kind should clear the other. Configuration by right-click with a filled bucket fits in `EmcBarrelBlock.useItemOn`, before the item path.
- Add an `EmcBarrelFluidHandler` next to `EmcBarrelItemHandler` (same shape: drain debits, fill credits and learns, unvalued fluids refused) and register it in `EmcBarrels.registerCapabilities` for `Capabilities.FluidHandler.BLOCK`.
- Fluid EMC values come from the mod's own map (ProjectE drops fluid values after its graph solve), so the fluid handler can't use `IEMCProxy`.
