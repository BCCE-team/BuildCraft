"""Old Forge: 64px atlas uses the same sprite pathway as working modern GUI.

No extra foreground overdraws that can double-blit the three buttons.
"""
import sys,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'scripts'))
from source_layout import load_properties,resolve_effective_source,target_layout
from source_preprocessor import preprocess_text
class Tests(unittest.TestCase):
 def test_sprite_normalization_and_no_native_bypass(self):
  props=load_properties()
  for t in ('1.19.2-forge','1.20.1-forge'):
   with self.subTest(target=t):
    lay=target_layout(t,props)
    f=resolve_effective_source(lay,props,'src/main/java/buildcraft/lib/gui/json/ElementTypeButton.java')
    c=preprocess_text(f.read_text('utf-8'),minecraft=props[f'target.{t}.deps.minecraft'],family=lay.family,platform=lay.platform,source=str(f))
    self.assertIn('texSize = resolveEquationInt(json, key + ".texture_size", ctx);',c)
    self.assertIn('new SpriteRaw(loc, uvs[0], uvs[1], uvs[2], uvs[3], texSize)',c)
    self.assertIn('GuiUtil.subRelative(sprite, uvs[0], uvs[1], uvs[2], uvs[3], texSize)',c)
    self.assertNotIn('return (graphics, x, y) ->',c)
    for st in ('enabled','hovered','active','active_hovered','disabled','active_disabled'):
     self.assertIn(f'"modes.{st}.texture_size"',c)
 def test_no_duplicate_overlay_and_buttons_still_interactive(self):
  c=(ROOT/'source-families/old/src/main/java/buildcraft/builders/gui/GuiFiller.java').read_text('utf-8')
  for key in ('filler.preview','filler.excavate','filler.invert'):
   self.assertIn(f'properties.put("{key}", IButtonBehaviour.TOGGLE)',c)
  self.assertNotIn('drawFillerButtonsFinalPass(',c)
  self.assertNotIn('shownElements.add(new GuiElementSimple(',c)
if __name__=='__main__': unittest.main()
