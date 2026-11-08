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

## EU and machine time (#67, #71)

The pack's owner decided on #64 that time is a cost the player pays (the pack's `docs/research/time-in-emc.md`), and on
#71 that it is always counted, at rates built into the mapper (`GtRecipeEmcMapper.timeRate` and `euPrice`; there is no
config). Each GT recipe with a consumed input also costs, before the cost is split across its outputs:

- **EU:** EU per run (EU per tick, voltage × amperage, × duration in ticks) × the EU price at the recipe's tier;
- **machine time:** duration in seconds × the time rate at the recipe's tier.

The tier is the recipe's EU voltage tier (ULV when it uses no EU, such as the primitive blast furnace). The duration is
the listed one, with no overclock or parallel. Generator recipes (EU out) add neither, and a fusion recipe's start
energy isn't counted. The cost is a fake input worth it in thousandths of an EMC, so the split below treats it like any
other input.

| Tier | ULV | LV | MV | HV | EV | IV | LuV and above |
|---|---|---|---|---|---|---|---|
| Time (EMC per machine-second) | 2 | 4 | 8 | 16 | 32 | 64 | ×2 per tier (UV 512, MAX 32,768) |
| EU price (EMC per EU) | 0.006338 | 0.006338 | 0.003213 | 0.001235 | 0.0002835 | 0.0002835 | 9.765625e-05 |

**Time doubles every tier** because GT's overclock runs a recipe twice as fast per tier above its own: a recipe valued at
its own tier then earns no more per second in a faster machine than that machine's own time. **The EU price** is the
cheapest generator available by the tier, (fuel EMC + generator time × the time rate) ÷ EU made, from the pack's
`docs/research/emc-pricing/eu_price.py` (rounded down to 4 significant figures): LV and MV gas turbines, a large steel
boiler with large steam turbines at HV, the large gas turbine at EV and IV, the large plasma turbine from LuV (no
cheaper generator is modelled above). ULV takes LV's.

## Crafting with GT tools (#67)

ProjectE doesn't map crafting-table recipes that use a GT tool (the tool comes back damaged, which it can't value). With
time counted, about 1,000 of them would make items worth more than their inputs: the item's only mapped route is a timed
machine recipe, while the craft is instant. So the mapper also maps every crafting recipe with an ingredient only GT
tools match: the tool is left out (not consumed), the other ingredients are the cost (a tag as its cheapest item, as
ProjectE does), and the craft takes no time. A small tungsten fluid pipe, crafted from a tungsten plate with a wrench
and a hammer, is then worth the plate rather than an ingot extruded at HV. Skipped: special recipes, and recipes whose
other ingredients leave something behind (buckets, fluid containers).

**A cheaper machine route sets the price (#69).** A tool craft values its output in full, but a machine making it can also
make byproducts. The file craft values a rod at a whole ingot, while the lathe turns an ingot into a rod and 2 small
dusts: with the rod already worth the ingot, the lathe made half an ingot per run (RTM alloy gained about 6,100 EMC/s in
the Pack). So when a GT recipe's outputs valued elsewhere are worth more than its inputs, the ones a tool craft values
(the craft gives them their seed value; see 4 below) become the recipe's own, and share what's left once the others are
paid. The lathe then values the rod at the ingot plus its EU and time, less the 2 small dusts: about half an ingot.

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
   ProjectE's defaults and the tags they pin, listed fluids) can't be lowered: they take their value first. In any other
   gaining recipe whose first output is guaranteed (not recycling), two kinds of output are lowered, becoming its own:
   - outputs valued by a GT tool craft (#69): an instant craft ignores the byproducts a machine makes, so the machine's
     price is the fair one;
   - outputs worth more on their own than the recipe's whole cost (#83): the recipe is a cheaper route to them.
     Desulfurizing 8000 mB of sulfuric heavy fuel makes 8000 mB of heavy fuel and 1000 mB of hydrogen sulfide; in the
     Pack a distillery valued heavy fuel at 59,101 per 1000 mB, giving the whole cost of a cracked light fuel to the
     150 mB of heavy fuel in it, so desulfurizing gained about 470,000 EMC a run. Heavy fuel now takes the
     desulfurization's cost less the hydrogen sulfide.

   The rest is left as #52 had it (its first output takes the whole cost when it is that output's route): lowering
   byproducts valued by other recipes carries through to everything made from them. In the GameTest prices, lowering
   every gaining recipe's outputs halved the macerators and sent copper dust from 108 to 8 EMC (cheap inputs such as
   clay, lava or netherrack whose byproducts are priced as rare materials); lowering them until the rest is worth less
   than the inputs, largest first, still pulled the oil chain to 0 (two fractions of oil each worth half the gain) and
   lowered copper and gold through recycling. Lowering only outputs worth more than the whole cost, and never in
   recycling, keeps the macerators and circuits within 3-22% of their old values.
5. **Fixed proportions, and no loops.** The shares never change within ProjectE's mapping: taking them from the values
   being computed would let them feed on themselves. A share that lowers an output is still dangerous when that output
   leads back to one of the recipe's inputs: ProjectE walks the loop again and again and pulls everything on it to 0
   (an EBF making an ingot plus ash from a dust, the ingot macerated back into dust). So when an own output leads back
   to an input (through conversions within 5% of the seed's values), or the share takes away more than 5% of the
   cost, the output gets a fixed value (a conversion from a fake 1-EMC node) instead of a fraction of the inputs. Fixed
   values are recomputed from more private passes (each halfway to the new value, so they settle): at least 4, then
   until none moves by more than 0.05% (or 1 EMC), at most 16. Every other share is a fixed fraction of the inputs, so
   the output follows the inputs' values.
6. **What the seed can't see (#83).** The passes also re-check what the seed decided:
   - A share is a fixed fraction of the cost, while an output valued elsewhere keeps its value. When the inputs end up
     cheaper than in the seed (propene falls from about 20,000 to 4,500 per 1000 mB once its own recipes are split),
     that output (hydrochloric acid, hydrogen sulfide) is worth a larger part of the cost and the recipe gains. Shares
     are not refreshed from the passes (in the GameTest that pulled sulfuric fuels to under 1 EMC and benzene down 30
     times); instead, a recipe whose outputs would make more than 0.1% over its cost at a pass's values gets fixed
     values from that pass.
   - A recipe whose inputs have no value in the seed (they are only made as outputs the seed doesn't split, such as
     uranium 235) is split from the first pass that values them, with fixed values. The uranium 235 lathe gained about
     248,000 EMC a run in the GameTest: the file craft values the rod at a whole ingot, and the lathe was never split.

The private passes take about 7-10 s on top of the mapping itself (2-3 s before #83, with 4 passes); ProjectE's exploit warnings are silenced for them.

## What still makes EMC

The GameTest `noSplitGtRecipeMakesMoreEmcThanItConsumes` sweeps every GT recipe (the rule of the pack's
`docs/research/emc-pricing/audit.py`, with each recipe's EU and machine time added to its inputs) and fails if a recipe
split by share makes EMC. It logs the worst offenders of each kind of recipe that can still gain (GameTest prices: 0
split by share, 0 split with fixed values, 103 with pinned outputs, 341 others; before #83: 0, 5 split by share whose
inputs fell since the seed, 25, 77 and 198):

- **Split with fixed values** (loops, lathes of alloys, alloy decompositions): a fixed value can lag behind inputs that
  drop in the same mapping when its passes don't settle.
- **Pinned outputs worth more than the inputs** (ender pearl electrolysis into pinned elements, gold pipes arc-smelted
  into pinned gold ingots once their gold is cheap): only re-pricing fixes these.
- **Outputs valued elsewhere worth more than the inputs**, none of them on its own worth the whole cost (not
  decompositions; see 4 above), recycling (machines macerated or arc-smelted into more than they cost), and recipes
  whose inputs never get a value.

The sweep allows 2 EMC or 0.5% of the inputs for rounding.

## Tests

`src/test/.../GtRecipeEmcGameTests.java`, with EU and machine time always counted: the macerator tiers and circuits stay
within the research's bounds; the built-in time rate doubles every tier (ULV 2, LV 4, MAX 32,768) and the EU price is
the pack's per tier; an LV and an HV recipe total materials + EU × price + seconds × rate; the lathe and a decomposition
(potash) are worth no more than their inputs plus EU and time; a tool craft (the small tungsten fluid pipe) caps its
output, and the lathe sets the RTM alloy, tungsten steel and uranium 235 rods' values below their file crafts' (rod + 2
small dusts ≤ ingot + EU + time); desulfurizing heavy fuel, light fuel and naphtha makes no more than it costs; fluids use their explicit values (plus EU and time), unlisted fluids take their derived value; a RAM wafer costs its
cheapest engraving's wafer (a phosphorus wafer makes 4) plus EU and time, with the lens not counted;
and the sweep above.
