package io.github.vexedpanda.mcincremental.prestige.barrel;

import java.math.BigInteger;

import io.github.vexedpanda.mcincremental.prestige.emc.WorldEmc;
import moze_intel.projecte.api.ItemInfo;
import moze_intel.projecte.api.proxy.IEMCProxy;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * What pipes and machines see of an EMC barrel: one slot holding the configured item, as many as the world's balance
 * can pay for (up to a stack). Extracting debits each item's EMC value from the world balance. Inserting any item with
 * an EMC value credits that value to the world balance and learns the item; items with no EMC are refused and left
 * with the caller. Both directions use ProjectE's EMC value, not its sell value, so insert-then-extract nets zero.
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

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        MinecraftServer server = barrel.server();
        if (server == null || stack.isEmpty() || !isItemValid(slot, stack)) return stack;
        if (!simulate) {
            WorldEmc.addEmc(server, value(stack).multiply(BigInteger.valueOf(stack.getCount())));
            WorldEmc.learn(server, stack);
        }
        return ItemStack.EMPTY;
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        ItemStack out = available(amount);
        if (out.isEmpty() || simulate) return out;
        // Can't fail: available() just checked the balance, on the server thread.
        if (!WorldEmc.removeEmc(barrel.server(), value(out).multiply(BigInteger.valueOf(out.getCount())))) return ItemStack.EMPTY;
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
        BigInteger value = value(stack);
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
        return IEMCProxy.INSTANCE.hasValue(stack);
    }

    /** EMC value of one of this item. */
    private static BigInteger value(ItemStack stack) {
        return BigInteger.valueOf(IEMCProxy.INSTANCE.getValue(stack));
    }
}
