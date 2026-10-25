package io.github.vexedpanda.mcincremental.prestige.gametest;

import java.util.ArrayList;
import java.util.List;

import io.github.vexedpanda.mcincremental.prestige.McIncrementalPrestige;
import io.github.vexedpanda.mcincremental.prestige.run.RunArea;
import io.github.vexedpanda.mcincremental.prestige.run.RunAreas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PortalProcessor;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * A Run stays in its Run dimension (pack ADR-0008): during a Run, lighting a Nether portal frame fails and portal
 * blocks don't move entities. The {@code portals_during_run} batch gives the world a Run (a far dummy Run area, which
 * moves nothing) and puts the record back afterwards; the {@code portals_without_run} batch is the control, showing
 * the same frame and portal work when there is no Run.
 */
@GameTestHolder(McIncrementalPrestige.MODID)
@PrefixGameTestTemplate(false)
public class RunDimensionGameTests {
    private static final String DURING_RUN = "portals_during_run";
    private static final String WITHOUT_RUN = "portals_without_run";

    /**
     * The smallest Nether portal frame: obsidian standing along x at z = 1, corners left out
     * ({@code data/mcincremental_prestige/structure/nether_portal_frame.nbt}, 4x5x3). The test helper's y = 0 is the
     * floor under the template, so the frame's inside is x 1 to 2, y 2 to 4.
     */
    private static final String FRAME_TEMPLATE = "nether_portal_frame";
    /** The bottom-left block inside the frame of {@link #FRAME_TEMPLATE}. */
    private static final BlockPos INSIDE_FRAME = new BlockPos(1, 2, 1);

    private static List<RunArea> visitedBefore = List.of();

    @BeforeBatch(batch = DURING_RUN)
    public static void startRun(ServerLevel level) {
        visitedBefore = RunAreas.visited(level.getServer());
        List<RunArea> withRun = new ArrayList<>(visitedBefore);
        withRun.add(new RunArea(new ChunkPos(1_500_000, 1_500_000), 1));
        RunAreas.replaceVisited(level.getServer(), withRun);
    }

    @AfterBatch(batch = DURING_RUN)
    public static void endRun(ServerLevel level) {
        RunAreas.replaceVisited(level.getServer(), visitedBefore);
    }

    @BeforeBatch(batch = WITHOUT_RUN)
    public static void noRun(ServerLevel level) {
        visitedBefore = RunAreas.visited(level.getServer());
        RunAreas.replaceVisited(level.getServer(), List.of());
    }

    @AfterBatch(batch = WITHOUT_RUN)
    public static void restoreRuns(ServerLevel level) {
        RunAreas.replaceVisited(level.getServer(), visitedBefore);
    }

    @GameTest(template = FRAME_TEMPLATE, batch = DURING_RUN)
    public static void lightingAnObsidianFrameDuringARunMakesNoPortal(GameTestHelper helper) {
        helper.setBlock(INSIDE_FRAME, Blocks.FIRE);
        helper.assertTrue(!anyPortalInFrame(helper), "Lighting the frame during a Run should make no portal");
        helper.succeed();
    }

    @GameTest(template = FRAME_TEMPLATE, batch = WITHOUT_RUN)
    public static void lightingAnObsidianFrameWithoutARunMakesAPortal(GameTestHelper helper) {
        helper.setBlock(INSIDE_FRAME, Blocks.FIRE);
        helper.assertTrue(anyPortalInFrame(helper), "Control: the frame should light when there is no Run.");
        helper.succeed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = DURING_RUN)
    public static void anEntityInANetherPortalDuringARunStaysPut(GameTestHelper helper) {
        assertEntityStaysIn(helper, netherPortal());
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = DURING_RUN)
    public static void anEntityInAnEndPortalDuringARunStaysPut(GameTestHelper helper) {
        assertEntityStaysIn(helper, Blocks.END_PORTAL.defaultBlockState());
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = WITHOUT_RUN)
    public static void anEntityInANetherPortalWithoutARunLeaves(GameTestHelper helper) {
        Pig pig = pigIn(helper, netherPortal());
        helper.succeedWhen(() -> helper.assertTrue(pig.isRemoved(),
                "Control: the pig should go through the portal when there is no Run"));
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = DURING_RUN, timeoutTicks = 200)
    public static void thePrestigeTeleportStillMovesAPlayerOutOfTheNetherFromAPortal(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel nether = server.getLevel(Level.NETHER);
        RunArea area = RunAreas.current(server).orElseThrow(() -> new GameTestAssertException("No Run area"));
        PrestigeGameTests.withWorldRestored(helper, () -> {
            ServerPlayer player = GameTests.joinedTravellingPlayer(helper, "traveller");
            try {
                player.teleportTo(nether, 0.5, 64, 0.5, 0, 0);
                helper.assertTrue(player.level().dimension() == Level.NETHER, "Setup: the player should be in the Nether");
                // Standing in a Nether portal as Prestige happens.
                player.portalProcess = new PortalProcessor((Portal) Blocks.NETHER_PORTAL, player.blockPosition());

                RunAreas.teleport(player, area);

                helper.assertTrue(player.level().dimension() == Level.OVERWORLD,
                        "The Prestige teleport should bring the player back to the overworld, got " + player.level().dimension());
                helper.assertTrue(area.contains(player.blockPosition()),
                        "The player at " + player.blockPosition() + " should be in the Run area around " + area.centre());
            } finally {
                player.discard();
            }
        });
        helper.succeed();
    }

    /** A pig stood in a single portal block for 20 ticks (non-players use a portal on their first tick in it). */
    private static void assertEntityStaysIn(GameTestHelper helper, BlockState portal) {
        Pig pig = pigIn(helper, portal);
        BlockPos start = pig.blockPosition();
        helper.runAfterDelay(20, () -> {
            helper.assertTrue(!pig.isRemoved(), "The pig should not leave through the portal during a Run");
            helper.assertTrue(pig.level().dimension() == Level.OVERWORLD, "The pig should stay in the overworld");
            helper.assertTrue(pig.blockPosition().equals(start),
                    "The pig should stay at " + start + ", got " + pig.blockPosition());
            pig.discard();
            helper.succeed();
        });
    }

    /**
     * A portal block placed without block updates, so it stands without a frame, and a pig in it that can't wander
     * out.
     */
    private static Pig pigIn(GameTestHelper helper, BlockState portal) {
        BlockPos pos = new BlockPos(1, 0, 1); // standing on the ground, so it can't fall out of the portal
        helper.getLevel().setBlock(helper.absolutePos(pos), portal, Block.UPDATE_CLIENTS);
        return helper.spawnWithNoFreeWill(EntityType.PIG, pos);
    }

    private static BlockState netherPortal() {
        return Blocks.NETHER_PORTAL.defaultBlockState().setValue(NetherPortalBlock.AXIS, Direction.Axis.X);
    }

    private static boolean anyPortalInFrame(GameTestHelper helper) {
        for (int x = 1; x <= 2; x++) {
            for (int y = 2; y <= 4; y++) {
                if (helper.getBlockState(new BlockPos(x, y, 1)).is(Blocks.NETHER_PORTAL)) return true;
            }
        }
        return false;
    }
}
