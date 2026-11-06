"""Compile-boundary regression for modern Builders previews.

These checks inspect the target-selected Java after Stonecutter transforms. They
cannot replace the real NeoForge compile, but catch the exact BakedQuad/Camera
API mismatch that broke 1.21.11 while preserving the separate 1.21.1 downport.
"""
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from source_lookup import read_materialized_java

RENDER = "src/main/java/buildcraft/builders/client/render/"
MUTABLE = "src/main/java/buildcraft/lib/client/model/MutableQuad.java"


class BuildersPreviewQuadCameraCompat(unittest.TestCase):
    def test_all_modern_filler_previews_use_version_aware_camera(self):
        for target in ("1.21.11-neoforge", "26.1.2-neoforge", "26.2-neoforge", "26.3-neoforge"):
            with self.subTest(target=target):
                filler = read_materialized_java(target, RENDER + "FillerGhostPreview.java")
                self.assertIn("import buildcraft.lib.compat.minecraft.render.BCCamera;", filler)
                if target in ("26.2-neoforge", "26.3-neoforge"):
                    self.assertIn("BCCamera.position(Minecraft.getInstance().gameRenderer.mainCamera())", filler)
                    self.assertNotIn("gameRenderer.getMainCamera()", filler)
                else:
                    self.assertIn("BCCamera.position(Minecraft.getInstance().gameRenderer.getMainCamera())", filler)
                    self.assertNotIn("gameRenderer.mainCamera()", filler)
                self.assertNotIn("getMainCamera().getPosition()", filler)

    def test_preview_pipe_uses_same_legacy_quad_type_as_mutable_quad(self):
        for target, legacy in (
            ("1.21.11-neoforge", "mc121111"),
            ("26.1.2-neoforge", "mc2612"),
            ("26.2-neoforge", "mc2612"),
            ("26.3-neoforge", "mc2612"),
        ):
            with self.subTest(target=target):
                preview = read_materialized_java(target, RENDER + "pip/PreviewPipeModel.java")
                mutable = read_materialized_java(target, MUTABLE)
                required_import = f"import buildcraft.lib.compat.{legacy}.client.renderer.block.model.BakedQuad;"
                self.assertIn(required_import, preview)
                self.assertIn(required_import, mutable)
                self.assertNotIn("import net.minecraft.client.renderer.block.model.BakedQuad;", preview)
                self.assertIn("private static void copy(List<MutableQuad> target, List<BakedQuad> from)", preview)

    def test_1211_downport_stays_separate(self):
        preview = read_materialized_java("1.21.1-neoforge", RENDER + "pip/PreviewPipeModel.java")
        self.assertIn("import net.minecraft.client.renderer.block.model.BakedQuad;", preview)
        self.assertNotIn("import buildcraft.lib.compat.mc121111.client.renderer.block.model.BakedQuad;", preview)


if __name__ == "__main__":
    unittest.main()
