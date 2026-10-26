package io.github.vexedpanda.mcincremental.prestige.barrel;

import java.math.BigInteger;

import io.github.vexedpanda.mcincremental.prestige.emc.ItemEmc;
import io.github.vexedpanda.mcincremental.prestige.emc.WorldEmc;
import moze_intel.projecte.api.ItemInfo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * What pipes and machines see of an EMC barrel: one slot holding the configured item, as many as the world's balance
 * can pay for (up to a stack). Extracting debits each item's EMC value (ProjectE's EMC value, not its sell value, as
 * the EMC intake credits) from the world balance. It only outputs: every insert is refused and left with the caller.
 */
final class EmcBarrelItemHandler implements IItemHandler {
    private final EmcBarrelBlockEntity barrel;

    EmcBarrelItemHandler(EmcBarrelBlockEntity barrel) {
        this.barrel = barrel;
    }

    @Override
    public int getSlots() {
        return 1;
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        return available(Item.ABSOLUTE_MAX_STACK_SIZE);
    }

    /** The barrel only outputs: nothing goes in (the EMC intake converts items into EMC). */
    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        return stack;
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        ItemStack out = available(amount);
        if (out.isEmpty() || simulate) return out;
        // Can't fail: available() just checked the balance, on the server thread.
        if (!WorldEmc.removeEmc(barrel.server(), ItemEmc.stackValue(out))) return ItemStack.EMPTY;
        return out;
    }

    /**
     * Up to {@code max} of the configured item (capped at its stack size), as many as the world balance pays for; empty
     * when unconfigured, no longer known, worthless, or the balance can't pay for one.
     */
    private ItemStack available(int max) {
        MinecraftServer server = barrel.server();
        ItemInfo item = barrel.configuredItem();
        if (server == null || item == null || max <= 0 || !WorldEmc.knows(server, item)) return ItemStack.EMPTY;
        ItemStack stack = item.createStack();
        BigInteger value = ItemEmc.stackValue(stack);
        if (value.signum() <= 0) return ItemStack.EMPTY;
        BigInteger affordable = WorldEmc.balance(server).divide(value);
        int count = (int) Math.min(Math.min(max, stack.getMaxStackSize()), affordable.min(BigInteger.valueOf(Integer.MAX_VALUE)).longValue());
        return count <= 0 ? ItemStack.EMPTY : stack.copyWithCount(count);
    }

    @Override
    public int getSlotLimit(int slot) {
        return Item.ABSOLUTE_MAX_STACK_SIZE;
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return false;
    }
}
