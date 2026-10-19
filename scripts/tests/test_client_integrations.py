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
        self.assertIn('new ModelPipeItem(pipeItem.getDefinition(), baseModel)', current)
        model = self.java('buildcraft/transport/client/model/ModelPipeItem.java')
        self.assertIn('implements ItemModel', model)
        self.assertNotIn('BakedModel', model)
        self.assertIn('var baseModel = event.itemStackModels().get(id);', current)
        self.assertIn('this.colours[0] = baseModel;', model)
        self.assertIn('new CompositeModel(List.of(baseModel,', model)
        self.assertIn('BakedModel', self.java('buildcraft/transport/client/model/ModelPipeItem.java', old=True))

    def test_1_21_1_pulsar_inventory_dynamic_layer_resets_item_pose(self):
        old = self.java('buildcraft/silicon/BCSiliconModels.java', old=True)
        self.assertIn(
            'new ModelPluggableItem(PULSAR_STATIC::getCutoutQuads, BCSiliconModels::getPulsarItemDynamicQuads)',
            old,
        )
        helper = old[old.index('private static MutableQuad[] getPulsarItemDynamicQuads()'):]
        self.assertLess(
            helper.index('PluggablePulsar.setModelVariablesForItem();'),
            helper.index('return PULSAR_DYNAMIC.getCutoutQuads();'),
        )

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
                       'GuiAdvancedCraftingTable','GuiAutoCraftItems','ScreenHeatExchange','GuiEngineStone_BC8'):
            self.assertIn('addRecipeClickArea('+screen+'.class', plugin)
        # The combustion engine fuel tank must remain interactive outside JEI recipe shortcuts.
        # JEI must not steal bucket clicks from WidgetFluidTank.
        self.assertNotIn('addRecipeClickArea(GuiEngineIron_BC8.class', plugin)
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
        shared_action = (ROOT / 'source-shared/src/main/java/buildcraft/transport/statements/ActionPipeDirection.java').read_text()
        old_pipe = (ROOT / 'version-src/1.19.2-forge/src/main/java/buildcraft/transport/block/BlockPipeHolder.java').read_text()
        mid_pipe = (ROOT / 'version-src/1.20.1-forge/src/main/java/buildcraft/transport/block/BlockPipeHolder.java').read_text()

        # Alpha handling is generation-specific. Forge 1.19.2/1.20.1 and NeoForge 1.21.1 still use the legacy
        # baked-quad path with opaque facade vertex alpha, so they require a 0.2 multiplier.
        # 1.21.11+ carries per-vertex BakedColors/alpha natively and must not receive that extra multiplier.
        old_baker = self.java('buildcraft/silicon/client/model/plug/PlugBakerFacade.java', old=True)
        forge_baker = (ROOT / 'source-platforms/forge/src/main/java/buildcraft/silicon/client/model/plug/PlugBakerFacade.java').read_text()
        self.assertIn('multColourd(1.0, 1.0, 1.0, 0.2)', forge_baker)
        self.assertIn('multColourd(1.0, 1.0, 1.0, 0.2)', old_baker)
        self.assertNotIn('multColourd(1.0, 1.0, 1.0, 0.2)', current_baker)
        self.assertNotIn('GLASS_FACADE_ALPHA', current_baker)
        # Blocks without a normal item form remain valid facade materials when vanilla supplies a clone stack.
        # The public extension signature differs between 1.21.1 and 1.21.11, so the bridge resolves both without
        # falling back to asItem() and losing state-dependent variants.
        self.assertIn('getCloneStack(block, state)', current_facades)
        self.assertIn('LevelReader.class, BlockPos.class,', current_facades)
        self.assertIn('BlockState.class, boolean.class, Player.class', current_facades)
        self.assertIn('BlockState.class);', current_facades)
        self.assertIn('if (!state.getFluidState().isEmpty())', current_facades)
        # Pipe holders remain targetable as a pipe while client data is pending on every target.
        self.assertIn('return new VoxelShape[] {BOX_CENTER};', current_pipe)
        self.assertNotIn('return new VoxelShape[] {Shapes.block()};', old_pipe)
        self.assertNotIn('return new VoxelShape[] {Shapes.block()};', mid_pipe)
        # Gate text must receive the normal translated direction component, not a raw enum string.
        self.assertIn('Component.translatable("direction." + direction.getName())', shared_action)

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
        for registration in ('registerBlockDataProvider(BlockServerDataProvider.INSTANCE, TileBC_Neptune.class)',
                             'registerBlockComponent(BlockProvider.INSTANCE, BlockBCTile_Neptune.class)',
                             'registerEntityDataProvider(RobotServerDataProvider.INSTANCE, EntityRobot.class)',
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

    def test_oil_fuel_immersion_matches_1_21_1(self):
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
        legacy_menu = self.java('buildcraft/builders/menu/ContainerArchitectTable.java', old=True)
        self.assertIn('public static final int MAX_BLUEPRINT_NAME_LENGTH = 128;', legacy_menu)

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

    def test_runtime_gui_regressions_found_on_1_21_11(self):
        current_root = self.roots['1.21.11-neoforge'] / 'src/main/java'
        old_root = self.roots['1.21.1-neoforge'] / 'src/main/java'

        # Filler JSON slots must see SlotBase even though NeoForge now backs it with ItemHandlerCopySlot.
        for root in (old_root, current_root):
            holder = (root / 'buildcraft/lib/gui/json/InventorySlotHolder.java').read_text()
            self.assertIn('slot instanceof SlotBase baseSlot', holder)
            self.assertIn('baseSlot.itemHandler == inventory', holder)

        # 1.21.11 must preserve vanilla Screen/ContainerScreen input dispatch. Without these calls
        # List edit boxes and other native widgets never receive mouse/keyboard input.
        gui = (current_root / 'buildcraft/lib/gui/GuiBC8.java').read_text()
        self.assertIn('super.mouseClicked(mouseX, mouseY, mouseButton)', gui)
        self.assertIn('super.mouseDragged(mouseX, mouseY, button, dragX, dragY)', gui)
        self.assertIn('super.mouseReleased(mouseX, mouseY, button)', gui)
        self.assertIn('super.keyPressed(keyCode, scanCode, modifiers)', gui)
        self.assertIn('super.charTyped(codePoint, modifiers)', gui)
        self.assertIn('persistentElementCount', gui)
        self.assertIn('shownElements.subList(persistentElementCount', gui)

        # Glass facades must stay in the terrain translucent pass. A block-entity/dynamic translucent pass is
        # sorted independently from water/ice and causes the facade to see through other translucent terrain.
        for root in (old_root, current_root):
            silicon_models = (root / 'buildcraft/silicon/BCSiliconModels.java').read_text()
            self.assertNotIn('registry.registerRenderer(PluggableFacade.class, PlugFacadeRenderer.INSTANCE)', silicon_models)
        old_facade = (old_root / 'buildcraft/silicon/plug/PluggableFacade.java').read_text()
        self.assertNotIn('if (isGlass(blockState)) {\n                return null;', old_facade)
        self.assertIn('targetLayer.contains(RenderType.translucent())', old_facade)

        # The holder's collision shape can report its pipe rather than the visible facade. Paint therefore resolves
        # the facade from its actual shape at the hit position and must not fall through to the pipe.
        for root in (old_root, current_root):
            pipe_holder = (root / 'buildcraft/transport/block/BlockPipeHolder.java').read_text()
            paint = pipe_holder[pipe_holder.index('InteractionResult attemptPaint'):]
            self.assertIn('Direction facadeSide = getFacadeSideAt(tile, pos, hitPos, hitSide)', paint)
            facade_hit = paint[paint.index('private static Direction getFacadeSideAt'):paint.index('private static Direction getLensSideAt')]
            self.assertIn('tile.getPluggable(hitSide)', facade_hit)
            self.assertIn('selected.getBoundingBox().bounds().inflate(1.0E-4).contains(localHit)', facade_hit)
            self.assertIn('pluggable.getBoundingBox().bounds().inflate(1.0E-4).contains(localHit)', facade_hit)
            self.assertIn('!facade.isHollow()', facade_hit)
            self.assertLess(paint.index('if (facadeSide != null)'), paint.index('Pipe pipe = tile.getPipe()'))
            self.assertNotIn('computSubhit(tile, localHit', paint)

        # 1.21.11 uses the real vanilla Recipe Book texture/sprites/layout rather than a recoloured custom panel.
        book = (current_root / 'buildcraft/lib/gui/recipe/GuiRecipeBookPhantom.java').read_text()
        button = (current_root / 'buildcraft/lib/gui/recipe/GuiButtonRecipePhantom.java').read_text()
        for token in (
            'textures/gui/recipe_book.png',
            'recipe_book/tab_selected',
            'recipe_book/page_forward',
            'recipe_book/page_backward',
            'PANEL_WIDTH = 147',
            'PANEL_HEIGHT = 166',
            'panelX + 11 + (i % GRID_COLUMNS) * 25',
            'panelY + 31 + (i / GRID_COLUMNS) * 25',
        ):
            self.assertIn(token, book)
        self.assertIn('recipe_book/slot_craftable', button)
        self.assertIn('recipe_book/slot_uncraftable', button)
        self.assertNotIn('fill(', book)

        # Live Zone Planner render states must not reference textures that a wall-clock cache can release underneath them.
        zone = (current_root / 'buildcraft/robotics/client/render/RenderZonePlanner.java').read_text()
        self.assertIn('.maximumSize(256)', zone)
        self.assertNotIn('expireAfterAccess(', zone)
        self.assertIn('colours[textureY * TEXTURE_WIDTH + textureX] = MAP_BACKGROUND_COLOUR;', zone)

        # Jade gets the concrete translation key from the server data. At header assembly time its client block
        # entity can still expose only the generic Pipe Holder item, so item lookup is only a fallback.
        jade = (current_root / 'buildcraft/compat/jade/BuildCraftJadePlugin.java').read_text()
        self.assertIn('blockAccessor.getBlockEntity() instanceof TilePipeHolder holder', jade)
        self.assertIn('tag.putString("NameKey", pipe.definition.identifier.toLanguageKey("pipe"))', jade)
        self.assertIn('NbtCompat.getCompound(root, "Pipe")', jade)
        self.assertIn('Component.translatable(nameKey).withStyle(ChatFormatting.WHITE)', jade)
        self.assertIn('PipeRegistry.INSTANCE.getItemForPipe(pipe.getDefinition())', jade)
        self.assertIn('BuiltInRegistries.BLOCK.getKey(BCTransportBlocks.pipeHolder.get())', jade)

        # The facade's active phase must be part of both initial creation data and incremental updates.
        # Otherwise the server accepts a brush colour, but a joining/reloaded client keeps rendering phase zero.
        facade_state = self.java('buildcraft/silicon/plug/PluggableFacade.java')
        self.assertIn('activeState = MathUtil.clamp(buffer.readVarInt(), 0, states.phasedStates.length - 1);', facade_state)
        self.assertIn('buffer.writeVarInt(activeState);', facade_state)
        self.assertIn('if (states.type == FacadeType.Basic)', facade_state)
        self.assertIn('CustomPaintHelper.recoloredState(', facade_state)
        self.assertIn('states = new FacadeInstance(', facade_state)
        self.assertIn('states.writeToBuffer(buffer);', facade_state)
        self.assertIn('states = FacadeInstance.readFromBuffer(buffer);', facade_state)
        self.assertIn('public CompoundTag writeSyncState(BCNetworkSide side)', facade_state)

        # Legacy baked vertices are ABGR. The native 1.21.11 pipe converter reads these vertices before passing
        # their colours through BlockColor, so an ARGB decode swaps red and blue (red pipes become blue).
        mutable_vertex = (current_root / 'buildcraft/lib/client/model/MutableVertex.java').read_text()
        self.assertIn('colourAbgr(data[offset + 3]);', mutable_vertex)
        self.assertIn('return colouri(abgr, abgr >> 8, abgr >> 16, abgr >>> 24);', mutable_vertex)

        pipe_colours = (current_root / 'buildcraft/transport/client/model/PipeBaseModelGenStandard.java').read_text()
        old_pipe_colours = (old_root / 'buildcraft/transport/client/model/PipeBaseModelGenStandard.java').read_text()
        self.assertIn('return 0xFF_00_00_00 | ColourUtil.getLightHex(c);', pipe_colours)
        self.assertIn('return 0x40_00_00_00 | ColourUtil.getLightHex(c);', old_pipe_colours)

        native_pipe = (current_root / 'buildcraft/transport/client/model/ModelPipeNative121111.java').read_text()
        self.assertIn('BakedColors.of(0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF)', native_pipe)

        # 1.21.11 native quads preserve BakedColors/alpha, so glass facades belong in the same terrain
        # translucent pass as water, ice and vanilla glass instead of being filtered to a dynamic renderer.
        native_model = (current_root / 'buildcraft/transport/client/model/ModelPipeNative121111.java').read_text()
        cache = (current_root / 'buildcraft/transport/client/model/PipeModelCachePluggable.java').read_text()
        self.assertIn('ModelPipeNative121111::isNativeStaticPluggable', native_model)
        self.assertIn('BakedColors.of(argb(quad.vertex_0)', native_model)
        self.assertNotIn('PluggableFacade.isGlass(', native_model)
        self.assertIn('Predicate<PipePluggable> include', cache)

        # Decorative gears are regular GUI elements, rendered after the texture overlay by GuiBC8's guaranteed
        # element layer instead of an unreliable direct call from the legacy background hook.
        for gui_name in ('GuiEngineFE.java', 'GuiDynamoMJ.java'):
            energy_gui = (current_root / 'buildcraft/energy/client/gui' / gui_name).read_text()
            self.assertIn('private void addGearIcon(Item item, int x, int y)', energy_gui)
            self.assertIn('public void drawBackground(GuiGraphics guiGraphics, float partialTicks)', energy_gui)
            self.assertIn('guiGraphics.renderItem(new ItemStack(item)', energy_gui)

        # Snapshot creation stays in the world store. A normal request response is only a transient client copy;
        # NET_DOWN is the sole route that may write the portable blueprints directory.
        snapshots = (current_root / 'buildcraft/builders/snapshot/GlobalSavedDataSnapshots.java').read_text()
        response = (current_root / 'buildcraft/builders/snapshot/MessageSnapshotResponseClientHandler.java').read_text()
        self.assertNotIn('!level.getServer().isDedicatedServer()', snapshots)
        self.assertNotIn('Snapshot local = getClientSnapshot(key)', snapshots)
        self.assertNotIn('GlobalSavedDataSnapshots.saveClientSnapshot(message.getSnapshot())', response)
        request = (current_root / 'buildcraft/builders/snapshot/MessageSnapshotRequest.java').read_text()
        self.assertIn('GlobalSavedDataSnapshots.getSnapshotForConstruction(', request)
        self.assertIn('player.level()', request)

        # List matching may only interpret common c:/forge: material-form tags as OreDictionary equivalents.
        ore = (current_root / 'buildcraft/lib/list/ListMatchHandlerOreDictionary.java').read_text()
        armor = (current_root / 'buildcraft/lib/list/ListMatchHandlerArmor.java').read_text()
        lists = (current_root / 'buildcraft/lib/list/ListHandler.java').read_text()
        self.assertIn('MATERIAL_FORM_ROOTS', ore)
        self.assertIn('namespace.equals("c")', ore)
        self.assertIn('namespace.equals("forge")', ore)
        self.assertIn('type == ListMatchType.TYPE', armor)
        self.assertNotIn('Collections.shuffle(stackList)', lists)

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

    def test_1_21_11_jade_server_and_client_providers_are_separate(self):
        plugin = self.java('buildcraft/compat/jade/BuildCraftJadePlugin.java')
        self.assertIn('BlockServerDataProvider.INSTANCE', plugin)
        self.assertIn('RobotServerDataProvider.INSTANCE', plugin)
        self.assertIn('private enum BlockProvider implements IBlockComponentProvider', plugin)
        self.assertIn('private enum RobotProvider implements IEntityComponentProvider', plugin)
        self.assertIn('UID_BLOCK_DATA = id("block_data")', plugin)
        self.assertIn('UID_ENTITY_ROBOT_DATA = id("robot_data")', plugin)
        self.assertIn('return UID_BLOCK_DATA;', plugin)
        self.assertIn('return UID_ENTITY_ROBOT_DATA;', plugin)
        self.assertNotIn('implements IBlockComponentProvider, IServerDataProvider', plugin)
        self.assertNotIn('implements IEntityComponentProvider, IServerDataProvider', plugin)

    def test_1_21_11_jade_multimod_plugin_scan_is_deduplicated(self):
        root = self.roots['1.21.11-neoforge']
        metadata = (root / 'src/main/resources/META-INF/neoforge.mods.toml').read_text()
        mixin_config = (root / 'src/main/resources/buildcraft.jade.mixins.json').read_text()
        mixin = (root / 'src/main/java/buildcraft/lib/compat/jade/mixin/JadeEntrypointDedupMixin.java').read_text()

        # BuildCraft intentionally exposes several module mod ids from one physical NeoForge jar.
        # Jade 1.21.11 scans the same file-level annotation data once per ModContainer, so the
        # single @WailaPlugin class otherwise appears repeatedly and Jade rejects it as a duplicate.
        self.assertGreaterEqual(metadata.count('[[mods]]'), 8)
        self.assertIn('config="buildcraft.jade.mixins.json"', metadata)
        self.assertIn('requiredMods=["jade"]', metadata)
        self.assertIn('"JadeEntrypointDedupMixin"', mixin_config)
        self.assertIn('@Pseudo', mixin)
        self.assertIn('targets = "snownee.jade.util.CommonProxy"', mixin)
        self.assertIn('method = "loadEntrypoints"', mixin)
        self.assertIn('require = 0', mixin)
        self.assertIn('seenClasses.add(className)', mixin)
        self.assertIn('cir.setReturnValue(List.copyOf(unique));', mixin)

        # Keep the actual plugin singular in the materialized target. The mixin fixes only Jade's
        # repeated scan result and must not introduce another BuildCraft plugin class itself.
        plugins = list((root / 'src/main/java').rglob('*.java'))
        annotated = [path for path in plugins if re.search(r'^\s*@WailaPlugin\s*$', path.read_text(encoding='utf-8'), re.M)]
        self.assertEqual(1, len(annotated))

        # The 1.21.1 Jade loader scans file data globally and does not have this duplicate-entrypoint bug.
        old_root = self.roots['1.21.1-neoforge']
        old_metadata = (old_root / 'src/main/resources/META-INF/neoforge.mods.toml').read_text()
        self.assertNotIn('buildcraft.jade.mixins.json', old_metadata)
        self.assertFalse((old_root / 'src/main/resources/buildcraft.jade.mixins.json').exists())
        self.assertFalse((old_root / 'src/main/java/buildcraft/lib/compat/jade/mixin/JadeEntrypointDedupMixin.java').exists())




# ---- JEI assembly recipes, sync and reload ----



import subprocess

from source_layout import load_properties, target_layout, resolve_effective_source, _materialize_text_file

JEI_ASSEMBLY_SYNC_MODERN = ('1.21.11-neoforge', '26.1.2-neoforge', '26.2-neoforge', '26.3-neoforge')
JEI_ASSEMBLY_SYNC_LEGACY = ('1.19.2-forge', '1.20.1-forge')


def JEI_ASSEMBLY_SYNC_write(root: Path, relative: str, code: str) -> Path:
    file = root / relative
    file.parent.mkdir(parents=True, exist_ok=True)
    file.write_text(code, encoding='utf-8')
    return file


def JEI_ASSEMBLY_SYNC_javaprobe(root: Path, sources: list[Path], main: str) -> str:
    classes = root / 'classes'
    classes.mkdir(exist_ok=True)
    result = subprocess.run(['javac', '--release', '21', '-encoding', 'UTF-8', '-d', str(classes), *(str(f) for f in sources)], capture_output=True, text=True, timeout=40)
    if result.returncode:
        raise AssertionError('javac error: ' + result.stderr)
    result = subprocess.run(['java', '-cp', str(classes), main], capture_output=True, text=True, timeout=30)
    if result.returncode:
        raise AssertionError('Java assertion: ' + result.stderr + '\n' + result.stdout)
    return result.stdout


class JeiAssemblySyncTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='bc-jei-skm-')
        cls.props = load_properties()
        cls.roots = {target: Path(cls.temp.name) / target for target in (*JEI_ASSEMBLY_SYNC_MODERN, *JEI_ASSEMBLY_SYNC_LEGACY, '1.21.1-neoforge')}

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def source(self, target, path):
        relative = 'src/main/java/' + path
        layout = target_layout(target, self.props)
        source = resolve_effective_source(layout, self.props, relative)
        self.assertIsNotNone(source, f'missing {target}: {relative}')
        dest = self.roots[target] / relative
        if not dest.is_file():
            downports = tuple(root for root in (layout.family_downport_root, layout.family_platform_downport_root) if root)
            _materialize_text_file(source, dest, logical_relative=relative,
                minecraft=self.props[f'target.{target}.deps.minecraft'], family=layout.family,
                platform=layout.platform, preprocess=True,
                native_source=any(source.is_relative_to(root) for root in downports))
        return dest

    def code(self, target, path):
        return self.source(target, path).read_text(encoding='utf-8')

    def test_modern_jei_registers_initial_and_late_assembly_recipes(self):
        for target in JEI_ASSEMBLY_SYNC_MODERN:
            with self.subTest(target=target):
                plugin = self.code(target, 'buildcraft/compat/jei/BuildCraftJeiPlugin.java')
                sync = self.code(target, 'buildcraft/silicon/BCSiliconRecipeSync.java')
                provider = self.code(target, 'buildcraft/silicon/BCSiliconRecipesProvider.java')
                self.assertIn('registration.addRecipes(ASSEMBLY, assemblyRecipes);', plugin)
                self.assertIn('onRuntimeAvailable(IJeiRuntime runtime)', plugin)
                self.assertIn('setAssemblyRecipesChangedListener(BuildCraftJeiPlugin::refreshAssemblyRecipes)', plugin)
                self.assertIn('runtime.getRecipeManager().addRecipes(ASSEMBLY, updated)', plugin)
                self.assertIn('runtime.getRecipeManager().hideRecipes(ASSEMBLY, publishedAssemblyRecipes)', plugin)
                self.assertIn('source.equals(publishedAssemblySource)', plugin)
                self.assertIn('collectAssemblyRecipes(source)', plugin)
                self.assertIn('registration.addRecipes(PROGRAMMING', plugin)
                self.assertIn('registration.addRecipes(INTEGRATION', plugin)
                self.assertIn('registration.addRecipes(DISTILLATION', plugin)
                self.assertIn('registration.addRecipes(HEAT_EXCHANGE', plugin)
                self.assertIn('registration.addRecipes(COMBUSTION_FUEL', plugin)
                self.assertIn('FacadeAssemblyJeiRecipe.create()', plugin)
                self.assertIn('GROUPED_ASSEMBLY_RECIPES', plugin)
                self.assertIn('event.sendRecipes(BCSiliconRecipes.ASSEMBLY_TYPE.get())', sync)
                self.assertIn('notifyAssemblyRecipesChanged()', sync)
                self.assertIn('assembly = List.of();', sync)
                for marker in ('assembly/gate/and_', 'assembly/gate/or_', 'assembly/lens/', 'assembly/diamond_chipset'):
                    self.assertIn(marker, provider)

    def test_assembly_output_previews_preserve_item_components(self):
        # Before 1.21.11 JEI used Recipe#getResultItem, but modern JEI's
        # assemblyResult() consumes getOutputPreviews(). Returning an empty
        # set makes every gate, lens, wire and chipset have an empty output.
        for target in JEI_ASSEMBLY_SYNC_MODERN:
            with self.subTest(target=target):
                code = self.code(target, 'buildcraft/lib/recipe/AssemblyRecipe.java')
                match = re.search(r'public Set<ItemStack> getOutputPreviews\(\) \{.*?\n    \}', code, re.S)
                self.assertIsNotNone(match)
                method = match.group()
                deferred = target.startswith('26.')
                self.assertIn('return ImmutableSet.of(resultTemplate().create());' if deferred
                              else 'return ImmutableSet.of(output.copy());', method)
                self.assertNotIn('ImmutableSet.of();', method)
                root = Path(self.temp.name) / ('preview-stubs-' + target)
                value_type = 'ItemStackTemplate' if deferred else 'ItemStack'
                value_type_init = 'new ItemStackTemplate("gate", 193)' if deferred else 'new ItemStack("gate", 193)'
                template_accessor = 'ItemStackTemplate resultTemplate(){return output;}' if deferred else ''
                fixture = '''import java.util.*;
public class PreviewProbe {
  record ItemStack(String item,int components) {ItemStack copy(){return new ItemStack(item,components);}}
  record ItemStackTemplate(String item,int components) {ItemStack create(){return new ItemStack(item,components);}}
  static class ImmutableSet { static <T> Set<T> of(T value){return Set.of(value);} }
  VALUE_TYPE output = VALUE_INIT;
  TEMPLATE_ACCESSOR
  METHOD
  public static void main(String[]args){
    PreviewProbe recipe=new PreviewProbe();
    var previews=recipe.getOutputPreviews();
    if(previews.size()!=1)throw new AssertionError("missing output");
    var result=previews.iterator().next();
    if(!result.item().equals("gate")||result.components()!=193)throw new AssertionError("lost gate variant");
    System.out.println("assembly output preview PASS");
  }
}
'''.replace('VALUE_TYPE', value_type).replace('VALUE_INIT', value_type_init).replace('TEMPLATE_ACCESSOR', template_accessor).replace('  METHOD', '  ' + method)
                out=JEI_ASSEMBLY_SYNC_javaprobe(root,[JEI_ASSEMBLY_SYNC_write(root,'PreviewProbe.java',fixture)],'PreviewProbe')
                self.assertIn('PASS',out)

    def test_263_gate_lens_json_present_and_compatible_with_assembly_codec(self):
        # On 26.3 datagen Java is intentionally omitted; packaged JSON from
        # the 1.21.X source family remains the authoritative recipe source.
        target = '26.3-neoforge'
        layout = target_layout(target, self.props)
        path_root = 'src/main/resources/data/buildcraftsilicon/recipe/assembly/'
        for relative, result in (
            ('gate/and_iron_no_modifier.json', 'buildcraftsilicon:plug/gate'),
            ('lens/regular/purple.json', 'buildcraftsilicon:plug/lens'),
            ('lens/filter/red.json', 'buildcraftsilicon:plug/lens'),
        ):
            with self.subTest(recipe=relative):
                src = resolve_effective_source(layout, self.props, path_root + relative)
                self.assertIsNotNone(src)
                recipe = json.loads(src.read_text(encoding='utf-8'))
                self.assertEqual(recipe['type'], 'buildcraftsilicon:assembly')
                self.assertEqual(recipe['result']['item'], result)
                self.assertEqual(len(recipe['ingredients']),len(recipe['ingredient_counts']))
                self.assertGreater(recipe['MJ'],0)
        source = self.code(target,'buildcraft/lib/recipe/AssemblyRecipe.java')
        for field in ('"ingredients"','"ingredient_counts"','"result"','"MJ"'):
            self.assertIn(field,source)
        custom = self.code(target,'buildcraft/lib/recipe/BCLibIngredientTypes.java')
        self.assertIn('"strict_nbt"',custom)

    def test_1211_falls_back_to_sync_but_1211_api_not_used_on_1211_downport(self):
        old = self.code('1.21.1-neoforge', 'buildcraft/compat/jei/BuildCraftJeiPlugin.java')
        self.assertIn('.getAllRecipesFor(BCSiliconRecipes.ASSEMBLY_TYPE.get())', old)
        self.assertNotIn('setAssemblyRecipesChangedListener', old)

    def test_recipe_sync_real_source_compiles_and_notifies_both_orders(self):
        root = Path(self.temp.name) / 'sync-stubs'
        stubs = {
            'buildcraft/lib/recipe/AssemblyRecipeBasic.java': 'package buildcraft.lib.recipe; public class AssemblyRecipeBasic {}',
            'buildcraft/silicon/BCSilicon.java': 'package buildcraft.silicon; public class BCSilicon { public static final String MODID="buildcraftsilicon"; }',
            'buildcraft/silicon/BCSiliconRecipes.java': 'package buildcraft.silicon; import buildcraft.lib.recipe.AssemblyRecipeBasic; import net.minecraft.world.item.crafting.RecipeType; public class BCSiliconRecipes { public static final Entry<RecipeType<AssemblyRecipeBasic>> ASSEMBLY_TYPE = new Entry<>(new RecipeType<>()); public record Entry<T>(T obj){ public T get(){return obj;} }}',
            'net/minecraft/world/item/crafting/RecipeType.java': 'package net.minecraft.world.item.crafting; public class RecipeType<T> {}',
            'net/minecraft/world/item/crafting/RecipeHolder.java': 'package net.minecraft.world.item.crafting; public record RecipeHolder<T>(String id,T value) {}',
            'net/minecraft/world/item/crafting/RecipeMap.java': 'package net.minecraft.world.item.crafting; import java.util.*; public record RecipeMap(List<RecipeHolder<?>> entries){ @SuppressWarnings("unchecked") public <T> List<RecipeHolder<T>> byType(RecipeType<T> type){return (List)entries;} }',
            'net/neoforged/api/distmarker/Dist.java': 'package net.neoforged.api.distmarker; public enum Dist { CLIENT, DEDICATED_SERVER }',
            'net/neoforged/bus/api/EventPriority.java': 'package net.neoforged.bus.api; public enum EventPriority { NORMAL, HIGHEST }',
            'net/neoforged/bus/api/SubscribeEvent.java': 'package net.neoforged.bus.api; import java.lang.annotation.*; @Retention(RetentionPolicy.RUNTIME) public @interface SubscribeEvent { EventPriority priority() default EventPriority.NORMAL; }',
            'net/neoforged/fml/common/EventBusSubscriber.java': 'package net.neoforged.fml.common; import java.lang.annotation.*; import net.neoforged.api.distmarker.Dist; @Retention(RetentionPolicy.RUNTIME) public @interface EventBusSubscriber { String modid(); Dist value() default Dist.DEDICATED_SERVER; }',
            'net/neoforged/neoforge/event/OnDatapackSyncEvent.java': 'package net.neoforged.neoforge.event; import net.minecraft.world.item.crafting.RecipeType; public class OnDatapackSyncEvent{public RecipeType<?> sent; public void sendRecipes(RecipeType<?> type){sent=type;}}',
            'net/neoforged/neoforge/client/event/RecipesReceivedEvent.java': 'package net.neoforged.neoforge.client.event; import java.util.*; import net.minecraft.world.item.crafting.*; public record RecipesReceivedEvent(Set<RecipeType<?>> getRecipeTypes,RecipeMap getRecipeMap) {}',
            'net/neoforged/neoforge/client/event/ClientPlayerNetworkEvent.java': 'package net.neoforged.neoforge.client.event; public class ClientPlayerNetworkEvent { public static class LoggingOut{} }'
        }
        sources = [JEI_ASSEMBLY_SYNC_write(root, k, v) for k, v in stubs.items()]
        source = self.source('1.21.11-neoforge', 'buildcraft/silicon/BCSiliconRecipeSync.java')
        sources.append(source)
        main = '''import buildcraft.silicon.*;
import buildcraft.lib.recipe.AssemblyRecipeBasic;
import net.minecraft.world.item.crafting.*;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.client.event.*;
import java.util.*;
public class TestMain{
  static int changed;
  static void check(boolean b){if(!b)throw new AssertionError();}
  static RecipesReceivedEvent event(RecipeHolder<AssemblyRecipeBasic>... holders){return new RecipesReceivedEvent(Set.of(BCSiliconRecipes.ASSEMBLY_TYPE.get()),new RecipeMap(new ArrayList<>(List.of(holders))));}
  @SuppressWarnings("unchecked")
  public static void main(String[] args){
    check(BCSiliconRecipeSync.Client.assemblyRecipes().isEmpty());
    OnDatapackSyncEvent toSend=new OnDatapackSyncEvent();BCSiliconRecipeSync.onDatapackSync(toSend);
    check(toSend.sent==BCSiliconRecipes.ASSEMBLY_TYPE.get());
    var gate=new RecipeHolder<>("assembly/gate",new AssemblyRecipeBasic());
    var lens=new RecipeHolder<>("assembly/lens",new AssemblyRecipeBasic());
    // Recipes arrive before JEI runtime becomes available.
    BCSiliconRecipeSync.Client.onRecipes(event(gate,lens));
    check(BCSiliconRecipeSync.Client.assemblyRecipes().size()==2);
    BCSiliconRecipeSync.Client.setAssemblyRecipesChangedListener(()->changed++);
    check(changed==1); // listener immediately receives existing cache
    BCSiliconRecipeSync.Client.onRecipes(event(lens));
    check(changed==2 && BCSiliconRecipeSync.Client.assemblyRecipes().size()==1);
    BCSiliconRecipeSync.Client.onRecipes(event());
    check(changed==3 && BCSiliconRecipeSync.Client.assemblyRecipes().isEmpty());
    BCSiliconRecipeSync.Client.setAssemblyRecipesChangedListener(null);
    BCSiliconRecipeSync.Client.onRecipes(event(gate));
    check(changed==3);
    BCSiliconRecipeSync.Client.setAssemblyRecipesChangedListener(()->changed++);
    check(changed==4);
    BCSiliconRecipeSync.Client.onLogout(new ClientPlayerNetworkEvent.LoggingOut());
    check(changed==5 && BCSiliconRecipeSync.Client.assemblyRecipes().isEmpty());
    System.out.println("server recipe sync ordering and reload PASS");
  }
}
'''
        sources.append(JEI_ASSEMBLY_SYNC_write(root, 'TestMain.java', main))
        out = JEI_ASSEMBLY_SYNC_javaprobe(root, sources, 'TestMain')
        self.assertIn('PASS', out)



    def test_modern_jei_real_refresh_method_removes_stale_pages(self):
        # Compile the actual methods extracted from each maintained JEI plugin
        # against the runtime contracts. Exercise a late login, unchanged sync,
        # datapack replacement, empty reload, and runtime detach.
        for target in JEI_ASSEMBLY_SYNC_MODERN:
            with self.subTest(target=target):
                code = self.code(target, 'buildcraft/compat/jei/BuildCraftJeiPlugin.java')
                def method(signature):
                    start = code.index(signature)
                    end = code.index('\n    }', start) + len('\n    }')
                    return code[start:end]
                # The Assembly runtime probe intentionally models only the Assembly category.
                # The implementation must still reconcile every newly synced JEI category.
                self.assertIn('private static void refreshOtherRecipes(IJeiRuntime runtime)', code)
                for category in ('PROGRAMMING', 'INTEGRATION', 'DISTILLATION',
                                 'HEAT_EXCHANGE', 'COMBUSTION_FUEL'):
                    self.assertIn('reconcile(runtime, ' + category + ',', code)
                available = method('public void onRuntimeAvailable(IJeiRuntime runtime)')
                refresh = method('private static void refreshAssemblyRecipes()')
                unavailable = method('public void onRuntimeUnavailable()')
                available = available.replace('BuildCraftJeiPlugin::refreshAssemblyRecipes', 'JeiRuntimeProbe::refreshAssemblyRecipes')
                source = """import java.util.*;
public class JeiRuntimeProbe {
  interface IJeiRuntime { RecipeManager getRecipeManager(); }
  static class RecipeManager {
    List<AssemblyJeiRecipe> visible=new ArrayList<>();int added,hidden;
    void hideRecipes(Object type, Collection<AssemblyJeiRecipe> recipes){visible.removeAll(recipes); hidden+=recipes.size();}
    void addRecipes(Object type, List<AssemblyJeiRecipe> recipes){visible.addAll(recipes); added+=recipes.size();}
  }
  record AssemblyRecipeBasic(String name){}
  record RecipeHolder<T>(String id,T value){}
  record AssemblyJeiRecipe(String id,AssemblyRecipeBasic recipe){}
  static class BCSiliconRecipeSync {
    static class Client {
      private static List<RecipeHolder<AssemblyRecipeBasic>> recipes=List.of();private static Runnable listener;
      static List<RecipeHolder<AssemblyRecipeBasic>> assemblyRecipes(){return recipes;}
      static void setAssemblyRecipesChangedListener(Runnable next){listener=next;if(listener!=null)listener.run();}
      static void push(RecipeHolder<AssemblyRecipeBasic>... items){recipes=List.of(items);if(listener!=null)listener.run();}
    }
  }
  record RecipeGeneration(long id){}
  record RecipeSnapshot(RecipeGeneration generation){}
  static class MachineRecipeService {
    long generation;
    List<RecipeHolder<AssemblyRecipeBasic>> addonRecipes=List.of();
    RecipeSnapshot snapshot(){return new RecipeSnapshot(new RecipeGeneration(generation));}
    List<RecipeHolder<AssemblyRecipeBasic>> assemblyRecipes(){return addonRecipes;}
    @SafeVarargs final void publish(RecipeHolder<AssemblyRecipeBasic>... next){
      addonRecipes=List.of(next);generation++;
    }
  }
  static class BuildCraftServices { static final Object MACHINE_RECIPES=new Object(); }
  static class BuildCraftApi {
    static final MachineRecipeService MACHINE_RECIPES=new MachineRecipeService();
    static MachineRecipeService service(Object key){
      check(key==BuildCraftServices.MACHINE_RECIPES);return MACHINE_RECIPES;
    }
  }
  static class GroupedAssemblyRecipes { void clear() {} }
  static final Map<String,Integer> GROUPED_ASSEMBLY_RECIPES=new HashMap<>();
  static final Object ASSEMBLY=new Object();
  private static IJeiRuntime activeRuntime;
  private static List<RecipeHolder<AssemblyRecipeBasic>> publishedAssemblySource=List.of();
  private static List<AssemblyJeiRecipe> publishedAssemblyRecipes=List.of();
  private static long publishedAssemblyApiGeneration=-1;
  private static List<Object> publishedProgramming=List.of();
  private static List<Object> publishedIntegration=List.of();
  private static List<Object> publishedDistillation=List.of();
  private static List<Object> publishedHeatExchange=List.of();
  private static List<Object> publishedFuels=List.of();
  // The Assembly-only probe intentionally supplies a no-op for unrelated JEI recipe types.
  // Their production reconciliation contract is asserted against the real source above.
  private static void refreshOtherRecipes(IJeiRuntime runtime) {}
  static List<AssemblyJeiRecipe> collectAssemblyRecipes(List<RecipeHolder<AssemblyRecipeBasic>> source){
    List<AssemblyJeiRecipe> views=new ArrayList<>();
    for(var recipe:source)views.add(new AssemblyJeiRecipe(recipe.id(),recipe.value()));
    for(var recipe:BuildCraftApi.service(BuildCraftServices.MACHINE_RECIPES).assemblyRecipes())
      views.add(new AssemblyJeiRecipe(recipe.id(),recipe.value()));
    return List.copyOf(views);
  }
  public void register(){var source=BCSiliconRecipeSync.Client.assemblyRecipes();
    publishedAssemblySource=source;publishedAssemblyRecipes=collectAssemblyRecipes(source);
    publishedAssemblyApiGeneration=BuildCraftApi.service(BuildCraftServices.MACHINE_RECIPES).snapshot().generation().id();}
  AVAILABLE
  REFRESH
  UNAVAILABLE
  static void check(boolean value){if(!value)throw new AssertionError();}
  public static void main(String[] args){
    var probe=new JeiRuntimeProbe();var manager=new RecipeManager();IJeiRuntime rt=()->manager;
    probe.register();probe.onRuntimeAvailable(rt);
    check(manager.added==0 && manager.hidden==0);
    var gate=new RecipeHolder<>("gate",new AssemblyRecipeBasic("and"));
    var lens=new RecipeHolder<>("lens",new AssemblyRecipeBasic("red"));
    BCSiliconRecipeSync.Client.push(gate,lens);
    check(manager.visible.size()==2 && manager.added==2);
    BCSiliconRecipeSync.Client.push(gate,lens); // unchanged snapshot: no duplicates
    check(manager.added==2 && manager.hidden==0);
    var addon=new RecipeHolder<>("addon/board",new AssemblyRecipeBasic("addon"));
    BuildCraftApi.MACHINE_RECIPES.publish(addon);
    BCSiliconRecipeSync.Client.push(gate,lens); // same vanilla recipes, new API generation
    check(manager.visible.size()==3 && manager.visible.stream().anyMatch(r->r.id().equals("addon/board")));
    check(manager.added==5 && manager.hidden==2);
    BCSiliconRecipeSync.Client.push(gate,lens); // same generation must not re-register recipes
    check(manager.added==5 && manager.hidden==2);
    BuildCraftApi.MACHINE_RECIPES.publish(); // remove the addon-only recipe
    BCSiliconRecipeSync.Client.push(gate,lens);
    check(manager.visible.size()==2 && manager.visible.stream().noneMatch(r->r.id().equals("addon/board")));
    check(manager.added==7 && manager.hidden==5);
    BCSiliconRecipeSync.Client.push(gate); // reload replaces synced recipes
    check(manager.visible.size()==1 && manager.visible.get(0).id().equals("gate"));
    check(manager.added==8 && manager.hidden==7);
    BCSiliconRecipeSync.Client.push(); // empty reload or server leaves world
    check(manager.visible.isEmpty() && manager.hidden==8);
    probe.onRuntimeUnavailable();
    check(publishedAssemblyApiGeneration==-1);
    BCSiliconRecipeSync.Client.push(lens);
    check(manager.visible.isEmpty() && manager.added==8); // detached from JEI
    System.out.println("JEI live registration and reload PASS");
  }
}
"""
                source = source.replace('  AVAILABLE', available).replace('  REFRESH', refresh).replace('  UNAVAILABLE', unavailable)
                root = Path(self.temp.name) / ('jei-stubs-' + target)
                result=JEI_ASSEMBLY_SYNC_javaprobe(root, [JEI_ASSEMBLY_SYNC_write(root,'JeiRuntimeProbe.java',source)], 'JeiRuntimeProbe')
                self.assertIn('PASS', result)


if __name__ == '__main__':
    unittest.main()
