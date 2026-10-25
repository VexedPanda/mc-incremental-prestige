package io.github.vexedpanda.mcincremental.prestige.controller;

import java.util.ArrayList;
import java.util.List;

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
     * Runs a Prestige for the world: wipes every player's inventory and gives them the starting items, starts a new Run area (border and world spawn
     * move to it) and teleports every player there. The world's EMC balance and Knowledge are untouched.
     *
     * @return the new Run area
     */
    public static RunArea run(MinecraftServer server) {
        // Prestige sweep of the old Run area goes here, before anything moves (#29).
        List<ServerPlayer> players = players(server);
        for (ServerPlayer player : players) {
            wipeInventory(player);
            StartingItems.give(player);
        }
        RunArea area = RunAreas.beginRun(server);
        // Run snapshot of the new area goes here, before players arrive (#29).
        for (ServerPlayer player : players) RunAreas.teleport(player, area);
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
    private static void wipeInventory(ServerPlayer player) {
        player.getInventory().clearContent();
        player.inventoryMenu.getCraftSlots().clearContent();
        player.containerMenu.setCarried(ItemStack.EMPTY);
        player.inventoryMenu.setCarried(ItemStack.EMPTY);
        player.containerMenu.broadcastChanges();
    }
}
