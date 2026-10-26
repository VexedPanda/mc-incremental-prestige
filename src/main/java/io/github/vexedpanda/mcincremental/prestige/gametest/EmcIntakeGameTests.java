package io.github.vexedpanda.mcincremental.prestige.gametest;

import java.math.BigInteger;

import io.github.vexedpanda.mcincremental.prestige.McIncrementalPrestige;
import io.github.vexedpanda.mcincremental.prestige.emc.WorldEmc;
import io.github.vexedpanda.mcincremental.prestige.intake.EmcIntakes;
import moze_intel.projecte.api.proxy.IEMCProxy;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * The EMC intake, driven the way pipes and players drive it: a hopper, its item and fluid handler capabilities, and
 * using the block with an item or a bucket. Fluid values come from the GameTest-only datapack file (lava 1500 per
 * 1000 mB, water unmapped). Each test has its own batch, as in {@link EmcBarrelGameTests}: the world balance is global.
 */
@GameTestHolder(McIncrementalPrestige.MODID)
@PrefixGameTestTemplate(false)
public class EmcIntakeGameTests {
    private static final BlockPos INTAKE = new BlockPos(1, 1, 1);

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "emc_intake_hopper", timeoutTicks = 400)
    public static void aHopperFeedsItemsIntoTheIntakeAsEmc(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        GameTests.rebuildEmcMap(helper);
        BigInteger[] before = new BigInteger[1];
        helper.startSequence()
                .thenWaitUntil(() -> GameTests.assertEmcMapBuilt(helper))
                .thenExecute(() -> {
                    placeIntake(helper, INTAKE);
                    before[0] = WorldEmc.balance(server);
                    GameTests.placeHopperAbove(helper, INTAKE, new ItemStack(Items.QUARTZ, 5));
                })
                .thenIdle(GameTests.HOPPER_TICKS)
                .thenExecute(() -> {
                    int left = GameTests.hopperCount(helper, INTAKE.above(), Items.QUARTZ);
                    helper.assertTrue(left == 0, "The hopper should push all its quartz into the intake, " + left + " left");
                    // ProjectE's default value for nether quartz is 256.
                    BigInteger expected = before[0].add(BigInteger.valueOf(5 * 256));
                    helper.assertTrue(WorldEmc.balance(server).equals(expected),
                            "Balance should be " + expected + ", got " + WorldEmc.balance(server));
                    helper.assertTrue(WorldEmc.knows(server, new ItemStack(Items.QUARTZ)), "The world should know quartz");
                })
                .thenSucceed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "emc_intake_reject", timeoutTicks = 400)
    public static void itemsWithNoEmcAreRefusedNotDestroyed(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        GameTests.rebuildEmcMap(helper);
        helper.startSequence()
                .thenWaitUntil(() -> GameTests.assertEmcMapBuilt(helper))
                .thenExecute(() -> {
                    ItemStack bedrock = new ItemStack(Items.BEDROCK, 3);
                    helper.assertFalse(IEMCProxy.INSTANCE.hasValue(bedrock), "Precondition: bedrock has no EMC");
                    IItemHandler intake = placeIntake(helper, INTAKE);
                    BigInteger before = WorldEmc.balance(server);

                    helper.assertFalse(intake.isItemValid(0, bedrock), "The intake should not accept bedrock");
                    ItemStack rest = intake.insertItem(0, bedrock, false);
                    helper.assertTrue(rest.is(Items.BEDROCK) && rest.getCount() == 3, "All the bedrock should come back, got " + rest);
                    helper.assertTrue(bedrock.getCount() == 3, "The offered stack must not be shrunk");

                    Player player = useWith(helper, new ItemStack(Items.BEDROCK, 3));
                    helper.assertTrue(player.getMainHandItem().is(Items.BEDROCK) && player.getMainHandItem().getCount() == 3,
                            "Right-clicking with bedrock should leave it in hand, holds " + player.getMainHandItem());

                    helper.assertTrue(WorldEmc.balance(server).equals(before), "Balance should be unchanged");
                    helper.assertFalse(WorldEmc.knows(server, bedrock), "Bedrock should not be learned");
                })
                .thenSucceed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "emc_intake_use_item", timeoutTicks = 400)
    public static void rightClickingWithAnItemConvertsTheWholeStack(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        GameTests.rebuildEmcMap(helper);
        helper.startSequence()
                .thenWaitUntil(() -> GameTests.assertEmcMapBuilt(helper))
                .thenExecute(() -> {
                    placeIntake(helper, INTAKE);
                    BigInteger before = WorldEmc.balance(server);

                    Player player = useWith(helper, new ItemStack(Items.ENDER_PEARL, 3));

                    helper.assertTrue(player.getMainHandItem().isEmpty(), "The ender pearls should be used up, holds "
                            + player.getMainHandItem());
                    // ProjectE's default value for an ender pearl is 1024.
                    BigInteger expected = before.add(BigInteger.valueOf(3 * 1024));
                    helper.assertTrue(WorldEmc.balance(server).equals(expected),
                            "Balance should be " + expected + ", got " + WorldEmc.balance(server));
                    helper.assertTrue(WorldEmc.knows(server, new ItemStack(Items.ENDER_PEARL)), "The world should know ender pearls");
                })
                .thenSucceed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "emc_intake_fluid")
    public static void aFluidIsCreditedAtItsExplicitValueAndLearned(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        WorldEmc.forgetFluid(server, Fluids.LAVA);
        IFluidHandler intake = placeIntakeTank(helper, INTAKE);
        BigInteger before = EmcBarrelFluidGameTests.milliBalance(server);

        int filled = intake.fill(new FluidStack(Fluids.LAVA, 1001), FluidAction.EXECUTE);

        helper.assertTrue(filled == 1001, "The intake should take all 1001 mB of lava, took " + filled);
        // 1001 mB at 1500 EMC per 1000 mB = 1501.5 EMC.
        EmcBarrelFluidGameTests.assertMilliDelta(helper, server, before, 1_501_500, "after inserting 1001 mB of lava");
        helper.assertTrue(WorldEmc.knowsFluid(server, Fluids.LAVA), "The world should know lava");
        helper.assertTrue(intake.getFluidInTank(0).isEmpty() && intake.drain(1000, FluidAction.EXECUTE).isEmpty(),
                "Nothing comes out of the intake");
        helper.succeed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "emc_intake_bucket")
    public static void rightClickingWithALavaBucketEmptiesItIntoEmc(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        placeIntakeTank(helper, INTAKE);
        BigInteger before = EmcBarrelFluidGameTests.milliBalance(server);

        Player player = useWith(helper, new ItemStack(Items.LAVA_BUCKET));

        helper.assertTrue(player.getMainHandItem().is(Items.BUCKET), "The player should be left holding an empty bucket, holds "
                + player.getMainHandItem());
        EmcBarrelFluidGameTests.assertMilliDelta(helper, server, before, 1_500_000, "after emptying a lava bucket");
        helper.succeed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "emc_intake_water")
    public static void waterIsRefusedNotDestroyed(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        WorldEmc.forgetFluid(server, Fluids.WATER);
        IFluidHandler intake = placeIntakeTank(helper, INTAKE);
        BigInteger before = EmcBarrelFluidGameTests.milliBalance(server);
        FluidStack water = new FluidStack(Fluids.WATER, 1000);

        helper.assertFalse(intake.isFluidValid(0, water), "The intake should not accept water");
        int filled = intake.fill(water, FluidAction.EXECUTE);
        helper.assertTrue(filled == 0, "The intake should take none of the water, took " + filled);
        helper.assertTrue(water.getAmount() == 1000, "The offered stack must not be shrunk");

        // A water bucket is refused as water, never converted as an item.
        Player player = useWith(helper, new ItemStack(Items.WATER_BUCKET));
        helper.assertTrue(player.getMainHandItem().is(Items.WATER_BUCKET), "The water bucket should stay full in hand, holds "
                + player.getMainHandItem());

        EmcBarrelFluidGameTests.assertMilliDelta(helper, server, before, 0, "after offering water");
        helper.assertFalse(WorldEmc.knowsFluid(server, Fluids.WATER), "Water should not be learned");
        helper.succeed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE)
    public static void intakeHasACraftingRecipe(GameTestHelper helper) {
        var id = ResourceLocation.fromNamespaceAndPath(McIncrementalPrestige.MODID, "emc_intake");
        var recipe = helper.getLevel().getRecipeManager().byKey(id);
        helper.assertTrue(recipe.isPresent(), "Recipe " + id + " should load");
        helper.assertTrue(recipe.get().value().getResultItem(helper.getLevel().registryAccess()).is(EmcIntakes.ITEM.get()),
                "Recipe should make an EMC intake");
        helper.succeed();
    }

    /** A survival player right-clicks the intake at {@link #INTAKE} holding {@code held}; returns the player. */
    private static Player useWith(GameTestHelper helper, ItemStack held) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, held);
        helper.useBlock(INTAKE, player);
        return player;
    }

    /** Places an EMC intake at {@code pos} and returns its item handler, as a pipe would find it. */
    static IItemHandler placeIntake(GameTestHelper helper, BlockPos pos) {
        helper.setBlock(pos, EmcIntakes.BLOCK.get());
        IItemHandler handler = helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, helper.absolutePos(pos), null);
        helper.assertTrue(handler != null, "EMC intake should expose an item handler");
        return handler;
    }

    /** Places an EMC intake at {@code pos} and returns its fluid handler, as a pipe would find it. */
    static IFluidHandler placeIntakeTank(GameTestHelper helper, BlockPos pos) {
        helper.setBlock(pos, EmcIntakes.BLOCK.get());
        IFluidHandler handler = helper.getLevel().getCapability(Capabilities.FluidHandler.BLOCK, helper.absolutePos(pos), null);
        helper.assertTrue(handler != null, "EMC intake should expose a fluid handler");
        return handler;
    }
}
