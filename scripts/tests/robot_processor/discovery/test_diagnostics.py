"""Stage 26: actual Java telemetry, priority and bounded catalog threshold checks."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[4]
SCHED = ROOT / 'source-shared/src/main/java/buildcraft/robotics/scheduler'
SOURCE_FILES = [SCHED / name for name in (
    'RobotProcessorDiagnostics.java', 'RobotWorkIndex.java', 'RobotMiningPriority.java', 'RobotExecutionQueue.java')]

SHIM = '''package net.minecraft.core;
public record BlockPos(int x,int y,int z){
 public int getX(){return x;} public int getY(){return y;} public int getZ(){return z;}
 public long asLong(){return ((long)x&0x3ffffffL)<<38|((long)z&0x3ffffffL)<<12|((long)y&0xfffL);}
 public static BlockPos of(long p){return new BlockPos((int)(p>>38),(int)(p<<52>>52),(int)(p<<26>>38));}
}'''
FIXTURE = '''import buildcraft.robotics.scheduler.*;
import net.minecraft.core.BlockPos;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
public class Stage26MetricsFixture {
 static void ok(boolean value,String message){if(!value)throw new AssertionError(message);}
 public static void main(String...args){
  RobotProcessorDiagnostics d=new RobotProcessorDiagnostics();
  ok(d.snapshot(0,0,0,0).inspected()==0,"new telemetry nonempty");
  for(int tick=1;tick<=20;tick++){
   d.beginTick(tick); d.inspected(); d.background();
   if(tick%2==0){d.dirty();d.focused();}
   if(tick==4){d.budgetLimited();d.skippedSection();}
   d.finishTick(tick==20?200000:100000);
  }
  ok(d.snapshot(1,1,2,3).inspected()==0,"published partial window prematurely");
  d.beginTick(21);
  var snapshot=d.snapshot(123,2,4,7);
  ok(snapshot.inspected()==20&&snapshot.background()==20,"real block read metrics wrong");
  ok(snapshot.dirty()==10&&snapshot.focused()==10&&snapshot.skippedSections()==1,"source counters wrong");
  ok(snapshot.budgetLimitedTicks()==1,"budget denied ticks not counted");
  ok(snapshot.averageMicros()==105&&snapshot.peakMicros()==200,"CPU window incorrect");
  ok(snapshot.cachedCandidates()==123&&snapshot.linkedRobots()==4&&snapshot.loadedChunks()==7,"live snapshot metadata stale");
  d.finishTick(300000);
  d.beginTick(10000); // unload/game-time jump: bounded window
  ok(d.snapshot(0,0,0,0).averageMicros()==300,"window under time jump missing");
  d.reset();ok(d.snapshot(0,0,0,0).peakMicros()==0,"reset leaked profiling window");
  var index=new RobotWorkIndex();
  for(int i=0;i<50000;i++)index.observe("buildcraft:miner",i,0);
  AtomicInteger predicates=new AtomicInteger();
  int first=index.countMatchingUpTo("buildcraft:miner",1,pos->{predicates.incrementAndGet();return true;},5);
  ok(first==5&&predicates.get()==5,"threshold scan walked entire huge catalog");
  predicates.set(0);
  int selective=index.countMatchingUpTo("buildcraft:miner",1,pos->{predicates.incrementAndGet();return pos%2==0;},7);
  ok(selective==7&&predicates.get()==13,"threshold scan skipped candidate validation");
  predicates.set(0);
  ok(index.countMatchingUpTo("buildcraft:miner",1,pos->{predicates.incrementAndGet();return true;},0)==0
      && predicates.get()==0,"zero threshold should not scan");
  ok(index.totalCandidates()==50000,"metadata size wrong");
  index.invalidate("buildcraft:miner",42);
  ok(index.totalCandidates()==49999,"index invalidation missed live metadata");
  var a=new BlockPos(-30000000,-64,30000000); var b=new BlockPos(30000000,319,-30000000);
  ok(RobotMiningPriority.squaredDistance(a,b)>0,"large coordinates overflowed");
  List<Long> entries=List.of(new BlockPos(9,65,0).asLong(),new BlockPos(2,65,0).asLong(),
         new BlockPos(4,65,0).asLong(),new BlockPos(-2,65,0).asLong());
  BlockPos origin=new BlockPos(0,65,0);
  var nearest=RobotMiningPriority.nearestMembers(origin,entries,2);
  ok(nearest.size()==2&&RobotMiningPriority.squaredDistance(origin,BlockPos.of(nearest.get(0)))==4
      && RobotMiningPriority.squaredDistance(origin,BlockPos.of(nearest.get(1)))==4,"nearest members not ordered deterministically");
  ok(RobotMiningPriority.nearestMemberSquared(origin,entries)==4,"wrong nearest member");
  ok(RobotMiningPriority.materiallyCloser(10,100)&&!RobotMiningPriority.materiallyCloser(95,100),"missing distance hysteresis");
  var queue = new RobotExecutionQueue();
  ok(queue.addDeposit(91,Map.of(10L,1L,11L,2L),2),"initial deposit rejected");
  ok(queue.addDeposit(91,Map.of(20L,3L),2),"second deposit rejected");
  ok(queue.replacePendingDeposit(91,10L,Map.of(20L,4L))==null,
      "overlap replacement stole another deposit");
  ok(queue.contains(91,10L),"failed swap released pending deposit");
  ok(queue.replacePendingDeposit(91,10L,Map.of(11L,99L))==null,
      "same-cell swap can release a replacement claim");
  var displaced=queue.replacePendingDeposit(91,10L,Map.of(30L,5L,31L,6L));
  ok(displaced!=null&&displaced.remainingBlocks()==2&&queue.size(91)==2,
      "atomic reprioritization changed queue size");
  ok(queue.contains(91,30L)&&!queue.contains(91,10L),"new deposit not swapped");
  ok(queue.activate(91,30L),"active replacement not claimable");
  ok(queue.replacePendingDeposit(91,30L,Map.of(40L,7L))==null,
      "active executing deposit was displaced");
  ok(queue.contains(91,30L)&&queue.contains(91,20L),"failed swap damaged other work");
  System.out.println("PASS: 20t telemetry, zero work sampling, bounded count and ordered nearest ore");
 }
}'''

class ProcessorDiagnosticsTests(unittest.TestCase):
    def test_native_value_only_diagnostics_and_priority(self):
        with tempfile.TemporaryDirectory(prefix='bc-stage26-metrics-') as directory:
            folder=Path(directory)
            pos=folder/'net/minecraft/core/BlockPos.java'
            pos.parent.mkdir(parents=True,exist_ok=True);pos.write_text(SHIM)
            test=folder/'Stage26MetricsFixture.java';test.write_text(FIXTURE)
            javac=subprocess.run(['javac','--release','17','-d',str(folder),str(pos),str(test),*(str(f) for f in SOURCE_FILES)],
                                 text=True,capture_output=True,timeout=45)
            self.assertEqual(javac.returncode,0,javac.stdout+javac.stderr)
            run=subprocess.run(['java','-cp',str(folder),'Stage26MetricsFixture'],
                               text=True,capture_output=True,timeout=15)
            self.assertEqual(run.returncode,0,run.stdout+run.stderr)

    def test_packet_consistency_and_hover_in_all_gui_families(self):
        container=(ROOT/'source-shared/src/main/java/buildcraft/robotics/container/ContainerRobotProcessor.java').read_text()
        self.assertLess(container.index('allocId("scan_progress")'),container.index('allocId("processor_diagnostics")'))
        self.assertIn('getRobotWaitReason(robot)',container)
        self.assertIn('buf.writeUtf(robot.reason(), 96)',container)
        self.assertIn('buffer.readUtf(96)',container)
        for field in ('inspected','dirty','focused','background','skippedSections','budgetLimitedTicks',
                      'cachedCandidates','categories','linkedRobots','loadedChunks','averageMicros','peakMicros'):
            self.assertIn('next.'+field+'()',container)
        for f in (
             ROOT/'version-src/1.19.2-forge/src/main/java/buildcraft/robotics/gui/GuiRobotProcessor.java',
             ROOT/'version-src/1.20.1-forge/src/main/java/buildcraft/robotics/gui/GuiRobotProcessor.java',
             ROOT/'source-families/1.21.X/src/main/java/buildcraft/robotics/gui/GuiRobotProcessor.java',
             ROOT/'source-downports/1.21.X/1.21.1/family/src/main/java/buildcraft/robotics/gui/GuiRobotProcessor.java',
             ROOT/'source-families/26.X/src/main/java/buildcraft/robotics/gui/GuiRobotProcessor.java'):
            text=f.read_text()
            self.assertIn('diagnosticLines(mouseX, mouseY)',text,f.name)
            self.assertIn('selected.reason()',text,f.name)
            self.assertIn('getDiagnostics()',text,f.name)
            self.assertIn('RobotProcessorUiText.component("diag.load.reads"',text,f.name)

    def test_target_source_resolution_all_supported_variants(self):
        import sys
        sys.path.insert(0, str(ROOT / 'scripts'))
        from source_lookup import read_materialized_java, resolve_target_source
        from source_config import target_ids
        versions = target_ids()
        paths = ('gui/GuiRobotProcessor.java', 'container/ContainerRobotProcessor.java',
                 'scheduler/RobotProcessorWorkScheduler.java',
                 'scheduler/RobotMiningPriority.java',
                 'scheduler/RobotProcessorDiagnostics.java')
        for version in versions:
            with self.subTest(version=version):
                for subpath in paths:
                    logical = 'src/main/java/buildcraft/robotics/' + subpath
                    self.assertTrue(resolve_target_source(version,logical).exists())
                    code=read_materialized_java(version, logical)
                    self.assertNotIn('//?',code,subpath)
                    if subpath.startswith('gui/'):
                        self.assertEqual(code.count('private List<Component> diagnosticLines('),1)
                    if subpath.endswith('RobotProcessorWorkScheduler.java'):
                        # A full discovery pass must not stop at execution-queue capacity.
                        self.assertIn('if (queuedCategories.contains(category)) missing = Math.max(1, missing);',code)
                        self.assertIn('remainingDemand.put(category, missing)',code)
                        self.assertIn('diagnostics.snapshot(index.totalCandidates()',code)

if __name__=='__main__': unittest.main()
