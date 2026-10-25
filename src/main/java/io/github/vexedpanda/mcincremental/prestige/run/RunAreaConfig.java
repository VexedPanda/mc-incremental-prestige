package io.github.vexedpanda.mcincremental.prestige.run;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Run area settings, in the world's server config ({@code serverconfig/mcincremental_prestige-server.toml}), so each
 * world can tune its own. Read when a Run area is chosen or swept; changes apply from the next Prestige.
 */
public final class RunAreaConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue RADIUS_CHUNKS;
    public static final ModConfigSpec.IntValue MIN_DISTANCE_CHUNKS;
    public static final ModConfigSpec.IntValue SWEEP_MARGIN_CHUNKS;
    public static final ModConfigSpec.BooleanValue AVOID_OCEANS;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("runArea");
        RADIUS_CHUNKS = builder
                .comment("Chunks from a new Run area's centre chunk to its edge: 1 is 3x3 chunks, 2 is 5x5.",
                        "1 matches GregTech's 3-chunk ore-vein grid: one vein per layer.")
                .defineInRange("radiusChunks", 1, 0, 16);
        MIN_DISTANCE_CHUNKS = builder
                .comment("Minimum distance in chunks (along x or z) between a new Run area's centre and every earlier one.",
                        "New areas are placed 1 to 2 times this far from the current one.")
                .defineInRange("minDistanceChunks", 64, 8, 100_000);
        SWEEP_MARGIN_CHUNKS = builder
                .comment("Chunks around the Run area that the Prestige sweep also covers: 1 sweeps 5x5 around a 3x3 area.")
                .defineInRange("sweepMarginChunks", 1, 0, 16);
        AVOID_OCEANS = builder
                .comment("Re-pick a new Run area whose centre is in an ocean biome.")
                .define("avoidOceans", true);
        builder.pop();
        SPEC = builder.build();
    }

    private RunAreaConfig() {}
}
