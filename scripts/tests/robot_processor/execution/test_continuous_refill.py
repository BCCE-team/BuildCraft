"""Regressions for live (no world reload) discovery and connected job refill.

The simulated Minecraft world compiles the real scheduler for legacy and modern
source families; the cursor test proves disjoint nearest-first 3D scanning.
"""
import subprocess
import tempfile
import unittest
from pathlib import Path

from scripts.tests.robot_processor.discovery.test_scheduler_runtime import ROOT, STUBS, SOURCES
from scripts.source_preprocessor import preprocess_text

CURSOR = r'''
import java.util.*;
import buildcraft.robotics.scheduler.RobotLocalDiscoveryProbe;
public class FocusProbeCheck {
 static void ok(boolean v,String reason){if(!v)throw new AssertionError(reason);}
 public static void main(String[] args) {
  var probe=new RobotLocalDiscoveryProbe(10,64,-20,3);
  Set<RobotLocalDiscoveryProbe.Position> found=new HashSet<>();
  int observations=0, ringTwoHit=-1, previousRadius=1;
  while(probe.hasNext()) {
   var pos=probe.next();
   ok(pos!=null,"Probe unexpectedly exhausted");
   int distance=Math.max(Math.abs(pos.x()-10),Math.max(Math.abs(pos.y()-64),Math.abs(pos.z()+20)));
   ok(distance>=previousRadius,"Went backwards in shell priority");
   ok(distance>=1 && distance<=3,"Outside bounded scan");
   ok(found.add(pos),"Duplicate world-read position");
   if(pos.equals(new RobotLocalDiscoveryProbe.Position(12,64,-20)))ringTwoHit=observations;
   previousRadius=distance;
   observations++;
  }
  ok(observations==342,"Non-disjoint 3D shells: "+observations);
  ok(ringTwoHit>=26 && ringTwoHit<124,"Air-gap ore not reached in the first 124 reads");
  ok(probe.next()==null,"Exhausted scan returned a position");
  System.out.println("PASS: 342 positions once, nearby independent ore in two shells");
 }
}
'''

SCENARIO = r'''
package fixture;
import java.util.*;
import buildcraft.robotics.scheduler.*;
import buildcraft.robotics.internal.legacy.robots.*;
import buildcraft.robotics.tile.TileRobotProcessor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
public class ContinuousRefillCheck {
 static void ok(boolean value,String why){if(!value) throw new AssertionError(why);}
 public static void main(String[] args){
  var world=new ServerLevel();
  world.target=new BlockPos(99,65,99); // No incidental ore in the fixture.
  for(int x=2;x<=12;x+=2)world.extra.add(new BlockPos(x,65,2));
  var a=new EntityRobotBase(world,17,new BlockPos(1,65,2));
  var b=new EntityRobotBase(world,18,new BlockPos(1,65,3));
  a.setBoardId("buildcraft:miner"); b.setBoardId("buildcraft:miner");
  RobotManager.registryProvider = level -> new IRobotRegistry() {
   public EntityRobotBase getLoadedRobot(long id){return id==17?a:id==18?b:null;}
   public boolean isTaken(ResourceIdBlock res){return false;}
   public long robotIdTaking(ResourceIdBlock res){return 0;}
  };
  var panel=new TileRobotProcessor(world,new BlockPos(0,65,0));
  panel.max=6;panel.linked.add(17L);panel.linked.add(18L);
  var scheduler=new RobotProcessorWorkScheduler(panel);
  world.time++;scheduler.tick(world);
  // A real miner has selected the first ore during play, not after reloading.
  var seed=new BlockPos(2,65,2);
  RobotProcessorWorkScheduler.targetAcquired(a,seed);
  ok(scheduler.queueCount(17)>=1,"Acquired ore was not accounted as a useful job");
  int countEarly=0;
  for(int tick=0;tick<14;tick++){
   world.time++;scheduler.tick(world);
   if(tick==3)countEarly=scheduler.queueCount(17)+scheduler.queueCount(18);
  }
  ok(countEarly>=2,"Ore separated by air not discovered before next search");
  Set<BlockPos> union=new HashSet<>(RobotProcessorWorkScheduler.queuedMiningTargets(a));
  int bCount=RobotProcessorWorkScheduler.queuedMiningTargets(b).size();
  union.addAll(RobotProcessorWorkScheduler.queuedMiningTargets(b));
  ok(union.size()>=5,"Live scheduler failed to prefill independent deposits");
  ok(union.size()==RobotProcessorWorkScheduler.queuedMiningTargets(a).size()+bCount,
     "Two miners shared a claimed deposit");
  // Consume already scheduled work while the server stays running.
  for(BlockPos pos : List.copyOf(union)) {
   world.extra.remove(pos);
   RobotProcessorWorkScheduler.executionFinished(a,pos,true);
   RobotProcessorWorkScheduler.executionFinished(b,pos,true);
  }
  // Fresh ore appears in the same nearby area without logout/rejoin.
  world.extra.add(new BlockPos(3,65,4));
  world.extra.add(new BlockPos(5,65,4));
  RobotProcessorWorkScheduler.worldBlockChanged(world,new BlockPos(3,65,4));
  RobotProcessorWorkScheduler.worldBlockChanged(world,new BlockPos(5,65,4));
  for(int tick=0;tick<8;tick++){world.time++;scheduler.tick(world);}
  var next=new HashSet<>(RobotProcessorWorkScheduler.queuedMiningTargets(a));
  next.addAll(RobotProcessorWorkScheduler.queuedMiningTargets(b));
  ok(next.contains(new BlockPos(3,65,4)) || next.contains(new BlockPos(5,65,4)),
     "New jobs were not issued until the next world reload");
  scheduler.onRemoved();
  System.out.println("PASS: live initial prefill, no duplicate work, refill without reload");
 }
}
'''


class ContinuousRefillTests(unittest.TestCase):
    def test_nearest_first_probe_reaches_air_gapped_ore_without_duplicates(self):
        with tempfile.TemporaryDirectory(prefix='bc-focused-probe-') as d:
            src=Path(d)/'FocusProbeCheck.java'
            src.write_text(CURSOR)
            probe=ROOT/'source-shared/src/main/java/buildcraft/robotics/scheduler/RobotLocalDiscoveryProbe.java'
            c=subprocess.run(['javac','--release','17','-d',d,str(probe),str(src)],capture_output=True,text=True)
            self.assertEqual(c.returncode,0,c.stderr)
            r=subprocess.run(['java','-cp',d,'FocusProbeCheck'],capture_output=True,text=True)
            self.assertEqual(r.returncode,0,r.stdout+r.stderr)

    def test_live_miner_prefills_and_refills_without_nbt_reload(self):
        for version,family,platform in [('1.19.2','old','forge'),('26.3','26.X','neoforge')]:
            with self.subTest(version=version),tempfile.TemporaryDirectory(prefix='bc-live-refill-') as d:
                stubs=dict(STUBS)
                level='net/minecraft/world/level/Level.java'
                stubs[level]=stubs[level].replace(' public long time; public boolean sectionAir;',
                    ' public java.util.Set<BlockPos> extra = new java.util.HashSet<>();\n public long time; public boolean sectionAir;').replace(
                    'return new BlockState(!pos.equals(target));',
                    'return new BlockState(!pos.equals(target) && !extra.contains(pos));')
                board='buildcraft/robotics/boards/BoardRobotGenericSearchBlock.java'
                stubs[board]=stubs[board].replace('private final NBT nbt = new NBT("lumberjack");',
                    'private NBT nbt = new NBT("lumberjack"); public void setBoardId(String id){nbt=new NBT(id);}')
                robot='buildcraft/robotics/internal/legacy/robots/EntityRobotBase.java'
                stubs[robot]=stubs[robot].replace(' public long getRobotId(){return id;}',
                    ' public void setBoardId(String id){board.setBoardId(id);}\n public long getRobotId(){return id;}')
                panel='buildcraft/robotics/tile/TileRobotProcessor.java'
                stubs[panel]=stubs[panel].replace(' public int getMaxExecutionQueue(){return 5;}',
                    ' public int max=5; public int getMaxExecutionQueue(){return max;}')
                files=[]
                for rel,txt in stubs.items():
                    dest=Path(d)/rel;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_text(txt);files.append(str(dest))
                for rel in SOURCES:
                    dest=Path(d)/rel.removeprefix('source-shared/src/main/java/')
                    dest.parent.mkdir(parents=True,exist_ok=True)
                    dest.write_text(preprocess_text((ROOT/rel).read_text(),minecraft=version,
                        family=family,platform=platform,source=rel))
                    files.append(str(dest))
                demo=Path(d)/'fixture/ContinuousRefillCheck.java'
                demo.parent.mkdir(parents=True,exist_ok=True);demo.write_text(SCENARIO)
                files.append(str(demo))
                classes=Path(d)/'classes';classes.mkdir()
                c=subprocess.run(['javac','--release','17','-d',str(classes),*files],
                    capture_output=True,text=True,timeout=40)
                self.assertEqual(c.returncode,0,c.stderr)
                r=subprocess.run(['java','-cp',str(classes),'fixture.ContinuousRefillCheck'],
                    capture_output=True,text=True,timeout=20)
                self.assertEqual(r.returncode,0,r.stdout+r.stderr)


if __name__=='__main__': unittest.main()
