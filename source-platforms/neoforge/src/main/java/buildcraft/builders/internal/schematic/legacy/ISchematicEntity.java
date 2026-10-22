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
//? if mc_26_x {
import net.minecraft.world.level.BlockGetter;
//? } else {
import net.minecraft.world.level.BlockAndTintGetter;
//?}
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

//? if mc_26_x {
    Entity build(BlockGetter world, BlockPos basePos);
//? } else {
    Entity build(BlockAndTintGetter world, BlockPos basePos);
//?}

//? if mc_26_x {
    Entity buildWithoutChecks(BlockGetter world, BlockPos basePos);
//? } else {
    Entity buildWithoutChecks(BlockAndTintGetter world, BlockPos basePos);
//?}

    CompoundTag serializeNBT();

    /** @throws InvalidInputDataException If the input data wasn't correct or didn't make sense. */
    void deserializeNBT(CompoundTag nbt) throws InvalidInputDataException;
}
//? if mc_26_x {

//? } else {
//?}
