"""Real combat UUID claim queue: ownership, fencing, fairness and cleanup."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'source-shared/src/main/java/buildcraft/robotics/scheduler/RobotCombatTargetQueue.java'

FIXTURE = r'''
import java.util.UUID;
import buildcraft.robotics.scheduler.RobotCombatTargetQueue;
public class CombatQueueFixture {
 static void ok(boolean value,String why){if(!value)throw new AssertionError(why);}
 public static void main(String... args){
  var queue=new RobotCombatTargetQueue();
  UUID sheep=UUID.randomUUID(), cow=UUID.randomUUID(), zombie=UUID.randomUUID();
  var first=queue.reserve(sheep,22,1,2,100,100);
  ok(first!=null && first.token()>0 && !first.active(),"initial claim");
  ok(queue.reserve(sheep,22,2,2,100,100)==null,"shared target reserved twice");
  ok(queue.reserve(sheep,44,3,2,100,100)==null,"overlapping panel stole target");
  ok(queue.reserve(sheep,22,1,2,100,100).token()==first.token(),"non idempotent claim");
  var second=queue.reserve(cow,22,1,2,100,100);
  ok(second!=null && queue.reserve(zombie,22,1,2,100,100)==null,"capacity overflow");
  var active=queue.activate(sheep,22,1,120,100);
  ok(active!=null && active.active(),"activate failed");
  queue.trim(22,1,1);
  ok(queue.count(22,1)==1 && queue.jobs(22,1).get(0).entity().equals(sheep),"trim discarded active");
  ok(!queue.release(sheep,1,first.token()+1),"stale token released newer claim");
  ok(queue.renew(sheep,22,1,active.token(),180,100),"active renew failed");
  queue.expire(260);
  ok(queue.count(22,1)==1,"renewed claim expired early");
  ok(queue.release(sheep,1,active.token()),"complete did not release");
  var newer=queue.reserve(sheep,44,3,1,280,100);
  ok(newer!=null && newer.token()!=first.token(),"fencing token reused");
  ok(!queue.release(sheep,1,first.token()),"old attack freed new claim");
  queue.clearPanel(44);
  ok(!queue.isReserved(sheep,280),"panel teardown leaked reservation");
  var another=queue.reserve(cow,33,8,2,280,100);
  queue.clearRobot(33,8);
  ok(!queue.isReserved(cow,280),"unlink leaked reservation");
  System.out.println("PASS: combat UUID queue, duplicate prevention, tokens, shrink, lifecycle");
 }
}
'''

class CombatQueueTests(unittest.TestCase):
    def test_combat_queue_fencing_and_lifecycle(self):
        with tempfile.TemporaryDirectory(prefix='bc-combat-queue-') as td:
            tmp=Path(td)
            fixture=tmp/'CombatQueueFixture.java'
            fixture.write_text(FIXTURE)
            proc=subprocess.run(['javac','--release','17','-d',td,str(SOURCE),str(fixture)],
                                capture_output=True,text=True,timeout=30)
            self.assertEqual(proc.returncode,0,proc.stderr)
            proc=subprocess.run(['java','-cp',td,'CombatQueueFixture'],
                                capture_output=True,text=True,timeout=15)
            self.assertEqual(proc.returncode,0,proc.stdout+proc.stderr)

if __name__=='__main__': unittest.main()
