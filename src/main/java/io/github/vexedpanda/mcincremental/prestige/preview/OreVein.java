package io.github.vexedpanda.mcincremental.prestige.preview;

import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.ResourceLocation;

/**
 * A GT ore vein found in a Candidate area: its vein type (the GT ore definition's id, e.g. {@code gtceu:magnetite_vein})
 * and the ore materials of that vein actually found in the area's blocks (GT material ids, e.g. {@code gtceu:gold}),
 * sorted. Holds no GregTech types, so it works without GT installed.
 */
public record OreVein(ResourceLocation type, List<ResourceLocation> materials) {
    public static final Codec<OreVein> CODEC = RecordCodecBuilder.create(i -> i.group(
            ResourceLocation.CODEC.fieldOf("type").forGetter(OreVein::type),
            ResourceLocation.CODEC.listOf().fieldOf("materials").forGetter(OreVein::materials))
            .apply(i, OreVein::new));

    public OreVein {
        materials = List.copyOf(materials);
    }
}
