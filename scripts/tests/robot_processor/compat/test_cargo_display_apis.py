"""Guard cargo names and wrapped labels after the full target source pipeline."""
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT / 'scripts'))
from source_config import load_properties, target_layout
from source_layout import _materialize_text_file, resolve_effective_source

TARGETS = ('1.21.11-neoforge', '26.1.2-neoforge', '26.2-neoforge',
           '26.3-neoforge', '26.3-fabric')


def effective_java(target, relative, directory):
    props = load_properties()
    layout = target_layout(target, props)
    logical = 'src/main/java/buildcraft/' + relative
    source = resolve_effective_source(layout, props, logical)
    assert source is not None, (target, logical)
    output = directory / logical
    native_roots = tuple(r for r in (layout.family_downport_root,
                                     layout.family_platform_downport_root) if r is not None)
    _materialize_text_file(source, output, logical_relative=logical,
        minecraft=props[f'target.{target}.deps.minecraft'], family=layout.family,
        platform=layout.platform, preprocess=True,
        native_source=any(source.is_relative_to(r) for r in native_roots),
        native_passthrough=layout.platform == 'fabric' and
            source.is_relative_to(layout.family_platform_root))
    return output.read_text(encoding='utf-8')


class CargoDisplayApis(unittest.TestCase):
    def test_native_fluid_names_and_registry_lookup(self):
        for target in TARGETS:
            with self.subTest(target=target), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                gui = effective_java(target, 'robotics/gui/GuiRobotProcessor.java', root)
                compat = effective_java(target, 'lib/compat/FluidCompat.java', root)
                stack = ('buildcraft.lib.fluid.FluidStack' if target.endswith('-fabric')
                         else 'net.neoforged.neoforge.fluids.FluidStack')
                self.assertIn(f'import {stack};', gui)
                self.assertIn(f'import {stack};', compat)
                self.assertIn('BuiltInRegistries.FLUID.getOptional(Identifier.parse(id)).orElse(Fluids.EMPTY)', gui)
                self.assertNotIn('.map(net.minecraft.core.Holder.Reference::value)', gui)
                name = gui.split('private Component cargoFluidName(', 1)[1].split('\n    }', 1)[0]
                self.assertIn('cargoFluid(entry.fluidId())', name)
                self.assertIn('FluidCompat.getDisplayName(new FluidStack(', name)
                self.assertNotIn('getFluidType()', name)
                self.assertIn('stack.getHoverName()', compat)

    def test_wrapped_text_uses_formatted_sequence_overload(self):
        for target in TARGETS:
            with self.subTest(target=target), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                gui = effective_java(target, 'robotics/gui/GuiRobotProcessor.java', root)
                graphics = effective_java(target, 'lib/compat/minecraft/gui/BCGraphics.java', root)
                wrap = gui.split('private void panelWrappedText(', 1)[1].split('\n    }', 1)[0]
                self.assertIn('font.split(', wrap)
                self.assertIn('BCGraphics.text(graphics, font, line,', wrap)
                self.assertNotIn('drawString(graphics, font, line,', wrap)
                self.assertRegex(graphics, r'public static void text\([^\n]*FormattedCharSequence text,')


if __name__ == '__main__':
    unittest.main()
