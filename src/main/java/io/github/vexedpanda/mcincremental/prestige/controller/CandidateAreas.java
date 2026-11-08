package io.github.vexedpanda.mcincremental.prestige.controller;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.vexedpanda.mcincremental.prestige.McIncrementalPrestige;
import io.github.vexedpanda.mcincremental.prestige.run.RunArea;
import net.minecraft.Util;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.biome.Biome;

/**
 * The world's choosing state between the two steps of a Prestige: after the sweep, the world offers
 * {@link #COUNT} Candidate areas until one is chosen ({@link Prestige#choose}). World-global and saved with the world.
 * While candidates are offered, every player is held still and unhurt ({@link ChoosingFreeze}). Server thread only.
 */
public final class CandidateAreas {
    /** How many Candidate areas a Prestige offers. */
    public static final int COUNT = 3;

    /** Chat heading above the candidates. */
    public static final String HEADING_KEY = McIncrementalPrestige.MODID + ".candidates.heading";
    /** Chat line for one candidate: its number, its middle's x and z, and the biome at its centre. */
    public static final String LINE_KEY = McIncrementalPrestige.MODID + ".candidates.line";

    private CandidateAreas() {}

    /** The Candidate areas on offer, numbered from 1 in this order; empty when no choice is pending. */
    public static List<RunArea> offered(MinecraftServer server) {
        return List.copyOf(CandidateAreaData.get(server).candidates);
    }

    /** Whether the world is between a Prestige and the choice of its next Run area. */
    public static boolean isOffered(MinecraftServer server) {
        return !CandidateAreaData.get(server).candidates.isEmpty();
    }

    /**
     * Withdraws any candidates on offer, as if none had been: no Run starts and players are released. For GameTests,
     * which share one persistent world and must not leave it mid-choice.
     */
    public static void withdraw(MinecraftServer server) {
        CandidateAreaData data = CandidateAreaData.get(server);
        data.candidates.clear();
        data.converted.clear();
        data.setDirty();
        ChoosingFreeze.release();
    }

    /** Offers {@code candidates}, replacing any on offer (a re-roll). Players stay held where they are. */
    static void offer(MinecraftServer server, List<RunArea> candidates) {
        CandidateAreaData data = CandidateAreaData.get(server);
        data.candidates.clear();
        data.candidates.addAll(candidates);
        data.setDirty();
    }

    /**
     * Takes candidate {@code number} (from 1) and ends the choice: nothing is offered any more and players are
     * released. Empty, changing nothing, when no candidate has that number (none offered, or already chosen).
     */
    static Optional<RunArea> take(MinecraftServer server, int number) {
        CandidateAreaData data = CandidateAreaData.get(server);
        if (number < 1 || number > data.candidates.size()) return Optional.empty();
        RunArea chosen = data.candidates.get(number - 1);
        withdraw(server);
        return Optional.of(chosen);
    }

    /** Whether the pending Prestige has converted this player's inventory and ender chest. */
    static boolean isConverted(MinecraftServer server, UUID player) {
        return CandidateAreaData.get(server).converted.contains(player);
    }

    /** Records that the pending Prestige has converted this player's inventory and ender chest. */
    static void markConverted(MinecraftServer server, UUID player) {
        CandidateAreaData data = CandidateAreaData.get(server);
        if (data.converted.add(player)) data.setDirty();
    }

    /** Lists the candidates on offer in the player's chat, with the command that chooses one. */
    static void list(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        List<RunArea> candidates = offered(server);
        if (candidates.isEmpty()) return;
        player.sendSystemMessage(Component.translatable(HEADING_KEY, candidates.size()));
        ServerLevel level = server.overworld();
        for (int i = 0; i < candidates.size(); i++) {
            RunArea area = candidates.get(i);
            player.sendSystemMessage(Component.translatable(LINE_KEY, i + 1,
                    (int) Math.floor(area.middleX()), (int) Math.floor(area.middleZ()), biomeName(level, area)));
        }
    }

    /** The biome at the area's middle, at sea level, from the biome source (generates nothing). */
    private static Component biomeName(ServerLevel level, RunArea area) {
        Holder<Biome> biome = level.getUncachedNoiseBiome(QuartPos.fromBlock((int) Math.floor(area.middleX())),
                QuartPos.fromBlock(level.getSeaLevel()), QuartPos.fromBlock((int) Math.floor(area.middleZ())));
        return biome.unwrapKey()
                .<Component>map(key -> Component.translatable(Util.makeDescriptionId("biome", key.location())))
                .orElseGet(() -> Component.literal("?"));
    }
}
