package io.github.vexedpanda.mcincremental.prestige.network;

import io.github.vexedpanda.mcincremental.prestige.McIncrementalPrestige;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client to server: the player's Candidate selection screen is closed while the client believes a choice is pending
 * (closed with Escape, or another screen was shown over it). The server answers with the current {@link
 * CandidatesPayload}: the candidates if they are still offered (the screen reopens), or no rows (it stays closed).
 */
public record RequestCandidatesPayload() implements CustomPacketPayload {
    public static final RequestCandidatesPayload INSTANCE = new RequestCandidatesPayload();
    public static final Type<RequestCandidatesPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(McIncrementalPrestige.MODID, "request_candidates"));
    public static final StreamCodec<ByteBuf, RequestCandidatesPayload> STREAM_CODEC = StreamCodec.unit(INSTANCE);

    /** The server's handler, on the main thread. */
    public static void handle(RequestCandidatesPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) CandidateNetwork.send(player);
    }

    @Override
    public Type<RequestCandidatesPayload> type() {
        return TYPE;
    }
}
