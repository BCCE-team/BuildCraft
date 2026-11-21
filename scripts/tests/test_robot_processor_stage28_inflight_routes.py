"""Stage 28: real in-flight A* coordination and shared-route CPU accounting.

Compile actual value helpers, then the actual panel scheduler against the same
Minecraft-signature stand-ins used by the earlier stage 14/18 regressions.
"""
from __future__ import annotations

from pathlib import Path
import subprocess
import tempfile
import unittest

from scripts.tests.test_robot_processor_scheduler_runtime import ROOT, STUBS, SOURCES
from scripts.source_preprocessor import preprocess_text

INFLIGHT = ROOT / 'source-shared/src/main/java/buildcraft/robotics/scheduler/RobotInFlightRoutes.java'
BUDGET = ROOT / 'source-shared/src/main/java/buildcraft/robotics/scheduler/RobotSharedSearchBudget.java'

FIXTURE = r'''
import java.util.*;
import buildcraft.robotics.scheduler.RobotInFlightRoutes;
import buildcraft.robotics.scheduler.RobotSharedSearchBudget;
public class InFlightRouteFixture {
 static int checks;
 static void ok(boolean v,String message){checks++;if(!v)throw new AssertionError(message);}
 static List<Long> goal(long... ids){List<Long> result=new ArrayList<>();for(long id:ids)result.add(id);return result;}
 public static void main(String...args){
  var route=new RobotInFlightRoutes();
  long original=route.begin(100,1,81,goal(3,2,1));
  ok(original>0,"leader not registered");
  ok(route.begin(100,2,81,goal(2,3,1))==0,"same start/goals discovered twice");
  ok(route.begin(100,3,82,goal(1,2,3))>0,"adjacent, possibly disconnected cave was blocked");
  ok(route.begin(100,4,81,goal(1,2,99))>0,"new target should bypass old in-flight route");
  ok(route.renew(110,1,original),"leader cannot renew");
  ok(route.begin(130,2,81,goal(1,2,3))==0,"renewed leader expired too soon");
  var slow=new RobotInFlightRoutes();
  long slowToken=slow.begin(1,91,81,goal(1,2,3));
  for(long tick=20;tick<=100;tick+=20)ok(slow.renew(tick,91,slowToken),"slow leader expired");
  ok(slow.begin(102,92,81,goal(1,2,3))==-1,
     "very slow leader starved same-start follower indefinitely");
  ok(route.finished(1,original),"completed route not released");
  long replacement=route.begin(130,2,81,goal(1,2,3));
  ok(replacement>0 && replacement!=original,"follower never acquired new lease");
  ok(!route.finished(1,original),"stale leader removed replacement");
  ok(route.begin(171,5,81,goal(1,2,3))>0,"stalled leader never expired");
  ok(!route.renew(171,2,replacement),"old token renewed after takeover");
  route.clear();
  ok(route.activeSearches()==0,"link change left a lease");
  ok(route.begin(180,5,81,goal(1,2,3))>0,"clear prevented later work");
  var separatePanel=new RobotInFlightRoutes();
  long separateToken=separatePanel.begin(180,5,81,goal(1,2,3));
  ok(separateToken>0,"coordinator leaked across worlds/panels");
  ok(separateToken!=route.begin(180,5,81,goal(1,2,3)),
     "stale callback could release another panel's route token");
  ok(route.begin(181,5,81,Collections.nCopies(129,42L))==-1,"oversize set not bypassed safely");
  route.clear();
  for(int i=0;i<128;i++)ok(route.begin(200,i,1000+i,goal(i+1))>0,"cap filled early");
  ok(route.begin(200,129,2000,goal(500))==-1,"unbounded in-flight search cap");
  route.expire(241);
  ok(route.activeSearches()==0,"old leases not collected");

  // Existing 3,072/tick envelope still holds without any roster.
  var cpu=new RobotSharedSearchBudget();
  ok(cpu.acquire(1,1,2,1024)==1024,"legacy 2-worker share changed");
  ok(cpu.acquire(1,2,2,1024)==1024,"legacy allocation lost");
  ok(cpu.returnUnused(1,1,1024,14)==1010,"unspent A* nodes not returned");
  ok(cpu.returnUnused(1,1,1024,0)==0,"duplicate refund minted credits");
  ok(cpu.acquire(1,1,2,1024)==0,"refunded grant bypassed per-robot cap");
  ok(cpu.remaining()==2034,"refunded world accounting incorrect");
  ok(cpu.returnUnused(2,2,1024,0)==0,"late refund crossed ticks");

  var large=new RobotSharedSearchBudget();
  Set<Long> all=new HashSet<>();for(long i=0;i<250;i++)all.add(i);
  Set<Long> served=new HashSet<>();
  for(long tick=1;tick<=3;tick++){
   large.setActiveWorkers(tick,all);
   int total=0,chosen=0;
   for(long id:all){
    int grant=large.acquire(tick,id,250,1024);
    total+=grant;
    if(grant>0){
     served.add(id);chosen++;
     ok(grant==32,"oversubscribed miner got only a one-node slice");
     ok(large.acquire(tick,id,250,1024)==0,"same miner got double slot");
    }
   }
   ok(total<=3072 && chosen==96,"world route budget or active batch size exceeded");
  }
  ok(served.size()==250,"late-ID miners starved under rotation");
  System.out.println("PASS "+checks+" exact in-flight/fenced/expiry/bounded/fair route assertions");
 }
}
'''

PANEL = r'''package fixture;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import buildcraft.robotics.internal.legacy.robots.*;
import buildcraft.robotics.scheduler.*;
import buildcraft.robotics.tile.TileRobotProcessor;
public class InFlightPanelFixture {
 static void ok(boolean v,String message){if(!v)throw new AssertionError(message);}
 public static void main(String...args){
  ServerLevel level=new ServerLevel();level.time=1;
  EntityRobotBase a=new EntityRobotBase(level,10,new BlockPos(1,65,1));
  EntityRobotBase b=new EntityRobotBase(level,11,new BlockPos(1,65,1));
  EntityRobotBase c=new EntityRobotBase(level,12,new BlockPos(2,65,1));
  EntityRobotBase d=new EntityRobotBase(level,13,new BlockPos(1,65,1));
  a.setBoardId("buildcraft:miner");b.setBoardId("buildcraft:miner");
  c.setBoardId("buildcraft:miner");d.setBoardId("buildcraft:miner");
  RobotManager.registryProvider=world->new IRobotRegistry(){
   public EntityRobotBase getLoadedRobot(long id){return id==10?a:id==11?b:id==12?c:id==13?d:null;}
   public boolean isTaken(ResourceIdBlock key){return false;}
   public long robotIdTaking(ResourceIdBlock key){return 0;}
  };
  var panel=new TileRobotProcessor(level,new BlockPos(0,65,0));
  panel.linked.addAll(List.of(10L,11L,12L));
  var scheduler=new RobotProcessorWorkScheduler(panel);
  scheduler.tick(level);
  var goals=List.of(new BlockPos(20,65,2),new BlockPos(21,65,2));
  long t=RobotProcessorWorkScheduler.beginRouteSearch(a,a.blockPosition(),goals);
  ok(t>0,"managed miner did not own A*");
  ok(RobotProcessorWorkScheduler.beginRouteSearch(b,b.blockPosition(),goals)==0,
     "two identical concurrent A* searches were admitted");
  ok(RobotProcessorWorkScheduler.beginRouteSearch(c,c.blockPosition(),goals)>0,
     "different starting cell must not be delayed");
  ok(RobotProcessorWorkScheduler.beginRouteSearch(d,d.blockPosition(),goals)==-1,
     "unlinked worker was coalesced");
  ok(RobotProcessorWorkScheduler.renewRouteSearch(a,t),"leader could not renew");
  RobotProcessorWorkScheduler.finishRouteSearch(a,t);
  long next=RobotProcessorWorkScheduler.beginRouteSearch(b,b.blockPosition(),goals);
  ok(next>0 && next!=t,"waiting miner did not resume");
  scheduler.onLinkChanged();
  ok(!RobotProcessorWorkScheduler.renewRouteSearch(b,next),"link policy change retained old session");
  long fresh=RobotProcessorWorkScheduler.beginRouteSearch(a,a.blockPosition(),goals);
  ok(fresh>0,"relocated controller could not begin a new search");
  scheduler.onRemoved();
  ok(!RobotProcessorWorkScheduler.renewRouteSearch(a,fresh),"removed panel retained session");
  ok(RobotProcessorWorkScheduler.beginRouteSearch(a,a.blockPosition(),goals)==-1,
    "unloaded panel still intercepted native searches");
  System.out.println("PASS: real panel primary ownership, independent approach, unload cleanup");
 }
}'''

class InFlightTests(unittest.TestCase):
    def test_exact_coalescing_and_rotating_route_budget_jvm(self):
        with tempfile.TemporaryDirectory(prefix='bc-inflight-pure-') as tmp:
            root=Path(tmp)
            fixture=root/'InFlightRouteFixture.java';fixture.write_text(FIXTURE)
            cmd=['javac','--release','17','-d',tmp,str(INFLIGHT),str(BUDGET),str(fixture)]
            proc=subprocess.run(cmd,capture_output=True,text=True,timeout=35)
            self.assertEqual(proc.returncode,0,proc.stderr)
            proc=subprocess.run(['java','-cp',tmp,'InFlightRouteFixture'],
                                capture_output=True,text=True,timeout=20)
            self.assertEqual(proc.returncode,0,proc.stdout+proc.stderr)

    def test_real_panel_ownership_and_lifecycle_across_families(self):
        for version,family,platform in [('1.19.2','old','forge'),('1.21.11','1.21.X','neoforge'),
                                        ('26.3','26.X','fabric')]:
            with self.subTest(version=version,platform=platform),tempfile.TemporaryDirectory(prefix='bc-inflight-panel-') as tmp:
                folder=Path(tmp);stubs=dict(STUBS)
                board='buildcraft/robotics/boards/BoardRobotGenericSearchBlock.java'
                stubs[board]=stubs[board].replace('private final NBT nbt = new NBT("lumberjack");',
                    'private NBT nbt=new NBT("lumberjack");\n'
                    ' public void setBoardId(String id){nbt=new NBT(id);}\n'
                    ' public boolean processorSupportsQueue(){return true;}')
                entity='buildcraft/robotics/internal/legacy/robots/EntityRobotBase.java'
                stubs[entity]=stubs[entity].replace(' public long getRobotId(){return id;}',
                    ' public void setBoardId(String id){board.setBoardId(id);}\n public long getRobotId(){return id;}')
                files=[]
                for rel,source in stubs.items():
                    path=folder/rel;path.parent.mkdir(parents=True,exist_ok=True)
                    path.write_text(source);files.append(str(path))
                for rel in SOURCES:
                    dest=folder/rel.removeprefix('source-shared/src/main/java/')
                    dest.parent.mkdir(parents=True,exist_ok=True)
                    dest.write_text(preprocess_text((ROOT/rel).read_text(),minecraft=version,
                                    family=family,platform=platform,source=rel));files.append(str(dest))
                p=folder/'fixture/InFlightPanelFixture.java'
                p.parent.mkdir(parents=True,exist_ok=True);p.write_text(PANEL);files.append(str(p))
                proc=subprocess.run(['javac','--release','17','-d',str(folder/'classes'),*files],
                                    capture_output=True,text=True,timeout=50)
                self.assertEqual(proc.returncode,0,proc.stdout+proc.stderr)
                proc=subprocess.run(['java','-cp',str(folder/'classes'),'fixture.InFlightPanelFixture'],
                                    capture_output=True,text=True,timeout=20)
                self.assertEqual(proc.returncode,0,proc.stdout+proc.stderr)

if __name__ == '__main__': unittest.main()
