package io.github.vexedpanda.mcincremental.prestige.intake;

import java.math.BigInteger;

import com.mojang.serialization.MapCodec;

import io.github.vexedpanda.mcincremental.prestige.emc.ItemEmc;
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
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidUtil;

/** The EMC intake block. Its behaviour lives in {@link EmcIntakeBlockEntity}; using it feeds it the held item. */
public class EmcIntakeBlock extends BaseEntityBlock {
    private static final String CONVERTED = "block.mcincremental_prestige.emc_intake.converted";
    private static final String FLUID_CONVERTED = "block.mcincremental_prestige.emc_intake.fluid_converted";
    private static final String NO_VALUE = "block.mcincremental_prestige.emc_intake.no_value";
    private static final String USAGE = "block.mcincremental_prestige.emc_intake.usage";
    private static final MapCodec<EmcIntakeBlock> CODEC = simpleCodec(EmcIntakeBlock::new);

    public EmcIntakeBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<EmcIntakeBlock> codec() {
        return CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    /**
     * Right-click holding something to convert it. An item holding a fluid (a filled bucket, a tank) is emptied into the
     * intake and kept, never converted as an item, so a water bucket is refused rather than eaten. Any other item is
     * converted whole, the entire held stack.
     */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
            InteractionHand hand, BlockHitResult hit) {
        if (stack.isEmpty() || !(level.getBlockEntity(pos) instanceof EmcIntakeBlockEntity intake)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (level.isClientSide()) return ItemInteractionResult.SUCCESS;
        FluidStack fluid = FluidUtil.getFluidContained(stack).orElse(FluidStack.EMPTY);
        if (!fluid.isEmpty()) {
            Component name = fluid.getHoverName();
            boolean emptied = FluidUtil.interactWithFluidHandler(player, hand, intake.fluidHandler());
            player.displayClientMessage(Component.translatable(emptied ? FLUID_CONVERTED : NO_VALUE, name), true);
            return ItemInteractionResult.CONSUME;
        }
        BigInteger value = ItemEmc.stackValue(stack);
        Component name = stack.getHoverName();
        int count = stack.getCount();
        if (!intake.itemHandler().insertItem(0, stack, false).isEmpty()) {
            player.displayClientMessage(Component.translatable(NO_VALUE, name), true);
            return ItemInteractionResult.CONSUME;
        }
        if (!player.hasInfiniteMaterials()) player.setItemInHand(hand, ItemStack.EMPTY);
        player.displayClientMessage(Component.translatable(CONVERTED, count, name, value.toString()), true);
        return ItemInteractionResult.CONSUME;
    }

    /** Right-click with an empty hand explains what the intake does. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        player.displayClientMessage(Component.translatable(USAGE), true);
        return InteractionResult.CONSUME;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new EmcIntakeBlockEntity(pos, state);
    }
}
