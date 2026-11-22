"""Real combat UUID claim queue: ownership, fencing, fairness and cleanup."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[4]
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


# Consolidated related checks from test_area_reservations.py.
from pathlib import Path

import subprocess

import tempfile

import unittest

AREA_SOURCE = ROOT / 'source-shared/src/main/java/buildcraft/robotics/scheduler/RobotAreaActionQueue.java'

AREA_FIXTURE = r'''
import buildcraft.robotics.scheduler.RobotAreaActionQueue;
public class AreaQueueFixture {
 static void ok(boolean value,String message){if(!value)throw new AssertionError(message);}
 static long pos(int x,int y,int z){return ((long)x&0x3ffffffL)<<38|((long)z&0x3ffffffL)<<12|((long)y&0xfffL);}
 public static void main(String...args){
  var q=new RobotAreaActionQueue();var s=RobotAreaActionQueue.Kind.STRIPES;
  var b=RobotAreaActionQueue.Kind.BOMBER;long a=pos(-4,-10,3),near=pos(5,-10,3),far=pos(18,-10,3);
  var first=q.reserve(b,a,1,10,2,100,100);
  ok(first!=null,"first TNT claim missing");
  ok(q.reserve(b,near,2,20,2,100,100)==null,"overlapping TNT from second panel");
  ok(q.reserve(b,far,2,20,2,100,100)!=null,"separated TNT incorrectly blocked");
  ok(q.reserve(s,a,2,30,2,100,100)!=null,"independent stripes role blocked");
  ok(q.reserve(s,a,2,31,2,100,100)==null,"stripes position assigned twice");
  var active=q.activate(b,a,1,10,110,100);
  ok(active!=null&&active.active()&&active.token()==first.token(),"activate changed token");
  q.trim(b,1,10,0);
  ok(q.count(b,1,10)==1,"active TNT discarded on queue resize");
  ok(!q.release(b,a,10,first.token()+1,true,111),"stale callback freed new TNT");
  ok(q.renew(active,150,100),"renew failed");
  q.expire(210);
  ok(q.count(b,1,10)==1,"renewed TNT expired");
  ok(q.release(b,a,10,first.token(),true,215),"TNT completion did not release");
  ok(q.reserve(b,near,2,21,1,220,100)==null,"post-blast overlap allowed");
  q.expire(416);
  ok(q.reserve(b,near,2,21,1,416,100)!=null,"blast cooldown never expires");
  q.clearPanel(2);
  ok(q.count(b,2,21)==0,"panel unload leaked reservation");
  var next=q.reserve(s,pos(0,0,0),9,8,2,420,100);
  ok(next!=null,"fresh stripes claim");
  q.clearRobot(9,8);
  ok(q.available(s,pos(0,0,0),420),"unlink left reservation");
  System.out.println("PASS: fenced stripes and nonoverlapping TNT claims, cooldowns, trims, unload");
 }
}
'''

class AreaActionQueueTests(unittest.TestCase):
    def test_blast_exclusion_and_stripes_fencing(self):
        with tempfile.TemporaryDirectory(prefix='bc-area-queue-') as td:
            dest=Path(td)/'AreaQueueFixture.java'
            dest.write_text(AREA_FIXTURE)
            result=subprocess.run(['javac','--release','17','-d',td,str(AREA_SOURCE),str(dest)],
                                  capture_output=True,text=True,timeout=30)
            self.assertEqual(result.returncode,0,result.stderr)
            run=subprocess.run(['java','-cp',td,'AreaQueueFixture'],capture_output=True,text=True,timeout=15)
            self.assertEqual(run.returncode,0,run.stdout+run.stderr)


if __name__ == "__main__":
    unittest.main()
