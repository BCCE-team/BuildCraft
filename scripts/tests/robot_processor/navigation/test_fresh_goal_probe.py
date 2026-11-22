"""A new ore after a 32k-node failure gets a bounded probe, not silent starvation.

Only a *proven* shortest path can end the probe directly. Otherwise normal
multi-goal A* resumes with its original target set and total node cap.
"""
from __future__ import annotations
import subprocess
import tempfile
import unittest
from pathlib import Path
from scripts.tests.test_miner_nearest_route_runtime import ROOT, REAL, STUBS, materialize_old_modern

SCENARIO=r'''package fixture;
import java.util.*;import java.lang.reflect.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import buildcraft.robotics.ai.*;
import buildcraft.robotics.internal.legacy.robots.*;
import buildcraft.robotics.scheduler.*;
public class FreshGoalFixture {
 static int checks;
 static void ok(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
 static BlockPos p(int x,int z){return new BlockPos(x,32,z);}
 static BlockState ore(){return new BlockState(new Block("ore"));}
 static int expansions(AIRobotSearchNearestBlock ai){try{
  Field f=AIRobotSearchNearestBlock.class.getDeclaredField("expanded");f.setAccessible(true);return f.getInt(ai);
 }catch(Exception ex){throw new RuntimeException(ex);}}
 static boolean probe(AIRobotSearchNearestBlock ai){try{
  Field f=AIRobotSearchNearestBlock.class.getDeclaredField("freshProbeActive");f.setAccessible(true);return f.getBoolean(ai);
 }catch(Exception ex){throw new RuntimeException(ex);}}
 static void run(AIRobotSearchNearestBlock ai){for(int i=0;i<200&&ai.blockFound==null;i++)ai.update();}
 static void setup(List<BlockPos> old,BlockPos start){
  RobotProcessorWorkScheduler.failures.worldChanged();
  ok(RobotProcessorWorkScheduler.failures.remember(start,old,List.of(start),100,false)==old.size(),
   "could not set fresh negative witnesses");
  List<BlockPos> plus=new ArrayList<>(old);plus.add(p(2,1));
  ok(RobotProcessorWorkScheduler.failures.untestedGoals(start,plus,100).equals(List.of(p(2,1))),
     "overlapping failed set not filtered");
 }
 public static void main(String...unused){
  RobotProcessorWorkScheduler.managed=true;
  BlockPos start=p(0,0);
  // Previous expensive A* from this exact start saw twelve old ores. The new
  // nearby ore is cheap to reach, and the old ones have a strictly worse
  // Manhattan lower bound. The probe may safely finish immediately.
  var level=new Level();List<BlockPos> far=new ArrayList<>();
  for(int i=0;i<12;i++){BlockPos old=p(36+i,0);far.add(old);level.put(old,ore());}
  BlockPos fresh=p(2,1);level.put(fresh,ore());
  setup(far,start);
  List<BlockPos> all=new ArrayList<>(far);all.add(fresh);
  var fast=new AIRobotSearchNearestBlock(new EntityRobotBase(level,start),
       (world,at)->all.contains(at),0,all);
  run(fast);
  ok(fast.blockFound!=null&&fast.blockFound.toBlockPos().equals(fresh),"fresh nearby ore missed");
  ok(probe(fast),"near-fresh safe search did not take the bounded probe path");
  ok(expansions(fast)<100,"fresh target did not complete with bounded cost");
  // Crucial: the earlier 32k-node limit is NOT proof that a previously
  // explored nearer ore is impossible. If the fresh candidate has a longer
  // path, we MUST fall back and take the reachable shorter old goal.
  var another=new Level();List<BlockPos> nearOld=new ArrayList<>();
  for(int i=0;i<12;i++){BlockPos old=p(3+i,0);nearOld.add(old);another.put(old,ore());}
  BlockPos distant=p(26,1);another.put(distant,ore());
  RobotProcessorWorkScheduler.failures.worldChanged();
  ok(RobotProcessorWorkScheduler.failures.remember(start,nearOld,List.of(start),100,false)==12,
      "could not set old-target failures");
  List<BlockPos> targets=new ArrayList<>(nearOld);targets.add(distant);
  var fallback=new AIRobotSearchNearestBlock(new EntityRobotBase(another,start),
       (world,at)->targets.contains(at),0,targets);
  run(fallback);
  ok(fallback.blockFound!=null&&fallback.blockFound.toBlockPos().equals(p(3,0)),
     "fresh-goal probe incorrectly suppressed a reachable shorter old target");
  ok(!probe(fallback),"unsafe probe hit never returned to full A*");
  ok(expansions(fallback)<=32768,"probe got a free second 32k-node limit");
  // TTL never permanently hides old targets; a world-change event flushes
  // partial hints so even the old goals are immediately eligible again.
  // Probe frontier can also run out without any route. Fall back to every
  // original target, not just the fresh one; old ore is still reachable.
  var tunnel=new Level();tunnel.setSolidByDefault(true);
  List<BlockPos> tunnelOld=new ArrayList<>();
  for(int x=0;x<=22;x++)tunnel.put(p(x,0),BlockState.AIR);
  for(int i=2;i<=13;i++){
   BlockPos old=p(i,1);tunnelOld.add(old);tunnel.put(old,ore());
  }
  BlockPos unreachable=p(30,3);tunnel.put(unreachable,ore());
  RobotProcessorWorkScheduler.failures.worldChanged();
  ok(RobotProcessorWorkScheduler.failures.remember(start,tunnelOld,List.of(start),100,false)==12,
     "could not set compact cave failures");
  List<BlockPos> tunnelGoals=new ArrayList<>(tunnelOld);tunnelGoals.add(unreachable);
  var exhaustedProbe=new AIRobotSearchNearestBlock(new EntityRobotBase(tunnel,start),
      (world,at)->tunnelGoals.contains(at),0,tunnelGoals);
  run(exhaustedProbe);
  ok(exhaustedProbe.blockFound!=null&&exhaustedProbe.blockFound.toBlockPos().equals(p(2,1)),
      "empty fresh-goal probe did not fall back to accessible old ore");
  ok(!probe(exhaustedProbe),"empty probe left full A* disabled");
  ok(expansions(exhaustedProbe)<=32768,"fallback double-counted A* nodes");
  RobotProcessorWorkScheduler.failures.worldChanged();
  ok(RobotProcessorWorkScheduler.failures.untestedGoals(start,targets,100).size()==targets.size(),
    "world mutation did not release negative hints");
  ok(RobotProcessorWorkScheduler.failures.untestedGoals(start,targets,201).size()==targets.size(),
    "hint expiration did not restore old targets");
  System.out.println("PASS "+checks+" new-target probe, strict optimality fallback, cap, invalidation");
 }
}'''

class FreshGoalProbeRuntimeTests(unittest.TestCase):
    def test_fresh_goal_probe_preserves_global_target_choice(self):
        for version in ('forge','neoforge-1.21.11','neoforge-26.2'):
            with self.subTest(version=version), tempfile.TemporaryDirectory(prefix='bc-fresh-goal-') as td:
                root=Path(td);files=[];stubs=dict(STUBS)
                stubs.pop('fixture/NearestOreFixture.java',None)
                for rel,code in stubs.items():
                    p=root/rel;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(code);files.append(str(p))
                for rel in REAL:
                    p=root/rel;p.parent.mkdir(parents=True,exist_ok=True)
                    code=(ROOT/rel).read_text()
                    if rel.endswith('AIRobotSearchNearestBlock.java'):
                        code=materialize_old_modern(code,version=='forge')
                    p.write_text(code);files.append(str(p))
                case=root/'fixture/FreshGoalFixture.java';case.parent.mkdir(parents=True,exist_ok=True)
                case.write_text(SCENARIO);files.append(str(case))
                out=root/'classes';out.mkdir()
                c=subprocess.run(['javac','--release','17','-d',str(out),*files],capture_output=True,text=True,timeout=50)
                self.assertEqual(c.returncode,0,c.stdout+c.stderr)
                run=subprocess.run(['java','-cp',str(out),'fixture.FreshGoalFixture'],capture_output=True,text=True,timeout=30)
                self.assertEqual(run.returncode,0,run.stdout+run.stderr)

if __name__=='__main__':unittest.main()
