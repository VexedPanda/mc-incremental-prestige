package io.github.vexedpanda.mcincremental.prestige.sweep.gt;

import javax.annotation.Nullable;

import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.trait.recipe.RecipeLogic;
import com.gregtechceu.gtceu.api.recipe.ConsumedInputsData;

import io.github.vexedpanda.mcincremental.prestige.sweep.EmcCredit;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.common.crafting.SizedIngredient;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;

/**
 * The inputs a GregTech machine has consumed for the recipe it is still running (#30). GT takes a recipe's inputs out
 * of the machine's slots and tanks when the recipe starts and records them in the machine's {@link RecipeLogic}
 * ({@link RecipeLogic#getConsumedInputs()}); a multiblock records them on its controller. No NeoForge capability
 * exposes them, so the Prestige sweep reads them here. GregTech is optional: only call this when it is installed
 * ({@code ModList.isLoaded("gtceu")}), and keep every GT type inside this package.
 */
public final class GtRecipesInProgress {
    private GtRecipesInProgress() {}

    /**
     * Adds the inputs consumed by every unfinished recipe of the GT machine {@code blockEntity} to {@code credit}; does
     * nothing for any other block entity. Call it only for a machine the sweep removes: a machine left in place would
     * finish the recipe and make its outputs.
     */
    public static void addConsumedInputs(@Nullable BlockEntity blockEntity, EmcCredit credit) {
        if (!(blockEntity instanceof MetaMachine machine)) return;
        for (RecipeLogic logic : machine.getTraits(RecipeLogic.class)) {
            if (!isUnfinished(logic)) continue;
            ConsumedInputsData consumed = logic.getConsumedInputs();
            for (SizedIngredient input : consumed.getConsumedInputs(ItemRecipeCapability.CAP)) {
                // GT records each consumed stack as a one-stack ingredient (count 1) holding the stack it took.
                ItemStack[] stacks = input.ingredient().getItems();
                if (stacks.length > 0) credit.addItem(stacks[0].copyWithCount(stacks[0].getCount() * input.count()));
            }
            for (SizedFluidIngredient input : consumed.getConsumedInputs(FluidRecipeCapability.CAP)) {
                FluidStack[] fluids = input.getFluids();
                if (fluids.length > 0) credit.addFluid(fluids[0].getFluid(), input.amount());
            }
        }
    }

    /**
     * Whether the logic is part way through a recipe: it has one and isn't idle (working, waiting for power or space, or
     * paused). GT clears its record of consumed inputs only when the next recipe starts, so after a recipe finishes
     * (idle, or suspended with no recipe) the record is stale: those inputs already became the outputs.
     */
    private static boolean isUnfinished(RecipeLogic logic) {
        return logic.getLastRecipe() != null && !logic.isIdle();
    }
}
