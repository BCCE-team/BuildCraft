#!/usr/bin/env python3
"""26.X factory materialization and 26.3 machine API contracts."""
from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
sys.path.insert(0, str(ROOT / 'scripts/tests'))
from source_config import load_properties, target_layout
from source_layout import effective_source_files, _materialize_text_file
from minecraft_compat_fixture import parse_sources
from factory_26_fixture import execute

FACTORY = 'src/main/java/buildcraft/factory'


class Factory26EffectiveContracts(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='bc-factory-26-')
        cls.outputs = {}
        props = load_properties()
        for version in ('26.1.2', '26.2', '26.3'):
            name = version+'-neoforge'
            layout = target_layout(name, props)
            downs = tuple(p for p in (layout.family_downport_root, layout.family_platform_downport_root) if p)
            out = Path(cls.temp.name)/version
            for logical, src in sorted(effective_source_files(layout, props, FACTORY).items()):
                _materialize_text_file(src, out/logical, logical_relative=logical,
                    minecraft=props[f'target.{name}.deps.minecraft'],family=layout.family,
                    platform=layout.platform,preprocess=True,
                    native_source=any(src.is_relative_to(p) for p in downs))
            cls.outputs[version] = out

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def text(self, version, relative):
        return (self.outputs[version]/FACTORY/relative).read_text(encoding='utf-8')


    def test_2612_factory_sources_materialize(self):
        root = self.outputs["26.1.2"] / FACTORY
        for relative in ("BCFactory.java", "tile/TileFloodGate.java"):
            with self.subTest(source=relative):
                self.assertTrue((root / relative).is_file())

    def test_262_factory_sources_materialize(self):
        root = self.outputs["26.2"] / FACTORY
        for relative in ("BCFactory.java", "tile/TileFloodGate.java"):
            with self.subTest(source=relative):
                self.assertTrue((root / relative).is_file())

    def test_native_recipe_bootstrap(self):
        main=self.text('26.3','BCFactory.java')
        rec=self.text('26.3','BCFactoryRecipesProvider.java')
        self.assertIn('modEventBus.addListener(this::gatherData)',main)
        self.assertIn('GatherDataEvent.Server event',main)
        self.assertIn('event.createReloadableRegistryObjects(',main)
        self.assertIn('RecipeProvider.asBootstrap(BCFactoryRecipesProvider::new)',main)
        self.assertIn('BootstrapContext<Recipe<?>> recipes',rec)
        self.assertIn('BootstrapContext<Advancement> advancements',rec)
        self.assertIn('protected void buildRecipes()',rec)
        self.assertEqual(8,rec.count('shaped(RecipeCategory.MISC'))
        self.assertIn('ResourceKey.create(Registries.RECIPE',rec)
        self.assertIn('autowork_bench_1',rec)
        self.assertIn('autowork_bench_2',rec)
        self.assertNotIn('ShapedRecipeBuilder.shaped(',rec)

    def test_flood_gate_uses_native_transfer_for_bucket_placement(self):
        src=self.text('26.3','tile/TileFloodGate.java')
        self.assertIn('net.neoforged.neoforge.transfer.fluid.FluidUtil;',src)
        self.assertIn('new FactoryTankResourceHandler263(tank)',src)
        self.assertIn('true, null',src)
        self.assertNotIn('net.neoforged.neoforge.fluids.FluidUtil;',src)
        self.assertNotIn('tank.drain(FluidType.BUCKET_VOLUME, FluidAction.SIMULATE)',src)
        self.assertIn('AutomationPermissionUtil.mayBlock(',src)

    def test_native_tank_adapter_rollback(self):
        src=self.outputs['26.3']/FACTORY/'compat/FactoryTankResourceHandler263.java'
        result = execute(src)
        print(result, flush=True)
        self.assertIn('43 assertions PASS', result)

    def test_heat_exchange_gui_does_not_allocate_dummy_item_handler(self):
        m=self.text('26.3','client/gui/MenuHeatExchange.java')
        t=self.text('26.3','tile/TileHeatExchange.java')
        self.assertNotIn('IItemHandler item',m)
        self.assertNotIn('new ItemStackHandler',m)
        self.assertNotIn('new ItemStackHandler',t)
        self.assertIn('new MenuHeatExchange(id, inventory, tankData, stateData,',t)

    def test_workbench_slot_change_and_recipe_selection(self):
        w=self.text('26.3','tile/TileAutoWorkbenchBase.java')
        c=self.text('26.3','container/ContainerAutoCraftItems.java')
        self.assertIn('StackChangeCallback updateCrafting',w)
        self.assertIn('crafting.onInventoryChange(handler)',w)
        self.assertIn('invMaterials.setCallback(updateCrafting)',w)
        self.assertIn('ItemProvider resultClient',c)
        self.assertNotIn('import net.neoforged.neoforge.items.IItemHandler;',c)
        self.assertIn('BUTTON_NEXT_RECIPE',c)

    def test_machine_renderers_remain_available(self):
        for version in ("26.2", "26.3"):
            for renderer in ("RenderDistiller", "RenderHeatExchange", "RenderMiningWell", "RenderPump", "RenderTank"):
                with self.subTest(version=version, renderer=renderer):
                    source = self.text(version, "client/render/" + renderer + ".java")
                    self.assertIn("class " + renderer, source)

    def test_26_3_java_syntax(self):
        result=parse_sources((self.outputs['26.2']/FACTORY,self.outputs['26.3']/FACTORY))
        self.assertIn('0 errors',result)
        print(result,flush=True)


if __name__=='__main__':
    unittest.main()
