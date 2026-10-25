package io.github.vexedpanda.mcincremental.prestige.gametest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;

/**
 * Shared GameTest helpers. GameTests only run in dev ({@code runGameTestServer}); these classes are inert in the
 * shipped jar.
 */
public final class GameTests {
    /** A 3x3x3 air structure at {@code data/mcincremental_prestige/structure/empty.nbt}. */
    public static final String EMPTY_TEMPLATE = "empty";

    private GameTests() {}

    /**
     * ProjectE builds its EMC map only on player join or {@code /reload}, so on the GameTest server every value is 0
     * until this runs. Call it at the start of any test that reads EMC, then wait with {@code succeedWhen}: the
     * rebuild finishes a few ticks later. Batch order is undefined, so don't rely on another test having done it.
     */
    public static void rebuildEmcMap(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        server.reloadResources(server.getPackRepository().getSelectedIds());
    }
}
