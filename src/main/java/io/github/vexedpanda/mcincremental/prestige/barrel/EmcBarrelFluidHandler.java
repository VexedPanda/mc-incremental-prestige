package io.github.vexedpanda.mcincremental.prestige.barrel;

import io.github.vexedpanda.mcincremental.prestige.emc.FluidEmc;
import io.github.vexedpanda.mcincremental.prestige.emc.WorldEmc;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/**
 * What pipes and machines see of an EMC barrel's fluid side; the fluid twin of {@link EmcBarrelItemHandler}. One tank
 * holding the configured fluid, as many mB as the world's balance pays for. Draining debits the fluid's EMC value
 * from the world balance, exactly (fractions of an EMC go to the world's fractional EMC, see {@link WorldEmc}). Filling with any fluid that has an EMC value ({@link FluidEmc}) credits that value to the world balance and learns
 * the fluid; fluids with no value (or with data components) are refused, never voided.
 */
final class EmcBarrelFluidHandler implements IFluidHandler {
    private final EmcBarrelBlockEntity barrel;

    EmcBarrelFluidHandler(EmcBarrelBlockEntity barrel) {
        this.barrel = barrel;
    }

    @Override
    public int getTanks() {
        return 1;
    }

    @Override
    public FluidStack getFluidInTank(int tank) {
        return available(Integer.MAX_VALUE);
    }

    @Override
    public int getTankCapacity(int tank) {
        return Integer.MAX_VALUE;
    }

    @Override
    public boolean isFluidValid(int tank, FluidStack stack) {
        return !stack.isEmpty() && stack.isComponentsPatchEmpty() && FluidEmc.hasValue(stack.getFluid());
    }

    @Override
    public int fill(FluidStack resource, FluidAction action) {
        MinecraftServer server = barrel.server();
        if (server == null || !isFluidValid(0, resource)) return 0;
        if (action.execute()) {
            WorldEmc.addFluidEmc(server, resource.getFluid(), resource.getAmount());
            WorldEmc.learnFluid(server, resource.getFluid());
        }
        return resource.getAmount();
    }

    @Override
    public FluidStack drain(FluidStack resource, FluidAction action) {
        if (resource.isEmpty() || !FluidStack.isSameFluidSameComponents(resource, available(1))) return FluidStack.EMPTY;
        return drain(resource.getAmount(), action);
    }

    @Override
    public FluidStack drain(int maxDrain, FluidAction action) {
        FluidStack out = available(maxDrain);
        if (out.isEmpty() || action.simulate()) return out;
        // Can't fail: available() just checked the balance, on the server thread.
        if (!WorldEmc.removeFluidEmc(barrel.server(), out.getFluid(), out.getAmount())) return FluidStack.EMPTY;
        return out;
    }

    /**
     * Up to {@code max} mB of the configured fluid, as many as the world balance pays for; empty when unconfigured, no
     * longer known, worthless, or the balance can't pay for one mB.
     */
    private FluidStack available(int max) {
        MinecraftServer server = barrel.server();
        Fluid fluid = barrel.configuredFluid();
        if (server == null || fluid == null || max <= 0 || !WorldEmc.knowsFluid(server, fluid)) return FluidStack.EMPTY;
        long amount = Math.min(max, WorldEmc.affordableMb(server, fluid));
        return amount <= 0 ? FluidStack.EMPTY : new FluidStack(fluid, (int) amount);
    }
}
