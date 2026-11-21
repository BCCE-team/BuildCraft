"""Stage 30: real counters, source-family compatibility and packet contract.

No fabricated TPS claim: reference A* savings are labelled estimates. GUI
snapshots must not call a world scanner or write into the saved level.
"""
from __future__ import annotations
import re
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PROFILE = ROOT/'source-shared/src/main/java/buildcraft/robotics/scheduler/RobotProcessorNavigationProfiler.java'
CACHE = ROOT/'source-shared/src/main/java/buildcraft/robotics/scheduler/RobotPositiveRouteCache.java'
SCHED = ROOT/'source-shared/src/main/java/buildcraft/robotics/scheduler/RobotProcessorWorkScheduler.java'
MENU = ROOT/'source-shared/src/main/java/buildcraft/robotics/container/ContainerRobotProcessor.java'
FIELDS = ('cacheProbes','cacheCandidates','directHits','warmStarts','rejectedRoutes','storedRoutes',
          'validatedWaypoints','aStarPolled','aStarExpanded','grantedNodes','refundedNodes',
          'sharedWaitRobotTicks','budgetWaitRobotTicks','emptyQueueRobotTicks','readyMinerRobotTicks',
          'estimatedReferenceNodesBypassed','cachedRoutes')

FIXTURE = r'''
import java.util.List;
import buildcraft.robotics.scheduler.*;
public class NavigationProfileFixture {
 static int assertions;
 static void ok(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
 public static void main(String[] args){
  var p=new RobotProcessorNavigationProfiler();
  var cache=new RobotPositiveRouteCache();
  var empty=p.snapshot(0);
  ok(empty.cacheProbes()==0&&empty.cacheCandidates()==0,"profiler not initially empty");
  for(int t=1;t<=20;t++){
   p.beginTick(t);
   p.sampleQueues(4,3);
   p.lookup(t%2==0);
   p.grant(64);
   p.search(40,30);
   p.refund(24);
   p.validated(2);
   p.sharedWait();
   if(t%4==0){p.budgetWait();p.rejected();}
  }
  ok(p.snapshot(0).cacheProbes()==0,"published partial profile window");
  p.beginTick(21);
  var snap=p.snapshot(5);
  ok(snap.cacheProbes()==20&&snap.cacheCandidates()==10,"lookups/candidates wrong");
  ok(snap.aStarPolled()==800&&snap.aStarExpanded()==600,"A* poll/stale queue accounting wrong");
  ok(snap.grantedNodes()==1280&&snap.refundedNodes()==480,"node accounting wrong");
  ok(snap.sharedWaitRobotTicks()==20&&snap.budgetWaitRobotTicks()==5,"wait ticks wrong");
  ok(snap.emptyQueueRobotTicks()==60&&snap.readyMinerRobotTicks()==80,"queue empty fraction wrong");
  ok(snap.validatedWaypoints()==40&&snap.rejectedRoutes()==5,"validation or rejection wrong");
  ok(snap.cachedRoutes()==5&&p.snapshot(3).cachedRoutes()==3,"cache size is not live metadata");
  p.stored();p.directHit(123);p.warmStart();p.lookup(true);
  p.beginTick(41);
  var saved=p.snapshot(2);
  ok(saved.directHits()==1&&saved.warmStarts()==1&&saved.storedRoutes()==1,"direct/warm/store window missing");
  ok(saved.estimatedReferenceNodesBypassed()==123,"reference A* effort lost");
  p.directHit(Integer.MAX_VALUE);p.directHit(Integer.MAX_VALUE);
  p.beginTick(61);
  ok(p.snapshot(0).estimatedReferenceNodesBypassed()==RobotProcessorNavigationProfiler.MAX_WIRE_COUNTER,
     "counter sent past packet range");
  p.beginTick(10);ok(p.snapshot(0).aStarPolled()==0,"time rollback failed to reset counters");
  p.reset();ok(p.snapshot(0).cacheCandidates()==0,"reset leaked old counts");
  // Original expanded-node count is stored with the witness, NOT invented on
  // cache reuse. It is a reference estimate only, and existing APIs remain.
  var goals=List.of(10L);var path=List.of(2L,3L,4L);
  ok(cache.remember(100,1L,goals,10L,path,541),"reference route was not stored");
  var found=cache.find(101,1L,goals);
  ok(found!=null&&found.exact()&&found.route().referenceExpandedNodes()==541,"wrong reference effort");
  cache.worldChanged(999L);
  var warm=cache.find(102,1L,goals);
  ok(warm!=null&&!warm.exact()&&warm.route().referenceExpandedNodes()==541,
     "warm corridor lost reference information");
  // The old five-argument API is retained for tests and integrations.
  ok(cache.remember(110,1L,goals,10L,path),"legacy cache API broken");
  ok(cache.find(110,1L,goals).route().referenceExpandedNodes()==0,"legacy default estimate not zero");
  System.out.println("PASS "+assertions+" real JVM navigation profiler/cache checks");
 }
}'''

class NavigationProfileTests(unittest.TestCase):
    def test_value_only_profiling_and_reference_route_cache(self):
        with tempfile.TemporaryDirectory(prefix='bc-nav-profile-') as folder:
            fixture=Path(folder)/'NavigationProfileFixture.java'
            fixture.write_text(FIXTURE)
            result=subprocess.run(['javac','--release','17','-d',folder,
                str(PROFILE),str(CACHE),str(fixture)],capture_output=True,text=True,timeout=40)
            self.assertEqual(result.returncode,0,result.stdout+result.stderr)
            run=subprocess.run(['java','-cp',folder,'NavigationProfileFixture'],
                                capture_output=True,text=True,timeout=20)
            self.assertEqual(run.returncode,0,run.stdout+run.stderr)
            self.assertIn('real JVM navigation profiler',run.stdout)

    def test_packet_fields_and_source_gui_compatible(self):
        text=MENU.read_text()
        self.assertLess(text.index('allocId("processor_diagnostics")'),
                        text.index('allocId("processor_navigation_profile")'))
        send=text.split('sendMessage(ID_NAVIGATION_PROFILE, out -> {',1)[1].split('});',1)[0]
        read=text.split('navigationProfile = new RobotProcessorNavigationProfiler.Snapshot(',1)[1].split(');',1)[0]
        writes=re.findall(r'out\.writeVarInt\(next\.(\w+)\(\)\)',send)
        reads=re.findall(r'"navigation (\w+)"',read)
        self.assertEqual(writes,list(FIELDS),'server packet order changed')
        self.assertEqual(reads,list(FIELDS),'client packet differs from server packet')
        self.assertEqual(len(re.findall(r'buffer\.readVarInt\(\)',read)),len(FIELDS))
        for source in (
                'version-src/1.19.2-forge', 'version-src/1.20.1-forge',
                'source-families/1.21.X', 'source-downports/1.21.X/1.21.1/family',
                'source-families/26.X'):
            screen=ROOT/source/'src/main/java/buildcraft/robotics/gui/GuiRobotProcessor.java'
            gui=screen.read_text()
            self.assertIn('if (hasShiftDown())',gui,screen.name)
            self.assertIn('getNavigationProfile()',gui,screen.name)
            self.assertIn('Estimated ref. A* nodes bypassed:',gui,screen.name)
            self.assertIn('Hold Shift: A* navigation profile',gui,screen.name)
        tile=(ROOT/'source-shared/src/main/java/buildcraft/robotics/tile/TileRobotProcessor.java').read_text()
        self.assertIn('workScheduler.navigationProfile()',tile)
        self.assertIn('navigation.snapshot(positiveRoutes.entries())',SCHED.read_text())

    def test_target_materialization_and_hook_coverage(self):
        import sys
        sys.path.insert(0,str(ROOT/'scripts'))
        from source_lookup import resolve_target_source, read_materialized_java
        for target in ('1.19.2-forge','1.20.1-forge','1.21.11-neoforge',
                       '26.1.2-neoforge','26.3-neoforge','26.3-fabric'):
            with self.subTest(target=target):
                for source in ('scheduler/RobotProcessorNavigationProfiler.java',
                               'scheduler/RobotProcessorWorkScheduler.java',
                               'scheduler/RobotPositiveRouteCache.java',
                               'ai/AIRobotSearchNearestBlock.java',
                               'container/ContainerRobotProcessor.java'):
                    rel='src/main/java/buildcraft/robotics/'+source
                    self.assertTrue(resolve_target_source(target,rel).exists())
                    code=read_materialized_java(target,rel)
                    self.assertNotIn('//?',code,source)
                    if source.endswith('AIRobotSearchNearestBlock.java'):
                        self.assertIn('profileAStarProgress(robot, polled',code)
                        self.assertIn('profileVerifiedRouteWaypoints(robot, checked)',code)
                        self.assertIn('profilePositiveRouteOutcome(robot, true',code)
                    if source.endswith('RobotProcessorWorkScheduler.java'):
                        self.assertIn('navigation.sampleQueues(',code)
                gui=read_materialized_java(target,'src/main/java/buildcraft/robotics/gui/GuiRobotProcessor.java')
                self.assertIn('getNavigationProfile()',gui)

if __name__=='__main__':unittest.main()
