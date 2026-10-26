package io.github.vexedpanda.mcincremental.prestige.intake;

import io.github.vexedpanda.mcincremental.prestige.emc.WorldEmc;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

/**
 * An EMC intake: converts every item and fluid put into it into EMC for the world's balance and Knowledge (ADR-0004,
 * no owner; ADR-0009, what passes through is discovered). It stores nothing and has no settings; the capabilities
 * registered in {@link EmcIntakes} do all the work against {@link WorldEmc}, so it works with no player online. The
 * block entity exists so that pipes which look for one before asking for a capability still connect.
 */
public class EmcIntakeBlockEntity extends BlockEntity {
    private final EmcIntakeItemHandler itemHandler = new EmcIntakeItemHandler(this);
    private final EmcIntakeFluidHandler fluidHandler = new EmcIntakeFluidHandler(this);

    public EmcIntakeBlockEntity(BlockPos pos, BlockState state) {
        super(EmcIntakes.BLOCK_ENTITY.get(), pos, state);
    }

    /** The item handler pipes and machines use; see {@link EmcIntakeItemHandler}. */
    public IItemHandler itemHandler() {
        return itemHandler;
    }

    /** The fluid handler pipes and machines use; see {@link EmcIntakeFluidHandler}. */
    public IFluidHandler fluidHandler() {
        return fluidHandler;
    }

    /** The server whose world balance this intake credits; null on the client or before it is in a level. */
    @Nullable MinecraftServer server() {
        return level == null || level.isClientSide() ? null : level.getServer();
    }
}
