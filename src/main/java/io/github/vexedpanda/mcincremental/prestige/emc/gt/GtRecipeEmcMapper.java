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
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
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
 * <p>Per recipe of every GT recipe type:
 * <ul>
 *   <li>Inputs are the consumed item and fluid inputs. Non-consumed inputs (chance 0: programmed circuits, molds,
 *       lenses, catalysts) are left out, so they neither add cost nor make a loop. An item that is both input and
 *       output is netted out.</li>
 *   <li>Only the primary output is valued: the first guaranteed item output, else the first guaranteed fluid output.
 *       Its value is the whole input cost, less any byproduct fluid that has an explicit value. Other outputs
 *       (byproducts, chanced outputs) get no value from the recipe and aren't subtracted: subtracting byproducts that
 *       are valued by other routes drove primary outputs toward 0, which made new exploits. So a recipe with byproducts
 *       slightly overvalues its primary output (ProjectE takes the cheapest route, so only when it has no other).</li>
 *   <li>Fluids are valued per 1000 mB, from {@code fluid_emc} datapack values ({@link FluidEmc}); never from buckets.
 *       A fluid listed at 0 (water, steam) is free and left out, and so is water when unlisted (ADR-0005). An unlisted
 *       fluid gets no fixed value: it is valued only through GT recipes that make it (e.g. a fluid extractor melting an
 *       ingot), and that value is published for {@link FluidEmc} ({@link FluidEmc#derivedValueKey}), so a tank of it
 *       is worth what the recipes cost it at. Amounts are scaled so a recipe's fluid amounts stay exact.</li>
 *   <li>A conversion never makes its output free ({@link NeverFreeArithmetic}): a recipe with no consumed input, or
 *       whose valued byproduct fluids are worth more than its inputs, gives no value instead.</li>
 * </ul>
 *
 * <p>Skipped: GT's recycling recipes ({@code *_recycling} categories: arc furnace, macerator and extractor scrapping,
 * whose outputs come from GT's material bills), except those that only change the form of one ingot, gem or dust (a
 * copper ingot arc-smelted into annealed copper, a diamond macerated into dust); GT's {@code decomposition_*} recipes
 * (centrifuge and electrolyzer splitting a compound or alloy back into its components, the inverse of the recipe that
 * values it); recipes with ranged ("int provider") amounts or fluid-container ingredients; outputs matching several
 * items or fluids; and per-tick inputs and outputs (EU, and the rare per-tick item or fluid).
 */
@EMCMapper(requiredMods = "gtceu")
public class GtRecipeEmcMapper implements IEMCMapper<NormalizedSimpleStack, Long> {
    /** Amounts are scaled so that one fluid node is 1000 mB and every fluid amount is a whole number of mB. */
    private static final int MB_PER_NODE = 1000;

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
        NSSFake.setCurrentNamespace(McIncrementalPrestige.MODID + ":gt_recipes");
        try {
            MappingPass pass = new MappingPass(collector);
            int mapped = 0;
            int skipped = 0;
            for (var type : BuiltInRegistries.RECIPE_TYPE) {
                if (!(type instanceof GTRecipeType gtType)) continue;
                for (RecipeHolder<GTRecipe> holder : serverResources.getRecipeManager().getAllRecipesFor(gtType)) {
                    if (pass.map(holder.value())) mapped++;
                    else skipped++;
                }
            }
            McIncrementalPrestige.LOGGER.info("GT recipe EMC mapper: mapped {} GT recipes, skipped {}", mapped, skipped);
        } finally {
            NSSFake.resetNamespace();
        }
    }

    /** State for one mapping pass: the fluid nodes and ingredient groups created so far. */
    private static final class MappingPass {
        private final IMappingCollector<NormalizedSimpleStack, Long> collector;
        private final Map<Fluid, NormalizedSimpleStack> fluidNodes = new HashMap<>();
        private final Map<Set<NormalizedSimpleStack>, NormalizedSimpleStack> groups = new HashMap<>();
        /** Fluid nodes with an explicit (datapack) value. */
        private final Set<NormalizedSimpleStack> valuedFluids = new HashSet<>();

        /** ProjectE's collector, when it takes a per-conversion arithmetic (it always does today). */
        @SuppressWarnings("rawtypes")
        private final IExtendedMappingCollector extended;
        private final IValueArithmetic<?> arithmetic;

        @SuppressWarnings({"rawtypes", "unchecked"})
        MappingPass(IMappingCollector<NormalizedSimpleStack, Long> collector) {
            this.collector = collector;
            if (collector instanceof IExtendedMappingCollector ext) {
                extended = ext;
                arithmetic = new NeverFreeArithmetic<>((IValueArithmetic) ext.getArithmetic());
            } else {
                extended = null;
                arithmetic = null;
                McIncrementalPrestige.LOGGER.warn("GT recipe EMC mapper: ProjectE's collector takes no arithmetic; "
                        + "a recipe whose byproducts are worth more than its inputs will make its outputs free");
            }
        }

        /** Adds the recipe's conversions; false when the recipe is skipped. */
        boolean map(GTRecipe recipe) {
            boolean recycling = recipe.recipeCategory != null && recipe.recipeCategory.id.getPath().endsWith("_recycling");
            if (recycling && !isMaterialFormChange(recipe)) return false;
            if (recipe.id != null && recipe.id.getPath().contains("decomposition_")) return false;
            // Amounts in thousandths: items × 1000, fluids in mB (one node = 1000 mB).
            Map<NormalizedSimpleStack, Long> in = new LinkedHashMap<>();
            Map<NormalizedSimpleStack, Long> out = new LinkedHashMap<>();
            if (!collect(recipe.inputs, true, in) || !collect(recipe.outputs, false, out)) return false;
            if (out.isEmpty()) return false;
            // An item that comes back out is only consumed (or made) by the difference.
            for (var node : new ArrayList<>(out.keySet())) {
                Long consumed = in.get(node);
                if (consumed == null) continue;
                long net = out.get(node) - consumed;
                in.remove(node);
                out.remove(node);
                if (net > 0) out.put(node, net);
                else if (net < 0) in.put(node, -net);
            }
            if (out.isEmpty()) return false;
            // Only the primary output (the first item, else the first fluid) is valued, from the whole input cost less
            // byproduct fluids with an explicit value. Other outputs get no value from this recipe and aren't
            // subtracted: subtracting byproducts valued by other routes drives the primary output's value toward 0.
            var primary = out.entrySet().iterator().next();
            Map<NormalizedSimpleStack, Long> amounts = new LinkedHashMap<>(in);
            for (var other : out.entrySet()) {
                if (other != primary && valuedFluids.contains(other.getKey())) {
                    amounts.merge(other.getKey(), -other.getValue(), Long::sum);
                }
            }
            return addConversion(primary.getKey(), primary.getValue(), amounts);
        }

        /**
         * Whether a recycling recipe only changes the form of one material item (e.g. an arc furnace turning a copper
         * ingot into annealed copper, a macerator turning a gem into dust): one consumed ingot, gem or dust, and one
         * guaranteed output. Recycling of parts, pipes, wires and machines is left out: its outputs come from GT's
         * material bills, not from what the recipe costs.
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

        /** Reduces the thousandths amounts by their common divisor and adds the conversion. */
        private boolean addConversion(NormalizedSimpleStack output, long outAmount, Map<NormalizedSimpleStack, Long> amounts) {
            amounts.values().removeIf(v -> v == 0);
            BigInteger gcd = BigInteger.valueOf(outAmount);
            for (long v : amounts.values()) gcd = gcd.gcd(BigInteger.valueOf(Math.abs(v)));
            long divisor = gcd.longValueExact();
            long outCount = outAmount / divisor;
            if (outCount > Integer.MAX_VALUE) return false;
            Object2IntMap<NormalizedSimpleStack> ingredients = new Object2IntOpenHashMap<>();
            for (var entry : amounts.entrySet()) {
                long v = entry.getValue() / divisor;
                if (Math.abs(v) > Integer.MAX_VALUE) return false;
                ingredients.put(entry.getKey(), (int) v);
            }
            if (arithmetic != null) extended.addConversion((int) outCount, output, ingredients, arithmetic);
            else collector.addConversion((int) outCount, output, ingredients);
            return true;
        }

        /**
         * Adds a capability map's consumed (inputs) or guaranteed (outputs) items and fluids to {@code into}; false
         * when the recipe can't be mapped.
         */
        private boolean collect(Map<?, List<Content>> contents, boolean inputs, Map<NormalizedSimpleStack, Long> into) {
            for (Content content : contents.getOrDefault(ItemRecipeCapability.CAP, List.of())) {
                if (inputs ? content.chance() == 0 : content.chance() < content.maxChance()) continue;
                SizedIngredient sized = ItemRecipeCapability.CAP.of(content.content());
                Ingredient ingredient = sized.ingredient();
                if (ingredient.getCustomIngredient() instanceof IntCircuitIngredient) continue;
                if (ingredient.getCustomIngredient() != null && !isPlain(ingredient)) return false;
                NormalizedSimpleStack node = inputs ? itemInput(ingredient) : itemOutput(ingredient);
                if (node == null) return false;
                into.merge(node, (long) sized.count() * MB_PER_NODE, Long::sum);
            }
            for (Content content : contents.getOrDefault(FluidRecipeCapability.CAP, List.of())) {
                if (inputs ? content.chance() == 0 : content.chance() < content.maxChance()) continue;
                SizedFluidIngredient sized = FluidRecipeCapability.CAP.of(content.content());
                if (sized.ingredient().getClass().getSimpleName().startsWith("IntProvider")) return false;
                FluidStack[] fluids = sized.getFluids();
                if (fluids.length == 0) return false;
                NormalizedSimpleStack node = inputs ? fluidInput(fluids) : (fluids.length == 1 ? fluidNode(fluids[0].getFluid()) : null);
                if (node == FREE) continue;
                if (node == null) return false;
                into.merge(node, (long) sized.amount(), Long::sum);
            }
            return true;
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
                    collector.addConversion(1, dummy, one);
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
                    collector.setValueBefore(node, listed.getAsLong());
                    valuedFluids.add(node);
                } else {
                    // Publish the value derived for the fluid, so tanks and the barrel use it too (FluidEmc).
                    Object2IntMap<NormalizedSimpleStack> bucket = new Object2IntOpenHashMap<>();
                    bucket.put(node, 1);
                    collector.addConversion(1, FluidEmc.derivedValueKey(f).toNSS(), bucket);
                }
                return node;
            });
        }
    }

    /** Material forms (GT tag prefix names) whose recycling recipes ({@link MappingPass#isMaterialFormChange}) are mapped. */
    private static final Set<String> BASIC_FORMS = Set.of("ingot", "gem", "dust");

    /** Marker for a free ingredient, which is left out of the conversion. */
    private static final NormalizedSimpleStack FREE = NSSFake.create("free marker (never added)");
}
