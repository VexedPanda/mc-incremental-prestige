package io.github.vexedpanda.mcincremental.prestige.preview.gt;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.gregtechceu.gtceu.api.block.OreBlock;
import com.gregtechceu.gtceu.api.data.chemical.material.Material;
import com.gregtechceu.gtceu.api.data.worldgen.GTOreDefinition;
import com.gregtechceu.gtceu.api.data.worldgen.generator.VeinGenerator;
import com.gregtechceu.gtceu.api.data.worldgen.ores.GeneratedVeinMetadata;
import com.gregtechceu.gtceu.integration.map.cache.server.ServerCache;

import io.github.vexedpanda.mcincremental.prestige.preview.OreVein;
import io.github.vexedpanda.mcincremental.prestige.run.RunArea;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * The GT ores and ore veins in a Candidate area, read from its generated chunks. GregTech is optional: only call this
 * when it is installed ({@code ModList.isLoaded("gtceu")}), and keep every GT type inside this package.
 *
 * <p>Ores: every GT {@link OreBlock} in the area's chunks, by material. Veins: GT records every vein it generates
 * (type, origin chunk and centre) in its {@link ServerCache}, whether or not a map mod is installed; a vein is listed
 * when its centre is in the area and at least one of its materials' ore blocks was found there, with the materials
 * found. Ore blocks are not tagged with their vein, so a material two veins share counts for both.
 */
public final class GtOres {
    /** The ores found in an area. */
    public record Found(List<OreVein> veins, List<ResourceLocation> ores) {}

    private GtOres() {}

    public static Found find(ServerLevel level, RunArea area, List<ChunkAccess> chunks) {
        Set<Material> found = new HashSet<>();
        for (ChunkAccess chunk : chunks) {
            for (LevelChunkSection section : chunk.getSections()) {
                if (section.hasOnlyAir() || !section.maybeHas(s -> s.getBlock() instanceof OreBlock)) continue;
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            if (section.getBlockState(x, y, z).getBlock() instanceof OreBlock ore) found.add(ore.material);
                        }
                    }
                }
            }
        }
        List<OreVein> veins = new ArrayList<>();
        Set<ResourceLocation> seen = new HashSet<>();
        int size = area.sizeBlocks() - 1;
        for (GeneratedVeinMetadata vein : ServerCache.instance.getVeinsInArea(level.dimension(),
                new int[] { area.minBlockX(), area.minBlockZ(), size, size })) {
            ResourceLocation type = vein.definition().unwrapKey().map(ResourceKey::location).orElse(null);
            GTOreDefinition definition = vein.definition().value();
            VeinGenerator generator = definition.veinGenerator();
            if (type == null || generator == null || !seen.add(type)) continue;
            TreeSet<ResourceLocation> materials = new TreeSet<>();
            for (Material material : generator.getAllMaterials()) {
                if (found.contains(material)) materials.add(material.getResourceLocation());
            }
            if (!materials.isEmpty()) veins.add(new OreVein(type, List.copyOf(materials)));
        }
        veins.sort(Comparator.comparing(OreVein::type));
        TreeSet<ResourceLocation> ores = new TreeSet<>();
        for (Material material : found) ores.add(material.getResourceLocation());
        return new Found(List.copyOf(veins), List.copyOf(ores));
    }
}
