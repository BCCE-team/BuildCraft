"""Connected deposits are logical queue jobs rather than individual block orders."""
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
QUEUE = ROOT / 'source-shared/src/main/java/buildcraft/robotics/scheduler/RobotExecutionQueue.java'

JAVA = r'''
import java.util.*;
import buildcraft.robotics.scheduler.RobotExecutionQueue;
public class VerifyDepositQueue {
 static void ok(boolean b,String m){if(!b)throw new AssertionError(m);}
 static Map<Long,Long> deposit(int first,int last) {
  var map=new LinkedHashMap<Long,Long>();
  for(int i=first;i<=last;i++)map.put((long)i,(long)(100+i));
  return map;
 }
 public static void main(String[] args) {
  var q=new RobotExecutionQueue();
  ok(q.addDeposit(7,deposit(1,10),6),"Cannot enqueue ten-block deposit");
  ok(q.size(7)==1 && q.jobs(7).get(0).remainingBlocks()==10,
      "Ten connected blocks consumed ten job slots");
  ok(q.activate(7,1),"Cannot activate a member of deposit");
  ok(q.jobs(7).get(0).recoveryPosition()!=1,
      "Recovery would persist soon-to-be-mined block when alternatives remain");
  ok(q.remove(7,1).leaseToken()==101,"Break released incorrect block lease");
  ok(q.size(7)==1 && q.jobs(7).get(0).remainingBlocks()==9,
      "First break prematurely completed the whole deposit");
  for(int i=2;i<=10;i++)ok(q.remove(7,i)!=null,"Missing break member "+i);
  ok(q.size(7)==0,"Last break did not complete the deposit");
  for(int i=1;i<=6;i++)ok(q.add(7,20+i,200+i,6),"Cannot add isolated ore");
  ok(q.size(7)==6,"Six isolated ores should be six jobs");
  ok(!q.add(7,30,230,6),"Seventh isolated ore bypassed six-job limit");
  var other=new RobotExecutionQueue();
  other.addDeposit(7,deposit(1,3),6);
  other.addDeposit(7,deposit(5,7),6);
  ok(other.size(7)==2,"Expected separate provisional jobs");
  ok(other.addDeposit(7,deposit(2,6),6),"Cannot merge newly connected deposits");
  ok(other.size(7)==1 && other.jobs(7).get(0).remainingBlocks()==7,
      "Connected job merge lost members or occupied two slots");
  var cancelled=other.removeDeposit(7,3);
  ok(cancelled.remainingBlocks()==7 && other.size(7)==0,
      "Cancelling deposit leaked a second queued job");
  System.out.println("PASS: one deposit per job, individual break completion, six isolated jobs, merge, recovery, cancel");
 }
}
'''


class MiningDepositQueueTests(unittest.TestCase):
    def test_grouped_java_jobs_keep_single_slot_until_last_block(self):
        with tempfile.TemporaryDirectory(prefix='bc-deposit-queue-') as temp:
            test = Path(temp) / 'VerifyDepositQueue.java'
            test.write_text(JAVA)
            compiled = subprocess.run(['javac', '--release', '17', '-d', temp, str(QUEUE), str(test)],
                                      capture_output=True, text=True)
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            result = subprocess.run(['java', '-cp', temp, 'VerifyDepositQueue'],
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_scheduler_reserves_entire_deposit_and_count_reflects_jobs(self):
        scheduler = (ROOT / 'source-shared/src/main/java/buildcraft/robotics/scheduler/RobotProcessorWorkScheduler.java').read_text()
        self.assertIn('board.processorJobMembers(level, seed, MAX_VEIN_MEMBERS)', scheduler)
        self.assertIn('executionQueues.addDeposit(id, members, panel.getMaxExecutionQueue())', scheduler)
        self.assertIn('job.recoveryPosition()', scheduler)
        self.assertIn('int active = executionQueues.size(robotId);', scheduler)
        self.assertIn('return Math.min(panel.getMaxExecutionQueue(), pending.size());', scheduler)
        self.assertIn('isMiningDiscoveryInProgress(EntityRobotBase robot)', scheduler)
        assert 'registry.isTaken(resource) && registry.robotIdTaking(resource) != id' in scheduler


if __name__ == '__main__':
    unittest.main()
