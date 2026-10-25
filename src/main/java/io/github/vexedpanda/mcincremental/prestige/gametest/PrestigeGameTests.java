package io.github.vexedpanda.mcincremental.prestige.gametest;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import io.github.vexedpanda.mcincremental.prestige.McIncrementalPrestige;
import io.github.vexedpanda.mcincremental.prestige.controller.PrestigeItems;
import io.github.vexedpanda.mcincremental.prestige.emc.WorldEmc;
import moze_intel.projecte.api.ItemInfo;
import io.github.vexedpanda.mcincremental.prestige.run.RunArea;
import io.github.vexedpanda.mcincremental.prestige.run.RunAreas;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Prestige: item, command, inventory wipe, new Run area and world border. A Prestige moves every player in the world
 * and the world border, so each test that runs one has its own batch (batches run one after another).
 */
@GameTestHolder(McIncrementalPrestige.MODID)
@PrefixGameTestTemplate(false)
public class PrestigeGameTests {
    @GameTest(template = GameTests.EMPTY_TEMPLATE)
    public static void prestigeItemIsCraftedFromOneDirtSandOrCobblestone(GameTestHelper helper) {
        for (Item input : new Item[] {Items.DIRT, Items.SAND, Items.COBBLESTONE}) {
            ItemStack result = craft(helper, input);
            helper.assertTrue(result.is(PrestigeItems.PRESTIGE.get()),
                    "One " + input + " should craft the Prestige item, got " + result);
        }
        helper.assertFalse(craft(helper, Items.STONE).is(PrestigeItems.PRESTIGE.get()),
                "Stone should not craft the Prestige item");
        helper.succeed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "prestige_wipe", timeoutTicks = 400)
    public static void prestigeWipesInventoryAndKeepsKnowledgeAndEmc(GameTestHelper helper) {
        GameTests.rebuildEmcMap(helper);
        MinecraftServer server = helper.getLevel().getServer();
        helper.startSequence()
                .thenWaitUntil(() -> GameTests.assertEmcMapBuilt(helper))
                .thenExecute(() -> withWorldRestored(helper, () -> {
                    WorldEmc.addEmc(server, 4096);
                    WorldEmc.learn(server, ItemInfo.fromItem(Items.GOLD_INGOT));
                    BigInteger balance = WorldEmc.balance(server);
                    Set<ItemInfo> knowledge = WorldEmc.knowledge(server);

                    ServerPlayer player = GameTests.joinedPlayer(helper, "prestiger");
                    player.getInventory().add(new ItemStack(Items.DIAMOND, 5));
                    player.getInventory().armor.set(0, new ItemStack(Items.IRON_BOOTS));
                    player.getInventory().offhand.set(0, new ItemStack(Items.TORCH));
                    player.containerMenu.setCarried(new ItemStack(Items.STICK));

                    runPrestigeCommand(helper);

                    helper.assertTrue(player.getInventory().isEmpty(), "Inventory should be empty after Prestige");
                    helper.assertTrue(player.containerMenu.getCarried().isEmpty(), "Carried stack should be wiped");
                    helper.assertTrue(WorldEmc.balance(server).equals(balance),
                            "EMC balance should be kept: " + balance + ", got " + WorldEmc.balance(server));
                    helper.assertTrue(WorldEmc.knowledge(server).equals(knowledge), "Knowledge should be kept");
                    helper.assertTrue(WorldEmcGameTests.knowledge(player).getEmc().equals(balance),
                            "The player should still see the world balance");
                    player.discard();
                }))
                .thenSucceed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "prestige_area", timeoutTicks = 400)
    public static void prestigeMovesEveryoneToANewFarRunAreaOnTheGtGrid(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        withWorldRestored(helper, () -> {
            ServerPlayer a = GameTests.joinedPlayer(helper, "a");
            ServerPlayer b = GameTests.joinedPlayer(helper, "b");
            // Two Prestiges, so the second must also avoid the area the first chose.
            for (int i = 0; i < 2; i++) {
                List<RunArea> earlier = RunAreas.visited(server);
                runPrestigeCommand(helper);
                RunArea area = RunAreas.current(server).orElseThrow(() -> new GameTestAssertException("No Run area"));
                ChunkPos c = area.centre();

                helper.assertTrue(Math.floorMod(c.x, 3) == 0 && Math.floorMod(c.z, 3) == 0,
                        "Run area should be centred on a GT grid chunk (x and z multiples of 3), got " + c);
                helper.assertTrue(RunAreas.visited(server).equals(append(earlier, area)),
                        "The new Run area should be recorded as visited after the earlier ones");
                for (RunArea old : earlier) {
                    // "Far": more than 1000 blocks between centres, so the 3x3 areas and their surroundings never overlap.
                    int blocks = 16 * Math.max(Math.abs(old.centre().x - c.x), Math.abs(old.centre().z - c.z));
                    helper.assertTrue(blocks >= 1000, "New Run area " + c + " is only " + blocks
                            + " blocks from earlier Run area " + old.centre());
                }
                for (ServerPlayer p : new ServerPlayer[] {a, b}) {
                    helper.assertTrue(p.level() == server.overworld(), p.getScoreboardName() + " should be in the overworld");
                    int x = p.getBlockX(), z = p.getBlockZ();
                    // A 3x3-chunk area centred on chunk c spans chunks c-1 to c+1.
                    helper.assertTrue(x >= (c.x - 1) * 16 && x < (c.x + 2) * 16 && z >= (c.z - 1) * 16 && z < (c.z + 2) * 16,
                            p.getScoreboardName() + " at " + p.blockPosition() + " should be in the Run area around chunk " + c);
                }
            }
            a.discard();
            b.discard();
        });
        helper.succeed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "prestige_border", timeoutTicks = 400)
    public static void prestigeFitsTheWorldBorderToTheNewRunArea(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        withWorldRestored(helper, () -> {
            runPrestigeCommand(helper);
            ChunkPos c = RunAreas.current(server).orElseThrow(() -> new GameTestAssertException("No Run area")).centre();
            WorldBorder border = server.overworld().getWorldBorder();
            // 3x3 chunks = 48 blocks; the centre chunk's middle is the area's middle.
            helper.assertTrue(border.getCenterX() == c.x * 16 + 8 && border.getCenterZ() == c.z * 16 + 8,
                    "Border centre should be " + (c.x * 16 + 8) + "," + (c.z * 16 + 8) + ", got "
                            + border.getCenterX() + "," + border.getCenterZ());
            helper.assertTrue(border.getSize() == 48, "Border size should be 48, got " + border.getSize());
        });
        helper.succeed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "prestige_item", timeoutTicks = 400)
    public static void usingThePrestigeItemRunsAPrestige(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        withWorldRestored(helper, () -> {
            ServerPlayer player = GameTests.joinedPlayer(helper, "user");
            ItemStack item = new ItemStack(PrestigeItems.PRESTIGE.get());
            player.setItemInHand(InteractionHand.MAIN_HAND, item);
            player.getInventory().add(new ItemStack(Items.COBBLESTONE, 10));
            int runs = RunAreas.visited(server).size();

            player.gameMode.useItem(player, player.serverLevel(), item, InteractionHand.MAIN_HAND);

            helper.assertTrue(RunAreas.visited(server).size() == runs + 1, "Using the item should start a new Run area");
            RunArea area = RunAreas.current(server).orElseThrow();
            helper.assertTrue(area.contains(player.blockPosition()), "The player should be in the new Run area");
            helper.assertTrue(player.getInventory().isEmpty(), "Inventory should be wiped");
            player.discard();
        });
        helper.succeed();
    }

    private static <T> List<T> append(List<T> list, T item) {
        List<T> out = new ArrayList<>(list);
        out.add(item);
        return out;
    }

    /** Runs {@code /prestige} as the server console. */
    static void runPrestigeCommand(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "prestige");
    }

    /**
     * A Prestige moves the world border and world spawn, which are shared by every test and persist in the test world
     * between runs: put them back whatever happens.
     */
    static void withWorldRestored(GameTestHelper helper, Runnable body) {
        ServerLevel level = helper.getLevel().getServer().overworld();
        WorldBorder border = level.getWorldBorder();
        double x = border.getCenterX(), z = border.getCenterZ(), size = border.getSize();
        BlockPos spawn = level.getSharedSpawnPos();
        float spawnAngle = level.getSharedSpawnAngle();
        try {
            body.run();
        } finally {
            border.setCenter(x, z);
            border.setSize(size);
            level.setDefaultSpawnPos(spawn, spawnAngle);
        }
    }

    private static ItemStack craft(GameTestHelper helper, Item input) {
        var level = helper.getLevel();
        CraftingInput grid = CraftingInput.of(1, 1, List.of(new ItemStack(input)));
        return level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, grid, level)
                .map(r -> r.value().assemble(grid, level.registryAccess()))
                .orElse(ItemStack.EMPTY);
    }
}
