package io.github.vexedpanda.mcincremental.prestige.preview.gt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import com.gregtechceu.gtceu.api.data.chemical.ChemicalHelper;
import com.gregtechceu.gtceu.api.data.chemical.material.Material;
import com.gregtechceu.gtceu.api.data.tag.TagPrefix;
import com.gregtechceu.gtceu.api.data.worldgen.GTOreDefinition;
import com.gregtechceu.gtceu.api.data.worldgen.generator.VeinGenerator;
import com.gregtechceu.gtceu.api.item.MetaMachineItem;
import com.gregtechceu.gtceu.api.machine.MachineDefinition;
import com.gregtechceu.gtceu.api.registry.GTRegistries;

import io.github.vexedpanda.mcincremental.prestige.preview.NewOres;
import moze_intel.projecte.api.ItemInfo;
import moze_intel.projecte.api.proxy.IEMCProxy;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.ItemAbilities;

/**
 * {@link NewOres} for GT ores: maps a GT material to its stone ore block, what mining that block drops and the
 * pickaxes and miners that harvest it. GregTech is optional: only call this when it is installed.
 */
public final class GtNewOres {
    private GtNewOres() {}

    public static NewOres of(ServerLevel level, Set<ItemInfo> knowledge) {
        Set<Item> known = new HashSet<>();
        List<ItemStack> pickaxes = new ArrayList<>();
        boolean miner = false;
        for (ItemInfo info : knowledge) {
            known.add(info.getItem().value());
            ItemStack stack = info.createStack();
            if (stack.is(ItemTags.PICKAXES) || stack.canPerformAction(ItemAbilities.PICKAXE_DIG)) pickaxes.add(stack);
            if (stack.getItem() instanceof MetaMachineItem machine && isMiner(machine.getDefinition())) miner = true;
        }
        boolean anyMiner = miner;
        Map<ResourceLocation, Boolean> cache = new HashMap<>();
        Predicate<ResourceLocation> isNew = id -> cache.computeIfAbsent(id,
                material -> isNew(level, GTRegistries.MATERIALS.get(material), known, pickaxes, anyMiner));
        boolean anyLeft = false;
        for (GTOreDefinition vein : level.registryAccess().registryOrThrow(GTRegistries.Keys.ORE_VEIN)) {
            VeinGenerator generator = vein.veinGenerator();
            if (generator == null || !vein.canGenerate() || !vein.dimensionFilter().contains(level.dimension())) continue;
            for (Material material : generator.getAllMaterials()) {
                if (isNew.test(material.getResourceLocation())) {
                    anyLeft = true;
                    break;
                }
            }
            if (anyLeft) break;
        }
        return new NewOres(isNew, anyLeft);
    }

    private static boolean isNew(ServerLevel level, Material material, Set<Item> known,
            List<ItemStack> pickaxes, boolean miner) {
        if (material == null) return false;
        Block ore = stoneOre(material);
        if (ore == null) return false;
        BlockState state = ore.defaultBlockState();
        // Lacks it: neither its drops nor any of its ore blocks (silk touch) are known.
        List<ItemStack> drops = Block.getDrops(state, level, BlockPos.ZERO, null, null, new ItemStack(Items.NETHERITE_PICKAXE));
        boolean resource = false;
        for (ItemStack drop : drops) {
            if (drop.isEmpty() || !IEMCProxy.INSTANCE.hasValue(drop)) continue;
            resource = true;
            if (known.contains(drop.getItem())) return false;
        }
        if (!resource) return false;
        for (TagPrefix prefix : TagPrefix.ORES.keySet()) {
            Block block = ChemicalHelper.getBlock(prefix, material);
            if (block != null && block != Blocks.AIR && known.contains(block.asItem())) return false;
        }
        // Can harvest it: a pickaxe of its tier, or a miner.
        return miner || pickaxes.stream().anyMatch(pickaxe -> pickaxe.isCorrectToolForDrops(state));
    }

    /** The material's ore block in stone (e.g. {@code gtceu:gold_ore}), or null if it has none. */
    private static Block stoneOre(Material material) {
        for (Map.Entry<TagPrefix, TagPrefix.OreType> type : TagPrefix.ORES.entrySet()) {
            if (!type.getValue().stoneType().get().is(Blocks.STONE)) continue;
            Block block = ChemicalHelper.getBlock(type.getKey(), material);
            if (block != null && block != Blocks.AIR) return block;
        }
        return null;
    }

    /**
     * GT's miners (steam, electric and large: {@code gtceu:*_miner}), which mine any ore whatever its harvest level.
     * Not the bedrock ore miner, which harvests no ore block. By id, as GT's machine entries are Registrate types.
     */
    private static boolean isMiner(MachineDefinition definition) {
        ResourceLocation id = definition.getId();
        return id.getNamespace().equals("gtceu") && id.getPath().endsWith("_miner") && !id.getPath().contains("bedrock");
    }
}
