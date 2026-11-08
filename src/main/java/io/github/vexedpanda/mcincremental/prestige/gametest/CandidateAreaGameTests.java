package io.github.vexedpanda.mcincremental.prestige.gametest;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.github.vexedpanda.mcincremental.prestige.McIncrementalPrestige;
import io.github.vexedpanda.mcincremental.prestige.controller.CandidateArea;
import io.github.vexedpanda.mcincremental.prestige.controller.CandidateAreas;
import io.github.vexedpanda.mcincremental.prestige.emc.WorldEmc;
import io.github.vexedpanda.mcincremental.prestige.run.RunArea;
import io.github.vexedpanda.mcincremental.prestige.run.RunAreaConfig;
import io.github.vexedpanda.mcincremental.prestige.run.RunAreas;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.structures.BuriedTreasurePieces;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * A Prestige is two steps: {@code /prestige} sweeps and converts, then offers 3 Candidate areas; {@code /prestige
 * choose <n>} starts the Run in candidate n's area. Each test Prestiges, so each has its own batch.
 */
@GameTestHolder(McIncrementalPrestige.MODID)
@PrefixGameTestTemplate(false)
public class CandidateAreaGameTests {
    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "candidates_offered", timeoutTicks = 400)
    public static void prestigeOffersThreeCandidateAreasAndStartsNoRun(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        PrestigeGameTests.withWorldRestored(helper, () -> {
            List<Component> messages = new ArrayList<>();
            ServerPlayer player = GameTests.joinedRecordingPlayer(helper, "chooser", messages);
            try {
                player.getInventory().add(new ItemStack(Items.DIAMOND, 5));
                List<RunArea> visited = RunAreas.visited(server);
                BlockPos before = player.blockPosition();

                PrestigeGameTests.runPrestigeCommand(helper);

                List<CandidateArea> candidates = CandidateAreas.offered(server);
                helper.assertTrue(candidates.size() == 3, "3 Candidate areas should be offered, got " + candidates);
                helper.assertTrue(RunAreas.visited(server).equals(visited), "No Run should start before a choice");
                helper.assertTrue(player.blockPosition().equals(before),
                        "The player should stay where they were, " + before + ", got " + player.blockPosition());
                helper.assertTrue(player.getInventory().countItem(Items.DIAMOND) == 0,
                        "The inventory should be converted at the Prestige, not at the choice");
                for (int n = 1; n <= 3; n++) {
                    helper.assertTrue(candidateLine(messages, n) != null,
                            "Candidate area " + n + " should be listed in chat, got " + messages);
                }
            } finally {
                player.discard();
            }
        });
        helper.succeed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "candidates_grouped", timeoutTicks = 400)
    public static void candidatesAreGroupedByDimensionAndNumberedInGroupOrder(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        PrestigeGameTests.withWorldRestored(helper, () -> {
            PrestigeGameTests.runPrestigeCommand(helper);

            Map<ResourceKey<Level>, List<CandidateArea>> groups = CandidateAreas.offeredByDimension(server);
            helper.assertTrue(groups.keySet().equals(Set.of(Level.OVERWORLD)),
                    "Only the Overworld should offer candidates for now, got " + groups.keySet());
            List<CandidateArea> overworld = groups.get(Level.OVERWORLD);
            helper.assertTrue(overworld.size() == 3, "The Overworld should offer 3 candidates, got " + overworld);
            helper.assertTrue(CandidateAreas.offered(server).equals(overworld),
                    "Candidates should be numbered through the groups in order, got " + CandidateAreas.offered(server));
            RunArea second = overworld.get(1).area();
            choose(helper, 2);
            helper.assertTrue(RunAreas.current(server).equals(Optional.of(second)),
                    "Choosing 2 should start the Run in the Overworld's second candidate " + second);
        });
        helper.succeed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "candidates_preview_biome", timeoutTicks = 400)
    public static void eachCandidateIsGeneratedAndPreviewsTheBiomeAtItsMiddle(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        PrestigeGameTests.withWorldRestored(helper, () -> {
            PrestigeGameTests.runPrestigeCommand(helper);

            ServerLevel level = server.overworld();
            for (CandidateArea candidate : CandidateAreas.offered(server)) {
                RunArea area = candidate.area();
                for (int x = area.centre().x - area.radiusChunks(); x <= area.centre().x + area.radiusChunks(); x++) {
                    for (int z = area.centre().z - area.radiusChunks(); z <= area.centre().z + area.radiusChunks(); z++) {
                        helper.assertTrue(level.hasChunk(x, z), "Chunk " + x + "," + z + " of " + area + " should be generated");
                    }
                }
                // The GameTest world is superflat plains.
                helper.assertTrue(candidate.preview().biome().equals(Optional.of(ResourceLocation.withDefaultNamespace("plains"))),
                        "Candidate " + area + " should preview plains, got " + candidate.preview().biome());
            }
        });
        helper.succeed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "candidates_preview_structures", timeoutTicks = 400)
    public static void aCandidatePreviewListsTheStructuresWhoseStartsOverlapIt(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        PrestigeGameTests.withWorldRestored(helper, () -> {
            List<Component> messages = new ArrayList<>();
            ServerPlayer player = GameTests.joinedRecordingPlayer(helper, "previewer", messages);
            PrestigeGameTests.runPrestigeCommand(helper); // the sweep, so the next Prestige only re-rolls
            long seed = 77;
            List<RunArea> predicted = predictReRoll(server, seed);
            ServerLevel level = server.overworld();
            ChunkPos centre = predicted.get(0).centre();
            ChunkAccess chunk = level.getChunk(centre.x, centre.z);
            Map<Structure, StructureStart> starts = new HashMap<>(chunk.getAllStarts());
            Map<Structure, LongSet> references = new HashMap<>(chunk.getAllReferences());
            // A buried treasure (which superflat never generates) started in candidate 1's centre chunk, as worldgen would.
            Structure treasure = level.registryAccess().registryOrThrow(Registries.STRUCTURE).getOrThrow(BuiltinStructures.BURIED_TREASURE);
            BlockPos at = new BlockPos(centre.getMiddleBlockX(), level.getMinBuildHeight() + 2, centre.getMiddleBlockZ());
            chunk.setStartForStructure(treasure, new StructureStart(treasure, centre, 0,
                    new PiecesContainer(List.of(new BuriedTreasurePieces.BuriedTreasurePiece(at)))));
            chunk.addReferenceForStructure(treasure, centre.toLong());
            try {
                messages.clear();
                reRoll(helper, seed);

                List<CandidateArea> offered = CandidateAreas.offered(server);
                helper.assertTrue(offered.stream().map(CandidateArea::area).toList().equals(predicted),
                        "The re-roll should offer the predicted areas " + predicted + ", got " + offered);
                ResourceLocation id = BuiltinStructures.BURIED_TREASURE.location();
                helper.assertTrue(offered.get(0).preview().structures().contains(id),
                        "Candidate 1 should list the buried treasure, got " + offered.get(0).preview().structures());
                for (int i = 1; i < offered.size(); i++) {
                    helper.assertFalse(offered.get(i).preview().structures().contains(id),
                            "Candidate " + (i + 1) + " has no buried treasure, got " + offered.get(i).preview().structures());
                }
                Component line = candidateLine(messages, 1);
                helper.assertTrue(line != null && lineArgument(line, 3).contains("buried_treasure"),
                        "Candidate 1's chat line should list the buried treasure, got " + line);
            } finally {
                chunk.setAllStarts(starts);
                chunk.setAllReferences(references);
                player.discard();
            }
        });
        helper.succeed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "candidates_choose", timeoutTicks = 400)
    public static void choosingACandidateStartsTheRunInItsArea(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        PrestigeGameTests.withWorldRestored(helper, () -> {
            ServerPlayer player = GameTests.joinedPlayer(helper, "chooser");
            try {
                int runs = RunAreas.runNumber(server);
                PrestigeGameTests.runPrestigeCommand(helper);
                RunArea second = CandidateAreas.offered(server).get(1).area();

                choose(helper, 2);

                helper.assertTrue(RunAreas.current(server).equals(Optional.of(second)),
                        "The Run should start in candidate 2's area " + second + ", got " + RunAreas.current(server));
                helper.assertTrue(RunAreas.runNumber(server) == runs + 1, "Exactly one Run should start");
                helper.assertTrue(second.contains(player.blockPosition()),
                        "The player at " + player.blockPosition() + " should be in the chosen area " + second);
                PrestigeGameTests.assertHoldsOnlyOneTablet(helper, player);
                helper.assertTrue(CandidateAreas.offered(server).isEmpty(), "No candidates should be offered once chosen");
            } finally {
                player.discard();
            }
        });
        helper.succeed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "candidates_second_choice", timeoutTicks = 400)
    public static void aSecondChoiceIsIgnored(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        PrestigeGameTests.withWorldRestored(helper, () -> {
            PrestigeGameTests.runPrestigeCommand(helper);
            RunArea first = CandidateAreas.offered(server).get(0).area();
            choose(helper, 1);
            List<RunArea> visited = RunAreas.visited(server);

            int result = choose(helper, 2);

            helper.assertTrue(result == 0, "A second choice should fail, got " + result);
            helper.assertTrue(RunAreas.visited(server).equals(visited), "A second choice should start no Run");
            helper.assertTrue(RunAreas.current(server).equals(Optional.of(first)), "The first choice should stand");
        });
        helper.succeed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "candidates_reroll", timeoutTicks = 400)
    public static void prestigingAgainReRollsWithoutSweepingAgain(GameTestHelper helper) {
        GameTests.rebuildEmcMap(helper);
        MinecraftServer server = helper.getLevel().getServer();
        helper.startSequence()
                .thenWaitUntil(() -> GameTests.assertEmcMapBuilt(helper))
                .thenExecute(() -> PrestigeGameTests.withWorldRestored(helper, () -> {
                    ServerPlayer player = GameTests.joinedPlayer(helper, "reroller");
                    try {
                        List<RunArea> visited = RunAreas.visited(server);
                        PrestigeGameTests.runPrestigeCommand(helper);
                        // Whatever a player holds now would be converted by a second sweep.
                        player.getInventory().add(new ItemStack(Items.DIAMOND, 2));
                        BigInteger balance = WorldEmc.balance(server);

                        PrestigeGameTests.runPrestigeCommand(helper);

                        helper.assertTrue(CandidateAreas.offered(server).size() == 3,
                                "A re-roll should offer 3 candidates, got " + CandidateAreas.offered(server));
                        helper.assertTrue(player.getInventory().countItem(Items.DIAMOND) == 2,
                                "A re-roll should not convert inventories again");
                        helper.assertTrue(WorldEmc.balance(server).equals(balance),
                                "A re-roll should credit nothing: " + balance + ", got " + WorldEmc.balance(server));
                        helper.assertTrue(RunAreas.visited(server).equals(visited), "A re-roll should start no Run");
                        RunArea third = CandidateAreas.offered(server).get(2).area();
                        choose(helper, 3);
                        helper.assertTrue(RunAreas.current(server).equals(Optional.of(third)),
                                "The re-rolled candidate 3 should be chosen");
                    } finally {
                        player.discard();
                    }
                }))
                .thenSucceed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "candidates_restart", timeoutTicks = 400)
    public static void theCandidatesSurviveARestart(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        PrestigeGameTests.withWorldRestored(helper, () -> {
            PrestigeGameTests.runPrestigeCommand(helper);
            List<CandidateArea> candidates = CandidateAreas.offered(server);

            RunRestartGameTests.simulateRestart(server);

            helper.assertTrue(CandidateAreas.offered(server).equals(candidates),
                    "The candidates " + candidates + " should persist, got " + CandidateAreas.offered(server));
            choose(helper, 3);
            helper.assertTrue(RunAreas.current(server).equals(Optional.of(candidates.get(2).area())),
                    "Choosing after the restart should start the Run in candidate 3's area");
        });
        helper.succeed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "candidates_distance", timeoutTicks = 400)
    public static void candidatesAreFarFromEveryEarlierRunAreaAndEachOther(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        PrestigeGameTests.withWorldRestored(helper, () -> {
            // Twice, so the second Prestige also has the first's chosen area to keep away from.
            for (int i = 0; i < 2; i++) {
                List<RunArea> visited = RunAreas.visited(server);
                PrestigeGameTests.runPrestigeCommand(helper);
                List<RunArea> candidates = CandidateAreas.offered(server).stream().map(CandidateArea::area).toList();
                int min = RunAreaConfig.MIN_DISTANCE_CHUNKS.get();
                for (RunArea candidate : candidates) {
                    ChunkPos c = candidate.centre();
                    helper.assertTrue(Math.floorMod(c.x, 3) == 0 && Math.floorMod(c.z, 3) == 0,
                            "Candidate " + c + " should be centred on a GT grid chunk");
                    for (RunArea old : visited) {
                        helper.assertTrue(old.distanceChunks(candidate) >= min,
                                "Candidate " + c + " is closer than " + min + " chunks to earlier area " + old.centre());
                    }
                    for (RunArea other : candidates) {
                        helper.assertTrue(other == candidate || other.distanceChunks(candidate) >= min,
                                "Candidates " + c + " and " + other.centre() + " are closer than " + min + " chunks");
                    }
                }
                choose(helper, 1);
            }
        });
        helper.succeed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "candidates_ocean", timeoutTicks = 400)
    public static void candidatesAreRepickedUntilTheirCentreIsNotOcean(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        // The GameTest world is superflat plains, so stand in for oceans: the first 5 centres checked are "ocean".
        Set<ChunkPos> oceans = new HashSet<>();
        List<RunArea> candidates = RunAreas.candidates(server, 3,
                c -> oceans.contains(c) || oceans.size() < 5 && oceans.add(c));
        helper.assertTrue(oceans.size() == 5, "The ocean check should have rejected 5 centres, got " + oceans.size());
        helper.assertTrue(candidates.size() == 3, "3 candidates should be found, got " + candidates);
        for (RunArea candidate : candidates) {
            helper.assertFalse(oceans.contains(candidate.centre()),
                    "Candidate " + candidate.centre() + " is centred in a fake ocean");
        }
        helper.succeed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "candidates_frozen", timeoutTicks = 400)
    public static void aPlayerWhoJoinsWhileCandidatesAreOfferedIsFrozenAndInvulnerable(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Runnable restore = PrestigeGameTests.saveWorld(helper);
        List<Component> messages = new ArrayList<>();
        ServerPlayer[] joiner = new ServerPlayer[1];
        Vec3[] joinedAt = new Vec3[1];
        helper.startSequence()
                .thenExecute(() -> {
                    PrestigeGameTests.runPrestigeCommand(helper);
                    joiner[0] = GameTests.joinedRecordingPlayer(helper, "latecomer", messages);
                    joinedAt[0] = joiner[0].position();
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    helper.assertTrue(candidateLine(messages, 1) != null,
                            "A player joining mid-choice should be shown the candidates, got " + messages);
                    joiner[0].teleportTo(joinedAt[0].x + 5, joinedAt[0].y, joinedAt[0].z + 5);
                    float health = joiner[0].getHealth();
                    // Void damage bypasses the spawn protection a player has just after joining.
                    joiner[0].hurt(joiner[0].damageSources().fellOutOfWorld(), 5);
                    helper.assertTrue(joiner[0].getHealth() == health,
                            "A player should be invulnerable while candidates are offered");
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    helper.assertTrue(joiner[0].position().distanceTo(joinedAt[0]) < 0.01,
                            "The player should be held at " + joinedAt[0] + ", got " + joiner[0].position());
                    choose(helper, 1);
                    RunArea area = RunAreas.current(server).orElseThrow();
                    helper.assertTrue(area.contains(joiner[0].blockPosition()),
                            "The player who joined mid-choice should be moved to the chosen area");
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    Vec3 landed = joiner[0].position();
                    joinedAt[0] = new Vec3(landed.x + 1, landed.y, landed.z);
                    joiner[0].teleportTo(joinedAt[0].x, joinedAt[0].y, joinedAt[0].z);
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    helper.assertTrue(joiner[0].position().distanceTo(joinedAt[0]) < 0.01,
                            "Once a Run starts, players can move again: expected " + joinedAt[0] + ", got "
                                    + joiner[0].position());
                    float health = joiner[0].getHealth();
                    joiner[0].hurt(joiner[0].damageSources().fellOutOfWorld(), 1);
                    helper.assertTrue(joiner[0].getHealth() < health, "Once a Run starts, players can be hurt again");
                })
                .thenExecute(() -> {
                    joiner[0].discard();
                    restore.run();
                })
                .thenSucceed();
    }

    /**
     * Seeds the overworld's random and picks the Candidate areas a re-roll would offer with that seed, without offering
     * them, so a test can prepare their chunks first; {@link #reRoll} with the same seed then offers exactly these.
     */
    static List<RunArea> predictReRoll(MinecraftServer server, long seed) {
        server.overworld().getRandom().setSeed(seed);
        return RunAreas.candidates(server, CandidateAreas.COUNT);
    }

    /** Re-rolls the candidates on offer ({@code /prestige} again) with the overworld's random seeded. */
    static void reRoll(GameTestHelper helper, long seed) {
        helper.getLevel().getServer().overworld().getRandom().setSeed(seed);
        PrestigeGameTests.runPrestigeCommand(helper);
    }

    /** Argument {@code i} of a translated chat line, as text. */
    static String lineArgument(Component line, int i) {
        Object[] args = ((TranslatableContents) line.getContents()).getArgs();
        return i < args.length ? (args[i] instanceof Component c ? c.getString() : String.valueOf(args[i])) : "";
    }

    /** Runs {@code /prestige choose <n>} as the server console. @return the command's result */
    static int choose(GameTestHelper helper, int n) {
        MinecraftServer server = helper.getLevel().getServer();
        try {
            return server.getCommands().getDispatcher().execute("prestige choose " + n,
                    server.createCommandSourceStack().withSuppressedOutput());
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            throw new net.minecraft.gametest.framework.GameTestAssertException("/prestige choose " + n + ": " + e.getMessage());
        }
    }

    /** The chat line listing candidate {@code n}, or null if there is none. */
    static Component candidateLine(List<Component> messages, int n) {
        for (Component message : messages) {
            if (message.getContents() instanceof TranslatableContents t && t.getKey().equals(CandidateAreas.LINE_KEY)
                    && t.getArgs().length > 0 && String.valueOf(t.getArgs()[0]).equals(String.valueOf(n))) {
                return message;
            }
        }
        return null;
    }
}
