/* Copyright (c) the BuildCraft team. SPDX-License-Identifier: MPL-2.0 */
package buildcraft.core.marker.volume;

import java.util.Optional;
import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.permission.AutomationActor;
import buildcraft.api.v2.permission.PermissionVerdict;
import buildcraft.api.v2.permission.WorldOperationContext;
import buildcraft.api.v2.permission.WorldOperationKind;
import buildcraft.api.v2.permission.WorldOperationTarget;
import net.minecraft.resources.ResourceLocation;
import org.apache.commons.lang3.tuple.Pair;
import buildcraft.lib.misc.PositionUtil;
import buildcraft.lib.misc.VecUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Canonical Volume Box editing logic, exclusively invoked by the Marker Connector.
 * The Volume Box item only creates a new box; it MUST NOT invoke this handler.
 */
public final class VolumeBoxToolActions {
    private VolumeBoxToolActions() {}

    private static final ResourceLocation PERMISSION_REASON =
        java.util.Objects.requireNonNull(ResourceLocation.tryParse("buildcraft:volume_box_edit"));

    /** Every editor entry point, including creation by the Volume Box item, shares one gate. */
    public static boolean mayEdit(Level level, Player player, BlockPos pos) {
        if (player == null || pos == null || !level.hasChunkAt(pos)) return false;
        if (level.isClientSide) return false;
        try {
            return BuildCraftApi.service(BuildCraftServices.PERMISSIONS).decide(new WorldOperationContext(
                AutomationActor.player(player.getUUID(), player.getName().getString()),
                level, pos, WorldOperationTarget.block(pos), WorldOperationKind.BLOCK_INTERACT,
                OperationMode.EXECUTE, PERMISSION_REASON
            )).verdict() != PermissionVerdict.DENY;
        } catch (RuntimeException faultyProvider) {
            // A broken permission integration must never silently authorize world edits.
            return false;
        }
    }

    public static InteractionResult use(Level level, Player player) {
        if (level.isClientSide) return InteractionResult.PASS;
        WorldSavedDataVolumeBoxes data = WorldSavedDataVolumeBoxes.get(level);
        VolumeBox editing = data.getCurrentEditing(player);

        // Complete the active operation first, independent of where the player is looking.
        // Previously an addon at the cursor hijacked confirm/cancel, leaving an edit stuck.
        if (editing != null) {
            if (!mayEdit(level, player, editing.box.min()) || !mayEdit(level, player, editing.box.max())) return InteractionResult.FAIL;
            if (player.isCrouching()) editing.cancelEditing();
            else editing.confirmEditing();
            data.setDirty();
            return InteractionResult.SUCCESS;
        }

        // The connector also opens/removes installed add-ons at a corner, like original BC8.
        Pair<VolumeBox, EnumAddonSlot> hit = EnumAddonSlot.getSelectingVolumeBoxAndSlot(
            player, data.volumeBoxes);
        VolumeBox addonBox = hit.getLeft();
        EnumAddonSlot slot = hit.getRight();
        if (addonBox != null && slot != null && !addonBox.isEditing()) {
            Addon addon = addonBox.addons.get(slot);
            if (addon != null) {
                if (!mayEdit(level, player, addonBox.box.min())) return InteractionResult.FAIL;
                if (addonBox.getLockTargetsStream().anyMatch(target ->
                    target instanceof Lock.Target.TargetAddon locked && locked.slot == slot)) {
                    return InteractionResult.FAIL;
                }
                if (player.isCrouching()) {
                    addon.onRemoved();
                    addonBox.addons.remove(slot);
                } else {
                    addon.onPlayerRightClick(player);
                }
                data.setDirty();
                return InteractionResult.SUCCESS;
            }
        }

        Vec3 start = player.getEyePosition();
        Vec3 end = start.add(player.getLookAngle().scale(4));
        if (player.isCrouching()) {
            VolumeBox nearest = null;
            double best = Double.MAX_VALUE;
            for (VolumeBox box : data.volumeBoxes) {
                if (box.isEditing()) continue;
                Optional<Vec3> ray = box.box.getBoundingBox().clip(start, end);
                if (ray.isPresent()) {
                    double dist = ray.get().distanceToSqr(start);
                    if (dist < best) { best = dist; nearest = box; }
                }
            }
            if (nearest == null) return InteractionResult.FAIL;
            if (isLocked(nearest) || !mayEdit(level, player, nearest.box.min())
                || !mayEdit(level, player, nearest.box.max())) return InteractionResult.FAIL;
            nearest.addons.values().forEach(Addon::onRemoved);
            data.volumeBoxes.remove(nearest);
            data.setDirty();
            return InteractionResult.SUCCESS;
        }

        VolumeBox selected = null;
        BlockPos selectedCorner = null;
        double best = Double.MAX_VALUE;
        for (VolumeBox box : data.volumeBoxes) {
            if (box.isEditing() || isResizeLocked(box)) continue;
            for (BlockPos corner : PositionUtil.getCorners(box.box.min(), box.box.max())) {
                // Use the original full block-sized corner target and 4-block reach.
                Optional<Vec3> ray = new AABB(corner).clip(start, end);
                if (ray.isPresent()) {
                    double dist = ray.get().distanceToSqr(start);
                    if (dist < best) { selected = box; selectedCorner = corner; best = dist; }
                }
            }
        }
        if (selected == null) return InteractionResult.FAIL;
        if (!mayEdit(level, player, selectedCorner) || !mayEdit(level, player, selected.box.min())
            || !mayEdit(level, player, selected.box.max())) return InteractionResult.FAIL;
        BlockPos min = selected.box.min();
        BlockPos max = selected.box.max();
        BlockPos held = min;
        if (selectedCorner.getX() == min.getX()) held = VecUtil.replaceValue(held, Direction.Axis.X, max.getX());
        if (selectedCorner.getY() == min.getY()) held = VecUtil.replaceValue(held, Direction.Axis.Y, max.getY());
        if (selectedCorner.getZ() == min.getZ()) held = VecUtil.replaceValue(held, Direction.Axis.Z, max.getZ());
        selected.setPlayer(player);
        selected.setHeldDistOldMinOldMax(held, Math.max(1.5, Math.sqrt(best) + 0.5), min, max);
        data.setDirty();
        return InteractionResult.SUCCESS;
    }

    private static boolean isResizeLocked(VolumeBox box) {
        return box.getLockTargetsStream().anyMatch(target -> target instanceof Lock.Target.TargetResize);
    }

    public static boolean isLocked(VolumeBox box) {
        return box.getLockTargetsStream().anyMatch(target ->
            target instanceof Lock.Target.TargetResize || target instanceof Lock.Target.TargetRemove);
    }
}
