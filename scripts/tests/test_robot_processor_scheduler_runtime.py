"""Compile the real stage-1/2 scheduler against a tiny Minecraft simulation.

Covers 1.19.2 and 26.3 source signatures, blocked chunk reads, fairness,
nearest target, duplicated assignments and the absence of demand.
"""
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from source_preprocessor import preprocess_text

SOURCES = [
    'source-shared/src/main/java/buildcraft/robotics/scheduler/RobotProcessorWorkScheduler.java',
    'source-shared/src/main/java/buildcraft/robotics/scheduler/RobotWorkIndex.java',
    'source-shared/src/main/java/buildcraft/robotics/scheduler/RobotLocalDiscoveryProbe.java',
    'source-shared/src/main/java/buildcraft/robotics/scheduler/RobotWorkLeases.java',
    'source-shared/src/main/java/buildcraft/robotics/scheduler/RobotExecutionQueue.java',
    'source-shared/src/main/java/buildcraft/robotics/scheduler/ProcessorCandidateBoard.java',
]

STUBS = {
'buildcraft/robotics/RobotTrace.java': '''package buildcraft.robotics;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
public final class RobotTrace {
 public static boolean enabled(){return false;}
 public static void event(EntityRobotBase robot,String type,String format,Object... args){}
}''',
'net/minecraft/core/BlockPos.java': '''package net.minecraft.core;
public record BlockPos(int x,int y,int z) {
 public int getX(){return x;} public int getY(){return y;} public int getZ(){return z;}
 public BlockPos immutable(){return this;}
 public BlockPos above(){return new BlockPos(x,y+1,z);}
 public BlockPos below(){return new BlockPos(x,y-1,z);}
 public BlockPos offset(int dx,int dy,int dz){return new BlockPos(x+dx,y+dy,z+dz);}
 public long asLong(){return ((long)x&0x3ffffffL)<<38|((long)z&0x3ffffffL)<<12|((long)y&0xfffL);}
 public static BlockPos of(long v){return new BlockPos((int)(v>>38),(int)(v<<52>>52),(int)(v<<26>>38));}
}''',
'net/minecraft/core/Direction.java': '''package net.minecraft.core; public enum Direction {NORTH,SOUTH,EAST,WEST,UP,DOWN;}''',
'buildcraft/robotics/internal/legacy/robots/DockingStation.java': '''package buildcraft.robotics.internal.legacy.robots; public class DockingStation {public int x(){return 0;}public int y(){return 0;}public int z(){return 0;} public net.minecraft.core.Direction side(){return net.minecraft.core.Direction.NORTH;}}''',
'net/minecraft/world/level/ChunkPos.java': '''package net.minecraft.world.level;
public class ChunkPos {public final int x,z;public ChunkPos(int x,int z){this.x=x;this.z=z;}}''',
'net/minecraft/world/level/block/state/BlockState.java': '''package net.minecraft.world.level.block.state;
public record BlockState(boolean air) {public boolean isAir(){return air;}}''',
'net/minecraft/world/level/Level.java': '''package net.minecraft.world.level;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
public class Level {
 public long time; public boolean sectionAir;
 public int blockReads; public BlockPos target=new BlockPos(1,64,1);
 public long getGameTime(){return time;}
 public int getMinBuildHeight(){return 64;}
 public int getMaxBuildHeight(){return 80;}
 public boolean hasChunkAt(BlockPos pos){return (pos.getX()>>4)==0 && (pos.getZ()>>4)==0;}
 public BlockState getBlockState(BlockPos pos) {
   if(!hasChunkAt(pos))throw new AssertionError("Attempt to load an ungenerated chunk: "+pos);
   blockReads++;
   return new BlockState(!pos.equals(target));
 }
}''',
'buildcraft/lib/misc/ChunkUtil.java': '''package buildcraft.lib.misc;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunk;
public class ChunkUtil {
 public static LevelChunk getChunk(Level level,int x,int z,boolean force){
  if(force)throw new AssertionError("Force loading in planner!");
  return level.hasChunkAt(new BlockPos(x<<4,64,z<<4)) ? new LevelChunk(level):null;
 }
}''',
'net/minecraft/world/level/chunk/LevelChunk.java': '''package net.minecraft.world.level.chunk;
import net.minecraft.world.level.Level;
public class LevelChunk {
 private final Level level;
 public LevelChunk(Level level){this.level=level;}
 public LevelChunkSection[] getSections(){return new LevelChunkSection[]{new LevelChunkSection(level)};}
}''',
'net/minecraft/world/level/chunk/LevelChunkSection.java': '''package net.minecraft.world.level.chunk;
import net.minecraft.world.level.Level;
public class LevelChunkSection {
 private final Level level;
 public LevelChunkSection(Level level){this.level=level;}
 public boolean hasOnlyAir(){return level.sectionAir;}
 public States getStates(){return new States();}
 public static class States {
  public boolean maybeHas(java.util.function.Predicate<net.minecraft.world.level.block.state.BlockState> p) {
   return p.test(new net.minecraft.world.level.block.state.BlockState(false));
  }
 }
}''',
'net/minecraft/server/level/ServerLevel.java': '''package net.minecraft.server.level;
public class ServerLevel extends net.minecraft.world.level.Level {}''',
'net/minecraft/world/phys/Vec3.java': '''package net.minecraft.world.phys;
public record Vec3(double x,double y,double z) {}''',
'buildcraft/lib/internal/area/IZone.java': '''package buildcraft.lib.internal.area;
public interface IZone {boolean contains(net.minecraft.world.phys.Vec3 pos);}''',
'buildcraft/robotics/internal/legacy/robots/AIRobot.java': '''package buildcraft.robotics.internal.legacy.robots;
public class AIRobot {
 public boolean isWithinWorkChain(AIRobot other){return this==other;}
}''',
'buildcraft/robotics/internal/legacy/boards/RedstoneBoardRobot.java': '''package buildcraft.robotics.internal.legacy.boards;
public class RedstoneBoardRobot extends buildcraft.robotics.internal.legacy.robots.AIRobot {}''',
'buildcraft/robotics/boards/BoardRobotGenericSearchBlock.java': '''package buildcraft.robotics.boards;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import net.minecraft.world.level.Level; import net.minecraft.core.BlockPos;
public class BoardRobotGenericSearchBlock extends buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobot implements buildcraft.robotics.scheduler.ProcessorCandidateBoard {
 public record NBT(String id){ public String getID(){return id;} }
 private final NBT nbt = new NBT("lumberjack");
 public NBT getNBTHandler(){return nbt;}
 public boolean isExpectedBlock(Level world, BlockPos pos){return !world.getBlockState(pos).isAir();}
 public boolean isEligibleForProcessorTarget(Level world,BlockPos pos){return isExpectedBlock(world,pos);}
 public boolean acceptsProcessorCandidateHints(){return true;}
 public String processorWorkCategory(){return nbt.id();}
 public boolean processorScanCandidate(Level level,BlockPos pos){return isExpectedBlock(level,pos);}
 public boolean processorTargetEligible(Level level,BlockPos pos){return isEligibleForProcessorTarget(level,pos);}
 public boolean processorAcceptsHints(){return acceptsProcessorCandidateHints();}
}''',
'buildcraft/robotics/internal/legacy/robots/EntityRobotBase.java': '''package buildcraft.robotics.internal.legacy.robots;
import net.minecraft.core.BlockPos;import net.minecraft.world.level.Level;
import buildcraft.robotics.boards.BoardRobotGenericSearchBlock;
import buildcraft.lib.internal.area.IZone;
public class EntityRobotBase {
 public static final int SAFETY_ENERGY=20000;
 public final Level level; public Level level(){return level;}
 private final long id; private final BlockPos location;
 private final BoardRobotGenericSearchBlock board=new BoardRobotGenericSearchBlock();
 public EntityRobotBase(Level world,long id,BlockPos location){level=world;this.id=id;this.location=location;}
 public boolean isRemoved(){return false;}
 public int getEnergy(){return 50000;}
 public buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobot getBoard(){return board;}
 public AIRobot getActiveAI(){return board;}
 public long getRobotId(){return id;}
 public BlockPos blockPosition(){return location;}
 public int getBlockX(){return location.getX();}
 public int getBlockY(){return location.getY();}
 public int getBlockZ(){return location.getZ();}
 public IZone getZoneToWork(){return null;}
 public IRobotRegistry getRegistry(){return RobotManager.registryProvider.getRegistry(level);}
}''',
'buildcraft/robotics/internal/legacy/robots/ResourceIdBlock.java': '''package buildcraft.robotics.internal.legacy.robots;
public class ResourceIdBlock {public ResourceIdBlock(net.minecraft.core.BlockPos pos){}}''',
'buildcraft/robotics/internal/legacy/robots/IRobotRegistry.java': '''package buildcraft.robotics.internal.legacy.robots;
public interface IRobotRegistry {
 EntityRobotBase getLoadedRobot(long id);
 boolean isTaken(ResourceIdBlock resource);
 long robotIdTaking(ResourceIdBlock resource);
}''',
'buildcraft/robotics/internal/legacy/robots/RobotManager.java': '''package buildcraft.robotics.internal.legacy.robots;
import net.minecraft.world.level.Level;
public class RobotManager {
 public interface Provider {IRobotRegistry getRegistry(Level level);}
 public static Provider registryProvider;
}''',
'buildcraft/robotics/tile/TileRobotProcessor.java': '''package buildcraft.robotics.tile;
import java.util.List;import net.minecraft.core.BlockPos;import net.minecraft.world.level.Level;
public class TileRobotProcessor {
 public static final int WORK_AREA_RADIUS_CHUNKS=3;
 private final Level world; private final BlockPos pos;
 public boolean removed; public final List<Long> linked=new java.util.ArrayList<>();
 public TileRobotProcessor(Level world,BlockPos pos){this.world=world;this.pos=pos;}
 public Level getLevel(){return world;}public BlockPos getBlockPos(){return pos;}
 public boolean isRemoved(){return removed;}
 public List<Long> getLinkedRobotIds(){return List.copyOf(linked);}
 public boolean isRobotLinked(long robotId){return linked.contains(robotId);}
 public boolean allowsWorkPosition(BlockPos target){return true;}
 public static final int MAX_EXECUTION_QUEUE=8;
 public int getMaxExecutionQueue(){return 5;}
 public void setChanged(){}
 public record StationKey(net.minecraft.core.BlockPos pos,net.minecraft.core.Direction side){}
 public List<StationKey> getLinkedStations(){return List.of();}
}''',
}
# The block-discovery fixtures exercise the real block scheduler. Item-entity
# logistics has its own JVM fixture and is stubbed here to keep the fake world
# focused on block navigation rather than needing a full entity subsystem.
STUBS['buildcraft/robotics/boards/BoardRobotPicker.java'] = '''package buildcraft.robotics.boards;
public class BoardRobotPicker extends buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobot {}
'''
STUBS['buildcraft/robotics/scheduler/RobotPickupScheduler.java'] = '''package buildcraft.robotics.scheduler;
import java.util.List;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.tile.TileRobotProcessor;
import net.minecraft.server.level.ServerLevel;
public class RobotPickupScheduler {
 public RobotPickupScheduler(TileRobotProcessor panel){}
 public void tick(ServerLevel level,List<EntityRobotBase> pickers){}
 public void onRemoved(){}
 public void onLinkChanged(){}
 public void onQueueLimitChanged(){}
 public int queueCount(long id){return 0;}
 public static boolean ready(EntityRobotBase robot){return false;}
}
'''
# Stage 10 cargo/source/request dispatch is tested in its own JVM fixture.
# Block-world stand-ins intentionally do not model inventories or pipe docks.
STUBS['buildcraft/robotics/scheduler/RobotLogisticsScheduler.java'] = '''package buildcraft.robotics.scheduler;
import buildcraft.robotics.tile.TileRobotProcessor;
import net.minecraft.server.level.ServerLevel;
public class RobotLogisticsScheduler {
 public RobotLogisticsScheduler(TileRobotProcessor panel){}
 public void tick(ServerLevel level){}
 public void onRemoved(){}
 public void onLinkChanged(){}
 public void onQueueLimitChanged(){}
 public int queueCount(long id){return 0;}
}
'''
for name in ['AIRobotSleep','AIRobotShutdown','AIRobotRecharge','AIRobotGotoSleep',
             'AIRobotGoAndLinkToDock','AIRobotSearchBlock','AIRobotSearchAndGotoBlock',
             'AIRobotGotoBlock','AIRobotBreak','AIRobotHarvest','AIRobotPlant','AIRobotUseToolOnBlock','AIRobotSearchNearestBlock']:
    STUBS[f'buildcraft/robotics/ai/{name}.java'] = (
        f'package buildcraft.robotics.ai; public class {name} '
        'extends buildcraft.robotics.internal.legacy.robots.AIRobot {}')

SIMULATION = '''package fixture;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import buildcraft.robotics.internal.legacy.robots.*;
import buildcraft.robotics.tile.TileRobotProcessor;
import buildcraft.robotics.scheduler.RobotProcessorWorkScheduler;
public class SchedulerFixture {
 static void check(boolean v,String msg){if(!v)throw new AssertionError(msg);}
 public static void main(String[] args) {
   ServerLevel world = new ServerLevel();
   EntityRobotBase a = new EntityRobotBase(world,1,new BlockPos(0,65,0));
   EntityRobotBase b = new EntityRobotBase(world,2,new BlockPos(2,65,2));
   RobotManager.registryProvider = level -> new IRobotRegistry() {
     public EntityRobotBase getLoadedRobot(long id){return id==1?a:id==2?b:null;}
     public boolean isTaken(ResourceIdBlock id){return false;}
     public long robotIdTaking(ResourceIdBlock id){return 0;}
   };
   TileRobotProcessor panel = new TileRobotProcessor(world,new BlockPos(0,65,0));
   panel.linked.add(1L); panel.linked.add(2L);
   RobotProcessorWorkScheduler scheduler = new RobotProcessorWorkScheduler(panel);
   // Only a loaded 0,0 chunk exists. The scanner must never load others.
   for (int i=0; i<7; i++) {world.time++; scheduler.tick(world);}
   var work = RobotProcessorWorkScheduler.suggest(a);
   check(work!=null && work.position().equals(world.target),"No near-work hint");
   check(RobotProcessorWorkScheduler.suggest(b)==null,"Duplicate simultaneous hint");
   scheduler.onRemoved();
   check(RobotProcessorWorkScheduler.suggest(b)==null,"Removed panel still offers work");
   // Re-adding panel must not be blocked by a stale world claim.
   RobotProcessorWorkScheduler next = new RobotProcessorWorkScheduler(panel);
   world.time++; next.tick(world);
   check(RobotProcessorWorkScheduler.suggest(b)!=null,"Lease not released on panel removal");
   next.onRemoved();
   // One robot is not a duplicated scanning workload; don't scan for it.
   panel.linked.remove(1L);
   RobotProcessorWorkScheduler solo = new RobotProcessorWorkScheduler(panel);
   world.time++; solo.tick(world);
   check(RobotProcessorWorkScheduler.suggest(b)==null,"Single worker should keep native search");
 }
}'''


class SchedulerMinecraftCompatibilityTests(unittest.TestCase):
    def test_legacy_and_modern_runtime_source_with_stub_world(self):
        for version in ('1.19.2','26.3'):
            with self.subTest(version=version), tempfile.TemporaryDirectory(prefix='bc-sched-') as d:
                output = Path(d)
                sources = []
                for relative, text in STUBS.items():
                    dest = output / relative
                    dest.parent.mkdir(parents=True, exist_ok=True)
                    dest.write_text(text)
                    sources.append(str(dest))
                fixture = output / 'fixture/SchedulerFixture.java'
                fixture.parent.mkdir(parents=True, exist_ok=True)
                fixture.write_text(SIMULATION)
                sources.append(str(fixture))
                for relative in SOURCES:
                    original = ROOT / relative
                    code = preprocess_text(original.read_text(), minecraft=version,
                                family='old' if version=='1.19.2' else '26.X',
                                platform='forge' if version=='1.19.2' else 'neoforge')
                    dest = output / 'buildcraft/robotics/scheduler' / original.name
                    dest.parent.mkdir(parents=True, exist_ok=True)
                    dest.write_text(code)
                    sources.append(str(dest))
                compile_run = subprocess.run(['javac', '--release', '17', '-d', str(output), *sources],
                    text=True, capture_output=True, timeout=45)
                self.assertEqual(compile_run.returncode, 0, compile_run.stdout + compile_run.stderr)
                runtime = subprocess.run(['java', '-cp', str(output), 'fixture.SchedulerFixture'],
                    text=True, capture_output=True, timeout=20)
                self.assertEqual(runtime.returncode, 0, runtime.stdout + runtime.stderr)

if __name__=='__main__': unittest.main()
