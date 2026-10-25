package io.github.vexedpanda.mcincremental.prestige.sweep;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import io.github.vexedpanda.mcincremental.prestige.emc.FluidEmc;
import io.github.vexedpanda.mcincremental.prestige.run.RunArea;
import moze_intel.projecte.api.proxy.IEMCProxy;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.GlowItemFrame;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * The Prestige sweep (ADR-0007, mod {@code docs/prestige-sweep.md}): diffs the Run area against the world's Run
 * snapshot and turns everything the players placed or changed into EMC at full value, removing it. Untouched natural
 * blocks are kept, and a position whose Block is the same as at Run start counts as unchanged whatever its state (crop
 * age, fence connections, leaf distance, redstone power). Server thread only.
 */
public final class PrestigeSweep {
    /** No neighbour updates (no cascades, no popping attached blocks) and no drops. */
    private static final int REMOVE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

    private PrestigeSweep() {}

    /**
     * Sweeps the Run area the world's snapshot is of, then discards the snapshot, so a Run is swept at most once. Does
     * nothing when there is no snapshot. Doesn't credit anything: the caller credits the returned EMC to the world.
     *
     * <p>Runs in phases, each finishing before the next starts, so a multiblock is refunded whole: nothing is removed
     * (which could drop, void or break-trigger a neighbour's contents) until every inventory has been drained.
     * <ol>
     *   <li>collect: every position whose Block differs from the snapshot;
     *   <li>drain: every block entity's item and fluid handlers in the swept area (#30);
     *   <li>value: every changed position with EMC, a block entity or a fluid source;
     *   <li>remove: those positions, with no drops and no neighbour updates;
     *   <li>fluid sources: changed positions still holding a valued fluid source (e.g. leaked into the margin);
     *   <li>entities: dropped items, and value-holding entities that weren't there at Run start.
     * </ol>
     *
     * @return the EMC value of everything swept
     */
    public static BigInteger sweep(MinecraftServer server) {
        RunSnapshotData snapshot = RunSnapshotData.get(server);
        RunArea area = snapshot.area;
        if (area == null) return BigInteger.ZERO;
        ServerLevel level = server.overworld();

        List<BlockPos> changed = changedPositions(level, snapshot, area);
        BigInteger total = drainBlockEntities(level, area);
        List<BlockPos> swept = new ArrayList<>();
        for (BlockPos pos : changed) {
            BlockState state = level.getBlockState(pos);
            BlockEntity blockEntity = level.getBlockEntity(pos);
            long value = value(level, pos, state, blockEntity);
            // Fluid sources are valued here too (#30).
            if (value <= 0 && blockEntity == null) continue;
            total = total.add(BigInteger.valueOf(value));
            swept.add(pos);
        }
        for (BlockPos pos : swept) remove(level, pos);
        total = total.add(sweepFluidSources(level, changed));
        total = total.add(sweepEntities(level, snapshot, area));
        snapshot.clear();
        return total;
    }

    /**
     * Drains every block entity in the swept area, changed or not, before any block is removed. Returns the EMC
     * drained. Not yet implemented (#30): it must skip unrolled loot containers and the EMC barrel's handlers
     * (docs/emc-barrel.md). Until then contents are lost with their block.
     */
    private static BigInteger drainBlockEntities(ServerLevel level, RunArea area) {
        return BigInteger.ZERO;
    }

    /**
     * Refunds and removes the changed positions still holding a fluid source with an EMC value ({@link FluidEmc}), such
     * as lava leaked into the margin. Runs after the remove phase, reading the live world, so a position an earlier
     * phase already swept (now air) is never credited twice. A source block is 1000 mB: its value is whole EMC.
     */
    private static BigInteger sweepFluidSources(ServerLevel level, List<BlockPos> changed) {
        BigInteger total = BigInteger.ZERO;
        for (BlockPos pos : changed) {
            FluidState fluid = level.getFluidState(pos);
            if (!fluid.isSource() || !FluidEmc.hasValue(fluid.getType())) continue;
            total = total.add(BigInteger.valueOf(FluidEmc.valuePerBucket(fluid.getType())));
            remove(level, pos);
        }
        return total;
    }

    /**
     * Refunds and removes entities in the swept chunks (entities phase, #31): every dropped item, whenever it appeared,
     * and every other entity holding value (item form plus contents) that wasn't there at Run start, such as a chest
     * minecart, item frame or armor stand placed during the Run. Returns the EMC refunded.
     */
    private static BigInteger sweepEntities(ServerLevel level, RunSnapshotData snapshot, RunArea area) {
        // Entities are only seen in accessible chunks: hold the swept chunks ticking while collecting them.
        List<Entity> inBox = RunSnapshots.whileTicking(level, area.centre(), area.radiusChunks() + snapshot.marginChunks, () -> {
            List<Entity> found = new ArrayList<>();
            for (Entity entity : level.getAllEntities()) {
                if (area.containsChunk(entity.chunkPosition(), snapshot.marginChunks)) found.add(entity);
            }
            return found;
        });
        BigInteger total = BigInteger.ZERO;
        for (Entity entity : inBox) {
            if (entity.isRemoved()) continue;
            long value;
            if (entity instanceof ItemEntity item) {
                value = emc(item.getItem());
            } else {
                if (snapshot.entities.contains(entity.getUUID()) || !isSweepable(entity)) continue;
                value = entityValue(entity);
                if (value <= 0) continue;
                // Discarding a container entity spills its contents: they are refunded, so empty it first.
                if (entity instanceof Container container) container.clearContent();
            }
            total = total.add(BigInteger.valueOf(value));
            entity.discard();
        }
        return total;
    }

    /**
     * Whether a non-item entity may be swept: not a player or mob (an armor stand is a decoration), and not a container
     * entity with an unrolled loot table (reading or removing it would roll the loot).
     */
    private static boolean isSweepable(Entity entity) {
        if (entity instanceof LivingEntity && !(entity instanceof ArmorStand)) return false;
        return !(entity instanceof ContainerEntity container && container.getLootTable() != null);
    }

    /** An entity's EMC: its item form plus everything it holds. */
    private static long entityValue(Entity entity) {
        long value;
        if (entity instanceof ItemFrame frame) {
            // An item frame's pick result is its framed item when it has one.
            value = emc(new ItemStack(frame instanceof GlowItemFrame ? Items.GLOW_ITEM_FRAME : Items.ITEM_FRAME));
            value += emc(frame.getItem());
            return value;
        }
        ItemStack form = entity.getPickResult();
        value = form == null ? 0 : emc(form);
        if (entity instanceof Container container) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) value += emc(container.getItem(slot));
        } else if (entity instanceof ArmorStand stand) {
            for (EquipmentSlot slot : EquipmentSlot.values()) value += emc(stand.getItemBySlot(slot));
        } else {
            IItemHandler items = entity.getCapability(Capabilities.ItemHandler.ENTITY);
            if (items != null) {
                for (int slot = 0; slot < items.getSlots(); slot++) value += emc(items.getStackInSlot(slot));
            }
        }
        return value;
    }

    /**
     * Removes a block without drops or neighbour updates. The block entity goes first, so containers can't spill their
     * (already drained) contents in {@code onRemove}.
     */
    private static void remove(ServerLevel level, BlockPos pos) {
        level.removeBlockEntity(pos);
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), REMOVE_FLAGS);
    }

    /** Every non-air position whose Block differs from the snapshot's, in the swept chunks. */
    private static List<BlockPos> changedPositions(ServerLevel level, RunSnapshotData snapshot, RunArea area) {
        List<BlockPos> changed = new ArrayList<>();
        int radius = area.radiusChunks() + snapshot.marginChunks;
        ChunkPos centre = area.centre();
        for (int cx = centre.x - radius; cx <= centre.x + radius; cx++) {
            for (int cz = centre.z - radius; cz <= centre.z + radius; cz++) {
                PalettedContainer<BlockState>[] was = snapshot.chunks.get(ChunkPos.asLong(cx, cz));
                if (was == null) continue;
                LevelChunk chunk = level.getChunk(cx, cz);
                LevelChunkSection[] sections = chunk.getSections();
                for (int i = 0; i < sections.length; i++) {
                    int sectionY = level.getMinSection() + i;
                    int snapshotIndex = sectionY - snapshot.minSection;
                    if (snapshotIndex < 0 || snapshotIndex >= was.length) continue;
                    LevelChunkSection live = sections[i];
                    if (live.hasOnlyAir()) continue; // nothing to sweep: whatever was there is gone
                    PalettedContainer<BlockState> old = was[snapshotIndex];
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                BlockState now = live.getBlockState(x, y, z);
                                if (now.isAir() || now.getBlock() == old.get(x, y, z).getBlock()) continue;
                                changed.add(new BlockPos(SectionPos.sectionToBlockCoord(cx, x),
                                        SectionPos.sectionToBlockCoord(sectionY, y), SectionPos.sectionToBlockCoord(cz, z)));
                            }
                        }
                    }
                }
            }
        }
        return changed;
    }

    /**
     * A block's full EMC value through ProjectE: its item form first (which includes values set on the item, e.g.
     * hand-made GT EMC), then its pick-block stack, then its drops.
     */
    static long value(ServerLevel level, BlockPos pos, BlockState state, @Nullable BlockEntity blockEntity) {
        long value = emc(new ItemStack(state.getBlock().asItem()));
        if (value > 0) return value;
        value = emc(state.getBlock().getCloneItemStack(level, pos, state));
        if (value > 0) return value;
        long drops = 0;
        for (ItemStack drop : Block.getDrops(state, level, pos, blockEntity)) drops += emc(drop);
        return drops;
    }

    private static long emc(ItemStack stack) {
        return stack.isEmpty() ? 0 : IEMCProxy.INSTANCE.getValue(stack) * stack.getCount();
    }
}
