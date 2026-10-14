#!/usr/bin/env python3
"""Electronic Library, templates, schematics and native placement."""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

import shutil
import subprocess
import tempfile
import unittest

LIBRARY_BASE = ROOT / 'source-shared/src/main/java'
LIBRARY_STUBS = {
 'net/minecraft/resources/ResourceLocation.java': '''package net.minecraft.resources;
 public record ResourceLocation(String value){public static ResourceLocation tryParse(String v){return new ResourceLocation(v);}public String toString(){return value;}}''',
 'net/minecraft/nbt/CompoundTag.java': '''package net.minecraft.nbt;
 public class CompoundTag{private final java.util.Map<String,String> m=new java.util.HashMap<>();
 public void putString(String k,String v){m.put(k,v);}public String getString(String k){return m.getOrDefault(k,"");}
 public CompoundTag copy(){CompoundTag c=new CompoundTag();c.m.putAll(m);return c;}}''',
 'buildcraft/builders/snapshot/Snapshot.java': '''package buildcraft.builders.snapshot;
 import java.util.*;import net.minecraft.nbt.CompoundTag;
 public class Snapshot {
  public Key key=new Key("",null); public String name=""; public Kind kind=Kind.BLUEPRINT;
  public enum Kind{BLUEPRINT,TEMPLATE} public Kind getType(){return kind;}
  public static Snapshot readFromNBT(CompoundTag t) throws java.io.IOException{Snapshot s=new Snapshot();s.name=t.getString("name");
   s.kind=Kind.valueOf(t.getString("type"));return s;}
  public static CompoundTag writeToNBT(Snapshot s){CompoundTag t=new CompoundTag();t.putString("name",s.name);
   t.putString("type",s.kind.name());return t;}
  public void computeKey(){key=new Key("digest_"+name,key.header);}
  public static class Key {
   private final String id; public final Header header;
   public Key(String id,Header h){this.id=id;header=h;}
   public Key(Key src,Header h){this(src.id,h);}public String toString(){return id;}
  }
  public static class Header {
   public final Key key;public final UUID owner;public final String ownerName; public final Date created;
   public final String name; public final boolean allowCreative,canRotate,canExcavate;
   public Header(Key key,UUID owner,Date created,String name){this(key,owner,"",created,name,true,true,true);}
   public Header(Key key,UUID owner,String ownerName,Date created,String name,
       boolean allowCreative,boolean canRotate,boolean canExcavate){this.key=key;this.owner=owner;
       this.ownerName=ownerName;this.created=created;this.name=name;this.allowCreative=allowCreative;
       this.canRotate=canRotate;this.canExcavate=canExcavate;}
  }
 }''',
 'buildcraft/builders/snapshot/GlobalSavedDataSnapshots.java': '''package buildcraft.builders.snapshot;
 import java.util.*;
 public class GlobalSavedDataSnapshots {
  private static final Map<String,Snapshot> cache=new LinkedHashMap<>();
  public static java.util.List<Snapshot.Key> getClientSnapshotList(){return cache.values().stream().map(v->v.key).toList();}
  public static Snapshot getClientSnapshot(Snapshot.Key key){return cache.get(key.toString());}
  public static void saveClientSnapshot(Snapshot value){cache.putIfAbsent(value.key.toString(),value);}
 }''',
}
LIBRARY_MAIN = r'''package check;
import java.io.*;import java.nio.charset.*;import java.util.*;
import net.minecraft.nbt.*;import net.minecraft.resources.*;
import buildcraft.api.v2.library.*;
import buildcraft.builders.internal.schematic.api2.*;
import dev.bcce.apifixture.BlueprintLibraryFixtureAddon;
public class LibraryFormatProbe {
 static void ok(boolean b,String msg){if(!b)throw new AssertionError(msg);}
 static byte[] text(String s){return s.getBytes(StandardCharsets.UTF_8);}
 public static void main(String[] args) throws Exception {
  var service=LibraryServiceImpl.INSTANCE;
  var format=ResourceLocation.tryParse("fixture:blueprint");
  service.register(format,"bcmod",7,new BlueprintLibraryFixtureAddon());
  ok(service.formats().size()==1,"Registration missing");
  ok(service.formatForFilename("Sample.BCMOD").isPresent(),"Case insensitive suffix not found");
  ok(service.formatForFilename("Sample.bcmod.backup").isEmpty(),"Format matched wrong suffix");
  ok(service.decode("unknown.dat",text("x")).isEmpty(),"Unknown extension decoded");
  var entry=service.importClient("Imported.bcmod",text("Workshop|BLUEPRINT"));
  ok(entry.kind().toString().equals("buildcraft:blueprint"),"Blueprint type lost");
  ok(entry.contentId().equals("digest_Workshop"),"Content key not recalculated");
  ok(service.clientEntries().size()==1,"Library index not updated");
  ok(service.clientDocument(entry.contentId()).orElseThrow().getString("name").equals("Workshop"),"Document missing");
  var modified=service.clientDocument(entry.contentId()).orElseThrow();modified.putString("name","CORRUPTED");
  ok(service.clientDocument(entry.contentId()).orElseThrow().getString("name").equals("Workshop"),"Document was mutable");
  ok(new String(service.exportClient(entry.contentId(),format),StandardCharsets.UTF_8).equals("Workshop|BLUEPRINT"),"Export failed");
  service.importClient("Building.bcmod",text("Tower|TEMPLATE"));
  ok(service.clientEntries().size()==2,"Template import failed");
  try { service.register(ResourceLocation.tryParse("fixture:second"),"bcmod",0,new BlueprintLibraryFixtureAddon());
    throw new AssertionError("Duplicate extension accepted"); }catch(IllegalStateException expected){}
  try { service.register(ResourceLocation.tryParse("fixture:reserved"),"bcnbt",0,new BlueprintLibraryFixtureAddon());
    throw new AssertionError("Native extension shadowed"); }catch(IllegalArgumentException expected){}
  try { service.importClient("broken.bcmod",text("garbage"));
    throw new AssertionError("Corrupt snapshot accepted"); }catch(IOException expected){}
  ok(service.clientEntries().size()==2,"Corrupt import wrote to cache");
  try { service.decode("too-large.bcmod",new byte[LibraryServiceImpl.MAX_FORMAT_BYTES+1]);
    throw new AssertionError("Oversize input accepted"); }catch(IOException expected){}
  try { service.exportClient("not-found",format);throw new AssertionError("Missing entry exported");
  }catch(IOException expected){}
  System.out.println("Independent Electronic Library addon import/export/persistence/limits OK");
 }
}'''

class LibraryFormatConformanceTest(unittest.TestCase):
 def test_real_library_runtime_with_independent_addon(self):
  if not shutil.which('javac') or not shutil.which('java'):self.skipTest('JDK unavailable')
  addon=ROOT/'addon-fixture/src/main/java/dev/bcce/apifixture/BlueprintLibraryFixtureAddon.java'
  data=addon.read_text()
  self.assertNotIn('buildcraft.builders.',data)
  self.assertNotIn('buildcraft.lib.',data)
  with tempfile.TemporaryDirectory(prefix='library-format-') as d:
   tmp=Path(d)
   for name,source in LIBRARY_STUBS.items():
    path=tmp/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(source)
   harness=tmp/'check/LibraryFormatProbe.java';harness.parent.mkdir(parents=True,exist_ok=True);harness.write_text(LIBRARY_MAIN)
   sources=[*(str(tmp/name) for name in LIBRARY_STUBS),str(addon),str(harness),
       *(str(LIBRARY_BASE/('buildcraft/api/v2/library/'+name)) for name in ('LibraryFormatHandler.java',
          'LibraryFormatRegistration.java','LibraryService.java','LibraryEntry.java')),
       str(LIBRARY_BASE/'buildcraft/builders/internal/schematic/api2/LibraryServiceImpl.java')]
   result=subprocess.run(['javac','-encoding','UTF-8','-d',str(tmp/'classes'),*sources],capture_output=True,text=True,timeout=40)
   self.assertEqual(result.returncode,0,result.stderr)
   test=subprocess.run(['java','-cp',str(tmp/'classes'),'check.LibraryFormatProbe'],capture_output=True,text=True,timeout=30)
   self.assertEqual(test.returncode,0,test.stdout+'\n'+test.stderr)


 def test_materialized_library_addon_stays_public_across_targets(self):
  import sys
  scripts=str(ROOT/'scripts')
  if scripts not in sys.path:sys.path.insert(0,scripts)
  from source_config import load_properties,target_ids,target_layout
  from source_layout import _materialize_text_file

  addon=ROOT/'addon-fixture/src/main/java/dev/bcce/apifixture/BlueprintLibraryFixtureAddon.java'
  properties=load_properties()
  with tempfile.TemporaryDirectory(prefix='library-addon-') as d:
   tmp=Path(d)
   for target in target_ids(properties):
    layout=target_layout(target,properties)
    selected=tmp/target/'dev/bcce/apifixture/BlueprintLibraryFixtureAddon.java'
    _materialize_text_file(addon,selected,
       logical_relative='addon-fixture/src/main/java/dev/bcce/apifixture/BlueprintLibraryFixtureAddon.java',
       minecraft=properties[f'target.{target}.deps.minecraft'],
       family=layout.family,platform=layout.platform,preprocess=True)
    code=selected.read_text(encoding='utf-8')
    with self.subTest(target=target):
     self.assertNotIn('buildcraft.lib.',code)
     self.assertIn('readString(snapshot, "type")',code)

   # Minecraft changed CompoundTag#getString from String to Optional<String>.
   simple=LIBRARY_STUBS['net/minecraft/nbt/CompoundTag.java']
   modern=simple.replace('public String getString(String k){return m.getOrDefault(k,"");}',
      'public java.util.Optional<String> getString(String k){return java.util.Optional.ofNullable(m.get(k));}')
   self.assertNotEqual(simple,modern)
   harness='''package check;
import dev.bcce.apifixture.BlueprintLibraryFixtureAddon;
import java.nio.charset.StandardCharsets;
public class Check {
 public static void main(String[] args) throws Exception {
  var codec=new BlueprintLibraryFixtureAddon();
  var blueprint=codec.decode("Workshop|BLUEPRINT".getBytes(StandardCharsets.UTF_8));
  var exported=new String(codec.encode(blueprint),StandardCharsets.UTF_8);
  if (!exported.equals("Workshop|BLUEPRINT")) throw new AssertionError(exported);
  var template=codec.decode("Tower|TEMPLATE".getBytes(StandardCharsets.UTF_8));
  if (!new String(codec.encode(template),StandardCharsets.UTF_8).equals("Tower|TEMPLATE")) throw new AssertionError();
  try { codec.decode("invalid".getBytes(StandardCharsets.UTF_8)); throw new AssertionError(); }
  catch (java.io.IOException expected) {}
 }
}
'''
   for target,nbt_stub in [('1.19.2-forge',simple),('1.21.11-neoforge',modern),('26.3-neoforge',modern)]:
    with self.subTest(compilation=target):
     temp=tmp/('javac-'+target)
     stub=temp/'net/minecraft/nbt/CompoundTag.java'
     stub.parent.mkdir(parents=True,exist_ok=True)
     stub.write_text(nbt_stub,encoding='utf-8')
     check=temp/'check/Check.java'
     check.parent.mkdir(parents=True,exist_ok=True)
     check.write_text(harness,encoding='utf-8')
     addon_source=tmp/target/'dev/bcce/apifixture/BlueprintLibraryFixtureAddon.java'
     classes=temp/'classes'
     compilation=subprocess.run(['javac','--release','17','-encoding','UTF-8','-d',str(classes),
         str(stub),str(LIBRARY_BASE/'buildcraft/api/v2/library/LibraryFormatHandler.java'),
         str(addon_source),str(check)],capture_output=True,text=True,timeout=35)
     self.assertEqual(0,compilation.returncode,compilation.stderr)
     run=subprocess.run(['java','-cp',str(classes),'check.Check'],capture_output=True,text=True,timeout=15)
     self.assertEqual(0,run.returncode,run.stderr)


TEMPLATES_API = ROOT/'source-shared/src/main/java'
TEMPLATES_STUBS = {
 'net/minecraft/resources/ResourceLocation.java': '''package net.minecraft.resources;
 public record ResourceLocation(String id){public static ResourceLocation tryParse(String s){return new ResourceLocation(s);}}''',
 'net/minecraft/core/BlockPos.java':'package net.minecraft.core;public record BlockPos(int x,int y,int z){}',
 'net/minecraft/world/level/Level.java':'package net.minecraft.world.level;public class Level{}',
 'net/minecraft/world/entity/player/Player.java':'package net.minecraft.world.entity.player;public class Player{}',
 'net/minecraft/world/item/ItemStack.java':'''package net.minecraft.world.item;
 public record ItemStack(int count){public int getCount(){return count;}}''',
}
TEMPLATES_MAIN = r'''package check;
import buildcraft.api.v2.template.*;
import buildcraft.lib.internal.api.v2.TemplateServiceImpl;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.player.Player;
import dev.bcce.apifixture.TemplatePlacementFixtureAddon;
public class TemplatePlacementProbe {
 static void ok(boolean b,String message){if(!b)throw new AssertionError(message);}
 public static void main(String[] args){
  var service=new TemplateServiceImpl();var addon=new TemplatePlacementFixtureAddon();
  var item=new ItemStack(3);var ignored=new ItemStack(1);
  service.register(ResourceLocation.tryParse("fixture:reject"),100, new TemplateHandler(){
   public boolean acceptsItem(ItemStack s){throw new IllegalArgumentException("bad addon predicate");}
   public boolean handle(Level l,BlockPos p,Player a,ItemStack s){throw new IllegalStateException("bad addon tick");}
  });
  service.register(ResourceLocation.tryParse("fixture:custom"),1,addon);
  ok(service.acceptsItem(item),"Addon-only item rejected");
  ok(!service.acceptsItem(ignored),"Vanilla stack passed custom predicate");
  ok(service.handle(new Level(),new BlockPos(0,0,0),new Player(),item),"Addon did not place");
  ok(addon.executions==1,"Addon received wrong tick count");
  ok(!service.handle(new Level(),new BlockPos(0,0,0),new Player(),ignored),"Unknown item handled");
  ok(service.handlers().get(0).priority()==100,"Handler priority lost");
  try{service.register(ResourceLocation.tryParse("fixture:custom"),0,addon);
   throw new AssertionError("duplicate handler accepted");}catch(IllegalStateException expected){}
  System.out.println("Addon template placement, priority, fault isolation OK");
 }
}'''

class TemplatePlacementConformanceTest(unittest.TestCase):
 def test_independent_template_handlers_run_in_actual_service(self):
  if not shutil.which('javac') or not shutil.which('java'):self.skipTest('JDK unavailable')
  addon=ROOT/'addon-fixture/src/main/java/dev/bcce/apifixture/TemplatePlacementFixtureAddon.java'
  text=addon.read_text();self.assertNotIn('buildcraft.lib.',text);self.assertNotIn('buildcraft.builders.',text)
  with tempfile.TemporaryDirectory(prefix='template-placement-') as d:
   tmp=Path(d)
   for name,value in TEMPLATES_STUBS.items():
    file=tmp/name;file.parent.mkdir(parents=True,exist_ok=True);file.write_text(value)
   check=tmp/'check/TemplatePlacementProbe.java';check.parent.mkdir(parents=True,exist_ok=True);check.write_text(TEMPLATES_MAIN)
   sources=[*(str(tmp/name) for name in TEMPLATES_STUBS),str(addon),str(check),
     *(str(TEMPLATES_API/('buildcraft/api/v2/template/'+name)) for name in
       ('TemplateHandler.java','TemplateRegistration.java','TemplateService.java')),
     str(TEMPLATES_API/'buildcraft/lib/internal/api/v2/TemplateServiceImpl.java')]
   compiled=subprocess.run(['javac','-encoding','UTF-8','-d',str(tmp/'classes'),*sources],capture_output=True,timeout=40)
   self.assertEqual(compiled.returncode,0,compiled.stderr.decode('utf-8',errors='replace'))
   result=subprocess.run(['java','-cp',str(tmp/'classes'),'check.TemplatePlacementProbe'],capture_output=True,timeout=20)
   self.assertEqual(result.returncode,0,(result.stdout+b'\n'+result.stderr).decode('utf-8',errors='replace'))

 def test_production_bridges_and_native_library_stores(self):
  for family in ('old','1.21.X'):
   template=ROOT/f'source-families/{family}/src/main/java/buildcraft/builders/snapshot/TemplateBuilder.java'
   src=template.read_text()
   self.assertIn('BuildCraftServices.TEMPLATES).acceptsItem(stack)',src)
   self.assertIn('TemplateHandlerDefault.INSTANCE.handle',src)
  for loader in ('forge','neoforge'):
   store=ROOT/f'source-platforms/{loader}/src/main/java/buildcraft/builders/snapshot/GlobalSavedDataSnapshots.java'
   src=store.read_text()
   self.assertIn('LibraryServiceImpl.normalizedSnapshot(file.getName()',src)
   self.assertIn('library.decode(file.getName(), Files.readAllBytes(file.toPath()))',src)
   self.assertIn('library.formatForFilename(file.getName()).isPresent()',src)
   self.assertIn('getServerStore(level).getSnapshot(key)',src)
   self.assertIn('getClientSnapshot(key)',src)
   self.assertIn('!result.contains(snapshot.key)',src)
   self.assertIn('existing.getRight().getName().toLowerCase(Locale.ROOT).endsWith(SNAPSHOT_FILE_EXTENSION)',src)
  bootstrap=(TEMPLATES_API/'buildcraft/builders/internal/schematic/api2/BuildersSchematicApi2.java').read_text()
  self.assertIn('BuildCraftServices.LIBRARY, LibraryServiceImpl.INSTANCE',bootstrap)
  self.assertIn('BuildCraftServices.SCHEMATICS, SchematicServiceImpl.INSTANCE',bootstrap)
  for name,call in [('SchematicBlockManager.java','.capture(new SchematicCaptureContext('),
      ('SchematicEntityManager.java','.captureEntity(new SchematicEntityCaptureContext(')]:
   src=(TEMPLATES_API/'buildcraft/builders/snapshot'/name).read_text()
   self.assertIn(call,src)
  filler=(TEMPLATES_API/'buildcraft/builders/api2/FillerApi2Bridge.java').read_text()
  self.assertIn('BuildCraftRegistries.FILLER_PATTERN_TYPES',filler)
  self.assertIn('type.pattern().createMask(',filler)
  features=(TEMPLATES_API/'buildcraft/api/v2/BuildCraftFeatures.java').read_text()
  self.assertIn('LIBRARY_FORMATS = id("library_formats")',features)
  for location in ('source-shared','source-families/26.X','source-family-platforms/26.X/neoforge'):
   runtime=(ROOT/f'{location}/src/main/java/buildcraft/lib/internal/api/v2/BuildCraftApiRuntime.java').read_text()
   self.assertIn('new ApiFeature(BuildCraftFeatures.LIBRARY_FORMATS, 1)',runtime)


SCHEMATIC_BASE=ROOT/'source-shared/src/main/java'
SCHEMATIC_STUBS={
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
SCHEMATIC_MAIN=r'''package check;
import java.util.*;import buildcraft.api.v2.*;import buildcraft.api.v2.schematic.*;
import net.minecraft.resources.ResourceLocation;import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;import net.minecraft.server.level.ServerLevel;
import buildcraft.builders.internal.schematic.api2.SchematicServiceImpl;
public class SchematicDispatchProbe {
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
  System.out.println("Native schematic capture, entity, registry, fault isolation and placement OK");
 }
}'''

class SchematicDispatchConformanceTest(unittest.TestCase):
 def test_real_schematic_dispatcher_and_addon_behaviour(self):
  if not shutil.which('javac') or not shutil.which('java'):self.skipTest('JDK unavailable')
  with tempfile.TemporaryDirectory(prefix='schematic-dispatch-') as d:
   tmp=Path(d)
   for name,source in SCHEMATIC_STUBS.items():
    p=tmp/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(source)
   harness=tmp/'check/SchematicDispatchProbe.java';harness.parent.mkdir(parents=True,exist_ok=True);harness.write_text(SCHEMATIC_MAIN)
   sources=[*(str(tmp/name) for name in SCHEMATIC_STUBS),str(harness),
       str(SCHEMATIC_BASE/'buildcraft/api/v2/schematic/SnapshotElement.java'),
       str(SCHEMATIC_BASE/'buildcraft/api/v2/schematic/SchematicService.java'),
       str(SCHEMATIC_BASE/'buildcraft/builders/internal/schematic/api2/SchematicServiceImpl.java')]
   compiled=subprocess.run(['javac','-d',str(tmp/'classes'),*sources],capture_output=True,text=True,timeout=40)
   self.assertEqual(compiled.returncode,0,compiled.stderr)
   executed=subprocess.run(['java','-cp',str(tmp/'classes'),'check.SchematicDispatchProbe'],capture_output=True,text=True,timeout=20)
   self.assertEqual(executed.returncode,0,executed.stdout+'\n'+executed.stderr)

if __name__ == "__main__":
    unittest.main()
