package io.github.vexedpanda.mcincremental.prestige.gametest;

import java.math.BigInteger;

import io.github.vexedpanda.mcincremental.prestige.McIncrementalPrestige;
import io.github.vexedpanda.mcincremental.prestige.emc.FluidEmc;
import io.github.vexedpanda.mcincremental.prestige.emc.WorldEmc;
import io.github.vexedpanda.mcincremental.prestige.run.RunArea;
import moze_intel.projecte.api.proxy.IEMCProxy;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
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
                helper.assertTrue(emc > 0, "gtceu:" + circuit + " should have EMC, got " + emc);
            }
        });
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, timeoutTicks = 600)
    public static void fluidInputsUseTheirExplicitFluidValue(GameTestHelper helper) {
        GameTests.rebuildEmcMap(helper);
        helper.succeedWhen(() -> {
            GameTests.assertEmcMapBuilt(helper);
            // The fluid solidifier makes an ingot from 144 mB; molten red alloy is listed at 1000 per 1000 mB.
            long emc = emc(helper, "gtceu:red_alloy_ingot");
            helper.assertTrue(emc == 144, "A red alloy ingot should cost its 144 mB of molten red alloy, got " + emc);
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
                    // Molten copper is unlisted. Its cheapest recipe is the fluid extractor melting copper dust (108)
                    // into 144 mB: 750 EMC per 1000 mB.
                    long value = FluidEmc.valuePerBucket(copper);
                    helper.assertTrue(value == 750, "Molten copper should be worth its recipe's 750 per 1000 mB, got " + value);
                    IFluidHandler intake = EmcIntakeGameTests.placeIntakeTank(helper, new BlockPos(1, 1, 1));
                    BigInteger before = EmcBarrelFluidGameTests.milliBalance(server);

                    int filled = intake.fill(new FluidStack(copper, 144), IFluidHandler.FluidAction.EXECUTE);

                    helper.assertTrue(filled == 144, "The intake should take a dust's 144 mB of molten copper, took " + filled);
                    EmcBarrelFluidGameTests.assertMilliDelta(helper, server, before, 108_000, "after inserting 144 mB of molten copper");
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

                    // The hatch (pinned at 800 in gametest.json) and 1000 mB of molten copper at 750.
                    PrestigeSweepGameTests.assertCredited(helper, before, 800 + 750);
                }))
                .thenSucceed();
    }

    /** GT's molten copper; asserts the copper dust it is melted from is worth 108 EMC. */
    private static Fluid moltenCopper(GameTestHelper helper) {
        long dust = emc(helper, "gtceu:copper_dust");
        helper.assertTrue(dust == 108, "Copper dust should be worth 108, got " + dust);
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
