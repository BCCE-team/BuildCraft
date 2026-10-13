#!/usr/bin/env python3
"""Compile the real schematic dispatcher with independent addons and recoverable broken adapters."""
from __future__ import annotations
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
BASE=ROOT/'source-shared/src/main/java'
STUBS={
 'net/minecraft/resources/ResourceLocation.java':'''package net.minecraft.resources;
 public record ResourceLocation(String raw){public static ResourceLocation tryParse(String s){return new ResourceLocation(s);}
 public String toString(){return raw;}}''',
 'net/minecraft/core/BlockPos.java':'package net.minecraft.core;public record BlockPos(int x,int y,int z){}',
 'net/minecraft/world/level/block/state/BlockState.java':'''package net.minecraft.world.level.block.state;
 public class BlockState{public Object getBlock(){return new Object();}}''',
 'net/minecraft/world/level/Level.java':'package net.minecraft.world.level;public class Level{}',
 'net/minecraft/server/level/ServerLevel.java':'''package net.minecraft.server.level;
 public class ServerLevel extends net.minecraft.world.level.Level{}''',
 'net/minecraft/world/entity/player/Player.java':'package net.minecraft.world.entity.player;public class Player{}',
 'com/mojang/authlib/GameProfile.java':'package com.mojang.authlib;public record GameProfile(java.util.UUID id,String name){}',
 'buildcraft/api/v2/registry/RegistryKey.java':'''package buildcraft.api.v2.registry;
 public class RegistryKey<T>{}''',
 'buildcraft/api/v2/registry/RegistryEntry.java':'''package buildcraft.api.v2.registry;
 public record RegistryEntry<T>(net.minecraft.resources.ResourceLocation id,T value){}''',
 'buildcraft/api/v2/registry/ApiRegistry.java':'''package buildcraft.api.v2.registry;
 import java.util.*;import net.minecraft.resources.ResourceLocation;
 public class ApiRegistry<T>{private final List<RegistryEntry<T>> items=new ArrayList<>();
 public void add(ResourceLocation id,T value){items.add(new RegistryEntry<>(id,value));}
 public List<RegistryEntry<T>> entries(){return items;}
 public T get(ResourceLocation id){for(var e:items)if(e.id().equals(id))return e.value();return null;}}''',
 'buildcraft/api/v2/BuildCraftRegistries.java':'''package buildcraft.api.v2;
 import buildcraft.api.v2.registry.*;import buildcraft.api.v2.schematic.*;
 public class BuildCraftRegistries{
 public static final RegistryKey<SchematicAdapter> SCHEMATIC_ADAPTERS=new RegistryKey<>();
 public static final RegistryKey<EntitySchematicAdapter> SCHEMATIC_ENTITY_ADAPTERS=new RegistryKey<>();
 public static final RegistryKey<Object> SNAPSHOT_ELEMENT_TYPES=new RegistryKey<>();
 }''',
 'buildcraft/api/v2/BuildCraftApi.java':'''package buildcraft.api.v2;
 import java.util.*;import buildcraft.api.v2.registry.*;
 public class BuildCraftApi{
 private static final Map<RegistryKey<?>,ApiRegistry<?>> REGISTRIES=new HashMap<>();
 @SuppressWarnings("unchecked")public static <T> ApiRegistry<T> registry(RegistryKey<T> k){
 return (ApiRegistry<T>)REGISTRIES.computeIfAbsent(k,unused->new ApiRegistry<>());}
 }''',
 'buildcraft/api/v2/OperationMode.java':'''package buildcraft.api.v2;
 public enum OperationMode{SIMULATE,EXECUTE;public boolean isSimulation(){return this==SIMULATE;}}''',
 'buildcraft/api/v2/permission/AutomationActor.java':'''package buildcraft.api.v2.permission;
 import java.util.*;public class AutomationActor{
 public Optional<UUID> playerId(){return Optional.empty();}public Optional<String> playerName(){return Optional.empty();}}''',
 'buildcraft/api/v2/schematic/SchematicCaptureContext.java':'''package buildcraft.api.v2.schematic;
 import net.minecraft.core.*;import net.minecraft.world.level.*;import net.minecraft.world.level.block.state.*;
 public record SchematicCaptureContext(Level level,BlockPos origin,BlockPos position,BlockState state){}''',
 'buildcraft/api/v2/schematic/SchematicEntityCaptureContext.java':'''package buildcraft.api.v2.schematic;
 import net.minecraft.core.*;import net.minecraft.world.level.*;
 public record SchematicEntityCaptureContext(Level level,BlockPos origin,Object entity){}''',
 'buildcraft/api/v2/schematic/SchematicPlacementContext.java':'''package buildcraft.api.v2.schematic;
 import net.minecraft.core.*;import net.minecraft.world.level.*;
 public record SchematicPlacementContext(Level level,BlockPos origin,BlockPos position,
 buildcraft.api.v2.OperationMode mode,buildcraft.api.v2.permission.AutomationActor actor){}''',
 'buildcraft/api/v2/schematic/SchematicResult.java':'''package buildcraft.api.v2.schematic;
 public record SchematicResult(Status status,String message){public enum Status{SUCCESS,FAILED}}''',
 'buildcraft/api/v2/schematic/SchematicAdapter.java':'''package buildcraft.api.v2.schematic;
 import java.util.*;
 public interface SchematicAdapter{
 int priority();boolean supports(SchematicCaptureContext c);
 Optional<? extends SnapshotElement> capture(SchematicCaptureContext c);
 default boolean supportsElement(SnapshotElement e){return false;}
 SchematicResult place(SnapshotElement e,SchematicPlacementContext c);
 }''',
 'buildcraft/api/v2/schematic/EntitySchematicAdapter.java':'''package buildcraft.api.v2.schematic;
 import java.util.*;
 public interface EntitySchematicAdapter{
 int priority();boolean supports(SchematicEntityCaptureContext c);
 Optional<? extends SnapshotElement> capture(SchematicEntityCaptureContext c);
 default boolean supportsElement(SnapshotElement e){return false;}
 SchematicResult place(SnapshotElement e,SchematicPlacementContext c);
 }''',
 'buildcraft/builders/internal/schematic/legacy/ISchematicBlock.java':'''package buildcraft.builders.internal.schematic.legacy;
 import buildcraft.api.v2.schematic.SnapshotElement;import net.minecraft.core.BlockPos;
 import net.minecraft.world.level.Level;import net.minecraft.world.entity.player.Player;
 public interface ISchematicBlock extends SnapshotElement{
 boolean canBuild(Level l,BlockPos p);boolean build(Level l,BlockPos p,Player player); }''',
 'buildcraft/builders/internal/schematic/legacy/ISchematicEntity.java':'''package buildcraft.builders.internal.schematic.legacy;
 import buildcraft.api.v2.schematic.SnapshotElement;import net.minecraft.core.BlockPos;import net.minecraft.world.level.Level;
 public interface ISchematicEntity extends SnapshotElement{Object build(Level level,BlockPos origin); }''',
 'buildcraft/builders/internal/schematic/legacy/SchematicBlockContext.java':'''package buildcraft.builders.internal.schematic.legacy;
 public record SchematicBlockContext(net.minecraft.world.level.Level l,net.minecraft.core.BlockPos origin,
 net.minecraft.core.BlockPos position,net.minecraft.world.level.block.state.BlockState state,Object block){}''',
 'buildcraft/builders/internal/schematic/legacy/SchematicEntityContext.java':'''package buildcraft.builders.internal.schematic.legacy;
 public record SchematicEntityContext(net.minecraft.world.level.Level l,net.minecraft.core.BlockPos origin,Object entity){}''',
 'buildcraft/builders/snapshot/SchematicBlockManager.java':'''package buildcraft.builders.snapshot;
 import buildcraft.builders.internal.schematic.legacy.*;
 public class SchematicBlockManager {
 public static ISchematicBlock getLegacySchematicBlock(SchematicBlockContext c){return null;} }''',
 'buildcraft/builders/snapshot/SchematicEntityManager.java':'''package buildcraft.builders.snapshot;
 import buildcraft.builders.internal.schematic.legacy.*;
 public class SchematicEntityManager{
 public static ISchematicEntity getLegacySchematicEntity(SchematicEntityContext c){return null;} }''',
 'buildcraft/lib/misc/FakePlayerProvider.java':'''package buildcraft.lib.misc;
 import com.mojang.authlib.GameProfile;import net.minecraft.core.BlockPos;
 import net.minecraft.server.level.ServerLevel;import net.minecraft.world.entity.player.Player;
 public class FakePlayerProvider{public static final FakePlayerProvider INSTANCE=new FakePlayerProvider();
 public static final GameProfile NULL_PROFILE=new GameProfile(new java.util.UUID(0,0),"Builder");
 public Player getFakePlayer(ServerLevel l,GameProfile p,BlockPos pos){return new Player();}}''',
 'buildcraft/lib/internal/debug/BCLog.java':'''package buildcraft.lib.internal.debug;
 public class BCLog{public static final Logger logger=new Logger();
 public static class Logger{public void warn(String s,Throwable e){} }}''',
}
MAIN=r'''package check;
import java.util.*;import buildcraft.api.v2.*;import buildcraft.api.v2.schematic.*;
import net.minecraft.resources.ResourceLocation;import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;import net.minecraft.server.level.ServerLevel;
import buildcraft.builders.internal.schematic.api2.SchematicServiceImpl;
public class P12SchematicCheck {
 static void ok(boolean pass,String msg){if(!pass)throw new AssertionError(msg);}
 record Sample(ResourceLocation typeId) implements SnapshotElement{}
 static final ResourceLocation TYPE=ResourceLocation.tryParse("addon:stone");
 static final ResourceLocation ENTITY=ResourceLocation.tryParse("addon:entity");
 static final Sample BLOCK=new Sample(TYPE), MOB=new Sample(ENTITY);
 static final SchematicResult SUCCESS=new SchematicResult(SchematicResult.Status.SUCCESS,"done");
 static class Broken implements SchematicAdapter{
 public int priority(){return 100;}
 public boolean supports(SchematicCaptureContext c){throw new IllegalStateException("bad addon");}
 public Optional<SnapshotElement> capture(SchematicCaptureContext c){throw new IllegalStateException("bad capture");}
 public boolean supportsElement(SnapshotElement e){throw new IllegalStateException("bad element");}
 public SchematicResult place(SnapshotElement e,SchematicPlacementContext c){throw new IllegalStateException("bad place");}
 }
 static class Working implements SchematicAdapter{
 boolean fail;
 public int priority(){return 1;}
 public boolean supports(SchematicCaptureContext c){return true;}
 public Optional<SnapshotElement> capture(SchematicCaptureContext c){return Optional.of(BLOCK);}
 public boolean supportsElement(SnapshotElement e){return e.typeId().equals(TYPE);}
 public SchematicResult place(SnapshotElement e,SchematicPlacementContext c){if(fail)throw new IllegalStateException("place failed");return SUCCESS;}
 }
 static class EntityWorking implements EntitySchematicAdapter{
 public int priority(){return 1;}
 public boolean supports(SchematicEntityCaptureContext c){return true;}
 public Optional<SnapshotElement> capture(SchematicEntityCaptureContext c){return Optional.of(MOB);}
 public boolean supportsElement(SnapshotElement e){return e.typeId().equals(ENTITY);}
 public SchematicResult place(SnapshotElement e,SchematicPlacementContext c){return SUCCESS;}
 }
 public static void main(String[] args){
  var types=BuildCraftApi.registry(BuildCraftRegistries.SNAPSHOT_ELEMENT_TYPES);
  types.add(TYPE,new Object());types.add(ENTITY,new Object());
  var blocks=BuildCraftApi.registry(BuildCraftRegistries.SCHEMATIC_ADAPTERS);
  var good=new Working(); blocks.add(ResourceLocation.tryParse("addon:broken"),new Broken());
  blocks.add(ResourceLocation.tryParse("addon:working"),good);
  var entities=BuildCraftApi.registry(BuildCraftRegistries.SCHEMATIC_ENTITY_ADAPTERS);
  entities.add(ResourceLocation.tryParse("addon:entity_adapter"),new EntityWorking());
  var service=SchematicServiceImpl.INSTANCE;var level=new ServerLevel();var pos=new BlockPos(2,3,4);
  var context=new SchematicCaptureContext(level,pos,pos,new BlockState());
  ok(service.capture(context).orElseThrow().typeId().equals(TYPE),"Broken addon blocked block capture");
  var entityContext=new SchematicEntityCaptureContext(level,pos,new Object());
  ok(service.captureEntity(entityContext).orElseThrow().typeId().equals(ENTITY),"Entity capture failed");
  var placement=new SchematicPlacementContext(level,pos,pos,OperationMode.EXECUTE,
    new buildcraft.api.v2.permission.AutomationActor());
  ok(service.place(BLOCK,placement).status()==SchematicResult.Status.SUCCESS,"Block placement missing");
  ok(service.place(MOB,placement).status()==SchematicResult.Status.SUCCESS,"Entity placement missing");
  good.fail=true;
  ok(service.place(BLOCK,placement).status()==SchematicResult.Status.FAILED,"Addon exception crashed Builder");
  ok(service.place(new Sample(ResourceLocation.tryParse("other:none")),placement).status()==SchematicResult.Status.FAILED,
    "Unknown schematic was executed");
  System.out.println("P12 native schematic capture, entity, registry, fault isolation and placement OK");
 }
}'''

class SchematicP12Test(unittest.TestCase):
 def test_real_schematic_dispatcher_and_addon_behaviour(self):
  if not shutil.which('javac') or not shutil.which('java'):self.skipTest('JDK unavailable')
  with tempfile.TemporaryDirectory(prefix='p12-schematic-') as d:
   tmp=Path(d)
   for name,source in STUBS.items():
    p=tmp/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(source)
   harness=tmp/'check/P12SchematicCheck.java';harness.parent.mkdir(parents=True,exist_ok=True);harness.write_text(MAIN)
   sources=[*(str(tmp/name) for name in STUBS),str(harness),
       str(BASE/'buildcraft/api/v2/schematic/SnapshotElement.java'),
       str(BASE/'buildcraft/api/v2/schematic/SchematicService.java'),
       str(BASE/'buildcraft/builders/internal/schematic/api2/SchematicServiceImpl.java')]
   compiled=subprocess.run(['javac','-d',str(tmp/'classes'),*sources],capture_output=True,text=True,timeout=40)
   self.assertEqual(compiled.returncode,0,compiled.stderr)
   executed=subprocess.run(['java','-cp',str(tmp/'classes'),'check.P12SchematicCheck'],capture_output=True,text=True,timeout=20)
   self.assertEqual(executed.returncode,0,executed.stdout+'\n'+executed.stderr)
if __name__=='__main__':unittest.main()
