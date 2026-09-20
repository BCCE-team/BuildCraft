#!/usr/bin/env python3
from __future__ import annotations

import shutil
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]


def materialize(target: str, out: Path) -> Path:
    subprocess.run(
        ["python", str(ROOT / "scripts" / "source_layout.py"), target, "--output", str(out)],
        cwd=ROOT,
        check=True,
        stdout=subprocess.DEVNULL,
    )
    return out / "src" / "main" / "java"


def require(cond: bool, message: str) -> None:
    if not cond:
        raise SystemExit(message)


def main() -> None:
    tmp = Path(tempfile.mkdtemp(prefix="bcce-fluid-render-"))
    try:
        java = materialize("1.21.11-neoforge", tmp / "12111")

        tank = (java / "buildcraft/factory/client/render/RenderTank.java").read_text(encoding="utf-8")
        holder = (java / "buildcraft/transport/client/render/RenderPipeHolder.java").read_text(encoding="utf-8")
        pipe_fluid = (java / "buildcraft/transport/client/render/PipeFlowRendererFluids.java").read_text(encoding="utf-8")

        require(
            "BlockEntityRenderer<TileTank, RenderTank.TankRenderState>" in tank,
            "1.21.11 tank renderer fell back to the dead legacy render(...) entry point",
        )
        require("submitCustomGeometry" in tank, "1.21.11 tank fluid is not submitted to the render queue")
        require("RenderCompat.translucent()" in tank, "Tank fluid is not using the transparent block-atlas phase")
        require("FluidRenderer.renderFluid(" in tank, "Tank renderer no longer emits the 1.21.1 fluid cuboid")
        require("LegacyBlockEntityRenderer<TileTank>" not in tank, "Tank renderer regressed back to LegacyBlockEntityRenderer")

        require(
            "pipe.flow instanceof PipeFlowFluids fluidFlow" in holder,
            "RenderPipeHolder no longer has the native 1.21.11 fluid-flow branch",
        )
        require(
            "PipeFlowRendererFluids.INSTANCE.submit(fluidFlow" in holder,
            "Fluid pipes are routed through the legacy cutout-only flow bridge",
        )
        require("SubmitNodeCollector" in pipe_fluid, "Pipe fluid renderer lacks a native submit path")
        require("collector.submitCustomGeometry(matrix, RenderCompat.translucent()" in pipe_fluid,
                "Pipe fluid geometry is not submitted in the transparent phase")
        require("renderFluidGeometry" in pipe_fluid, "Pipe fluid native/legacy paths no longer share identical geometry")

        print("Fluid tank + pipe 1.21.11 render submission validation: OK")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    main()
