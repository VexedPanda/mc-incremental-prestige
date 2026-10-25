package io.github.vexedpanda.mcincremental.prestige.emc;

import java.math.BigInteger;

import moze_intel.projecte.api.proxy.IEMCProxy;
import net.minecraft.world.item.ItemStack;

/** EMC values of item stacks, through ProjectE. */
public final class ItemEmc {
    private ItemEmc() {}

    /** A stack's full EMC value: ProjectE's value of one item times the count; 0 for an empty or worthless stack. */
    public static BigInteger stackValue(ItemStack stack) {
        if (stack.isEmpty()) return BigInteger.ZERO;
        return BigInteger.valueOf(IEMCProxy.INSTANCE.getValue(stack)).multiply(BigInteger.valueOf(stack.getCount()));
    }
}
