package io.github.vexedpanda.mcincremental.prestige.run;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.mojang.serialization.Codec;

import io.github.vexedpanda.mcincremental.prestige.Config;
import io.github.vexedpanda.mcincremental.prestige.McIncrementalPrestige;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
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
 * Starting items ({@link Config#STARTING_ITEMS}): every player is given them on first joining a world and again at
 * the start of every Run. Server thread only.
 */
@EventBusSubscriber(modid = McIncrementalPrestige.MODID)
public final class StartingItems {
    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, McIncrementalPrestige.MODID);

    /** Whether the player has had their first-join starting items. Saved with the player and kept through death. */
    private static final DeferredHolder<AttachmentType<?>, AttachmentType<Boolean>> RECEIVED = ATTACHMENTS.register(
            "received_starting_items",
            () -> AttachmentType.builder(() -> false).serialize(Codec.BOOL).copyOnDeath().build());

    private StartingItems() {}

    public static void register(IEventBus modEventBus) {
        ATTACHMENTS.register(modEventBus);
    }

    /** Gives the player one of each starting item. For the start of a Run, after the inventory wipe. */
    public static void give(ServerPlayer player) {
        for (Item item : configured()) player.getInventory().placeItemBackInInventory(new ItemStack(item));
        player.setData(RECEIVED, true);
    }

    /**
     * First join: gives the starting items the player doesn't already hold. Later joins (rejoin, respawn, dimension
     * change) give nothing.
     */
    @SubscribeEvent
    static void onPlayerJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof ServerPlayer player)
                || player instanceof FakePlayer || player.getData(RECEIVED)) return;
        for (Item item : configured()) {
            if (!player.getInventory().contains(new ItemStack(item))) {
                player.getInventory().placeItemBackInInventory(new ItemStack(item));
            }
        }
        player.setData(RECEIVED, true);
    }

    private static List<Item> configured() {
        List<Item> items = new ArrayList<>();
        for (String id : Config.STARTING_ITEMS.get()) {
            Optional<Item> item = Optional.ofNullable(ResourceLocation.tryParse(id))
                    .flatMap(BuiltInRegistries.ITEM::getOptional);
            if (item.isPresent()) items.add(item.get());
            else McIncrementalPrestige.LOGGER.warn("Unknown starting item {}, skipping it", id);
        }
        return items;
    }
}
