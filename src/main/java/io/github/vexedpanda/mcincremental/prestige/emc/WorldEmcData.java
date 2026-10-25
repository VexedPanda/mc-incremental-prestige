package io.github.vexedpanda.mcincremental.prestige.emc;

import java.math.BigInteger;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

import io.github.vexedpanda.mcincremental.prestige.McIncrementalPrestige;
import moze_intel.projecte.api.ItemInfo;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * The world's EMC balance and Knowledge, saved with the overworld. {@link WorldEmc} is the API; this class only
 * holds and persists the values, plus which live players currently mirror them (not saved).
 */
final class WorldEmcData extends SavedData {
    private static final String NAME = McIncrementalPrestige.MODID + "_world_emc";
    private static final Factory<WorldEmcData> FACTORY = new Factory<>(WorldEmcData::new, WorldEmcData::load, null);

    BigInteger balance = BigInteger.ZERO;
    final Set<ItemInfo> knowledge = new LinkedHashSet<>();
    /** Fluids in the world's Knowledge, as source fluid ids (#36). */
    final Set<ResourceLocation> fluids = new LinkedHashSet<>();
    /** Thousandths of an EMC beside {@link #balance}, 0 to 999, left over from fluid transfers (#36). */
    long fractionalMilliEmc;

    /** Players whose ProjectE data holds the world's values; only these are read back for changes. */
    final Set<ServerPlayer> mirrored = Collections.newSetFromMap(new WeakHashMap<>());
    /** Players whose ProjectE Knowledge changed since it was last compared with the world's. */
    final Set<UUID> knowledgeChanged = new HashSet<>();

    static WorldEmcData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putString("balance", balance.toString());
        ItemInfo.CODEC.listOf()
                .encodeStart(RegistryOps.create(NbtOps.INSTANCE, registries), List.copyOf(knowledge))
                .resultOrPartial(e -> McIncrementalPrestige.LOGGER.error("Can't save world Knowledge: {}", e))
                .ifPresent(list -> tag.put("knowledge", list));
        ListTag fluidList = new ListTag();
        for (ResourceLocation fluid : fluids) fluidList.add(StringTag.valueOf(fluid.toString()));
        tag.put("fluids", fluidList);
        tag.putLong("fractional_milli_emc", fractionalMilliEmc);
        return tag;
    }

    private static WorldEmcData load(CompoundTag tag, HolderLookup.Provider registries) {
        WorldEmcData data = new WorldEmcData();
        try {
            data.balance = new BigInteger(tag.getString("balance"));
        } catch (NumberFormatException e) {
            McIncrementalPrestige.LOGGER.error("Bad world EMC balance '{}', using 0", tag.getString("balance"));
        }
        Tag list = tag.get("knowledge");
        if (list != null) {
            ItemInfo.CODEC.listOf()
                    .parse(RegistryOps.create(NbtOps.INSTANCE, registries), list)
                    .resultOrPartial(e -> McIncrementalPrestige.LOGGER.error("Can't load world Knowledge: {}", e))
                    .ifPresent(data.knowledge::addAll);
        }
        for (Tag fluid : tag.getList("fluids", Tag.TAG_STRING)) {
            ResourceLocation id = ResourceLocation.tryParse(fluid.getAsString());
            if (id != null) data.fluids.add(id);
        }
        data.fractionalMilliEmc = Math.floorMod(tag.getLong("fractional_milli_emc"), 1000L);
        return data;
    }
}
