"""Ensure all Filler targets resolve a 64x64 button config from the correct family.

A previous deduplication patch removed the modern overrides, breaking working
1.21.X/26.X UI even though a shared fallback existed. Preserve source ownership.
"""
from pathlib import Path
import json,struct,sys,unittest
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'scripts'))
from source_layout import load_properties,resolve_effective_source,target_layout
REL='src/main/resources/assets/buildcraftbuilders/gui/filler_base.json'
PNG=ROOT/'source-shared/src/main/resources/assets/buildcraftbuilders/textures/gui/filler_buttons.png'
EXPECTED={
 'button_preview':[(0,0),(0,16),(16,0),(16,16)],
 'button_excavate':[(32,0),(32,16),(48,0),(48,16)],
 'button_invert':[(0,32),(0,48),(16,32),(16,48)]
}
class Tests(unittest.TestCase):
 def test_png_kept_under_shared(self):
  raw=PNG.read_bytes(); self.assertEqual(raw[:8],b'\x89PNG\r\n\x1a\n')
  self.assertEqual(struct.unpack('>II',raw[16:24]),(64,64))
 def test_family_ownership_and_all_states(self):
  props=load_properties()
  for target,family in [('1.19.2-forge','old'),('1.20.1-forge','old'),('1.21.1-neoforge','1.21.X'),('1.21.11-neoforge','1.21.X'),('26.1.2-neoforge','26.X'),('26.2-neoforge','26.X'),('26.3-neoforge','26.X')]:
   with self.subTest(target=target):
    s=resolve_effective_source(target_layout(target,props),props,REL)
    self.assertIsNotNone(s)
    self.assertIn(f'source-families/{family}/',s.as_posix())
    j=json.loads(s.read_text(encoding='utf-8'))
    for name,values in EXPECTED.items():
     obj=j['types'][name]
     self.assertEqual(obj['texture'],'buildcraftbuilders:gui/filler_buttons')
     if family == 'old':
      self.assertNotIn('texture_size',obj)
      for state in ('enabled','hovered','active','active_hovered','disabled','active_disabled'):
       self.assertEqual(obj['modes.'+state+'.texture_size'],64)
     else:
      self.assertEqual(obj['texture_size'],64)
     self.assertEqual(obj['size'],[16,16])
     for state,uv in zip(('enabled','hovered','active','active_hovered'),values):
      self.assertEqual(obj['modes'][state],list(uv))
     self.assertEqual(set(obj['modes']),{'enabled','hovered','active','active_hovered','disabled','active_disabled'})
 def test_shared_texture_and_localization_not_duplicated(self):
  for fam in ('old','1.21.X','26.X'):
   self.assertFalse((ROOT/f'source-families/{fam}/src/main/resources/assets/buildcraftbuilders/textures/gui/filler_buttons.png').exists())
if __name__=='__main__': unittest.main()
