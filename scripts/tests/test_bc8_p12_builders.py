#!/usr/bin/env python3
"""Independent template-handler conformance and production integration for builders."""
from __future__ import annotations
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
API = ROOT/'source-shared/src/main/java'
STUBS = {
 'net/minecraft/resources/ResourceLocation.java': '''package net.minecraft.resources;
 public record ResourceLocation(String id){public static ResourceLocation tryParse(String s){return new ResourceLocation(s);}}''',
 'net/minecraft/core/BlockPos.java':'package net.minecraft.core;public record BlockPos(int x,int y,int z){}',
 'net/minecraft/world/level/Level.java':'package net.minecraft.world.level;public class Level{}',
 'net/minecraft/world/entity/player/Player.java':'package net.minecraft.world.entity.player;public class Player{}',
 'net/minecraft/world/item/ItemStack.java':'''package net.minecraft.world.item;
 public record ItemStack(int count){public int getCount(){return count;}}''',
}
MAIN = r'''package check;
import buildcraft.api.v2.template.*;
import buildcraft.lib.internal.api.v2.TemplateServiceImpl;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.player.Player;
import dev.bcce.apifixture.TemplatePlacementFixtureAddon;
public class P12TemplateCheck {
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
  System.out.println("P12 addon template placement, priority, fault isolation OK");
 }
}'''

class BuildersP12Tests(unittest.TestCase):
 def test_independent_template_handlers_run_in_actual_service(self):
  if not shutil.which('javac') or not shutil.which('java'):self.skipTest('JDK unavailable')
  addon=ROOT/'addon-fixture/src/main/java/dev/bcce/apifixture/TemplatePlacementFixtureAddon.java'
  text=addon.read_text();self.assertNotIn('buildcraft.lib.',text);self.assertNotIn('buildcraft.builders.',text)
  with tempfile.TemporaryDirectory(prefix='p12-template-') as d:
   tmp=Path(d)
   for name,value in STUBS.items():
    file=tmp/name;file.parent.mkdir(parents=True,exist_ok=True);file.write_text(value)
   check=tmp/'check/P12TemplateCheck.java';check.parent.mkdir(parents=True,exist_ok=True);check.write_text(MAIN)
   sources=[*(str(tmp/name) for name in STUBS),str(addon),str(check),
     *(str(API/('buildcraft/api/v2/template/'+name)) for name in
       ('TemplateHandler.java','TemplateRegistration.java','TemplateService.java')),
     str(API/'buildcraft/lib/internal/api/v2/TemplateServiceImpl.java')]
   compiled=subprocess.run(['javac','-encoding','UTF-8','-d',str(tmp/'classes'),*sources],capture_output=True,timeout=40)
   self.assertEqual(compiled.returncode,0,compiled.stderr.decode('utf-8',errors='replace'))
   result=subprocess.run(['java','-cp',str(tmp/'classes'),'check.P12TemplateCheck'],capture_output=True,timeout=20)
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
  bootstrap=(API/'buildcraft/builders/internal/schematic/api2/BuildersSchematicApi2.java').read_text()
  self.assertIn('BuildCraftServices.LIBRARY, LibraryServiceImpl.INSTANCE',bootstrap)
  self.assertIn('BuildCraftServices.SCHEMATICS, SchematicServiceImpl.INSTANCE',bootstrap)
  for name,call in [('SchematicBlockManager.java','.capture(new SchematicCaptureContext('),
      ('SchematicEntityManager.java','.captureEntity(new SchematicEntityCaptureContext(')]:
   src=(API/'buildcraft/builders/snapshot'/name).read_text()
   self.assertIn(call,src)
  filler=(API/'buildcraft/builders/api2/FillerApi2Bridge.java').read_text()
  self.assertIn('BuildCraftRegistries.FILLER_PATTERN_TYPES',filler)
  self.assertIn('type.pattern().createMask(',filler)
  features=(API/'buildcraft/api/v2/BuildCraftFeatures.java').read_text()
  self.assertIn('LIBRARY_FORMATS = id("library_formats")',features)
  for location in ('source-shared','source-families/26.X','source-family-platforms/26.X/neoforge'):
   runtime=(ROOT/f'{location}/src/main/java/buildcraft/lib/internal/api/v2/BuildCraftApiRuntime.java').read_text()
   self.assertIn('new ApiFeature(BuildCraftFeatures.LIBRARY_FORMATS, 1)',runtime)

if __name__=='__main__':unittest.main()
