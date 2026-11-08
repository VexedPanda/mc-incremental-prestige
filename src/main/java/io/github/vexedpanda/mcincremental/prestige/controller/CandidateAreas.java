package io.github.vexedpanda.mcincremental.prestige.controller;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.github.vexedpanda.mcincremental.prestige.McIncrementalPrestige;
import io.github.vexedpanda.mcincremental.prestige.preview.CandidatePreview;
import io.github.vexedpanda.mcincremental.prestige.preview.CandidatePreviews;
import io.github.vexedpanda.mcincremental.prestige.preview.NewOres;
import io.github.vexedpanda.mcincremental.prestige.preview.OreVein;
import io.github.vexedpanda.mcincremental.prestige.run.RunArea;
import io.github.vexedpanda.mcincremental.prestige.run.RunAreas;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;

/**
 * The world's choosing state between the two steps of a Prestige: after the sweep, the world offers
 * {@link #COUNT} Candidate areas per dimension, each with its preview, until one is chosen ({@link Prestige#choose}). World-global and saved with the world.
 * While candidates are offered, every player is held still and unhurt ({@link ChoosingFreeze}). Server thread only.
 */
public final class CandidateAreas {
    /** How many Candidate areas a Prestige offers. */
    public static final int COUNT = 3;
    /**
     * How many areas beyond the first {@link #COUNT} a Prestige may try per dimension to offer something new (#79).
     * Each costs its chunks' generation, about 2 s on a real world.
     */
    public static final int MAX_EXTRA_AREAS = 6;

    /** Chat heading above the candidates. */
    public static final String HEADING_KEY = McIncrementalPrestige.MODID + ".candidates.heading";
    /** Chat line naming a dimension, above its candidates. */
    public static final String DIMENSION_KEY = McIncrementalPrestige.MODID + ".candidates.dimension";
    /** Chat text for one GT ore vein: its vein type's name and the materials found. */
    public static final String VEIN_KEY = McIncrementalPrestige.MODID + ".candidates.vein";
    /** Chat text marking an ore material new: the world can harvest it and hasn't discovered it. */
    public static final String NEW_KEY = McIncrementalPrestige.MODID + ".candidates.new";
    /** Chat text for an empty part of a preview. */
    public static final String NONE_KEY = McIncrementalPrestige.MODID + ".candidates.none";
    /**
     * Chat line for one candidate: its number, its middle's x and z, the biome at its middle, its GT ore veins and its
     * structures.
     */
    public static final String LINE_KEY = McIncrementalPrestige.MODID + ".candidates.line";

    private CandidateAreas() {}

    /** The Candidate areas on offer, numbered from 1 through the dimension groups in order; empty when no choice is pending. */
    public static List<CandidateArea> offered(MinecraftServer server) {
        return List.copyOf(CandidateAreaData.get(server).numbered());
    }

    /**
     * The Candidate areas on offer grouped by dimension, in group order (only the Overworld for now: Run dimensions
     * come with spec 2). Empty when no choice is pending.
     */
    public static Map<ResourceKey<Level>, List<CandidateArea>> offeredByDimension(MinecraftServer server) {
        Map<ResourceKey<Level>, List<CandidateArea>> groups = new LinkedHashMap<>();
        CandidateAreaData.get(server).groups.forEach((dimension, candidates) -> groups.put(dimension, List.copyOf(candidates)));
        return Collections.unmodifiableMap(groups);
    }

    /** Whether the world is between a Prestige and the choice of its next Run area. */
    public static boolean isOffered(MinecraftServer server) {
        return !CandidateAreaData.get(server).groups.isEmpty();
    }

    /**
     * Withdraws any candidates on offer, as if none had been: no Run starts and players are released. For GameTests,
     * which share one persistent world and must not leave it mid-choice.
     */
    public static void withdraw(MinecraftServer server) {
        CandidateAreaData data = CandidateAreaData.get(server);
        data.groups.clear();
        data.converted.clear();
        data.setDirty();
        ChoosingFreeze.release();
    }

    /**
     * Picks {@link #COUNT} fresh Candidate areas per dimension, reads their previews from their generated chunks, marks
     * what each offers that's new ({@link NewOres}) and offers them, replacing any on offer (a re-roll). Players stay
     * held where they are.
     *
     * <p>Biased towards something new (#79): within each dimension at most one candidate may offer nothing new. While
     * more do, every one of them after the first is replaced by a freshly picked area, until the rule holds or
     * {@link #MAX_EXTRA_AREAS} more areas have been tried; then the best set found is offered. Waived when no ore of
     * the dimension is new anywhere ({@link NewOres#anyLeft}). Every area is picked with one random forked at the
     * start ({@link #pickingRandom}), far from every area already tried, so generating chunks can't change which
     * areas are picked.
     */
    static void offerFresh(MinecraftServer server) {
        long start = System.nanoTime();
        ServerLevel overworld = server.overworld();
        RandomSource random = pickingRandom(overworld);
        NewOres newOres = NewOres.of(overworld);
        List<RunArea> tried = new ArrayList<>(RunAreas.candidates(server, COUNT, random, List.of()));
        List<CandidateArea> candidates = new ArrayList<>();
        for (RunArea area : tried) candidates.add(preview(overworld, area, newOres));
        while (newOres.anyLeft()) {
            List<Integer> surplus = surplusNothingNew(candidates);
            int extra = Math.min(surplus.size(), COUNT + MAX_EXTRA_AREAS - tried.size());
            if (extra <= 0) break;
            List<RunArea> more = RunAreas.candidates(server, extra, random, tried);
            tried.addAll(more);
            for (int i = 0; i < extra; i++) candidates.set(surplus.get(i), preview(overworld, more.get(i), newOres));
        }
        CandidateAreaData data = CandidateAreaData.get(server);
        data.groups.clear();
        data.groups.put(Level.OVERWORLD, List.copyOf(candidates));
        data.setDirty();
        McIncrementalPrestige.LOGGER.info("Candidate areas: generated and previewed {} areas in {} ms ({} offering something new)",
                tried.size(), (System.nanoTime() - start) / 1_000_000,
                candidates.stream().filter(c -> c.preview().offersSomethingNew()).count());
    }

    /**
     * The random a fresh offer picks every area with, forked from the overworld's: seeding the overworld's random
     * fixes it. Public for tests that predict a re-roll's areas.
     */
    public static RandomSource pickingRandom(ServerLevel level) {
        return RandomSource.create(level.getRandom().nextLong());
    }

    private static CandidateArea preview(ServerLevel level, RunArea area, NewOres newOres) {
        return new CandidateArea(area, newOres.mark(CandidatePreviews.read(level, area)));
    }

    /** The positions of the candidates offering nothing new, except the first such one. */
    private static List<Integer> surplusNothingNew(List<CandidateArea> candidates) {
        List<Integer> surplus = new ArrayList<>();
        boolean first = true;
        for (int i = 0; i < candidates.size(); i++) {
            if (candidates.get(i).preview().offersSomethingNew()) continue;
            if (first) first = false;
            else surplus.add(i);
        }
        return surplus;
    }

    /**
     * Takes candidate {@code number} (from 1, through the groups in order) and ends the choice: nothing is offered any
     * more and players are released. Empty, changing nothing, when no candidate has that number (none offered, or
     * already chosen).
     */
    static Optional<RunArea> take(MinecraftServer server, int number) {
        List<CandidateArea> candidates = CandidateAreaData.get(server).numbered();
        if (number < 1 || number > candidates.size()) return Optional.empty();
        RunArea chosen = candidates.get(number - 1).area();
        withdraw(server);
        return Optional.of(chosen);
    }

    /** Whether the pending Prestige has converted this player's inventory and ender chest. */
    static boolean isConverted(MinecraftServer server, UUID player) {
        return CandidateAreaData.get(server).converted.contains(player);
    }

    /** Records that the pending Prestige has converted this player's inventory and ender chest. */
    static void markConverted(MinecraftServer server, UUID player) {
        CandidateAreaData data = CandidateAreaData.get(server);
        if (data.converted.add(player)) data.setDirty();
    }

    /** Lists the candidates on offer in the player's chat, by dimension, with their previews and the command that chooses one. */
    static void list(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        Map<ResourceKey<Level>, List<CandidateArea>> groups = offeredByDimension(server);
        if (groups.isEmpty()) return;
        int count = groups.values().stream().mapToInt(List::size).sum();
        player.sendSystemMessage(Component.translatable(HEADING_KEY, count));
        int n = 0;
        for (Map.Entry<ResourceKey<Level>, List<CandidateArea>> group : groups.entrySet()) {
            player.sendSystemMessage(Component.translatable(DIMENSION_KEY, Component.literal(group.getKey().location().toString())));
            for (CandidateArea candidate : group.getValue()) {
                CandidatePreview preview = candidate.preview();
                player.sendSystemMessage(Component.translatable(LINE_KEY, ++n, biomeName(preview),
                        veins(preview), structures(preview)));
            }
        }
    }

    private static Component biomeName(CandidatePreview preview) {
        return preview.biome()
                .<Component>map(id -> Component.translatable(Util.makeDescriptionId("biome", id)))
                .orElseGet(() -> Component.literal("?"));
    }

    /** "magnetite vein (Gold, Magnetite), ...", or "none". */
    private static Component veins(CandidatePreview preview) {
        if (preview.veins().isEmpty()) return Component.translatable(NONE_KEY);
        MutableComponent text = Component.empty();
        for (int i = 0; i < preview.veins().size(); i++) {
            OreVein vein = preview.veins().get(i);
            if (i > 0) text.append(", ");
            MutableComponent materials = Component.empty();
            for (int j = 0; j < vein.materials().size(); j++) {
                if (j > 0) materials.append(", ");
                // GT's material names are under material.<namespace>.<name>.
                ResourceLocation material = vein.materials().get(j);
                Component name = Component.translatable(material.toLanguageKey("material"));
                materials.append(preview.isNew(material) ? newMark(name) : name);
            }
            text.append(Component.translatable(VEIN_KEY, vein.type().getPath(), materials));
        }
        return text;
    }

    /** "Gold (new)", in green. Public for the selection screen. */
    public static Component newMark(Component name) {
        return Component.translatable(NEW_KEY, name).withStyle(ChatFormatting.GREEN);
    }

    /** "village_plains, mineshaft", or "none". */
    private static Component structures(CandidatePreview preview) {
        if (preview.structures().isEmpty()) return Component.translatable(NONE_KEY);
        return Component.literal(String.join(", ", preview.structures().stream().map(ResourceLocation::getPath).toList()));
    }
}
