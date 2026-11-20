"""JVM regressions for two-sided logistics planning and replay-safe unloading dock leases."""
import tempfile
import unittest
from pathlib import Path
from scripts.tests.test_robot_processor_stage10_logistics import (
    ROOT, QUEUE, ROUTES, DISPATCH, STUBS, LogisticsSchedulerTests,
)
from scripts.source_preprocessor import preprocess_text

ROUTE_LEASES_CONTRACT = r'''
import buildcraft.robotics.scheduler.RobotLogisticsRouteLeases;
class RouteLeaseContract {
 static void check(boolean good,String message){if(!good) throw new AssertionError(message);}
 public static void main(String[] args) {
  var leases=new RobotLogisticsRouteLeases();
  var a=new RobotLogisticsRouteLeases.Dock(22,1);
  var b=new RobotLogisticsRouteLeases.Dock(23,1);
  var first=leases.reserve(a,91,1,10,120);
  check(first!=null && leases.owned(a,1,first.token(),10),"first reservation missing");
  check(leases.reserve(a,91,2,10,120)==null,"same receiver assigned twice");
  check(leases.reserve(b,91,1,10,120)==null,"robot assigned two destinations");
  check(leases.reserve(b,91,2,10,120)!=null,"independent destination blocked");
  check(!leases.release(a,1,first.token()+1),"outdated token released active route");
  check(leases.renewRobot(91,1,100,120),"loaded robot did not extend its route");
  leases.expire(135);
  check(leases.owned(a,1,first.token(),135),"renewed reservation expired early");
  leases.expire(221);
  check(!leases.owned(a,1,first.token(),221),"expired route still active");
  var replacement=leases.reserve(a,91,3,221,120);
  check(replacement!=null && replacement.token()!=first.token(),"generation token recycled");
  check(!leases.release(a,1,first.token()),"stale robot freed a new route");
  leases.clearPanel(91);
  check(leases.size()==0,"panel teardown leaked destination reservations");
  var finalClaim=leases.reserve(b,93,4,300,100);
  check(finalClaim!=null,"reserve after cleanup failed");
  leases.clearRobot(4);
  check(leases.size()==0,"unloaded robot kept route");
  System.out.println("PASS route leases and fencing");
 }
}
'''

LOADED_ROUTE_TEST = r'''
package fixture;
import java.util.*;
import buildcraft.robotics.scheduler.*;
import buildcraft.robotics.internal.legacy.robots.*;
import buildcraft.robotics.boards.*;
import buildcraft.robotics.tile.TileRobotProcessor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
public class TwoSidedLogisticsRuntime {
 static void check(boolean good,String message){if(!good) throw new AssertionError(message);}
 public static void main(String[] args) {
  ServerLevel world=new ServerLevel();IRobotRegistry registry=new IRobotRegistry();
  RobotManager.registryProvider=ignored->registry;
  DockingStation home=new DockingStation(0,0);
  DockingStation source=new DockingStation(8,0);source.itemCount=32;
  DockingStation receiver=new DockingStation(16,0);
  DockingStation backup=new DockingStation(24,0);
  registry.stations.addAll(List.of(home,source,receiver,backup));
  EntityRobotBase robot=new EntityRobotBase(world,42,new BoardRobotCarrier(),4);
  robot.home=home;robot.registry=registry;registry.robots.put(42L,robot);
  TileRobotProcessor panel=new TileRobotProcessor(world,new BlockPos(4,65,3));panel.ids.add(42L);
  RobotLogisticsScheduler scheduler=new RobotLogisticsScheduler(panel);
  world.time=1;scheduler.tick(world);
  check(scheduler.queueCount(42)==0,"planned a shipment with no receiver");
  receiver.acceptsItems=true;
  world.time=22;scheduler.tick(world);
  check(scheduler.queueCount(42)>=1,"receiver availability was not rescanned");
  var dispatch=RobotLogisticsScheduler.acquire(robot);
  check(dispatch!=null && dispatch.destination()==receiver && dispatch.itemPreview().getCount()>0,
        "no two-sided route or cargo preview");
  check(RobotLogisticsScheduler.ownsDestination(robot,receiver,dispatch.destinationToken()),
        "receiver lease missing");
  // The gate is removed after source selection and before unloading.
  receiver.acceptsItems=false;
  robot.cargo=true;
  RobotLogisticsScheduler.finishDestination(robot,receiver,dispatch.destinationToken());
  check(RobotLogisticsScheduler.recoverDestination(robot)==null,
        "cargo routed into a receiver that stopped accepting it");
  backup.acceptsItems=true;
  var recovered=RobotLogisticsScheduler.recoverDestination(robot);
  check(recovered!=null && recovered.station()==backup,
        "loaded cargo could not recover onto a newly available destination");
  check(!RobotLogisticsScheduler.ownsDestination(robot,receiver,dispatch.destinationToken()),
        "old route acquired back a new destination");
  RobotLogisticsScheduler.finishDestination(robot,backup,recovered.token());
  check(!RobotLogisticsScheduler.ownsDestination(robot,backup,recovered.token()),
        "completed route retained its lease");
  scheduler.onRemoved();
  System.out.println("PASS preflight, invalidation, loaded cargo recovery and release");
 }
}
'''

MIXED_ITEM_TEST = r'''
package fixture;
import java.util.*;
import buildcraft.robotics.scheduler.*;
import buildcraft.robotics.internal.legacy.robots.*;
import buildcraft.robotics.boards.*;
import buildcraft.robotics.tile.TileRobotProcessor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
public class MixedItemRouteRuntime {
 static void check(boolean good,String msg){if(!good)throw new AssertionError(msg);}
 public static void main(String[] args) {
  var world=new ServerLevel();var registry=new IRobotRegistry();
  RobotManager.registryProvider=l->registry;
  var home=new DockingStation(0,0);
  var source=new DockingStation(8,0);source.itemCount=32;
  var receiver=new DockingStation(16,0);receiver.acceptsItems=true;
  registry.stations.addAll(List.of(home,source,receiver));
  var robot=new EntityRobotBase(world,1,new BoardRobotCarrier(),1);
  robot.home=home;robot.registry=registry;registry.robots.put(1L,robot);
  var panel=new TileRobotProcessor(world,new BlockPos(2,65,2));panel.ids.add(1L);
  var scheduler=new RobotLogisticsScheduler(panel);
  world.time=1;scheduler.tick(world);
  var dispatch=RobotLogisticsScheduler.acquire(robot);
  check(dispatch!=null && "usable".equals(dispatch.itemPreview().type),
    "scheduler refused usable second cargo type");
  check(dispatch.destination()==receiver,"route used wrong destination");
  scheduler.onRemoved();
  System.out.println("PASS mixed item source selection");
 }
}
'''

class TwoSidedLogisticsRouteTests(unittest.TestCase):
    def compile_and_run(self, sources, classname, folder):
        return LogisticsSchedulerTests().compile_and_run(sources, classname, folder)

    def test_receiver_generation_and_lifecycle(self):
        with tempfile.TemporaryDirectory(prefix='bc-route-lease-') as tmp:
            self.compile_and_run({
                'buildcraft/robotics/scheduler/RobotLogisticsRouteLeases.java': ROUTES.read_text(),
                'RouteLeaseContract.java': ROUTE_LEASES_CONTRACT,
            }, 'RouteLeaseContract', Path(tmp))

    def test_mixed_item_source_selects_compatible_cargo(self):
        with tempfile.TemporaryDirectory(prefix='bc-route-mixed-') as tmp:
            sources=STUBS.copy()
            board=sources['buildcraft/robotics/boards/BoardRobotCarrier.java']
            board=board.replace('return src.itemCount>0?java.util.List.of(new ItemStack("ore",src.itemCount)):java.util.List.of();',
                'return src.itemCount>0?java.util.List.of(new ItemStack("blocked",4),new ItemStack("usable",8)):java.util.List.of();')
            board=board.replace('return destination.acceptsItems?item.getCount():0;',
                'return destination.acceptsItems && "usable".equals(item.type)?item.getCount():0;')
            sources['buildcraft/robotics/boards/BoardRobotCarrier.java']=board
            for source in [QUEUE,ROUTES,DISPATCH]:
                relative=source.relative_to(ROOT/'source-shared/src/main/java')
                sources[str(relative)]=preprocess_text(source.read_text(),minecraft='1.19.2',
                    family='old',platform='forge',source=str(source))
            sources['fixture/MixedItemRouteRuntime.java']=MIXED_ITEM_TEST
            self.compile_and_run(sources,'fixture.MixedItemRouteRuntime',Path(tmp))

    def test_two_sided_preflight_and_loaded_cargo_recovery(self):
        for version,family,platform in [('1.19.2','old','forge'),
                                        ('1.21.11','1.21.X','neoforge'),
                                        ('26.3','26.X','fabric')]:
            with self.subTest(target=version+'-'+platform), tempfile.TemporaryDirectory(prefix='bc-route-world-') as tmp:
                sources=STUBS.copy()
                for source in [QUEUE,ROUTES,DISPATCH]:
                    relative=source.relative_to(ROOT/'source-shared/src/main/java')
                    sources[str(relative)]=preprocess_text(source.read_text(),minecraft=version,
                        family=family,platform=platform,source=str(source))
                sources['fixture/TwoSidedLogisticsRuntime.java']=LOADED_ROUTE_TEST
                self.compile_and_run(sources,'fixture.TwoSidedLogisticsRuntime',Path(tmp))

if __name__=='__main__': unittest.main()
