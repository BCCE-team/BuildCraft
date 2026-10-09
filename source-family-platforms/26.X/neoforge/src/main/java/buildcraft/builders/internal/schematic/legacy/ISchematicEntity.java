package buildcraft.builders.internal.schematic.legacy;

import java.util.Collections;
import java.util.List;

import javax.annotation.Nonnull;

import buildcraft.lib.internal.core.InvalidInputDataException;
import buildcraft.api.v2.schematic.SnapshotElement;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidStack;

public interface ISchematicEntity extends SnapshotElement {
    @Override
    default ResourceLocation typeId() {
        return SchematicEntityFactoryRegistry.getFactoryByInstance(this).name;
    }

    void init(SchematicEntityContext context);

    Vec3 getPos();

    @Nonnull
    default List<ItemStack> computeRequiredItems(Level level) {
        return Collections.emptyList();
    }

    @Nonnull
    default List<FluidStack> computeRequiredFluids(Level level) {
        return Collections.emptyList();
    }

    ISchematicEntity getRotated(Rotation rotation);

    Entity build(BlockGetter world, BlockPos basePos);

    Entity buildWithoutChecks(BlockGetter world, BlockPos basePos);

    CompoundTag serializeNBT();

    /** @throws InvalidInputDataException If the input data wasn't correct or didn't make sense. */
    void deserializeNBT(CompoundTag nbt) throws InvalidInputDataException;
}

