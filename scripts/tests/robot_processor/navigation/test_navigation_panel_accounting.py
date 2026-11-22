"""Compile the actual primary-panel routing and verify per-panel A* accounting."""
from __future__ import annotations
from pathlib import Path
import subprocess
import tempfile
import unittest
from scripts.source_preprocessor import preprocess_text
from scripts.tests.robot_processor.discovery.test_scheduler_runtime import ROOT, SOURCES, STUBS

SCENARIO=r'''
package fixture;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import buildcraft.robotics.internal.legacy.robots.*;
import buildcraft.robotics.scheduler.*;
import buildcraft.robotics.tile.TileRobotProcessor;
public class NavigationPanelFixture {
 static int assertions;
 static void ok(boolean v,String why){assertions++;if(!v)throw new AssertionError(why);}
 public static void main(String[] args){
  ServerLevel world=new ServerLevel();world.time=1;
  EntityRobotBase a=new EntityRobotBase(world,21,new BlockPos(0,65,0));
  EntityRobotBase b=new EntityRobotBase(world,22,new BlockPos(24,65,0));
  RobotManager.registryProvider=level->new IRobotRegistry(){
   public EntityRobotBase getLoadedRobot(long id){return id==21?a:id==22?b:null;}
   public boolean isTaken(ResourceIdBlock p){return false;}
   public long robotIdTaking(ResourceIdBlock p){return 0;}
  };
  TileRobotProcessor ap=new TileRobotProcessor(world,new BlockPos(0,65,0));
  TileRobotProcessor bp=new TileRobotProcessor(world,new BlockPos(32,65,0));
  ap.linked.add(21L);bp.linked.add(22L);
  var first=new RobotProcessorWorkScheduler(ap);
  var second=new RobotProcessorWorkScheduler(bp);
  first.tick(world);second.tick(world);
  var target=new BlockPos(16,65,0);
  var witness=List.of(new BlockPos(1,65,0),new BlockPos(2,65,0));
  RobotProcessorWorkScheduler.rememberPositiveRoute(a,a.blockPosition(),List.of(target),target,witness,319);
  var cached=RobotProcessorWorkScheduler.positiveRouteCandidate(a,a.blockPosition(),List.of(target));
  ok(cached!=null&&cached.route().referenceExpandedNodes()==319,"real panel did not store reference nodes");
  RobotProcessorWorkScheduler.profileVerifiedRouteWaypoints(a,2);
  RobotProcessorWorkScheduler.profilePositiveRouteOutcome(a,true,319);
  RobotProcessorWorkScheduler.profileAStarProgress(a,90,87);
  RobotProcessorWorkScheduler.profileSharedRouteWait(a);
  RobotProcessorWorkScheduler.profileRejectedPositiveRoute(a);
  int budget=RobotProcessorWorkScheduler.requestRouteSearchBudget(a,1024);
  ok(budget>0,"managed miner got no A* credits");
  RobotProcessorWorkScheduler.returnUnusedRouteSearchBudget(a,budget,17);
  ok(first.navigationProfile().cacheProbes()==0,"published incomplete profile too early");
  for(int i=2;i<=21;i++){world.time=i;first.tick(world);second.tick(world);}
  var snap=first.navigationProfile();
  ok(snap.cacheProbes()==1&&snap.cacheCandidates()==1&&snap.storedRoutes()==1,"real cache hooks not routed");
  ok(snap.directHits()==1&&snap.estimatedReferenceNodesBypassed()==319,"real direct route reference estimate lost");
  ok(snap.aStarPolled()==90&&snap.aStarExpanded()==87,"real A* counters missing");
  ok(snap.grantedNodes()==budget&&snap.refundedNodes()==budget-17,"real budget/refund mismatch");
  ok(snap.sharedWaitRobotTicks()==1&&snap.rejectedRoutes()==1,"wait/rejection missing");
  ok(second.navigationProfile().cacheProbes()==0&&second.navigationProfile().aStarPolled()==0,
    "search telemetry leaked between panels");
  first.onLinkChanged();
  ok(first.navigationProfile().cacheProbes()==0,"changed panel policy retained previous metrics");
  first.onRemoved();second.onRemoved();
  ok(first.navigationProfile().directHits()==0&&second.navigationProfile().aStarExpanded()==0,
     "unloaded panel retained profile metrics");
  System.out.println("PASS "+assertions+" real panel navigation sampling and isolation");
 }
}
'''

class NavigationPanelIntegrationTests(unittest.TestCase):
    def test_real_panel_profile_isolation_and_shared_node_budget(self):
        variants=(('1.19.2','old','forge'),('1.21.11','1.21.X','neoforge'),
                  ('26.3','26.X','fabric'))
        for version,family,loader in variants:
            with self.subTest(version=version,loader=loader),tempfile.TemporaryDirectory(prefix='bc-nav-panel-') as td:
                root=Path(td);files=[]
                for rel,text in STUBS.items():
                    if rel == 'buildcraft/robotics/boards/BoardRobotGenericSearchBlock.java':
                        text=text.replace('new NBT("lumberjack")','new NBT("buildcraft:miner")')
                    path=root/rel;path.parent.mkdir(parents=True,exist_ok=True)
                    path.write_text(text);files.append(str(path))
                for rel in SOURCES:
                    path=root/rel.removeprefix('source-shared/src/main/java/')
                    path.parent.mkdir(parents=True,exist_ok=True)
                    code=preprocess_text((ROOT/rel).read_text(),minecraft=version,
                            family=family,platform=loader,source=rel)
                    path.write_text(code);files.append(str(path))
                scenario=root/'fixture/NavigationPanelFixture.java'
                scenario.parent.mkdir(parents=True,exist_ok=True);scenario.write_text(SCENARIO)
                files.append(str(scenario))
                classes=root/'classes';classes.mkdir()
                comp=subprocess.run(['javac','--release','17','-d',str(classes),*files],
                            capture_output=True,text=True,timeout=65)
                self.assertEqual(comp.returncode,0,comp.stdout+comp.stderr)
                result=subprocess.run(['java','-cp',str(classes),'fixture.NavigationPanelFixture'],
                            capture_output=True,text=True,timeout=30)
                self.assertEqual(result.returncode,0,result.stdout+result.stderr)

if __name__=='__main__':unittest.main()
