#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[3]
SCRIPT_DIR = ROOT / "scripts"
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))
from source_lookup import resolve_source_path


def text(rel: str) -> str:
    path = resolve_source_path(rel)
    if not path.is_file():
        raise SystemExit(f"ERROR: missing {rel}")
    return path.read_text(encoding="utf-8")


def require(rel: str, token: str) -> None:
    data = text(rel)
    if token not in data:
        raise SystemExit(f"ERROR: {rel}: missing regression guard {token!r}")


def forbid(rel: str, token: str) -> None:
    data = text(rel)
    if token in data:
        raise SystemExit(f"ERROR: {rel}: forbidden critical regression token remains: {token!r}")


stripes = "source-shared/src/main/java/buildcraft/transport/stripes/StripesHandlerEntityInteract.java"
require(stripes, "player.interactOn(entity, InteractionHand.MAIN_HAND).consumesAction()")
forbid(stripes, "== InteractionResult.SUCCESS")

workbench = "source-shared/src/main/java/buildcraft/factory/tile/TileAutoWorkbenchItems.java"
require(workbench, "public int getContainerSize()")
require(workbench, "return 1;")
require(workbench, "return index == 0 ? invResult.getStackInSlot(0) : ItemStack.EMPTY;")
require(workbench, "return index == 0 ? invResult.extractItem(0, num, false) : ItemStack.EMPTY;")
require(workbench, "return false;")
forbid(workbench, "return invMaterialFilter.getStackInSlot")
forbid(workbench, "return invMaterialFilter.extractItem")
forbid(workbench, "invMaterialFilter.setStackInSlot(index-1")

for platform in ("forge", "neoforge"):
    suite = f"source-platforms/{platform}/src/gametest/java/buildcraft/gametest/CriticalGameplayGameTests.java"
    require(suite, "stripesEntityInteractCommitsConsumingServerResult")
    require(suite, "StripesApi2Bridge.item(StripesHandlerEntityInteract.INSTANCE::handle).activate(context)")
    require(suite, "result.status() == AutomationResult.Status.SUCCESS")
    require(suite, "context.stack().getCount() == 1")
    require(suite, "autoWorkbenchContainerNeverExposesPhantomFilters")
    require(suite, "tile.getContainerSize() == 1")
    require(suite, "tile.removeItem(1, 1).isEmpty()")
    require(suite, "tile.invMaterialFilter.getStackInSlot(0).is(Items.DIAMOND)")

print("Current critical regression guards passed")
print(" - Stripes entity interactions commit all consuming server results")
print(" - Auto Workbench external Container exposes only the real crafted-result slot")
print(" - 2 economy-breaking GameTests are present on Forge and NeoForge")
