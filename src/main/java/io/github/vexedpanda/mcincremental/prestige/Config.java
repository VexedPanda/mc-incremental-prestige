package io.github.vexedpanda.mcincremental.prestige;

import java.util.List;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;

/** The mod's common config: {@code config/mcincremental_prestige-common.toml}. */
public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    /** Item ids every player is given at the start of every Run and, if they lack one, on every join. */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> STARTING_ITEMS = BUILDER
            .comment("Item ids every player is given after every Prestige, one of each. On every join a player is also",
                    "given any of these they don't hold.")
            .defineListAllowEmpty("startingItems", List.of("projecte:transmutation_tablet"), () -> "",
                    o -> o instanceof String s && ResourceLocation.tryParse(s) != null);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {}
}
