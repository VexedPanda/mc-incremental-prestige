package io.github.vexedpanda.mcincremental.prestige.network;

import io.github.vexedpanda.mcincremental.prestige.McIncrementalPrestige;
import io.github.vexedpanda.mcincremental.prestige.controller.Prestige;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client to server: the player clicked Candidate area {@code number} (from 1, through the rows in order) on the
 * Candidate selection screen. Any player may choose, with no permission check (#74); the server applies the same
 * first-choice-wins rule as {@code /prestige choose}, so a click after the choice does nothing.
 */
public record ChooseCandidatePayload(int number) implements CustomPacketPayload {
    public static final Type<ChooseCandidatePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(McIncrementalPrestige.MODID, "choose_candidate"));
    public static final StreamCodec<ByteBuf, ChooseCandidatePayload> STREAM_CODEC =
            ByteBufCodecs.VAR_INT.map(ChooseCandidatePayload::new, ChooseCandidatePayload::number);

    /** The server's handler, on the main thread. */
    public static void handle(ChooseCandidatePayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        Prestige.choose(player.getServer(), payload.number()).ifPresent(area -> McIncrementalPrestige.LOGGER.info(
                "{} chose Candidate area {} on the selection screen", player.getScoreboardName(), payload.number()));
    }

    @Override
    public Type<ChooseCandidatePayload> type() {
        return TYPE;
    }
}
