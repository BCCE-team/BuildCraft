"""Stage 19: conservative, loader-neutral robot trip energy preflight.

Compile the production budget and adapter; exercise the *actual* scheduler in
fake Minecraft worlds with charger reservations, distant deposits, and recharge.
"""
import subprocess
import tempfile
import unittest
from pathlib import Path

from scripts.source_preprocessor import preprocess_text
from scripts.tests.test_robot_processor_scheduler_runtime import ROOT, SOURCES, STUBS

BUDGET = ROOT / 'source-shared/src/main/java/buildcraft/robotics/scheduler/RobotWorkEnergyBudget.java'
ADAPTER = ROOT / 'source-shared/src/main/java/buildcraft/robotics/scheduler/RobotWorkEnergyPlanner.java'

CORE_FIXTURE = r'''import java.util.*;
import buildcraft.robotics.scheduler.RobotWorkEnergyBudget;
public class RobotEnergyMathFixture {
 static void check(boolean good,String msg){if(!good)throw new AssertionError(msg);}
 static RobotWorkEnergyBudget.Point p(double x,double y,double z){return new RobotWorkEnergyBudget.Point(x,y,z);}
 static RobotWorkEnergyBudget.Charger dock(double x, boolean free){return new RobotWorkEnergyBudget.Charger(p(x,64,0),free);}
 public static void main(String[] args) {
  var home=List.of(dock(0,true));
  var near=RobotWorkEnergyBudget.estimate(p(0,64,0),p(2,64,0),home,24000,20000,1,1,4);
  var far=RobotWorkEnergyBudget.estimate(p(0,64,0),p(80,64,0),home,24000,20000,1,1,4);
  check(near.affordable(),"Nearby ore incorrectly denied");
  check(!far.affordable(),"Distant ore allowed without return budget");
  check(near.required()<far.required(),"Travel did not affect energy");
  var almostEmpty=RobotWorkEnergyBudget.estimate(p(0,64,0),p(5,64,0),home,21000,20000,1,1,1);
  check(!almostEmpty.affordable(),"A robot with no return reserve was dispatched");
  var rich=RobotWorkEnergyBudget.estimate(p(0,64,0),p(5,64,0),home,100000,20000,1,1,2048);
  check(rich.affordable() && rich.estimatedBlocks()<=4,"A huge deposit must not require one full-battery trip");
  var expensive=RobotWorkEnergyBudget.estimate(p(0,64,0),p(5,64,0),home,100000,20000,0.5,3,1);
  check(expensive.required()>rich.required(),"Balance multipliers ignored");
  var two=RobotWorkEnergyBudget.estimate(p(0,64,0),p(80,64,0),List.of(dock(0,true),dock(78,true)),24000,20000,1,1,1);
  check(two.required()<far.required(),"Nearest destination charger ignored");
  var occupied=RobotWorkEnergyBudget.estimate(p(0,64,0),p(5,64,0),List.of(dock(0,false)),100000,20000,1,1,1);
  var free=RobotWorkEnergyBudget.estimate(p(0,64,0),p(5,64,0),home,100000,20000,1,1,1);
  check(occupied.required()>free.required(),"Dock waiting margin ignored");
  var noDock=RobotWorkEnergyBudget.estimate(p(0,64,0),p(80,64,0),List.of(),40000,20000,1,1,1);
  check(noDock.affordable() && !noDock.chargerKnown(),"Modpack with external charging became deadlocked");
  var lowBattery=RobotWorkEnergyBudget.estimate(p(0,64,0),p(5,64,0),home,100,20000,1,1,1);
  check(!lowBattery.affordable(),"Emergency reserve ignored");
  System.out.println("PASS: one-trip budget, large vein, modifiers, distant chargers, external charging, occupied dock");
 }
}'''

WORLD_FIXTURE = r'''package fixture;
import java.util.*;
import buildcraft.robotics.scheduler.*;
import buildcraft.robotics.internal.legacy.robots.*;
import buildcraft.robotics.tile.TileRobotProcessor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
public class EnergyDispatchRuntime {
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 public static void main(String[] args){
  ServerLevel level=new ServerLevel();
  var low=new EntityRobotBase(level,1,new BlockPos(0,65,0));
  low.energy=22100; // enough to start a search, but not a round trip plus one break
  low.setBoardId("buildcraft:miner");
  var powered=new DockingStation(0,65,0,true);
  low.home=powered;
  var registry=new IRobotRegistry(){
   public EntityRobotBase getLoadedRobot(long id){return id==1?low:null;}
   public boolean isTaken(ResourceIdBlock r){return false;}
   public long robotIdTaking(ResourceIdBlock r){return 0;}
   public java.util.Collection<DockingStation> getStations(){return java.util.List.of(powered);}
  };
  RobotManager.registryProvider=l->registry;
  var panel=new TileRobotProcessor(level,new BlockPos(0,65,0));
  panel.linked.add(1L);
  var scheduler=new RobotProcessorWorkScheduler(panel);
  level.time++;scheduler.tick(level);
  var nearby=new BlockPos(3,65,0);
  RobotProcessorWorkScheduler.worldBlockChanged(level,nearby);
  for(int t=0;t<22;t++){level.time++;scheduler.tick(level);}
  check(scheduler.queueCount(1)==0,"Near ore was assigned with insufficient reserve");
  check(RobotProcessorWorkScheduler.shouldRechargeBeforeWork(low),"Energy shortage did not request early charging");
  check(scheduler.cachedCandidates()>0,"Energy shortage removed known ore from catalog");
  low.energy=48000;
  for(int t=0;t<20;t++){level.time++;scheduler.tick(level);}
  check(scheduler.queueCount(1)>0,"Charged robot did not receive preserved ore job");
  check(!RobotProcessorWorkScheduler.shouldRechargeBeforeWork(low),"Charge did not clear wait flag");
  var targets=RobotProcessorWorkScheduler.queuedMiningTargets(low);
  check(targets.contains(nearby),"Previously rejected ore not offered after charging");
  // Drop battery before starting the following trip. Keep the prepared task,
  // but do not let A* run while the charging AI takes over.
  low.energy=21800;
  targets=RobotProcessorWorkScheduler.queuedMiningTargets(low);
  check(targets.isEmpty(),"Undercharged robot still handed ore to A*");
  check(scheduler.queueCount(1)>0,"Low energy erased a queued mining intention");
  check(RobotProcessorWorkScheduler.shouldRechargeBeforeWork(low),"Current queue did not set recharge recommendation");
  scheduler.onRemoved();
  check(!RobotProcessorWorkScheduler.shouldRechargeBeforeWork(low),"Unlinked panel left a recharge request");
  System.out.println("PASS: undercharged miner, charge recovery, queue retention, pre-A* gate and cleanup");
 }
}'''


def world_stubs():
    stubs=dict(STUBS)
    stubs.pop('buildcraft/robotics/scheduler/RobotWorkEnergyPlanner.java')
    stubs['buildcraft/robotics/internal/legacy/robots/DockingStation.java']='''package buildcraft.robotics.internal.legacy.robots;
    public class DockingStation {
      private final int x,y,z;private final boolean powered;
      public DockingStation(int x,int y,int z,boolean powered){this.x=x;this.y=y;this.z=z;this.powered=powered;}
      public int x(){return x;}public int y(){return y;}public int z(){return z;}
      public net.minecraft.core.Direction side(){return net.minecraft.core.Direction.NORTH;}
      public boolean providesPower(){return powered;}
      public boolean isInitialized(){return true;}
      public boolean isTaken(){return false;}
      public long robotIdTaking(){return 0;}
    }'''
    stubs['buildcraft/robotics/internal/legacy/boards/RedstoneBoardRobot.java']='''package buildcraft.robotics.internal.legacy.boards;
    public class RedstoneBoardRobot extends buildcraft.robotics.internal.legacy.robots.AIRobot {
      public static class NBT {private final String id;public NBT(String id){this.id=id;}public String getID(){return id;}}
      public NBT getNBTHandler(){return new NBT("lumberjack");}
    }'''
    board='buildcraft/robotics/boards/BoardRobotGenericSearchBlock.java'
    stubs[board]=stubs[board].replace('public record NBT(String id){ public String getID(){return id;} }',
            'public static class NBT extends buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobot.NBT {\n'
            ' public NBT(String id){super(id);}public String id(){return getID();} }')
    stubs[board]=stubs[board].replace('private final NBT nbt = new NBT("lumberjack");',
            'private NBT nbt = new NBT("lumberjack");\n public void setBoardId(String id){nbt=new NBT(id);}')
    stubs[board]=stubs[board].replace(' public boolean processorAcceptsHints(){return acceptsProcessorCandidateHints();}',
            ' public boolean processorSupportsQueue(){return true;}\n'
            ' public boolean processorAcceptsHints(){return acceptsProcessorCandidateHints();}')
    entity='buildcraft/robotics/internal/legacy/robots/EntityRobotBase.java'
    stubs[entity]=stubs[entity].replace(' public DockingStation getLinkedStation(){return null;}',
            ' public DockingStation home;\n public DockingStation getLinkedStation(){return home;}')
    stubs[entity]=stubs[entity].replace(' public long getRobotId(){return id;}',
            ' public void setBoardId(String id){board.setBoardId(id);}\n public long getRobotId(){return id;}')
    stubs['buildcraft/lib/balance/BalanceConfig.java']='''package buildcraft.lib.balance;
    public final class BalanceConfig {
      public static double speed=1,cost=1;
      public static double robot(String id,String prop,double value){
       if(prop.equals("movement_speed_multiplier"))return speed;
       if(prop.equals("energy_cost_multiplier"))return cost;
       return value;
      }
    }'''
    # Small world: all stone except the ore at x=3, and a loaded charger at x=0.
    stubs['net/minecraft/world/level/Level.java']=stubs['net/minecraft/world/level/Level.java'].replace(
            'public int blockReads; public BlockPos target=new BlockPos(1,64,1);',
            'public int blockReads; public BlockPos target=new BlockPos(3,65,0);')
    return stubs


class EnergyPlannerTests(unittest.TestCase):
    @staticmethod
    def compile_run(sources, classname, directory):
        files=[]
        for path,contents in sources.items():
            file=directory/path
            file.parent.mkdir(parents=True,exist_ok=True)
            file.write_text(contents)
            files.append(str(file))
        classes=directory/'classes'
        classes.mkdir()
        comp=subprocess.run(['javac','--release','17','-d',str(classes),*files],
                            capture_output=True,text=True,timeout=55)
        if comp.returncode:return comp.returncode,comp.stderr
        run=subprocess.run(['java','-cp',str(classes),classname],
                           capture_output=True,text=True,timeout=20)
        return run.returncode,run.stdout+run.stderr

    def test_pure_energy_math(self):
        with tempfile.TemporaryDirectory(prefix='bc-energy-core-') as tmp:
            code,log=self.compile_run({
               'buildcraft/robotics/scheduler/RobotWorkEnergyBudget.java':BUDGET.read_text(),
               'RobotEnergyMathFixture.java':CORE_FIXTURE,
            },'RobotEnergyMathFixture',Path(tmp))
            self.assertEqual(code,0,log)

    def test_real_scheduler_with_powered_dock_on_three_loaders(self):
        for version,family,loader in [('1.19.2','old','forge'),
                                      ('1.20.1','old','forge'),
                                      ('1.21.11','1.21.X','neoforge'),
                                      ('26.2','26.X','neoforge'),
                                      ('26.3','26.X','fabric')]:
            with self.subTest(version=version,loader=loader),tempfile.TemporaryDirectory(prefix='bc-energy-robot-') as tmp:
                sources=world_stubs()
                for source in SOURCES:
                    rel=source.removeprefix('source-shared/src/main/java/')
                    sources[rel]=preprocess_text((ROOT/source).read_text(),
                      minecraft=version,family=family,platform=loader,source=source)
                source=ADAPTER.relative_to(ROOT)
                rel=str(ADAPTER.relative_to(ROOT/'source-shared/src/main/java'))
                sources[rel]=preprocess_text(ADAPTER.read_text(),
                      minecraft=version,family=family,platform=loader,source=str(source))
                sources['fixture/EnergyDispatchRuntime.java']=WORLD_FIXTURE
                code,log=self.compile_run(sources,'fixture.EnergyDispatchRuntime',Path(tmp))
                self.assertEqual(code,0,log)

    def test_production_main_ai_recharge_gate_and_active_movement(self):
        from scripts.tests.robot_recharge_fixture import execute
        shared = ROOT / 'source-shared/src/main/java/buildcraft/robotics'
        result = execute(shared/'ai/AIRobotMain.java', shared/'ai/AIRobotRecharge.java',
                         shared/'ai/AIRobotSleep.java', shared/'ai/AIRobotSearchStation.java',
                         shared/'ai/AIRobotSearchAndGotoStation.java',
                         queue=shared/'scheduler/ChargingDockQueue.java')
        self.assertIn('40 assertions PASS', result)

    def test_recharge_handoff_keeps_native_ai_in_control(self):
        source=(ROOT/'source-shared/src/main/java/buildcraft/robotics/ai/AIRobotMain.java').read_text()
        self.assertIn('RobotProcessorWorkScheduler.shouldRechargeBeforeWork(robot)',source)
        self.assertIn('!dockedAtCharger',source)
        self.assertIn('overridingAI == null',source)
        self.assertIn('new AIRobotRecharge(robot)',source)
        ai=(ROOT/'source-shared/src/main/java/buildcraft/robotics/ai/AIRobotSearchNearestBlock.java').read_text()
        self.assertIn('RobotProcessorWorkScheduler.shouldRechargeBeforeWork(robot)',ai)
        self.assertIn('return sharedRoutePaused || watchProcessorQueue',ai)


if __name__ == '__main__':
    unittest.main()
