#!/usr/bin/env python3
"""26.X silicon source selection, frozen downport views and native 26.3 recipe/menu/render paths."""
from __future__ import annotations

import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
sys.path[:0]=[str(ROOT/'scripts'),str(ROOT/'scripts/tests')]
from source_config import load_properties,target_layout
from source_layout import effective_source_files,_materialize_text_file,resolve_effective_source
from minecraft_compat_fixture import parse_sources
from silicon_26_fixture import execute

SILICON='src/main/java/buildcraft/silicon'

class Silicon26Tests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp=tempfile.TemporaryDirectory(prefix='bc-silicon-26-test-')
        cls.roots={}
        props=load_properties()
        for version in ('26.1.2','26.2','26.3'):
            target=version+'-neoforge'
            layout=target_layout(target,props)
            downports=tuple(root for root in (layout.family_downport_root,layout.family_platform_downport_root) if root)
            dest=Path(cls.temp.name)/version
            for logical,source in sorted(effective_source_files(layout,props,SILICON).items()):
                _materialize_text_file(source,dest/logical,logical_relative=logical,
                    minecraft=props[f'target.{target}.deps.minecraft'],family=layout.family,
                    platform=layout.platform,preprocess=True,
                    native_source=any(source.is_relative_to(root) for root in downports))
            cls.roots[version]=dest/SILICON

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def java(self,version,relative):
        return (self.roots[version]/relative).read_text(encoding='utf-8')

    def hashes(self,version):
        base=self.roots[version]
        return {(Path(SILICON)/path.relative_to(base)).as_posix():hashlib.sha256(path.read_bytes()).hexdigest()
                for path in base.rglob('*.java')}

    def test_full_silicon_2612_byte_parity(self):
        manifest=json.loads((ROOT/'build-config/materialized-baselines/26.1.2-neoforge.json').read_text())['files']
        expected={name:digest for name,digest in manifest.items() if name.startswith(SILICON+'/') and name.endswith('.java')}
        self.assertEqual(91,len(expected))
        self.assertEqual(expected,self.hashes('26.1.2'))

    def test_full_silicon_262_byte_parity(self):
        manifest=json.loads((ROOT/'build-config/materialized-baselines/26.2-silicon.json').read_text())
        self.assertEqual('26.2-neoforge',manifest['target'])
        self.assertEqual(92,manifest['file_count'])
        self.assertEqual(manifest['files'],self.hashes('26.2'))

    def test_native_263_file_selection(self):
        actual=self.hashes('26.3')
        self.assertEqual(93,len(actual))
        self.assertIn(SILICON+'/compat/SiliconDisplayContainer263.java',actual)
        self.assertIn(SILICON+'/client/render/SiliconDebugGeometry263.java',actual)
        for version in ('26.1.2','26.2'):
            self.assertNotIn(SILICON+'/compat/SiliconDisplayContainer263.java',self.hashes(version))
        self.assertNotIn(SILICON+'/client/render/SiliconDebugGeometry263.java',self.hashes('26.1.2'))
        self.assertIn(SILICON+'/client/render/SiliconDebugGeometry263.java',self.hashes('26.2'))

    def test_263_recipes_reloadable_bootstrap(self):
        bootstrap=self.java('26.3','BCSilicon.java')
        recipes=self.java('26.3','BCSiliconRecipesProvider.java')
        self.assertIn('modEventBus.addListener(this::gatherData)',bootstrap)
        self.assertIn('event.createReloadableRegistryObjects(',bootstrap)
        self.assertIn('RecipeProvider.asBootstrap(BCSiliconRecipesProvider::new)',bootstrap)
        self.assertIn('BootstrapContext<Recipe<?>> recipes',recipes)
        self.assertIn('protected void buildRecipes()',recipes)
        self.assertIn('ResourceKey.create(Registries.RECIPE, id)',recipes)
        self.assertNotIn('TriggerInstance.hasItems(',recipes)
        self.assertNotIn('.save(writer, Identifier.',recipes)
        self.assertIn('new GateLogicChangeRecipe(), null',recipes)
        for marker in ('assembly/plug_timer','assembly/light_sensor','assembly/plug_pulsar',
                       'assembly/gate/and_','assembly/gate/or_','assembly/lens/lens_filter',
                       'assembly/diamond_chipset','assembly/gate_copier'):
            self.assertIn(marker,recipes)

    def test_262_remains_on_original_recipe_pipeline(self):
        self.assertNotIn('RecipeProvider.asBootstrap',self.java('26.2','BCSilicon.java'))
        self.assertIn('buildRecipes(RecipeOutput writer)',self.java('26.2','BCSiliconRecipesProvider.java'))

    def test_preview_slots_use_vanilla_readonly_container(self):
        menus=('ContainerAdvancedCraftingTable','ContainerAssemblyTable',
               'ContainerIntegrationTable','ContainerProgrammingTable')
        for menu in menus:
            file=self.java('26.3','container/'+menu+'.java')
            self.assertIn('SiliconDisplayContainer263',file,menu)
            self.assertNotIn('new SlotDisplay(',file,menu)
            self.assertNotIn('net.neoforged.neoforge.items.IItemHandler',file,menu)
        adapter=self.java('26.3','compat/SiliconDisplayContainer263.java')
        self.assertIn('implements Container',adapter)
        self.assertIn('new Slot(this, index, x, y)',adapter)
        self.assertIn('mayPlace(ItemStack stack) { return false; }',adapter)
        self.assertIn('mayPickup(Player player) { return false; }',adapter)
        self.assertIn('item.copy()',adapter)

    def test_native_display_java_probe(self):
        result=execute(self.roots['26.3']/'compat/SiliconDisplayContainer263.java')
        self.assertIn('assertions PASS',result)
        print(result,flush=True)

    def test_debug_target_public_access_by_target(self):
        manifest=json.loads((ROOT/'build-config/materialized-baselines/26.1.2-neoforge.json').read_text())['files']
        relative='src/main/java/buildcraft/lib/debug/BCAdvDebugging.java'
        props=load_properties()
        for version in ('26.1.2','26.2','26.3'):
            with self.subTest(target=version):
                layout=target_layout(version+'-neoforge',props)
                source=resolve_effective_source(layout,props,relative)
                self.assertIsNotNone(source)
                downports=tuple(root for root in (layout.family_downport_root,layout.family_platform_downport_root) if root)
                with tempfile.TemporaryDirectory(prefix='bc-debug-target-') as td:
                    root=Path(td)
                    dest=root/relative
                    _materialize_text_file(source,dest,logical_relative=relative,
                        minecraft=props[f'target.{version}-neoforge.deps.minecraft'],
                        family=layout.family,platform=layout.platform,preprocess=True,
                        native_source=any(source.is_relative_to(d) for d in downports))
                    text=dest.read_text(encoding='utf-8')
                    if version=='26.1.2':
                        self.assertEqual(manifest[relative],hashlib.sha256(dest.read_bytes()).hexdigest())
                        self.assertNotIn('getClientDebugTarget(',text)
                        continue
                    self.assertIn('public static IAdvDebugTarget getClientDebugTarget()',text)
                    laser=self.java(version,'client/render/SiliconDebugGeometry263.java')
                    self.assertIn('BCAdvDebugging.getClientDebugTarget()',laser)
                    self.assertNotIn('BCAdvDebugging.INSTANCE.targetClient',laser)
                    interface=root/'buildcraft/lib/debug/IAdvDebugTarget.java'
                    interface.parent.mkdir(parents=True,exist_ok=True)
                    interface.write_text('package buildcraft.lib.debug; public interface IAdvDebugTarget { void disableDebugging(); boolean doesExistInWorld(); void sendDebugState(); }')
                    probe=root/'buildcraft/silicon/client/render/DebugAccessProbe.java'
                    probe.parent.mkdir(parents=True,exist_ok=True)
                    probe.write_text("""package buildcraft.silicon.client.render;
import buildcraft.lib.debug.BCAdvDebugging;
import buildcraft.lib.debug.IAdvDebugTarget;
public final class DebugAccessProbe {
    public static void main(String[] args) {
        IAdvDebugTarget target = new IAdvDebugTarget() {
            public void disableDebugging() {}
            public boolean doesExistInWorld() {return true;}
            public void sendDebugState() {}
        };
        BCAdvDebugging.setClientDebugTarget(target);
        if (BCAdvDebugging.getClientDebugTarget() != target) throw new AssertionError();
        BCAdvDebugging.setClientDebugTarget(null);
        if (BCAdvDebugging.getClientDebugTarget() != null) throw new AssertionError();
        System.out.println("debug target public accessor PASS");
    }
}
""")
                    classes=root/'classes'
                    compiled=subprocess.run(['javac','--release','21','-encoding','UTF-8','-d',str(classes),
                        str(dest),str(interface),str(probe)],capture_output=True,text=True,timeout=30)
                    self.assertEqual(0,compiled.returncode,compiled.stderr)
                    executed=subprocess.run(['java','-cp',str(classes),
                        'buildcraft.silicon.client.render.DebugAccessProbe'],capture_output=True,text=True,timeout=30)
                    self.assertEqual(0,executed.returncode,executed.stderr)
                    self.assertIn('debug target public accessor PASS',executed.stdout)

    def test_debugger_geometry_extraction_and_submission(self):
        laser=self.java('26.3','client/render/AdvDebuggerLaser.java')
        event=self.java('26.3','client/render/SiliconDebugGeometry263.java')
        self.assertIn('SiliconDebugGeometry263.isCapturing()',laser)
        self.assertIn('BCWorldGeometry.buffer(RenderCompat.solid())',laser)
        self.assertNotIn('renderBuffers().bufferSource()',laser)
        self.assertIn('BCWorldGeometry.capture(',event)
        self.assertIn('event.getRenderState().setRenderData(',event)
        self.assertIn('event.getLevelRenderState().getRenderData(',event)
        self.assertIn('BCWorldGeometry.submit(',event)
        self.assertIn('pose.translate(-camera.x, -camera.y, -camera.z)',event)
        self.assertEqual(laser,self.java('26.2','client/render/AdvDebuggerLaser.java'))
        self.assertEqual(event,self.java('26.2','client/render/SiliconDebugGeometry263.java'))

    def test_rest_of_silicon_gameplay_is_unmodified_from_262(self):
        diffs={key.removeprefix(SILICON+'/') for key in self.hashes('26.3')
               if self.hashes('26.2').get(key)!=self.hashes('26.3').get(key)}
        self.assertEqual({
            'BCSilicon.java','BCSiliconRecipesProvider.java',
            'compat/SiliconDisplayContainer263.java',
            'container/ContainerAdvancedCraftingTable.java',
            'container/ContainerAssemblyTable.java','container/ContainerIntegrationTable.java',
            'container/ContainerProgrammingTable.java'},diffs)
        for name in ('tile/TileAssemblyTable.java','tile/TileIntegrationTable.java',
                     'tile/TileLaser.java','tile/TileProgrammingTable_Neptune.java',
                     'gate/GateVariant.java','recipe/FacadeAssemblyRecipes.java'):
            self.assertEqual(self.java('26.2',name),self.java('26.3',name),name)

    def test_java_syntax_all_targets(self):
        result=parse_sources([self.roots[version] for version in ('26.1.2','26.2','26.3')])
        print(result,flush=True)
        self.assertIn('91 units, 0 errors',result)
        self.assertIn('93 units, 0 errors',result)

if __name__=='__main__':
    unittest.main()
