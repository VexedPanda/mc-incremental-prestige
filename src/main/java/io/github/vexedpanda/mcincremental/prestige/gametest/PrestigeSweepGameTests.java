package io.github.vexedpanda.mcincremental.prestige.gametest;

import java.math.BigInteger;
import java.util.Optional;

import io.github.vexedpanda.mcincremental.prestige.McIncrementalPrestige;
import io.github.vexedpanda.mcincremental.prestige.barrel.EmcBarrelBlockEntity;
import io.github.vexedpanda.mcincremental.prestige.barrel.EmcBarrels;
import io.github.vexedpanda.mcincremental.prestige.emc.WorldEmc;
import io.github.vexedpanda.mcincremental.prestige.run.RunArea;
import io.github.vexedpanda.mcincremental.prestige.run.RunAreas;
import io.github.vexedpanda.mcincremental.prestige.sweep.RunSnapshots;
import moze_intel.projecte.api.proxy.IEMCProxy;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The Prestige sweep of placed and changed blocks (ADR-0007), driven through {@code /prestige}. Each test Prestiges
 * once to start a Run in a fresh Run area, changes that area, then Prestiges again and checks what the sweep removed,
 * kept and credited. Nothing ticks between the two Prestiges, so the world changes only where the test changes it.
 * A Prestige moves the border and every player, so each test has its own batch.
 */
@GameTestHolder(McIncrementalPrestige.MODID)
@PrefixGameTestTemplate(false)
public class PrestigeSweepGameTests {
    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "sweep_placed", timeoutTicks = 400)
    public static void placedBlockIsRemovedAndItsFullEmcCredited(GameTestHelper helper) {
        GameTests.rebuildEmcMap(helper);
        MinecraftServer server = helper.getLevel().getServer();
        helper.startSequence()
                .thenWaitUntil(() -> GameTests.assertEmcMapBuilt(helper))
                .thenExecute(() -> PrestigeGameTests.withWorldRestored(helper, () -> {
                    BlockPos pos = surface(helper, startRun(helper));
                    level(helper).setBlockAndUpdate(pos, Blocks.DIAMOND_BLOCK.defaultBlockState());
                    BigInteger before = WorldEmc.balance(server);

                    PrestigeGameTests.runPrestigeCommand(helper);

                    helper.assertTrue(level(helper).getBlockState(pos).isAir(),
                            "The placed diamond block should be swept, found " + level(helper).getBlockState(pos));
                    // ProjectE's default value for a diamond block: 9 diamonds at 8192.
                    assertCredited(helper, before, 73728);
                }))
                .thenSucceed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "sweep_ore", timeoutTicks = 400)
    public static void untouchedOreIsKeptAndCreditsNothing(GameTestHelper helper) {
        GameTests.rebuildEmcMap(helper);
        MinecraftServer server = helper.getLevel().getServer();
        helper.startSequence()
                .thenWaitUntil(() -> GameTests.assertEmcMapBuilt(helper))
                .thenExecute(() -> PrestigeGameTests.withWorldRestored(helper, () -> {
                    // ProjectE's OreBlacklistMapper gives everything in c:ores 0 EMC, so an ore is kept whatever the sweep
                    // thinks of it. The amethyst block stands in for an ore with a value (as GT ores will have): it is
                    // natural (geodes) and valued, so keeping it is the sweep's decision.
                    long amethystEmc = IEMCProxy.INSTANCE.getValue(Items.AMETHYST_BLOCK);
                    helper.assertTrue(amethystEmc > 0, "Amethyst blocks need an EMC value for this test to mean anything");
                    RunArea area = startRun(helper);
                    ServerLevel level = level(helper);
                    BlockPos ore = surface(helper, area).below();
                    BlockPos valuable = ore.east(2);
                    BlockPos placed = ore.east(4);
                    level.setBlockAndUpdate(ore, Blocks.DIAMOND_ORE.defaultBlockState());
                    level.setBlockAndUpdate(valuable, Blocks.AMETHYST_BLOCK.defaultBlockState());
                    // The test world is flat, with no ore or geodes: put some in, then take the Run start snapshot
                    // again so they are part of the Run area's original terrain.
                    RunSnapshots.take(server, area);
                    // Control: the same valuable block placed during the Run is swept.
                    level.setBlockAndUpdate(placed, Blocks.AMETHYST_BLOCK.defaultBlockState());
                    BigInteger before = WorldEmc.balance(server);

                    PrestigeGameTests.runPrestigeCommand(helper);

                    helper.assertTrue(level.getBlockState(ore).is(Blocks.DIAMOND_ORE),
                            "Untouched ore should be kept, found " + level.getBlockState(ore));
                    helper.assertTrue(level.getBlockState(valuable).is(Blocks.AMETHYST_BLOCK),
                            "An untouched natural block with EMC should be kept, found " + level.getBlockState(valuable));
                    helper.assertTrue(level.getBlockState(placed).isAir(),
                            "The amethyst block placed during the Run should be swept, found " + level.getBlockState(placed));
                    assertCredited(helper, before, amethystEmc);
                }))
                .thenSucceed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "sweep_same_block", timeoutTicks = 400)
    public static void cropGrowthAndFenceConnectionsAreIgnored(GameTestHelper helper) {
        GameTests.rebuildEmcMap(helper);
        MinecraftServer server = helper.getLevel().getServer();
        helper.startSequence()
                .thenWaitUntil(() -> GameTests.assertEmcMapBuilt(helper))
                .thenExecute(() -> PrestigeGameTests.withWorldRestored(helper, () -> {
                    long fenceEmc = IEMCProxy.INSTANCE.getValue(Items.OAK_FENCE);
                    helper.assertTrue(fenceEmc > 0 && IEMCProxy.INSTANCE.getValue(Items.WHEAT_SEEDS) > 0,
                            "Fences and wheat need EMC values for this test to mean anything");
                    RunArea area = startRun(helper);
                    ServerLevel level = level(helper);
                    BlockPos crop = surface(helper, area);
                    BlockPos fence = crop.east(2);
                    level.setBlockAndUpdate(crop.below(), Blocks.FARMLAND.defaultBlockState());
                    level.setBlockAndUpdate(crop, Blocks.WHEAT.defaultBlockState());
                    level.setBlockAndUpdate(fence, Blocks.OAK_FENCE.defaultBlockState());
                    // As if the farm and fence were there when the Run started (e.g. a village).
                    RunSnapshots.take(server, area);
                    BigInteger before = WorldEmc.balance(server);

                    level.setBlockAndUpdate(crop, ((CropBlock) Blocks.WHEAT).getStateForAge(7));
                    // A fence placed next to the original one connects to it, changing the original's state.
                    level.setBlockAndUpdate(fence.east(), Blocks.OAK_FENCE.defaultBlockState());
                    helper.assertTrue(level.getBlockState(fence).getValue(FenceBlock.EAST),
                            "The original fence should have connected to the new one");

                    PrestigeGameTests.runPrestigeCommand(helper);

                    helper.assertTrue(level.getBlockState(crop).is(Blocks.WHEAT), "Grown wheat should be kept, found "
                            + level.getBlockState(crop));
                    helper.assertTrue(level.getBlockState(fence).is(Blocks.OAK_FENCE),
                            "The original fence should be kept, found " + level.getBlockState(fence));
                    helper.assertTrue(level.getBlockState(fence.east()).isAir(),
                            "The placed fence should be swept, found " + level.getBlockState(fence.east()));
                    assertCredited(helper, before, fenceEmc);
                }))
                .thenSucceed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "sweep_snapshot_overwritten", timeoutTicks = 400)
    public static void eachRunOverwritesTheOnlySnapshot(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        PrestigeGameTests.withWorldRestored(helper, () -> {
            RunArea first = startRun(helper);
            helper.assertTrue(RunSnapshots.area(server).equals(Optional.of(first)),
                    "The snapshot should be of the first Run's area " + first + ", is of " + RunSnapshots.area(server));
            RunArea second = startRun(helper);
            helper.assertTrue(RunSnapshots.area(server).equals(Optional.of(second)),
                    "The snapshot should now be of the second Run's area " + second + ", is of " + RunSnapshots.area(server));
            // The first area is not snapshotted any more: a block placed there now is never swept.
            BlockPos pos = surface(helper, first);
            level(helper).setBlockAndUpdate(pos, Blocks.DIAMOND_BLOCK.defaultBlockState());
            PrestigeGameTests.runPrestigeCommand(helper);
            helper.assertTrue(level(helper).getBlockState(pos).is(Blocks.DIAMOND_BLOCK),
                    "A Run area from before the last Run start should never be swept");
            level(helper).setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        });
        helper.succeed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "sweep_barrel", timeoutTicks = 400)
    public static void emcBarrelIsSweptAtTheBlocksValueWithoutDrainingTheBalance(GameTestHelper helper) {
        GameTests.rebuildEmcMap(helper);
        MinecraftServer server = helper.getLevel().getServer();
        helper.startSequence()
                .thenWaitUntil(() -> GameTests.assertEmcMapBuilt(helper))
                .thenExecute(() -> PrestigeGameTests.withWorldRestored(helper, () -> {
                    long barrelEmc = IEMCProxy.INSTANCE.getValue(EmcBarrels.ITEM.get());
                    helper.assertTrue(barrelEmc > 0, "The EMC barrel needs an EMC value for this test to mean anything");
                    WorldEmc.addEmc(server, 1_000_000);
                    WorldEmc.learn(server, new ItemStack(Items.DIAMOND));
                    BlockPos pos = surface(helper, startRun(helper));
                    ServerLevel level = level(helper);
                    level.setBlockAndUpdate(pos, EmcBarrels.BLOCK.get().defaultBlockState());
                    helper.assertTrue(level.getBlockEntity(pos) instanceof EmcBarrelBlockEntity barrel
                            && barrel.configure(new ItemStack(Items.DIAMOND)), "The barrel should take diamonds");
                    BigInteger before = WorldEmc.balance(server);

                    PrestigeGameTests.runPrestigeCommand(helper);

                    helper.assertTrue(level.getBlockState(pos).isAir(), "The barrel should be swept, found "
                            + level.getBlockState(pos));
                    assertCredited(helper, before, barrelEmc);
                }))
                .thenSucceed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "sweep_dropped_item", timeoutTicks = 400)
    public static void droppedItemIsRefundedAndRemoved(GameTestHelper helper) {
        GameTests.rebuildEmcMap(helper);
        MinecraftServer server = helper.getLevel().getServer();
        helper.startSequence()
                .thenWaitUntil(() -> GameTests.assertEmcMapBuilt(helper))
                .thenExecute(() -> PrestigeGameTests.withWorldRestored(helper, () -> {
                    BlockPos pos = surface(helper, startRun(helper));
                    ServerLevel level = level(helper);
                    ItemEntity dropped = new ItemEntity(level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5,
                            new ItemStack(Items.DIAMOND, 3));
                    helper.assertTrue(level.addFreshEntity(dropped), "The dropped diamonds should spawn");
                    BigInteger before = WorldEmc.balance(server);

                    PrestigeGameTests.runPrestigeCommand(helper);

                    helper.assertTrue(dropped.isRemoved(), "The dropped diamonds should be swept");
                    // ProjectE's default value for a diamond: 8192.
                    assertCredited(helper, before, 3 * 8192);
                }))
                .thenSucceed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "sweep_margin_fluid", timeoutTicks = 400)
    public static void fluidLeakedIntoTheMarginIsRefunded(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        PrestigeGameTests.withWorldRestored(helper, () -> {
            // Two chunks east of the Run area's middle: past the border, in the one-chunk margin.
            BlockPos leaked = surface(helper, startRun(helper)).east(32);
            ServerLevel level = level(helper);
            level.setBlockAndUpdate(leaked, Blocks.LAVA.defaultBlockState());
            BigInteger before = WorldEmc.balance(server);

            PrestigeGameTests.runPrestigeCommand(helper);

            helper.assertTrue(level.getBlockState(leaked).isAir(),
                    "The lava source leaked into the margin should be swept, found " + level.getBlockState(leaked));
            // The GameTest datapack values lava at 1500 per 1000 mB, and a source block is 1000 mB.
            assertCredited(helper, before, 1500);
        });
        helper.succeed();
    }

    /** Prestiges to start a Run in a new Run area and returns it. */
    static RunArea startRun(GameTestHelper helper) {
        PrestigeGameTests.runPrestigeCommand(helper);
        return RunAreas.current(helper.getLevel().getServer()).orElseThrow(() -> new GameTestAssertException("No Run area"));
    }

    /** The first air block above the surface at the middle of the Run area. */
    static BlockPos surface(GameTestHelper helper, RunArea area) {
        return RunAreas.spawnPoint(level(helper), area);
    }

    static ServerLevel level(GameTestHelper helper) {
        return helper.getLevel().getServer().overworld();
    }

    static void assertCredited(GameTestHelper helper, BigInteger before, long emc) {
        BigInteger expected = before.add(BigInteger.valueOf(emc));
        BigInteger balance = WorldEmc.balance(helper.getLevel().getServer());
        helper.assertTrue(balance.equals(expected), "The sweep should credit " + emc + " EMC: balance should be "
                + expected + ", got " + balance + " (credited " + balance.subtract(before) + ")");
    }
}
