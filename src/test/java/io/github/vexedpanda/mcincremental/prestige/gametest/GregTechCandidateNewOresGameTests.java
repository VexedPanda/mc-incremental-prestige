package io.github.vexedpanda.mcincremental.prestige.gametest;

import java.util.ArrayList;
import java.util.List;

import com.gregtechceu.gtceu.common.data.GTOreVeins;

import io.github.vexedpanda.mcincremental.prestige.McIncrementalPrestige;
import io.github.vexedpanda.mcincremental.prestige.controller.CandidateArea;
import io.github.vexedpanda.mcincremental.prestige.controller.CandidateAreas;
import io.github.vexedpanda.mcincremental.prestige.run.RunArea;
import moze_intel.projecte.api.proxy.IEMCProxy;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * "New" marks on Candidate area previews and the bias towards undiscovered resources (VexedPanda/Mc-incremental#79):
 * an ore is new when the world's Knowledge lacks it and the world can harvest it, and at most one candidate of a
 * dimension offers nothing new unless nothing harvestable is left to discover. Every test sets the world's Knowledge
 * and places GT veins in the areas a seeded re-roll will try ({@link CandidateAreaGameTests#predictReRoll}).
 * Knowledge only holds items with EMC, so each test waits for ProjectE's EMC map first.
 */
@GameTestHolder(McIncrementalPrestige.MODID)
@PrefixGameTestTemplate(false)
public class GregTechCandidateNewOresGameTests {
    private static final ResourceLocation GOLD = GtTestVeins.gt("gold");

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "candidates_new_harvest", timeoutTicks = 600)
    public static void aVeinIsMarkedNewOnlyOnceTheWorldCanHarvestIt(GameTestHelper helper) {
        GameTests.rebuildEmcMap(helper);
        helper.startSequence().thenWaitUntil(() -> assertRawGoldValued(helper)).thenExecute(() -> {
            MinecraftServer server = helper.getLevel().getServer();
            ServerLevel level = server.overworld();
            // The pickaxe one tier too low for gold ore, and one of its tier, by the ore block's tool tags.
            BlockState goldOre = BuiltInRegistries.BLOCK.get(GtTestVeins.gt("gold_ore")).defaultBlockState();
            Item tooLow, enough;
            if (goldOre.is(BlockTags.NEEDS_DIAMOND_TOOL)) {
                tooLow = Items.IRON_PICKAXE;
                enough = Items.DIAMOND_PICKAXE;
            } else if (goldOre.is(BlockTags.NEEDS_IRON_TOOL)) {
                tooLow = Items.STONE_PICKAXE;
                enough = Items.IRON_PICKAXE;
            } else if (goldOre.is(BlockTags.NEEDS_STONE_TOOL)) {
                tooLow = Items.WOODEN_PICKAXE;
                enough = Items.STONE_PICKAXE;
            } else {
                throw new GameTestAssertException("GT's gold ore should need a stone, iron or diamond tool, has tags "
                        + goldOre.getTags().map(t -> t.location().toString()).toList());
            }
            PrestigeGameTests.withWorldRestored(helper, () -> {
                List<Component> messages = new ArrayList<>();
                ServerPlayer player = GameTests.joinedRecordingPlayer(helper, "harvester", messages);
                PrestigeGameTests.runPrestigeCommand(helper); // the sweep, so the next Prestige only re-rolls
                long seed = 7901;
                RunArea first = CandidateAreaGameTests.predictReRoll(server, seed).get(0);
                Runnable unplace = GtTestVeins.place(helper, level, first.centre(), GTOreVeins.MAGNETITE_VEIN, "gold");
                try {
                    // A pickaxe below gold ore's tier can't mine it: not new.
                    GameTests.withKnowledge(server, List.of(new ItemStack(tooLow)),
                            () -> CandidateAreaGameTests.reRoll(helper, seed));
                    CandidateArea candidate = CandidateAreas.offered(server).get(0);
                    helper.assertTrue(candidate.area().equals(first), "Candidate 1 should be " + first + ", got " + candidate.area());
                    helper.assertTrue(candidate.preview().veins().size() == 1, "Candidate 1 should list the gold vein, got " + candidate.preview());
                    helper.assertTrue(candidate.preview().newOres().isEmpty(),
                            "Gold the world can't harvest yet isn't new, got " + candidate.preview().newOres());

                    // One of its tier can: new, in the candidate data and the chat listing.
                    messages.clear();
                    GameTests.withKnowledge(server, List.of(new ItemStack(enough)),
                            () -> CandidateAreaGameTests.reRoll(helper, seed));
                    candidate = CandidateAreas.offered(server).get(0);
                    helper.assertTrue(candidate.area().equals(first), "Candidate 1 should be " + first + ", got " + candidate.area());
                    helper.assertTrue(candidate.preview().newOres().equals(List.of(GOLD)),
                            "Gold the world can harvest and hasn't discovered is new, got " + candidate.preview().newOres());
                    Component line = CandidateAreaGameTests.candidateLine(messages, 1);
                    helper.assertTrue(line != null && marksNew(line, GOLD), "Candidate 1's chat line should mark gold new, got " + line);

                    // Once what mining gold ore gives is discovered, it isn't new any more.
                    List<ItemStack> knowledge = new ArrayList<>(goldOreDrops(level, enough));
                    knowledge.add(new ItemStack(enough));
                    GameTests.withKnowledge(server, knowledge, () -> CandidateAreaGameTests.reRoll(helper, seed));
                    candidate = CandidateAreas.offered(server).get(0);
                    helper.assertTrue(candidate.area().equals(first), "Candidate 1 should be " + first + ", got " + candidate.area());
                    helper.assertTrue(candidate.preview().newOres().isEmpty(),
                            "Gold the world has discovered isn't new, got " + candidate.preview().newOres());
                } finally {
                    unplace.run();
                    player.discard();
                }
            });
        }).thenSucceed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "candidates_new_bias", timeoutTicks = 600)
    public static void atMostOneCandidateOffersNothingNew(GameTestHelper helper) {
        GameTests.rebuildEmcMap(helper);
        helper.startSequence().thenWaitUntil(() -> assertRawGoldValued(helper)).thenExecute(() -> {
            MinecraftServer server = helper.getLevel().getServer();
            ServerLevel level = server.overworld();
            PrestigeGameTests.withWorldRestored(helper, () -> {
                PrestigeGameTests.runPrestigeCommand(helper);
                long seed = 7902;
                // The first 3 areas offer nothing new; the next 2 the re-roll tries each have an undiscovered gold vein.
                List<RunArea> tried = CandidateAreaGameTests.predictReRoll(server, seed, CandidateAreas.COUNT + 2);
                Runnable unplaceA = GtTestVeins.place(helper, level, tried.get(3).centre(), GTOreVeins.MAGNETITE_VEIN, "gold");
                Runnable unplaceB = GtTestVeins.place(helper, level, tried.get(4).centre(), GTOreVeins.MAGNETITE_VEIN, "gold");
                try {
                    GameTests.withKnowledge(server, List.of(new ItemStack(Items.IRON_PICKAXE)),
                            () -> CandidateAreaGameTests.reRoll(helper, seed));
                    List<CandidateArea> offered = CandidateAreas.offered(server);
                    helper.assertTrue(offered.size() == CandidateAreas.COUNT, "3 candidates should be offered, got " + offered);
                    long nothingNew = offered.stream().filter(c -> c.preview().newOres().isEmpty()).count();
                    helper.assertTrue(nothingNew <= 1, "At most one candidate may offer nothing new, got " + offered);
                    List<RunArea> expected = List.of(tried.get(0), tried.get(3), tried.get(4));
                    helper.assertTrue(offered.stream().map(CandidateArea::area).toList().equals(expected),
                            "Candidates 2 and 3, offering nothing new, should be replaced by the gold areas " + expected
                                    + ", got " + offered);
                } finally {
                    unplaceA.run();
                    unplaceB.run();
                }
            });
        }).thenSucceed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "candidates_new_bounded", timeoutTicks = 800)
    public static void theBiasGivesUpAfterABoundedNumberOfAreas(GameTestHelper helper) {
        GameTests.rebuildEmcMap(helper);
        helper.startSequence().thenWaitUntil(() -> assertRawGoldValued(helper)).thenExecute(() -> {
            MinecraftServer server = helper.getLevel().getServer();
            PrestigeGameTests.withWorldRestored(helper, () -> {
                PrestigeGameTests.runPrestigeCommand(helper);
                long seed = 7903;
                // Undiscovered gold can be harvested, but no area tried has any ore.
                List<RunArea> bound = CandidateAreaGameTests.predictReRoll(server, seed,
                        CandidateAreas.COUNT + CandidateAreas.MAX_EXTRA_AREAS);
                GameTests.withKnowledge(server, List.of(new ItemStack(Items.IRON_PICKAXE)),
                        () -> CandidateAreaGameTests.reRoll(helper, seed));
                List<CandidateArea> offered = CandidateAreas.offered(server);
                helper.assertTrue(offered.size() == CandidateAreas.COUNT, "3 candidates should still be offered, got " + offered);
                helper.assertTrue(offered.stream().allMatch(c -> bound.contains(c.area())),
                        "Every candidate should be one of the first " + bound.size() + " areas tried " + bound + ", got " + offered);
                helper.assertTrue(offered.get(0).area().equals(bound.get(0)), "Candidate 1 offers nothing new and is kept, got " + offered);
            });
        }).thenSucceed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "candidates_new_waived", timeoutTicks = 800)
    public static void theRuleIsWaivedWhenEverythingIsDiscovered(GameTestHelper helper) {
        GameTests.rebuildEmcMap(helper);
        helper.startSequence().thenWaitUntil(() -> assertRawGoldValued(helper)).thenExecute(() -> {
            MinecraftServer server = helper.getLevel().getServer();
            ServerLevel level = server.overworld();
            PrestigeGameTests.withWorldRestored(helper, () -> {
                PrestigeGameTests.runPrestigeCommand(helper);
                long seed = 7904;
                List<RunArea> predicted = CandidateAreaGameTests.predictReRoll(server, seed);
                Runnable unplace = GtTestVeins.place(helper, level, predicted.get(0).centre(), GTOreVeins.MAGNETITE_VEIN, "gold");
                // Everything with EMC is discovered, including every pickaxe.
                List<ItemStack> everything = new ArrayList<>();
                for (Item item : BuiltInRegistries.ITEM) {
                    ItemStack stack = new ItemStack(item);
                    if (!stack.isEmpty() && IEMCProxy.INSTANCE.hasValue(stack)) everything.add(stack);
                }
                for (ItemStack drop : goldOreDrops(level, Items.DIAMOND_PICKAXE)) {
                    helper.assertTrue(everything.stream().anyMatch(s -> s.is(drop.getItem())),
                            "Gold ore's drop " + drop + " should have EMC, so the world can discover it");
                }
                try {
                    GameTests.withKnowledge(server, everything, () -> CandidateAreaGameTests.reRoll(helper, seed));
                    List<CandidateArea> offered = CandidateAreas.offered(server);
                    helper.assertTrue(offered.stream().map(CandidateArea::area).toList().equals(predicted),
                            "With nothing left to discover the re-roll should offer the first areas " + predicted + ", got " + offered);
                    helper.assertTrue(offered.stream().allMatch(c -> c.preview().newOres().isEmpty()),
                            "Nothing is new once everything is discovered, got " + offered);
                } finally {
                    unplace.run();
                }
            });
        }).thenSucceed();
    }

    /**
     * For {@code thenWaitUntil} after {@link GameTests#rebuildEmcMap}: throws until raw gold, which GT's gold ore drops,
     * has EMC, which holds only once a rebuild with the GameTest ProjectE config is done (a map built earlier may still
     * be in place while the rebuild runs).
     */
    private static void assertRawGoldValued(GameTestHelper helper) {
        GameTests.assertEmcMapBuilt(helper);
        helper.assertTrue(IEMCProxy.INSTANCE.hasValue(new ItemStack(Items.RAW_GOLD)),
                "EMC map not rebuilt with raw gold valued yet (" + IEMCProxy.INSTANCE.getValue(Items.RAW_GOLD) + ")");
    }

    /** What mining GT's gold ore with {@code pickaxe} gives. */
    private static List<ItemStack> goldOreDrops(ServerLevel level, Item pickaxe) {
        BlockState ore = BuiltInRegistries.BLOCK.get(GtTestVeins.gt("gold_ore")).defaultBlockState();
        return Block.getDrops(ore, level, BlockPos.ZERO, null, null, new ItemStack(pickaxe));
    }

    /** Whether the chat line's veins (argument 3) mark this material new. */
    private static boolean marksNew(Component line, ResourceLocation material) {
        Object veins = ((TranslatableContents) line.getContents()).getArgs()[2];
        return veins instanceof Component component && marksNewIn(component, material.toLanguageKey("material"));
    }

    private static boolean marksNewIn(Component component, String materialKey) {
        if (component.getContents() instanceof TranslatableContents t && t.getKey().equals(CandidateAreas.NEW_KEY)
                && t.getArgs().length > 0 && t.getArgs()[0] instanceof Component name
                && name.getContents() instanceof TranslatableContents n && n.getKey().equals(materialKey)) return true;
        for (Object arg : component.getContents() instanceof TranslatableContents t ? t.getArgs() : new Object[0]) {
            if (arg instanceof Component c && marksNewIn(c, materialKey)) return true;
        }
        return component.getSiblings().stream().anyMatch(sibling -> marksNewIn(sibling, materialKey));
    }
}
