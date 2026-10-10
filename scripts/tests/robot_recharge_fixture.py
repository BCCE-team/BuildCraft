"""Compile and execute the actual Robot Main/Recharge/Sleep AIs against an isolated station simulator.

This is an AI-decision regression harness, not a replacement for running Minecraft.
"""
from __future__ import annotations

import subprocess
import tempfile
from pathlib import Path

STUBS = {
'net/minecraft/core/Direction': '''package net.minecraft.core;
public enum Direction {
 DOWN(0,-1,0), UP(0,1,0), NORTH(0,0,-1), SOUTH(0,0,1), WEST(-1,0,0), EAST(1,0,0);
 private final int x,y,z; Direction(int x,int y,int z){this.x=x;this.y=y;this.z=z;}
 public int getStepX(){return x;}public int getStepY(){return y;}public int getStepZ(){return z;}
}''',
'net/minecraft/world/phys/Vec3': '''package net.minecraft.world.phys;
public record Vec3(double x,double y,double z) {public static final Vec3 ZERO=new Vec3(0,0,0);}''',
'net/minecraft/nbt/CompoundTag': '''package net.minecraft.nbt;
import java.util.HashMap;
public class CompoundTag extends HashMap<String,Integer>{
 public void putInt(String key,int value){put(key,value);}public int getInt(String key){return getOrDefault(key,0);}
}''',
'buildcraft/lib/compat/NbtCompat': '''package buildcraft.lib.compat;
import net.minecraft.nbt.CompoundTag;
public final class NbtCompat {public static int getInt(CompoundTag tag,String key){return tag.getInt(key);}}''',
'buildcraft/lib/internal/statement/StatementSlot': '''package buildcraft.lib.internal.statement;
public class StatementSlot {public final Object statement;public StatementSlot(Object statement){this.statement=statement;}}''',
'buildcraft/robotics/statements/ActionRobotWakeUp': '''package buildcraft.robotics.statements;
public class ActionRobotWakeUp {}''',
'buildcraft/robotics/IStationFilter': '''package buildcraft.robotics;
import buildcraft.robotics.internal.legacy.robots.DockingStation;
public interface IStationFilter {boolean matches(DockingStation station);}''',
'buildcraft/lib/internal/area/IZone': '''package buildcraft.lib.internal.area;
import net.minecraft.world.phys.Vec3;
public interface IZone {boolean contains(Vec3 pos);}''',
'buildcraft/robotics/statements/ActionStationForbidRobot': '''package buildcraft.robotics.statements;
import java.util.HashSet;import java.util.Set;
import buildcraft.robotics.internal.legacy.robots.*;
public class ActionStationForbidRobot {
 public static final Set<DockingStation> forbidden=new HashSet<>();
 public static boolean isForbidden(DockingStation s,EntityRobotBase robot){return forbidden.contains(s);}
}''',
'buildcraft/robotics/internal/legacy/robots/DockingStation': '''package buildcraft.robotics.internal.legacy.robots;
import java.util.List;
import buildcraft.lib.internal.statement.StatementSlot;
import net.minecraft.core.Direction;
public class DockingStation {
 public final int x,y,z;public final boolean power;public boolean active,taken,initialized=true;
 public DockingStation(int x,int y,int z,boolean power){this.x=x;this.y=y;this.z=z;this.power=power;}
 public boolean providesPower(){return power;}public int x(){return x;}public int y(){return y;}public int z(){return z;}
 public Direction side(){return Direction.UP;}
 public boolean isInitialized(){return initialized;}public boolean isTaken(){return taken;}
 public long robotIdTaking(){return taken?99:-1;}
 public Iterable<StatementSlot> getActiveActions(){return active?List.of(new StatementSlot(new buildcraft.robotics.statements.ActionRobotWakeUp())):List.of();}
}''',
'buildcraft/robotics/internal/legacy/robots/EntityRobotBase': '''package buildcraft.robotics.internal.legacy.robots;
import java.util.ArrayList;import java.util.List;
import buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobot;
import net.minecraft.world.phys.Vec3;
public class EntityRobotBase {
 public static final int MAX_ENERGY=100000,SAFETY_ENERGY=20000,SHUTDOWN_ENERGY=0;
 public int energy;public double x,y,z;public Vec3 movement=Vec3.ZERO;
 public DockingStation home,dock;public RedstoneBoardRobot board;
 public final Registry registry=new Registry();
 public EntityRobotBase(int energy){this.energy=energy;}
 public int getEnergy(){return energy;}public double getX(){return x;}public double getY(){return y;}public double getZ(){return z;}
 public DockingStation getLinkedStation(){return home;}public DockingStation getDockingStation(){return dock;}
 public long getRobotId(){return 10;}
 public RedstoneBoardRobot getBoard(){return board;}public Registry getRegistry(){return registry;}
 public void setDeltaMovement(Vec3 value){movement=value;}
 public static class Registry {
  public final List<DockingStation> stations=new ArrayList<>();public int releases;
  public void releaseResources(EntityRobotBase robot){releases++;}
  public List<DockingStation> getStations(){buildcraft.robotics.ai.AIEvents.searched++;return stations;}
 }
}''',
'buildcraft/robotics/internal/legacy/robots/AIRobot': '''package buildcraft.robotics.internal.legacy.robots;
public class AIRobot {
 protected final EntityRobotBase robot;
 private AIRobot delegate,parent; private boolean success=true;
 public AIRobot(EntityRobotBase robot){this.robot=robot;}
 public void start(){}public void preempt(AIRobot ai){}public void update(){}public void delegateAIEnded(AIRobot ai){}
 public int getEnergyCost(){return 1;}public boolean canLoadFromNBT(){return false;}
 public void writeSelfToNBT(net.minecraft.nbt.CompoundTag nbt){}public void loadSelfFromNBT(net.minecraft.nbt.CompoundTag nbt){}
 public void setSuccess(boolean value){success=value;}public boolean success(){return success;}
 public AIRobot getDelegateAI(){return delegate;}
 public AIRobot getActiveAI(){return delegate==null?this:delegate.getActiveAI();}
 public void startDelegateAI(AIRobot next){delegate=next;next.parent=this;next.start();}
 public void terminate(){if(parent!=null){AIRobot p=parent;parent=null;p.delegate=null;p.delegateAIEnded(this);}}
}''',
'buildcraft/robotics/internal/legacy/boards/RedstoneBoardRobot': '''package buildcraft.robotics.internal.legacy.boards;
import buildcraft.robotics.internal.legacy.robots.*;
public class RedstoneBoardRobot extends AIRobot {public RedstoneBoardRobot(EntityRobotBase r){super(r);}}''',
'buildcraft/robotics/ai/AIRobotGotoSleep': '''package buildcraft.robotics.ai;
import buildcraft.robotics.internal.legacy.robots.*;
public class AIRobotGotoSleep extends AIRobot{public AIRobotGotoSleep(EntityRobotBase robot){super(robot);}}''',
'buildcraft/robotics/ai/AIRobotShutdown': '''package buildcraft.robotics.ai;
import buildcraft.robotics.internal.legacy.robots.*;
public class AIRobotShutdown extends AIRobot{public AIRobotShutdown(EntityRobotBase robot){super(robot);}}''',
'buildcraft/robotics/ai/AIRobotReturnToLostStation': '''package buildcraft.robotics.ai;
import buildcraft.robotics.internal.legacy.robots.*;
public class AIRobotReturnToLostStation extends AIRobot{public AIRobotReturnToLostStation(EntityRobotBase robot){super(robot);}}''',
'buildcraft/robotics/ai/AIRobotGotoStation': '''package buildcraft.robotics.ai;
import buildcraft.robotics.internal.legacy.robots.*;
public class AIRobotGotoStation extends AIRobot {
 public static int visits;public static DockingStation lastTarget;
 public final DockingStation target;
 public AIRobotGotoStation(EntityRobotBase r,DockingStation station){super(r);target=station;lastTarget=station;}
 public void start(){visits++;}
}''',
'buildcraft/robotics/ai/AIEvents': '''package buildcraft.robotics.ai;
public final class AIEvents {public static int searched;}''',
'buildcraft/robotics/ai/RobotRechargeHarness': '''package buildcraft.robotics.ai;
import buildcraft.robotics.internal.legacy.robots.*;
import net.minecraft.world.phys.Vec3;
public class RobotRechargeHarness {
 private static int checks;
 static void ok(boolean c){checks++;if(!c)throw new AssertionError("check #"+checks);}
 static void type(AIRobotMain main,Class<?> clazz){ok(clazz.isInstance(main.getDelegateAI()));}
 static AIRobotGotoStation travelling(AIRobotMain main){
  var recharge=(AIRobotRecharge)main.getDelegateAI();
  var search=(AIRobotSearchAndGotoStation)recharge.getDelegateAI();
  return (AIRobotGotoStation)search.getDelegateAI();
 }
 public static void main(String[] args){
  // 14 MJ display = 1400 internal units. A noncharging home cannot monopolize low-battery AI.
  var r=new EntityRobotBase(1400);var home=new DockingStation(1,0,0,false);
  var nearPower=new DockingStation(5,0,0,true);var farPower=new DockingStation(50,0,0,true);
  r.home=home;r.registry.stations.add(home);r.registry.stations.add(farPower);r.registry.stations.add(nearPower);
  var main=new AIRobotMain(r);main.preempt(null);type(main,AIRobotRecharge.class);
  ok(travelling(main).target==nearPower);ok(r.registry.releases==1);ok(r.movement.equals(Vec3.ZERO));
  var current=main.getDelegateAI();main.preempt(current);ok(main.getDelegateAI()==current);
  main.preempt(current);ok(main.getDelegateAI()==current);

  // If the robot was already sleeping at an unpowered station, migrate that persisted sleep state to recharge.
  var stranded=new EntityRobotBase(1400);stranded.home=home;stranded.dock=home;
  stranded.registry.stations.add(home);stranded.registry.stations.add(nearPower);
  var sleepers=new AIRobotMain(stranded);sleepers.startDelegateAI(new AIRobotSleep(stranded));
  sleepers.preempt(sleepers.getDelegateAI());type(sleepers,AIRobotRecharge.class);
  ok(travelling(sleepers).target==nearPower);

  // Also recover a stale low-energy GotoSleep saved while an unpowered home was configured.
  var waiting=new AIRobotMain(stranded);waiting.startDelegateAI(new AIRobotGotoSleep(stranded));
  waiting.preempt(waiting.getDelegateAI());type(waiting,AIRobotRecharge.class);
  ok(travelling(waiting).target==nearPower);

  // Powered sleeping dock is safe: do not bounce back to work until the battery has charged.
  var charged=new EntityRobotBase(1400);charged.dock=nearPower;charged.home=home;
  nearPower.active=true;var stay=new AIRobotMain(charged);
  stay.startDelegateAI(new AIRobotSleep(charged));AIRobot currentSleep=stay.getDelegateAI();
  stay.preempt(currentSleep);ok(stay.getDelegateAI()==currentSleep);
  currentSleep.preempt(null);ok(stay.getDelegateAI()==currentSleep);
  charged.energy=EntityRobotBase.SAFETY_ENERGY;currentSleep.preempt(null);
  ok(stay.getDelegateAI()==null);

  // If there is no charger, the Recharge AI fails with a cooldown; it should not restart every tick.
  var noPower=new EntityRobotBase(1400);noPower.home=home;noPower.registry.stations.add(home);
  var cooldown=new AIRobotMain(noPower);int searches=AIEvents.searched;
  cooldown.preempt(null);ok(cooldown.getDelegateAI()==null);
  ok(AIEvents.searched==searches+1);
  cooldown.preempt(null);ok(AIEvents.searched==searches+1);

  // A powered home at a distance does not preempt an already-running search or force sleep.
  var powerHome=new EntityRobotBase(1400);powerHome.home=farPower;
  powerHome.registry.stations.add(nearPower);powerHome.registry.stations.add(farPower);
  var seek=new AIRobotMain(powerHome);seek.preempt(null);type(seek,AIRobotRecharge.class);
  ok(travelling(seek).target==nearPower);AIRobot active=seek.getDelegateAI();
  seek.preempt(active);ok(seek.getDelegateAI()==active);

  // Actual original station search must honour occupied docks and Forbid Robot gates.
  var filtered=new EntityRobotBase(1400);filtered.home=home;
  filtered.registry.stations.add(home);filtered.registry.stations.add(nearPower);filtered.registry.stations.add(farPower);
  nearPower.taken=true;
  var filteredAi=new AIRobotMain(filtered);filteredAi.preempt(null);
  type(filteredAi,AIRobotRecharge.class);ok(travelling(filteredAi).target==farPower);
  nearPower.taken=false;
  buildcraft.robotics.statements.ActionStationForbidRobot.forbidden.add(nearPower);
  var forbidAi=new AIRobotMain(filtered);forbidAi.preempt(null);
  type(forbidAi,AIRobotRecharge.class);ok(travelling(forbidAi).target==farPower);
  buildcraft.robotics.statements.ActionStationForbidRobot.forbidden.clear();

  // A lost-home recovery has priority over all low-battery decisions.
  var lost=new EntityRobotBase(1400);lost.home=home;lost.registry.stations.add(nearPower);
  var recover=new AIRobotMain(lost);recover.startDelegateAI(new AIRobotReturnToLostStation(lost));
  AIRobot rescue=recover.getDelegateAI();recover.preempt(rescue);ok(recover.getDelegateAI()==rescue);

  // A zero-energy robot outside a charger retains canonical shutdown behaviour.
  var empty=new EntityRobotBase(0);empty.home=home;empty.registry.stations.add(nearPower);
  var powerOff=new AIRobotMain(empty);powerOff.preempt(null);type(powerOff,AIRobotShutdown.class);

  // A completely empty battery at an already-powered dock is allowed to accept charge, not forced into shutdown.
  var atCharger=new EntityRobotBase(0);atCharger.dock=nearPower;atCharger.home=home;
  atCharger.registry.stations.add(nearPower);
  var chargeFromZero=new AIRobotMain(atCharger);chargeFromZero.preempt(null);
  type(chargeFromZero,AIRobotRecharge.class);

  // An old, unpowered sleep state at zero energy must not remain stuck forever.
  var deadSleep=new EntityRobotBase(0);deadSleep.home=home;deadSleep.dock=home;
  var wakeDead=new AIRobotMain(deadSleep);wakeDead.startDelegateAI(new AIRobotSleep(deadSleep));
  wakeDead.preempt(wakeDead.getDelegateAI());type(wakeDead,AIRobotShutdown.class);

  // Preserve the old emergency zero-energy return if it is already heading to a powered home.
  var lastChance=new EntityRobotBase(0);lastChance.home=farPower;
  var emergency=new AIRobotMain(lastChance);emergency.startDelegateAI(new AIRobotGotoSleep(lastChance));
  AIRobot returning=emergency.getDelegateAI();emergency.preempt(returning);
  ok(emergency.getDelegateAI()==returning);

  // Higher-energy worker follows board, while sleeping on a noncharging home is not interrupted prematurely.
  var normal=new EntityRobotBase(EntityRobotBase.SAFETY_ENERGY+100);
  var work=new AIRobotMain(normal);normal.board=new buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobot(normal);
  work.update();type(work,buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobot.class);
  work.preempt(work.getDelegateAI());type(work,buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobot.class);

  System.out.println(checks+" assertions PASS");
 }
}'''
}


def execute(main: Path, recharge: Path, sleep: Path, search: Path, search_and_go: Path) -> str:
    with tempfile.TemporaryDirectory(prefix='bc-robot-recharge-java-') as td:
        src=Path(td)/'src'
        files=[]
        for logical, body in STUBS.items():
            dest=src/(logical+'.java')
            dest.parent.mkdir(parents=True,exist_ok=True)
            dest.write_text(body,encoding='utf-8')
            files.append(str(dest))
        for real in (main,recharge,sleep,search,search_and_go):
            dest=src/'buildcraft/robotics/ai'/real.name
            dest.write_bytes(real.read_bytes())
            files.append(str(dest))
        compilation=subprocess.run(['javac','--release','21','-encoding','UTF-8','-d',str(Path(td)/'classes'),*files],capture_output=True,text=True,timeout=50)
        if compilation.returncode:
            raise AssertionError('Robot AI Java compilation failed:\n'+compilation.stdout+compilation.stderr)
        run=subprocess.run(['java','-cp',str(Path(td)/'classes'),'buildcraft.robotics.ai.RobotRechargeHarness'],capture_output=True,text=True,timeout=25)
        if run.returncode:
            raise AssertionError('Robot AI logic failed:\n'+run.stdout+run.stderr)
        return run.stdout.strip()
