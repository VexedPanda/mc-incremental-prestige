package io.github.vexedpanda.mcincremental.prestige.gametest;

import static io.github.vexedpanda.mcincremental.prestige.gametest.PrestigeSweepGameTests.level;
import static io.github.vexedpanda.mcincremental.prestige.gametest.PrestigeSweepGameTests.startRun;
import static io.github.vexedpanda.mcincremental.prestige.gametest.PrestigeSweepGameTests.surface;

import java.util.ArrayList;
import java.util.List;

import io.github.vexedpanda.mcincremental.prestige.McIncrementalPrestige;
import io.github.vexedpanda.mcincremental.prestige.discovery.Discovery;
import io.github.vexedpanda.mcincremental.prestige.emc.WorldEmc;
import io.github.vexedpanda.mcincremental.prestige.run.RunArea;
import moze_intel.projecte.api.proxy.IEMCProxy;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Discovery (pack ADR-0009, mod {@code docs/discovery.md}), checked through the world's Knowledge: perform the action,
 * then the world knows the resource. Knowledge persists in the test world, so each test forgets its resources first,
 * and each uses resources no other test learns (never nautilus shell or emerald, which other tests need unknown).
 */
@GameTestHolder(McIncrementalPrestige.MODID)
@PrefixGameTestTemplate(false)
public class DiscoveryGameTests {
    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "discovery_pickup", timeoutTicks = 400)
    public static void pickingUpAnItemDiscoversIt(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        GameTests.rebuildEmcMap(helper);
        helper.startSequence()
                .thenWaitUntil(() -> GameTests.assertEmcMapBuilt(helper))
                .thenExecute(() -> {
                    ItemStack feather = new ItemStack(Items.FEATHER);
                    forget(helper, feather);
                    ServerPlayer player = GameTests.joinedPlayer(helper, "picker");
                    try {
                        pickUp(helper, player, new ItemStack(Items.FEATHER, 3));
                        helper.assertTrue(player.getInventory().countItem(Items.FEATHER) == 3, "The player should hold the feathers");
                        helper.assertTrue(WorldEmc.knows(server, feather), "Picking up feathers should discover them");
                    } finally {
                        player.discard();
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "discovery_craft", timeoutTicks = 400)
    public static void craftingAnItemDiscoversIt(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        GameTests.rebuildEmcMap(helper);
        helper.startSequence()
                .thenWaitUntil(() -> GameTests.assertEmcMapBuilt(helper))
                .thenExecute(() -> {
                    ItemStack planks = new ItemStack(Items.BIRCH_PLANKS);
                    forget(helper, planks);
                    ServerPlayer player = GameTests.joinedPlayer(helper, "crafter");
                    try {
                        // The 2x2 grid in the player's inventory screen, then a shift-click on the result.
                        InventoryMenu menu = player.inventoryMenu;
                        menu.getCraftSlots().setItem(0, new ItemStack(Items.BIRCH_LOG));
                        helper.assertTrue(menu.getSlot(InventoryMenu.RESULT_SLOT).getItem().is(Items.BIRCH_PLANKS),
                                "Precondition: a birch log in the grid offers birch planks");
                        menu.quickMoveStack(player, InventoryMenu.RESULT_SLOT);
                        helper.assertTrue(player.getInventory().countItem(Items.BIRCH_PLANKS) == 4, "The player should hold 4 planks");
                        helper.assertTrue(WorldEmc.knows(server, planks), "Crafting birch planks should discover them");
                    } finally {
                        player.discard();
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "discovery_announce", timeoutTicks = 400)
    public static void aNewDiscoveryIsAnnouncedOnceAndWorthlessItemsAreNeverDiscovered(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        GameTests.rebuildEmcMap(helper);
        helper.startSequence()
                .thenWaitUntil(() -> GameTests.assertEmcMapBuilt(helper))
                .thenExecute(() -> {
                    forget(helper, new ItemStack(Items.GLOWSTONE_DUST));
                    ItemStack bedrock = new ItemStack(Items.BEDROCK);
                    helper.assertFalse(IEMCProxy.INSTANCE.hasValue(bedrock), "Precondition: bedrock has no EMC");
                    List<Component> messages = new ArrayList<>();
                    ServerPlayer player = GameTests.joinedRecordingPlayer(helper, "listener", messages);
                    try {
                        pickUp(helper, player, new ItemStack(Items.GLOWSTONE_DUST, 2));
                        pickUp(helper, player, new ItemStack(Items.GLOWSTONE_DUST, 5));
                        pickUp(helper, player, bedrock.copy());

                        List<String> announced = announced(messages);
                        helper.assertTrue(announced.equals(List.of(new ItemStack(Items.GLOWSTONE_DUST).getHoverName().getString())),
                                "Only glowstone dust should be announced, once; announced " + announced);
                        helper.assertFalse(WorldEmc.knows(server, bedrock), "Bedrock has no EMC, so it is never discovered");
                    } finally {
                        player.discard();
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "discovery_sweep", timeoutTicks = 400)
    public static void everythingThePrestigeSweepConvertsIsDiscovered(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        GameTests.rebuildEmcMap(helper);
        helper.startSequence()
                .thenWaitUntil(() -> GameTests.assertEmcMapBuilt(helper))
                .thenExecute(() -> PrestigeGameTests.withWorldRestored(helper, () -> {
                    List<ItemStack> converted = List.of(new ItemStack(Items.BLAZE_ROD), new ItemStack(Items.OBSIDIAN),
                            new ItemStack(Items.FLINT));
                    for (ItemStack stack : converted) forget(helper, stack);
                    forgetFluid(helper, Fluids.LAVA);
                    ServerLevel level = level(helper);
                    BlockPos origin = surface(helper, startRun(helper));
                    // Chest contents, a placed block, a dropped item and a placed fluid source.
                    level.setBlockAndUpdate(origin, Blocks.CHEST.defaultBlockState());
                    ((Container) level.getBlockEntity(origin)).setItem(0, new ItemStack(Items.BLAZE_ROD, 2));
                    level.setBlockAndUpdate(origin.east(2), Blocks.OBSIDIAN.defaultBlockState());
                    level.setBlockAndUpdate(origin.east(4), Blocks.LAVA.defaultBlockState());
                    helper.assertTrue(level.addFreshEntity(new ItemEntity(level, origin.getX() + 0.5, origin.getY(),
                            origin.getZ() - 1.5, new ItemStack(Items.FLINT, 3))), "The flint should spawn");

                    PrestigeGameTests.runPrestigeCommand(helper);

                    for (ItemStack stack : converted) {
                        helper.assertTrue(WorldEmc.knows(server, stack), "The sweep converted " + stack + ", so it should be discovered");
                    }
                    helper.assertTrue(WorldEmc.knowsFluid(server, Fluids.LAVA), "The sweep converted lava, so it should be discovered");
                }))
                .thenSucceed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "discovery_scan_chest", timeoutTicks = 400)
    public static void theScanDiscoversChestContentsInTheRunAreaOnly(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        GameTests.rebuildEmcMap(helper);
        helper.startSequence()
                .thenWaitUntil(() -> GameTests.assertEmcMapBuilt(helper))
                .thenExecute(() -> PrestigeGameTests.withWorldRestored(helper, () -> {
                    ItemStack inside = new ItemStack(Items.GUNPOWDER), outside = new ItemStack(Items.SLIME_BALL);
                    forget(helper, inside);
                    forget(helper, outside);
                    ServerLevel level = level(helper);
                    RunArea area = startRun(helper);
                    BlockPos chest = surface(helper, area);
                    // Control: a chest in a loaded chunk outside the Run area (the test's own structure).
                    BlockPos far = helper.absolutePos(new BlockPos(1, 1, 1));
                    helper.assertFalse(area.containsChunk(new net.minecraft.world.level.ChunkPos(far), 0),
                            "Precondition: the test structure is outside the Run area");
                    for (BlockPos pos : List.of(chest, far)) level.setBlockAndUpdate(pos, Blocks.CHEST.defaultBlockState());
                    ((Container) level.getBlockEntity(chest)).setItem(5, inside.copyWithCount(7));
                    ((Container) level.getBlockEntity(far)).setItem(5, outside.copyWithCount(7));

                    Discovery.scan(server);

                    helper.assertTrue(WorldEmc.knows(server, inside), "The scan should discover gunpowder in a Run area chest");
                    helper.assertFalse(WorldEmc.knows(server, outside), "The scan must not look outside the Run area");
                    level.setBlockAndUpdate(far, Blocks.AIR.defaultBlockState());
                }))
                .thenSucceed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "discovery_scan_tank", timeoutTicks = 400)
    public static void theScanDiscoversTankFluidsButNeverWater(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        GameTests.rebuildEmcMap(helper);
        helper.startSequence()
                .thenWaitUntil(() -> GameTests.assertEmcMapBuilt(helper))
                .thenExecute(() -> PrestigeGameTests.withWorldRestored(helper, () -> {
                    forgetFluid(helper, Fluids.LAVA);
                    WorldEmc.forgetFluid(server, Fluids.WATER);
                    ServerLevel level = level(helper);
                    BlockPos lava = surface(helper, startRun(helper)), water = lava.east(2);
                    fillDrum(helper, level, lava, new FluidStack(Fluids.LAVA, 4000));
                    fillDrum(helper, level, water, new FluidStack(Fluids.WATER, 4000));

                    Discovery.scan(server);

                    helper.assertTrue(WorldEmc.knowsFluid(server, Fluids.LAVA), "The scan should discover lava in a Run area drum");
                    helper.assertFalse(WorldEmc.knowsFluid(server, Fluids.WATER), "Water has no EMC, so it is never discovered");
                }))
                .thenSucceed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "discovery_scan_held", timeoutTicks = Discovery.SCAN_INTERVAL_TICKS + 200)
    public static void theEvery30SecondScanDiscoversWhatAPlayerHolds(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        GameTests.rebuildEmcMap(helper);
        ItemStack leather = new ItemStack(Items.LEATHER);
        ServerPlayer[] player = new ServerPlayer[1];
        helper.startSequence()
                .thenWaitUntil(() -> GameTests.assertEmcMapBuilt(helper))
                .thenExecute(() -> {
                    forget(helper, leather);
                    player[0] = GameTests.joinedPlayer(helper, "holder");
                    // However it got there (a command, a container, a trade): no event discovers it.
                    player[0].getInventory().add(leather.copyWithCount(4));
                    helper.assertFalse(WorldEmc.knows(server, leather), "Precondition: holding alone hasn't discovered it yet");
                })
                .thenWaitUntil(() -> helper.assertTrue(WorldEmc.knows(server, leather), "The scan should discover held leather"))
                .thenExecute(() -> player[0].discard())
                .thenSucceed();
    }

    /** Forgets {@code stack} and checks the precondition that it has EMC and is unknown. */
    static void forget(GameTestHelper helper, ItemStack stack) {
        MinecraftServer server = helper.getLevel().getServer();
        helper.assertTrue(IEMCProxy.INSTANCE.hasValue(stack), "Precondition: " + stack + " has EMC");
        WorldEmc.forget(server, stack);
        helper.assertFalse(WorldEmc.knows(server, stack), "Precondition: the world doesn't know " + stack);
    }

    private static void forgetFluid(GameTestHelper helper, Fluid fluid) {
        WorldEmc.forgetFluid(helper.getLevel().getServer(), fluid);
        helper.assertFalse(WorldEmc.knowsFluid(helper.getLevel().getServer(), fluid), "Precondition: " + fluid + " is unknown");
    }

    /** Drops {@code stack} on the player and has the player walk into it, as a real pickup does. */
    static void pickUp(GameTestHelper helper, ServerPlayer player, ItemStack stack) {
        ItemEntity item = new ItemEntity(player.level(), player.getX(), player.getY(), player.getZ(), stack);
        item.setNoPickUpDelay();
        player.level().addFreshEntity(item);
        item.playerTouch(player);
        helper.assertTrue(item.isRemoved(), "The item entity should be picked up whole");
    }

    /** Places a GregTech steel drum (a tank with a block entity) and fills it through its fluid handler. */
    private static void fillDrum(GameTestHelper helper, ServerLevel level, BlockPos pos, FluidStack fluid) {
        Block drum = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("gtceu", "steel_drum"));
        helper.assertTrue(drum != Blocks.AIR, "GregTech's steel drum should be loaded in GameTests");
        level.setBlockAndUpdate(pos, drum.defaultBlockState());
        IFluidHandler tank = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, null);
        helper.assertTrue(tank != null && tank.fill(fluid, FluidAction.EXECUTE) == fluid.getAmount(), "The drum should take " + fluid);
    }

    /** The names in every Discovery announcement, in order. */
    private static List<String> announced(List<Component> messages) {
        List<String> names = new ArrayList<>();
        for (Component message : messages) {
            if (message.getContents() instanceof TranslatableContents t && t.getKey().equals(Discovery.DISCOVERED_KEY)) {
                names.add(t.getArgs()[0] instanceof Component c ? c.getString() : String.valueOf(t.getArgs()[0]));
            }
        }
        return names;
    }
}
