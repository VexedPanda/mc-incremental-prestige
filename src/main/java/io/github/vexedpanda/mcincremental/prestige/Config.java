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

    /**
     * EMC per machine-second for each GT voltage tier (ULV, LV, MV, HV, EV, IV, LuV, ZPM, UV, UHV, ...), added to every GT
     * recipe's cost at the recipe's tier (VexedPanda/Mc-incremental#67). Empty, or all 0: off.
     */
    public static final ModConfigSpec.ConfigValue<List<? extends Number>> TIME_RATE;
    /** EMC per EU for each GT voltage tier, the same way. */
    public static final ModConfigSpec.ConfigValue<List<? extends Number>> EU_PRICE;

    static {
        BUILDER.comment("GT recipe EMC: what a GT machine recipe's EU and machine time add to its outputs' EMC",
                "(VexedPanda/Mc-incremental#67). Each is a list per GT voltage tier, starting at ULV:",
                "[ULV, LV, MV, HV, EV, IV, LuV, ZPM, UV, UHV, UEV, UIV, UXV, OpV, MAX]. A tier past the end of a list",
                "takes the last value listed. Empty (the default) or 0: not counted.").push("emc");
        TIME_RATE = BUILDER
                .comment("EMC per machine-second at the recipe's tier (its duration, before overclocking). Should at least",
                        "double each tier: an overclocked recipe runs twice as fast per tier above its own.")
                .defineListAllowEmpty("timeRate", List.of(), () -> 0.0, Config::isRate);
        EU_PRICE = BUILDER
                .comment("EMC per EU at the recipe's tier (EU per tick x duration). Generator recipes add none.")
                .defineListAllowEmpty("euPrice", List.of(), () -> 0.0, Config::isRate);
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    /** A list's value at a GT tier: a tier past the end takes the last value listed; an empty list is 0. */
    public static double atTier(List<? extends Number> perTier, int tier) {
        if (perTier.isEmpty()) return 0;
        return perTier.get(Math.min(Math.max(tier, 0), perTier.size() - 1)).doubleValue();
    }

    /** GT voltage tier names, in the lists' order. */
    public static final List<String> TIERS = List.of("ULV", "LV", "MV", "HV", "EV", "IV", "LuV", "ZPM", "UV", "UHV", "UEV",
            "UIV", "UXV", "OpV", "MAX");

    /**
     * The overclock check (VexedPanda/Mc-incremental#67): a warning for each tier whose time rate is less than double the
     * tier below's. GT's overclock runs a recipe twice as fast per tier above its own, so a recipe valued at its own tier
     * would otherwise earn more per second in a higher-tier machine than that machine's time rate.
     */
    public static List<String> timeRateWarnings(List<? extends Number> timeRate) {
        List<String> warnings = new java.util.ArrayList<>();
        for (int tier = 1; tier < timeRate.size(); tier++) {
            double below = timeRate.get(tier - 1).doubleValue();
            double rate = timeRate.get(tier).doubleValue();
            if (rate < 2 * below) {
                String name = tier < TIERS.size() ? TIERS.get(tier) : "tier " + tier;
                String belowName = tier - 1 < TIERS.size() ? TIERS.get(tier - 1) : "tier " + (tier - 1);
                warnings.add("emc.timeRate at " + name + " (" + rate + ") is less than double " + belowName + "'s (" + below
                        + "): an overclocked " + belowName + " recipe earns more than a " + name + " machine's time");
            }
        }
        return warnings;
    }

    private static boolean isRate(Object o) {
        return o instanceof Number n && n.doubleValue() >= 0 && Double.isFinite(n.doubleValue());
    }

    private Config() {}
}
