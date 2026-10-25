package io.github.vexedpanda.mcincremental.prestige.run;

import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The Run area manager: chooses the world's Run areas, remembers every one used, and owns the world border and world
 * spawn. Run areas are in the overworld. Server thread only.
 */
public final class RunAreas {
    /**
     * Minimum distance between a new Run area's centre and every earlier one, in chunks (Chebyshev): 64 chunks = 1024
     * blocks. The default until it becomes configurable (#33).
     */
    public static final int MIN_DISTANCE_CHUNKS = 64;

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
     * Starts a new Run area: chooses one far from every earlier area, records it as current, fits the world border to
     * it and moves the world spawn into it. Doesn't move any player: call {@link #teleport} for each.
     */
    public static RunArea beginRun(MinecraftServer server) {
        RunAreaData data = RunAreaData.get(server);
        ServerLevel level = server.overworld();
        RunArea area = choose(level, data.visited);
        data.visited.add(area);
        data.setDirty();

        WorldBorder border = level.getWorldBorder();
        border.setCenter(area.middleX(), area.middleZ());
        border.setSize(RunArea.sizeBlocks());
        level.setDefaultSpawnPos(spawnPoint(level, area), 0);
        return area;
    }

    /**
     * Moves a player to the Run area's spawn point and makes them respawn there: a bed or anchor respawn point from an
     * earlier Run is forgotten.
     */
    public static void teleport(ServerPlayer player, RunArea area) {
        ServerLevel level = player.getServer().overworld();
        BlockPos spawn = spawnPoint(level, area);
        player.setRespawnPosition(Level.OVERWORLD, null, 0, false, false);
        player.teleportTo(level, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, player.getYRot(), player.getXRot());
        player.resetFallDistance();
    }

    /**
     * The surface at the middle of the Run area: the first block above the highest motion-blocking non-leaf block.
     * Loads (generating if needed) the centre chunk. Making it safe (not water, lava or a cave roof) is #33.
     */
    public static BlockPos spawnPoint(ServerLevel level, RunArea area) {
        int x = (int) Math.floor(area.middleX()), z = (int) Math.floor(area.middleZ());
        level.getChunk(area.centre().x, area.centre().z);
        return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
    }

    /**
     * A grid-centred area 1 to 2 minimum distances from the current area (or the world spawn before the first Run),
     * in a random direction, at least the minimum distance from every earlier area.
     */
    private static RunArea choose(ServerLevel level, List<RunArea> visited) {
        ChunkPos origin = visited.isEmpty() ? new ChunkPos(level.getSharedSpawnPos()) : visited.getLast().centre();
        RandomSource random = level.getRandom();
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            double angle = random.nextDouble() * Mth.TWO_PI;
            double distance = MIN_DISTANCE_CHUNKS * (1 + random.nextDouble());
            int x = snapToGrid(origin.x + Math.cos(angle) * distance);
            int z = snapToGrid(origin.z + Math.sin(angle) * distance);
            if (Math.abs(x) > MAX_CENTRE_CHUNK || Math.abs(z) > MAX_CENTRE_CHUNK) continue;
            RunArea candidate = new RunArea(new ChunkPos(x, z));
            if (visited.stream().allMatch(old -> old.distanceChunks(candidate) >= MIN_DISTANCE_CHUNKS)) return candidate;
        }
        throw new IllegalStateException("No free Run area found near chunk " + origin + " after " + MAX_ATTEMPTS + " attempts");
    }

    private static int snapToGrid(double chunk) {
        return GRID_CHUNKS * (int) Math.round(chunk / GRID_CHUNKS);
    }
}
