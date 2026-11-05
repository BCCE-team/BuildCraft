/*
 * Copyright (c) 2026 the BuildCraftUnofficial contributors
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */
package buildcraft.builders.filler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import buildcraft.builders.snapshot.Template;

/**
 * What a client's Filler keeps between frames so its preview does not walk its box every frame: the cells still to
 * fill, and the order to draw them in.
 * <p>
 * The cells come from a <em>pass</em> over the template, at most {@link #SCAN_CELLS_PER_FRAME} cells per frame, so a
 * huge box costs a bounded slice of every frame for a while instead of one long stall; until a pass finishes the
 * previous result keeps being drawn. A pass keeps only the {@link #MAX_CUBES} cells nearest the camera as it began
 * (a bounded max-heap of packed distance and template index, no per-cell storage), because no more than that is ever
 * drawn. A new pass starts after the last one finished, because a block placed or broken inside the box (by a player,
 * a pipe, a robot) announces itself to no client event: {@link #RESCAN_TICKS} later, or as long after as the finished
 * pass itself took if that was longer, so a box too big to read in {@link #RESCAN_TICKS} is scanned at most half of
 * the time instead of every frame; and at once when the Filler builds a new {@code BuildingInfo}. A changed pattern,
 * parameter, invert or box always does, and the same object identity is what makes a stale preview impossible: the old
 * cells are dropped the frame the new info shows up. A pass nobody advanced for {@link #STALL_TICKS} (the Filler was
 * building, so the preview stood down, or it was out of view) is dropped and started again: what it had read is out of
 * date, and its pacing and its camera would be too.
 * <p>
 * The draw order — far to near, for the translucent blend — is redone when a pass finishes or the camera has moved a
 * few blocks; it only ever sorts the kept cells.
 */
public final class FillPreviewCache {
    /** The least a finished pass is trusted before the next one starts, in game ticks: a pass that took longer is
     *  trusted for as long as it took ({@link #cellsToDraw(Level, Template.BuildingInfo, Vec3, long)}). */
    public static final int RESCAN_TICKS = 10;
    /** How long the Filler must have had nothing to place or break before its preview shows, so the gaps between
     *  tasks of a running build do not flicker it. */
    public static final int IDLE_GRACE_TICKS = 20;
    /** How many game ticks a pass may go without a frame advancing it before it is thrown away and started again: far
     *  more than a frame takes even on a slow client, and less than any pause for a build, since the preview waits
     *  {@link #IDLE_GRACE_TICKS} quiet ticks before it draws again. */
    public static final int STALL_TICKS = 20;
    /** Most cubes drawn (24 vertices each) and so most cells kept: past this a pass keeps the nearest and leaves the far
     *  ones undrawn, so a huge box never costs every frame the draw of all of it. */
    public static final int MAX_CUBES = 4096;
    /** Most template cells a pass reads per frame (each is a block read and a few small objects: a fraction of a
     *  millisecond). */
    public static final int SCAN_CELLS_PER_FRAME = 8192;

    private static final double RESORT_DISTANCE_SQR = 2.0 * 2.0;

    private final int maxCubes;
    private final int scanCellsPerFrame;

    // The last finished pass.
    @Nullable
    private Template.BuildingInfo publishedFor;
    private long publishedAt;
    /** How long the last finished pass is trusted: {@link #RESCAN_TICKS}, or the ticks that pass took if more. */
    private long trustTicks = RESCAN_TICKS;
    private List<BlockPos> cells = List.of();
    @Nullable
    private Vec3 sortedFrom;
    private List<BlockPos> drawOrder = List.of();

    // The pass in progress, if any.
    @Nullable
    private FillPreview.Walk walk;
    @Nullable
    private long[] nearest;
    private int nearestCount;
    private Vec3 passCamera = Vec3.ZERO;
    private long passStartedAt;
    /** The game time of the last frame that advanced the pass. */
    private long walkAdvancedAt;

    private long lastBusy = Long.MIN_VALUE / 2;

    public FillPreviewCache() {
        this(MAX_CUBES, SCAN_CELLS_PER_FRAME);
    }

    /** A cache drawing at most {@code maxCubes} cubes instead of {@link #MAX_CUBES}. */
    public FillPreviewCache(int maxCubes) {
        this(maxCubes, SCAN_CELLS_PER_FRAME);
    }

    /** A cache that also reads at most {@code scanCellsPerFrame} template cells a frame instead of
     *  {@link #SCAN_CELLS_PER_FRAME}. */
    public FillPreviewCache(int maxCubes, int scanCellsPerFrame) {
        this.maxCubes = Math.max(1, maxCubes);
        this.scanCellsPerFrame = Math.max(1, scanCellsPerFrame);
    }

    /**
     * Notes whether the Filler has blocks in flight this frame.
     *
     * @return true once it has been quiet for {@link #IDLE_GRACE_TICKS}
     */
    public boolean isIdle(long gameTime, boolean workInFlight) {
        if (workInFlight || gameTime < lastBusy) {
            lastBusy = gameTime;
        }
        return gameTime - lastBusy >= IDLE_GRACE_TICKS;
    }

    /** The cubes to draw this frame, farthest first, at most {@link #MAX_CUBES} (or the constructor's) of the nearest. */
    public List<BlockPos> cellsToDraw(Level level, Template.BuildingInfo info, Vec3 camera) {
        return cellsToDraw(level, info, camera, level.getGameTime());
    }

    /**
     * {@link #cellsToDraw(Level, Template.BuildingInfo, Vec3)} at the game time {@code now}, which is what the other
     * overload passes. The clock is all a pass's pacing depends on: how long a finished pass took sets how long its
     * result is trusted, and a pass that no frame advanced for {@link #STALL_TICKS} starts over, with the camera and
     * the clock of the frame that resumes it.
     */
    public List<BlockPos> cellsToDraw(Level level, Template.BuildingInfo info, Vec3 camera, long now) {
        if (publishedFor != null && publishedFor != info) {
            // New building info: what was kept is the shape the Filler had, not the one it has.
            publishedFor = null;
            cells = List.of();
            drawOrder = List.of();
            sortedFrom = null;
        }
        if (walk != null && (walk.info() != info || now < walkAdvancedAt || now - walkAdvancedAt > STALL_TICKS)) {
            walk = null;
        }
        if (walk == null && (publishedFor != info || now < publishedAt || now - publishedAt >= trustTicks)) {
            walk = new FillPreview.Walk(info);
            nearestCount = 0;
            passCamera = camera;
            passStartedAt = now;
            if (nearest == null) {
                nearest = new long[maxCubes];
            }
        }
        if (walk != null) {
            walkAdvancedAt = now;
            if (walk.advance(scanCellsPerFrame, (index, pos) -> consider(level, index, pos))) {
                publish(info, now);
                walk = null;
            }
        }
        if (sortedFrom == null || sortedFrom.distanceToSqr(camera) > RESORT_DISTANCE_SQR) {
            drawOrder = farToNear(cells, camera);
            sortedFrom = camera;
        }
        return drawOrder;
    }

    /** Drops everything kept, so a preview switched off holds no cell lists and the next one starts fresh. */
    public void clear() {
        publishedFor = null;
        cells = List.of();
        sortedFrom = null;
        drawOrder = List.of();
        walk = null;
        nearestCount = 0;
        nearest = null;
        trustTicks = RESCAN_TICKS;
    }

    /** One template cell of the pass: kept if the Filler could still fill it and it is among the nearest so far. */
    private void consider(Level level, int index, BlockPos pos) {
        if (!FillPreview.isFillable(level, pos)) {
            return;
        }
        double dx = pos.getX() + 0.5 - passCamera.x;
        double dy = pos.getY() + 0.5 - passCamera.y;
        double dz = pos.getZ() + 0.5 - passCamera.z;
        // Positive floats order like their bit patterns; the index rides in the low half.
        long key = ((long) Float.floatToIntBits((float) (dx * dx + dy * dy + dz * dz)) << 32) | index;
        if (nearestCount < nearest.length) {
            siftUp(nearestCount++, key);
        } else if (key < nearest[0]) {
            siftDown(key);
        }
    }

    /** Keeps the cells the finished pass kept, as the new result. */
    private void publish(Template.BuildingInfo info, long now) {
        Template template = info.getSnapshot();
        List<BlockPos> kept = new ArrayList<>(nearestCount);
        for (int k = 0; k < nearestCount; k++) {
            kept.add(info.toWorld(template.indexToPos((int) nearest[k])));
        }
        cells = kept;
        publishedFor = info;
        publishedAt = now;
        // A pass is spread over frames, so a huge box keeps one running for many ticks. A fixed wait of RESCAN_TICKS
        // after a pass of L ticks scans L / (L + 10) of the time: nine tenths for a 90 tick pass, a slice of the box read
        // every frame almost for ever. Waiting as long as the pass took caps the scanning at half the time.
        trustTicks = Math.max(RESCAN_TICKS, now - passStartedAt);
        sortedFrom = null;
    }

    /** Max-heap on the packed key: the root is the farthest cell kept so far, the first to be replaced. */
    private void siftUp(int at, long key) {
        while (at > 0) {
            int parent = (at - 1) >>> 1;
            if (nearest[parent] >= key) {
                break;
            }
            nearest[at] = nearest[parent];
            at = parent;
        }
        nearest[at] = key;
    }

    /** Replaces the root with {@code key} and restores the heap. */
    private void siftDown(long key) {
        int at = 0;
        int half = nearestCount >>> 1;
        while (at < half) {
            int child = 2 * at + 1;
            if (child + 1 < nearestCount && nearest[child + 1] > nearest[child]) {
                child++;
            }
            if (key >= nearest[child]) {
                break;
            }
            nearest[at] = nearest[child];
            at = child;
        }
        nearest[at] = key;
    }

    /** The cells, farthest first. Sorted on packed (distance bits, index) keys: a primitive sort allocates nothing per
     *  comparison. */
    private static List<BlockPos> farToNear(List<BlockPos> cells, Vec3 from) {
        int count = cells.size();
        long[] keys = new long[count];
        for (int i = 0; i < count; i++) {
            BlockPos p = cells.get(i);
            double dx = p.getX() + 0.5 - from.x;
            double dy = p.getY() + 0.5 - from.y;
            double dz = p.getZ() + 0.5 - from.z;
            keys[i] = ((long) Float.floatToIntBits((float) (dx * dx + dy * dy + dz * dz)) << 32) | i;
        }
        Arrays.sort(keys);
        List<BlockPos> ordered = new ArrayList<>(count);
        for (int k = count - 1; k >= 0; k--) {
            ordered.add(cells.get((int) keys[k]));
        }
        return ordered;
    }
}
