"""Regression check for 26.1.2/26.2 NeoForge processor API compatibility.

Checks *materialized* sources, not just shared-file preprocessing: 26.1.2
keeps a version-src Zone Planner that previously masked the compatible ctor.
"""
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT / "scripts"))
from source_layout import resolve_effective_source, _materialize_text_file
from source_config import load_properties, target_layout
from source_preprocessor import preprocess_text

JAVA_ROOT = Path("src/main/java/buildcraft/robotics")


def java_source(target: str, relative: str, directory: Path) -> str:
    """Run the production materializer for the selected Java file only."""
    props = load_properties()
    layout = target_layout(target, props)
    logical_relative = (JAVA_ROOT / relative).as_posix()
    source = resolve_effective_source(layout, props, logical_relative)
    assert source is not None, f"No source for {target}: {logical_relative}"
    effective = directory / logical_relative
    native_roots = tuple(root for root in (
        layout.family_downport_root, layout.family_platform_downport_root
    ) if root is not None)
    _materialize_text_file(
        source, effective, logical_relative=logical_relative,
        minecraft=props[f"target.{target}.deps.minecraft"],
        family=layout.family, platform=layout.platform, preprocess=True,
        native_source=any(source.is_relative_to(root) for root in native_roots),
        native_passthrough=layout.platform == "fabric" and
            source.is_relative_to(layout.family_platform_root),
    )
    return effective.read_text(encoding="utf-8")


class RobotProcessor26XDownportCompileContractTests(unittest.TestCase):
    def test_neoforge_downports_keep_chunk_accessors_messages_and_tooltips(self):
        for target in ("26.1.2-neoforge", "26.2-neoforge"):
            with self.subTest(target=target), tempfile.TemporaryDirectory(prefix="bcce-26x-downport-") as tmp:
                tree = Path(tmp) / "effective"
                scheduler = java_source(target, "scheduler/RobotProcessorWorkScheduler.java", tree)
                panel = java_source(target, "tile/TileRobotProcessor.java", tree)
                zone = java_source(target, "gui/GuiZonePlanner.java", tree)
                menu = java_source(target, "container/ContainerRobotProcessor.java", tree)
                controller = java_source(target, "item/ItemRobotController.java", tree)
                gui = java_source(target, "gui/GuiRobotProcessor.java", tree)
                for code in (scheduler, panel, zone):
                    self.assertRegex(code, r"\b(?:pos|chunk)\.x\(\)")
                    self.assertRegex(code, r"\b(?:pos|chunk)\.z\(\)")
                for code in (menu, controller):
                    self.assertIn("sendOverlayMessage(", code)
                    self.assertNotIn("displayClientMessage(", code)
                self.assertIn("protected void extractTooltip(", gui)
                self.assertNotIn("super.render(graphics, mouseX, mouseY, partialTicks)", gui)
                self.assertIn("super.extractTooltip(graphics, mouseX, mouseY)", gui)

    def test_2612_zone_planner_constructor_supports_processor_tile_type(self):
        with tempfile.TemporaryDirectory(prefix="bcce-2612-zone-tile-") as tmp:
            tree = Path(tmp) / "effective"
            zone_tile = java_source("26.1.2-neoforge", "tile/TileZonePlanner.java", tree)
            processor_tile = java_source("26.1.2-neoforge", "tile/TileRobotProcessor.java", tree)
            self.assertIn("protected TileZonePlanner(net.minecraft.world.level.block.entity.BlockEntityType<?> type,", zone_tile)
            self.assertIn("super(type, pos, state)", zone_tile)
            self.assertIn("this(BCRoboticsBlocks.ZONE_PLANNER_TILE.get(), pos, state)", zone_tile)
            self.assertIn("super(BCRoboticsBlocks.ROBOT_PROCESSOR_TILE.get(), pos, state)", processor_tile)
            self.assertNotIn("public TileZonePlanner(BlockPos pos, BlockState state) {\n        super(", zone_tile)

    def test_old_targets_still_use_public_chunk_fields_and_player_messages(self):
        root = ROOT / "source-shared/src/main/java/buildcraft/robotics"
        for family, mc, platform in (("old", "1.19.2", "forge"),
                                     ("old", "1.20.1", "forge"),
                                     ("1.21.X", "1.21.1", "neoforge"),
                                     ("1.21.X", "1.21.11", "neoforge")):
            with self.subTest(version=mc):
                scheduler_path = root / "scheduler/RobotProcessorWorkScheduler.java"
                scheduler = preprocess_text(scheduler_path.read_text(encoding="utf-8"),
                    minecraft=mc, family=family, platform=platform)
                self.assertIn("return pos.x;", scheduler)
                self.assertIn("return pos.z;", scheduler)
                menu_path = root / "container/ContainerRobotProcessor.java"
                menu = preprocess_text(menu_path.read_text(encoding="utf-8"),
                    minecraft=mc, family=family, platform=platform)
                self.assertIn("displayClientMessage(", menu)
                self.assertNotIn("sendOverlayMessage(", menu)


if __name__ == "__main__":
    unittest.main()
