package io.github.vexedpanda.mcincremental.prestige.gametest;

import java.math.BigInteger;

import io.github.vexedpanda.mcincremental.prestige.McIncrementalPrestige;
import io.github.vexedpanda.mcincremental.prestige.barrel.EmcBarrelBlockEntity;
import io.github.vexedpanda.mcincremental.prestige.barrel.EmcBarrels;
import io.github.vexedpanda.mcincremental.prestige.emc.WorldEmc;
import moze_intel.projecte.api.proxy.IEMCProxy;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * The EMC barrel, driven the way pipes and players drive it: through its item handler capability and by using the
 * block. The world balance and Knowledge are global to the test server and persist between local runs, so each test
 * has its own batch (batches run one after another), asserts deltas or sets the balance itself, and uses items no
 * other test learns. No player is ever online in these tests.
 */
@GameTestHolder(McIncrementalPrestige.MODID)
@PrefixGameTestTemplate(false)
public class EmcBarrelGameTests {
    private static final BlockPos BARREL = new BlockPos(1, 1, 1);

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "emc_barrel_insert", timeoutTicks = 400)
    public static void insertionCreditsExactlyTheItemsEmcAndLearnsIt(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        GameTests.rebuildEmcMap(helper);
        helper.startSequence()
                .thenWaitUntil(() -> GameTests.assertEmcMapBuilt(helper))
                .thenExecute(() -> {
                    IItemHandler barrel = placeBarrel(helper);
                    BigInteger before = WorldEmc.balance(server);

                    ItemStack rest = barrel.insertItem(0, new ItemStack(Items.QUARTZ, 5), false);

                    helper.assertTrue(rest.isEmpty(), "Barrel should take all the quartz, left " + rest);
                    // ProjectE's default value for nether quartz is 256.
                    BigInteger expected = before.add(BigInteger.valueOf(5 * 256));
                    helper.assertTrue(WorldEmc.balance(server).equals(expected),
                            "Balance should be " + expected + ", got " + WorldEmc.balance(server));
                    helper.assertTrue(WorldEmc.knows(server, new ItemStack(Items.QUARTZ)), "The world should know quartz");
                })
                .thenSucceed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "emc_barrel_extract", timeoutTicks = 400)
    public static void extractionDebitsExactlyTheItemsEmcAndStopsWhenTheBalanceIsShort(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        GameTests.rebuildEmcMap(helper);
        helper.startSequence()
                .thenWaitUntil(() -> GameTests.assertEmcMapBuilt(helper))
                .thenExecute(() -> {
                    helper.assertTrue(server.getPlayerList().getPlayerCount() == 0, "No player should be online");
                    IItemHandler barrel = placeBarrel(helper);
                    WorldEmc.learn(server, new ItemStack(Items.GOLD_INGOT));
                    configure(helper, Items.GOLD_INGOT);
                    // ProjectE's default value for a gold ingot is 2048. Leave room for two and a bit.
                    withBalance(server, BigInteger.valueOf(2 * 2048 + 100), () -> {
                        ItemStack simulated = barrel.extractItem(0, 3, true);
                        helper.assertTrue(simulated.is(Items.GOLD_INGOT) && simulated.getCount() == 2,
                                "Simulated extraction should offer 2 gold, got " + simulated);
                        assertBalance(helper, server, 2 * 2048 + 100, "after a simulated extraction");

                        ItemStack extracted = barrel.extractItem(0, 3, false);
                        helper.assertTrue(extracted.is(Items.GOLD_INGOT) && extracted.getCount() == 2,
                                "Extraction should hand out the 2 gold the balance pays for, got " + extracted);
                        assertBalance(helper, server, 100, "after extracting 2 gold");

                        ItemStack none = barrel.extractItem(0, 1, false);
                        helper.assertTrue(none.isEmpty(), "Extraction should stop when the balance is short, got " + none);
                        assertBalance(helper, server, 100, "after a refused extraction");
                    });
                })
                .thenSucceed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "emc_barrel_configure", timeoutTicks = 400)
    public static void onlyLearnedItemsCanBeConfigured(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        GameTests.rebuildEmcMap(helper);
        helper.startSequence()
                .thenWaitUntil(() -> GameTests.assertEmcMapBuilt(helper))
                .thenExecute(() -> {
                    // No test ever learns a nautilus shell.
                    ItemStack unlearned = new ItemStack(Items.NAUTILUS_SHELL);
                    helper.assertTrue(IEMCProxy.INSTANCE.hasValue(unlearned), "Precondition: nautilus shell has EMC");
                    helper.assertFalse(WorldEmc.knows(server, unlearned), "Precondition: nobody knows nautilus shell");
                    IItemHandler barrel = placeBarrel(helper);
                    withBalance(server, BigInteger.valueOf(1_000_000), () -> {
                        configure(helper, Items.NAUTILUS_SHELL);
                        helper.assertTrue(barrel.getStackInSlot(0).isEmpty(),
                                "An unlearned item must not be configured, barrel shows " + barrel.getStackInSlot(0));
                        helper.assertTrue(barrel.extractItem(0, 1, false).isEmpty(), "Nothing should come out");

                        WorldEmc.learn(server, new ItemStack(Items.COAL));
                        configure(helper, Items.COAL);
                        helper.assertTrue(barrel.getStackInSlot(0).is(Items.COAL), "A learned item should be configured, barrel shows "
                                + barrel.getStackInSlot(0));

                        // The setting survives the barrel being saved and reloaded (chunk unload, restart).
                        var level = helper.getLevel();
                        BlockPos pos = helper.absolutePos(BARREL);
                        var tag = level.getBlockEntity(pos).saveWithFullMetadata(level.registryAccess());
                        var reloaded = BlockEntity.loadStatic(pos, level.getBlockState(pos), tag, level.registryAccess());
                        helper.assertTrue(reloaded instanceof EmcBarrelBlockEntity b && b.configuredItem() != null
                                && b.configuredItem().createStack().is(Items.COAL), "A reloaded barrel should still hand out coal");
                    });
                })
                .thenSucceed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "emc_barrel_reject", timeoutTicks = 400)
    public static void itemsWithNoEmcAreRejectedNotDestroyed(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        GameTests.rebuildEmcMap(helper);
        helper.startSequence()
                .thenWaitUntil(() -> GameTests.assertEmcMapBuilt(helper))
                .thenExecute(() -> {
                    ItemStack bedrock = new ItemStack(Items.BEDROCK, 3);
                    helper.assertFalse(IEMCProxy.INSTANCE.hasValue(bedrock), "Precondition: bedrock has no EMC");
                    IItemHandler barrel = placeBarrel(helper);
                    BigInteger before = WorldEmc.balance(server);

                    helper.assertFalse(barrel.isItemValid(0, bedrock), "Barrel should not accept bedrock");
                    ItemStack rest = barrel.insertItem(0, bedrock, false);

                    helper.assertTrue(rest.is(Items.BEDROCK) && rest.getCount() == 3, "All the bedrock should come back, got " + rest);
                    helper.assertTrue(bedrock.getCount() == 3, "The offered stack must not be shrunk");
                    helper.assertTrue(WorldEmc.balance(server).equals(before), "Balance should be unchanged");
                    helper.assertFalse(WorldEmc.knows(server, bedrock), "Bedrock should not be learned");
                })
                .thenSucceed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "emc_barrel_round_trip", timeoutTicks = 400)
    public static void insertThenExtractNetsZeroEmc(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        GameTests.rebuildEmcMap(helper);
        helper.startSequence()
                .thenWaitUntil(() -> GameTests.assertEmcMapBuilt(helper))
                .thenExecute(() -> {
                    IItemHandler barrel = placeBarrel(helper);
                    BigInteger before = WorldEmc.balance(server);

                    helper.assertTrue(barrel.insertItem(0, new ItemStack(Items.REDSTONE, 7), false).isEmpty(),
                            "Barrel should take the redstone");
                    configure(helper, Items.REDSTONE);
                    ItemStack out = barrel.extractItem(0, 7, false);

                    helper.assertTrue(out.is(Items.REDSTONE) && out.getCount() == 7, "Should get the 7 redstone back, got " + out);
                    helper.assertTrue(WorldEmc.balance(server).equals(before),
                            "Insert then extract should net zero: balance was " + before + ", now " + WorldEmc.balance(server));
                })
                .thenSucceed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE)
    public static void barrelHasACraftingRecipe(GameTestHelper helper) {
        var id = ResourceLocation.fromNamespaceAndPath(McIncrementalPrestige.MODID, "emc_barrel");
        var recipe = helper.getLevel().getRecipeManager().byKey(id);
        helper.assertTrue(recipe.isPresent(), "Recipe " + id + " should load");
        helper.assertTrue(recipe.get().value().getResultItem(helper.getLevel().registryAccess()).is(EmcBarrels.ITEM.get()),
                "Recipe should make an EMC barrel");
        helper.succeed();
    }

    /** A player right-clicks the barrel holding one of {@code item}. */
    static void configure(GameTestHelper helper, Item item) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item));
        helper.useBlock(BARREL, player);
        helper.assertTrue(player.getMainHandItem().is(item) && player.getMainHandItem().getCount() == 1,
                "Configuring should not take the held item");
    }

    /**
     * Runs {@code body} with the world balance set to exactly {@code balance}, then restores the balance from before.
     * Only safe in a batch of its own: concurrent tests would see the temporary balance.
     */
    static void withBalance(MinecraftServer server, BigInteger balance, Runnable body) {
        BigInteger saved = WorldEmc.balance(server);
        WorldEmc.removeEmc(server, saved);
        WorldEmc.addEmc(server, balance);
        try {
            body.run();
        } finally {
            WorldEmc.removeEmc(server, WorldEmc.balance(server));
            WorldEmc.addEmc(server, saved);
        }
    }

    static void assertBalance(GameTestHelper helper, MinecraftServer server, long expected, String when) {
        BigInteger actual = WorldEmc.balance(server);
        helper.assertTrue(actual.equals(BigInteger.valueOf(expected)), "Balance " + when + " should be " + expected + ", got " + actual);
    }

    /** Places an EMC barrel at {@link #BARREL} and returns its item handler, as a pipe would find it. */
    static IItemHandler placeBarrel(GameTestHelper helper) {
        helper.setBlock(BARREL, EmcBarrels.BLOCK.get());
        IItemHandler handler = helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, helper.absolutePos(BARREL), null);
        helper.assertTrue(handler != null, "EMC barrel should expose an item handler");
        return handler;
    }
}
