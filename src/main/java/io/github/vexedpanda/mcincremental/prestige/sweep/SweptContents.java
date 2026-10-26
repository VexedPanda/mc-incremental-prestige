package io.github.vexedpanda.mcincremental.prestige.sweep;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import io.github.vexedpanda.mcincremental.prestige.barrel.EmcBarrelBlockEntity;
import io.github.vexedpanda.mcincremental.prestige.intake.EmcIntakeBlockEntity;
import io.github.vexedpanda.mcincremental.prestige.emc.FluidEmc;
import io.github.vexedpanda.mcincremental.prestige.sweep.gt.GtRecipesInProgress;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import net.neoforged.neoforge.items.wrapper.InvWrapper;

/**
 * Phase 2 of the Prestige sweep: empties a block's inventories and tanks into an {@link EmcCredit} through NeoForge's item
 * and fluid handler capabilities, so it works for any mod's machines (GT buses, hatches and machines included) without
 * depending on them. Server thread only.
 *
 * <p>Items: every slot of every handler (unsided, then each side) is extracted; what a handler won't hand out (a GT
 * input bus, a furnace's fuel slot) is cleared with {@link IItemHandlerModifiable#setStackInSlot} and credited too.
 * Fluids with an EMC value are drained the same way. Anything still left can't be taken out: it is credited only if the
 * block is about to be removed (it goes with the block entity), counted once, from the unsided handler; a kept block
 * keeps it and nothing is credited. The same goes for the inputs a GregTech machine consumed for a recipe it hasn't
 * finished ({@link GtRecipesInProgress}, only with GregTech installed).
 *
 * <p>Skipped entirely: unrolled loot containers (reading them would roll the loot), EMC barrels and EMC intakes (their
 * handlers are the world balance itself and hold nothing, docs/emc-barrel.md, docs/emc-intake.md).
 */
final class SweptContents {
    /** Guards against handlers that never run dry (creative sources): extractions per slot or tank. */
    private static final int MAX_PULLS = 64;
    /** GregTech is optional: {@link GtRecipesInProgress} is only loaded when it is installed. */
    private static final boolean GREGTECH_LOADED = ModList.get().isLoaded("gtceu");

    private SweptContents() {}

    /**
     * Empties the block at {@code pos} into {@code credit}.
     *
     * @param removed whether the sweep removes the block afterwards, so anything that can't be taken out is lost with it
     */
    static void drain(ServerLevel level, BlockPos pos, @Nullable BlockEntity blockEntity, boolean removed, EmcCredit credit) {
        if (blockEntity instanceof EmcBarrelBlockEntity || blockEntity instanceof EmcIntakeBlockEntity) return;
        if (blockEntity instanceof RandomizableContainerBlockEntity loot && loot.getLootTable() != null) return;
        BlockState state = level.getBlockState(pos);
        List<IItemHandler> items = blockEntity instanceof ChestBlockEntity chest
                // Not the chest capability: it spans a double chest, whose other half may be an unrolled loot chest.
                ? List.of(new InvWrapper(chest))
                : handlers(level, pos, state, blockEntity, Capabilities.ItemHandler.BLOCK);
        for (IItemHandler handler : items) {
            for (int slot = 0; slot < handler.getSlots(); slot++) drainSlot(handler, slot, credit);
        }
        if (removed && !items.isEmpty()) {
            IItemHandler all = items.get(0);
            for (int slot = 0; slot < all.getSlots(); slot++) credit.addItem(all.getStackInSlot(slot));
        }
        List<IFluidHandler> tanks = handlers(level, pos, state, blockEntity, Capabilities.FluidHandler.BLOCK);
        for (IFluidHandler handler : tanks) {
            for (int tank = 0; tank < handler.getTanks(); tank++) drainTank(handler, tank, credit);
        }
        if (removed && !tanks.isEmpty()) {
            IFluidHandler all = tanks.get(0);
            for (int tank = 0; tank < all.getTanks(); tank++) {
                FluidStack left = all.getFluidInTank(tank);
                if (!left.isEmpty()) credit.addFluid(left.getFluid(), left.getAmount());
            }
        }
        // A GT machine's unfinished recipe holds the inputs it consumed; they go with the removed machine.
        if (removed && GREGTECH_LOADED) GtRecipesInProgress.addConsumedInputs(blockEntity, credit);
    }

    private static void drainSlot(IItemHandler handler, int slot, EmcCredit credit) {
        for (int i = 0; i < MAX_PULLS; i++) {
            ItemStack in = handler.getStackInSlot(slot);
            if (in.isEmpty()) return;
            ItemStack out = handler.extractItem(slot, in.getCount(), false);
            if (out.isEmpty()) break;
            credit.addItem(out);
        }
        ItemStack stuck = handler.getStackInSlot(slot);
        if (!stuck.isEmpty() && handler instanceof IItemHandlerModifiable modifiable) {
            credit.addItem(stuck.copy());
            modifiable.setStackInSlot(slot, ItemStack.EMPTY);
        }
    }

    private static void drainTank(IFluidHandler handler, int tank, EmcCredit credit) {
        for (int i = 0; i < MAX_PULLS; i++) {
            FluidStack in = handler.getFluidInTank(tank);
            if (in.isEmpty() || !FluidEmc.hasValue(in.getFluid())) return;
            FluidStack out = handler.drain(in.copy(), IFluidHandler.FluidAction.EXECUTE);
            if (out.isEmpty()) return;
            credit.addFluid(out.getFluid(), out.getAmount());
        }
    }

    /** The block's handlers: unsided first, then each side; each distinct handler object once. */
    private static <T> List<T> handlers(ServerLevel level, BlockPos pos, BlockState state, @Nullable BlockEntity blockEntity,
                                        BlockCapability<T, Direction> capability) {
        List<T> found = new ArrayList<>();
        add(found, level.getCapability(capability, pos, state, blockEntity, null));
        for (Direction side : Direction.values()) add(found, level.getCapability(capability, pos, state, blockEntity, side));
        return found;
    }

    private static <T> void add(List<T> found, @Nullable T handler) {
        if (handler == null) return;
        for (T t : found) if (t == handler) return;
        found.add(handler);
    }
}
