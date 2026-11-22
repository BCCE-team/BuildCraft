"""Real JVM checks for stage 9 pickup reservations and loaded-world dispatch."""
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT / 'scripts'))
from source_preprocessor import preprocess_text

CORE = ROOT / 'source-shared/src/main/java/buildcraft/robotics/scheduler/RobotPickupQueue.java'
DISPATCH = ROOT / 'source-shared/src/main/java/buildcraft/robotics/scheduler/RobotPickupScheduler.java'

CORE_FIXTURE = r'''
import java.util.*;
import buildcraft.robotics.scheduler.RobotPickupQueue;
public class PickupQueueContract {
 static void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
 public static void main(String[] args){
  var q=new RobotPickupQueue(); long now=100;
  List<UUID> ids=new ArrayList<>();for(int i=0;i<9;i++)ids.add(UUID.randomUUID());
  for(int i=0;i<6;i++)check(q.claim(ids.get(i),10,1,6,now,100)!=null,"cannot queue 6");
  check(q.claim(ids.get(6),10,1,6,now,100)==null,"exceeded limit");
  check(q.claim(ids.get(0),20,2,6,now,100)==null,"another panel double-booked UUID");
  var first=q.activate(ids.get(0),10,1,now,100);
  check(first!=null && first.active(),"activation missing");
  q.trim(10,1,2);
  check(q.size(10,1)==2 && q.jobs(10,1).get(0).active(),"trim dropped active pickup");
  check(!q.release(ids.get(0),1,first.token()+1),"old token released new claim");
  check(q.release(ids.get(0),1,first.token()),"failed to release pickup");
  var again=q.claim(ids.get(0),10,2,6,now,100);
  check(again!=null && again.token()!=first.token(),"lease generations not distinct");
  check(!q.release(ids.get(0),1,first.token()),"stale completion removed reassignment");
  check(q.isClaimedByOther(ids.get(0),1,now),"other robot may steal assigned item");
  check(q.renew(ids.get(0),10,2,again.token(),now+30,100),"could not renew claim");
  q.expire(now+120);
  check(q.size(10,2)==1,"renewed claim expired too early");
  q.expire(now+140);
  check(q.size(10,2)==0,"unrefreshed claim did not expire");
  q.clearPanel(10);
  check(q.size(10,1)==0,"panel unload retained claims");
  System.out.println("PASS pickup UUID leases, exact capacity, exclusivity, token fencing, renewal, expiry, cleanup");
 }
}
'''

STUBS = {
 'net/minecraft/core/BlockPos.java': '''package net.minecraft.core;
 public record BlockPos(int x,int y,int z) {
  public int getX(){return x;}public int getY(){return y;}public int getZ(){return z;}
  public long asLong(){return ((long)x<<32)^((long)z<<8)^y;}
 }''',
 'net/minecraft/world/phys/Vec3.java': '''package net.minecraft.world.phys;
 public record Vec3(double x,double y,double z) {}''',
 'net/minecraft/world/phys/AABB.java': '''package net.minecraft.world.phys;
 public record AABB(double minX,double minY,double minZ,double maxX,double maxY,double maxZ) {
  public boolean contains(double x,double y,double z){return x>=minX&&x<maxX&&y>=minY&&y<maxY&&z>=minZ&&z<maxZ;}
 }''',
 'net/minecraft/world/level/Level.java': '''package net.minecraft.world.level;
 public class Level {public long time;public long getGameTime(){return time;}public int getMinBuildHeight(){return -64;}public int getMaxBuildHeight(){return 320;}}''',
 'net/minecraft/server/level/ServerLevel.java': '''package net.minecraft.server.level;
 import net.minecraft.world.entity.Entity;import net.minecraft.world.phys.AABB;import java.util.*;import java.util.function.Predicate;
 public class ServerLevel extends net.minecraft.world.level.Level {
  public java.util.List<Entity> entities=new java.util.ArrayList<>();public int queries;
  public Entity getEntity(UUID id){for(Entity e:entities)if(e.id.equals(id)&&e.alive)return e;return null;}
  public <T extends Entity>java.util.List<T> getEntitiesOfClass(Class<T> type,AABB box,Predicate<T> accept){
   queries++;java.util.List<T> out=new java.util.ArrayList<>();
   for(Entity e:entities)if(type.isInstance(e)&&e.alive&&box.contains(e.x,e.y,e.z)){
    T t=type.cast(e);if(accept.test(t))out.add(t);
   }return out;
  }
 }''',
 'net/minecraft/world/entity/Entity.java': '''package net.minecraft.world.entity;
 import java.util.UUID;import net.minecraft.core.BlockPos;import net.minecraft.world.phys.Vec3;
 public class Entity {
  public UUID id=UUID.randomUUID();public double x,y,z;public boolean alive=true;
  public Entity(double x,double y,double z){this.x=x;this.y=y;this.z=z;}
  public boolean isAlive(){return alive;}public UUID getUUID(){return id;}
  public BlockPos blockPosition(){return new BlockPos((int)Math.floor(x),(int)Math.floor(y),(int)Math.floor(z));}
  public Vec3 position(){return new Vec3(x,y,z);}
  public double distanceToSqr(Entity other){double dx=x-other.x,dy=y-other.y,dz=z-other.z;return dx*dx+dy*dy+dz*dz;}
 }''',
 'net/minecraft/world/item/ItemStack.java': '''package net.minecraft.world.item;
 public class ItemStack {public final int count;public ItemStack(int count){this.count=count;}public int getCount(){return count;}public boolean isEmpty(){return count==0;}}''',
 'net/minecraft/world/entity/item/ItemEntity.java': '''package net.minecraft.world.entity.item;
 import net.minecraft.world.item.ItemStack;
 public class ItemEntity extends net.minecraft.world.entity.Entity{
  public final ItemStack stack;
  public ItemEntity(double x,double y,double z,int count){super(x,y,z);stack=new ItemStack(count);}
  public ItemStack getItem(){return stack;}
 }''',
 'buildcraft/lib/internal/area/IZone.java': '''package buildcraft.lib.internal.area;
 public interface IZone{boolean contains(net.minecraft.world.phys.Vec3 pos);}''',
 'buildcraft/lib/internal/core/IStackFilter.java': '''package buildcraft.lib.internal.core;
 public interface IStackFilter{boolean matches(net.minecraft.world.item.ItemStack stack);}''',
 'buildcraft/robotics/internal/legacy/robots/AIRobot.java': '''package buildcraft.robotics.internal.legacy.robots;
 public class AIRobot{public boolean isWithinWorkChain(AIRobot board){return this==board;}}''',
 'buildcraft/robotics/internal/legacy/boards/RedstoneBoardRobot.java': '''package buildcraft.robotics.internal.legacy.boards;
 public class RedstoneBoardRobot extends buildcraft.robotics.internal.legacy.robots.AIRobot{}''',
 'buildcraft/robotics/boards/BoardRobotPicker.java': '''package buildcraft.robotics.boards;
 import java.util.*;import net.minecraft.world.entity.item.ItemEntity;import net.minecraft.world.level.Level;
 public class BoardRobotPicker extends buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobot{
  public record TargetKey(String world, UUID id){public static TargetKey of(ItemEntity item){return new TargetKey("test",item.getUUID());}}
  public static final Set<TargetKey> targettedItems=new HashSet<>();
 }''',
 'buildcraft/robotics/internal/legacy/robots/DockingStation.java': '''package buildcraft.robotics.internal.legacy.robots;
 public class DockingStation{}''',
 'buildcraft/robotics/statements/ActionRobotFilter.java': '''package buildcraft.robotics.statements;
 import buildcraft.lib.internal.core.IStackFilter;import buildcraft.robotics.internal.legacy.robots.DockingStation;
 public class ActionRobotFilter {public static boolean allow=true;public static IStackFilter getGateFilter(DockingStation station){return stack -> allow;}}''',
 'buildcraft/robotics/internal/legacy/robots/EntityRobotBase.java': '''package buildcraft.robotics.internal.legacy.robots;
 import net.minecraft.world.level.Level;import buildcraft.lib.internal.area.IZone;
 import buildcraft.robotics.boards.BoardRobotPicker;
 public class EntityRobotBase extends net.minecraft.world.entity.Entity {
  public static final int SAFETY_ENERGY=20000;
  public final Level level;public Level level(){return level;}public long idNumber;public boolean removed;
  public BoardRobotPicker board=new BoardRobotPicker();
  public EntityRobotBase(Level world,long id,double x){super(x,65,1);level=world;idNumber=id;}
  public boolean isRemoved(){return removed;}public int getEnergy(){return 60000;}
  public BoardRobotPicker getBoard(){return board;}public AIRobot getActiveAI(){return board;}
  public long getRobotId(){return idNumber;}public DockingStation getLinkedStation(){return new DockingStation();}
  public IZone getZoneToWork(){return null;}public boolean isKnownUnreachable(net.minecraft.world.entity.Entity e){return false;}
 }''',
 'buildcraft/robotics/entity/EntityRobot.java': '''package buildcraft.robotics.entity;
 import net.minecraft.world.level.Level;import net.minecraft.world.item.ItemStack;
 public class EntityRobot extends buildcraft.robotics.internal.legacy.robots.EntityRobotBase{
  public int space=64;public EntityRobot(Level level,long id,double x){super(level,id,x);}
  public ItemStack insertIntoInventory(ItemStack stack,boolean commit){return new ItemStack(Math.max(0,stack.getCount()-space));}
 }''',
 'buildcraft/robotics/ai/AIRobotFetchItem.java': '''package buildcraft.robotics.ai;
 public class AIRobotFetchItem extends buildcraft.robotics.internal.legacy.robots.AIRobot{}''',
 'buildcraft/robotics/ai/AIRobotGotoBlock.java': '''package buildcraft.robotics.ai;
 public class AIRobotGotoBlock extends buildcraft.robotics.internal.legacy.robots.AIRobot{}''',
 'buildcraft/robotics/tile/TileRobotProcessor.java': '''package buildcraft.robotics.tile;
 import java.util.*;import net.minecraft.core.BlockPos;import net.minecraft.world.level.Level;
 public class TileRobotProcessor{
  public static final int WORK_AREA_RADIUS_CHUNKS=3;
  public final Level level;public final BlockPos pos;public boolean removed,allow=true;public int max=3;
  public final List<Long> linked=new ArrayList<>();
  public TileRobotProcessor(Level level,BlockPos pos){this.level=level;this.pos=pos;}
  public Level getLevel(){return level;}public BlockPos getBlockPos(){return pos;}
  public boolean isRemoved(){return removed;}public List<Long> getLinkedRobotIds(){return linked;}
  public boolean isRobotLinked(long id){return linked.contains(id);}
  public int getMaxExecutionQueue(){return max;}
  public boolean allowsWorkPosition(BlockPos pos){return allow;}
 }'''
}

SIMULATION = r'''package fixture;
import java.util.*;
import buildcraft.robotics.scheduler.*;
import buildcraft.robotics.entity.EntityRobot;
import buildcraft.robotics.tile.TileRobotProcessor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import buildcraft.robotics.statements.ActionRobotFilter;
public class PickerDispatchRuntime {
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 public static void main(String[] args){
  var world=new ServerLevel();var p=new TileRobotProcessor(world,new BlockPos(0,64,0));
  var a=new EntityRobot(world,1,0);var b=new EntityRobot(world,2,2);
  p.linked.add(1L);p.linked.add(2L);
  List<ItemEntity> items=new ArrayList<>();
  for(int i=0;i<7;i++){ItemEntity item=new ItemEntity(4+i*2,65,1,4);items.add(item);world.entities.add(item);}
  var scheduler=new RobotPickupScheduler(p);
  world.time=1;scheduler.tick(world,List.of(a,b));
  check(scheduler.queueCount(1)==3&&scheduler.queueCount(2)==3,"picker queues not fairly filled");
  check(RobotPickupScheduler.hasController(a),"panel not registered");
  var forA=RobotPickupScheduler.acquire(a);var forB=RobotPickupScheduler.acquire(b);
  check(forA!=null&&forB!=null&&forA.entity()!=forB.entity(),"double allocated item entity");
  check(RobotPickupScheduler.reservedByOther(a,forB.entity()),"autonomous picker may steal job");
  RobotPickupScheduler.finish(a,forA.entity().getUUID(),forA.leaseToken());
  check(scheduler.queueCount(1)==2,"completed pickup did not free slot");
  world.time=21;scheduler.tick(world,List.of(a,b));
  check(scheduler.queueCount(1)==3,"queue did not replenish without relog");
  ActionRobotFilter.allow=false;world.time=22;scheduler.tick(world,List.of(a,b));
  check(scheduler.queueCount(1)==0&&scheduler.queueCount(2)==0,"filter revoked but jobs survived");
  ActionRobotFilter.allow=true;world.time=42;scheduler.tick(world,List.of(a,b));
  check(scheduler.queueCount(1)>0,"queue did not resume after filter returned");
  p.allow=false;world.time=43;scheduler.tick(world,List.of(a,b));
  check(scheduler.queueCount(1)==0&&scheduler.queueCount(2)==0,"zone revoked but assignments survived");
  p.allow=true;world.time=63;scheduler.tick(world,List.of(a,b));
  check(scheduler.queueCount(1)>0,"queue not restored after map unblocked");
  b.removed=true;world.time=64;scheduler.tick(world,List.of(a));
  check(scheduler.queueCount(2)==0,"unloaded robot kept claims");
  p.max=1;scheduler.onQueueLimitChanged();
  check(scheduler.queueCount(1)<=1,"lowered limit ignored");
  scheduler.onRemoved();check(!RobotPickupScheduler.hasController(a),"unloaded panel retained controller");
  check(scheduler.queueCount(1)==0,"unloaded panel retained reservations");
  System.out.println("PASS: 2 pickers, exclusive UUID jobs, live refill, filter, area, unload and limits");
 }
}'''

class PickupExecutionQueueTests(unittest.TestCase):
    def test_value_only_uuid_leases(self):
        with tempfile.TemporaryDirectory(prefix='bc-stage9-core-') as tmp:
            fixture = Path(tmp) / 'PickupQueueContract.java'
            fixture.write_text(CORE_FIXTURE)
            run = subprocess.run(['javac', '--release', '17', '-d', tmp, str(CORE), str(fixture)],
                                 text=True, capture_output=True, timeout=30)
            self.assertEqual(run.returncode, 0, run.stderr)
            result = subprocess.run(['java', '-cp', tmp, 'PickupQueueContract'],
                                    text=True, capture_output=True, timeout=10)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_real_dispatcher_in_legacy_and_modern_fake_worlds(self):
        for version, family, platform in [('1.19.2', 'old', 'forge'),
                                          ('1.21.11', '1.21.X', 'neoforge'),
                                          ('26.3', '26.X', 'fabric')]:
            with self.subTest(version=version), tempfile.TemporaryDirectory(prefix='bc-stage9-jvm-') as tmp:
                out = Path(tmp)
                paths = []
                for rel, content in STUBS.items():
                    path = out / rel
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text(content)
                    paths.append(str(path))
                for source in (CORE, DISPATCH):
                    rel = source.relative_to(ROOT / 'source-shared/src/main/java')
                    path = out / rel
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text(preprocess_text(source.read_text(), minecraft=version,
                                                    family=family, platform=platform, source=str(source)))
                    paths.append(str(path))
                fixture = out / 'fixture/PickerDispatchRuntime.java'
                fixture.parent.mkdir(parents=True, exist_ok=True)
                fixture.write_text(SIMULATION)
                paths.append(str(fixture))
                classes = out / 'classes'
                classes.mkdir()
                compile_result = subprocess.run(['javac', '--release', '17', '-d', str(classes), *paths],
                                                text=True, capture_output=True, timeout=40)
                self.assertEqual(compile_result.returncode, 0, compile_result.stderr)
                result = subprocess.run(['java', '-cp', str(classes), 'fixture.PickerDispatchRuntime'],
                                        text=True, capture_output=True, timeout=15)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_fetch_ai_has_live_control_and_releases_claims(self):
        for family in ('old', '1.21.X'):
            source = (ROOT / f'source-families/{family}/src/main/java/buildcraft/robotics/ai/AIRobotFetchItem.java').read_text()
            self.assertIn('RobotPickupScheduler.acquire(robot)', source)
            self.assertIn('RobotPickupScheduler.finish(robot, queuedItemUuid, queuedLeaseToken)', source)
            self.assertIn('RobotPickupScheduler.reservedByOther(robot, item)', source)
            self.assertIn('RobotPickupScheduler.permitsTarget(robot, target)', source)
            self.assertIn('if (RobotPickupScheduler.hasController(robot))', source)
            self.assertIn('boolean moved = remaining.getCount() < stack.getCount()', source)
            self.assertIn('return target == null && RobotPickupScheduler.hasController(robot) ? 0 : 15', source)
