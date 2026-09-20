#!/usr/bin/env python3
"""Regression tests on the REAL materialized 1.21.11 source set.
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
        props = cls.layout.load_properties(ROOT/'builds/modern/targets.properties')
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
        data = json.loads((ROOT/'scripts/tests/item_names_12111.json').read_text())
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

if __name__ == '__main__': unittest.main(verbosity=2)
