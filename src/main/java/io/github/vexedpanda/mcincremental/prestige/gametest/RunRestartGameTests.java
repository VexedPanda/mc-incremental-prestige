package io.github.vexedpanda.mcincremental.prestige.gametest;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.vexedpanda.mcincremental.prestige.McIncrementalPrestige;
import io.github.vexedpanda.mcincremental.prestige.emc.WorldEmc;
import io.github.vexedpanda.mcincremental.prestige.run.RunArea;
import io.github.vexedpanda.mcincremental.prestige.run.RunAreas;
import io.github.vexedpanda.mcincremental.prestige.sweep.RunSnapshots;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * A Run survives a server restart mid-Run: its Run area, the record of visited areas, the world border and the Run
 * snapshot persist, so a Prestige after the restart sweeps correctly. A GameTest can't restart the server, so
 * {@link #simulateRestart} saves the world the way shutdown does and reloads the mod's saved state from disk.
 */
@GameTestHolder(McIncrementalPrestige.MODID)
@PrefixGameTestTemplate(false)
public class RunRestartGameTests {
    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "run_restart", timeoutTicks = 400)
    public static void afterARestartMidRunPrestigeRefundsBlocksPlacedBeforeIt(GameTestHelper helper) {
        GameTests.rebuildEmcMap(helper);
        MinecraftServer server = helper.getLevel().getServer();
        helper.startSequence()
                .thenWaitUntil(() -> GameTests.assertEmcMapBuilt(helper))
                .thenExecute(() -> PrestigeGameTests.withWorldRestored(helper, () -> {
                    RunArea area = PrestigeSweepGameTests.startRun(helper);
                    ServerLevel level = PrestigeSweepGameTests.level(helper);
                    BlockPos placed = PrestigeSweepGameTests.surface(helper, area);
                    BlockPos ground = placed.below();
                    BlockState groundState = level.getBlockState(ground);
                    level.setBlockAndUpdate(placed, Blocks.DIAMOND_BLOCK.defaultBlockState());
                    List<RunArea> visited = RunAreas.visited(server);
                    WorldBorder border = level.getWorldBorder();
                    double borderX = border.getCenterX(), borderZ = border.getCenterZ(), borderSize = border.getSize();

                    simulateRestart(server);

                    helper.assertTrue(RunAreas.current(server).equals(Optional.of(area)),
                            "The Run area " + area + " should still be current, got " + RunAreas.current(server));
                    helper.assertTrue(RunAreas.visited(server).equals(visited),
                            "The visited Run areas should persist: " + visited + ", got " + RunAreas.visited(server));
                    helper.assertTrue(RunSnapshots.area(server).equals(Optional.of(area)),
                            "The Run snapshot should still be of " + area + ", got " + RunSnapshots.area(server));
                    border = level.getWorldBorder();
                    helper.assertTrue(border.getCenterX() == borderX && border.getCenterZ() == borderZ
                                    && border.getSize() == borderSize,
                            "The world border should persist at " + borderX + "," + borderZ + " size " + borderSize
                                    + ", got " + border.getCenterX() + "," + border.getCenterZ() + " size " + border.getSize());
                    BigInteger before = WorldEmc.balance(server);

                    PrestigeGameTests.runPrestigeCommand(helper);

                    helper.assertTrue(level.getBlockState(placed).isAir(),
                            "The diamond block placed before the restart should be swept, found " + level.getBlockState(placed));
                    helper.assertTrue(level.getBlockState(ground).equals(groundState),
                            "The untouched ground should be kept, found " + level.getBlockState(ground));
                    // ProjectE's default value for a diamond block: 9 diamonds at 8192.
                    PrestigeSweepGameTests.assertCredited(helper, before, 73728);
                }))
                .thenSucceed();
    }

    /**
     * Stands in for a server restart, as far as the Run's persisted state goes: saves every level and the level data
     * the way shutdown does, then forgets the mod's saved data so the next read loads it from disk, and re-applies the
     * world border from the saved level data as server start does. Players and loaded chunks are untouched.
     */
    static void simulateRestart(MinecraftServer server) {
        // Shutdown's save: writes each level's saved data and copies the live border into the level data. Forced, as
        // the GameTest level may have saving off.
        server.saveAllChunks(true, false, true);
        evictModSavedData(server.overworld().getDataStorage());
        WorldBorder border = server.overworld().getWorldBorder();
        border.setCenter(0, 0);
        border.setSize(WorldBorder.MAX_SIZE);
        border.applySettings(server.getWorldData().overworldData().getWorldBorder());
    }

    /**
     * Removes the key (not just the value: a key mapped to null reads as "no file") of every mod saved data from the
     * storage's cache. Only the mod's: vanilla holds on to some of its own instances.
     */
    @SuppressWarnings("unchecked")
    private static void evictModSavedData(DimensionDataStorage storage) {
        try {
            Field field = DimensionDataStorage.class.getDeclaredField("cache");
            field.setAccessible(true);
            Map<String, SavedData> cache = (Map<String, SavedData>) field.get(storage);
            cache.keySet().removeIf(name -> name.startsWith(McIncrementalPrestige.MODID + "_"));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Can't reach the saved data cache", e);
        }
    }
}
