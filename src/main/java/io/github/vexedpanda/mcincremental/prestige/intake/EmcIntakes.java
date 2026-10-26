package io.github.vexedpanda.mcincremental.prestige.intake;

import io.github.vexedpanda.mcincremental.prestige.McIncrementalPrestige;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Registers the EMC intake ({@code mcincremental_prestige:emc_intake}): block, item, block entity and capabilities. */
public final class EmcIntakes {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(McIncrementalPrestige.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(McIncrementalPrestige.MODID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, McIncrementalPrestige.MODID);

    public static final DeferredBlock<EmcIntakeBlock> BLOCK = BLOCKS.register("emc_intake",
            () -> new EmcIntakeBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.BARREL)));
    public static final DeferredItem<BlockItem> ITEM = ITEMS.registerSimpleBlockItem(BLOCK, new Item.Properties());
    @SuppressWarnings("DataFlowIssue") // the type's datafixer may be null
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<EmcIntakeBlockEntity>> BLOCK_ENTITY =
            BLOCK_ENTITIES.register("emc_intake",
                    () -> BlockEntityType.Builder.of(EmcIntakeBlockEntity::new, BLOCK.get()).build(null));

    private EmcIntakes() {}

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        modBus.addListener(EmcIntakes::registerCapabilities);
        modBus.addListener(EmcIntakes::addToCreativeTab);
    }

    private static void registerCapabilities(RegisterCapabilitiesEvent event) {
        // Same handler on every side, and for a null side (a block-wide query).
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, BLOCK_ENTITY.get(), (intake, side) -> intake.itemHandler());
        event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, BLOCK_ENTITY.get(), (intake, side) -> intake.fluidHandler());
    }

    private static void addToCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) event.accept(ITEM);
    }
}
