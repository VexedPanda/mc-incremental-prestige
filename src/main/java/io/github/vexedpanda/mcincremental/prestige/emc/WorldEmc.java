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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
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
}
