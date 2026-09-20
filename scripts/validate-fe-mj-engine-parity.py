#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
from source_lookup import resolve_source_path
errors = []


def text(rel):
    path = resolve_source_path(rel)
    if not path.is_file():
        errors.append(f"missing {rel}")
        return ""
    return path.read_text(encoding="utf-8", errors="ignore")


def require(rel, *tokens):
    source = text(rel)
    for token in tokens:
        if token not in source:
            errors.append(f"{rel}: missing FE/MJ parity guard {token!r}")


def forbid(rel, *tokens):
    source = text(rel)
    for token in tokens:
        if token in source:
            errors.append(f"{rel}: stale FE/MJ parity implementation {token!r}")


# BC8 engine animation keeps previous raw piston progress and interpolates at render time.
for platform in ("forge", "neoforge"):
    rel = f"source-platforms/{platform}/src/main/java/buildcraft/lib/engine/TileEngineBase_BC8.java"
    require(
        rel,
        "private float lastProgress;",
        "lastProgress = progress;",
        "float last = lastProgress;",
        "public float getRenderProgress(float partialTicks)",
        "computeRenderProgress(getProgressClient(partialTicks))",
    )
    forbid(rel, "float last = RenderProgress;")

for rel in (
    "version-src/1.19.2-forge/src/main/java/buildcraft/core/client/render/RenderEngine_BC8.java",
    "version-src/1.20.1-forge/src/main/java/buildcraft/core/client/render/RenderEngine_BC8.java",
    "source-platforms/neoforge/src/main/java/buildcraft/core/client/render/RenderEngine_BC8.java",
    "version-src/1.19.2-forge/src/main/java/buildcraft/energy/client/render/RenderDynamoMJ.java",
    "version-src/1.20.1-forge/src/main/java/buildcraft/energy/client/render/RenderDynamoMJ.java",
    "source-platforms/neoforge/src/main/java/buildcraft/energy/client/render/RenderDynamoMJ.java",
):
    require(rel, "getRenderProgress(partialTicks)")
    forbid(rel, "tile.RenderProgress")

# Original MJ Dynamo block actively revalidates its output direction and supports wrench rotation.
require(
    "source-shared/src/main/java/buildcraft/energy/block/BlockDynamoMJ.java",
    "implements EntityBlock, ICustomRotationHandler",
    "public void neighborChanged(",
    "dynamo.rotateIfInvalid();",
    "public InteractionResult attemptRotation(",
    "dynamo.attemptRotation()",
)

# FE Engine parity: FE enters on every side, MJ exits through the normal engine head, max chain = four additional engines.
for platform in ("forge", "neoforge"):
    rel = f"source-platforms/{platform}/src/main/java/buildcraft/energy/tile/TileEngineFE.java"
    require(
        rel,
        "return Optional.of(api2FeInputPort);",
        "getMaxChainLength() { return 4; }",
        "caps.addEnergyStorage(feStorage,",
        "EnumPipePart.VALUES",
    )
    forbid(rel, "return feCapability.cast();", "Capabilities.EnergyStorage.BLOCK")

# MJ Dynamo parity: MJ only enters non-output faces; FE only exits currentDirection; aligned chain length is three.
for platform in ("forge", "neoforge"):
    rel = f"source-platforms/{platform}/src/main/java/buildcraft/energy/tile/TileDynamoMJ.java"
    require(
        rel,
        "return side != currentDirection ? Optional.of(mjReceiver) : Optional.empty();",
        "return side == currentDirection ? Optional.of(api2FeOutputPort) : Optional.empty();",
        "getMaxChainLength() { return 3; }",
        "public boolean isPoweredTile(BlockEntity tile, Direction side)",
        "return getFeReceiver(tile, side) != null;",
    )

require(
    "source-platforms/forge/src/main/java/buildcraft/energy/tile/TileDynamoMJ.java",
    "caps.addEnergyStorage(side -> side == currentDirection ? feStorage : null,",
    "PlatformStorage.energy(tile, side.getOpposite())",
)
require(
    "source-platforms/neoforge/src/main/java/buildcraft/energy/tile/TileDynamoMJ.java",
    "caps.addEnergyStorage(side -> side == currentDirection ? feStorage : null,",
    "PlatformStorage.energy(",
    "level, tile.getBlockPos(), side.getOpposite()",
)

# Jade must treat the standalone Dynamo as a Dynamo rather than the generic engine block.
for rel in (
    "version-src/1.19.2-forge/src/main/java/buildcraft/compat/jade/BuildCraftJadePlugin.java",
    "version-src/1.20.1-forge/src/main/java/buildcraft/compat/jade/BuildCraftJadePlugin.java",
    "source-platforms/neoforge/src/main/java/buildcraft/compat/jade/BuildCraftJadePlugin.java",
):
    require(
        rel,
        "engineNameKey(engine)",
        'return "block.buildcraftenergy.mj_dynamo";',
        "dynamo.getMjStored(), dynamo.getMjCapacity()",
        'tag.putBoolean("OutputFe", engine instanceof TileDynamoMJ);',
        "LocaleUtil.localizeFeFlow(output)",
        "LocaleUtil.localizeMjFlow(output)",
    )

# Remote FE lookup is delegated, not removed. Keep native side-aware behavior below the lib boundary.
require("source-platforms/forge/src/main/java/buildcraft/lib/platform/storage/PlatformStorage.java", "provider.getCapability(CapUtil.CAP_FE, face).orElse(null)")
require("source-platforms/neoforge/src/main/java/buildcraft/lib/platform/storage/PlatformStorage.java", "CapUtil.getEnergyStorage(level, pos, face)")


if errors:
    for error in errors:
        print("ERROR:", error)
    sys.exit(1)

print("FE Engine / MJ Dynamo parity guards OK")
print(" - MJ Dynamo Jade identity, MJ battery and FE output units are explicit")
print(" - engine piston rendering interpolates previous/current raw progress")
print(" - MJ Dynamo neighbour/wrench rotation matches the original block contract")
print(" - FE Engine and MJ Dynamo sided energy/chain rules match BC8")
