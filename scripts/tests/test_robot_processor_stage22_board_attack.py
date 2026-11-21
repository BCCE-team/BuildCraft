"""Compile actual Butcher/Knight boards and actual Attack AI with combat claims."""
from pathlib import Path
import subprocess
import tempfile
import unittest
from scripts.source_preprocessor import preprocess_text
from scripts.tests.test_robot_processor_stage22_combat_runtime import ROOT, STUBS

EXTRA={
'buildcraft/lib/internal/core/IStackFilter.java': '''package buildcraft.lib.internal.core;
public interface IStackFilter {boolean matches(net.minecraft.world.item.ItemStack stack);}''',
'buildcraft/robotics/internal/legacy/boards/RedstoneBoardRobotNBT.java': '''package buildcraft.robotics.internal.legacy.boards;
public class RedstoneBoardRobotNBT {}''',
'buildcraft/robotics/internal/legacy/boards/RedstoneBoardRobot.java': '''package buildcraft.robotics.internal.legacy.boards;
import buildcraft.robotics.internal.legacy.robots.*;
public class RedstoneBoardRobot extends AIRobot{
 public RedstoneBoardRobot(EntityRobotBase robot){super(robot);}
 public RedstoneBoardRobotNBT getNBTHandler(){return null;}
}''',
'buildcraft/robotics/BCRoboticsBoards.java': '''package buildcraft.robotics;
import buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobotNBT;
public class BCRoboticsBoards{
 public static Entry getByKey(String key){return new Entry();}
 public static class Entry{public RedstoneBoardRobotNBT nbt(){return new RedstoneBoardRobotNBT();}}
}''',
'buildcraft/robotics/boards/RobotBoardUtil.java': '''package buildcraft.robotics.boards;
public class RobotBoardUtil{
 public static void dropHeldItem(buildcraft.robotics.internal.legacy.robots.EntityRobotBase robot){}
}''',
'buildcraft/robotics/ai/AIRobotFetchAndEquipItemStack.java': '''package buildcraft.robotics.ai;
import buildcraft.robotics.internal.legacy.robots.*;
import buildcraft.lib.internal.core.IStackFilter;
public class AIRobotFetchAndEquipItemStack extends AIRobot{
 public AIRobotFetchAndEquipItemStack(EntityRobotBase robot,IStackFilter filter){super(robot);}
}''',
'buildcraft/robotics/ai/AIRobotGotoSleep.java': '''package buildcraft.robotics.ai;
import buildcraft.robotics.internal.legacy.robots.*;
public class AIRobotGotoSleep extends AIRobot{public AIRobotGotoSleep(EntityRobotBase robot){super(robot);}}
''',
'buildcraft/robotics/ai/AIRobotGotoStationAndUnload.java': '''package buildcraft.robotics.ai;
import buildcraft.robotics.internal.legacy.robots.*;
public class AIRobotGotoStationAndUnload extends AIRobot{public AIRobotGotoStationAndUnload(EntityRobotBase robot){super(robot);}}
''',
'buildcraft/robotics/ai/AIRobotGotoBlock.java': '''package buildcraft.robotics.ai;
import buildcraft.robotics.internal.legacy.robots.*;
public class AIRobotGotoBlock extends AIRobot{public AIRobotGotoBlock(EntityRobotBase robot,int x,int y,int z){super(robot);}}
''',
'buildcraft/robotics/entity/EntityRobot.java': '''package buildcraft.robotics.entity;
import buildcraft.robotics.internal.legacy.robots.*;
import net.minecraft.world.level.Level;import net.minecraft.world.entity.Entity;
public class EntityRobot extends EntityRobotBase{
 public EntityRobot(Level level,long id,double x,double y,double z){super(level,id,x,y,z);}
 public void attackTargetEntityWithCurrentItem(Entity entity){}
}''',
'net/minecraft/world/entity/MobCategory.java': '''package net.minecraft.world.entity;public enum MobCategory{CREATURE,MONSTER}''',
'net/minecraft/world/entity/EntityType.java': '''package net.minecraft.world.entity;
public class EntityType<T>{private final MobCategory category;public EntityType(MobCategory category){this.category=category;}
 public MobCategory getCategory(){return category;}}
''',
'net/minecraft/world/entity/animal/Animal.java': '''package net.minecraft.world.entity.animal;
import net.minecraft.world.entity.LivingEntity;
public class Animal extends LivingEntity{
 public boolean named=false;
 public Animal(double x,double y,double z){super(x,y,z,false,true);}
 public boolean hasCustomName(){return named;}
}''',
'net/minecraft/world/entity/TamableAnimal.java': '''package net.minecraft.world.entity;
public class TamableAnimal extends net.minecraft.world.entity.animal.Animal{
 public TamableAnimal(double x,double y,double z){super(x,y,z);}
 public boolean isTame(){return tamed;}
}''',
'net/minecraft/world/entity/animal/Cat.java': '''package net.minecraft.world.entity.animal;
public class Cat extends net.minecraft.world.entity.TamableAnimal{public Cat(){super(0,65,0);}}''',
'net/minecraft/world/entity/animal/Ocelot.java': '''package net.minecraft.world.entity.animal;
public class Ocelot extends Animal{public Ocelot(){super(0,65,0);}}''',
'net/minecraft/world/entity/animal/Wolf.java': '''package net.minecraft.world.entity.animal;
public class Wolf extends net.minecraft.world.entity.TamableAnimal{
 public boolean angry;public Wolf(){super(0,65,0);}public boolean isAngry(){return angry;}}
''',
'net/minecraft/world/entity/animal/horse/AbstractHorse.java': '''package net.minecraft.world.entity.animal.horse;
public class AbstractHorse extends net.minecraft.world.entity.animal.Animal{
 public AbstractHorse(){super(0,65,0);}public boolean isTamed(){return tamed;}}
''',
'net/minecraft/world/entity/monster/Enemy.java': '''package net.minecraft.world.entity.monster;public interface Enemy {}''',
}

# Build a complete isolated classpath from the same Minecraft signature shims.
# We exercise source code from both source families, not reconstructed logic.
AI='''package buildcraft.robotics.internal.legacy.robots;
public class AIRobot{
 public EntityRobotBase robot;protected boolean ok=true;
 public AIRobot(EntityRobotBase r){robot=r;}
 public void start(){}public void update(){}public void preempt(AIRobot other){}
 public void terminate(){end();} public void end(){}public void onUnload(){}
 public void delegateAIEnded(AIRobot other){}public boolean success(){return ok;}
 protected void setSuccess(boolean ok){this.ok=ok;}
 public void startDelegateAI(AIRobot other){}public void abortDelegateAI(){}
 public int getEnergyCost(){return 1;}public int getExecutionProgress(){return -1;}
 public boolean isWithinWorkChain(AIRobot board){return this==board;}
 public static class SleepingAI extends AIRobot {
  public AIRobot board; public SleepingAI(EntityRobotBase r,AIRobot board){super(r);this.board=board;}
  @Override public boolean isWithinWorkChain(AIRobot other){return other==board;}
 }
}'''

FIXTURE='''package fixture;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.animal.*;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import buildcraft.robotics.boards.*;
import buildcraft.robotics.ai.*;
import buildcraft.robotics.internal.legacy.robots.*;
import buildcraft.robotics.tile.TileRobotProcessor;
import buildcraft.robotics.scheduler.*;
import java.util.*;
public class CombatBoardsFixture {
 static void ok(boolean b,String m){if(!b)throw new AssertionError(m);}
 public static void main(String...args){
  var world=new ServerLevel();
  var robot=new EntityRobotBase(world,91,0,65,0);
  var cow=new Animal(2,65,0);var named=new Animal(2,65,0);named.named=true;
  var wolf=new Wolf();wolf.angry=true;
  var horse=new AbstractHorse();horse.tamed=true;
  var monster=new LivingEntity(5,65,0,true,false);
  ok(BoardRobotButcher.isValidTarget(cow),"butcher rejected ordinary animal");
  ok(!BoardRobotButcher.isValidTarget(named),"butcher attacked named pet");
  ok(!BoardRobotButcher.isValidTarget(wolf),"butcher attacked wolf");
  ok(!BoardRobotButcher.isValidTarget(horse),"butcher attacked tamed horse");
  ok(BoardRobotKnight.isHostileTarget(monster),"knight rejected hostile mob");
  ok(BoardRobotKnight.isHostileTarget(wolf),"knight rejected angry wolf");
  ok(!BoardRobotKnight.isHostileTarget(cow),"knight attacked cow");
  var panel=new TileRobotProcessor(world,new BlockPos(0,65,0));panel.linked.add(91L);
  robot.setBoard(new BoardRobotButcher(robot));world.entities.add(cow);
  var scheduler=new RobotCombatTargetScheduler(panel);world.time=1;
  scheduler.tick(world,List.of(robot));
  var assigned=RobotCombatTargetScheduler.acquire(robot,BoardRobotButcher::isValidTarget,250,null);
  ok(assigned!=null,"failed to reserve real board target");
  var attack=new AIRobotAttack(robot,assigned.entity(),assigned.token());
  ok(scheduler.queueCount(91)==1,"target not reserved while attacking");
  attack.end();
  ok(scheduler.queueCount(91)==0,"real attack end did not release lease");
  world.time=22;scheduler.tick(world,List.of(robot));
  assigned=RobotCombatTargetScheduler.acquire(robot,BoardRobotButcher::isValidTarget,250,null);
  ok(assigned!=null,"did not reacquire after end");
  var unloading=new AIRobotAttack(robot,assigned.entity(),assigned.token());
  unloading.onUnload();
  ok(scheduler.queueCount(91)==0,"real attack unload did not release lease");
  world.time=43;scheduler.tick(world,List.of(robot));
  assigned=RobotCombatTargetScheduler.acquire(robot,BoardRobotButcher::isValidTarget,250,null);
  ok(assigned!=null,"missing freshly reserved target before naming");
  var renamedDuringAttack=new AIRobotAttack(robot,assigned.entity(),assigned.token());
  cow.named=true;
  renamedDuringAttack.update();
  ok(scheduler.queueCount(91)==0,"butcher kept attacking newly named animal");
  scheduler.onRemoved();
  System.out.println("PASS: real butcher/knight filters and real combat attack claim release");
 }
}'''

class CombatBoardAndAttackTests(unittest.TestCase):
 def test_actual_boards_and_attack_for_all_source_families(self):
  for version,family,platform in [('1.19.2','old','forge'),('1.21.11','1.21.X','neoforge'),('26.3','26.X','fabric')]:
   with self.subTest(version=version,platform=platform),tempfile.TemporaryDirectory(prefix='bc-combat-boards-') as td:
    base=Path(td);stubs=dict(STUBS);stubs.update(EXTRA)
    stubs.pop('buildcraft/robotics/boards/BoardRobotButcher.java')
    stubs.pop('buildcraft/robotics/boards/BoardRobotKnight.java')
    stubs['buildcraft/robotics/internal/legacy/robots/AIRobot.java']=AI
    stubs['net/minecraft/world/entity/Entity.java']=stubs['net/minecraft/world/entity/Entity.java'].replace(
      ' public boolean isAlive(){return alive;}',
      ' public net.minecraft.world.entity.EntityType<?> getType(){return new net.minecraft.world.entity.EntityType<>(net.minecraft.world.entity.MobCategory.CREATURE);}\n public boolean isAlive(){return alive;}')
    stubs['net/minecraft/world/entity/LivingEntity.java']=stubs['net/minecraft/world/entity/LivingEntity.java'].replace(
      'public LivingEntity(double x,double y,double z,boolean hostile,boolean passive){super(x,y,z);',
      'public float getMaxHealth(){return 20F;}public float getHealth(){return 10F;}\n @Override public net.minecraft.world.entity.EntityType<?> getType(){return new net.minecraft.world.entity.EntityType<>(hostile?net.minecraft.world.entity.MobCategory.MONSTER:net.minecraft.world.entity.MobCategory.CREATURE);}\n public LivingEntity(double x,double y,double z,boolean hostile,boolean passive){super(x,y,z);')
    stubs['buildcraft/robotics/internal/legacy/robots/EntityRobotBase.java']=stubs['buildcraft/robotics/internal/legacy/robots/EntityRobotBase.java'].replace(
      'public AABB getBoundingBox()',
      'public void setItemActive(boolean v){} public void aimItemAt(BlockPos pos){} public void unreachableEntityDetected(net.minecraft.world.entity.Entity ent){}\n public AABB getBoundingBox()')
    files=[]
    for rel,code in stubs.items():
     f=base/rel;f.parent.mkdir(parents=True,exist_ok=True);f.write_text(code);files.append(str(f))
    sourcefiles=['source-shared/src/main/java/buildcraft/robotics/boards/BoardRobotButcher.java',
                 'source-shared/src/main/java/buildcraft/robotics/boards/BoardRobotKnight.java',
                 'source-shared/src/main/java/buildcraft/robotics/ai/AIRobotAttack.java',
                 'source-shared/src/main/java/buildcraft/robotics/scheduler/RobotCombatTargetQueue.java',
                 'source-shared/src/main/java/buildcraft/robotics/scheduler/RobotCombatTargetScheduler.java',
                 f'source-families/{"old" if family=="old" else "1.21.X"}/src/main/java/buildcraft/robotics/ai/AIRobotSearchEntity.java']
    for rel in sourcefiles:
     dest=base/('buildcraft/robotics/ai/AIRobotSearchEntity.java' if 'AIRobotSearchEntity' in rel else rel.removeprefix('source-shared/src/main/java/'))
     dest.parent.mkdir(parents=True,exist_ok=True)
     code=preprocess_text((ROOT/rel).read_text(),minecraft=version,family=family,platform=platform,source=rel)
     dest.write_text(code);files.append(str(dest))
    f=base/'fixture/CombatBoardsFixture.java';f.parent.mkdir(parents=True,exist_ok=True);f.write_text(FIXTURE);files.append(str(f))
    result=subprocess.run(['javac','--release','17','-d',str(base/'classes'),*files],capture_output=True,text=True,timeout=60)
    self.assertEqual(result.returncode,0,result.stdout+result.stderr)
    result=subprocess.run(['java','-cp',str(base/'classes'),'fixture.CombatBoardsFixture'],capture_output=True,text=True,timeout=20)
    self.assertEqual(result.returncode,0,result.stdout+result.stderr)

if __name__=='__main__':unittest.main()
