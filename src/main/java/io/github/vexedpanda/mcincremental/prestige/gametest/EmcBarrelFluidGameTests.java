package io.github.vexedpanda.mcincremental.prestige.gametest;

import java.math.BigInteger;

import io.github.vexedpanda.mcincremental.prestige.McIncrementalPrestige;
import io.github.vexedpanda.mcincremental.prestige.barrel.EmcBarrelBlockEntity;
import io.github.vexedpanda.mcincremental.prestige.barrel.EmcBarrels;
import io.github.vexedpanda.mcincremental.prestige.emc.WorldEmc;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The EMC barrel's fluid side, driven through its fluid handler capability and by using the block with a bucket.
 * Fluid values come from the GameTest-only datapack file ({@link FluidEmcGameTests}): lava is worth 1500 per 1000 mB
 * (1.5 EMC per mB), water is unmapped. Balances are compared in thousandths of an EMC (whole balance plus the world's
 * fractional EMC), since a mB of lava is worth a fraction. Each test has its own batch, as in
 * {@link EmcBarrelGameTests}.
 */
@GameTestHolder(McIncrementalPrestige.MODID)
@PrefixGameTestTemplate(false)
public class EmcBarrelFluidGameTests {
    private static final BlockPos BARREL = new BlockPos(1, 1, 1);

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "emc_barrel_fluid_insert")
    public static void fluidInsertionCreditsTheExplicitValueAndLearnsTheFluid(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        WorldEmc.forgetFluid(server, Fluids.LAVA);
        IFluidHandler barrel = placeBarrel(helper);
        BigInteger before = milliBalance(server);

        int filled = barrel.fill(new FluidStack(Fluids.LAVA, 1001), FluidAction.EXECUTE);

        helper.assertTrue(filled == 1001, "Barrel should take all 1001 mB of lava, took " + filled);
        // 1001 mB at 1500 EMC per 1000 mB = 1501.5 EMC.
        assertMilliDelta(helper, server, before, 1_501_500, "after inserting 1001 mB of lava");
        helper.assertTrue(WorldEmc.knowsFluid(server, Fluids.LAVA), "The world should know lava");
        helper.succeed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "emc_barrel_fluid_extract")
    public static void fluidExtractionDebitsTheExplicitValueAndStopsWhenTheBalanceIsShort(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        helper.assertTrue(server.getPlayerList().getPlayerCount() == 0, "No player should be online");
        IFluidHandler barrel = placeBarrel(helper);
        WorldEmc.learnFluid(server, Fluids.LAVA);
        EmcBarrelGameTests.configure(helper, Items.LAVA_BUCKET);
        // 3000 EMC (plus under one EMC of fractional EMC) pays for exactly 2000 mB of lava at 1.5 EMC per mB.
        EmcBarrelGameTests.withBalance(server, BigInteger.valueOf(3000), () -> {
            BigInteger before = milliBalance(server);
            FluidStack shown = barrel.getFluidInTank(0);
            helper.assertTrue(shown.is(Fluids.LAVA) && shown.getAmount() == 2000, "Tank should show 2000 mB of lava, shows " + shown);

            FluidStack simulated = barrel.drain(5000, FluidAction.SIMULATE);
            helper.assertTrue(simulated.is(Fluids.LAVA) && simulated.getAmount() == 2000,
                    "Simulated drain should offer 2000 mB of lava, got " + simulated);
            assertMilliDelta(helper, server, before, 0, "after a simulated drain");

            helper.assertTrue(barrel.drain(new FluidStack(Fluids.WATER, 100), FluidAction.EXECUTE).isEmpty(),
                    "Draining a fluid other than the configured one should give nothing");

            FluidStack drained = barrel.drain(new FluidStack(Fluids.LAVA, 5000), FluidAction.EXECUTE);
            helper.assertTrue(drained.is(Fluids.LAVA) && drained.getAmount() == 2000,
                    "Drain should hand out the 2000 mB of lava the balance pays for, got " + drained);
            assertMilliDelta(helper, server, before, -3_000_000, "after draining 2000 mB of lava");

            helper.assertTrue(barrel.drain(1, FluidAction.EXECUTE).isEmpty(), "Drain should stop when the balance is short");
            assertMilliDelta(helper, server, before, -3_000_000, "after a refused drain");
        });
        helper.succeed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "emc_barrel_fluid_round_trip")
    public static void fluidInsertThenExtractNetsZeroEmc(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        IFluidHandler barrel = placeBarrel(helper);
        BigInteger before = milliBalance(server);

        // 7 mB of lava is worth 10.5 EMC: the half must come back too.
        helper.assertTrue(barrel.fill(new FluidStack(Fluids.LAVA, 7), FluidAction.EXECUTE) == 7, "Barrel should take the lava");
        EmcBarrelGameTests.configure(helper, Items.LAVA_BUCKET);
        FluidStack out = barrel.drain(7, FluidAction.EXECUTE);

        helper.assertTrue(out.is(Fluids.LAVA) && out.getAmount() == 7, "Should get the 7 mB of lava back, got " + out);
        assertMilliDelta(helper, server, before, 0, "after inserting then extracting 7 mB of lava");
        helper.succeed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "emc_barrel_fluid_configure")
    public static void onlyLearnedFluidsWithAValueCanBeConfigured(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        IFluidHandler barrel = placeBarrel(helper);
        EmcBarrelGameTests.withBalance(server, BigInteger.valueOf(1_000_000), () -> {
            // Water has no fluid EMC value, so it can't be configured even once learned.
            WorldEmc.learnFluid(server, Fluids.WATER);
            EmcBarrelGameTests.configure(helper, Items.WATER_BUCKET);
            helper.assertTrue(barrel(helper).configuredFluid() == null, "Unmapped water must not be configured");
            helper.assertTrue(barrel.drain(1000, FluidAction.EXECUTE).isEmpty(), "No water should come out");
            WorldEmc.forgetFluid(server, Fluids.WATER);

            WorldEmc.forgetFluid(server, Fluids.LAVA);
            EmcBarrelGameTests.configure(helper, Items.LAVA_BUCKET);
            helper.assertTrue(barrel(helper).configuredFluid() == null, "Unlearned lava must not be configured");
            helper.assertTrue(barrel.drain(1000, FluidAction.EXECUTE).isEmpty(), "No lava should come out");

            WorldEmc.learnFluid(server, Fluids.LAVA);
            EmcBarrelGameTests.configure(helper, Items.LAVA_BUCKET);
            helper.assertTrue(barrel.getFluidInTank(0).is(Fluids.LAVA), "Learned lava should be configured, tank shows "
                    + barrel.getFluidInTank(0));

            // The setting survives the barrel being saved and reloaded (chunk unload, restart).
            var level = helper.getLevel();
            BlockPos pos = helper.absolutePos(BARREL);
            var tag = level.getBlockEntity(pos).saveWithFullMetadata(level.registryAccess());
            var reloaded = BlockEntity.loadStatic(pos, level.getBlockState(pos), tag, level.registryAccess());
            helper.assertTrue(reloaded instanceof EmcBarrelBlockEntity b && b.configuredFluid() == Fluids.LAVA,
                    "A reloaded barrel should still hand out lava");

            // Configuring an item replaces the fluid.
            WorldEmc.learn(server, new ItemStack(Items.COAL));
            EmcBarrelGameTests.configure(helper, Items.COAL);
            helper.assertTrue(barrel(helper).configuredFluid() == null && barrel.drain(1000, FluidAction.EXECUTE).isEmpty(),
                    "Configuring coal should clear the fluid");
        });
        helper.succeed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "emc_barrel_fluid_reject")
    public static void unmappedFluidsAreRejectedNotDestroyed(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        WorldEmc.forgetFluid(server, Fluids.WATER);
        IFluidHandler barrel = placeBarrel(helper);
        BigInteger before = milliBalance(server);
        FluidStack water = new FluidStack(Fluids.WATER, 1000);

        helper.assertFalse(barrel.isFluidValid(0, water), "Barrel should not accept water");
        int filled = barrel.fill(water, FluidAction.EXECUTE);

        helper.assertTrue(filled == 0, "Barrel should take none of the water, took " + filled);
        helper.assertTrue(water.getAmount() == 1000, "The offered stack must not be shrunk");
        assertMilliDelta(helper, server, before, 0, "after offering water");
        helper.assertFalse(WorldEmc.knowsFluid(server, Fluids.WATER), "Water should not be learned");
        helper.succeed();
    }

    static EmcBarrelBlockEntity barrel(GameTestHelper helper) {
        return (EmcBarrelBlockEntity) helper.getBlockEntity(BARREL);
    }

    /** The world's balance in thousandths of an EMC, including its fractional EMC. */
    static BigInteger milliBalance(MinecraftServer server) {
        return WorldEmc.balance(server).multiply(BigInteger.valueOf(1000)).add(BigInteger.valueOf(WorldEmc.fractionalMilliEmc(server)));
    }

    static void assertMilliDelta(GameTestHelper helper, MinecraftServer server, BigInteger before, long delta, String when) {
        BigInteger actual = milliBalance(server).subtract(before);
        helper.assertTrue(actual.equals(BigInteger.valueOf(delta)),
                "Balance change " + when + " should be " + delta + " thousandths of an EMC, got " + actual);
    }

    /** Places an EMC barrel at {@link #BARREL} and returns its fluid handler, as a pipe would find it. */
    static IFluidHandler placeBarrel(GameTestHelper helper) {
        helper.setBlock(BARREL, EmcBarrels.BLOCK.get());
        IFluidHandler handler = helper.getLevel().getCapability(Capabilities.FluidHandler.BLOCK, helper.absolutePos(BARREL), null);
        helper.assertTrue(handler != null, "EMC barrel should expose a fluid handler");
        return handler;
    }
}
