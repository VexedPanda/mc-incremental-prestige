# EMC intake

Block `mcincremental_prestige:emc_intake` (pack `GLOSSARY.md`: **EMC intake**; ticket VexedPanda/Mc-incremental#60). The only automated way into EMC: it converts anything piped or put into it, items and fluids alike, into EMC for the world's balance, and discovers it (pack ADR-0009). Its counterpart, the EMC barrel ([`emc-barrel.md`](emc-barrel.md)), only outputs. Like the barrel it belongs to the world (pack ADR-0004): it goes through `emc.WorldEmc` and works with no player online. It has no settings and stores nothing.

## Behaviour

- **Item handler** (`Capabilities.ItemHandler.BLOCK`, any side, one always-empty slot), see `intake.EmcIntakeItemHandler`: inserting any item with an EMC value credits its ProjectE EMC value (not its sell value) times the count and learns it. Items with no EMC value are refused and returned whole (`isItemValid` false), so they stay in the hopper or pipe, never voided. Nothing can be extracted.
- **Fluid handler** (`Capabilities.FluidHandler.BLOCK`, any side, one always-empty tank of capacity `Integer.MAX_VALUE`), see `intake.EmcIntakeFluidHandler`: filling with any fluid that has a value (`emc.FluidEmc`, explicit per-fluid values) credits its exact value, fractions of an EMC kept in the world's fractional EMC, and learns the fluid. Any amount can be piped in. Fluids with no value (water) or with data components are refused (`fill` returns 0). Nothing can be drained.
- **Right-click** holding:
  - something that contains a fluid (`FluidUtil.getFluidContained`: a filled bucket, a tank item): it is emptied into the fluid handler and the player keeps the emptied container. A container of a fluid with no value is refused and stays full; it is never converted as an item, so a water bucket is not eaten.
  - any other item: the whole held stack is converted, if it has an EMC value; otherwise it stays in hand.
  - nothing: a usage message.
  
  Creative players keep what they hold.
- Values are the same ones the barrel uses, so intake-then-barrel nets exactly zero EMC.

## Recipe (placeholder)

`data/mcincremental_prestige/recipe/emc_intake.json`: shapeless, one hopper + one redstone dust, mirroring the barrel's barrel + redstone. The pack can override it with a datapack recipe of the same id.

## Prestige sweep

The sweep converts the intake like any other placed block, at the block's EMC value (from its recipe). It skips the intake's handlers (`SweptContents`), which hold nothing.
