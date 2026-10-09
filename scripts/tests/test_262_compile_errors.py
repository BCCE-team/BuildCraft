#!/usr/bin/env python3
"""Regression checks for verified 26.2 compiler diagnostics, without altering frozen 26.1.2."""
from __future__ import annotations

import hashlib
import json
import re
import sys
import tempfile
import unittest
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
sys.path[:0]=[str(ROOT/'scripts'),str(ROOT/'scripts/tests')]
from source_config import load_properties,target_layout
from source_layout import resolve_effective_source,_materialize_text_file
from minecraft_compat_fixture import parse_sources

# Every type/compilation error in the original log mapped to one of these effective Java files.
ERROR_FILES=(
 'builders/snapshot/SchematicEntityDefault.java',
 'energy/BCEnergyRecipes.java',
 'lib/client/guide/GuideContent.java',
 'lib/misc/LocaleUtil.java',
 'lib/recipe/AssemblyRecipeBuilder.java',
 'lib/recipe/NbtShapedRecipeBuilder.java',
 'robotics/SimpleRobotRegistryProvider.java',
 'robotics/ai/AIRobotSearchBlock.java',
 'robotics/boards/BoardRobotMiner.java',
 'robotics/client/render/RenderRobot.java',
 'robotics/client/render/RenderZonePlanner.java',
 'robotics/container/ContainerRequester.java',
 'robotics/gui/GuiRequester.java',
 'robotics/gui/GuiZonePlanner.java',
 'robotics/tile/TileZonePlanner.java',
 'robotics/zone/MessageZoneMapRequest.java',
 'robotics/zone/ZonePlannerMapChunk.java',
 'robotics/zone/ZonePlannerMapChunkKey.java',
 'robotics/zone/ZonePlannerMapDataServer.java',
 'silicon/client/render/AdvDebuggerLaser.java',
 'transport/client/model/ModelPipeItem.java',
 'transport/item/ItemPipeHolder.java',
)

class Compiler262Regression(unittest.TestCase):
 @classmethod
 def setUpClass(cls):
  cls.temporary=tempfile.TemporaryDirectory(prefix='bc-262-compiler-')
  cls.trees={}
  props=load_properties()
  for ver in ('26.1.2','26.2','26.3'):
   layout=target_layout(ver+'-neoforge',props)
   downs=tuple(root for root in (layout.family_downport_root,layout.family_platform_downport_root) if root)
   root=Path(cls.temporary.name)/ver
   for name in ERROR_FILES:
    relative='src/main/java/buildcraft/'+name
    source=resolve_effective_source(layout,props,relative)
    assert source is not None,relative
    _materialize_text_file(source,root/relative,logical_relative=relative,
      minecraft=props[f'target.{ver}-neoforge.deps.minecraft'],
      family=layout.family,platform=layout.platform,preprocess=True,
      native_source=any(source.is_relative_to(d) for d in downs))
   cls.trees[ver]=root

 @classmethod
 def tearDownClass(cls): cls.temporary.cleanup()

 def source(self,version,name):
  return (self.trees[version]/'src/main/java/buildcraft'/name).read_text(encoding='utf-8')

 def test_2612_all_twenty_two_original_hashes(self):
  expected=json.loads((ROOT/'build-config/materialized-baselines/26.1.2-neoforge.json').read_text())['files']
  self.assertEqual(22,len(ERROR_FILES))
  for name in ERROR_FILES:
   path='src/main/java/buildcraft/'+name
   with self.subTest(path=path):
    self.assertEqual(expected[path],hashlib.sha256((self.trees['26.1.2']/path).read_bytes()).hexdigest())

 def test_262_record_chunk_position_accessors(self):
  for file in ('robotics/ai/AIRobotSearchBlock.java',
               'robotics/zone/MessageZoneMapRequest.java','robotics/zone/ZonePlannerMapChunk.java',
               'robotics/zone/ZonePlannerMapChunkKey.java','robotics/zone/ZonePlannerMapDataServer.java',
               'robotics/tile/TileZonePlanner.java'):
   code=self.source('26.2',file)
   with self.subTest(file=file):
    self.assertNotRegex(code,r'\b(?:chunkPos|plannerChunk)\.(?:x|z)\b(?!\()')
    self.assertRegex(code,r'\b(?:chunkPos|plannerChunk)\.(?:x|z)\(\)')
  for file in ('robotics/SimpleRobotRegistryProvider.java','robotics/zone/MessageZoneMapRequest.java'):
   self.assertNotIn('new ChunkPos(new BlockPos(',self.source('26.2',file))
   self.assertNotIn('new ChunkPos(menu.tile.getBlockPos())',self.source('26.2',file))

 def test_262_saveddata_and_ore_tags(self):
  registry=self.source('26.2','robotics/SimpleRobotRegistryProvider.java')
  self.assertIn('SavedDataStorage',registry)
  self.assertNotIn('DimensionDataStorage',registry)
  self.assertIn('Identifier.withDefaultNamespace(DATA_NAME)',registry)
  ore=self.source('26.2','robotics/boards/BoardRobotMiner.java')
  self.assertIn('Tags.Blocks.ORES',ore)
  self.assertIn('BlockTags.IRON_ORES',ore)
  self.assertIn('BlockTags.COPPER_ORES',ore)
  self.assertIn('BlockTags.GOLD_ORES',ore)
  self.assertNotIn('BlockTags.ORES)',ore)
  for removed in ('COAL_ORES','REDSTONE_ORES','EMERALD_ORES','LAPIS_ORES','DIAMOND_ORES'):
   self.assertNotIn('BlockTags.'+removed,ore)

 def test_262_click_input_and_gui_extraction(self):
  requester=self.source('26.2','robotics/container/ContainerRequester.java')
  self.assertIn('ContainerInput.PICKUP',requester)
  self.assertIn('ContainerInput.QUICK_MOVE',requester)
  self.assertNotIn('ClickType',requester)
  for name in ('robotics/gui/GuiZonePlanner.java','robotics/gui/GuiRequester.java'):
   gui=self.source('26.2',name)
   with self.subTest(gui=name):
    self.assertNotIn('imageWidth = SIZE_X;',gui)
    self.assertNotIn('imageHeight = SIZE_Y;',gui)
    self.assertIn('super(container, inv, title, SIZE_X, SIZE_Y)',gui)
  gui=self.source('26.2','robotics/gui/GuiZonePlanner.java')
  self.assertIn('extractRenderState(GuiGraphicsExtractor',gui)
  self.assertIn('drawBackgroundLayer(PoseStack pose',gui)
  self.assertIn('drawForegroundLayer(PoseStack pose',gui)
  self.assertNotIn('import net.minecraft.client.gui.GuiGraphics;',gui)
  self.assertIn('new net.minecraft.client.input.CharacterEvent(codePoint)',gui)
  self.assertNotIn('new net.minecraft.client.input.CharacterEvent(codePoint, modifiers)',gui)
  self.assertIn('drawMap(guiGraphics, mouseX, mouseY)',gui)

 def test_262_client_lights_camera_and_sheets(self):
  for name in ('robotics/client/render/RenderRobot.java','robotics/client/render/RenderZonePlanner.java'):
   code=self.source('26.2',name)
   self.assertIn('net.minecraft.client.renderer.state.level.CameraRenderState',code)
   self.assertIn('net.minecraft.util.LightCoordsUtil;',code)
   self.assertIn('LightCoordsUtil.FULL_BRIGHT',code)
   self.assertNotIn('LightTexture',code)
  self.assertIn('Sheets.cutoutBlockItemSheet()',self.source('26.2','transport/client/model/ModelPipeItem.java'))

 def test_262_recipes_localization_and_entity_spawn(self):
  for name in ('lib/recipe/AssemblyRecipeBuilder.java','lib/recipe/NbtShapedRecipeBuilder.java'):
   code=self.source('26.2',name)
   self.assertIn('net.minecraft.advancements.triggers.Criterion;',code)
   self.assertIn('net.minecraft.advancements.triggers.RecipeUnlockedTrigger;',code)
   self.assertNotIn('net.minecraft.advancements.criterion.RecipeUnlockedTrigger',code)
  self.assertNotIn('InventoryChangeTrigger.TriggerInstance',self.source('26.2','energy/BCEnergyRecipes.java'))
  for name in ('lib/client/guide/GuideContent.java','lib/misc/LocaleUtil.java','transport/item/ItemPipeHolder.java'):
   code=self.source('26.2',name)
   with self.subTest(name=name):
    self.assertNotIn('I18n.exists(',code)
    self.assertIn('Language.getInstance().has(',code)
  self.assertIn('BCTextFormat.GRAY.apply(Style.EMPTY)',self.source('26.2','transport/item/ItemPipeHolder.java'))
  entity=self.source('26.2','builders/snapshot/SchematicEntityDefault.java')
  self.assertIn('new EntitySpawnRequest(EntitySpawnReason.LOAD, false)',entity)

 def test_262_silicon_debug_is_extraction_only(self):
  laser=self.source('26.2','silicon/client/render/AdvDebuggerLaser.java')
  self.assertIn('BCWorldGeometry.buffer(',laser)
  self.assertNotIn('renderBuffers()',laser)
  self.assertNotIn('import com.mojang.blaze3d.vertex.Tesselator;',laser)
  self.assertNotIn('import buildcraft.lib.compat.mc2612.blaze3d.vertex.BufferUploader;',laser)
  self.assertIn('SiliconDebugGeometry263.isCapturing()',laser)

 def test_262_all_reported_sources_java_syntax(self):
  result=parse_sources([self.trees['26.2']/ 'src/main/java/buildcraft'])
  self.assertIn('22 units, 0 errors',result)
  print(result,flush=True)

 def test_263_all_reported_sources_java_syntax(self):
  result=parse_sources([self.trees['26.3']/ 'src/main/java/buildcraft'])
  self.assertIn('22 units, 0 errors',result)
  print(result,flush=True)

if __name__=='__main__':unittest.main()
