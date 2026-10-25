package io.github.vexedpanda.mcincremental.prestige.gametest;

import static io.github.vexedpanda.mcincremental.prestige.gametest.PrestigeSweepGameTests.assertCredited;
import static io.github.vexedpanda.mcincremental.prestige.gametest.PrestigeSweepGameTests.level;
import static io.github.vexedpanda.mcincremental.prestige.gametest.PrestigeSweepGameTests.startRun;
import static io.github.vexedpanda.mcincremental.prestige.gametest.PrestigeSweepGameTests.surface;

import java.math.BigInteger;
import java.util.List;

import io.github.vexedpanda.mcincremental.prestige.McIncrementalPrestige;
import io.github.vexedpanda.mcincremental.prestige.emc.WorldEmc;
import io.github.vexedpanda.mcincremental.prestige.run.RunArea;
import io.github.vexedpanda.mcincremental.prestige.sweep.RunSnapshots;
import moze_intel.projecte.api.proxy.IEMCProxy;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The Prestige sweep of inventories, tanks and fluid sources (VexedPanda/Mc-incremental#30), and the ender chest wipe.
 * Same pattern as {@link PrestigeSweepGameTests}: Prestige to start a Run, change the Run area, Prestige again, check.
 * Expected values are ProjectE's defaults (diamond 8192, iron ingot 256, coal 128) and the GameTest fluid values
 * ({@code src/test/resources/.../fluid_emc/gametest.json}: lava 1500 per 1000 mB, water unlisted).
 */
@GameTestHolder(McIncrementalPrestige.MODID)
@PrefixGameTestTemplate(false)
public class PrestigeSweepInventoryGameTests {
    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "sweep_inventories", timeoutTicks = 400)
    public static void chestAndMachineContentsAreCreditedAtFullEmc(GameTestHelper helper) {
        GameTests.rebuildEmcMap(helper);
        MinecraftServer server = helper.getLevel().getServer();
        helper.startSequence()
                .thenWaitUntil(() -> GameTests.assertEmcMapBuilt(helper))
                .thenExecute(() -> PrestigeGameTests.withWorldRestored(helper, () -> {
                    long chestEmc = IEMCProxy.INSTANCE.getValue(Items.CHEST);
                    long furnaceEmc = IEMCProxy.INSTANCE.getValue(Items.FURNACE);
                    helper.assertTrue(chestEmc > 0 && furnaceEmc > 0, "Chests and furnaces need EMC values");
                    RunArea area = startRun(helper);
                    ServerLevel level = level(helper);
                    BlockPos origin = surface(helper, area);
                    BlockPos oldChest = origin.north(3);
                    BlockPos left = origin, right = origin.east(), furnace = origin.east(3);
                    // A chest that was there at Run start (e.g. one the players emptied of its loot): its contents
                    // are credited and taken, the chest itself is kept.
                    level.setBlockAndUpdate(oldChest, Blocks.CHEST.defaultBlockState());
                    RunSnapshots.take(server, area);
                    container(level, oldChest).setItem(0, new ItemStack(Items.DIAMOND, 2));
                    // A placed double chest and a furnace part-way through smelting.
                    level.setBlockAndUpdate(left, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.TYPE, ChestType.LEFT));
                    level.setBlockAndUpdate(right, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.TYPE, ChestType.RIGHT));
                    container(level, left).setItem(0, new ItemStack(Items.DIAMOND, 5));
                    container(level, right).setItem(26, new ItemStack(Items.IRON_INGOT, 64));
                    level.setBlockAndUpdate(furnace, Blocks.FURNACE.defaultBlockState());
                    FurnaceBlockEntity f = (FurnaceBlockEntity) level.getBlockEntity(furnace);
                    f.setItem(0, new ItemStack(Items.IRON_INGOT, 3));
                    f.setItem(1, new ItemStack(Items.COAL, 2));
                    f.setItem(2, new ItemStack(Items.DIAMOND, 1));
                    BigInteger before = WorldEmc.balance(server);

                    PrestigeGameTests.runPrestigeCommand(helper);

                    for (BlockPos pos : List.of(left, right, furnace)) {
                        helper.assertTrue(level.getBlockState(pos).isAir(), "Placed block at " + pos + " should be swept");
                    }
                    helper.assertTrue(level.getBlockState(oldChest).is(Blocks.CHEST), "The Run start chest should be kept");
                    helper.assertTrue(container(level, oldChest).isEmpty(), "The Run start chest should be emptied");
                    assertNoItemEntities(helper, area);
                    assertCredited(helper, before, 2 * chestEmc + furnaceEmc
                            + (2 + 5 + 1) * 8192L + (64 + 3) * 256L + 2 * 128L);
                }))
                .thenSucceed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "sweep_fluids", timeoutTicks = 400)
    public static void tankAndPlacedFluidSourceAreCreditedAtTheirFluidEmc(GameTestHelper helper) {
        GameTests.rebuildEmcMap(helper);
        MinecraftServer server = helper.getLevel().getServer();
        helper.startSequence()
                .thenWaitUntil(() -> GameTests.assertEmcMapBuilt(helper))
                .thenExecute(() -> PrestigeGameTests.withWorldRestored(helper, () -> {
                    long cauldronEmc = IEMCProxy.INSTANCE.getValue(Items.CAULDRON);
                    helper.assertTrue(cauldronEmc > 0, "Cauldrons need an EMC value");
                    ServerLevel level = level(helper);
                    BlockPos origin = surface(helper, startRun(helper));
                    BlockPos source = origin, cauldron = origin.east(3);
                    level.setBlockAndUpdate(source, Blocks.LAVA.defaultBlockState());
                    // A lava cauldron is a 1000 mB lava tank (NeoForge's cauldron fluid handler).
                    level.setBlockAndUpdate(cauldron, Blocks.LAVA_CAULDRON.defaultBlockState());
                    BigInteger before = WorldEmc.balance(server);
                    long fraction = WorldEmc.fractionalMilliEmc(server);

                    PrestigeGameTests.runPrestigeCommand(helper);

                    helper.assertTrue(level.getBlockState(source).isAir(), "The placed lava should be swept, found "
                            + level.getBlockState(source));
                    helper.assertTrue(level.getBlockState(cauldron).isAir(), "The lava cauldron should be swept");
                    // 2000 mB of lava at 1500 per 1000 mB: 3000 EMC exactly, the fractional EMC is untouched.
                    assertCredited(helper, before, cauldronEmc + 3000);
                    helper.assertTrue(WorldEmc.fractionalMilliEmc(server) == fraction, "Fractional EMC should not change");
                }))
                .thenSucceed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "sweep_worthless_fluid", timeoutTicks = 400)
    public static void fluidWithNoEmcValueIsIgnored(GameTestHelper helper) {
        GameTests.rebuildEmcMap(helper);
        MinecraftServer server = helper.getLevel().getServer();
        helper.startSequence()
                .thenWaitUntil(() -> GameTests.assertEmcMapBuilt(helper))
                .thenExecute(() -> PrestigeGameTests.withWorldRestored(helper, () -> {
                    long cauldronEmc = IEMCProxy.INSTANCE.getValue(Items.CAULDRON);
                    ServerLevel level = level(helper);
                    BlockPos origin = surface(helper, startRun(helper));
                    BlockPos source = origin, cauldron = origin.east(3);
                    level.setBlockAndUpdate(source, Blocks.WATER.defaultBlockState());
                    level.setBlockAndUpdate(cauldron, Blocks.WATER_CAULDRON.defaultBlockState());
                    BigInteger before = WorldEmc.balance(server);
                    long fraction = WorldEmc.fractionalMilliEmc(server);

                    PrestigeGameTests.runPrestigeCommand(helper);

                    helper.assertTrue(level.getBlockState(source).is(Blocks.WATER), "Water (no EMC value) should be left alone");
                    // The cauldron itself is still swept at its own value; the water in it is worth nothing.
                    assertCredited(helper, before, cauldronEmc);
                    helper.assertTrue(WorldEmc.fractionalMilliEmc(server) == fraction, "Fractional EMC should not change");
                }))
                .thenSucceed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "sweep_loot_chest", timeoutTicks = 400)
    public static void unopenedLootChestIsUntouched(GameTestHelper helper) {
        GameTests.rebuildEmcMap(helper);
        MinecraftServer server = helper.getLevel().getServer();
        helper.startSequence()
                .thenWaitUntil(() -> GameTests.assertEmcMapBuilt(helper))
                .thenExecute(() -> PrestigeGameTests.withWorldRestored(helper, () -> {
                    RunArea area = startRun(helper);
                    ServerLevel level = level(helper);
                    BlockPos pos = surface(helper, area);
                    // A generated dungeon chest whose loot hasn't been rolled yet.
                    level.setBlockAndUpdate(pos, Blocks.CHEST.defaultBlockState());
                    ((ChestBlockEntity) level.getBlockEntity(pos)).setLootTable(BuiltInLootTables.SIMPLE_DUNGEON, 42L);
                    RunSnapshots.take(server, area);
                    BigInteger before = WorldEmc.balance(server);

                    PrestigeGameTests.runPrestigeCommand(helper);

                    helper.assertTrue(level.getBlockEntity(pos) instanceof ChestBlockEntity chest
                            && BuiltInLootTables.SIMPLE_DUNGEON.equals(chest.getLootTable()),
                            "The loot chest should be kept with its loot still unrolled");
                    assertCredited(helper, before, 0);
                }))
                .thenSucceed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "sweep_ender_chest", timeoutTicks = 400)
    public static void enderChestIsCreditedAndEmptied(GameTestHelper helper) {
        GameTests.rebuildEmcMap(helper);
        MinecraftServer server = helper.getLevel().getServer();
        helper.startSequence()
                .thenWaitUntil(() -> GameTests.assertEmcMapBuilt(helper))
                .thenExecute(() -> PrestigeGameTests.withWorldRestored(helper, () -> {
                    startRun(helper);
                    ServerPlayer player = GameTests.joinedPlayer(helper, "ender");
                    try {
                        player.getEnderChestInventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
                        player.getEnderChestInventory().setItem(26, new ItemStack(Items.IRON_INGOT, 10));
                        // The player holds only the starting tablet, worth 0, so the ender chest is the whole credit.
                        BigInteger before = WorldEmc.balance(server);

                        PrestigeGameTests.runPrestigeCommand(helper);

                        helper.assertTrue(player.getEnderChestInventory().isEmpty(), "The ender chest should be emptied");
                        assertCredited(helper, before, 3 * 8192L + 10 * 256L);
                    } finally {
                        player.discard();
                    }
                }))
                .thenSucceed();
    }

    static net.minecraft.world.Container container(ServerLevel level, BlockPos pos) {
        return (net.minecraft.world.Container) level.getBlockEntity(pos);
    }

    /** No dropped items anywhere in the old Run area: nothing the sweep took was spilled instead. */
    static void assertNoItemEntities(GameTestHelper helper, RunArea area) {
        ServerLevel level = level(helper);
        AABB box = new AABB(area.minBlockX(), level.getMinBuildHeight(), area.minBlockZ(),
                area.maxBlockX() + 1, level.getMaxBuildHeight(), area.maxBlockZ() + 1);
        List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, box);
        helper.assertTrue(items.isEmpty(), "Nothing should be dropped in the swept area, found " + items);
    }
}
