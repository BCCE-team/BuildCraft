#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
from source_lookup import resolve_source_path
SOURCE_ROOTS = [
    ROOT / "source-shared/src/main/java",
    ROOT / "source-families/legacy/src/main/java",
    ROOT / "source-families/modern/src/main/java",
    ROOT / "source-platforms/forge/src/main/java",
    ROOT / "source-platforms/neoforge/src/main/java",
    ROOT / "source-family-platforms/legacy/forge/src/main/java",
    ROOT / "source-family-platforms/modern/neoforge/src/main/java",
    ROOT / "version-src/1.19.2-forge/src/main/java",
    ROOT / "version-src/1.20.1-forge/src/main/java",
    ROOT / "version-src/1.21.1-neoforge/src/main/java",
    ROOT / "version-src/1.21.11-neoforge/src/main/java",
]

RETIRED = {
    "buildcraft.api.facades.FacadeAPI",
    "buildcraft.api.facades.FacadeType",
    "buildcraft.api.facades.IFacade",
    "buildcraft.api.facades.IFacadeItem",
    "buildcraft.api.facades.IFacadePhasedState",
    "buildcraft.api.facades.IFacadeRegistry",
    "buildcraft.api.facades.IFacadeState",
    "buildcraft.api.items.FluidItemDrops",
    "buildcraft.api.items.IItemFluidShard",
    "buildcraft.api.items.IList",
    "buildcraft.api.items.IMapLocation",
    "buildcraft.api.items.IMapLocation.MapLocationType",
    "buildcraft.api.items.INamedItem",
    "buildcraft.api.lists.ListMatchHandler",
    "buildcraft.api.lists.ListMatchHandler.Type",
    "buildcraft.api.lists.ListRegistry",
}

IMPORT = re.compile(r"^\s*import\s+(buildcraft\.api\.(?!v2\.)[^;]+);", re.MULTILINE)
SIMPLE_RETIRED = {
    "FacadeAPI", "IFacade", "IFacadeItem", "IFacadePhasedState", "IFacadeRegistry", "IFacadeState",
    "FluidItemDrops", "IItemFluidShard", "IList", "IMapLocation", "INamedItem", "ListMatchHandler", "ListRegistry",
}
COMMENT_RE = re.compile(r"/\*.*?\*/|//[^\n]*", re.DOTALL)


def read(rel: str) -> str:
    path = resolve_source_path(rel)
    if not path.is_file():
        raise AssertionError(f"missing {rel}")
    return path.read_text(encoding="utf-8", errors="ignore")


def require_tokens(errors: list[str], rel: str, *tokens: str) -> None:
    text = read(rel)
    for token in tokens:
        if token not in text:
            errors.append(f"{rel}: missing Facades/Lists/Map API2 hook {token}")


def main() -> int:
    errors: list[str] = []
    current: set[str] = set()

    for source_root in SOURCE_ROOTS:
        if not source_root.exists():
            continue
        for path in source_root.rglob("*.java"):
            text = path.read_text(encoding="utf-8", errors="ignore")
            rel = path.relative_to(ROOT)
            current.update(IMPORT.findall(text))
            for retired in RETIRED:
                if re.search(rf"\b(?:package|import)\s+{re.escape(retired)}(?:\s*;|\.)", text):
                    errors.append(f"{rel}: retired Facades/Lists/Map public symbol remains: {retired}")
            code = COMMENT_RE.sub("", text)
            for simple in SIMPLE_RETIRED:
                if re.search(rf"\b{re.escape(simple)}\b", code):
                    errors.append(f"{rel}: retired Facades/Lists/Map simple symbol remains in code: {simple}")

    stale = sorted(RETIRED & current)
    errors.extend(f"retired Facades/Lists/Map import still active: {symbol}" for symbol in stale)

    runtime = read("source-shared/src/main/java/buildcraft/lib/internal/api/v2/BuildCraftApiRuntime.java")
    for service in ("ITEM_LISTS", "ITEM_LABELS", "MAP_LOCATIONS", "FLUID_DROPS", "FACADES"):
        if f"BuildCraftServices.{service}" not in runtime:
            errors.append(f"BuildCraftApiRuntime does not install {service}")

    require_tokens(
        errors,
        "source-shared/src/main/java/buildcraft/lib/internal/api/v2/FacadeServiceImpl.java",
        "BuildCraftRegistries.FACADE_MATERIAL_ADAPTERS",
        "implements FacadeService",
    )
    require_tokens(
        errors,
        "source-shared/src/main/java/buildcraft/lib/internal/api/v2/ItemListServiceImpl.java",
        "implements ItemListService",
        "instanceof ItemListAdapter",
    )
    require_tokens(
        errors,
        "source-shared/src/main/java/buildcraft/lib/internal/api/v2/ItemLabelServiceImpl.java",
        "BuildCraftRegistries.ITEM_LABEL_ADAPTERS",
        "instanceof ItemLabelAdapter",
    )
    require_tokens(
        errors,
        "source-shared/src/main/java/buildcraft/lib/internal/api/v2/MapLocationServiceImpl.java",
        "BuildCraftRegistries.MAP_LOCATION_ADAPTERS",
        "instanceof MapLocationAdapter",
    )
    require_tokens(
        errors,
        "source-shared/src/main/java/buildcraft/lib/internal/api/v2/FluidDropServiceImpl.java",
        "BuildCraftRegistries.FLUID_DROP_PROVIDERS",
        "implements FluidDropService",
    )
    require_tokens(
        errors,
        "source-shared/src/main/java/buildcraft/lib/list/ListMatchHandlerBackend.java",
        "implements ListMatchAdapter",
        "ListMatchContext",
        "ListMatchType",
    )

    for rel in (
        "source-platforms/forge/src/main/java/buildcraft/lib/misc/StackUtil.java",
        "source-platforms/neoforge/src/main/java/buildcraft/lib/misc/StackUtil.java",
    ):
        require_tokens(errors, rel, "BuildCraftServices.ITEM_LISTS")

    for rel in (
        "source-platforms/forge/src/main/java/buildcraft/core/list/ContainerList.java",
        "source-platforms/neoforge/src/main/java/buildcraft/core/list/ContainerList.java",
    ):
        require_tokens(errors, rel, "BuildCraftServices.ITEM_LISTS", "BuildCraftServices.ITEM_LABELS")
        if "instanceof ItemList_BC8" in read(rel):
            errors.append(f"{rel}: list container still hardcodes ItemList_BC8 instead of ItemListService")

    for rel in (
        "version-src/1.19.2-forge/src/main/java/buildcraft/core/item/ItemList_BC8.java",
        "version-src/1.20.1-forge/src/main/java/buildcraft/core/item/ItemList_BC8.java",
        "source-platforms/neoforge/src/main/java/buildcraft/core/item/ItemList_BC8.java",
    ):
        require_tokens(errors, rel, "ItemListAdapter", "ItemLabelAdapter", "OperationMode")

    for rel in (
        "version-src/1.19.2-forge/src/main/java/buildcraft/core/item/ItemMapLocation.java",
        "version-src/1.20.1-forge/src/main/java/buildcraft/core/item/ItemMapLocation.java",
        "source-platforms/neoforge/src/main/java/buildcraft/core/item/ItemMapLocation.java",
    ):
        require_tokens(errors, rel, "MapLocationAdapter", "ItemLabelAdapter", "MapLocationView", "OperationMode")

    for rel in (
        "source-platforms/forge/src/main/java/buildcraft/lib/fluid/FluidDropRuntime.java",
        "source-platforms/neoforge/src/main/java/buildcraft/lib/fluid/FluidDropRuntime.java",
    ):
        require_tokens(errors, rel, "BuildCraftServices.FLUID_DROPS", "FluidDropContext")

    for rel in (
        "source-platforms/forge/src/main/java/buildcraft/core/client/RenderTickListener.java",
        "source-platforms/neoforge/src/main/java/buildcraft/core/client/RenderTickListener.java",
        "source-platforms/forge/src/main/java/buildcraft/robotics/tile/TileZonePlanner.java",
        "source-platforms/neoforge/src/main/java/buildcraft/robotics/tile/TileZonePlanner.java",
    ):
        require_tokens(errors, rel, "BuildCraftServices.MAP_LOCATIONS")
        text = read(rel)
        if "instanceof ItemMapLocation" in text:
            errors.append(f"{rel}: map-location runtime still hardcodes ItemMapLocation instead of API2 adapter lookup")

    fixture = "addon-fixture/src/main/java/dev/bcce/apifixture/ApiV2FixtureAddon.java"
    require_tokens(
        errors,
        fixture,
        "BuildCraftRegistries.FACADE_MATERIAL_ADAPTERS",
        "BuildCraftRegistries.LIST_MATCH_ADAPTERS",
        "BuildCraftRegistries.ITEM_LABEL_ADAPTERS",
        "BuildCraftRegistries.MAP_LOCATION_ADAPTERS",
        "BuildCraftRegistries.FLUID_DROP_PROVIDERS",
        "BuildCraftServices.ITEM_LISTS",
        "BuildCraftServices.ITEM_LABELS",
        "BuildCraftServices.MAP_LOCATIONS",
        "BuildCraftServices.FLUID_DROPS",
        "BuildCraftServices.FACADES",
    )

    if current:
        for symbol in sorted(current):
            errors.append(f"non-v2 BuildCraft API import remains after finalization: {symbol}")

    if errors:
        print("Facades / Lists / Map API2 migration FAILED:")
        for error in errors:
            print(f" - {error}")
        return 1

    print(
        "Facades / Lists / Map API2 runtime OK: retired public symbols remain absent; "
        "facade/list/map/label/fluid-drop runtime uses API2; 0 non-v2 BuildCraft API imports"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
