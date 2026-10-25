package io.github.vexedpanda.mcincremental.prestige.gametest;

import java.math.BigInteger;

import io.github.vexedpanda.mcincremental.prestige.McIncrementalPrestige;
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
    @GameTest(template = GameTests.EMPTY_TEMPLATE, timeoutTicks = 600)
    public static void machinesAreValuedFromTheirRecipesAndCostMoreEachTier(GameTestHelper helper) {
        GameTests.rebuildEmcMap(helper);
        helper.succeedWhen(() -> {
            GameTests.assertEmcMapBuilt(helper);
            long previous = 0;
            for (String tier : new String[] {"lv", "mv", "hv", "ev"}) {
                long emc = emc(helper, "gtceu:" + tier + "_macerator");
                helper.assertTrue(emc > previous, "The " + tier + " macerator should be worth more than the tier below ("
                        + previous + "), got " + emc);
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
    public static void sweepingAPlacedMvMachineRefundsItsDerivedEmc(GameTestHelper helper) {
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

    static long emc(GameTestHelper helper, String id) {
        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
        helper.assertTrue(item != Items.AIR, id + " is not registered");
        return IEMCProxy.INSTANCE.getValue(item);
    }
}
