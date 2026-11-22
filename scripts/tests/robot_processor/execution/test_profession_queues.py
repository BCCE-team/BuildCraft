"""Stage 8: execute the real shared scheduler in JVM fake worlds.

These tests are independent of the browser GUI and cover the assignment,
reservation, completion, and NBT-intent recovery contract for non-miner boards.
"""
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT / 'scripts'))
from source_preprocessor import preprocess_text
from source_config import load_properties, target_layout
from source_layout import resolve_effective_source
from scripts.tests.robot_processor.discovery.test_scheduler_runtime import STUBS, SOURCES

SIMULATION = r'''package fixture;
import buildcraft.robotics.scheduler.*;
import buildcraft.robotics.internal.legacy.robots.*;
import buildcraft.robotics.tile.TileRobotProcessor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import java.util.*;
public class Stage8Professions {
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 static class Scenario {
  final ServerLevel level = new ServerLevel();
  final EntityRobotBase a = new EntityRobotBase(level,1,new BlockPos(0,65,1));
  final EntityRobotBase b = new EntityRobotBase(level,2,new BlockPos(0,65,2));
  final TileRobotProcessor panel = new TileRobotProcessor(level,new BlockPos(0,65,0));
  RobotProcessorWorkScheduler scheduler;
  Scenario(String first,String second,int limit){
   a.setBoardId(first); b.setBoardId(second); panel.max=limit;
   panel.linked.add(1L);panel.linked.add(2L);
   RobotManager.registryProvider = world->new IRobotRegistry(){
    public EntityRobotBase getLoadedRobot(long id){return id==1?a:id==2?b:null;}
    public boolean isTaken(ResourceIdBlock r){return false;}
    public long robotIdTaking(ResourceIdBlock r){return 0;}
   };
   scheduler=new RobotProcessorWorkScheduler(panel);
  }
  void add(int... xs){for(int x:xs)level.extra.add(new BlockPos(x,64,1));}
  void tick(int times){for(int i=0;i<times;i++){level.time++;scheduler.tick(level);}}
  List<BlockPos> jobs(EntityRobotBase robot){return RobotProcessorWorkScheduler.queuedWorkTargets(robot);}
 }
 public static void main(String[] args) {
  // Crop operations, soil preparation, planting, shovel operations, and leaves
  // are independent useful actions: each position consumes one queue slot.
  for(String category:List.of("buildcraft:boardRobotHarvester", "buildcraft:boardRobotFarmer",
     "buildcraft:boardRobotPlanter", "buildcraft:shovelman", "buildcraft:leave_cutter")) {
    Scenario s=new Scenario(category,category,3);
    s.add(2,4,6,8,10,12);
    s.tick(30);
    int count=s.scheduler.queueCount(1)+s.scheduler.queueCount(2);
    check(count>=2,"No central queue for "+category+" got "+count);
    check(s.scheduler.queueCount(1)<=3 && s.scheduler.queueCount(2)<=3,
       "Exceeded GUI queue limit for "+category);
    HashSet<BlockPos> all=new HashSet<>(s.jobs(s.a));
    int sizeB=s.jobs(s.b).size();all.addAll(s.jobs(s.b));
    check(all.size()==s.jobs(s.a).size()+sizeB,"Double assigned target: "+category);
    EntityRobotBase worker=s.jobs(s.a).isEmpty()?s.b:s.a;
    BlockPos current=s.jobs(worker).get(0);
    RobotProcessorWorkScheduler.targetAcquired(worker,current);
    int before=s.scheduler.queueCount(worker.getRobotId());
    s.level.extra.remove(current);
    RobotProcessorWorkScheduler.executionFinished(worker,current,true);
    check(s.scheduler.queueCount(worker.getRobotId())==before-1,
      "Executor did not complete one useful job: "+category);
    s.scheduler.onRemoved();
  }
  // A connected tree is one logical job, with more than one break step.
  Scenario tree=new Scenario("buildcraft:boardRobotLumberjack","buildcraft:boardRobotLumberjack",3);
  tree.add(2,3,4);
  tree.tick(35);
  EntityRobotBase forester=tree.jobs(tree.a).isEmpty()?tree.b:tree.a;
  check(tree.scheduler.queueCount(forester.getRobotId())==1,"Tree split into separate jobs");
  check(tree.jobs(forester).size()==3,"Tree members missing from queue");
  BlockPos first=tree.jobs(forester).get(0);
  tree.level.extra.remove(first);
  RobotProcessorWorkScheduler.executionFinished(forester,first,true);
  check(tree.scheduler.queueCount(forester.getRobotId())==1,"First log ended tree job");
  check(tree.jobs(forester).size()==2,"Completed log not consumed");
  long[] snapshot=tree.scheduler.pendingWorkSnapshot();
  tree.scheduler.onRemoved();
  RobotProcessorWorkScheduler restored=new RobotProcessorWorkScheduler(tree.panel);
  restored.restorePendingWork(snapshot);
  tree.scheduler=restored;
  tree.tick(20);
  check(tree.jobs(forester).size()==2,"Tree recovery did not revalidate remaining logs");
  tree.scheduler.onRemoved();
  // Cross-profession competition must reserve the same block globally.
  Scenario mixed=new Scenario("buildcraft:boardRobotFarmer","buildcraft:boardRobotPlanter",2);
  mixed.add(2,4,6);
  mixed.tick(35);
  Set<BlockPos> unique=new HashSet<>(mixed.jobs(mixed.a));
  int aCount=unique.size(), bCount=mixed.jobs(mixed.b).size();
  unique.addAll(mixed.jobs(mixed.b));
  check(unique.size()==aCount+bCount,"Cross-category conflicting claim");
  mixed.scheduler.onRemoved();
  System.out.println("PASS: five single-action professions; whole-tree jobs; completion; recovery; cross-category leases");
 }
}'''


class ProfessionExecutionQueueTests(unittest.TestCase):
    def test_java_non_miner_queues_in_legacy_and_modern_world(self):
        for version, family, platform in (('1.19.2', 'old', 'forge'),
                                           ('1.21.11', '1.21.X', 'neoforge'),
                                           ('26.3', '26.X', 'fabric')):
            with self.subTest(version=version, platform=platform), tempfile.TemporaryDirectory(prefix='bc-stage8-') as d:
                out = Path(d)
                stubs = dict(STUBS)
                stub_board = 'buildcraft/robotics/boards/BoardRobotGenericSearchBlock.java'
                stubs[stub_board] = stubs[stub_board].replace(
                    'private final NBT nbt = new NBT("lumberjack");',
                    'private NBT nbt = new NBT("lumberjack");\n'
                    ' public void setBoardId(String id){nbt=new NBT(id);}')
                stubs[stub_board] = stubs[stub_board].replace(
                    ' public boolean processorAcceptsHints(){return acceptsProcessorCandidateHints();}',
                    ''' public boolean processorAcceptsHints(){return acceptsProcessorCandidateHints();}
 public boolean processorSupportsQueue(){return true;}
 public java.util.List<BlockPos> processorJobMembers(Level w,BlockPos p,int limit){
   if(nbt.id().equals("buildcraft:boardRobotLumberjack") && p.getX()>=2 && p.getX()<=4){
     return java.util.List.of(new BlockPos(2,64,1),new BlockPos(3,64,1),new BlockPos(4,64,1));
   }
   return java.util.List.of(p);
 }''')
                stub_entity = 'buildcraft/robotics/internal/legacy/robots/EntityRobotBase.java'
                stubs[stub_entity] = stubs[stub_entity].replace(
                    ' public long getRobotId(){return id;}',
                    ' public void setBoardId(String id){board.setBoardId(id);}\n public long getRobotId(){return id;}')
                stub_world = 'net/minecraft/world/level/Level.java'
                stubs[stub_world] = stubs[stub_world].replace(
                    ' public long time; public boolean sectionAir;',
                    ' public java.util.Set<BlockPos> extra = new java.util.HashSet<>();\n public long time; public boolean sectionAir;').replace(
                    'return new BlockState(!pos.equals(target));',
                    'return new BlockState(!extra.contains(pos));')
                stub_panel = 'buildcraft/robotics/tile/TileRobotProcessor.java'
                stubs[stub_panel] = stubs[stub_panel].replace(
                    ' public int getMaxExecutionQueue(){return 5;}',
                    ' public int max=5; public int getMaxExecutionQueue(){return max;}')
                sources = []
                for rel, text in stubs.items():
                    path = out / rel
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text(text)
                    sources.append(str(path))
                for rel in SOURCES:
                    path = out / rel.removeprefix('source-shared/src/main/java/')
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text(preprocess_text((ROOT / rel).read_text(),
                        minecraft=version, family=family, platform=platform, source=rel))
                    sources.append(str(path))
                fixture = out / 'fixture/Stage8Professions.java'
                fixture.parent.mkdir(parents=True, exist_ok=True)
                fixture.write_text(SIMULATION)
                sources.append(str(fixture))
                classes = out / 'classes'
                classes.mkdir()
                comp = subprocess.run(['javac', '--release', '17', '-d', str(classes), *sources],
                                      capture_output=True, text=True, timeout=60)
                self.assertEqual(comp.returncode, 0, comp.stderr)
                exe = subprocess.run(['java', '-cp', str(classes), 'fixture.Stage8Professions'],
                                     capture_output=True, text=True, timeout=20)
                self.assertEqual(exe.returncode, 0, exe.stdout + exe.stderr)

    def test_real_board_contracts_and_executor_protection(self):
        shared = ROOT / 'source-shared/src/main/java/buildcraft/robotics'
        generic = (shared / 'boards/BoardRobotGenericSearchBlock.java').read_text()
        self.assertIn('boolean processorSupportsQueue() { return true; }', generic)
        for board, action in (('BoardRobotHarvester', 'AIRobotHarvest'),
                              ('BoardRobotFarmer', 'AIRobotUseToolOnBlock'),
                              ('BoardRobotPlanter', 'AIRobotPlant')):
            source = (shared / 'boards' / f'{board}.java').read_text()
            self.assertIn(f'ai instanceof {action}', source)
            self.assertIn('RobotProcessorWorkScheduler.executionFinished', source)
        for board in ('BoardRobotFarmer', 'BoardRobotPlanter'):
            source = (shared / 'boards' / f'{board}.java').read_text()
            self.assertIn('processorSupportsQueue() { return true; }', source)
        for loader in ('forge', 'neoforge'):
            trees = (ROOT / f'source-platforms/{loader}/src/main/java/buildcraft/robotics/boards/BoardRobotLumberjack.java').read_text()
            self.assertIn('processorJobMembers(Level level, BlockPos origin', trees)
            self.assertIn('processorPlanningEligible(Level level, BlockPos pos)', trees)
        # Only the effective maintained sources are part of the runtime contract.
        # A recursive ROOT.glob also visits stale build/materialized and Gradle
        # output copies, which may predate the scheduler's final zone guard.
        properties = load_properties()
        targets = [name.strip() for name in properties['targets'].split(',') if name.strip()]
        self.assertTrue(targets, 'No registered Minecraft targets to validate')
        for ai_name in ('AIRobotPlant', 'AIRobotHarvest', 'AIRobotUseToolOnBlock'):
            relative = f'src/main/java/buildcraft/robotics/ai/{ai_name}.java'
            for target in targets:
                layout = target_layout(target, properties)
                source = resolve_effective_source(layout, properties, relative)
                with self.subTest(ai=ai_name, target=target):
                    self.assertIsNotNone(source, f'{target}: {relative} has no source owner')
                    effective = preprocess_text(
                        source.read_text(encoding='utf-8'),
                        minecraft=properties[f'target.{target}.deps.minecraft'],
                        family=layout.family, platform=layout.platform, source=str(source),
                    )
                    self.assertIn('!serverLevel.hasChunkAt(pos)', effective,
                                  f'{target}: {ai_name} must not operate in unloaded chunks')
                    self.assertIn('RobotProcessorWorkScheduler.allowsRobotTarget(robot, pos)', effective,
                                  f'{target}: {ai_name} must recheck the panel zone before execution')
