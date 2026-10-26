package io.github.vexedpanda.mcincremental.prestige.intake;

import io.github.vexedpanda.mcincremental.prestige.emc.ItemEmc;
import io.github.vexedpanda.mcincremental.prestige.emc.WorldEmc;
import moze_intel.projecte.api.proxy.IEMCProxy;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * What pipes and machines see of an EMC intake's item side: one always-empty slot. Inserting any item with an EMC
 * value credits its full ProjectE EMC value (not its sell value, so intake-then-barrel nets zero) times the count to
 * the world balance and learns it. Items with no EMC are refused and left with the caller, never voided. Nothing can
 * be extracted.
 */
final class EmcIntakeItemHandler implements IItemHandler {
    private final EmcIntakeBlockEntity intake;

    EmcIntakeItemHandler(EmcIntakeBlockEntity intake) {
        this.intake = intake;
    }

    @Override
    public int getSlots() {
        return 1;
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        MinecraftServer server = intake.server();
        if (server == null || stack.isEmpty() || !isItemValid(slot, stack)) return stack;
        if (!simulate) {
            WorldEmc.addEmc(server, ItemEmc.stackValue(stack));
            WorldEmc.learn(server, stack);
        }
        return ItemStack.EMPTY;
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        return ItemStack.EMPTY;
    }

    @Override
    public int getSlotLimit(int slot) {
        return Item.ABSOLUTE_MAX_STACK_SIZE;
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return IEMCProxy.INSTANCE.hasValue(stack);
    }
}
