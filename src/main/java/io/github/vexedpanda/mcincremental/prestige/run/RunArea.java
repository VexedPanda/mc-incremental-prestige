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

    /** Whether the Prestige sweep covers the chunk: the area plus {@link RunAreaConfig#SWEEP_MARGIN_CHUNKS} around it. */
    public boolean inSweep(ChunkPos chunk) {
        return containsChunk(chunk, RunAreaConfig.SWEEP_MARGIN_CHUNKS.get());
    }

    /** Chebyshev distance between the two areas' centre chunks. */
    public int distanceChunks(RunArea other) {
        return Math.max(Math.abs(centre.x - other.centre.x), Math.abs(centre.z - other.centre.z));
    }
}
