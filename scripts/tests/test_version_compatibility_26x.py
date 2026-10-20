#!/usr/bin/env python3
"""Minecraft 26.x API compatibility and native migration contracts."""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

import re
import tempfile
import unittest
import json
import shutil
import subprocess
from source_config import load_properties, target_layout
from source_layout import resolve_effective_source, _materialize_text_file, target_layout, load_properties
from minecraft_compat_fixture import parse_sources
from transforms.neoforge263 import relocate_removed_neoforge_apis, upgrade_minecraft_263_apis

# Compiler-sensitive effective sources covered by compatibility checks.
PLATFORM_262_API_SOURCES=(
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

class PlatformCompilerConformance262(unittest.TestCase):
 @classmethod
 def setUpClass(cls):
  cls.temporary=tempfile.TemporaryDirectory(prefix='bc-262-compiler-')
  cls.trees={}
  props=load_properties()
  for ver in ('26.1.2','26.2','26.3'):
   layout=target_layout(ver+'-neoforge',props)
   downs=tuple(root for root in (layout.family_downport_root,layout.family_platform_downport_root) if root)
   root=Path(cls.temporary.name)/ver
   for name in PLATFORM_262_API_SOURCES:
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

 def test_2612_sensitive_sources_materialize(self):
     self.assertEqual(22, len(PLATFORM_262_API_SOURCES))
     for name in PLATFORM_262_API_SOURCES:
         relative = "src/main/java/buildcraft/" + name
         with self.subTest(source=name):
             self.assertTrue((self.trees["26.1.2"] / relative).is_file())

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

 def test_262_api_sources_java_syntax(self):
  result=parse_sources([self.trees['26.2']/ 'src/main/java/buildcraft'])
  self.assertIn('22 units, 0 errors',result)
  print(result,flush=True)

 def test_263_api_sources_java_syntax(self):
  result=parse_sources([self.trees['26.3']/ 'src/main/java/buildcraft'])
  self.assertIn('22 units, 0 errors',result)
  print(result,flush=True)


PLATFORM_263_REMOVED = (
    'net.neoforged.neoforge.items.IItemHandler',
    'net.neoforged.neoforge.fluids.capability.IFluidHandler',
    'net.neoforged.neoforge.energy.IEnergyStorage',
    'net.neoforged.neoforge.fluids.FluidUtil',
    'net.neoforged.neoforge.fluids.IFluidTank',
    'net.neoforged.neoforge.items.wrapper.InvWrapper',
)

class NativeApiConformance263(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        import tempfile
        cls.tmp = tempfile.TemporaryDirectory(prefix='bcce-263-api-')
        cls.props = load_properties()
        cls.sources = {}
        selected = (
            'lib/compat/transfer/TransferInterop.java',
            'lib/platform/storage/PlatformStorage.java',
            'lib/misc/CapUtil.java',
            'lib/fluid/Tank.java',
            'energy/BCEnergyWorldGen.java',
            'energy/generation/features/OilGenFeature.java',
            'energy/generation/features/OilFeatureConfiguration.java',
            'builders/snapshot/FakeWorld.java',
        )
        for ver in ('26.2','26.3'):
            layout=target_layout(ver+'-neoforge',cls.props)
            downs=tuple(r for r in (layout.family_downport_root,layout.family_platform_downport_root) if r)
            for rel in selected:
                logical='src/main/java/buildcraft/'+rel
                source=resolve_effective_source(layout,cls.props,logical)
                assert source is not None,logical
                output=Path(cls.tmp.name)/ver/logical
                _materialize_text_file(source,output,logical_relative=logical,
                  minecraft=ver,family=layout.family,platform=layout.platform,
                  preprocess=True,native_source=any(source.is_relative_to(d) for d in downs))
                cls.sources[ver,rel] = output.read_text(encoding='utf-8')

    @classmethod
    def tearDownClass(cls): cls.tmp.cleanup()

    def test_api_relocations_only_on_263(self):
        sample='\n'.join('import '+s+';' for s in PLATFORM_263_REMOVED)
        self.assertEqual(sample, relocate_removed_neoforge_apis(sample,minecraft='26.2',relative='Any.java'))
        new=relocate_removed_neoforge_apis(sample,minecraft='26.3',relative='Any.java')
        for old in PLATFORM_263_REMOVED: self.assertNotIn(old,new)
        self.assertIn('buildcraft.lib.compat.neoforge263.',new)

    def test_262_previous_api_preserved(self):
        for rel in ('lib/compat/transfer/TransferInterop.java','lib/misc/CapUtil.java'):
            self.assertIn('net.neoforged.neoforge.items.IItemHandler;',self.sources['26.2',rel])
            self.assertNotIn('buildcraft.lib.compat.neoforge263.',self.sources['26.2',rel])

    def test_263_transfer_bridge_not_legacy_loader_api(self):
        for rel in ('lib/compat/transfer/TransferInterop.java','lib/misc/CapUtil.java',
                    'lib/platform/storage/PlatformStorage.java','lib/fluid/Tank.java'):
            src=self.sources['26.3',rel]
            for old in PLATFORM_263_REMOVED: self.assertNotIn(old,src,rel)
        self.assertIn('TransferInterop.importEnergy(handler)',
                      self.sources['26.3','lib/platform/storage/PlatformStorage.java'])
        self.assertIn('TransferInterop.importFluids(modern)',
                      self.sources['26.3','lib/misc/CapUtil.java'])

    def test_263_only_java_contracts(self):
        base=ROOT/'source-families/26.X/src/main/java/buildcraft/lib/compat/neoforge263'
        classes=('items/IItemHandler.java','items/IItemHandlerModifiable.java',
           'items/SlotItemHandler.java','items/ItemHandlerCopySlot.java',
           'items/wrapper/CombinedInvWrapper.java','items/wrapper/InvWrapper.java',
           'fluids/IFluidHandler.java','fluids/IFluidHandlerItem.java',
           'fluids/IFluidTank.java','fluids/FluidUtil.java','energy/IEnergyStorage.java')
        for cls in classes:
            path=base/cls
            self.assertTrue(path.is_file(),cls)
            self.assertTrue(path.read_text().startswith('//? source if >=26.3'),cls)

    def test_263_worldgen_uses_feature_type_and_map_codec(self):
        wg=self.sources['26.3','energy/BCEnergyWorldGen.java']
        self.assertIn('"minecraft:worldgen/feature_type"',wg)
        self.assertIn('BCDeferredRegister<MapCodec<? extends Feature>>',wg)
        feature=self.sources['26.3','energy/generation/features/OilGenFeature.java']
        self.assertIn('implements Feature',feature)
        self.assertIn('MapCodec<OilGenFeature>',feature)
        self.assertNotIn('FeaturePlaceContext',feature)
        config=self.sources['26.3','energy/generation/features/OilFeatureConfiguration.java']
        self.assertIn('MapCodec<OilFeatureConfiguration> CODEC = RecordCodecBuilder.mapCodec',config)
        self.assertNotIn('implements FeatureConfiguration',config)
        old=self.sources['26.2','energy/generation/features/OilGenFeature.java']
        self.assertIn('extends Feature<OilFeatureConfiguration>',old)

    def test_263_fake_world_removed_262_hooks(self):
        old=self.sources['26.2','builders/snapshot/FakeWorld.java']
        new=self.sources['26.3','builders/snapshot/FakeWorld.java']
        self.assertIn('FuelValues fuelValues()',old)
        self.assertNotIn('FuelValues fuelValues()',new)
        self.assertNotIn('PotionBrewing',new)

    def test_263_oil_placement_inline(self):
        src=ROOT/'resource-src/26.X/26.3/data/buildcraftenergy/worldgen/placed_feature/oil_placed_feature.json'
        value=json.loads(src.read_text(encoding='utf-8'))
        self.assertEqual('buildcraftenergy:worldgen.feature.oil',value['feature']['type'])
        self.assertIn('oilStructureSetting',value['feature'])
        self.assertEqual('buildcraftenergy:oil', value['feature']['oilStructureSetting']['genOilState'])
        self.assertNotIsInstance(value['feature']['oilStructureSetting']['genOilState'], dict)
        self.assertNotIn('config',value['feature'])
        self.assertIn('placement',value)

    def test_263_neoforge_dependency_range_tracks_pinned_version(self):
        dep = self.props['target.26.3-neoforge.deps.neoforge']
        version_range = self.props['target.26.3-neoforge.neoforge.version_range']
        self.assertTrue(dep.startswith('26.3.'), dep)
        self.assertEqual(f'[{dep},)', version_range)
        self.assertNotEqual(dep, self.props['target.26.2-neoforge.deps.neoforge'])



# ---- 26.3 inventory adapter Java contract ----




PLATFORM_263_ITEM_STORAGE_263_ROOT=Path(__file__).resolve().parents[2]
PLATFORM_263_ITEM_STORAGE_263_CLASSES=PLATFORM_263_ITEM_STORAGE_263_ROOT/'source-families/26.X/src/main/java/buildcraft/lib/compat/neoforge263/items'

PLATFORM_263_ITEM_STORAGE_263_ITEM_STACK=r'''package net.minecraft.world.item;
public final class ItemStack {
    public static final ItemStack EMPTY = new ItemStack("", 0);
    private String id; private int count;
    public ItemStack(String id,int count){this.id=id;this.count=count;}
    public boolean isEmpty(){return count<=0 || id.isEmpty();}
    public int getCount(){return count;}
    public int getMaxStackSize(){return 64;}
    public void grow(int count){this.count += count;}
    public ItemStack copy(){return new ItemStack(id,count);}
    public ItemStack copyWithCount(int c){return new ItemStack(id,c);}
    public static boolean isSameItemSameComponents(ItemStack a,ItemStack b){return a.id.equals(b.id);}
    public String toString(){return id+"="+count;}
}
'''
PLATFORM_263_ITEM_STORAGE_263_CONTAINER=r'''package net.minecraft.world;
import net.minecraft.world.item.ItemStack;
public interface Container {
    int getContainerSize(); ItemStack getItem(int slot);
    ItemStack removeItem(int slot, int count);
    void setItem(int slot, ItemStack stack); void setChanged();
    default int getMaxStackSize(ItemStack stack){return stack.getMaxStackSize();}
    default boolean canPlaceItem(int slot,ItemStack stack){return true;}
}
'''
PLATFORM_263_ITEM_STORAGE_263_HARNESS=r'''import buildcraft.lib.compat.neoforge263.items.wrapper.InvWrapper;
import buildcraft.lib.compat.neoforge263.items.wrapper.CombinedInvWrapper;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
public class Validate263ItemStorage {
  static int checked=0;
  static void is(boolean b){checked++;if(!b) throw new AssertionError("check "+checked);}
  static ItemStack s(String name,int count){return new ItemStack(name,count);}
  static class Inventory implements Container {
    final ItemStack[] slots={ItemStack.EMPTY,ItemStack.EMPTY}; boolean forbid=false;int changes=0;
    public int getContainerSize(){return slots.length;}
    public ItemStack getItem(int n){return slots[n];}
    public ItemStack removeItem(int n,int count){int taken=Math.min(slots[n].getCount(),count);
      ItemStack result=slots[n].copyWithCount(taken);slots[n]=slots[n].copyWithCount(slots[n].getCount()-taken);return result;}
    public void setItem(int n,ItemStack value){slots[n]=value;changes++;}
    public void setChanged(){changes++;}
    public boolean canPlaceItem(int n,ItemStack stack){return !forbid;}
  }
  public static void main(String[] a){
    Inventory first=new Inventory(); InvWrapper h=new InvWrapper(first);
    is(h.getSlots()==2);is(h.getStackInSlot(0).isEmpty());
    is(h.insertItem(0,s("iron",22),true).isEmpty()); is(h.getStackInSlot(0).isEmpty());
    is(h.insertItem(0,s("iron",22),false).isEmpty());is(h.getStackInSlot(0).getCount()==22);
    is(h.extractItem(0,5,true).getCount()==5);is(h.getStackInSlot(0).getCount()==22);
    is(h.extractItem(0,5,false).getCount()==5);is(h.getStackInSlot(0).getCount()==17);
    is(h.insertItem(0,s("gold",1),false).getCount()==1); is(h.getStackInSlot(0).getCount()==17);
    is(h.insertItem(0,s("iron",50),false).getCount()==3);is(h.getStackInSlot(0).getCount()==64);
    is(h.getSlotLimit(0)==64);is(h.extractItem(0,99,false).getCount()==64);
    is(h.getStackInSlot(0).isEmpty());
    first.forbid=true;is(!h.isItemValid(0,s("gold",1)));is(h.insertItem(0,s("gold",1),false).getCount()==1);
    first.forbid=false;
    h.setStackInSlot(0,s("copper",8));is(h.getStackInSlot(0).getCount()==8);
    Inventory second=new Inventory(); InvWrapper right=new InvWrapper(second);
    CombinedInvWrapper both=new CombinedInvWrapper(h,right);
    is(both.getSlots()==4);is(both.getStackInSlot(0).getCount()==8);
    both.setStackInSlot(2,s("steel",4));is(right.getStackInSlot(0).getCount()==4);
    is(both.getStackInSlot(2).getCount()==4);
    is(both.extractItem(2,2,false).getCount()==2);is(right.getStackInSlot(0).getCount()==2);
    is(both.insertItem(3,s("copper",3),false).isEmpty());
    is(second.getItem(1).getCount()==3);is(first.changes>0 && second.changes>0);
    System.out.println("26.3 inventory bridge: "+checked+" assertions PASS");
  }
}
'''

class JavaItemBridge263(unittest.TestCase):
    def test_inventory_wrapper_real_java(self):
        if not shutil.which('javac') or not shutil.which('java'): self.skipTest('JDK missing')
        with tempfile.TemporaryDirectory(prefix='bcce-263-items-') as temp:
            root=Path(temp)
            stubfiles={
                'net/minecraft/world/item/ItemStack.java':PLATFORM_263_ITEM_STORAGE_263_ITEM_STACK,
                'net/minecraft/world/Container.java':PLATFORM_263_ITEM_STORAGE_263_CONTAINER,
                'Validate263ItemStorage.java':PLATFORM_263_ITEM_STORAGE_263_HARNESS,
            }
            for path,content in stubfiles.items():
                dst=root/path;dst.parent.mkdir(parents=True,exist_ok=True);dst.write_text(content)
            own=[PLATFORM_263_ITEM_STORAGE_263_CLASSES/'IItemHandler.java',PLATFORM_263_ITEM_STORAGE_263_CLASSES/'IItemHandlerModifiable.java',
                PLATFORM_263_ITEM_STORAGE_263_CLASSES/'wrapper/InvWrapper.java',PLATFORM_263_ITEM_STORAGE_263_CLASSES/'wrapper/CombinedInvWrapper.java']
            cmd=['javac','-d',str(root/'classes')]+[str(x) for x in own]+[str(root/x) for x in stubfiles]
            build=subprocess.run(cmd,capture_output=True,text=True,timeout=25)
            self.assertEqual(0,build.returncode,build.stderr)
            run=subprocess.run(['java','-cp',str(root/'classes'),'Validate263ItemStorage'],capture_output=True,text=True,timeout=15)
            self.assertEqual(0,run.returncode,run.stderr)
            self.assertIn('assertions PASS',run.stdout)
            print(run.stdout.strip(),flush=True)


NATIVE_263_API_SOURCES = (
    'src/main/java/buildcraft/builders/BCBuilders.java',
    'src/main/java/buildcraft/builders/BCBuildersSchematics.java',
    'src/main/java/buildcraft/builders/item/ItemSchematicSingle.java',
    'src/main/java/buildcraft/builders/menu/ContainerBuilder.java',
    'src/main/java/buildcraft/builders/snapshot/SchematicBlockDefault.java',
    'src/main/java/buildcraft/builders/snapshot/SchematicBlockFluid.java',
    'src/main/java/buildcraft/core/BCCore.java',
    'src/main/java/buildcraft/core/client/render/RenderEngine_BC8.java',
    'src/main/java/buildcraft/core/item/ItemMapLocation.java',
    'src/main/java/buildcraft/core/item/ItemMarkerConnector.java',
    'src/main/java/buildcraft/core/item/ItemWrench.java',
    'src/main/java/buildcraft/energy/BCEnergy.java',
    'src/main/java/buildcraft/energy/BCEnergyBiomeModifiers.java',
    'src/main/java/buildcraft/energy/BCEnergyFluids.java',
    'src/main/java/buildcraft/energy/generation/features/OilStructure.java',
    'src/main/java/buildcraft/factory/BCFactory.java',
    'src/main/java/buildcraft/factory/client/render/RenderHeatExchange.java',
    'src/main/java/buildcraft/factory/container/ContainerAutoCraftItems.java',
    'src/main/java/buildcraft/lib/client/guide/GuiGuide.java',
    'src/main/java/buildcraft/lib/fluid/BCFluid.java',
    'src/main/java/buildcraft/lib/gui/BuildCraftGui.java',
    'src/main/java/buildcraft/lib/gui/component/AbstractComponent.java',
    'src/main/java/buildcraft/lib/gui/component/TankComponent.java',
    'src/main/java/buildcraft/lib/misc/InventoryUtil.java',
    'src/main/java/buildcraft/robotics/boards/BoardRobotFarmer.java',
    'src/main/java/buildcraft/robotics/boards/BoardRobotLumberjack.java',
    'src/main/java/buildcraft/robotics/boards/BoardRobotShovelman.java',
    'src/main/java/buildcraft/robotics/client/render/RenderRobot.java',
    'src/main/java/buildcraft/robotics/entity/EntityRobot.java',
    'src/main/java/buildcraft/robotics/gui/GuiZonePlanner.java',
    'src/main/java/buildcraft/robotics/plug/RobotStationPluggable.java',
    'src/main/java/buildcraft/robotics/tile/TileZonePlanner.java',
    'src/main/java/buildcraft/silicon/BCSilicon.java',
    'src/main/java/buildcraft/silicon/client/model/plug/PlugBakerFacade.java',
    'src/main/java/buildcraft/transport/BCTransport.java',
    'src/main/java/buildcraft/transport/block/BlockPipeHolder.java',
    'src/main/java/buildcraft/transport/client/render/RenderPipeHolder.java',
    'src/main/java/buildcraft/transport/pipe/behaviour/PipeBehaviourObsidian.java',
    'src/main/java/buildcraft/transport/pipe/behaviour/PipeBehaviourStripes.java',
    'src/main/java/buildcraft/transport/pipe/behaviour/PipeBehaviourWood.java',
    'src/main/java/buildcraft/transport/pipe/flow/PipeFlowPower.java',
    'src/main/java/buildcraft/transport/stripes/StripesHandlerHoe.java',
)

NATIVE_263_EXTRA_FILES = (
    "src/main/java/buildcraft/lib/internal/mj/IMjReceiver.java",
    "src/main/java/buildcraft/lib/internal/mj/MjBattery.java",
)

NATIVE_263_BLOCKED = (
    "getGuiLeft()", "getGuiTop()", "getXSize()", "getYSize()", "Type.COMMON",
    ".blocksMotion()", "PushReaction.DESTROY", "instanceof HoeItem",
    "instanceof AxeItem", "instanceof ShovelItem", "GLFW.GLFW_KEY",
    "import org.lwjgl.glfw.GLFW;", "material.shade()", ".mulPose(Axis.",
    "chunkPos.x,", "BlockPos::new", "public void playerDestroy(Level world",
    "spawnDestroyParticles(world, player", "Util.getPlatform().openUri",
    "player.swingingArm =",
)

class MinecraftApiConformance263(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory(prefix="bcce-native-api-")
        props = load_properties()
        cls.sources = {}
        for version in ("26.3", "26.2"):
            layout = target_layout(version + "-neoforge", props)
            for path in (*NATIVE_263_API_SOURCES, *NATIVE_263_EXTRA_FILES) if version == "26.3" else (
                "src/main/java/buildcraft/lib/gui/BuildCraftGui.java",
                "src/main/java/buildcraft/energy/BCEnergyBiomeModifiers.java",
                "src/main/java/buildcraft/lib/internal/mj/IMjReceiver.java",
                "src/main/java/buildcraft/robotics/boards/BoardRobotFarmer.java",
            ):
                original = resolve_effective_source(layout, props, path)
                if original is None:
                    raise AssertionError(f"Missing {version} selected source: {path}")
                dst = Path(cls.tmp.name) / version / path
                _materialize_text_file(original, dst, logical_relative=path,
                    minecraft=version, family=layout.family, platform=layout.platform,
                    preprocess=True)
                cls.sources[version, path] = dst.read_text(encoding="utf-8")

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def get_source(self, path):
        return self.sources["26.3", "src/main/java/buildcraft/" + path]

    def test_effective_source_selection_covers_native_api_boundaries(self):
        self.assertEqual(42, len(NATIVE_263_API_SOURCES))
        self.assertEqual(len(NATIVE_263_API_SOURCES) + len(NATIVE_263_EXTRA_FILES), len([k for k in self.sources if k[0] == "26.3"]))

    def test_263_native_api_sources_parse_syntax(self):
        result = parse_sources((Path(self.tmp.name) / "26.3",))
        self.assertIn("0 errors", result)

    def test_removed_263_symbols_not_in_effective_java(self):
        for (v, rel), src in self.sources.items():
            if v != "26.3": continue
            with self.subTest(path=rel):
                for token in NATIVE_263_BLOCKED:
                    self.assertNotIn(token, src, token)

    def test_262_transformation_is_identity(self):
        sample = ("Type.COMMON\ngetGuiLeft()\nPushReaction.DESTROY\n"
                  "foo.mulPose(Axis.XP.rotationDegrees(90));")
        self.assertEqual(sample, upgrade_minecraft_263_apis(
            sample, minecraft="26.2", relative="src/main/java/buildcraft/Test.java"))
        self.assertIn("gui::getXSize", self.sources["26.2", "src/main/java/buildcraft/lib/gui/BuildCraftGui.java"])

    def test_required_263_migrations_are_present(self):
        checks = (
            ("lib/gui/BuildCraftGui.java", "gui::getImageWidth"),
            ("energy/BCEnergyBiomeModifiers.java", "modify(RegistryAccess registries,"),
            ("energy/BCEnergyFluids.java", "PushReaction.POPPED"),
            ("robotics/gui/GuiZonePlanner.java", "InputConstants.KEY_RETURN"),
            ("robotics/tile/TileZonePlanner.java", "chunkPos.x()"),
            ("transport/client/render/RenderPipeHolder.java", "setUv3(float u, float v)"),
            ("builders/snapshot/SchematicBlockFluid.java", "pos.getZ()"),
            ("core/item/ItemWrench.java", "SwingAnimation.DEFAULT"),
            ("transport/pipe/flow/PipeFlowPower.java", "FluidAction.valueOf(action.name())"),
            ("lib/internal/mj/IMjReceiver.java", "Enum<?> action"),
            ("lib/internal/mj/MjBattery.java", "Enum<?> action"),
            ("robotics/boards/BoardRobotFarmer.java", "ItemTags.HOES"),
            ("factory/container/ContainerAutoCraftItems.java", "new ItemHandlerSimple(1), access"),
            ("builders/menu/ContainerBuilder.java", "new ItemProvider(slot ->"),
        )
        for rel, token in checks:
            with self.subTest(rel=rel):
                self.assertIn(token, self.get_source(rel))

        # FACT-03: the client's result preview must be a mutable, synchronized slot.
        # ItemProvider is display-only and cannot accept server-to-client slot updates.
        preview_menu = self.get_source("factory/container/ContainerAutoCraftItems.java")
        self.assertIn("IItemHandler resultClient", preview_menu)
        self.assertIn("new SlotDisplay(resultClient, 0, 93, 27)", preview_menu)
        self.assertNotIn("new ItemProvider(slot ->", preview_menu)

    def test_remaining_native_api_contracts(self):
        pipe = self.get_source("transport/block/BlockPipeHolder.java")
        self.assertIn(
            "public void playerDestroy(ServerLevel world, net.minecraft.server.level.ServerPlayer player,",
            pipe)
        self.assertNotIn("public void playerDestroy(ServerLevel world, Player player,", pipe)

        zone = self.get_source("robotics/gui/GuiZonePlanner.java")
        self.assertIn("InputConstants.KEY_MINUS", zone)
        self.assertIn("org.lwjgl.sdl.SDLScancode.SDL_SCANCODE_KP_MINUS", zone)
        self.assertNotIn("InputConstants.KEY_SUBTRACT", zone)
        self.assertNotIn("GLFW.GLFW_KEY_KP_SUBTRACT", zone)

        renderer = self.get_source("transport/client/render/RenderPipeHolder.java")
        self.assertEqual(1, renderer.count("public VertexConsumer setUv3(float u, float v)"))
        self.assertIn("@Override\n        public VertexConsumer setUv3(float u, float v)", renderer)
        self.assertNotIn("//? if >=26.3", renderer)

    def test_263_discarding_consumer_conforms_to_vertexconsumer_java(self):
        if not shutil.which("javac") or not shutil.which("java"):
            self.skipTest("JDK not available")
        src = self.get_source("transport/client/render/RenderPipeHolder.java")
        begin = src.index("private static final VertexConsumer DISCARDING_VERTEX_CONSUMER")
        end = src.index("public static final Direction[] renderFacing", begin)
        body = src[begin:end]
        contract = """interface VertexConsumer {
            VertexConsumer addVertex(float x,float y,float z);
            VertexConsumer setColor(int r,int g,int b,int a);
            VertexConsumer setUv(float u,float v);
            VertexConsumer setUv1(int u,int v);
            VertexConsumer setUv2(int u,int v);
            VertexConsumer setUv3(float u,float v);
            VertexConsumer setNormal(float x,float y,float z);
            VertexConsumer setLineWidth(float w);
        }
        public class CheckVertex263 {
        """ + body + """
            public static void main(String[] args) {
                var c = DISCARDING_VERTEX_CONSUMER;
                if (c.setUv3(.25f, .75f) != c || c.setUv2(4, 12) != c)
                    throw new AssertionError("Discarding consumer must be chainable");
                System.out.println("VERTEX CONTRACT PASS");
            }
        }
        """
        with tempfile.TemporaryDirectory(prefix="bcce-263-vertex-final-") as tmp:
            path = Path(tmp) / "CheckVertex263.java"
            path.write_text(contract, encoding="utf-8")
            build = subprocess.run(["javac", str(path)], capture_output=True,
                text=True, timeout=30)
            self.assertEqual(0, build.returncode, build.stderr)
            run = subprocess.run(["java", "-cp", tmp, "CheckVertex263"],
                capture_output=True, text=True, timeout=20)
            self.assertEqual(0, run.returncode, run.stderr)
            self.assertIn("VERTEX CONTRACT PASS", run.stdout)

    def test_263_player_destroy_overrides_server_player_signature(self):
        if not shutil.which("javac"):
            self.skipTest("JDK not available")
        source = self.get_source("transport/block/BlockPipeHolder.java")
        start = source.index("public void playerDestroy(")
        end = source.index("\n\t}", start) + len("\n\t}")
        method = source[start:end]
        fixture = """class Level {}
        class ServerLevel extends Level {}
        class Player {}
        class ServerPlayer extends Player {}
        class BlockPos {}
        class BlockState {}
        class BlockEntity {}
        class ItemStack {}
        class Block {
          public void playerDestroy(ServerLevel world, ServerPlayer player,
              BlockPos pos, BlockState state, BlockEntity be, ItemStack stack) {}
        }
        public class CheckDestroy263 extends Block {
        """ + "\n    @Override\n    " + method.replace(
            "net.minecraft.server.level.ServerPlayer", "ServerPlayer") + "\n}\n"
        with tempfile.TemporaryDirectory(prefix="bcce-263-player-final-") as tmp:
            path = Path(tmp) / "CheckDestroy263.java"
            path.write_text(fixture, encoding="utf-8")
            build = subprocess.run(["javac", str(path)], capture_output=True,
                text=True, timeout=30)
            self.assertEqual(0, build.returncode, build.stderr)

    def test_262_renderer_and_player_signatures_unchanged(self):
        props = load_properties()
        layout = target_layout("26.2-neoforge", props)
        for file in ("transport/client/render/RenderPipeHolder.java",
                     "transport/block/BlockPipeHolder.java",
                     "robotics/gui/GuiZonePlanner.java"):
            path = "src/main/java/buildcraft/" + file
            original = resolve_effective_source(layout, props, path)
            self.assertIsNotNone(original)
            with tempfile.TemporaryDirectory(prefix="bcce-262-no-regression-") as tmp:
                output = Path(tmp) / "Output.java"
                _materialize_text_file(original, output, logical_relative=path,
                    minecraft="26.2", family=layout.family, platform=layout.platform,
                    preprocess=True)
                result = output.read_text(encoding="utf-8")
                if file.endswith("RenderPipeHolder.java"):
                    self.assertNotIn("public VertexConsumer setUv3(float u, float v)", result)
                elif file.endswith("BlockPipeHolder.java"):
                    self.assertIn("public void playerDestroy(Level world, Player player,", result)
                else:
                    self.assertIn("GLFW.GLFW_KEY_KP_SUBTRACT", result)

    def test_vertex_fallback_only_inserts_once(self):
        sample = """class Test {
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }
        }"""
        path = "src/main/java/buildcraft/transport/client/render/RenderPipeHolder.java"
        output = upgrade_minecraft_263_apis(sample, minecraft="26.3", relative=path)
        self.assertEqual(1, output.count("setUv3(float u, float v)"))
        output = upgrade_minecraft_263_apis(output, minecraft="26.3", relative=path)
        self.assertEqual(1, output.count("setUv3(float u, float v)"))

    def test_power_adapter_java_execution(self):
        if not shutil.which("javac") or not shutil.which("java"):
            self.skipTest("JDK not available")
        with tempfile.TemporaryDirectory(prefix="bcce-263-power-") as tmp:
            root = Path(tmp)
            fixture = {
                "buildcraft/lib/internal/mj/IMjReceiver.java": self.get_source("lib/internal/mj/IMjReceiver.java"),
                "buildcraft/lib/internal/mj/IMjConnector.java": "package buildcraft.lib.internal.mj; public interface IMjConnector {}",
                "buildcraft/lib/compat/neoforge263/fluids/IFluidHandler.java":
                    "package buildcraft.lib.compat.neoforge263.fluids; public interface IFluidHandler { enum FluidAction { EXECUTE, SIMULATE; } }",
                "buildcraft/transport/internal/pipe/FluidAction.java":
                    "package buildcraft.transport.internal.pipe; public enum FluidAction { EXECUTE, SIMULATE; }",
                "VerifyPower263.java": NATIVE_263_POWER_HARNESS,
            }
            for name, value in fixture.items():
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(value, encoding="utf-8")
            build = subprocess.run(["javac", "-d", str(root / "classes"),
                *[str(root / name) for name in fixture]], capture_output=True, text=True, timeout=30)
            self.assertEqual(0, build.returncode, build.stderr)
            run = subprocess.run(["java", "-cp", str(root / "classes"), "VerifyPower263"],
                capture_output=True, text=True, timeout=20)
            self.assertEqual(0, run.returncode, run.stderr)
            self.assertIn("POWER BRIDGE PASS", run.stdout)

NATIVE_263_POWER_HARNESS = """
import buildcraft.lib.internal.mj.IMjReceiver;
import buildcraft.lib.compat.neoforge263.fluids.IFluidHandler.FluidAction;
class Legacy implements IMjReceiver {
  long executed = 0; long simulated = 0;
  public long getPowerRequested() { return 1000; }
  public long receivePower(long n, FluidAction mode) {
    if (mode == FluidAction.EXECUTE) executed += n;
    else simulated += n;
    return 0;
  }
}
class Transport implements IMjReceiver {
  long executed = 0; long simulated = 0;
  public long getPowerRequested() { return 1000; }
  public long receivePower(long n, buildcraft.transport.internal.pipe.FluidAction mode) {
    if (mode == buildcraft.transport.internal.pipe.FluidAction.EXECUTE) executed += n;
    else simulated += n;
    return 0;
  }
  public long receivePower(long n, FluidAction mode) {
    return receivePower(n, buildcraft.transport.internal.pipe.FluidAction.valueOf(mode.name()));
  }
}
public class VerifyPower263 {
  public static void main(String[] args) {
    Legacy a = new Legacy(); Transport b = new Transport();
    IMjReceiver ra = a, rb = b;
    ra.receivePower(14, buildcraft.transport.internal.pipe.FluidAction.SIMULATE);
    ra.receivePower(12, buildcraft.transport.internal.pipe.FluidAction.EXECUTE);
    rb.receivePower(7, FluidAction.EXECUTE);
    rb.receivePower(11, buildcraft.transport.internal.pipe.FluidAction.SIMULATE);
    if (a.simulated != 14 || a.executed != 12 || b.executed != 7 || b.simulated != 11)
      throw new AssertionError("Power-action crossover broken");
    System.out.println("POWER BRIDGE PASS");
  }
}
"""

if __name__ == "__main__":
    unittest.main()
