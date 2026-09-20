/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * Copyright (c) 2026 the BuildCraft Community Edition contributors
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */
package buildcraft.lib.platform.permission;

import buildcraft.lib.misc.FakePlayerProvider;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.permission.AutomationActor;
import buildcraft.api.v2.permission.PermissionDecision;
import buildcraft.api.v2.permission.PermissionVerdict;
import buildcraft.api.v2.permission.WorldOperationContext;
import buildcraft.api.v2.permission.WorldOperationKind;
import buildcraft.api.v2.permission.WorldOperationTarget;
import com.mojang.authlib.GameProfile;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/**
 * Connects BuildCraft's saved owner identities to the loader-neutral API2 permission service.
 *
 * <p>This deliberately does <strong>not</strong> implement an internal claims/ownership ACL. BuildCraft ownership is
 * execution identity: addon permission providers may deny an action here, while the actual world mutation still runs
 * through the platform interaction/event path so protection mods see the same owner as a fake player.</p>
 */
public class BCPermissions {
    public static final ResourceLocation SOURCE_BUILDER = id("builder");
    public static final ResourceLocation SOURCE_QUARRY = id("quarry");
    public static final ResourceLocation SOURCE_ROBOT = id("robot");
    public static final ResourceLocation SOURCE_PUMP = id("pump");
    public static final ResourceLocation SOURCE_FLOOD_GATE = id("flood_gate");
    public static final ResourceLocation SOURCE_MINING_WELL = id("mining_well");
    public static final ResourceLocation SOURCE_STRIPES_PIPE = id("stripes_pipe");

    protected BCPermissions() {}

    public static AutomationActor actor(GameProfile owner, ResourceLocation sourceId) {
        Objects.requireNonNull(sourceId, "sourceId");
        if (isRealOwner(owner)) {
            return AutomationActor.machineOwner(owner.getId(), owner.getName(), sourceId);
        }
        return AutomationActor.system(sourceId);
    }

    public static PermissionDecision decideBlock(
        Level level,
        BlockPos origin,
        BlockPos target,
        GameProfile owner,
        ResourceLocation sourceId,
        WorldOperationKind operation,
        OperationMode mode
    ) {
        return decide(level, origin, WorldOperationTarget.block(target), owner, sourceId, operation, mode);
    }

    public static PermissionDecision decideEntity(
        Level level,
        BlockPos origin,
        Entity target,
        GameProfile owner,
        ResourceLocation sourceId,
        WorldOperationKind operation,
        OperationMode mode
    ) {
        Objects.requireNonNull(target, "target");
        return decide(level, origin, WorldOperationTarget.entity(target.getUUID()), owner, sourceId, operation, mode);
    }

    public static boolean mayBlock(
        Level level,
        BlockPos origin,
        BlockPos target,
        GameProfile owner,
        ResourceLocation sourceId,
        WorldOperationKind operation,
        OperationMode mode
    ) {
        return decideBlock(level, origin, target, owner, sourceId, operation, mode).verdict() != PermissionVerdict.DENY;
    }

    public static boolean mayEntity(
        Level level,
        BlockPos origin,
        Entity target,
        GameProfile owner,
        ResourceLocation sourceId,
        WorldOperationKind operation,
        OperationMode mode
    ) {
        return decideEntity(level, origin, target, owner, sourceId, operation, mode).verdict() != PermissionVerdict.DENY;
    }

    private static PermissionDecision decide(
        Level level,
        BlockPos origin,
        WorldOperationTarget target,
        GameProfile owner,
        ResourceLocation sourceId,
        WorldOperationKind operation,
        OperationMode mode
    ) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(mode, "mode");
        return BuildCraftApi.service(BuildCraftServices.PERMISSIONS).decide(new WorldOperationContext(
            actor(owner, sourceId), level, origin, target, operation, mode, sourceId
        ));
    }

    private static ResourceLocation id(String path) {
        return Objects.requireNonNull(ResourceLocation.tryParse("buildcraft:" + path));
    }

    private static boolean isRealOwner(GameProfile owner) {
        return owner != null
            && owner.getId() != null
            && !owner.getId().equals(FakePlayerProvider.NULL_PROFILE.getId());
    }
}
