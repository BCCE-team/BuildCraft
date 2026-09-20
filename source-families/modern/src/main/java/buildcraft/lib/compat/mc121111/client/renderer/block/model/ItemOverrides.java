//? source if >=1.21.11
package buildcraft.lib.compat.mc121111.client.renderer.block.model;

import javax.annotation.Nullable;

import net.minecraft.client.multiplayer.ClientLevel;
import buildcraft.lib.compat.mc121111.client.resources.model.BakedModel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/** Legacy item-override API shim required by shared model code on 1.21.11. */
public class ItemOverrides {
    public static final ItemOverrides EMPTY = new ItemOverrides();

    public BakedModel resolve(BakedModel originalModel, ItemStack stack, @Nullable ClientLevel level,
            @Nullable LivingEntity entity, int seed) {
        return originalModel;
    }
}
