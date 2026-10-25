package io.github.vexedpanda.mcincremental.prestige.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.github.vexedpanda.mcincremental.prestige.McIncrementalPrestige;
import io.github.vexedpanda.mcincremental.prestige.sweep.PrestigeSweep;
import io.github.vexedpanda.mcincremental.prestige.sweep.EmcCredit;
import io.github.vexedpanda.mcincremental.prestige.sweep.RunSnapshots;
import io.github.vexedpanda.mcincremental.prestige.run.RunArea;
import io.github.vexedpanda.mcincremental.prestige.run.RunAreas;
import io.github.vexedpanda.mcincremental.prestige.run.StartingItems;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * The Prestige controller: ends the world's current Run and starts a new one (ADR-0001, ADR-0004). Everything here is
 * world-global: a Prestige affects every player in the world. Server thread only.
 */
public final class Prestige {
    private Prestige() {}

    /**
     * Runs a Prestige for the world: sweeps the current Run area into the world's EMC balance, wipes every player's
     * inventory (crediting their ender chests, then emptying them) and gives them the starting items, starts a new Run area (border and world spawn move to it), snapshots it and teleports every player
     * there. Knowledge is untouched, and the balance only grows by what the sweep credits.
     *
     * @return the new Run area
     */
    public static RunArea run(MinecraftServer server) {
        long start = System.nanoTime();
        EmcCredit credit = PrestigeSweep.sweep(server);
        long sweptAt = System.nanoTime();
        List<ServerPlayer> players = players(server);
        for (ServerPlayer player : players) {
            // The ender chest is converted to EMC (owner decision on #30); the inventory itself is only wiped.
            credit.addAndClear(player.getEnderChestInventory());
            wipeInventory(player);
            StartingItems.give(player);
        }
        credit.creditTo(server);
        McIncrementalPrestige.LOGGER.info("Prestige: sweep and ender chests credited {} in {} ms", credit, (sweptAt - start) / 1_000_000);
        return startRun(server, players);
    }

    /**
     * Starts the world's first Run if it has none yet: a new Run area, as after a Prestige, but with no sweep, wipe or
     * starting items. Does nothing for a world that already has a Run.
     *
     * @return the first Run area, or empty if the world already had a Run
     */
    public static Optional<RunArea> startFirstRun(MinecraftServer server) {
        if (RunAreas.current(server).isPresent()) return Optional.empty();
        return Optional.of(startRun(server, players(server)));
    }

    /**
     * Starts a new Run: the single place a Run begins, for a Prestige and for the world's first Run. Chooses a new Run
     * area (border and world spawn move to it), takes its Run start snapshot and teleports the players there.
     */
    private static RunArea startRun(MinecraftServer server, List<ServerPlayer> players) {
        RunArea area = RunAreas.beginRun(server);
        long snapshotStart = System.nanoTime();
        RunSnapshots.take(server, area);
        McIncrementalPrestige.LOGGER.info("New Run area {} snapshotted in {} ms",
                area.centre(), (System.nanoTime() - snapshotStart) / 1_000_000);
        for (ServerPlayer player : players) {
            RunAreas.teleport(player, area);
            MissedPrestige.joinCurrentRun(player);
        }
        return area;
    }

    /**
     * Every player in the world's levels. Not {@code PlayerList}: GameTest players are in a level but not the list,
     * and a real player is always in a level.
     */
    static List<ServerPlayer> players(MinecraftServer server) {
        List<ServerPlayer> players = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (ServerPlayer player : level.players()) {
                if (!(player instanceof FakePlayer) && !player.isRemoved()) players.add(player);
            }
        }
        return players;
    }

    /** Inventory, armour, offhand, the 2x2 crafting grid and the stack on the cursor. Not the ender chest. */
    static void wipeInventory(ServerPlayer player) {
        player.getInventory().clearContent();
        player.inventoryMenu.getCraftSlots().clearContent();
        player.containerMenu.setCarried(ItemStack.EMPTY);
        player.inventoryMenu.setCarried(ItemStack.EMPTY);
        player.containerMenu.broadcastChanges();
    }
}
