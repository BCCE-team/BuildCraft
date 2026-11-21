"""Real panel scheduler + target queue across Minecraft signature families.

Coverage: no chunk loading, bounded scans, shared footprint, sleep/Gate,
claim fencing, work-area changes and completion cooldown.
"""
from pathlib import Path
import subprocess
import tempfile
import unittest
from scripts.source_preprocessor import preprocess_text

ROOT=Path(__file__).resolve().parents[2]
FILES=[
 'source-shared/src/main/java/buildcraft/robotics/scheduler/RobotAreaActionQueue.java',
 'source-shared/src/main/java/buildcraft/robotics/scheduler/RobotAreaActionScheduler.java',
]
STUBS={
'net/minecraft/core/BlockPos.java': '''package net.minecraft.core;
public record BlockPos(int x,int y,int z){
 public int getX(){return x;}public int getY(){return y;}public int getZ(){return z;}
 public BlockPos offset(int dx,int dy,int dz){return new BlockPos(x+dx,y+dy,z+dz);}
 public long asLong(){return ((long)x&0x3ffffffL)<<38|((long)z&0x3ffffffL)<<12|((long)y&0xfffL);}
 public static BlockPos of(long p){return new BlockPos((int)(p>>38),(int)(p<<52>>52),(int)(p<<26>>38));}
}''',
'net/minecraft/world/level/levelgen/Heightmap.java': '''package net.minecraft.world.level.levelgen;
public class Heightmap{public enum Types{WORLD_SURFACE}}''',
'net/minecraft/world/level/block/state/BlockState.java': '''package net.minecraft.world.level.block.state;
public record BlockState(boolean air){public boolean isAir(){return air;}}''',
'net/minecraft/world/level/Level.java': '''package net.minecraft.world.level;
import net.minecraft.core.BlockPos;import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.state.BlockState;
public class Level{
 public long time;public int heightCalls,blockReads;public boolean noGround;
 public long getGameTime(){return time;}
 public int getMinBuildHeight(){return -64;}public int getMaxBuildHeight(){return 320;}
 public boolean hasChunkAt(BlockPos p){return p.x()>=-48&&p.x()<64&&p.z()>=-48&&p.z()<64;}
 public boolean isLoaded(BlockPos p){return hasChunkAt(p);}
 public boolean isEmptyBlock(BlockPos p){return getBlockState(p).isAir();}
 public int getHeight(Heightmap.Types type,int x,int z){
  if(!hasChunkAt(new BlockPos(x,70,z)))throw new AssertionError("heightmap force loaded chunk");
  heightCalls++;return noGround?-64:71;
 }
 public BlockState getBlockState(BlockPos p){
  if(!isLoaded(p))throw new AssertionError("unloaded world read "+p);
  blockReads++;return new BlockState(noGround||p.y()!=70);
 }
}''',
'net/minecraft/server/level/ServerLevel.java': '''package net.minecraft.server.level;
public class ServerLevel extends net.minecraft.world.level.Level{}''',
'buildcraft/lib/misc/ChunkUtil.java': '''package buildcraft.lib.misc;
import net.minecraft.world.level.Level;import net.minecraft.core.BlockPos;
public class ChunkUtil{
 public static Object getChunk(Level world,int x,int z,boolean force){
  if(force)throw new AssertionError("force loaded chunk");
  return world.hasChunkAt(new BlockPos(x<<4,70,z<<4))?new Object():null;
 }
}''',
'buildcraft/robotics/RobotTrace.java': '''package buildcraft.robotics;
public class RobotTrace{
 public static boolean enabled(){return false;}
 public static void event(buildcraft.robotics.internal.legacy.robots.EntityRobotBase r,String type,String msg,Object... args){}
}''',
'buildcraft/robotics/internal/legacy/robots/AIRobot.java': '''package buildcraft.robotics.internal.legacy.robots;
public class AIRobot{
 public final EntityRobotBase robot;public AIRobot(EntityRobotBase r){robot=r;}
 public boolean isWithinWorkChain(AIRobot b){return this==b||this instanceof Sleep s&&s.board==b;}
 public static class Sleep extends AIRobot{final AIRobot board;public Sleep(EntityRobotBase r,AIRobot b){super(r);board=b;}}
}''',
'buildcraft/robotics/internal/legacy/robots/EntityRobotBase.java': '''package buildcraft.robotics.internal.legacy.robots;
import net.minecraft.world.level.Level;import net.minecraft.core.BlockPos;
public class EntityRobotBase{
 public static final int SAFETY_ENERGY=20000;
 public final Level level;public Level level(){return level;}
 private final long id;private final BlockPos at;public AIRobot board;
 public boolean gated,sleeping,removed;public int energy=60000;public Object dock=new Object();
 public EntityRobotBase(Level level,long id,BlockPos at){this.level=level;this.id=id;this.at=at;}
 public long getRobotId(){return id;}public AIRobot getBoard(){return board;}
 public AIRobot getActiveAI(){return gated?new AIRobot(this):sleeping?new AIRobot.Sleep(this,board):board;}
 public int getEnergy(){return energy;}public boolean isRemoved(){return removed;}public Object getLinkedStation(){return dock;}
 public BlockPos blockPosition(){return at;}
 public int getBlockX(){return at.x();}public int getBlockY(){return at.y();}public int getBlockZ(){return at.z();}
}''',
'buildcraft/robotics/boards/BoardRobotStripes.java': '''package buildcraft.robotics.boards;
import buildcraft.robotics.internal.legacy.robots.*;import net.minecraft.core.BlockPos;
public class BoardRobotStripes extends AIRobot{
 public boolean haveItem=true;
 public BoardRobotStripes(EntityRobotBase r){super(r);}
 public boolean processorHasItem(){return haveItem;}
 public boolean processorCanUseAt(BlockPos p){return haveItem&&robot.level.isEmptyBlock(p);}
}''',
'buildcraft/robotics/boards/BoardRobotBomber.java': '''package buildcraft.robotics.boards;
import buildcraft.robotics.internal.legacy.robots.*;import net.minecraft.core.BlockPos;
public class BoardRobotBomber extends AIRobot{
 public boolean haveTnt=true;
 public BoardRobotBomber(EntityRobotBase r){super(r);}
 public boolean processorHasTnt(){return haveTnt;}
 public boolean processorCanBombAt(BlockPos p){return haveTnt&&!robot.level.getBlockState(p).isAir();}
}''',
'buildcraft/robotics/tile/TileRobotProcessor.java': '''package buildcraft.robotics.tile;
import java.util.*;import net.minecraft.core.BlockPos;import net.minecraft.world.level.Level;
public class TileRobotProcessor{
 public static final int WORK_AREA_RADIUS_CHUNKS=3;
 public final List<Long> linked=new ArrayList<>();public Level level;public BlockPos at;
 public int limit=2;public boolean removed;
 public int minAllowedX=Integer.MIN_VALUE;
 public TileRobotProcessor(Level world,BlockPos pos){level=world;at=pos;}
 public BlockPos getBlockPos(){return at;}public Level getLevel(){return level;}
 public boolean isRemoved(){return removed;}public boolean isRobotLinked(long id){return linked.contains(id);}
 public List<Long> getLinkedRobotIds(){return linked;}
 public int getMaxExecutionQueue(){return limit;}
 public boolean allowsWorkPosition(BlockPos p){return p.x()>=minAllowedX
  && Math.abs((p.x()>>4)-(at.x()>>4))<=3
  && Math.abs((p.z()>>4)-(at.z()>>4))<=3;}
}''',
}
FIXTURE=r'''
package fixture;
import java.util.*;import net.minecraft.core.BlockPos;import net.minecraft.server.level.ServerLevel;
import buildcraft.robotics.boards.*;import buildcraft.robotics.internal.legacy.robots.*;
import buildcraft.robotics.tile.TileRobotProcessor;import buildcraft.robotics.scheduler.*;
public class AreaSchedulerFixture {
 static void ok(boolean result,String why){if(!result)throw new AssertionError(why);}
 static EntityRobotBase robot(ServerLevel level,long id,BlockPos pos,boolean bomber){
  EntityRobotBase r=new EntityRobotBase(level,id,pos);
  r.board=bomber?new BoardRobotBomber(r):new BoardRobotStripes(r);return r;
 }
 public static void main(String...args){
  var level=new ServerLevel();
  var panel=new TileRobotProcessor(level,new BlockPos(0,64,0));
  var other=new TileRobotProcessor(level,new BlockPos(16,64,0));
  var stripes1=robot(level,11,new BlockPos(0,72,0),false);
  var stripes2=robot(level,12,new BlockPos(0,72,0),false);
  var bomber=robot(level,13,new BlockPos(0,72,0),true);
  panel.linked.addAll(List.of(11L,12L,13L));panel.limit=1;
  var dispatcher=new RobotAreaActionScheduler(panel);
  level.time=1;dispatcher.tick(level,List.of(stripes1,stripes2,bomber));
  ok(RobotAreaActionScheduler.hasController(stripes1),"stripes was not linked");
  ok(RobotAreaActionScheduler.hasController(bomber),"bomber was not linked");
  ok(dispatcher.queueCount(11)==1&&dispatcher.queueCount(12)==1,"air work not distributed");
  ok(dispatcher.queueCount(13)==1,"ground work not found");
  ok(level.heightCalls<=96,"unbounded heightmap scans");
  var first=RobotAreaActionScheduler.acquire(stripes1);
  var second=RobotAreaActionScheduler.acquire(stripes2);
  ok(first!=null&&second!=null&&!first.target().equals(second.target()),"two stripes claimed same air");
  ok(RobotAreaActionScheduler.owns(stripes1,first.target(),first.token()),"claimed air target not fenced");
  stripes1.gated=true;
  ok(!RobotAreaActionScheduler.hasAvailableTarget(stripes1),"Gate override ignored");
  stripes1.gated=false;
  RobotAreaActionScheduler.finished(stripes1,first.target(),first.token(),true);
  ok(!RobotAreaActionScheduler.owns(stripes1,first.target(),first.token()),"completion left active claim");
  var tnt=RobotAreaActionScheduler.acquire(bomber);
  ok(tnt!=null&&tnt.token()>0,"bomber did not acquire ground");
  ok(RobotAreaActionScheduler.owns(bomber,tnt.target(),tnt.token()),"bomber claim missing");
  level.time=2;
  var otherDispatcher=new RobotAreaActionScheduler(other);
  otherDispatcher.tick(level,List.of());
  ok(RobotAreaActionScheduler.hasController(bomber),"lost owner on second panel tick");
  RobotAreaActionScheduler.finished(bomber,tnt.target(),tnt.token(),true);
  ok(!RobotAreaActionScheduler.owns(bomber,tnt.target(),tnt.token()),"explosion completed but claim remained");
  stripes1.sleeping=true;
  for(int t=3;t<=25;t++){level.time=t;dispatcher.tick(level,List.of(stripes1,stripes2,bomber));}
  ok(RobotAreaActionScheduler.hasAvailableTarget(stripes1),"sleeping stripes saw no replacement queue");
  panel.linked.remove(11L);
  dispatcher.onLinkChanged();
  ok(!RobotAreaActionScheduler.hasController(stripes1),"unlink did not discard ownership");
  level.noGround=true;
  for(int t=26;t<80;t++){level.time=t;dispatcher.tick(level,List.of(stripes2,bomber));}
  ok(level.heightCalls<100,"excessive ground query rate");
  dispatcher.onRemoved();otherDispatcher.onRemoved();
  ok(!RobotAreaActionScheduler.hasController(stripes2),"panel unload left controller");
  var swarmLevel=new ServerLevel();
  var swarmPanel=new TileRobotProcessor(swarmLevel,new BlockPos(0,64,0));
  swarmPanel.limit=1;
  var swarmScheduler=new RobotAreaActionScheduler(swarmPanel);
  java.util.List<EntityRobotBase> swarm=new java.util.ArrayList<>();
  for(int n=0;n<12;n++){
   var worker=robot(swarmLevel,100+n,new BlockPos(0,72,0),false);
   swarm.add(worker);swarmPanel.linked.add(100L+n);
  }
  for(int t=1;t<=121;t++){swarmLevel.time=t;swarmScheduler.tick(swarmLevel,swarm);}
  for(int n=0;n<12;n++)ok(swarmScheduler.queueCount(100L+n)==1,
    "shared scan budget starved stripes robot "+n);
  swarmScheduler.onRemoved();
  // Painted work exists several chunks away, with NO eligible local air.
  // The panel's coarse global surface scan must eventually find it without
  // using the autonomous full-air scanner or force-loading far chunks.
  var remoteLevel=new ServerLevel();
  var remotePanel=new TileRobotProcessor(remoteLevel,new BlockPos(0,64,0));
  remotePanel.minAllowedX=40;
  remotePanel.limit=1;
  var remote=robot(remoteLevel,401,new BlockPos(0,72,0),false);
  remotePanel.linked.add(401L);
  var remoteScheduler=new RobotAreaActionScheduler(remotePanel);
  for(int t=1;t<=1500 && remoteScheduler.queueCount(401)==0;t++){
   remoteLevel.time=t;remoteScheduler.tick(remoteLevel,List.of(remote));
  }
  var assignedRemote=RobotAreaActionScheduler.acquire(remote);
  ok(assignedRemote!=null&&assignedRemote.target().x()>=40,
    "Stripes global scan ignored painted work three chunks away");
  remoteScheduler.onRemoved();
  System.out.println("PASS: shared Stripes/Bomber discovery, Gate, claims, bounded heightmap, unload, remote work");
 }
}
'''

class AreaActionSchedulerTests(unittest.TestCase):
    def test_scheduler_runtime_variants(self):
        variants=[('1.19.2','old','forge'),('1.20.1','old','forge'),
                  ('1.21.11','1.21.X','neoforge'),('26.3','26.X','neoforge'),
                  ('26.3','26.X','fabric')]
        for version,family,platform in variants:
            with self.subTest(version=version,platform=platform), tempfile.TemporaryDirectory(prefix='bc-area-') as d:
                folder=Path(d); sources=[]
                for rel,body in STUBS.items():
                    dest=folder/rel;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_text(body);sources.append(str(dest))
                for rel in FILES:
                    dest=folder/rel.removeprefix('source-shared/src/main/java/')
                    dest.parent.mkdir(parents=True,exist_ok=True)
                    dest.write_text(preprocess_text((ROOT/rel).read_text(),minecraft=version,family=family,
                                                    platform=platform,source=rel))
                    sources.append(str(dest))
                scenario=folder/'fixture/AreaSchedulerFixture.java'
                scenario.parent.mkdir(parents=True,exist_ok=True);scenario.write_text(FIXTURE);sources.append(str(scenario))
                classes=folder/'classes';classes.mkdir()
                result=subprocess.run(['javac','--release','17','-d',str(classes),*sources],
                                      capture_output=True,text=True,timeout=40)
                self.assertEqual(result.returncode,0,result.stderr)
                run=subprocess.run(['java','-cp',str(classes),'fixture.AreaSchedulerFixture'],
                                   capture_output=True,text=True,timeout=20)
                self.assertEqual(run.returncode,0,run.stdout+run.stderr)
if __name__=='__main__':unittest.main()
