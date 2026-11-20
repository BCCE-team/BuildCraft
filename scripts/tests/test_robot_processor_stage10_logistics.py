"""JVM contract tests for transient route leases and loaded-world logistics dispatch."""
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from source_preprocessor import preprocess_text

QUEUE = ROOT / 'source-shared/src/main/java/buildcraft/robotics/scheduler/RobotLogisticsQueue.java'
DISPATCH = ROOT / 'source-shared/src/main/java/buildcraft/robotics/scheduler/RobotLogisticsScheduler.java'
ROUTES = ROOT / 'source-shared/src/main/java/buildcraft/robotics/scheduler/RobotLogisticsRouteLeases.java'

CORE_CONTRACT = r'''
import buildcraft.robotics.scheduler.RobotLogisticsQueue;
public class LogisticsQueueContract {
  static void check(boolean b, String msg){if(!b)throw new AssertionError(msg);}
  public static void main(String[] args) {
    RobotLogisticsQueue q = new RobotLogisticsQueue(); long tick=100;
    var a=new RobotLogisticsQueue.Key(RobotLogisticsQueue.Kind.ITEM_SOURCE,10,2,"");
    var b=new RobotLogisticsQueue.Key(RobotLogisticsQueue.Kind.FLUID_SOURCE,10,2,"");
    var c=new RobotLogisticsQueue.Key(RobotLogisticsQueue.Kind.ITEM_REQUEST,10,2,"mod:iron");
    var d=new RobotLogisticsQueue.Key(RobotLogisticsQueue.Kind.ITEM_REQUEST,10,2,"mod:gold");
    var claim=q.claim(a,99,1,3,tick,100);
    check(claim!=null && q.size(99,1)==1,"source lease missing");
    check(q.claim(a,88,2,3,tick,100)==null,"same source cross-panel collision");
    check(q.claim(b,99,1,3,tick,100)!=null,"fluid and item source should have distinct keys");
    check(q.claim(c,99,1,3,tick,100)!=null,"delivery request missing");
    check(q.claim(d,99,1,3,tick,100)==null,"queue exceeded configured capacity");
    var active=q.activate(a,99,1,tick,100);
    check(active!=null && active.active(),"active source not marked");
    q.trim(99,1,1);
    check(q.size(99,1)==1 && q.get(a,tick)!=null,"trim removed active claim");
    check(!q.release(a,1,active.token()+1),"wrong token released claim");
    check(q.renew(a,99,1,active.token(),tick+50,100),"renew failed");
    q.expire(tick+120);
    check(q.get(a,tick+120)!=null,"renewed lease expired early");
    q.expire(tick+151);
    check(q.get(a,tick+151)==null,"expired source lease remained");
    var replacement=q.claim(a,88,2,3,tick+151,100);
    check(replacement!=null && replacement.token()!=active.token(),"lease generation reused");
    check(!q.release(a,1,active.token()),"old robot released newly assigned source");
    check(q.isTakenByOther(a,1,tick+151),"other robot does not see active reservation");
    q.clearPanel(88);
    check(q.get(a,tick+151)==null,"unloaded panel kept reservation");
    System.out.println("PASS logistics lease capacity, token fencing, renewal, panel cleanup and request identity");
  }
}
'''

STUBS = {
'net/minecraft/core/BlockPos.java': '''package net.minecraft.core;
public record BlockPos(int x, int y, int z) {
 public long asLong(){return ((long)x<<40)|((long)z<<20)|(long)y;}
 public static BlockPos of(long n){return new BlockPos((int)(n>>40),(int)(n&1048575),(int)((n>>20)&1048575));}
}''',
'net/minecraft/core/Direction.java': '''package net.minecraft.core;
public enum Direction{DOWN,UP,NORTH,SOUTH,WEST,EAST;
 public int get3DDataValue(){return ordinal();}
 public static Direction from3DDataValue(int i){return values()[i];}
}''',
'net/minecraft/world/phys/Vec3.java': '''package net.minecraft.world.phys;
public record Vec3(double x,double y,double z){}''',
'net/minecraft/world/level/Level.java': '''package net.minecraft.world.level;
public class Level{public long time=0;public long getGameTime(){return time;}}''',
'net/minecraft/server/level/ServerLevel.java': '''package net.minecraft.server.level;
public class ServerLevel extends net.minecraft.world.level.Level{
 public boolean hasChunkAt(net.minecraft.core.BlockPos pos){return true;}
}''',
'net/minecraft/world/item/ItemStack.java': '''package net.minecraft.world.item;
public class ItemStack{public static final ItemStack EMPTY=new ItemStack("",0);public int count;public String type;public ItemStack(String type,int count){this.type=type;this.count=count;}
 public boolean isEmpty(){return count<=0;}
 public ItemStack copy(){return new ItemStack(type,count);}
 public int getMaxStackSize(){return 64;}
 public void setCount(int n){count=n;}
 public int getCount(){return count;}
}''',
'buildcraft/lib/internal/area/IZone.java': '''package buildcraft.lib.internal.area;
public interface IZone{boolean contains(net.minecraft.world.phys.Vec3 pos);}''',
'buildcraft/lib/internal/core/IStackFilter.java': '''package buildcraft.lib.internal.core;
public interface IStackFilter{boolean matches(net.minecraft.world.item.ItemStack stack);}''',
'buildcraft/lib/internal/core/IFluidFilter.java': '''package buildcraft.lib.internal.core;
public interface IFluidFilter{boolean matches(Object fluid);}''',
'buildcraft/lib/internal/core/BlockIndex.java': '''package buildcraft.lib.internal.core;
public record BlockIndex(net.minecraft.core.BlockPos pos){public net.minecraft.core.BlockPos toBlockPos(){return pos;}}''',
'buildcraft/api/v2/request/ItemRequest.java': '''package buildcraft.api.v2.request;
import java.util.*;import net.minecraft.world.item.ItemStack;
public class ItemRequest{
 private final Id id;private final String type;
 public ItemRequest(String id,String type){this.id=new Id(id);this.type=type;}
 public Id id(){return id;} public int priority(){return 1;} public int minimum(){return 1;} public int maximum(){return 64;}
 public Matcher matcher(){return new Matcher(type);}
 public record Id(String value){public String toString(){return value;}}
 public record Matcher(String type){public java.util.List<ItemStack> examples(){return java.util.List.of(new ItemStack(type,1));}
  public boolean matches(ItemStack s){return type.equals(s.type);}}
}''',
'buildcraft/api/v2/request/RequestProvider.java': '''package buildcraft.api.v2.request;
import java.util.*;public class RequestProvider{public List<ItemRequest> orders=new ArrayList<>();
 public List<ItemRequest> requests(){return orders;}}
''',
'buildcraft/robotics/internal/legacy/robots/AIRobot.java': '''package buildcraft.robotics.internal.legacy.robots;
public class AIRobot{public boolean isWithinWorkChain(AIRobot other){return true;}}''',
'buildcraft/robotics/boards/BoardRobotCarrier.java': '''package buildcraft.robotics.boards;
import net.minecraft.world.item.ItemStack;
import buildcraft.robotics.internal.legacy.robots.DockingStation;
public class BoardRobotCarrier extends buildcraft.robotics.internal.legacy.robots.AIRobot{
 public ItemStack previewSource(DockingStation src){return src.itemCount>0?new ItemStack("ore",src.itemCount):ItemStack.EMPTY;}
 public java.util.List<ItemStack> previewSources(DockingStation src){return src.itemCount>0?java.util.List.of(new ItemStack("ore",src.itemCount)):java.util.List.of();}
 public int canRoute(DockingStation destination,ItemStack item){return destination.acceptsItems?item.getCount():0;}
}''',
'buildcraft/robotics/boards/BoardRobotFluidCarrier.java': '''package buildcraft.robotics.boards;
import buildcraft.robotics.internal.legacy.robots.DockingStation;
public class BoardRobotFluidCarrier extends buildcraft.robotics.internal.legacy.robots.AIRobot{
 public int canRoute(DockingStation src,DockingStation dest){return src.fluidCount>0&&dest.acceptsFluids?1000:0;}
 public boolean canUnloadCargoAt(DockingStation dest){return dest.acceptsFluids;}
}''',
'buildcraft/robotics/boards/BoardRobotDelivery.java': '''package buildcraft.robotics.boards;
public class BoardRobotDelivery extends buildcraft.robotics.internal.legacy.robots.AIRobot{
 public boolean holding;public boolean hasCurrentRequest(){return holding;}}''',
'buildcraft/robotics/internal/legacy/robots/ResourceId.java': '''package buildcraft.robotics.internal.legacy.robots;
public record ResourceId(String key){}''',
'buildcraft/robotics/internal/legacy/robots/DockingStation.java': '''package buildcraft.robotics.internal.legacy.robots;
import net.minecraft.core.*;import buildcraft.api.v2.request.RequestProvider;
public class DockingStation{
 public BlockPos pos;public Direction direction=Direction.EAST;public long owner;public int itemCount,fluidCount;
 public RequestProvider provider;
 public boolean acceptsItems,acceptsFluids;
 public DockingStation(int x,int z){pos=new BlockPos(x,65,z);}
 public boolean isInitialized(){return true;}
 public BlockIndex index(){return new BlockIndex(pos);}
 public Direction side(){return direction;}
 public int x(){return pos.x();}public int y(){return pos.y();}public int z(){return pos.z();}
 public boolean isTaken(){return owner!=0;}public long robotIdTaking(){return owner;}
 public boolean take(EntityRobotBase robot){if(owner!=0 && owner!=robot.id)return false;owner=robot.id;return true;}
 public void release(EntityRobotBase robot){if(owner==robot.id)owner=0;}
 public RequestProvider getRequestProvider(){return provider;}
}'''.replace('import buildcraft.api.v2.request.RequestProvider;', 'import buildcraft.api.v2.request.RequestProvider;import buildcraft.lib.internal.core.BlockIndex;'),
'buildcraft/robotics/internal/legacy/robots/IRobotRegistry.java': '''package buildcraft.robotics.internal.legacy.robots;
import java.util.*;import net.minecraft.core.*;
public class IRobotRegistry{
 public List<DockingStation> stations=new ArrayList<>();public Map<Long,EntityRobotBase> robots=new HashMap<>();
 public Set<ResourceId> taken=new HashSet<>();
 public java.util.List<DockingStation> getStations(){return stations;}
 public DockingStation getStation(BlockPos pos,Direction side){for(DockingStation s:stations)
  if(s.pos.equals(pos) && s.side()==side)return s;return null;}
 public EntityRobotBase getLoadedRobot(long id){return robots.get(id);}
 public boolean isTaken(ResourceId id){return taken.contains(id);}
 public boolean take(ResourceId id, EntityRobotBase r){return taken.add(id);}
 public void release(ResourceId id){taken.remove(id);}
}''',
'buildcraft/robotics/internal/legacy/robots/RobotManager.java': '''package buildcraft.robotics.internal.legacy.robots;
import net.minecraft.world.level.Level;
public class RobotManager{public static Provider registryProvider;
 public interface Provider{IRobotRegistry getRegistry(Level level);}}
''',
'buildcraft/robotics/internal/legacy/robots/EntityRobotBase.java': '''package buildcraft.robotics.internal.legacy.robots;
import net.minecraft.world.level.Level;import buildcraft.lib.internal.area.IZone;
import buildcraft.robotics.boards.*;
public class EntityRobotBase{
 public static final int SAFETY_ENERGY=2000;
 public long id;public Level level;public AIRobot board;public DockingStation home;public IRobotRegistry registry;
 public double x,y,z;public boolean cargo,removed;public int fluid,energy=10000;
 public EntityRobotBase(Level l,long id,AIRobot board,double x){this.level=l;this.id=id;this.board=board;this.x=x;this.y=65;this.z=0;}
 public Level level(){return level;}public boolean isRemoved(){return removed;}
 public long getRobotId(){return id;}public AIRobot getBoard(){return board;}public AIRobot getActiveAI(){return board;}
 public DockingStation getLinkedStation(){return home;}public IRobotRegistry getRegistry(){return registry;}
 public boolean containsItems(){return cargo;}public int getEnergy(){return energy;}
 public double getX(){return x;}public double getY(){return y;}public double getZ(){return z;}
 public IZone getZoneToLoadUnload(){return null;}public IZone getZoneToWork(){return null;}
 public Fluid getFluidInTank(int slot){return new Fluid(fluid);}
 public record Fluid(int amount){public boolean isEmpty(){return amount<=0;}public int getAmount(){return amount;}}
}''',
'buildcraft/robotics/RobotTrace.java': '''package buildcraft.robotics;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
public class RobotTrace {public static boolean enabled(){return false;}public static void event(EntityRobotBase r,String name,String fmt,Object...args){} }''',
'buildcraft/robotics/StackRequest.java': '''package buildcraft.robotics;
import net.minecraft.world.level.Level;import net.minecraft.world.item.ItemStack;
import buildcraft.api.v2.request.*;import buildcraft.robotics.internal.legacy.robots.*;
public class StackRequest{
 public DockingStation station;public ItemRequest.Id id;public ItemStack stack;
 public StackRequest(RequestProvider p,ItemRequest.Id id,ItemStack s){this.id=id;stack=s;}
 public void setStation(DockingStation s){station=s;}
 public ResourceId getResourceId(Level world){return new ResourceId(station.x()+"/"+station.z()+"/"+id);}
}''',
'buildcraft/robotics/ai/AIRobotUnload.java': '''package buildcraft.robotics.ai;import buildcraft.robotics.internal.legacy.robots.*;
public class AIRobotUnload{public static boolean unload(EntityRobotBase r,DockingStation s,boolean doIt,boolean action){return s.acceptsItems;}}''',
'buildcraft/robotics/ai/AIRobotLoad.java': '''package buildcraft.robotics.ai;
import buildcraft.robotics.internal.legacy.robots.*;import buildcraft.lib.internal.core.IStackFilter;
public class AIRobotLoad{public static final int ANY_QUANTITY=-1;
 public static boolean load(EntityRobotBase r,DockingStation s,IStackFilter filter,int quantity,boolean commit){
  return s.itemCount>0 && filter.matches(new net.minecraft.world.item.ItemStack("ore",1));
 }}''',
'buildcraft/robotics/ai/AIRobotLoadFluids.java': '''package buildcraft.robotics.ai;
import buildcraft.robotics.internal.legacy.robots.*;import buildcraft.lib.internal.core.IFluidFilter;
public class AIRobotLoadFluids{
 public static int load(EntityRobotBase r,DockingStation s,IFluidFilter filter,boolean commit){return s.fluidCount>0?1000:0;}
 }''',
'buildcraft/robotics/statements/ActionRobotFilter.java': '''package buildcraft.robotics.statements;
import buildcraft.robotics.internal.legacy.robots.DockingStation;
import buildcraft.lib.internal.core.*;
public class ActionRobotFilter{
 public static boolean itemAllowed=true;
 public static IStackFilter getGateFilter(DockingStation s){return item->itemAllowed;}
 public static IFluidFilter getGateFluidFilter(DockingStation s){return fluid->true;}
}''',
'buildcraft/robotics/statements/ActionStationForbidRobot.java': '''package buildcraft.robotics.statements;
import buildcraft.robotics.internal.legacy.robots.*;
public class ActionStationForbidRobot{public static boolean isForbidden(DockingStation s,EntityRobotBase r){return false;}}
''',
'buildcraft/robotics/tile/TileRobotProcessor.java': '''package buildcraft.robotics.tile;
import net.minecraft.core.BlockPos;import net.minecraft.world.level.Level;import java.util.*;
public class TileRobotProcessor {
 public Level level;public BlockPos pos;public List<Long> ids=new ArrayList<>();public boolean removed;public int limit=3;
 public TileRobotProcessor(Level level,BlockPos pos){this.level=level;this.pos=pos;}
 public Level getLevel(){return level;}public BlockPos getBlockPos(){return pos;}
 public boolean isRemoved(){return removed;}public List<Long> getLinkedRobotIds(){return ids;}
 public boolean isRobotLinked(long id){return ids.contains(id);}
 public int getMaxExecutionQueue(){return limit;}
}''',
}

SIMULATION = r'''
package fixture;
import java.util.*;
import buildcraft.robotics.scheduler.*;
import buildcraft.robotics.internal.legacy.robots.*;
import buildcraft.robotics.boards.*;
import buildcraft.robotics.tile.TileRobotProcessor;
import buildcraft.api.v2.request.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
public class LogisticsDispatchRuntime {
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 static EntityRobotBase bot(ServerLevel world,IRobotRegistry reg,long id,
                            buildcraft.robotics.internal.legacy.robots.AIRobot role,double x,DockingStation home){
  EntityRobotBase robot=new EntityRobotBase(world,id,role,x);robot.home=home;robot.registry=reg;
  reg.robots.put(id,robot);return robot;
 }
 public static void main(String[] args){
  ServerLevel world=new ServerLevel();IRobotRegistry reg=new IRobotRegistry();
  RobotManager.registryProvider=l->reg;
  DockingStation home=new DockingStation(0,0);reg.stations.add(home);
  for(int n=1;n<=6;n++){DockingStation source=new DockingStation(n*4,0);source.itemCount=64;reg.stations.add(source);}
  for(int n=1;n<=3;n++){DockingStation fluid=new DockingStation(n*4,9);fluid.fluidCount=3000;reg.stations.add(fluid);}
  for(int n=1;n<=3;n++){DockingStation dest=new DockingStation(n*5,18);dest.acceptsItems=true;reg.stations.add(dest);}
  for(int n=1;n<=2;n++){DockingStation dest=new DockingStation(n*8,22);dest.acceptsFluids=true;reg.stations.add(dest);}
  DockingStation requestStation=new DockingStation(55,0);
  requestStation.provider=new RequestProvider();
  requestStation.provider.orders.add(new ItemRequest("test:iron","iron"));
  reg.stations.add(requestStation);
  TileRobotProcessor panel=new TileRobotProcessor(world,new BlockPos(3,65,3));
  var c1=bot(world,reg,1,new BoardRobotCarrier(),2,home);
  var c2=bot(world,reg,2,new BoardRobotCarrier(),3,home);
  var f1=bot(world,reg,3,new BoardRobotFluidCarrier(),12,home);
  var d1=bot(world,reg,4,new BoardRobotDelivery(),17,home);
  panel.ids.addAll(List.of(1L,2L,3L,4L));
  RobotLogisticsScheduler scheduler=new RobotLogisticsScheduler(panel);
  world.time=1;scheduler.tick(world);
  check(scheduler.queueCount(1)>=1 && scheduler.queueCount(2)>=1,"carrier dispatch missing");
  check(scheduler.queueCount(3)>=1,"fluid source queue missing");
  check(scheduler.queueCount(4)==1,"delivery request not queued");
  var a=RobotLogisticsScheduler.acquire(c1);
  var b=RobotLogisticsScheduler.acquire(c2);
  check(a!=null&&b!=null&&!a.key().equals(b.key()),"two carriers sharing one source");
  check(a.destination()!=null&&b.destination()!=null&&!a.destination().equals(b.destination()),
        "two carriers reserved the same receiver");
  check(RobotLogisticsScheduler.ownsDestination(c1,a.destination(),a.destinationToken()),
        "carrier did not own its planned receiver");
  check(RobotLogisticsScheduler.ownsSource(c1,a.station(),RobotLogisticsQueue.Kind.ITEM_SOURCE,a.leaseToken()),
        "carrier cannot validate its active source");
  check(!RobotLogisticsScheduler.ownsSource(c2,a.station(),RobotLogisticsQueue.Kind.ITEM_SOURCE,a.leaseToken()),
        "foreign carrier accepted first lease");
  var delivery=RobotLogisticsScheduler.acquire(d1);
  check(delivery!=null&&delivery.request()!=null,"delivery failed to acquire real request");
  check(reg.taken.size()==1&&requestStation.owner==4,"delivery did not reserve registry AND dock");
  check(scheduler.queueCount(4)==0,"committed delivery must not retain advisory claim");
  ((BoardRobotDelivery)d1.board).holding=true;
  check(scheduler.queueCount(4)==1,"committed delivery not represented in GUI");
  var liquid=RobotLogisticsScheduler.acquire(f1);
  check(liquid!=null&&liquid.key().kind()==RobotLogisticsQueue.Kind.FLUID_SOURCE,"fluid carrier not assigned");
  RobotLogisticsScheduler.finish(c1,a.key(),a.leaseToken(),false);
  RobotLogisticsScheduler.finishDestination(c1,a.destination(),a.destinationToken());
  check(!RobotLogisticsScheduler.ownsDestination(c1,a.destination(),a.destinationToken()),"old route still holds receiver");
  check(!RobotLogisticsScheduler.ownsSource(c1,a.station(),a.key().kind(),a.leaseToken()),
        "failed job still owns source");
  check(RobotLogisticsScheduler.acquire(c1)!=null,"carrier did not use next prepared source");
  c2.cargo=true;
  world.time=2;scheduler.tick(world);
  check(scheduler.queueCount(2)==1,"carried cargo not represented as one trip");
  c2.cargo=false;
  world.time=21;scheduler.tick(world);
  check(scheduler.queueCount(2)>=1,"carrier did not refill after unloading without world relog");
  buildcraft.robotics.statements.ActionRobotFilter.itemAllowed=false;
  world.time=32;scheduler.tick(world);
  check(scheduler.queueCount(1)==0&&scheduler.queueCount(2)==0,"gate change left invalid source claims");
  scheduler.onRemoved();
  check(!RobotLogisticsScheduler.hasController(c1),"unloaded panel still controls a carrier");
  System.out.println("PASS shared cargo sources, delivery registry claims, fluid routing, live refill and panel teardown");
 }
}
'''

class LogisticsSchedulerTests(unittest.TestCase):
    def compile_and_run(self, sources, classname, root):
        paths=[]
        for rel, content in sources.items():
            p=root/rel;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content)
            paths.append(str(p))
        out=root/'classes';out.mkdir()
        cp=subprocess.run(['javac','--release','17','-d',str(out),*paths],capture_output=True,text=True,timeout=45)
        self.assertEqual(cp.returncode,0,cp.stderr)
        run=subprocess.run(['java','-cp',str(out),classname],capture_output=True,text=True,timeout=15)
        self.assertEqual(run.returncode,0,run.stdout+run.stderr)

    def test_pure_java_lease_contract(self):
        with tempfile.TemporaryDirectory(prefix='bc-logistics-core-') as tmp:
            self.compile_and_run({
                'buildcraft/robotics/scheduler/RobotLogisticsQueue.java':QUEUE.read_text(),
                'LogisticsQueueContract.java':CORE_CONTRACT,
            },'LogisticsQueueContract',Path(tmp))

    def test_loaded_world_dispatch_on_old_modern_and_fabric(self):
        for version,family,platform in [('1.19.2','old','forge'),
                                        ('1.21.11','1.21.X','neoforge'),
                                        ('26.3','26.X','fabric')]:
            with self.subTest(target=version+'-'+platform),tempfile.TemporaryDirectory(prefix='bc-logistics-world-') as tmp:
                sources=STUBS.copy()
                for source in [QUEUE,ROUTES,DISPATCH]:
                    rel=source.relative_to(ROOT/'source-shared/src/main/java')
                    sources[str(rel)]=preprocess_text(source.read_text(),minecraft=version,
                             family=family,platform=platform,source=str(source))
                sources['fixture/LogisticsDispatchRuntime.java']=SIMULATION
                self.compile_and_run(sources,'fixture.LogisticsDispatchRuntime',Path(tmp))

    def test_all_carrier_boards_route_to_existing_transfer_ai(self):
        for path in [ROOT/'source-shared/src/main/java/buildcraft/robotics/boards/BoardRobotCarrier.java',
          *(ROOT/p/'src/main/java/buildcraft/robotics/boards/BoardRobotFluidCarrier.java' for p in
            ['source-platforms/forge','source-platforms/neoforge','source-family-platforms/26.X/fabric'])]:
            source=path.read_text()
            self.assertIn('RobotLogisticsScheduler.acquire(robot)',source,str(path))
            self.assertIn('RobotLogisticsScheduler.finish(robot, reservedSource, sourceToken, success)',source,str(path))
            self.assertIn('AIRobotWaitForLogistics',source,str(path))
        for family in ('old','1.21.X'):
            delivery=(ROOT/f'source-families/{family}/src/main/java/buildcraft/robotics/boards/BoardRobotDelivery.java').read_text()
            self.assertIn('RobotLogisticsScheduler.acquire(robot)',delivery)
            self.assertIn('RobotLogisticsScheduler.failedDelivery(robot, dispatchedRequest)',delivery)
            self.assertIn('releaseCurrentRequest()',delivery)

if __name__=='__main__':unittest.main()
