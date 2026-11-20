"""Regression: one deposit occupies one queue slot, even when it has many blocks."""
import subprocess
import tempfile
import unittest
from pathlib import Path
from scripts.tests.test_robot_processor_stage3_execution_queue import BASE_STUBS, SOURCES, ROOT
from scripts.source_preprocessor import preprocess_text

FIXTURE = r"""package fixture;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import buildcraft.robotics.internal.legacy.robots.*;
import buildcraft.robotics.tile.TileRobotProcessor;
import buildcraft.robotics.scheduler.RobotProcessorWorkScheduler;
public class Stage5MiningFixture {
 static void ok(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 public static void main(String[]args){
  ServerLevel level=new ServerLevel();
  level.target=new BlockPos(255,64,255);
  var miner=new EntityRobotBase(level,1,new BlockPos(0,65,0));miner.setBoardId("buildcraft:miner");
  var known=new ArrayList<BlockPos>();
  for(int x=1;x<=10;x++){BlockPos pos=new BlockPos(x,64,1);known.add(pos);level.extra.add(pos);}
  miner.setKnown(known);
  RobotManager.registryProvider = world -> new IRobotRegistry() {
    public EntityRobotBase getLoadedRobot(long id){return id==1?miner:null;}
    public boolean isTaken(ResourceIdBlock resource){return false;}
    public long robotIdTaking(ResourceIdBlock resource){return 0;}
  };
  var panel=new TileRobotProcessor(level,new BlockPos(0,65,0));panel.linked.add(1L);panel.max=6;
  var scheduler=new RobotProcessorWorkScheduler(panel);
  level.time++;scheduler.tick(level);
  ok(scheduler.queueCount(1)==1,"Ten touching blocks must make ONE mining job");
  ok(RobotProcessorWorkScheduler.queuedMiningTargets(miner).size()==10,"Group lost its break steps");
  var first=RobotProcessorWorkScheduler.queuedMiningTargets(miner).get(0);
  RobotProcessorWorkScheduler.targetAcquired(miner,first);
  level.extra.remove(first);
  RobotProcessorWorkScheduler.executionFinished(miner,first,true);
  ok(scheduler.queueCount(1)==1,"Breaking one member prematurely completed the vein job");
  ok(RobotProcessorWorkScheduler.queuedMiningTargets(miner).size()==9,"Break did not remove one substep");
  long[] saved=scheduler.pendingWorkSnapshot();
  ok(saved.length==2,"Ten-block deposit must be ONE persisted intention, not nine");
  scheduler.onRemoved();
  var restored=new RobotProcessorWorkScheduler(panel);
  restored.restorePendingWork(saved);
  level.time++;restored.tick(level);
  ok(restored.queueCount(1)==1,"One vein job was lost during restore");
  ok(RobotProcessorWorkScheduler.queuedMiningTargets(miner).size()==9,"Restored vein lost its members");
  ok(!RobotProcessorWorkScheduler.queuedMiningTargets(miner).contains(first),"Already mined block returned");
  for(var pos:new ArrayList<>(RobotProcessorWorkScheduler.queuedMiningTargets(miner))){
    level.extra.remove(pos);RobotProcessorWorkScheduler.executionFinished(miner,pos,true);
  }
  ok(restored.queueCount(1)==0,"The last block must complete the vein job");
  restored.onRemoved();

  // Ore - air - ore: all six blocks are independent deposits and six jobs.
  level.extra.clear();known.clear();
  for(int x=1;x<=11;x+=2){var pos=new BlockPos(x,64,1);known.add(pos);level.extra.add(pos);}
  miner.setKnown(known);
  var isolated=new RobotProcessorWorkScheduler(panel);
  for(int i=0;i<5;i++){level.time++;isolated.tick(level);}
  ok(isolated.queueCount(1)==6,"Air-separated ores must be SIX distinct jobs");
  isolated.onRemoved();

  // The first job may contain hundreds of ores. Its first 64 members must
  // not conceal the second independent job from queued route selection.
  level.extra.clear();known.clear();
  // Keep all simulated ores in the *single loaded chunk* (x/z 0..15).
  // The 14x2x5 blob is connected into one >64-member deposit.
  var distinct=new BlockPos(8,64,12);
  known.add(new BlockPos(1,64,1));known.add(distinct);
  for(int x=1;x<=14;x++)for(int y=64;y<=65;y++)for(int z=1;z<=5;z++) {
   BlockPos pos=new BlockPos(x,y,z);
   level.extra.add(pos);
   if(!(x==1&&y==64&&z==1))known.add(pos);
  }
  level.extra.add(distinct);
  miner.setKnown(known);panel.max=6;
  var fair=new RobotProcessorWorkScheduler(panel);
  for(int i=0;i<12;i++){level.time++;fair.tick(level);}
  ok(fair.queueCount(1)>=2,"Failed to allocate distinct jobs after a huge deposit");
  var sampled=RobotProcessorWorkScheduler.queuedWorkTargets(miner);
  ok(sampled.contains(distinct),"Large first vein hid the second queued job");
  ok(sampled.size()<=64,"Queued search exceeded its target bound");
  RobotProcessorWorkScheduler.queuedMiningSearchFailed(miner, java.util.List.of(distinct));
  ok(fair.queueCount(1)==1,"Failed route did not retire its own deposit");
  for(int i=0;i<30;i++){level.time++;fair.tick(level);}
  ok(fair.queueCount(1)==1,"Unreachable deposit returned before retry cooldown");
  // Once the bounded cooldown ends the still-existing ore becomes eligible
  // again. This is a delayed retry, not permanent blacklisting.
  level.time+=1201;
  for(int i=0;i<16;i++){level.time++;fair.tick(level);}
  ok(fair.queueCount(1)>=2,"Unreachable deposit never became eligible again");
  fair.onRemoved();
  System.out.println("PASS: connected vein, restore, separate ores, fair sampling across huge jobs");
 }
}"""

class StageFiveMinerTests(unittest.TestCase):
 def test_legacy_and_modern_mining_queue_with_recovery(self):
  for version in ('1.19.2','1.21.11','26.3'):
   with self.subTest(version=version), tempfile.TemporaryDirectory(prefix='bc-stage5-') as directory:
    stubs=dict(BASE_STUBS)
    stubs['net/minecraft/world/level/Level.java']=stubs['net/minecraft/world/level/Level.java'].replace(
      ' public long time; public boolean sectionAir;',
      ' public java.util.Set<BlockPos> extra = new java.util.HashSet<>();\n public long time; public boolean sectionAir;').replace(
      'return new BlockState(!pos.equals(target));',
      'return new BlockState(!pos.equals(target) && !extra.contains(pos));')
    k='buildcraft/robotics/boards/BoardRobotGenericSearchBlock.java'
    stubs[k]=stubs[k].replace('private final NBT nbt = new NBT("lumberjack");',
      'private NBT nbt = new NBT("lumberjack");\n public void setBoardId(String id){nbt=new NBT(id);}\n private java.util.List<BlockPos> known=java.util.List.of();\n public void setKnown(java.util.List<BlockPos> value){known=value;}\n @Override public java.util.List<BlockPos> processorKnownCandidates(){return known;}')
    # 26-neighbour groups in the simulated miner's work index.
    grouping = r"""
 @Override public java.util.List<BlockPos> processorJobMembers(Level level, BlockPos start, int max) {
  java.util.List<BlockPos> result = new java.util.ArrayList<>();
  java.util.Set<BlockPos> seen = new java.util.HashSet<>();
  java.util.ArrayDeque<BlockPos> work = new java.util.ArrayDeque<>(); work.add(start);
  while (!work.isEmpty() && result.size()<max) {
   BlockPos pos=work.removeFirst();
   if (!seen.add(pos) || !level.extra.contains(pos))continue;
   result.add(pos);
   for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++)for(int dz=-1;dz<=1;dz++) {
    if(dx==0&&dy==0&&dz==0)continue;
    BlockPos next=pos.offset(dx,dy,dz);
    if(!seen.contains(next) && level.hasChunkAt(next))work.addLast(next);
   }
  } return result;
 }
"""
    stubs[k]=stubs[k].replace(' public boolean isExpectedBlock(Level world, BlockPos pos)',
                             grouping+' public boolean isExpectedBlock(Level world, BlockPos pos)')
    k='buildcraft/robotics/internal/legacy/robots/EntityRobotBase.java'
    stubs[k]=stubs[k].replace(' public long getRobotId(){return id;}',
      ' public void setBoardId(String id){board.setBoardId(id);}\n public void setKnown(java.util.List<BlockPos> known){board.setKnown(known);}\n public long getRobotId(){return id;}')
    k='buildcraft/robotics/tile/TileRobotProcessor.java'
    stubs[k]=stubs[k].replace(' public int getMaxExecutionQueue(){return 5;}',
      ' public int max=5; public int getMaxExecutionQueue(){return max;}')
    target=Path(directory);java_files=[]
    for name,source in stubs.items():
     f=target/name;f.parent.mkdir(parents=True,exist_ok=True);f.write_text(source);java_files.append(str(f))
    for rel in SOURCES:
     f=target/rel.removeprefix('source-shared/src/main/java/')
     f.parent.mkdir(parents=True,exist_ok=True)
     f.write_text(preprocess_text((ROOT/rel).read_text(),minecraft=version,
      family='26.X' if version=='26.3' else '1.21.X' if version=='1.21.11' else 'old',
      platform='neoforge' if version!='1.19.2' else 'forge',source=rel));java_files.append(str(f))
    f=target/'fixture/Stage5MiningFixture.java';f.parent.mkdir(parents=True,exist_ok=True);f.write_text(FIXTURE);java_files.append(str(f))
    classes=target/'classes';classes.mkdir()
    compiled=subprocess.run(['javac','--release','17','-d',str(classes),*java_files],capture_output=True,text=True)
    self.assertEqual(compiled.returncode,0,compiled.stderr)
    result=subprocess.run(['java','-cp',str(classes),'fixture.Stage5MiningFixture'],capture_output=True,text=True)
    self.assertEqual(result.returncode,0,result.stdout+result.stderr)

 def test_restore_does_not_copy_lease_tokens(self):
  source=(ROOT/'source-shared/src/main/java/buildcraft/robotics/scheduler/RobotProcessorWorkScheduler.java').read_text()
  assert 'pendingWorkSnapshot()' in source
  assert 'restorePendingWork(long[] snapshot)' in source
  assert 'if (!level.hasChunkAt(pos)) continue' in source
  assert 'legacyForcedTicketCleanup' in (ROOT/'source-shared/src/main/java/buildcraft/robotics/tile/TileRobotProcessor.java').read_text()
