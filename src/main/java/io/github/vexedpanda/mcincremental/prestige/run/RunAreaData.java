package io.github.vexedpanda.mcincremental.prestige.run;

import java.util.ArrayList;
import java.util.List;

import io.github.vexedpanda.mcincremental.prestige.McIncrementalPrestige;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

/** Every Run area the world has used, oldest first; the last is the current one. Saved with the overworld. */
final class RunAreaData extends SavedData {
    private static final String NAME = McIncrementalPrestige.MODID + "_run_areas";
    private static final Factory<RunAreaData> FACTORY = new Factory<>(RunAreaData::new, RunAreaData::load, null);

    final List<RunArea> visited = new ArrayList<>();

    static RunAreaData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLongArray("visited", visited.stream().mapToLong(a -> a.centre().toLong()).toArray());
        tag.putIntArray("radii", visited.stream().mapToInt(RunArea::radiusChunks).toArray());
        return tag;
    }

    private static RunAreaData load(CompoundTag tag, HolderLookup.Provider registries) {
        RunAreaData data = new RunAreaData();
        long[] centres = tag.getLongArray("visited");
        int[] radii = tag.getIntArray("radii");
        // Worlds saved before the radius was configurable have no radii: their areas were all radius 1.
        for (int i = 0; i < centres.length; i++) {
            data.visited.add(new RunArea(new ChunkPos(centres[i]), i < radii.length ? radii[i] : 1));
        }
        return data;
    }
}
