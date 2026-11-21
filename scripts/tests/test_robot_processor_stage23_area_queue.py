"""Real Java area reservations: cross-panel blast overlap, fencing and cooldown."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'source-shared/src/main/java/buildcraft/robotics/scheduler/RobotAreaActionQueue.java'
FIXTURE = r'''
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
            dest.write_text(FIXTURE)
            result=subprocess.run(['javac','--release','17','-d',td,str(SOURCE),str(dest)],
                                  capture_output=True,text=True,timeout=30)
            self.assertEqual(result.returncode,0,result.stderr)
            run=subprocess.run(['java','-cp',td,'AreaQueueFixture'],capture_output=True,text=True,timeout=15)
            self.assertEqual(run.returncode,0,run.stdout+run.stderr)
if __name__=='__main__':unittest.main()
