package io.github.vexedpanda.mcincremental.prestige.emc.gt;

import java.util.ArrayList;
import java.util.List;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import moze_intel.projecte.api.mapper.arithmetic.IValueArithmetic;
import moze_intel.projecte.api.mapper.collector.IExtendedMappingCollector;
import moze_intel.projecte.api.nss.NormalizedSimpleStack;
import moze_intel.projecte.emc.arithmetic.HiddenBigFractionArithmetic;
import net.minecraft.core.HolderLookup;

/**
 * A mapping collector that only records what it is given, so the same mappings can be replayed into several of
 * ProjectE's graphs ({@link #replayInto}) and inspected ({@link #conversions}). Conversions with no arithmetic of their
 * own are replayed with the target's.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
final class RecordingCollector implements IExtendedMappingCollector<NormalizedSimpleStack, Long, IValueArithmetic<?>> {
    /** A recorded mapping: a conversion ({@code value == null}) or a value set before or after inheriting. */
    record Row(Kind kind, NormalizedSimpleStack output, int outnumber, Object2IntMap<NormalizedSimpleStack> ingredients,
            IValueArithmetic<?> arithmetic, Long value) {
    }

    enum Kind { CONVERSION, FROM_CONVERSION, BEFORE, AFTER }

    /** ProjectE's arithmetic for EMC values (fractions, floored when at least 1), as its own mapping uses. */
    private final IValueArithmetic<?> arithmetic = new HiddenBigFractionArithmetic();
    private final List<Row> rows = new ArrayList<>();

    void replayInto(IExtendedMappingCollector target) {
        for (Row row : rows) {
            switch (row.kind()) {
                case CONVERSION -> {
                    if (row.arithmetic() == null) target.addConversion(row.outnumber(), row.output(), row.ingredients());
                    else target.addConversion(row.outnumber(), row.output(), row.ingredients(), row.arithmetic());
                }
                case FROM_CONVERSION -> target.setValueFromConversion(row.outnumber(), row.output(), row.ingredients());
                case BEFORE -> target.setValueBefore(row.output(), row.value());
                case AFTER -> target.setValueAfter(row.output(), row.value());
            }
        }
    }

    /**
     * Adds every pinned node to {@code into}: given a value before inheriting, or set from one conversion (ProjectE's
     * defaults pin tags that way), which no other conversion can lower.
     */
    void pinned(java.util.Set<NormalizedSimpleStack> into) {
        for (Row row : rows) if (row.kind() == Kind.BEFORE || row.kind() == Kind.FROM_CONVERSION) into.add(row.output());
    }

    /** Every recorded conversion (including those that set a value from a conversion). */
    List<Row> conversions() {
        return rows.stream().filter(row -> row.kind() == Kind.CONVERSION || row.kind() == Kind.FROM_CONVERSION).toList();
    }

    @Override
    public void addConversion(int outnumber, NormalizedSimpleStack output, Object2IntMap<NormalizedSimpleStack> ingredients,
            IValueArithmetic<?> arithmeticForConversion) {
        rows.add(new Row(Kind.CONVERSION, output, outnumber, new Object2IntOpenHashMap<>(ingredients),
                arithmeticForConversion == arithmetic ? null : arithmeticForConversion, null));
    }

    @Override
    public void addConversion(int outnumber, NormalizedSimpleStack output, Iterable<NormalizedSimpleStack> ingredients,
            IValueArithmetic<?> arithmeticForConversion) {
        addConversion(outnumber, output, counts(ingredients), arithmeticForConversion);
    }

    @Override
    public void setValueBefore(NormalizedSimpleStack something, Long value) {
        rows.add(new Row(Kind.BEFORE, something, 0, null, null, value));
    }

    @Override
    public void setValueAfter(NormalizedSimpleStack something, Long value) {
        rows.add(new Row(Kind.AFTER, something, 0, null, null, value));
    }

    @Override
    public void setValueFromConversion(int outnumber, NormalizedSimpleStack something, Iterable<NormalizedSimpleStack> ingredients) {
        setValueFromConversion(outnumber, something, counts(ingredients));
    }

    @Override
    public void setValueFromConversion(int outnumber, NormalizedSimpleStack something, Object2IntMap<NormalizedSimpleStack> ingredients) {
        rows.add(new Row(Kind.FROM_CONVERSION, something, outnumber, new Object2IntOpenHashMap<>(ingredients), null, null));
    }

    @Override
    public void finishCollection(HolderLookup.Provider registries) {
    }

    @Override
    public IValueArithmetic<?> getArithmetic() {
        return arithmetic;
    }

    private static Object2IntMap<NormalizedSimpleStack> counts(Iterable<NormalizedSimpleStack> ingredients) {
        Object2IntMap<NormalizedSimpleStack> counts = new Object2IntOpenHashMap<>();
        for (NormalizedSimpleStack ingredient : ingredients) counts.mergeInt(ingredient, 1, Integer::sum);
        return counts;
    }
}
