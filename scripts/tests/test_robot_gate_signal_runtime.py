#!/usr/bin/env python3
"""Compile the production gate scheduler and verify that a held Gate signal cannot bounce robots."""
from __future__ import annotations

import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from test_robot_home_return_runtime import STUBS as HOME_STUBS

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / 'source-shared/src/main/java'
STUBS = dict(HOME_STUBS)

STUBS['buildcraft/robotics/internal/legacy/robots/AIRobot.java'] = '''package buildcraft.robotics.internal.legacy.robots;
import net.minecraft.nbt.CompoundTag;
public class AIRobot {
 public final EntityRobotBase robot;
 private AIRobot parent,delegate;private boolean success=true,terminated;
 public AIRobot(EntityRobotBase r){robot=r;}
 public void start(){}public void update(){}public void preempt(AIRobot ai){}public void end(){}
 public void delegateAIEnded(AIRobot ai){}public void delegateAIAborted(AIRobot ai){}
 public int getEnergyCost(){return 1;}
 public void cycle(){preempt(delegate);if(delegate!=null)delegate.cycle();else update();}
 public boolean success(){return success;}protected void setSuccess(boolean b){success=b;}
 public boolean canLoadFromNBT(){return false;}
 public void writeSelfToNBT(CompoundTag nbt){}public void loadSelfFromNBT(CompoundTag nbt){}
 public void writeToNBT(CompoundTag nbt){}
 public static AIRobot loadAI(CompoundTag nbt,EntityRobotBase robot){return null;}
 public AIRobot getDelegateAI(){return delegate;}
 public AIRobot getActiveAI(){return delegate==null?this:delegate.getActiveAI();}
 public void startDelegateAI(AIRobot ai){abortDelegateAI();delegate=ai;ai.parent=this;ai.start();}
 public void abortDelegateAI(){if(delegate!=null)delegate.abort();}
 public void abort(){abortDelegateAI();end();if(parent!=null){parent.delegate=null;parent.delegateAIAborted(this);}}
 public void terminate(){if(terminated)return;terminated=true;abortDelegateAI();end();
   if(parent!=null){parent.delegate=null;parent.delegateAIEnded(this);}}
 public boolean isTerminated(){return terminated;}
}'''

STUBS['buildcraft/robotics/internal/legacy/robots/EntityRobotBase.java'] = '''package buildcraft.robotics.internal.legacy.robots;
import buildcraft.robotics.ai.AIRobotMain;
import buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;import net.minecraft.world.phys.Vec3;
public class EntityRobotBase {
 public static final long NULL_ROBOT_ID=-1;
 public static final int SAFETY_ENERGY=20000,SHUTDOWN_ENERGY=0,MAX_ENERGY=100000;
 public final Level level=new Level();
 public Level level(){return level;}
 public final IRobotRegistry registry=new IRobotRegistry();
 public final RedstoneBoardRobot board=new RedstoneBoardRobot(this);
 public AIRobotMain main;
 public double x,y,z;public Vec3 velocity=Vec3.ZERO;public DockingStation linked,docked;
 public int gateCommands;
 public EntityRobotBase(double x,double y,double z){this.x=x;this.y=y;this.z=z;}
 public int getEnergy(){return 100000;}
 public RedstoneBoardRobot getBoard(){return board;}
 public double getX(){return x;}public double getY(){return y;}public double getZ(){return z;}
 public void setPos(double x,double y,double z){this.x=x;this.y=y;this.z=z;}
 public BlockPos blockPosition(){return BlockPos.containing(x,y,z);}
 public Vec3 getDeltaMovement(){return velocity;}public void setDeltaMovement(Vec3 v){velocity=v;}
 public boolean isMoving(){return velocity.length()>0.0000001;}
 public double distanceToSqr(double x,double y,double z){double a=this.x-x,b=this.y-y,c=this.z-z;return a*a+b*b+c*c;}
 public void aimItemAt(int x,int y,int z){}public void aimItemAt(float yaw,float pitch){}
 public float getAimYaw(){return 0;}public float getAimPitch(){return 0;}
 public DockingStation getLinkedStation(){return linked;}
 public DockingStation getDockingStation(){return docked;}
 public IRobotRegistry getRegistry(){return registry;}
 public long getRobotId(){return 4L;}
 public void dock(DockingStation station){docked=station;velocity=Vec3.ZERO;}
 public void undock(){docked=null;}
 public void setMainAIOverride(AIRobot ai){gateCommands++;main.setOverridingAI(ai);}
}'''

STUBS['buildcraft/robotics/internal/legacy/robots/DockingStation.java'] = STUBS['buildcraft/robotics/internal/legacy/robots/DockingStation.java'].replace(
    ' public boolean isInitialized()', ' public boolean providesPower(){return false;}\n public boolean isInitialized()'
)
STUBS['buildcraft/robotics/RobotTrace.java'] = STUBS['buildcraft/robotics/RobotTrace.java'].replace(
    ' public static boolean enabled()',
    ' public static String name(buildcraft.robotics.internal.legacy.robots.AIRobot ai){return ai==null?"none":ai.getClass().getSimpleName();}\n public static boolean enabled()'
)
STUBS['net/minecraft/nbt/CompoundTag.java'] = STUBS['net/minecraft/nbt/CompoundTag.java'].replace(
    ' public void put(String k, CompoundTag t){v.put(k,t);}',
    ' public Object get(String k){return v.get(k);}\n public void put(String k, CompoundTag t){v.put(k,t);}'
)
STUBS['buildcraft/robotics/internal/legacy/boards/RedstoneBoardRobot.java'] = '''package buildcraft.robotics.internal.legacy.boards;
import buildcraft.robotics.internal.legacy.robots.*;
public class RedstoneBoardRobot extends AIRobot {
 public int workStarts;
 public RedstoneBoardRobot(EntityRobotBase r){super(r);}
 public AIRobot createWorkAI(){workStarts++;return new AIRobot(robot);}
 public RedstoneBoardRobotNBT getNBTHandler(){return new RedstoneBoardRobotNBT();}
}'''
STUBS['buildcraft/robotics/internal/legacy/boards/RedstoneBoardRobotNBT.java'] = '''package buildcraft.robotics.internal.legacy.boards;
public class RedstoneBoardRobotNBT{public String getID(){return "boardRobotMiner";}}
'''
STUBS['buildcraft/lib/balance/BalanceConfig.java'] = '''package buildcraft.lib.balance;
public class BalanceConfig{public static double robot(String name,String key,double fallback){return fallback;}}
'''
STUBS['buildcraft/robotics/internal/api2/ApiTaskAI.java'] = '''package buildcraft.robotics.internal.api2;
import buildcraft.robotics.internal.legacy.robots.*;
public class ApiTaskAI extends AIRobot{public ApiTaskAI(EntityRobotBase r){super(r);}}
'''
for name in ('AIRobotRecharge','AIRobotGotoSleep','AIRobotSleep','AIRobotReturnToLostStation','AIRobotShutdown'):
    STUBS[f'buildcraft/robotics/ai/{name}.java'] = f'''package buildcraft.robotics.ai;
import buildcraft.robotics.internal.legacy.robots.*;
public class {name} extends AIRobot{{public {name}(EntityRobotBase r){{super(r);}}}}
'''
STUBS['buildcraft/robotics/ai/AIRobotGotoBlock.java'] = '''package buildcraft.robotics.ai;
import buildcraft.robotics.internal.legacy.robots.*;
public class AIRobotGotoBlock extends AIRobot {
 public AIRobotGotoBlock(EntityRobotBase r,int x,int y,int z,double range){super(r);}
 public AIRobotGotoBlock forStationReturn(int x,int y,int z){return this;}
}'''
STUBS['buildcraft/robotics/ai/AIRobotStraightMoveTo.java'] = '''package buildcraft.robotics.ai;
import buildcraft.robotics.internal.legacy.robots.*;
public class AIRobotStraightMoveTo extends AIRobot {
 public AIRobotStraightMoveTo(EntityRobotBase r,double x,double y,double z){super(r);}
}'''

FIXTURE = '''package fixture;
import buildcraft.robotics.ai.*;
import buildcraft.robotics.internal.legacy.robots.*;
import buildcraft.robotics.statements.ActionRobotGotoStation;
import buildcraft.robotics.plug.RobotStationPluggable;
import buildcraft.lib.internal.statement.*;
import buildcraft.transport.internal.pipe.IPipeHolder;
import net.minecraft.core.*;
import net.minecraft.world.level.Level;
public class GateSignalFixture {
 static void check(boolean ok,String description){if(!ok)throw new AssertionError(description);}
 public static void main(String[] args){
  DockingStation dock=new DockingStation(8,0,0,Direction.UP);
  EntityRobotBase r=new EntityRobotBase(8.5,1,0.5);
  r.linked=dock;r.docked=dock;r.registry.station=dock;dock.taker=r;
  r.main=new AIRobotMain(r);
  ActionRobotGotoStation gate=new ActionRobotGotoStation();
  IPipeHolder holder=new IPipeHolder(){
   public Object getPluggable(Direction dir){return dir==Direction.UP?new RobotStationPluggable(dock):null;}
   public Level getPipeWorld(){return r.level;}
  };
  IStatementContainer c=()->holder;
  for(int i=0;i<100;i++){
   gate.actionActivate(c,new IStatementParameter[0]);
   r.main.cycle();
   check(r.main.getDelegateAI() instanceof AIRobotGoAndLinkToDock,
    "Continuous active Gate restarted the worker at iteration "+i);
   AIRobotGoAndLinkToDock ai=(AIRobotGoAndLinkToDock)r.main.getDelegateAI();
   check(ai.isHoldingAtStation(),"Gate did not hold the already docked robot");
   check(ai.getEnergyCost()==0,"Dock hold used energy");
   check(r.docked==dock,"Gate hold undocked robot");
   check(r.board.workStarts==0,"Worker was started despite active Gate");
   r.level.time++;
  }
  for(int i=0;i<3;i++){r.main.cycle();r.level.time++;}
  check(r.main.getDelegateAI() == null || !(r.main.getDelegateAI() instanceof AIRobotGoAndLinkToDock),
   "Gate release never completed after signal stopped");
  r.main.cycle();
  check(r.board.workStarts>0,"Worker did not resume after Gate became inactive");
  check(r.main.getDelegateAI()!=null && !(r.main.getDelegateAI() instanceof AIRobotGoAndLinkToDock),
   "Old Gate override restarted after it had been released");
  System.out.println("PASS: production Gate scheduler holds docked worker, releases on inactive signal, resumes board");
 }
}'''

class RobotGateSignalRuntime(unittest.TestCase):
    @unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'Java JDK unavailable')
    def test_production_scheduler_holds_continuous_gate(self):
        with tempfile.TemporaryDirectory(prefix='bc-gate-signal-') as tmp:
            root = Path(tmp)
            sources = []
            for rel, data in STUBS.items():
                dst = root / rel
                dst.parent.mkdir(parents=True,exist_ok=True)
                dst.write_text(data,encoding='utf-8')
                sources.append(str(dst))
            for rel in (
                'buildcraft/robotics/ai/AIRobotMain.java',
                'buildcraft/robotics/ai/AIRobotGoAndLinkToDock.java',
                'buildcraft/robotics/statements/ActionRobotGotoStation.java',
            ):
                dst = SRC / rel
                self.assertTrue(dst.is_file(), str(dst))
                sources.append(str(dst))
            target = root / 'fixture/GateSignalFixture.java'
            target.parent.mkdir(parents=True,exist_ok=True)
            target.write_text(FIXTURE,encoding='utf-8')
            sources.append(str(target))
            compiled = subprocess.run(['javac','-encoding','UTF-8','-d',str(root/'classes'),*sources],
                                       capture_output=True,text=True,timeout=50)
            self.assertEqual(compiled.returncode,0,compiled.stderr[-10000:])
            result = subprocess.run(['java','-cp',str(root/'classes'),'fixture.GateSignalFixture'],
                                    capture_output=True,text=True,timeout=15)
            self.assertEqual(result.returncode,0,result.stderr[-10000:])
            self.assertIn('PASS:',result.stdout)
            print(result.stdout.strip(),flush=True)

if __name__ == '__main__':
    unittest.main()
