package io.github.vexedpanda.mcincremental.prestige.gametest;

import io.github.vexedpanda.mcincremental.prestige.McIncrementalPrestige;
import io.github.vexedpanda.mcincremental.prestige.emc.FluidEmc;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Fluid EMC values, loaded from {@code fluid_emc/*.json} in datapacks. The values here come from the GameTest-only
 * file {@code src/test/resources/data/mcincremental_prestige/fluid_emc/gametest.json}: lava 1500 per 1000 mB, water
 * unlisted, and nothing derives a value for it.
 */
@GameTestHolder(McIncrementalPrestige.MODID)
@PrefixGameTestTemplate(false)
public class FluidEmcGameTests {
    @GameTest(template = GameTests.EMPTY_TEMPLATE)
    public static void fluidValuesComeFromDataAndUnlistedWaterIsWorthNothing(GameTestHelper helper) {
        helper.assertTrue(FluidEmc.valuePerBucket(Fluids.LAVA) == 1500, "Lava should be worth 1500 per 1000 mB, got "
                + FluidEmc.valuePerBucket(Fluids.LAVA));
        helper.assertTrue(FluidEmc.valuePerBucket(Fluids.FLOWING_LAVA) == 1500, "Flowing lava is valued as its source");
        helper.assertTrue(FluidEmc.valuePerBucket(Fluids.WATER) == 0, "Unlisted water should be worth 0");
        helper.assertFalse(FluidEmc.hasValue(Fluids.WATER), "Unlisted water has no value");
        helper.assertFalse(FluidEmc.hasValue(Fluids.EMPTY), "The empty fluid has no value");
        helper.succeed();
    }
}
