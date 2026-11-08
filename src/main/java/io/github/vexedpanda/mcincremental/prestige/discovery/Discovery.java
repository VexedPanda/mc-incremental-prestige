package io.github.vexedpanda.mcincremental.prestige.discovery;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.github.vexedpanda.mcincremental.prestige.McIncrementalPrestige;
import io.github.vexedpanda.mcincremental.prestige.barrel.EmcBarrelBlockEntity;
import io.github.vexedpanda.mcincremental.prestige.emc.FluidEmc;
import io.github.vexedpanda.mcincremental.prestige.emc.WorldEmc;
import io.github.vexedpanda.mcincremental.prestige.intake.EmcIntakeBlockEntity;
import io.github.vexedpanda.mcincremental.prestige.run.RunArea;
import io.github.vexedpanda.mcincremental.prestige.run.RunAreas;
import moze_intel.projecte.api.proxy.IEMCProxy;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.TradeWithVillagerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Discovery (pack ADR-0009, mod {@code docs/discovery.md}): a resource with EMC enters the world's Knowledge as soon as
 * anything in the world produces it or a player holds it, and every player is told. Resources with no EMC (water) are
 * never discovered. Triggered by items entering a player's inventory (pickup, crafting, smelting, trading), the EMC
 * intake, the Prestige sweep, and a {@linkplain #scan periodic scan} of the current Run area. Server thread only.
 */
@EventBusSubscriber(modid = McIncrementalPrestige.MODID)
public final class Discovery {
    /** How often the Run area and every player's inventory are scanned: every 30 s. */
    public static final int SCAN_INTERVAL_TICKS = 30 * 20;
    public static final String DISCOVERED_KEY = "mcincremental_prestige.discovery.discovered";

    private Discovery() {}

    /** Discovers an item if it has EMC and the world doesn't know it yet. @return whether it was newly discovered */
    public static boolean discover(MinecraftServer server, ItemStack stack) {
        if (stack.isEmpty() || !IEMCProxy.INSTANCE.hasValue(stack) || !WorldEmc.learn(server, stack)) return false;
        announce(server, stack.getHoverName());
        return true;
    }

    /** Discovers a fluid (its source) if it has EMC and the world doesn't know it yet. @return whether it was new */
    public static boolean discoverFluid(MinecraftServer server, Fluid fluid) {
        if (!FluidEmc.hasValue(fluid) || !WorldEmc.learnFluid(server, fluid)) return false;
        announce(server, FluidEmc.source(fluid).getFluidType().getDescription());
        return true;
    }

    /** Discovers every item and fluid given (the Prestige sweep's credit). */
    public static void discoverAll(MinecraftServer server, Iterable<ItemStack> items, Iterable<Fluid> fluids) {
        for (ItemStack stack : items) discover(server, stack);
        for (Fluid fluid : fluids) discoverFluid(server, fluid);
    }

    /**
     * The periodic scan: discovers what every online player holds, and the contents of every item and fluid handler of
     * the block entities in the current Run area's loaded chunks. Bounded: only the Run area's own chunks (no sweep
     * margin), only those already loaded (none is loaded or generated), and only reads (nothing is extracted).
     */
    public static void scan(MinecraftServer server) {
        for (ServerPlayer player : players(server)) discoverAll(server, player.getInventory());
        Optional<RunArea> current = RunAreas.current(server);
        if (current.isEmpty()) return;
        RunArea area = current.get();
        ServerLevel level = server.overworld();
        ChunkPos centre = area.centre();
        int radius = area.radiusChunks();
        for (int cx = centre.x - radius; cx <= centre.x + radius; cx++) {
            for (int cz = centre.z - radius; cz <= centre.z + radius; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                for (BlockEntity blockEntity : List.copyOf(chunk.getBlockEntities().values())) scan(server, level, blockEntity);
            }
        }
    }

    private static void scan(MinecraftServer server, ServerLevel level, BlockEntity blockEntity) {
        // The barrel's and intake's handlers are the world balance itself; reading an unrolled loot container rolls it.
        if (blockEntity instanceof EmcBarrelBlockEntity || blockEntity instanceof EmcIntakeBlockEntity) return;
        if (blockEntity instanceof RandomizableContainerBlockEntity loot && loot.getLootTable() != null) return;
        for (IItemHandler items : handlers(level, blockEntity, Capabilities.ItemHandler.BLOCK)) {
            for (int slot = 0; slot < items.getSlots(); slot++) discover(server, items.getStackInSlot(slot));
        }
        for (IFluidHandler tanks : handlers(level, blockEntity, Capabilities.FluidHandler.BLOCK)) {
            for (int tank = 0; tank < tanks.getTanks(); tank++) {
                FluidStack fluid = tanks.getFluidInTank(tank);
                if (!fluid.isEmpty()) discoverFluid(server, fluid.getFluid());
            }
        }
    }

    /** The block's unsided handler, or, if it has none, each side's (each distinct handler once). */
    private static <T> List<T> handlers(ServerLevel level, BlockEntity blockEntity,
                                        BlockCapability<T, Direction> capability) {
        List<T> found = new ArrayList<>();
        T unsided = level.getCapability(capability, blockEntity.getBlockPos(), blockEntity.getBlockState(), blockEntity, null);
        if (unsided != null) return List.of(unsided);
        for (Direction side : Direction.values()) {
            T handler = level.getCapability(capability, blockEntity.getBlockPos(), blockEntity.getBlockState(), blockEntity, side);
            if (handler != null && found.stream().noneMatch(h -> h == handler)) found.add(handler);
        }
        return found;
    }

    private static void discoverAll(MinecraftServer server, Container container) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) discover(server, container.getItem(slot));
    }

    private static void announce(MinecraftServer server, Component name) {
        Component message = Component.translatable(DISCOVERED_KEY, name);
        McIncrementalPrestige.LOGGER.info("Discovered {}", name.getString());
        for (ServerPlayer player : players(server)) player.sendSystemMessage(message);
    }

    /** Every player in any level, including GameTest players that aren't in the player list. */
    private static List<ServerPlayer> players(MinecraftServer server) {
        List<ServerPlayer> players = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (ServerPlayer player : level.players()) {
                if (!(player instanceof FakePlayer) && !player.isRemoved()) players.add(player);
            }
        }
        return players;
    }

    // ---- Triggers ----

    @SubscribeEvent
    static void onPickup(ItemEntityPickupEvent.Post event) {
        if (event.getPlayer() instanceof ServerPlayer player) discover(player.getServer(), event.getOriginalStack());
    }

    @SubscribeEvent
    static void onCraft(PlayerEvent.ItemCraftedEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) discover(player.getServer(), event.getCrafting());
    }

    @SubscribeEvent
    static void onSmelt(PlayerEvent.ItemSmeltedEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) discover(player.getServer(), event.getSmelting());
    }

    @SubscribeEvent
    static void onTrade(TradeWithVillagerEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) discover(player.getServer(), event.getMerchantOffer().getResult());
    }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % SCAN_INTERVAL_TICKS == 0) scan(event.getServer());
    }
}
