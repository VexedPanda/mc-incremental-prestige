package io.github.vexedpanda.mcincremental.prestige.sweep;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.mojang.serialization.Codec;

import io.github.vexedpanda.mcincremental.prestige.McIncrementalPrestige;
import io.github.vexedpanda.mcincremental.prestige.run.RunArea;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * The world's one Run snapshot: the block states of the Run area plus its margin as they were at Run start, per chunk
 * section, and the entities there. Held in memory as copies of the live sections' containers; encoded only when saved, with the same codec
 * vanilla chunk saving uses (block states by name, so it survives registry id changes). Saved with the overworld.
 */
final class RunSnapshotData extends SavedData {
    private static final String NAME = McIncrementalPrestige.MODID + "_run_snapshot";
    private static final Factory<RunSnapshotData> FACTORY = new Factory<>(RunSnapshotData::new, RunSnapshotData::load, null);
    static final Codec<PalettedContainer<BlockState>> SECTION_CODEC = PalettedContainer.codecRW(
            Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES,
            Blocks.AIR.defaultBlockState());

    /** The Run area this snapshot is of, or null when there is none (before the first Run, or once swept). */
    @Nullable RunArea area;
    /** Chunks covered around the Run area, on every side. */
    int marginChunks;
    /** The overworld's lowest section y when taken: section i of a chunk is at section y {@code minSection + i}. */
    int minSection;
    /** Per chunk ({@link ChunkPos#toLong()}): a copy of every section's block states, bottom to top. */
    final Map<Long, PalettedContainer<BlockState>[]> chunks = new HashMap<>();
    /** Every entity in the snapshot's chunks at Run start, which the sweep leaves alone (item entities aside). */
    final Set<UUID> entities = new HashSet<>();

    static RunSnapshotData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    void clear() {
        area = null;
        marginChunks = 0;
        chunks.clear();
        entities.clear();
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (area == null) return tag;
        tag.putLong("area", area.centre().toLong());
        tag.putInt("radius", area.radiusChunks());
        tag.putInt("margin", marginChunks);
        tag.putInt("min_section", minSection);
        ListTag list = new ListTag();
        chunks.forEach((pos, sections) -> {
            CompoundTag chunk = new CompoundTag();
            chunk.putLong("pos", pos);
            ListTag encoded = new ListTag();
            for (PalettedContainer<BlockState> section : sections) {
                encoded.add(SECTION_CODEC.encodeStart(NbtOps.INSTANCE, section).getOrThrow());
            }
            chunk.put("sections", encoded);
            list.add(chunk);
        });
        tag.put("chunks", list);
        ListTag uuids = new ListTag();
        for (UUID uuid : entities) uuids.add(NbtUtils.createUUID(uuid));
        tag.put("entities", uuids);
        return tag;
    }

    @SuppressWarnings("unchecked")
    private static RunSnapshotData load(CompoundTag tag, HolderLookup.Provider registries) {
        RunSnapshotData data = new RunSnapshotData();
        if (!tag.contains("area")) return data;
        // Snapshots saved before the radius was configurable have none: their areas were radius 1.
        data.area = new RunArea(new ChunkPos(tag.getLong("area")), tag.contains("radius") ? tag.getInt("radius") : 1);
        data.marginChunks = tag.getInt("margin");
        data.minSection = tag.getInt("min_section");
        for (Tag t : tag.getList("chunks", Tag.TAG_COMPOUND)) {
            CompoundTag chunk = (CompoundTag) t;
            ListTag encoded = chunk.getList("sections", Tag.TAG_COMPOUND);
            PalettedContainer<BlockState>[] sections = new PalettedContainer[encoded.size()];
            for (int i = 0; i < sections.length; i++) {
                sections[i] = SECTION_CODEC.parse(NbtOps.INSTANCE, encoded.get(i)).getOrThrow();
            }
            data.chunks.put(chunk.getLong("pos"), sections);
        }
        for (Tag uuid : tag.getList("entities", Tag.TAG_INT_ARRAY)) data.entities.add(NbtUtils.loadUUID(uuid));
        return data;
    }
}
