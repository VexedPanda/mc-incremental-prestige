package io.github.vexedpanda.mcincremental.prestige.barrel;

import com.mojang.serialization.MapCodec;

import moze_intel.projecte.api.ItemInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** The EMC barrel block. Its behaviour lives in {@link EmcBarrelBlockEntity}. */
public class EmcBarrelBlock extends BaseEntityBlock {
    private static final String CONFIGURED = "block.mcincremental_prestige.emc_barrel.configured";
    private static final String NOT_LEARNED = "block.mcincremental_prestige.emc_barrel.not_learned";
    private static final String UNCONFIGURED = "block.mcincremental_prestige.emc_barrel.unconfigured";
    private static final String SHOWS = "block.mcincremental_prestige.emc_barrel.shows";
    private static final MapCodec<EmcBarrelBlock> CODEC = simpleCodec(EmcBarrelBlock::new);

    public EmcBarrelBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<EmcBarrelBlock> codec() {
        return CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    /** Right-click holding a learned item to configure the barrel to it; the held item is not used up. */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
            InteractionHand hand, BlockHitResult hit) {
        if (stack.isEmpty() || !(level.getBlockEntity(pos) instanceof EmcBarrelBlockEntity barrel)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (level.isClientSide()) return ItemInteractionResult.SUCCESS;
        if (barrel.configure(stack)) {
            player.displayClientMessage(Component.translatable(CONFIGURED, stack.getHoverName()), true);
        } else {
            player.displayClientMessage(Component.translatable(NOT_LEARNED, stack.getHoverName()), true);
        }
        return ItemInteractionResult.CONSUME;
    }

    /** Right-click with an empty hand shows what the barrel is configured to. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof EmcBarrelBlockEntity barrel)) return InteractionResult.PASS;
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        ItemInfo item = barrel.configuredItem();
        player.displayClientMessage(item == null ? Component.translatable(UNCONFIGURED)
                : Component.translatable(SHOWS, item.createStack().getHoverName()), true);
        return InteractionResult.CONSUME;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new EmcBarrelBlockEntity(pos, state);
    }
}
