#!/usr/bin/env python3
"""Redstone module: actual source/overlay regression checks for non-Fabric targets."""
import json
import unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]

class RedstoneModuleTests(unittest.TestCase):
    def text(self,relative):return (ROOT/relative).read_text(encoding='utf-8')
    def test_recipe(self):
        for generation,folder in [('old','recipes'),('1.21.X','recipe')]:
            payload=json.loads(self.text(f'source-families/{generation}/src/main/resources/data/buildcraftcore/{folder}/redstone_control_module.json'))
            self.assertEqual(payload['pattern'],['IPI','PRP','IPI'])
            self.assertEqual(payload['key']['I']['item'],'minecraft:iron_nugget')
            self.assertEqual(payload['key']['P']['item'],'minecraft:paper')
            self.assertEqual(payload['key']['R']['item'],'minecraft:redstone')
    def test_registered_all_versions(self):
        for path in ['version-src/1.19.2-forge/src/main/java/buildcraft/core/BCCoreItems.java',
                     'version-src/1.20.1-forge/src/main/java/buildcraft/core/BCCoreItems.java',
                     'source-families/1.21.X/src/main/java/buildcraft/core/BCCoreItems.java']:
            self.assertIn('REDSTONE_CONTROL_MODULE = ITEMS.register("redstone_control_module"', self.text(path))
    def test_persistent_module_and_loot(self):
        for path in ['source-platforms/forge/src/main/java/buildcraft/lib/tile/TileBC_Neptune.java',
                     'source-family-platforms/1.21.X/neoforge/src/main/java/buildcraft/lib/tile/TileBC_Neptune.java',
                     'source-families/26.X/src/main/java/buildcraft/lib/tile/TileBC_Neptune.java']:
            code=self.text(path)
            for piece in ('redstoneControlModuleInstalled','hasNeighborSignal(worldPosition)',
                          'REDSTONE_CONTROL_MODULE.get()', 'supportsRedstoneControlModule()'):
                self.assertIn(piece,code,path)
    def test_legacy_forge_registry_api(self):
        # Both old targets share this implementation. 1.19.2 predates BuiltInRegistries.
        path = 'source-platforms/forge/src/main/java/buildcraft/lib/tile/TileBC_Neptune.java'
        code = self.text(path)
        self.assertIn('ForgeRegistries.BLOCKS', code)
        self.assertNotIn('net.minecraft.core.registries.BuiltInRegistries.BLOCK', code)
        import sys
        sys.path.insert(0, str(ROOT / 'scripts'))
        from source_config import load_properties, target_layout
        from source_layout import resolve_effective_source
        props = load_properties()
        for target in ('1.19.2-forge', '1.20.1-forge'):
            effective = resolve_effective_source(
                target_layout(target, props), props,
                'src/main/java/buildcraft/lib/tile/TileBC_Neptune.java'
            )
            self.assertIsNotNone(effective, target)
            source = effective.read_text(encoding='utf-8')
            self.assertIn('ForgeRegistries.BLOCKS', source, target)
            self.assertNotIn('net.minecraft.core.registries.BuiltInRegistries', source, target)

    def test_click_interception(self):
        for loader in ['forge','neoforge']:
            c=self.text(f'source-platforms/{loader}/src/main/java/buildcraft/core/RedstoneModuleInteractions.java')
            for piece in ('event.setCanceled(true)', 'installRedstoneControlModule()',
                          'removeRedstoneControlModule()', 'WrenchUtil.isWrench(held)',
                          'event.getEntity().getInventory().add(returned)'):
                self.assertIn(piece,c)
    def test_effective_source_ownership_seven_targets(self):
        import sys
        sys.path.insert(0, str(ROOT / 'scripts'))
        from source_config import load_properties, target_layout
        from source_layout import resolve_effective_source
        props = load_properties()
        targets = ('1.19.2-forge', '1.20.1-forge', '1.21.1-neoforge', '1.21.11-neoforge',
                   '26.1.2-neoforge', '26.2-neoforge', '26.3-neoforge')
        sources = ('lib/tile/TileBC_Neptune.java', 'core/BCCore.java', 'core/BCCoreItems.java',
                   'core/RedstoneModuleInteractions.java')
        for target in targets:
            layout = target_layout(target, props)
            for name in sources:
                path = resolve_effective_source(layout, props, 'src/main/java/buildcraft/' + name)
                self.assertIsNotNone(path, (target, name))
                code = path.read_text(encoding='utf-8')
                key = 'redstoneControlModuleInstalled' if name.startswith('lib/') else (
                    'REDSTONE_CONTROL_MODULE' if name.endswith('Items.java') else 'RedstoneModuleInteractions')
                if name.endswith('Interactions.java'):
                    key = 'installRedstoneControlModule()'
                self.assertIn(key, code, (target, name, path))

    def test_item_model_and_texture_path(self):
        import struct
        model = json.loads(self.text('source-shared/src/main/resources/assets/buildcraftcore/models/item/redstone_control_module.json'))
        # 1.21.1 still uses legacy item models; a shared items/ JSON would leak
        # into that target. 1.21.11+ must generate the same vanilla definition.
        from tempfile import TemporaryDirectory
        import sys
        sys.path.insert(0, str(ROOT / 'scripts'))
        from transforms.resources import generate_modern_item_definitions
        self.assertFalse((ROOT / 'source-shared/src/main/resources/assets/buildcraftcore/items/redstone_control_module.json').exists())
        for minecraft in ('1.21.1', '1.21.11', '26.3'):
            with self.subTest(minecraft=minecraft), TemporaryDirectory(prefix='bcce-rcm-item-') as temp:
                generated_root = Path(temp)
                model_path = generated_root / 'src/main/resources/assets/buildcraftcore/models/item/redstone_control_module.json'
                model_path.parent.mkdir(parents=True, exist_ok=True)
                model_path.write_text(json.dumps(model), encoding='utf-8')
                output = generated_root / 'src/main/resources/assets/buildcraftcore/items/redstone_control_module.json'
                count = generate_modern_item_definitions(generated_root, minecraft=minecraft)
                if minecraft == '1.21.1':
                    self.assertEqual(0, count)
                    self.assertFalse(output.exists(), 'Legacy item-model target must not contain modern items/ definitions')
                else:
                    self.assertEqual(1, count)
                    item = json.loads(output.read_text(encoding='utf-8'))
                    self.assertEqual(item['model'], {
                        'type': 'minecraft:model',
                        'model': 'buildcraftcore:item/redstone_control_module',
                    })
                    self.assertEqual(0, generate_modern_item_definitions(generated_root, minecraft=minecraft))
        # Preserve the module artwork from the previous visual-fix patch; do not
        # silently switch the model to a differently named contributor sprite.
        self.assertEqual(model['textures']['layer0'], 'buildcraftcore:item/redstone_control_module')
        art = ROOT / 'source-shared/src/main/resources/assets/buildcraftcore/textures/item/redstone_control_module.png'
        content = art.read_bytes()
        self.assertEqual(content[:8], b"\x89PNG\r\n\x1a\n")
        self.assertEqual(struct.unpack('>II', content[16:24]), (16, 16))

    def test_visual_models_and_multipart_coverage(self):
        import struct
        assets = ROOT / 'source-shared/src/main/resources/assets'
        groups = [
            ('buildcraftbuilders', 'filler', 'block/filler/main_redstone',
             ['blocks/filler/front_redstone', 'blocks/filler/side_redstone']),
            ('buildcraftbuilders', 'quarry', 'block/quarry_redstone',
             ['blocks/quarry/front_redstone']),
            ('buildcraftfactory', 'mining_well', 'block/mining_well_redstone',
             ['blocks/mining_well/front_redstone', 'blocks/mining_well/top_redstone']),
            ('buildcraftfactory', 'pump', 'block/pump_redstone',
             ['blocks/pump/side_redstone']),
        ]
        # Builder has two independent family blockstates, with overlay slots remaining unchanged.
        for family in ('old', '1.21.X'):
            state = json.loads(self.text(f'source-families/{family}/src/main/resources/assets/buildcraftbuilders/blockstates/builder.json'))
            for facing in ('north', 'east', 'south', 'west'):
                self.assertTrue(any(p.get('when',{}) == {'facing': facing, 'redstone_module':'true'}
                                    and p.get('apply',{}).get('model') == 'buildcraftbuilders:block/builder/main_redstone'
                                    for p in state['multipart']))
                self.assertTrue(any(p.get('when',{}) == {'facing': facing, 'redstone_module':'false'}
                                    for p in state['multipart']))
        groups.insert(0, ('buildcraftbuilders', 'builder', 'block/builder/main_redstone',
                          ['blocks/builder/front_redstone']))
        for namespace, block, modelname, expected_textures in groups:
            if block != 'builder':
                state = json.loads((assets/namespace/'blockstates'/f'{block}.json').read_text(encoding='utf-8'))
                if block == 'pump':
                    self.assertEqual(set(state['variants']), {
                        f'waterlogged={w},redstone_module={m}' for w in ('true','false') for m in ('true','false')})
                else:
                    for facing in ('north','south','east','west'):
                        for val in ('true','false'):
                            self.assertTrue(any(x.get('when',{}) == {'facing':facing,'redstone_module':val}
                                                for x in state['multipart']), (block,facing,val))
            model = json.loads((assets/namespace/'models'/f'{modelname}.json').read_text(encoding='utf-8'))
            self.assertTrue({namespace + ':' + t for t in expected_textures}.issubset(set(model['textures'].values())), block)
            for texture in expected_textures:
                path = assets/namespace/'textures'/f'{texture}.png'
                content = path.read_bytes()
                self.assertEqual(content[:8], b"\x89PNG\r\n\x1a\n", path)
                self.assertEqual(struct.unpack('>II', content[16:24]), (16,16), path)

    def test_blockstate_switch_persist_and_restore_all_seven(self):
        import sys
        sys.path.insert(0, str(ROOT / 'scripts'))
        from source_config import load_properties, target_layout
        from source_layout import resolve_effective_source
        props = load_properties()
        targets = ('1.19.2-forge','1.20.1-forge','1.21.1-neoforge','1.21.11-neoforge',
                   '26.1.2-neoforge','26.2-neoforge','26.3-neoforge')
        blocks = ('builders/block/BlockBuilder.java','builders/block/BlockFiller.java',
                  'builders/block/BlockQuarry.java','factory/block/BlockMiningWell.java',
                  'factory/block/BlockPump.java')
        for target in targets:
            layout = target_layout(target, props)
            for block in blocks:
                path = resolve_effective_source(layout, props, 'src/main/java/buildcraft/'+block)
                self.assertIsNotNone(path, (target, block))
                self.assertIn('RedstoneModuleState.INSTALLED', path.read_text(encoding='utf-8'),(target,block))
            path = resolve_effective_source(layout,props,'src/main/java/buildcraft/lib/tile/TileBC_Neptune.java')
            self.assertIsNotNone(path,target)
            code = path.read_text(encoding='utf-8')
            self.assertIn('syncRedstoneControlModuleBlockState();',code,target)
            self.assertIn('state.setValue(RedstoneModuleState.INSTALLED, redstoneControlModuleInstalled)',code,target)
            self.assertIn('level.setBlock(worldPosition',code,target)

    def test_atlas_contains_all_module_texture_sprites(self):
        # The models may resolve to a valid PNG and STILL show missing texture if
        # modern MC's explicit block atlas does not register that sprite.
        wanted = {
            'buildcraftbuilders:blocks/builder/front_redstone',
            'buildcraftbuilders:blocks/filler/front_redstone',
            'buildcraftbuilders:blocks/filler/side_redstone',
            'buildcraftbuilders:blocks/quarry/front_redstone',
            'buildcraftfactory:blocks/mining_well/front_redstone',
            'buildcraftfactory:blocks/mining_well/top_redstone',
            'buildcraftfactory:blocks/pump/side_redstone',
        }
        sources = (
            'source-families/1.21.X/src/main/resources/assets/minecraft/atlases/blocks.json',
            'source-downports/1.21.X/1.21.1/neoforge/src/main/resources/assets/minecraft/atlases/blocks.json',
        )
        for path in sources:
            with self.subTest(atlas=path):
                atlas = json.loads(self.text(path))
                resources = [r.get('resource') for r in atlas['sources'] if isinstance(r,dict)]
                for sprite in wanted:
                    self.assertEqual(resources.count(sprite), 1, (path,sprite))

    def test_redstone_models_refer_to_registered_sprites(self):
        from pathlib import PurePosixPath
        assets = ROOT / 'source-shared/src/main/resources/assets'
        atlas = json.loads(self.text(
            'source-families/1.21.X/src/main/resources/assets/minecraft/atlases/blocks.json'))
        listed = {r.get('resource') for r in atlas['sources'] if isinstance(r,dict)}
        models = [
            ('buildcraftbuilders','models/block/builder/main_redstone.json'),
            ('buildcraftbuilders','models/block/filler/main_redstone.json'),
            ('buildcraftbuilders','models/block/quarry_redstone.json'),
            ('buildcraftfactory','models/block/mining_well_redstone.json'),
            ('buildcraftfactory','models/block/pump_redstone.json'),
        ]
        for namespace,relative in models:
            data=json.loads((assets/namespace/relative).read_text(encoding='utf-8'))
            for sprite in data.get('textures',{}).values():
                if not sprite.endswith('_redstone'): continue
                self.assertIn(sprite, listed, (relative,sprite))
                prefix,resource=sprite.split(':',1)
                self.assertTrue(all(part not in ('..','') for part in PurePosixPath(resource).parts))
                # The original artwork lives in the contributor's checkout; intentionally
                # do not generate or overwrite PNG files in the patch.
                png=assets/prefix/'textures'/f'{resource}.png'
                if png.is_file():
                    self.assertEqual(png.read_bytes()[:8],b'\x89PNG\r\n\x1a\n',png)

    def test_atlas_ownership_covers_every_non_fabric_target(self):
        import sys
        sys.path.insert(0,str(ROOT/'scripts'))
        from source_config import load_properties,target_layout
        from source_layout import resolve_effective_source
        props=load_properties()
        for target in ('1.19.2-forge','1.20.1-forge','1.21.1-neoforge','1.21.11-neoforge',
                       '26.1.2-neoforge','26.2-neoforge','26.3-neoforge'):
            with self.subTest(target=target):
                layout=target_layout(target,props)
                atlas=resolve_effective_source(layout,props,'src/main/resources/assets/minecraft/atlases/blocks.json')
                if target == '1.19.2-forge':
                    # Old Minecraft scans model textures without a JSON atlas.
                    self.assertIsNone(atlas)
                    continue
                if target == '1.20.1-forge':
                    # The old build generates the 1.20.1 atlas from 1.21.X canonical.
                    self.assertIsNone(atlas)
                    continue
                self.assertIsNotNone(atlas,target)
                data=json.loads(atlas.read_text(encoding='utf-8'))
                entries={r.get('resource') for r in data['sources'] if isinstance(r,dict)}
                self.assertIn('buildcraftbuilders:blocks/builder/front_redstone', entries,target)
                self.assertNotIn('buildcraftbuilders:addons/redstone_card', entries, target)

    def test_run_gating(self):
        files=[
            *[f'version-src/{v}-forge/src/main/java/buildcraft/builders/tile/{tile}.java'
              for v in ('1.19.2','1.20.1') for tile in ('TileBuilder','TileQuarry')],
            *[f'source-platforms/neoforge/src/main/java/buildcraft/builders/tile/{tile}.java'
              for tile in ('TileBuilder','TileQuarry')],
            *[f'source-platforms/{v}/src/main/java/buildcraft/builders/tile/TileFiller.java'
              for v in ('forge','neoforge')],
            *[f'source-platforms/{v}/src/main/java/buildcraft/factory/tile/{tile}.java'
              for v in ('forge','neoforge') for tile in ('TileMiner','TilePump')]
        ]
        for path in files:
            self.assertIn('canWorkWithRedstoneControl()',self.text(path),path)

if __name__=='__main__':unittest.main()
