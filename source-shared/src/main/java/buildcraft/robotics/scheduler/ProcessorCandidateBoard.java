package buildcraft.robotics.scheduler;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Opt-in contract for blocks that have an ordinary robot execution AI.
 * The central index never performs an action and never grants permissions.
 * Implementations must revalidate dynamic tools, seeds and gate restrictions
 * for the actual robot before a candidate may be offered.
 */
public interface ProcessorCandidateBoard {
    String processorWorkCategory();

    /** Explicit opt-in: the planner may maintain a bounded queue of real
     * useful jobs for this board. External candidate providers remain hint-only. */
    default boolean processorSupportsQueue() { return false; }

    /** A positive observation, not a grant to execute the action. No chunk loads. */
    boolean processorScanCandidate(Level level, BlockPos position);

    /** Broad, tool-independent discovery. Final execution eligibility is always rechecked. */
    default boolean processorDiscoveryCandidate(Level level, BlockPos position) {
        return processorScanCandidate(level, position);
    }

    /** Avoid rereading the same block when the scanner already owns its state. */
    default boolean processorDiscoveryCandidate(Level level, BlockPos position,
            net.minecraft.world.level.block.state.BlockState state) {
        return processorDiscoveryCandidate(level, position);
    }

    /** Conservative palette check. False means this state can NEVER contain work
     * for the profession; default true protects third-party extensions. */
    default boolean processorPaletteMightContain(net.minecraft.world.level.block.state.BlockState state) {
        return true;
    }


    /** Called immediately before offering the observation to this robot. */
    boolean processorTargetEligible(Level level, BlockPos position);

    /** True only if every board of this category has exactly the same scan
     * predicate regardless of robot, tool, inventory or remembered work. */
    default boolean processorSharedScanPredicate() { return false; }

    /** Already discovered targets (e.g. a connected vein). No extra world scanning. */
    default java.util.List<BlockPos> processorKnownCandidates() { return java.util.List.of(); }

    /** Queue planning may include the next deposit while the miner still works
     * its current vein. Actual execution keeps the board's stricter predicate. */
    default boolean processorPlanningEligible(Level level, BlockPos position) {
        return processorTargetEligible(level, position);
    }

    /** The useful work grouped with this target (one ore vein = one job).
     * Default to a singleton for professions without a deposit concept. */
    default java.util.List<BlockPos> processorJobMembers(Level level, BlockPos position, int max) {
        return java.util.List.of(position);
    }

    /** Specialized shortest-route workers can reject direct target hints. */
    default boolean processorAcceptsHints() { return true; }
}
