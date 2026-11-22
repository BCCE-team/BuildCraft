"""Compile real combat target scheduler and entity search in the Minecraft API families."""
from __future__ import annotations
import subprocess
import tempfile
import unittest
from pathlib import Path
from scripts.source_preprocessor import preprocess_text

ROOT=Path(__file__).resolve().parents[4]
STUBS={
'net/minecraft/core/BlockPos.java': '''package net.minecraft.core;
public record BlockPos(int x,int y,int z){
 public int getX(){return x;} public int getY(){return y;} public int getZ(){return z;}
 public long asLong(){return ((long)x&0x3ffffffL)<<38|((long)z&0x3ffffffL)<<12|((long)y&0xfffL);}
}''',
'net/minecraft/world/phys/Vec3.java': '''package net.minecraft.world.phys;
public record Vec3(double x,double y,double z){}''',
'net/minecraft/world/phys/AABB.java': '''package net.minecraft.world.phys;
public class AABB{
 public final double x1,y1,z1,x2,y2,z2;
 public AABB(double x1,double y1,double z1,double x2,double y2,double z2){this.x1=x1;this.y1=y1;this.z1=z1;this.x2=x2;this.y2=y2;this.z2=z2;}
 public AABB inflate(double n){return new AABB(x1-n,y1-n,z1-n,x2+n,y2+n,z2+n);}
 public boolean contains(Vec3 p){return p.x()>=x1&&p.x()<x2&&p.y()>=y1&&p.y()<y2&&p.z()>=z1&&p.z()<z2;}
}''',
'net/minecraft/world/entity/Entity.java': '''package net.minecraft.world.entity;
import java.util.UUID;import net.minecraft.core.BlockPos;import net.minecraft.world.phys.*;
public class Entity{
 public final UUID id=UUID.randomUUID();public boolean alive=true,protectedMob=false,removed=false;
 public double x,y,z;public Entity(double x,double y,double z){this.x=x;this.y=y;this.z=z;}
 public boolean isAlive(){return alive;}public boolean isRemoved(){return removed;}public UUID getUUID(){return id;}
 public BlockPos blockPosition(){return new BlockPos((int)Math.floor(x),(int)Math.floor(y),(int)Math.floor(z));}
 public Vec3 position(){return new Vec3(x,y,z);}
 public double getX(){return x;}public double getY(){return y;}public double getZ(){return z;}
 public double distanceToSqr(Entity other){double dx=x-other.x,dy=y-other.y,dz=z-other.z;return dx*dx+dy*dy+dz*dz;}
}''',
'net/minecraft/world/entity/LivingEntity.java': '''package net.minecraft.world.entity;
public class LivingEntity extends Entity{
 public final boolean hostile,passive;public boolean tamed;
 public LivingEntity(double x,double y,double z,boolean hostile,boolean passive){super(x,y,z);this.hostile=hostile;this.passive=passive;}
}''',
'net/minecraft/world/entity/EquipmentSlot.java': '''package net.minecraft.world.entity;public enum EquipmentSlot{MAINHAND}''',
'net/minecraft/world/item/SwordItem.java': '''package net.minecraft.world.item;public class SwordItem{}''',
'net/minecraft/world/item/ItemStack.java': '''package net.minecraft.world.item;
public class ItemStack{
 public Object item;public boolean damaged;
 public ItemStack(Object item){this.item=item;}
 public boolean isEmpty(){return item==null;}public Object getItem(){return item;}
 public boolean isDamageableItem(){return damaged;}public int getDamageValue(){return 0;}public int getMaxDamage(){return 100;}
}''',
'net/minecraft/world/level/Level.java': '''package net.minecraft.world.level;
import net.minecraft.world.entity.*;import net.minecraft.world.phys.*;
import java.util.*;
public class Level{
 public long time;public final List<LivingEntity> entities=new ArrayList<>();public int entityQueries;
 public long getGameTime(){return time;}public int getMinBuildHeight(){return -64;}public int getMaxBuildHeight(){return 320;}
 public boolean hasChunkAt(net.minecraft.core.BlockPos pos){return true;}
 public <T extends Entity> List<T> getEntitiesOfClass(Class<T> type,AABB box,java.util.function.Predicate<? super T> test){
  entityQueries++;List<T> out=new ArrayList<>();for(LivingEntity ent:entities)
   if(type.isInstance(ent)&&box.contains(ent.position())&&test.test(type.cast(ent)))out.add(type.cast(ent));
  return out;
 }
 public List<Entity> getEntities(Entity excluded,AABB box,java.util.function.Predicate<Entity> test){
  entityQueries++;List<Entity> out=new ArrayList<>();for(Entity ent:entities)
   if(ent!=excluded&&box.contains(ent.position())&&test.test(ent))out.add(ent);return out;
 }
 public Entity getEntity(UUID id){for(Entity entity:entities)if(entity.getUUID().equals(id))return entity;return null;}
}''',
'net/minecraft/server/level/ServerLevel.java': '''package net.minecraft.server.level;
public class ServerLevel extends net.minecraft.world.level.Level{}''',
'buildcraft/lib/internal/area/IZone.java': '''package buildcraft.lib.internal.area;
public interface IZone{boolean contains(net.minecraft.world.phys.Vec3 pos);}''',
'buildcraft/api/v2/OperationMode.java': '''package buildcraft.api.v2;public enum OperationMode{SIMULATE,EXECUTE}''',
'buildcraft/api/v2/permission/WorldOperationKind.java': '''package buildcraft.api.v2.permission;public enum WorldOperationKind{ENTITY_ATTACK}''',
'buildcraft/robotics/internal/api2/RobotAutomationSupport.java': '''package buildcraft.robotics.internal.api2;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;import net.minecraft.world.level.Level;
import net.minecraft.world.entity.Entity;import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.permission.WorldOperationKind;
public final class RobotAutomationSupport{
 public static boolean permitsEntity(EntityRobotBase robot,Level level,Entity target,WorldOperationKind kind,OperationMode mode){return !target.protectedMob;}
}''',
'buildcraft/robotics/RobotTrace.java': '''package buildcraft.robotics;
public class RobotTrace{
 public static boolean enabled(){return false;}
 public static void event(buildcraft.robotics.internal.legacy.robots.EntityRobotBase robot,String evt,String fmt,Object... args){}
}''',
'buildcraft/robotics/internal/legacy/robots/AIRobot.java': '''package buildcraft.robotics.internal.legacy.robots;
public class AIRobot{
 public EntityRobotBase robot;public AIRobot(EntityRobotBase r){robot=r;}
 public void start(){}public void terminate(){}public boolean success(){return true;}
 public boolean isWithinWorkChain(AIRobot board){return this==board||this instanceof SleepingAI&&((SleepingAI)this).board==board;}
 public static class SleepingAI extends AIRobot{public AIRobot board;public SleepingAI(EntityRobotBase r,AIRobot b){super(r);board=b;}}
 public int getEnergyCost(){return 1;}
}''',
'buildcraft/robotics/internal/legacy/robots/EntityRobotBase.java': '''package buildcraft.robotics.internal.legacy.robots;
import net.minecraft.core.BlockPos;import net.minecraft.world.phys.*;import net.minecraft.world.entity.*;
import net.minecraft.world.item.*;import net.minecraft.world.level.Level;
import buildcraft.lib.internal.area.IZone;
public class EntityRobotBase extends Entity{
 public static final int SAFETY_ENERGY=20000;
 public final Level level;private final long id;private AIRobot board;public boolean gated=false,sleeping=false;
 public Object station=new Object();public ItemStack held=new ItemStack(new SwordItem());
 public IZone zone;
 public EntityRobotBase(Level level,long id,double x,double y,double z){super(x,y,z);this.level=level;this.id=id;}
 public Level level(){return level;}public AIRobot getBoard(){return board;}public void setBoard(AIRobot board){this.board=board;}
 public AIRobot getActiveAI(){return gated?new AIRobot(this):sleeping?new AIRobot.SleepingAI(this,board):board;}
 public int getEnergy(){return 50000;}public Object getLinkedStation(){return station;}
 public ItemStack getItemBySlot(EquipmentSlot slot){return held;}
 public long getRobotId(){return id;}public IZone getZoneToWork(){return zone;}
 public boolean isKnownUnreachable(Entity entity){return false;}
 public Level getCommandSenderWorld(){return level;}
 public AABB getBoundingBox(){return new AABB(x,y,z,x+1,y+1,z+1);}
}''',
'buildcraft/robotics/boards/BoardRobotButcher.java': '''package buildcraft.robotics.boards;
import buildcraft.robotics.internal.legacy.robots.*;import net.minecraft.world.entity.*;
public class BoardRobotButcher extends AIRobot{
 public BoardRobotButcher(EntityRobotBase robot){super(robot);}
 public static boolean isValidTarget(Entity target){return target instanceof LivingEntity mob&&mob.passive&&!mob.tamed;}
}''',
'buildcraft/robotics/boards/BoardRobotKnight.java': '''package buildcraft.robotics.boards;
import buildcraft.robotics.internal.legacy.robots.*;import net.minecraft.world.entity.*;
public class BoardRobotKnight extends AIRobot{
 public BoardRobotKnight(EntityRobotBase robot){super(robot);}
 public static boolean isHostileTarget(Entity target){return target instanceof LivingEntity mob&&mob.hostile;}
}''',
'buildcraft/robotics/tile/TileRobotProcessor.java': '''package buildcraft.robotics.tile;
import net.minecraft.core.BlockPos;import net.minecraft.world.level.Level;import java.util.*;
public class TileRobotProcessor{
 public static final int WORK_AREA_RADIUS_CHUNKS=3;
 public final List<Long> linked=new ArrayList<>();public Level level;public BlockPos pos;public boolean removed;
 public int queueLimit=2;
 public TileRobotProcessor(Level level,BlockPos pos){this.level=level;this.pos=pos;}
 public BlockPos getBlockPos(){return pos;}public Level getLevel(){return level;}
 public boolean isRemoved(){return removed;}public boolean isRobotLinked(long id){return linked.contains(id);}
 public List<Long> getLinkedRobotIds(){return linked;}
 public int getMaxExecutionQueue(){return queueLimit;}
 public boolean allowsWorkPosition(BlockPos pos){return Math.abs((pos.getX()>>4)-(this.pos.getX()>>4))<=3&&Math.abs((pos.getZ()>>4)-(this.pos.getZ()>>4))<=3;}
}'''
}

FIXTURE=r'''
package fixture;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import buildcraft.robotics.tile.TileRobotProcessor;
import buildcraft.robotics.scheduler.RobotCombatTargetScheduler;
import buildcraft.robotics.boards.*;
import buildcraft.robotics.internal.legacy.robots.*;
import buildcraft.robotics.ai.AIRobotSearchEntity;
import java.util.*;
public class CombatPanelFixture {
 static void ok(boolean v,String why){if(!v)throw new AssertionError(why);}
 static EntityRobotBase robot(ServerLevel level,long id,int x,boolean knight){
  var r=new EntityRobotBase(level,id,x,65,0);
  r.setBoard(knight?new BoardRobotKnight(r):new BoardRobotButcher(r));return r;
 }
 public static void main(String...args){
  ServerLevel level=new ServerLevel();
  TileRobotProcessor panel=new TileRobotProcessor(level,new BlockPos(0,65,0));
  TileRobotProcessor other=new TileRobotProcessor(level,new BlockPos(16,65,0));
  var a=robot(level,1,0,false);var b=robot(level,2,8,false);var c=robot(level,3,16,true);var d=robot(level,4,20,true);
  panel.linked.addAll(List.of(1L,2L,3L));other.linked.add(4L);
  var cow=new LivingEntity(3,65,0,false,true);
  var sheep=new LivingEntity(5,65,0,false,true);
  var named=new LivingEntity(7,65,0,false,true);named.tamed=true;
  var zombie=new LivingEntity(18,65,0,true,false);
  var protectedZombie=new LivingEntity(20,65,0,true,false);protectedZombie.protectedMob=true;
  var distant=new LivingEntity(500,65,0,true,false);
  level.entities.addAll(List.of(cow,sheep,named,zombie,protectedZombie,distant));
  var queueA=new RobotCombatTargetScheduler(panel);
  var queueB=new RobotCombatTargetScheduler(other);
  level.time=1;
  queueA.tick(level,List.of(a,b,c));
  ok(level.entityQueries==1,"N robots still each scanned all entities");
  ok(queueA.queueCount(1L)==1&&queueA.queueCount(2L)==1,"butchers not distributed separate targets");
  ok(queueA.queueCount(3L)==1,"knight not assigned hostile");
  ok(RobotCombatTargetScheduler.hasController(a),"connected butcher not managed");
  ok(!RobotCombatTargetScheduler.hasController(d),"unknown panel should not control robot before registration");
  queueB.tick(level,List.of(d));
  ok(queueB.queueCount(4L)==0,"second panel stole claimed hostile / protected entity");
  ok(queueA.queueCount(3L)==1,"first panel lost its claim");
  var hunt=RobotCombatTargetScheduler.acquire(a,BoardRobotButcher::isValidTarget,250F,null);
  ok(hunt!=null&&hunt.entity()!=sheep&&hunt.entity()!=named&&hunt.token()>0,"bad butcher target or no fenced claim");
  // Acquiring a combat target does not release it; it remains reserved until the attack ends.
  ok(RobotCombatTargetScheduler.acquire(a,BoardRobotButcher::isValidTarget,250F,null)==null,
    "active combat target assigned again to same robot");
  ok(queueA.queueCount(1L)==1,"attack did not retain claim");
  RobotCombatTargetScheduler.finished(a,hunt.entity().getUUID(),hunt.token());
  ok(queueA.queueCount(1L)==0,"attack completion failed to free the target");
  a.sleeping=true;
  level.time=25;
  queueA.tick(level,List.of(a,b,c));
  ok(RobotCombatTargetScheduler.hasAvailableTarget(a),"sleeping worker cannot see reserved work");
  a.gated=true;
  ok(!RobotCombatTargetScheduler.hasAvailableTarget(a),"Gate override unexpectedly woke worker");
  a.gated=false;
  panel.queueLimit=1;queueA.onQueueLimitChanged();
  ok(queueA.queueCount(1L)<=1,"queue shrink ignored");
  queueA.onRemoved();
  level.time=45;
  queueB.tick(level,List.of(d));
  ok(queueB.queueCount(4L)==1,"removed panel leaked hostile claim");
  // On a robot without a panel, the original AI still discovers nearby mobs.
  var independent=robot(level,99,0,false);
  var search=new AIRobotSearchEntity(independent,BoardRobotButcher::isValidTarget,250,null);
  int queries=level.entityQueries;search.start();
  ok(search.target!=null&&search.claimToken()==0,"autonomous search changed");
  ok(level.entityQueries==queries+1,"autonomous search stopped scanning");
  // In managed mode the search is purely an index lookup, no entity sweep.
  var selected=RobotCombatTargetScheduler.acquire(d,BoardRobotKnight::isHostileTarget,250,null);
  ok(selected!=null&&selected.entity()==zombie,"managed knight did not receive reserved zombie");
  int before=level.entityQueries;
  var managedSearch=new AIRobotSearchEntity(d,BoardRobotKnight::isHostileTarget,250,null);
  managedSearch.start();
  ok(level.entityQueries==before,"managed search unexpectedly scanned world");
  RobotCombatTargetScheduler.finished(d,selected.entity().getUUID(),selected.token());
  queueB.onRemoved();
  System.out.println("PASS: shared entity scan, butcher/knight filters, cross-panel lease, Gate, sleep, autonomous fallback");
 }
}
'''

SOURCES=[
 'source-shared/src/main/java/buildcraft/robotics/scheduler/RobotCombatTargetQueue.java',
 'source-shared/src/main/java/buildcraft/robotics/scheduler/RobotCombatTargetScheduler.java'
]

class CombatIntegrationTests(unittest.TestCase):
    def test_live_entity_selection_for_version_families(self):
        versions=(('1.19.2','old','forge'),('1.20.1','old','forge'),
                  ('1.21.11','1.21.X','neoforge'),('26.3','26.X','neoforge'),('26.3','26.X','fabric'))
        for version,family,loader in versions:
            with self.subTest(version=version,loader=loader),tempfile.TemporaryDirectory(prefix='bc-combat-') as td:
                base=Path(td);sources=[]
                for rel,source in STUBS.items():
                    p=base/rel;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(source);sources.append(str(p))
                for rel in SOURCES+[f'source-families/{"old" if family=="old" else "1.21.X"}/src/main/java/buildcraft/robotics/ai/AIRobotSearchEntity.java']:
                    p=base/('buildcraft/robotics/ai/AIRobotSearchEntity.java' if 'AIRobotSearchEntity' in rel else rel.removeprefix('source-shared/src/main/java/'))
                    p.parent.mkdir(parents=True,exist_ok=True)
                    text=preprocess_text((ROOT/rel).read_text(),minecraft=version,family=family,platform=loader,source=rel)
                    p.write_text(text);sources.append(str(p))
                fixture=base/'fixture/CombatPanelFixture.java'
                fixture.parent.mkdir(parents=True,exist_ok=True);fixture.write_text(FIXTURE);sources.append(str(fixture))
                proc=subprocess.run(['javac','--release','17','-d',str(base/'classes'),*sources],
                                    capture_output=True,text=True,timeout=60)
                self.assertEqual(proc.returncode,0,proc.stdout+proc.stderr)
                proc=subprocess.run(['java','-cp',str(base/'classes'),'fixture.CombatPanelFixture'],
                                    capture_output=True,text=True,timeout=20)
                self.assertEqual(proc.returncode,0,proc.stdout+proc.stderr)

if __name__=='__main__': unittest.main()
