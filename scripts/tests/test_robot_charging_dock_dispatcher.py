#!/usr/bin/env python3
"""Production charging station arbitration: JVM model and AI-search integration.

The test compiles the actual shared Java sources against a tiny world; the
simulator never stands in for an actual Minecraft/Forge runtime test.
"""
from __future__ import annotations

import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE_ROOT = ROOT / 'source-shared/src/main/java'

STUBS = {
    'net/minecraft/core/Direction': '''package net.minecraft.core;
public enum Direction { DOWN,UP,NORTH,SOUTH,WEST,EAST; }''',
    'net/minecraft/world/phys/Vec3': '''package net.minecraft.world.phys;
public record Vec3(double x,double y,double z) {}''',
    'net/minecraft/world/level/Level': '''package net.minecraft.world.level;
public class Level {public long ticks;public long getGameTime(){return ticks;}}''',
    'buildcraft/lib/internal/area/IZone': '''package buildcraft.lib.internal.area;
import net.minecraft.world.phys.Vec3;
public interface IZone {boolean contains(Vec3 pos);}''',
    'buildcraft/robotics/IStationFilter': '''package buildcraft.robotics;
import buildcraft.robotics.internal.legacy.robots.DockingStation;
public interface IStationFilter {boolean matches(DockingStation s);
 default boolean prefersProcessorLinkedStations(){return false;}}''',
    'buildcraft/robotics/RobotTrace': '''package buildcraft.robotics;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
public class RobotTrace {public static boolean enabled(){return false;}
 public static void event(EntityRobotBase r,String name,String format,Object... args){} }''',
    'buildcraft/robotics/statements/ActionStationForbidRobot': '''package buildcraft.robotics.statements;
import java.util.*;
import buildcraft.robotics.internal.legacy.robots.*;
public class ActionStationForbidRobot {
 public static final Set<DockingStation> forbidden=new HashSet<>();
 public static boolean isForbidden(DockingStation s,EntityRobotBase r){return forbidden.contains(s);}
}''',
    'buildcraft/robotics/scheduler/RobotProcessorWorkScheduler': '''package buildcraft.robotics.scheduler;
import buildcraft.robotics.internal.legacy.robots.*;
public class RobotProcessorWorkScheduler {
 public static boolean isLinkedChargingStation(EntityRobotBase robot,DockingStation station){return false;}
}''',
    'buildcraft/robotics/internal/legacy/robots/AIRobot': '''package buildcraft.robotics.internal.legacy.robots;
public class AIRobot {protected final EntityRobotBase robot;
 public AIRobot(EntityRobotBase r){robot=r;}
 public void start(){}public void terminate(){}public boolean success(){return true;}
 protected void setSuccess(boolean b){}
}''',
    'buildcraft/robotics/internal/legacy/robots/DockingStation': '''package buildcraft.robotics.internal.legacy.robots;
import net.minecraft.core.Direction;
public class DockingStation {
 public final int x,y,z;public boolean loaded=true,power=true;public long taking=-1;
 public DockingStation(int x,int y,int z){this.x=x;this.y=y;this.z=z;}
 public int x(){return x;}public int y(){return y;}public int z(){return z;}
 public Direction side(){return Direction.UP;}
 public boolean isTaken(){return taking!=-1;}public long robotIdTaking(){return taking;}
 public boolean isInitialized(){return loaded;}public boolean providesPower(){return power;}
}''',
    'buildcraft/robotics/internal/legacy/robots/IRobotRegistry': '''package buildcraft.robotics.internal.legacy.robots;
import java.util.*;
public class IRobotRegistry {
 public final List<DockingStation> stations=new ArrayList<>();
 public List<DockingStation> getStations(){return stations;}
}''',
    'buildcraft/robotics/internal/legacy/robots/EntityRobotBase': '''package buildcraft.robotics.internal.legacy.robots;
import net.minecraft.world.level.Level;
public class EntityRobotBase {
 public static final long NULL_ROBOT_ID=-1;
 public static final int SHUTDOWN_ENERGY=0,SAFETY_ENERGY=20000;
 public final long id; public int energy; public final Level world;
 public final IRobotRegistry registry;public DockingStation dock;
 public EntityRobotBase(long id,int energy,Level world,IRobotRegistry registry){
  this.id=id;this.energy=energy;this.world=world;this.registry=registry;}
 public Level level(){return world;}public long getRobotId(){return id;}
 public int getEnergy(){return energy;}public IRobotRegistry getRegistry(){return registry;}
 public DockingStation getDockingStation(){return dock;}
 public double getX(){return 0;}public double getY(){return 64;}public double getZ(){return 0;}
}''',
    'fixture/ChargingDockDispatcherFixture': '''package fixture;
import java.util.*;
import net.minecraft.world.level.Level;
import buildcraft.robotics.ai.AIRobotSearchStation;
import buildcraft.robotics.IStationFilter;
import buildcraft.robotics.internal.legacy.robots.*;
import buildcraft.robotics.scheduler.ChargingDockQueue;
import buildcraft.robotics.statements.ActionStationForbidRobot;
public class ChargingDockDispatcherFixture {
 static int checks;
 static void ok(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 static IStationFilter charging = new IStationFilter(){
  public boolean matches(DockingStation s){return s.providesPower();}
  public boolean prefersProcessorLinkedStations(){return true;}
 };
 static AIRobotSearchStation search(EntityRobotBase r,IStationFilter filter){
  var s=new AIRobotSearchStation(r,filter,null);s.start();return s;
 }
 public static void main(String[]args){
  // Pure coordinator: emergency priority, no double allocation and explicit cancellation.
  var q=new ChargingDockQueue<String>();
  q.register(1,0,10,Set.of("dock"));
  q.register(2,2,11,Set.of("dock"));
  ok(!q.maySelect(1,"dock",11),"ordinary robot stole emergency dock");
  ok(q.maySelect(2,"dock",11),"critical robot could not charge");
  q.cancel(2);ok(q.maySelect(1,"dock",11),"cancel left phantom waiter");
  q.register(3,2,12,Set.of("other"));
  ok(q.maySelect(1,"dock",12),"unrelated dock affected arbitration");
  // Replacing eligibility must also update the inverted station index.
  q.register(3,2,13,Set.of("dock"));
  ok(!q.maySelect(1,"dock",13),"updated station preference not indexed");
  q.register(3,2,14,Set.of("other"));
  ok(q.maySelect(1,"dock",14),"old preference remained in station index");
  ok(q.waitingCount(100)==0,"stale requests were never expired");
  q.register(5,0,100,Set.of("dock"));
  q.register(4,0,100,Set.of("dock"));
  ok(q.maySelect(4,"dock",100),"ties must be deterministic");
  ok(!q.maySelect(5,"dock",100),"equal-priority waiter jumped ahead");
  q.cancel(4);q.cancel(5);
  // Aging: an old normal waiter eventually beats a freshly arrived emergency.
  q.register(5,0,120,Set.of("dock"));
  for(long time=140;time<=1340;time+=20)q.register(5,0,time,Set.of("dock"));
  q.register(6,2,1340,Set.of("dock"));
  ok(q.maySelect(5,"dock",1340),"normal waiter starved despite aging");
  ok(!q.maySelect(6,"dock",1340),"new emergency stole a long-waited dock");
  q.cancel(5);q.cancel(6);
  // Negative updates revoke eligibility rather than keeping stale permissions.
  q.register(7,2,1400,Set.of("dock"));
  q.register(7,2,1401,Set.of());
  ok(q.waitingCount(1401)==0,"empty allowed-station set retained old rights");

  // Actual production AIRobotSearchStation integration; no load required.
  Level world=new Level();IRobotRegistry registry=new IRobotRegistry();
  DockingStation one=new DockingStation(4,64,4);one.taking=99;
  registry.stations.add(one);
  var ordinary=new EntityRobotBase(31,30000,world,registry);
  var low=new EntityRobotBase(32,1000,world,registry);
  world.ticks=10;
  ok(search(ordinary,charging).targetStation==null,"occupied station wrongly offered");
  ok(search(low,charging).targetStation==null,"critical robot bypassed occupancy");
  one.taking=-1;world.ticks=11;
  var blocked=search(ordinary,charging);
  ok(blocked.targetStation==null && blocked.hasOccupiedMatch(),"queued priority was ignored");
  ok(search(low,charging).targetStation==one,"older ordinary request blocked urgent robot");
  one.taking=low.id;
  ok(search(ordinary,charging).targetStation==null,"physical station owner was ignored");
  one.taking=-1;
  AIRobotSearchStation.cancelChargingWait(low);
  ok(search(ordinary,charging).targetStation==one,"ordinary robot did not receive free station");
  // An inaccessible station must never enter the demand set.
  one.taking=99;
  ActionStationForbidRobot.forbidden.add(one);
  ok(search(low,charging).targetStation==null,"forbidden station was assigned");
  ActionStationForbidRobot.forbidden.clear();
  one.taking=-1;
  // Noncharging dock trips are unaffected by recharge coordination.
  var custom=new IStationFilter(){public boolean matches(DockingStation s){return true;}};
  ok(search(low,custom).targetStation==one,"regular station AI was altered");
  // A second dimension has an independent queue even for identical coords and IDs.
  Level world2=new Level();IRobotRegistry registry2=new IRobotRegistry();
  DockingStation other=new DockingStation(4,64,4);registry2.stations.add(other);
  var second=new EntityRobotBase(31,30000,world2,registry2);
  ok(search(second,charging).targetStation==other,"requests leaked between dimensions");
  // A robot already physically docked is not displaced by another waiter's claim.
  other.taking=second.id;second.dock=other;
  ok(search(second,charging).targetStation==other,"docked robot was displaced");
  System.out.println("PASS: "+checks+" production queue and station search assertions");
 }
}''',
}


class ChargingDockDispatcherTests(unittest.TestCase):
    @unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'Java JDK unavailable')
    def test_arbitration_and_production_search_in_jvm(self):
        with tempfile.TemporaryDirectory(prefix='bc-charge-dock-') as tmp:
            root = Path(tmp)
            sources = []
            for logical, java in STUBS.items():
                target = root / (logical + '.java')
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(java, encoding='utf8')
                sources.append(str(target))
            for rel in ('buildcraft/robotics/scheduler/ChargingDockQueue.java',
                        'buildcraft/robotics/ai/AIRobotSearchStation.java'):
                source = SOURCE_ROOT / rel
                self.assertTrue(source.is_file(), str(source))
                sources.append(str(source))
            compiled = subprocess.run(['javac', '--release', '17', '-d', str(root / 'classes'), *sources],
                                      capture_output=True, text=True, timeout=45)
            self.assertEqual(compiled.returncode, 0, compiled.stdout + compiled.stderr)
            tested = subprocess.run(['java', '-cp', str(root / 'classes'), 'fixture.ChargingDockDispatcherFixture'],
                                    capture_output=True, text=True, timeout=15)
            self.assertEqual(tested.returncode, 0, tested.stdout + tested.stderr)
            self.assertIn('PASS:', tested.stdout)

    def test_cancellation_on_unload_and_aborted_recharge(self):
        search = (SOURCE_ROOT / 'buildcraft/robotics/ai/AIRobotSearchAndGotoStation.java').read_text()
        recharge = (SOURCE_ROOT / 'buildcraft/robotics/ai/AIRobotRecharge.java').read_text()
        for text in (search, recharge):
            self.assertIn('public void onUnload()', text)
            self.assertIn('cancelChargingWait(robot)', text)
            self.assertIn('public void end()', text)
        self.assertIn('weak', (SOURCE_ROOT / 'buildcraft/robotics/ai/AIRobotSearchStation.java')
                      .read_text().lower())


if __name__ == '__main__':
    unittest.main()
