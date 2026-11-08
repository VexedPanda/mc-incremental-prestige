package io.github.vexedpanda.mcincremental.prestige.preview;

import java.util.List;
import java.util.TreeSet;
import java.util.function.Predicate;

import io.github.vexedpanda.mcincremental.prestige.emc.WorldEmc;
import io.github.vexedpanda.mcincremental.prestige.preview.gt.GtNewOres;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.ModList;

/**
 * Which ores are new to the world, read from its Knowledge once per offer of Candidate areas (VexedPanda/Mc-incremental#79).
 * An ore (a GT material) is new when the world's Knowledge lacks it and the world can harvest it:
 * <ul>
 * <li><b>lacks it:</b> Knowledge holds neither anything its ore block drops when mined with a pickaxe, nor any of its
 * ore blocks. An ore whose drops have no EMC can never be discovered, so it is never new;</li>
 * <li><b>can harvest it:</b> Knowledge holds a pickaxe that is the correct tool for its ore block (the ore block's
 * vanilla tool tags: {@code needs_stone_tool}, {@code needs_iron_tool}, {@code needs_diamond_tool}, none meaning a
 * wooden pickaxe will do), or a GT miner, which mines any ore.</li>
 * </ul>
 * Without GregTech there are no ores, so nothing is new. Server thread only.
 */
public final class NewOres {
    private static final boolean GREGTECH_LOADED = ModList.get().isLoaded("gtceu");
    private static final NewOres NONE = new NewOres(material -> false, false);

    private final Predicate<ResourceLocation> isNew;
    private final boolean anyLeft;

    /**
     * @param isNew   whether an ore material (GT material id) is new
     * @param anyLeft whether any ore of the level's dimension is new, wherever it is
     */
    public NewOres(Predicate<ResourceLocation> isNew, boolean anyLeft) {
        this.isNew = isNew;
        this.anyLeft = anyLeft;
    }

    /** What is new in {@code level}'s dimension, as the world's Knowledge stands now. */
    public static NewOres of(ServerLevel level) {
        if (!GREGTECH_LOADED) return NONE;
        return GtNewOres.of(level, WorldEmc.knowledge(level.getServer()));
    }

    /**
     * Whether any ore that generates in the dimension is new, in any area. When none is, the bias towards something
     * new is waived: there is nothing left to offer.
     */
    public boolean anyLeft() {
        return anyLeft;
    }

    public boolean isNew(ResourceLocation material) {
        return isNew.test(material);
    }

    /** {@code preview} with the materials of its veins that are new marked new. */
    public CandidatePreview mark(CandidatePreview preview) {
        TreeSet<ResourceLocation> found = new TreeSet<>();
        for (OreVein vein : preview.veins()) {
            for (ResourceLocation material : vein.materials()) if (isNew(material)) found.add(material);
        }
        return preview.withNewOres(List.copyOf(found));
    }
}
