package io.github.vexedpanda.mcincremental.prestige.network;

import java.util.ArrayList;
import java.util.List;

import io.github.vexedpanda.mcincremental.prestige.McIncrementalPrestige;
import io.github.vexedpanda.mcincremental.prestige.controller.CandidateArea;
import io.github.vexedpanda.mcincremental.prestige.controller.CandidateAreas;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

/**
 * Server to client: the Candidate areas on offer, one row per dimension in the server's numbering order, for the
 * Candidate selection screen. Sent when candidates are offered or re-rolled, when a player joins mid-choice and when a
 * player's screen asks again ({@link RequestCandidatesPayload}). No rows means nothing is offered: the screen closes.
 */
public record CandidatesPayload(List<Row> rows) implements CustomPacketPayload {
    /** One dimension's candidates, in order. */
    public record Row(ResourceKey<Level> dimension, List<CandidateArea> candidates) {
        public Row {
            candidates = List.copyOf(candidates);
        }

        static final StreamCodec<ByteBuf, Row> STREAM_CODEC = StreamCodec.composite(
                ResourceKey.streamCodec(Registries.DIMENSION), Row::dimension,
                ByteBufCodecs.fromCodec(CandidateArea.CODEC).apply(ByteBufCodecs.list()), Row::candidates,
                Row::new);
    }

    public static final Type<CandidatesPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(McIncrementalPrestige.MODID, "candidates"));
    public static final StreamCodec<ByteBuf, CandidatesPayload> STREAM_CODEC =
            Row.STREAM_CODEC.apply(ByteBufCodecs.list()).map(CandidatesPayload::new, CandidatesPayload::rows);

    public CandidatesPayload {
        rows = List.copyOf(rows);
    }

    /** The candidates the world offers now, by dimension; no rows when no choice is pending. */
    public static CandidatesPayload offered(MinecraftServer server) {
        List<Row> rows = new ArrayList<>();
        CandidateAreas.offeredByDimension(server).forEach((dimension, candidates) -> rows.add(new Row(dimension, candidates)));
        return new CandidatesPayload(rows);
    }

    /** Every candidate, numbered from 1 through the rows in order, as {@code /prestige choose} numbers them. */
    public List<CandidateArea> numbered() {
        return rows.stream().flatMap(row -> row.candidates().stream()).toList();
    }

    @Override
    public Type<CandidatesPayload> type() {
        return TYPE;
    }
}
