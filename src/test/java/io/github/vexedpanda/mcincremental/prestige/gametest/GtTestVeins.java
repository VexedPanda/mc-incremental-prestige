package io.github.vexedpanda.mcincremental.prestige.gametest;

import java.util.ArrayList;
import java.util.List;

import com.gregtechceu.gtceu.api.block.OreBlock;
import com.gregtechceu.gtceu.api.data.worldgen.GTOreDefinition;
import com.gregtechceu.gtceu.api.data.worldgen.ores.GeneratedVeinMetadata;
import com.gregtechceu.gtceu.api.registry.GTRegistries;
import com.gregtechceu.gtceu.config.ConfigHolder;
import com.gregtechceu.gtceu.integration.map.cache.server.ServerCache;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Places GT ore veins for Candidate area tests. The GameTest world is superflat, so GT generates no veins: a vein is
 * placed as GT's worldgen would, its ore blocks plus GT's record of the vein.
 */
final class GtTestVeins {
    private GtTestVeins() {}

    static ResourceLocation gt(String path) {
        return ResourceLocation.fromNamespaceAndPath("gtceu", path);
    }

    /**
     * Places a vein of type {@code vein} centred in chunk {@code centre}: one stone ore block of each of
     * {@code materials} (GT material names, e.g. {@code gold}) in a row near the bottom of the chunk, and GT's record
     * of the vein. @return what puts the chunk back as it was
     */
    static Runnable place(GameTestHelper helper, ServerLevel level, ChunkPos centre, ResourceKey<GTOreDefinition> vein,
            String... materials) {
        level.getChunk(centre.x, centre.z);
        BlockPos base = new BlockPos(centre.getMinBlockX() + 3, level.getMinBuildHeight() + 6, centre.getMinBlockZ() + 3);
        List<BlockPos> placed = new ArrayList<>();
        List<BlockState> before = new ArrayList<>();
        for (int i = 0; i < materials.length; i++) {
            BlockPos pos = base.east(i);
            placed.add(pos);
            before.add(level.getBlockState(pos));
            Block ore = BuiltInRegistries.BLOCK.get(gt(materials[i] + "_ore"));
            helper.assertTrue(ore instanceof OreBlock, "gtceu:" + materials[i] + "_ore should be a GT ore block, got " + ore);
            level.setBlockAndUpdate(pos, ore.defaultBlockState());
        }
        Holder<GTOreDefinition> definition = level.registryAccess().registryOrThrow(GTRegistries.Keys.ORE_VEIN).getHolderOrThrow(vein);
        int grid = ConfigHolder.INSTANCE.worldgen.oreVeins.oreVeinGridSize;
        ServerCache.instance.addVein(level.dimension(), Math.floorDiv(centre.x, grid), Math.floorDiv(centre.z, grid),
                new GeneratedVeinMetadata(centre, centre.getMiddleBlockPosition(base.getY()), definition));
        return () -> {
            for (int i = 0; i < placed.size(); i++) level.setBlockAndUpdate(placed.get(i), before.get(i));
            ServerCache.instance.removeAllInChunk(level.dimension(), centre);
        };
    }
}
