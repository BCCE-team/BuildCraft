package buildcraft.silicon.recipe;

import buildcraft.lib.misc.ItemStackUtil;
import buildcraft.lib.internal.debug.BCLog;
import buildcraft.lib.BCLib;
import buildcraft.silicon.BCSiliconRecipes;
import buildcraft.silicon.gate.EnumGateLogic;
import buildcraft.silicon.gate.GateVariant;
import buildcraft.silicon.item.ItemPluggableGate;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

public class GateLogicChangeRecipe extends CustomRecipe {

    public GateLogicChangeRecipe(CraftingBookCategory category) {
        super(category);
    }

    @Override
    public boolean matches(CraftingInput container, Level level) {
        ItemStack gateStack = ItemStack.EMPTY;
        for (int slot = 0; slot < container.size(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.isEmpty() && stack.getItem() instanceof ItemPluggableGate) {
                if (!gateStack.isEmpty()) {
                    return false;
                }
                gateStack = stack;
            }
        }
        return !gateStack.isEmpty();
    }

    @Override
    public ItemStack assemble(CraftingInput container, HolderLookup.Provider registries) {
        ItemStack gateStack = ItemStack.EMPTY;
        for (int slot = 0; slot < container.size(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.isEmpty() && stack.getItem() instanceof ItemPluggableGate) {
                if (!gateStack.isEmpty()) {
                    return ItemStack.EMPTY;
                }
                gateStack = stack;
            }
        }

        if (gateStack.isEmpty()) {
            return ItemStack.EMPTY;
        }

        try {
            ItemPluggableGate gate = (ItemPluggableGate) gateStack.getItem();
            CompoundTag tag = ItemStackUtil.getCustomData(gateStack);
            if (!tag.contains("gate")) {
                BCLog.logger.error("GateLogicChangeRecipe: encountered a gate with missing gate NBT");
                return ItemStack.EMPTY;
            }

            CompoundTag gateTag = tag.getCompound("gate");
            int newLogic = gateTag.getInt("logic") == EnumGateLogic.AND.ordinal()
                ? EnumGateLogic.OR.ordinal()
                : EnumGateLogic.AND.ordinal();
            gateTag.putInt("logic", newLogic);
            return gate.getStack(new GateVariant(gateTag));
        } catch (Exception e) {
            BCLog.logger.error("GateLogicChangeRecipe: encountered a gate with invalid gate NBT");
            if (BCLib.DEV) {
                BCLog.logger.warn("Failed to copy gate logic NBT", e);
            }
            return ItemStack.EMPTY;
        }
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width >= 1 && height >= 1;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return BCSiliconRecipes.GATE_CHANGE_SERIALIZER.get();
    }
}
