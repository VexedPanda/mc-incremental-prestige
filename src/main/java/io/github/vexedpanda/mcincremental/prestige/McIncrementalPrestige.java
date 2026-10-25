package io.github.vexedpanda.mcincremental.prestige;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import io.github.vexedpanda.mcincremental.prestige.controller.PrestigeItems;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

@Mod(McIncrementalPrestige.MODID)
public class McIncrementalPrestige {
    public static final String MODID = "mcincremental_prestige";
    public static final Logger LOGGER = LogUtils.getLogger();

    public McIncrementalPrestige(IEventBus modEventBus, ModContainer modContainer) {
        PrestigeItems.register(modEventBus);
        LOGGER.info("Mc-incremental Prestige loaded");
    }
}
