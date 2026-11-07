#!/usr/bin/env python3
"""GUI regression tests on the real materialized modern source sets.

The main GUI suite targets 1.21.11, while target-specific regressions that belong
to the same UI failure domain (currently the 26.1.2 Filler binding check) live here
instead of as unreferenced standalone test files.

Run: python3 -m unittest discover -s scripts/tests -p test_gui_regressions.py -v
Java probes use small API-contract fixtures to test our own logic without game dependencies.
They do not claim binary API compatibility or a client/server Minecraft test. Gradle remains required.
"""
from __future__ import annotations
import importlib.util
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from gui_render_fixture import STUBS, PROBE

ROOT = Path(__file__).resolve().parents[2]
PREFIX = 'buildcraft/'
COLOURS = 'white orange magenta light_blue yellow lime pink gray light_gray cyan purple blue brown green red black'.split()


def load_layout():
    spec = importlib.util.spec_from_file_location('bc_gui_source_layout', ROOT/'scripts/source_layout.py')
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def java_method(source, signature):
    """Extract balanced body; all selected methods have no literal braces in strings/comments."""
    start = source.index(signature)
    brace = source.index('{', start)
    level = 1
    i = brace + 1
    while level:
        if source[i] == '{': level += 1
        elif source[i] == '}': level -= 1
        i += 1
    return source[start:i]


def clean_java(text):
    return re.sub(r'/\*.*?\*/|//[^\n]*', '', text, flags=re.S)


class GuiRegressions(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='bc-gui-regression-')
        cls.work = Path(cls.temp.name)
        cls.layout = load_layout()
        props = cls.layout.load_properties(ROOT/'builds/1.21.X/targets.properties')
        cls.layout.materialize_target('1.21.11-neoforge', cls.work/'effective', props)
        cls.java = cls.work/'effective/src/main/java'
        cls.resources = cls.work/'effective/src/main/resources'
        cls.lang = json.loads((cls.resources/'assets/buildcraft/lang/en_us.json').read_text())

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def src(self, path):
        return (self.java/PREFIX/path).read_text()

    def test_escape_reaches_close_before_search(self):
        guide = self.src('lib/client/guide/GuiGuide.java')
        method = java_method(guide, 'public boolean keyPressed(KeyEvent event)')
        self.assertIn('event.key() == InputConstants.KEY_ESCAPE', method)
        self.assertLess(method.index('onClose();'), method.index('keyPressed(event.key(),'))
        self.assertIn('super.keyPressed(event)', method)
        self.assertIn('persistGuideState();', java_method(guide, 'public void removed()'))

    def test_native_sprite_queue_not_inert_gl_shim(self):
        sprite = self.src('lib/client/sprite/SpriteNineSliced.java')
        draw = self.src('lib/client/sprite/GuiSpriteRender121111.java')
        for forbidden in ['BufferUploader', 'Tesselator', 'RenderSystem.', 'getShaderColor', 'RenderCompat.pose', 'PoseStack']:
            self.assertNotIn(forbidden, sprite)
        for required in ['submitGuiElementRenderState', 'new Matrix3x2f(graphics.pose())',
                         'graphics.peekScissorStack()', 'TextureSetup.singleTexture', '.setColor(argb)']:
            self.assertIn(required, draw)
        icon = java_method(self.src('lib/gui/GuiIcon.java'), 'private static void drawSprite(')
        self.assertIn('GuiSpriteRender121111.draw', icon)
        self.assertNotIn('* textureSize', icon)

    def test_actual_ledger_targets_use_explicit_tint_and_scissor(self):
        ledger = self.src('lib/gui/ledger/Ledger_Neptune.java')
        self.assertIn('interpWidth, interpHeight, colour)', ledger)
        self.assertEqual(2, ledger.count('guiGraphics.enableScissor('))
        self.assertEqual(2, ledger.count('guiGraphics.disableScissor()'))
        self.assertNotIn('GuiUtil.scissor(', ledger)
        self.assertNotIn('setGLColor', ledger)
        help = self.src('lib/gui/ledger/LedgerHelp.java')
        self.assertIn('split.draw(guiGraphics, rect, 0xFF000000', help)
        self.assertIn('info.info.addGuiElements(container)', help)
        self.assertIn('gui.ledger.help.hint', help)
        self.assertIn('gui.ledger.help.hint', self.lang)
        self.assertEqual(2, self.src('lib/gui/elem/GuiElementText.java').count('opaqueRgb(colourValue)'))
        self.assertIn('opaqueRgb(colour)', self.src('lib/misc/GuiUtil.java'))

    def test_owner_read_is_not_a_claim_and_writes_are_server_dirty(self):
        tile = self.src('lib/tile/TileBC_Neptune.java')
        getter = java_method(tile, 'public GameProfile getOwner()')
        self.assertNotIn('owner = FakePlayerProvider', getter)
        for sig in ['public void onPlacedBy(', 'public void onPlayerOpen(']:
            method = java_method(tile, sig)
            self.assertIn('level.isClientSide()', method)
            self.assertRegex(method, r'owner = profile;\s*setChanged\(\);')
        self.assertIn('output.put("owner", writeGameProfile(owner))', java_method(tile, 'protected void writeCommonData('))
        self.assertIn('map(TileBC_Neptune::readGameProfile).orElse(null)', tile)
        self.assertIn('MessageUtil.writeGameProfile(buffer, owner)', tile)
        self.assertIn('owner = MessageUtil.readGameProfile(buffer)', tile)
        ownership = self.src('lib/gui/ledger/LedgerOwnership.java')
        self.assertNotIn('tile.getOwner()', ownership)
        self.assertIn('getKnownOwner()', ownership)
        self.assertIn('lastOwnerText', ownership)

    def test_skin_not_stubbed_and_lookup_keeps_async_supplier(self):
        skin = self.src('lib/misc/SpriteUtil.java')
        self.assertNotIn('getSkinSpriteLocation0', skin)
        self.assertIn('mc.player.getSkin().body().texturePath()', skin)
        self.assertIn('getPlayerInfo(profile.id())', skin)
        self.assertIn('createLookup(resolved, true)', skin)
        self.assertIn('resolver.fetchById(profile.id())', skin)
        self.assertIn('lookup.getNow(fallback).get()', skin)
        self.assertIn('Util.nonCriticalIoPool()', skin)
        self.assertIn('SKINS.getUnchecked(profile).get()', skin)
        self.assertIn('maximumSize(256)', skin)
        self.assertIn('DefaultPlayerSkin.get(profile)', skin)

    def test_every_audited_key_exists_verbatim(self):
        data = json.loads((ROOT/'scripts/tests/item_names_1.21.11.json').read_text())
        mapping = dict(re.findall(r'case "([^"]+)" -> "([^"]+)";',self.src('lib/compat/ItemNameKeys121111.java')))
        self.assertEqual(len(data),len(mapping))
        for item in data:
            with self.subTest(item=item['id']):
                self.assertEqual(item['key'], mapping[item['id']])
                self.assertIn(item['key'],self.lang)
        # Dynamic stack names: all lens/filter colours, all robot and redstone-board types, gate fragments.
        for kind in ['lens','filter']:
            for colour in ['clear', *COLOURS]:self.assertIn('item.'+kind+'.'+colour,self.lang)
        boards = re.findall(r'board\("[^"]+", "([^"]+)"',self.src('robotics/BCRoboticsBoards.java'))
        self.assertEqual(18,len(boards))
        for kind in ['robot','redstone_board']:
            for board in boards:self.assertIn('item.buildcraftrobotics.'+kind+'.'+board,self.lang)
        for path in ['silicon/item/ItemPluggableLens.java', 'silicon/item/ItemRedstoneChipset.java','builders/item/ItemSnapshot.java']:
            self.assertIn('public net.minecraft.network.chat.Component getName(ItemStack stack)',self.src(path))
            self.assertIn('translatable(getDescriptionId(stack))',self.src(path))
        self.assertIn('return super.getName(p_41458_);',self.src('lib/item/MultiBlockItem.java'))
        for material in ['iron', 'nether_brick', 'gold']:self.assertIn('gate.material.'+material, self.lang)
        for logic in ['and', 'or']:self.assertIn('gate.logic.'+logic, self.lang)
        for key in ['gate.name','gate.name.basic','item.buildcraftsilicon.facade.named','item.buildcraftbuilders.blueprint','item.buildcraftbuilders.template']:
            self.assertIn(key,self.lang)

    def test_mapping_covers_actual_registrations_not_model_filenames(self):
        mapping = dict(re.findall(r'case "([^"]+)" -> "([^"]+)";',self.src('lib/compat/ItemNameKeys121111.java')))
        expected = set()
        for path in self.java.rglob('*.java'):
            t = clean_java(path.read_text())
            for reg, ident in re.findall(r'RegistryCompat.registerItem\(\s*([^,]+),\s*"([^"]+)"\s*,',t):
                ns = 'buildcraft'+path.relative_to(self.java).parts[1]
                if reg == 'BCEnergy.ITEMS':ns='buildcraftenergy'
                expected.add(ns+':'+ident)
        for c in COLOURS:
            expected.add('buildcraftcore:paintbrush/'+c)
            expected.add('buildcrafttransport:wire/'+c)
        enum = self.src('lib/internal/enums/EnumRedstoneChipset.java').split('implements StringRepresentable {',1)[1].split(';',1)[0]
        for c in re.findall(r'\b[A-Z][A-Z_]+\b',enum):expected.add('buildcraftsilicon:redstone_chipset/'+c.lower())
        pipes=clean_java(self.src('transport/BCTransportPipes.java'))
        for ident in re.findall(r'\.idTex(?:Prefix)?\("([^"]+)"\)',pipes):expected.add('buildcrafttransport:'+ident)
        fluids = self.src('energy/BCEnergyFluids.java')
        names = re.findall(r'"([^"]+)"',re.search(r'String\[\] NAME\s*=\s*\{(.*?)\}',fluids,re.S)[1])
        heats = re.findall(r'"([^"]+)"',re.search(r'String\[\] HEAT_NAMES\s*=\s*\{(.*?)\}',fluids,re.S)[1])
        for name in names:
            for heat in heats:expected.add('buildcraftenergy:'+name+'/'+heat+'_bucket')
        self.assertEqual(expected,set(mapping), 'Registry coverage changed; update key audit explicitly')
        registration = self.src('lib/compat/RegistryCompat.java')
        self.assertIn('return BCRegistrationScope.itemProperties(properties)', registration)
        binding = self.src('lib/compat/minecraft/registry/BCRegistrationScope.java')
        self.assertIn('properties.overrideDescription(name)', binding)
        self.assertIn('ItemNameKeys121111.key(id.identifier())', binding)

    @unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'JDK 21 required for executable probes')
    def test_real_java_logic_with_headless_contract_fixtures(self):
        probe_root = self.work/'probe'
        for rel, text in STUBS.items():
            path=probe_root/rel;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(text)
        for rel in ['lib/client/sprite/GuiSpriteRender121111.java','lib/client/sprite/SpriteNineSliced.java','lib/compat/ItemNameKeys121111.java']:
            path=probe_root/PREFIX/rel;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(self.src(rel))
        guide = java_method(self.src('lib/client/guide/GuiGuide.java'), 'public boolean keyPressed(KeyEvent event)')
        tile = self.src('lib/tile/TileBC_Neptune.java')
        owner = '\n'.join(java_method(tile,sig) for sig in ['public void onPlacedBy(', 'public void onPlayerOpen(',
            'public GameProfile getKnownOwner()', 'public GameProfile getOwner()',
            'private static GameProfile readGameProfile(', 'private static CompoundTag writeGameProfile('])
        (probe_root/'GuiPortProbe.java').write_text(PROBE.replace('/* GUIDE_METHOD */',guide).replace('/* OWNER_METHODS */',owner))
        paths=[str(p) for p in probe_root.rglob('*.java')]
        proc=subprocess.run(['javac','--release','21','-d',str(probe_root/'classes'),*paths],capture_output=True,text=True,timeout=30)
        self.assertEqual(0,proc.returncode,proc.stdout+proc.stderr)
        proc=subprocess.run(['java','-ea','-cp',str(probe_root/'classes'),'GuiPortProbe'],capture_output=True,text=True,timeout=15)
        self.assertEqual(0,proc.returncode,proc.stdout+proc.stderr)
        print(proc.stdout.strip())


class FillerGui26_1_2Regressions(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='bc-26.1.2-filler-gui-')
        cls.work = Path(cls.temp.name)
        layout = load_layout()
        props = layout.load_properties(ROOT/'builds/26.X/targets.properties')
        layout.materialize_target('26.1.2-neoforge', cls.work/'effective', props)
        cls.java = cls.work/'effective/src/main/java'
        cls.resources = cls.work/'effective/src/main/resources'

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def test_player_inventory_is_bound_before_json_deserialization(self):
        gui = (self.java/'buildcraft/builders/gui/GuiFiller.java').read_text(encoding='utf-8')
        resources = (
            self.resources/'assets/buildcraftbuilders/gui/filler.json'
        ).read_text(encoding='utf-8')

        binding = 'properties.put("player.inventory", new InventorySlotHolder(container, container.playerInventory));'
        self.assertIn('"slot": "player.inventory"', resources)
        constructor = gui[gui.index('public GuiFiller('):gui.index('protected void preLoad(')]
        pre_load_start = gui.index('protected void preLoad(')
        pre_load = gui[pre_load_start:gui.index('public void containerTick()', pre_load_start)]

        self.assertIn(binding, pre_load)
        self.assertLess(constructor.index('preLoad(jsonGui);'), constructor.index('jsonGui.load();'))



class TankGuiPackedDataRegressions(unittest.TestCase):
    """The tank's custom 32-bit network packet must match TankComponent's 16-bit GUI slots."""

    TARGETS = (
        '1.19.2-forge', '1.20.1-forge', '1.21.1-neoforge', '1.21.11-neoforge',
        '26.1.2-neoforge', '26.2-neoforge', '26.3-neoforge',
    )
    MENU = 'src/main/java/buildcraft/factory/container/ContainerTank.java'
    SCREEN = 'src/main/java/buildcraft/factory/gui/GuiTank.java'
    PACKED = 'src/main/java/buildcraft/lib/gui/TankContainerData.java'

    def test_every_target_selects_matching_packed_layout(self):
        layout = load_layout()
        props = layout.load_properties()
        for target in self.TARGETS:
            with self.subTest(target=target):
                selection = layout.target_layout(target, props)
                menu = layout.resolve_effective_source(selection, props, self.MENU)
                screen = layout.resolve_effective_source(selection, props, self.SCREEN)
                packed = layout.resolve_effective_source(selection, props, self.PACKED)
                self.assertIsNotNone(menu)
                self.assertIsNotNone(screen)
                self.assertIsNotNone(packed)
                menu_source = menu.read_text(encoding='utf-8')
                screen_source = screen.read_text(encoding='utf-8')
                self.assertIn('return TankContainerData.LEN;', menu_source)
                self.assertIn('case 1, 3 -> syncedFluidAmount;', menu_source)
                self.assertIn('case 4 -> syncedCapacity;', menu_source)
                self.assertIn('case 5, 6 -> -1;', menu_source)
                self.assertRegex(screen_source, r'176,\s*0,\s*8\s*// capacity: fifth 32-bit field')
                self.assertIn('getPacked(data, offset + 2)',
                              layout.resolve_effective_source(selection, props,
                                  'src/main/java/buildcraft/lib/gui/component/TankComponent.java')
                                  .read_text(encoding='utf-8'))

    @unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'JDK required for live Java slot probes')
    def test_real_java_gui_slot_mirror_roundtrips_zero_and_32bit_values(self):
        """Compile the actual nested menu data view and TankContainerData.getPacked, not a Python copy."""
        layout = load_layout()
        props = layout.load_properties()
        menus = {}
        for target in self.TARGETS:
            selection = layout.target_layout(target, props)
            menu = layout.resolve_effective_source(selection, props, self.MENU)
            packed = layout.resolve_effective_source(selection, props, self.PACKED)
            menus[(str(menu), str(packed))] = target
        self.assertEqual(4, len(menus), 'Unexpected new tank-menu/packed-reader combination; audit its GUI slots')
        for (menu_path, packed_path), target in menus.items():
            with self.subTest(implementation=target):
                inner = java_method(Path(menu_path).read_text(encoding='utf-8'),
                                    'private final class SyncedTankData implements ContainerData')
                reader = java_method(Path(packed_path).read_text(encoding='utf-8'),
                                     'public static int getPacked(ContainerData data, int lowWordIndex)')
                probe = r'''
interface ContainerData {
    int get(int index);
    void set(int index, int value);
    int getCount();
}
final class TankContainerData {
    static final int LEN = 14;
    /* READ_PACKED */
}
public final class ContainerTank {
    private int syncedFluidId;
    private int syncedFluidAmount;
    private int syncedCapacity;
    /* GUI_DATA */

    private void updateLocalState(int fluidId, int amount, int capacity) {
        syncedFluidId = Math.max(0, fluidId);
        syncedFluidAmount = Math.max(0, amount);
        syncedCapacity = Math.max(0, capacity);
    }
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    private void checkLayout(int fluidId, int amount, int capacity) {
        updateLocalState(fluidId, amount, capacity);
        ContainerData gui = new SyncedTankData();
        check(gui.getCount() == 14, "14 slots for fluid, render fluid, capacity, and cache IDs");
        check(TankContainerData.getPacked(gui, 0) == fluidId, "fluid type ID");
        check(TankContainerData.getPacked(gui, 2) == amount, "fluid amount (not capacity)");
        check(TankContainerData.getPacked(gui, 4) == fluidId, "render fluid type ID");
        check(TankContainerData.getPacked(gui, 6) == amount, "render fluid amount");
        check(TankContainerData.getPacked(gui, 8) == capacity, "tank capacity");
        check(TankContainerData.getPacked(gui, 10) == -1, "no fabricated server cache entry");
        check(TankContainerData.getPacked(gui, 12) == -1, "no fabricated render cache entry");
        gui.set(2, 12345); // GUI must not override packet-authoritative state
        check(TankContainerData.getPacked(gui, 2) == amount, "read-only GUI state");
    }
    public static void main(String[] args) {
        ContainerTank tank = new ContainerTank();
        tank.checkLayout(0, 0, 16000);        // empty must not show 16 buckets of air
        tank.checkLayout(1, 500, 16000);      // partially filled tank
        tank.checkLayout(3, 16000, 16000);    // exactly full tank
        tank.checkLayout(77, 65539, 125000);  // values crossing signed 16-bit boundaries
        tank.checkLayout(171, 200000, 1000000); // large multiblock capacity
        tank.checkLayout(0, 0, 16000);        // drain/reopen must not retain previous content
        System.out.println("Tank GUI packed data: 6 state transitions PASS");
    }
}
'''.replace('/* READ_PACKED */', reader).replace('/* GUI_DATA */', inner)
                with tempfile.TemporaryDirectory(prefix='bc-tank-gui-slot-probe-') as tmp:
                    path = Path(tmp) / 'ContainerTank.java'
                    path.write_text(probe, encoding='utf-8')
                    compiled = subprocess.run(['javac', '--release', '17', str(path)],
                                              cwd=tmp, capture_output=True, text=True, timeout=30)
                    self.assertEqual(0, compiled.returncode, compiled.stdout + compiled.stderr)
                    executed = subprocess.run(['java', '-ea', '-cp', tmp, 'ContainerTank'],
                                              cwd=tmp, capture_output=True, text=True, timeout=15)
                    self.assertEqual(0, executed.returncode, executed.stdout + executed.stderr)
                    self.assertIn('6 state transitions PASS', executed.stdout)

class ElectronicLibraryScrollingRegressions(unittest.TestCase):
    # The viewport must scroll by snapshot keys, not by names or old row positions.

    TARGETS = (
        '1.19.2-forge', '1.20.1-forge', '1.21.1-neoforge', '1.21.11-neoforge',
        '26.1.2-neoforge', '26.2-neoforge', '26.3-neoforge',
    )
    SCREEN = 'src/main/java/buildcraft/builders/gui/GuiElectronicLibrary.java'
    VIEWPORT = 'src/main/java/buildcraft/builders/gui/ElectronicLibraryViewport.java'

    def test_all_targets_use_the_same_scroll_index_for_display_and_click(self):
        layout = load_layout()
        props = layout.load_properties()
        for target in self.TARGETS:
            with self.subTest(target=target):
                selection = layout.target_layout(target, props)
                path = layout.resolve_effective_source(selection, props, self.SCREEN)
                helper = layout.resolve_effective_source(selection, props, self.VIEWPORT)
                self.assertIsNotNone(path)
                self.assertIsNotNone(helper)
                source = path.read_text(encoding='utf-8')
                self.assertIn('viewport.firstIndex(entries)', source)
                self.assertIn('viewport.entryAtRow(entries, row)', source)
                self.assertIn('iterateSnapshots(entries,', source)
                self.assertIn('viewport.scrollBy(GlobalSavedDataSnapshots.getClientSnapshotList()', source)
                self.assertIn('viewport.scrollToFraction(entries,', source)
                self.assertIn('draggingScrollbar = true;', source)
                self.assertIn('container.sendSelectedToServer(key);', source)
                self.assertIn('fitSnapshotName(text, LIST_TEXT_WIDTH)', source)
                self.assertIn('entries.contains(container.tile.selected)', source)
                self.assertNotIn('Math.min(list.size(), MAX_VISIBLE_SNAPSHOTS)', source)
                self.assertNotIn('rect.contains(mainGui.mouse)', source)
                self.assertEqual(1, source.count('private void drawScrollbar('))
                self.assertEqual(1, source.count('public boolean mouseScrolled('))

    def test_hover_resolves_scrolled_row_and_uses_inventory_blueprint_preview(self):
        layout = load_layout()
        props = layout.load_properties()
        for target in self.TARGETS:
            with self.subTest(target=target):
                selection = layout.target_layout(target, props)
                path = layout.resolve_effective_source(selection, props, self.SCREEN)
                item = layout.resolve_effective_source(selection, props,
                    'src/main/java/buildcraft/builders/item/ItemSnapshot.java')
                helper = layout.resolve_effective_source(selection, props,
                    'src/main/java/buildcraft/builders/gui/ElectronicLibraryEntryTooltip.java')
                self.assertTrue(path and item and helper)
                screen = path.read_text(encoding='utf-8')
                item_source = item.read_text(encoding='utf-8')
                mapping = helper.read_text(encoding='utf-8')
                self.assertIn('ElectronicLibraryEntryTooltip.displayName(key)', screen)
                self.assertNotIn('header == null ? key.toString() : header.name', screen)
                self.assertIn('viewport.entryAtRow(entries, row)', java_method(screen,
                    'private Snapshot.Key hoveredEntry('))
                self.assertIn('mouseX >= listLeft() + LIST_TEXT_WIDTH + 1', screen)
                self.assertIn('draggingScrollbar || !insideList(mouseX, mouseY)', screen)
                hover = java_method(screen, 'private void renderLibraryEntryTooltip(')
                # Named and unnamed entries share one short tooltip, never the full ItemStack text.
                self.assertIn('ElectronicLibraryEntryTooltip.compactLines(key)', hover)
                self.assertNotIn('ElectronicLibraryEntryTooltip.inventoryItem(', hover)
                self.assertNotIn('GuiUtil.getUnFormattedTooltip(', hover)
                self.assertNotIn('ElectronicLibraryEntryTooltip.fallbackLines(key)', hover)
                self.assertIn('ItemSnapshot.getUsed(snapshot.getType(), header)', mapping)
                self.assertIn('Component.translatable("item.blueprint.unnamed")', mapping)
                self.assertIn('"item.buildcraftbuilders.snapshot.hash"', mapping)
                self.assertIn('HashUtil.convertHashToString(header.key.hash)', item_source)
                self.assertLess(item_source.index('"item.buildcraftbuilders.snapshot.hash"'),
                    item_source.index('if (flag.isAdvanced())'))
                self.assertIn('renderLibraryEntryTooltip(', screen)
                if target in ('1.19.2-forge', '1.20.1-forge'):
                    self.assertIn('LegacyBlueprintGuiRenderer.render(', screen)
                else:
                    self.assertIn('BlueprintRenderer.renderSnapshot(', screen)
                if target.startswith('26.'):
                    self.assertIn('RenderCompat.renderComponentTooltip(graphics, font,', hover)
                    self.assertIn('List<Component> tooltipLines = ElectronicLibraryEntryTooltip.compactLines(key);', hover)
                elif target.startswith('1.19.'):
                    self.assertIn('renderComponentTooltip(pose, ElectronicLibraryEntryTooltip.compactLines(key),', hover)
                elif target.startswith('1.21.'):
                    # RenderCompat is selected only for >=1.21.11; both 1.21.X
                    # targets must call the family-neutral tooltip boundary.
                    self.assertIn('import buildcraft.lib.compat.minecraft.gui.BCGuiTooltip;', screen)
                    self.assertIn('BCGuiTooltip.components(graphics, font, ElectronicLibraryEntryTooltip.compactLines(key), mouseX, mouseY);', hover)
                    self.assertNotIn('graphics.renderComponentTooltip(', hover)
                    self.assertNotIn('RenderCompat.renderComponentTooltip(', hover)
                    compat = layout.resolve_effective_source(selection, props,
                        'src/main/java/buildcraft/lib/compat/minecraft/gui/BCGuiTooltip.java')
                    self.assertIsNotNone(compat)
                    adapter = compat.read_text(encoding='utf-8')
                    self.assertIn('public static void components(', adapter)
                    if target == '1.21.11-neoforge':
                        self.assertIn('graphics.setComponentTooltipForNextFrame(', adapter)
                    else:
                        self.assertIn('graphics.renderComponentTooltip(', adapter)
                else:
                    self.assertIn('graphics.renderComponentTooltip(font, ElectronicLibraryEntryTooltip.compactLines(key),', hover)

    def test_library_snapshot_names_survive_copy_and_client_storage_all_targets(self):
        # The library obtains its keys from copies of snapshots read from disk.
        # A copy must not discard the Header that owns the user-supplied name.
        layout = load_layout()
        props = layout.load_properties()
        for target in self.TARGETS:
            with self.subTest(target=target):
                selection = layout.target_layout(target, props)
                for relative, instance in (
                    ('src/main/java/buildcraft/builders/snapshot/Template.java', 'template'),
                    ('src/main/java/buildcraft/builders/snapshot/Blueprint.java', 'blueprint'),
                ):
                    file = layout.resolve_effective_source(selection, props, relative)
                    self.assertIsNotNone(file, (target, relative))
                    method = java_method(file.read_text(encoding='utf-8'),
                        f'public {instance.capitalize()} copy(')
                    self.assertIn(f'{instance}.computeKey();', method)
                    self.assertIn(f'{instance}.key = new Snapshot.Key({instance}.key, key.header);', method)
                storage = layout.resolve_effective_source(selection, props,
                    'src/main/java/buildcraft/builders/snapshot/GlobalSavedDataSnapshots.java')
                self.assertIsNotNone(storage, target)
                contents = storage.read_text(encoding='utf-8')
                validator = java_method(contents, 'private static Snapshot validatedCopy(')
                self.assertIn('Snapshot.Header header = snapshot.key.header;', validator)
                self.assertIn('new Snapshot.Key(copy.key, header)', validator)
                self.assertIn('sameContentKey(header.key, copy.key)', validator)
                writer = java_method(contents, 'private void writeClientSnapshot(')
                self.assertIn('contentMatch.getLeft().key.header == null', writer)
                self.assertIn('writeSnapshotFile(contentMatch.getRight(), checked)', writer)
                self.assertIn('invalidate(checked.key);', writer)

    @unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'JDK required for snapshot metadata probe')
    def test_real_java_snapshot_copy_cache_and_library_name(self):
        # Execute the real copy() and validatedCopy() bodies with small Minecraft API-contract fixtures.
        # The prior bug caused the Header to disappear on both copies and disk reads.
        folder_root = ROOT
        template = java_method((folder_root/'source-shared/src/main/java/buildcraft/builders/snapshot/Template.java')
                               .read_text(encoding='utf-8'), 'public Template copy(')
        blueprint = java_method((folder_root/'source-platforms/neoforge/src/main/java/buildcraft/builders/snapshot/Blueprint.java')
                                .read_text(encoding='utf-8'), 'public Blueprint copy(')
        validated = java_method((folder_root/'source-platforms/neoforge/src/main/java/buildcraft/builders/snapshot/GlobalSavedDataSnapshots.java')
                                 .read_text(encoding='utf-8'), 'private static Snapshot validatedCopy(')
        fixture = r'''package buildcraft.builders.snapshot;
import java.util.*;
public abstract class Snapshot {
    public Key key = new Key(new byte[] {1, 2, 3}, null);
    public String size = "size", facing = "north", offset = "origin";
    public abstract Snapshot copy();
    public String getType() { return "BLUEPRINT"; }
    public void computeKey() { key = new Key(key, new byte[] {1, 2, 3}); }
    public static final class Key {
        public final byte[] hash;
        public final Header header;
        public Key(byte[] hash, Header header) { this.hash=hash;this.header=header; }
        public Key(Key old, byte[] hash) { this(hash,old.header); }
        public Key(Key old, Header header) { this(old.hash,header); }
        public String toString() { return "abc123"; }
    }
    public static final class Header {
        public final Key key;
        public final String name;
        public Header(Key key, String name) { this.key=key;this.name=name; }
    }
}
class Template extends Snapshot {
    java.util.BitSet data = new java.util.BitSet();
    @Override
''' + template + '''
}
class Blueprint extends Snapshot {
    java.util.List<String> palette = new java.util.ArrayList<>();
    int[] data = new int[1];
    java.util.List<String> entities = new java.util.ArrayList<>();
    @Override
''' + blueprint + '''
}
class BCLog {
    static final Logger logger = new Logger();
    static class Logger { void warn(String message, Throwable throwable) { throw new AssertionError(message, throwable); } }
}
class GlobalSavedDataSnapshots {
    static boolean sameContentKey(Snapshot.Key a, Snapshot.Key b) {
        return a != null && b != null && java.util.Arrays.equals(a.hash,b.hash);
    }
    static Snapshot validate(Snapshot snapshot) { return validatedCopy(snapshot); }
''' + validated + '''
}
'''
        probe = r'''package buildcraft.builders.snapshot;
import buildcraft.builders.gui.ElectronicLibraryEntryTooltip;
import java.util.*;
public class HeaderCopyProbe {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static void checkNamed(Snapshot input, boolean headerPreservingCopy) {
        Snapshot.Key content = new Snapshot.Key(new byte[]{1,2,3}, null);
        Snapshot.Header header = new Snapshot.Header(content, "Named library blueprint");
        input.key = new Snapshot.Key(content, header);
        Snapshot copy = input.copy();
        check(copy.key.header == header || !headerPreservingCopy, "copy must retain user-visible name");
        Snapshot saved = GlobalSavedDataSnapshots.validate(input);
        check(saved != null && saved.key.header == header, "validated file read must retain name");
        check(ElectronicLibraryEntryTooltip.displayName(saved.key).equals("Named library blueprint"),
            "library list must display the real name");
        check(ElectronicLibraryEntryTooltip.inventoryItem(saved.key, saved).id.equals("BLUEPRINT:Named library blueprint"),
            "inventory preview must receive the named used blueprint");
        input.key = new Snapshot.Key(content, new Snapshot.Header(new Snapshot.Key(new byte[]{9}, null), "Wrong"));
        check(GlobalSavedDataSnapshots.validate(input) == null, "invalid header hash should be rejected");
    }
    static final class HeaderDroppingSnapshot extends Snapshot {
        @Override public HeaderDroppingSnapshot copy() { return new HeaderDroppingSnapshot(); }
    }
    public static void main(String[] ignored) {
        Template template = new Template();
        template.data.set(3);
        checkNamed(template, true);
        Blueprint blueprint = new Blueprint();
        blueprint.palette.add("stone");
        blueprint.entities.add("frame");
        checkNamed(blueprint, true);
        checkNamed(new HeaderDroppingSnapshot(), false);
        System.out.println("snapshot copy, validated storage, and name/preview PASS");
    }
}'''
        component = r'''package net.minecraft.network.chat;
public class Component {
    private final String value;
    private Component(String s){this.value=s;}
    public static Component literal(String s){return new Component(s);}
    public static Component translatable(String s, Object... args){return new Component(s+(args.length==0?"": ": "+args[0]));}
    public Component withStyle(net.minecraft.ChatFormatting ignored){return this;}
    public String getString(){return value;}
}'''
        files = {
            'buildcraft/builders/snapshot/Snapshot.java': fixture,
            'buildcraft/builders/snapshot/HeaderCopyProbe.java': probe,
            'net/minecraft/ChatFormatting.java': 'package net.minecraft; public enum ChatFormatting { DARK_GRAY }',
            'net/minecraft/network/chat/Component.java': component,
            'net/minecraft/world/item/ItemStack.java': 'package net.minecraft.world.item; public class ItemStack { '
                'public static final ItemStack EMPTY = new ItemStack("empty"); public final String id; '
                'public ItemStack(String id){this.id=id;} }',
            'buildcraft/builders/item/ItemSnapshot.java': 'package buildcraft.builders.item; '
                'public class ItemSnapshot {public static net.minecraft.world.item.ItemStack getUsed('
                'String type, buildcraft.builders.snapshot.Snapshot.Header header) {'
                'return new net.minecraft.world.item.ItemStack(type+":"+header.name);} }',
        }
        with tempfile.TemporaryDirectory(prefix='bc-lib-header-copy-') as tmp:
            directory = Path(tmp)
            for rel, source in files.items():
                path = directory/rel
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(source, encoding='utf-8')
            target = directory/'buildcraft/builders/gui/ElectronicLibraryEntryTooltip.java'
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT/'source-shared/src/main/java/buildcraft/builders/gui/ElectronicLibraryEntryTooltip.java',target)
            sources = [str(p) for p in directory.rglob('*.java')]
            compilation = subprocess.run(['javac','--release','17','-d',str(directory/'classes'),*sources],
                capture_output=True,text=True,timeout=35)
            self.assertEqual(0, compilation.returncode, compilation.stderr)
            result = subprocess.run(['java','-ea','-cp',str(directory/'classes'),
                'buildcraft.builders.snapshot.HeaderCopyProbe'],capture_output=True,text=True,timeout=15)
            self.assertEqual(0, result.returncode, result.stdout+result.stderr)
            self.assertIn('snapshot copy, validated storage, and name/preview PASS', result.stdout)

    @unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'JDK required for library entry helper probe')
    def test_real_java_library_entry_names_and_inventory_hover_payload(self):
        root = ROOT/'source-shared/src/main/java/buildcraft/builders/gui/ElectronicLibraryEntryTooltip.java'
        files = {
            'net/minecraft/ChatFormatting.java': 'package net.minecraft; public enum ChatFormatting { DARK_GRAY }',
            'net/minecraft/network/chat/Component.java': r'''package net.minecraft.network.chat;
public class Component {
    final String value;
    Component(String value) { this.value=value; }
    public static Component literal(String text) { return new Component(text); }
    public static Component translatable(String key, Object... args) {
        return new Component(key + (args.length == 0 ? "" : ": " + args[0]));
    }
    public Component withStyle(net.minecraft.ChatFormatting style) { return this; }
    public String getString() { return value; }
}''',
            'net/minecraft/world/item/ItemStack.java': r'''package net.minecraft.world.item;
public class ItemStack {
    public static final ItemStack EMPTY = new ItemStack("empty");
    public final String id;
    public ItemStack(String id) { this.id=id; }
}''',
            'buildcraft/builders/snapshot/Snapshot.java': r'''package buildcraft.builders.snapshot;
public class Snapshot {
    public final Key key;
    final String type;
    public Snapshot(Key key, String type) { this.key=key;this.type=type; }
    public String getType() { return type; }
    public static class Header {
        public final String name;
        public Header(String name) {this.name=name;}
    }
    public static class Key {
        public final Header header;
        final String hash;
        public Key(String hash, Header header) {this.hash=hash; this.header=header;}
        public String toString() {return hash;}
    }
}''',
            'buildcraft/builders/item/ItemSnapshot.java': r'''package buildcraft.builders.item;
import buildcraft.builders.snapshot.Snapshot;
import net.minecraft.world.item.ItemStack;
public class ItemSnapshot {
  public static ItemStack getUsed(String type, Snapshot.Header header) {
    return new ItemStack(type + ":" + header.name);
  }
}''',
            'ElectronicLibraryEntryTooltipProbe.java': r'''import buildcraft.builders.gui.ElectronicLibraryEntryTooltip;
import buildcraft.builders.snapshot.Snapshot;
class ElectronicLibraryEntryTooltipProbe {
    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        Snapshot.Header named = new Snapshot.Header("Castle with a roof");
        Snapshot.Key key = new Snapshot.Key("0123deadbeef", named);
        Snapshot stored = new Snapshot(key, "BLUEPRINT");
        check(ElectronicLibraryEntryTooltip.displayName(key).equals("Castle with a roof"), "saved name");
        check(ElectronicLibraryEntryTooltip.inventoryItem(key, stored).id.equals("BLUEPRINT:Castle with a roof"), "used inventory item");
        Snapshot.Key empty = new Snapshot.Key("0123deadbeef", null);
        Snapshot old = new Snapshot(empty, "TEMPLATE");
        check(ElectronicLibraryEntryTooltip.displayName(empty).equals("item.blueprint.unnamed"), "legacy unnamed");
        check(ElectronicLibraryEntryTooltip.inventoryItem(empty, old).id.equals("empty"), "do not invent metadata");
        check(ElectronicLibraryEntryTooltip.fallbackLines(empty).get(1).getString().contains("0123deadbeef"), "hash in fallback");
        check(ElectronicLibraryEntryTooltip.compactLines(key).size() == 2, "named tooltip has only name and hash");
        check(ElectronicLibraryEntryTooltip.compactLines(key).get(0).getString().equals("Castle with a roof"), "named tooltip displays saved name");
        check(ElectronicLibraryEntryTooltip.compactLines(key).get(1).getString().contains("0123deadbeef"), "named tooltip displays hash");
        check(ElectronicLibraryEntryTooltip.compactLines(empty).size() == 2, "headerless tooltip has only name and hash");
        check(ElectronicLibraryEntryTooltip.compactLines(empty).get(0).getString().equals("item.blueprint.unnamed"), "unnamed fallback");
        check(ElectronicLibraryEntryTooltip.inventoryItem(key, null).id.equals("empty"), "missing local file");
        check(ElectronicLibraryEntryTooltip.displayName(new Snapshot.Key("h", new Snapshot.Header("<unnamed>")))
            .equals("item.blueprint.unnamed"), "placeholder");
        System.out.println("Library entry hover: name, item payload, missing header, hash PASS");
    }
}''',
        }
        with tempfile.TemporaryDirectory(prefix='bc-library-hover-') as tmp:
            folder = Path(tmp)
            for rel, data in files.items():
                target = folder/rel
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(data, encoding='utf-8')
            (folder/'buildcraft/builders/gui').mkdir(parents=True, exist_ok=True)
            shutil.copy2(root, folder/'buildcraft/builders/gui/ElectronicLibraryEntryTooltip.java')
            sources = [str(p) for p in folder.rglob('*.java')]
            result = subprocess.run(['javac','--release','17','-d',str(folder/'classes'),*sources],
                capture_output=True,text=True,timeout=25)
            self.assertEqual(0,result.returncode,result.stderr)
            result = subprocess.run(['java','-ea','-cp',str(folder/'classes'),
                'ElectronicLibraryEntryTooltipProbe'],capture_output=True,text=True,timeout=10)
            self.assertEqual(0,result.returncode,result.stderr)

    @unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'JDK required for viewport probe')
    def test_real_java_viewport_handles_100_entries_deletion_duplicates_and_reopen(self):
        source = ROOT / 'source-shared/src/main/java/buildcraft/builders/gui/ElectronicLibraryViewport.java'
        probe = r'''
import buildcraft.builders.gui.ElectronicLibraryViewport;
import java.util.*;
public class LibraryViewportProbe {
    record Entry(int id, String title) {}
    static void check(boolean result, String message) {
        if (!result) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        List<Entry> entries = new ArrayList<>();
        for (int i = 1; i <= 100; i++) entries.add(new Entry(i, i == 1 || i == 100 ? "Duplicate" : "Blueprint " + i));
        ElectronicLibraryViewport<Entry> viewport = new ElectronicLibraryViewport<>(13);
        check(viewport.firstIndex(entries) == 0, "initial viewport");
        check(viewport.maxFirstIndex(entries.size()) == 87, "100/13 scroll extent");
        viewport.scrollToFraction(entries, 1);
        check(viewport.firstIndex(entries) == 87, "bottom of 100-entry list");
        check(viewport.entryAtRow(entries, 12).id() == 100, "last blueprint reachable");
        check(!viewport.entryAtRow(entries, 12).equals(entries.get(0)), "duplicate names retain distinct keys");
        viewport.scrollBy(entries, -3);
        check(viewport.entryAtRow(entries, 0).id() == 85, "upward wheel scroll");
        check(viewport.entryAtRow(entries, 12).id() == 97, "click row matches visible range");
        check(viewport.entryAtRow(entries, 13) == null, "bottom spillover not selectable");
        entries.add(0, new Entry(101, "Inserted"));
        viewport.update(entries);
        check(viewport.entryAtRow(entries, 0).id() == 85, "anchor survives new leading entry");
        entries.removeIf(entry -> entry.id() == 85);
        viewport.update(entries);
        check(viewport.entryAtRow(entries, 0).id() == 86, "anchor removal keeps valid row");
        viewport.scrollToFraction(entries, 1);
        check(viewport.entryAtRow(entries, 12).id() == 100, "last item still accessible after deletion");
        entries.removeIf(entry -> entry.id() == 100);
        viewport.update(entries);
        check(viewport.entryAtRow(entries, 12) != null && viewport.entryAtRow(entries, 12).id() == 99,
            "deleting last item re-clamps viewport");
        entries.subList(4, entries.size()).clear();
        viewport.update(entries);
        check(viewport.firstIndex(entries) == 0, "short collection clamps to start");
        check(viewport.entryAtRow(entries, 4) == null, "empty rows unselectable");
        ElectronicLibraryViewport<Entry> reopened = new ElectronicLibraryViewport<>(13);
        check(reopened.firstIndex(entries) == 0, "reopening shows valid range");
        List<Entry> fresh = new ArrayList<>();
        for (int i = 1; i <= 100; i++) fresh.add(new Entry(i, "Library " + i));
        reopened.scrollToFraction(fresh, 1);
        check(reopened.entryAtRow(fresh, 12).id() == 100, "reopen still scrolls to entry 100");
        System.out.println("Electronic library viewport: 100 entries, edits, duplicate names, reopen PASS");
    }
}
'''
        with tempfile.TemporaryDirectory(prefix='bc-electronic-library-viewport-') as tmp:
            root = Path(tmp)
            (root/'LibraryViewportProbe.java').write_text(probe, encoding='utf-8')
            compiled = subprocess.run(['javac', '--release', '17', '-d', str(root),
                str(source), str(root/'LibraryViewportProbe.java')],
                capture_output=True, text=True, timeout=30)
            self.assertEqual(0, compiled.returncode, compiled.stdout+compiled.stderr)
            executed = subprocess.run(['java', '-ea', '-cp', str(root), 'LibraryViewportProbe'],
                capture_output=True, text=True, timeout=15)
            self.assertEqual(0, executed.returncode, executed.stdout+executed.stderr)
            self.assertIn('reopen PASS', executed.stdout)


class BlueprintPreviewFrameRegressions(unittest.TestCase):
    """Small target-aware test: no full target materialization required."""

    def test_legacy_forge_inventory_preview_ignores_horizontal_text_tooltip_flip(self):
        source = ROOT / 'source-platforms/forge/src/main/java/buildcraft/builders/client/tooltip/LegacyBlueprintTooltipOverlay.java'
        code = source.read_text(encoding='utf-8')
        self.assertIn('BlueprintPreviewFrame.tooltipX(event.getX(), event.getScreenWidth(), SIZE)', code)
        self.assertNotIn('Math.min(tooltipX,', code)
        self.assertNotIn('int x = tooltipX;', code)
        self.assertNotIn('int x = location.x();', code)
        # On old Forge, the long hash moves the text tooltip to the opposite
        # side of the cursor. The 3D panel must not inherit that X position.
        for target in ('1.19.2-forge', '1.20.1-forge'):
            with self.subTest(target=target):
                self.assertIn('int tooltipY', code)
                self.assertIn('if (x < 0) return;', code)


    def test_inventory_preview_anchors_to_cursor_not_repositioned_text_tooltip(self):
        layout = load_layout()
        props = layout.load_properties()
        rel = 'src/main/java/buildcraft/builders/client/tooltip/BlueprintTooltipOverlay.java'
        targets = ('1.21.1-neoforge', '1.21.11-neoforge',
            '26.1.2-neoforge', '26.2-neoforge', '26.3-neoforge')
        for target in targets:
            with self.subTest(target=target):
                source = layout.resolve_effective_source(layout.target_layout(target, props), props, rel)
                self.assertIsNotNone(source)
                code = source.read_text(encoding='utf-8')
                self.assertIn('BlueprintPreviewFrame.tooltipXFromWindowMouse(', code)
                self.assertIn('minecraft.mouseHandler.xpos()', code)
                self.assertIn('minecraft.getWindow().getWidth()', code)
                self.assertNotIn('tooltipX(event.getX()', code)
                self.assertNotIn('int x = position.x();', code)
                self.assertIn('if (x < 0 || event.getScreenHeight() < SIDE + 8) return;', code)
                self.assertIn('Math.min(y, event.getScreenHeight() - SIDE - 4)', code)

    @unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'JDK required')
    def test_inventory_preview_uses_real_mouse_not_repositioned_tooltip_origin(self):
        source = ROOT / 'source-shared/src/main/java/buildcraft/builders/gui/BlueprintPreviewFrame.java'
        probe = r"""import buildcraft.builders.gui.BlueprintPreviewFrame;
public final class BlueprintTooltipCursorProbe {
  static int preview(double mousePixels, int physicalWidth, int guiWidth) {
    return BlueprintPreviewFrame.tooltipXFromWindowMouse(mousePixels, physicalWidth, guiWidth, 100);
  }
  public static void main(String[] args) {
    // A very long tooltip may be clamped to X=0 despite hovering at X=720.
    if (preview(1440, 1920, 960) != 732) throw new AssertionError("cursor anchoring: ignores tooltip X=0");
    if (preview(1400, 1920, 960) != 712) throw new AssertionError("pixel-to-GUI scale conversion");
    if (preview(1918, 1920, 960) != 856) throw new AssertionError("right edge clamp");
    if (preview(0, 1920, 960) != 12) throw new AssertionError("left edge");
    if (preview(500, 0, 960) != -1) throw new AssertionError("invalid physical window");
    if (preview(Double.NaN, 1920, 960) != -1) throw new AssertionError("invalid pointer");
    if (preview(200, 1920, 98) != -1) throw new AssertionError("insufficient gui space");
    for (int physical = 0; physical < 1920; physical++) {
      int x = preview(physical, 1920, 960);
      if (x < 4 || x + 100 + 4 > 960) throw new AssertionError("offscreen at " + physical);
    }
    System.out.println("Actual cursor tooltip position PASS");
  }
}"""
        with tempfile.TemporaryDirectory(prefix='bc-tooltip-cursor-') as tmp:
            work = Path(tmp)
            (work/'BlueprintTooltipCursorProbe.java').write_text(probe, encoding='utf-8')
            compilation = subprocess.run(['javac', '--release', '17', '-d', str(work),
                str(source), str(work/'BlueprintTooltipCursorProbe.java')],
                capture_output=True, text=True, timeout=30)
            self.assertEqual(0, compilation.returncode, compilation.stdout + compilation.stderr)
            execution = subprocess.run(['java', '-ea', '-cp', str(work), 'BlueprintTooltipCursorProbe'],
                capture_output=True, text=True, timeout=15)
            self.assertEqual(0, execution.returncode, execution.stdout + execution.stderr)
            self.assertIn('Actual cursor tooltip position PASS', execution.stdout)

    @unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'JDK required')
    def test_inventory_preview_stays_on_screen_without_horizontal_jumps(self):
        source = ROOT / 'source-shared/src/main/java/buildcraft/builders/gui/BlueprintPreviewFrame.java'
        probe = r"""import buildcraft.builders.gui.BlueprintPreviewFrame;
public final class BlueprintTooltipPositionProbe {
  private static int x(int cursor, int screen) {
    return BlueprintPreviewFrame.tooltipX(cursor, screen, 100);
  }
  public static void main(String[] args) {
    if (x(270, 320) != 216) throw new AssertionError("long tooltip moved preview to left edge");
    if (x(10, 320) != 22) throw new AssertionError("near cursor alignment");
    if (x(0, 320) != 12) throw new AssertionError("left edge placement");
    if (x(120, 100) != -1) throw new AssertionError("too small GUI scale");
    for (int width : new int[]{108, 136, 220, 320, 640}) {
      int previous = -1;
      for (int mouse = 0; mouse < width; mouse++) {
        int current = x(mouse, width);
        if (current < 4 || current + 100 + 4 > width)
          throw new AssertionError("preview outside screen at " + mouse + ", width " + width);
        if (previous >= 0 && (current < previous || current > previous + 1))
          throw new AssertionError("preview jumps at " + mouse + ", width " + width);
        previous = current;
      }
    }
    System.out.println("Inventory preview positioning PASS");
  }
}"""
        with tempfile.TemporaryDirectory(prefix='bc-tooltip-placement-') as tmp:
            work = Path(tmp)
            (work/'BlueprintTooltipPositionProbe.java').write_text(probe, encoding='utf-8')
            compilation = subprocess.run(['javac', '--release', '17', '-d', str(work),
                str(source), str(work/'BlueprintTooltipPositionProbe.java')],
                capture_output=True, text=True, timeout=30)
            self.assertEqual(0, compilation.returncode, compilation.stdout + compilation.stderr)
            execution = subprocess.run(['java', '-ea', '-cp', str(work), 'BlueprintTooltipPositionProbe'],
                capture_output=True, text=True, timeout=15)
            self.assertEqual(0, execution.returncode, execution.stdout + execution.stderr)
            self.assertIn('Inventory preview positioning PASS', execution.stdout)
    TARGETS = ('1.19.2-forge', '1.20.1-forge', '1.21.1-neoforge',
        '1.21.11-neoforge', '26.1.2-neoforge', '26.2-neoforge', '26.3-neoforge')

    def test_all_library_previews_and_inventory_overlay_draw_a_frame(self):
        layout = load_layout()
        props = layout.load_properties()
        screen_path = 'src/main/java/buildcraft/builders/gui/GuiElectronicLibrary.java'
        overlay_path = 'src/main/java/buildcraft/builders/client/tooltip/BlueprintTooltipOverlay.java'
        frame = ROOT/'source-shared/src/main/java/buildcraft/builders/gui/BlueprintPreviewFrame.java'
        helper = frame.read_text(encoding='utf-8')
        self.assertIn('public static void draw(', helper)
        self.assertIn('public static void drawOutline(', helper)
        for target in self.TARGETS:
            with self.subTest(target=target):
                selection = layout.target_layout(target, props)
                source_path = layout.resolve_effective_source(selection, props, screen_path)
                self.assertIsNotNone(source_path)
                gui = source_path.read_text(encoding='utf-8')
                self.assertIn('BlueprintPreviewFrame.draw(', gui)
                if target.startswith('1.19.') or target.startswith('1.20.'):
                    method = java_method(gui, 'private void renderLibraryEntryTooltip(')
                    self.assertNotIn('GuiUtil.getUnFormattedTooltip(item)', method)
                    self.assertIn('ElectronicLibraryEntryTooltip.compactLines(key)', method)
                    self.assertIn('LegacyBlueprintGuiRenderer.render(', method)
                    self.assertIn('BlueprintPreviewFrame.draw(', method)
                    self.assertLess(method.index('renderComponentTooltip('), method.index('BlueprintPreviewFrame.draw('))
                    # Rendering the ItemStack's tooltip would trigger a second preview.
                    self.assertNotRegex(method, r'(?<!Component)renderTooltip\(')
                    self.assertEqual(1, method.count('LegacyBlueprintGuiRenderer.render('))
                else:
                    # The inventory preview overlay remains available outside the library.
                    overlay = layout.resolve_effective_source(selection, props, overlay_path)
                    self.assertIsNotNone(overlay, target)
                    self.assertIn('BlueprintPreviewFrame.drawOutline(', overlay.read_text(encoding='utf-8'))
                    method = java_method(gui, 'private void renderLibraryEntryTooltip(')
                    self.assertNotIn('GuiUtil.getUnFormattedTooltip(item)', method)
                    self.assertIn('ElectronicLibraryEntryTooltip.compactLines(key)', method)
                    self.assertIn('BlueprintPreviewFrame.draw(', method)
                    self.assertEqual(1, method.count('BlueprintRenderer.renderSnapshot('))
                    self.assertNotRegex(method, r'(?<!Component)renderTooltip\(')
                    if target.startswith('1.21.'):
                        self.assertIn('BCGuiTooltip.components(', method)
                    else:
                        self.assertIn('renderComponentTooltip(', method)

    @unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'JDK required')
    def test_frame_geometry_has_visible_outline_and_clear_viewport(self):
        source = ROOT/'source-shared/src/main/java/buildcraft/builders/gui/BlueprintPreviewFrame.java'
        probe = r"""import buildcraft.builders.gui.BlueprintPreviewFrame;
import java.util.*;
public final class FrameProbe {
  static record Rect(int l, int t, int r, int b, int c) {}
  public static void main(String[] args) {
    List<Rect> rects = new ArrayList<>();
    BlueprintPreviewFrame.Fill fill = (l,t,r,b,c) -> rects.add(new Rect(l,t,r,b,c));
    BlueprintPreviewFrame.draw(fill, 20, 40, 100, 100);
    if (rects.size() != 5) throw new AssertionError("missing backing or borders");
    if (!rects.get(0).equals(new Rect(16,36,124,144,0xF0100010)))
      throw new AssertionError("background geometry");
    for (int i = 1; i < rects.size(); i++) {
      Rect r = rects.get(i);
      if (r.c() == 0 || r.l() >= r.r() || r.t() >= r.b())
        throw new AssertionError("invisible or invalid border " + i);
    }
    rects.clear();
    BlueprintPreviewFrame.drawOutline(fill, 20, 40, 100, 100);
    if (rects.size() != 4) throw new AssertionError("tooltip border missing");
    for (Rect r: rects) {
      if (r.l() < 17 || r.t() < 37 || r.r() > 123 || r.b() > 143)
        throw new AssertionError("stroke outside panel");
    }
    System.out.println("Blueprint preview framed viewport PASS");
  }
}"""
        with tempfile.TemporaryDirectory(prefix='bc-blueprint-preview-frame-') as tmp:
            work = Path(tmp)
            (work/'FrameProbe.java').write_text(probe, encoding='utf-8')
            result = subprocess.run(['javac', '--release', '17', '-d', str(work),
                str(source), str(work/'FrameProbe.java')], capture_output=True, text=True, timeout=30)
            self.assertEqual(0, result.returncode, result.stdout+result.stderr)
            result = subprocess.run(['java','-ea','-cp',str(work),'FrameProbe'],
                capture_output=True, text=True, timeout=10)
            self.assertEqual(0, result.returncode, result.stdout+result.stderr)
            self.assertIn('PASS', result.stdout)


if __name__ == '__main__': unittest.main(verbosity=2)
