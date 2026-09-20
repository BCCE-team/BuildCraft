#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[3]
SCRIPT_DIR = ROOT / "scripts"
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))
from source_lookup import resolve_source_path
errors = []


def text(rel):
    path = resolve_source_path(rel)
    if not path.is_file():
        errors.append(f"missing {rel}")
        return ""
    return path.read_text(encoding="utf-8")


def require(rel, *tokens):
    source = text(rel)
    for token in tokens:
        if token not in source:
            errors.append(f"{rel}: missing machine regression guard {token!r}")


require(
    "source-shared/src/main/java/buildcraft/builders/snapshot/SchematicBlockManager.java",
    "readFromNBTAllowUnavailable",
    "new UnavailableSchematicBlock(tag)",
    "isUnavailable(ISchematicBlock schematicBlock)",
    "unavailable.serializedEnvelope()",
)
require(
    "source-shared/src/main/java/buildcraft/builders/snapshot/UnavailableSchematicBlock.java",
    "Lossless placeholder",
    "return true;",
    "serializedEnvelope()",
)
require(
    "source-shared/src/main/java/buildcraft/builders/snapshot/SchematicEntityManager.java",
    "readFromNBTAllowUnavailable",
    "new UnavailableSchematicEntity(tag)",
    "isUnavailable(ISchematicEntity schematicEntity)",
)
require(
    "source-shared/src/main/java/buildcraft/builders/snapshot/UnavailableSchematicEntity.java",
    "Lossless placeholder",
    "serializedEnvelope()",
)
require(
    "source-shared/src/main/java/buildcraft/builders/internal/schematic/api2/UnavailableSchematicAdapters.java",
    "boolean isPlaced",
    "return true;",
)
for platform in ("forge", "neoforge"):
    require(
        f"source-platforms/{platform}/src/main/java/buildcraft/builders/snapshot/Blueprint.java",
        "readFromNBTAllowUnavailable",
        "SchematicEntityManager.readFromNBTAllowUnavailable",
        "SchematicEntityManager.isUnavailable",
        "getUnavailableSchematicCount()",
    )
    require(
        f"source-platforms/{platform}/src/main/java/buildcraft/silicon/gate/GateLogic.java",
        "List<PendingGateAction> pendingActions",
        "targetsSameSetting",
        "the last row wins",
    )
    require(
        f"source-platforms/{platform}/src/main/java/buildcraft/factory/tile/TileHeatExchange.java",
        "mergeDuplicateSection",
        "mergeTankContents",
        "duplicate.tankManager.addDrops",
    )
    require(
        f"source-platforms/{platform}/src/main/java/buildcraft/lib/misc/JsonUtil.java",
        "entry.getValue().deepCopy()",
    )
    require(
        f"source-platforms/{platform}/src/gametest/java/buildcraft/gametest/CoreIntegrityGameTests.java",
        "unavailableBlueprintElementsRoundTripLosslessly",
        "relatedGateActionVariantsTargetOneSetting",
        "jsonInlineCopiesAreIndependent",
    )

require(
    "source-shared/src/main/java/buildcraft/lib/statement/ActionWrapper.java",
    "targetsSameSetting(ActionWrapper other)",
    "ctx.getAllPossible()",
)
for family in ("legacy", "modern"):
    require(
        f"source-families/{family}/src/main/java/buildcraft/lib/gui/statement/GuiElementStatement.java",
        "ctx.getAllPossible()",
        "possible.removeIf",
    )

require(
    "source-shared/src/main/java/buildcraft/lib/client/model/json/VariablePartTextureExpand.java",
    "VariableFaceData data = faceUv.evaluate(spriteLookup)",
    "data.uvs.minU",
    "quad.rotate(Direction.SOUTH, targetFace",
    "quad.texFromSprite(data.sprite)",
    "addTo.add(quad)",
)

for rel in (
    "version-src/1.19.2-forge/src/main/java/buildcraft/builders/item/ItemSnapshot.java",
    "version-src/1.20.1-forge/src/main/java/buildcraft/builders/item/ItemSnapshot.java",
    "source-platforms/neoforge/src/main/java/buildcraft/builders/item/ItemSnapshot.java",
):
    require(rel, "getUnavailableSchematicCount()", "blueprint.unavailable_elements")

# The six audited machine markers must be resolved rather than hidden behind non-actionable comments.
for path in ROOT.rglob("*.java"):
    if any(part in {"build", ".gradle"} for part in path.parts):
        continue
    source = path.read_text(encoding="utf-8", errors="ignore")
    for obsolete in (
        "Support partial blueprint loading when some schematic elements are unavailable",
        "Define merge/override semantics when multiple gate actions target the same setting",
        "Validate generated side-wrapped gate actions against the active GUI/container context",
        "Merge compatible adjacent Heat Exchanger sections instead of discarding duplicates",
        "We really need to deep-copy the element",
        "Restore texture_expand rendering and apply the requested UV sub-region",
    ):
        if obsolete in source:
            errors.append(f"{path.relative_to(ROOT)}: unresolved audited machine marker {obsolete!r}")

if errors:
    for error in errors:
        print("ERROR:", error)
    sys.exit(1)

print("Machine regression guards OK")
print(" - missing-addon blueprint blocks are lossless and non-destructive")
print(" - same-setting gate actions coalesce with last-row-wins semantics")
print(" - side-wrapped statement choices remain context-filtered")
print(" - Heat Exchanger duplicate sections preserve or drop all fluid")
print(" - JSON inline expansion deep-copies mutable values")
print(" - texture_expand emits UV-aware geometry on maintained targets")
