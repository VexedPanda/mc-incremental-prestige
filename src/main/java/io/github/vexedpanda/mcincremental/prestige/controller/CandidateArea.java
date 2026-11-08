package io.github.vexedpanda.mcincremental.prestige.controller;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.github.vexedpanda.mcincremental.prestige.preview.CandidatePreview;
import io.github.vexedpanda.mcincremental.prestige.run.RunArea;
import net.minecraft.world.level.ChunkPos;

/** A Candidate area: a Run area offered at a Prestige, with its preview. */
public record CandidateArea(RunArea area, CandidatePreview preview) {
    public static final Codec<CandidateArea> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.LONG.fieldOf("centre").forGetter(c -> c.area().centre().toLong()),
            Codec.INT.fieldOf("radius").forGetter(c -> c.area().radiusChunks()),
            CandidatePreview.CODEC.fieldOf("preview").forGetter(CandidateArea::preview))
            .apply(i, (centre, radius, preview) -> new CandidateArea(new RunArea(new ChunkPos(centre), radius), preview)));
}
