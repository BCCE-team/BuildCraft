/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.builders.snapshot;

import java.util.Objects;

import buildcraft.builders.internal.schematic.legacy.ISchematicEntity;
import buildcraft.builders.internal.schematic.legacy.SchematicEntityContext;
import buildcraft.lib.internal.core.InvalidInputDataException;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;

/** Lossless placeholder for a legacy schematic entity whose owning addon is currently unavailable. */
public final class UnavailableSchematicEntity implements ISchematicEntity {
    private static final ResourceLocation FALLBACK_TYPE = Objects.requireNonNull(
        ResourceLocation.tryParse("buildcraft:unavailable_schematic_entity")
    );

    private final CompoundTag serialized;
    private final ResourceLocation typeId;

    public UnavailableSchematicEntity(CompoundTag serialized) {
        this.serialized = Objects.requireNonNull(serialized, "serialized").copy();
        ResourceLocation parsed = ResourceLocation.tryParse(serialized.getString("name"));
        this.typeId = parsed == null ? FALLBACK_TYPE : parsed;
    }

    public CompoundTag serializedEnvelope() {
        return serialized.copy();
    }

    @Override
    public ResourceLocation typeId() {
        return typeId;
    }

    @Override
    public void init(SchematicEntityContext context) {
    }

    @Override
    public Vec3 getPos() {
        // Unavailable entities are filtered from BuildingInfo before this position participates in scheduling.
        return Vec3.ZERO;
    }

    @Override
    public ISchematicEntity getRotated(Rotation rotation) {
        return this;
    }

    @Override
    public Entity build(BlockGetter world, BlockPos basePos) {
        return null;
    }

    @Override
    public Entity buildWithoutChecks(BlockGetter world, BlockPos basePos) {
        return null;
    }

    @Override
    public CompoundTag serializeNBT() {
        return serialized.getCompound("data").copy();
    }

    @Override
    public void deserializeNBT(CompoundTag nbt) throws InvalidInputDataException {
        throw new InvalidInputDataException("Unavailable schematic entity placeholders are immutable");
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof UnavailableSchematicEntity other && serialized.equals(other.serialized);
    }

    @Override
    public int hashCode() {
        return serialized.hashCode();
    }
}

