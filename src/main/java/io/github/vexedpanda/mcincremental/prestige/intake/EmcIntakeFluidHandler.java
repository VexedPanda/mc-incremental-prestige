package io.github.vexedpanda.mcincremental.prestige.intake;

import io.github.vexedpanda.mcincremental.prestige.emc.FluidEmc;
import io.github.vexedpanda.mcincremental.prestige.emc.WorldEmc;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/**
 * What pipes and machines see of an EMC intake's fluid side; the fluid twin of {@link EmcIntakeItemHandler}. One
 * always-empty tank of unlimited capacity. Filling with any fluid that has an EMC value ({@link FluidEmc}) credits its
 * exact value to the world balance (fractions of an EMC go to the world's fractional EMC, see {@link WorldEmc}) and
 * learns the fluid, so any amount can be piped in. Fluids with no value (water) or with data components are refused
 * ({@code fill} returns 0), never voided. Nothing can be drained.
 */
final class EmcIntakeFluidHandler implements IFluidHandler {
    private final EmcIntakeBlockEntity intake;

    EmcIntakeFluidHandler(EmcIntakeBlockEntity intake) {
        this.intake = intake;
    }

    @Override
    public int getTanks() {
        return 1;
    }

    @Override
    public FluidStack getFluidInTank(int tank) {
        return FluidStack.EMPTY;
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
        MinecraftServer server = intake.server();
        if (server == null || !isFluidValid(0, resource)) return 0;
        if (action.execute()) {
            WorldEmc.addFluidEmc(server, resource.getFluid(), resource.getAmount());
            WorldEmc.learnFluid(server, resource.getFluid());
        }
        return resource.getAmount();
    }

    @Override
    public FluidStack drain(FluidStack resource, FluidAction action) {
        return FluidStack.EMPTY;
    }

    @Override
    public FluidStack drain(int maxDrain, FluidAction action) {
        return FluidStack.EMPTY;
    }
}
