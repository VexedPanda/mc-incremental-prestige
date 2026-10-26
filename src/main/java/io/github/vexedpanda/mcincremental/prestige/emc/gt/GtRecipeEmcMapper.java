package io.github.vexedpanda.mcincremental.prestige.emc.gt;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.data.chemical.ChemicalHelper;
import com.gregtechceu.gtceu.api.data.tag.TagPrefix;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.ingredient.IntCircuitIngredient;

import io.github.vexedpanda.mcincremental.prestige.McIncrementalPrestige;
import io.github.vexedpanda.mcincremental.prestige.emc.FluidEmc;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import moze_intel.projecte.api.mapper.EMCMapper;
import moze_intel.projecte.api.mapper.IEMCMapper;
import moze_intel.projecte.api.mapper.arithmetic.IValueArithmetic;
import moze_intel.projecte.api.mapper.collector.IExtendedMappingCollector;
import moze_intel.projecte.api.mapper.collector.IMappingCollector;
import moze_intel.projecte.api.nss.NSSFake;
import moze_intel.projecte.api.nss.NSSItem;
import moze_intel.projecte.api.nss.NormalizedSimpleStack;
import moze_intel.projecte.shaded.org.apache.commons.math3.fraction.BigFraction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.ReloadableServerResources;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.common.crafting.SizedIngredient;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;

/**
 * Feeds GregTech machine recipes into ProjectE's EMC mapper, so each output is valued from the cost of its inputs the
 * way ProjectE values crafting recipes (VexedPanda/Mc-incremental#52). ProjectE discovers this class by its
 * annotation and loads it only when GregTech is installed; nothing else may reference it, so the mod runs without GT.
 *
 * <p>Every GT recipe is mapped, recycling and decomposition included (VexedPanda/Mc-incremental#58):
 * <ul>
 *   <li>Inputs are the consumed item and fluid inputs, at their expected amount (a chanced input at its chance).
 *       Non-consumed inputs (chance 0: programmed circuits, molds, lenses, catalysts) are left out, so they neither add
 *       cost nor make a loop. An item that is both input and output is netted out.</li>
 *   <li>Outputs are every item and fluid output, chanced ones at their expected amount. The inputs' cost is split
 *       across them ({@link OutputShares}): outputs valued elsewhere keep their value, and the outputs this recipe
 *       values share the rest in proportion to their values, so the outputs together are worth the inputs.</li>
 *   <li>Fluids are valued per 1000 mB, from {@code fluid_emc} datapack values ({@link FluidEmc}); never from buckets.
 *       A fluid listed at 0 (water, steam) is free and left out, and so is water when unlisted (ADR-0005). An unlisted
 *       fluid gets no fixed value: it is valued only through GT recipes that make it (e.g. a fluid extractor melting an
 *       ingot), and that value is published for {@link FluidEmc} ({@link FluidEmc#derivedValueKey}), so a tank of it
 *       is worth what the recipes cost it at.</li>
 *   <li>A conversion never makes its output free ({@link NeverFreeArithmetic}).</li>
 * </ul>
 *
 * <p>Skipped: recipes with no consumed input (left after netting); recipes with ranged ("int provider") amounts or
 * fluid-container ingredients; outputs matching several items or fluids; and per-tick inputs and outputs (EU, and the
 * rare per-tick item or fluid).
 */
@EMCMapper(requiredMods = "gtceu")
public class GtRecipeEmcMapper implements IEMCMapper<NormalizedSimpleStack, Long> {
    /**
     * What the last mapping found pinned at a fixed value (custom EMC values, ProjectE's defaults, listed fluids): no
     * recipe can lower those, so a recipe whose pinned outputs alone are worth more than its inputs keeps making EMC
     * until it is re-priced.
     */
    private static volatile Set<NormalizedSimpleStack> pinned = Set.of();

    public static boolean isPinned(NormalizedSimpleStack node) {
        return pinned.contains(node);
    }

    /** The GT recipes whose cost the last mapping split across their outputs by share, and with fixed values. */
    private static volatile Set<ResourceLocation> split = Set.of();
    private static volatile Set<ResourceLocation> fixed = Set.of();

    public static boolean isSplitByShare(ResourceLocation recipe) {
        return split.contains(recipe);
    }

    public static boolean isSplitWithFixedValues(ResourceLocation recipe) {
        return fixed.contains(recipe);
    }

    /** Shares are rounded up to millionths of a recipe's cost (coarser only when a conversion's amounts overflow). */
    static final long SHARE_DENOMINATOR = 1_000_000;

    @Override
    public String getName() {
        return "GTRecipeMapper";
    }

    @Override
    public String getTranslationKey() {
        return "config." + McIncrementalPrestige.MODID + ".gt_recipe_mapper";
    }

    @Override
    public String getDescription() {
        return "Values GregTech items from their GT machine recipes (Mc-incremental Prestige).";
    }

    @Override
    public void addMappings(IMappingCollector<NormalizedSimpleStack, Long> collector, ReloadableServerResources serverResources,
            RegistryAccess registryAccess, ResourceManager resourceManager) {
        Recipes recipes;
        NSSFake.setCurrentNamespace(McIncrementalPrestige.MODID + ":gt_recipes");
        try {
            recipes = new Recipes();
            for (var type : BuiltInRegistries.RECIPE_TYPE) {
                if (!(type instanceof GTRecipeType gtType)) continue;
                for (RecipeHolder<GTRecipe> holder : serverResources.getRecipeManager().getAllRecipesFor(gtType)) {
                    recipes.read(holder.value());
                }
            }
        } finally {
            NSSFake.resetNamespace();
        }
        OutputShares shares = OutputShares.find(this, recipes, serverResources, registryAccess, resourceManager);
        pinned = Set.copyOf(shares.pinned);
        split = Set.copyOf(shares.splitRecipes(false));
        fixed = Set.copyOf(shares.splitRecipes(true));
        IExtendedMappingCollector<NormalizedSimpleStack, Long, ?> target = extended(collector);
        recipes.nodes.replayInto(target);
        int dropped = shares.emit(target);
        McIncrementalPrestige.LOGGER.info("GT recipe EMC mapper: mapped {} GT recipes, skipped {}", recipes.splits.size(),
                recipes.skipped);
        if (dropped > 0) {
            McIncrementalPrestige.LOGGER.error("GT recipe EMC mapper: {} recipe outputs left out because their amounts "
                    + "don't fit ProjectE's conversions", dropped);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static IExtendedMappingCollector<NormalizedSimpleStack, Long, ?> extended(IMappingCollector<NormalizedSimpleStack, Long> collector) {
        if (collector instanceof IExtendedMappingCollector ext) return ext;
        throw new IllegalStateException("ProjectE's mapping collector takes no arithmetic: " + collector.getClass());
    }

    /**
     * One GT recipe as amounts per run: consumed inputs and outputs, items in items and fluids in 1000 mB. {@code first}
     * (whether the first output may take the whole cost, as in #52) is false when no output is guaranteed, and for
     * GT's recycling (other than a material form change) and decomposition recipes: their outputs come from GT's
     * material bills, or undo the recipe that values them, so they only value outputs nothing else values.
     */
    record Split(ResourceLocation id, Map<NormalizedSimpleStack, BigFraction> inputs, List<NormalizedSimpleStack> outputs,
            List<BigFraction> amounts, boolean first, boolean decomposition) {
    }

    private enum Side {
        INPUT, GUARANTEED, CHANCED;

        /** Consumed inputs (chance above 0); guaranteed or chanced outputs. */
        boolean takes(Content content) {
            return switch (this) {
                case INPUT -> content.chance() > 0;
                case GUARANTEED -> content.chance() >= content.maxChance();
                case CHANCED -> content.chance() > 0 && content.chance() < content.maxChance();
            };
        }
    }

    /** Material forms (GT tag prefix names) whose recycling recipes count as form changes. */
    private static final Set<String> BASIC_FORMS = Set.of("ingot", "gem", "dust");

    /** The GT recipes read so far, and the fluid and ingredient-group nodes they use. */
    static final class Recipes {
        final List<Split> splits = new ArrayList<>();
        /** The nodes' own mappings (groups, fluid values), replayed into every graph before the recipes. */
        final RecordingCollector nodes = new RecordingCollector();
        int skipped;
        private final Map<Fluid, NormalizedSimpleStack> fluidNodes = new HashMap<>();
        private final Map<Set<NormalizedSimpleStack>, NormalizedSimpleStack> groups = new HashMap<>();
        /** A fake node pinned at 1 EMC: a fixed value is a conversion from that many of it. */
        private final NormalizedSimpleStack unit = NSSFake.create("emc unit");

        Recipes() {
            nodes.setValueBefore(unit, 1L);
        }

        void read(GTRecipe recipe) {
            Map<NormalizedSimpleStack, BigFraction> in = new LinkedHashMap<>();
            Map<NormalizedSimpleStack, BigFraction> out = new LinkedHashMap<>();
            // Outputs in order: guaranteed items, guaranteed fluids, then chanced ones; the first is #52's.
            if (!collect(recipe.inputs, Side.INPUT, in) || !collect(recipe.outputs, Side.GUARANTEED, out)) {
                skipped++;
                return;
            }
            Set<NormalizedSimpleStack> guaranteed = Set.copyOf(out.keySet());
            if (!collect(recipe.outputs, Side.CHANCED, out)) {
                skipped++;
                return;
            }
            // An item that comes back out is only consumed (or made) by the difference.
            for (var node : new ArrayList<>(out.keySet())) {
                BigFraction consumed = in.remove(node);
                if (consumed == null) continue;
                BigFraction net = out.remove(node).subtract(consumed);
                if (net.compareTo(BigFraction.ZERO) > 0) out.put(node, net);
                else if (net.compareTo(BigFraction.ZERO) < 0) in.put(node, net.negate());
            }
            if (in.isEmpty() || out.isEmpty()) {
                skipped++;
                return;
            }
            boolean recycling = recipe.recipeCategory != null && recipe.recipeCategory.id.getPath().endsWith("_recycling");
            boolean decomposition = recipe.id != null && recipe.id.getPath().contains("decomposition_");
            splits.add(new Split(recipe.id, in, List.copyOf(out.keySet()), List.copyOf(out.values()),
                    guaranteed.contains(out.keySet().iterator().next()) && !decomposition
                            && (!recycling || isMaterialFormChange(recipe)), decomposition));
        }

        /**
         * Whether a recycling recipe only changes the form of one material item (e.g. an arc furnace turning a copper
         * ingot into annealed copper, a macerator turning a gem into dust): one consumed ingot, gem or dust, and one
         * guaranteed output.
         */
        private static boolean isMaterialFormChange(GTRecipe recipe) {
            List<Content> items = recipe.inputs.getOrDefault(ItemRecipeCapability.CAP, List.of()).stream()
                    .filter(c -> c.chance() > 0).toList();
            if (items.size() != 1) return false;
            long outputs = recipe.outputs.values().stream().flatMap(List::stream).filter(c -> c.chance() >= c.maxChance()).count();
            if (outputs != 1) return false;
            ItemStack[] stacks = ItemRecipeCapability.CAP.of(items.get(0).content()).ingredient().getItems();
            return stacks.length > 0 && Arrays.stream(stacks).allMatch(stack -> {
                TagPrefix prefix = ChemicalHelper.getPrefix(stack);
                return prefix != null && BASIC_FORMS.contains(prefix.name());
            });
        }

        /** Nodes with a value set before inheriting (pinned): by other mappers, or listed fluids. */
        Set<NormalizedSimpleStack> pinned(RecordingCollector others) {
            Set<NormalizedSimpleStack> pinned = new HashSet<>();
            others.pinned(pinned);
            nodes.pinned(pinned);
            return pinned;
        }

        /** Output k at a fixed value per run ({@code value} EMC for the recipe's whole amount of it). */
        @SuppressWarnings({"rawtypes", "unchecked"})
        boolean addFixed(IExtendedMappingCollector target, IValueArithmetic arithmetic, Split split, int k, double value) {
            double each = value / split.amounts().get(k).doubleValue();
            for (long outnumber : new long[] {1000, 1}) {
                double units = Math.ceil(each * outnumber - 1e-9);
                if (units < 1 || units > Integer.MAX_VALUE) continue;
                Object2IntMap<NormalizedSimpleStack> ingredients = new Object2IntOpenHashMap<>();
                ingredients.put(unit, (int) units);
                target.addConversion((int) outnumber, split.outputs().get(k), ingredients, arithmetic);
                return true;
            }
            return false;
        }

        /**
         * Adds a capability map's consumed (inputs) or made (outputs) items and fluids to {@code into}, at their expected
         * amounts; false when the recipe can't be mapped.
         */
        private boolean collect(Map<?, List<Content>> contents, Side side, Map<NormalizedSimpleStack, BigFraction> into) {
            boolean inputs = side == Side.INPUT;
            for (Content content : contents.getOrDefault(ItemRecipeCapability.CAP, List.of())) {
                if (!side.takes(content)) continue;
                SizedIngredient sized = ItemRecipeCapability.CAP.of(content.content());
                Ingredient ingredient = sized.ingredient();
                if (ingredient.getCustomIngredient() instanceof IntCircuitIngredient) continue;
                if (ingredient.getCustomIngredient() != null && !isPlain(ingredient)) return false;
                NormalizedSimpleStack node = inputs ? itemInput(ingredient) : itemOutput(ingredient);
                if (node == null) return false;
                into.merge(node, expected(content, sized.count()), BigFraction::add);
            }
            for (Content content : contents.getOrDefault(FluidRecipeCapability.CAP, List.of())) {
                if (!side.takes(content)) continue;
                SizedFluidIngredient sized = FluidRecipeCapability.CAP.of(content.content());
                if (sized.ingredient().getClass().getSimpleName().startsWith("IntProvider")) return false;
                FluidStack[] fluids = sized.getFluids();
                if (fluids.length == 0) return false;
                NormalizedSimpleStack node = inputs ? fluidInput(fluids) : (fluids.length == 1 ? fluidNode(fluids[0].getFluid()) : null);
                if (node == FREE) continue;
                if (node == null) return false;
                into.merge(node, expected(content, sized.amount()).divide(MB_PER_NODE), BigFraction::add);
            }
            return true;
        }

        /** The amount a run uses or makes on average: a chanced one at its chance. */
        private static BigFraction expected(Content content, int amount) {
            BigFraction chance = content.chance() >= content.maxChance() ? BigFraction.ONE
                    : new BigFraction(content.chance(), content.maxChance());
            return chance.multiply(amount);
        }

        /** GT's ranged ("int provider") and fluid-container ingredients have no fixed amount we can cost. */
        private static boolean isPlain(Ingredient ingredient) {
            String name = ingredient.getCustomIngredient().getClass().getSimpleName();
            return !name.startsWith("IntProvider") && !name.startsWith("FluidContainer");
        }

        private NormalizedSimpleStack itemOutput(Ingredient ingredient) {
            ItemStack[] items = ingredient.getItems();
            return items.length == 1 && !items[0].isEmpty() ? NSSItem.createItem(items[0]) : null;
        }

        /** One item, or a group node worth its cheapest member (as ProjectE does for tag ingredients). */
        private NormalizedSimpleStack itemInput(Ingredient ingredient) {
            Set<NormalizedSimpleStack> members = new HashSet<>();
            for (ItemStack stack : ingredient.getItems()) {
                if (!stack.isEmpty()) members.add(NSSItem.createItem(stack));
            }
            return group(members);
        }

        private NormalizedSimpleStack fluidInput(FluidStack[] fluids) {
            Set<NormalizedSimpleStack> members = new HashSet<>();
            for (FluidStack stack : fluids) {
                NormalizedSimpleStack node = fluidNode(stack.getFluid());
                if (node == FREE) return FREE; // a free member makes the whole ingredient free
                members.add(node);
            }
            return group(members);
        }

        private NormalizedSimpleStack group(Set<NormalizedSimpleStack> members) {
            if (members.isEmpty()) return null;
            if (members.size() == 1) return members.iterator().next();
            return groups.computeIfAbsent(Set.copyOf(members), set -> {
                NormalizedSimpleStack dummy = NSSFake.create("group " + groups.size());
                for (NormalizedSimpleStack member : set) {
                    Object2IntMap<NormalizedSimpleStack> one = new Object2IntOpenHashMap<>();
                    one.put(member, 1);
                    nodes.addConversion(1, dummy, one);
                }
                return dummy;
            });
        }

        /** 1000 mB of the fluid; {@link #FREE} when its listed value is 0, and for unlisted water (ADR-0005). */
        private NormalizedSimpleStack fluidNode(Fluid fluid) {
            Fluid source = FluidEmc.source(fluid);
            OptionalLong listed = FluidEmc.listedValuePerBucket(source);
            if (listed.isPresent() ? listed.getAsLong() == 0 : source == Fluids.WATER) return FREE;
            return fluidNodes.computeIfAbsent(source, f -> {
                NormalizedSimpleStack node = NSSFake.create("fluid " + BuiltInRegistries.FLUID.getKey(f));
                if (listed.isPresent()) {
                    nodes.setValueBefore(node, listed.getAsLong());
                } else {
                    // Publish the value derived for the fluid, so tanks and the barrel use it too (FluidEmc).
                    Object2IntMap<NormalizedSimpleStack> bucket = new Object2IntOpenHashMap<>();
                    bucket.put(node, 1);
                    nodes.addConversion(1, FluidEmc.derivedValueKey(f).toNSS(), bucket);
                }
                return node;
            });
        }
    }

    /**
     * Output k's conversion, costing {@code share} of the recipe's inputs; coarsens the share (rounding up) until the
     * amounts fit ProjectE's ints. False when they never do.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static boolean addShare(IExtendedMappingCollector target, IValueArithmetic arithmetic, Split split, int k, BigFraction share) {
        for (long denominator = SHARE_DENOMINATOR; denominator >= 1; denominator /= 10) {
            BigFraction rounded = share;
            if (share.getDenominator().compareTo(BigInteger.valueOf(denominator)) > 0) {
                BigFraction scaled = share.multiply(denominator);
                BigInteger up = scaled.getNumerator().add(scaled.getDenominator()).subtract(BigInteger.ONE).divide(scaled.getDenominator());
                rounded = new BigFraction(up, BigInteger.valueOf(denominator));
            }
            BigFraction perOutput = rounded.divide(split.amounts().get(k));
            // Ingredient amounts per output: input amount × share ÷ output amount, scaled to whole numbers.
            Map<NormalizedSimpleStack, BigFraction> ratios = new LinkedHashMap<>();
            BigInteger outnumber = BigInteger.ONE;
            for (var input : split.inputs().entrySet()) {
                BigFraction ratio = input.getValue().multiply(perOutput);
                ratios.put(input.getKey(), ratio);
                outnumber = lcm(outnumber, ratio.getDenominator());
            }
            if (outnumber.bitLength() > 31) continue;
            Object2IntMap<NormalizedSimpleStack> ingredients = new Object2IntOpenHashMap<>();
            boolean fits = true;
            for (var ratio : ratios.entrySet()) {
                BigInteger amount = ratio.getValue().multiply(outnumber).getNumerator();
                if (amount.bitLength() > 31) {
                    fits = false;
                    break;
                }
                ingredients.put(ratio.getKey(), amount.intValue());
            }
            if (!fits) continue;
            target.addConversion(outnumber.intValue(), split.outputs().get(k), ingredients, arithmetic);
            return true;
        }
        return false;
    }

    private static BigInteger lcm(BigInteger a, BigInteger b) {
        return a.divide(a.gcd(b)).multiply(b);
    }

    /** One fluid node is 1000 mB. */
    private static final int MB_PER_NODE = 1000;

    /** Marker for a free ingredient, which is left out of the conversion. */
    private static final NormalizedSimpleStack FREE = NSSFake.create("free marker (never added)");
}
