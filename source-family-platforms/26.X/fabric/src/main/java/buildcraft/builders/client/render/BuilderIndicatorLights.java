/* Copyright (c) 2026 BuildCraft Community Edition contributors
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * BCU-style machine indicators, client rendering only.
 */
package buildcraft.builders.client.render;

import buildcraft.builders.block.BlockArchitectTable;
import buildcraft.builders.tile.TileArchitectTable;
import buildcraft.builders.tile.TileFiller;
import buildcraft.builders.tile.TileQuarry;
import buildcraft.lib.block.BlockBCBase_Neptune;
import buildcraft.lib.client.render.tile.RenderPartCube;
import buildcraft.lib.internal.tiles.IControllable.Mode;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * Tiny fullbright status lamps. They are baked into the ordinary client block-atlas
 * vertex buffer and never change emission/block states or the world's light map.
 * Their placement and state colours follow BCU's Filler/Quarry/Architect LEDs.
 */
final class BuilderIndicatorLights {
    private static final int OFF = 0xFF1F101B;
    private static final int GREEN = 0xFF77DD77;
    private static final int GREEN_HALF = 0xFF3F773F;
    private static final int RED = 0xFFDD2222;
    private static final int RED_HALF = 0xFF771111;
    private static final double INSET = 0.4 / 16.0;

    private final RenderPartCube[] green = new RenderPartCube[4];
    private final RenderPartCube[] red = new RenderPartCube[4];

    BuilderIndicatorLights() {
        for (int i = 0; i < 4; i++) {
            green[i] = createLamp();
            red[i] = createLamp();
        }
    }

    private static RenderPartCube createLamp() {
        RenderPartCube lamp = new RenderPartCube();
        lamp.setWhiteTex();
        // Both light-map channels are forced to 15. No neighbouring blocks receive light.
        lamp.center.lighti(15, 15);
        return lamp;
    }

    void drawFiller(TileFiller tile, Matrix4f pose, Matrix3f normal, VertexConsumer consumer) {
        if (tile == null || tile.getLevel() == null) return;
        Mode mode = tile.getControlMode();
        // On a remote client the battery is not necessarily synced outside the GUI.
        // Work-in-flight is synced for rendering, so keep the lamp active while the
        // Filler is visibly working rather than showing a false "no power" alert.
        boolean tasksInFlight = tile.getBuilder() != null
            && (!tile.getBuilder().clientBreakTasks.isEmpty()
                || !tile.getBuilder().clientPlaceTasks.isEmpty());
        boolean powered = tile.getBattery().getStored() > 0 || tasksInFlight;
        int greenColour, redColour;
        if (mode == Mode.OFF) {
            greenColour = OFF;
            redColour = OFF;
        } else if (tile.isFinished()) {
            greenColour = GREEN;
            redColour = RED;
        } else if (!powered) {
            greenColour = OFF;
            redColour = RED;
        } else {
            greenColour = mode == Mode.LOOP ? GREEN_HALF : GREEN;
            redColour = OFF;
        }
        drawHorizontal(tile.getLevel(), tile.getBlockPos(), pose, normal, consumer,
            greenColour, redColour, 13.5 / 16.0, 1.5 / 16.0, 3.5 / 16.0, null);
    }

    void drawQuarry(TileQuarry tile, Matrix4f pose, Matrix3f normal, VertexConsumer consumer) {
        if (tile == null || tile.getLevel() == null) return;
        BlockState state = tile.getLevel().getBlockState(tile.getBlockPos());
        Direction rear = state.hasProperty(BlockBCBase_Neptune.PROP_FACING)
            ? state.getValue(BlockBCBase_Neptune.PROP_FACING).getOpposite()
            : Direction.SOUTH;
        // The live quarry task is synced to clients. The battery itself isn't part of
        // the quarry's render packet, so don't pretend a client-side battery is authoritative.
        boolean working = tile.currentTask != null;
        int greenColour = working || !tile.hasWork() ? GREEN : OFF;
        int redColour = working ? OFF : RED;
        drawHorizontal(tile.getLevel(), tile.getBlockPos(), pose, normal, consumer,
            greenColour, redColour, 13.5 / 16.0, 1.5 / 16.0, 3.5 / 16.0, rear);
    }

    void drawArchitect(TileArchitectTable tile, Matrix4f pose, Matrix3f normal, VertexConsumer consumer) {
        if (tile == null || tile.getLevel() == null) return;
        Level level = tile.getLevel();
        BlockPos pos = tile.getBlockPos();
        BlockState state = level.getBlockState(pos);
        Direction front = state.hasProperty(BlockBCBase_Neptune.PROP_FACING)
            ? state.getValue(BlockBCBase_Neptune.PROP_FACING)
            : Direction.NORTH;
        if (!isVisible(level, pos, state, front)) return;

        boolean valid = tile.box.isInitialized() &&
            (!state.hasProperty(BlockArchitectTable.PROP_VALID)
                || state.getValue(BlockArchitectTable.PROP_VALID));
        boolean hasInput = !tile.invSnapshotIn.getStackInSlot(0).isEmpty();
        boolean hasOutput = !tile.invSnapshotOut.getStackInSlot(0).isEmpty();
        int greenColour = valid ? GREEN : OFF;
        int redColour = !valid || hasInput ? RED : (hasOutput ? RED_HALF : OFF);
        renderFace(front, pose, normal, consumer, green[0], red[0], greenColour,
            redColour, 3.5 / 16.0, 2.5 / 16.0, 4.5 / 16.0);
    }

    private void drawHorizontal(Level level, BlockPos pos, Matrix4f pose, Matrix3f normal,
            VertexConsumer consumer, int greenColour, int redColour, double height,
            double greenOffset, double redOffset, Direction excluded) {
        BlockState state = level.getBlockState(pos);
        for (int i = 0; i < 4; i++) {
            Direction face = Direction.from2DDataValue(i);
            if (face == excluded || !isVisible(level, pos, state, face)) continue;
            renderFace(face, pose, normal, consumer, green[i], red[i],
                greenColour, redColour, height, greenOffset, redOffset);
        }
    }

    private static boolean isVisible(Level level, BlockPos pos, BlockState state, Direction face) {
        return Block.shouldRenderFace(state, level.getBlockState(pos.relative(face)), face);
    }

    private static void renderFace(Direction face, Matrix4f pose, Matrix3f normal, VertexConsumer consumer,
            RenderPartCube green, RenderPartCube red, int greenColour, int redColour,
            double y, double greenOffset, double redOffset) {
        placeLamp(green, face, greenOffset, y, greenColour);
        placeLamp(red, face, redOffset, y, redColour);
        green.render(pose, normal, consumer);
        red.render(pose, normal, consumer);
    }

    private static void placeLamp(RenderPartCube lamp, Direction face, double sideOffset, double y, int colour) {
        // Same 0.4 px face inset and handed offsets as BCU's LedRenderUtil.
        double x, z;
        if (face.getAxis() == Direction.Axis.X) {
            x = face == Direction.EAST ? 1.0 - INSET : INSET;
            z = 0.5 + face.getAxisDirection().getStep() * sideOffset;
        } else {
            x = 0.5 - face.getAxisDirection().getStep() * sideOffset;
            z = face == Direction.SOUTH ? 1.0 - INSET : INSET;
        }
        lamp.center.positiond(x, y, z);
        lamp.center.colouri(colour);
    }
}
