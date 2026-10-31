#!/usr/bin/env python3
"""Compile actual BCCE return-navigation AI and check physical, passable routes."""
from __future__ import annotations

import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / 'source-shared/src/main/java'
REAL = [
    'buildcraft/robotics/ai/AIRobotStraightMoveTo.java',
    'buildcraft/robotics/ai/AIRobotGoAndLinkToDock.java',
    'buildcraft/robotics/ai/AIRobotGotoStation.java',
    'buildcraft/robotics/ai/RobotStationPathPlanner.java',
    'buildcraft/robotics/statements/ActionRobotGotoStation.java',
]
STUBS = {
    'net/minecraft/core/BlockPos.java': '''package net.minecraft.core;
public record BlockPos(int x,int y,int z) {
 public static BlockPos containing(double x,double y,double z){return new BlockPos((int)Math.floor(x),(int)Math.floor(y),(int)Math.floor(z));}
}''',
    'net/minecraft/core/Direction.java': '''package net.minecraft.core;
public enum Direction { DOWN(0,-1,0),UP(0,1,0),NORTH(0,0,-1),SOUTH(0,0,1),WEST(-1,0,0),EAST(1,0,0);
 private final int x,y,z; Direction(int x,int y,int z){this.x=x;this.y=y;this.z=z;}
 public int getStepX(){return x;}public int getStepY(){return y;}public int getStepZ(){return z;}
}''',
    'net/minecraft/nbt/CompoundTag.java': '''package net.minecraft.nbt;
import java.util.*;
public class CompoundTag {
 private final Map<String,Object> v=new HashMap<>();
 public void put(String k, CompoundTag t){v.put(k,t);}public boolean contains(String k){return v.containsKey(k);}
 public CompoundTag getCompound(String k){return (CompoundTag)v.getOrDefault(k,new CompoundTag());}
 public void putInt(String k,int n){v.put(k,n);}public int getInt(String k){return (int)v.getOrDefault(k,0);}
 public void putByte(String k,byte n){v.put(k,n);}public byte getByte(String k){return (byte)v.getOrDefault(k,(byte)0);}
 public void putDouble(String k,double n){v.put(k,n);}public double getDouble(String k){return (double)v.getOrDefault(k,0.0);}
 public void putBoolean(String k,boolean n){v.put(k,n);}public boolean getBoolean(String k){return (boolean)v.getOrDefault(k,false);}
}''',
    'net/minecraft/util/Mth.java': '''package net.minecraft.util;
public class Mth {public static int floor(float f){return (int)Math.floor(f);}
 public static int floor(double d){return (int)Math.floor(d);}}
''',
    'net/minecraft/world/phys/Vec3.java': '''package net.minecraft.world.phys;
public class Vec3 {
 public static final Vec3 ZERO=new Vec3(0,0,0);public final double x,y,z;
 public Vec3(double x,double y,double z){this.x=x;this.y=y;this.z=z;}
 public double length(){return Math.sqrt(x*x+y*y+z*z);}public Vec3 scale(double f){return new Vec3(x*f,y*f,z*f);}
}''',
    'net/minecraft/world/level/Level.java': '''package net.minecraft.world.level;
import net.minecraft.core.BlockPos;
public class Level {
 public boolean allLoaded=true;public int blockedX=Integer.MAX_VALUE;
 public boolean isLoaded(BlockPos p){return allLoaded || p.x()<blockedX;}
}''',
    'net/minecraft/world/item/ItemStack.java': '''package net.minecraft.world.item;
public class ItemStack {public boolean isEmpty(){return true;}}
''',
    'net/minecraft/network/chat/Component.java': '''package net.minecraft.network.chat;
public class Component {public static Component translatable(String k){return new Component();}}
''',
    'buildcraft/lib/internal/core/BlockIndex.java': '''package buildcraft.lib.internal.core;
import net.minecraft.core.BlockPos;import net.minecraft.nbt.CompoundTag;
public class BlockIndex {
 public final int x,y,z;
 public BlockIndex(int x,int y,int z){this.x=x;this.y=y;this.z=z;}
 public BlockIndex(CompoundTag tag){x=tag.getInt("x");y=tag.getInt("y");z=tag.getInt("z");}
 public BlockPos toBlockPos(){return new BlockPos(x,y,z);}
 public void writeTo(CompoundTag t){t.putInt("x",x);t.putInt("y",y);t.putInt("z",z);}
}''',
    'buildcraft/robotics/RobotTrace.java': '''package buildcraft.robotics;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
public class RobotTrace {
 public static boolean enabled(){return false;}
 public static void event(EntityRobotBase r,String name,String format,Object...args){}
}''',
    'buildcraft/robotics/internal/legacy/robots/AIRobot.java': '''package buildcraft.robotics.internal.legacy.robots;
import net.minecraft.nbt.CompoundTag;
public class AIRobot {
 protected final EntityRobotBase robot;private AIRobot parent,delegate;private boolean success=true,terminated;
 public AIRobot(EntityRobotBase r){robot=r;}
 public void start(){} public void update(){} public void preempt(AIRobot ai){} public void end(){}
 public int getEnergyCost(){return 1;} public boolean success(){return success;}
 protected void setSuccess(boolean b){success=b;}
 public boolean canLoadFromNBT(){return false;}
 public void writeSelfToNBT(CompoundTag nbt){}public void loadSelfFromNBT(CompoundTag nbt){}
 public AIRobot getDelegateAI(){return delegate;}
 public void delegateAIEnded(AIRobot ai){}
 public void startDelegateAI(AIRobot ai){delegate=ai;ai.parent=this;ai.start();}
 public void abortDelegateAI(){delegate=null;}
 public void terminate(){if(terminated)return;terminated=true;end();if(parent!=null){parent.delegate=null;parent.delegateAIEnded(this);}}
 public boolean isTerminated(){return terminated;}
}''',
    'buildcraft/robotics/internal/legacy/robots/IRobotRegistry.java': '''package buildcraft.robotics.internal.legacy.robots;
import net.minecraft.core.*;
public class IRobotRegistry {
 public DockingStation station;
 public DockingStation getStation(BlockPos p,Direction d){return station!=null && station.x()==p.x() && station.y()==p.y()
  && station.z()==p.z() && station.side()==d?station:null;}
}''',
    'buildcraft/robotics/internal/legacy/robots/EntityRobotBase.java': '''package buildcraft.robotics.internal.legacy.robots;
import net.minecraft.world.level.Level;import net.minecraft.world.phys.Vec3;
public class EntityRobotBase {
 public static final long NULL_ROBOT_ID=-1; public final Level level=new Level();
 public Level level(){return level;}
 public final IRobotRegistry registry=new IRobotRegistry();
 public double x,y,z;public Vec3 velocity=Vec3.ZERO;public DockingStation linked,docked;
 public int gateCommands;
 public EntityRobotBase(double x,double y,double z){this.x=x;this.y=y;this.z=z;}
 public double getX(){return x;}public double getY(){return y;}public double getZ(){return z;}
 public void setPos(double x,double y,double z){this.x=x;this.y=y;this.z=z;}
 public Vec3 getDeltaMovement(){return velocity;}public void setDeltaMovement(Vec3 v){velocity=v;}
 public double distanceToSqr(double x,double y,double z){double a=this.x-x,b=this.y-y,c=this.z-z;return a*a+b*b+c*c;}
 public void tickMove(){setPos(x+velocity.x,y+velocity.y,z+velocity.z);}
 public void aimItemAt(int x,int y,int z){}public void aimItemAt(float yaw,float pitch){}
 public float getAimYaw(){return 0;}public float getAimPitch(){return 0;}
 public DockingStation getLinkedStation(){return linked;}
 public DockingStation getDockingStation(){return docked;}
 public IRobotRegistry getRegistry(){return registry;}
 public long getRobotId(){return 4L;}
 public void dock(DockingStation station){docked=station;velocity=Vec3.ZERO;}
 public void undock(){docked=null;}
 public void setMainAIOverride(AIRobot ai){gateCommands++;}
}''',
    'buildcraft/robotics/internal/legacy/robots/DockingStation.java': '''package buildcraft.robotics.internal.legacy.robots;
import buildcraft.lib.internal.core.BlockIndex;import net.minecraft.core.Direction;
public class DockingStation {
 private final BlockIndex idx;private final Direction dir;public EntityRobotBase taker;public boolean available=true;
 public DockingStation(int x,int y,int z,Direction dir){idx=new BlockIndex(x,y,z);this.dir=dir;}
 public int x(){return idx.x;}public int y(){return idx.y;}public int z(){return idx.z;}
 public Direction side(){return dir;}public BlockIndex index(){return idx;}
 public boolean isInitialized(){return available;}
 public boolean takeAsMain(EntityRobotBase r){if(!available)return false;r.linked=this;taker=r;return true;}
 public boolean take(EntityRobotBase r){if(!available)return false;taker=r;return true;}
 public void release(EntityRobotBase r){if(taker==r)taker=null;}
 public long robotIdTaking(){return taker==null?EntityRobotBase.NULL_ROBOT_ID:taker.getRobotId();}
 public EntityRobotBase robotTaking(){return taker;}
}''',
    'buildcraft/robotics/ai/AIRobotGoto.java': '''package buildcraft.robotics.ai;
import buildcraft.robotics.internal.legacy.robots.*;import net.minecraft.world.phys.Vec3;
public class AIRobotGoto extends AIRobot {
 public AIRobotGoto(EntityRobotBase r){super(r);}
 public int getEnergyCost(){return 3;}
 protected void setDestination(EntityRobotBase r,double x,double y,double z){
  double a=x-r.getX(),b=y-r.getY(),c=z-r.getZ(),d=Math.sqrt(a*a+b*b+c*c);
  r.setDeltaMovement(d==0?Vec3.ZERO:new Vec3(a/d*0.15,b/d*0.15,c/d*0.15));
 }
}''',
    'buildcraft/robotics/ai/AIRobotGotoBlock.java': '''package buildcraft.robotics.ai;
import buildcraft.robotics.internal.legacy.robots.*;
public class AIRobotGotoBlock extends AIRobot {
 public static int starts;public AIRobotGotoBlock(EntityRobotBase r,int x,int y,int z,double range){super(r);}
 public void start(){starts++;robot.undock();}
 public void update(){setSuccess(false);terminate();}
}''',
    'buildcraft/robotics/internal/legacy/robots/RobotManager.java': '''package buildcraft.robotics.internal.legacy.robots;
import net.minecraft.world.level.Level;
public class RobotManager {
 public static RegistryProvider registryProvider=new RegistryProvider();
 public static class RegistryProvider {public IRobotRegistry getRegistry(Level l){return new IRobotRegistry();}}
}''',
    'buildcraft/lib/internal/statement/IStatementParameter.java': '''package buildcraft.lib.internal.statement;
public interface IStatementParameter {}''',
    'buildcraft/lib/internal/statement/IStatementContainer.java': '''package buildcraft.lib.internal.statement;
public interface IStatementContainer {Object getTile();}''',
    'buildcraft/lib/internal/statement/IActionInternal.java': '''package buildcraft.lib.internal.statement;
public interface IActionInternal {void actionActivate(IStatementContainer c,IStatementParameter[] p);}''',
    'buildcraft/lib/internal/core/render/ISprite.java': '''package buildcraft.lib.internal.core.render;
public interface ISprite {}''',
    'buildcraft/core/statements/BCStatement.java': '''package buildcraft.core.statements;
import net.minecraft.network.chat.Component;
import buildcraft.lib.internal.statement.IStatementParameter;
import buildcraft.lib.internal.core.render.ISprite;
public class BCStatement {
 public BCStatement(String name){}public int maxParameters(){return 0;}
 public IStatementParameter createParameter(int index){return null;}
 public Component getDescription(){return null;}public ISprite getSprite(){return null;}
}''',
    'buildcraft/robotics/BCRoboticsSprites.java': '''package buildcraft.robotics;
import buildcraft.lib.internal.core.render.ISprite;
public class BCRoboticsSprites {public static final ISprite ACTION_ROBOT_GOTO_STATION=new ISprite(){};}''',
    'buildcraft/robotics/statements/StatementParameterMapLocation.java': '''package buildcraft.robotics.statements;
import buildcraft.lib.internal.statement.IStatementParameter;
import net.minecraft.world.item.ItemStack;
public class StatementParameterMapLocation implements IStatementParameter {public ItemStack getItemStack(){return new ItemStack();}}''',
    'buildcraft/robotics/plug/RobotStationPluggable.java': '''package buildcraft.robotics.plug;
import buildcraft.robotics.internal.legacy.robots.DockingStation;
public class RobotStationPluggable {public final DockingStation station;
 public RobotStationPluggable(DockingStation s){station=s;}public DockingStation getStation(){return station;}}
''',
    'buildcraft/transport/internal/pipe/IPipeHolder.java': '''package buildcraft.transport.internal.pipe;
import net.minecraft.core.Direction;import net.minecraft.world.level.Level;
public interface IPipeHolder {Object getPluggable(Direction dir);Level getPipeWorld();}
''',
    'buildcraft/api/v2/BuildCraftServices.java': '''package buildcraft.api.v2;
public class BuildCraftServices {public static final String MAP_LOCATIONS="locations";}
''',
    'buildcraft/api/v2/BuildCraftApi.java': '''package buildcraft.api.v2;
import net.minecraft.world.item.ItemStack;import net.minecraft.core.*;import java.util.Optional;
public class BuildCraftApi {
 public static MapService service(String ignored){return new MapService();}
 public static class MapService {public Optional<MapLocation> read(ItemStack s){return Optional.empty();}}
 public record MapLocation(Optional<BlockPos> point,Optional<Direction> pointSide){}
}''',
}

# Upgrade the minimal Minecraft/robot fixtures to exercise the REAL pathfinding AI.
STUBS.pop('buildcraft/robotics/ai/AIRobotGotoBlock.java')
STUBS['net/minecraft/core/BlockPos.java'] = r'''package net.minecraft.core;
public record BlockPos(int x,int y,int z) {
 public BlockPos immutable(){return this;}
 public int getX(){return x;}public int getY(){return y;}public int getZ(){return z;}
 public BlockPos relative(Direction d){return new BlockPos(x+d.getStepX(),y+d.getStepY(),z+d.getStepZ());}
 public BlockPos above(){return new BlockPos(x,y+1,z);}
 public static BlockPos containing(double x,double y,double z){return new BlockPos((int)Math.floor(x),(int)Math.floor(y),(int)Math.floor(z));}
}'''
STUBS['net/minecraft/world/level/Level.java'] = '''package net.minecraft.world.level;
import net.minecraft.core.BlockPos;import java.util.*;
import net.minecraft.world.level.block.state.BlockState;
public class Level {
 public final Set<BlockPos> air=new HashSet<>();
 public final Set<BlockPos> missing=new HashSet<>();
 public long time;
 public boolean isLoaded(BlockPos p){return !missing.contains(p);}
 public long getGameTime(){return time;}
 public BlockState getBlockState(BlockPos p){if(!isLoaded(p))throw new AssertionError("Unloaded block access: "+p);
  return new BlockState(air.contains(p));}
}'''
STUBS['net/minecraft/world/level/block/state/BlockState.java'] = '''package net.minecraft.world.level.block.state;
import net.minecraft.world.level.Level;import net.minecraft.core.BlockPos;
public class BlockState {
 private final boolean air;public BlockState(boolean air){this.air=air;}
 public boolean isAir(){return air;}
 public Shape getCollisionShape(Level l,BlockPos p){return new Shape(air);}
 public record Shape(boolean empty){public boolean isEmpty(){return empty;}}
}'''
STUBS['buildcraft/lib/internal/core/BlockIndex.java'] = '''package buildcraft.lib.internal.core;
import net.minecraft.core.BlockPos;import net.minecraft.nbt.CompoundTag;
public class BlockIndex {
 public final int x,y,z;
 public BlockIndex(int x,int y,int z){this.x=x;this.y=y;this.z=z;}
 public BlockIndex(BlockPos p){this(p.x(),p.y(),p.z());}
 public BlockIndex(CompoundTag tag){x=tag.getInt("x");y=tag.getInt("y");z=tag.getInt("z");}
 public BlockPos toBlockPos(){return new BlockPos(x,y,z);}
 public void writeTo(CompoundTag t){t.putInt("x",x);t.putInt("y",y);t.putInt("z",z);}
}'''
STUBS['buildcraft/robotics/ai/AIRobotGoto.java'] = '''package buildcraft.robotics.ai;
import buildcraft.robotics.internal.legacy.robots.*;import net.minecraft.world.phys.Vec3;
public class AIRobotGoto extends AIRobot {
 protected double nextX,nextY,nextZ;
 public AIRobotGoto(EntityRobotBase r){super(r);}
 public int getEnergyCost(){return 3;}
 protected void setDestination(EntityRobotBase r,double x,double y,double z){
  nextX=x;nextY=y;nextZ=z;
  double a=x-r.getX(),b=y-r.getY(),c=z-r.getZ(),d=Math.sqrt(a*a+b*b+c*c);
  r.setDeltaMovement(d==0?Vec3.ZERO:new Vec3(a/d*0.15,b/d*0.15,c/d*0.15));
 }
}'''
STUBS['buildcraft/robotics/internal/legacy/robots/EntityRobotBase.java'] = '''package buildcraft.robotics.internal.legacy.robots;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;import net.minecraft.world.phys.Vec3;
public class EntityRobotBase {
 public static final long NULL_ROBOT_ID=-1; public final Level level=new Level();
 public Level level(){return level;}
 public final IRobotRegistry registry=new IRobotRegistry();
 public double x,y,z;public Vec3 velocity=Vec3.ZERO;public DockingStation linked,docked;
 public int gateCommands;
 public EntityRobotBase(double x,double y,double z){this.x=x;this.y=y;this.z=z;}
 public double getX(){return x;}public double getY(){return y;}public double getZ(){return z;}
 public void setPos(double x,double y,double z){this.x=x;this.y=y;this.z=z;}
 public BlockPos blockPosition(){return BlockPos.containing(x,y,z);}
 public Vec3 getDeltaMovement(){return velocity;}public void setDeltaMovement(Vec3 v){velocity=v;}
 public boolean isMoving(){return velocity.length()>0.0000001;}
 public double distanceToSqr(double x,double y,double z){double a=this.x-x,b=this.y-y,c=this.z-z;return a*a+b*b+c*c;}
 public void tickMove(){setPos(x+velocity.x,y+velocity.y,z+velocity.z);level.time++;}
 public void aimItemAt(int x,int y,int z){}public void aimItemAt(float yaw,float pitch){}
 public float getAimYaw(){return 0;}public float getAimPitch(){return 0;}
 public DockingStation getLinkedStation(){return linked;}
 public DockingStation getDockingStation(){return docked;}
 public IRobotRegistry getRegistry(){return registry;}
 public long getRobotId(){return 4L;}
 public void dock(DockingStation station){docked=station;velocity=Vec3.ZERO;}
 public void undock(){docked=null;}
 public void setMainAIOverride(AIRobot ai){gateCommands++;}
}'''
FIXTURE = '''package fixture;
import buildcraft.robotics.ai.*;
import buildcraft.robotics.internal.legacy.robots.*;
import buildcraft.robotics.statements.ActionRobotGotoStation;
import buildcraft.robotics.plug.RobotStationPluggable;
import buildcraft.lib.internal.statement.*;
import buildcraft.transport.internal.pipe.IPipeHolder;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import java.util.*;
public class HomeReturnFixture {
 static void check(boolean yes,String why){if(!yes)throw new AssertionError(why);}
 static void air(Level level,int x,int y,int z){level.air.add(new BlockPos(x,y,z));}
 static EntityRobotBase caveRobot(DockingStation station){
  var robot=new EntityRobotBase(0.5,0.5,0.5);robot.registry.station=station;
  station.taker=robot;
  for(int z=0;z<=40;z++)air(robot.level,0,0,z);
  for(int x=0;x<=8;x++)air(robot.level,x,0,40);
  for(int z=1;z<=40;z++)air(robot.level,8,0,z);
  air(robot.level,8,1,1);air(robot.level,8,1,0);
  return robot;
 }
 static void step(AIRobot ai,EntityRobotBase r,boolean actuallyMove){
  AIRobot leaf=ai;
  while(leaf.getDelegateAI()!=null)leaf=leaf.getDelegateAI();
  leaf.update();
  if(actuallyMove){
   r.tickMove();
   // The final docking movement may overlap the pipe itself; all route cells
   // before the final movement must be traversable, never stone.
  }else r.level.time++;
 }
 static void incrementalFinderUsesLongDetour(){
  var dock=new DockingStation(8,0,0,Direction.UP);
  var r=caveRobot(dock);
  var planner=new RobotStationPathPlanner(r.level,r.blockPosition(),new BlockPos(8,0,0),new BlockPos(8,1,0));
  RobotStationPathPlanner.Result result=planner.result();int ticks=0;
  while(result==RobotStationPathPlanner.Result.SEARCHING && ticks++<200){result=planner.advance(r.level,4);}
  check(result==RobotStationPathPlanner.Result.FOUND,"Long mine detour not found");
  check(ticks>1,"Search was not incremental");
  var path=planner.path();check(path.size()>65,"Path incorrectly jumped through the solid wall");
  BlockPos previous=r.blockPosition();
  for(var segment:path){
   BlockPos next=segment.toBlockPos();
   check(r.level.air.contains(next),"Route visits stone: "+next);
   int distance=Math.abs(next.x()-previous.x())+Math.abs(next.y()-previous.y())+Math.abs(next.z()-previous.z());
   check(distance==1,"Route skipped a block: "+previous+" -> "+next);
   previous=next;
  }
 }
 static void gateReturnsWithoutPhasingThroughRock(){
  var dock=new DockingStation(8,0,0,Direction.UP);
  var r=caveRobot(dock);
  var gate=new AIRobotGoAndLinkToDock(r,dock);gate.start();
  int ticks=0;boolean sawMovement=false;
  while(!gate.isHoldingAtStation() && ticks++<2500){
   gate.refreshGateSignal();
   AIRobot leaf=gate;
   while(leaf.getDelegateAI()!=null)leaf=leaf.getDelegateAI();
   boolean isRoute=leaf instanceof AIRobotGotoBlock;
   step(gate,r,true);
   if(isRoute && r.getDockingStation()==null){
    check(r.level.air.contains(r.blockPosition()),"Robot flew inside stone: "+r.blockPosition());
    sawMovement=true;
   }
  }
  check(ticks<2500,"Gate never returned from the mine");
  check(gate.success() && gate.isHoldingAtStation() && !gate.isTerminated(),
   "Gate must hold its station after arriving while the command is active");
  check(r.getLinkedStation()==dock && r.getDockingStation()==dock,"Home docking failed");
  check(sawMovement,"Home return skipped real path traversal");
 }
 static void blockedDockFaceNeverCrossesStone(){
  var dock=new DockingStation(8,0,0,Direction.DOWN);
  var r=caveRobot(dock);
  // Another face is open, but the DOWN socket is covered with stone.
  // Routing to a different face and then docking straight through the
  // pipe or stone is NOT a physically valid route.
  var planner=new RobotStationPathPlanner(r.level,r.blockPosition(),new BlockPos(8,0,0),new BlockPos(8,-1,0));
  check(planner.result()==RobotStationPathPlanner.Result.NO_ROUTE,
      "Blocked docking socket must not be bypassed through another face");
 }
 static void dynamicObstructionStopsBeforeSolid(){
  var dock=new DockingStation(8,0,0,Direction.UP);var r=caveRobot(dock);
  var route=new AIRobotGotoBlock(r,8,1,0,24).forStationReturn(8,0,0);
  route.start();step(route,r,true);step(route,r,true);
  check(r.getZ()<1.0,"Unexpected starting route position");
  var blocked=new BlockPos(0,0,1);r.level.air.remove(blocked);
  route.update();
  check(r.getDeltaMovement().length()==0.0,"Robot kept moving toward solid block added after planning");
  r.tickMove();
  check(!r.blockPosition().equals(blocked),"Robot phased through newly placed stone");
 }
 static void failedReturnRecoversAfterTunnelOpens(){
  var dock=new DockingStation(8,0,0,Direction.UP);var r=caveRobot(dock);
  var obstacle=new BlockPos(0,0,1);r.level.air.remove(obstacle);
  var gate=new AIRobotGoAndLinkToDock(r,dock);gate.start();
  // Starting point has no open neighbor; no fake route is permitted.
  for(int t=0;t<5 && gate.getDelegateAI()!=null;t++)step(gate,r,true);
  check(gate.getDelegateAI()==null && !gate.isTerminated(),"Failed path did not enter retry wait");
  check(gate.getEnergyCost()==0,"Waiting after failed route consumed energy");
  // The player opens the original tunnel. Recovery must happen without restarting the robot.
  r.level.air.add(obstacle);
  for(int t=0;t<39;t++){gate.update();r.level.time++;}
  check(gate.getDelegateAI()==null,"Gate retried before the backoff elapsed");
  gate.update();
  check(gate.getDelegateAI()!=null,"Gate did not retry after the world changed");
  int ticks=0;
  while(!gate.isHoldingAtStation() && ticks++<2500){gate.refreshGateSignal();step(gate,r,true);}
  check(gate.isHoldingAtStation() && gate.success() && r.getDockingStation()==dock,
   "Robot failed to resume a legitimate route after tunnel reopened");
 }
 static void temporaryDockUsesReachablePipeFace(){
  var dock=new DockingStation(8,0,0,Direction.DOWN);var r=caveRobot(dock);
  // Physically open a tunnel underneath this station's configured socket.
  air(r.level,8,-1,1);air(r.level,8,-1,0);
  var temporary=new AIRobotGotoStation(r,dock);temporary.start();
  int ticks=0;
  while(!temporary.isTerminated() && ticks++<2500)step(temporary,r,true);
  check(temporary.isTerminated() && temporary.success() && r.getDockingStation()==dock,
   "Bottom-mounted temporary station failed despite an open path to its actual socket");
 }
 static void temporaryStationRetriesAfterTunnelOpens(){
  var dock=new DockingStation(8,0,0,Direction.UP);var r=caveRobot(dock);
  var blocked=new BlockPos(0,0,1);r.level.air.remove(blocked);
  var temporary=new AIRobotGotoStation(r,dock);temporary.start();
  for(int t=0;t<6 && temporary.getDelegateAI()!=null;t++)step(temporary,r,true);
  check(!temporary.isTerminated() && temporary.getDelegateAI()==null,
   "Temporary station navigation aborted after one failed route");
  check(temporary.getEnergyCost()==0,"Station retry wait must not draw energy");
  air(r.level,0,0,1);
  int ticks=0;
  while(!temporary.isTerminated() && ticks++<2500)step(temporary,r,true);
  check(temporary.isTerminated() && temporary.success() && r.getDockingStation()==dock,
   "Temporary station AI did not retry after opening a tunnel");
 }
 static void temporaryStationRouteFailuresAreBounded(){
  var dock=new DockingStation(8,0,0,Direction.UP);var r=caveRobot(dock);
  r.level.air.remove(new BlockPos(0,0,1));
  var temporary=new AIRobotGotoStation(r,dock);temporary.start();
  int ticks=0;
  while(!temporary.isTerminated() && ticks++<260)step(temporary,r,true);
  check(temporary.isTerminated() && !temporary.success(),
   "Temporary station must eventually report a genuinely unreachable dock");
  check(dock.taker==null,"Unreachable temporary station left a stale reservation");
 }
 static void missingChunksWaitWithoutWorldAccess(){
  var dock=new DockingStation(8,0,0,Direction.UP);var r=caveRobot(dock);
  // Remove the only first step of the mine's path.
  var blocked=new BlockPos(0,0,1);r.level.missing.add(blocked);
  var planner=new RobotStationPathPlanner(r.level,r.blockPosition(),new BlockPos(8,0,0),new BlockPos(8,1,0));
  RobotStationPathPlanner.Result result=planner.result();
  for(int t=0;t<30 && result==RobotStationPathPlanner.Result.SEARCHING;t++)result=planner.advance(r.level,100);
  check(result==RobotStationPathPlanner.Result.WAITING_FOR_CHUNKS,"Expected non-destructive chunk wait");
  r.level.missing.clear();
  var resumed=new RobotStationPathPlanner(r.level,r.blockPosition(),new BlockPos(8,0,0),new BlockPos(8,1,0));
  result=resumed.result();
  for(int t=0;t<30 && result==RobotStationPathPlanner.Result.SEARCHING;t++)result=resumed.advance(r.level,100);
  check(result==RobotStationPathPlanner.Result.FOUND,"Search did not recover after chunk load");
 }
 static void blockedTravelIsRecognizedFromPosition(){
  var dock=new DockingStation(8,0,0,Direction.UP);var r=caveRobot(dock);
  AIRobotGotoBlock gotoStation=new AIRobotGotoBlock(r,8,1,0,24.0).forStationReturn(8,0,0);
  gotoStation.start();
  for(int i=0;i<180 && !gotoStation.isTerminated();i++){gotoStation.update();r.level.time++;}
  // A route exists and has nonzero velocity, but no simulated travel occurs.
  check(gotoStation.isTerminated() && !gotoStation.success(),
   "Station route must recognize unchanged actual position as a stall");
 }
 static void continuousGateSignalHoldsAndReleases(){
  var dock=new DockingStation(8,0,0,Direction.UP);
  var r=caveRobot(dock);r.linked=dock;r.docked=dock;
  var gate=new AIRobotGoAndLinkToDock(r,dock);gate.start();
  check(gate.isHoldingAtStation() && !gate.isTerminated(),
   "An already docked Gate command must not terminate while active");
  int initialCost=gate.getEnergyCost();
  check(initialCost==0,"Holding at the dock consumed energy");
  for(int t=0;t<30;t++){
   gate.refreshGateSignal();gate.update();r.level.time++;
   check(gate.isHoldingAtStation() && !gate.isTerminated(),
    "Repeated active Gate signal released the station hold");
   check(r.getDockingStation()==dock,"Gate hold undocked the worker");
  }
  for(int t=0;t<3;t++){gate.update();r.level.time++;}
  check(gate.isTerminated(),"Stopping the Gate must release its dock hold");
 }
 static void persistenceAndGateDedup(){
  var dock=new DockingStation(8,0,0,Direction.UP);var r=caveRobot(dock);
  var flight=new AIRobotGotoBlock(r,8,1,0,24).forStationReturn(8,0,0);
  var saved=new CompoundTag();flight.writeSelfToNBT(saved);
  var loaded=new AIRobotGotoBlock(r);loaded.loadSelfFromNBT(saved);
  check(loaded.getEnergyCost()==0,"Loaded return must replan without consuming movement energy");
  loaded.start();loaded.update();
  check(!loaded.isTerminated(),"Loaded return was discarded");
  var gate=new AIRobotGoAndLinkToDock(r,dock);gate.start();
  var tag=new CompoundTag();gate.writeSelfToNBT(tag);
  var revived=new AIRobotGoAndLinkToDock(r);revived.loadSelfFromNBT(tag);
  check(revived.sameDestination(gate),"Gate destination lost on reload");
  r.linked=dock;r.docked=dock;
  var action=new ActionRobotGotoStation();
  IPipeHolder holder=new IPipeHolder(){
   public Object getPluggable(Direction dir){return dir==Direction.UP?new RobotStationPluggable(dock):null;}
   public Level getPipeWorld(){return r.level;}
  };
  IStatementContainer container=()->holder;
  action.actionActivate(container,new IStatementParameter[0]);
  check(r.gateCommands==1,"Active Gate must keep broadcasting its docking signal");
  r.docked=null;action.actionActivate(container,new IStatementParameter[0]);
  check(r.gateCommands==2,"Gate cannot command an undocked robot");
 }
 public static void main(String[] args){
  incrementalFinderUsesLongDetour();gateReturnsWithoutPhasingThroughRock();
  blockedDockFaceNeverCrossesStone();temporaryDockUsesReachablePipeFace();
  failedReturnRecoversAfterTunnelOpens();temporaryStationRetriesAfterTunnelOpens();
  temporaryStationRouteFailuresAreBounded();dynamicObstructionStopsBeforeSolid();
  missingChunksWaitWithoutWorldAccess();
  blockedTravelIsRecognizedFromPosition();continuousGateSignalHoldsAndReleases();persistenceAndGateDedup();
  System.out.println("PASS: long winding return, voxel-safe movement, real-face docking, signal-held Gate, recovery after world edits, chunk wait, NBT");
 }
}'''

class HomeReturnRuntime(unittest.TestCase):
    def test_real_station_pathfinder_and_docking_ai(self):
        if not shutil.which('javac') or not shutil.which('java'):
            self.skipTest('Java development kit unavailable')
        with tempfile.TemporaryDirectory(prefix='bc-station-route-') as tmp:
            root = Path(tmp)
            stubs = []
            for relative, code in STUBS.items():
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(code, encoding='utf-8')
                stubs.append(str(path))
            fixture = root / 'fixture/HomeReturnFixture.java'
            fixture.parent.mkdir(parents=True, exist_ok=True)
            fixture.write_text(FIXTURE, encoding='utf-8')
            for version, family in (('legacy-1192','old'), ('legacy-1201','old'), ('modern','1.21.X')):
                sources = list(stubs) + [str(fixture)]
                source_root = ROOT / ('source-families/' + family + '/src/main/java')
                for relative in REAL:
                    path = SRC / relative
                    raw = path.read_text(encoding='utf-8')
                    dst = root / version / relative
                    dst.parent.mkdir(parents=True, exist_ok=True)
                    dst.write_text(raw, encoding='utf-8')
                    sources.append(str(dst))
                rel = 'buildcraft/robotics/ai/AIRobotGotoBlock.java'
                raw = (source_root / rel).read_text(encoding='utf-8')
                if version == 'legacy-1201':
                    original = ('        return robot.level;\n        //?} else {\n'
                                '        /*?\n        return robot.level();\n        ?*/\n        //?}')
                    self.assertIn(original, raw)
                    raw = raw.replace(original, '        return robot.level();')
                dst = root / version / rel
                dst.parent.mkdir(parents=True, exist_ok=True)
                dst.write_text(raw, encoding='utf-8')
                sources.append(str(dst))
                classes = root / ('classes-' + version)
                classes.mkdir()
                compiled = subprocess.run(['javac','-encoding','UTF-8','-d',str(classes),*sources],
                                          capture_output=True,text=True,timeout=90)
                self.assertEqual(compiled.returncode, 0, version + ': ' + compiled.stderr[-12000:])
                executed = subprocess.run(['java','-cp',str(classes),'fixture.HomeReturnFixture'],
                                         capture_output=True,text=True,timeout=35)
                self.assertEqual(executed.returncode, 0, version + ': ' + executed.stderr[-12000:])
                self.assertIn('PASS:', executed.stdout)
                print(version + ': ' + executed.stdout.strip(), flush=True)

if __name__ == '__main__':
    unittest.main()
