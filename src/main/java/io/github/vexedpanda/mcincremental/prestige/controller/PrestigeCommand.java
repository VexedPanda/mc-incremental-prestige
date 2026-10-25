package io.github.vexedpanda.mcincremental.prestige.controller;

import io.github.vexedpanda.mcincremental.prestige.McIncrementalPrestige;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /prestige}: runs a Prestige for the world, with no confirmation. For pack developers and scripts (KubeJS,
 * command blocks, functions), so it needs permission level 2 (operators, cheats on in singleplayer). Players Prestige
 * with the Prestige item instead.
 */
@EventBusSubscriber(modid = McIncrementalPrestige.MODID)
public final class PrestigeCommand {
    private PrestigeCommand() {}

    @SubscribeEvent
    static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("prestige")
                .requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(context -> {
                    CommandSourceStack source = context.getSource();
                    Prestige.run(source.getServer());
                    source.sendSuccess(() -> Component.translatable("commands.mcincremental_prestige.prestige.success"), true);
                    return 1;
                }));
    }
}
