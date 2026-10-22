package buildcraft.silicon.client;

import buildcraft.silicon.item.ItemPluggableFacade;
import buildcraft.silicon.plug.FacadeInstance;
import buildcraft.silicon.plug.FacadePhasedState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.item.ItemColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.FoliageColor;
import net.minecraft.world.level.GrassColor;

public enum FacadeItemColours implements ItemColor {
    INSTANCE;

    @Override
    public int getColor(ItemStack stack, int tintIndex) {
        FacadeInstance states = ItemPluggableFacade.getStates(stack);
        FacadePhasedState state = states.getCurrentStateForStack();
        int colour = -1;
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.stateInfo.state.getBlock());
        if (id != null && "wildnature".equals(id.getNamespace())) {
            // Fixes https://github.com/BuildCraft/BuildCraft/issues/4435
            // (Basically wildnature doesn't handle the null world+position correctly)
            // (But instead of throwing an NPE they pass invalid values to "ColourizerGrass")
            return -1;
        }
        try {
            colour = Minecraft.getInstance().getBlockColors().getColor(state.stateInfo.state, null, null);
        } catch (NullPointerException ex) {
            // the block didn't like the null world or player
        }
        if (colour != -1 && colour != 0) {
            return colour;
        }
        String path = id == null ? "" : id.getPath();
        if (path.endsWith("_leaves") || path.equals("leaves") || path.contains("mangrove") || path.contains("vine")) {
            return FoliageColor.get(0.5D, 1.0D);
        }
        if (path.contains("grass") || path.contains("fern")) {
            return GrassColor.get(0.5D, 1.0D);
        }
        return colour;
    }
}
