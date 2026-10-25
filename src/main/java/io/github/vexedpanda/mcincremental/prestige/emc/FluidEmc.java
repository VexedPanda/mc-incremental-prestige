package io.github.vexedpanda.mcincremental.prestige.emc;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.TreeMap;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.github.vexedpanda.mcincremental.prestige.McIncrementalPrestige;
import moze_intel.projecte.api.ItemInfo;
import moze_intel.projecte.api.proxy.IEMCProxy;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;

/**
 * Fluid EMC values (pack {@code docs/pocs/poc-1.md}: "Fluid EMC is explicit"). ProjectE can't hold fluid values, so
 * they come from datapack files {@code <namespace>:fluid_emc/<name>.json}:
 *
 * <pre>{"comment": "...", "emc_per_1000_mb": {"minecraft:lava": 1500}}</pre>
 *
 * keyed by source fluid id, value per 1000 mB. Every file in every namespace is merged (files sorted by id; a fluid in
 * two files takes the later file's value, with a warning); a file of the same id in a higher datapack replaces a lower
 * one, as usual. Values are never derived from buckets. Reloaded with datapacks ({@code /reload}). Server thread only.
 *
 * <p>An unlisted fluid is worth what ProjectE derives for it from the recipes that make it, if any: with GregTech
 * installed, the GT recipe EMC mapper (#52) values a molten metal from the fluid extractor recipe that melts its ingot,
 * and publishes that value in ProjectE's EMC map under {@link #derivedValueKey}, so the barrel and the Prestige sweep
 * use the same value the recipes cost it at. ProjectE rounds it down to a whole EMC per 1000 mB, so a tank can differ
 * from the exact recipe cost by under 1 EMC per 1000 mB. An unlisted fluid with no derived value is worth 0.
 *
 * <p>An mB of a fluid is usually worth a fraction of an EMC; {@link #milliValue} gives exact values in thousandths of
 * an EMC, which {@link WorldEmc#addFluidEmc}/{@link WorldEmc#removeFluidEmc} bank without loss.
 */
@EventBusSubscriber(modid = McIncrementalPrestige.MODID)
public final class FluidEmc {
    /** Datapack directory the values are read from. */
    public static final String DIRECTORY = "fluid_emc";
    private static final String VALUES_KEY = "emc_per_1000_mb";
    /** Custom data key naming the fluid in a {@link #derivedValueKey}. */
    private static final String DERIVED_VALUE_TAG = McIncrementalPrestige.MODID + ":derived_fluid_emc";

    /** EMC per 1000 mB by source fluid id; ids of fluids that aren't installed are kept, and simply never match. */
    private static volatile Map<ResourceLocation, Long> values = Map.of();

    private FluidEmc() {}

    /**
     * EMC value of 1000 mB of {@code fluid} (flowing fluids count as their source): its listed value, else the value
     * ProjectE derived for it from recipes, else 0.
     */
    public static long valuePerBucket(Fluid fluid) {
        Fluid source = source(fluid);
        if (source == Fluids.EMPTY) return 0;
        Long listed = values.get(BuiltInRegistries.FLUID.getKey(source));
        return listed != null ? listed : IEMCProxy.INSTANCE.getValue(derivedValueKey(source));
    }

    /**
     * EMC value of 1000 mB of {@code fluid} (flowing fluids count as their source) if a datapack lists it, which
     * tells a fluid listed at 0 (free, like water) from an unlisted one; empty when unlisted, even if a value was
     * derived for it. EMC mappers use this, never {@link #valuePerBucket}, so a derived value isn't fed back in.
     */
    public static OptionalLong listedValuePerBucket(Fluid fluid) {
        Fluid source = source(fluid);
        Long value = source == Fluids.EMPTY ? null : values.get(BuiltInRegistries.FLUID.getKey(source));
        return value == null ? OptionalLong.empty() : OptionalLong.of(value);
    }

    /** Whether {@code fluid} has a (non-zero) EMC value. */
    public static boolean hasValue(Fluid fluid) {
        return valuePerBucket(fluid) > 0;
    }

    /** Exact EMC value of {@code mB} millibuckets of {@code fluid}, in thousandths of an EMC ({@code mB × value}). */
    public static BigInteger milliValue(Fluid fluid, long mB) {
        return BigInteger.valueOf(valuePerBucket(fluid)).multiply(BigInteger.valueOf(mB));
    }

    /**
     * The key ProjectE's EMC map holds an unlisted fluid's derived value under (EMC per 1000 mB of its source): ProjectE
     * keeps only item values, so the value is carried by a barrier item tagged with the fluid's id in its custom data.
     * Barriers have no EMC of their own, so the key is never confused with a real item's value. An EMC mapper deriving
     * a fluid's value adds the conversion {@code 1 key = 1000 mB of the fluid}.
     */
    public static ItemInfo derivedValueKey(Fluid fluid) {
        CompoundTag tag = new CompoundTag();
        tag.putString(DERIVED_VALUE_TAG, BuiltInRegistries.FLUID.getKey(source(fluid)).toString());
        return ItemInfo.fromItem(Items.BARRIER, DataComponentPatch.builder().set(DataComponents.CUSTOM_DATA, CustomData.of(tag)).build());
    }

    /** The fluid that EMC values and Knowledge are keyed by: the source of a flowing fluid, else itself. */
    public static Fluid source(Fluid fluid) {
        return fluid instanceof FlowingFluid flowing ? flowing.getSource() : fluid;
    }

    @SubscribeEvent
    static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new Loader());
    }

    private static final class Loader extends SimpleJsonResourceReloadListener {
        Loader() {
            super(new Gson(), DIRECTORY);
        }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager, ProfilerFiller profiler) {
            Map<ResourceLocation, Long> merged = new HashMap<>();
            for (var file : new TreeMap<>(files).entrySet()) {
                try {
                    JsonObject entries = GsonHelper.getAsJsonObject(GsonHelper.convertToJsonObject(file.getValue(), "file"), VALUES_KEY);
                    for (var entry : entries.entrySet()) {
                        ResourceLocation fluid = ResourceLocation.tryParse(entry.getKey());
                        long value = GsonHelper.convertToLong(entry.getValue(), entry.getKey());
                        if (fluid == null || value < 0) {
                            McIncrementalPrestige.LOGGER.warn("Fluid EMC {}: skipping bad entry {} = {}", file.getKey(), entry.getKey(), entry.getValue());
                            continue;
                        }
                        Long old = merged.put(fluid, value);
                        if (old != null && old != value) {
                            McIncrementalPrestige.LOGGER.warn("Fluid EMC {}: {} = {} overrides {}", file.getKey(), fluid, value, old);
                        }
                    }
                } catch (RuntimeException e) {
                    McIncrementalPrestige.LOGGER.error("Fluid EMC {}: can't read file: {}", file.getKey(), e.getMessage());
                }
            }
            for (ResourceLocation fluid : merged.keySet()) {
                if (!BuiltInRegistries.FLUID.containsKey(fluid)) {
                    McIncrementalPrestige.LOGGER.debug("Fluid EMC: {} is not an installed fluid", fluid);
                }
            }
            values = Map.copyOf(merged);
            McIncrementalPrestige.LOGGER.info("Loaded EMC values for {} fluids", values.size());
        }
    }
}
