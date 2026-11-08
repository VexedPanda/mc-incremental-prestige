package io.github.vexedpanda.mcincremental.prestige.controller;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import io.github.vexedpanda.mcincremental.prestige.McIncrementalPrestige;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * While Candidate areas are offered, every player in the world is frozen in place and invulnerable: nothing should
 * happen to them in the swept-out old Run area before the Run starts. Each player is held where they were first seen
 * during the choice (at the Prestige, or where they joined), every server tick, so it needs nothing on the client and
 * covers players who join mid-choice. Server thread only.
 */
@EventBusSubscriber(modid = McIncrementalPrestige.MODID)
public final class ChoosingFreeze {
    private record Hold(ResourceKey<Level> dimension, Vec3 position) {}

    /** Where each player is held during the current choice. Lost on restart: players are then held where they join. */
    private static final Map<UUID, Hold> HELD = new HashMap<>();

    private ChoosingFreeze() {}

    /** Forgets every hold, so the next choice holds players where they are then. Called when a choice ends. */
    static void release() {
        HELD.clear();
    }

    /**
     * After every server tick (not the player tick, which only runs for players with a live connection), puts every
     * player back where they are held.
     */
    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (!CandidateAreas.isOffered(server)) {
            HELD.clear();
            return;
        }
        for (ServerPlayer player : Prestige.players(server)) {
            Hold hold = HELD.computeIfAbsent(player.getUUID(), uuid -> new Hold(player.level().dimension(),
                    player.position()));
            ServerLevel level = server.getLevel(hold.dimension());
            if (level == null) continue;
            if (player.level() != level || player.position().distanceToSqr(hold.position()) > 1.0E-6) {
                player.teleportTo(level, hold.position().x, hold.position().y, hold.position().z,
                        player.getYRot(), player.getXRot());
            }
            player.setDeltaMovement(Vec3.ZERO);
            player.resetFallDistance();
        }
    }

    @SubscribeEvent
    static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && !(player instanceof FakePlayer)
                && CandidateAreas.isOffered(player.getServer())) {
            event.setCanceled(true);
        }
    }
}
