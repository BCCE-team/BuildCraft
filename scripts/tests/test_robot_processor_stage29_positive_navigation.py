"""Real AIRobotSearchNearestBlock with a positive-corridor cache on MC stubs.

Checks real A* shortcut, alternate-tool shortest path, world/block changes,
loaded chunk checks, warm-start and precise route-node accounting.
"""
from __future__ import annotations

import subprocess
import tempfile
import unittest
from pathlib import Path

from scripts.tests.test_miner_nearest_route_runtime import (
    ROOT, REAL, STUBS, materialize_old_modern,
)

SCENARIO = r'''package fixture;
import java.util.*;
import java.lang.reflect.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import buildcraft.robotics.ai.*;
import buildcraft.robotics.internal.legacy.robots.*;
import buildcraft.robotics.scheduler.*;
public class PositiveNavigationFixture {
 static int assertions;
 static void ok(boolean yes,String message){assertions++;if(!yes)throw new AssertionError(message);}
 static BlockPos p(int x,int z){return new BlockPos(x,32,z);}
 static BlockState ore(){return new BlockState(new Block("copper_ore"));}
 static int expanded(AIRobotSearchNearestBlock s){
  try{Field f=AIRobotSearchNearestBlock.class.getDeclaredField("expanded");f.setAccessible(true);return f.getInt(s);}
  catch(Exception e){throw new RuntimeException(e);}
 }
 static void run(AIRobotSearchNearestBlock ai,int ticks){
  for(int i=0;i<ticks && ai.blockFound==null;i++)ai.update();
 }
 public static void main(String[]args){
  RobotProcessorWorkScheduler.managed=true;
  var level=new Level();var initial=p(0,0);var oreA=p(40,0);
  level.put(oreA,ore());
  var robot1=new EntityRobotBase(level,initial);
  var first=new AIRobotSearchNearestBlock(robot1,(world,at)->at.equals(oreA),0,List.of(oreA));
  run(first,200);
  ok(first.blockFound!=null&&oreA.equals(first.blockFound.toBlockPos()),"first A* found no ore");
  ok(expanded(first)>0,"first A* did not actually search");
  ok(first.path.size()==39,"first A* path witness length wrong");
  ok(RobotProcessorWorkScheduler.positive.get(level).entries()==1,"success did not store positive witness");
  var robot2=new EntityRobotBase(level,initial);
  var reused=new AIRobotSearchNearestBlock(robot2,(world,at)->at.equals(oreA),0,List.of(oreA));
  run(reused,10);
  ok(reused.blockFound!=null&&reused.path.size()==39,"same-start path not reused");
  ok(expanded(reused)==0,"exact valid route did another A* exploration");
  ok(RobotProcessorWorkScheduler.sessions.activeSearches()==0,"cache hit left a native A* lease");
  var interrupted=new AIRobotSearchNearestBlock(new EntityRobotBase(level,initial),
      (world,at)->at.equals(oreA),0,List.of(oreA));
  interrupted.update();
  ok(interrupted.blockFound==null&&expanded(interrupted)==0,
     "cache validation was not distributed across ticks");
  // The passage changes after 32 waypoints have been checked. A stale
  // positive witness must not finish when the next chunk of checks resumes.
  var changedMidValidation=p(35,0);
  level.put(changedMidValidation,new BlockState(new Block("temporarily_blocked")));
  RobotProcessorWorkScheduler.positive.get(level).worldChanged(changedMidValidation.asLong());
  run(interrupted,120);
  ok(interrupted.blockFound!=null&&oreA.equals(interrupted.blockFound.toBlockPos()),
     "invalidation during validation prevented an alternate route");
  ok(expanded(interrupted)>0,"invalidation during partial validation reused a stale direct route");
  level.mine(changedMidValidation);
  RobotProcessorWorkScheduler.positive.get(level).worldChanged(changedMidValidation.asLong());

  // A different robot/tool can accept previously filtered-out nearer work.
  // The positive cache must NOT force the old, farther target.
  BlockPos close=p(4,4);level.put(close,ore());
  var selective=new AIRobotSearchNearestBlock(new EntityRobotBase(level,initial),
       (world,at)->at.equals(oreA),0,List.of(oreA,close));
  run(selective,300);
  ok(selective.blockFound!=null&&oreA.equals(selective.blockFound.toBlockPos()),"selective initial search");
  var flexible=new AIRobotSearchNearestBlock(new EntityRobotBase(level,initial),
       (world,at)->at.equals(oreA)||at.equals(close),0,List.of(oreA,close));
  run(flexible,60);
  ok(flexible.blockFound!=null&&close.equals(flexible.blockFound.toBlockPos()),
      "cached distant ore stole a now-eligible closer goal");
  ok(expanded(flexible)>0,"unsafe multi-goal exact shortcut bypassed A*");

  // Ore was mined; only the corridor should survive as a warm-start to a
  // nearby second ore. A* must still choose the valid current target.
  level.mine(oreA);
  RobotProcessorWorkScheduler.positive.get(level).worldChanged(oreA.asLong());
  var nextOre=p(41,1);level.put(nextOre,ore());
  var warm=new AIRobotSearchNearestBlock(new EntityRobotBase(level,initial),
      (world,at)->at.equals(nextOre),0,List.of(nextOre));
  run(warm,100);
  ok(warm.blockFound!=null&&nextOre.equals(warm.blockFound.toBlockPos()),
     "warm corridor did not lead to new ore");
  ok(expanded(warm)>0,"changed-goal corridor improperly skipped A*");
  int expandedWithWarmCorridor=expanded(warm);
  // Identical world and filters, but erase the positive cache to measure how
  // much A* exploration the verified corridor avoided.
  RobotProcessorWorkScheduler.positive.remove(level);
  var uncached=new AIRobotSearchNearestBlock(new EntityRobotBase(level,initial),
      (world,at)->at.equals(nextOre),0,List.of(nextOre));
  run(uncached,100);
  ok(uncached.blockFound!=null&&nextOre.equals(uncached.blockFound.toBlockPos()),
      "uncached control could not find the same ore");
  ok(expanded(uncached)>expandedWithWarmCorridor,
      "validated positive corridor did not reduce A* expansions: warm="+
      expandedWithWarmCorridor+" cold="+expanded(uncached));

  // A path can be invalidated by an addon without firing the panel event.
  // Revalidation must see the newly placed solid block before reuse.
  level.put(oreA,ore());
  var blocker=p(20,0);
  level.put(blocker,new BlockState(new Block("wall")));
  var blocked=new AIRobotSearchNearestBlock(new EntityRobotBase(level,initial),
      (world,at)->at.equals(oreA),0,List.of(oreA));
  run(blocked,150);
  ok(blocked.blockFound!=null&&oreA.equals(blocked.blockFound.toBlockPos()),
      "blocked old corridor prevented finding an alternate route");
  ok(expanded(blocked)>0,"solid corridor accepted without A* revalidation");

  // A cache lookup must not read an unloaded chunk. The original search
  // remains free to wait rather than force-load a route.
  var otherLevel=new Level();otherLevel.put(oreA,ore());
  var r3=new EntityRobotBase(otherLevel,initial);
  var known=new AIRobotSearchNearestBlock(r3,(world,at)->at.equals(oreA),0,List.of(oreA));
  run(known,100);
  ok(known.blockFound!=null,"second world's route missing");
  otherLevel.unloadXChunk(1);
  var unloaded=new AIRobotSearchNearestBlock(new EntityRobotBase(otherLevel,initial),
       (world,at)->at.equals(oreA),0,List.of(oreA));
  unloaded.update();
  ok(unloaded.blockFound==null,"used cached route traversing an unloaded chunk");
  ok(RobotProcessorWorkScheduler.positive.get(level) != RobotProcessorWorkScheduler.positive.get(otherLevel),
      "route cache accidentally crossed dimensions");
  RobotProcessorWorkScheduler.managed=false;
  var autonomous=new AIRobotSearchNearestBlock(new EntityRobotBase(level,initial),
      (world,at)->at.equals(close),0);
  run(autonomous,100);
  ok(autonomous.blockFound!=null&&close.equals(autonomous.blockFound.toBlockPos()),
      "autonomous miner behavior changed");
  System.out.println("PASS "+assertions+" real A*: exact hit, alternate filters, warm corridor, walls, unloaded chunks, autonomous");
 }
}'''

class PositiveRouteSearchRuntimeTests(unittest.TestCase):
    def test_real_search_shortcuts_and_safe_fallback_across_mc_families(self):
        for version in ('forge','neoforge-1.21.11','neoforge-26.2'):
            with self.subTest(version=version),tempfile.TemporaryDirectory(prefix='bc-positive-ai-') as tmp:
                root=Path(tmp);files=[];stubs=dict(STUBS)
                stubs.pop('fixture/NearestOreFixture.java',None)
                for rel,text in stubs.items():
                    p=root/rel;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
                    files.append(str(p))
                for rel in REAL:
                    p=root/rel;p.parent.mkdir(parents=True,exist_ok=True)
                    text=(ROOT/rel).read_text()
                    if rel.endswith('AIRobotSearchNearestBlock.java'):
                        text=materialize_old_modern(text,version=='forge')
                    p.write_text(text);files.append(str(p))
                fixture=root/'fixture/PositiveNavigationFixture.java'
                fixture.parent.mkdir(parents=True,exist_ok=True);fixture.write_text(SCENARIO)
                files.append(str(fixture))
                out=root/'classes';out.mkdir()
                result=subprocess.run(['javac','--release','17','-d',str(out),*files],
                                      capture_output=True,text=True,timeout=55)
                self.assertEqual(result.returncode,0,result.stdout+result.stderr)
                run=subprocess.run(['java','-cp',str(out),'fixture.PositiveNavigationFixture'],
                                   capture_output=True,text=True,timeout=60)
                self.assertEqual(run.returncode,0,run.stdout+run.stderr)

if __name__=='__main__':unittest.main()
