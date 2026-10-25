package io.github.vexedpanda.mcincremental.prestige.run;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.github.vexedpanda.mcincremental.prestige.Config;
import io.github.vexedpanda.mcincremental.prestige.McIncrementalPrestige;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * Starting items ({@link Config#STARTING_ITEMS}): every player is given them at the start of every Run, and on every
 * join (first join, rejoin, respawn, dimension change) gets any they don't hold. Duplicates don't matter: the Pack
 * values the Transmutation Tablet at 0 EMC. Server thread only.
 */
@EventBusSubscriber(modid = McIncrementalPrestige.MODID)
public final class StartingItems {
    private StartingItems() {}

    /** Gives the player one of each starting item. For the start of a Run, after the inventory wipe. */
    public static void give(ServerPlayer player) {
        for (Item item : configured()) player.getInventory().placeItemBackInInventory(new ItemStack(item));
    }

    /** Gives the starting items the player doesn't hold anywhere in their inventory, armour or offhand. */
    @SubscribeEvent
    static void onPlayerJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof ServerPlayer player)
                || player instanceof FakePlayer) return;
        for (Item item : configured()) {
            if (!player.getInventory().contains(new ItemStack(item))) {
                player.getInventory().placeItemBackInInventory(new ItemStack(item));
            }
        }
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
