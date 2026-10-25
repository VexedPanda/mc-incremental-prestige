package io.github.vexedpanda.mcincremental.prestige.gametest;

import io.github.vexedpanda.mcincremental.prestige.McIncrementalPrestige;
import io.github.vexedpanda.mcincremental.prestige.run.RunArea;
import io.github.vexedpanda.mcincremental.prestige.run.RunAreaConfig;
import io.github.vexedpanda.mcincremental.prestige.run.RunAreas;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Run area quality and config: ocean avoidance, safe spawn, and the server config values. Tests that run a Prestige or
 * change config have their own batch (batches run one after another) and put everything back.
 */
@GameTestHolder(McIncrementalPrestige.MODID)
@PrefixGameTestTemplate(false)
public class RunAreaGameTests {
    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "run_area_radius", timeoutTicks = 400)
    public static void runAreaRadiusConfigSetsTheNewAreaAndBorderSize(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        PrestigeGameTests.withWorldRestored(helper, () -> withConfig(RunAreaConfig.RADIUS_CHUNKS, 2, () -> {
            ServerPlayer player = GameTests.joinedPlayer(helper, "wide");
            try {
                PrestigeGameTests.runPrestigeCommand(helper);
                RunArea area = RunAreas.current(server).orElseThrow(() -> new GameTestAssertException("No Run area"));
                ChunkPos c = area.centre();
                double size = server.overworld().getWorldBorder().getSize();
                // Radius 2 = 5x5 chunks = 80 blocks.
                helper.assertTrue(size == 80, "Border size should be 80 for radius 2, got " + size);
                helper.assertTrue(area.contains(new BlockPos((c.x - 2) * 16, 64, (c.z + 2) * 16 + 15)),
                        "A radius-2 area should reach 2 chunks from its centre");
                helper.assertFalse(area.contains(new BlockPos((c.x + 3) * 16, 64, c.z * 16)),
                        "A radius-2 area should stop 2 chunks from its centre");
                helper.assertTrue(area.contains(player.blockPosition()), "The player should be in the new Run area");
            } finally {
                player.discard();
            }
        }));
        helper.succeed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "run_area_distance", timeoutTicks = 400)
    public static void minDistanceConfigSetsHowFarNewAreasAre(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        PrestigeGameTests.withWorldRestored(helper, () -> withConfig(RunAreaConfig.MIN_DISTANCE_CHUNKS, 16, () -> {
            // Two Prestiges, so the second has a previous area to measure from even in a fresh world.
            for (int i = 0; i < 2; i++) {
                java.util.List<RunArea> earlier = RunAreas.visited(server);
                PrestigeGameTests.runPrestigeCommand(helper);
                RunArea area = RunAreas.current(server).orElseThrow(() -> new GameTestAssertException("No Run area"));
                for (RunArea old : earlier) {
                    helper.assertTrue(old.distanceChunks(area) >= 16,
                            "New area " + area.centre() + " is closer than 16 chunks to " + old.centre());
                }
                if (!earlier.isEmpty()) {
                    // 1 to 2 minimum distances from the previous area, plus up to 2 chunks of grid snapping per axis.
                    int d = earlier.getLast().distanceChunks(area);
                    helper.assertTrue(d <= 34, "New area should be at most ~32 chunks from the previous one, got " + d);
                }
            }
        }));
        helper.succeed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "run_area_margin")
    public static void sweepMarginConfigSetsHowFarTheSweepReaches(GameTestHelper helper) {
        RunArea area = new RunArea(new ChunkPos(30, -60), 1);
        ChunkPos twoOut = new ChunkPos(32, -60), threeOut = new ChunkPos(30, -57);
        withConfig(RunAreaConfig.SWEEP_MARGIN_CHUNKS, 1, () -> {
            helper.assertTrue(area.inSweep(twoOut), "Margin 1 should sweep the chunk next to a 3x3 area");
            helper.assertFalse(area.inSweep(threeOut), "Margin 1 should not sweep 2 chunks past a 3x3 area");
        });
        withConfig(RunAreaConfig.SWEEP_MARGIN_CHUNKS, 2, () -> {
            helper.assertTrue(area.inSweep(threeOut), "Margin 2 should sweep 2 chunks past a 3x3 area");
            helper.assertFalse(area.inSweep(new ChunkPos(34, -60)), "Margin 2 should not sweep 3 chunks past a 3x3 area");
        });
        helper.succeed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "run_area_ocean", timeoutTicks = 400)
    public static void newRunAreasAreRepickedUntilTheCentreIsNotOcean(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        PrestigeGameTests.withWorldRestored(helper, () -> {
            // The GameTest world is superflat plains, so stand in for oceans: the first 5 candidates checked are
            // "ocean". (Not a region: in the persistent test world, earlier areas can crowd any fixed region.)
            Set<ChunkPos> oceans = new HashSet<>();
            RunArea area = RunAreas.beginRun(server, c -> oceans.contains(c) || oceans.size() < 5 && oceans.add(c));
            helper.assertTrue(oceans.size() == 5, "The ocean check should have rejected 5 candidates, got " + oceans.size());
            helper.assertFalse(oceans.contains(area.centre()), "Run area " + area.centre() + " is centred in a fake ocean");
            helper.assertTrue(RunAreas.current(server).orElseThrow().equals(area), "The chosen area should be current");
        });
        helper.succeed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE)
    public static void oceanBiomesCountAsOcean(GameTestHelper helper) {
        var biomes = helper.getLevel().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME);
        for (var key : java.util.List.of(Biomes.OCEAN, Biomes.DEEP_OCEAN, Biomes.WARM_OCEAN, Biomes.DEEP_FROZEN_OCEAN)) {
            helper.assertTrue(RunAreas.isOcean(biomes.getHolderOrThrow(key)), key.location() + " should count as ocean");
        }
        for (var key : java.util.List.of(Biomes.PLAINS, Biomes.BEACH, Biomes.RIVER, Biomes.MUSHROOM_FIELDS)) {
            helper.assertFalse(RunAreas.isOcean(biomes.getHolderOrThrow(key)), key.location() + " should not count as ocean");
        }
        helper.succeed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "run_area_spawn_hazard")
    public static void spawnPointAvoidsLavaAndWaterAtTheCentre(GameTestHelper helper) {
        // One fluid at a time: lava placed next to water turns to obsidian at once.
        for (Block fluid : new Block[] {Blocks.LAVA, Blocks.WATER}) {
            ServerLevel level = helper.getLevel();
            RunArea area = new RunArea(new ChunkPos(helper.absolutePos(BlockPos.ZERO)), 1);
            int mx = (int) Math.floor(area.middleX()), mz = (int) Math.floor(area.middleZ());
            level.getChunk(area.centre().x, area.centre().z);
            int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, mx, mz) - 1;
            // A 5x5 pool in place of the surface blocks around the middle.
            Map<BlockPos, BlockState> original = new HashMap<>();
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos pos = new BlockPos(mx + dx, surface, mz + dz);
                    original.put(pos, level.getBlockState(pos));
                    level.setBlock(pos, fluid.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
            try {
                BlockPos spawn = RunAreas.spawnPoint(level, area);
                assertSafe(helper, level, spawn);
                // Standing on something above the pool (an earlier test's structure block) is fine; on it isn't.
                helper.assertFalse(Math.abs(spawn.getX() - mx) <= 2 && Math.abs(spawn.getZ() - mz) <= 2
                                && spawn.getY() <= surface + 1,
                        "Spawn " + spawn + " should be off the " + fluid + " pool around " + mx + "," + mz);
                helper.assertTrue(area.contains(spawn), "Spawn " + spawn + " should be in the Run area");
            } finally {
                original.forEach((pos, state) -> level.setBlock(pos, state, Block.UPDATE_CLIENTS));
            }
        }
        helper.succeed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "run_area_spawn_prestige", timeoutTicks = 400)
    public static void prestigeLandsPlayersOnASafeSurfaceBlock(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        PrestigeGameTests.withWorldRestored(helper, () -> {
            ServerPlayer player = GameTests.joinedPlayer(helper, "lander");
            try {
                PrestigeGameTests.runPrestigeCommand(helper);
                assertSafe(helper, server.overworld(), player.blockPosition());
                helper.assertTrue(player.blockPosition().equals(server.overworld().getSharedSpawnPos()),
                        "The player should land on the world spawn, " + server.overworld().getSharedSpawnPos()
                                + ", got " + player.blockPosition());
            } finally {
                player.discard();
            }
        });
        helper.succeed();
    }

    /** A safe spawn: solid, dry block below; nothing solid or liquid at feet and head. */
    private static void assertSafe(GameTestHelper helper, ServerLevel level, BlockPos feet) {
        BlockState below = level.getBlockState(feet.below());
        helper.assertTrue(below.isFaceSturdy(level, feet.below(), net.minecraft.core.Direction.UP)
                        && below.getFluidState().isEmpty(),
                "Block below spawn " + feet + " should be solid and dry, got " + below);
        for (BlockPos pos : new BlockPos[] {feet, feet.above()}) {
            BlockState state = level.getBlockState(pos);
            helper.assertTrue(state.isAir(), "Spawn " + feet + " should have air at " + pos + ", got " + state);
        }
    }

    /** Sets a config value for the body, then puts the old value back whatever happens. */
    static <T> void withConfig(ModConfigSpec.ConfigValue<T> value, T newValue, Runnable body) {
        T old = value.get();
        value.set(newValue);
        try {
            body.run();
        } finally {
            value.set(old);
        }
    }
}
