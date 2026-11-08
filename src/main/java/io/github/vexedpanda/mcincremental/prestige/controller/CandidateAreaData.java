package io.github.vexedpanda.mcincremental.prestige.controller;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.github.vexedpanda.mcincremental.prestige.McIncrementalPrestige;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * The world's choosing state: the Candidate areas offered since the last Prestige, grouped by dimension (none outside
 * a choice), and the players whose inventory that Prestige has already converted. Saved with the overworld, so a
 * restart mid-choice keeps both.
 *
 * <p>NBT: {@code groups}, a list (in group order) of {@code {dimension: "minecraft:overworld", candidates: [{centre:
 * <chunk pos long>, radius: <int>, preview: {...}}]}} ({@link CandidateArea#CODEC}), and {@code converted}, the
 * players' UUIDs.
 */
final class CandidateAreaData extends SavedData {
    private static final String NAME = McIncrementalPrestige.MODID + "_candidate_areas";
    private static final Factory<CandidateAreaData> FACTORY = new Factory<>(CandidateAreaData::new, CandidateAreaData::load, null);

    /** One dimension's candidates, in the order they are numbered. */
    private record Group(ResourceKey<Level> dimension, List<CandidateArea> candidates) {
        static final Codec<Group> CODEC = RecordCodecBuilder.create(i -> i.group(
                Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(Group::dimension),
                CandidateArea.CODEC.listOf().fieldOf("candidates").forGetter(Group::candidates))
                .apply(i, Group::new));
    }

    /**
     * The candidates per dimension, in group order; numbering runs through the groups in this order (1 first). Empty
     * when no choice is pending.
     */
    final Map<ResourceKey<Level>, List<CandidateArea>> groups = new LinkedHashMap<>();
    /** Players converted by the pending Prestige: online at its sweep, or since joined while the choice is pending. */
    final Set<UUID> converted = new HashSet<>();

    static CandidateAreaData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    /** Every candidate on offer, numbered through the groups in order. */
    List<CandidateArea> numbered() {
        List<CandidateArea> all = new ArrayList<>();
        groups.values().forEach(all::addAll);
        return all;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        List<Group> list = groups.entrySet().stream().map(e -> new Group(e.getKey(), e.getValue())).toList();
        tag.put("groups", Group.CODEC.listOf().encodeStart(NbtOps.INSTANCE, list).getOrThrow());
        ListTag players = new ListTag();
        for (UUID uuid : converted) players.add(NbtUtils.createUUID(uuid));
        tag.put("converted", players);
        return tag;
    }

    private static CandidateAreaData load(CompoundTag tag, HolderLookup.Provider registries) {
        CandidateAreaData data = new CandidateAreaData();
        if (tag.contains("groups")) Group.CODEC.listOf().parse(NbtOps.INSTANCE, tag.get("groups"))
                .resultOrPartial(error -> McIncrementalPrestige.LOGGER.error("Unreadable Candidate areas: {}", error))
                .ifPresent(list -> list.forEach(g -> data.groups.put(g.dimension(), List.copyOf(g.candidates()))));
        for (Tag uuid : tag.getList("converted", Tag.TAG_INT_ARRAY)) {
            data.converted.add(NbtUtils.loadUUID(uuid));
        }
        return data;
    }
}
