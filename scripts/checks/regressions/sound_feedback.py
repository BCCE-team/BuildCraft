#!/usr/bin/env python3
"""Keep audible feedback on all automated BuildCraft world mutations."""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[3]

VARIANTS = (
    "source-platforms/forge/src/main/java/buildcraft/lib/misc/BlockUtil.java",
    "source-downports/1.21.X/1.21.1/neoforge/src/main/java/buildcraft/lib/misc/BlockUtil.java",
    "source-family-platforms/1.21.X/neoforge/src/main/java/buildcraft/lib/misc/BlockUtil.java",
    "source-family-platforms/26.X/neoforge/src/main/java/buildcraft/lib/misc/BlockUtil.java",
)

STRIPES_VARIANTS = (
    "source-platforms/forge/src/main/java/buildcraft/transport/stripes/PipeExtensionManager.java",
    "source-platforms/neoforge/src/main/java/buildcraft/transport/stripes/PipeExtensionManager.java",
    "source-family-platforms/26.X/neoforge/src/main/java/buildcraft/transport/stripes/PipeExtensionManager.java",
)

REQUIRED = (
    "boolean placed = PlatformWorldActions.placeBlock",
    "SoundUtil.playBlockPlace(level, pos, state);",
    "SoundUtil.playBlockBreak(world, pos, state);",
    "if (!world.destroyBlock(pos, true))",
)

errors = []
for rel in VARIANTS:
    path = ROOT / rel
    if not path.is_file():
        errors.append(f"missing {rel}")
        continue
    content = path.read_text(encoding="utf-8")
    for token in REQUIRED:
        if token not in content:
            errors.append(f"{rel}: missing automated sound feedback {token!r}")

for rel in STRIPES_VARIANTS:
    path = ROOT / rel
    if not path.is_file():
        errors.append(f"missing {rel}")
        continue
    if path.read_text(encoding="utf-8").count("SoundUtil.playBlockPlace(w, p, stripesStateOld);") != 2:
        errors.append(f"{rel}: both successful Stripes pipe moves must play a placement sound")

if errors:
    for error in errors:
        print("ERROR:", error)
    raise SystemExit(1)

print("Automated block place/break sound feedback guards OK")

# BC8 sound parity. BuildCraft 8 did not ship a custom sounds.json/OGG bank: its audible identity came from
# vanilla SoundEvents, per-block SoundType, fluid action sounds, and world event 2001 for robot breaking.
# Validate the effective source selected by every maintained target so overlays/downports cannot silently regress it.
SCRIPT_DIR = ROOT / "scripts"
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))
from source_lookup import resolve_target_source

TARGETS = (
    "1.19.2-forge",
    "1.20.1-forge",
    "1.21.1-neoforge",
    "1.21.11-neoforge",
    "26.1.2-neoforge",
)


def require_effective(target: str, logical: str, *tokens: str) -> None:
    path = resolve_target_source(target, logical)
    if not path.is_file():
        errors.append(f"{target}: missing effective {logical}")
        return
    content = path.read_text(encoding="utf-8", errors="ignore")
    for token in tokens:
        if token not in content:
            errors.append(f"{target}: {logical} missing original sound parity token {token!r}")


def forbid_effective(target: str, logical: str, *tokens: str) -> None:
    path = resolve_target_source(target, logical)
    if not path.is_file():
        errors.append(f"{target}: missing effective {logical}")
        return
    content = path.read_text(encoding="utf-8", errors="ignore")
    for token in tokens:
        if token in content:
            errors.append(f"{target}: {logical} retains non-original sound token {token!r}")


for target in TARGETS:
    # SoundUtil contract from BC8: lever click, piston slide, slime paint, block state place/break and fluid action sounds.
    sound_util = "src/main/java/buildcraft/lib/misc/SoundUtil.java"
    require_effective(
        target,
        sound_util,
        "SoundEvents.LEVER_CLICK",
        "SoundEvents.PISTON_CONTRACT",
        "SoundEvents.PISTON_EXTEND",
        "SoundEvents.SLIME_SQUISH",
        "soundType.getPlaceSound()",
        "soundType.getBreakSound()",
        "SoundSource.PLAYERS",
    )
    forbid_effective(target, sound_util, "SoundEvents.LAVA_POP")

    # BC8's BlockBCBase_Neptune defaulted to METAL. These blocks existed in BC8 and did not override it there.
    for logical in (
        "src/main/java/buildcraft/factory/block/BlockTank.java",
        "src/main/java/buildcraft/factory/block/BlockDistiller.java",
        "src/main/java/buildcraft/factory/block/BlockHeatExchange.java",
        "src/main/java/buildcraft/builders/block/BlockFrame.java",
        "src/main/java/buildcraft/lib/block/BlockMarkerBase.java",
        "src/main/java/buildcraft/transport/block/BlockPipeHolder.java",
    ):
        require_effective(target, logical, "SoundType.METAL")

    forbid_effective(target, "src/main/java/buildcraft/factory/block/BlockTank.java", "SoundType.GLASS")
    forbid_effective(target, "src/main/java/buildcraft/factory/block/BlockDistiller.java", "SoundType.GLASS")
    forbid_effective(target, "src/main/java/buildcraft/factory/block/BlockHeatExchange.java", "SoundType.GLASS")
    forbid_effective(target, "src/main/java/buildcraft/builders/block/BlockFrame.java", "SoundType.STONE")
    forbid_effective(target, "src/main/java/buildcraft/lib/block/BlockMarkerBase.java", "SoundType.WOOD")
    forbid_effective(target, "src/main/java/buildcraft/transport/block/BlockPipeHolder.java", "SoundType.STONE")

    # Requester is restored BC7-only content. Its last original implementation inherited vanilla's stone step sound.
    require_effective(target, "src/main/java/buildcraft/robotics/block/BlockRequester.java", "SoundType.STONE")

    # Intentional original exceptions to the BC8 METAL default.
    require_effective(target, "src/main/java/buildcraft/builders/block/BlockQuarry.java", "SoundType.ANVIL")
    require_effective(target, "src/main/java/buildcraft/core/block/BlockSpring.java", "SoundType.STONE")
    require_effective(target, "src/main/java/buildcraft/factory/block/BlockWaterGel.java", "SoundType.SLIME_BLOCK")
    require_effective(target, "src/main/java/buildcraft/robotics/block/BlockZonePlanner.java", "SoundType.STONE")
    forbid_effective(target, "src/main/java/buildcraft/robotics/block/BlockZonePlanner.java", "SoundType.METAL")

    # Explicit original interaction/automation feedback.
    expected_calls = {
        "src/main/java/buildcraft/core/item/ItemWrench.java": ("SoundUtil.playSlideSound",),
        "src/main/java/buildcraft/lib/item/ItemPluggableSimple.java": ("SoundUtil.playBlockPlace",),
        "src/main/java/buildcraft/lib/fluid/Tank.java": (
            "ItemStack transferStackToTank",
            "SoundUtil.playBucketEmpty",
            "SoundUtil.playBucketFill",
        ),
        "src/main/java/buildcraft/silicon/item/ItemPluggableGate.java": ("SoundUtil.playBlockPlace",),
        "src/main/java/buildcraft/silicon/item/ItemPluggableLens.java": ("SoundUtil.playBlockPlace",),
        "src/main/java/buildcraft/silicon/item/ItemPluggableFacade.java": ("SoundUtil.playBlockPlace",),
        "src/main/java/buildcraft/core/item/ItemPaintbrush_BC8.java": ("SoundUtil.playChangeColour",),
        "src/main/java/buildcraft/silicon/plug/PluggablePulsar.java": ("SoundUtil.playLeverSwitch",),
        "src/main/java/buildcraft/builders/item/ItemSchematicSingle.java": ("SoundUtil.playBlockPlace",),
        "src/main/java/buildcraft/factory/tile/TileHeatExchange.java": ("SoundUtil.playSlideSound",),
        "src/main/java/buildcraft/factory/item/ItemWaterGel.java": ("SoundEvents.SNOWBALL_THROW",),
        "src/main/java/buildcraft/robotics/boards/BoardRobotBomber.java": ("SoundEvents.TNT_PRIMED",),
        "src/main/java/buildcraft/robotics/ai/AIRobotBreak.java": ("levelEvent(null, 2001",),
    }
    for logical, tokens in expected_calls.items():
        require_effective(target, logical, *tokens)

    # BC8 played bucket sounds at the committed transfer itself, so shift-click/container-slot paths must not
    # depend on a GUI-widget-only before/after wrapper.
    forbid_effective(target, "src/main/java/buildcraft/lib/fluid/Tank.java", "playCommittedGuiTransferSound")

    # Forge 1.12 Fluid supplied generic bucket fill/empty sounds by default. Modern FluidType does not, so every
    # BuildCraft oil/fuel FluidType must explicitly restore those defaults; this also fixes world pickup and native
    # loader fluid-container interactions, not just BuildCraft's own tank GUI.
    require_effective(
        target,
        "src/main/java/buildcraft/energy/BCEnergyFluids.java",
        "SoundActions.BUCKET_FILL",
        "SoundActions.BUCKET_EMPTY",
        "SoundEvents.BUCKET_FILL",
        "SoundEvents.BUCKET_EMPTY",
    )

    # NeoForge's generic item<->handler helper skips playback when a third-party FluidType omitted an action sound.
    # BC8 still fell back to the generic bucket sound, so BuildCraft's direct machine/tank interaction keeps that
    # fallback without duplicating fluids that already provide their own sound.
    if target.endswith("-neoforge"):
        require_effective(
            target,
            "src/main/java/buildcraft/lib/misc/FluidUtilBC.java",
            "snapshotFluids(fluidHandler)",
            "SoundUtil.playBucketEmptyFallbackIfMissing",
            "SoundUtil.playBucketFillFallbackIfMissing",
        )
        require_effective(
            target,
            "src/main/java/buildcraft/lib/misc/SoundUtil.java",
            "playBucketEmptyFallbackIfMissing",
            "playBucketFillFallbackIfMissing",
            "SoundSource.PLAYERS",
        )

if errors:
    for error in errors:
        print("ERROR:", error)
    raise SystemExit(1)

print("Original BuildCraft sound parity guards OK for all maintained targets")
