#!/usr/bin/env python3
"""Compile an independent addon format handler with the Builder-owned Electronic Library backend."""
from __future__ import annotations
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / 'source-shared/src/main/java'
STUBS = {
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
MAIN = r'''package check;
import java.io.*;import java.nio.charset.*;import java.util.*;
import net.minecraft.nbt.*;import net.minecraft.resources.*;
import buildcraft.api.v2.library.*;
import buildcraft.builders.internal.schematic.api2.*;
import dev.bcce.apifixture.BlueprintLibraryFixtureAddon;
public class P12LibraryCheck {
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
  System.out.println("P12 independent Electronic Library addon import/export/persistence/limits OK");
 }
}'''

class LibraryP12Test(unittest.TestCase):
 def test_real_library_runtime_with_independent_addon(self):
  if not shutil.which('javac') or not shutil.which('java'):self.skipTest('JDK unavailable')
  addon=ROOT/'addon-fixture/src/main/java/dev/bcce/apifixture/BlueprintLibraryFixtureAddon.java'
  data=addon.read_text()
  self.assertNotIn('buildcraft.builders.',data)
  self.assertNotIn('buildcraft.lib.',data)
  with tempfile.TemporaryDirectory(prefix='p12-library-') as d:
   tmp=Path(d)
   for name,source in STUBS.items():
    path=tmp/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(source)
   harness=tmp/'check/P12LibraryCheck.java';harness.parent.mkdir(parents=True,exist_ok=True);harness.write_text(MAIN)
   sources=[*(str(tmp/name) for name in STUBS),str(addon),str(harness),
       *(str(BASE/('buildcraft/api/v2/library/'+name)) for name in ('LibraryFormatHandler.java',
          'LibraryFormatRegistration.java','LibraryService.java','LibraryEntry.java')),
       str(BASE/'buildcraft/builders/internal/schematic/api2/LibraryServiceImpl.java')]
   result=subprocess.run(['javac','-encoding','UTF-8','-d',str(tmp/'classes'),*sources],capture_output=True,text=True,timeout=40)
   self.assertEqual(result.returncode,0,result.stderr)
   test=subprocess.run(['java','-cp',str(tmp/'classes'),'check.P12LibraryCheck'],capture_output=True,text=True,timeout=30)
   self.assertEqual(test.returncode,0,test.stdout+'\n'+test.stderr)


 def test_materialized_library_addon_stays_public_across_targets(self):
  import sys
  scripts=str(ROOT/'scripts')
  if scripts not in sys.path:sys.path.insert(0,scripts)
  from source_config import load_properties,target_ids,target_layout
  from source_layout import _materialize_text_file

  addon=ROOT/'addon-fixture/src/main/java/dev/bcce/apifixture/BlueprintLibraryFixtureAddon.java'
  properties=load_properties()
  with tempfile.TemporaryDirectory(prefix='p12-public-fixture-') as d:
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
   simple=STUBS['net/minecraft/nbt/CompoundTag.java']
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
         str(stub),str(BASE/'buildcraft/api/v2/library/LibraryFormatHandler.java'),
         str(addon_source),str(check)],capture_output=True,text=True,timeout=35)
     self.assertEqual(0,compilation.returncode,compilation.stderr)
     run=subprocess.run(['java','-cp',str(classes),'check.Check'],capture_output=True,text=True,timeout=15)
     self.assertEqual(0,run.returncode,run.stderr)

if __name__=='__main__':unittest.main()
