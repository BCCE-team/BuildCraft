/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.misc;

import buildcraft.lib.logic.blueprint.BlueprintRotation;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class RotationUtil {
	@Deprecated//cache this!
    public static AABB rotateAABB(AABB aabb, Direction facing) {
        if (facing == Direction.DOWN) {
            return new AABB(aabb.minX, aabb.maxY, aabb.minZ, aabb.maxX, aabb.minY, aabb.maxZ);
        } else if (facing == Direction.UP) {
            return new AABB(aabb.minX, 1 - aabb.maxY, aabb.minZ, aabb.maxX, 1 - aabb.minY, aabb.maxZ);
        } else if (facing == Direction.NORTH) {
            return new AABB(aabb.minX, aabb.minZ, aabb.minY, aabb.maxX, aabb.maxZ, aabb.maxY);
        } else if (facing == Direction.SOUTH) {
            return new AABB(aabb.minX, aabb.minZ, 1 - aabb.maxY, aabb.maxX, aabb.maxZ, 1 - aabb.minY);
        } else if (facing == Direction.WEST) {
            return new AABB(aabb.minY, aabb.minZ, aabb.minX, aabb.maxY, aabb.maxZ, aabb.maxX);
        } else if (facing == Direction.EAST) {
            return new AABB(1 - aabb.maxY, aabb.minZ, aabb.minX, 1 - aabb.minY, aabb.maxZ, aabb.maxX);
        }
        return aabb;
    }

    public static Vec3 rotateVec3(Vec3 vec, Rotation rotation) {
        BlueprintRotation.Point rotated = BlueprintRotation.rotateUnit(
            new BlueprintRotation.Point(vec.x, vec.y, vec.z), quarterTurns(rotation)
        );
        return new Vec3(rotated.x(), rotated.y(), rotated.z());
    }

    public static Direction rotateAll(Direction facing) {
        switch (facing) {
            case NORTH:
                return Direction.EAST;
            case EAST:
                return Direction.SOUTH;
            case SOUTH:
                return Direction.WEST;
            case WEST:
                return Direction.UP;
            case UP:
                return Direction.DOWN;
            case DOWN:
                return Direction.NORTH;
        }
        throw new IllegalArgumentException();
    }

    public static Rotation invert(Rotation rotation) {
        return fromQuarterTurns(BlueprintRotation.invertQuarterTurns(quarterTurns(rotation)));
    }

    private static int quarterTurns(Rotation rotation) {
        return switch (rotation) {
            case NONE -> 0;
            case CLOCKWISE_90 -> 1;
            case CLOCKWISE_180 -> 2;
            case COUNTERCLOCKWISE_90 -> 3;
        };
    }

    private static Rotation fromQuarterTurns(int quarterTurns) {
        return switch (Math.floorMod(quarterTurns, 4)) {
            case 0 -> Rotation.NONE;
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            case 3 -> Rotation.COUNTERCLOCKWISE_90;
            default -> throw new AssertionError();
        };
    }
}
