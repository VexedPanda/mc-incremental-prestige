package io.github.vexedpanda.mcincremental.prestige.controller;

import io.github.vexedpanda.mcincremental.prestige.McIncrementalPrestige;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** The Prestige controller's items. */
public final class PrestigeItems {
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(McIncrementalPrestige.MODID);

    /** {@code mcincremental_prestige:prestige}. Its recipe is a datapack recipe, so the Pack can change it. */
    public static final DeferredItem<PrestigeItem> PRESTIGE = ITEMS.registerItem("prestige", PrestigeItem::new, new Item.Properties());

    private PrestigeItems() {}

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
        modEventBus.addListener(PrestigeItems::addToCreativeTabs);
    }

    private static void addToCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) event.accept(PRESTIGE);
    }
}
