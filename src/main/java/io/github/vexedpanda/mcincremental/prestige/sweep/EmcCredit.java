package io.github.vexedpanda.mcincremental.prestige.sweep;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.vexedpanda.mcincremental.prestige.emc.FluidEmc;
import io.github.vexedpanda.mcincremental.prestige.emc.ItemEmc;
import io.github.vexedpanda.mcincremental.prestige.emc.WorldEmc;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;

/**
 * EMC owed to the world for things a Prestige takes away: whole EMC for items and blocks (ProjectE's value), and fluid
 * amounts, which {@link #credit} pays through {@link WorldEmc#addFluidEmc} so fractions of an EMC are kept exactly.
 * Collect everything first, then credit once. Server thread only.
 */
public final class EmcCredit {
    private BigInteger emc = BigInteger.ZERO;
    /** mB by source fluid; only fluids with an EMC value. */
    private final Map<Fluid, Long> fluids = new LinkedHashMap<>();

    /** Adds an amount of whole EMC. */
    public void addEmc(long amount) {
        if (amount < 0) throw new IllegalArgumentException("Negative EMC: " + amount);
        emc = emc.add(BigInteger.valueOf(amount));
    }

    /** Adds an amount of whole EMC. */
    public void addEmc(BigInteger amount) {
        if (amount.signum() < 0) throw new IllegalArgumentException("Negative EMC: " + amount);
        emc = emc.add(amount);
    }

    /** Adds a stack's full EMC value (its ProjectE value times its count); a stack with no value adds nothing. */
    public void addItem(ItemStack stack) {
        emc = emc.add(ItemEmc.stackValue(stack));
    }

    /** Adds {@code mB} of a fluid at its {@link FluidEmc} value; a fluid with no value is ignored. */
    public void addFluid(Fluid fluid, long mB) {
        if (mB <= 0 || !FluidEmc.hasValue(fluid)) return;
        fluids.merge(FluidEmc.source(fluid), mB, Long::sum);
    }

    /** Adds everything in the container at full value, then empties it. */
    public void addAndClear(Container container) {
        for (int i = 0; i < container.getContainerSize(); i++) addItem(container.getItem(i));
        container.clearContent();
    }

    /** The whole EMC collected, not counting fluids. */
    public BigInteger emc() {
        return emc;
    }

    /** Credits everything collected to the world's balance. */
    public void creditTo(MinecraftServer server) {
        WorldEmc.addEmc(server, emc);
        fluids.forEach((fluid, mB) -> WorldEmc.addFluidEmc(server, fluid, mB));
    }

    @Override
    public String toString() {
        if (fluids.isEmpty()) return emc + " EMC";
        StringBuilder out = new StringBuilder().append(emc).append(" EMC and");
        fluids.forEach((fluid, mB) -> out.append(' ').append(mB).append(" mB of ").append(BuiltInRegistries.FLUID.getKey(fluid)));
        return out.toString();
    }
}
