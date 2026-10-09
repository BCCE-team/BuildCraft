/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */
package buildcraft.lib.misc;

import java.util.Map;

import com.google.common.collect.ImmutableMap;

import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.world.phys.AABB;
import org.joml.AxisAngle4f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

public class MatrixUtil {
    private static final Map<Direction, Matrix4f> ROTATION_MAP;

    static {
        ImmutableMap.Builder<Direction, Matrix4f> builder = ImmutableMap.builder();
        for (Direction face : Direction.values()) {
            Matrix4f matrix = new Matrix4f().identity();
            if (face == Direction.WEST) {
                builder.put(face, matrix);
                continue;
            }
            matrix.setTranslation(new Vector3f(0.5f, 0.5f, 0.5f));
            Matrix4f transform = new Matrix4f().identity();
            if (face.getAxis() == Axis.Y) {
                transform.rotate(new AxisAngle4f((float) Math.PI * 0.5f * -face.getStepY(), 0, 0, 1));
                matrix.mul(transform);
                transform.identity().rotate(new AxisAngle4f((float) Math.PI * (1 + face.getStepY() * 0.5f), 1, 0, 0));
                matrix.mul(transform);
            } else {
                int angle = face == Direction.EAST ? 2 : face == Direction.NORTH ? 3 : 1;
                transform.rotate(new AxisAngle4f((float) Math.PI * 0.5f * angle, 0, 1, 0));
                matrix.mul(transform);
            }
            transform.identity().setTranslation(new Vector3f(-0.5f, -0.5f, -0.5f));
            matrix.mul(transform);
            builder.put(face, matrix);
        }
        ROTATION_MAP = builder.build();
    }

    public static Matrix4f rotateTowardsFace(Direction face) {
        return new Matrix4f(ROTATION_MAP.get(face));
    }

    public static Matrix4f rotateTowardsFace(Direction from, Direction to) {
        Matrix4f inverseFrom = rotateTowardsFace(from).invert();
        return rotateTowardsFace(to).mul(inverseFrom);
    }

    public static AABB multiply(AABB box, Matrix4f matrix) {
        Vector4f min = new Vector4f((float) box.minX, (float) box.minY, (float) box.minZ, 1);
        Vector4f max = new Vector4f((float) box.maxX, (float) box.maxY, (float) box.maxZ, 1);
        matrix.transform(min);
        matrix.transform(max);
        return new AABB(min.x, min.y, min.z, max.x, max.y, max.z);
    }

    public static AABB[] multiplyAll(AABB[] boxes, Matrix4f matrix) {
        AABB[] result = new AABB[boxes.length];
        for (int i = 0; i < boxes.length; i++) result[i] = multiply(boxes[i], matrix);
        return result;
    }
}
