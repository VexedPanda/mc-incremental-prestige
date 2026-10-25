package io.github.vexedpanda.mcincremental.prestige;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import io.github.vexedpanda.mcincremental.prestige.barrel.EmcBarrels;
import io.github.vexedpanda.mcincremental.prestige.controller.PrestigeItems;
import io.github.vexedpanda.mcincremental.prestige.run.RunAreaConfig;
import io.github.vexedpanda.mcincremental.prestige.run.StartingItems;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

@Mod(McIncrementalPrestige.MODID)
public class McIncrementalPrestige {
    public static final String MODID = "mcincremental_prestige";
    public static final Logger LOGGER = LogUtils.getLogger();

    public McIncrementalPrestige(IEventBus modEventBus, ModContainer modContainer) {
        EmcBarrels.register(modEventBus);
        PrestigeItems.register(modEventBus);
        StartingItems.register(modEventBus);
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        modContainer.registerConfig(ModConfig.Type.SERVER, RunAreaConfig.SPEC);
        LOGGER.info("Mc-incremental Prestige loaded");
    }
}
