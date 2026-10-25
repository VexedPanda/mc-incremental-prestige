package io.github.vexedpanda.mcincremental.prestige.controller;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * The Prestige item: using it asks for confirmation; sneaking and using it within {@link #CONFIRM_TICKS} runs a Prestige
 * for the world. Confirming takes a sneak-use rather than a second plain use because holding the use button repeats
 * the use every few ticks, which would confirm by accident. No GUI, so it works without the mod on the client.
 */
public class PrestigeItem extends Item {
    /** How long after a first use a second use confirms the Prestige: 5 seconds. */
    public static final int CONFIRM_TICKS = 100;

    /** Game time of each player's unconfirmed use. Server thread only; lost on restart, which just asks again. */
    private static final Map<UUID, Long> PENDING = new HashMap<>();

    public PrestigeItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player instanceof ServerPlayer serverPlayer) {
            long now = level.getGameTime();
            Long asked = PENDING.get(player.getUUID());
            boolean pending = asked != null && now >= asked && now - asked <= CONFIRM_TICKS;
            if (pending && player.isShiftKeyDown()) {
                PENDING.remove(player.getUUID());
                PrestigeSummary.runAndAnnounce(serverPlayer.getServer());
            } else if (!pending) {
                // Only a plain use starts the window. While it's open, more plain uses (a held button) do nothing.
                if (!player.isShiftKeyDown()) PENDING.put(player.getUUID(), now);
                serverPlayer.sendSystemMessage(Component.translatable("item.mcincremental_prestige.prestige.confirm",
                        CONFIRM_TICKS / 20));
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }
}
