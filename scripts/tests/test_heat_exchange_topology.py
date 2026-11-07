"""Structural regression for BCCE heat exchanger topology invalidation across source families.

The real placement/reassembly behavior is covered by HeatExchangerReassemblyGameTests.
This suite checks that Stonecutter always selects the corresponding block/tile hooks.
"""
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT / 'scripts'))
from source_config import load_properties, target_layout
from source_layout import resolve_effective_source

BLOCK = 'src/main/java/buildcraft/factory/block/BlockHeatExchange.java'
TILE = 'src/main/java/buildcraft/factory/tile/TileHeatExchange.java'
TARGETS = (
    '1.19.2-forge','1.20.1-forge',
    '1.21.1-neoforge','1.21.11-neoforge',
    '26.1.2-neoforge','26.2-neoforge','26.3-neoforge',
)

class HeatExchangerTopologySourceTests(unittest.TestCase):
    def test_all_targets_receive_reassembly_lifecycle(self):
        props=load_properties()
        for name in TARGETS:
            with self.subTest(target=name):
                layout=target_layout(name,props)
                block=resolve_effective_source(layout,props,BLOCK)
                tile=resolve_effective_source(layout,props,TILE)
                self.assertIsNotNone(block)
                self.assertIsNotNone(tile)
                bt=block.read_text(encoding='utf-8')
                tt=tile.read_text(encoding='utf-8')
                self.assertIn('exchange.markCheckNeighbours();',bt)
                self.assertIn('void onPlace(',bt)
                self.assertIn('invalidateAdjacent(level, pos);',bt)
                self.assertTrue('void onRemove(' in bt or 'affectNeighborsAfterRemoval(' in bt)
                self.assertIn('public void markCheckNeighbours()',tt)
                self.assertIn('markCheckNeighbours();',tt[tt.index('onLoad()'):tt.index('onLoad()')+110])
                self.assertIn('exchangers.size() < 3',tt)
                self.assertIn('exchangers.size() > 5',tt)
    def test_each_platform_has_real_reassembly_gametests(self):
        for platform in ('forge','neoforge'):
            with self.subTest(platform=platform):
                path=ROOT/f'source-platforms/{platform}/src/gametest/java/buildcraft/gametest/HeatExchangerReassemblyGameTests.java'
                text=path.read_text(encoding='utf-8')
                self.assertEqual(text.count('@GameTest('),4)
                self.assertIn('replacingMiddleRestoresChain',text)
                self.assertIn('replacingEndRestoresChain',text)
                self.assertIn('replacingStartRestoresChain',text)
                self.assertIn('shorteningFourSections',text)
                self.assertIn('helper.setBlock(at(2), Blocks.AIR.defaultBlockState());',text)

if __name__ == '__main__':
    unittest.main()
