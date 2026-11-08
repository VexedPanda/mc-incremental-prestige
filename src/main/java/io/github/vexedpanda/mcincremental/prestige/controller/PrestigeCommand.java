package io.github.vexedpanda.mcincremental.prestige.controller;

import com.mojang.brigadier.arguments.IntegerArgumentType;

import io.github.vexedpanda.mcincremental.prestige.McIncrementalPrestige;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /prestige}: runs a Prestige for the world, with no confirmation, which offers Candidate areas (or re-rolls
 * them while they are offered). {@code /prestige choose <1-3>}: starts the Run in that candidate's area; the first
 * choice wins. For pack developers, server consoles and scripts (KubeJS, command blocks, functions), so both need
 * permission level 2 (operators, cheats on in singleplayer). Players Prestige with the Prestige item instead.
 */
@EventBusSubscriber(modid = McIncrementalPrestige.MODID)
public final class PrestigeCommand {
    private static final String KEY = "commands." + McIncrementalPrestige.MODID + ".prestige";

    private PrestigeCommand() {}

    @SubscribeEvent
    static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("prestige")
                .requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(context -> {
                    CommandSourceStack source = context.getSource();
                    PrestigeSummary.runAndAnnounce(source.getServer());
                    source.sendSuccess(() -> Component.translatable(KEY + ".success", CandidateAreas.COUNT), true);
                    return 1;
                })
                .then(Commands.literal("choose")
                        .then(Commands.argument("candidate", IntegerArgumentType.integer(1, CandidateAreas.COUNT))
                                .executes(context -> {
                                    CommandSourceStack source = context.getSource();
                                    int number = IntegerArgumentType.getInteger(context, "candidate");
                                    if (Prestige.choose(source.getServer(), number).isEmpty()) {
                                        source.sendFailure(Component.translatable(KEY + ".nothing_offered"));
                                        return 0;
                                    }
                                    source.sendSuccess(() -> Component.translatable(KEY + ".chosen", number), true);
                                    return 1;
                                }))));
    }
}
