package io.github.vexedpanda.mcincremental.prestige.gametest;

import java.math.BigInteger;

import io.github.vexedpanda.mcincremental.prestige.McIncrementalPrestige;
import io.github.vexedpanda.mcincremental.prestige.emc.WorldEmc;
import moze_intel.projecte.api.ItemInfo;
import moze_intel.projecte.api.capabilities.IKnowledgeProvider;
import moze_intel.projecte.api.capabilities.PECapabilities;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The EMC balance and Knowledge belong to the world (ADR-0004): every player sees the same, whoever changed it.
 * The world store is global to the test server, so each test has its own batch (batches run one after another) and
 * uses items no other test learns.
 */
@GameTestHolder(McIncrementalPrestige.MODID)
@PrefixGameTestTemplate(false)
public class WorldEmcGameTests {
    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "world_emc_offline", timeoutTicks = 400)
    public static void emcAndKnowledgeChangedWithNoPlayerOnlineAppearOnNextJoin(GameTestHelper helper) {
        GameTests.rebuildEmcMap(helper);
        helper.startSequence()
                .thenWaitUntil(() -> GameTests.assertEmcMapBuilt(helper))
                .thenExecute(() -> {
                    var server = helper.getLevel().getServer();
                    BigInteger before = WorldEmc.balance(server);
                    WorldEmc.addEmc(server, BigInteger.valueOf(1234));
                    WorldEmc.learn(server, ItemInfo.fromItem(Items.LAPIS_LAZULI));

                    // The joining player's ProjectE data is stale: it must be replaced by the world's.
                    ServerPlayer player = GameTests.newPlayer(helper, "late");
                    IKnowledgeProvider stale = knowledge(player);
                    stale.setEmc(BigInteger.valueOf(999_999));
                    GameTests.join(helper, player);

                    IKnowledgeProvider k = knowledge(player);
                    helper.assertTrue(k.getEmc().equals(before.add(BigInteger.valueOf(1234))),
                            "Joining player should see the world balance " + before + "+1234, got " + k.getEmc());
                    helper.assertTrue(k.hasKnowledge(ItemInfo.fromItem(Items.LAPIS_LAZULI)),
                            "Joining player should know lapis, learned while nobody was online");
                    player.discard();
                })
                .thenSucceed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "world_emc_player_gain", timeoutTicks = 400)
    public static void emcGainedByOnePlayerShowsForEveryPlayer(GameTestHelper helper) {
        GameTests.rebuildEmcMap(helper);
        var server = helper.getLevel().getServer();
        ServerPlayer[] players = new ServerPlayer[2];
        BigInteger[] before = new BigInteger[1];
        helper.startSequence()
                .thenWaitUntil(() -> GameTests.assertEmcMapBuilt(helper))
                .thenExecute(() -> {
                    players[0] = GameTests.joinedPlayer(helper, "burner");
                    players[1] = GameTests.joinedPlayer(helper, "watcher");
                    before[0] = WorldEmc.balance(server);
                    // What a Transmutation Table does when a player burns an item: set that player's ProjectE EMC.
                    IKnowledgeProvider burner = knowledge(players[0]);
                    burner.setEmc(burner.getEmc().add(BigInteger.valueOf(8192)));
                })
                .thenWaitUntil(() -> {
                    BigInteger expected = before[0].add(BigInteger.valueOf(8192));
                    BigInteger watcher = knowledge(players[1]).getEmc();
                    helper.assertTrue(watcher.equals(expected), "Other player should see " + expected + ", got " + watcher);
                    helper.assertTrue(WorldEmc.balance(server).equals(expected),
                            "World balance should be " + expected + ", got " + WorldEmc.balance(server));
                })
                .thenExecute(() -> discard(players))
                .thenSucceed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "world_emc_world_gain", timeoutTicks = 400)
    public static void emcAddedToTheWorldShowsForEveryPlayer(GameTestHelper helper) {
        GameTests.rebuildEmcMap(helper);
        var server = helper.getLevel().getServer();
        helper.startSequence()
                .thenWaitUntil(() -> GameTests.assertEmcMapBuilt(helper))
                .thenExecute(() -> {
                    ServerPlayer a = GameTests.joinedPlayer(helper, "a");
                    ServerPlayer b = GameTests.joinedPlayer(helper, "b");
                    BigInteger before = WorldEmc.balance(server);
                    WorldEmc.addEmc(server, 500);
                    helper.assertTrue(WorldEmc.removeEmc(server, 200), "Removing 200 of the balance should succeed");
                    helper.assertFalse(WorldEmc.removeEmc(server, before.add(BigInteger.valueOf(301))),
                            "Removing more than the balance should fail");
                    BigInteger expected = before.add(BigInteger.valueOf(300));
                    for (ServerPlayer p : new ServerPlayer[] {a, b}) {
                        helper.assertTrue(knowledge(p).getEmc().equals(expected),
                                p.getScoreboardName() + " should see " + expected + ", got " + knowledge(p).getEmc());
                    }
                    discard(a, b);
                })
                .thenSucceed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "world_emc_player_learn", timeoutTicks = 400)
    public static void itemLearnedByOnePlayerIsLearnedForEveryPlayer(GameTestHelper helper) {
        GameTests.rebuildEmcMap(helper);
        var server = helper.getLevel().getServer();
        ItemInfo emerald = ItemInfo.fromItem(Items.EMERALD);
        ServerPlayer[] players = new ServerPlayer[2];
        helper.startSequence()
                .thenWaitUntil(() -> GameTests.assertEmcMapBuilt(helper))
                .thenExecute(() -> {
                    players[0] = GameTests.joinedPlayer(helper, "learner");
                    players[1] = GameTests.joinedPlayer(helper, "watcher");
                    helper.assertFalse(knowledge(players[1]).hasKnowledge(emerald), "Nobody should know emerald yet");
                    // What a Transmutation Table does when a player burns a new item.
                    knowledge(players[0]).addKnowledge(emerald);
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(knowledge(players[1]).hasKnowledge(emerald), "Other player should know emerald");
                    helper.assertTrue(WorldEmc.knows(server, emerald), "The world should know emerald");
                })
                .thenExecute(() -> {
                    // Unlearning (e.g. /projecte knowledge unlearn) is shared too.
                    knowledge(players[1]).removeKnowledge(emerald);
                })
                .thenWaitUntil(() -> {
                    helper.assertFalse(knowledge(players[0]).hasKnowledge(emerald), "Unlearning should reach everyone");
                    helper.assertFalse(WorldEmc.knows(server, emerald), "The world should have forgotten emerald");
                })
                .thenExecute(() -> discard(players))
                .thenSucceed();
    }

    private static void discard(ServerPlayer... players) {
        for (ServerPlayer p : players) p.discard();
    }

    static IKnowledgeProvider knowledge(ServerPlayer player) {
        return player.getCapability(PECapabilities.KNOWLEDGE_CAPABILITY);
    }
}
