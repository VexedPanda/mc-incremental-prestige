package io.github.vexedpanda.mcincremental.prestige.run;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

/**
 * A Run area: the square of chunks a Run is confined to, centred on {@link #centre()} and reaching
 * {@link #radiusChunks()} chunks from it on every side. The radius is fixed when the area is chosen (from
 * {@link RunAreaConfig#RADIUS_CHUNKS}), so changing the config never resizes an earlier area.
 */
public record RunArea(ChunkPos centre, int radiusChunks) {
    /** Side length in chunks. */
    public int sizeChunks() {
        return 2 * radiusChunks + 1;
    }

    /**
     * The smallest simulation distance that keeps the whole area ticking wherever in it the player stands: from a
     * corner chunk, the far edge is {@code 2 * radiusChunks} chunks away (2 for a 3x3 area). Never below vanilla's
     * minimum of {@value #MIN_SIMULATION_DISTANCE}.
     */
    public int simulationDistanceChunks() {
        return simulationDistanceChunks(radiusChunks);
    }

    /** Vanilla's smallest simulation distance. */
    public static final int MIN_SIMULATION_DISTANCE = 2;

    /** {@link #simulationDistanceChunks()} for an area of the given radius. */
    public static int simulationDistanceChunks(int radiusChunks) {
        return Math.max(MIN_SIMULATION_DISTANCE, 2 * radiusChunks);
    }

    /** Side length in blocks: the world border's size. */
    public int sizeBlocks() {
        return 16 * sizeChunks();
    }

    /** Lowest block x inside the area. */
    public int minBlockX() {
        return (centre.x - radiusChunks) * 16;
    }

    /** Lowest block z inside the area. */
    public int minBlockZ() {
        return (centre.z - radiusChunks) * 16;
    }

    /** Highest block x inside the area (inclusive). */
    public int maxBlockX() {
        return (centre.x + radiusChunks) * 16 + 15;
    }

    /** Highest block z inside the area (inclusive). */
    public int maxBlockZ() {
        return (centre.z + radiusChunks) * 16 + 15;
    }

    /** The area's middle in block coordinates: the world border's centre. */
    public double middleX() {
        return centre.getMiddleBlockX();
    }

    public double middleZ() {
        return centre.getMiddleBlockZ();
    }

    public boolean contains(BlockPos pos) {
        return pos.getX() >= minBlockX() && pos.getX() <= maxBlockX()
                && pos.getZ() >= minBlockZ() && pos.getZ() <= maxBlockZ();
    }

    /** Whether the chunk is in the area grown by {@code marginChunks} on every side (e.g. 1 for the sweep's 5x5). */
    public boolean containsChunk(ChunkPos chunk, int marginChunks) {
        int r = radiusChunks + marginChunks;
        return Math.abs(chunk.x - centre.x) <= r && Math.abs(chunk.z - centre.z) <= r;
    }

    /** Chebyshev distance between the two areas' centre chunks. */
    public int distanceChunks(RunArea other) {
        return Math.max(Math.abs(centre.x - other.centre.x), Math.abs(centre.z - other.centre.z));
    }
}
