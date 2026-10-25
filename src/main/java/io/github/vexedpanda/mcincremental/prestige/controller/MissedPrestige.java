package io.github.vexedpanda.mcincremental.prestige.controller;

import com.mojang.serialization.Codec;

import io.github.vexedpanda.mcincremental.prestige.McIncrementalPrestige;
import io.github.vexedpanda.mcincremental.prestige.run.RunArea;
import io.github.vexedpanda.mcincremental.prestige.run.RunAreas;
import io.github.vexedpanda.mcincremental.prestige.run.StartingItems;
import io.github.vexedpanda.mcincremental.prestige.sweep.Refund;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.server.TickTask;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * A Prestige only reaches the players online. Every player remembers the last Run they took part in; one who joins
 * having missed a Prestige catches up as if they had been there: their inventory and ender chest are refunded to the
 * world's EMC balance at full value and wiped, they get the starting items and are moved into the current Run area.
 * Server thread only.
 */
@EventBusSubscriber(modid = McIncrementalPrestige.MODID)
public final class MissedPrestige {
    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, McIncrementalPrestige.MODID);

    /**
     * The {@link RunAreas#runNumber Run number} of the last Run the player took part in. Saved with the player and
     * kept through death. Absent for a player who has never joined.
     */
    private static final DeferredHolder<AttachmentType<?>, AttachmentType<Integer>> LAST_RUN = ATTACHMENTS.register(
            "last_run", () -> AttachmentType.builder(() -> 0).serialize(Codec.INT).copyOnDeath().build());

    private MissedPrestige() {}

    public static void register(IEventBus modEventBus) {
        ATTACHMENTS.register(modEventBus);
    }

    /** Records that the player is taking part in the world's current Run. */
    static void joinCurrentRun(ServerPlayer player) {
        player.setData(LAST_RUN, RunAreas.runNumber(player.getServer()));
    }

    /**
     * On join, before the starting-items top-up (which would otherwise be refunded): a player who missed a Prestige
     * catches up; a player joining for the first time joins the current Run as they are.
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    static void onPlayerJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof ServerPlayer player)
                || player instanceof FakePlayer) return;
        var server = player.getServer();
        RunArea area = RunAreas.current(server).orElse(null);
        if (area == null) return;
        if (player.hasData(LAST_RUN) && player.getData(LAST_RUN) < RunAreas.runNumber(server)) {
            catchUp(player, area);
        }
        joinCurrentRun(player);
    }

    private static void catchUp(ServerPlayer player, RunArea area) {
        Refund refund = new Refund();
        refund.addAndClear(player.getInventory());
        refund.addAndClear(player.getEnderChestInventory());
        refund.credit(player.getServer());
        Prestige.wipeInventory(player);
        StartingItems.give(player);
        if (player.level().dimension() == Level.OVERWORLD) {
            RunAreas.teleport(player, area);
        } else {
            // Changing dimension while the player is being added to a level is unsafe: wait a tick.
            var server = player.getServer();
            server.tell(new TickTask(server.getTickCount() + 1, () -> {
                if (!player.isRemoved()) RunAreas.teleport(player, area);
            }));
        }
        McIncrementalPrestige.LOGGER.info("{} missed a Prestige: refunded {} and moved to the Run area around {}",
                player.getScoreboardName(), refund, area.centre());
    }
}
