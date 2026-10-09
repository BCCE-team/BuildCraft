/* Copyright (c) the BuildCraft team. SPDX-License-Identifier: MPL-2.0 */
package buildcraft.core.marker.volume;

import java.util.Optional;
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

    public static InteractionResult use(Level level, Player player) {
        if (level.isClientSide) return InteractionResult.PASS;
        WorldSavedDataVolumeBoxes data = WorldSavedDataVolumeBoxes.get(level);
        VolumeBox editing = data.getCurrentEditing(player);

        // Complete the active operation first, independent of where the player is looking.
        // Previously an addon at the cursor hijacked confirm/cancel, leaving an edit stuck.
        if (editing != null) {
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
            if (isLocked(nearest)) return InteractionResult.FAIL;
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
