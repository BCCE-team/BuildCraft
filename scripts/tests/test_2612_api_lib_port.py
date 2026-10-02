#!/usr/bin/env python3
"""Structural guard for the first 26.1.2 port slice: API + library."""
from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from source_config import load_properties, target_layout  # noqa: E402
from transforms.java_compat import upgrade_symbols  # noqa: E402
SHARED_API = ROOT / "source-shared/src/main/java/buildcraft/api"
PORT_ROOT = ROOT / "source-families/26.X"
PORT_LIB = PORT_ROOT / "src/main/java/buildcraft/lib"
TARGETS = ROOT / "build-config/targets.properties"
GENERATION = ROOT / "builds/26.X/targets.properties"
NEOFORGE_BUILD = ROOT / "builds/26.X/build.neoforge.gradle"


def fail(message: str) -> None:
    raise SystemExit(f"26.1.2 API/lib port: {message}")


def main() -> None:
    if not SHARED_API.is_dir():
        fail("the shared public buildcraft.api surface is missing")
    api_overrides = list((PORT_ROOT / "src/main/java/buildcraft/api").rglob("*.java"))
    expected_api_override = PORT_ROOT / "src/main/java/buildcraft/api/v2/recipe/CountedIngredient.java"
    if api_overrides != [expected_api_override]:
        fail("26.X may override only CountedIngredient for the vanilla 26.1.2 Ingredient API")
    if "target.26.1.2-neoforge.deps.minecraft=26.1.2" not in TARGETS.read_text(encoding="utf-8"):
        fail("26.1.2 NeoForge target is not registered")
    if "generation=26.X" not in GENERATION.read_text(encoding="utf-8"):
        fail("26.X target generation is not registered")
    if "moddev-gradle" not in NEOFORGE_BUILD.read_text(encoding="utf-8"):
        fail("26.X NeoForge build does not use ModDevGradle")

    layout = target_layout("26.1.2-neoforge", load_properties(GENERATION))
    effective = layout.effective_files("src/main/java")
    files = sorted(
        path for relative, path in effective.items()
        if relative.startswith("src/main/java/buildcraft/lib/") and path.suffix == ".java"
    )
    if not files:
        fail("missing 26.X buildcraft.lib baseline")
    for path in files:
        source = path.read_text(encoding="utf-8")
        if "121111" in path.as_posix() or "121111" in source:
            fail(f"stale 1.21.11 compatibility namespace in effective {path.relative_to(ROOT)}")
    compat_cases = (
        (
            ROOT / "source-family-platforms/26.X/neoforge/src/main/java/buildcraft/lib/compat/RenderCompat.java",
            ("return target instanceof GuiEventListener widget && widget.mouseClicked(event, doubleClick);",
             "return target instanceof GuiEventListener widget && widget.keyPressed(event);",
             "return RenderTypes.translucentMovingBlock();"),
            ("RenderCompat.mouseClicked(widget, event, doubleClick)",
             "RenderCompat.keyPressed(widget, event)",
             "return RenderCompat.translucent();"),
        ),
        (
            ROOT / "source-family-platforms/26.X/neoforge/src/main/java/buildcraft/lib/compat/minecraft/gui/BCGuiInput.java",
            ("widget.mouseClicked(new MouseButtonEvent", "widget.keyPressed(new KeyEvent"),
            ("RenderCompat.mouseClicked(widget", "RenderCompat.keyPressed(widget"),
        ),
    )
    for path, required, forbidden in compat_cases:
        generated = upgrade_symbols(
            path.read_text(encoding="utf-8"),
            minecraft="26.1.2",
            relative=path.relative_to(ROOT).as_posix(),
        )
        for fragment in required:
            if fragment not in generated:
                fail(f"materializer damaged native compatibility call in {path.relative_to(ROOT)}: {fragment}")
        for fragment in forbidden:
            if fragment in generated:
                fail(f"materializer introduced recursive compatibility call in {path.relative_to(ROOT)}: {fragment}")

    container_screen = ROOT / "source-family-platforms/26.X/neoforge/src/main/java/buildcraft/lib/compat/minecraft/gui/BCContainerScreen.java"
    container_generated = upgrade_symbols(
        container_screen.read_text(encoding="utf-8"), minecraft="26.1.2",
        relative=container_screen.relative_to(ROOT).as_posix(),
    )
    for fragment in (
        "super.mouseClicked(event, doubleClick)",
        "super.mouseDragged(event, dragX, dragY)",
        "super.mouseReleased(event)",
    ):
        if fragment not in container_generated:
            fail(f"materializer removed vanilla container fallback: {fragment}")

    gui_bc8 = ROOT / "source-family-platforms/26.X/neoforge/src/main/java/buildcraft/lib/gui/GuiBC8.java"
    gui_generated = upgrade_symbols(
        gui_bc8.read_text(encoding="utf-8"), minecraft="26.1.2",
        relative=gui_bc8.relative_to(ROOT).as_posix(),
    )
    if "return super.charTyped(codePoint, modifiers);" not in gui_generated:
        fail("materializer reintroduced GuiBC8 native/legacy charTyped recursion")

    counted = expected_api_override.read_text(encoding="utf-8")
    for fragment in ("ItemStack.isSameItemSameComponents", "stack.is(tag)"):
        if fragment not in counted:
            fail(f"CountedIngredient lost 1.21.x matching semantics: {fragment}")

    for namespace in ("buildcraft/lib/compat/mc2612", "buildcraft/lib/compat/neoforge2612"):
        if not (PORT_LIB / namespace.removeprefix("buildcraft/lib/")).is_dir():
            fail(f"missing versioned compatibility namespace {namespace}")

    print(f"26.1.2 API/lib primary-port structure OK: shared API, {len(files)} 26.X library files")


if __name__ == "__main__":
    main()
