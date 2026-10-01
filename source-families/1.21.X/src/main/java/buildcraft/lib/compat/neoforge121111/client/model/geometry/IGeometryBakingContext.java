//? source if >=1.21.11
package buildcraft.lib.compat.neoforge121111.client.model.geometry;

import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.resources.model.Material;

public interface IGeometryBakingContext {
    default boolean hasMaterial(String name) { return false; }
    default Material getMaterial(String name) { return null; }
    default ItemTransforms getTransforms() { return ItemTransforms.NO_TRANSFORMS; }
}
