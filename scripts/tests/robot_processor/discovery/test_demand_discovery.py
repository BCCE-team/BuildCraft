"""Stage 4: demand-driven candidate discovery with real JVM scheduler fixtures.

The scanner runs against stub-loaded chunks for both legacy and modern
Stonecutter code. These are NOT a replacement for in-game Gradle/GameTests.
"""
from pathlib import Path
import subprocess
import tempfile
import unittest
import sys

ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT / 'scripts'))
from source_preprocessor import preprocess_text
from scripts.tests.robot_processor.discovery.test_scheduler_runtime import STUBS, SOURCES

FIXTURE = r'''package fixture;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import buildcraft.robotics.internal.legacy.robots.*;
import buildcraft.robotics.scheduler.*;
import buildcraft.robotics.tile.TileRobotProcessor;
public class Stage4Fixture {
 static void check(boolean ok, String message) {if (!ok) throw new AssertionError(message);}
 public static void main(String[] args) {
  ServerLevel level = new ServerLevel();
  var first = new EntityRobotBase(level,1,new BlockPos(0,65,0));
  var second = new EntityRobotBase(level,2,new BlockPos(0,65,1));
  first.setBoardId("buildcraft:harvester");
  second.setBoardId("buildcraft:harvester");
  RobotManager.registryProvider = world -> new IRobotRegistry() {
    public EntityRobotBase getLoadedRobot(long id) {return id==1?first:id==2?second:null;}
    public boolean isTaken(ResourceIdBlock resource) {return false;}
    public long robotIdTaking(ResourceIdBlock resource) {return 0;}
  };
  TileRobotProcessor panel = new TileRobotProcessor(level,new BlockPos(0,65,0));
  panel.linked.add(1L);panel.linked.add(2L);
  RobotProcessorWorkScheduler scheduler = new RobotProcessorWorkScheduler(panel);
  level.sectionAir=true;
  for(int tick=0;tick<4;tick++) {level.time++;scheduler.tick(level);}
  check(level.blockReads==0,"All-air section read voxel-by-voxel");
  check(RobotProcessorWorkScheduler.suggest(first)==null,"Empty section has work");
  // A recent mutation must work even though the full pass is sleeping.
  level.sectionAir=false;
  RobotProcessorWorkScheduler.worldBlockChanged(level,level.target);
  level.time++; scheduler.tick(level);
  var hint=RobotProcessorWorkScheduler.suggest(first);
  check(hint!=null && hint.position().equals(level.target),"Dirty update did not find crop");
  check(RobotProcessorWorkScheduler.suggest(second)==null,"Same crop offered twice");
  RobotProcessorWorkScheduler.releaseHint(first,hint);
  // World changes invalidate the old crop and expose new work without a full pass.
  BlockPos old=level.target;
  level.target=new BlockPos(3,64,3);
  RobotProcessorWorkScheduler.worldBlockChanged(level,old);
  RobotProcessorWorkScheduler.worldBlockChanged(level,level.target);
  level.time++; scheduler.tick(level);
  var replacement=RobotProcessorWorkScheduler.suggest(second);
  check(replacement!=null && replacement.position().equals(level.target),
      "Crop growth/harvest invalidation missed fresh target");
  RobotProcessorWorkScheduler.releaseHint(second,replacement);
  scheduler.onRemoved();

  // The same dispatch protocol can handle farm worker categories via the
  // candidate-board interface, without calling their world actions directly.
  first.setBoardId("buildcraft:farmer");
  second.setBoardId("buildcraft:farmer");
  RobotProcessorWorkScheduler farm = new RobotProcessorWorkScheduler(panel);
  level.time++;farm.tick(level);
  var farmland = RobotProcessorWorkScheduler.suggest(first);
  check(farmland!=null && farmland.position().equals(level.target),
       "Farmer candidate contract did not enter shared index");
  farm.onRemoved();
  // An explicitly linked single worker still gets a discovery pass.
  panel.linked.remove(2L);
  first.asleep=true;
  var solo = new RobotProcessorWorkScheduler(panel);
  level.time++;solo.tick(level);
  check(solo.scanProgress().phase()!=RobotProcessorWorkScheduler.SCAN_WAITING,
       "A single sleeping linked worker never started discovery");
  int oldProgress=solo.scanProgress().percent();
  solo.onPowerPaused();
  check(solo.scanProgress().percent()==oldProgress,"Power pause discarded scan coverage");
  level.time++;solo.tick(level);
  check(solo.scanProgress().percent()>=oldProgress,"Resuming power restarted discovery");
  solo.onRemoved();
  System.out.println("PASS: sparse sections, changed crops, exclusive hints, farm category, single sleeping worker and power pause");
 }
}'''

INDEX_FIXTURE = r'''import buildcraft.robotics.scheduler.RobotWorkIndex;
public class VerifyWorkIndex {
 public static void main(String[] args) {
  var i = new RobotWorkIndex();
  i.observe("harvest",11,10);i.observe("harvest",12,10);
  if(i.countMatching("harvest",10,v->v!=11)!=1)throw new AssertionError("Counts reserved work");
  i.observe("planter",12,10);
  i.invalidatePosition(12);
  if(i.count("harvest",10)!=1 || i.count("planter",10)!=0)
   throw new AssertionError("World mutation not invalidated across categories");
  if(i.countMatching("harvest",1212,v->true)!=1)
   throw new AssertionError("Discovered work must survive inactive ticks");
 } }'''

class DemandDiscoveryTests(unittest.TestCase):
    def test_jvm_sparse_chunks_dirty_refresh_categories_and_fallback(self):
        versions = (('1.19.2','old','forge'), ('1.20.1','old','forge'),
                    ('1.21.1','1.21.X','neoforge'), ('1.21.11','1.21.X','neoforge'),
                    ('26.1.2','26.X','neoforge'), ('26.2','26.X','neoforge'),
                    ('26.3','26.X','neoforge'), ('26.3','26.X','fabric'))
        for version, family, platform in versions:
            with self.subTest(version=version, platform=platform), tempfile.TemporaryDirectory(prefix='bc-stage4-') as d:
                folder = Path(d)
                stubs = dict(STUBS)
                key = 'buildcraft/robotics/boards/BoardRobotGenericSearchBlock.java'
                stubs[key] = stubs[key].replace('private final NBT nbt = new NBT("lumberjack");',
                    'private NBT nbt = new NBT("lumberjack");\n public void setBoardId(String id){nbt=new NBT(id);}')
                key = 'buildcraft/robotics/internal/legacy/robots/EntityRobotBase.java'
                stubs[key] = stubs[key].replace(' public long getRobotId(){return id;}',
                    ' public void setBoardId(String id){board.setBoardId(id);}\n public long getRobotId(){return id;}')
                stubs[key] = stubs[key].replace('public AIRobot getActiveAI(){return board;}',
                    'public boolean asleep; public AIRobot getActiveAI(){return asleep?new AIRobot():board;}')
                files=[]
                for rel, text in stubs.items():
                    path = folder/rel;path.parent.mkdir(parents=True,exist_ok=True)
                    path.write_text(text);files.append(str(path))
                for rel in SOURCES:
                    dest = folder/rel.removeprefix('source-shared/src/main/java/')
                    dest.parent.mkdir(parents=True,exist_ok=True)
                    dest.write_text(preprocess_text((ROOT/rel).read_text(),minecraft=version,
                        family=family, platform=platform, source=rel))
                    files.append(str(dest))
                test=folder/'fixture/Stage4Fixture.java';test.parent.mkdir(parents=True,exist_ok=True)
                test.write_text(FIXTURE);files.append(str(test))
                output=folder/'classes';output.mkdir()
                compiled=subprocess.run(['javac','--release','17','-d',str(output),*files],
                    capture_output=True,text=True,timeout=45)
                self.assertEqual(compiled.returncode,0,compiled.stderr)
                run=subprocess.run(['java','-cp',str(output),'fixture.Stage4Fixture'],
                    capture_output=True,text=True,timeout=20)
                self.assertEqual(run.returncode,0,run.stdout+run.stderr)

    def test_index_available_count_and_invalidate_all_categories(self):
        with tempfile.TemporaryDirectory(prefix='bc-stage4-index-') as d:
            test=Path(d)/'VerifyWorkIndex.java';test.write_text(INDEX_FIXTURE)
            compiled=subprocess.run(['javac','--release','17','-d',d,
                str(ROOT/'source-shared/src/main/java/buildcraft/robotics/scheduler/RobotWorkIndex.java'),str(test)],
                capture_output=True,text=True)
            self.assertEqual(compiled.returncode,0,compiled.stderr)
            run=subprocess.run(['java','-cp',d,'VerifyWorkIndex'],capture_output=True,text=True)
            self.assertEqual(run.returncode,0,run.stderr)

    def test_ordinary_ais_remain_and_profession_criteria_are_dynamic(self):
        boards=ROOT/'source-shared/src/main/java/buildcraft/robotics/boards'
        farmer=(boards/'BoardRobotFarmer.java').read_text()
        planter=(boards/'BoardRobotPlanter.java').read_text()
        harvester=(boards/'BoardRobotHarvester.java').read_text()
        lumberjack=(ROOT/'source-platforms/forge/src/main/java/buildcraft/robotics/boards/BoardRobotLumberjack.java').read_text()
        self.assertIn('implements ProcessorCandidateBoard',farmer)
        self.assertIn('implements ProcessorCandidateBoard',planter)
        self.assertIn('return isHoe(held) && isExpectedBlock(level, pos)',farmer)
        self.assertIn('seedFilter.matches(seed) && isPlantable(level, seed, pos)',planter)
        self.assertIn('new AIRobotSearchAndGotoBlock(robot, false, this::isExpectedBlock)',farmer)
        self.assertIn('new AIRobotSearchAndGotoBlock(robot, false, (level, pos) -> isPlantable',planter)
        self.assertIn('RobotProcessorWorkScheduler.executionFinished',harvester)
        self.assertIn('currentTree.contains(pos)',lumberjack)

if __name__=='__main__': unittest.main()
