package io.github.vexedpanda.mcincremental.prestige.emc;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.vexedpanda.mcincremental.prestige.McIncrementalPrestige;
import moze_intel.projecte.api.ItemInfo;
import moze_intel.projecte.api.capabilities.IKnowledgeProvider;
import moze_intel.projecte.api.capabilities.PECapabilities;
import moze_intel.projecte.api.event.PlayerKnowledgeChangeEvent;
import moze_intel.projecte.api.proxy.IEMCProxy;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * The world's EMC balance and Knowledge (ADR-0004: nothing has an owner). This is the source of truth; every player's
 * ProjectE data is a mirror of it, overwritten when the player joins and updated on every change. Changes players
 * make through ProjectE itself (a Transmutation Table, ProjectE commands) are read back into the world every tick and
 * before every call here, then mirrored to everyone. Changes made with no player online are saved with the world.
 *
 * <p>Call these instead of a player's {@code IKnowledgeProvider}. Server thread only.
 */
@EventBusSubscriber(modid = McIncrementalPrestige.MODID)
public final class WorldEmc {
    private WorldEmc() {}

    /** The world's EMC balance. */
    public static BigInteger balance(MinecraftServer server) {
        return capture(server).balance;
    }

    /** Credits the world's balance. {@code amount} must not be negative. */
    public static void addEmc(MinecraftServer server, BigInteger amount) {
        if (amount.signum() < 0) throw new IllegalArgumentException("Negative EMC: " + amount);
        WorldEmcData data = capture(server);
        if (amount.signum() == 0) return;
        setBalance(data, data.balance.add(amount));
    }

    public static void addEmc(MinecraftServer server, long amount) {
        addEmc(server, BigInteger.valueOf(amount));
    }

    /**
     * Debits the world's balance if it holds at least {@code amount}; otherwise changes nothing.
     *
     * @return whether the EMC was removed
     */
    public static boolean removeEmc(MinecraftServer server, BigInteger amount) {
        if (amount.signum() < 0) throw new IllegalArgumentException("Negative EMC: " + amount);
        WorldEmcData data = capture(server);
        if (data.balance.compareTo(amount) < 0) return false;
        if (amount.signum() > 0) setBalance(data, data.balance.subtract(amount));
        return true;
    }

    public static boolean removeEmc(MinecraftServer server, long amount) {
        return removeEmc(server, BigInteger.valueOf(amount));
    }

    /**
     * Adds a resource to the world's Knowledge. Like ProjectE, it keeps only the item's persistent components.
     *
     * @return whether it was newly learned
     */
    public static boolean learn(MinecraftServer server, ItemInfo item) {
        WorldEmcData data = capture(server);
        ItemInfo info = IEMCProxy.INSTANCE.getPersistentInfo(item);
        if (!data.knowledge.add(info)) return false;
        data.setDirty();
        pushKnowledge(data, List.of(info), List.of());
        return true;
    }

    public static boolean learn(MinecraftServer server, ItemStack stack) {
        return learn(server, ItemInfo.fromStack(stack));
    }

    /** Whether the world's Knowledge holds this resource. */
    public static boolean knows(MinecraftServer server, ItemInfo item) {
        return capture(server).knowledge.contains(IEMCProxy.INSTANCE.getPersistentInfo(item));
    }

    public static boolean knows(MinecraftServer server, ItemStack stack) {
        return knows(server, ItemInfo.fromStack(stack));
    }

    /** A copy of the world's Knowledge. */
    public static Set<ItemInfo> knowledge(MinecraftServer server) {
        return Set.copyOf(capture(server).knowledge);
    }

    // ---- Mirroring ----

    @SubscribeEvent
    static void onPlayerJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof ServerPlayer player)
                || player instanceof FakePlayer) return;
        // Read back first: a player changing dimension may have unread changes.
        WorldEmcData data = capture(player.getServer());
        mirrorAll(data, player);
    }

    /** Reads back a leaving player's changes from this tick before the player is gone. */
    @SubscribeEvent
    static void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && !(player instanceof FakePlayer)) capture(player.getServer());
    }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        capture(event.getServer());
    }

    @SubscribeEvent
    static void onPlayerKnowledgeChange(PlayerKnowledgeChangeEvent event) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) WorldEmcData.get(server).knowledgeChanged.add(event.getPlayerUUID());
    }

    /**
     * Reads changes players made through ProjectE back into the world, then mirrors them to everyone. Every mirrored
     * player held the world's values after the last change, so any difference was made by that player.
     */
    private static WorldEmcData capture(MinecraftServer server) {
        WorldEmcData data = WorldEmcData.get(server);
        BigInteger delta = BigInteger.ZERO;
        Set<ItemInfo> learned = new HashSet<>();
        Set<ItemInfo> forgotten = new HashSet<>();
        // Players who left (logout, death, dimension change) are mirrored again when they rejoin a level.
        data.mirrored.removeIf(ServerPlayer::isRemoved);
        for (ServerPlayer player : List.copyOf(data.mirrored)) {
            IKnowledgeProvider k = knowledge(player);
            if (k == null) continue;
            delta = delta.add(k.getEmc().subtract(data.balance));
            if (data.knowledgeChanged.contains(player.getUUID())) {
                Set<ItemInfo> theirs = k.getKnowledge();
                for (ItemInfo info : theirs) if (!data.knowledge.contains(info)) learned.add(info);
                for (ItemInfo info : data.knowledge) if (!theirs.contains(info)) forgotten.add(info);
            }
        }
        data.knowledgeChanged.clear();
        if (delta.signum() != 0) setBalance(data, data.balance.add(delta).max(BigInteger.ZERO));
        if (!learned.isEmpty() || !forgotten.isEmpty()) {
            data.knowledge.addAll(learned);
            data.knowledge.removeAll(forgotten);
            data.setDirty();
            pushKnowledge(data, learned, forgotten);
        }
        return data;
    }

    private static void setBalance(WorldEmcData data, BigInteger balance) {
        data.balance = balance;
        data.setDirty();
        for (ServerPlayer player : List.copyOf(data.mirrored)) {
            IKnowledgeProvider k = knowledge(player);
            if (k == null || k.getEmc().equals(balance)) continue;
            k.setEmc(balance);
            sync(player, () -> k.syncEmc(player));
        }
    }

    private static void pushKnowledge(WorldEmcData data, Iterable<ItemInfo> learned, Iterable<ItemInfo> forgotten) {
        for (ServerPlayer player : List.copyOf(data.mirrored)) {
            IKnowledgeProvider k = knowledge(player);
            if (k == null) continue;
            for (ItemInfo info : learned) {
                if (k.addKnowledge(info)) sync(player, () -> k.syncKnowledgeChange(player, info, true));
            }
            for (ItemInfo info : forgotten) {
                if (k.removeKnowledge(info)) sync(player, () -> k.syncKnowledgeChange(player, info, false));
            }
        }
    }

    /** Overwrites a joining player's ProjectE EMC and Knowledge with the world's. */
    private static void mirrorAll(WorldEmcData data, ServerPlayer player) {
        IKnowledgeProvider k = knowledge(player);
        if (k == null) return;
        k.setEmc(data.balance);
        for (ItemInfo info : new ArrayList<>(k.getKnowledge())) {
            if (!data.knowledge.contains(info)) k.removeKnowledge(info);
        }
        for (ItemInfo info : data.knowledge) k.addKnowledge(info);
        data.mirrored.add(player);
        sync(player, () -> k.sync(player));
    }

    private static IKnowledgeProvider knowledge(ServerPlayer player) {
        return player.getCapability(PECapabilities.KNOWLEDGE_CAPABILITY);
    }

    /**
     * ProjectE's sync packets throw for a connection that never negotiated ProjectE's channels (GameTest players);
     * the data is already set, so there is nothing to send to such a player anyway.
     */
    private static void sync(ServerPlayer player, Runnable send) {
        try {
            send.run();
        } catch (UnsupportedOperationException e) {
            McIncrementalPrestige.LOGGER.debug("Can't sync ProjectE data to {}: {}", player.getScoreboardName(), e.getMessage());
        }
    }

    // ---- Fluids (#36) ----
    // Knowledge also holds fluids, kept beside the items as source fluid ids. ProjectE has no fluid Knowledge, so these
    // are not mirrored to players. An mB of fluid is usually worth a fraction of an EMC: the fraction is banked as the
    // world's fractional EMC (thousandths, 0 to 999) so that fluid in and out is exact.

    /** Thousandths of an EMC banked beside the whole balance (0 to 999), left over from fluid transfers. */
    public static long fractionalMilliEmc(MinecraftServer server) {
        return capture(server).fractionalMilliEmc;
    }

    /** Credits the world with the EMC value of {@code mB} of {@code fluid} ({@link FluidEmc}), exactly. */
    public static void addFluidEmc(MinecraftServer server, Fluid fluid, long mB) {
        if (mB < 0) throw new IllegalArgumentException("Negative amount: " + mB);
        WorldEmcData data = capture(server);
        BigInteger[] whole = FluidEmc.milliValue(fluid, mB).add(BigInteger.valueOf(data.fractionalMilliEmc)).divideAndRemainder(MILLI);
        setFractional(data, whole[1].longValueExact());
        addEmc(server, whole[0]);
    }

    /**
     * Debits the world with the EMC value of {@code mB} of {@code fluid} if the balance (with its fractional EMC) holds
     * it; otherwise changes nothing.
     *
     * @return whether the EMC was removed
     */
    public static boolean removeFluidEmc(MinecraftServer server, Fluid fluid, long mB) {
        if (mB < 0) throw new IllegalArgumentException("Negative amount: " + mB);
        WorldEmcData data = capture(server);
        BigInteger available = data.balance.multiply(MILLI).add(BigInteger.valueOf(data.fractionalMilliEmc));
        BigInteger left = available.subtract(FluidEmc.milliValue(fluid, mB));
        if (left.signum() < 0) return false;
        BigInteger[] whole = left.divideAndRemainder(MILLI);
        setFractional(data, whole[1].longValueExact());
        if (!whole[0].equals(data.balance)) setBalance(data, whole[0]);
        return true;
    }

    /** How many mB of {@code fluid} the world's balance pays for; 0 when it has no value. */
    public static long affordableMb(MinecraftServer server, Fluid fluid) {
        long value = FluidEmc.valuePerBucket(fluid);
        if (value <= 0) return 0;
        WorldEmcData data = capture(server);
        BigInteger mB = data.balance.multiply(MILLI).add(BigInteger.valueOf(data.fractionalMilliEmc)).divide(BigInteger.valueOf(value));
        return mB.min(BigInteger.valueOf(Long.MAX_VALUE)).longValue();
    }

    /** Adds a fluid (its source) to the world's Knowledge. @return whether it was newly learned */
    public static boolean learnFluid(MinecraftServer server, Fluid fluid) {
        WorldEmcData data = capture(server);
        if (!data.fluids.add(fluidId(fluid))) return false;
        data.setDirty();
        return true;
    }

    /** Removes a fluid from the world's Knowledge. @return whether it was known */
    public static boolean forgetFluid(MinecraftServer server, Fluid fluid) {
        WorldEmcData data = capture(server);
        if (!data.fluids.remove(fluidId(fluid))) return false;
        data.setDirty();
        return true;
    }

    /** Whether the world's Knowledge holds this fluid (or its source). */
    public static boolean knowsFluid(MinecraftServer server, Fluid fluid) {
        return capture(server).fluids.contains(fluidId(fluid));
    }

    /** A copy of the fluids in the world's Knowledge, as source fluid ids (including any no longer installed). */
    public static Set<ResourceLocation> knownFluids(MinecraftServer server) {
        return Set.copyOf(capture(server).fluids);
    }

    private static final BigInteger MILLI = BigInteger.valueOf(1000);

    private static ResourceLocation fluidId(Fluid fluid) {
        return BuiltInRegistries.FLUID.getKey(FluidEmc.source(fluid));
    }

    private static void setFractional(WorldEmcData data, long milli) {
        if (data.fractionalMilliEmc == milli) return;
        data.fractionalMilliEmc = milli;
        data.setDirty();
    }
}
