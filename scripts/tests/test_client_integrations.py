#!/usr/bin/env python3
"""Pipe item/book and JEI/Jade integration regressions; executable doubles are not runtime acceptance."""
from __future__ import annotations

import json
from pathlib import Path
import re
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from source_layout import load_properties, materialize_target
from client_integration_fixture import run_probe
from minecraft_compat_fixture import parse_sources


class ClientIntegrations(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='bc-client-integrations-suite-')
        cls.props = load_properties()
        cls.roots = {}
        for target in ('1.21.1-neoforge', '1.21.11-neoforge'):
            path = Path(cls.temp.name)/target
            materialize_target(target, path, cls.props)
            cls.roots[target] = path

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def java(self, logical: str, old: bool = False) -> str:
        target = '1.21.1-neoforge' if old else '1.21.11-neoforge'
        return (self.roots[target]/'src/main/java'/logical).read_text(encoding='utf-8')

    def test_executable_native_contracts(self):
        print(run_probe(self.roots['1.21.11-neoforge']))

    def test_materialized_java_syntax(self):
        print(parse_sources([r/'src/main/java' for r in self.roots.values()]))

    def test_pipe_model_registered_on_every_resource_bake(self):
        current = self.java('buildcraft/transport/BCTransportModels.java')
        self.assertIn('for (Item item : BuiltInRegistries.ITEM)', current)
        self.assertIn('event.itemStackModels().put(', current)
        self.assertIn('new ModelPipeItem(pipeItem.getDefinition())', current)
        model = self.java('buildcraft/transport/client/model/ModelPipeItem.java')
        self.assertIn('implements ItemModel', model)
        self.assertNotIn('BakedModel', model)
        self.assertIn('new ModelPipeItem', current)
        self.assertIn('BakedModel', self.java('buildcraft/transport/client/model/ModelPipeItem.java', old=True))

    def test_native_recipe_book_not_jei_only(self):
        recipe = self.java('buildcraft/transport/recipe/PipeRecipe.java')
        self.assertNotIn('PlacementInfo.NOT_PLACEABLE', recipe)
        self.assertIn('PlacementInfo.create(ingredients)', recipe)
        self.assertIn('new ShapedCraftingRecipeDisplay(3, 1', recipe)
        self.assertIn('new ShapelessCraftingRecipeDisplay(', recipe)
        self.assertNotIn('mezz.jei', recipe)
        self.assertNotIn('mezz.jei', self.java('buildcraft/silicon/BCSiliconRecipeSync.java'))

    def test_pipe_recipe_discovery_matches_reference_target(self):
        def rewarded_recipes(root: Path) -> set[str]:
            data = root / 'src/main/resources/data/buildcrafttransport'
            rewarded: set[str] = set()
            for directory in ('advancements', 'advancement'):
                base = data / directory
                if not base.is_dir():
                    continue
                for path in base.rglob('*.json'):
                    value = json.loads(path.read_text(encoding='utf-8'))
                    rewarded.update(value.get('rewards', {}).get('recipes', []))
            return rewarded

        reference = rewarded_recipes(self.roots['1.21.1-neoforge'])
        current = rewarded_recipes(self.roots['1.21.11-neoforge'])
        self.assertGreater(len(reference), 30)
        self.assertEqual(reference, current)

    def test_jei_categories_and_catalysts(self):
        plugin = self.java('buildcraft/compat/jei/BuildCraftJeiPlugin.java')
        for category in ('Assembly','Programming','Integration','Distillation','HeatExchange','CombustionFuel'):
            self.assertIn('new '+category+'Category(guiHelper)', plugin)
        self.assertEqual(6, plugin.count('public int getWidth()'))
        self.assertEqual(6, plugin.count('public int getHeight()'))
        self.assertEqual(6, plugin.count('background.draw(guiGraphics, 0, 0)'))
        self.assertNotIn('getBackground()', plugin)
        self.assertIn('RecipeTypes.SMELTING_FUEL', plugin)
        self.assertIn('BCSiliconRecipeSync.Client.assemblyRecipes()', plugin)
        self.assertIn('holder.id().identifier()', plugin)
        self.assertIn('recipe.getOutputPreviews()', plugin)
        self.assertNotIn('variant.getResultItem(', plugin)
        self.assertIn('groupAssemblyRecipes(assemblyRecipes)', plugin)
        self.assertIn('hadFacadeRecipe && BCSiliconConfig.enableFacades', plugin)
        self.assertIn('GROUPED_ASSEMBLY_RECIPES.clear()', plugin)

    def test_click_areas_and_transfer_routes(self):
        plugin = self.java('buildcraft/compat/jei/BuildCraftJeiPlugin.java')
        for screen in ('GuiAssemblyTable','GuiProgrammingTable','GuiIntegrationTable',
                       'GuiAdvancedCraftingTable','GuiAutoCraftItems','ScreenHeatExchange','GuiEngineIron_BC8','GuiEngineStone_BC8'):
            self.assertIn('addRecipeClickArea('+screen+'.class', plugin)
        self.assertIn('new AutoWorkbenchRecipeTransferHandler()', plugin)
        self.assertIn('new AdvancedCraftingRecipeTransferHandler()', plugin)
        self.assertIn('ContainerAssemblyTable.class', plugin)
        self.assertIn('getGuiExtraAreas', plugin)
        self.assertIn('Ledger_Neptune', plugin)
        for name in ('AutoWorkbenchRecipeTransferHandler', 'AdvancedCraftingRecipeTransferHandler'):
            self.assertIn('IRecipeType<RecipeHolder<CraftingRecipe>>', self.java('buildcraft/compat/jei/'+name+'.java'))
            old = self.java('buildcraft/compat/jei/'+name+'.java', old=True)
            self.assertNotIn('IRecipeType', old)
            self.assertIn('RecipeType<RecipeHolder<CraftingRecipe>>', old)

    def test_subtypes_and_tooltip_api(self):
        plugin = self.java('buildcraft/compat/jei/BuildCraftJeiPlugin.java')
        self.assertNotIn('IIngredientSubtypeInterpreter', plugin)
        self.assertNotIn('.addTooltipCallback(', plugin)
        self.assertIn('.addRichTooltipCallback(', plugin)
        for item in ('PLUG_FACADE_ITEM', 'PLUG_GATE_ITEM', 'PLUG_LENS_ITEM', 'REDSTONE_BOARD', 'ROBOT'):
            self.assertRegex(plugin, r'registerSubtypeInterpreter\([^\n]*'+item)
        self.assertIn('BCLibConfig.hideFluidValues', plugin)
        self.assertIn('Either::right', plugin)  # Rich tooltip elements are not flattened to text.
        self.assertIn('getCompoundOrEmpty("facade")', plugin)

    def test_cross_target_facade_and_pipe_presentation_contracts(self):
        current_baker = self.java('buildcraft/silicon/client/model/plug/PlugBakerFacade.java')
        current_facades = self.java('buildcraft/silicon/plug/FacadeStateManager.java')
        current_pipe = self.java('buildcraft/transport/block/BlockPipeHolder.java')
        legacy_action = (ROOT / 'source-families/legacy/src/main/java/buildcraft/transport/statements/ActionPipeDirection.java').read_text()
        old_pipe = (ROOT / 'version-src/1.19.2-forge/src/main/java/buildcraft/transport/block/BlockPipeHolder.java').read_text()
        mid_pipe = (ROOT / 'version-src/1.20.1-forge/src/main/java/buildcraft/transport/block/BlockPipeHolder.java').read_text()

        # Keep the thin translucent facade consistent with legacy geometry rather than rendering vanilla glass opaque.
        self.assertIn('GLASS_FACADE_ALPHA = 0.2D', current_baker)
        # Blocks without a normal item form remain valid facade materials when vanilla supplies a clone stack.
        self.assertIn('block.getCloneItemStack(new SingleBlockAccess(state), BlockPos.ZERO, state)', current_facades)
        # Pipe holders remain targetable as a pipe while client data is pending on every target.
        self.assertIn('return new VoxelShape[] {BOX_CENTER};', current_pipe)
        self.assertNotIn('return new VoxelShape[] {Shapes.block()};', old_pipe)
        self.assertNotIn('return new VoxelShape[] {Shapes.block()};', mid_pipe)
        # Gate text must receive the normal translated direction component, not a raw enum string.
        self.assertIn('Component.translatable("direction." + direction.getName())', legacy_action)

    def test_jade_21_typed_groups_and_all_module_bases(self):
        plugin = self.java('buildcraft/compat/jade/BuildCraftJadePlugin.java')
        for view in ('FluidView','EnergyView','ProgressView'):
            self.assertIn('IServerExtensionProvider<'+view+'.Data>', plugin)
            self.assertIn('IClientExtensionProvider<'+view+'.Data, '+view+'>', plugin)
        for forbidden in ('IBoxElement','IElementHelper','FluidView.writeDefault','ProgressView.create',
                          'new EnergyView()', 'usePickedResult(', 'IServerExtensionProvider<CompoundTag>'):
            self.assertNotIn(forbidden, plugin)
        # TileBC_Neptune covers Core, Factory, Energy, Silicon and Builders, with special
        # transport/robot providers supplying details rather than duplicating generic inventories.
        for registration in ('registerBlockDataProvider(BlockProvider.INSTANCE, TileBC_Neptune.class)',
                             'registerBlockComponent(BlockProvider.INSTANCE, BlockBCTile_Neptune.class)',
                             'registerEntityDataProvider(RobotProvider.INSTANCE, EntityRobot.class)',
                             'registerEntityComponent(RobotProvider.INSTANCE, EntityRobot.class)',
                             'registerProgress(ProgressProvider.INSTANCE, TileZonePlanner.class)',
                             'registerProgress(ProgressProvider.INSTANCE, TileLaserTableBase.class)',
                             'registerProgress(ProgressProvider.INSTANCE, TilePipeHolder.class)'):
            self.assertIn(registration, plugin)
        for functionality in ('getKnownOwner()', 'getOwnerProfile()', 'getAverageThroughput()',
                              'getTransferCapacityPerTick()', 'IdentityHashMap<>', 'MjReceiverEnergyStorage'):
            self.assertIn(functionality, plugin)
        self.assertIn('replace(JadeIds.CORE_OBJECT_NAME, title)', plugin)
        self.assertIn('registration.blockOperations().pick(', plugin)
        self.assertNotIn('.getCompound(DATA_ROOT)', plugin)

    def test_oil_fuel_immersion_matches_1211(self):
        current_proxy = self.java('buildcraft/energy/BCEnergyClientProxy.java')
        current_type = self.java('buildcraft/energy/fluid/BCFluidType.java')
        old_type = self.java('buildcraft/energy/fluid/BCFluidType.java', old=True)

        # 1.21.11 no longer accepts FluidType.initializeClient; its client extension must be
        # registered explicitly. OIL_TYPE contains all oil and fuel heat variants.
        self.assertIn('for (var holder : BCEnergyFluids.OIL_TYPE)', current_proxy)
        self.assertIn('registerFluidType(new IClientFluidTypeExtensions()', current_proxy)
        self.assertIn('textures/misc/underwater.png', current_proxy)
        self.assertIn('getRenderOverlayTexture(Minecraft mc)', current_proxy)
        self.assertIn('getOverlayTexture()', current_proxy)
        self.assertIn('return null;', current_proxy)
        self.assertIn('modifyFogColor(Camera camera', current_proxy)
        self.assertIn('new Vector4f(0.5f, 0.5f, 0.5f, fluidFogColor.w)', current_proxy)

        # The 1.21.1 reference target installs the same underwater overlay and neutral-grey
        # fog from BCFluidType.initializeClient.
        self.assertIn('initializeClient(Consumer<IClientFluidTypeExtensions> consumer)', old_type)
        self.assertIn('textures/misc/underwater.png', old_type)
        self.assertIn('new Vector3f(0.5f, 0.5f, 0.5f)', old_type)

        # Keep client-only camera/fog classes out of the common 1.21.11 FluidType itself.
        self.assertNotIn('net.minecraft.client.', current_type)
        self.assertNotIn('IClientFluidTypeExtensions', current_type)

    def test_architect_resize_preserves_draft_focus_and_geometry(self):
        current = self.java('buildcraft/builders/gui/GuiArchitectTable.java')
        menu = self.java('buildcraft/builders/menu/ContainerArchitectTable.java')

        # Window resize and GUI-scale changes rebuild the screen widget tree. The unsent/current
        # edit state must survive that re-init instead of reverting to TileArchitectTable.name.
        self.assertIn('String currentName = nameField == null ? container.tile.name : nameField.getValue();', current)
        self.assertIn('boolean focusName = nameField == null || nameField.isFocused();', current)
        self.assertIn('nameField.setValue(currentName);', current)
        self.assertIn('nameField.setMaxLength(ContainerArchitectTable.MAX_BLUEPRINT_NAME_LENGTH);', current)
        self.assertIn('if (focusName)', current)
        self.assertIn('setInitialFocus(nameField);', current)

        # Keep the texture bounds and native menu slot geometry in one source of truth so a
        # re-centered screen cannot visually drift away from the actual slot hitboxes.
        for token in (
            'GUI_WIDTH = 256', 'GUI_HEIGHT = 166',
            'PLAYER_INVENTORY_X = 88', 'PLAYER_INVENTORY_Y = 84',
            'INPUT_SLOT_X = 135', 'INPUT_SLOT_Y = 35',
            'OUTPUT_SLOT_X = 194', 'OUTPUT_SLOT_Y = 35',
        ):
            self.assertIn(token, menu)
        self.assertIn('addFullPlayerInventory(PLAYER_INVENTORY_X, PLAYER_INVENTORY_Y)', menu)
        self.assertIn('new SlotBase(in, 0, INPUT_SLOT_X, INPUT_SLOT_Y)', menu)
        self.assertIn('new SlotOutput(out, 0, OUTPUT_SLOT_X, OUTPUT_SLOT_Y)', menu)
        self.assertIn('SIZE_X = ContainerArchitectTable.GUI_WIDTH', current)
        self.assertIn('SIZE_Y = ContainerArchitectTable.GUI_HEIGHT', current)

        # This hardening is loader-neutral and intentionally keeps both modern targets on the
        # same resize behavior instead of adding another 1.21.11-only branch.
        old = self.java('buildcraft/builders/gui/GuiArchitectTable.java', old=True)
        self.assertIn('String currentName = nameField == null ? container.tile.name : nameField.getValue();', old)
        self.assertIn('nameField.setMaxLength(ContainerArchitectTable.MAX_BLUEPRINT_NAME_LENGTH);', old)

    def test_architect_resize_keeps_native_slots_and_jei_sidebar_paths(self):
        current = self.java('buildcraft/builders/gui/GuiArchitectTable.java')
        boundary = self.java('buildcraft/lib/compat/minecraft/gui/BCContainerScreen.java')
        jei = self.java('buildcraft/compat/jei/BuildCraftJeiPlugin.java')

        # Architect handles only its BC elements/edit box. Unhandled clicks continue through the
        # 1.21.11 native container screen, preserving slot and third-party/sidebar interaction.
        self.assertIn('boolean handled = super.mouseClicked(mouseX, mouseY, mouseButton);', current)
        self.assertIn('handled |= RenderCompat.mouseClicked(nameField, mouseX, mouseY, mouseButton);', current)
        self.assertIn('mouseClicked(event.x(), event.y(), event.button()) || super.mouseClicked(event, doubleClick)', boundary)

        # JEI computes ledger exclusion areas from live element coordinates, so after a resize the
        # sidebar follows the re-centered GUI instead of retaining stale absolute rectangles.
        self.assertIn('getGuiExtraAreas', jei)
        self.assertIn('Ledger_Neptune', jei)
        self.assertIn('ledger.getX()', jei)
        self.assertIn('ledger.getY()', jei)

    def test_optional_dependencies_enabled_not_required(self):
        for mod in ('jei','jade'):
            self.assertEqual('true', self.props['target.1.21.11-neoforge.compat.'+mod+'.enabled'])
            self.assertTrue(self.props['target.1.21.11-neoforge.deps.'+mod].startswith('maven.modrinth:'))
        resource = self.roots['1.21.11-neoforge']/'src/main/resources/META-INF/neoforge.mods.toml'
        metadata = resource.read_text()
        for mod in ('jei','jade'):
            match = re.search(r'modId\s*=\s*"'+mod+r'"(.*?)(?=\[\[|\Z)', metadata, re.S)
            self.assertIsNotNone(match, mod)
            self.assertIn('optional', match.group(1).lower(), mod)
        old = self.java('buildcraft/compat/jade/BuildCraftJadePlugin.java', old=True)
        self.assertIn('IServerExtensionProvider<CompoundTag>', old)


if __name__ == '__main__':
    unittest.main()
