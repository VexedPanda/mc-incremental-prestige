package io.github.vexedpanda.mcincremental.prestige.run;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import io.github.vexedpanda.mcincremental.prestige.McIncrementalPrestige;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The Run area manager: chooses the world's Run areas, remembers every one used, and owns the world border and world
 * spawn. Run areas are in the overworld. Server thread only.
 */
public final class RunAreas {
    /**
     * Run area centres are GT ore-vein grid chunks: GT places one vein per layer on a 3-chunk grid, so a 3x3 area
     * centred on a grid chunk holds exactly one vein per layer (pack docs/pocs/poc-1.md).
     */
    public static final int GRID_CHUNKS = 3;

    /** Keeps Run areas well inside the vanilla world border limit (29,999,984 blocks). */
    private static final int MAX_CENTRE_CHUNK = 29_999_984 / 16 - 64;
    private static final int MAX_ATTEMPTS = 1000;

    private RunAreas() {}

    /** The world's current Run area, if a Run has started. */
    public static Optional<RunArea> current(MinecraftServer server) {
        List<RunArea> visited = RunAreaData.get(server).visited;
        return visited.isEmpty() ? Optional.empty() : Optional.of(visited.getLast());
    }

    /**
     * The world's Run number: how many Runs it has started, so 0 before the first Run. Each Run start adds one Run
     * area to the record, so the record's length is the counter.
     */
    public static int runNumber(MinecraftServer server) {
        return RunAreaData.get(server).visited.size();
    }

    /** Every Run area the world has used, oldest first, including the current one. */
    public static List<RunArea> visited(MinecraftServer server) {
        return List.copyOf(RunAreaData.get(server).visited);
    }

    /**
     * Replaces the record of every Run area the world has used. For GameTests, which empty it to act on a world with
     * no Run yet and put it back afterwards; moves nothing.
     */
    public static void replaceVisited(MinecraftServer server, List<RunArea> visited) {
        RunAreaData data = RunAreaData.get(server);
        data.visited.clear();
        data.visited.addAll(visited);
        data.setDirty();
    }

    /**
     * Starts a new Run area: chooses one far from every earlier area and, if {@link RunAreaConfig#AVOID_OCEANS} is on,
     * not centred in an ocean; records it as current, fits the world border to it and moves the world spawn to its
     * safe spawn point. Doesn't move any player: call {@link #teleport} for each.
     */
    public static RunArea beginRun(MinecraftServer server) {
        ServerLevel level = server.overworld();
        Predicate<ChunkPos> unsuitable = RunAreaConfig.AVOID_OCEANS.get() ? c -> isOceanCentred(level, c) : c -> false;
        return beginRun(server, unsuitable);
    }

    /**
     * {@link #beginRun(MinecraftServer)} with the centre check given: candidates whose centre chunk is
     * {@code unsuitable} are re-picked. Public for tests (the GameTest world is superflat, with no oceans).
     */
    public static RunArea beginRun(MinecraftServer server, Predicate<ChunkPos> unsuitable) {
        RunAreaData data = RunAreaData.get(server);
        ServerLevel level = server.overworld();
        RunArea area = choose(level, data.visited, unsuitable).orElseGet(() -> {
            // E.g. the current area is deep in a huge ocean: better an ocean Run area (with a stone spawn block) than
            // no Prestige.
            McIncrementalPrestige.LOGGER.warn("Every candidate Run area was unsuitable (ocean); ignoring the check");
            return choose(level, data.visited, c -> false).orElseThrow(() -> new IllegalStateException(
                    "No free Run area found after " + MAX_ATTEMPTS + " attempts"));
        });
        data.visited.add(area);
        data.setDirty();

        WorldBorder border = level.getWorldBorder();
        border.setCenter(area.middleX(), area.middleZ());
        border.setSize(area.sizeBlocks());
        level.setDefaultSpawnPos(spawnPoint(level, area), 0);
        return area;
    }

    /**
     * Whether the chunk's middle is in an ocean biome, read from the biome source: doesn't load or generate the
     * chunk, so rejecting a candidate is cheap.
     */
    public static boolean isOceanCentred(ServerLevel level, ChunkPos chunk) {
        int y = level.getSeaLevel();
        Holder<Biome> biome = level.getUncachedNoiseBiome(
                QuartPos.fromBlock(chunk.getMiddleBlockX()), QuartPos.fromBlock(y), QuartPos.fromBlock(chunk.getMiddleBlockZ()));
        return isOcean(biome);
    }

    /** Ocean and deep ocean biomes, by the vanilla tags (modded oceans tag themselves too). */
    public static boolean isOcean(Holder<Biome> biome) {
        return biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_DEEP_OCEAN);
    }

    /**
     * Moves a player to the Run area's spawn point (the world spawn while it is still safe and in the area) and makes them respawn there: a bed or anchor respawn point from an
     * earlier Run is forgotten.
     */
    public static void teleport(ServerPlayer player, RunArea area) {
        ServerLevel level = player.getServer().overworld();
        BlockPos spawn = level.getSharedSpawnPos();
        if (!area.contains(spawn) || !isSafeSpawn(level, spawn)) {
            // Not this area's spawn, or the world changed under it since beginRun (e.g. a spring flowed once the area
            // started ticking): find a safe one again and keep the world spawn in step.
            spawn = spawnPoint(level, area);
            level.setDefaultSpawnPos(spawn, 0);
        }
        player.setRespawnPosition(Level.OVERWORLD, null, 0, false, false);
        player.teleportTo(level, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, player.getYRot(), player.getXRot());
        player.resetFallDistance();
    }

    /**
     * The Run area's spawn point: the safe surface column ({@link #isSafeSpawn}) nearest the area's middle, searched
     * in square rings outward and never outside the area. If the whole area is unsafe (all sea or lava, possible with
     * the ocean check off), makes the middle safe: a stone block to stand on and air above it. Loads (generating if
     * needed) the chunks it searches; deterministic, so calling it again gives the same point.
     */
    public static BlockPos spawnPoint(ServerLevel level, RunArea area) {
        int mx = (int) Math.floor(area.middleX()), mz = (int) Math.floor(area.middleZ());
        int maxRing = Math.max(Math.max(mx - area.minBlockX(), area.maxBlockX() - mx),
                Math.max(mz - area.minBlockZ(), area.maxBlockZ() - mz));
        for (int ring = 0; ring <= maxRing; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.abs(dx) != ring && Math.abs(dz) != ring) continue; // inside the ring: already checked
                    int x = mx + dx, z = mz + dz;
                    BlockPos feet = surface(level, x, z);
                    if (area.contains(feet) && isSafeSpawn(level, feet)) return feet;
                }
            }
        }
        BlockPos feet = surface(level, mx, mz);
        level.setBlockAndUpdate(feet.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(feet, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(feet.above(), Blocks.AIR.defaultBlockState());
        return feet;
    }

    /**
     * Whether a player can stand at {@code feet}: the block below is solid on top, dry and harmless, and feet and
     * head are free (air, or a plant you walk through), dry and not burning.
     */
    public static boolean isSafeSpawn(ServerLevel level, BlockPos feet) {
        BlockState below = level.getBlockState(feet.below());
        if (!below.isFaceSturdy(level, feet.below(), Direction.UP) || !below.getFluidState().isEmpty()
                || below.is(Blocks.MAGMA_BLOCK) || below.is(BlockTags.CAMPFIRES)) return false;
        return isFree(level, feet) && isFree(level, feet.above());
    }

    private static boolean isFree(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return true;
        return state.canBeReplaced() && state.getFluidState().isEmpty() && !state.is(BlockTags.FIRE)
                && state.getCollisionShape(level, pos).isEmpty() && !state.is(Blocks.POWDER_SNOW);
    }

    /** The first block above the highest motion-blocking non-leaf block (fluids count as blocking). */
    private static BlockPos surface(ServerLevel level, int x, int z) {
        level.getChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z));
        return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
    }

    /**
     * A grid-centred area 1 to 2 minimum distances (further if that ring is crowded) from the current area (or the world spawn before the first Run),
     * in a random direction, at least the minimum distance from every earlier area and not {@code unsuitable}.
     */
    private static Optional<RunArea> choose(ServerLevel level, List<RunArea> visited, Predicate<ChunkPos> unsuitable) {
        ChunkPos origin = visited.isEmpty() ? new ChunkPos(level.getSharedSpawnPos()) : visited.getLast().centre();
        RandomSource random = level.getRandom();
        int minDistance = RunAreaConfig.MIN_DISTANCE_CHUNKS.get();
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            double angle = random.nextDouble() * Mth.TWO_PI;
            // 1 to 2 minimum distances; after every 100 failed attempts reach further, as earlier areas can crowd the
            // ring around the current one.
            double distance = minDistance * (1 + random.nextDouble() * (1 + attempt / 100));
            int x = snapToGrid(origin.x + Math.cos(angle) * distance);
            int z = snapToGrid(origin.z + Math.sin(angle) * distance);
            if (Math.abs(x) > MAX_CENTRE_CHUNK || Math.abs(z) > MAX_CENTRE_CHUNK) continue;
            RunArea candidate = new RunArea(new ChunkPos(x, z), RunAreaConfig.RADIUS_CHUNKS.get());
            if (visited.stream().allMatch(old -> old.distanceChunks(candidate) >= minDistance)
                    && !unsuitable.test(candidate.centre())) return Optional.of(candidate);
        }
        return Optional.empty();
    }

    private static int snapToGrid(double chunk) {
        return GRID_CHUNKS * (int) Math.round(chunk / GRID_CHUNKS);
    }
}
