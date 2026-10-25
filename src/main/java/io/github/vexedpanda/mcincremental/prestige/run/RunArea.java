package io.github.vexedpanda.mcincremental.prestige.run;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

/**
 * A Run area: the square of chunks a Run is confined to, centred on {@link #centre()}. It is 3x3 chunks (radius 1)
 * until Run area size becomes configurable (#33).
 */
public record RunArea(ChunkPos centre) {
    /** Chunks from the centre chunk to the edge: 1 for a 3x3 area. */
    public static final int RADIUS_CHUNKS = 1;

    /** Side length in chunks. */
    public static int sizeChunks() {
        return 2 * RADIUS_CHUNKS + 1;
    }

    /** Side length in blocks: the world border's size. */
    public static int sizeBlocks() {
        return 16 * sizeChunks();
    }

    /** Lowest block x inside the area. */
    public int minBlockX() {
        return (centre.x - RADIUS_CHUNKS) * 16;
    }

    /** Lowest block z inside the area. */
    public int minBlockZ() {
        return (centre.z - RADIUS_CHUNKS) * 16;
    }

    /** Highest block x inside the area (inclusive). */
    public int maxBlockX() {
        return (centre.x + RADIUS_CHUNKS) * 16 + 15;
    }

    /** Highest block z inside the area (inclusive). */
    public int maxBlockZ() {
        return (centre.z + RADIUS_CHUNKS) * 16 + 15;
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
        int r = RADIUS_CHUNKS + marginChunks;
        return Math.abs(chunk.x - centre.x) <= r && Math.abs(chunk.z - centre.z) <= r;
    }

    /** Chebyshev distance between the two areas' centre chunks. */
    public int distanceChunks(RunArea other) {
        return Math.max(Math.abs(centre.x - other.centre.x), Math.abs(centre.z - other.centre.z));
    }
}
