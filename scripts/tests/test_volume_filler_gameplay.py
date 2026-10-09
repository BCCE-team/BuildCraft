#!/usr/bin/env python3
"""Gameplay-cycle regression tests for BuildCraft Volume Box + Filler Planner.

Materializes changed boundaries on all seven targets. The executable Java probe
uses typed doubles; it does not replace full NeoForge/Forge compile or in-game tests.
"""
from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
sys.path[:0]=[str(ROOT/'scripts'),str(ROOT/'scripts/tests')]
from source_config import load_properties,target_layout
from source_layout import resolve_effective_source,_materialize_text_file
from minecraft_compat_fixture import parse_sources
from volume_box_lifecycle_fixture import execute
from volume_box_render_fixture import execute as check_quad_vertices

TARGETS=('1.19.2-forge','1.20.1-forge','1.21.1-neoforge','1.21.11-neoforge',
         '26.1.2-neoforge','26.2-neoforge','26.3-neoforge')
P='src/main/java/buildcraft/'
CLASSES={
 'core/BCCore.java','core/BCCoreItems.java','core/item/ItemVolumeBox.java',
 'core/item/ItemMarkerConnector.java','core/marker/volume/AddonQuadRenderer.java',
 'core/marker/volume/AddonDefaultRenderer.java',
 'core/marker/volume/BCCoreVolumeBoxEvents.java','core/marker/volume/VolumeBoxToolActions.java',
 'core/marker/volume/EnumAddonSlot.java','core/marker/volume/ItemAddon.java',
 'core/client/render/RenderVolumeBoxes.java',
 'builders/BCBuildersItems.java','builders/BCBuildersGuis.java','builders/BCBuildersClientGuis.java',
 'builders/item/ItemFillerPlanner.java','builders/addon/AddonFillerPlanner.java',
 'builders/addon/AddonRendererFillerPlanner.java', 'builders/menu/ContainerFillerPlanner.java',
 'builders/gui/GuiFillerPlanner.java','builders/platform/PlannerMenuOpening.java',
}
RECIPE_ID={'volume_box':'buildcraftcore:volume_box','filler_planner':'buildcraftbuilders:filler_planner'}

class VolumeFillerGameplayTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp=tempfile.TemporaryDirectory(prefix='volume-filler-effective-')
        cls.outputs={}
        props=load_properties()
        for target in TARGETS:
            layout=target_layout(target,props)
            downs=tuple(p for p in (layout.family_downport_root,layout.family_platform_downport_root) if p)
            out=Path(cls.tmp.name)/target
            for relative in sorted(CLASSES):
                logical=P+relative
                src=resolve_effective_source(layout,props,logical)
                if src is None:raise AssertionError(f'Missing {target}: {logical}')
                _materialize_text_file(src,out/logical,logical_relative=logical,
                    minecraft=props[f'target.{target}.deps.minecraft'],family=layout.family,
                    platform=layout.platform,preprocess=True,
                    native_source=any(src.is_relative_to(d) for d in downs))
            cls.outputs[target]=out

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def java(self,target,relative):
        return (self.outputs[target]/P/relative).read_text(encoding='utf-8')

    def test_java_syntax_all_target_boundaries(self):
        for target,out in self.outputs.items():
            with self.subTest(target=target):
                result=parse_sources([out/'src/main/java/buildcraft/core',out/'src/main/java/buildcraft/builders'])
                self.assertIn('0 errors',result)

    def test_editing_event_registers_each_target(self):
        for target in TARGETS:
            with self.subTest(target=target):
                source=self.java(target,'core/BCCore.java')
                self.assertIn('BCCoreVolumeBoxEvents.register()',source)
                event=self.java(target,'core/marker/volume/BCCoreVolumeBoxEvents.java')
                self.assertIn('WorldSavedDataVolumeBoxes.get(server)',event)
                self.assertIn('boxes.tick()',event)
                self.assertIn('MessageVolumeBoxes(boxes.volumeBoxes)',event)
                self.assertIn('ClientVolumeBoxes.INSTANCE.volumeBoxes.clear()',event)

    def test_block_vertex_lifecycle_java_stubs_old_and_new(self):
        for target in ('1.19.2-forge','1.21.1-neoforge','26.3-neoforge'):
            with self.subTest(target=target):
                file=self.outputs[target]/P/'core/marker/volume/AddonQuadRenderer.java'
                result=check_quad_vertices(file)
                self.assertIn('28 assertions PASS',result)
                print(target+': '+result,flush=True)

    def test_original_buildcraft_separates_volume_item_from_connector(self):
        for target in TARGETS:
            with self.subTest(target=target):
                item=self.java(target,'core/item/ItemVolumeBox.java')
                connector=self.java(target,'core/item/ItemMarkerConnector.java')
                tool=self.java(target,'core/marker/volume/VolumeBoxToolActions.java')
                self.assertIn('boxes.addVolumeBox(position)',item)
                self.assertIn('boxes.setDirty()',item)
                self.assertNotIn('VolumeBoxToolActions.use(',item)
                self.assertNotIn('InteractionResult use(Level',item)
                self.assertNotIn('InteractionResultHolder<ItemStack> use(Level',item)
                self.assertIn('VolumeBoxToolActions.use(world, player)',connector)
                self.assertIn('editing.cancelEditing()',tool)
                self.assertIn('editing.confirmEditing()',tool)
                self.assertLess(tool.index('if (editing != null)'),tool.index('getSelectingVolumeBoxAndSlot('))
                self.assertIn('selected.setHeldDistOldMinOldMax(',tool)
                self.assertIn('nearest.addons.values().forEach(Addon::onRemoved)',tool)
                self.assertIn('Lock.Target.TargetRemove',tool)

    def test_reported_cross_version_compile_boundaries(self):
        for target in TARGETS:
            with self.subTest(target=target):
                item=self.java(target,'builders/item/ItemFillerPlanner.java')
                volume=self.java(target,'core/item/ItemVolumeBox.java')
                base=self.java(target,'core/marker/volume/ItemAddon.java')
                menu=self.java(target,'builders/menu/ContainerFillerPlanner.java')
                renderer=self.java(target,'builders/addon/AddonRendererFillerPlanner.java')
                gui=self.java(target,'builders/gui/GuiFillerPlanner.java')
                self.assertIn('import net.minecraft.world.item.Item;',item)
                self.assertNotIn('VolumeBoxToolActions.use(',volume)
                self.assertIn('boxes.addVolumeBox(position)',volume)
                self.assertIn('!volumeBox.isEditing()',base)
                self.assertIn('applyingServerUpdate',menu)
                if target == '1.19.2-forge':
                    self.assertIn('return playerInventory.player.level;',menu)
                    self.assertIn('player.level.isEmptyBlock(p)',renderer)
                    self.assertNotIn('player.level().isEmptyBlock(p)',renderer)
                else:
                    self.assertIn('return playerInventory.player.level();',menu)
                    self.assertIn('player.level().isEmptyBlock(p)',renderer)
                if target.startswith('26.'):
                    self.assertIn('inventory, title, 176, 81);',gui)
                    self.assertNotIn('imageWidth =',gui)
                    self.assertNotIn('imageHeight =',gui)
                else:
                    self.assertIn('imageWidth = json.getSizeX()',gui)
                if target.startswith(('1.19.', '1.20.', '1.21.1-')):
                    self.assertIn('InteractionResultHolder<ItemStack> use(Level',base)
                else:
                    self.assertIn('InteractionResult use(Level',base)
                    self.assertNotIn('InteractionResultHolder',base)
                self.assertNotIn('InteractionResultHolder',volume)

    def test_large_template_allocation_is_guarded(self):
        addon=(ROOT/'source-shared/src/main/java/buildcraft/builders/addon/AddonFillerPlanner.java').read_text()
        self.assertIn('long voxels',addon)
        self.assertIn('voxels > 2_000_000L',addon)
        self.assertIn('buildingInfo = FillerUtil.createBuildingInfo(',addon)

    def test_java_volume_tool_lifecycle(self):
        result=execute(ROOT/'source-shared/src/main/java/buildcraft/core/marker/volume/VolumeBoxToolActions.java')
        self.assertIn('assertions PASS',result)
        print(result,flush=True)

    def test_addon_attaches_consumes_and_returns_item(self):
        for target in TARGETS:
            with self.subTest(target=target):
                item=self.java(target,'core/marker/volume/ItemAddon.java')
                addon=self.java(target,'builders/addon/AddonFillerPlanner.java')
                self.assertIn('!player.getAbilities().instabuild',item)
                self.assertIn('shrink(1)',item)
                self.assertIn('PlannerMenuOpening.open(',addon)
                self.assertIn('void onRemoved()',addon)
                self.assertIn('BCBuildersItems.FILLER_PLANNER.get()',addon)
                self.assertIn('volumeBox.world.addFreshEntity(dropped)',addon)

    def test_planner_server_authority_and_network_sync(self):
        for target in TARGETS:
            with self.subTest(target=target):
                menu=self.java(target,'builders/menu/ContainerFillerPlanner.java')
                self.assertIn('implements IContainerFilling',menu)
                self.assertIn('serverSnapshot',menu)
                self.assertIn('clientEditable',menu)
                self.assertIn('FullStatement<IFillerPattern> ignored',menu)
                self.assertIn('valuesChanged()',menu)
                self.assertIn('updateBuildingInfo()',menu)
                self.assertIn('isLocked()',menu)
                self.assertIn('WorldSavedDataVolumeBoxes.get(',menu)
                self.assertIn('distanceToSqr(',menu)
                self.assertIn('buf.readUUID()',menu)
                self.assertIn('buf.readEnum(EnumAddonSlot.class)',menu)

    def test_planner_gui_and_existing_filler_pattern_widgets(self):
        guiasset=ROOT/'source-shared/src/main/resources/assets/buildcraftbuilders/gui/filler_planner.json'
        parent=ROOT/'source-shared/src/main/resources/assets/buildcraftbuilders/gui/filler_base.json'
        gui=json.loads(guiasset.read_text());base=json.loads(parent.read_text())
        self.assertEqual('buildcraftbuilders:gui/filler_base',gui['parent'])
        for name in ('pattern_drag_target','pattern_drawable','params','pattern_possible','button_invert'):
            self.assertIn(name,base['elements'])
        for target in TARGETS:
            with self.subTest(target=target):
                self.assertIn('BCBuildersGuis.MENU_FILLER_PLANNER.get()',self.java(target,'builders/BCBuildersClientGuis.java'))
                self.assertIn('MENU_FILLER_PLANNER = MENUS.register(',self.java(target,'builders/BCBuildersGuis.java'))
                editor=self.java(target,'builders/gui/GuiFillerPlanner.java')
                self.assertIn('filler_planner.json',editor)
                self.assertIn('filler.invert',editor)
                self.assertIn('filler.pattern',editor)
                self.assertIn('container.sendInverted(',editor)

    def test_both_loaders_menu_open_correctly(self):
        for target in TARGETS:
            cls=self.java(target,'builders/platform/PlannerMenuOpening.java')
            with self.subTest(target=target):
                if target.endswith('-forge'):
                    self.assertIn('NetworkHooks.openScreen(',cls)
                    self.assertNotIn('net.neoforged',cls)
                else:
                    self.assertIn('player.openMenu(provider, buffer -> {',cls)
                    self.assertNotIn('net.minecraftforge',cls)
                self.assertIn('buffer.writeUUID(id)',cls)
                self.assertIn('buffer.writeEnum(slot)',cls)

    def test_crafting_resources_and_creative_discovery(self):
        for target in TARGETS:
            old=target.startswith(('1.19.2','1.20.1'))
            rel='recipes' if old else 'recipe'
            fam='old' if old else '1.21.X'
            core=ROOT/f'source-families/{fam}/src/main/resources/data/buildcraftcore/{rel}/volume_box.json'
            builders=ROOT/f'source-families/{fam}/src/main/resources/data/buildcraftbuilders/{rel}/filler_planner.json'
            for name,path in (('volume_box',core),('filler_planner',builders)):
                with self.subTest(target=target,recipe=name):
                    recipe=json.loads(path.read_text())
                    self.assertEqual('minecraft:crafting_shaped',recipe['type'])
                    self.assertEqual(RECIPE_ID[name],recipe['result']['item' if old else 'id'])
                    self.assertEqual(3,len(recipe['pattern']))
                    self.assertEqual(3,len(recipe['pattern'][0]))
            coreItems=self.java(target,'core/BCCoreItems.java')
            buildersItems=self.java(target,'builders/BCBuildersItems.java')
            if old and target.startswith('1.19.2'):
                self.assertIn('VOLUME_BOX = ITEMS.register("volume_box", () -> new ItemVolumeBox(new Item.Properties().tab(',coreItems)
                self.assertIn('FILLER_PLANNER = ITEMS.register("filler_planner", () -> new ItemFillerPlanner(new Item.Properties().tab(',buildersItems)
            else:
                self.assertIn('add(items, VOLUME_BOX)',coreItems)
                self.assertIn('add(items, FILLER_PLANNER)',buildersItems)

    def test_preview_is_bounded_and_translucent(self):
        for target in TARGETS:
            with self.subTest(target=target):
                renderer=self.java(target,'builders/addon/AddonRendererFillerPlanner.java')
                self.assertIn('32_768',renderer)
                self.assertIn('1_024',renderer)
                self.assertIn('getSnapshot().data.get(index)',renderer)
        for target in ('26.1.2-neoforge','26.2-neoforge','26.3-neoforge'):
            source=self.java(target,'core/client/render/RenderVolumeBoxes.java')
            self.assertIn('RenderCompat.translucent()',source)

    def test_block_format_renderers_complete_every_vertex(self):
        for target in TARGETS:
            with self.subTest(target=target):
                emitter=self.java(target,'core/marker/volume/AddonQuadRenderer.java')
                base=self.java(target,'core/marker/volume/AddonDefaultRenderer.java')
                ghost=self.java(target,'builders/addon/AddonRendererFillerPlanner.java')
                self.assertIn('OverlayTexture.NO_OVERLAY',emitter)
                self.assertIn('FULL_BRIGHT',emitter)
                self.assertEqual(6,emitter.count('quad(out, sprite,'))
                self.assertIn('AddonQuadRenderer.box(builder, addon.getBoundingBox(), s, 255)',base)
                self.assertIn('AddonQuadRenderer.box(vb, bb, s, 127)',ghost)
                if target.startswith(('1.19.', '1.20.')):
                    self.assertIn('.overlayCoords(OverlayTexture.NO_OVERLAY)',emitter)
                    self.assertIn('.normal(nx, ny, nz).endVertex()',emitter)
                else:
                    self.assertIn('.setOverlay(OverlayTexture.NO_OVERLAY)',emitter)
                    self.assertIn('.setNormal(nx, ny, nz)',emitter)

    def test_help_and_language(self):
        texts=json.loads((ROOT/'source-shared/src/main/resources/assets/buildcraft/guide/text/en_us.json').read_text())['pages']
        self.assertIn('Marker Connector',' '.join(texts['buildcraftcore/item/box_volume']))
        self.assertNotIn('with the Volume Box or Marker Connector',' '.join(texts['buildcraftcore/item/box_volume']))
        lang=json.loads((ROOT/'source-shared/src/main/resources/assets/buildcraft/lang/en_us.json').read_text())
        for name in ('volume_box','filler_planner'):
            for i in range(3):self.assertIn(f'buildcraft.tooltip.{name}.{i}',lang)


if __name__=='__main__':unittest.main()
