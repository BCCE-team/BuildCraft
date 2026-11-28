#!/usr/bin/env python3
"""Effective source/resource parity checks, not a game client or Gradle compile."""
import json
from pathlib import Path
import sys
import unittest
import re

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from source_config import load_properties, target_ids, target_layout
from source_layout import resolve_effective_source


class FabricGameplayVisualParityTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.props = load_properties()
        cls.fabric = [t for t in target_ids(cls.props) if t.endswith('-fabric')]
        cls.layouts = {t: target_layout(t, cls.props) for t in target_ids(cls.props)}

    def path(self, target, rel):
        p = resolve_effective_source(self.layouts[target], self.props, rel)
        self.assertIsNotNone(p, (target, rel))
        return p

    def java(self, target, rel):
        return self.path(target, 'src/main/java/buildcraft/' + rel).read_text(encoding='utf-8')

    def asset(self, target, rel):
        return json.loads(self.path(target, 'src/main/resources/assets/' + rel).read_text(encoding='utf-8'))

    def test_all_target_display_names(self):
        self.assertEqual(len(self.fabric), 4)
        for target in self.layouts:
            with self.subTest(target=target):
                if target in self.fabric:
                    p = self.path(target, 'src/main/resources/fabric.mod.json')
                    self.assertEqual(json.loads(p.read_text())['name'], 'BuildCraft Community Edition')
                else:
                    path = 'META-INF/neoforge.mods.toml' if target.endswith('-neoforge') else 'META-INF/mods.toml'
                    p = self.path(target, 'src/main/resources/' + path)
                    names = re.findall(r'displayName\s*=\s*"([^"]+)"', p.read_text())
                    self.assertTrue(names)
                    for name in names:
                        self.assertNotRegex(name, r'(?i)\b(?:fabric|neoforge|forge)\b|\d+\.\d+')

    def test_tank_cutout_in_every_fabric_target(self):
        for target in self.fabric:
            with self.subTest(target=target):
                self.assertIn('FabricBlockTransparency.register();', self.java(target, 'fabric/BuildCraftFabric263Client.java'))
                code = self.java(target, 'fabric/FabricBlockTransparency.java')
                self.assertIn('BCFactoryBlocks.TANK_BLOCK.get()', code)
                self.assertIn('ChunkSectionLayer.CUTOUT', code)
                if not target.startswith('1.21.11'):
                    self.assertIn('quad.chunkLayer(ChunkSectionLayer.CUTOUT)', code)
                    self.assertIn('emitter.popTransform();', code)

    def test_gas_bucket_rotation_matches_fluid_material(self):
        material = (ROOT / 'source-shared/src/main/java/buildcraft/energy/fluid/BoatFluidMaterial.java').read_text()
        tiers = {f: int(t) for f, t in re.findall(r'"([a-z_]+)"\s*,\s*new int\[\]\s*\{\d+,\s*\d+,\s*(\d+)\}', material)}
        self.assertEqual(len(tiers), 10)
        self.assertEqual(sum(3 - v for v in tiers.values()), 10)
        for target in self.fabric:
            parent = self.asset(target, 'buildcraftenergy/models/item/fluid_overlays/gas_bucket.json')
            self.assertEqual(parent['display']['gui']['rotation'], [0, 0, 180])
            self.assertEqual(len(parent['display']), 8)
            for family, first in tiers.items():
                for i, tier in enumerate(('cool', 'hot', 'searing')):
                    with self.subTest(target=target, family=family, tier=tier):
                        item = self.asset(target, f'buildcraftenergy/items/{family}/{tier}_bucket.json')['model']
                        if i >= first:
                            self.assertEqual(item['type'], 'minecraft:model')
                            model = self.asset(target, f'buildcraftenergy/models/item/fluid_overlays/{family}_{tier}_bucket.json')
                            self.assertEqual(model['parent'], 'buildcraftenergy:item/fluid_overlays/gas_bucket')
                            self.assertEqual(model['textures']['layer0'], 'minecraft:item/bucket')
                            self.assertEqual(model['textures']['layer1'], f'buildcraftenergy:item/fluid_overlays/{family}_{tier}')
                        else:
                            self.assertEqual(item['type'], 'minecraft:composite')

    def test_part_clicks_are_routed_separately_from_pipe_mining(self):
        for target in self.fabric:
            with self.subTest(target=target):
                client = self.java(target, 'fabric/PipePartClientInteraction.java')
                self.assertIn('ClientPreAttackCallback.EVENT.register', client)
                self.assertIn('client.hitResult instanceof BlockHitResult', client)
                self.assertIn('if (interceptedAttack) return true;', client)
                self.assertIn('client.gameMode.stopDestroyBlock()', client)
                self.assertIn('hit.getLocation(), part, false', client)
                mixin = self.java(target, 'fabric/mixin/PipePartPickMixin.java')
                self.assertIn('handlePickItemFromBlock', mixin)
                self.assertIn('ci.cancel()', mixin)
                cfg = json.loads(self.path(target, 'src/main/resources/buildcraft.fabric263.mixins.json').read_text())
                self.assertIn('PipePartPickMixin', cfg['client'])
                self.assertIn('MessagePipePartInteraction.class', self.java(target, 'transport/BCTransport.java'))
                self.assertIn('MessagePipePartInteraction.pickOverride', self.java(target, 'fabric/BuildCraftFabric263.java'))
                pipe = self.java(target, 'transport/block/BlockPipeHolder.java')
                removal = pipe.split('public boolean removeTargetedAttachment(Level world, BlockPos pos, Player player, int subHit)', 1)[1].split('public boolean onDestroyedByPlayer', 1)[0]
                self.assertIn('subHit <= 6', removal)
                self.assertIn('removePluggable(side, tile, drops)', removal)
                self.assertNotIn('removeBlock(', removal)
                self.assertNotIn('super.onDestroyedByPlayer', removal)
                self.assertIn('if (!player.isCreative()) InventoryUtil.dropAll', removal)
                packet = self.java(target, 'transport/net/MessagePipePartInteraction.java')
                for check in ('Double.isFinite', 'blockInteractionRange()', 'ClipContext.Block.OUTLINE',
                              'pipe.targetedPart(level, message.pos, verified)', 'tile.canPlayerInteract(player)',
                              'PlatformWorldActions.canBreakBlock', 'handlePickItemFromBlock', 'PICK.remove()'):
                    self.assertIn(check, packet)

    def test_marker_texture_and_laser_reload_parity(self):
        for target in self.fabric:
            with self.subTest(target=target):
                lifecycle = self.java(target, 'fabric/FabricModelLifecycle263.java')
                self.assertLess(lifecycle.index('SpriteHolderRegistry.onTextureStitchPost(stitched)'), lifecycle.index('LaserRenderer_BC8.clearModels()'))
                self.assertIn('missingSprite == currentMissingSprite', lifecycle)
                neo = target.replace('-fabric', '-neoforge')
                for rel in ('textures/blocks/marker_volume.png', 'textures/items/marker_volume.png',
                            'textures/lasers/marker_volume_signal.png', 'textures/lasers/marker_volume_connected.png',
                            'textures/lasers/marker_volume_possible.png'):
                    rel = 'src/main/resources/assets/buildcraftcore/' + rel
                    self.assertEqual(self.path(target, rel).read_bytes(), self.path(neo, rel).read_bytes())


if __name__ == '__main__':
    unittest.main()
