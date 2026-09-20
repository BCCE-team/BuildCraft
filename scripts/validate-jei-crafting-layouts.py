#!/usr/bin/env python3
"""Guard JEI crafting-layout parity for BuildCraft's mixed-mode PipeRecipe.

PipeRecipe intentionally represents two different crafting semantics:
  * BASE is a shaped 3x1 left-middle-right recipe;
  * UPGRADE/DOWNGRADE are shapeless conversions.

JEI cannot infer this from the recipe class alone, so every supported target must
register a vanilla crafting-category extension that supplies the correct layout.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

from source_layout import effective_source_files, load_properties, resolve_effective_source, target_ids, target_layout, version_tuple

ROOT = Path(__file__).resolve().parents[1]
PLUGIN_REL = Path("src/main/java/buildcraft/compat/jei/BuildCraftJeiPlugin.java")
EXT_REL = Path("src/main/java/buildcraft/compat/jei/PipeCraftingCategoryExtension.java")
RECIPE_REL = Path("src/main/java/buildcraft/transport/recipe/PipeRecipe.java")


def fail(errors: list[str], message: str) -> None:
    errors.append(message)


def validate_target(target: str, props: dict[str, str], errors: list[str]) -> tuple[int, int, int]:
    layout = target_layout(target, props)

    plugin_path = resolve_effective_source(layout, props, PLUGIN_REL)
    ext_path = resolve_effective_source(layout, props, EXT_REL)
    recipe_path = resolve_effective_source(layout, props, RECIPE_REL)
    for label, path in (("JEI plugin", plugin_path), ("pipe JEI extension", ext_path), ("PipeRecipe", recipe_path)):
        if path is None:
            fail(errors, f"{target}: missing effective {label}")
    if plugin_path is None or ext_path is None or recipe_path is None:
        return 0, 0, 0

    plugin = plugin_path.read_text(encoding="utf-8")
    extension = ext_path.read_text(encoding="utf-8")
    recipe = recipe_path.read_text(encoding="utf-8")

    if "registerVanillaCategoryExtensions" not in plugin:
        fail(errors, f"{target}: JEI plugin does not register vanilla crafting extensions")
    if "PipeRecipe.class" not in plugin:
        fail(errors, f"{target}: PipeRecipe is not registered with JEI's crafting category")
    if layout.family == "modern":
        if "addExtension(PipeRecipe.class, PipeCraftingCategoryExtension.INSTANCE)" not in plugin:
            fail(errors, f"{target}: modern JEI PipeRecipe extension registration is missing")
        if "ICraftingCategoryExtension<PipeRecipe>" not in extension:
            fail(errors, f"{target}: modern PipeRecipe extension lost its generic recipe binding")
    else:
        if "addCategoryExtension(PipeRecipe.class, PipeCraftingCategoryExtension::new)" not in plugin:
            fail(errors, f"{target}: legacy JEI PipeRecipe extension registration is missing")

    native_displays = version_tuple(props[f"target.{target}.deps.minecraft"]) >= version_tuple("1.21.11")
    # JEI 27's extension consumes vanilla SlotDisplays; its default setRecipe
    # builds the slots from Recipe.display(). Older targets still fill the layout
    # explicitly. Guard both contracts rather than demanding a removed API.
    layout_tokens = [
        "hasShapedBasePattern() ? 3 : 0",
        "hasShapedBasePattern() ? 1 : 0",
    ]
    if native_displays:
        layout_tokens.extend(("List<SlotDisplay> getIngredients", "Ingredient::display"))
        for token in ("PlacementInfo.create(ingredients)", "new ShapedCraftingRecipeDisplay(3, 1,",
                      "new ShapelessCraftingRecipeDisplay("):
            if token not in recipe:
                fail(errors, f"{target}: PipeRecipe lost native recipe-book layout token {token!r}")
    else:
        layout_tokens.extend(("createAndSetInputs", "createAndSetOutputs"))

    for token in layout_tokens:
        if token not in extension:
            fail(errors, f"{target}: PipeRecipe JEI extension lost layout token {token!r}")

    if "mode == Mode.BASE" not in recipe:
        fail(errors, f"{target}: PipeRecipe no longer distinguishes BASE mode")
    for ingredient in ("left", "middle", "right"):
        if f"ingredients.add({ingredient})" not in recipe:
            fail(errors, f"{target}: PipeRecipe BASE ingredient order lost {ingredient!r}")

    resources = effective_source_files(layout, props, "src/main/resources")
    counts = {"base": 0, "upgrade": 0, "downgrade": 0}
    for rel, path in resources.items():
        if not rel.endswith(".json") or ("/recipe/" not in rel and "/recipes/" not in rel):
            continue
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except Exception:
            continue
        if data.get("type") != "buildcrafttransport:pipe":
            continue
        mode = data.get("mode")
        if mode not in counts:
            fail(errors, f"{target}: unknown buildcrafttransport:pipe recipe mode {mode!r} in {rel}")
            continue
        counts[mode] += 1
        if mode == "base":
            missing = [key for key in ("left", "middle", "right") if key not in data]
            if missing:
                fail(errors, f"{target}: BASE pipe recipe {rel} is missing {', '.join(missing)}")
        elif mode == "upgrade":
            missing = [key for key in ("from", "additional") if key not in data]
            if missing:
                fail(errors, f"{target}: UPGRADE pipe recipe {rel} is missing {', '.join(missing)}")
        else:
            if "from" not in data:
                fail(errors, f"{target}: DOWNGRADE pipe recipe {rel} is missing from")

    if counts["base"] == 0:
        fail(errors, f"{target}: no BASE pipe recipes found; JEI shaped-layout guard is not exercising anything")
    return counts["base"], counts["upgrade"], counts["downgrade"]


def main() -> int:
    props = load_properties()
    errors: list[str] = []
    summaries: list[str] = []
    for target in target_ids(props):
        base, upgrade, downgrade = validate_target(target, props, errors)
        summaries.append(f"{target}: {base} base 3x1, {upgrade} upgrade shapeless, {downgrade} downgrade shapeless")

    if errors:
        print("JEI crafting layout validation FAILED:")
        for error in errors:
            print(f" - {error}")
        return 1

    print("JEI crafting layout parity OK:")
    for summary in summaries:
        print(f" - {summary}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
