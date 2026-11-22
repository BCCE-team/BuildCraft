"""JVM tests of the real exact target index and shared route-tick CPU envelope."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[4]
DIST = ROOT / 'source-shared/src/main/java/buildcraft/robotics/ai/RobotTargetDistanceIndex.java'
BUDGET = ROOT / 'source-shared/src/main/java/buildcraft/robotics/scheduler/RobotSharedSearchBudget.java'

POS = '''package net.minecraft.core;
public record BlockPos(int x,int y,int z) {
 public int getX(){return x;}public int getY(){return y;}public int getZ(){return z;}
}'''

CHECKS = '''import java.util.*;
import net.minecraft.core.BlockPos;
import buildcraft.robotics.ai.RobotTargetDistanceIndex;
import buildcraft.robotics.scheduler.RobotSharedSearchBudget;
public class VerifySharedNavigation {
 static void check(boolean okay,String error){if(!okay)throw new AssertionError(error);}
 static int naive(List<BlockPos> targets, BlockPos from) {
  long best=Long.MAX_VALUE;
  for(BlockPos p:targets) {
   long d=Math.abs((long)p.getX()-from.getX())+Math.abs((long)p.getY()-from.getY())+
          Math.abs((long)p.getZ()-from.getZ());
   best=Math.min(best,d);
  }
  return (int)Math.min(Integer.MAX_VALUE,Math.max(0L,best-1));
 }
 static void indexIsExactlyAdmissible() {
  Random rng = new Random(8104012);
  for(int size:new int[]{0,1,2,6,7,9,32,64,128}) {
   ArrayList<BlockPos> goals=new ArrayList<>();
   for(int i=0;i<size;i++)goals.add(new BlockPos(rng.nextInt(1024)-512,rng.nextInt(192)-64,rng.nextInt(1024)-512));
   RobotTargetDistanceIndex index=new RobotTargetDistanceIndex(goals);
   for(int q=0;q<900;q++){
    BlockPos from=new BlockPos(rng.nextInt(1400)-700,rng.nextInt(250)-100,rng.nextInt(1400)-700);
    check(index.distanceToAdjacent(from)==(size==0?0:naive(goals,from)),
        "A* heuristic changed route lower bound ("+size+" targets, "+q+")");
   }
  }
  var extremes=List.of(new BlockPos(-29_999_999,100,29_999_999),
                       new BlockPos(29_999_999,-64,-29_999_999));
  var index=new RobotTargetDistanceIndex(extremes);
  check(index.distanceToAdjacent(new BlockPos(0,0,0))==naive(extremes,new BlockPos(0,0,0)),
        "Coordinate overflow in heuristic");
 }
 static void sharedBudgetIsFairAndBounded() {
  for(int workers: new int[]{1,2,3,4,6,12,50}) {
   var budget=new RobotSharedSearchBudget();
   int total=0,min=Integer.MAX_VALUE,max=0;
   for(long id=1;id<=workers;id++) {
    int granted=budget.acquire(20,id,workers,1024);
    check(granted>0,"Worker starved: "+id+" of "+workers);
    check(granted<=1024,"Per-worker navigation limit exceeded");
    total+=granted;
    min=Math.min(min,granted);max=Math.max(max,granted);
    check(budget.acquire(20,id,workers,1024)==0,"Double update bypassed per-robot grant");
   }
   check(max-min<=1,"A late miner received less route CPU than an early one");
   check(total<=RobotSharedSearchBudget.MAX_NODES_PER_TICK,"Route CPU exceeded server envelope");
   check(total+budget.remaining()==RobotSharedSearchBudget.MAX_NODES_PER_TICK,
         "Route budget accounting leak");
   check(budget.acquire(21,1,workers,1024)>0,"Budget did not reset next tick");
  }
 }
 public static void main(String[] args) {
  indexIsExactlyAdmissible();sharedBudgetIsFairAndBounded();
  System.out.println("PASS exact Manhattan lower bound, coordinates, fleet fairness, server budget, tick rollover");
 }
}'''

class SharedNavigationBudgetTests(unittest.TestCase):
    def test_real_java_helpers_and_fairness(self):
        with tempfile.TemporaryDirectory(prefix='bc-shared-routes-') as tmp:
            directory=Path(tmp)
            source=directory/'net/minecraft/core/BlockPos.java'
            source.parent.mkdir(parents=True,exist_ok=True)
            source.write_text(POS,encoding='utf-8')
            check=directory/'VerifySharedNavigation.java'
            check.write_text(CHECKS,encoding='utf-8')
            classes=directory/'classes'
            classes.mkdir()
            proc=subprocess.run(['javac','--release','17','-d',str(classes),
                                 str(source),str(DIST),str(BUDGET),str(check)],
                                capture_output=True,text=True,timeout=35)
            self.assertEqual(proc.returncode,0,proc.stderr)
            run=subprocess.run(['java','-cp',str(classes),'VerifySharedNavigation'],
                               capture_output=True,text=True,timeout=35)
            self.assertEqual(run.returncode,0,run.stdout+run.stderr)
            self.assertIn('PASS exact Manhattan',run.stdout)

    def test_nearest_search_respects_shared_budget_without_changing_autonomous_path(self):
        text=(ROOT/'source-shared/src/main/java/buildcraft/robotics/ai/AIRobotSearchNearestBlock.java').read_text()
        self.assertIn('RobotProcessorWorkScheduler.requestRouteSearchBudget(robot, NODES_PER_TICK)',text)
        self.assertIn('queuedTargets == null ? NODES_PER_TICK',text)
        self.assertIn('Math.ceil(Math.sqrt(furthestSquared)) + 24',text)

if __name__=='__main__':unittest.main()

# Exercise the actual scheduler's per-world dispatch, not just its pure budget helper.
ACTUAL_SCHEDULER_FIXTURE = '''package fixture;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import buildcraft.robotics.internal.legacy.robots.*;
import buildcraft.robotics.scheduler.RobotProcessorWorkScheduler;
import buildcraft.robotics.tile.TileRobotProcessor;
public class RealSharedBudgetFixture {
 static void check(boolean good,String reason){if(!good)throw new AssertionError(reason);}
 public static void main(String[] args){
  ServerLevel world=new ServerLevel();world.time=1;
  Map<Long,EntityRobotBase> robots=new HashMap<>();
  for(long i=1;i<=8;i++)robots.put(i,new EntityRobotBase(world,i,new BlockPos(1,65,1)));
  RobotManager.registryProvider=l->new IRobotRegistry(){
   public EntityRobotBase getLoadedRobot(long id){return robots.get(id);}
   public boolean isTaken(ResourceIdBlock id){return false;}
   public long robotIdTaking(ResourceIdBlock id){return 0;}
  };
  TileRobotProcessor panel=new TileRobotProcessor(world,new BlockPos(1,65,1));
  for(long i=1;i<=8;i++)panel.linked.add(i);
  var scheduler=new RobotProcessorWorkScheduler(panel);
  scheduler.tick(world);
  int spent=0;
  for(long i=1;i<=8;i++){
   int granted=RobotProcessorWorkScheduler.requestRouteSearchBudget(robots.get(i),1024);
   check(granted==384,"Expected eight equal world shares, got "+granted+" for "+i);
   spent+=granted;
  }
  check(spent==3072,"World quota exceeded or left unused");
  int second=RobotProcessorWorkScheduler.requestRouteSearchBudget(robots.get(1L),1024);
  check(second==0,"Second search update in a tick got extra route CPU: "+second);
  world.time++;
  check(RobotProcessorWorkScheduler.requestRouteSearchBudget(robots.get(8L),1024)==384,
   "Next tick failed to restore fair share");
  scheduler.onRemoved();
  check(RobotProcessorWorkScheduler.requestRouteSearchBudget(robots.get(1L),1024)==1024,
   "Unloaded controller kept throttling autonomous worker");
  System.out.println("PASS: real panel eight miners, shared budget, rollover, controller removal");
 }
}'''

class SharedNavigationIntegrationTests(unittest.TestCase):
    def test_real_scheduler_eight_miners_share_world_budget(self):
        from scripts.tests.robot_processor.discovery.test_scheduler_runtime import STUBS, SOURCES
        from scripts.source_preprocessor import preprocess_text
        for version, family, loader in [('1.19.2', 'old', 'forge'),
                                        ('1.21.11', '1.21.X', 'neoforge'),
                                        ('26.3', '26.X', 'fabric')]:
            with self.subTest(target=version+'-'+loader), tempfile.TemporaryDirectory(prefix='bc-stage14-sim-') as tmp:
                folder=Path(tmp)
                sources=dict(STUBS)
                board='buildcraft/robotics/boards/BoardRobotGenericSearchBlock.java'
                self.assertIn('return nbt.id();',sources[board])
                sources[board]=sources[board].replace('return nbt.id();', 'return "buildcraft:miner";')
                sources['fixture/RealSharedBudgetFixture.java']=ACTUAL_SCHEDULER_FIXTURE
                all_files=[]
                for rel, content in sources.items():
                    path=folder/rel
                    path.parent.mkdir(parents=True,exist_ok=True)
                    path.write_text(content,encoding='utf8')
                    all_files.append(str(path))
                for rel in SOURCES:
                    path=folder/rel.removeprefix('source-shared/src/main/java/')
                    path.parent.mkdir(parents=True,exist_ok=True)
                    path.write_text(preprocess_text((ROOT/rel).read_text(),minecraft=version,
                              family=family,platform=loader,source=rel),encoding='utf8')
                    all_files.append(str(path))
                classes=folder/'classes';classes.mkdir()
                compiled=subprocess.run(['javac','--release','17','-d',str(classes),*all_files],
                                        capture_output=True,text=True,timeout=45)
                self.assertEqual(compiled.returncode,0,compiled.stderr)
                run=subprocess.run(['java','-cp',str(classes),'fixture.RealSharedBudgetFixture'],
                                   capture_output=True,text=True,timeout=30)
                self.assertEqual(run.returncode,0,run.stdout+run.stderr)
                self.assertIn('PASS: real panel eight miners',run.stdout)
