package io.github.vexedpanda.mcincremental.prestige.network;

import io.github.vexedpanda.mcincremental.prestige.McIncrementalPrestige;
import io.github.vexedpanda.mcincremental.prestige.client.ClientCandidates;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * The Candidate selection screen's network channel: {@link CandidatesPayload} to the client, {@link
 * ChooseCandidatePayload} and {@link RequestCandidatesPayload} to the server. The server decides everything; the
 * client only shows what it is sent and reports clicks. Server thread only, apart from registration.
 */
@EventBusSubscriber(modid = McIncrementalPrestige.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class CandidateNetwork {
    private static final String VERSION = "1";

    private CandidateNetwork() {}

    @SubscribeEvent
    static void register(RegisterPayloadHandlersEvent event) {
        event.registrar(VERSION)
                // A lambda body, not a method reference, so a dedicated server never loads the client class.
                .playToClient(CandidatesPayload.TYPE, CandidatesPayload.STREAM_CODEC,
                        (payload, context) -> ClientCandidates.handle(payload))
                .playToServer(ChooseCandidatePayload.TYPE, ChooseCandidatePayload.STREAM_CODEC, ChooseCandidatePayload::handle)
                .playToServer(RequestCandidatesPayload.TYPE, RequestCandidatesPayload.STREAM_CODEC, RequestCandidatesPayload::handle);
    }

    /**
     * Sends the player the candidates on offer now (no rows if none, which closes their screen). Skipped for a
     * connection without this mod's channel: a client without the mod, or a GameTest player.
     */
    public static void send(ServerPlayer player) {
        if (player.connection != null && player.connection.hasChannel(CandidatesPayload.TYPE)) {
            PacketDistributor.sendToPlayer(player, CandidatesPayload.offered(player.getServer()));
        }
    }
}
