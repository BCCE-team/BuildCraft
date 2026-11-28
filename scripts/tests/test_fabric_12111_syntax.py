#!/usr/bin/env python3
"""Parse the complete 1.21.11 Fabric source tree with javac (not type checking)."""
from pathlib import Path
import json
import shutil
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from source_layout import materialize_target
from minecraft_compat_fixture import parse_sources


class Fabric12111SyntaxTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='bc-fabric-12111-syntax-')
        cls.addClassCleanup(cls.temp.cleanup)
        cls.tree = materialize_target('1.21.11-fabric', Path(cls.temp.name) / 'effective')

    @unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'JDK required')
    def test_all_java_source_sets_parse(self):
        roots = [self.tree / 'src' / name / 'java' for name in ('main', 'test', 'gametest')]
        for root in roots:
            self.assertTrue(any(root.rglob('*.java')), root)
        result = parse_sources(roots)
        print(result, flush=True)
        self.assertEqual(result.count('0 errors (parse only)'), len(roots), result)

    def test_facade_initialization_contract(self):
        java = self.tree / 'src/main/java/buildcraft/silicon'
        manager = (java / 'plug/FacadeStateManager.java').read_text(encoding='utf-8')
        items = (java / 'BCSiliconItems.java').read_text(encoding='utf-8')
        self.assertIn('public static boolean isInitialized() {\n        return initialized;\n    }', manager)
        self.assertIn('if (!FacadeStateManager.isInitialized())', items)
        self.assertEqual(manager.count('previewState = validFacadeStates.getOrDefault('), 1)
        self.assertIn('commitCache.run();', manager)
        self.assertIn('initializing = false;', manager)
        phased = manager.split('public Optional<ItemStack> createPhased(', 1)[1]
        self.assertLess(phased.index('init();'), phased.index('validFacadeStates.get('))


    def read_java(self, path):
        return (self.tree / 'src/main/java/buildcraft' / path).read_text(encoding='utf-8')

    def test_native_fabric_api_contracts(self):
        self.assertIn('TooltipComponentCallback.EVENT.register', self.read_java('builders/client/FabricBuildersClient.java'))
        net = self.read_java('lib/net/MessageManager.java')
        for name in ('playS2C()', 'playC2S()'):
            self.assertIn('PayloadTypeRegistry.' + name, net)
        self.assertEqual(self.read_java('lib/recipe/BCLibIngredientTypes.java').count('getPacketCodec()'), 2)
        for ingredient in ('ExactComponentsIngredient', 'LegacyStrictNbtIngredient'):
            self.assertIn('getMatchingItems()', self.read_java('lib/recipe/' + ingredient + '.java'))
        self.assertIn('variant.getComponents()', self.read_java('lib/fluid/FabricFluidVariants.java'))
        self.assertFalse((self.tree / 'src/main/java/buildcraft/lib/compat/mc2612/world/item/crafting/SimpleCraftingRecipeSerializer.java').exists())

    def test_render_and_update_adapters(self):
        block = self.read_java('lib/compat/minecraft/persistence/BCBlockEntity.java')
        self.assertIn('if (readClientRenderUpdate(input)) return;', block)
        self.assertIn('protected boolean readClientRenderUpdate', self.read_java('lib/tile/TileBC_Neptune.java'))
        aw = (self.tree / 'src/main/resources/buildcraft.accesswidener').read_text(encoding='utf-8')
        for field in ('renderables', 'scissorStack', 'guiRenderState'):
            self.assertIn(' ' + field + ' ', aw)
        fog = self.read_java('fabric/mixin/FluidFogMixin.java')
        self.assertIn('@ModifyArgs', fog)
        self.assertIn('updateBuffer(Ljava/nio/ByteBuffer;ILorg/joml/Vector4f;FFFFFF)V', fog)
        self.assertNotIn('fog.color', fog)

    def test_machine_and_frame_render_layers(self):
        bootstrap = self.read_java('fabric/BuildCraftFabric263Client.java')
        self.assertIn('FabricBlockTransparency.register();', bootstrap)
        layers = self.read_java('fabric/FabricBlockTransparency.java')
        self.assertIn('BCBuildersBlocks.FRAME.get(), ChunkSectionLayer.CUTOUT', layers)
        self.assertIn('BCFactoryBlocks.HEATEXCHANGE_BLOCK.get(), BCSiliconBlocks.PROGRAMMING_TABLE_BLOCK.get()', layers)
        self.assertIn('new LayeredMachineModel(model, HEAT_GLASS, ChunkSectionLayer.SOLID)', layers)
        self.assertIn('new LayeredMachineModel(model, TABLE_GLASS, ChunkSectionLayer.CUTOUT)', layers)
        self.assertIn('quad.renderLayer(ChunkSectionLayer.TRANSLUCENT)', layers)
        self.assertIn('quad.renderLayer(bodyLayer)', layers)
        self.assertIn('emitter.popTransform();', layers)
        self.assertIn('new LayeredGeometryKey(original, glassSprite, bodyLayer)', layers)
        assets = self.tree / 'src/main/resources/assets'
        def model(namespace, path):
            return json.loads((assets / namespace / 'models/block' / (path + '.json')).read_text())
        for part in ('base', 'connection'):
            self.assertEqual(model('buildcraftbuilders', 'frame/' + part)['render_type'], 'minecraft:cutout')
        for part in ('start', 'middle', 'end'):
            glass = model('buildcraftfactory', 'heat_exchange_' + part + '_glass')
            self.assertEqual(glass['textures']['glass'], 'buildcraftfactory:block/heat_exchange/glass')
            self.assertEqual(glass['render_type'], 'minecraft:translucent')
        self.assertEqual(model('buildcraftsilicon', 'table/programming/transparent')['textures']['glass'],
                         'minecraft:block/white_stained_glass')

    def test_pipe_packet_contract(self):
        message = self.read_java('transport/net/MessageMultiPipeItem.java')
        flow = self.read_java('transport/pipe/flow/PipeFlowItems.java')
        self.assertIn('MessageMultiPipeItem.appendTracked(', flow)
        self.assertIn('PipeItemMessageQueue.appendTracked(', message)
        self.assertIn('ItemStackUtil.readOptional(buf)', message)
        self.assertIn('ItemStackUtil.writeOptional(buf, stack)', message)
        for field in ('stack', 'visualTransitId', 'serverStartTick', 'serverSentTick'):
            self.assertIn('data.' + field, flow)
            self.assertIn('this.' + field, message)
        self.assertIn('scheduleClientItem(item, data.serverStartTick, data.serverSentTick)', flow)

    def test_no_external_forge_imports(self):
        for root in ('main', 'test', 'gametest'):
            for path in (self.tree / 'src' / root / 'java').rglob('*.java'):
                self.assertNotRegex(path.read_text(encoding='utf-8'), r'(?m)^\s*import\s+(?:static\s+)?net\.(?:neoforged|minecraftforge)\.', str(path))


if __name__ == '__main__':
    unittest.main()
