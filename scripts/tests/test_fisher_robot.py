"""Fisher Robot: compile the actual shared AI/board for every target with typed JVM doubles.

The harness models the result of vanilla FishingRodItem.use: a persistent FishingHook
which causes ordinary world item drops and rod durability only when reeled. This is
not a Minecraft runtime GameTest and never manufactures an invented loot table.
"""
from __future__ import annotations

import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from scripts.source_preprocessor import preprocess_text
from scripts.tests.test_shepherd_robot import STUBS as SHEPHERD_STUBS

ROOT = Path(__file__).resolve().parents[2]
JAVA = [
    'source-shared/src/main/java/buildcraft/robotics/boards/BoardRobotFisher.java',
    'source-shared/src/main/java/buildcraft/robotics/ai/AIRobotFish.java',
]

STUBS = dict(SHEPHERD_STUBS)
STUBS.update({
    'net/minecraft/core/BlockPos.java': '''package net.minecraft.core;
public record BlockPos(int x,int y,int z){
 public int getX(){return x;}public int getY(){return y;}public int getZ(){return z;}
 public BlockPos offset(int dx,int dy,int dz){return new BlockPos(x+dx,y+dy,z+dz);}
 public BlockPos above(){return offset(0,1,0);}public BlockPos immutable(){return this;}
}''',
    'net/minecraft/world/phys/Vec3.java': '''package net.minecraft.world.phys;
public class Vec3{public final double x,y,z;
 public Vec3(double x,double y,double z){this.x=x;this.y=y;this.z=z;}
 public double x(){return x;}public double y(){return y;}public double z(){return z;}
 public static Vec3 atCenterOf(net.minecraft.core.BlockPos p){return new Vec3(p.x()+.5,p.y()+.5,p.z()+.5);}
 public double distanceToSqr(Vec3 other){double dx=x-other.x,dy=y-other.y,dz=z-other.z;return dx*dx+dy*dy+dz*dz;}
}''',
    'net/minecraft/world/level/Level.java': '''package net.minecraft.world.level;
public class Level{}''',
    'net/minecraft/world/level/levelgen/Heightmap.java': '''package net.minecraft.world.level.levelgen;
public class Heightmap{public enum Types{WORLD_SURFACE}}''',
    'buildcraft/lib/misc/ChunkUtil.java': '''package buildcraft.lib.misc;
import net.minecraft.world.level.Level;import net.minecraft.core.BlockPos;
public class ChunkUtil {
 public static Object getChunk(Level l,int cx,int cz,boolean force){
  return ((net.minecraft.server.level.ServerLevel)l).hasChunkAt(new BlockPos(cx*16,64,cz*16)) ? new Object():null;}
 public static Object getChunk(Level l,BlockPos p,boolean force){return getChunk(l,p.getX()>>4,p.getZ()>>4,force);}
}''',
    'net/minecraft/tags/FluidTags.java': '''package net.minecraft.tags;
public class FluidTags{public static final Object WATER=new Object();}''',
    'net/minecraft/world/level/material/FluidState.java': '''package net.minecraft.world.level.material;
public class FluidState{boolean water;public FluidState(boolean water){this.water=water;}
 public boolean is(Object tag){return water && tag==net.minecraft.tags.FluidTags.WATER;}
 public boolean isSource(){return water;}}''',
    'net/minecraft/world/level/block/state/BlockState.java': '''package net.minecraft.world.level.block.state;
public class BlockState{boolean air;public BlockState(){this(true);}public BlockState(boolean air){this.air=air;}
 public boolean isAir(){return air;}public boolean is(Object tag){return false;}}''',
    'net/minecraft/server/level/ServerLevel.java': '''package net.minecraft.server.level;
import net.minecraft.core.BlockPos;import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;
public class ServerLevel extends net.minecraft.world.level.Level{
 public int casts,reels;public boolean permitChunks=true,hasWater=true;
 public int waterY=64,waterMinX=3,waterMaxX=8,waterMinZ=-2,waterMaxZ=2;
 public int heightQueries,unloadedHeightQueries;
 public final List<net.minecraft.world.entity.item.ItemEntity> drops=new ArrayList<>();
 public final List<ServerPlayer> clients=new ArrayList<>();
 public List<ServerPlayer> players(){return clients;}
 public boolean hasChunkAt(BlockPos p){return permitChunks;}
 public boolean water(BlockPos p){return hasWater&&p.y()==waterY&&p.x()>=waterMinX
    &&p.x()<=waterMaxX&&p.z()>=waterMinZ&&p.z()<=waterMaxZ;}
 public int getMinBuildHeight(){return -64;}public int getMaxBuildHeight(){return 320;}
 public int getHeight(net.minecraft.world.level.levelgen.Heightmap.Types type,int x,int z){
   heightQueries++;
   if(!permitChunks){unloadedHeightQueries++;throw new IllegalStateException("queried unloaded chunk");}
   if(hasWater&&x>=waterMinX&&x<=waterMaxX&&z>=waterMinZ&&z<=waterMaxZ) return waterY+1;
   return 65;
 }
 public FluidState getFluidState(BlockPos p){return new FluidState(water(p));}
 public BlockState getBlockState(BlockPos p){return new BlockState(!water(p));}
}''',
    'com/mojang/authlib/GameProfile.java': '''package com.mojang.authlib;
import java.util.UUID;
public class GameProfile{public final UUID id;public final String name;
 public GameProfile(){this(UUID.fromString("00000000-0000-0000-0000-000000000001"),"owner");}
 public GameProfile(UUID id,String name){this.id=id;this.name=name;}
 public int hashCode(){return id.hashCode();}public boolean equals(Object o){return o instanceof GameProfile p&&p.id.equals(id);}
 public String toString(){return id.toString();}}''',
    'net/minecraft/world/item/Item.java': '''package net.minecraft.world.item;
public class Item{public void use(net.minecraft.world.level.Level l,net.minecraft.world.entity.player.Player p,
 net.minecraft.world.InteractionHand h){}}''',
    'net/minecraft/world/item/FishingRodItem.java': '''package net.minecraft.world.item;
import net.minecraft.server.level.ServerLevel;import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;import net.minecraft.world.entity.projectile.FishingHook;
public class FishingRodItem extends Item{
 public void use(net.minecraft.world.level.Level level,Player player,InteractionHand hand){
  ServerLevel server=(ServerLevel)level;
  if(player.fishing==null){player.fishing=new FishingHook(player);server.casts++;}
  else{
   server.reels++;
   if(player.fishing.isInWater() && player.fishing.isBiting()){
    server.drops.add(new net.minecraft.world.entity.item.ItemEntity(new ItemStack(new Item(),2)));
   }
   player.getItemInHand(hand).damage(1);player.fishing.discard();player.fishing=null;
  }
 }
}''',
    'net/minecraft/world/item/ItemStack.java': '''package net.minecraft.world.item;
public class ItemStack {
 public static final ItemStack EMPTY=new ItemStack(null,0);
 private final Item item;private int count,damage;
 public ItemStack(Item i){this(i,1);}public ItemStack(Item i,int c){item=i;count=c;}
 public boolean isEmpty(){return count<=0||item==null;}public Item getItem(){return item;}
 public int getCount(){return count;}public int getDamageValue(){return damage;}
 public boolean isDamageableItem(){return item instanceof FishingRodItem;}
 public int getMaxDamage(){return 64;}public void damage(int n){damage+=n;if(damage>=64)count=0;}
 public ItemStack copy(){ItemStack c=new ItemStack(item,count);c.damage=damage;return c;}
}''',
    'net/minecraft/world/entity/Entity.java': '''package net.minecraft.world.entity;
import java.util.UUID;import net.minecraft.core.BlockPos;import net.minecraft.world.phys.*;
public class Entity{public final UUID uuid=UUID.randomUUID();public boolean removed;
 private static int nextEntityId=500;private final int entityId=nextEntityId++;
 public net.minecraft.network.syncher.SynchedEntityData data=new net.minecraft.network.syncher.SynchedEntityData();
 public void setInvisible(boolean v){data.changed=v;}
 public net.minecraft.network.syncher.SynchedEntityData getEntityData(){return data;}
 public Object getType(){return null;}
 public Vec3 getDeltaMovement(){return new Vec3(0,0,0);}
 public int getId(){return entityId;}
 public double x,y,z;public UUID getUUID(){return uuid;}public boolean isRemoved(){return removed;}
 public double getX(){return x;}public double getY(){return y;}public double getZ(){return z;}
 public BlockPos blockPosition(){return new BlockPos((int)x,(int)y,(int)z);}
 public Vec3 position(){return new Vec3(x,y,z);}
 public void setPos(double x,double y,double z){this.x=x;this.y=y;this.z=z;}
 public double distanceToSqr(Entity other){return distanceToSqr(other.position());}
 public double distanceToSqr(Vec3 at){double dx=x-at.x(),dy=y-at.y(),dz=z-at.z();return dx*dx+dy*dy+dz*dz;}
}''',
    'net/minecraft/world/entity/projectile/FishingHook.java': '''package net.minecraft.world.entity.projectile;
import net.minecraft.world.entity.Entity;import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
public class FishingHook extends Entity{
 private static final net.minecraft.network.syncher.EntityDataAccessor<Boolean> DATA_BITING=
  new net.minecraft.network.syncher.EntityDataAccessor<>();
 // First bubbles/swirl particles are only the approach phase, NOT a bite.
 private int timeUntilHooked;private int nibble;
 private Vec3 motion=new Vec3(0,0,0);private boolean water=true;
 public final Player owner;public FishingHook(Player owner){this.owner=owner;
  setPos(owner.getX(),owner.getEyeY(),owner.getZ());}
 public boolean isInWater(){return water;}public void setInWater(boolean v){water=v;}
 public void setBiting(boolean yes){nibble=yes?24:0;getEntityData().set(DATA_BITING,yes);}
 public void setNetworkBiting(boolean yes){getEntityData().set(DATA_BITING,yes);}
 public void setFishApproaching(boolean yes){timeUntilHooked=yes?40:0;}
 public boolean isBiting(){return nibble>0;}
 private Entity hookedIn;
 public Entity getHookedIn(){return hookedIn;}public void setHookedIn(Entity e){hookedIn=e;}
 public Vec3 getDeltaMovement(){return motion;}
 public void setDeltaMovement(Vec3 v){motion=v;}public void discard(){removed=true;}
}''',
    'net/minecraft/world/entity/player/Player.java': '''package net.minecraft.world.entity.player;
import net.minecraft.world.InteractionHand;import net.minecraft.world.item.*;
public class Player extends net.minecraft.world.entity.Entity{
 private ItemStack hand=ItemStack.EMPTY;
 public net.minecraft.world.entity.projectile.FishingHook fishing;
 public void setItemInHand(InteractionHand h,ItemStack stack){hand=stack;}
 public ItemStack getItemInHand(InteractionHand h){return hand;}
 public double getEyeY(){return getY()+1.62;}
 public float yaw,pitch,headYaw;
 public void setYRot(float f){yaw=f;}public float getYRot(){return yaw;}
 public void setXRot(float f){pitch=f;}public float getXRot(){return pitch;}
 public void setYHeadRot(float f){headYaw=f;}
 public float getYHeadRot(){return headYaw;}
 public void setYBodyRot(float f){bodyYaw=f;}public float bodyYaw;
}''',
    'net/minecraft/server/level/ServerPlayer.java': '''package net.minecraft.server.level;
public class ServerPlayer extends net.minecraft.world.entity.player.Player{
 public final PacketListener connection=new PacketListener();
 public Object getAddEntityPacket(){return new net.minecraft.network.protocol.game.ClientboundAddPlayerPacket(this);}
 public static class PacketListener{
  public final java.util.List<Object> packets=new java.util.ArrayList<>();
  public void send(Object packet){packets.add(packet);}
 }
}''',
    'buildcraft/lib/misc/FakePlayerProvider.java': '''package buildcraft.lib.misc;
import net.minecraft.server.level.*;import net.minecraft.core.BlockPos;
import com.mojang.authlib.GameProfile;import java.util.*;
public class FakePlayerProvider{
 public static final FakePlayerProvider INSTANCE=new FakePlayerProvider();
 public final Map<GameProfile,ServerPlayer> players=new HashMap<>();
 public ServerPlayer getFakePlayer(ServerLevel world,GameProfile profile,BlockPos pos){
  return players.computeIfAbsent(profile,ignored->new ServerPlayer());}
}''',
    'buildcraft/api/v2/permission/WorldOperationKind.java': '''package buildcraft.api.v2.permission;
public enum WorldOperationKind{ITEM_USE}''',
    'buildcraft/robotics/internal/api2/RobotAutomationSupport.java': '''package buildcraft.robotics.internal.api2;
import buildcraft.api.v2.OperationMode;import buildcraft.api.v2.permission.WorldOperationKind;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import com.mojang.authlib.GameProfile;
public class RobotAutomationSupport{
 public static boolean allowed=true;
 public static boolean permitsBlock(EntityRobotBase r,net.minecraft.server.level.ServerLevel level,
  net.minecraft.core.BlockPos target,WorldOperationKind kind,OperationMode mode){return allowed;}
 public static GameProfile owner(EntityRobotBase r){return new GameProfile();}
}''',
    'buildcraft/robotics/ai/AIRobotGotoBlock.java': '''package buildcraft.robotics.ai;
public class AIRobotGotoBlock extends buildcraft.robotics.internal.legacy.robots.AIRobot{
 public final int x,y,z;
 public AIRobotGotoBlock(buildcraft.robotics.internal.legacy.robots.EntityRobotBase r,int x,int y,int z){
  super(r);this.x=x;this.y=y;this.z=z;}}
''',
    'buildcraft/robotics/scheduler/RobotProcessorWorkScheduler.java': '''package buildcraft.robotics.scheduler;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;import net.minecraft.core.BlockPos;
public class RobotProcessorWorkScheduler{
 public static boolean allowed=true;
 public static boolean allowsRobotTarget(EntityRobotBase r,BlockPos pos){return allowed;}}
''',
    'buildcraft/robotics/internal/legacy/robots/EntityRobotBase.java': '''package buildcraft.robotics.internal.legacy.robots;
import net.minecraft.world.entity.*;import net.minecraft.world.item.*;import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;import net.minecraft.world.phys.*;
public class EntityRobotBase extends Entity{
 public final ServerLevel world;public ItemStack held=new ItemStack(new FishingRodItem());
 public boolean station=true,active,movingNow;public BlockPos aimed;
 public EntityRobotBase(ServerLevel level){world=level;x=0;y=64;z=0;}
 public net.minecraft.world.level.Level getCommandSenderWorld(){return world;}
 public ItemStack getItemBySlot(EquipmentSlot slot){return held;}
 public void setItemInUse(ItemStack item){held=item.copy();}
 public void setItemActive(boolean v){active=v;}
 public boolean isMoving(){return movingNow;}
 public void aimItemAt(BlockPos pos){aimed=pos;}
 public Object getLinkedStation(){return station?new Object():null;}
 public buildcraft.lib.internal.area.IZone getZoneToWork(){return null;}
}''',
})

HARNESS = '''package fixture;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.*;
import buildcraft.robotics.boards.BoardRobotFisher;
import buildcraft.robotics.ai.AIRobotFish;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.internal.api2.RobotAutomationSupport;
import buildcraft.lib.misc.FakePlayerProvider;
public class FisherProbe{
 static void ok(boolean pass,String why){if(!pass)throw new AssertionError(why);}
 static AIRobotFish cast(EntityRobotBase robot,ServerLevel world){
  AIRobotFish ai=new AIRobotFish(robot);
  for(int i=0;i<280&&world.casts<1;i++)ai.update();
  ok(world.casts==1,"Vanilla rod did not cast a real hook");
  return ai;
 }
 public static void main(String[] args){
  ServerLevel world=new ServerLevel();EntityRobotBase robot=new EntityRobotBase(world);
  ok(BoardRobotFisher.isUsableRod(robot.held),"Rejected actual fishing rod");
  ok(!BoardRobotFisher.isUsableRod(new ItemStack(new Item())),"Accepted invalid rod");
  AIRobotFish first=cast(robot,world);
  ok(robot.active,"Robot did not animate casting/waiting");
  ok(world.reels==0&&world.drops.isEmpty(),"Fabricated fish before a real bite");
  ServerPlayerHolder state=new ServerPlayerHolder();
  state.findHook();FishingHook hook=state.hook;
  ok(Math.abs(hook.getY()-(robot.getY()+.42))<.02,
     "The real vanilla bobber starts above the robot instead of its fishing rod");
  double launchOffset=Math.hypot(hook.getX()-robot.getX(),hook.getZ()-robot.getZ());
  ok(launchOffset>.85&&launchOffset<1.05,
     "Vanilla bobber still spawned inside Fisher's collision body instead of ahead of its rod");
  for(int i=0;i<55;i++)first.update();
  ok(world.reels==0&&world.drops.isEmpty(),"Premature reel without vanilla bite");
  // Bubbles begin when a fish approaches. Vanilla nibble remains zero.
  hook.setFishApproaching(true);
  for(int i=0;i<190;i++)first.update();
  ok(!hook.isRemoved()&&world.reels==0,
     "Fishing line vanished during approaching-fish particles, without a bite");
  // At the surface a bobber can momentarily report itself as outside water.
  // The old code aborted the entire cast on the first such tick after 160 ticks.
  hook.setInWater(false);first.update();hook.setInWater(true);
  first.update();
  ok(!hook.isRemoved()&&world.reels==0,
     "One dry tick at the surface cancelled a live vanilla fishing session");
  // Slow fishing may take longer than the old global 1,800-tick cutoff.
  for(int i=0;i<1900;i++)first.update();
  ok(!hook.isRemoved()&&world.reels==0&&world.drops.isEmpty(),
     "The robot abandoned a valid bobber before vanilla's actual bite");
  hook.setDeltaMovement(new Vec3(0,-.35,0));first.update();
  ok(world.reels==0&&world.drops.isEmpty(),
     "Ordinary negative bobber motion was misread as a bite");
  hook.setNetworkBiting(true);first.update();
  ok(world.reels==0,"Synced visual flag without retrievable vanilla nibble caused an early reel");
  hook.setBiting(true);
  // This old/late packet state must not hide server-authoritative nibble.
  hook.setNetworkBiting(false);
  first.update();
  ok(world.reels==1&&world.drops.size()==1,"A real vanilla bite was not reeled");
  ok(robot.held.getDamageValue()==1,"Rod durability not transferred to robot");
  ok(!robot.active,"Robot still has active rod after reel");
  ok(FakePlayerProvider.INSTANCE.players.size()==1,"Missing dedicated fake angler");
  ServerLevel visualWorld=new ServerLevel();
  net.minecraft.server.level.ServerPlayer viewer=new net.minecraft.server.level.ServerPlayer();
  visualWorld.clients.add(viewer);viewer.setPos(0,64,0);
  EntityRobotBase visualRobot=new EntityRobotBase(visualWorld);
  // Non-integer coordinates expose the whole-block spawn bug on 1.21.1+ and 26.X.
  visualRobot.setPos(0.6,64.3,0.4);
  AIRobotFish visual=cast(visualRobot,visualWorld);
  var packets=viewer.connection.packets;
  ok(packets.size()==4,"Viewer needs profile, player, invisibility AND held rod equipment");
  ok(packets.get(0).getClass().getSimpleName().startsWith("ClientboundPlayerInfo"),
     "Player info must precede owner spawn");
  ok(packets.get(1) instanceof net.minecraft.network.protocol.game.ClientboundAddPlayerPacket
     || packets.get(1) instanceof net.minecraft.network.protocol.game.ClientboundAddEntityPacket,
     "Angler entity must precede the vanilla fishing hook");
  // Every modern target must preserve precise angler coordinates, not just 1.21.11.
  if(!"1.19.2".equals(System.getProperty("mc.version"))
      && !"1.20.1".equals(System.getProperty("mc.version"))){
   ok(packets.get(1) instanceof net.minecraft.network.protocol.game.ClientboundAddEntityPacket spawn
      && spawn.isPrecise()
      && Math.abs(spawn.getX()-visualRobot.getX())<1E-6
      && Math.abs(spawn.getY()-(visualRobot.getY()+0.42-1.62))<1E-6
      && Math.abs(spawn.getZ()-visualRobot.getZ())<1E-6,
      "Fishing line spawned at rounded block coordinates instead of Fisher's hand");
  }
  ok(packets.get(2) instanceof net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket,
     "Angler invisible flag not synced");
  ok(packets.get(3) instanceof net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket equip
       && !equip.slots().isEmpty()
       && equip.slots().get(0).getSecond().getItem() instanceof FishingRodItem,
     "Angler must hold a rod on the client so the line uses the correct hand");
  visual.update();
  ok(packets.size()==4,"Static angler generated redundant movement packets");
  // A moving Fisher must hide both the line and its phantom owner's rod.
  // Even an interrupted cast must not turn into an early vanilla reel.
  visualRobot.movingNow=true;
  visualRobot.x=1.75;visualRobot.y=64.35;
  visual.update();
  ok(visualWorld.reels==0 && !visualRobot.active,
     "Fisher displayed/retrieved a rod during movement");
  ok(packets.stream().anyMatch(packet->packet instanceof net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket),
     "Moving Fisher left its fishing line/ghost owner visible");
  int packetCount=packets.size();
  visual.update();
  ok(packets.size()==packetCount,"Hidden angler sent redundant packets while robot moved");
  visualRobot.movingNow=false;
  visual.onUnload();
  // A navigating Fisher must never cast or display its rod before arriving.
  ServerLevel movingWorld=new ServerLevel();
  EntityRobotBase navigatingRobot=new EntityRobotBase(movingWorld);
  navigatingRobot.movingNow=true;
  AIRobotFish navigation=new AIRobotFish(navigatingRobot);
  for(int i=0;i<35;i++)navigation.update();
  ok(movingWorld.casts==0 && !navigatingRobot.active,
     "Fisher cast or displayed a rod while moving to water");
  navigatingRobot.movingNow=false;
  for(int i=0;i<280&&movingWorld.casts==0;i++)navigation.update();
  ok(movingWorld.casts==1,"Fisher did not resume casting once it stopped");
  navigation.onUnload();
  // An accidental collision with the Fisher robot is not a fish bite.
  ServerLevel collisionWorld=new ServerLevel();
  EntityRobotBase collisionRobot=new EntityRobotBase(collisionWorld);
  AIRobotFish collision=cast(collisionRobot,collisionWorld);
  FishingHook collided=null;
  for(var fake:FakePlayerProvider.INSTANCE.players.values())if(fake.fishing!=null){collided=fake.fishing;break;}
  ok(collided!=null,"Collision fixture has no bobber");
  collided.setHookedIn(collisionRobot);
  collision.update();
  ok(collisionWorld.reels==0&&collisionWorld.drops.isEmpty()&&collision.success(),
     "Fisher pulled its own body or treated a self-hook as a successful fish catch");
  collision.onUnload();
  // Hooking ANY entity (not just its own robot) is not a fishing bite.
  ServerLevel hookedWorld=new ServerLevel();
  AIRobotFish hooked=cast(new EntityRobotBase(hookedWorld),hookedWorld);
  FishingHook bystanderHook=null;
  for(var fake:FakePlayerProvider.INSTANCE.players.values()) if(fake.fishing != null){
   bystanderHook=fake.fishing;break;}
  ok(bystanderHook!=null,"Missing bystander collision hook");
  bystanderHook.setHookedIn(new net.minecraft.world.entity.Entity());
  hooked.update();
  ok(hookedWorld.reels==0 && hookedWorld.drops.isEmpty(),
     "Entity collision caused an early FishingRodItem.use(reel)");
  hooked.onUnload();
  ok(packets.stream().anyMatch(packet->packet instanceof net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket),
     "Fisher did not remove the private angler entity after unload");
  ok(packets.stream().anyMatch(packet->packet.getClass().getSimpleName().contains("PlayerInfo")
    && packet.getClass().getSimpleName().contains("Remove")
    || packet instanceof net.minecraft.network.protocol.game.ClientboundPlayerInfoPacket p
    && p.action()==net.minecraft.network.protocol.game.ClientboundPlayerInfoPacket.Action.REMOVE_PLAYER),
     "Fisher did not remove the private angler profile after unload");
  ServerLevel farWorld=new ServerLevel();
  var farViewer=new net.minecraft.server.level.ServerPlayer();
  farViewer.setPos(1000,64,1000);farWorld.clients.add(farViewer);
  AIRobotFish far=cast(new EntityRobotBase(farWorld),farWorld);
  ok(farViewer.connection.packets.isEmpty(),"Private angler was sent to a distant client");
  far.onUnload();
  // If a cast misses water, Fisher discards it and searches another spot.
  // It MUST NOT use the rod to reel before a real bite.
  ServerLevel missedWorld=new ServerLevel();
  AIRobotFish missed=cast(new EntityRobotBase(missedWorld),missedWorld);
  for(var fake:FakePlayerProvider.INSTANCE.players.values()){
   if(fake.fishing!=null){fake.fishing.setInWater(false);}
  }
  for(int i=0;i<170;i++)missed.update();
  ok(missedWorld.reels==0,"A timeout was incorrectly turned into a vanilla reel");
  ok(missed.success(),"A missed cast incorrectly failed the entire profession");
  ok(missedWorld.drops.isEmpty(),"Missed cast invented fishing loot");
  missed.onUnload();
  // Denied use must not spawn bobbers or loot.
  ServerLevel deniedWorld=new ServerLevel();
  EntityRobotBase denied=new EntityRobotBase(deniedWorld);
  RobotAutomationSupport.allowed=false;
  AIRobotFish refuse=new AIRobotFish(denied);
  for(int i=0;i<280;i++)refuse.update();
  ok(deniedWorld.casts==0&&deniedWorld.drops.isEmpty(),"Fishing ignored block-use permission");
  RobotAutomationSupport.allowed=true;
  // A fisher with no loaded water may not generate catches out of thin air.
  ServerLevel dry=new ServerLevel();dry.hasWater=false;
  EntityRobotBase onDry=new EntityRobotBase(dry);
  AIRobotFish noLake=new AIRobotFish(onDry);
  for(int i=0;i<280;i++)noLake.update();
  ok(dry.casts==0&&dry.drops.isEmpty(),"Fished without water");
  // A small 2x2 pond is valid; the old 3x3 neighborhood rule rejected it.
  ServerLevel pond=new ServerLevel();pond.waterMinX=3;pond.waterMaxX=4;
  pond.waterMinZ=0;pond.waterMaxZ=1;
  EntityRobotBase pondRobot=new EntityRobotBase(pond);
  cast(pondRobot,pond);
  // A dock high above a lake and 48+ blocks away must still find its
  // exposed surface through the heightmap, then navigate toward it.
  ServerLevel distant=new ServerLevel();distant.waterY=62;
  distant.waterMinX=48;distant.waterMaxX=56;
  distant.waterMinZ=-3;distant.waterMaxZ=3;
  EntityRobotBase highRobot=new EntityRobotBase(distant);highRobot.y=92;
  AIRobotFish searching=new AIRobotFish(highRobot);
  for(int i=0;i<260&&searching.delegate==null;i++)searching.update();
  ok(searching.delegate instanceof buildcraft.robotics.ai.AIRobotGotoBlock,
    "Could not discover a lake far below / farther than 24 blocks");
  ok(distant.casts==0,"Cast from a high cliff before navigating to water");
  buildcraft.robotics.ai.AIRobotGotoBlock route=
    (buildcraft.robotics.ai.AIRobotGotoBlock)searching.delegate;
  highRobot.setPos(route.x+.5,route.y+.5,route.z+.5);
  searching.delegateAIEnded(route);
  for(int i=0;i<25&&distant.casts==0;i++)searching.update();
  ok(distant.casts==1,"Did not cast after reaching the lake shore");
  // Never even query the heightmap in an unloaded chunk.
  ServerLevel unloaded=new ServerLevel();unloaded.permitChunks=false;
  AIRobotFish unloadedFish=new AIRobotFish(new EntityRobotBase(unloaded));
  for(int i=0;i<15;i++)unloadedFish.update();
  ok(unloaded.heightQueries==0&&unloaded.casts==0,"Queried or fished in an unloaded chunk");
  // No water at first is not a permanent terminal AI failure: new water in
  // already loaded chunks becomes discoverable after the bounded retry.
  ServerLevel lateLake=new ServerLevel();lateLake.hasWater=false;
  AIRobotFish retrying=new AIRobotFish(new EntityRobotBase(lateLake));
  for(int i=0;i<420;i++)retrying.update();
  ok(lateLake.casts==0,"Created fish before a pond existed");
  lateLake.hasWater=true;
  for(int i=0;i<250&&lateLake.casts==0;i++)retrying.update();
  ok(lateLake.casts==1,"Did not retry when a pond appeared later");
  // Each robot owns a distinct fake angler / hook even with the same owner.
  ServerLevel duo=new ServerLevel();
  EntityRobotBase a=new EntityRobotBase(duo),b=new EntityRobotBase(duo);
  AIRobotFish aiA=cast(a,duo);
  duo.casts=0;
  AIRobotFish aiB=cast(b,duo);
  ok(FakePlayerProvider.INSTANCE.players.size()>=3,"Fishers shared a single owner bobber");
  aiA.onUnload();aiB.onUnload();
  ok(duo.reels==0,"Unloading fishers generated artificial loot");
  System.out.println("Fisher PASS: bite state only, no timeout/collision reel, hides line during movement, vanilla loot, rod damage, permissions, cleanup");
 }
 static class ServerPlayerHolder{
  FishingHook hook;
  void findHook(){for(var player:FakePlayerProvider.INSTANCE.players.values()){
   if(player.fishing!=null){hook=player.fishing;break;}}
   ok(hook!=null,"No real FishingHook attached to fake player");}
 }
}'''

# Network protocol doubles: verify an invisible private owner is spawned BEFORE
# the actual vanilla hook, and removed when its Fisher AI terminates.
STUBS.update({'net/minecraft/network/syncher/SynchedEntityData.java': '''package net.minecraft.network.syncher;
import java.util.*;
public class SynchedEntityData{ public boolean changed=false;
 private final Map<EntityDataAccessor<?>,Object> values=new HashMap<>();
 public <T> void set(EntityDataAccessor<T> key,T value){values.put(key,value);}
 @SuppressWarnings("unchecked")
 public <T> T get(EntityDataAccessor<T> key){return (T)values.getOrDefault(key,Boolean.FALSE);}
 public List<String> getNonDefaultValues(){return changed?List.of("invisible"):null;}
}''', 'net/minecraft/network/syncher/EntityDataAccessor.java': '''package net.minecraft.network.syncher;
public class EntityDataAccessor<T>{}
''', 'com/mojang/datafixers/util/Pair.java': '''package com.mojang.datafixers.util;
public record Pair<A,B>(A first,B second){
 public static <A,B> Pair<A,B> of(A a,B b){return new Pair<>(a,b);}
 public B getSecond(){return second;}
}
''', 'net/minecraft/network/protocol/game/ClientboundAddPlayerPacket.java': 'package net.minecraft.network.protocol.game;\npublic record ClientboundAddPlayerPacket(Object owner){}', 'net/minecraft/network/protocol/game/ClientboundPlayerInfoPacket.java': 'package net.minecraft.network.protocol.game;\npublic record ClientboundPlayerInfoPacket(Action action,Object player){\n public enum Action{ADD_PLAYER,REMOVE_PLAYER}}\n', 'net/minecraft/network/protocol/game/ClientboundPlayerInfoUpdatePacket.java': 'package net.minecraft.network.protocol.game;\nimport java.util.*;\npublic record ClientboundPlayerInfoUpdatePacket(List<?> players){\n public static ClientboundPlayerInfoUpdatePacket createPlayerInitializing(List<?> players){\n return new ClientboundPlayerInfoUpdatePacket(players);}}\n', 'net/minecraft/network/protocol/game/ClientboundPlayerInfoRemovePacket.java': 'package net.minecraft.network.protocol.game;\nimport java.util.*;\npublic record ClientboundPlayerInfoRemovePacket(List<?> players){}',     'net/minecraft/network/protocol/game/ClientboundAddEntityPacket.java': """package net.minecraft.network.protocol.game;
import net.minecraft.world.entity.Entity;import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;import java.util.UUID;
public class ClientboundAddEntityPacket {
 private final double x,y,z;private final boolean precise;
 public ClientboundAddEntityPacket(Entity entity,int data,BlockPos pos){
  x=pos.getX();y=pos.getY();z=pos.getZ();precise=false;
 }
 public ClientboundAddEntityPacket(int id,UUID uuid,double x,double y,double z,
     float xRot,float yRot,Object type,int data,Vec3 movement,double headRot){
  this.x=x;this.y=y;this.z=z;precise=true;
 }
 public double getX(){return x;}public double getY(){return y;}
 public double getZ(){return z;}public boolean isPrecise(){return precise;}
}""",
'net/minecraft/network/protocol/game/ClientboundSetEntityDataPacket.java': 'package net.minecraft.network.protocol.game;\nimport java.util.*;import net.minecraft.network.syncher.SynchedEntityData;\npublic record ClientboundSetEntityDataPacket(int id,Object data){\n public ClientboundSetEntityDataPacket(int id,SynchedEntityData data,boolean all){this(id,(Object)data);}\n public ClientboundSetEntityDataPacket(int id,List<?> values){this(id,(Object)values);}}\n', 'net/minecraft/network/protocol/game/ClientboundRemoveEntitiesPacket.java': 'package net.minecraft.network.protocol.game;\npublic record ClientboundRemoveEntitiesPacket(int id){}', 'net/minecraft/network/protocol/game/ClientboundSetEquipmentPacket.java': '''package net.minecraft.network.protocol.game;
import java.util.*;import com.mojang.datafixers.util.Pair;
import net.minecraft.world.item.ItemStack;import net.minecraft.world.entity.EquipmentSlot;
public record ClientboundSetEquipmentPacket(int id,List<Pair<EquipmentSlot,ItemStack>> slots){}
'''})

# The fake owner has no entity tracker. Model both legacy and modern sync packets.
STUBS.update({
    'net/minecraft/network/protocol/game/ClientboundTeleportEntityPacket.java': """package net.minecraft.network.protocol.game;
import net.minecraft.world.entity.Entity;
public record ClientboundTeleportEntityPacket(Entity entity){}
""",
    'net/minecraft/network/protocol/game/ClientboundEntityPositionSyncPacket.java': """package net.minecraft.network.protocol.game;
import net.minecraft.world.entity.Entity;
public record ClientboundEntityPositionSyncPacket(Entity entity) {
 public static ClientboundEntityPositionSyncPacket of(Entity e) {
  return new ClientboundEntityPositionSyncPacket(e);
 }
}
""",
})

class FisherRobotTests(unittest.TestCase):
    TARGETS = [
        ('1.19.2','old','forge'),('1.20.1','old','forge'),
        ('1.21.1','1.21.X','neoforge'),('1.21.11','1.21.X','neoforge'),
        ('26.1.2','26.X','neoforge'),('26.2','26.X','neoforge'),
        ('26.3','26.X','neoforge'),('26.3','26.X','fabric')]

    def test_actual_fisher_ai_compiles_and_uses_vanilla_rod(self):
        for version,family,platform in self.TARGETS:
            with self.subTest(version=version,platform=platform),tempfile.TemporaryDirectory(prefix='bc-fisher-') as tmp:
                root=Path(tmp)
                sources=[]
                for rel,stub in STUBS.items():
                    if rel == 'net/minecraft/world/entity/projectile/FishingHook.java':
                        # Exercise the actual names found in Mojang/Forge SRG and
                        # Fabric intermediary mappings, plus a nibble-only fallback.
                        if version == '1.19.2':
                            stub = stub.replace('DATA_BITING', 'f_37102_').replace('nibble', 'f_37089_')
                        elif platform == 'fabric':
                            stub = stub.replace('DATA_BITING', 'field_23234').replace('nibble', 'field_7173')
                        elif version == '26.2':
                            stub = stub.replace('DATA_BITING', 'non_public_mapped_field')
                    path=root/rel;path.parent.mkdir(parents=True,exist_ok=True)
                    path.write_text(stub);sources.append(str(path))
                for rel in JAVA:
                    path=root/rel.removeprefix('source-shared/src/main/java/')
                    path.parent.mkdir(parents=True,exist_ok=True)
                    path.write_text(preprocess_text((ROOT/rel).read_text(encoding='utf-8'),minecraft=version,
                         family=family,platform=platform,source=rel));sources.append(str(path))
                p=root/'fixture/FisherProbe.java';p.parent.mkdir(parents=True,exist_ok=True)
                p.write_text(HARNESS);sources.append(str(p))
                classes=root/'classes';classes.mkdir()
                comp=subprocess.run(['javac','--release','17','-d',str(classes),*sources],capture_output=True,text=True,timeout=70)
                self.assertEqual(0,comp.returncode,comp.stderr)
                run=subprocess.run(['java','-Dmc.version='+version,'-cp',str(classes),'fixture.FisherProbe'],capture_output=True,text=True,timeout=20)
                self.assertEqual(0,run.returncode,run.stdout+run.stderr)
                self.assertIn('Fisher PASS',run.stdout)

    def test_registration_models_recipes_and_guide_all_targets(self):
        board = (ROOT/'source-families/1.21.X/src/main/java/buildcraft/robotics/BCRoboticsBoards.java').read_text()
        self.assertIn('board("buildcraft:boardRobotFisher", "fisher"',board)
        self.assertIn('return new BoardRobotFisher(robot);',board)
        self.assertTrue((ROOT/'source-shared/src/main/resources/assets/buildcraftrobotics/textures/entities/robot_fisher.png').exists())
        self.assertTrue((ROOT/'source-shared/src/main/resources/assets/buildcraftrobotics/textures/entities/overlay_side.png').exists())
        self.assertIn('"fisher"', (ROOT/'source-shared/src/main/java/buildcraft/lib/balance/BalanceIni.java').read_text())
        for target in self.TARGETS:
            self.assertEqual((ROOT/'source-shared/src/main/java/buildcraft/robotics/ai/AIRobotFish.java').read_text().count('rod.getItem().use('),2)
        for reg in (
            'source-platforms/neoforge/src/main/java/buildcraft/robotics/BCRobotics.java',
            'source-family-platforms/26.X/fabric/src/main/java/buildcraft/robotics/BCRobotics.java',
            'source-family-platforms/26.X/neoforge/src/main/java/buildcraft/robotics/BCRobotics.java',
            'source-downports/26.X/26.1.2/neoforge/src/main/java/buildcraft/robotics/BCRobotics.java',
            'source-downports/26.X/26.2/neoforge/src/main/java/buildcraft/robotics/BCRobotics.java',
            'version-src/1.19.2-forge/src/main/java/buildcraft/robotics/BCRobotics.java',
            'version-src/1.20.1-forge/src/main/java/buildcraft/robotics/BCRobotics.java'):
            source=(ROOT/reg).read_text()
            self.assertIn('registerAIRobot(BoardRobotFisher.class, "boardFisher")',source)
            self.assertIn('registerAIRobot(AIRobotFish.class, "fish")',source)
        for fam,directory in [('old','recipes'),('1.21.X','recipe'),('26.X','recipe')]:
            p=ROOT/f'source-families/{fam}/src/main/resources/data/buildcraftrobotics/{directory}/programming_table/fisher.json'
            self.assertEqual(3200,json.loads(p.read_text())['energy']['mj'])
        models=['source-shared/src/main/resources/assets/buildcraftrobotics/models/item/robot.json',
                'resource-src/1.21.X/1.21.11/assets/buildcraftrobotics/items/robot.json',
                'source-families/26.X/src/main/resources/assets/buildcraftrobotics/items/robot.json']
        for model in models:
            self.assertIn('buildcraftrobotics:item/robot/fisher',(ROOT/model).read_text())
        manifest=json.loads((ROOT/'source-shared/src/main/resources/assets/buildcraft/guide/original_manifest.json').read_text())
        self.assertTrue(any(entry['id']=='buildcraftrobotics:robot/fisher' for entry in manifest['entries']))
        pages=json.loads((ROOT/'source-shared/src/main/resources/assets/buildcraft/guide/page_layouts.json').read_text())
        self.assertEqual(5,pages['pages']['buildcraftrobotics/robot/fisher']['slots'])
        lang=json.loads((ROOT/'source-shared/src/main/resources/assets/buildcraft/lang/en_us.json').read_text())
        self.assertEqual('Fisher Robot',lang['item.buildcraftrobotics.robot.fisher'])
        self.assertEqual('Fisher Redstone Board', lang['item.buildcraftrobotics.redstone_board.fisher'])
        # Both robot and board tooltips compute this exact legacy key on every target.
        self.assertIn('buildcraft.boardRobotFisher.desc', lang)
        self.assertIn('fishing rod', lang['buildcraft.boardRobotFisher.desc'])
        self.assertNotEqual('buildcraft.boardRobotFisher.desc', lang['buildcraft.boardRobotFisher.desc'])

if __name__=='__main__': unittest.main()
