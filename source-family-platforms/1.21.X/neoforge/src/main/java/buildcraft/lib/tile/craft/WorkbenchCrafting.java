//? source if >=1.21.11
/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.lib.tile.craft;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import buildcraft.lib.inventory.filter.ArrayStackFilter;
import buildcraft.lib.misc.InventoryUtil;
import buildcraft.lib.misc.ItemStackKey;
import buildcraft.lib.tile.TileBC_Neptune;
import buildcraft.lib.tile.item.ItemHandlerSimple;
import it.unimi.dsi.fastutil.objects.Object2IntArrayMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.items.IItemHandler;
import buildcraft.lib.compat.NbtCompat;

/**
 * 1.21.11 implementation of BuildCraft's phantom-blueprint workbench.
 *
 * <p>Uses the BuildCraft workbench gameplay model with the 1.21.11 recipe APIs, including
 * recipe discovery and crafting for Auto Workbenches and the Advanced Crafting Table.</p>
 */
public class WorkbenchCrafting extends TransientCraftingContainer {
    public static final AbstractContainerMenu CONTAINER_EVENT_HANDLER = new AbstractContainerMenu(null, -1) {
        public ItemStack quickMoveStack(Player player, int index) {
            return ItemStack.EMPTY;
        }

        public boolean stillValid(Player player) {
            return false;
        }
    };

    private final BlockEntity tile;
    private final ItemHandlerSimple invBlueprint;
    private final ItemHandlerSimple invMaterials;
    private final ItemHandlerSimple invResult;
    private final ItemHandlerSimple invAssumedResult = new ItemHandlerSimple(1);

    private boolean isBlueprintDirty = true;
    private boolean areMaterialsDirty = true;
    private boolean cachedHasRequirements = false;

    private final List<CraftingRecipe> matchingRecipes = new ArrayList<>();
    private final List<Identifier> matchingRecipeIds = new ArrayList<>();
    @Nullable
    private Identifier selectedRecipeId;
    private int selectedRecipeIndex = -1;

    private final int blueprintSize;
    private final int materialsSize;
    private final int resultSize;
    private final int craftTableSize;
    private final int width;
    private final int height;

    @Nullable
    private CraftingRecipe currentRecipe;
    private ItemStack assumedResult = ItemStack.EMPTY;

    public WorkbenchCrafting(int width, int height, TileBC_Neptune tile, ItemHandlerSimple invBlueprint,
        ItemHandlerSimple invMaterials, ItemHandlerSimple invResult) {
        super(CONTAINER_EVENT_HANDLER, width, height);
        this.tile = tile;
        this.invBlueprint = invBlueprint;
        this.blueprintSize = invBlueprint.getSlots();
        this.materialsSize = invMaterials.getSlots();
        this.resultSize = invResult.getSlots();
        this.craftTableSize = width * height;
        if (invBlueprint.getSlots() < craftTableSize) {
            throw new IllegalArgumentException("Passed blueprint has a smaller size than width * height! (expected "
                + craftTableSize + ", got " + invBlueprint.getSlots() + ")");
        }
        this.invMaterials = invMaterials;
        this.invResult = invResult;
        this.width = width;
        this.height = height;
    }

    public ItemStack getItem(int index) {
        if (index < craftTableSize) {
            return isBlueprintDirty ? invBlueprint.getStackInSlot(index) : super.getItem(index);
        }
        int[] result = new int[1];
        ItemHandlerSimple inv = pickInv(index, result);
        if (inv != null) {
            return inv.getStackInSlot(result[0]);
        }
        if (index == craftTableSize + blueprintSize + materialsSize + resultSize) {
            return invAssumedResult.getStackInSlot(0);
        }
        return ItemStack.EMPTY;
    }

    public void setItem(int index, ItemStack item) {
        if (index < craftTableSize) {
            super.setItem(index, item);
            return;
        }
        int[] result = new int[1];
        ItemHandlerSimple inv = pickInv(index, result);
        if (inv != null) {
            inv.setStackInSlot(result[0], item);
        }
    }

    private ItemHandlerSimple pickInv(int index, int[] result) {
        if (index < craftTableSize + blueprintSize) {
            result[0] = index - craftTableSize;
            return invBlueprint;
        }
        if (index < craftTableSize + blueprintSize + materialsSize) {
            result[0] = index - craftTableSize - blueprintSize;
            return invMaterials;
        }
        if (index < craftTableSize + blueprintSize + materialsSize + resultSize) {
            result[0] = index - craftTableSize - blueprintSize - materialsSize;
            return invResult;
        }
        return null;
    }

    public int getContainerSize() {
        return craftTableSize;
    }

    public int getSlotSize() {
        return blueprintSize + materialsSize + resultSize;
    }

    public ItemStack removeItemNoUpdate(int index) {
        if (index < craftTableSize) {
            return super.removeItemNoUpdate(index);
        }
        int[] result = new int[1];
        ItemHandlerSimple inv = pickInv(index, result);
        return inv == null ? ItemStack.EMPTY : ContainerHelper.takeItem(inv.stacks, result[0]);
    }

    public ItemStack removeItem(int index, int count) {
        if (index < craftTableSize) {
            return super.removeItem(index, count);
        }
        int[] result = new int[1];
        ItemHandlerSimple inv = pickInv(index, result);
        return inv == null ? ItemStack.EMPTY : ContainerHelper.removeItem(inv.stacks, result[0], count);
    }

    public ItemStack getAssumedResult() {
        return assumedResult;
    }

    public int getMatchingRecipeCount() {
        return matchingRecipes.size();
    }

    public int getSelectedRecipeIndex() {
        return selectedRecipeIndex;
    }

    public boolean selectRecipe(int delta) {
        Level world = tile.getLevel();
        if (world == null || matchingRecipes.size() <= 1 || delta == 0) {
            return false;
        }
        int base = selectedRecipeIndex < 0 ? 0 : selectedRecipeIndex;
        int next = Math.floorMod(base + delta, matchingRecipes.size());
        if (next == base) {
            return false;
        }
        applySelectedRecipe(next, world);
        return true;
    }

    public void writeSelection(CompoundTag nbt) {
        if (selectedRecipeId != null) {
            nbt.putString("selectedCraftingRecipe", selectedRecipeId.toString());
        } else {
            nbt.remove("selectedCraftingRecipe");
        }
    }

    public void readSelection(CompoundTag nbt) {
        selectedRecipeId = nbt.contains("selectedCraftingRecipe")
            ? Identifier.tryParse(NbtCompat.getString(nbt, "selectedCraftingRecipe"))
            : null;
        selectedRecipeIndex = -1;
        isBlueprintDirty = true;
    }

    private CraftingInput blueprintInput() {
        TransientCraftingContainer grid = new TransientCraftingContainer(CONTAINER_EVENT_HANDLER, width, height);
        for (int slot = 0; slot < craftTableSize; slot++) {
            grid.setItem(slot, invBlueprint.getStackInSlot(slot).copy());
        }
        return grid.asCraftInput();
    }

    private void clearRecipeState() {
        currentRecipe = null;
        assumedResult = ItemStack.EMPTY;
        selectedRecipeIndex = -1;
        invAssumedResult.setStackInSlot(0, ItemStack.EMPTY);
        cachedHasRequirements = false;
    }

    private void rebuildMatchingRecipes(Level world) {
        matchingRecipes.clear();
        matchingRecipeIds.clear();

        CraftingInput input = blueprintInput();
        if (!(world.recipeAccess() instanceof RecipeManager recipeManager)) {
            clearRecipeState();
            return;
        }

        for (RecipeHolder<?> holder : recipeManager.getRecipes()) {
            Recipe<?> raw = holder.value();
            if (raw instanceof CraftingRecipe recipe && recipe.matches(input, world)) {
                matchingRecipes.add(recipe);
                matchingRecipeIds.add(holder.id().identifier());
            }
        }

        if (matchingRecipes.isEmpty()) {
            clearRecipeState();
            return;
        }

        int selected = 0;
        if (selectedRecipeId != null) {
            int persisted = matchingRecipeIds.indexOf(selectedRecipeId);
            if (persisted >= 0) {
                selected = persisted;
            }
        }
        applySelectedRecipe(selected, world);
    }

    private void applySelectedRecipe(int index, Level world) {
        selectedRecipeIndex = index;
        currentRecipe = matchingRecipes.get(index);
        selectedRecipeId = matchingRecipeIds.get(index);
        CraftingInput input = blueprintInput();
        assumedResult = currentRecipe.matches(input, world)
            ? currentRecipe.assemble(input, world.registryAccess())
            : ItemStack.EMPTY;
        invAssumedResult.setStackInSlot(0, assumedResult.copy());
        areMaterialsDirty = true;
    }

    public void onInventoryChange(IItemHandler inv) {
        if (inv == invBlueprint) {
            isBlueprintDirty = true;
        } else if (inv == invMaterials) {
            areMaterialsDirty = true;
        }
    }

    /** @return true if the cached recipe selection changed. */
    public boolean tick() {
        Level world = tile.getLevel();
        if (world == null) return false;
        if (world.isClientSide()) {
            throw new IllegalStateException("Never call this on the client side!");
        }
        if (isBlueprintDirty) {
            rebuildMatchingRecipes(world);
            isBlueprintDirty = false;
            return true;
        }
        return false;
    }

    public boolean canCraft() {
        if (currentRecipe == null || isBlueprintDirty || assumedResult.isEmpty()) {
            return false;
        }
        if (!invResult.canFullyAccept(assumedResult)) {
            return false;
        }
        if (areMaterialsDirty) {
            areMaterialsDirty = false;
            cachedHasRequirements = hasExactStacks();
        }
        return cachedHasRequirements;
    }

    public boolean craft() throws IllegalStateException {
        if (isBlueprintDirty || currentRecipe == null) {
            return false;
        }
        return craftExact();
    }

    private boolean hasExactStacks() {
        Object2IntMap<ItemStackKey> required = new Object2IntArrayMap<>(craftTableSize);
        for (int slot = 0; slot < craftTableSize; slot++) {
            ItemStack req = invBlueprint.getStackInSlot(slot);
            if (!req.isEmpty()) {
                int count = req.getCount();
                if (count != 1) {
                    req = req.copy();
                    req.setCount(1);
                }
                ItemStackKey key = new ItemStackKey(req);
                required.merge(key, count, Integer::sum);
            }
        }
        for (Object2IntMap.Entry<ItemStackKey> entry : required.object2IntEntrySet()) {
            int count = entry.getIntValue();
            ArrayStackFilter filter = new ArrayStackFilter(entry.getKey().baseStack);
            ItemStack inInventory = invMaterials.extract(filter, count, count, true);
            if (inInventory.isEmpty() || inInventory.getCount() != count) {
                return false;
            }
        }
        return !required.isEmpty();
    }

    private boolean craftExact() {
        Level world = tile.getLevel();
        if (world == null || currentRecipe == null) return false;
        BlockPos pos = tile.getBlockPos();

        // Never overwrite a transient crafting slot unless every previous item was returned.
        if (!clearInventory()) {
            return false;
        }

        for (int slot = 0; slot < craftTableSize; slot++) {
            ItemStack blueprint = invBlueprint.getStackInSlot(slot);
            if (!blueprint.isEmpty()) {
                ItemStack stack = invMaterials.extract(new ArrayStackFilter(blueprint), 1, 1, false);
                if (stack.isEmpty()) {
                    clearInventory();
                    return false;
                }
                super.setItem(slot, stack);
            }
        }

        CraftingInput craftingInput = asCraftInput();
        if (!currentRecipe.matches(craftingInput, world)) {
            clearInventory();
            return false;
        }

        ItemStack result = currentRecipe.assemble(craftingInput, world.registryAccess());
        if (result.isEmpty()) {
            clearInventory();
            return false;
        }

        ItemStack leftover = invResult.insert(result, false, false);
        if (!leftover.isEmpty()) {
            InventoryUtil.addToBestAcceptor(world, pos, null, leftover);
        }

        NonNullList<ItemStack> remainingStacks = currentRecipe.getRemainingItems(craftingInput);

        // asCraftInput() trims empty outer rows and columns. Map the recipe-local
        // remaining-item coordinates back onto the physical workbench grid.
        int minX = width;
        int minY = height;
        for (int slot = 0; slot < craftTableSize; slot++) {
            if (!super.getItem(slot).isEmpty()) {
                minX = Math.min(minX, slot % width);
                minY = Math.min(minY, slot / width);
            }
        }

        for (int slot = 0; slot < remainingStacks.size(); slot++) {
            int inputX = slot % craftingInput.width();
            int inputY = slot / craftingInput.width();
            int gridSlot = (minY + inputY) * width + minX + inputX;
            ItemStack inSlot = super.getItem(gridSlot);
            ItemStack remaining = remainingStacks.get(slot);

            if (!inSlot.isEmpty()) {
                super.removeItem(gridSlot, 1);
            }

            if (!remaining.isEmpty()) {
                leftover = invMaterials.insert(remaining, false, false);
                if (!leftover.isEmpty()) {
                    InventoryUtil.addToBestAcceptor(world, pos, null, leftover);
                }
            }
        }

        // Return any recipe leftovers still present in the transient grid to material storage.
        for (int slot = 0; slot < craftTableSize; slot++) {
            ItemStack inSlot = super.removeItemNoUpdate(slot);
            if (!inSlot.isEmpty()) {
                leftover = invMaterials.insert(inSlot, false, false);
                if (!leftover.isEmpty()) {
                    InventoryUtil.addToBestAcceptor(world, pos, null, leftover);
                }
            }
        }

        areMaterialsDirty = true;
        return true;
    }

    /** @return true if this transient crafting grid is now clear. */
    private boolean clearInventory() {
        for (int slot = 0; slot < craftTableSize; slot++) {
            ItemStack inSlot = super.getItem(slot);
            if (!inSlot.isEmpty()) {
                ItemStack leftover = invMaterials.insert(inSlot, false, false);
                int inserted = inSlot.getCount() - (leftover.isEmpty() ? 0 : leftover.getCount());
                if (inserted > 0) {
                    super.removeItem(slot, inserted);
                }
                if (!leftover.isEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }

}
