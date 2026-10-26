package io.github.vexedpanda.mcincremental.prestige.barrel;

import io.github.vexedpanda.mcincremental.prestige.McIncrementalPrestige;
import io.github.vexedpanda.mcincremental.prestige.emc.FluidEmc;
import io.github.vexedpanda.mcincremental.prestige.emc.WorldEmc;
import moze_intel.projecte.api.ItemInfo;
import moze_intel.projecte.api.proxy.IEMCProxy;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

/**
 * An EMC barrel: linked to the world's EMC balance and Knowledge (ADR-0004, no owner), configured to hand out one
 * learned item or one learned fluid (configuring one clears the other). It only outputs; the EMC intake is the way in. It stores no items, fluid or EMC itself; the
 * capabilities registered in {@link EmcBarrels} do all the work against {@link WorldEmc}, so it works with no player
 * online.
 */
public class EmcBarrelBlockEntity extends BlockEntity {
    private static final String ITEM_KEY = "item";
    private static final String FLUID_KEY = "fluid";

    private final EmcBarrelItemHandler itemHandler = new EmcBarrelItemHandler(this);
    private final EmcBarrelFluidHandler fluidHandler = new EmcBarrelFluidHandler(this);
    /** The configured item (persistent components only, as Knowledge holds it), or null when unconfigured. */
    private @Nullable ItemInfo item;
    /** The configured fluid (a source fluid), or null. */
    private @Nullable Fluid fluid;

    public EmcBarrelBlockEntity(BlockPos pos, BlockState state) {
        super(EmcBarrels.BLOCK_ENTITY.get(), pos, state);
    }

    /** The item handler pipes and machines use; see {@link EmcBarrelItemHandler}. */
    public IItemHandler itemHandler() {
        return itemHandler;
    }

    /** The fluid handler pipes and machines use; see {@link EmcBarrelFluidHandler}. */
    public IFluidHandler fluidHandler() {
        return fluidHandler;
    }

    /** The configured item, or null. */
    public @Nullable ItemInfo configuredItem() {
        return item;
    }

    /**
     * Configures the barrel to hand out {@code stack}'s item, if the world knows it and it has an EMC value.
     *
     * @return whether the barrel is now configured to it
     */
    public boolean configure(ItemStack stack) {
        MinecraftServer server = server();
        if (server == null || stack.isEmpty() || !IEMCProxy.INSTANCE.hasValue(stack) || !WorldEmc.knows(server, stack)) {
            return false;
        }
        item = IEMCProxy.INSTANCE.getPersistentInfo(ItemInfo.fromStack(stack));
        fluid = null;
        setChanged();
        return true;
    }

    /** The configured fluid, or null. */
    public @Nullable Fluid configuredFluid() {
        return fluid;
    }

    /**
     * Configures the barrel to hand out {@code fluid} (its source), if the world knows it and it has an EMC value
     * ({@link FluidEmc}).
     *
     * @return whether the barrel is now configured to it
     */
    public boolean configureFluid(Fluid fluid) {
        MinecraftServer server = server();
        if (server == null || !FluidEmc.hasValue(fluid) || !WorldEmc.knowsFluid(server, fluid)) return false;
        this.fluid = FluidEmc.source(fluid);
        item = null;
        setChanged();
        return true;
    }

    /** The server whose world balance this barrel uses; null on the client or before the barrel is in a level. */
    @Nullable MinecraftServer server() {
        return level == null || level.isClientSide() ? null : level.getServer();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (fluid != null) tag.putString(FLUID_KEY, BuiltInRegistries.FLUID.getKey(fluid).toString());
        if (item == null) return;
        ItemInfo.CODEC.encodeStart(RegistryOps.create(NbtOps.INSTANCE, registries), item)
                .resultOrPartial(e -> McIncrementalPrestige.LOGGER.error("Can't save EMC barrel item: {}", e))
                .ifPresent(t -> tag.put(ITEM_KEY, t));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        item = null;
        fluid = null;
        if (tag.contains(FLUID_KEY)) {
            ResourceLocation id = ResourceLocation.tryParse(tag.getString(FLUID_KEY));
            fluid = id == null ? null : BuiltInRegistries.FLUID.getOptional(id).filter(f -> f != Fluids.EMPTY).orElse(null);
        }
        if (!tag.contains(ITEM_KEY)) return;
        ItemInfo.CODEC.parse(RegistryOps.create(NbtOps.INSTANCE, registries), tag.get(ITEM_KEY))
                .resultOrPartial(e -> McIncrementalPrestige.LOGGER.error("Can't load EMC barrel item: {}", e))
                .ifPresent(info -> item = info);
    }
}
