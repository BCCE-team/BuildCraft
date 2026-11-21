"""Stage 16: exact progress accounting, server-side phases and GUI synchronization."""
from pathlib import Path
import subprocess
import tempfile
import unittest

from scripts.tests.test_robot_processor_scheduler_runtime import ROOT, SOURCES
from scripts.tests.test_robot_processor_proactive_discovery import fixture_stubs
from scripts.source_preprocessor import preprocess_text

CURSOR_PATH = ROOT / 'source-shared/src/main/java/buildcraft/robotics/scheduler/RobotVerticalSectionScanCursor.java'
CURSOR_FIXTURE = r'''
import buildcraft.robotics.scheduler.RobotVerticalSectionScanCursor;
class ScanPercentFixture {
 static void check(boolean okay,String why){if(!okay)throw new AssertionError(why);}
 public static void main(String[] args){
  RobotVerticalSectionScanCursor c=new RobotVerticalSectionScanCursor();
  c.reset(3);
  check(c.totalWorkUnits()==3L*4096L,"wrong denominator");
  check(c.completedWorkUnits()==0 && c.progressPercent()==0,"nonzero initial percent");
  for(int i=0;i<40;i++)c.advanceBlock();
  check(c.completedWorkUnits()==40 && c.progressPercent()==0,"premature rounding");
  for(int i=0;i<24;i++)c.advanceBlock();
  check(c.completedWorkUnits()==64,"wrong partial slice count");
  c.skipSection();
  check(c.completedWorkUnits()==128,"skipped section not counted as resolved");
  int prior=c.progressPercent();
  while(!c.complete()){
   long old=c.completedWorkUnits();
   c.skipSection();
   check(c.completedWorkUnits()>=old,"percentage went backwards");
   check(c.progressPercent()>=prior,"percentage decreased");
   prior=c.progressPercent();
  }
  check(c.progressPercent()==100,"completion must be exactly 100 percent");
  check(c.completedWorkUnits()==c.totalWorkUnits(),"completion count mismatched");
  c.reset(1);
  check(c.progressPercent()==0,"new pass did not reset cursor progress");
  c.reset(0);
  check(c.progressPercent()==100&&c.totalWorkUnits()==0,"empty pass is complete");
  System.out.println("PASS precise percentage, skipped sections, reset and completion");
 }
}
'''

WORLD_FIXTURE = r'''
package fixture;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import buildcraft.robotics.internal.legacy.robots.*;
import buildcraft.robotics.scheduler.*;
import buildcraft.robotics.tile.TileRobotProcessor;
public class LiveScanProgressFixture {
 static void check(boolean okay,String why){if(!okay)throw new AssertionError(why);}
 static void tick(ServerLevel level,RobotProcessorWorkScheduler scheduler){level.time++;scheduler.tick(level);}
 public static void main(String[] args){
  ServerLevel world=new ServerLevel();
  world.maxChunkX=0;
  world.ores.add(new BlockPos(2,64,2));
  var miner=new EntityRobotBase(world,31,new BlockPos(2,64,2));
  var miner2=new EntityRobotBase(world,32,new BlockPos(4,64,2));
  miner.asleep=true;miner2.asleep=true;
  // Keeping the tools absent is intentional: discovery must not depend on
  // workers occupying an execution queue or currently being awake.
  miner.board.toolReady=false;miner2.board.toolReady=false;
  RobotManager.registryProvider=level->new IRobotRegistry(){
   public EntityRobotBase getLoadedRobot(long id){return id==31?miner:id==32?miner2:null;}
   public boolean isTaken(ResourceIdBlock res){return false;}
   public long robotIdTaking(ResourceIdBlock res){return 0;}
  };
  var panel=new TileRobotProcessor(world,new BlockPos(0,64,0));
  panel.linked.add(31L);panel.linked.add(32L);
  RobotProcessorWorkScheduler scheduler=new RobotProcessorWorkScheduler(panel);
  var snapshot=scheduler.scanProgress();
  check(snapshot.phase()==RobotProcessorWorkScheduler.SCAN_WAITING,"initial state should be waiting");
  int highest=0;boolean completed=false,observedProgress=false;
  for(int i=0;i<80;i++){
   tick(world,scheduler);
   snapshot=scheduler.scanProgress();
   check(snapshot.percent()>=highest,"first scan percent regressed");
   highest=snapshot.percent();
   if(snapshot.percent()>0 && snapshot.percent()<100)observedProgress=true;
   if(snapshot.phase()==RobotProcessorWorkScheduler.SCAN_COMPLETE){completed=true;break;}
  }
  check(observedProgress,"did not expose partial scan percent");
  check(completed&&snapshot.percent()==100&&snapshot.currentPassPercent()==100,
       "first pass never finished at 100 percent");
  boolean refreshing=false;
  for(int i=0;i<260;i++){
   tick(world,scheduler);
   snapshot=scheduler.scanProgress();
   check(snapshot.percent()==100,"100 percent disappeared during normal periodic refresh");
   if(snapshot.phase()==RobotProcessorWorkScheduler.SCAN_REFRESHING){
    refreshing=true;
    check(snapshot.currentPassPercent()<100,"refresh has no independent progress");
    break;
   }
  }
  check(refreshing,"never exposed a background refresh pass");
  // New loaded chunks and matching ore invalidate the earlier first-pass 100%.
  world.maxChunkX=2;
  world.ores.add(new BlockPos(34,64,2));
  boolean scopeReset=false;
  for(int i=0;i<320;i++){
   tick(world,scheduler);
   snapshot=scheduler.scanProgress();
   if(snapshot.phase()==RobotProcessorWorkScheduler.SCAN_RUNNING){
    check(snapshot.percent()<100,"new region incorrectly counted as already scanned");
    check(snapshot.sections()==2,"two matching vertical sections expected");
    scopeReset=true;break;
   }
  }
  check(scopeReset,"newly loaded chunk did not restart initial scan coverage");
  for(int i=0;i<100&&scheduler.scanProgress().percent()<100;i++)tick(world,scheduler);
  check(scheduler.scanProgress().percent()==100,"new loaded section not fully scanned");
  scheduler.onRemoved();
  // A connected panel with no loaded chunks must never claim completion.
  world.maxChunkX=-1;
  var empty=new RobotProcessorWorkScheduler(panel);
  tick(world,empty);
  check(empty.scanProgress().phase()==RobotProcessorWorkScheduler.SCAN_NO_CHUNKS,
      "missing chunks incorrectly treated as scanned");
  empty.onRemoved();
  System.out.println("PASS first scan, sticky 100%, rescan, new chunk and no-loaded-chunks state");
 }
}
'''


class ProcessorScanPercentTests(unittest.TestCase):
    def compile_run(self, inputs, classname, directory):
        sources=[]
        for rel,code in inputs.items():
            path=directory/rel
            path.parent.mkdir(parents=True,exist_ok=True)
            path.write_text(code,encoding='utf-8')
            sources.append(str(path))
        classes=directory/'classes';classes.mkdir()
        compilation=subprocess.run(['javac','--release','17','-d',str(classes),*sources],
                                   capture_output=True,text=True,timeout=55)
        self.assertEqual(compilation.returncode,0,compilation.stderr)
        execute=subprocess.run(['java','-cp',str(classes),classname],
                               capture_output=True,text=True,timeout=30)
        self.assertEqual(execute.returncode,0,execute.stdout+execute.stderr)

    def test_cursor_exact_accounting(self):
        with tempfile.TemporaryDirectory(prefix='bc-scan-percent-') as d:
            self.compile_run({'buildcraft/robotics/scheduler/RobotVerticalSectionScanCursor.java':
                              CURSOR_PATH.read_text(),
                              'ScanPercentFixture.java':CURSOR_FIXTURE},
                             'ScanPercentFixture',Path(d))

    def test_first_pass_and_periodic_refresh_in_real_scheduler(self):
        for version,family,loader in [('1.19.2','old','forge'),
                                      ('1.21.11','1.21.X','neoforge'),
                                      ('26.3','26.X','fabric')]:
            with self.subTest(version=version,loader=loader),tempfile.TemporaryDirectory(prefix='bc-scan-state-') as d:
                sources=fixture_stubs()
                world=sources['net/minecraft/world/level/Level.java']
                world=world.replace('public int blockReads;', 'public int blockReads;public int maxChunkX=2;')
                world=world.replace('(pos.getX()>>4)<=2', '(pos.getX()>>4)<=maxChunkX')
                sources['net/minecraft/world/level/Level.java']=world
                for rel in SOURCES:
                    src=ROOT/rel
                    sources[rel.removeprefix('source-shared/src/main/java/')]=preprocess_text(
                        src.read_text(),minecraft=version,family=family,platform=loader,source=rel)
                sources['fixture/LiveScanProgressFixture.java']=WORLD_FIXTURE
                self.compile_run(sources,'fixture.LiveScanProgressFixture',Path(d))

    def test_gui_and_network_wired_on_all_versions(self):
        menu=(ROOT/'source-shared/src/main/java/buildcraft/robotics/container/ContainerRobotProcessor.java').read_text()
        self.assertIn('ID_SCAN_PROGRESS = ROBOT_IDS.allocId("scan_progress")',menu)
        self.assertIn('sendMessage(ID_SCAN_PROGRESS, out ->',menu)
        self.assertIn('id == ID_SCAN_PROGRESS && side == BCNetworkSide.CLIENT',menu)
        self.assertIn('snapshotTick % 10 == 0',menu)
        self.assertIn('getScanProgress()',menu)
        self.assertIn('scanProgress = new ScanProgress(',menu)
        tile=(ROOT/'source-shared/src/main/java/buildcraft/robotics/tile/TileRobotProcessor.java').read_text()
        self.assertIn('workScheduler.scanProgress()',tile)
        for rel in [
            'version-src/1.19.2-forge/src/main/java/buildcraft/robotics/gui/GuiRobotProcessor.java',
            'version-src/1.20.1-forge/src/main/java/buildcraft/robotics/gui/GuiRobotProcessor.java',
            'source-families/1.21.X/src/main/java/buildcraft/robotics/gui/GuiRobotProcessor.java',
            'source-families/26.X/src/main/java/buildcraft/robotics/gui/GuiRobotProcessor.java',
        ]:
            with self.subTest(gui=rel):
                content=(ROOT/rel).read_text()
                self.assertIn('renderScanProgress(pose);',content)
                self.assertIn('"Scan: " + scan.percent() + "%"',content)
                self.assertIn('"Rescan: " + scan.passPercent() + "%"',content)
                self.assertIn('panelFill(pose, 215, 130, 316, 134',content)

if __name__=='__main__': unittest.main()
