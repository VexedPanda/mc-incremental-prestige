package io.github.vexedpanda.mcincremental.prestige.controller;

import java.math.BigInteger;

import io.github.vexedpanda.mcincremental.prestige.emc.WorldEmc;
import io.github.vexedpanda.mcincremental.prestige.run.RunArea;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Runs a Prestige and tells every player what it gave: the EMC gained (the world's balance after minus before, which is
 * what the Prestige sweep added) and the new balance. Server thread only.
 */
public final class PrestigeSummary {
    private PrestigeSummary() {}

    /** {@link Prestige#run}, then the summary to every player. @return the new Run area */
    public static RunArea runAndAnnounce(MinecraftServer server) {
        BigInteger before = WorldEmc.balance(server);
        RunArea area = Prestige.run(server);
        BigInteger after = WorldEmc.balance(server);
        Component summary = Component.translatable("mcincremental_prestige.prestige.summary",
                after.subtract(before).toString(), after.toString());
        for (ServerPlayer player : Prestige.players(server)) player.sendSystemMessage(summary);
        return area;
    }
}
