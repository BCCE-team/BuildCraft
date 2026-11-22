"""Actual 16-high section cursor plus loaded quarry scan across multiple Y levels."""
from pathlib import Path
import subprocess
import tempfile
import unittest

from scripts.tests.test_robot_processor_scheduler_runtime import ROOT, SOURCES
from scripts.tests.test_robot_processor_proactive_discovery import fixture_stubs
from scripts.source_preprocessor import preprocess_text

CURSOR_SOURCE = ROOT / 'source-shared/src/main/java/buildcraft/robotics/scheduler/RobotVerticalSectionScanCursor.java'

CURSOR_CONTRACT = r'''
import buildcraft.robotics.scheduler.RobotVerticalSectionScanCursor;
import java.util.*;
class VerticalSectionCursorContract {
 static void check(boolean condition, String message) {if(!condition)throw new AssertionError(message);}
 static void verify(int sections) {
  var cursor=new RobotVerticalSectionScanCursor();
  cursor.reset(sections);
  Set<Long> visited=new HashSet<>();
  int[] bySection=new int[sections];
  int total=0;
  while(!cursor.complete()) {
   int section=cursor.sectionIndex(), slice=cursor.sliceIndex();
   int x=cursor.localX(), y=cursor.localY(), z=cursor.localZ();
   check(x>=0&&x<16&&y>=0&&y<16&&z>=0&&z<16,"Invalid local coordinate");
   if (sections>1 && total<64*sections) check(slice==0,"Exhausted a section before visiting all vertical sections");
   long encoded=((long)section<<12)|y<<8|z<<4|x;
   check(visited.add(encoded),"Block double-scanned at "+encoded);
   bySection[section]++;
   cursor.advanceBlock();total++;
  }
  check(total==sections*4096,"Incomplete scan: "+total+" of "+sections*4096);
  for(int count:bySection)check(count==4096,"Uneven section coverage: "+count);
  cursor.advanceBlock();cursor.skipSection();
  check(cursor.complete(),"Completed pass reopened");
  // In the first four slices each horizontal column is observed once, and
  // all sixteen Y levels are represented, unlike an x/z-only stripe scan.
  var spread=new RobotVerticalSectionScanCursor();spread.reset(1);
  Set<Integer> firstFootprint=new HashSet<>(), firstHeights=new HashSet<>();
  for(int n=0;n<256;n++) {
   firstFootprint.add(spread.localZ()*16+spread.localX());
   firstHeights.add(spread.localY());
   spread.advanceBlock();
  }
  check(firstFootprint.size()==256,"First four rounds missed a horizontal column");
  check(firstHeights.size()==16,"First four rounds missed Y levels");
 }
 public static void main(String[] args){
  for(int count:new int[]{0,1,2,3,49})verify(count);
  var cursor=new RobotVerticalSectionScanCursor();cursor.reset(3);
  cursor.skipSection();check(cursor.sectionIndex()==1,"Skip didn't advance");
  cursor.skipSection();cursor.skipSection();
  check(cursor.sliceIndex()==1&&cursor.sectionIndex()==0,"Skip didn't advance to next vertical round");
  int seen=0;
  while(!cursor.complete()) {cursor.skipSection();seen++;check(seen<=200,"Skip loop never finished");}
  System.out.println("PASS 64-slice 3D coverage, exact 4096 voxels, round-robin and unloaded-section skipping");
 }
}
'''

QUARRY_SCENARIO = r'''
package fixture;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import buildcraft.robotics.internal.legacy.robots.*;
import buildcraft.robotics.scheduler.*;
import buildcraft.robotics.tile.TileRobotProcessor;
public class VerticalQuarryRuntime {
 static void check(boolean b, String s){if(!b)throw new AssertionError(s);}
 public static void main(String[] args) {
  ServerLevel level=new ServerLevel();
  // Rich surface ore at the LAST horizontal column of its section, and a
  // different deposit at the FIRST column, six sections below the workers.
  BlockPos surface=new BlockPos(15,79,15);
  BlockPos deep=new BlockPos(32,-31,0);
  level.ores.add(surface);level.ores.add(deep);
  // Four scattered lower voxels, including MIN_BUILD_HEIGHT and opposite
  // x/z corners: every stratum and column must eventually be visited.
  BlockPos lowerCorner=new BlockPos(47,-32,15);
  BlockPos upperCorner=new BlockPos(32,-17,15);
  BlockPos lowerWall=new BlockPos(47,-18,0);
  level.ores.add(lowerCorner);
  level.ores.add(upperCorner);
  level.ores.add(lowerWall);
  var first=new EntityRobotBase(level,101,new BlockPos(2,64,2));
  var second=new EntityRobotBase(level,102,new BlockPos(4,64,2));
  first.asleep=true;second.asleep=true;
  first.board.toolReady=false;second.board.toolReady=false;
  RobotManager.registryProvider=world->new IRobotRegistry(){
   public EntityRobotBase getLoadedRobot(long id){return id==101?first:id==102?second:null;}
   public boolean isTaken(ResourceIdBlock id){return false;}
   public long robotIdTaking(ResourceIdBlock id){return 0;}
  };
  TileRobotProcessor panel=new TileRobotProcessor(level,new BlockPos(0,64,0));
  panel.linked.add(101L);panel.linked.add(102L);
  RobotProcessorWorkScheduler scheduler=new RobotProcessorWorkScheduler(panel);
  // Previous whole-section traversal read 4096 surface blocks before
  // visiting this lower section. Measure reads TO FIRST DISCOVERY, not the
  // entire monitoring interval (which continues to fill the discovery cache).
  int readsAtDiscovery=-1;
  try {
   var field=RobotProcessorWorkScheduler.class.getDeclaredField("index");
   field.setAccessible(true);
   var index=(RobotWorkIndex)field.get(scheduler);
   for(int i=0;i<14;i++) {
    level.time++;scheduler.tick(level);
    if (readsAtDiscovery<0 && index.nearest("buildcraft:miner",32,-31,0,level.time,10,pos->true)
                          .contains(deep.asLong())) readsAtDiscovery=level.blockReads;
   }
  }catch(Exception ex){throw new AssertionError(ex);}
  check(readsAtDiscovery>=0,"Lower cave's vertical section starved behind upper ore");
  check(readsAtDiscovery<2048,"Too many reads before discovering lower section: "+readsAtDiscovery);
  first.board.toolReady=true;second.board.toolReady=true;
  check(RobotProcessorWorkScheduler.hasAvailableWorkForSleepingRobot(first),"Deep ore did not wake miner");
  first.asleep=false;second.asleep=false;
  for(int i=0;i<10;i++){level.time++;scheduler.tick(level);}
  check(RobotProcessorWorkScheduler.queuedMiningTargets(first).contains(deep)
     || RobotProcessorWorkScheduler.queuedMiningTargets(second).contains(deep),
     "Deep quarry ore not assigned to any worker");
  // Continue the same bounded scanner until it finishes a full vertical pass.
  // This catches the old surface-biased traversal which never reaches some
  // bottom and opposite-corner blocks before losing its cached work.
  first.asleep=true;second.asleep=true;
  try {
   var field=RobotProcessorWorkScheduler.class.getDeclaredField("index");
   field.setAccessible(true);
   var index=(RobotWorkIndex)field.get(scheduler);
   for(int i=0;i<160;i++) {level.time++;scheduler.tick(level);}
   var targets=index.nearest("buildcraft:miner",40,-26,8,level.time,20,p->true);
   for(BlockPos remaining:List.of(deep,lowerCorner,upperCorner,lowerWall)) {
    check(targets.contains(remaining.asLong()),"Lower ore never indexed: "+remaining);
   }
   var heights=index.heightExtent("buildcraft:miner",64);
   check(heights.minY()==-32 && heights.maxY()==79 && heights.below()>=4,
         "Vertical summary lost low or high ore: "+heights);
  } catch(ReflectiveOperationException e) {throw new AssertionError(e);}
  scheduler.onRemoved();
  System.out.println("PASS early Y<0 scan, sleeping miners, fair vertical sections and queueing");
 }
}
'''

class VerticalSectionPlanningTests(unittest.TestCase):
    def compile_run(self, sources, classname, root):
        files=[]
        for relative, content in sources.items():
            file=root/relative
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_text(content, encoding='utf-8')
            files.append(str(file))
        classes=root/'classes';classes.mkdir()
        compiled=subprocess.run(['javac','--release','17','-d',str(classes),*files],
                                capture_output=True,text=True,timeout=45)
        self.assertEqual(compiled.returncode,0,compiled.stderr)
        run=subprocess.run(['java','-cp',str(classes),classname],
                           capture_output=True,text=True,timeout=20)
        self.assertEqual(run.returncode,0,run.stdout+run.stderr)

    def test_cursor_covers_all_vertical_layers_in_many_sections(self):
        with tempfile.TemporaryDirectory(prefix='bc-vertical-cursor-') as tmp:
            self.compile_run({
                'buildcraft/robotics/scheduler/RobotVerticalSectionScanCursor.java':CURSOR_SOURCE.read_text(),
                'VerticalSectionCursorContract.java':CURSOR_CONTRACT,
            },'VerticalSectionCursorContract',Path(tmp))

    def test_lower_quarry_sections_are_discovered_before_upper_section_is_exhausted(self):
        for version,family,platform in [('1.19.2','old','forge'),
                                        ('1.21.11','1.21.X','neoforge'),
                                        ('26.3','26.X','fabric')]:
            with self.subTest(version=version,platform=platform),tempfile.TemporaryDirectory(prefix='bc-vertical-quarry-') as tmp:
                sources=fixture_stubs()
                if version == '26.3':
                    # ChunkPos.x/z are no longer public fields in 26.3.
                    # Mirror the accessor-only API so compiled scheduler code
                    # cannot silently depend on old direct field access.
                    sources['net/minecraft/world/level/ChunkPos.java'] = (
                        'package net.minecraft.world.level; '
                        'public record ChunkPos(int x, int z) {}'
                    )
                for rel in SOURCES:
                    path=ROOT/rel
                    sources[rel.removeprefix('source-shared/src/main/java/')]=preprocess_text(
                        path.read_text(),minecraft=version,family=family,platform=platform,source=rel)
                sources['fixture/VerticalQuarryRuntime.java']=QUARRY_SCENARIO
                self.compile_run(sources,'fixture.VerticalQuarryRuntime',Path(tmp))

if __name__=='__main__':unittest.main()
