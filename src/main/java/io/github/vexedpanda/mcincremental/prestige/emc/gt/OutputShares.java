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
 *   <li>The shares are fixed, so they can't feed back into themselves within ProjectE's mapping. A share that would
 *       lower an output that leads back (within {@link #NEAR} of its value) to one of the recipe's inputs, or one that
 *       takes away more than {@link #NEAR} of the cost, could still pull a loop of recipes down to 0 as ProjectE
 *       walks it; those outputs get a fixed value instead (computed from the seed, then refreshed from {@link #PASSES}
 *       more private passes), which nothing can lower. Every other share is a fixed fraction of the inputs, so the
 *       output follows the inputs' values.</li>
 * </ul>
 */
final class OutputShares {
    /** How close (relative) a route must be to an output's value to count as leading back, and the largest share
     *  taken away from the own outputs that is still applied as a fraction of the inputs. */
    static final double NEAR = 0.05;
    /** Private passes after the seed that refresh the fixed values. */
    static final int PASSES = 4;

    enum Kind { SEED, SPLIT, FIXED }

    private final GtRecipeEmcMapper.Recipes recipes;
    private final Kind[] kinds;
    private final boolean[][] own;
    /** {@link Kind#SPLIT}: each own output's share of the recipe's cost. */
    private final BigFraction[][] shares;
    /** {@link Kind#FIXED}: each own output's weight among the own outputs, and its fixed value per run (null: #52). */
    private final double[][] weights;
    private final double[][] values;
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
    }

    /** The recipes whose cost is split across their outputs: by share ({@code fixed} false) or with fixed values. */
    Set<net.minecraft.resources.ResourceLocation> splitRecipes(boolean fixed) {
        Set<net.minecraft.resources.ResourceLocation> ids = new HashSet<>();
        for (int r = 0; r < kinds.length; r++) {
            boolean match = fixed ? kinds[r] == Kind.FIXED && values[r] != null : kinds[r] == Kind.SPLIT;
            if (match) ids.add(recipes.splits.get(r).id());
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
        try {
            Map<NormalizedSimpleStack, Double> seed = result.generateValues(others, registryAccess);
            result.plan(others, seed);
            for (int pass = 0; pass < PASSES; pass++) {
                result.refresh(result.generateValues(others, registryAccess));
            }
        } finally {
            if (silenced) setLogFoundExploits(MappingConfig.logExploits());
        }
        McIncrementalPrestige.LOGGER.info("GT recipe EMC mapper: found output shares in {} ms ({} recipes split by "
                + "share, {} with fixed values, {} keep the first-output conversion)", (System.nanoTime() - start) / 1_000_000,
                result.count(Kind.SPLIT), result.count(Kind.FIXED), result.count(Kind.SEED));
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
            Split split = splits.get(r);
            cost[r] = cost(split, seed);
            int n = split.outputs().size();
            own[r] = new boolean[n];
            if (!(cost[r] > 0)) continue;
            NormalizedSimpleStack first = split.outputs().get(0);
            Double firstValue = seed.get(first);
            boolean valuesFirst = firstValue == null || near(floorValue(cost[r] / split.amounts().get(0).doubleValue()), firstValue, 1e-9);
            for (int k = 0; k < n; k++) {
                NormalizedSimpleStack output = split.outputs().get(k);
                own[r][k] = !pinned.contains(output) && (k == 0 && split.first() ? valuesFirst : !seed.containsKey(output));
            }
        }
        Map<NormalizedSimpleStack, Integer> loops = components(others, seed);
        for (int r = 0; r < splits.size(); r++) {
            Split split = splits.get(r);
            int n = split.outputs().size();
            if (!(cost[r] > 0)) continue;
            double kept = 0;
            int owned = 0;
            double ownValue = 0;
            double ownAmount = 0;
            for (int k = 0; k < n; k++) {
                double amount = split.amounts().get(k).doubleValue();
                Double value = seed.get(split.outputs().get(k));
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
            double rest = cost[r] - kept;
            if (rest <= cost[r] * 1e-9) {
                if (split.decomposition()) lowerAll(r, split, seed, pinned, cost[r]);
                continue;
            }
            if (owned == 0 || (owned == 1 && kept == 0 && split.first() && own[r][0])) continue;
            double perUnit = ownAmount > 0 ? ownValue / ownAmount : 1;
            weights[r] = new double[n];
            double total = 0;
            for (int k = 0; k < n; k++) {
                if (!own[r][k]) continue;
                Double value = seed.get(split.outputs().get(k));
                weights[r][k] = split.amounts().get(k).doubleValue() * (value != null ? value : perUnit);
                total += weights[r][k];
            }
            for (int k = 0; k < n; k++) weights[r][k] /= total;
            if (kept > NEAR * cost[r] || leadsBack(loops, split, own[r])) {
                kinds[r] = Kind.FIXED;
                values[r] = fixedValues(r, rest, null);
            } else {
                kinds[r] = Kind.SPLIT;
                shares[r] = new BigFraction[n];
                for (int k = 0; k < n; k++) {
                    if (own[r][k]) shares[r][k] = roundUp(rest / cost[r] * weights[r][k]);
                }
            }
        }
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

    /** Recomputes the fixed values from a pass's values, halfway from the previous ones (so they settle). */
    private void refresh(Map<NormalizedSimpleStack, Double> pass) {
        List<Split> splits = recipes.splits;
        for (int r = 0; r < splits.size(); r++) {
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
            values[r] = costed && rest > cost * 1e-9 ? fixedValues(r, rest, values[r]) : null;
        }
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
