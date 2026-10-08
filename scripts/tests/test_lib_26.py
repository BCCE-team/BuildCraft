#!/usr/bin/env python3
"""26.X lib contracts: real boundary code with offline API doubles and byte parity."""
from __future__ import annotations

import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from source_layout import load_properties, materialize_target
from transforms.lib_symbols import upgrade_lib_symbols
from transforms.java_symbols import OPAQUE
from minecraft_compat_fixture import parse_sources
from lib_26_fixture import render, input_and_text, fuel

spec = importlib.util.spec_from_file_location("byte_parity", ROOT / "scripts/validate-26.1.2-byte-parity.py")
assert spec is not None and spec.loader is not None
parity = importlib.util.module_from_spec(spec)
spec.loader.exec_module(parity)


class LibSymbolAliases(unittest.TestCase):
    def rewrite(self, text: str, version: str = "26.3", path: str = "Example.java") -> str:
        return upgrade_lib_symbols(text, minecraft=version, relative=path)

    def test_aliases_are_version_gated_and_idempotent(self):
        text = "import net.minecraft.client.renderer.MultiBufferSource;\nclass A { MultiBufferSource buffers; }"
        for version in ("1.19.2", "1.20.1", "1.21.1", "1.21.11", "26.1.2"):
            self.assertEqual(text, self.rewrite(text, version))
        for version in ("26.2", "26.3"):
            result = self.rewrite(text, version)
            self.assertIn("import buildcraft.lib.compat.minecraft.render.BCVertexBuffers;", result)
            self.assertIn("BCVertexBuffers buffers;", result)
            self.assertEqual(result, self.rewrite(result, version))
        self.assertEqual(text, self.rewrite(text, path="Example.txt"))

    def test_literals_comments_and_text_blocks_are_opaque(self):
        text = ('import net.minecraft.ChatFormatting;\nclass A { ChatFormatting value; '
                'String s="ChatFormatting net.minecraft.ChatFormatting"; '
                'String block="""\nimport net.minecraft.ChatFormatting;\n"""; }\n'
                '// ChatFormatting\n/* net.minecraft.ChatFormatting */')
        result = self.rewrite(text)
        self.assertIn("BCTextFormat value;", result)
        for literal in ('"ChatFormatting net.minecraft.ChatFormatting"',
                        '"""\nimport net.minecraft.ChatFormatting;\n"""',
                        '// ChatFormatting', '/* net.minecraft.ChatFormatting */'):
            self.assertIn(literal, result)
        for opaque in ('/* import net.minecraft.ChatFormatting; */',
                       'String s="""\nimport net.minecraft.ChatFormatting;\n""";'):
            self.assertEqual(opaque, self.rewrite(opaque))

    def test_unrelated_types_are_not_rewritten(self):
        text = "import example.ChatFormatting;\nclass A { ChatFormatting value; }"
        self.assertEqual(text, self.rewrite(text))
        fq = "class A { net.minecraft.ChatFormatting value; }"
        self.assertEqual("class A { buildcraft.lib.compat.minecraft.text.BCTextFormat value; }", self.rewrite(fq))


class BaselineComparison(unittest.TestCase):
    def test_added_missing_changed_files_and_line_endings_are_detected(self):
        with tempfile.TemporaryDirectory(prefix="bc-byte-guard-probe-") as tmp:
            root = Path(tmp)
            original = b"class A {}\r\n"
            expected = {"A.java": hashlib.sha256(original).hexdigest()}
            self.assertEqual(["missing: A.java"], parity.compare(root, expected))
            (root / "A.java").write_bytes(original)
            self.assertEqual([], parity.compare(root, expected))
            (root / "A.java").write_bytes(b"class A {}\n")
            self.assertEqual(["changed: A.java"], parity.compare(root, expected))
            (root / "extra.bin").write_bytes(b"x")
            self.assertEqual(["added: extra.bin", "changed: A.java"], parity.compare(root, expected))


class MetadataRanges(unittest.TestCase):
    def test_release_ranges_accept_both_version_schemes_but_reject_wider_ranges(self):
        import contextlib
        import io
        spec = importlib.util.spec_from_file_location(
            "cross_integrity", ROOT / "scripts/validate-cross-target-integrity.py")
        assert spec is not None and spec.loader is not None
        validator = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(validator)
        try:
            props = load_properties()
            validator.validate_metadata(props)
            for target in ("1.19.2-forge", "26.1.2-neoforge", "26.2-neoforge", "26.3-neoforge"):
                with self.subTest(target=target):
                    invalid = dict(props)
                    invalid[f"target.{target}.minecraft.version_range"] = "[1,99)"
                    with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                        validator.validate_metadata(invalid)
        finally:
            validator._RESOURCE_TEMP.cleanup()


class Lib26Boundaries(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix="bc-lib-26-")
        cls.roots: dict[str, Path] = {}
        for version in ("26.1.2", "26.2", "26.3"):
            root = Path(cls.temporary.name) / version
            materialize_target(version + "-neoforge", root, load_properties())
            cls.roots[version] = root

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def java(self, version: str) -> Path:
        return self.roots[version] / "src/main/java"

    def text(self, version: str, relative: str) -> str:
        return (self.java(version) / "buildcraft/lib" / relative).read_text(encoding="utf-8")

    def test_complete_26_1_2_tree_is_byte_identical(self):
        manifest = json.loads(parity.MANIFEST.read_text(encoding="utf-8"))
        self.assertEqual("26.1.2-neoforge", manifest["target"])
        self.assertEqual("sha256", manifest["algorithm"])
        self.assertEqual(4351, len(manifest["files"]))
        self.assertEqual([], parity.compare(self.roots["26.1.2"], manifest["files"]))

    def test_native_lib_sources_parse(self):
        print(parse_sources([self.java(version) / "buildcraft/lib" for version in ("26.2", "26.3")]), flush=True)

    def test_geometry_capture_and_scope_contracts(self):
        for version in ("26.2", "26.3"):
            with self.subTest(version=version):
                print(version + ": " + render(self.java(version), sdl=version == "26.3"), flush=True)

    def test_native_input_and_text_contracts(self):
        for version in ("26.2", "26.3"):
            with self.subTest(version=version):
                print(version + ": " + input_and_text(self.java(version), sdl=version == "26.3"), flush=True)

    def test_fuel_uses_the_machine_context(self):
        print(fuel(self.java("26.3")), flush=True)
        caller = (self.java("26.3") / "buildcraft/energy/tile/TileEngineStone_BC8.java").read_text()
        self.assertIn("ItemCompat.getBurnTime(itemstack, this, contents)", caller)
        self.assertIn("ItemCompat.isFuel(b)", caller)
        self.assertIn("ItemCompat.getBurnTime(itemstack)",
                      (self.java("26.2") / "buildcraft/energy/tile/TileEngineStone_BC8.java").read_text())

    def test_removed_render_and_text_apis_do_not_reach_native_lib(self):
        common = ("net.minecraft.client.renderer.MultiBufferSource", "net.minecraft.ChatFormatting",
                  "com.mojang.blaze3d.vertex.Tesselator", "getInstance().renderBuffers()",
                  "getInstance().setScreen(")
        for version in ("26.2", "26.3"):
            forbidden = common + (("com.mojang.blaze3d.pipeline.RenderPipeline", "com.mojang.blaze3d.opengl.", "org.lwjgl.glfw.",
                                   "net.minecraft.world.level.block.entity.FuelValues") if version == "26.3" else ())
            for path in (self.java(version) / "buildcraft/lib").rglob("*.java"):
                code = OPAQUE.sub("", path.read_text(encoding="utf-8"))
                for removed in forbidden:
                    self.assertNotIn(removed, code, f"{version}: {path.name}: {removed}")
            for retired in ("BufferUploader", "VertexBuffer"):
                self.assertFalse((self.java(version) / f"buildcraft/lib/compat/mc2612/blaze3d/vertex/{retired}.java").exists())

    def test_native_recipe_and_quad_contracts_are_selected(self):
        for name in ("AssemblyRecipeBuilder", "NbtShapedRecipeBuilder"):
            current = self.text("26.3", f"recipe/{name}.java")
            self.assertIn("output.lookup(Registries.RECIPE).getOrThrow(key)", current)
            self.assertIn("criteria.forEach", current)
            self.assertNotIn("output.lookup(Registries.RECIPE)", self.text("26.2", f"recipe/{name}.java"))
        for relative in ("net/MessageGuideRecipeDisplays.java", "tile/craft/WorkbenchCrafting.java"):
            self.assertIn("recipeMap().values()", self.text("26.3", relative))
            self.assertIn("getRecipes()", self.text("26.2", relative))
        current = self.text("26.3", "compat/minecraft/model/NativeItemModelBuilder.java")
        self.assertIn("itemGlintRenderType()", current)
        self.assertIn("itemGlintSpecialRenderType()", current)
        self.assertIn("getShadeDirectionOverride()", current)
        self.assertNotIn("itemGlintSpecialRenderType()", self.text("26.2", "compat/minecraft/model/NativeItemModelBuilder.java"))

    def test_downports_are_complete_config_selected_native_files(self):
        config = load_properties()
        self.assertEqual("26.3", config["source.family.26.X.canonical_minecraft"])
        self.assertNotIn("target.26.3-neoforge.source.family_platform_downport_root", config)
        for version in ("26.1.2", "26.2"):
            for field in ("family_downport_root", "family_platform_downport_root"):
                root = ROOT / config[f"target.{version}-neoforge.source.{field}"]
                self.assertTrue(root.is_dir())
                for path in root.rglob("*.java"):
                    self.assertNotIn("//?", path.read_text(encoding="utf-8"), str(path))
        self.assertFalse((self.java("26.1.2") / "buildcraft/lib/compat/minecraft/text/BCTextFormat.java").exists())


if __name__ == "__main__":
    unittest.main()
