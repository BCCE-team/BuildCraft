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
from source_lookup import resolve_target_source
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
        # The combustion engine's former 26,18,16,60 click area is the actual fuel tank.
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
        old_pipe = resolve_target_source('1.19.2-forge', 'src/main/java/buildcraft/transport/block/BlockPipeHolder.java').read_text()
        mid_pipe = resolve_target_source('1.20.1-forge', 'src/main/java/buildcraft/transport/block/BlockPipeHolder.java').read_text()

        # Preserve vanilla glass texture alpha exactly; multiplying it again makes facades almost invisible.
        self.assertIn('GLASS_FACADE_ALPHA = 1.0D', current_baker)
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

    def test_runtime_gui_regressions_found_on_12111(self):
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

        # The modern facade dynamic renderer must keep the baker path, while the baker preserves source glass alpha.
        facade = (current_root / 'buildcraft/silicon/client/render/PlugFacadeRenderer.java').read_text()
        self.assertIn('bakeForKey(modelKey, true)', facade)
        self.assertNotIn('bakeForKey(modelKey, false)', facade)
        silicon_models = (current_root / 'buildcraft/silicon/BCSiliconModels.java').read_text()
        self.assertIn('registry.registerRenderer(PluggableFacade.class, PlugFacadeRenderer.INSTANCE)', silicon_models)

        # The holder's collision shape can report its pipe rather than the visible facade. Paint therefore resolves
        # the facade from its actual shape at the hit position and must not fall through to the pipe.
        for root in (old_root, current_root):
            pipe_holder = (root / 'buildcraft/transport/block/BlockPipeHolder.java').read_text()
            paint = pipe_holder[pipe_holder.index('InteractionResult attemptPaint'):]
            self.assertIn('Direction facadeSide = getFacadeSideAt(tile, pos, hitPos)', paint)
            self.assertIn('pluggable.getBoundingBox().bounds().contains(localHit)', paint)
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
        self.assertIn('states.type == FacadeType.Basic && enableStainedGlassPhases()', facade_state)
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

        # The native 1.21.11 terrain model must not bake glass facades: that pass loses their alpha. Glass is
        # deliberately left for RenderPipeHolder's dynamic translucent renderer.
        native_model = (current_root / 'buildcraft/transport/client/model/ModelPipeNative121111.java').read_text()
        cache = (current_root / 'buildcraft/transport/client/model/PipeModelCachePluggable.java').read_text()
        self.assertIn('ModelPipeNative121111::isNativeStaticPluggable', native_model)
        self.assertIn('PluggableFacade.isGlass(', native_model)
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

    def test_12111_jade_server_and_client_providers_are_separate(self):
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

    def test_12111_jade_multimod_plugin_scan_is_deduplicated(self):
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


if __name__ == '__main__':
    unittest.main()
