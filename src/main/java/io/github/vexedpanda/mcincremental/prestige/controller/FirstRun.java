package io.github.vexedpanda.mcincremental.prestige.controller;

import io.github.vexedpanda.mcincremental.prestige.McIncrementalPrestige;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/**
 * Starts the world's first Run when the server starts, so a new world confines its players to their first Run area
 * from the start and new players join inside it (the world spawn is there). A world that already has a Run is left
 * alone, so this is safe on every start.
 */
@EventBusSubscriber(modid = McIncrementalPrestige.MODID)
public final class FirstRun {
    /**
     * System property that turns this off when set to {@code false}. The GameTest server sets it: a first Run would
     * move the world border and spawn under every test, so the tests start it explicitly instead.
     */
    public static final String ENABLED_PROPERTY = McIncrementalPrestige.MODID + ".firstRunOnStart";

    private FirstRun() {}

    @SubscribeEvent
    static void onServerStarted(ServerStartedEvent event) {
        if (!Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "true"))) return;
        Prestige.startFirstRun(event.getServer()).ifPresent(area ->
                McIncrementalPrestige.LOGGER.info("Started the world's first Run in the Run area around chunk {}", area.centre()));
    }
}
