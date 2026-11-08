# Discovery

Pack `GLOSSARY.md`: **Discovery**; decision: pack ADR-0009; ticket VexedPanda/Mc-incremental#76 (spec #74). A resource with an EMC value enters the world's Knowledge as soon as anything in the world produces it or a player holds it, with no manual Transmutation Table step. Everything goes through `discovery.Discovery`, which calls `emc.WorldEmc.learn`/`learnFluid`.

## Rules

- Only resources with EMC: items with a ProjectE value (`IEMCProxy.hasValue`), fluids with a fluid EMC value (`emc.FluidEmc.hasValue`). Water and bedrock are never discovered.
- Items are discovered as ProjectE learns them (persistent components only); fluids as their source fluid.
- Each new discovery is announced in chat to every player (`mcincremental_prestige.discovery.discovered`) and logged. Something already known is not announced again.
- Dimension gates don't limit Discovery: holding an item is always enough (ADR-0009).

## Triggers

- **Inventory gains**, immediately: picking up an item entity (`ItemEntityPickupEvent.Post`), taking a crafting result (`PlayerEvent.ItemCraftedEvent`), taking a furnace result (`PlayerEvent.ItemSmeltedEvent`) and trading with a villager or wandering trader (`TradeWithVillagerEvent`).
- **EMC intake**: everything it converts ([`emc-intake.md`](emc-intake.md)).
- **Prestige sweep** and the inventory and ender chest conversion: `sweep.EmcCredit` records every item and fluid it is given (container and tank contents, placed blocks by their item form, dropped items, a GT recipe's consumed inputs, player inventories) and `creditTo` discovers them while crediting. So everything left in the Run area is discovered by Prestige at the latest. Entity contents (a chest minecart's items, an armor stand's armour) are only credited, not discovered.
- **Periodic scan**, every 30 s (`Discovery.SCAN_INTERVAL_TICKS`, a constant):
  - every online player's inventory, however the items got there (commands, containers, anything without an event);
  - the item and fluid handlers of every block entity in the current Run area (`run.RunAreas.current`).

## Scan bounds

- Only the current Run area's own chunks (centre ± `radiusChunks`, overworld), not the sweep margin, and only chunks already loaded (`getChunkNow`): the scan never loads or generates a chunk. No Run yet: only players are scanned.
- Read only: slots and tanks are read, never extracted.
- Skipped: EMC barrels and intakes (their handlers are the world balance), and containers with an unrolled loot table (reading them would roll the loot).
- Each block entity's unsided handler is read; a block with none is read through each side's handler.

## Tests

`gametest.DiscoveryGameTests`, through the world's Knowledge: pickup, crafting, the sweep (chest contents, a placed block, a dropped item, a lava source), the scan (a chest in the Run area and not one outside it, a GT steel drum of lava but not one of water, a held item within one scan interval), and a single announcement with nothing worthless discovered. `WorldEmc.forget` lets a test start with a resource unknown.
