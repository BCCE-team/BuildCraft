"""Stage 1/2: bounded shared scan and safe dispatch regression guards.

Pure index semantics are verified using javac --release 17; Minecraft-dependent
integration is checked at source/preprocessor level (not mistaken for a full build).
"""
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
SCHEDULER = ROOT / 'source-shared/src/main/java/buildcraft/robotics/scheduler/RobotProcessorWorkScheduler.java'
INDEX = ROOT / 'source-shared/src/main/java/buildcraft/robotics/scheduler/RobotWorkIndex.java'
LEASES = ROOT / 'source-shared/src/main/java/buildcraft/robotics/scheduler/RobotWorkLeases.java'
TILE = ROOT / 'source-shared/src/main/java/buildcraft/robotics/tile/TileRobotProcessor.java'
AI = ROOT / 'source-shared/src/main/java/buildcraft/robotics/ai/AIRobotSearchAndGotoBlock.java'
BOARD = ROOT / 'source-shared/src/main/java/buildcraft/robotics/boards/BoardRobotGenericSearchBlock.java'


class SharedSchedulerContractTests(unittest.TestCase):
    def test_pure_index_preserves_long_lived_nearest_lookup_and_leases(self):
        program = r'''
import buildcraft.robotics.scheduler.RobotWorkIndex;
import buildcraft.robotics.scheduler.RobotWorkLeases;
public class VerifyWorkIndex {
    // Exactly mirrors BlockPos.asLong(): sign-safe X/Z and signed 12-bit Y.
    static long pos(int x, int y, int z) {
        return ((long)x & 0x3ffffffL) << 38 | ((long)z & 0x3ffffffL) << 12 | ((long)y & 0xfffL);
    }
    static void ensure(boolean test) { if (!test) throw new AssertionError(); }
    public static void main(String[] args) {
        RobotWorkIndex index = new RobotWorkIndex();
        long a = pos(0, 64, 0), b = pos(-5, 64, -5), c = pos(50, 64, 50);
        index.observe("lumberjack", c, 1);
        index.observe("lumberjack", a, 1);
        index.observe("lumberjack", b, 1);
        ensure(index.count("lumberjack", 1) == 3);
        ensure(index.nearest("lumberjack", -4, 64, -4, 2, 1, id -> true).get(0) == b);
        ensure(index.nearest("lumberjack", -4, 64, -4, 2, 2, id -> id != b).get(0) == a);
        index.observe("miner", pos(3, -20, 9), 5);
        index.forgetUnused(java.util.List.of("lumberjack"));
        ensure(index.count("miner", 5) == 0);
        for (int i = 0; i < 600; i++) index.observe("lumberjack", pos(i, 64, 0), 10);
        ensure(index.count("lumberjack", 10) <= RobotWorkIndex.MAX_CANDIDATES_PER_CATEGORY);
        index.invalidate("lumberjack", pos(599, 64, 0));
        ensure(!index.nearest("lumberjack", 598, 64, 0, 10, 16, id -> true)
                .contains(pos(599, 64, 0)));
        ensure(index.count("lumberjack", 100_000) == 601);
        index.invalidate("lumberjack", a);
        ensure(index.count("lumberjack", 100_001) == 600);
        RobotWorkLeases leases = new RobotWorkLeases();
        ensure(leases.tryClaim(a, 1, 100, 1, 20));
        ensure(!leases.tryClaim(a, 2, 101, 2, 20));
        leases.release(a, 2, -1); // other robot must not release the claim
        ensure(!leases.available(a, 2, 101, 2));
        leases.releasePanel(100);
        long secondToken = leases.claim(a, 2, 101, 3, 20);
        ensure(secondToken > 0L);
        ensure(leases.tryClaim(b, 3, 101, 3, 20));
        leases.release(a, 2, secondToken - 1); // stale callback must not release newer claim
        ensure(!leases.available(a, 3, 102, 4));
        leases.releasePanel(101);
        ensure(leases.size() == 0);
        ensure(leases.tryClaim(a, 1, 100, 4, 20));
        ensure(!leases.tryClaim(a, 2, 101, 23, 20));
        leases.expire(24);
        ensure(leases.tryClaim(a, 2, 101, 24, 20));
    }
}'''
        with tempfile.TemporaryDirectory(prefix='bc-scheduler-index-') as d:
            test = Path(d, 'VerifyWorkIndex.java')
            test.write_text(program)
            subprocess.run(['javac', '--release', '17', '-d', d, str(INDEX), str(LEASES), str(test)],
                           check=True, capture_output=True, text=True)
            result = subprocess.run(['java', '-cp', d, 'VerifyWorkIndex'],
                                    capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_scans_only_loaded_chunks_near_ready_consumers_with_two_limits(self):
        code = SCHEDULER.read_text()
        for fragment in ('PER_PANEL_BLOCK_BUDGET = 384', 'PER_LEVEL_BLOCK_BUDGET = 2048',
                         'level.hasChunkAt(pos)', 'level.getBlockState(pos)',
                         'TileRobotProcessor.WORK_AREA_RADIUS_CHUNKS',
                         'anchor.getX() >> 4', 'index.forgetUnused(List.of())',
                         'index.countMatchingUpTo(entry.getKey(), now', 'MAX_CANDIDATES_PER_CATEGORY'):
            with self.subTest(feature=fragment): self.assertIn(fragment, code)
        self.assertNotIn('level.getChunk(', code)
        self.assertNotIn('setChunkForced(', code)
        self.assertIn('isWithinWorkChain(robot.getBoard())', code)
        self.assertIn('processorAcceptsHints()', code)

    def test_distributes_hints_with_temporary_global_leases_and_local_fallback(self):
        service = SCHEDULER.read_text()
        ai = AI.read_text()
        for fragment in ('state.leases.available(packed', 'state.leases.claim(packed',
                         'registry.isTaken(new ResourceIdBlock(pos))',
                         'board.processorTargetEligible(level, pos)',
                         'state.leases.release(hint.position().asLong(), robot.getRobotId(), hint.leaseToken())', 'robot.getRobotId()'):
            self.assertIn(fragment, service)
        self.assertIn('RobotProcessorWorkScheduler.suggest(robot)', ai)
        self.assertIn('RobotProcessorWorkScheduler.releaseHint(robot, processorHint)', ai)
        self.assertIn('nearestByRoute && !random', ai)
        self.assertIn('!prioritizeShortestPath()', BOARD.read_text())
        for path in (ROOT / 'source-families/old/src/main/java/buildcraft/robotics/ai/AIRobotSearchBlock.java',
                     ROOT / 'source-families/1.21.X/src/main/java/buildcraft/robotics/ai/AIRobotSearchBlock.java',
                     ROOT / 'source-families/26.X/src/main/java/buildcraft/robotics/ai/AIRobotSearchBlock.java',
                     ROOT / 'version-src/26.1.2-neoforge/src/main/java/buildcraft/robotics/ai/AIRobotSearchBlock.java'):
            text = path.read_text()
            self.assertIn('preferredTargets.removeFirst()', text)
            self.assertIn('while (scanner != null && !scanner.isDone()', text)
            self.assertIn('SearchResult proposed = evaluateCandidate(hinted, budget)', text)

    def test_panel_lifecycle_invalidation_and_no_extra_chunk_loading(self):
        source = TILE.read_text()
        self.assertIn('private final RobotProcessorWorkScheduler workScheduler', source)
        self.assertIn('workScheduler.tick(server)', source)
        self.assertIn('workScheduler.onRemoved()', source)
        self.assertIn('workScheduler.onLinkChanged()', source)
        self.assertIn('public boolean allowsWorkPosition(BlockPos target)', source)
        self.assertNotIn('writeLongArray("schedulerJobs"', source)


if __name__ == '__main__': unittest.main()
