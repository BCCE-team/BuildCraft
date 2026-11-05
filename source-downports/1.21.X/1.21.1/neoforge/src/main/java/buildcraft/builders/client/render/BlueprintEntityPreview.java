/* Copyright (c) 2026 BuildCraft Community Edition contributors
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.builders.client.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.mojang.blaze3d.vertex.PoseStack;

import buildcraft.builders.internal.schematic.legacy.ISchematicEntity;
import buildcraft.builders.snapshot.Blueprint;
import buildcraft.builders.snapshot.BlueprintSecurity;
import buildcraft.builders.BuildersNbtUtil;
import buildcraft.builders.snapshot.FakeWorld;
import buildcraft.lib.misc.NBTUtilBC;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

/** Read-only entity visualization for captured vanilla blueprint decorations.
 * Entity creation is local to FakeWorld: it never adds an entity to the real client level.
 */
final class BlueprintEntityPreview {
    private static final int MAX_ENTITIES = 256;
    private static final Set<String> SUPPORTED = Set.of(
        "minecraft:item_frame", "minecraft:glow_item_frame", "minecraft:armor_stand", "minecraft:painting"
    );

    private BlueprintEntityPreview() {}

    static List<Entity> create(Blueprint blueprint, FakeWorld world) {
        List<Entity> rendered = new ArrayList<>();
        if (blueprint == null || blueprint.entities == null) return rendered;
        for (ISchematicEntity schematic : blueprint.entities) {
            if (rendered.size() >= MAX_ENTITIES) break;
            try {
                CompoundTag snapshot = schematic.serializeNBT();
                if (snapshot == null || !snapshot.contains("entityNbt", Tag.TAG_COMPOUND)) continue;
                CompoundTag tag = snapshot.getCompound("entityNbt").copy();
                if (!SUPPORTED.contains(tag.getString("id"))) continue;
                Vec3 local = schematic.getPos();
                if (local == null || !Double.isFinite(local.x) || !Double.isFinite(local.y)
                    || !Double.isFinite(local.z) || local.x < 0 || local.y < 0 || local.z < 0
                    || local.x >= blueprint.size.getX() || local.y >= blueprint.size.getY()
                    || local.z >= blueprint.size.getZ()) continue;
                // The fake world's blocks are offset above Y=0. Use the same
                // coordinate frame for hanging entity attachment and lookup.
                Vec3 inWorld = local.add(Vec3.atLowerCornerOf(FakeWorld.BLUEPRINT_OFFSET));
                tag.put("Pos", NBTUtilBC.writeVec3(inWorld));
                BlueprintSecurity.stripUnsafeEntityState(tag);
                if (tag.getString("id").endsWith("item_frame")
                    || tag.getString("id").equals("minecraft:painting")) {
                    BlockPos relativeAnchor = BuildersNbtUtil.readBlockPos(snapshot, "hangingPos");
                    if (relativeAnchor == null) continue;
                    BlockPos anchor = relativeAnchor.offset(FakeWorld.BLUEPRINT_OFFSET);
                    tag.putIntArray("block_pos", new int[] {anchor.getX(), anchor.getY(), anchor.getZ()});
                    tag.putInt("TileX", anchor.getX());
                    tag.putInt("TileY", anchor.getY());
                    tag.putInt("TileZ", anchor.getZ());
                    Direction facing = NBTUtilBC.readEnum(snapshot.get("hangingFacing"), Direction.class);
                    if (facing != null) {
                        tag.putByte("Facing", (byte) facing.get3DDataValue());
                        if (tag.getString("id").equals("minecraft:painting")) {
                            tag.putByte("facing", (byte) facing.get2DDataValue());
                        }
                    }
                }
                // EntityType.create decodes only saved vanilla visual state.
                // Never call buildWithoutChecks / addFreshEntity / world.addEntity.
                Entity entity = EntityType.create(tag, world).orElse(null);
                if (entity != null) rendered.add(entity);
            } catch (RuntimeException | LinkageError unsupported) {
                // One corrupt optional entity does not hide any others.
            }
        }
        return List.copyOf(rendered);
    }

    static void render(List<Entity> entities, PoseStack pose, MultiBufferSource buffers) {
        EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
        Vec3 offset = Vec3.atLowerCornerOf(FakeWorld.BLUEPRINT_OFFSET);
        for (Entity entity : entities) {
            try {
                Vec3 local = entity.position().subtract(offset);
                dispatcher.render(entity, local.x, local.y, local.z,
                    entity.getYRot(), 0.0F, pose, buffers, 15728880);
            } catch (RuntimeException | LinkageError badRenderer) {
                // An addon/renderer issue must not prevent rendering blocks.
            }
        }
    }
}
