package io.github.vexedpanda.mcincremental.prestige.sweep;

import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import io.github.vexedpanda.mcincremental.prestige.McIncrementalPrestige;
import io.github.vexedpanda.mcincremental.prestige.run.RunArea;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;

/**
 * The Run snapshot (ADR-0007): the Run area's blocks as they were at Run start, which the Prestige sweep diffs the
 * live world against. The world has at most one; each Run start overwrites it. Server thread only.
 */
public final class RunSnapshots {
    /**
     * Chunks the snapshot covers around the Run area, on every side: fluids, explosions and thrown items can reach past
     * the world border, so the sweep can cover a margin (#31).
     */
    public static final int MARGIN_CHUNKS = 1;

    /** How long a Run start may wait for the Run area to generate and start ticking before giving up. */
    private static final long TICKING_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(60);

    private static final TicketType<ChunkPos> TICKET = TicketType.create(McIncrementalPrestige.MODID + ":run_snapshot",
            Comparator.comparingLong(ChunkPos::toLong));

    private RunSnapshots() {}

    /** The Run area the world's snapshot is of, if there is one. */
    public static Optional<RunArea> area(MinecraftServer server) {
        return Optional.ofNullable(RunSnapshotData.get(server).area);
    }

    /**
     * Snapshots the Run area plus {@link #MARGIN_CHUNKS} as it is now, replacing the world's snapshot. Call it at Run
     * start, before any player arrives. Loads, generates and ticks the chunks first, blocking the server thread until
     * they are ticking: only then has worldgen post-processing (fence shapes, spring fluid ticks) run, so the snapshot
     * matches the world the players will find.
     */
    public static void take(MinecraftServer server, RunArea area) {
        ServerLevel level = server.overworld();
        int radius = RunArea.RADIUS_CHUNKS + MARGIN_CHUNKS;
        ChunkPos centre = area.centre();
        // A region ticket's level drops by one per chunk from its centre: distance radius + 1 puts the whole snapshot
        // box at block-ticking level, with one ring of full chunks around it as their neighbours.
        int distance = radius + 1;
        ServerChunkCache chunks = level.getChunkSource();
        chunks.addRegionTicket(TICKET, centre, distance, centre);
        try {
            waitUntilTicking(chunks, centre, radius);
            RunSnapshotData data = RunSnapshotData.get(server);
            data.chunks.clear();
            data.area = area;
            data.marginChunks = MARGIN_CHUNKS;
            data.minSection = level.getMinSection();
            for (int x = centre.x - radius; x <= centre.x + radius; x++) {
                for (int z = centre.z - radius; z <= centre.z + radius; z++) {
                    LevelChunk chunk = level.getChunk(x, z);
                    LevelChunkSection[] sections = chunk.getSections();
                    @SuppressWarnings("unchecked")
                    PalettedContainer<BlockState>[] copies = new PalettedContainer[sections.length];
                    for (int i = 0; i < sections.length; i++) copies[i] = sections[i].getStates().copy();
                    data.chunks.put(ChunkPos.asLong(x, z), copies);
                }
            }
            data.setDirty();
        } finally {
            // Players (and the world border keeping them in) hold the area loaded from here on; the sweep loads it
            // again if it was unloaded.
            chunks.removeRegionTicket(TICKET, centre, distance, centre);
        }
    }

    /** Drives chunk loading on the server thread, the way {@code ServerChunkCache.getChunk} waits for a chunk. */
    private static void waitUntilTicking(ServerChunkCache chunks, ChunkPos centre, int radius) {
        long deadline = System.nanoTime() + TICKING_TIMEOUT_NANOS;
        while (!allTicking(chunks, centre, radius)) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("Run area around chunk " + centre + " did not start ticking in time");
            }
            if (!chunks.pollTask()) LockSupport.parkNanos(100_000L);
        }
    }

    private static boolean allTicking(ServerChunkCache chunks, ChunkPos centre, int radius) {
        for (int x = centre.x - radius; x <= centre.x + radius; x++) {
            for (int z = centre.z - radius; z <= centre.z + radius; z++) {
                if (!chunks.isPositionTicking(ChunkPos.asLong(x, z))) return false;
            }
        }
        return true;
    }
}
