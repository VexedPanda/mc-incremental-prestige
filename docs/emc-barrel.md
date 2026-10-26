# EMC barrel

Block `mcincremental_prestige:emc_barrel` (pack `GLOSSARY.md`: **EMC barrel**; spec VexedPanda/Mc-incremental#23, ticket #35). It belongs to the world, not a player (pack ADR-0004): every read and write goes through `emc.WorldEmc`, so it works with no player online. It only **outputs** (VexedPanda/Mc-incremental#60): nothing can be piped or put into it. The way into EMC is the EMC intake ([`emc-intake.md`](emc-intake.md)).

## Behaviour

- **Configure:** right-click it holding an item. Only an item the world knows (Knowledge) that has an EMC value is accepted; the held item is not used up. Right-click with an empty hand shows the current setting. The setting is saved in the block entity (`item`, as a ProjectE `ItemInfo`) and is lost if the block is broken.
- **Item handler** (`Capabilities.ItemHandler.BLOCK`, any side, one slot), see `barrel.EmcBarrelItemHandler`:
  - Slot 0 shows the configured item, as many as the world balance pays for, up to a stack.
  - Extraction hands out up to that many and debits each one's EMC value from the world balance. Simulated extraction debits nothing. When the balance can't pay for one, it returns nothing. An item the world no longer knows is not handed out.
  - Every insert is refused and the stack returned whole (`isItemValid` is false), so a hopper or pipe into the barrel keeps its items.
  - Extraction uses ProjectE's EMC value, not its sell value, like the EMC intake, so intake-then-barrel nets zero whatever `covalenceLoss` is set to.

## Recipe (placeholder)

`data/mcincremental_prestige/recipe/emc_barrel.json`: shapeless, one barrel + one redstone dust. Deliberately cheap until PoC 1 play-testing sets a real cost; the pack can override it with a datapack recipe of the same id.

## Prestige sweep caveat

The sweep drains block-entity item handlers. The barrel's handler never runs dry while the balance lasts (each extraction debits the balance and the sweep would credit it straight back), so the sweep must **skip the EMC barrel's item and fluid handlers** (`instanceof EmcBarrelBlockEntity`) and only value the block itself; the same holds for its fluid tank. It skips the EMC intake's handlers too (they hold nothing).

## Fluids (#36)

- **Fluid EMC values** (`emc.FluidEmc`): explicit, never derived from buckets (pack `docs/pocs/poc-1.md`). Read from every datapack file `<namespace>:fluid_emc/*.json`, format `{"comment": "...", "emc_per_1000_mb": {"<source fluid id>": <long>}}`; the Pack ships `mcincremental_prestige:fluid_emc/fluids.json`. Files are merged (sorted by id; a fluid listed twice takes the later file's value, with a warning); a same-id file in a higher datapack replaces a lower one. An unlisted fluid is worth the value ProjectE derives for it from recipes (with GregTech, the GT recipe EMC mapper of #52 values a molten metal from the cheapest GT recipe that makes it, such as a fluid extractor melting its dust; `FluidEmc.derivedValueKey` is where it publishes that value, rounded down to a whole EMC per 1000 mB), so a tank or the barrel is worth what a recipe costs it at; an unlisted fluid with no derived value (water, with no GT) is worth 0. Ids of fluids that aren't installed are kept and ignored. Reloads with `/reload`. API: `valuePerBucket(Fluid)` (EMC per 1000 mB), `hasValue(Fluid)`, `milliValue(Fluid, mB)` (exact value in thousandths of an EMC), `source(Fluid)` (flowing fluids are valued and learned as their source).
- **Learned fluids:** Knowledge holds fluids too, as a separate set of source fluid ids in the world store (`WorldEmcData`, NBT `fluids`), not as bucket items: a fluid's value has nothing to do with its bucket, and some fluids have no bucket. ProjectE has no fluid Knowledge, so this set is not mirrored to players. API: `WorldEmc.learnFluid/knowsFluid/forgetFluid/knownFluids`.
- **Lossless fractions:** an mB is usually worth a fraction of an EMC (lava at 1500 per 1000 mB is 1.5 EMC/mB). The world keeps a **fractional EMC** of 0 to 999 thousandths beside its whole balance (`WorldEmcData`, NBT `fractional_milli_emc`; `WorldEmc.fractionalMilliEmc`). `WorldEmc.addFluidEmc(server, fluid, mB)` credits `mB × value` thousandths and carries whole EMC into the balance; `removeFluidEmc` debits from balance plus fraction, or changes nothing; `affordableMb` says how many mB the world pays for. So any amount can be piped into the intake or out of the barrel (no minimum transfer, no pipe stalls) and intake-then-barrel nets exactly zero. The fraction never shows in ProjectE's balance. Use these (not `addEmc`) for anything fluid, e.g. the Prestige sweep valuing tanks and placed fluids.
- **Configure:** right-click holding anything that contains a fluid (`FluidUtil.getFluidContained`: a filled bucket, a tank item). Only a fluid with a value that the world knows is accepted; the held item is not used up or emptied. A fluid container always configures its fluid, never itself as an item (so a lava bucket can't be configured as an item by right-click). Configuring a fluid clears the item and vice versa. Saved in the block entity under `fluid` (fluid id).
- **Fluid handler** (`Capabilities.FluidHandler.BLOCK`, any side, one tank), see `barrel.EmcBarrelFluidHandler`:
  - The tank shows the configured fluid, as many mB as the world balance pays for (capacity `Integer.MAX_VALUE`).
  - Draining hands out up to that and debits its exact value; simulated drains debit nothing; draining a different fluid gives nothing; an unlearned fluid is not handed out.
  - Every fill is refused (`fill` returns 0, `isFluidValid` false).
- **Tests** get their values from `src/test/resources/data/mcincremental_prestige/fluid_emc/gametest.json` (lava 1500, water unlisted). `build.gradle` adds the test source set to the mod, so dev runs and GameTests load it but the jar doesn't contain it.
