package io.github.vexedpanda.mcincremental.prestige.controller;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** The Prestige item: using it runs a Prestige for the world. The confirmation step comes with #34. */
public class PrestigeItem extends Item {
    public PrestigeItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player instanceof ServerPlayer serverPlayer) Prestige.run(serverPlayer.getServer());
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }
}
