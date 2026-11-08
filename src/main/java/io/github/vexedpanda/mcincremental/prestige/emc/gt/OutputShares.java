package io.github.vexedpanda.mcincremental.prestige.emc.gt;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.vexedpanda.mcincremental.prestige.McIncrementalPrestige;
import io.github.vexedpanda.mcincremental.prestige.emc.gt.GtRecipeEmcMapper.Split;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import moze_intel.projecte.api.mapper.IEMCMapper;
import moze_intel.projecte.api.mapper.arithmetic.IValueArithmetic;
import moze_intel.projecte.api.mapper.collector.IExtendedMappingCollector;
import moze_intel.projecte.api.nss.NormalizedSimpleStack;
import moze_intel.projecte.config.MappingConfig;
import moze_intel.projecte.emc.EMCMappingHandler;
import moze_intel.projecte.emc.SimpleGraphMapper;
import moze_intel.projecte.emc.arithmetic.HiddenBigFractionArithmetic;
import moze_intel.projecte.emc.collector.LongToBigFractionCollector;
import moze_intel.projecte.shaded.org.apache.commons.math3.fraction.BigFraction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.ReloadableServerResources;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * How each GT recipe's cost is split across its outputs ({@link GtRecipeEmcMapper}, VexedPanda/Mc-incremental#58).
 *
 * <p>The split is found from a <em>seed</em>: ProjectE's own value generator, run privately on what the other enabled
 * EMC mappers add plus every GT recipe giving its whole cost to its first output (the #52 mapping, which never
 * collapses). From the seed's values:
 * <ul>
 *   <li>An output is the recipe's <em>own</em> when the recipe is what values it: the first output when the recipe
 *       gives it its seed value (or it has none), any other output when nothing else values it. Outputs valued
 *       elsewhere (byproducts with a value of their own) and pinned outputs keep their value.</li>
 *   <li>The own outputs share what's left of the cost once the other outputs are paid at their values, in proportion
 *       to their values (an own output with no value weighs the average per unit of those that have one). So the
 *       outputs together are worth the inputs. A recipe whose other outputs already take the whole cost, or with
 *       nothing to split, keeps the #52 conversion: its outputs then gain, which only re-pricing can fix.</li>
 *   <li>Except, in a recipe whose outputs valued elsewhere are worth more than its inputs: outputs valued by a GT tool
 *       craft become its own, so a cheaper machine route sets their price (the lathe's rod; #69); and so does any
 *       output worth more on its own than the recipe's whole cost (a desulfurized fuel priced by a distillery; #83).</li>
 *   <li>The shares are fixed, so they can't feed back into themselves within ProjectE's mapping. A share that would
 *       lower an output that leads back (within {@link #NEAR} of its value) to one of the recipe's inputs, or one that
 *       takes away more than {@link #NEAR} of the cost, could still pull a loop of recipes down to 0 as ProjectE
 *       walks it; those outputs get a fixed value instead (computed from the seed, then refreshed from more private
 *       passes until they settle), which nothing can lower. Every other share is a fixed fraction of the inputs, so the
 *       output follows the inputs' values.</li>
 *   <li>The passes also catch what the seed can't see (#83): a recipe split by share whose outputs valued elsewhere
 *       come to be worth more of its cost (its inputs fell) gets fixed values instead, and a recipe whose inputs only
 *       get a value after the seed is split from the first pass that values them, with fixed values.</li>
 * </ul>
 */
final class OutputShares {
    /** How close (relative) a route must be to an output's value to count as leading back, and the largest share
     *  taken away from the own outputs that is still applied as a fraction of the inputs. */
    static final double NEAR = 0.05;
    /**
     * Private passes after the seed that refresh the fixed values: at least {@link #PASSES}, then until no fixed value
     * moves by more than {@link #SETTLED} of itself (and no recipe changes how it is split), at most {@link #MAX_PASSES}.
     */
    static final int PASSES = 4;
    static final int MAX_PASSES = 16;
    static final double SETTLED = 0.0005;
    /** How much (relative to the cost) a recipe split by share may make once its shares drift, before it gets fixed values. */
    static final double DRIFT = 0.001;

    enum Kind { SEED, SPLIT, FIXED }

    private final GtRecipeEmcMapper.Recipes recipes;
    private final Kind[] kinds;
    private final boolean[][] own;
    /** {@link Kind#SPLIT}: each own output's share of the recipe's cost. */
    private final BigFraction[][] shares;
    /** {@link Kind#FIXED}: each own output's weight among the own outputs, and its fixed value per run (null: #52). */
    private final double[][] weights;
    private final double[][] values;
    /** Whether the recipe has been classified: its inputs had values in the seed, or in a later pass. */
    private final boolean[] planned;
    /** Nodes pinned at a fixed value (by other mappers, or listed fluids). */
    Set<NormalizedSimpleStack> pinned = Set.of();

    private OutputShares(GtRecipeEmcMapper.Recipes recipes) {
        this.recipes = recipes;
        int n = recipes.splits.size();
        kinds = new Kind[n];
        java.util.Arrays.fill(kinds, Kind.SEED);
        own = new boolean[n][];
        shares = new BigFraction[n][];
        weights = new double[n][];
        values = new double[n][];
        planned = new boolean[n];
    }

    /** The recipes whose cost is split across their outputs: by share ({@code fixed} false) or with fixed values. */
    Set<net.minecraft.resources.ResourceLocation> splitRecipes(boolean fixed) {
        Set<net.minecraft.resources.ResourceLocation> ids = new HashSet<>();
        for (int r = 0; r < kinds.length; r++) {
            boolean match = fixed ? kinds[r] == Kind.FIXED && values[r] != null : kinds[r] == Kind.SPLIT;
            if (match && recipes.splits.get(r).id() != null) ids.add(recipes.splits.get(r).id());
        }
        return ids;
    }

    int count(Kind kind) {
        int count = 0;
        for (Kind k : kinds) if (k == kind) count++;
        return count;
    }

    /** Finds the shares; when ProjectE's other mappers can't be read, every recipe keeps the #52 conversion. */
    static OutputShares find(GtRecipeEmcMapper self, GtRecipeEmcMapper.Recipes recipes, ReloadableServerResources serverResources,
            RegistryAccess registryAccess, ResourceManager resourceManager) {
        long start = System.nanoTime();
        OutputShares result = new OutputShares(recipes);
        RecordingCollector others = recordOtherMappers(self, serverResources, registryAccess, resourceManager);
        if (others == null) return result;
        boolean silenced = setLogFoundExploits(false);
        int passes = 0;
        try {
            Map<NormalizedSimpleStack, Double> seed = result.generateValues(others, registryAccess);
            result.plan(others, seed);
            double moved = 1;
            while (passes < MAX_PASSES && (passes < PASSES || moved > SETTLED)) {
                moved = result.refresh(result.generateValues(others, registryAccess));
                passes++;
            }
        } finally {
            if (silenced) setLogFoundExploits(MappingConfig.logExploits());
        }
        McIncrementalPrestige.LOGGER.info("GT recipe EMC mapper: found output shares in {} ms and {} passes ({} recipes "
                + "split by share, {} with fixed values, {} keep the first-output conversion)", (System.nanoTime() - start) / 1_000_000,
                passes, result.count(Kind.SPLIT), result.count(Kind.FIXED), result.count(Kind.SEED));
        return result;
    }

    /** Adds every recipe's conversions. Returns how many outputs were left out because their amounts overflow. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    int emit(IExtendedMappingCollector target) {
        IValueArithmetic arithmetic = new NeverFreeArithmetic<>((IValueArithmetic) target.getArithmetic());
        int dropped = 0;
        List<Split> splits = recipes.splits;
        for (int r = 0; r < splits.size(); r++) {
            Split split = splits.get(r);
            boolean firstOnly = kinds[r] == Kind.SEED || (kinds[r] == Kind.FIXED && values[r] == null);
            // The #52 conversion: the recipe's whole cost to its first output (never for recycling and decomposition).
            // A split recipe keeps it too when its first output is valued elsewhere (it then only matters if that other
            // route goes).
            if (split.first() && (firstOnly || !own[r][0])
                    && !GtRecipeEmcMapper.addShare(target, arithmetic, split, 0, BigFraction.ONE)) {
                dropped++;
            }
            if (firstOnly) continue;
            for (int k = 0; k < split.outputs().size(); k++) {
                if (!own[r][k]) continue;
                boolean added = kinds[r] == Kind.SPLIT
                        ? GtRecipeEmcMapper.addShare(target, arithmetic, split, k, shares[r][k])
                        : recipes.addFixed(target, arithmetic, split, k, values[r][k]);
                if (!added) dropped++;
            }
        }
        return dropped;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Map<NormalizedSimpleStack, Double> generateValues(RecordingCollector others, RegistryAccess registryAccess) {
        SimpleGraphMapper graph = new SimpleGraphMapper(new HiddenBigFractionArithmetic());
        LongToBigFractionCollector collector = new LongToBigFractionCollector(graph);
        others.replayInto(collector);
        recipes.nodes.replayInto(collector);
        emit(collector);
        collector.finishCollection(registryAccess);
        Map<NormalizedSimpleStack, BigFraction> generated = graph.generateValues();
        Map<NormalizedSimpleStack, Double> values = new HashMap<>(generated.size());
        generated.forEach((node, value) -> {
            if (value.compareTo(BigFraction.ZERO) > 0) values.put(node, value.doubleValue());
        });
        return values;
    }

    /** Classifies every recipe from the seed's values. */
    private void plan(RecordingCollector others, Map<NormalizedSimpleStack, Double> seed) {
        List<Split> splits = recipes.splits;
        pinned = recipes.pinned(others);
        double[] cost = new double[splits.size()];
        for (int r = 0; r < splits.size(); r++) {
            cost[r] = cost(splits.get(r), seed);
            own[r] = new boolean[splits.get(r).outputs().size()];
            planned[r] = cost[r] > 0;
            if (planned[r]) findOwn(r, seed, cost[r]);
        }
        Map<NormalizedSimpleStack, Integer> loops = components(others, seed);
        for (int r = 0; r < splits.size(); r++) {
            if (planned[r]) classify(r, seed, cost[r], loops);
        }
    }

    /** Which outputs the recipe values itself, at these values (the seed's, or a later pass's). */
    private void findOwn(int r, Map<NormalizedSimpleStack, Double> values, double cost) {
        Split split = recipes.splits.get(r);
        Double firstValue = values.get(split.outputs().get(0));
        boolean valuesFirst = firstValue == null || near(floorValue(cost / split.amounts().get(0).doubleValue()), firstValue, 1e-9);
        for (int k = 0; k < own[r].length; k++) {
            NormalizedSimpleStack output = split.outputs().get(k);
            own[r][k] = !pinned.contains(output) && (k == 0 && split.first() ? valuesFirst : !values.containsKey(output));
        }
        ownToolCrafted(split, own[r], values, cost);
        if (split.first()) ownLargest(split, own[r], values, cost);
    }

    /**
     * Splits the recipe's cost across its own outputs: by share, or with fixed values when a share would take away more
     * than {@link #NEAR} of the cost or an own output leads back to an input ({@code loops} null: always fixed).
     */
    private void classify(int r, Map<NormalizedSimpleStack, Double> prices, double cost, Map<NormalizedSimpleStack, Integer> loops) {
        Split split = recipes.splits.get(r);
        int n = split.outputs().size();
        double kept = 0;
        int owned = 0;
        double ownValue = 0;
        double ownAmount = 0;
        for (int k = 0; k < n; k++) {
            double amount = split.amounts().get(k).doubleValue();
            Double value = prices.get(split.outputs().get(k));
            if (!own[r][k]) {
                if (value != null) kept += amount * value;
            } else {
                owned++;
                if (value != null) {
                    ownValue += amount * value;
                    ownAmount += amount;
                }
            }
        }
        double rest = cost - kept;
        if (rest <= cost * 1e-9) {
            if (split.decomposition()) lowerAll(r, split, prices, pinned, cost);
            return;
        }
        if (owned == 0 || (owned == 1 && kept == 0 && split.first() && own[r][0])) return;
        double perUnit = ownAmount > 0 ? ownValue / ownAmount : 1;
        weights[r] = new double[n];
        double total = 0;
        for (int k = 0; k < n; k++) {
            if (!own[r][k]) continue;
            Double value = prices.get(split.outputs().get(k));
            weights[r][k] = split.amounts().get(k).doubleValue() * (value != null ? value : perUnit);
            total += weights[r][k];
        }
        for (int k = 0; k < n; k++) weights[r][k] /= total;
        if (loops == null || kept > NEAR * cost || leadsBack(loops, split, own[r])) {
            kinds[r] = Kind.FIXED;
            values[r] = fixedValues(r, rest, null);
        } else {
            kinds[r] = Kind.SPLIT;
            shares[r] = new BigFraction[n];
            for (int k = 0; k < n; k++) {
                if (own[r][k]) shares[r][k] = roundUp(rest / cost * weights[r][k]);
            }
        }
    }

    /**
     * A machine route cheaper than a GT tool craft sets the price (VexedPanda/Mc-incremental#69). When a recipe's outputs
     * valued elsewhere are worth more than its inputs, those valued by a GT tool craft (the craft gives them their seed
     * value) become its own, so they share what's left once the others are paid: the lathe turning an ingot into a rod
     * and 2 small dusts values the rod at the rest of the ingot, its EU and its time, below the file craft's whole
     * ingot. Only tool-crafted outputs are lowered: an instant craft ignores the byproducts a machine makes, while
     * lowering byproducts valued by other machine recipes carries through the economy (#58). Left as it is when the
     * outputs not valued by a tool craft alone are worth the inputs.
     */
    private void ownToolCrafted(Split split, boolean[] own, Map<NormalizedSimpleStack, Double> seed, double cost) {
        if (!(cost > 0) || recipes.toolCrafts.isEmpty()) return;
        int n = own.length;
        boolean[] crafted = new boolean[n];
        double kept = 0;
        double craftedValue = 0;
        for (int k = 0; k < n; k++) {
            if (own[k]) continue;
            NormalizedSimpleStack output = split.outputs().get(k);
            Double value = seed.get(output);
            if (value == null) continue;
            double worth = split.amounts().get(k).doubleValue() * value;
            kept += worth;
            if (!pinned.contains(output) && toolCrafted(output, value, seed)) {
                crafted[k] = true;
                craftedValue += worth;
            }
        }
        if (craftedValue <= 0 || kept <= cost * (1 + 1e-9) || kept - craftedValue >= cost * (1 - 1e-9)) return;
        for (int k = 0; k < n; k++) own[k] |= crafted[k];
    }

    /**
     * A recipe that makes its outputs for less than other routes value them sets the price of the largest ones
     * (VexedPanda/Mc-incremental#83). When the outputs valued elsewhere are still worth more than the inputs (after
     * {@link #ownToolCrafted}), the most valuable of them become its own, largest first, until the rest are worth less
     * than the inputs: desulfurizing sulfuric heavy fuel values the heavy fuel at its cost less the hydrogen sulfide,
     * below a distillery that gives the whole cost of a cracked fuel to the small part of it that is heavy fuel. Only as
     * few outputs as needed are lowered, so a cheap input's byproducts (lava's metals) keep their value unless they make
     * up the gain alone. Pinned outputs can't be lowered; decompositions are lowered in proportion instead
     * ({@link #lowerAll}).
     */
    private void ownLargest(Split split, boolean[] own, Map<NormalizedSimpleStack, Double> seed, double cost) {
        if (!(cost > 0)) return;
        int n = own.length;
        double kept = 0;
        List<Integer> lowerable = new ArrayList<>();
        double[] worth = new double[n];
        for (int k = 0; k < n; k++) {
            if (own[k]) continue;
            NormalizedSimpleStack output = split.outputs().get(k);
            Double value = seed.get(output);
            if (value == null) continue;
            worth[k] = split.amounts().get(k).doubleValue() * value;
            kept += worth[k];
            if (!pinned.contains(output)) lowerable.add(k);
        }
        if (kept <= cost * (1 + 1e-9)) return;
        List<Integer> lowered = new ArrayList<>();
        for (int k : lowerable) {
            if (worth[k] <= cost * (1 + 1e-9)) continue;
            lowered.add(k);
            kept -= worth[k];
        }
        if (lowered.isEmpty() || kept >= cost * (1 - 1e-9)) return;
        for (int k : lowered) own[k] = true;
    }

    /** Whether a GT tool craft gives the output its seed value. */
    private boolean toolCrafted(NormalizedSimpleStack output, double value, Map<NormalizedSimpleStack, Double> seed) {
        for (RecordingCollector.Row craft : recipes.toolCrafts.getOrDefault(output, List.of())) {
            double total = 0;
            boolean costed = true;
            for (Object2IntMap.Entry<NormalizedSimpleStack> entry : craft.ingredients().object2IntEntrySet()) {
                Double v = seed.get(entry.getKey());
                if (v == null) {
                    costed = false;
                    break;
                }
                total += entry.getIntValue() * v;
            }
            if (costed && near(floorValue(total / craft.outnumber()), value, 1e-9)) return true;
        }
        return false;
    }

    /**
     * A recipe whose outputs valued elsewhere are already worth more than its inputs: they are all lowered in proportion
     * to their values (as fixed values), so that together they are worth the inputs. Pinned outputs can't be lowered:
     * they take their value first, and if that alone is more than the inputs the recipe is left as it is.
     */
    private void lowerAll(int r, Split split, Map<NormalizedSimpleStack, Double> seed, Set<NormalizedSimpleStack> pinned,
            double cost) {
        int n = split.outputs().size();
        boolean[] lowered = new boolean[n];
        double[] weight = new double[n];
        double pinnedValue = 0;
        double total = 0;
        for (int k = 0; k < n; k++) {
            Double value = seed.get(split.outputs().get(k));
            if (value == null) continue;
            double worth = split.amounts().get(k).doubleValue() * value;
            if (pinned.contains(split.outputs().get(k))) {
                pinnedValue += worth;
            } else {
                lowered[k] = true;
                weight[k] = worth;
                total += worth;
            }
        }
        double left = cost - pinnedValue;
        if (total <= 0 || left <= cost * 1e-9) return;
        for (int k = 0; k < n; k++) weight[k] /= total;
        own[r] = lowered;
        weights[r] = weight;
        kinds[r] = Kind.FIXED;
        values[r] = fixedValues(r, left, null);
    }

    /**
     * Recomputes the fixed values from a pass's values, halfway from the previous ones (so they settle), and the shares
     * from the pass's values (VexedPanda/Mc-incremental#83): an output valued elsewhere keeps its value while the inputs
     * may fall below the seed's, so a share taken from the seed would leave that output a larger part of the cost.
     */
    private double refresh(Map<NormalizedSimpleStack, Double> pass) {
        List<Split> splits = recipes.splits;
        double moved = 0;
        for (int r = 0; r < splits.size(); r++) {
            Kind before = kinds[r];
            if (!planned[r]) {
                planLate(r, pass);
                if (kinds[r] != before) moved = 1;
                continue;
            }
            if (kinds[r] == Kind.SPLIT) {
                fixIfDrifted(r, splits.get(r), pass);
                if (kinds[r] != before) moved = 1;
                continue;
            }
            if (kinds[r] != Kind.FIXED) continue;
            Split split = splits.get(r);
            double cost = cost(split, pass);
            double kept = 0;
            boolean costed = cost > 0;
            for (int k = 0; k < split.outputs().size() && costed; k++) {
                if (own[r][k]) continue;
                Double value = pass.get(split.outputs().get(k));
                if (value != null) kept += split.amounts().get(k).doubleValue() * value;
            }
            double rest = cost - kept;
            double[] previous = values[r];
            values[r] = costed && rest > cost * 1e-9 ? fixedValues(r, rest, previous) : null;
            moved = Math.max(moved, moved(previous, values[r]));
        }
        return moved;
    }

    /** How far a recipe's fixed values moved, relative to their total (to at least 1 EMC: {@code 1 / SETTLED}). */
    private static double moved(double[] previous, double[] next) {
        if (previous == null || next == null) return previous == next ? 0 : 1;
        double before = 0;
        double change = 0;
        for (int k = 0; k < next.length; k++) {
            before += previous[k];
            change += Math.abs(next[k] - previous[k]);
        }
        return change / Math.max(before, 1 / SETTLED);
    }

    /**
     * A recipe whose inputs only get a value after the seed (its inputs are made by recipes the seed doesn't split) is
     * classified from the first pass that values them, always with fixed values: the loops are only known for the
     * seed.
     */
    private void planLate(int r, Map<NormalizedSimpleStack, Double> pass) {
        double cost = cost(recipes.splits.get(r), pass);
        if (!(cost > 0)) return;
        planned[r] = true;
        findOwn(r, pass, cost);
        classify(r, pass, cost, null);
    }

    /**
     * Shares are fixed fractions of the cost, but an output valued elsewhere keeps its value: when the inputs fall below
     * the seed's (or that output rises), it is worth a larger part of the cost and the recipe makes EMC
     * (VexedPanda/Mc-incremental#83). Such a recipe gets fixed values from the pass's values instead, refreshed like the
     * others. Shares themselves are never refreshed: taken from values they feed into, they pull loops down to 0.
     */
    private void fixIfDrifted(int r, Split split, Map<NormalizedSimpleStack, Double> pass) {
        double cost = cost(split, pass);
        if (!(cost > 0)) return;
        double kept = 0;
        double share = 0;
        for (int k = 0; k < split.outputs().size(); k++) {
            if (own[r][k]) {
                share += shares[r][k].doubleValue();
                continue;
            }
            Double value = pass.get(split.outputs().get(k));
            if (value != null) kept += split.amounts().get(k).doubleValue() * value;
        }
        double rest = cost - kept;
        if (share * cost <= rest + DRIFT * cost) return;
        kinds[r] = Kind.FIXED;
        values[r] = rest > cost * 1e-9 ? fixedValues(r, rest, null) : null;
    }

    private double[] fixedValues(int r, double rest, double[] previous) {
        double[] fixed = new double[weights[r].length];
        for (int k = 0; k < fixed.length; k++) {
            fixed[k] = rest * weights[r][k];
            if (previous != null) fixed[k] = (fixed[k] + previous[k]) / 2;
        }
        return fixed;
    }

    /** Whether an own output reaches one of the recipe's inputs (same strongly connected component). */
    private static boolean leadsBack(Map<NormalizedSimpleStack, Integer> components, Split split, boolean[] own) {
        Set<Integer> inputs = new HashSet<>();
        for (NormalizedSimpleStack input : split.inputs().keySet()) {
            Integer c = components.get(input);
            if (c != null) inputs.add(c);
        }
        for (int k = 0; k < own.length; k++) {
            if (!own[k]) continue;
            Integer c = components.get(split.outputs().get(k));
            if (c != null && inputs.contains(c)) return true;
        }
        return false;
    }

    /**
     * Strongly connected components of the graph whose edges go from each ingredient to the output of every conversion
     * within {@link #NEAR} of the output's seed value, plus from each GT recipe's inputs to its own outputs.
     */
    private Map<NormalizedSimpleStack, Integer> components(RecordingCollector others, Map<NormalizedSimpleStack, Double> seed) {
        Map<NormalizedSimpleStack, Set<NormalizedSimpleStack>> edges = new HashMap<>();
        List<RecordingCollector.Row> conversions = new ArrayList<>(others.conversions());
        conversions.addAll(recipes.nodes.conversions());
        for (RecordingCollector.Row row : conversions) {
            Double value = seed.get(row.output());
            if (value == null) continue;
            double total = 0;
            boolean costed = true;
            for (Object2IntMap.Entry<NormalizedSimpleStack> entry : row.ingredients().object2IntEntrySet()) {
                Double v = seed.get(entry.getKey());
                if (v == null) {
                    costed = false;
                    break;
                }
                total += entry.getIntValue() * v;
            }
            if (!costed || total <= 0 || floorValue(total / row.outnumber()) > value * (1 + NEAR)) continue;
            for (NormalizedSimpleStack ingredient : row.ingredients().keySet()) {
                edges.computeIfAbsent(ingredient, x -> new HashSet<>()).add(row.output());
            }
        }
        List<Split> splits = recipes.splits;
        for (int r = 0; r < splits.size(); r++) {
            Split split = splits.get(r);
            double cost = cost(split, seed);
            for (int k = 0; k < split.outputs().size(); k++) {
                NormalizedSimpleStack output = split.outputs().get(k);
                boolean edge = own[r][k];
                if (k == 0 && !edge && split.first() && cost > 0 && seed.containsKey(output)) {
                    // The #52 conversion of an output valued elsewhere: an edge when it's near that value.
                    edge = floorValue(cost / split.amounts().get(0).doubleValue()) <= seed.get(output) * (1 + NEAR);
                }
                if (!edge) continue;
                for (NormalizedSimpleStack input : split.inputs().keySet()) {
                    edges.computeIfAbsent(input, x -> new HashSet<>()).add(output);
                }
            }
        }
        return tarjan(edges);
    }

    /** Iterative Tarjan: each node's strongly connected component number. */
    private static Map<NormalizedSimpleStack, Integer> tarjan(Map<NormalizedSimpleStack, Set<NormalizedSimpleStack>> edges) {
        Map<NormalizedSimpleStack, Integer> index = new HashMap<>();
        Map<NormalizedSimpleStack, Integer> low = new HashMap<>();
        Map<NormalizedSimpleStack, Integer> component = new HashMap<>();
        ArrayDeque<NormalizedSimpleStack> stack = new ArrayDeque<>();
        Set<NormalizedSimpleStack> onStack = new HashSet<>();
        int next = 0;
        int components = 0;
        for (NormalizedSimpleStack root : edges.keySet()) {
            if (index.containsKey(root)) continue;
            ArrayDeque<Map.Entry<NormalizedSimpleStack, Iterator<NormalizedSimpleStack>>> work = new ArrayDeque<>();
            index.put(root, next);
            low.put(root, next++);
            stack.push(root);
            onStack.add(root);
            work.push(Map.entry(root, edges.getOrDefault(root, Set.of()).iterator()));
            while (!work.isEmpty()) {
                var top = work.peek();
                NormalizedSimpleStack v = top.getKey();
                Iterator<NormalizedSimpleStack> it = top.getValue();
                if (it.hasNext()) {
                    NormalizedSimpleStack w = it.next();
                    if (!index.containsKey(w)) {
                        index.put(w, next);
                        low.put(w, next++);
                        stack.push(w);
                        onStack.add(w);
                        work.push(Map.entry(w, edges.getOrDefault(w, Set.of()).iterator()));
                    } else if (onStack.contains(w)) {
                        low.put(v, Math.min(low.get(v), index.get(w)));
                    }
                    continue;
                }
                work.pop();
                if (!work.isEmpty()) {
                    NormalizedSimpleStack parent = work.peek().getKey();
                    low.put(parent, Math.min(low.get(parent), low.get(v)));
                }
                if (low.get(v).equals(index.get(v))) {
                    NormalizedSimpleStack w;
                    do {
                        w = stack.pop();
                        onStack.remove(w);
                        component.put(w, components);
                    } while (w != v);
                    components++;
                }
            }
        }
        return component;
    }

    /** The recipe's consumed inputs' value per run; NaN when one has no value. */
    private static double cost(Split split, Map<NormalizedSimpleStack, Double> values) {
        double total = 0;
        for (var input : split.inputs().entrySet()) {
            Double value = values.get(input.getKey());
            if (value == null) return Double.NaN;
            total += input.getValue().doubleValue() * value;
        }
        return total;
    }

    /** ProjectE's rounding of a conversion's value: down to a whole number when at least 1. */
    private static double floorValue(double value) {
        return value >= 1 ? Math.floor(value + 1e-9) : value;
    }

    private static boolean near(double a, double b, double relative) {
        return Math.abs(a - b) <= relative * Math.max(1, Math.max(Math.abs(a), Math.abs(b)));
    }

    /** A share rounded up to millionths. */
    private static BigFraction roundUp(double share) {
        long millionths = Math.max(1, (long) Math.ceil(share * GtRecipeEmcMapper.SHARE_DENOMINATOR - 1e-6));
        return new BigFraction(millionths, GtRecipeEmcMapper.SHARE_DENOMINATOR);
    }

    /**
     * What every other enabled EMC mapper adds (ProjectE runs them in this order, ending with its tag mapper); null,
     * so every recipe keeps the #52 conversion, when ProjectE's mapper list can't be read.
     */
    @SuppressWarnings("unchecked")
    private static RecordingCollector recordOtherMappers(GtRecipeEmcMapper self, ReloadableServerResources serverResources,
            RegistryAccess registryAccess, ResourceManager resourceManager) {
        List<IEMCMapper<NormalizedSimpleStack, Long>> mappers;
        try {
            Field field = EMCMappingHandler.class.getDeclaredField("mappers");
            field.setAccessible(true);
            mappers = new ArrayList<>((List<IEMCMapper<NormalizedSimpleStack, Long>>) field.get(null));
        } catch (ReflectiveOperationException | RuntimeException e) {
            McIncrementalPrestige.LOGGER.error("GT recipe EMC mapper: can't read ProjectE's EMC mappers, so each GT recipe "
                    + "gives its whole cost to its first output", e);
            return null;
        }
        RecordingCollector recorded = new RecordingCollector();
        for (IEMCMapper<NormalizedSimpleStack, Long> mapper : mappers) {
            if (mapper == self || mapper instanceof GtRecipeEmcMapper || !MappingConfig.isEnabled(mapper)) continue;
            try {
                mapper.addMappings(recorded, serverResources, registryAccess, resourceManager);
            } catch (Exception e) {
                McIncrementalPrestige.LOGGER.warn("GT recipe EMC mapper: EMC mapper {} failed while finding output shares",
                        mapper.getName(), e);
            }
        }
        return recorded;
    }

    /** ProjectE logs exploits on every value generation; its private passes here must not. True when it was set. */
    private static boolean setLogFoundExploits(boolean log) {
        try {
            Method method = SimpleGraphMapper.class.getDeclaredMethod("setLogFoundExploits", boolean.class);
            method.setAccessible(true);
            method.invoke(null, log);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            McIncrementalPrestige.LOGGER.warn("GT recipe EMC mapper: can't silence ProjectE's exploit log for its private "
                    + "passes; exploits may be logged more than once", e);
            return false;
        }
    }
}
