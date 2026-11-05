/* Copyright (c) 2026 BuildCraft Community Edition contributors
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * Filler construction effects adapted from BuildCraft Redux/BC8.
 */
package buildcraft.builders.client.render;

import java.util.Map;
import java.util.WeakHashMap;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.mojang.blaze3d.vertex.VertexConsumer;

import buildcraft.builders.BCBuildersSprites;
import buildcraft.builders.snapshot.SnapshotBuilder;
import buildcraft.core.client.BuildCraftLaserManager;
import buildcraft.lib.client.model.ModelUtil;
import buildcraft.lib.client.render.laser.LaserData_BC8;
import buildcraft.lib.client.render.laser.LaserRenderer_BC8;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * BCU/BC8-style in-progress Filler construction effects. Unlike the independent
 * white pattern preview, these are shown only for actual synchronized break tasks.
 * Never mutates tasks, block states, the battery, or construction scheduling.
 *
 * The texture is the original 64x64 builder robot atlas: each side occupies an
 * 8x8 segment of the first row. A green wireframe and red/green block outlines
 * are not a replacement for this animation.
 */
public final class FillerWorkRenderer {
    private static final int MAX_BREAK_BEAMS = 64;
    private static final Map<SnapshotBuilder<?>, RobotAnimation> ROBOTS = new WeakHashMap<>();
    private static final float RADIUS = 4.0F / 16.0F;

    private FillerWorkRenderer() {}

    /** Pose is already translated from the block entity's origin into world coordinates. */
    public static void render(SnapshotBuilder<?> builder, long gameTime, float partialTick,
                              Matrix4f pose, Matrix3f normal, VertexConsumer buffer) {
        if (builder == null || builder.clientBreakTasks.isEmpty()) {
            if (builder != null) ROBOTS.remove(builder);
            return;
        }

        Vec3 target = targetFor(builder);
        RobotAnimation state = ROBOTS.computeIfAbsent(builder, ignored -> new RobotAnimation(target, gameTime));
        Vec3 robot = state.positionAt(target, gameTime, partialTick);
        int light = LaserRenderer_BC8.computeLightmap(robot.x, robot.y, robot.z, 0);

        // A textured flying robot, not a fabricated laser-wireframe cube.
        int faceIndex = 0;
        for (Direction face : Direction.values()) {
            ModelUtil.UvFaceData uv = new ModelUtil.UvFaceData(
                BCBuildersSprites.ROBOT.getInterpU((faceIndex * 8) / 64D),
                BCBuildersSprites.ROBOT.getInterpV(0),
                BCBuildersSprites.ROBOT.getInterpU(((faceIndex + 1) * 8) / 64D),
                BCBuildersSprites.ROBOT.getInterpV(8D / 64D)
            );
            ModelUtil.createFace(face,
                new Vector3f((float) robot.x, (float) robot.y, (float) robot.z),
                new Vector3f(RADIUS, RADIUS, RADIUS), uv)
                .lighti(light)
                .render(pose, normal, buffer);
            faceIndex++;
        }

        int beams = 0;
        for (SnapshotBuilder<?>.BreakTask task : builder.clientBreakTasks) {
            if (beams++ >= MAX_BREAK_BEAMS) break;
            if (task.pos == null) continue;
            long targetPower = Math.max(1L, task.getTarget());
            double progress = Math.max(0D, Math.min(1D, (double) task.power / targetPower));
            int index = Math.min(BuildCraftLaserManager.POWERS.length - 1,
                (int) Math.round(progress * (BuildCraftLaserManager.POWERS.length - 1)));
            LaserRenderer_BC8.renderLaserDynamic(pose, normal,
                new LaserData_BC8(BuildCraftLaserManager.POWERS[index],
                    robot.subtract(0, 0.27, 0), Vec3.atCenterOf(task.pos), 1D / 16D, true), buffer);
        }
    }

    /** Use the highest work cell's Y, as in the original animated Filler robot. */
    private static Vec3 targetFor(SnapshotBuilder<?> builder) {
        double sumX = 0, sumZ = 0;
        int maxY = Integer.MIN_VALUE;
        int count = 0;
        for (SnapshotBuilder<?>.BreakTask task : builder.clientBreakTasks) {
            BlockPos pos = task.pos;
            if (pos == null) continue;
            sumX += pos.getX() + .5;
            sumZ += pos.getZ() + .5;
            maxY = Math.max(maxY, pos.getY());
            count++;
        }
        if (count == 0) return Vec3.ZERO;
        return new Vec3(sumX / count, maxY + 3D, sumZ / count);
    }

    private static final class RobotAnimation {
        private Vec3 position;
        private Vec3 visual;
        private Vec3 previousVisual;
        private long lastTick;

        RobotAnimation(Vec3 target, long tick) {
            position = target;
            visual = target;
            previousVisual = target;
            lastTick = tick;
        }

        Vec3 positionAt(Vec3 target, long tick, float partialTick) {
            if (tick < lastTick) {
                position = visual = previousVisual = target;
                lastTick = tick;
            } else if (tick > lastTick) {
                // Apply the original two 1/4-rate smoothing stages once per client tick,
                // not per render frame (FPS must not alter robot speed).
                for (long i = 0, n = Math.min(20, tick - lastTick); i < n; i++) {
                    previousVisual = visual;
                    position = position.lerp(target, 0.25);
                    visual = visual.lerp(position, 0.25);
                }
                lastTick = tick;
            }
            return previousVisual.lerp(visual, Math.max(0F, Math.min(1F, partialTick)));
        }
    }
}
