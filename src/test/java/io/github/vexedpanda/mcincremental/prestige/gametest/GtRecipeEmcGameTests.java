package io.github.vexedpanda.mcincremental.prestige.gametest;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.ingredient.IntCircuitIngredient;

import io.github.vexedpanda.mcincremental.prestige.Config;
import io.github.vexedpanda.mcincremental.prestige.McIncrementalPrestige;
import io.github.vexedpanda.mcincremental.prestige.emc.FluidEmc;
import io.github.vexedpanda.mcincremental.prestige.emc.WorldEmc;
import io.github.vexedpanda.mcincremental.prestige.emc.gt.GtRecipeEmcMapper;
import io.github.vexedpanda.mcincremental.prestige.run.RunArea;
import moze_intel.projecte.api.nss.NSSItem;
import moze_intel.projecte.api.proxy.IEMCProxy;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * EMC values derived from GregTech machine recipes (VexedPanda/Mc-incremental#52). Leaf material prices and fluid
 * values come from the GameTest-only files {@code pe_custom_conversions/gt_leaf_materials.json} and
 * {@code fluid_emc/gt_recipes.json}; everything asserted here is derived from them through GT recipes.
 */
@GameTestHolder(McIncrementalPrestige.MODID)
@PrefixGameTestTemplate(false)
public class GtRecipeEmcGameTests {
    private static final String[] TIERS = {"lv", "mv", "hv", "ev"};
    /**
     * The macerator's EMC per tier in the pack's {@code docs/research/emc-economy.md} (§2, "Cost per tier"), costed from
     * GT's recipes with the research's material price ladder. The test's leaf prices ({@code gt_leaf_materials.json})
     * are close to that ladder but not equal, and the research costs some parts differently, so each tier only has to
     * be "roughly in line": within a factor of 2 either way. Measured with these prices: LV 16,184, MV 33,390, HV 73,364,
     * EV 155,095 (0.87x, 1.09x, 0.99x, 1.58x the research).
     */
    private static final long[] RESEARCH_MACERATOR_EMC = {18_600, 30_600, 74_000, 98_000};

    @GameTest(template = GameTests.EMPTY_TEMPLATE, timeoutTicks = 600)
    public static void machinesAreValuedFromTheirRecipesAndCostMoreEachTier(GameTestHelper helper) {
        GameTests.rebuildEmcMap(helper);
        helper.succeedWhen(() -> {
            GameTests.assertEmcMapBuilt(helper);
            long previous = 0;
            for (int i = 0; i < TIERS.length; i++) {
                String tier = TIERS[i];
                long emc = emc(helper, "gtceu:" + tier + "_macerator");
                helper.assertTrue(emc > previous, "The " + tier + " macerator should be worth more than the tier below ("
                        + previous + "), got " + emc);
                long research = RESEARCH_MACERATOR_EMC[i];
                McIncrementalPrestige.LOGGER.info("GT recipe EMC: {} macerator {} EMC (research {})", tier, emc, research);
                helper.assertTrue(emc >= research / 2 && emc <= research * 2, "The " + tier + " macerator should be within a "
                        + "factor of 2 of the research's " + research + " EMC, got " + emc);
                previous = emc;
            }
        });
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, timeoutTicks = 600)
    public static void circuitsOfEveryTierUpToEvAreValued(GameTestHelper helper) {
        GameTests.rebuildEmcMap(helper);
        helper.succeedWhen(() -> {
            GameTests.assertEmcMapBuilt(helper);
            for (String circuit : new String[] {"basic_integrated_circuit", "good_integrated_circuit",
                    "advanced_integrated_circuit", "micro_processor_computer"}) {
                long emc = emc(helper, "gtceu:" + circuit);
                McIncrementalPrestige.LOGGER.info("GT recipe EMC: {} {} EMC", circuit, emc);
                helper.assertTrue(emc > 0, "gtceu:" + circuit + " should have EMC, got " + emc);
            }
        });
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, timeoutTicks = 600)
    public static void theLatheSplitsAnIngotBetweenItsRodAndSmallDusts(GameTestHelper helper) {
        GameTests.rebuildEmcMap(helper);
        helper.succeedWhen(() -> {
            GameTests.assertEmcMapBuilt(helper);
            // gtceu:lathe/lathe_tungsten_to_rod: 1 tungsten ingot -> 1 rod + 2 small dusts.
            long ingot = emc(helper, "gtceu:tungsten_ingot");
            long rod = emc(helper, "gtceu:tungsten_rod");
            long smallDust = emc(helper, "gtceu:small_tungsten_dust");
            McIncrementalPrestige.LOGGER.info("GT recipe EMC: tungsten ingot {}, rod {}, small dust {}", ingot, rod, smallDust);
            helper.assertTrue(ingot > 0 && rod > 0 && smallDust > 0, "Tungsten ingot, rod and small dust need EMC, got "
                    + ingot + ", " + rod + ", " + smallDust);
            helper.assertTrue(rod + 2 * smallDust <= ingot + rounding(ingot), "A tungsten rod and 2 small dusts (" + rod
                    + " + 2 x " + smallDust + ") should be worth no more than the ingot they're turned from (" + ingot + ")");
        });
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, timeoutTicks = 600)
    public static void aDecompositionValuesItsOutputsFromItsInput(GameTestHelper helper) {
        GameTests.rebuildEmcMap(helper);
        helper.succeedWhen(() -> {
            GameTests.assertEmcMapBuilt(helper);
            // gtceu:electrolyzer/decomposition_electrolyzing_potash: 3 potash -> 2 potassium + 1000 mB oxygen (free here).
            // Potash is a test leaf price; nothing else makes potassium.
            long potash = emc(helper, "gtceu:potash_dust");
            long potassium = emc(helper, "gtceu:potassium_dust");
            helper.assertTrue(potash == 96, "Potash dust should be its test leaf price 96, got " + potash);
            helper.assertTrue(potassium > 0, "Potassium dust should be valued from electrolysing potash, got " + potassium);
            helper.assertTrue(2 * potassium <= 3 * potash + rounding(3 * potash), "2 potassium dust (2 x " + potassium + ") should be worth no more "
                    + "than the 3 potash dust (3 x " + potash + ") they're electrolysed from");
        });
    }

    /**
     * With the time rate and EU price off (the default), values are v0.3.0's: these were measured in a GameTest run of
     * v0.3.0 (VexedPanda/Mc-incremental#67).
     */
    @GameTest(template = GameTests.EMPTY_TEMPLATE, timeoutTicks = 600)
    public static void withTheRatesOffValuesAreThoseOfV030(GameTestHelper helper) {
        GameTests.rebuildEmcMap(helper);
        helper.succeedWhen(() -> {
            GameTests.assertEmcMapBuilt(helper);
            Map<String, Long> v030 = Map.ofEntries(Map.entry("gtceu:lv_macerator", 10_417L), Map.entry("gtceu:mv_macerator", 28_324L),
                    Map.entry("gtceu:hv_macerator", 57_872L), Map.entry("gtceu:ev_macerator", 93_940L),
                    Map.entry("gtceu:basic_integrated_circuit", 94L), Map.entry("gtceu:good_integrated_circuit", 878L),
                    Map.entry("gtceu:advanced_integrated_circuit", 2_293L), Map.entry("gtceu:micro_processor_computer", 8_730L),
                    Map.entry("gtceu:tungsten_ingot", 4_086L), Map.entry("gtceu:tungsten_rod", 2_038L),
                    Map.entry("gtceu:small_tungsten_dust", 1_024L));
            v030.forEach((id, value) -> helper.assertTrue(emc(helper, id) == value, id + " should be worth " + value
                    + " as in v0.3.0, got " + emc(helper, id)));
        });
    }

    /** EMC per machine-second for ULV, LV, MV, HV and EV (the pack's LV 4, ×2 per tier); later tiers take EV's. */
    private static final List<Double> TEST_TIME_RATE = List.of(2.0, 4.0, 8.0, 16.0, 32.0);
    /** EMC per EU for ULV, LV, MV, HV and EV (the research's LV 4 ×2 column). */
    private static final List<Double> TEST_EU_PRICE = List.of(0.0063, 0.0063, 0.0032, 0.0012, 0.00028);

    /**
     * With the time rate and EU price set, a recipe's outputs total its materials plus its EU × the EU price plus its
     * seconds × the time rate, at the recipe's tier (VexedPanda/Mc-incremental#67). Values with the config off are
     * checked first, so the test also shows they move.
     */
    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "gt_emc_rates", timeoutTicks = 1200)
    public static void euAndMachineTimeAreAddedToARecipesCostAtItsTier(GameTestHelper helper) {
        GameTests.rebuildEmcMap(helper);
        helper.startSequence()
                .thenWaitUntil(() -> GameTests.assertEmcMapBuilt(helper))
                .thenExecute(() -> {
                    // Off (the default): v0.3.0's values.
                    helper.assertTrue(arsenic(helper) == 3_555, "Molten arsenic should be its 1000/144 arsenic dust (3555 per "
                            + "1000 mB) with the rates off, got " + arsenic(helper));
                    double[] off = sides(helper, CYCLOHEXANE);
                    assertTotal(helper, "Cyclohexane with the rates off", off[1], off[0]);
                    Config.TIME_RATE.set(TEST_TIME_RATE);
                    Config.EU_PRICE.set(TEST_EU_PRICE);
                    GameTests.rebuildEmcMap(helper);
                })
                .thenWaitUntil(() -> {
                    GameTests.assertEmcMapBuilt(helper);
                    // LV: gtceu:extractor/extract_arsenic_dust, 1 arsenic dust (512) -> 144 mB of molten arsenic (unlisted, so
                    // valued only from this recipe), 30 EU/t for 75 ticks: 2,250 EU at 0.0063 and 3.75 s at 4 EMC/s.
                    long arsenic = arsenic(helper);
                    assertTotal(helper, "144 mB of molten arsenic", arsenic * 0.144, 512 + 2_250 * 0.0063 + 3.75 * 4);
                    // HV: gtceu:chemical_reactor/cyclohexane, a nickel dust, 1000 mB of benzene and 6000 mB of hydrogen
                    // (free) -> 1000 mB of cyclohexane (nothing else makes it), 480 EU/t for 400 ticks: 192,000 EU at 0.0012
                    // and 20 s at 16 EMC/s. Its materials are its inputs' values with the rates on.
                    double[] on = sides(helper, CYCLOHEXANE);
                    assertTotal(helper, "1000 mB of cyclohexane", on[1], on[0] + 192_000 * 0.0012 + 20 * 16);
                    McIncrementalPrestige.LOGGER.info("GT recipe EMC with rates: molten arsenic {} per 1000 mB (3555 off); "
                            + "1000 mB of cyclohexane {} from {} of materials", arsenic, on[1], on[0]);
                    // Tool crafting: gtceu:shaped/small_tungsten_pipe, a tungsten plate with a wrench and a hammer, which
                    // aren't consumed. It takes no time, so it is cheaper than extruding an ingot at HV.
                    helper.assertTrue(craftedWithAGtTool(helper, "gtceu:tungsten_small_fluid_pipe"), "Expected a crafting "
                            + "recipe making a small tungsten fluid pipe with a GT tool");
                    long plate = emc(helper, "gtceu:tungsten_plate");
                    long pipe = emc(helper, "gtceu:tungsten_small_fluid_pipe");
                    McIncrementalPrestige.LOGGER.info("GT recipe EMC with rates: tungsten plate {}, small tungsten fluid pipe {}",
                            plate, pipe);
                    helper.assertTrue(plate > 0 && pipe > 0 && pipe <= plate, "A small tungsten fluid pipe should be "
                            + "worth no more than the tungsten plate it's crafted from with tools (" + plate + "), got " + pipe);
                })
                .thenExecute(GtRecipeEmcGameTests::ratesOff)
                .thenSucceed();
    }

    /** The overclock check: a time rate less than double the tier below's is warned about; doubling or more is fine. */
    @GameTest(template = GameTests.EMPTY_TEMPLATE)
    public static void aTimeRateLessThanDoubleTheTierBelowIsWarnedAbout(GameTestHelper helper) {
        helper.assertTrue(Config.timeRateWarnings(List.of(2, 4, 8, 16, 32)).isEmpty() && Config.timeRateWarnings(List.of(1, 4.0, 16)).isEmpty(),
                "Rates doubling or more each tier should pass the overclock check, got " + Config.timeRateWarnings(List.of(2, 4, 8, 16, 32)));
        List<String> warnings = Config.timeRateWarnings(List.of(2, 4, 6, 16));
        helper.assertTrue(warnings.size() == 1 && warnings.get(0).contains("MV") && warnings.get(0).contains("LV"),
                "LV 4 then MV 6 should give one warning, naming MV and LV, got " + warnings);
        helper.succeed();
    }

    /** Whether a crafting-table recipe makes {@code id} from an ingredient that only GT tools match. */
    private static boolean craftedWithAGtTool(GameTestHelper helper, String id) {
        var server = helper.getLevel().getServer();
        Item output = BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
        for (var holder : server.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            if (holder.value().getResultItem(server.registryAccess()).getItem() != output) continue;
            for (Ingredient ingredient : holder.value().getIngredients()) {
                ItemStack[] stacks = ingredient.getItems();
                if (stacks.length > 0 && Arrays.stream(stacks).allMatch(stack -> GtRecipeEmcMapper.isGtTool(stack.getItem()))) return true;
            }
        }
        return false;
    }

    private static void ratesOff() {
        Config.TIME_RATE.set(List.of());
        Config.EU_PRICE.set(List.of());
    }

    private static final ResourceLocation CYCLOHEXANE = ResourceLocation.parse("gtceu:chemical_reactor/cyclohexane");

    /** A GT recipe's materials (consumed inputs) and outputs, in EMC per run ({@link #costAndOutputs}). */
    private static double[] sides(GameTestHelper helper, ResourceLocation id) {
        var holder = helper.getLevel().getServer().getRecipeManager().byKey(id);
        helper.assertTrue(holder.isPresent() && holder.get().value() instanceof GTRecipe, id + " should be a GT recipe");
        double[] sides = costAndOutputs((GTRecipe) holder.get().value());
        helper.assertTrue(sides != null && sides[0] > 0, id + "'s inputs need EMC");
        return sides;
    }

    /** Molten arsenic's EMC per 1000 mB. */
    private static long arsenic(GameTestHelper helper) {
        return moltenValue(helper, "gtceu:arsenic");
    }

    /** An unlisted fluid's EMC per 1000 mB. */
    private static long moltenValue(GameTestHelper helper, String id) {
        Fluid fluid = BuiltInRegistries.FLUID.get(ResourceLocation.parse(id));
        helper.assertTrue(fluid != Fluids.EMPTY && FluidEmc.listedValuePerBucket(fluid).isEmpty(),
                id + " must be a registered, unlisted fluid");
        return FluidEmc.valuePerBucket(fluid);
    }

    private static void assertTotal(GameTestHelper helper, String what, double actual, double expected) {
        helper.assertTrue(Math.abs(actual - expected) <= rounding(expected), what + " should be worth " + String.format("%.1f",
                expected) + " (materials + EU x price + seconds x rate), got " + actual);
    }

    /**
     * Gain allowed per recipe run for rounding: 2 EMC, or 0.5% of the inputs' cost. Shares are rounded up, ProjectE
     * rounds values down, and a fixed share is refreshed from a private pass whose values can still move a little.
     */
    private static double rounding(double cost) {
        return Math.max(2, cost * 0.005);
    }

    /**
     * Sweeps every GT recipe and compares its outputs' EMC with its consumed inputs' (the rule of the pack's
     * {@code docs/research/emc-pricing/audit.py}, independent of the mapper). A recipe the mapper split by share (each
     * output a fixed fraction of the inputs) must not make EMC, beyond rounding. The others are reported, worst first:
     * recipes split with fixed values (loops, large or decomposition lowerings), whose values can lag behind their
     * inputs; pinned outputs alone worth more than the inputs (only re-pricing fixes those); and recipes left unsplit
     * because their outputs valued elsewhere are worth more than the inputs (see docs/gt-recipe-emc.md).
     */
    @GameTest(template = GameTests.EMPTY_TEMPLATE, timeoutTicks = 1200)
    public static void noSplitGtRecipeMakesMoreEmcThanItConsumes(GameTestHelper helper) {
        GameTests.rebuildEmcMap(helper);
        helper.startSequence()
                .thenWaitUntil(() -> GameTests.assertEmcMapBuilt(helper))
                .thenExecute(() -> {
                    record Gain(String recipe, double cost, double outputs) {
                        double gain() { return outputs - cost; }
                    }
                    List<Gain> split = new ArrayList<>();
                    List<Gain> fixed = new ArrayList<>();
                    List<Gain> pinned = new ArrayList<>();
                    List<Gain> unsplit = new ArrayList<>();
                    int checked = 0;
                    int splitChecked = 0;
                    var recipes = helper.getLevel().getServer().getRecipeManager();
                    for (var type : BuiltInRegistries.RECIPE_TYPE) {
                        if (!(type instanceof GTRecipeType gtType)) continue;
                        for (RecipeHolder<GTRecipe> holder : recipes.getAllRecipesFor(gtType)) {
                            double[] sides = costAndOutputs(holder.value());
                            if (sides == null) continue;
                            checked++;
                            boolean isSplit = GtRecipeEmcMapper.isSplitByShare(holder.id());
                            boolean isFixed = GtRecipeEmcMapper.isSplitWithFixedValues(holder.id());
                            if (isSplit) splitChecked++;
                            if (sides[1] - sides[0] <= rounding(sides[0])) continue;
                            Gain gain = new Gain(holder.id().toString(), sides[0], sides[1]);
                            if (isSplit) split.add(gain);
                            else if (isFixed) fixed.add(gain);
                            else if (sides[2] > sides[0]) pinned.add(gain);
                            else unsplit.add(gain);
                        }
                    }
                    McIncrementalPrestige.LOGGER.info("GT recipe EMC: {} GT recipes checked, {} of them split by share; making "
                            + "more EMC than they consume: {} split by share, {} split with fixed values, {} with pinned outputs "
                            + "worth more than the inputs, {} others", checked, splitChecked, split.size(), fixed.size(),
                            pinned.size(), unsplit.size());
                    for (var group : List.of(Map.entry("split", split), Map.entry("fixed", fixed), Map.entry("pinned", pinned),
                            Map.entry("unsplit", unsplit))) {
                        List<Gain> gains = new ArrayList<>(group.getValue());
                        gains.sort(Comparator.comparingDouble(Gain::gain).reversed());
                        for (Gain gain : gains.subList(0, Math.min(15, gains.size()))) {
                            McIncrementalPrestige.LOGGER.info("GT recipe EMC: {} gains {} (cost {}, outputs {}): {}", group.getKey(),
                                    String.format("%.1f", gain.gain()), String.format("%.1f", gain.cost()),
                                    String.format("%.1f", gain.outputs()), gain.recipe());
                        }
                    }
                    helper.assertTrue(checked > 5_000, "Expected to check most GT recipes, checked " + checked);
                    helper.assertTrue(splitChecked > 50, "Expected the mapper to split many recipes by share, got " + splitChecked);
                    helper.assertTrue(split.isEmpty(), split.size() + " GT recipes split by share make more EMC "
                            + "than they consume, e.g. " + split.subList(0, Math.min(5, split.size())));
                })
                .thenSucceed();
    }

    /**
     * A GT recipe's consumed inputs' EMC and its outputs' EMC per run, chanced ones at their expected amount and fluids
     * at their fluid EMC per 1000 mB (the rule of the pack's {@code docs/research/emc-pricing/audit.py}), and the
     * part of the outputs' EMC that is pinned at a fixed value; null when an input has no EMC, so the recipe can't be
     * costed.
     */
    private static double[] costAndOutputs(GTRecipe recipe) {
        double cost = 0;
        double outputs = 0;
        double pinned = 0;
        for (Content content : recipe.inputs.getOrDefault(ItemRecipeCapability.CAP, List.of())) {
            if (content.chance() == 0) continue;
            Ingredient ingredient = ItemRecipeCapability.CAP.of(content.content()).ingredient();
            if (ingredient.getCustomIngredient() instanceof IntCircuitIngredient) continue;
            long cheapest = Long.MAX_VALUE;
            for (ItemStack stack : ingredient.getItems()) {
                long value = IEMCProxy.INSTANCE.getValue(stack.getItem());
                if (value > 0) cheapest = Math.min(cheapest, value);
            }
            if (cheapest == Long.MAX_VALUE) return null;
            cost += cheapest * expected(content, ItemRecipeCapability.CAP.of(content.content()).count());
        }
        for (Content content : recipe.inputs.getOrDefault(FluidRecipeCapability.CAP, List.of())) {
            if (content.chance() == 0) continue;
            SizedFluidIngredient sized = FluidRecipeCapability.CAP.of(content.content());
            long cheapest = Long.MAX_VALUE;
            for (FluidStack fluid : sized.getFluids()) {
                long value = fluidValue(fluid.getFluid());
                if (value >= 0) cheapest = Math.min(cheapest, value);
            }
            if (cheapest == Long.MAX_VALUE) return null;
            cost += cheapest * expected(content, sized.amount()) / 1000;
        }
        for (Content content : recipe.outputs.getOrDefault(ItemRecipeCapability.CAP, List.of())) {
            ItemStack[] items = ItemRecipeCapability.CAP.of(content.content()).ingredient().getItems();
            if (items.length != 1) continue;
            double worth = IEMCProxy.INSTANCE.getValue(items[0].getItem())
                    * expected(content, ItemRecipeCapability.CAP.of(content.content()).count());
            outputs += worth;
            if (GtRecipeEmcMapper.isPinned(NSSItem.createItem(items[0]))) pinned += worth;
        }
        for (Content content : recipe.outputs.getOrDefault(FluidRecipeCapability.CAP, List.of())) {
            SizedFluidIngredient sized = FluidRecipeCapability.CAP.of(content.content());
            FluidStack[] fluids = sized.getFluids();
            if (fluids.length != 1) continue;
            double worth = FluidEmc.valuePerBucket(fluids[0].getFluid()) * expected(content, sized.amount()) / 1000;
            outputs += worth;
            if (FluidEmc.listedValuePerBucket(fluids[0].getFluid()).isPresent()) pinned += worth;
        }
        return new double[] {cost, outputs, pinned};
    }

    /** EMC per 1000 mB: listed (0 for a free fluid such as water), else derived; -1 when the fluid has no value. */
    private static long fluidValue(Fluid fluid) {
        var listed = FluidEmc.listedValuePerBucket(fluid);
        if (listed.isPresent()) return listed.getAsLong();
        if (FluidEmc.source(fluid) == Fluids.WATER) return 0;
        long derived = FluidEmc.valuePerBucket(fluid);
        return derived > 0 ? derived : -1;
    }

    private static double expected(Content content, int amount) {
        return (double) amount * content.chance() / content.maxChance();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, timeoutTicks = 600)
    public static void fluidInputsUseTheirExplicitFluidValue(GameTestHelper helper) {
        GameTests.rebuildEmcMap(helper);
        helper.succeedWhen(() -> {
            GameTests.assertEmcMapBuilt(helper);
            // The fluid solidifier makes an ingot from 144 mB; molten red alloy is listed at 16 per 1000 mB (2.3 EMC).
            long emc = emc(helper, "gtceu:red_alloy_ingot");
            helper.assertTrue(emc == 2, "A red alloy ingot should cost its 144 mB of molten red alloy, got " + emc);
        });
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, timeoutTicks = 600)
    public static void nonConsumedInputsAddNoCost(GameTestHelper helper) {
        GameTests.rebuildEmcMap(helper);
        helper.succeedWhen(() -> {
            GameTests.assertEmcMapBuilt(helper);
            // The laser engraver turns a silicon wafer into a RAM wafer; its lens is not consumed.
            long silicon = emc(helper, "gtceu:silicon_wafer");
            long ram = emc(helper, "gtceu:ram_wafer");
            helper.assertTrue(silicon > 0, "The silicon wafer needs EMC for this test to mean anything");
            helper.assertTrue(ram == silicon, "A RAM wafer should cost just its silicon wafer (" + silicon + "), got " + ram);
        });
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "sweep_gt_mv_machine", timeoutTicks = 1200)
    public static void sweepingAPlacedMvMachineCreditsItsDerivedEmc(GameTestHelper helper) {
        GameTests.rebuildEmcMap(helper);
        MinecraftServer server = helper.getLevel().getServer();
        helper.startSequence()
                .thenWaitUntil(() -> GameTests.assertEmcMapBuilt(helper))
                .thenExecute(() -> PrestigeGameTests.withWorldRestored(helper, () -> {
                    long macerator = emc(helper, "gtceu:mv_macerator");
                    helper.assertTrue(macerator > 0, "The MV macerator needs EMC for this test to mean anything");
                    RunArea area = PrestigeSweepGameTests.startRun(helper);
                    ServerLevel level = PrestigeSweepGameTests.level(helper);
                    BlockPos pos = PrestigeSweepGameTests.surface(helper, area);
                    level.setBlockAndUpdate(pos, BuiltInRegistries.BLOCK.get(ResourceLocation.parse("gtceu:mv_macerator"))
                            .defaultBlockState());
                    BigInteger before = WorldEmc.balance(server);

                    PrestigeGameTests.runPrestigeCommand(helper);

                    helper.assertTrue(level.getBlockState(pos).isAir(), "The MV macerator should be swept");
                    PrestigeSweepGameTests.assertCredited(helper, before, macerator);
                }))
                .thenSucceed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "gt_derived_fluid_intake", timeoutTicks = 600)
    public static void anUnlistedFluidIsWorthWhatItsRecipeCostsInTheIntake(GameTestHelper helper) {
        GameTests.rebuildEmcMap(helper);
        MinecraftServer server = helper.getLevel().getServer();
        helper.startSequence()
                .thenWaitUntil(() -> GameTests.assertEmcMapBuilt(helper))
                .thenExecute(() -> {
                    Fluid copper = moltenCopper(helper);
                    // Molten copper is unlisted, so it is worth what its cheapest GT recipe costs: at most the fluid
                    // extractor melting a copper dust into 144 mB.
                    long value = FluidEmc.valuePerBucket(copper);
                    long dust = emc(helper, "gtceu:copper_dust");
                    helper.assertTrue(value > 0 && value <= dust * 1000 / 144, "Molten copper should be worth what its recipe "
                            + "costs, at most " + dust * 1000 / 144 + " per 1000 mB, got " + value);
                    IFluidHandler intake = EmcIntakeGameTests.placeIntakeTank(helper, new BlockPos(1, 1, 1));
                    BigInteger before = EmcBarrelFluidGameTests.milliBalance(server);

                    int filled = intake.fill(new FluidStack(copper, 144), IFluidHandler.FluidAction.EXECUTE);

                    helper.assertTrue(filled == 144, "The intake should take a dust's 144 mB of molten copper, took " + filled);
                    EmcBarrelFluidGameTests.assertMilliDelta(helper, server, before, value * 144, "after inserting 144 mB of molten copper");
                })
                .thenSucceed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "sweep_gt_derived_fluid", timeoutTicks = 1200)
    public static void sweepingATankOfAnUnlistedFluidCreditsItsDerivedValue(GameTestHelper helper) {
        GameTests.rebuildEmcMap(helper);
        MinecraftServer server = helper.getLevel().getServer();
        helper.startSequence()
                .thenWaitUntil(() -> GameTests.assertEmcMapBuilt(helper))
                .thenExecute(() -> PrestigeGameTests.withWorldRestored(helper, () -> {
                    Fluid copper = moltenCopper(helper);
                    RunArea area = PrestigeSweepGameTests.startRun(helper);
                    ServerLevel level = PrestigeSweepGameTests.level(helper);
                    BlockPos pos = PrestigeSweepGameTests.surface(helper, area);
                    level.setBlockAndUpdate(pos, BuiltInRegistries.BLOCK.get(ResourceLocation.parse("gtceu:lv_input_hatch"))
                            .defaultBlockState());
                    IFluidHandler hatch = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, null);
                    int filled = hatch.fill(new FluidStack(copper, 1000), IFluidHandler.FluidAction.EXECUTE);
                    helper.assertTrue(filled == 1000, "The input hatch should take 1000 mB of molten copper, took " + filled);
                    BigInteger before = WorldEmc.balance(server);

                    PrestigeGameTests.runPrestigeCommand(helper);

                    // The hatch (pinned at 800 in gametest.json) and 1000 mB of molten copper at its derived value.
                    PrestigeSweepGameTests.assertCredited(helper, before, 800 + FluidEmc.valuePerBucket(copper));
                }))
                .thenSucceed();
    }

    /** GT's molten copper; asserts the copper dust it is melted from has EMC. */
    private static Fluid moltenCopper(GameTestHelper helper) {
        long dust = emc(helper, "gtceu:copper_dust");
        helper.assertTrue(dust > 0, "Copper dust should have EMC, got " + dust);
        Fluid copper = BuiltInRegistries.FLUID.get(ResourceLocation.parse("gtceu:copper"));
        helper.assertTrue(copper != Fluids.EMPTY, "gtceu:copper is not a registered fluid");
        helper.assertTrue(FluidEmc.listedValuePerBucket(copper).isEmpty(), "Molten copper must be unlisted for this test");
        return copper;
    }

    static long emc(GameTestHelper helper, String id) {
        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
        helper.assertTrue(item != Items.AIR, id + " is not registered");
        return IEMCProxy.INSTANCE.getValue(item);
    }
}
