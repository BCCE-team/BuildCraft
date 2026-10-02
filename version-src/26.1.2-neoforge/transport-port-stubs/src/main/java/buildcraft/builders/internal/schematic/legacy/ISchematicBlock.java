package buildcraft.builders.internal.schematic.legacy;

import java.util.List;

import buildcraft.lib.internal.core.InvalidInputDataException;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Compile-only bridge for the builders module. The real interface is supplied
 * once builders is ported; transport never ships this declaration.
 */
public interface ISchematicBlock {
    void init(SchematicBlockContext context);

    List<ItemStack> computeRequiredItems(Level level);

    List<FluidStack> computeRequiredFluids(Level level);

    ISchematicBlock getRotated(Rotation rotation);

    boolean canBuild(Level world, BlockPos blockPos);

    boolean build(Level world, BlockPos blockPos);

    boolean buildWithoutChecks(Level world, BlockPos blockPos);

    boolean isBuilt(Level world, BlockPos blockPos);

    CompoundTag serializeNBT();

    void deserializeNBT(CompoundTag nbt) throws InvalidInputDataException;
}
