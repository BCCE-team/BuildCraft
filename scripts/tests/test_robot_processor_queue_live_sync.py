"""Prevent GUI queue counts and panel snapshot lifecycle from regressing."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
ROBOTICS = ROOT / "source-shared/src/main/java/buildcraft/robotics"

class QueueLiveSyncTests(unittest.TestCase):
    def test_client_receives_new_queue_count_between_periodic_snapshots(self):
        src = (ROBOTICS / "container/ContainerRobotProcessor.java").read_text()
        self.assertIn("normalRefresh || queueCountsChanged(processor)", src)
        self.assertIn("snapshot.queued() != processor.getExecutionQueueSize(snapshot.id())", src)
        self.assertIn("buf.writeByte(robot.queued())", src)
        self.assertIn("buffer.readUnsignedByte()", src)

    def test_running_unscheduled_break_counts_as_one_actual_operation(self):
        src = (ROBOTICS / "tile/TileRobotProcessor.java").read_text()
        self.assertIn("int prepared = workScheduler.queueCount(robotId);", src)
        self.assertIn("if (active instanceof AIRobotBreak) return 1;", src)
        self.assertIn("!active.isWithinWorkChain(robot.getBoard())", src)
        self.assertIn("active instanceof AIRobotFetchItem fetch && fetch.hasPickupTarget()", src)

    def test_unloading_panel_does_not_redirty_its_chunk_while_saving(self):
        src = (ROBOTICS / "tile/TileRobotProcessor.java").read_text()
        segment = src.split("public void onChunkUnloaded() {", 1)[1].split("public void onRemove(boolean", 1)[0]
        self.assertIn("persistedMiningWork = workScheduler.pendingWorkSnapshot()", segment)
        self.assertIn("workScheduler.onRemoved()", segment)
        self.assertNotIn("setChanged();", segment)
        self.assertIn('data.writeLongArray("pendingMiningWork", savedMiningWork())', src)

if __name__ == "__main__":
    unittest.main()
