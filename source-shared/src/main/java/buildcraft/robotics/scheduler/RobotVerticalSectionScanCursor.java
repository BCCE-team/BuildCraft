package buildcraft.robotics.scheduler;

/**
 * Round-robin cursor for a set of 16x16x16 chunk sections.
 *
 * Rather than exhausting all 4,096 positions in a vertical section, each
 * turn samples 64 positions distributed across the entire 16x16 footprint
 * and across the 16 Y levels. Then the cursor visits the NEXT section.
 * Four turns sample all 256 horizontal columns, and 64 turns cover every
 * x/y/z exactly once. This avoids leaving one side or lower level of a
 * quarry until the very end of a full section scan.
 *
 * Chunk/section loading, scan budgets and the category predicates remain
 * the scheduler's responsibility. This class owns only traversal progress.
 */
public final class RobotVerticalSectionScanCursor {
    public static final int SECTION_WIDTH = 16;
    public static final int BLOCKS_PER_SLICE = 64;
    public static final int SLICE_COUNT = 64;

    private int sectionCount;
    private int sectionIndex;
    private int sliceIndex;
    private int sliceOffset;
    private boolean complete = true;

    public void reset(int count) {
        if (count < 0) throw new IllegalArgumentException("negative section count");
        sectionCount = count;
        sectionIndex = 0;
        sliceIndex = 0;
        sliceOffset = 0;
        complete = count == 0;
    }

    public boolean complete() { return complete; }
    public int sectionIndex() { return sectionIndex; }
    public int sectionCount() { return sectionCount; }
    public int sliceIndex() { return sliceIndex; }
    public int sliceOffset() { return sliceOffset; }

    /** Total potential block checks in this pass after unloaded and
     * irrelevant sections have been pruned by the scheduler. */
    public long totalWorkUnits() {
        return (long) sectionCount * SLICE_COUNT * BLOCKS_PER_SLICE;
    }

    /** Skipping an empty/unloaded section counts its remaining 64 positions
     * as resolved without touching the world. */
    public long completedWorkUnits() {
        if (complete) return totalWorkUnits();
        return ((long) sliceIndex * sectionCount + sectionIndex) * BLOCKS_PER_SLICE + sliceOffset;
    }

    /** 0-100, rounded DOWN; 100 means the cursor truly completed a pass. */
    public int progressPercent() {
        long total = totalWorkUnits();
        if (total == 0) return complete ? 100 : 0;
        return (int) (completedWorkUnits() * 100L / total);
    }

    private int group() { return sliceIndex & 3; }

    // Four interleaved 8x8 chessboard groups cover all 256 columns. Every
    // 64-position turn touches the entire chunk footprint instead of a single
    // x/z stripe; a deterministic height phase spreads reads vertically.
    public int localX() { return ((sliceOffset & 7) << 1) | (group() & 1); }
    public int localZ() { return ((sliceOffset >> 3) << 1) | (group() >> 1); }
    public int localY() {
        return ((sliceIndex >> 2) + 5 * localX() + 11 * localZ()) & 15;
    }

    public void advanceBlock() {
        if (complete) return;
        if (++sliceOffset < BLOCKS_PER_SLICE) return;
        advanceSection();
    }

    /** Skip the current section for this round if it unloaded or its palette no longer matches. */
    public void skipSection() {
        if (!complete) advanceSection();
    }

    private void advanceSection() {
        sliceOffset = 0;
        if (++sectionIndex < sectionCount) return;
        sectionIndex = 0;
        if (++sliceIndex == SLICE_COUNT) complete = true;
    }
}
