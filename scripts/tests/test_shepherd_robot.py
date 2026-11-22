"""Compile and execute the real Shepherd board and sheep-shearing AI against Java API doubles.

These are deliberately offline probes, not substitutes for in-game Forge/Fabric GameTests.
"""
from __future__ import annotations

import json
from pathlib import Path
import subprocess
import tempfile
import unittest

from scripts.source_preprocessor import preprocess_text

ROOT = Path(__file__).resolve().parents[2]
JAVA = [
    "source-shared/src/main/java/buildcraft/robotics/boards/BoardRobotShepherd.java",
    "source-shared/src/main/java/buildcraft/robotics/ai/AIRobotShearSheep.java",
]

STUBS = {
    "net/minecraft/core/BlockPos.java": """package net.minecraft.core;
public record BlockPos(int x,int y,int z){public int getX(){return x;}public int getY(){return y;}public int getZ(){return z;}}""",
    "net/minecraft/world/phys/Vec3.java": """package net.minecraft.world.phys;
public record Vec3(double x,double y,double z){}""",
    "net/minecraft/world/phys/AABB.java": """package net.minecraft.world.phys;
public class AABB {public AABB inflate(double n){return this;}}""",
    "com/mojang/authlib/GameProfile.java": """package com.mojang.authlib;public class GameProfile {}""",
    "net/minecraft/world/InteractionHand.java": """package net.minecraft.world;public enum InteractionHand{MAIN_HAND}""",
    "net/minecraft/world/entity/EquipmentSlot.java": """package net.minecraft.world.entity;public enum EquipmentSlot{MAINHAND}""",
    "net/minecraft/world/entity/Entity.java": """package net.minecraft.world.entity;
import java.util.UUID;import net.minecraft.core.BlockPos;import net.minecraft.world.phys.*;
public class Entity {
 public boolean alive=true,removed=false;public double x,y,z;
 public final UUID uuid=UUID.randomUUID();
 public boolean isAlive(){return alive;}public boolean isRemoved(){return removed;}
 public UUID getUUID(){return uuid;}public double getX(){return x;}public double getY(){return y;}public double getZ(){return z;}
 public BlockPos blockPosition(){return new BlockPos((int)x,(int)y,(int)z);}
 public Vec3 position(){return new Vec3(x,y,z);}
 public AABB getBoundingBox(){return new AABB();}
 public double distanceToSqr(Entity other){double dx=x-other.x,dy=y-other.y,dz=z-other.z;return dx*dx+dy*dy+dz*dz;}
}""",
    "net/minecraft/world/entity/animal/Sheep.java": """package net.minecraft.world.entity.animal;
public class Sheep extends net.minecraft.world.entity.Entity {
 public boolean baby,sheared;public boolean isBaby(){return baby;}public boolean isSheared(){return sheared;}
}""",
    "net/minecraft/world/entity/animal/sheep/Sheep.java": """package net.minecraft.world.entity.animal.sheep;
public class Sheep extends net.minecraft.world.entity.Entity {
 public boolean baby,sheared;public boolean isBaby(){return baby;}public boolean isSheared(){return sheared;}
}""",
    "net/minecraft/world/item/Item.java": """package net.minecraft.world.item;public class Item{}""",
    "net/minecraft/world/item/ShearsItem.java": """package net.minecraft.world.item;public class ShearsItem extends Item{}""",
    "net/minecraft/world/item/BlockItem.java": """package net.minecraft.world.item;
public class BlockItem extends Item{private final net.minecraft.world.level.block.Block block;
 public BlockItem(net.minecraft.world.level.block.Block block){this.block=block;}
 public net.minecraft.world.level.block.Block getBlock(){return block;}}""",
    "net/minecraft/world/item/ItemStack.java": """package net.minecraft.world.item;
public class ItemStack {
 public static final ItemStack EMPTY=new ItemStack(null,0);private final Item item;private int count,damage;
 public ItemStack(Item item){this(item,1);}public ItemStack(Item item,int count){this.item=item;this.count=count;}
 public boolean isEmpty(){return item==null||count==0;}public Item getItem(){return item;}
 public int getCount(){return count;}public void setCount(int n){count=n;}
 public boolean isDamageableItem(){return item instanceof ShearsItem;}
 public int getDamageValue(){return damage;}public int getMaxDamage(){return 238;}
 public void damage(int n){damage+=n;if(damage>=238)count=0;}
 public ItemStack copy(){ItemStack result=new ItemStack(item,count);result.damage=damage;return result;}
}""",
    "net/minecraft/tags/BlockTags.java": """package net.minecraft.tags;public final class BlockTags{public static final Object WOOL=new Object();}""",
    "net/minecraft/world/level/block/Block.java": """package net.minecraft.world.level.block;
public class Block {public net.minecraft.world.level.block.state.BlockState defaultBlockState(){return new net.minecraft.world.level.block.state.BlockState();}}""",
    "net/minecraft/world/level/block/state/BlockState.java": """package net.minecraft.world.level.block.state;
public class BlockState{public boolean is(Object tag){return tag==net.minecraft.tags.BlockTags.WOOL;}}""",
    "net/minecraft/world/entity/item/ItemEntity.java": """package net.minecraft.world.entity.item;
import net.minecraft.world.entity.Entity;import net.minecraft.world.item.ItemStack;
public class ItemEntity extends Entity{private ItemStack item;
 public ItemEntity(ItemStack item){this.item=item;}public ItemStack getItem(){return item;}
 public void setItem(ItemStack item){this.item=item;}public void discard(){removed=true;}}""",
    "net/minecraft/world/level/Level.java": """package net.minecraft.world.level;public class Level {}""",
    "net/minecraft/server/level/ServerLevel.java": """package net.minecraft.server.level;
import java.util.*;import net.minecraft.world.entity.item.ItemEntity;import net.minecraft.world.phys.AABB;
public class ServerLevel extends net.minecraft.world.level.Level{
 public final List<ItemEntity> drops=new ArrayList<>();
 public boolean hasChunkAt(net.minecraft.core.BlockPos pos){return true;}
 public <T extends net.minecraft.world.entity.Entity> List<T> getEntitiesOfClass(Class<T> type,AABB box,java.util.function.Predicate<? super T> filter){
  List<T> out=new ArrayList<>();for(ItemEntity item:drops)if(!item.removed&&type.isInstance(item)&&filter.test(type.cast(item)))out.add(type.cast(item));return out;}
}""",
    "net/minecraft/world/entity/player/Player.java": """package net.minecraft.world.entity.player;
import net.minecraft.world.InteractionHand;import net.minecraft.world.item.*;import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.server.level.ServerLevel;
public class Player extends net.minecraft.world.entity.Entity{
 private ItemStack hand=ItemStack.EMPTY;public final ServerLevel world;
 public Player(ServerLevel world){this.world=world;}
 public void setPos(double x,double y,double z){this.x=x;this.y=y;this.z=z;}
 public void setItemInHand(InteractionHand slot,ItemStack item){hand=item;}
 public ItemStack getItemInHand(InteractionHand slot){return hand;}
 public boolean interactOn(net.minecraft.world.entity.Entity target,InteractionHand hand){return shear(target);}
 public boolean interactOn(net.minecraft.world.entity.Entity target,InteractionHand hand,net.minecraft.world.phys.Vec3 hit){return shear(target);}
 private boolean shear(net.minecraft.world.entity.Entity target){
  boolean baby=false,sheared=false; if(target instanceof net.minecraft.world.entity.animal.Sheep s){baby=s.baby;sheared=s.sheared;}
  else if(target instanceof net.minecraft.world.entity.animal.sheep.Sheep s){baby=s.baby;sheared=s.sheared;}
  else return false;
  if(baby||sheared||hand.isEmpty()||!(hand.getItem() instanceof ShearsItem))return false;
  if(target instanceof net.minecraft.world.entity.animal.Sheep s)s.sheared=true;
  else ((net.minecraft.world.entity.animal.sheep.Sheep)target).sheared=true;
  world.drops.add(new ItemEntity(new ItemStack(new BlockItem(new net.minecraft.world.level.block.Block()),2)));
  hand.damage(1);return true;}
}""",
    "buildcraft/api/v2/OperationMode.java": """package buildcraft.api.v2;public enum OperationMode{EXECUTE,SIMULATE}""",
    "buildcraft/api/v2/permission/WorldOperationKind.java": """package buildcraft.api.v2.permission;public enum WorldOperationKind{ENTITY_INTERACT}""",
    "buildcraft/lib/misc/FakePlayerProvider.java": """package buildcraft.lib.misc;
import com.mojang.authlib.GameProfile;import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;import net.minecraft.core.BlockPos;
public class FakePlayerProvider{public static final FakePlayerProvider INSTANCE=new FakePlayerProvider();
 public Player getFakePlayer(ServerLevel level,GameProfile profile,BlockPos pos){return new Player(level);}}""",
    "buildcraft/lib/internal/core/IStackFilter.java": """package buildcraft.lib.internal.core;
public interface IStackFilter{boolean matches(net.minecraft.world.item.ItemStack stack);}""",
    "buildcraft/robotics/internal/legacy/boards/RedstoneBoardRobotNBT.java": """package buildcraft.robotics.internal.legacy.boards;public class RedstoneBoardRobotNBT{}""",
    "buildcraft/robotics/internal/legacy/boards/RedstoneBoardRobot.java": """package buildcraft.robotics.internal.legacy.boards;
import buildcraft.robotics.internal.legacy.robots.*;
public class RedstoneBoardRobot extends AIRobot{public RedstoneBoardRobot(EntityRobotBase robot){super(robot);}
 public RedstoneBoardRobotNBT getNBTHandler(){return null;}}""",
    "buildcraft/robotics/internal/legacy/robots/AIRobot.java": """package buildcraft.robotics.internal.legacy.robots;
public class AIRobot{protected final EntityRobotBase robot;private boolean success=true;public AIRobot(EntityRobotBase r){robot=r;}
 public AIRobot delegate;public void update(){}public void preempt(AIRobot ai){}public void startDelegateAI(AIRobot other){delegate=other;}
 public void abortDelegateAI(){}public void terminate(){end();}public void end(){}public void onUnload(){}
 public void delegateAIEnded(AIRobot ai){}public boolean success(){return success;}protected void setSuccess(boolean x){success=x;}
 public int getEnergyCost(){return 1;}}""",
    "buildcraft/robotics/internal/legacy/robots/EntityRobotBase.java": """package buildcraft.robotics.internal.legacy.robots;
import net.minecraft.world.entity.*;import net.minecraft.world.item.*;import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;import net.minecraft.world.phys.*;
public class EntityRobotBase extends Entity{
 public final ServerLevel world;public ItemStack held=new ItemStack(new ShearsItem());
 public boolean free=true,station=true,contains=false;public net.minecraft.world.entity.Entity goal;
 public EntityRobotBase(ServerLevel world){this.world=world;}
 public net.minecraft.world.level.Level getCommandSenderWorld(){return world;}
 public ItemStack getItemBySlot(EquipmentSlot slot){return held;}
 public void setItemInUse(ItemStack item){held=item.copy();}
 public void setItemActive(boolean value){}public void aimItemAt(BlockPos pos){}
 public boolean hasFreeSlot(){return free;}public boolean containsItems(){return contains;}
 public Object getLinkedStation(){return station?new Object():null;}
 public net.minecraft.world.phys.AABB getBoundingBox(){return new AABB();}
 public void unreachableEntityDetected(Entity entity){}
 public net.minecraft.world.entity.Entity getTarget(){return goal;}
 public buildcraft.lib.internal.area.IZone getZoneToWork(){return null;}
}""",
    "buildcraft/lib/internal/area/IZone.java": """package buildcraft.lib.internal.area;
public interface IZone{boolean contains(net.minecraft.world.phys.Vec3 p);}""",
    "buildcraft/robotics/entity/EntityRobot.java": """package buildcraft.robotics.entity;
import net.minecraft.server.level.ServerLevel;import net.minecraft.world.item.ItemStack;
public class EntityRobot extends buildcraft.robotics.internal.legacy.robots.EntityRobotBase{
 public int cargo;public ItemStack lastCargo;
 public EntityRobot(ServerLevel world){super(world);}
 public ItemStack insertIntoInventory(ItemStack stack){cargo+=stack.getCount();lastCargo=stack.copy();return ItemStack.EMPTY;}
}""",
    "buildcraft/robotics/internal/api2/RobotAutomationSupport.java": """package buildcraft.robotics.internal.api2;
import buildcraft.api.v2.OperationMode;import buildcraft.api.v2.permission.WorldOperationKind;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import net.minecraft.world.entity.Entity;import net.minecraft.world.level.Level;
import com.mojang.authlib.GameProfile;
public class RobotAutomationSupport{
 public static boolean allowed=true;
 public static boolean permitsEntity(EntityRobotBase robot,Level level,Entity e,WorldOperationKind kind,OperationMode mode){return allowed;}
 public static GameProfile owner(EntityRobotBase robot){return new GameProfile();}
}""",
    "buildcraft/robotics/scheduler/RobotCombatTargetScheduler.java": """package buildcraft.robotics.scheduler;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;import java.util.UUID;
public class RobotCombatTargetScheduler{
 public static int finishes;public static void finished(EntityRobotBase robot,UUID target,long claim){finishes++;}
}""",
    "buildcraft/robotics/BCRoboticsBoards.java": """package buildcraft.robotics;
import buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobotNBT;
public class BCRoboticsBoards{
 public static class Entry{public RedstoneBoardRobotNBT nbt(){return new RedstoneBoardRobotNBT();}}
 public static Entry getByKey(String key){return new Entry();}
}""",
    "buildcraft/robotics/boards/RobotBoardUtil.java": """package buildcraft.robotics.boards;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
public class RobotBoardUtil{public static boolean dropHeldItem(EntityRobotBase r){r.held=net.minecraft.world.item.ItemStack.EMPTY;return true;}}
""",
    "buildcraft/robotics/ai/AIRobotGotoBlock.java": """package buildcraft.robotics.ai;
public class AIRobotGotoBlock extends buildcraft.robotics.internal.legacy.robots.AIRobot{
 public AIRobotGotoBlock(buildcraft.robotics.internal.legacy.robots.EntityRobotBase r,int x,int y,int z){super(r);}}
""",
    "buildcraft/robotics/ai/AIRobotSearchEntity.java": """package buildcraft.robotics.ai;
public class AIRobotSearchEntity extends buildcraft.robotics.internal.legacy.robots.AIRobot{
 public net.minecraft.world.entity.Entity target;public AIRobotSearchEntity(buildcraft.robotics.internal.legacy.robots.EntityRobotBase r,java.util.function.Predicate<net.minecraft.world.entity.Entity> p,float range,buildcraft.lib.internal.area.IZone z){super(r);}
 public long claimToken(){return 0;}}
""",
    "buildcraft/robotics/ai/AIRobotFetchAndEquipItemStack.java": """package buildcraft.robotics.ai;
public class AIRobotFetchAndEquipItemStack extends buildcraft.robotics.internal.legacy.robots.AIRobot{
 public AIRobotFetchAndEquipItemStack(buildcraft.robotics.internal.legacy.robots.EntityRobotBase r,buildcraft.lib.internal.core.IStackFilter f){super(r);}}
""",
    "buildcraft/robotics/ai/AIRobotGotoSleep.java": """package buildcraft.robotics.ai;
public class AIRobotGotoSleep extends buildcraft.robotics.internal.legacy.robots.AIRobot{
 public AIRobotGotoSleep(buildcraft.robotics.internal.legacy.robots.EntityRobotBase r){super(r);}}
""",
    "buildcraft/robotics/ai/AIRobotGotoStationAndUnload.java": """package buildcraft.robotics.ai;
public class AIRobotGotoStationAndUnload extends buildcraft.robotics.internal.legacy.robots.AIRobot{
 public AIRobotGotoStationAndUnload(buildcraft.robotics.internal.legacy.robots.EntityRobotBase r){super(r);}}
""",
}

HARNESS = r"""package fixture;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import buildcraft.robotics.ai.AIRobotShearSheep;
import buildcraft.robotics.boards.BoardRobotShepherd;
import buildcraft.robotics.entity.EntityRobot;
import buildcraft.robotics.internal.api2.RobotAutomationSupport;
import buildcraft.robotics.scheduler.RobotCombatTargetScheduler;
public class ShepherdProbe {
 static void check(boolean yes,String message){if(!yes)throw new AssertionError(message);}
 static Object sheep(boolean baby,boolean sheared){
  // Filled in via the compiled version of the test harness.
  return null;
 }
 public static void main(String[] args){
  ServerLevel world=new ServerLevel();EntityRobot robot=new EntityRobot(world);
  check(!BoardRobotShepherd.isUsableShears(new ItemStack(new Item())),"Accepted wrong tool");
  check(BoardRobotShepherd.isUsableShears(new ItemStack(new ShearsItem())),"Rejected shears");
  SHEEP_TYPE sheep=new SHEEP_TYPE();
  check(BoardRobotShepherd.isValidTarget(sheep),"Eligible adult not found");
  sheep.baby=true;check(!BoardRobotShepherd.isValidTarget(sheep),"Selected baby sheep");sheep.baby=false;
  sheep.sheared=true;check(!BoardRobotShepherd.isValidTarget(sheep),"Selected already-sheared sheep");sheep.sheared=false;
  ItemEntity preexisting=new ItemEntity(new ItemStack(new BlockItem(new net.minecraft.world.level.block.Block()),5));
  world.drops.add(preexisting);
   AIRobotShearSheep ai=new AIRobotShearSheep(robot,sheep,44L);
   // At 2-3 blocks the shepherd must navigate, not shear at a distance.
   sheep.x=2.5;
   for(int tick=0;tick<13;tick++)ai.update();
   check(!sheep.sheared && world.drops.size()==1 && robot.held.getDamageValue()==0,
       "Shepherd sheared a sheep from 2.5 blocks away");
   check(ai.delegate instanceof buildcraft.robotics.ai.AIRobotGotoBlock,
       "Shepherd did not begin approaching a distant sheep");
   // Even just outside the 1.5-block reach, it must still keep approaching.
   sheep.x=1.6;
   ai.update();
   check(!sheep.sheared && ai.delegate instanceof buildcraft.robotics.ai.AIRobotGotoBlock,
       "Shepherd sheared or stopped moving outside the 1.5-block reach");
   // After moving close enough it can interrupt navigation and shear as usual.
   sheep.x=1.25;
   ai.preempt(ai.delegate);
  for(int tick=0;tick<13;tick++)ai.update();
  check(sheep.sheared,"Vanilla interaction did not shear sheep");
  check(robot.cargo==0,"Shepherd collected wool instead of leaving it for Picker Robots");
  check(world.drops.size()==2 && !world.drops.get(1).removed
      && world.drops.get(1).getItem().getCount()==2,"Freshly sheared wool did not stay on the ground");
  check(robot.held.getDamageValue()==1,"Shears durability not updated");
  check(!preexisting.removed && preexisting.getItem().getCount()==5,"Stole preexisting wool");
  check(RobotCombatTargetScheduler.finishes>0,"Entity lease was not released");
  check(sheep.isAlive(),"Sheep was harmed");
  sheep.sheared=false;RobotAutomationSupport.allowed=false;
  int count=world.drops.size();int damage=robot.held.getDamageValue();
  AIRobotShearSheep denied=new AIRobotShearSheep(robot,sheep,0);
  for(int tick=0;tick<13;tick++)denied.update();
  check(!sheep.sheared && world.drops.size()==count && robot.held.getDamageValue()==damage,
       "Entity protection was bypassed");
  RobotAutomationSupport.allowed=true;
  robot.free=false;robot.contains=true;
  BoardRobotShepherd board=new BoardRobotShepherd(robot);
  board.update();
  check(board.delegate instanceof buildcraft.robotics.ai.AIRobotSearchEntity,
      "Shepherd tried to unload inventory instead of searching for sheep");
  AIRobotShearSheep full=new AIRobotShearSheep(robot,sheep,0);
  for(int tick=0;tick<13;tick++)full.update();
  check(sheep.sheared && world.drops.size()==count+1,
      "Full cargo inventory prevented shearing or lost the wool drop");
  check(robot.cargo==0,"Shepherd collected wool while inventory was full");
  check(robot.held.getDamageValue()==damage+1,"Full inventory prevented shears durability loss");
  System.out.println("Shepherd PASS: vanilla shearing, drops for Picker, full cargo, shears, permissions, lease");
 }
}"""


class ShepherdRobotTests(unittest.TestCase):
    def test_real_java_shepherd_behavior(self):
        for version, family, platform in (("1.19.2", "old", "forge"),
                                          ("1.20.1", "old", "forge"),
                                          ("1.21.1", "1.21.X", "neoforge"),
                                          ("1.21.11", "1.21.X", "neoforge"),
                                          ("26.1.2", "26.X", "neoforge"),
                                          ("26.2", "26.X", "neoforge"),
                                          ("26.3", "26.X", "neoforge"),
                                          ("26.3", "26.X", "fabric")):
            with self.subTest(version=version, platform=platform), tempfile.TemporaryDirectory(prefix="bc-shepherd-") as tmp:
                root = Path(tmp)
                sources = []
                for rel, contents in STUBS.items():
                    path = root / rel
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text(contents)
                    sources.append(str(path))
                for rel in JAVA:
                    text = preprocess_text((ROOT / rel).read_text(), minecraft=version, family=family,
                                           platform=platform, source=rel)
                    path = root / rel.removeprefix("source-shared/src/main/java/")
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text(text)
                    sources.append(str(path))
                sheep_class = ("net.minecraft.world.entity.animal.sheep.Sheep" if version in ("1.21.11", "26.1.2", "26.2", "26.3")
                               else "net.minecraft.world.entity.animal.Sheep")
                probe = root / "fixture/ShepherdProbe.java"
                probe.parent.mkdir(parents=True, exist_ok=True)
                probe.write_text(HARNESS.replace("SHEEP_TYPE", sheep_class))
                sources.append(str(probe))
                classes = root / "classes"
                classes.mkdir()
                compilation = subprocess.run(["javac", "--release", "17", "-d", str(classes), *sources],
                                             capture_output=True, text=True, timeout=45)
                self.assertEqual(0, compilation.returncode, compilation.stderr)
                result = subprocess.run(["java", "-cp", str(classes), "fixture.ShepherdProbe"],
                                        capture_output=True, text=True, timeout=20)
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                self.assertIn("Shepherd PASS", result.stdout)

    def test_registration_and_reload_contracts_all_targets(self):
        # RobotManager requires public (EntityRobotBase) constructors. The board is
        # NBT-persisted, while an in-flight sheep lease is deliberately transient.
        registered = (
            "version-src/1.19.2-forge/src/main/java/buildcraft/robotics/BCRobotics.java",
            "version-src/1.20.1-forge/src/main/java/buildcraft/robotics/BCRobotics.java",
            "source-platforms/neoforge/src/main/java/buildcraft/robotics/BCRobotics.java",
            "source-downports/26.X/26.1.2/neoforge/src/main/java/buildcraft/robotics/BCRobotics.java",
            "source-downports/26.X/26.2/neoforge/src/main/java/buildcraft/robotics/BCRobotics.java",
            "source-family-platforms/26.X/neoforge/src/main/java/buildcraft/robotics/BCRobotics.java",
            "source-family-platforms/26.X/fabric/src/main/java/buildcraft/robotics/BCRobotics.java",
        )
        for relative in registered:
            with self.subTest(source=relative):
                source = (ROOT / relative).read_text()
                self.assertIn('registerAIRobot(BoardRobotShepherd.class, "boardShepherd")', source)
                self.assertIn('registerAIRobot(AIRobotShearSheep.class, "shearSheep")', source)
        ai = (ROOT / JAVA[1]).read_text()
        board = (ROOT / JAVA[0]).read_text()
        self.assertIn('private static final double SHEAR_REACH_SQ = 1.5D * 1.5D;', ai)
        self.assertIn('robot.distanceToSqr(target) <= SHEAR_REACH_SQ', ai)
        self.assertIn('robot.distanceToSqr(target) > SHEAR_REACH_SQ', ai)
        self.assertNotIn("insertIntoInventory(", ai)
        self.assertNotIn("hasFreeSlot()", ai)
        self.assertNotIn("AIRobotGotoStationAndUnload", board)
        self.assertNotIn("containsItems()", board)
        self.assertIn('public AIRobotShearSheep(EntityRobotBase robot)', ai)
        self.assertNotIn('boolean canLoadFromNBT()', ai)
        balance = (ROOT / "source-shared/src/main/java/buildcraft/lib/balance/BalanceIni.java").read_text()
        self.assertIn('"shepherd"', balance)
        api = (ROOT / "source-shared/src/main/java/buildcraft/api/v2/robot/BuildCraftRobotBoards.java").read_text()
        self.assertIn('SHEPHERD = id("shepherd")', api)
        guide = json.loads((ROOT / "source-shared/src/main/resources/assets/buildcraft/guide/original_manifest.json").read_text())
        entries = [entry for entry in guide["entries"] if entry.get("id") == "buildcraftrobotics:robot/shepherd"]
        self.assertEqual(1, len(entries))
        self.assertEqual("buildcraftrobotics/robot/shepherd", entries[0]["page"])
        layout = json.loads((ROOT / "source-shared/src/main/resources/assets/buildcraft/guide/page_layouts.json").read_text())
        self.assertEqual(5, layout["pages"]["buildcraftrobotics/robot/shepherd"]["slots"])

    def test_model_recipe_registry_and_language(self):
        for family, path in (("old", "recipes"), ("1.21.X", "recipe")):
            board = (ROOT / f"source-families/{family}/src/main/java/buildcraft/robotics/BCRoboticsBoards.java").read_text()
            self.assertIn('board("buildcraft:boardRobotShepherd", "shepherd"', board)
            self.assertIn('return new BoardRobotShepherd(robot)', board)
            data = json.loads((ROOT / f"source-families/{family}/src/main/resources/data/buildcraftrobotics/{path}/programming_table/shepherd.json").read_text())
            self.assertEqual(data["energy"]["mj"], 3200)
        model = json.loads((ROOT / "source-shared/src/main/resources/assets/buildcraftrobotics/models/item/robot/shepherd.json").read_text())
        self.assertEqual("buildcraftrobotics:entities/robot_sheepherd_active", model["textures"]["all"])
        for suffix in ("", "_active"):
            self.assertTrue((ROOT / f"source-shared/src/main/resources/assets/buildcraftrobotics/textures/entities/robot_sheepherd{suffix}.png").exists())
        for path in ("source-shared/src/main/resources/assets/buildcraftrobotics/models/item/robot.json",
                     "resource-src/1.21.X/1.21.11/assets/buildcraftrobotics/items/robot.json",
                     "source-families/26.X/src/main/resources/assets/buildcraftrobotics/items/robot.json"):
            item = json.loads((ROOT / path).read_text())
            entries = item.get("overrides", item.get("model", {}).get("entries", []))
            self.assertEqual(18, len(entries))
            last = entries[-1]
            self.assertEqual("buildcraftrobotics:item/robot/shepherd", last.get("model") if "overrides" in item
                             else last["model"]["model"])
        lang = json.loads((ROOT / "source-shared/src/main/resources/assets/buildcraft/lang/en_us.json").read_text())
        self.assertEqual("Shepherd Robot", lang["item.buildcraftrobotics.robot.shepherd"])

if __name__ == "__main__":
    unittest.main()
