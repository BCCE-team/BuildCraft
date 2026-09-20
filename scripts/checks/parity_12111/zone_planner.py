#!/usr/bin/env python3
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[3]

with tempfile.TemporaryDirectory(prefix="bcce-zone-planner-12111-") as tmp:
    out = Path(tmp) / "effective"
    subprocess.run(
        ["python", str(ROOT / "scripts/source_layout.py"), "1.21.11-neoforge", "--output", str(out)],
        cwd=ROOT,
        check=True,
        stdout=subprocess.DEVNULL,
    )
    src = out / "src/main/java"
    chunk = (src / "buildcraft/robotics/zone/ZonePlannerMapChunk.java").read_text(encoding="utf-8")
    gui = (src / "buildcraft/robotics/gui/GuiZonePlanner.java").read_text(encoding="utf-8")
    renderer = (src / "buildcraft/robotics/client/render/RenderZonePlanner.java").read_text(encoding="utf-8")

    checks = [
        ("map colours use 1.21.11 ARGB API", "calculateARGBColor(brightness)" in chunk),
        ("map colours are not zeroed", "int nativeMapColour = 0" not in chunk and "calculateRGBColor" not in chunk),
        ("map network/cache stores ARGB directly", "new MapColourData(current.posY, mapColour)" in chunk),
        ("GUI NativeImage writes ARGB directly", "argbToAbgr(colour)" not in gui and "fillNativeImage(" in gui),
        ("GUI dynamic texture uploads", "mapTexture.upload()" in gui),
        ("GUI draws the dynamic map texture", "RenderCompat.blit(guiGraphics, TEXTURE_MAP" in gui),
        ("block preview is native 1.21.11 BER", "BlockEntityRenderer<TileZonePlanner, RenderZonePlanner.ZonePlannerRenderState>" in renderer),
        ("block preview submits custom geometry", "collector.submitCustomGeometry" in renderer),
        ("block preview writes ARGB directly", "pixels.setPixel(x, y, colours[" in renderer and "argbToAbgr" not in renderer),
        ("block preview keeps 10x8 BC8 geometry", "TEXTURE_WIDTH = 10" in renderer and "TEXTURE_HEIGHT = 8" in renderer),
    ]
    failed = [name for name, ok in checks if not ok]
    if failed:
        raise SystemExit("Zone Planner 1.21.11 render parity failed: " + "; ".join(failed))

print("Zone Planner GUI + block preview 1.21.11 render parity: OK")
