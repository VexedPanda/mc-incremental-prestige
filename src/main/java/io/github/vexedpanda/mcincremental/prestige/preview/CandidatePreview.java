package io.github.vexedpanda.mcincremental.prestige.preview;

import java.util.List;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.ResourceLocation;

/**
 * What a Candidate area holds, read from its generated chunks ({@link CandidatePreviews#read}) so the players can
 * choose where the next Run goes:
 * <ul>
 * <li>{@code biome}: the biome at the area's middle, on the surface (empty only if the biome is unregistered);</li>
 * <li>{@code veins}: the GT ore veins centred in the area whose ores were found in it, by vein type;</li>
 * <li>{@code ores}: every GT ore material found in the area's blocks, in a vein or not;</li>
 * <li>{@code structures}: the structures whose starts overlap the area;</li>
 * <li>{@code newOres}: the materials of its veins that are new, as of the offer: the world's Knowledge lacks them and
 * the world can harvest them ({@link NewOres}).</li>
 * </ul>
 * Every list is sorted and holds ids only (no GregTech types), so the preview is the same without GT, just with no
 * veins or ores.
 */
public record CandidatePreview(Optional<ResourceLocation> biome, List<OreVein> veins, List<ResourceLocation> ores,
        List<ResourceLocation> structures, List<ResourceLocation> newOres) {
    public static final Codec<CandidatePreview> CODEC = RecordCodecBuilder.create(i -> i.group(
            ResourceLocation.CODEC.optionalFieldOf("biome").forGetter(CandidatePreview::biome),
            OreVein.CODEC.listOf().fieldOf("veins").forGetter(CandidatePreview::veins),
            ResourceLocation.CODEC.listOf().fieldOf("ores").forGetter(CandidatePreview::ores),
            ResourceLocation.CODEC.listOf().fieldOf("structures").forGetter(CandidatePreview::structures),
            // Optional: candidates saved mid-choice before "new" marks existed have none.
            ResourceLocation.CODEC.listOf().optionalFieldOf("new_ores", List.of()).forGetter(CandidatePreview::newOres))
            .apply(i, CandidatePreview::new));

    public CandidatePreview {
        veins = List.copyOf(veins);
        ores = List.copyOf(ores);
        structures = List.copyOf(structures);
        newOres = List.copyOf(newOres);
    }

    /** A preview with nothing marked new. */
    public CandidatePreview(Optional<ResourceLocation> biome, List<OreVein> veins, List<ResourceLocation> ores,
            List<ResourceLocation> structures) {
        this(biome, veins, ores, structures, List.of());
    }

    /** This preview with {@code newOres} (sorted) marked new. */
    public CandidatePreview withNewOres(List<ResourceLocation> newOres) {
        return new CandidatePreview(biome, veins, ores, structures, newOres);
    }

    /** Whether the area offers anything new. */
    public boolean offersSomethingNew() {
        return !newOres.isEmpty();
    }

    /** Whether this ore material is marked new. */
    public boolean isNew(ResourceLocation material) {
        return newOres.contains(material);
    }

    /** A preview with only a biome: for an area whose chunks couldn't be read. */
    public static CandidatePreview biomeOnly(Optional<ResourceLocation> biome) {
        return new CandidatePreview(biome, List.of(), List.of(), List.of());
    }
}
