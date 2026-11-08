package io.github.vexedpanda.mcincremental.prestige.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.github.vexedpanda.mcincremental.prestige.McIncrementalPrestige;
import io.github.vexedpanda.mcincremental.prestige.network.CandidateNetwork;
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
 * The Prestige controller: ends the world's current Run, offers Candidate areas for the next one and starts it in the
 * chosen area (ADR-0001, ADR-0004, ADR-0008). Everything here is world-global: a Prestige affects every player in the
 * world, and any of them (or the console) chooses for all. Server thread only.
 */
public final class Prestige {
    private Prestige() {}

    /**
     * Runs a Prestige for the world, the first of its two steps: sweeps the current Run area into the world's EMC
     * balance, converts every player's ender chest and inventory into EMC (crediting them, then emptying them), then
     * offers {@link CandidateAreas#COUNT} Candidate areas for the next Run, previewed from their generated chunks,
     * holding every player still until one is chosen ({@link #choose}). Knowledge is untouched, and the balance only
     * grows by what the sweep and the conversion credit.
     *
     * <p>While candidates are already offered, a Prestige only re-rolls them: the old area is already swept and the
     * players already converted, so nothing is swept or converted again.
     *
     * @return the Candidate areas now offered
     */
    public static List<CandidateArea> run(MinecraftServer server) {
        if (CandidateAreas.isOffered(server)) {
            CandidateAreas.offerFresh(server);
            McIncrementalPrestige.LOGGER.info("Prestige: re-rolled the Candidate areas");
            return CandidateAreas.offered(server);
        }
        long start = System.nanoTime();
        EmcCredit credit = PrestigeSweep.sweep(server);
        long sweptAt = System.nanoTime();
        for (ServerPlayer player : players(server)) {
            // Owner decisions: ender chests on #30, online inventories on #57.
            credit.addAndClear(player.getEnderChestInventory());
            convertInventory(player, credit);
            CandidateAreas.markConverted(server, player.getUUID());
        }
        credit.creditTo(server);
        McIncrementalPrestige.LOGGER.info("Prestige: sweep, inventories and ender chests credited {} in {} ms", credit, (sweptAt - start) / 1_000_000);
        CandidateAreas.offerFresh(server);
        return CandidateAreas.offered(server);
    }

    /**
     * The second step of a Prestige: starts the Run in Candidate area {@code number} (from 1) for everyone, with the
     * usual Run start (border, world spawn, Run snapshot, starting items, teleport), and closes every player's Candidate
     * selection screen. The command and the screen both choose through here. The first choice wins: with no
     * candidates offered (none yet, or already chosen) it does nothing.
     *
     * @return the new Run area, or empty if nothing was chosen
     */
    public static Optional<RunArea> choose(MinecraftServer server, int number) {
        Optional<RunArea> chosen = CandidateAreas.take(server, number);
        chosen.ifPresent(area -> {
            List<ServerPlayer> players = players(server);
            for (ServerPlayer player : players) StartingItems.topUp(player);
            startRun(server, area, players);
            // Nothing is offered any more: this closes every player's Candidate selection screen.
            for (ServerPlayer player : players) CandidateNetwork.send(player);
        });
        return chosen;
    }

    /**
     * Starts the world's first Run if it has none yet: a new Run area, picked as a candidate would be but with no
     * choice, and no sweep, wipe or starting items. Does nothing for a world that already has a Run.
     *
     * @return the first Run area, or empty if the world already had a Run
     */
    public static Optional<RunArea> startFirstRun(MinecraftServer server) {
        if (RunAreas.current(server).isPresent()) return Optional.empty();
        return Optional.of(startRun(server, RunAreas.candidates(server, 1).getFirst(), players(server)));
    }

    /**
     * Starts a new Run in {@code area}: the single place a Run begins, after a choice and for the world's first Run.
     * Makes it the current Run area (border and world spawn move to it), takes its Run start snapshot and teleports
     * the players there.
     */
    private static RunArea startRun(MinecraftServer server, RunArea area, List<ServerPlayer> players) {
        RunAreas.beginRun(server, area);
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

    /**
     * Converts the player's inventory into EMC: adds everything they hold to {@code credit} at full value, then wipes
     * it. Covers the main inventory, armour, offhand, the 2x2 crafting grid, the stack on the cursor and the input
     * slots of an open menu (a crafting table's grid, an anvil's inputs), which the menu hands back on closing. Not the
     * ender chest. The one place a player's inventory is credited, for a Prestige and for a missed one.
     */
    static void convertInventory(ServerPlayer player, EmcCredit credit) {
        credit.addAndClear(player.getInventory());
        if (player.containerMenu != player.inventoryMenu) {
            // The inventory is empty now, so the menu has room to hand back its slots and the cursor instead of
            // dropping them. Each menu knows which of its slots are the player's to take back.
            player.closeContainer();
            credit.addAndClear(player.getInventory());
        }
        credit.addAndClear(player.inventoryMenu.getCraftSlots());
        credit.addItem(player.inventoryMenu.getCarried());
        player.inventoryMenu.setCarried(ItemStack.EMPTY);
        player.inventoryMenu.broadcastChanges();
    }
}
