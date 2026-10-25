package io.github.vexedpanda.mcincremental.prestige.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import io.github.vexedpanda.mcincremental.prestige.McIncrementalPrestige;
import io.github.vexedpanda.mcincremental.prestige.run.RunArea;
import io.github.vexedpanda.mcincremental.prestige.run.RunAreas;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Singleplayer simulation distance is clamped to the Run area, so a player in its corner chunk still ticks all of it.
 */
@GameTestHolder(McIncrementalPrestige.MODID)
@PrefixGameTestTemplate(false)
public class SimulationDistanceGameTests {
    @GameTest(template = GameTests.EMPTY_TEMPLATE)
    public static void simulationDistanceReachesTheFarEdgeFromACornerChunk(GameTestHelper helper) {
        // A 3x3 area's far edge is 2 chunks from a corner chunk; a 5x5 area's is 4.
        int r1 = new RunArea(new ChunkPos(0, 0), 1).simulationDistanceChunks();
        int r2 = new RunArea(new ChunkPos(0, 0), 2).simulationDistanceChunks();
        helper.assertTrue(r1 == 2, "A radius-1 area needs simulation distance 2, got " + r1);
        helper.assertTrue(r2 == 4, "A radius-2 area needs simulation distance 4, got " + r2);
        helper.succeed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "simulation_distance_singleplayer")
    public static void singleplayerSimulationDistanceIsClampedToTheRunArea(GameTestHelper helper) {
        // A radius-2 area, so the expected 4 can only come from the area's radius (the dedicated default is 2).
        // 5 is the client slider's minimum.
        assertSimulationDistanceAfterTicks(helper, true, 2, 5, 4);
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "simulation_distance_dedicated")
    public static void nonSingleplayerServersKeepTheirSimulationDistance(GameTestHelper helper) {
        // The GameTest server has no singleplayer owner, like a dedicated server: server.properties' value stands.
        assertSimulationDistanceAfterTicks(helper, false, 2, 5, 5);
    }

    /**
     * Makes the current Run area one of {@code radius}, sets the simulation distance to {@code initial} as the client
     * slider would, and after a few server ticks asserts it is {@code expected}. Singleplayer is simulated by giving
     * the server a singleplayer owner. Everything is put back afterwards.
     */
    private static void assertSimulationDistanceAfterTicks(GameTestHelper helper, boolean singleplayer, int radius,
            int initial, int expected) {
        MinecraftServer server = helper.getLevel().getServer();
        PlayerList players = server.getPlayerList();
        List<RunArea> visited = RunAreas.visited(server);
        int before = players.getSimulationDistance();
        List<RunArea> withArea = new ArrayList<>(visited);
        // Far from the test structures; only the radius matters, and replaceVisited moves nothing.
        withArea.add(new RunArea(new ChunkPos(1_500_000, 1_500_000), radius));
        RunAreas.replaceVisited(server, withArea);
        if (singleplayer) server.setSingleplayerProfile(new GameProfile(UUID.randomUUID(), "host"));
        players.setSimulationDistance(initial);
        helper.startSequence()
                .thenIdle(3)
                .thenExecute(() -> {
                    try {
                        int actual = players.getSimulationDistance();
                        helper.assertTrue(actual == expected,
                                "Simulation distance should be " + expected + ", got " + actual);
                    } finally {
                        server.setSingleplayerProfile(null);
                        RunAreas.replaceVisited(server, visited);
                        players.setSimulationDistance(before);
                    }
                })
                .thenSucceed();
    }
}
