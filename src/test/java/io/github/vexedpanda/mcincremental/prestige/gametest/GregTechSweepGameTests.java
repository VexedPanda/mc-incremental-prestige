package io.github.vexedpanda.mcincremental.prestige.gametest;

import static io.github.vexedpanda.mcincremental.prestige.gametest.PrestigeSweepGameTests.assertCredited;
import static io.github.vexedpanda.mcincremental.prestige.gametest.PrestigeSweepGameTests.level;
import static io.github.vexedpanda.mcincremental.prestige.gametest.PrestigeSweepGameTests.startRun;
import static io.github.vexedpanda.mcincremental.prestige.gametest.PrestigeSweepGameTests.surface;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine;

import io.github.vexedpanda.mcincremental.prestige.McIncrementalPrestige;
import io.github.vexedpanda.mcincremental.prestige.emc.WorldEmc;
import io.github.vexedpanda.mcincremental.prestige.run.RunArea;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The Prestige sweep of GregTech machines and a formed GT multiblock (VexedPanda/Mc-incremental#30). GregTech is a
 * dev-run-only dependency (build.gradle): these tests live in the test source set, which alone compiles against GT's
 * API, and they use it only to face the controller and check that the structure formed. Contents go in through NeoForge
 * capabilities, as a pipe would. Block EMC values are pinned in
 * {@code src/test/resources/.../pe_custom_conversions/gametest.json}; fluid values in {@code fluid_emc/gametest.json}.
 */
@GameTestHolder(McIncrementalPrestige.MODID)
@PrefixGameTestTemplate(false)
public class GregTechSweepGameTests {
    private static final long CASING = 100, COIL = 200, EBF = 5000, ENERGY_HATCH = 300, MAINTENANCE = 400, MUFFLER = 500,
            INPUT_BUS = 600, OUTPUT_BUS = 700, INPUT_HATCH = 800, ELECTRIC_FURNACE = 900;

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "sweep_gt_multiblock", timeoutTicks = 1200)
    public static void formedElectricBlastFurnaceIsRefundedWholeWithItsBusesAndHatches(GameTestHelper helper) {
        GameTests.rebuildEmcMap(helper);
        MinecraftServer server = helper.getLevel().getServer();
        helper.startSequence()
                .thenWaitUntil(() -> GameTests.assertEmcMapBuilt(helper))
                .thenExecute(() -> PrestigeGameTests.withWorldRestored(helper, () -> {
                    RunArea area = startRun(helper);
                    ServerLevel level = level(helper);
                    BlockPos controller = surface(helper, area);
                    List<BlockPos> structure = buildElectricBlastFurnace(level, controller);
                    MultiblockControllerMachine ebf = (MultiblockControllerMachine) MetaMachine.getMachine(level, controller);
                    ebf.checkAndFormStructure();
                    helper.assertTrue(ebf.isFormed(), "The EBF should form: " + ebf.getDefaultPatternState().getErrors());
                    // Items in the input bus (as a pipe would put them) and the output bus (as a finished recipe would).
                    IItemHandler input = level.getCapability(Capabilities.ItemHandler.BLOCK, inputBus(controller), null);
                    ItemStack rest = ItemHandlerHelper.insertItem(input, new ItemStack(Items.IRON_INGOT, 10), false);
                    helper.assertTrue(rest.isEmpty(), "The input bus should take the iron, left " + rest);
                    IItemHandler output = level.getCapability(Capabilities.ItemHandler.BLOCK, outputBus(controller), null);
                    helper.assertTrue(output instanceof IItemHandlerModifiable, "The output bus handler should be modifiable");
                    ((IItemHandlerModifiable) output).setStackInSlot(0, new ItemStack(Items.DIAMOND, 3));
                    IFluidHandler hatch = level.getCapability(Capabilities.FluidHandler.BLOCK, inputHatch(controller), null);
                    int filled = hatch.fill(new FluidStack(Fluids.LAVA, 4000), IFluidHandler.FluidAction.EXECUTE);
                    helper.assertTrue(filled == 4000, "The input hatch should take 4000 mB of lava, took " + filled);
                    BigInteger before = WorldEmc.balance(server);

                    PrestigeGameTests.runPrestigeCommand(helper);

                    for (BlockPos pos : structure) {
                        helper.assertTrue(level.getBlockState(pos).isAir(), "Every EBF block should be swept, found "
                                + level.getBlockState(pos) + " at " + pos);
                    }
                    PrestigeSweepInventoryGameTests.assertNoItemEntities(helper, area);
                    // 11 casings, 16 coils, the controller, 5 hatches and buses; 10 iron, 3 diamonds; 4000 mB lava.
                    assertCredited(helper, before, 11 * CASING + 16 * COIL + EBF + ENERGY_HATCH + MAINTENANCE + MUFFLER
                            + INPUT_BUS + OUTPUT_BUS + INPUT_HATCH + 10 * 256L + 3 * 8192L + 6000);
                }))
                .thenSucceed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "sweep_gt_machine", timeoutTicks = 1200)
    public static void gtMachineContentsAreRefunded(GameTestHelper helper) {
        GameTests.rebuildEmcMap(helper);
        MinecraftServer server = helper.getLevel().getServer();
        helper.startSequence()
                .thenWaitUntil(() -> GameTests.assertEmcMapBuilt(helper))
                .thenExecute(() -> PrestigeGameTests.withWorldRestored(helper, () -> {
                    RunArea area = startRun(helper);
                    ServerLevel level = level(helper);
                    BlockPos pos = surface(helper, area);
                    level.setBlockAndUpdate(pos, gt("lv_electric_furnace").defaultBlockState());
                    IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
                    ItemStack rest = ItemHandlerHelper.insertItem(handler, new ItemStack(Items.IRON_INGOT, 5), false);
                    helper.assertTrue(rest.isEmpty(), "The furnace should take the iron, left " + rest);
                    BigInteger before = WorldEmc.balance(server);

                    PrestigeGameTests.runPrestigeCommand(helper);

                    helper.assertTrue(level.getBlockState(pos).isAir(), "The GT furnace should be swept");
                    PrestigeSweepInventoryGameTests.assertNoItemEntities(helper, area);
                    assertCredited(helper, before, ELECTRIC_FURNACE + 5 * 256L);
                }))
                .thenSucceed();
    }

    /**
     * An Electric Blast Furnace (3 wide, 3 deep, 4 high) with its controller at {@code controller}, facing north, and
     * the rest of the structure south of it. Returns every position it occupies.
     */
    private static List<BlockPos> buildElectricBlastFurnace(ServerLevel level, BlockPos controller) {
        List<BlockPos> placed = new ArrayList<>();
        BlockPos centre = controller.south();
        for (int y = 0; y < 4; y++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos pos = centre.offset(dx, y, dz);
                    boolean middle = dx == 0 && dz == 0;
                    Block block;
                    if (y == 1 || y == 2) {
                        if (middle) continue;
                        block = gt("cupronickel_coil_block");
                    } else if (y == 3 && middle) {
                        block = gt("lv_muffler_hatch");
                    } else {
                        block = gt("heatproof_machine_casing");
                    }
                    level.setBlockAndUpdate(pos, block.defaultBlockState());
                    placed.add(pos);
                }
            }
        }
        // Hatches and buses in the bottom layer, in place of casings; the controller last, so it loads into a whole
        // structure.
        place(level, inputBus(controller), "lv_input_bus", Direction.WEST);
        place(level, outputBus(controller), "lv_output_bus", Direction.EAST);
        place(level, inputHatch(controller), "lv_input_hatch", Direction.SOUTH);
        place(level, controller.south(2).west(), "lv_energy_input_hatch", Direction.SOUTH);
        place(level, controller.south(2).east(), "maintenance_hatch", Direction.SOUTH);
        MetaMachine.getMachine(level, centre.above(3)).setFrontFacing(Direction.UP);
        place(level, controller, "electric_blast_furnace", Direction.NORTH);
        return placed;
    }

    private static BlockPos inputBus(BlockPos controller) {
        return controller.west();
    }

    private static BlockPos outputBus(BlockPos controller) {
        return controller.east();
    }

    private static BlockPos inputHatch(BlockPos controller) {
        return controller.south(2);
    }

    private static void place(ServerLevel level, BlockPos pos, String id, Direction facing) {
        level.setBlockAndUpdate(pos, gt(id).defaultBlockState());
        MetaMachine.getMachine(level, pos).setFrontFacing(facing);
    }

    private static Block gt(String id) {
        Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("gtceu", id));
        if (block == Blocks.AIR) throw new IllegalStateException("No GregTech block gtceu:" + id);
        return block;
    }
}
