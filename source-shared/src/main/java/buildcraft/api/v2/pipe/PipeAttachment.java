package buildcraft.api.v2.pipe;

import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Side-mounted extension implemented independently of BuildCraft's internal pluggable types. */
public interface PipeAttachment {
    ResourceLocation typeId();
    Direction side();

    default boolean blocksConnection() { return false; }

    default VoxelShape boundingBox() { return Shapes.empty(); }

    default ItemStack pickStack() { return ItemStack.EMPTY; }

    /** Called for loot evaluation; may be invoked more than once, so must not consume mutable resources. */
    default List<ItemStack> drops(int fortune) {
        ItemStack item = pickStack();
        return item.isEmpty() ? List.of() : List.of(item.copy());
    }

    default void placed(PipeExecutionContext pipe) { }

    default void restored(PipeExecutionContext pipe) { }

    default void tick(PipeExecutionContext pipe) { }

    default PipeActivationResult activate(PipeActivationContext context) { return PipeActivationResult.PASS; }

    default void removed() { }
}
