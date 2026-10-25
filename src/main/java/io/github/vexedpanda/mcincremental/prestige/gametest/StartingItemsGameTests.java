package io.github.vexedpanda.mcincremental.prestige.gametest;

import java.util.List;

import io.github.vexedpanda.mcincremental.prestige.Config;
import io.github.vexedpanda.mcincremental.prestige.McIncrementalPrestige;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Starting items: what every player is given at the start of every Run and on every join if they lack one. */
@GameTestHolder(McIncrementalPrestige.MODID)
@PrefixGameTestTemplate(false)
public class StartingItemsGameTests {
    static final ResourceLocation TABLET_ID = ResourceLocation.fromNamespaceAndPath("projecte", "transmutation_tablet");

    @GameTest(template = GameTests.EMPTY_TEMPLATE)
    public static void aPlayerJoiningForTheFirstTimeReceivesATransmutationTablet(GameTestHelper helper) {
        ServerPlayer player = GameTests.joinedPlayer(helper, "newcomer");
        helper.assertTrue(count(player, tablet()) == 1,
                "A new player should hold one Transmutation Tablet, got " + count(player, tablet()));
        player.discard();
        helper.succeed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE)
    public static void aReturningPlayerWhoNoLongerHoldsATabletGetsOneOnJoin(GameTestHelper helper) {
        ServerPlayer player = GameTests.joinedPlayer(helper, "loser");
        player.getInventory().clearContent();
        player = rejoin(helper, player);
        helper.assertTrue(count(player, tablet()) == 1,
                "A returning player without a tablet should get one, got " + count(player, tablet()));
        player.discard();
        helper.succeed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE)
    public static void aReturningPlayerHoldingATabletDoesNotGetASecond(GameTestHelper helper) {
        ServerPlayer player = GameTests.joinedPlayer(helper, "keeper");
        // In the offhand, which counts as holding it.
        player.getInventory().clearContent();
        player.getInventory().offhand.set(0, new ItemStack(tablet()));
        player = rejoin(helper, player);
        helper.assertTrue(count(player, tablet()) == 1, "Rejoining should not give a second tablet, got " + count(player, tablet()));
        player.discard();
        helper.succeed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE)
    public static void aNewPlayerAlreadyHoldingATabletIsNotGivenASecond(GameTestHelper helper) {
        ServerPlayer player = GameTests.newPlayer(helper, "holder");
        player.getInventory().add(new ItemStack(tablet()));
        GameTests.join(helper, player);
        helper.assertTrue(count(player, tablet()) == 1, "Should still hold one tablet, got " + count(player, tablet()));
        player.discard();
        helper.succeed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE)
    public static void theStartingItemsAreConfigurable(GameTestHelper helper) {
        List<? extends String> saved = Config.STARTING_ITEMS.get();
        try {
            Config.STARTING_ITEMS.set(List.of(TABLET_ID.toString(), "minecraft:bread"));
            ServerPlayer player = GameTests.joinedPlayer(helper, "configured");
            helper.assertTrue(count(player, tablet()) == 1 && count(player, Items.BREAD) == 1,
                    "Should hold one tablet and one bread, got " + count(player, tablet()) + " and " + count(player, Items.BREAD));
            player.discard();
        } finally {
            Config.STARTING_ITEMS.set(saved);
        }
        helper.succeed();
    }

    /** What a real rejoin does: the player is saved, leaves, and a new player entity is loaded from the save. */
    static ServerPlayer rejoin(GameTestHelper helper, ServerPlayer player) {
        CompoundTag saved = player.saveWithoutId(new CompoundTag());
        player.discard();
        ServerPlayer again = GameTests.newPlayer(helper, player.getScoreboardName());
        again.load(saved);
        return GameTests.join(helper, again);
    }

    static Item tablet() {
        return BuiltInRegistries.ITEM.get(TABLET_ID);
    }

    static int count(ServerPlayer player, Item item) {
        return player.getInventory().countItem(item);
    }
}
