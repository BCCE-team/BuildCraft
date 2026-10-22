package buildcraft.compat.jei;

import java.util.Optional;

import javax.annotation.Nullable;

import buildcraft.factory.BCFactoryGuis;
import buildcraft.factory.container.ContainerAutoCraftItems;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
//? if >=1.21.11 {
/*import mezz.jei.api.recipe.types.IRecipeType;*/
//? } else {
import mezz.jei.api.recipe.RecipeType;
//? }
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;

final class AutoWorkbenchRecipeTransferHandler
        implements IRecipeTransferHandler<ContainerAutoCraftItems, RecipeHolder<CraftingRecipe>> {
    @Override
    public Class<? extends ContainerAutoCraftItems> getContainerClass() {
        return ContainerAutoCraftItems.class;
    }

    @Override
    public Optional<MenuType<ContainerAutoCraftItems>> getMenuType() {
        return Optional.of(BCFactoryGuis.MENU_AUTOWORK_BENCH_ITEM.get());
    }

    @Override
    //? if >=1.21.11 {
    /*public IRecipeType<RecipeHolder<CraftingRecipe>> getRecipeType() {*/
    //? } else {
    public RecipeType<RecipeHolder<CraftingRecipe>> getRecipeType() {
    //? }
        return RecipeTypes.CRAFTING;
    }

    @Nullable
    @Override
    public IRecipeTransferError transferRecipe(ContainerAutoCraftItems container, RecipeHolder<CraftingRecipe> recipe,
            IRecipeSlotsView recipeSlots, Player player, boolean maxTransfer, boolean doTransfer) {
        if (doTransfer) {
            container.sendSetPhantomSlots(container.blueprintInv,
                    CraftingPhantomTransfer.getCraftingGrid(recipeSlots));
        }
        return null;
    }
}
