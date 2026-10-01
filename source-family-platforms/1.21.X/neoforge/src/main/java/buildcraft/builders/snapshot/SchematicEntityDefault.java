//? source if >=1.21.11
/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.builders.snapshot;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.Nonnull;

import org.apache.commons.lang3.tuple.Pair;

import buildcraft.lib.internal.core.InvalidInputDataException;
import buildcraft.builders.internal.schematic.legacy.ISchematicEntity;
import buildcraft.builders.internal.schematic.legacy.SchematicEntityContext;
import buildcraft.lib.misc.NBTUtilBC;
import buildcraft.lib.misc.RotationUtil;
import buildcraft.builders.BuildersNbtUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidStack;
import buildcraft.lib.compat.NbtCompat;

public class SchematicEntityDefault implements ISchematicEntity {
    private CompoundTag entityNbt;
    private Vec3 pos;
    private BlockPos hangingPos;
    private Direction hangingFacing;
    private Rotation entityRotation = Rotation.NONE;

    private static CompoundTag saveEntity(Entity entity) {
        return BlueprintEntityData.save(entity);
    }

    public static boolean predicate(SchematicEntityContext context) {
        Identifier registryName = EntityType.getKey(context.entity.getType());
        return registryName != null &&
            RulesLoader.READ_DOMAINS.contains(registryName.getNamespace()) &&
            RulesLoader.getRules(
                    EntityType.getKey(context.entity.getType()),
                saveEntity(context.entity)
            )
                .stream()
                .anyMatch(rule -> rule.capture);
    }

    public void init(SchematicEntityContext context) {
        entityNbt = saveEntity(context.entity);
        Identifier entityId = EntityType.getKey(context.entity.getType());
        if (entityId != null) {
            InventoryContentPolicy.stripDisallowedEntityContent(
                entityId,
                entityNbt,
                RulesLoader.getRules(entityId, entityNbt)
            );
        }
        pos = context.entity.position().subtract(Vec3.atLowerCornerOf(context.basePos));
        if (context.entity instanceof HangingEntity) {
            HangingEntity entityHanging = (HangingEntity) context.entity;
            hangingPos = entityHanging.getPos().subtract(context.basePos);
            hangingFacing = entityHanging.getDirection();
        } else {
            hangingPos = BlockPos.containing(pos);
            hangingFacing = Direction.NORTH;
        }
    }

    public Vec3 getPos() {
        return pos;
    }

    @Nonnull
    public List<ItemStack> computeRequiredItems(Level level) {
        Identifier entityId = Identifier.parse(NbtCompat.getString(entityNbt, "id"));
        Set<JsonRule> rules = RulesLoader.getRules(
            entityId,
            entityNbt
        );
        if (rules.isEmpty()) {
            throw new IllegalArgumentException("Rules are empty");
        }
        return rules.stream()
            .map(rule -> rule.requiredExtractors)
            .filter(Objects::nonNull)
            .flatMap(Collection::stream)
            .filter(requiredExtractor -> InventoryContentPolicy.isItemListExtractorAllowedForEntity(entityId, requiredExtractor))
            .flatMap(requiredExtractor -> requiredExtractor.extractItemsFromEntity(entityNbt, level).stream())
            .filter(((Predicate<ItemStack>) ItemStack::isEmpty).negate())
            .collect(Collectors.toList());
    }

    @Nonnull
    public List<FluidStack> computeRequiredFluids(Level level) {
        Set<JsonRule> rules = RulesLoader.getRules(
            Identifier.parse(NbtCompat.getString(entityNbt, "id")),
            entityNbt
        );
        return rules.stream()
            .map(rule -> rule.requiredExtractors)
            .filter(Objects::nonNull)
            .flatMap(Collection::stream)
            .flatMap(requiredExtractor -> requiredExtractor.extractFluidsFromEntity(entityNbt, level).stream())
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }

    public SchematicEntityDefault getRotated(Rotation rotation) {
        SchematicEntityDefault schematicEntity = SchematicEntityManager.createCleanCopy(this);
        schematicEntity.entityNbt = entityNbt;
        schematicEntity.pos = RotationUtil.rotateVec3(pos, rotation);
        schematicEntity.hangingPos = hangingPos.rotate(rotation);
        schematicEntity.hangingFacing = rotation.rotate(hangingFacing);
        schematicEntity.entityRotation = entityRotation.getRotated(rotation);
        return schematicEntity;
    }

    public Entity build(BlockAndTintGetter world, BlockPos basePos) {
        Identifier entityId = Identifier.parse(NbtCompat.getString(entityNbt, "id"));
        Set<JsonRule> rules = RulesLoader.getRules(
            entityId,
            entityNbt
        );
        CompoundTag replaceNbt = rules.stream()
            .map(rule -> rule.replaceNbt)
            .filter(Objects::nonNull)
            .map(Tag.class::cast)
            .reduce(NBTUtilBC::merge)
            .map(CompoundTag.class::cast)
            .orElse(null);
        Vec3 placePos = Vec3.atLowerCornerOf(basePos).add(pos);
        BlockPos placeHangingPos = basePos.offset(hangingPos);
        CompoundTag newEntityNbt = entityNbt.copy();
        newEntityNbt.put("Pos", NBTUtilBC.writeVec3(placePos));
        NbtCompat.putUUID(newEntityNbt, "UUID", UUID.randomUUID());
        boolean attached = newEntityNbt.contains("block_pos")
            || Stream.of("TileX", "TileY", "TileZ").allMatch(newEntityNbt::contains);
        if (attached) {
            // BlockAttachedEntity loads a BlockPos.CODEC array, not the legacy TileX/Y/Z triplet.
            newEntityNbt.putIntArray("block_pos", new int[] {placeHangingPos.getX(), placeHangingPos.getY(), placeHangingPos.getZ()});
            newEntityNbt.putInt("TileX", placeHangingPos.getX());
            newEntityNbt.putInt("TileY", placeHangingPos.getY());
            newEntityNbt.putInt("TileZ", placeHangingPos.getZ());
            if ("minecraft:painting".equals(entityId.toString())) {
                newEntityNbt.putByte("facing", (byte) hangingFacing.get2DDataValue());
            } else {
                newEntityNbt.putByte("Facing", (byte) hangingFacing.get3DDataValue());
            }
        }
        CompoundTag nbt = replaceNbt != null
                ? (CompoundTag) NBTUtilBC.merge(newEntityNbt, replaceNbt)
                : newEntityNbt;
        InventoryContentPolicy.stripDisallowedEntityContent(entityId, nbt, rules);
        Entity entity = null;
        if (world instanceof Level level) {
            BlueprintEntityData.prepareLoad(nbt);
            entity = EntityType.create(
                TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), nbt),
                level, EntitySpawnReason.LOAD
            ).orElse(null);
            if (entity != null) {
                BlueprintEntityData.restoreEquipment(entity, nbt);
                if (!attached) {
                    float yaw = 2 * entity.getYRot() - entity.rotate(entityRotation);
                    entity.snapTo(placePos.x, placePos.y, placePos.z, yaw, entity.getXRot());
                }
                if (level instanceof ServerLevel serverLevel) {
                    if (!serverLevel.addFreshEntity(entity)) return null;
                } else if (level instanceof FakeWorld fakeWorld) {
                    fakeWorld.addEntity(entity);
                }
            }
        }
        return entity;
    }

    public Entity buildWithoutChecks(BlockAndTintGetter world, BlockPos basePos) {
        return build(world, basePos);
    }

    public CompoundTag serializeNBT() {
        CompoundTag nbt = new CompoundTag();
        nbt.put("entityNbt", entityNbt);
        nbt.put("pos", NBTUtilBC.writeVec3(pos));
        nbt.put("hangingPos", NbtCompat.writeBlockPos(hangingPos));
        nbt.put("hangingFacing", NBTUtilBC.writeEnum(hangingFacing));
        nbt.put("entityRotation", NBTUtilBC.writeEnum(entityRotation));
        return nbt;
    }

    public void deserializeNBT(CompoundTag nbt) throws InvalidInputDataException {
        entityNbt = NbtCompat.getCompound(nbt, "entityNbt");
        pos = NBTUtilBC.readVec3(nbt.get("pos"));
        hangingPos = BuildersNbtUtil.readBlockPos(nbt, "hangingPos");
        hangingFacing = NBTUtilBC.readEnum(nbt.get("hangingFacing"), Direction.class);
        entityRotation = NBTUtilBC.readEnum(nbt.get("entityRotation"), Rotation.class);
    }

    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        SchematicEntityDefault that = (SchematicEntityDefault) o;

        return entityNbt.equals(that.entityNbt) &&
            pos.equals(that.pos) &&
            hangingPos.equals(that.hangingPos) &&
            hangingFacing == that.hangingFacing &&
            entityRotation == that.entityRotation;
    }

    public int hashCode() {
        int result = entityNbt.hashCode();
        result = 31 * result + pos.hashCode();
        result = 31 * result + hangingPos.hashCode();
        result = 31 * result + hangingFacing.hashCode();
        result = 31 * result + entityRotation.hashCode();
        return result;
    }
}
