package buildcraft.lib.compat.neoforge2612.client.model.geometry;

import net.minecraft.client.resources.model.cuboid.ItemTransforms;
import net.minecraft.client.resources.model.Material;

public interface IGeometryBakingContext {
    default boolean hasMaterial(String name) { return false; }
    default Material getMaterial(String name) { return null; }
    default ItemTransforms getTransforms() { return ItemTransforms.NO_TRANSFORMS; }
}
