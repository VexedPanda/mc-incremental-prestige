package io.github.vexedpanda.mcincremental.prestige.gametest;

import java.util.ArrayList;
import java.util.List;

import com.gregtechceu.gtceu.api.block.OreBlock;
import com.gregtechceu.gtceu.api.data.worldgen.GTOreDefinition;
import com.gregtechceu.gtceu.api.data.worldgen.ores.GeneratedVeinMetadata;
import com.gregtechceu.gtceu.api.registry.GTRegistries;
import com.gregtechceu.gtceu.common.data.GTOreVeins;
import com.gregtechceu.gtceu.config.ConfigHolder;
import com.gregtechceu.gtceu.integration.map.cache.server.ServerCache;

import io.github.vexedpanda.mcincremental.prestige.McIncrementalPrestige;
import io.github.vexedpanda.mcincremental.prestige.controller.CandidateArea;
import io.github.vexedpanda.mcincremental.prestige.controller.CandidateAreas;
import io.github.vexedpanda.mcincremental.prestige.preview.CandidatePreview;
import io.github.vexedpanda.mcincremental.prestige.preview.OreVein;
import io.github.vexedpanda.mcincremental.prestige.run.RunArea;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Candidate previews list the GT ore veins and ores in a candidate's chunks (VexedPanda/Mc-incremental#77). The
 * GameTest world is superflat, so GT generates no veins: the test places a vein in a candidate's chunks as GT's
 * worldgen would, its ore blocks plus GT's record of the vein, before the re-roll that offers that candidate.
 */
@GameTestHolder(McIncrementalPrestige.MODID)
@PrefixGameTestTemplate(false)
public class GregTechCandidatePreviewGameTests {
    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "candidates_preview_veins", timeoutTicks = 400)
    public static void aCandidatePreviewListsTheGtVeinsAndOresInItsChunks(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        PrestigeGameTests.withWorldRestored(helper, () -> {
            List<Component> messages = new ArrayList<>();
            ServerPlayer player = GameTests.joinedRecordingPlayer(helper, "prospector", messages);
            PrestigeGameTests.runPrestigeCommand(helper); // the sweep, so the next Prestige only re-rolls
            long seed = 7777;
            List<RunArea> predicted = CandidateAreaGameTests.predictReRoll(server, seed);
            ServerLevel level = server.overworld();
            ChunkPos centre = predicted.get(0).centre();
            level.getChunk(centre.x, centre.z);
            // A magnetite vein centred in candidate 1, with a magnetite ore and a gold ore (both of the vein) and a
            // cassiterite ore (of no vein here).
            BlockPos base = new BlockPos(centre.getMinBlockX() + 3, level.getMinBuildHeight() + 6, centre.getMinBlockZ() + 3);
            List<BlockPos> placed = List.of(base, base.east(), base.east(2));
            List<BlockState> before = placed.stream().map(level::getBlockState).toList();
            place(helper, level, placed.get(0), "magnetite");
            place(helper, level, placed.get(1), "gold");
            place(helper, level, placed.get(2), "cassiterite");
            Holder<GTOreDefinition> magnetite = level.registryAccess().registryOrThrow(GTRegistries.Keys.ORE_VEIN)
                    .getHolderOrThrow(GTOreVeins.MAGNETITE_VEIN);
            int grid = ConfigHolder.INSTANCE.worldgen.oreVeins.oreVeinGridSize;
            ServerCache.instance.addVein(level.dimension(), Math.floorDiv(centre.x, grid), Math.floorDiv(centre.z, grid),
                    new GeneratedVeinMetadata(centre, centre.getMiddleBlockPosition(base.getY()), magnetite));
            try {
                messages.clear();
                CandidateAreaGameTests.reRoll(helper, seed);

                List<CandidateArea> offered = CandidateAreas.offered(server);
                helper.assertTrue(offered.stream().map(CandidateArea::area).toList().equals(predicted),
                        "The re-roll should offer the predicted areas " + predicted + ", got " + offered);
                CandidatePreview preview = offered.get(0).preview();
                ResourceLocation magnetiteOre = gt("magnetite");
                ResourceLocation gold = gt("gold");
                ResourceLocation cassiterite = gt("cassiterite");
                helper.assertTrue(preview.ores().equals(List.of(cassiterite, gold, magnetiteOre)),
                        "Candidate 1 should list every ore found, got " + preview.ores());
                helper.assertTrue(preview.veins().equals(List.of(
                                new OreVein(GTOreVeins.MAGNETITE_VEIN.location(), List.of(gold, magnetiteOre)))),
                        "Candidate 1 should list the magnetite vein with its gold and magnetite, got " + preview.veins());
                for (int i = 1; i < offered.size(); i++) {
                    helper.assertTrue(offered.get(i).preview().veins().isEmpty() && offered.get(i).preview().ores().isEmpty(),
                            "Candidate " + (i + 1) + " has no ores, got " + offered.get(i).preview());
                }
                Component line = CandidateAreaGameTests.candidateLine(messages, 1);
                helper.assertTrue(line != null && listsVein(line, GTOreVeins.MAGNETITE_VEIN.location().getPath()),
                        "Candidate 1's chat line should list the magnetite vein, got " + line);
            } finally {
                for (int i = 0; i < placed.size(); i++) level.setBlockAndUpdate(placed.get(i), before.get(i));
                ServerCache.instance.removeAllInChunk(level.dimension(), centre);
                player.discard();
            }
        });
        helper.succeed();
    }

    /** Whether the candidate line's veins (argument 4) name a vein type of this name. */
    private static boolean listsVein(Component line, String name) {
        Object veins = ((TranslatableContents) line.getContents()).getArgs()[4];
        return veins instanceof Component component && namesVein(component, name);
    }

    private static boolean namesVein(Component component, String name) {
        if (component.getContents() instanceof TranslatableContents t && t.getKey().equals(CandidateAreas.VEIN_KEY)
                && String.valueOf(t.getArgs()[0]).equals(name)) return true;
        return component.getSiblings().stream().anyMatch(sibling -> namesVein(sibling, name));
    }

    private static ResourceLocation gt(String path) {
        return ResourceLocation.fromNamespaceAndPath("gtceu", path);
    }

    /** Places the stone ore block of a GT material (e.g. {@code gtceu:magnetite_ore}). */
    private static void place(GameTestHelper helper, ServerLevel level, BlockPos pos, String material) {
        Block ore = BuiltInRegistries.BLOCK.get(gt(material + "_ore"));
        helper.assertTrue(ore instanceof OreBlock, "gtceu:" + material + "_ore should be a GT ore block, got " + ore);
        level.setBlockAndUpdate(pos, ore.defaultBlockState());
    }
}
