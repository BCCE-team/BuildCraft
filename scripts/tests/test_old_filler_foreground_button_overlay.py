"""Regression test for separately owned Filler GUI definitions, old and modern.
Avoid asserting that family overrides must be deleted: that broke modern clients.
"""
import json,sys,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'scripts'))
from source_layout import load_properties,target_layout,resolve_effective_source
class Tests(unittest.TestCase):
 def test_old_uses_explicit_family_json_without_overlay(self):
  props=load_properties()
  for target in ('1.19.2-forge','1.20.1-forge'):
   with self.subTest(target=target):
    lay=target_layout(target,props)
    file=resolve_effective_source(lay,props,'src/main/resources/assets/buildcraftbuilders/gui/filler_base.json')
    self.assertIn('source-families/old/',str(file))
    d=json.loads(file.read_text('utf-8'))
    self.assertEqual(d['types']['button_preview']['modes.enabled.texture_size'],64)
    self.assertEqual(d['elements']['button_invert']['area'],[152,40,16,16])
    gui=resolve_effective_source(lay,props,'src/main/java/buildcraft/builders/gui/GuiFiller.java').read_text('utf-8')
    self.assertNotIn('drawFillerButtonsFinalPass(',gui)
 def test_modern_override_is_not_removed(self):
  for family in ('1.21.X','26.X'):
   path=ROOT/f'source-families/{family}/src/main/resources/assets/buildcraftbuilders/gui/filler_base.json'
   self.assertTrue(path.is_file(),str(path))
   self.assertEqual(json.loads(path.read_text())['types']['button_excavate']['texture_size'],64)
if __name__=='__main__': unittest.main()
