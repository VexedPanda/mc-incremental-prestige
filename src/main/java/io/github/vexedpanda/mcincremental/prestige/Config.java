package io.github.vexedpanda.mcincremental.prestige;

import java.util.List;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;

/** The mod's common config: {@code config/mcincremental_prestige-common.toml}. */
public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    /** Item ids every player holds when they first join a world and at the start of every Run. */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> STARTING_ITEMS = BUILDER
            .comment("Item ids every player is given when they first join a world and again after every Prestige,",
                    "one of each. A player already holding one of these isn't given another on join.")
            .defineListAllowEmpty("startingItems", List.of("projecte:transmutation_tablet"), () -> "",
                    o -> o instanceof String s && ResourceLocation.tryParse(s) != null);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {}
}
