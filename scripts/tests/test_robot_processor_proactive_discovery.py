"""Persistent panel discovery with sleeping miners and a deep quarry target.

JVM fixture compiles the actual scheduler and index. Artificial Minecraft
objects model a loaded 3-chunk corridor, ore palettes and a sleeping miner.
"""
from pathlib import Path
import subprocess
import tempfile
import unittest

from scripts.tests.test_robot_processor_scheduler_runtime import ROOT, SOURCES, STUBS as ORIGINAL
from scripts.source_preprocessor import preprocess_text

SCENARIO = r'''
package fixture;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import buildcraft.robotics.internal.legacy.robots.*;
import buildcraft.robotics.scheduler.*;
import buildcraft.robotics.tile.TileRobotProcessor;
import java.util.*;
public class ProactiveDiscoveryFixture {
 static void check(boolean truth,String why){if(!truth)throw new AssertionError(why);}
 public static void main(String[] args) {
  ServerLevel level=new ServerLevel();
  BlockPos deep=new BlockPos(33,-16,3);
  level.ores.add(deep);
  var a=new EntityRobotBase(level,10,new BlockPos(2,64,2));
  var b=new EntityRobotBase(level,11,new BlockPos(4,64,2));
  a.asleep=true;b.asleep=true;
  a.board.toolReady=false;b.board.toolReady=false;
  RobotManager.registryProvider = world -> new IRobotRegistry(){
   public EntityRobotBase getLoadedRobot(long id){return id==10?a:id==11?b:null;}
   public boolean isTaken(ResourceIdBlock res){return false;}
   public long robotIdTaking(ResourceIdBlock res){return 0;}
  };
  TileRobotProcessor panel=new TileRobotProcessor(level,new BlockPos(0,64,0));
  panel.linked.add(10L);panel.linked.add(11L);
  var scheduler=new RobotProcessorWorkScheduler(panel);
  // Neither robot produces normal ready() demand, yet the panel must scan.
  for(int t=0;t<35;t++) {level.time++;scheduler.tick(level);}
  check(scheduler.cachedCandidates()>=1,"Docked miners suppressed background discovery");
  check(level.blockReads<8_000,"Stone-only section palette was not pruned");
  check(!RobotProcessorWorkScheduler.hasAvailableWorkForSleepingRobot(a),
    "Miner woke up without a suitable tool");
  a.board.toolReady=true;b.board.toolReady=true;
  check(RobotProcessorWorkScheduler.hasAvailableWorkForSleepingRobot(a),
    "Sleeping miner cannot wake for discovered deep ore");
  a.asleep=false;b.asleep=false;
  for(int t=0;t<8;t++){level.time++;scheduler.tick(level);}
  var mineA=RobotProcessorWorkScheduler.queuedMiningTargets(a);
  var mineB=RobotProcessorWorkScheduler.queuedMiningTargets(b);
  check(mineA.contains(deep)||mineB.contains(deep),
    "Previously discovered deep quarry ore never entered an execution queue");
  // Cached work does not expire during a long idle session.
  level.time += 10_000;
  check(scheduler.cachedCandidates()>=1,"The discovery catalog expired while idle");
  // An external world mutation invalidates it, and the next ore is discovered
  // while mining without restarting the world or the panel.
  level.ores.remove(deep);
  RobotProcessorWorkScheduler.worldBlockChanged(level, deep);
  BlockPos replacement=new BlockPos(34,-16,3);
  level.ores.add(replacement);
  RobotProcessorWorkScheduler.worldBlockChanged(level,replacement);
  level.time++;scheduler.tick(level);
  check(scheduler.cachedCandidates()>=1,"New quarry ore was not observed after mutation");
  scheduler.onRemoved();
  System.out.println("PASS: sleeping miners, deep cave, tool independence, durable catalog, hot refresh");
 }
}
'''


def fixture_stubs():
    stubs = dict(ORIGINAL)
    stubs['net/minecraft/world/level/block/state/BlockState.java'] = '''package net.minecraft.world.level.block.state;
public record BlockState(boolean air, boolean ore) {public boolean isAir(){return air;}}
'''
    stubs['net/minecraft/world/level/Level.java'] = '''package net.minecraft.world.level;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
public class Level {
 public long time;public boolean sectionAir;
 public int blockReads;
 public final java.util.Set<BlockPos> ores=new java.util.HashSet<>();
 public long getGameTime(){return time;}
 public int getMinBuildHeight(){return -32;}
 public int getMaxBuildHeight(){return 96;}
 public boolean hasChunkAt(BlockPos pos){return (pos.getX()>>4)>=0 && (pos.getX()>>4)<=2 && (pos.getZ()>>4)==0;}
 public boolean oreInSection(int cx,int cz,int sy){
  for(var ore:ores)if((ore.getX()>>4)==cx && (ore.getZ()>>4)==cz && (ore.getY()>>4)==sy)return true;
  return false;
 }
 public BlockState getBlockState(BlockPos pos){
  if(!hasChunkAt(pos))throw new AssertionError("Force-loaded chunk: "+pos);
  blockReads++;return new BlockState(false,ores.contains(pos));
 }
}'''
    stubs['buildcraft/lib/misc/ChunkUtil.java'] = '''package buildcraft.lib.misc;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunk;
public class ChunkUtil {
 public static LevelChunk getChunk(Level level,int x,int z,boolean force){
  if(force)throw new AssertionError("Chunk generation requested");
  return level.hasChunkAt(new BlockPos(x<<4,64,z<<4))?new LevelChunk(level,x,z):null;
 }
}'''
    stubs['net/minecraft/world/level/chunk/LevelChunk.java'] = '''package net.minecraft.world.level.chunk;
import net.minecraft.world.level.Level;
public class LevelChunk {
 private final Level level;private final int cx,cz;
 public LevelChunk(Level level,int cx,int cz){this.level=level;this.cx=cx;this.cz=cz;}
 public LevelChunkSection[] getSections(){
  LevelChunkSection[] sections=new LevelChunkSection[8];
  for(int i=0;i<8;i++)sections[i]=new LevelChunkSection(level,cx,cz,i-2);
  return sections;
 }
}'''
    stubs['net/minecraft/world/level/chunk/LevelChunkSection.java'] = '''package net.minecraft.world.level.chunk;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
public class LevelChunkSection {
 private final Level level;private final int cx,cz,sy;
 public LevelChunkSection(Level level,int cx,int cz,int sy){this.level=level;this.cx=cx;this.cz=cz;this.sy=sy;}
 public boolean hasOnlyAir(){return false;}
 public States getStates(){return new States(level.oreInSection(cx,cz,sy));}
 public static class States {
  private final boolean ore;
  public States(boolean ore){this.ore=ore;}
  public boolean maybeHas(java.util.function.Predicate<BlockState> p) {
   return p.test(new BlockState(false,false)) || ore && p.test(new BlockState(false,true));
  }
 }
}'''
    stubs['buildcraft/robotics/boards/BoardRobotGenericSearchBlock.java'] = '''package buildcraft.robotics.boards;
import buildcraft.robotics.scheduler.ProcessorCandidateBoard;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
public class BoardRobotGenericSearchBlock extends buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobot
 implements ProcessorCandidateBoard {
 public boolean toolReady;
 public String processorWorkCategory(){return "buildcraft:miner";}
 public boolean processorSupportsQueue(){return true;}
 public boolean processorAcceptsHints(){return false;}
 public boolean processorScanCandidate(Level l,BlockPos p){return toolReady && l.getBlockState(p).ore();}
 public boolean processorDiscoveryCandidate(Level l,BlockPos p){return l.getBlockState(p).ore();}
 public boolean processorDiscoveryCandidate(Level l,BlockPos p,BlockState state){return state.ore();}
 public boolean processorPaletteMightContain(BlockState state){return state.ore();}
 public boolean processorTargetEligible(Level l,BlockPos p){return toolReady && l.getBlockState(p).ore();}
 public boolean processorPlanningEligible(Level l,BlockPos p){return processorTargetEligible(l,p);}
 public java.util.List<BlockPos> processorJobMembers(Level l,BlockPos p,int max){return java.util.List.of(p);}
}'''
    stubs['buildcraft/robotics/internal/legacy/robots/EntityRobotBase.java'] = '''package buildcraft.robotics.internal.legacy.robots;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import buildcraft.robotics.boards.BoardRobotGenericSearchBlock;
import buildcraft.lib.internal.area.IZone;
public class EntityRobotBase {
 public static final int SAFETY_ENERGY=20_000;
 public final Level level;public Level level(){return level;}
 private final long id;private final BlockPos location;
 public final BoardRobotGenericSearchBlock board=new BoardRobotGenericSearchBlock();
 private final AIRobot sleeper=new AIRobot(){public boolean isWithinWorkChain(AIRobot other){return other==board;}};
 public boolean asleep=true;
 public EntityRobotBase(Level level,long id,BlockPos pos){this.level=level;this.id=id;location=pos;}
 public boolean isRemoved(){return false;}
 public int getEnergy(){return 50_000;}
 public buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobot getBoard(){return board;}
 public AIRobot getActiveAI(){return asleep?sleeper:board;}
 public long getRobotId(){return id;}
 public BlockPos blockPosition(){return location;}
 public int getBlockX(){return location.getX();}
 public int getBlockY(){return location.getY();}
 public int getBlockZ(){return location.getZ();}
 public IZone getZoneToWork(){return null;}
 public IRobotRegistry getRegistry(){return RobotManager.registryProvider.getRegistry(level);}
}'''
    return stubs

class ProactiveDiscoveryTests(unittest.TestCase):
    def test_deep_quarry_discovery_while_miner_sleeps(self):
        for version, family, loader in [('1.19.2', 'old', 'forge'),
                                        ('1.21.11', '1.21.X', 'neoforge'),
                                        ('26.3', '26.X', 'fabric')]:
            with self.subTest(version=version, loader=loader), tempfile.TemporaryDirectory(prefix='bc-proactive-') as d:
                folder=Path(d)
                all_files=[]
                for rel,content in fixture_stubs().items():
                    dest=folder/rel;dest.parent.mkdir(parents=True,exist_ok=True)
                    dest.write_text(content);all_files.append(str(dest))
                for rel in SOURCES:
                    dest=folder/rel.removeprefix('source-shared/src/main/java/')
                    dest.parent.mkdir(parents=True,exist_ok=True)
                    dest.write_text(preprocess_text((ROOT/rel).read_text(),minecraft=version,
                               family=family,platform=loader,source=rel))
                    all_files.append(str(dest))
                fixture=folder/'fixture/ProactiveDiscoveryFixture.java'
                fixture.parent.mkdir(parents=True,exist_ok=True);fixture.write_text(SCENARIO)
                all_files.append(str(fixture))
                classes=folder/'classes';classes.mkdir()
                result=subprocess.run(['javac','--release','17','-d',str(classes),*all_files],
                        capture_output=True,text=True,timeout=45)
                self.assertEqual(result.returncode,0,result.stderr)
                result=subprocess.run(['java','-cp',str(classes),'fixture.ProactiveDiscoveryFixture'],
                        capture_output=True,text=True,timeout=20)
                self.assertEqual(result.returncode,0,result.stdout+result.stderr)

    def test_long_lived_catalog_keeps_more_than_old_256_cap(self):
        text = (ROOT/'source-shared/src/main/java/buildcraft/robotics/scheduler/RobotWorkIndex.java').read_text()
        self.assertIn('MAX_CANDIDATES_PER_CATEGORY = 65_536',text)
        self.assertNotIn('MAX_CANDIDATE_AGE_TICKS',text)
        self.assertIn('PriorityQueue<Long>',text)

    def test_sleeping_ai_checks_for_new_work_without_polling_each_tick(self):
        text=(ROOT/'source-shared/src/main/java/buildcraft/robotics/ai/AIRobotSleep.java').read_text()
        self.assertIn('sleptTime % 20 == 0',text)
        self.assertIn('hasAvailableWorkForSleepingRobot(robot)',text)

if __name__=='__main__':unittest.main()
