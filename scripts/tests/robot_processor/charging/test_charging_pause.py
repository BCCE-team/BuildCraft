"""Real Java scheduler regression: recharging miners keep intentions but not leases.

Run on 1.19.2 and 26.3 preprocessed source with a deliberately small world.
"""
import subprocess
import tempfile
import unittest
from pathlib import Path
from scripts.source_preprocessor import preprocess_text
from scripts.tests.robot_processor.execution.test_mining_queue import BASE_STUBS, SOURCES, ROOT

FIXTURE = r"""package fixture;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import buildcraft.robotics.internal.legacy.robots.*;
import buildcraft.robotics.tile.TileRobotProcessor;
import buildcraft.robotics.scheduler.RobotProcessorWorkScheduler;
public class ChargingPauseFixture {
 static void ok(boolean condition,String why){if(!condition)throw new AssertionError(why);}
 public static void main(String[]args){
  ServerLevel level = new ServerLevel();
  level.target = new BlockPos(300,64,300);
  var a = new BlockPos(1,64,1);
  var b = new BlockPos(3,64,1);
  var c = new BlockPos(5,64,1);
  level.extra.addAll(List.of(a,b,c));
  var miner = new EntityRobotBase(level,7,new BlockPos(0,65,0));
  miner.setBoardId("buildcraft:miner");
  miner.setKnown(List.of(a,b,c));
  RobotManager.registryProvider=world -> new IRobotRegistry() {
    public EntityRobotBase getLoadedRobot(long id){return id==7 ? miner : null;}
    public boolean isTaken(ResourceIdBlock resource){return false;}
    public long robotIdTaking(ResourceIdBlock resource){return 0;}
  };
  var panel=new TileRobotProcessor(level,new BlockPos(0,65,0));
  panel.linked.add(7L);panel.max=6;
  var scheduler=new RobotProcessorWorkScheduler(panel);
  for(int i=0;i<5;i++){level.time++;scheduler.tick(level);}
  ok(scheduler.queueCount(7)==3,"Did not prepare 3 separate deposits");
  ok(RobotProcessorWorkScheduler.queuedMiningTargets(miner).size()==3,"Active miner lost its queued targets");
  miner.energy=100;
  level.time++;scheduler.tick(level);
  ok(scheduler.queueCount(7)==3,"Recharge must pause, not discard queue telemetry");
  ok(RobotProcessorWorkScheduler.queuedMiningTargets(miner).isEmpty(),"Paused miner still has executable claims");
  ok(scheduler.pendingWorkSnapshot().length==6,"Paused intentions not persisted");
  // Lower queue limit during recharge. Keep the head, not the newest task.
  panel.max=1;scheduler.onQueueLimitChanged();
  ok(scheduler.queueCount(7)==1,"Reducing capacity while paused ignored");
  ok(scheduler.pendingWorkSnapshot().length==2,"Paused intentions not trimmed");
  miner.energy=50000;
  level.time++;scheduler.tick(level);
  ok(scheduler.queueCount(7)==1,"Recharged miner did not restore a job");
  ok(RobotProcessorWorkScheduler.queuedMiningTargets(miner).size()==1,"Restored target not executable");
  // A changed block must NOT be mined merely because it was saved.
  miner.energy=100;level.time++;scheduler.tick(level);
  var saved=scheduler.pendingWorkSnapshot();
  ok(saved.length==2,"Pending queue went missing during second recharge");
  level.extra.remove(BlockPos.of(saved[1]));
  miner.energy=50000;
  level.time++;scheduler.tick(level);
  ok(!RobotProcessorWorkScheduler.queuedMiningTargets(miner).contains(BlockPos.of(saved[1])),
    "Stale deferred target survived validation");
  scheduler.onRemoved();
  System.out.println("PASS: pause without execution, persistent intentions, resume, trim and stale-target validation");
 }
}"""

class RobotChargingPauseTests(unittest.TestCase):
    def test_miners_suspend_and_resume_in_legacy_and_modern_worlds(self):
        for version in ("1.19.2", "1.21.11", "26.3"):
            with self.subTest(version=version), tempfile.TemporaryDirectory(prefix="bc-charging-") as tmp:
                stubs = dict(BASE_STUBS)
                stubs['net/minecraft/world/level/Level.java'] = stubs['net/minecraft/world/level/Level.java'].replace(
                    ' public long time; public boolean sectionAir;',
                    ' public java.util.Set<BlockPos> extra = new java.util.HashSet<>();\n public long time; public boolean sectionAir;').replace(
                    'return new BlockState(!pos.equals(target));',
                    'return new BlockState(!pos.equals(target) && !extra.contains(pos));')
                k='buildcraft/robotics/boards/BoardRobotGenericSearchBlock.java'
                stubs[k] = stubs[k].replace('private final NBT nbt = new NBT("lumberjack");',
                    'private NBT nbt = new NBT("lumberjack");\n public void setBoardId(String id){nbt=new NBT(id);}\n'
                    ' private java.util.List<BlockPos> known=java.util.List.of();\n'
                    ' public void setKnown(java.util.List<BlockPos> list){known=list;}\n'
                    ' @Override public java.util.List<BlockPos> processorKnownCandidates(){return known;}')
                k='buildcraft/robotics/internal/legacy/robots/EntityRobotBase.java'
                stubs[k]=stubs[k].replace(' public int getEnergy(){return 50000;}',
                    ' public int energy=50000;public int getEnergy(){return energy;}').replace(
                    ' public long getRobotId(){return id;}',
                    ' public void setBoardId(String id){board.setBoardId(id);}\n'
                    ' public void setKnown(java.util.List<BlockPos> list){board.setKnown(list);}\n'
                    ' public long getRobotId(){return id;}')
                k='buildcraft/robotics/tile/TileRobotProcessor.java'
                stubs[k]=stubs[k].replace(' public int getMaxExecutionQueue(){return 5;}',
                    ' public int max=5;public int getMaxExecutionQueue(){return max;}')
                dest=Path(tmp);files=[]
                for path,source in stubs.items():
                    f=dest/path;f.parent.mkdir(parents=True,exist_ok=True)
                    f.write_text(source);files.append(str(f))
                for path in SOURCES:
                    f=dest/path.removeprefix('source-shared/src/main/java/')
                    f.parent.mkdir(parents=True,exist_ok=True)
                    f.write_text(preprocess_text((ROOT/path).read_text(),minecraft=version,
                        family='old' if version=='1.19.2' else '1.21.X' if version=='1.21.11' else '26.X',
                        platform='forge' if version=='1.19.2' else 'neoforge',source=path))
                    files.append(str(f))
                f=dest/'fixture/ChargingPauseFixture.java';f.parent.mkdir(parents=True,exist_ok=True)
                f.write_text(FIXTURE);files.append(str(f))
                classes=dest/'classes';classes.mkdir()
                compiled=subprocess.run(['javac','--release','17','-d',str(classes),*files],capture_output=True,text=True)
                self.assertEqual(compiled.returncode,0,compiled.stdout+compiled.stderr)
                result=subprocess.run(['java','-cp',str(classes),'fixture.ChargingPauseFixture'],capture_output=True,text=True)
                self.assertEqual(result.returncode,0,result.stdout+result.stderr)

if __name__=='__main__': unittest.main()
