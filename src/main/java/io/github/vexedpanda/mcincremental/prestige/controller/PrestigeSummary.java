package io.github.vexedpanda.mcincremental.prestige.controller;

import java.math.BigInteger;
import java.util.List;

import io.github.vexedpanda.mcincremental.prestige.emc.WorldEmc;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Runs a Prestige and tells every player what it gave: the EMC gained (the world's balance after minus before, which is
 * what the Prestige sweep added) and the new balance, then lists the Candidate areas offered. A re-roll only lists the
 * new candidates. Server thread only.
 */
public final class PrestigeSummary {
    private PrestigeSummary() {}

    /** {@link Prestige#run}, then the summary and the candidates to every player. @return the candidates offered */
    public static List<CandidateArea> runAndAnnounce(MinecraftServer server) {
        boolean reroll = CandidateAreas.isOffered(server);
        BigInteger before = WorldEmc.balance(server);
        List<CandidateArea> candidates = Prestige.run(server);
        BigInteger after = WorldEmc.balance(server);
        Component summary = Component.translatable("mcincremental_prestige.prestige.summary",
                after.subtract(before).toString(), after.toString());
        for (ServerPlayer player : Prestige.players(server)) {
            if (!reroll) player.sendSystemMessage(summary);
            CandidateAreas.list(player);
        }
        return candidates;
    }
}
