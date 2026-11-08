package io.github.vexedpanda.mcincremental.prestige.gametest;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.ingredient.IntCircuitIngredient;
import com.gregtechceu.gtceu.utils.GTUtil;

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
     * be "roughly in line": within a factor of 2 either way. Measured with these prices, EU and machine time included
     * (VexedPanda/Mc-incremental#71): LV 13,868, MV 31,882, HV 72,014, EV 118,485 (0.75x, 1.04x, 0.97x, 1.21x the research).
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
            assertLatheBalanced(helper, "tungsten");
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
            double cost = 3 * potash + euAndTime(gtRecipe(helper, "gtceu:electrolyzer/decomposition_electrolyzing_potash"));
            helper.assertTrue(2 * potassium <= cost + rounding(cost), "2 potassium dust (2 x " + potassium + ") should be "
                    + "worth no more than the 3 potash dust (3 x " + potash + ") they're electrolysed from, plus its EU and "
                    + "time (" + String.format("%.1f", cost) + " in all)");
        });
    }

    /**
     * A recipe's outputs total its materials plus its EU × the EU price plus its seconds × the time rate, at the recipe's
     * tier (VexedPanda/Mc-incremental#67, always counted since #71).
     */
    @GameTest(template = GameTests.EMPTY_TEMPLATE, timeoutTicks = 600)
    public static void euAndMachineTimeAreAddedToARecipesCostAtItsTier(GameTestHelper helper) {
        GameTests.rebuildEmcMap(helper);
        helper.succeedWhen(() -> {
            GameTests.assertEmcMapBuilt(helper);
            // LV: gtceu:extractor/extract_arsenic_dust, 1 arsenic dust (512) -> 144 mB of molten arsenic (unlisted, so
            // valued only from this recipe), 30 EU/t for 75 ticks: 2,250 EU at 0.006338 and 3.75 s at 4 EMC/s.
            long arsenic = moltenValue(helper, "gtceu:arsenic");
            assertTotal(helper, "144 mB of molten arsenic", arsenic * 0.144, 512 + 2_250 * 0.006338 + 3.75 * 4);
            // HV: gtceu:chemical_reactor/cyclohexane, a nickel dust, 1000 mB of benzene and 6000 mB of hydrogen (free)
            // -> 1000 mB of cyclohexane (nothing else makes it), 480 EU/t for 400 ticks: 192,000 EU at 0.001235 and 20 s
            // at 16 EMC/s.
            double[] sides = sides(helper, CYCLOHEXANE);
            assertTotal(helper, "1000 mB of cyclohexane", sides[1], sides[0] + 192_000 * 0.001235 + 20 * 16);
            McIncrementalPrestige.LOGGER.info("GT recipe EMC: molten arsenic {} per 1000 mB; 1000 mB of cyclohexane {} from "
                    + "{} of materials", arsenic, sides[1], sides[0]);
        });
    }

    /**
     * Crafting-table recipes that use a GT tool are mapped, with the tool not consumed (VexedPanda/Mc-incremental#67):
     * gtceu:shaped/small_tungsten_pipe, a tungsten plate with a wrench and a hammer. It takes no time, so it is cheaper
     * than extruding an ingot at HV.
     */
    @GameTest(template = GameTests.EMPTY_TEMPLATE, timeoutTicks = 600)
    public static void aGtToolCraftCapsItsOutputAtItsIngredients(GameTestHelper helper) {
        GameTests.rebuildEmcMap(helper);
        helper.succeedWhen(() -> {
            GameTests.assertEmcMapBuilt(helper);
            helper.assertTrue(craftedWithAGtTool(helper, "gtceu:tungsten_small_fluid_pipe"), "Expected a crafting recipe "
                    + "making a small tungsten fluid pipe with a GT tool");
            long plate = emc(helper, "gtceu:tungsten_plate");
            long pipe = emc(helper, "gtceu:tungsten_small_fluid_pipe");
            McIncrementalPrestige.LOGGER.info("GT recipe EMC: tungsten plate {}, small tungsten fluid pipe {}", plate, pipe);
            helper.assertTrue(plate > 0 && pipe > 0 && pipe <= plate, "A small tungsten fluid pipe should be worth no more "
                    + "than the tungsten plate it's crafted from with tools (" + plate + "), got " + pipe);
        });
    }

    /**
     * A machine route cheaper than a GT tool craft sets the price (VexedPanda/Mc-incremental#69). A rod is crafted from
     * one ingot with a file, which values it at a whole ingot; the lathe turns one ingot into a rod and 2 small dusts, so
     * it must value the rod at what's left of the ingot, its EU and its time once the small dusts are paid for. Checked
     * for RTM alloy and tungsten steel, the worst lathes in the Pack's audit.
     */
    @GameTest(template = GameTests.EMPTY_TEMPLATE, timeoutTicks = 600)
    public static void aLatheCheaperThanAToolCraftSetsTheRodsValue(GameTestHelper helper) {
        GameTests.rebuildEmcMap(helper);
        helper.succeedWhen(() -> {
            GameTests.assertEmcMapBuilt(helper);
            for (String material : new String[] {"rtm_alloy", "tungsten_steel"}) {
                helper.assertTrue(craftedWithAGtTool(helper, "gtceu:" + material + "_rod"), "Expected a crafting recipe "
                        + "making a " + material + " rod with a GT tool");
                assertLatheBalanced(helper, material);
            }
        });
    }

    /** The lathe turning a material's ingot into a rod and 2 small dusts: those are worth no more than the ingot + EU + time. */
    private static void assertLatheBalanced(GameTestHelper helper, String material) {
        var holder = helper.getLevel().getServer().getRecipeManager()
                .byKey(ResourceLocation.parse("gtceu:lathe/lathe_" + material + "_to_rod"));
        helper.assertTrue(holder.isPresent() && holder.get().value() instanceof GTRecipe, "Expected the " + material
                + " lathe recipe");
        double euAndTime = euAndTime((GTRecipe) holder.get().value());
        long ingot = emc(helper, "gtceu:" + material + "_ingot");
        long rod = emc(helper, "gtceu:" + material + "_rod");
        long smallDust = emc(helper, "gtceu:small_" + material + "_dust");
        McIncrementalPrestige.LOGGER.info("GT recipe EMC: {} ingot {}, rod {}, small dust {}; lathe EU and time {}", material,
                ingot, rod, smallDust, String.format("%.1f", euAndTime));
        helper.assertTrue(ingot > 0 && rod > 0 && smallDust > 0, material + " ingot, rod and small dust need EMC, got "
                + ingot + ", " + rod + ", " + smallDust);
        double cost = ingot + euAndTime;
        helper.assertTrue(rod + 2 * smallDust <= cost + rounding(cost), "A " + material + " rod and 2 small dusts (" + rod
                + " + 2 x " + smallDust + ") should be worth no more than the ingot (" + ingot + ") plus the lathe's EU and "
                + "time (" + String.format("%.1f", euAndTime) + ")");
    }

    /**
     * A recipe's EU × the EU price plus its seconds × the time rate, at its tier (the spec of #67), from the built-in
     * rates, which {@link #theBuiltInTimeRateDoublesEveryTier} pins to the ticket's values.
     */
    private static double euAndTime(GTRecipe recipe) {
        if (!recipe.getOutputEUt().isEmpty()) return 0;
        var eu = recipe.getInputEUt();
        int tier = eu.isEmpty() ? GTValues.ULV : GTUtil.getTierByVoltage(eu.voltage());
        return (double) eu.getTotalEU() * recipe.duration * GtRecipeEmcMapper.euPrice(tier)
                + recipe.duration / 20.0 * GtRecipeEmcMapper.timeRate(tier);
    }

    /**
     * The built-in rates (VexedPanda/Mc-incremental#71): time is 4 EMC per machine-second at LV and doubles every tier, so
     * an overclocked recipe (twice as fast per tier above its own) never earns more than the faster machine's time; the EU
     * price per tier is the Pack's from {@code docs/research/emc-pricing/eu_price.py}.
     */
    @GameTest(template = GameTests.EMPTY_TEMPLATE)
    public static void theBuiltInTimeRateDoublesEveryTier(GameTestHelper helper) {
        helper.assertTrue(GtRecipeEmcMapper.timeRate(GTValues.ULV) == 2 && GtRecipeEmcMapper.timeRate(GTValues.LV) == 4
                && GtRecipeEmcMapper.timeRate(GTValues.MAX) == 32_768, "Time should be ULV 2, LV 4 and MAX 32,768 EMC per "
                + "machine-second, got " + GtRecipeEmcMapper.timeRate(GTValues.ULV) + ", "
                + GtRecipeEmcMapper.timeRate(GTValues.LV) + " and " + GtRecipeEmcMapper.timeRate(GTValues.MAX));
        for (int tier = GTValues.LV; tier <= GTValues.MAX; tier++) {
            helper.assertTrue(GtRecipeEmcMapper.timeRate(tier) == 2 * GtRecipeEmcMapper.timeRate(tier - 1), "The time rate at "
                    + GTValues.VN[tier] + " should be double " + GTValues.VN[tier - 1] + "'s, got "
                    + GtRecipeEmcMapper.timeRate(tier));
        }
        double[] euPrice = {0.006338, 0.006338, 0.003213, 0.001235, 0.0002835, 0.0002835, 9.765625e-05};
        for (int tier = GTValues.ULV; tier <= GTValues.MAX; tier++) {
            double expected = euPrice[Math.min(tier, euPrice.length - 1)];
            helper.assertTrue(GtRecipeEmcMapper.euPrice(tier) == expected, "The EU price at " + GTValues.VN[tier]
                    + " should be " + expected + ", got " + GtRecipeEmcMapper.euPrice(tier));
        }
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

    private static final ResourceLocation CYCLOHEXANE = ResourceLocation.parse("gtceu:chemical_reactor/cyclohexane");

    /** A GT recipe's materials (consumed inputs) and outputs, in EMC per run ({@link #costAndOutputs}). */
    private static double[] sides(GameTestHelper helper, ResourceLocation id) {
        var holder = helper.getLevel().getServer().getRecipeManager().byKey(id);
        helper.assertTrue(holder.isPresent() && holder.get().value() instanceof GTRecipe, id + " should be a GT recipe");
        double[] sides = costAndOutputs((GTRecipe) holder.get().value());
        helper.assertTrue(sides != null && sides[0] > 0, id + "'s inputs need EMC");
        return sides;
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
     * Sweeps every GT recipe and compares its outputs' EMC with its consumed inputs' plus its EU and machine time (the
     * rule of the pack's {@code docs/research/emc-pricing/audit.py}, independent of the mapper). A recipe the mapper split by share (each
     * output a fixed fraction of the inputs) must not make EMC, beyond rounding, unless its inputs have fallen since the
     * seed its shares were taken from (VexedPanda/Mc-incremental#71): its outputs valued elsewhere then keep their value,
     * so it may gain up to that fall's part of its outputs. The others are reported, worst first:
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
                    List<Gain> drifted = new ArrayList<>();
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
                            sides[0] += euAndTime(holder.value());
                            checked++;
                            boolean isSplit = GtRecipeEmcMapper.isSplitByShare(holder.id());
                            boolean isFixed = GtRecipeEmcMapper.isSplitWithFixedValues(holder.id());
                            if (isSplit) splitChecked++;
                            if (sides[1] - sides[0] <= rounding(sides[0])) continue;
                            Gain gain = new Gain(holder.id().toString(), sides[0], sides[1]);
                            // Split by share at the seed's cost: when the inputs have since fallen, outputs valued
                            // elsewhere keep their value and are worth a larger part of the cost. Allowed up to that
                            // fall's part of the outputs.
                            double seedCost = GtRecipeEmcMapper.seedCost(holder.id()).orElse(sides[0]);
                            double fall = Math.max(0, 1 - sides[0] / seedCost);
                            if (isSplit && sides[1] - sides[0] <= fall * sides[1] + rounding(sides[0])) drifted.add(gain);
                            else if (isSplit) split.add(gain);
                            else if (isFixed) fixed.add(gain);
                            else if (sides[2] > sides[0]) pinned.add(gain);
                            else unsplit.add(gain);
                        }
                    }
                    McIncrementalPrestige.LOGGER.info("GT recipe EMC: {} GT recipes checked, {} of them split by share; making "
                            + "more EMC than they consume: {} split by share, {} split by share whose inputs fell since the "
                            + "seed, {} split with fixed values, {} with pinned outputs worth more than the inputs, {} others",
                            checked, splitChecked, split.size(), drifted.size(), fixed.size(), pinned.size(), unsplit.size());
                    for (var group : List.of(Map.entry("split", split), Map.entry("drifted", drifted), Map.entry("fixed", fixed),
                            Map.entry("pinned", pinned), Map.entry("unsplit", unsplit))) {
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

    /** The GT recipe with this id. */
    private static GTRecipe gtRecipe(GameTestHelper helper, String id) {
        var holder = helper.getLevel().getServer().getRecipeManager().byKey(ResourceLocation.parse(id));
        helper.assertTrue(holder.isPresent() && holder.get().value() instanceof GTRecipe, id + " should be a GT recipe");
        return (GTRecipe) holder.get().value();
    }

    /** The GT recipe of a type (e.g. {@code laser_engraver}) whose only item output is {@code item}. */
    private static GTRecipe gtRecipeMaking(GameTestHelper helper, String type, String item) {
        var recipeType = (GTRecipeType) BuiltInRegistries.RECIPE_TYPE.get(ResourceLocation.parse("gtceu:" + type));
        Item output = BuiltInRegistries.ITEM.get(ResourceLocation.parse(item));
        for (RecipeHolder<GTRecipe> holder : helper.getLevel().getServer().getRecipeManager().getAllRecipesFor(recipeType)) {
            List<Content> outputs = holder.value().outputs.getOrDefault(ItemRecipeCapability.CAP, List.of());
            if (outputs.size() != 1) continue;
            ItemStack[] items = ItemRecipeCapability.CAP.of(outputs.get(0).content()).ingredient().getItems();
            if (items.length == 1 && items[0].getItem() == output) return holder.value();
        }
        throw new net.minecraft.gametest.framework.GameTestAssertException("No gtceu:" + type + " recipe makes " + item);
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
            double expected = 16 * 0.144 + euAndTime(gtRecipeMaking(helper, "fluid_solidifier", "gtceu:red_alloy_ingot"));
            helper.assertTrue(Math.abs(emc - expected) <= 1, "A red alloy ingot should cost its 144 mB of molten red alloy "
                    + "plus the solidifier's EU and time (" + String.format("%.1f", expected) + "), got " + emc);
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
            double expected = silicon + euAndTime(gtRecipeMaking(helper, "laser_engraver", "gtceu:ram_wafer"));
            helper.assertTrue(Math.abs(ram - expected) <= rounding(expected), "A RAM wafer should cost just its silicon "
                    + "wafer (" + silicon + ") plus the engraver's EU and time (" + String.format("%.1f", expected) + "), got "
                    + ram);
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
                    // extractor melting a copper dust into 144 mB, with its EU and time.
                    long value = FluidEmc.valuePerBucket(copper);
                    double melt = (emc(helper, "gtceu:copper_dust")
                            + euAndTime(gtRecipe(helper, "gtceu:extractor/extract_copper_dust"))) * 1000 / 144;
                    helper.assertTrue(value > 0 && value <= melt + rounding(melt), "Molten copper should be worth what its "
                            + "recipe costs, at most " + String.format("%.1f", melt) + " per 1000 mB, got " + value);
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
