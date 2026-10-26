# GT recipe EMC

How the GT recipe EMC mapper (`emc/gt/`) values GregTech items from GT machine recipes (pack issues #52, #58). The
vocabulary and pricing decisions live in the pack repo (`GLOSSARY.md`, `docs/pocs/poc-1.md`, "How EMC values are made").

## What a recipe contributes

- **Inputs:** the consumed items and fluids, at their expected amount (a chanced input at its chance). Non-consumed
  inputs (programmed circuits, molds, lenses, catalysts) are left out. An item that is both input and output is netted.
- **Outputs:** every item and fluid output, chanced ones at their expected amount. Fluids are valued per 1000 mB from
  `fluid_emc` values, never from buckets; free fluids (listed at 0, and unlisted water) are left out.
- **Every GT recipe type**, recycling and decomposition included. Skipped: recipes with no consumed input, ranged
  ("int provider") amounts, fluid-container ingredients, outputs matching several items or fluids, and per-tick
  contents.

## Splitting the cost across the outputs (#58)

Each recipe's input cost is split across all its outputs, so its outputs together are worth no more than its inputs.
ProjectE's mapper only knows single-output conversions and takes the cheapest route to each item, so each output gets
its own conversion costing its share of the inputs. The shares are found before ProjectE's real mapping
(`OutputShares`):

1. **Seed.** ProjectE's value generator is run privately on what every other enabled EMC mapper adds, plus each GT
   recipe giving its whole cost to its first output (the #52 mapping; recycling and decomposition recipes, and recipes
   with no guaranteed output, add nothing here). This mapping never collapses, so it is the reference the shares are
   taken from.
2. **Own outputs.** An output is the recipe's own when the recipe is what values it in the seed: the first output when
   the recipe gives it its value (or it has none), any other output when nothing else values it. Outputs valued
   elsewhere (byproducts with a value of their own) keep that value.
3. **Shares.** The own outputs share what's left of the cost once the other outputs are paid at their values, in
   proportion to their values (an own output with no value weighs the average per unit of the others). The lathe
   turning a tungsten ingot into a rod and 2 small dusts: the small dusts are valued elsewhere (a quarter ingot each),
   so the rod gets the other half.
4. **Gaining recipes.** When the outputs valued elsewhere are already worth more than the inputs, a decomposition
   lowers them all in proportion to their values until they add up to its inputs (3 potash electrolysed into 2
   potassium: potassium can't be worth more than 1.5 potash). Outputs pinned at a fixed value (custom EMC values,
   ProjectE's defaults and the tags they pin, listed fluids) can't be lowered: they take their value first. Any other
   gaining recipe is left as #52 had it (its first output takes the whole cost when it is that output's route):
   lowering byproducts valued elsewhere carries through to everything made from them, and in the GameTest prices that
   halved the macerators and sent copper dust from 108 to 8 EMC (cheap inputs such as clay, lava or netherrack whose
   byproducts are priced as rare materials).
5. **Fixed proportions, and no loops.** The shares never change within ProjectE's mapping: taking them from the values
   being computed would let them feed on themselves. A share that lowers an output is still dangerous when that output
   leads back to one of the recipe's inputs: ProjectE walks the loop again and again and pulls everything on it to 0
   (an EBF making an ingot plus ash from a dust, the ingot macerated back into dust). So when an own output leads back
   to an input (through conversions within 5% of the seed's values), or the share takes away more than 5% of the
   cost, the output gets a fixed value (a conversion from a fake 1-EMC node) instead of a fraction of the inputs. Fixed
   values are recomputed from 4 more private passes (each halfway to the new value, so they settle); every other share
   is a fixed fraction of the inputs, so the output follows the inputs' values.

The private passes take about 2-3 s on top of the mapping itself; ProjectE's exploit warnings are silenced for them.

## What still makes EMC

The GameTest `noSplitGtRecipeMakesMoreEmcThanItConsumes` sweeps every GT recipe (the rule of the pack's
`docs/research/emc-pricing/audit.py`) and fails if a recipe split by share makes EMC. It logs the worst offenders of
each kind of recipe that can still gain (GameTest prices: 0 split by share, 31 split with fixed values, 327 with
pinned outputs, 440 others):

- **Split with fixed values** (loops, lathes of alloys, alloy decompositions): a fixed value lags behind inputs that
  drop in the same mapping, so these can gain up to a few hundred EMC (a few thousand at worst).
- **Pinned outputs worth more than the inputs** (ender pearl electrolysis into pinned elements, gold pipes arc-smelted
  into pinned gold ingots once their gold is cheap): only re-pricing fixes these.
- **Outputs valued elsewhere worth more than the inputs** (not decompositions; see 4 above), and recipes whose inputs
  have no value in the seed.

The sweep allows 2 EMC or 0.5% of the inputs for rounding.

## Tests

`src/test/.../GtRecipeEmcGameTests.java`: the macerator tiers and circuits stay within the research's bounds, the lathe
and a decomposition (potash) are balanced, fluids use their explicit values, unlisted fluids take their derived value,
and the sweep above.
