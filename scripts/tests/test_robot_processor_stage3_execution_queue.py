"""Stage 3: real bounded miner orders, lease ownership, queue completion and telemetry.

The JVM fixtures compile the Java implementation, not a copy of queue logic.
The simulated-world test uses the stage-1/2 Minecraft stand-ins for 1.19.2 and 26.3.
"""
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from source_preprocessor import preprocess_text
from scripts.tests.test_robot_processor_scheduler_runtime import STUBS as BASE_STUBS, SOURCES

QUEUE = ROOT / 'source-shared/src/main/java/buildcraft/robotics/scheduler/RobotExecutionQueue.java'
LEASES = ROOT / 'source-shared/src/main/java/buildcraft/robotics/scheduler/RobotWorkLeases.java'

QUEUE_FIXTURE = r'''
import buildcraft.robotics.scheduler.*;
import java.util.List;
public class VerifyExecutionQueue {
 static void check(boolean yes,String why){if(!yes)throw new AssertionError(why);}
 public static void main(String[] args){
  var q=new RobotExecutionQueue();
  for(int i=0;i<8;i++)check(q.add(7,i+1,i+100,8),"Cannot fill queue");
  check(!q.add(7,9,109,8),"Overfilled 8");
  check(!q.add(7,1,120,8),"Duplicate work");
  check(q.activate(7,5),"Failed activation");
  check(q.jobs(7).get(0).position()==5 && q.jobs(7).get(0).active(),"Active head not first");
  var trimmed=q.trim(7,1);
  check(trimmed.size()==7 && q.size(7)==1,"8->1 trim");
  check(q.jobs(7).get(0).position()==5,"Lost active job");
  check(q.remove(7,1)==null,"Removed untracked job");
  check(q.remove(7,5)!=null && q.size(7)==0,"Completed job not removed");
  check(q.add(9,10,22,1) && !q.add(9,11,23,1),"Limit one");
  check(q.trim(9,0).size()==1,"Cannot evict an old task for an already executing break");
  var l=new RobotWorkLeases();
  long token=l.claim(66,7,300,100,20);
  check(token>0 && !l.available(66,8,300,110),"No exclusive lease");
  check(!l.renew(66,8,300,token,110,20),"Other robot extended lease");
  check(!l.renew(66,7,300,token+1,110,20),"Old token extended lease");
  check(l.renew(66,7,300,token,110,20),"Couldn't renew own lease");
  l.release(66,7,token+1);
  check(!l.available(66,8,300,125),"Stale release destroyed new claim");
  l.release(66,7,token);
  check(l.available(66,8,300,125),"Lease not released at completion");
  check(!l.renew(66,7,300,token,125,10),"Extended released lease");
  System.out.println("PASS: exact execution capacity, active operation, lease lifecycle");
 }
}
'''

SIMULATED_WORLD = r'''
package fixture;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import buildcraft.robotics.internal.legacy.robots.*;
import buildcraft.robotics.tile.TileRobotProcessor;
import buildcraft.robotics.scheduler.RobotProcessorWorkScheduler;
import java.util.*;
public class Stage3MiningFixture {
 static void check(boolean condition,String msg){if(!condition)throw new AssertionError(msg);}
 public static void main(String[] args) {
  ServerLevel level=new ServerLevel();
  level.extra.add(new BlockPos(2,64,1));
  level.extra.add(new BlockPos(3,64,1));
  level.extra.add(new BlockPos(4,64,1));
  var a=new EntityRobotBase(level,1,new BlockPos(0,65,0));
  var b=new EntityRobotBase(level,2,new BlockPos(0,65,2));
  a.setBoardId("buildcraft:miner");
  b.setBoardId("buildcraft:miner");
  RobotManager.registryProvider = world -> new IRobotRegistry() {
    public EntityRobotBase getLoadedRobot(long id){return id==1?a:id==2?b:null;}
    public boolean isTaken(ResourceIdBlock res){return false;}
    public long robotIdTaking(ResourceIdBlock res){return 0;}
  };
  var panel=new TileRobotProcessor(level,new BlockPos(0,65,0));
  panel.linked.add(1L);panel.linked.add(2L);
  var scheduler=new RobotProcessorWorkScheduler(panel);
  for(int i=0;i<8;i++){level.time++;scheduler.tick(level);}
  var workA=RobotProcessorWorkScheduler.queuedMiningTargets(a);
  var workB=RobotProcessorWorkScheduler.queuedMiningTargets(b);
  check(!workA.isEmpty() && !workB.isEmpty(),"Miners were not assigned real queued work");
  check(scheduler.queueCount(1)==workA.size() && scheduler.queueCount(2)==workB.size(),"Incorrect GUI counts");
  Set<BlockPos> union=new HashSet<>(workA);union.addAll(workB);
  check(union.size()==workA.size()+workB.size(),"Two miners share the same queued ore");
  BlockPos selected=workA.get(0);
  RobotProcessorWorkScheduler.targetAcquired(a,selected);
  check(RobotProcessorWorkScheduler.queuedMiningTargets(a).get(0).equals(selected),"Actual target not at head");
  int before=scheduler.queueCount(1);
  RobotProcessorWorkScheduler.executionFinished(a,selected,true);
  check(scheduler.queueCount(1)==before-1,"Successful break not removed");
  panel.max=1;
  scheduler.onQueueLimitChanged();
  check(scheduler.queueCount(1)<=1 && scheduler.queueCount(2)<=1,"Reducing GUI cap failed");
  scheduler.onRemoved();
  check(scheduler.queueCount(1)==0 && scheduler.queueCount(2)==0,"Unloaded panel kept assignments");
  System.out.println("PASS: miner queue dispatch, exclusive assignment, AI completion, resize, unload");
 }
}
'''


class StageThreeQueueTests(unittest.TestCase):
    def test_java_queue_and_claims(self):
        with tempfile.TemporaryDirectory(prefix='bc-stage3-core-') as temp:
            test = Path(temp) / 'VerifyExecutionQueue.java'
            test.write_text(QUEUE_FIXTURE)
            c = subprocess.run(['javac', '--release', '17', '-d', temp,
                                str(QUEUE), str(LEASES), str(test)],
                               capture_output=True, text=True)
            self.assertEqual(c.returncode, 0, c.stderr)
            r = subprocess.run(['java', '-cp', temp, 'VerifyExecutionQueue'],
                               capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stderr)

    def test_miner_queue_in_legacy_and_modern_simulated_worlds(self):
        for version in ('1.19.2', '26.3'):
            with self.subTest(version=version), tempfile.TemporaryDirectory(prefix='bc-stage3-world-') as temp:
                stubs = dict(BASE_STUBS)
                stubs['net/minecraft/world/level/Level.java'] = stubs['net/minecraft/world/level/Level.java'].replace(
                    ' public long time; public boolean sectionAir;',
                    ' public java.util.Set<BlockPos> extra = new java.util.HashSet<>();\n public long time; public boolean sectionAir;').replace(
                    'return new BlockState(!pos.equals(target));',
                    'return new BlockState(!pos.equals(target) && !extra.contains(pos));')
                key = 'buildcraft/robotics/boards/BoardRobotGenericSearchBlock.java'
                stubs[key] = stubs[key].replace('private final NBT nbt = new NBT("lumberjack");',
                    'private NBT nbt = new NBT("lumberjack");\n public void setBoardId(String id){nbt=new NBT(id);}')
                key = 'buildcraft/robotics/internal/legacy/robots/EntityRobotBase.java'
                stubs[key] = stubs[key].replace(' public long getRobotId(){return id;}',
                    ' public void setBoardId(String id){board.setBoardId(id);}\n public long getRobotId(){return id;}')
                key = 'buildcraft/robotics/tile/TileRobotProcessor.java'
                stubs[key] = stubs[key].replace(' public int getMaxExecutionQueue(){return 5;}',
                    ' public int max=5; public int getMaxExecutionQueue(){return max;}')
                testfiles=[]
                for rel, source in stubs.items():
                    out = Path(temp) / rel
                    out.parent.mkdir(parents=True, exist_ok=True)
                    out.write_text(source)
                    testfiles.append(str(out))
                for rel in SOURCES:
                    out = Path(temp) / rel.removeprefix('source-shared/src/main/java/')
                    out.parent.mkdir(parents=True, exist_ok=True)
                    out.write_text(preprocess_text((ROOT/rel).read_text(),minecraft=version,
                        family='26.X' if version=='26.3' else 'old',
                        platform='neoforge' if version=='26.3' else 'forge',source=rel))
                    testfiles.append(str(out))
                demo = Path(temp) / 'fixture/Stage3MiningFixture.java'
                demo.parent.mkdir(parents=True,exist_ok=True)
                demo.write_text(SIMULATED_WORLD)
                testfiles.append(str(demo))
                classes=Path(temp)/'classes';classes.mkdir()
                compiled=subprocess.run(['javac','--release','17','-d',str(classes),*testfiles],
                                        capture_output=True,text=True)
                self.assertEqual(compiled.returncode,0,compiled.stderr)
                run=subprocess.run(['java','-cp',str(classes),'fixture.Stage3MiningFixture'],
                                   capture_output=True,text=True)
                self.assertEqual(run.returncode,0,run.stdout+run.stderr)

    def test_wire_and_ai_lifecycle_use_real_queue_counts_and_break_results(self):
        menu=(ROOT/'source-shared/src/main/java/buildcraft/robotics/container/ContainerRobotProcessor.java').read_text()
        gui=(ROOT/'version-src/1.19.2-forge/src/main/java/buildcraft/robotics/gui/GuiRobotProcessor.java').read_text()
        board=(ROOT/'source-shared/src/main/java/buildcraft/robotics/boards/BoardRobotGenericBreakBlock.java').read_text()
        route=(ROOT/'source-shared/src/main/java/buildcraft/robotics/ai/AIRobotSearchAndGotoBlock.java').read_text()
        miner=(ROOT/'source-shared/src/main/java/buildcraft/robotics/ai/AIRobotSearchNearestBlock.java').read_text()
        self.assertIn('processor.getExecutionQueueSize(robotId)',menu)
        self.assertIn('buf.writeByte(robot.queued())',menu)
        self.assertIn('buffer.readUnsignedByte()',menu)
        # Station snapshot wire layout must stay unchanged. An accidental extra
        # readByte here corrupts the next station and fails Java compilation.
        station_segment = menu.split('} else if (id == ID_STATIONS && side == BCNetworkSide.CLIENT) {', 1)[1].split('} else if (id == ID_QUEUE_STATE', 1)[0]
        self.assertEqual(station_segment.count('buffer.readUnsignedByte()'), 1)
        self.assertNotIn('"mining execution queue"', station_segment)
        self.assertIn('selected.queued()',gui)
        self.assertIn('RobotProcessorWorkScheduler.executionFinished(robot, blockFound().toBlockPos(), ai.success())',board)
        self.assertIn('queuedMiningTargets = RobotProcessorWorkScheduler.queuedMiningTargets(robot)',route)
        self.assertIn('RobotProcessorWorkScheduler.queuedMiningSearchFailed(robot,',route)
        self.assertIn('search.processorQueueTargets()',route)
        self.assertIn('(queuedTargets == null || queuedTargets.contains(next))',miner)


if __name__=='__main__': unittest.main()
