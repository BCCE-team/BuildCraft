"""Actual search AI sends measurable navigation events to the real profiler."""
from __future__ import annotations
from pathlib import Path
import subprocess
import tempfile
import unittest

from scripts.tests.test_miner_nearest_route_runtime import (
    ROOT, REAL, STUBS, materialize_old_modern,
)

FIXTURE=r'''
package fixture;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import buildcraft.robotics.ai.*;
import buildcraft.robotics.internal.legacy.robots.*;
import buildcraft.robotics.scheduler.*;
public class NavigationHooksFixture {
 static int assertions;
 static void ok(boolean yes,String why){assertions++;if(!yes)throw new AssertionError(why);}
 static BlockPos pos(int x,int z){return new BlockPos(x,32,z);}
 static BlockState ore(){return new BlockState(new Block("iron_ore"));}
 static void run(AIRobotSearchNearestBlock ai){for(int n=0;n<100&&ai.blockFound==null;n++)ai.update();}
 public static void main(String[]args){
  RobotProcessorWorkScheduler.managed=true;
  RobotProcessorWorkScheduler.navigation.beginTick(100);
  var world=new Level();BlockPos origin=pos(0,0), target=pos(40,0);
  world.put(target,ore());
  var a=new AIRobotSearchNearestBlock(new EntityRobotBase(world,origin),
      (lvl,at)->at.equals(target),0,List.of(target));
  run(a);ok(a.blockFound!=null,"A* did not discover first ore");
  ok(RobotProcessorWorkScheduler.navigation.snapshot(0).aStarExpanded()==0,
      "unfinished 20-tick sample leaked into GUI");
  var route=RobotProcessorWorkScheduler.positive.get(world).find(100,origin.asLong(),List.of(target.asLong()));
  ok(route!=null&&route.route().referenceExpandedNodes()>0,"real A* effort not recorded in witness");
  var b=new AIRobotSearchNearestBlock(new EntityRobotBase(world,origin),
      (lvl,at)->at.equals(target),0,List.of(target));
  run(b);ok(b.blockFound!=null&&b.path.size()==a.path.size(),"direct route not reused");
  RobotProcessorWorkScheduler.navigation.beginTick(120);
  var snap=RobotProcessorWorkScheduler.navigation.snapshot(1);
  ok(snap.storedRoutes()==1&&snap.cacheProbes()>=2,"cache stores/probes missing");
  ok(snap.cacheCandidates()>=1&&snap.directHits()==1,"actual direct-hit metric missing");
  ok(snap.aStarExpanded()>0&&snap.aStarPolled()>=snap.aStarExpanded(),"real A* expansions missing");
  ok(snap.validatedWaypoints()==a.path.size(),"verified route length not charged");
  ok(snap.estimatedReferenceNodesBypassed()==route.route().referenceExpandedNodes(),
     "estimated reference nodes not derived from original A*");
  ok(snap.grantedNodes()>0&&snap.refundedNodes()>=0,"route-node accounting missing");
  RobotProcessorWorkScheduler.navigation.beginTick(121);
  world.mine(target);
  RobotProcessorWorkScheduler.positive.get(world).worldChanged(target.asLong());
  BlockPos next=pos(41,1);world.put(next,ore());
  var c=new AIRobotSearchNearestBlock(new EntityRobotBase(world,origin),
      (lvl,at)->at.equals(next),0,List.of(next));
  run(c);ok(c.blockFound!=null,"warm A* failed");
  RobotProcessorWorkScheduler.navigation.beginTick(141);
  var warm=RobotProcessorWorkScheduler.navigation.snapshot(2);
  ok(warm.warmStarts()==1,"warm-start event not recorded by actual AI");
  ok(warm.aStarExpanded()>0,"warm hint bypassed normal A*");
  RobotProcessorWorkScheduler.managed=false;
  RobotProcessorWorkScheduler.navigation.reset();
  var independent=new AIRobotSearchNearestBlock(new EntityRobotBase(world,origin),
      (lvl,at)->at.equals(next),0);
  run(independent);
  RobotProcessorWorkScheduler.navigation.beginTick(200);
  ok(independent.blockFound!=null,"autonomous path changed");
  ok(RobotProcessorWorkScheduler.navigation.snapshot(0).aStarExpanded()==0,
      "autonomous A* wrongly attributed to processor");
  System.out.println("PASS "+assertions+" real A* cache instrumentation and autonomous fallback");
 }
}
'''

class NavigationRuntimeTests(unittest.TestCase):
    def test_real_search_instruments_direct_warm_and_autonomous(self):
        for version in ('forge','neoforge-1.21.11','neoforge-26.2'):
            with self.subTest(version=version),tempfile.TemporaryDirectory(prefix='bc-stage30-ai-') as td:
                folder=Path(td); sources=[]; stubs=dict(STUBS)
                stubs.pop('fixture/NearestOreFixture.java',None)
                rel='buildcraft/robotics/scheduler/RobotProcessorWorkScheduler.java'
                source=stubs[rel]
                source=source.replace(''' public static int routeBudgetRequests;''',''' public static int routeBudgetRequests;
 public static RobotProcessorNavigationProfiler navigation=new RobotProcessorNavigationProfiler();''')
                source=source.replace('''public static int requestRouteSearchBudget(EntityRobotBase robot,int amount){routeBudgetRequests++;return amount;}''','''public static int requestRouteSearchBudget(EntityRobotBase robot,int amount){
  routeBudgetRequests++;if(managed)navigation.grant(amount);return amount;
 }''')
                source=source.replace('''  return cache(robot).find(robot.level.getGameTime(),start.asLong(),
      goals.stream().map(BlockPos::asLong).toList());''','''  var candidate=cache(robot).find(robot.level.getGameTime(),start.asLong(),
      goals.stream().map(BlockPos::asLong).toList());
  navigation.lookup(candidate!=null);return candidate;''')
                source=source.replace('''  if(managed)cache(robot).remember(robot.level.getGameTime(),start.asLong(),
      goals.stream().map(BlockPos::asLong).toList(),target.asLong(),
      path.stream().map(BlockPos::asLong).toList(),expanded);''','''  if(managed && cache(robot).remember(robot.level.getGameTime(),start.asLong(),
      goals.stream().map(BlockPos::asLong).toList(),target.asLong(),
      path.stream().map(BlockPos::asLong).toList(),expanded)) navigation.stored();''')
                source=source.replace(''' public static void profileAStarProgress(EntityRobotBase robot,int polled,int expanded){}''',''' public static void profileAStarProgress(EntityRobotBase robot,int polled,int expanded){
  if(managed)navigation.search(polled,expanded);}
''')
                source=source.replace(''' public static void profileVerifiedRouteWaypoints(EntityRobotBase robot,int checked){}''',''' public static void profileVerifiedRouteWaypoints(EntityRobotBase robot,int checked){
  if(managed)navigation.validated(checked);}
''')
                source=source.replace(''' public static void profileSharedRouteWait(EntityRobotBase robot){}''',''' public static void profileSharedRouteWait(EntityRobotBase robot){if(managed)navigation.sharedWait();}''')
                source=source.replace(''' public static void profilePositiveRouteOutcome(EntityRobotBase robot,boolean direct,int reference){}''',''' public static void profilePositiveRouteOutcome(EntityRobotBase robot,boolean direct,int reference){
  if(managed){if(direct)navigation.directHit(reference);else navigation.warmStart();}}''')
                source=source.replace(''' public static void profileRejectedPositiveRoute(EntityRobotBase robot){}''',''' public static void profileRejectedPositiveRoute(EntityRobotBase robot){if(managed)navigation.rejected();}''')
                source=source.replace(''' public static void returnUnusedRouteSearchBudget(EntityRobotBase robot,int granted,int polled){}''',''' public static void returnUnusedRouteSearchBudget(EntityRobotBase robot,int granted,int polled){
  if(managed)navigation.refund(Math.max(0,granted-polled));}''')
                stubs[rel]=source
                for relative,code in stubs.items():
                    path=folder/relative;path.parent.mkdir(parents=True,exist_ok=True)
                    path.write_text(code);sources.append(str(path))
                for relative in REAL:
                    path=folder/relative;path.parent.mkdir(parents=True,exist_ok=True)
                    code=(ROOT/relative).read_text()
                    if relative.endswith('AIRobotSearchNearestBlock.java'):
                        code=materialize_old_modern(code,version=='forge')
                    path.write_text(code);sources.append(str(path))
                sources.append(str(ROOT/'source-shared/src/main/java/buildcraft/robotics/scheduler/RobotProcessorNavigationProfiler.java'))
                path=folder/'fixture/NavigationHooksFixture.java';path.parent.mkdir(parents=True,exist_ok=True)
                path.write_text(FIXTURE);sources.append(str(path))
                classes=folder/'classes';classes.mkdir()
                compile=subprocess.run(['javac','--release','17','-d',str(classes),*sources],
                            capture_output=True,text=True,timeout=55)
                self.assertEqual(compile.returncode,0,compile.stdout+compile.stderr)
                run=subprocess.run(['java','-cp',str(classes),'fixture.NavigationHooksFixture'],
                            capture_output=True,text=True,timeout=45)
                self.assertEqual(run.returncode,0,run.stdout+run.stderr)

if __name__=='__main__':unittest.main()
