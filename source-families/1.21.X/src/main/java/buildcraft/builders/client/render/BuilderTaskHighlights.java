/*
 * Copyright (c) 2026 BuildCraft Community Edition contributors
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/
 *
 * Builder work visualisation adapted from the BuildCraft Redux builder render path.
 * The server remains authoritative: these highlights only read synchronized client tasks.
 */
package buildcraft.builders.client.render;

import java.util.Map;
import java.util.WeakHashMap;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import buildcraft.builders.snapshot.SnapshotBuilder;
import buildcraft.core.client.BuildCraftLaserManager;
import buildcraft.lib.client.render.laser.LaserBoxRenderer;
import buildcraft.lib.client.render.laser.LaserData_BC8;
import buildcraft.lib.client.render.laser.LaserRenderer_BC8;
import buildcraft.lib.misc.data.Box;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Coloured work cells and a smoothly moving breaking robot, as in the Redux builders renderer. */
public final class BuilderTaskHighlights {
    private static final int MAX_CELLS = 64;
    private static final Map<SnapshotBuilder<?>, RobotPosition> ROBOTS = new WeakHashMap<>();

    private BuilderTaskHighlights() {}

    /** Call with a pose already translated from the block entity to world coordinates. */
    public static void render(SnapshotBuilder<?> builder, Matrix4f pose, Matrix3f normal, VertexConsumer buffer) {
        if (builder == null) return;
        int drawn = 0;
        double sumX = 0, sumY = 0, sumZ = 0;
        int breaking = 0;
        for (SnapshotBuilder<?>.BreakTask task : builder.clientBreakTasks) {
            if (drawn++ >= MAX_CELLS) break;
            BlockPos pos = task.pos;
            if (pos == null) continue;
            LaserBoxRenderer.renderLaserBoxDynamic(new Box(pos, pos), BuildCraftLaserManager.POWER_LOW,
                pose, normal, buffer, false);
            sumX += pos.getX() + 0.5;
            sumY += pos.getY() + 0.5;
            sumZ += pos.getZ() + 0.5;
            breaking++;
        }

        // A green outline marks each destination that is actively being built. This deliberately
        // does not claim that the entire blueprint has already been checked by the machine.
        drawn = 0;
        for (SnapshotBuilder<?>.PlaceTask task : builder.clientPlaceTasks) {
            if (drawn++ >= MAX_CELLS) break;
            if (task.pos == null) continue;
            LaserBoxRenderer.renderLaserBoxDynamic(new Box(task.pos, task.pos),
                BuildCraftLaserManager.POWER_HIGH, pose, normal, buffer, false);
        }
        if (breaking == 0) {
            ROBOTS.remove(builder);
            return;
        }

        Vec3 target = new Vec3(sumX / breaking, sumY / breaking + 2.8, sumZ / breaking);
        RobotPosition animation = ROBOTS.computeIfAbsent(builder, ignored -> new RobotPosition(target));
        Vec3 robot = animation.advance(target);
        // A small laser wireframe robot follows the centroid of the current breaking tasks.
        drawRobot(robot, pose, normal, buffer);

        drawn = 0;
        for (SnapshotBuilder<?>.BreakTask task : builder.clientBreakTasks) {
            if (drawn++ >= MAX_CELLS) break;
            if (task.pos == null) continue;
            long required = Math.max(1L, task.getTarget());
            double fraction = Math.min(1.0, Math.max(0.0, (double) task.power / required));
            int colour = Math.min(BuildCraftLaserManager.POWERS.length - 1,
                (int) Math.round(fraction * (BuildCraftLaserManager.POWERS.length - 1)));
            LaserRenderer_BC8.renderLaserDynamic(pose, normal,
                new LaserData_BC8(BuildCraftLaserManager.POWERS[colour],
                    robot.add(0, -0.27, 0), Vec3.atCenterOf(task.pos), 1.0 / 16.0, true), buffer);
        }
    }

    private static void drawRobot(Vec3 center, Matrix4f pose, Matrix3f normal, VertexConsumer buffer) {
        final double radius = 0.24;
        // Bottom/top rings and uprights form a proper 3D bounding cube instead of a flat icon.
        for (int y = -1; y <= 1; y += 2) {
            double yy = center.y + radius * y;
            for (int side = -1; side <= 1; side += 2) {
                double d = radius * side;
                laser(new Vec3(center.x - radius, yy, center.z + d),
                    new Vec3(center.x + radius, yy, center.z + d), pose, normal, buffer);
                laser(new Vec3(center.x + d, yy, center.z - radius),
                    new Vec3(center.x + d, yy, center.z + radius), pose, normal, buffer);
            }
        }
        for (int x = -1; x <= 1; x += 2) {
            for (int z = -1; z <= 1; z += 2) {
                laser(center.add(radius * x, -radius, radius * z),
                    center.add(radius * x, radius, radius * z), pose, normal, buffer);
            }
        }
    }

    private static void laser(Vec3 start, Vec3 end, Matrix4f pose, Matrix3f normal, VertexConsumer buffer) {
        LaserRenderer_BC8.renderLaserDynamic(pose, normal,
            new LaserData_BC8(BuildCraftLaserManager.POWER_HIGH, start, end, 1.0 / 24.0, true), buffer);
    }

    private static final class RobotPosition {
        private Vec3 position;
        private long lastNanos;
        private RobotPosition(Vec3 start) {
            position = start;
            lastNanos = System.nanoTime();
        }
        private Vec3 advance(Vec3 target) {
            long now = System.nanoTime();
            double delta = (now - lastNanos) / 1_000_000_000.0;
            lastNanos = now;
            if (delta < 0 || delta > 1.0) {
                position = target;
            } else {
                position = position.lerp(target, 1.0 - Math.exp(-Math.min(0.1, delta) * 9.0));
            }
            return position;
        }
    }
}
