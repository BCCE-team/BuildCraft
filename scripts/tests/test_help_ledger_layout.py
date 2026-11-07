"""Regression tests for the BCU-style responsive BCCE Help Tab."""
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
COMMON = ROOT / 'source-shared/src/main/java/buildcraft/lib/gui/ledger/HelpLedgerLayout.java'

class HelpLedgerLayoutTest(unittest.TestCase):
    def test_pure_screen_geometry(self):
        host = '''public class VerifyHelpLedgerLayout {
            private static void eq(double a, double b) { if (Math.abs(a-b) > 0.0001) throw new AssertionError(a+" != "+b); }
            public static void main(String[] args) {
                var h = buildcraft.lib.gui.ledger.HelpLedgerLayout.class;
                eq(buildcraft.lib.gui.ledger.HelpLedgerLayout.availableWidth(72, 320, false, 22), 70);
                eq(buildcraft.lib.gui.ledger.HelpLedgerLayout.availableWidth(248, 320, true, 22), 70);
                eq(buildcraft.lib.gui.ledger.HelpLedgerLayout.availableWidth(10, 320, false, 22), 22);
                eq(buildcraft.lib.gui.ledger.HelpLedgerLayout.upwardShift(110, 160, 240), 32);
                eq(buildcraft.lib.gui.ledger.HelpLedgerLayout.upwardShift(110, 60, 240), 0);
                eq(buildcraft.lib.gui.ledger.HelpLedgerLayout.upwardShift(110, 400, 240), 108);
                eq(buildcraft.lib.gui.ledger.HelpLedgerLayout.textWidth(70, 150), 38);
                eq(buildcraft.lib.gui.ledger.HelpLedgerLayout.textWidth(40, 150), 10);
            }
        }'''
        with tempfile.TemporaryDirectory() as td:
            host_file = Path(td) / 'VerifyHelpLedgerLayout.java'
            host_file.write_text(host)
            subprocess.run(['javac', '-d', td, str(COMMON), str(host_file)], check=True, capture_output=True, text=True)
            subprocess.run(['java', '-cp', td, 'VerifyHelpLedgerLayout'], check=True, capture_output=True, text=True)

    def test_every_help_family_selects_smallest_area(self):
        for rel in [
            'source-families/old/src/main/java/buildcraft/lib/gui/ledger/LedgerHelp.java',
            'source-downports/1.21.X/1.21.1/family/src/main/java/buildcraft/lib/gui/ledger/LedgerHelp.java',
            'source-families/1.21.X/src/main/java/buildcraft/lib/gui/ledger/LedgerHelp.java',
            'source-families/26.X/src/main/java/buildcraft/lib/gui/ledger/LedgerHelp.java',
        ]:
            text=(ROOT/rel).read_text()
            self.assertIn('area < smallest', text, rel)
            self.assertIn('availableHelpTextWidth()', text, rel)
            self.assertIn('fitToScreen()', text, rel)

    def test_ledger_anchors_all_materialization_layers(self):
        sources = [
            'source-shared/src/main/java/buildcraft/lib/gui/ledger/Ledger_Neptune.java',
            'source-families/1.21.X/src/main/java/buildcraft/lib/gui/ledger/Ledger_Neptune.java',
            'source-families/26.X/src/main/java/buildcraft/lib/gui/ledger/Ledger_Neptune.java',
            'version-src/1.19.2-forge/src/main/java/buildcraft/lib/gui/ledger/Ledger_Neptune.java',
        ]
        for path in sources:
            text = (ROOT/path).read_text()
            self.assertIn('HelpLedgerLayout.upwardShift(', text, path)
            self.assertIn('positionAnchor.offset', text, path)
            render = text[text.index('public void drawBackground('):]
            self.assertLess(render.index('updateScreenShift();'), render.index('double startY = getY();'), path)

    def test_additional_context_regions(self):
        for family in ['1.21.X', '26.X']:
            root = ROOT / f'source-families/{family}/src/main/java/buildcraft/'
            library = (root/'builders/gui/GuiElectronicLibrary.java').read_text()
            exchanger = (root/'factory/client/gui/ScreenHeatExchange.java').read_text()
            builder = (root/'builders/gui/GuiBuilder.java').read_text()
            self.assertIn('buildcraft.help.library.download_arrow.title', library)
            self.assertIn('buildcraft.help.library.upload_arrow.title', library)
            self.assertIn('73, 36, 30, 21', exchanger)
            self.assertNotIn('176, 0, 80, 222', builder)

    def test_all_gate_and_filler_families_define_precise_areas(self):
        for family in ['old', '1.21.X', '26.X']:
            path = ROOT / f'source-families/{family}/src/main/java/buildcraft/'
            gate = (path/'silicon/gui/GuiGate.java').read_text()
            filler = (path/'builders/gui/GuiFiller.java').read_text()
            self.assertIn('buildcraft.help.gate.trigger.title', gate)
            self.assertIn('buildcraft.help.gate.action.title', gate)
            self.assertIn('buildcraft.help.gate.connection.title', gate)
            self.assertIn('53, 39, 72, 18', filler)
            self.assertNotIn('9, 35, 158, 30', filler)
            self.assertNotIn('buildcraft.help.filler.work.title', filler)

if __name__ == '__main__':
    unittest.main()
