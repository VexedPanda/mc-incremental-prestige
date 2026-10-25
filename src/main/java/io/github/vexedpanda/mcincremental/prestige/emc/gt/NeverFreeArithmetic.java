package io.github.vexedpanda.mcincremental.prestige.emc.gt;

import moze_intel.projecte.api.mapper.arithmetic.IValueArithmetic;

/**
 * ProjectE's arithmetic, except that a conversion never makes its output free. ProjectE treats a conversion whose
 * ingredients are all free, or whose negative ingredients (byproducts) are worth at least its positive ones, as making
 * its output free (worth nothing, and left out of the cost of everything made from it). For a GT recipe that would
 * let one recipe with valuable byproducts (or none but non-consumed inputs) wipe out the value of its output and of
 * everything downstream, so such a conversion yields 0 instead, which ProjectE ignores as "no valid value".
 */
final class NeverFreeArithmetic<T extends Comparable<T>> implements IValueArithmetic<T> {
    private final IValueArithmetic<T> inner;

    NeverFreeArithmetic(IValueArithmetic<T> inner) {
        this.inner = inner;
    }

    @Override
    public T getFree() {
        return inner.getZero();
    }

    @Override
    public boolean isFree(T value) {
        return inner.isFree(value);
    }

    @Override
    public boolean isZero(T value) {
        return inner.isZero(value);
    }

    @Override
    public boolean isLessThanZero(T value) {
        return inner.isLessThanZero(value);
    }

    @Override
    public boolean isLessThanEqualZero(T value) {
        return inner.isLessThanEqualZero(value);
    }

    @Override
    public boolean isGreaterThanZero(T value) {
        return inner.isGreaterThanZero(value);
    }

    @Override
    public boolean isGreaterThanEqualZero(T value) {
        return inner.isGreaterThanEqualZero(value);
    }

    @Override
    public T getZero() {
        return inner.getZero();
    }

    @Override
    public T add(T a, T b) {
        return inner.add(a, b);
    }

    @Override
    public T mul(long a, T b) {
        return inner.mul(a, b);
    }

    @Override
    public T div(T a, long b) {
        return inner.div(a, b);
    }
}
