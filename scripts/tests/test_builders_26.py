#!/usr/bin/env python3
"""26.X Builders migration: immutable legacy views and native 26.3 container/fluids."""
from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT/'scripts'), str(ROOT/'scripts/tests')]
from source_config import load_properties, target_layout
from source_layout import effective_source_files, _materialize_text_file
from minecraft_compat_fixture import parse_sources
from builders_26_fixture import execute

BUILDERS = 'src/main/java/buildcraft/builders'


class Builders26Tests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory(prefix='bc-builders-26-')
        cls.outputs = {}
        props = load_properties()
        for version in ('26.1.2', '26.2', '26.3'):
            name = version + '-neoforge'
            layout = target_layout(name, props)
            downports = tuple(d for d in (
                layout.family_downport_root, layout.family_platform_downport_root) if d)
            out = Path(cls.tmp.name)/version
            for logical, source in sorted(effective_source_files(layout, props, BUILDERS).items()):
                _materialize_text_file(source, out/logical, logical_relative=logical,
                    minecraft=props[f'target.{name}.deps.minecraft'],
                    family=layout.family, platform=layout.platform, preprocess=True,
                    native_source=any(source.is_relative_to(d) for d in downports))
            cls.outputs[version] = out/BUILDERS

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def text(self, version, name):
        return (self.outputs[version]/name).read_text(encoding='utf-8')

    def files(self, version):
        root = self.outputs[version]
        return {(Path(BUILDERS) / p.relative_to(root)).as_posix() for p in root.rglob("*.java")}

    def test_2612_builders_sources_materialize(self):
        root = self.outputs["26.1.2"]
        for relative in ("BCBuilders.java", "client/render/RenderQuarry.java"):
            with self.subTest(source=relative):
                self.assertTrue((root / relative).is_file())

    def test_262_builders_sources_materialize(self):
        root = self.outputs["26.2"]
        for relative in ("BCBuilders.java", "client/render/RenderQuarry.java"):
            with self.subTest(source=relative):
                self.assertTrue((root / relative).is_file())

    def test_26_3_port_selects_native_implementations(self):
        files=set(self.files('26.3'))
        self.assertTrue(files)
        for name in (
            'compat/BuildersItemContainer263.java',
            'compat/BuildersDisplayContainer263.java',
            'compat/BuilderFluidContainers263.java'):
            self.assertIn(BUILDERS+'/'+name,files)
        for old_version in ('26.1.2','26.2'):
            for name in ('compat/BuildersItemContainer263.java','compat/BuildersDisplayContainer263.java','compat/BuilderFluidContainers263.java'):
                self.assertFalse((self.outputs[old_version]/name).exists())

    def test_replacer_inventory_is_live_and_uses_vanilla_slots(self):
        menu=self.text('26.3','gui/MenuReplacer.java')
        slot=self.text('26.3','compat/BuildersItemContainer263.java')
        self.assertIn('new BuildersItemContainer263(snapshot, from, to)',menu)
        self.assertIn('new Slot(handler, index, x, y)',menu)
        self.assertIn('machineInventory, 0',menu)
        self.assertIn('machineInventory, 1',menu)
        self.assertIn('machineInventory, 2',menu)
        self.assertNotIn('SlotItemHandler',menu)
        self.assertNotIn('IItemHandler',menu)
        self.assertIn('handlers[index].setStackInSlot(0, candidate.copy())',slot)
        self.assertIn('handlers[index].canSet(0, candidate)',slot)
        self.assertIn('ItemStack.matches(visible[index], lastKnown[index])',slot)
        self.assertIn('return 1;',slot)

    def test_builder_required_material_slots_are_read_only(self):
        menu=self.text('26.3','menu/ContainerBuilder.java')
        display=self.text('26.3','compat/BuildersDisplayContainer263.java')
        self.assertIn('new buildcraft.builders.compat.BuildersDisplayContainer263(invRequire)',menu)
        self.assertIn('public boolean mayPlace(ItemStack stack) { return false; }',menu)
        self.assertIn('public boolean mayPickup(net.minecraft.world.entity.player.Player player) { return false; }',menu)
        self.assertNotIn('SlotDisplay',menu)
        self.assertIn('provider.getStackInSlot(index).copy()',display)
        self.assertIn('public ItemStack removeItem(int index, int amount) { return ItemStack.EMPTY; }',display)

    def test_single_schematic_uses_native_transactional_item_fluid_capability(self):
        item=self.text('26.3','item/ItemSchematicSingle.java')
        bridge=self.text('26.3','compat/BuilderFluidContainers263.java')
        self.assertIn('BuilderFluidContainers263.drainContained(placementItems)',item)
        self.assertIn('requiredFluids.addAll(schematicBlock.computeRequiredFluids(world))',item)
        self.assertIn('ItemAccess.forHandlerIndexStrict(inventory, 0).oneByOne()',bridge)
        self.assertIn('Capabilities.Fluid.ITEM',bridge)
        self.assertIn('Transaction.openRoot()',bridge)
        self.assertIn('transaction.commit()',bridge)
        self.assertNotIn('net.neoforged.neoforge.fluids.FluidUtil;',item)
        self.assertNotIn('IFluidHandler',item)

    def test_quarry_blueprint_and_robot_material_logic_unmodified(self):
        for relative in (
            'tile/TileQuarry.java','tile/TileBuilder.java','tile/TileFiller.java',
            'snapshot/BlueprintBuilder.java','snapshot/SnapshotBuilder.java',
            'snapshot/TemplateBuilder.java','snapshot/ItemStackRef.java',
            'snapshot/Blueprint.java', 'snapshot/Template.java'):
            self.assertEqual(self.text('26.2',relative),self.text('26.3',relative),relative)

    def test_26_2_and_26_3_render_submission(self):
        for version in ('26.2','26.3'):
            for r in ('RenderBuilder','RenderFiller','RenderConstructionMarker'):
                render=self.text(version,'client/render/'+r+'.java')
                self.assertIn('LegacyBlockEntityRenderer.super.submit(',render)
                self.assertIn('RenderSnapshotBuilder.submit(',render)
            self.assertIn('ItemStackRenderState',self.text(version,'client/render/RenderSnapshotBuilder.java'))
            self.assertIn('SubmitNodeCollector',self.text(version,'client/render/RenderSnapshotBuilder.java'))

    def test_native_java_bridges_compile_and_behave(self):
        root=self.outputs['26.3']/'compat'
        result=execute([root/name for name in (
            'BuildersItemContainer263.java', 'BuildersDisplayContainer263.java', 'BuilderFluidContainers263.java')])
        print(result,flush=True)
        self.assertIn('46 assertions PASS',result)

    def test_java_syntax_all_versions(self):
        result=parse_sources([self.outputs[version] for version in ('26.2','26.3')])
        print(result,flush=True)
        self.assertIn('157 units, 0 errors',result)
        self.assertIn('160 units, 0 errors',result)


if __name__ == '__main__':
    unittest.main()
