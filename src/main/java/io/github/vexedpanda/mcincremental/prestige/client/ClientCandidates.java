package io.github.vexedpanda.mcincremental.prestige.client;

import io.github.vexedpanda.mcincremental.prestige.McIncrementalPrestige;
import io.github.vexedpanda.mcincremental.prestige.network.CandidatesPayload;
import io.github.vexedpanda.mcincremental.prestige.network.RequestCandidatesPayload;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The client side of the Candidate selection screen: shows what the server sends and nothing else. A {@link
 * CandidatesPayload} with rows opens (or refreshes) the {@link CandidateScreen}; one with no rows closes it. While the
 * last payload had rows and no screen is showing (the player pressed Escape, or a menu over it was closed), the client
 * asks the server again, which reopens the screen if the choice is still open. Client thread only.
 */
@EventBusSubscriber(modid = McIncrementalPrestige.MODID, value = Dist.CLIENT)
public final class ClientCandidates {
    /** Ticks between two requests while waiting for the server's answer. */
    private static final int REQUEST_INTERVAL_TICKS = 20;

    /** Whether the server's last payload offered candidates. */
    private static boolean offered;
    /** Ticks until the next request may be sent. */
    private static int requestCooldown;

    private ClientCandidates() {}

    /** The {@link CandidatesPayload} handler, on the client thread. */
    public static void handle(CandidatesPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        offered = !payload.rows().isEmpty();
        requestCooldown = 0;
        if (!offered) {
            if (minecraft.screen instanceof CandidateScreen) minecraft.setScreen(null);
        } else if (minecraft.screen == null || minecraft.screen instanceof CandidateScreen) {
            minecraft.setScreen(new CandidateScreen(payload));
        }
        // Another screen is showing (the pause menu, chat, a loading screen): ask again once it closes.
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!offered || minecraft.player == null || minecraft.getConnection() == null || minecraft.screen != null) return;
        if (requestCooldown > 0) {
            requestCooldown--;
            return;
        }
        PacketDistributor.sendToServer(RequestCandidatesPayload.INSTANCE);
        requestCooldown = REQUEST_INTERVAL_TICKS;
    }

    @SubscribeEvent
    static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        offered = false;
    }
}
