package io.github.vexedpanda.mcincremental.prestige.preview;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

import io.github.vexedpanda.mcincremental.prestige.McIncrementalPrestige;
import io.github.vexedpanda.mcincremental.prestige.preview.gt.GtOres;
import io.github.vexedpanda.mcincremental.prestige.run.RunArea;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.neoforged.fml.ModList;

/**
 * Reads a Candidate area's preview from its real blocks: generates the area's chunks (they are written to disk like
 * any other generated chunk), then reads the biome at the middle, the GT ore veins and ores, and the structures whose
 * starts overlap the area. Server thread only.
 */
public final class CandidatePreviews {
    private static final boolean GREGTECH_LOADED = ModList.get().isLoaded("gtceu");

    private CandidatePreviews() {}

    /**
     * Generates the area's chunks in {@code level} (if they aren't yet) and reads its preview from them. Never throws:
     * if the chunks can't be read, the preview has only the biome from the biome source, so a Prestige still offers
     * the area.
     */
    public static CandidatePreview read(ServerLevel level, RunArea area) {
        try {
            List<ChunkAccess> chunks = generate(level, area);
            List<OreVein> veins = List.of();
            List<ResourceLocation> ores = List.of();
            if (GREGTECH_LOADED) {
                GtOres.Found found = GtOres.find(level, area, chunks);
                veins = found.veins();
                ores = found.ores();
            }
            return new CandidatePreview(biome(level, area), veins, ores, structures(level, chunks));
        } catch (RuntimeException e) {
            McIncrementalPrestige.LOGGER.error("Couldn't read the preview of Candidate area {}", area, e);
            return CandidatePreview.biomeOnly(sourceBiome(level, area));
        }
    }

    /** Every chunk of the area, generated in full. */
    private static List<ChunkAccess> generate(ServerLevel level, RunArea area) {
        List<ChunkAccess> chunks = new ArrayList<>();
        ChunkPos c = area.centre();
        int r = area.radiusChunks();
        for (int x = c.x - r; x <= c.x + r; x++) {
            for (int z = c.z - r; z <= c.z + r; z++) {
                chunks.add(level.getChunk(x, z, ChunkStatus.FULL, true));
            }
        }
        return chunks;
    }

    /** The biome at the area's middle column, on the surface, from the generated chunk. */
    private static Optional<ResourceLocation> biome(ServerLevel level, RunArea area) {
        int x = (int) Math.floor(area.middleX()), z = (int) Math.floor(area.middleZ());
        BlockPos surface = new BlockPos(x, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z), z);
        return key(level.getBiome(surface));
    }

    /** The biome at the area's middle at sea level, from the biome source: generates nothing. */
    public static Optional<ResourceLocation> sourceBiome(ServerLevel level, RunArea area) {
        return key(level.getUncachedNoiseBiome(QuartPos.fromBlock((int) Math.floor(area.middleX())),
                QuartPos.fromBlock(level.getSeaLevel()), QuartPos.fromBlock((int) Math.floor(area.middleZ()))));
    }

    private static Optional<ResourceLocation> key(Holder<Biome> biome) {
        return biome.unwrapKey().map(ResourceKey::location);
    }

    /**
     * The structures whose starts overlap the area: worldgen gives a chunk a reference to a structure start only when
     * the start is valid and its bounding box covers the chunk, so the references in the area's chunks are exactly
     * those structures, including ones started in chunks outside the area. Loads no other chunk.
     */
    private static List<ResourceLocation> structures(ServerLevel level, List<ChunkAccess> chunks) {
        Registry<Structure> registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        TreeSet<ResourceLocation> found = new TreeSet<>();
        for (ChunkAccess chunk : chunks) {
            for (Map.Entry<Structure, LongSet> references : chunk.getAllReferences().entrySet()) {
                ResourceLocation id = registry.getKey(references.getKey());
                if (id != null && !references.getValue().isEmpty()) found.add(id);
            }
        }
        return List.copyOf(found);
    }
}
