package io.github.vexedpanda.mcincremental.prestige.controller;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import io.github.vexedpanda.mcincremental.prestige.McIncrementalPrestige;
import io.github.vexedpanda.mcincremental.prestige.run.RunArea;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * The world's choosing state: the Candidate areas offered since the last Prestige (none outside a choice), and the
 * players whose inventory that Prestige has already converted. Saved with the overworld, so a restart mid-choice keeps
 * both.
 */
final class CandidateAreaData extends SavedData {
    private static final String NAME = McIncrementalPrestige.MODID + "_candidate_areas";
    private static final Factory<CandidateAreaData> FACTORY = new Factory<>(CandidateAreaData::new, CandidateAreaData::load, null);

    /** Overworld candidates, in the order they are numbered (1 first). Empty when no choice is pending. */
    final List<RunArea> candidates = new ArrayList<>();
    /** Players converted by the pending Prestige: online at its sweep, or since joined while the choice is pending. */
    final Set<UUID> converted = new HashSet<>();

    static CandidateAreaData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLongArray("candidates", candidates.stream().mapToLong(a -> a.centre().toLong()).toArray());
        tag.putIntArray("radii", candidates.stream().mapToInt(RunArea::radiusChunks).toArray());
        ListTag players = new ListTag();
        for (UUID uuid : converted) players.add(NbtUtils.createUUID(uuid));
        tag.put("converted", players);
        return tag;
    }

    private static CandidateAreaData load(CompoundTag tag, HolderLookup.Provider registries) {
        CandidateAreaData data = new CandidateAreaData();
        long[] centres = tag.getLongArray("candidates");
        int[] radii = tag.getIntArray("radii");
        for (int i = 0; i < centres.length && i < radii.length; i++) {
            data.candidates.add(new RunArea(new ChunkPos(centres[i]), radii[i]));
        }
        for (Tag uuid : tag.getList("converted", Tag.TAG_INT_ARRAY)) {
            data.converted.add(NbtUtils.loadUUID(uuid));
        }
        return data;
    }
}
