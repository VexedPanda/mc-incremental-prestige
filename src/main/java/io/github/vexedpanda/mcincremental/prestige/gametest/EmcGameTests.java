package io.github.vexedpanda.mcincremental.prestige.gametest;

import io.github.vexedpanda.mcincremental.prestige.McIncrementalPrestige;
import moze_intel.projecte.api.proxy.IEMCProxy;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Smoke test: ProjectE is loaded at test runtime and its EMC API answers real values. */
@GameTestHolder(McIncrementalPrestige.MODID)
@PrefixGameTestTemplate(false)
public class EmcGameTests {
    @GameTest(template = GameTests.EMPTY_TEMPLATE, timeoutTicks = 400)
    public static void diamondHasProjectEDefaultEmc(GameTestHelper helper) {
        GameTests.rebuildEmcMap(helper);
        helper.succeedWhen(() -> {
            long expected = 8192; // ProjectE's default value for a diamond
            long emc = IEMCProxy.INSTANCE.getValue(Items.DIAMOND);
            helper.assertTrue(emc == expected, "Expected diamond EMC " + expected + ", got " + emc);
        });
    }
}
