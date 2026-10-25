package io.github.vexedpanda.mcincremental.prestige.gametest;

import java.util.List;

import io.github.vexedpanda.mcincremental.prestige.McIncrementalPrestige;
import io.github.vexedpanda.mcincremental.prestige.controller.Prestige;
import io.github.vexedpanda.mcincremental.prestige.run.RunArea;
import io.github.vexedpanda.mcincremental.prestige.run.RunAreas;
import io.github.vexedpanda.mcincremental.prestige.sweep.RunSnapshots;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.border.WorldBorder;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The first Run: a world with no Run yet starts one when the server starts. The GameTest server doesn't do that
 * itself (it would move the border and spawn under every test), so these tests start it explicitly, on a world whose
 * Run history is emptied for the test and put back afterwards.
 */
@GameTestHolder(McIncrementalPrestige.MODID)
@PrefixGameTestTemplate(false)
public class FirstRunGameTests {
    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "first_run", timeoutTicks = 400)
    public static void aWorldWithNoRunStartsItsFirstRunConfinedByTheBorder(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel overworld = server.overworld();
        withNoRunYet(helper, () -> {
            ChunkPos worldSpawn = new ChunkPos(overworld.getSharedSpawnPos());

            RunArea area = Prestige.startFirstRun(server)
                    .orElseThrow(() -> new GameTestAssertException("A world with no Run should start one"));

            helper.assertTrue(RunAreas.current(server).equals(java.util.Optional.of(area)), "The first Run area should be current");
            helper.assertTrue(RunAreas.visited(server).equals(List.of(area)), "The first Run area should be the only one visited");
            helper.assertTrue(RunSnapshots.area(server).equals(java.util.Optional.of(area)),
                    "The first Run area should be snapshotted so the first Prestige sweeps it, got " + RunSnapshots.area(server));
            ChunkPos c = area.centre();
            helper.assertTrue(Math.floorMod(c.x, 3) == 0 && Math.floorMod(c.z, 3) == 0,
                    "First Run area should be centred on a GT grid chunk, got " + c);
            // Same rule as later areas: 64 to 128 chunks (straight line) from where the world was, here its spawn,
            // give or take the snap to the 3-chunk grid.
            double chunks = Math.hypot(c.x - worldSpawn.x, c.z - worldSpawn.z);
            helper.assertTrue(chunks >= 64 - 3 && chunks <= 128 + 3,
                    "First Run area " + c + " should be 64-128 chunks from the world spawn chunk " + worldSpawn + ", is " + chunks);

            WorldBorder border = overworld.getWorldBorder();
            helper.assertTrue(border.getCenterX() == c.x * 16 + 8 && border.getCenterZ() == c.z * 16 + 8,
                    "Border centre should be " + (c.x * 16 + 8) + "," + (c.z * 16 + 8) + ", got "
                            + border.getCenterX() + "," + border.getCenterZ());
            helper.assertTrue(border.getSize() == 48, "Border size should be 48, got " + border.getSize());
            // New players join at the world spawn (within the spawn radius, which the border clamps).
            BlockPos spawn = overworld.getSharedSpawnPos();
            helper.assertTrue(area.contains(spawn), "World spawn " + spawn + " should be in the first Run area around " + c);
        });
        helper.succeed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "first_run_existing", timeoutTicks = 400)
    public static void aWorldThatAlreadyHasARunKeepsIt(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel overworld = server.overworld();
        withNoRunYet(helper, () -> {
            Prestige.startFirstRun(server);
            List<RunArea> visited = RunAreas.visited(server);
            WorldBorder border = overworld.getWorldBorder();
            double x = border.getCenterX(), z = border.getCenterZ(), size = border.getSize();
            BlockPos spawn = overworld.getSharedSpawnPos();

            // As on every later server start.
            helper.assertTrue(Prestige.startFirstRun(server).isEmpty(), "A world with a Run should not start another");

            helper.assertTrue(RunAreas.visited(server).equals(visited), "The Run areas should be untouched");
            helper.assertTrue(border.getCenterX() == x && border.getCenterZ() == z && border.getSize() == size,
                    "The world border should be untouched");
            helper.assertTrue(overworld.getSharedSpawnPos().equals(spawn), "The world spawn should be untouched");
        });
        helper.succeed();
    }

    /**
     * Runs the body on a world with no Run yet, then puts back the Run areas, world border and world spawn: they are
     * shared by every test and persist in the test world between runs.
     */
    private static void withNoRunYet(GameTestHelper helper, Runnable body) {
        MinecraftServer server = helper.getLevel().getServer();
        List<RunArea> visited = RunAreas.visited(server);
        PrestigeGameTests.withWorldRestored(helper, () -> {
            try {
                RunAreas.replaceVisited(server, List.of());
                body.run();
            } finally {
                RunAreas.replaceVisited(server, visited);
            }
        });
    }
}
