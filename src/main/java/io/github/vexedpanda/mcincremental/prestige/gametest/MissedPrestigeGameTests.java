package io.github.vexedpanda.mcincremental.prestige.gametest;

import java.math.BigInteger;

import io.github.vexedpanda.mcincremental.prestige.McIncrementalPrestige;
import io.github.vexedpanda.mcincremental.prestige.emc.WorldEmc;
import io.github.vexedpanda.mcincremental.prestige.run.RunArea;
import io.github.vexedpanda.mcincremental.prestige.run.RunAreas;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * A player offline during a Prestige catches up on their next join, as if they had been there: their inventory and
 * ender chest are refunded and wiped, they get the starting items and are moved into the current Run area. Each test
 * runs a Prestige, so each has its own batch.
 */
@GameTestHolder(McIncrementalPrestige.MODID)
@PrefixGameTestTemplate(false)
public class MissedPrestigeGameTests {
    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "missed_prestige", timeoutTicks = 400)
    public static void aPlayerAbsentDuringAPrestigeIsRefundedWipedAndMovedOnRejoin(GameTestHelper helper) {
        GameTests.rebuildEmcMap(helper);
        MinecraftServer server = helper.getLevel().getServer();
        helper.startSequence()
                .thenWaitUntil(() -> GameTests.assertEmcMapBuilt(helper))
                .thenExecute(() -> PrestigeGameTests.withWorldRestored(helper, () -> {
                    ServerPlayer player = GameTests.joinedPlayer(helper, "absentee");
                    player.getInventory().clearContent();
                    player.getInventory().add(new ItemStack(Items.DIAMOND, 5));
                    player.getEnderChestInventory().addItem(new ItemStack(Items.GOLD_INGOT));
                    CompoundTag saved = leave(player);

                    PrestigeGameTests.runPrestigeCommand(helper);
                    BigInteger before = WorldEmc.balance(server);

                    ServerPlayer back = rejoin(helper, "absentee", saved);

                    // 5 diamonds at 8192 and a gold ingot at 2048 (ProjectE defaults).
                    BigInteger refund = WorldEmc.balance(server).subtract(before);
                    helper.assertTrue(refund.equals(BigInteger.valueOf(5 * 8192 + 2048)),
                            "Inventory and ender chest should be refunded at full EMC (43008), got " + refund);
                    PrestigeGameTests.assertHoldsOnlyOneTablet(helper, back);
                    helper.assertTrue(back.getEnderChestInventory().isEmpty(), "The ender chest should be wiped");
                    RunArea area = RunAreas.current(server).orElseThrow(() -> new GameTestAssertException("No Run area"));
                    helper.assertTrue(area.contains(back.blockPosition()),
                            "The player at " + back.blockPosition() + " should be in the current Run area around " + area.centre());
                    back.discard();
                }))
                .thenSucceed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "missed_no_prestige", timeoutTicks = 400)
    public static void aPlayerPresentForEveryPrestigeIsUnaffectedOnRejoin(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        PrestigeGameTests.withWorldRestored(helper, () -> {
            ServerPlayer player = GameTests.joinedPlayer(helper, "regular");
            PrestigeGameTests.runPrestigeCommand(helper);
            player.getInventory().add(new ItemStack(Items.DIAMOND, 5));
            player.getEnderChestInventory().addItem(new ItemStack(Items.GOLD_INGOT));
            player.moveTo(player.getX() + 3, player.getY(), player.getZ());
            var position = player.blockPosition();
            BigInteger before = WorldEmc.balance(server);

            ServerPlayer back = rejoin(helper, "regular", leave(player));

            helper.assertTrue(WorldEmc.balance(server).equals(before), "Nothing should be refunded, balance changed by "
                    + WorldEmc.balance(server).subtract(before));
            helper.assertTrue(back.getInventory().countItem(Items.DIAMOND) == 5, "The inventory should be kept");
            helper.assertTrue(back.getEnderChestInventory().countItem(Items.GOLD_INGOT) == 1, "The ender chest should be kept");
            helper.assertTrue(back.blockPosition().equals(position),
                    "The player should rejoin where they left, " + position + ", got " + back.blockPosition());
            back.discard();
        });
        helper.succeed();
    }

    /** The player logs out: saved, then removed from the world. */
    static CompoundTag leave(ServerPlayer player) {
        CompoundTag saved = player.saveWithoutId(new CompoundTag());
        player.discard();
        return saved;
    }

    /** The player logs back in: a new player entity loaded from their save joins. */
    static ServerPlayer rejoin(GameTestHelper helper, String name, CompoundTag saved) {
        ServerPlayer again = GameTests.newPlayer(helper, name);
        again.load(saved);
        return GameTests.join(helper, again);
    }
}
