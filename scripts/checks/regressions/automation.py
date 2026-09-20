#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[3]
SCRIPT_DIR = ROOT / "scripts"
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))
from source_lookup import resolve_source_path, resolve_target_source
errors = []


def read(rel):
    path = resolve_source_path(rel)
    if not path.is_file():
        errors.append(f"missing {rel}")
        return ""
    return path.read_text(encoding="utf-8")


def require(rel, *tokens):
    text = read(rel)
    for token in tokens:
        if token not in text:
            errors.append(f"{rel}: missing automation regression guard {token!r}")


def forbid(rel, *tokens):
    text = read(rel)
    for token in tokens:
        if token in text:
            errors.append(f"{rel}: forbidden automation regression token {token!r}")


# 1.21.1 automation identity must be a real NeoForge FakePlayer, not a hand-rolled ServerPlayer.
require(
    "source-platforms/neoforge/src/main/java/buildcraft/lib/fake/FakePlayerBC.java",
    "import net.neoforged.neoforge.common.util.FakePlayer;",
    "public class FakePlayerBC extends FakePlayer",
    "super(level, profile);",
)
forbid(
    "source-platforms/neoforge/src/main/java/buildcraft/lib/fake/FakePlayerBC.java",
    "extends ServerPlayer",
    "DiscardingConnection",
    "ServerGamePacketListenerImpl",
)
for platform in ("forge", "neoforge"):
    require(
        f"source-platforms/{platform}/src/gametest/java/buildcraft/gametest/PermissionOwnerGameTests.java",
        "buildCraftAutomationPlayerUsesPlatformFakePlayer",
        "player instanceof FakePlayer",
    )

# 1.21 JEI must retain RecipeHolder identity instead of grouping every codec-default recipe under unnamed id.
jei = "source-platforms/neoforge/src/main/java/buildcraft/compat/jei/BuildCraftJeiPlugin.java"
require(
    jei,
    "private record AssemblyJeiRecipe(ResourceLocation id, AssemblyRecipeBasic recipe)",
    ".map(holder -> new AssemblyJeiRecipe(holder.id(), holder.value()))",
    "representative.id()",
    "GROUPED_ASSEMBLY_RECIPES.get(view.id())",
)
forbid(jei, ".map(RecipeHolder::value)", "GROUPED_ASSEMBLY_RECIPES.get(recipe.getId())")

# Picker reservations must be globally unambiguous across dimensions and runtime entity-id reuse.
require(
    "source-shared/src/main/java/buildcraft/robotics/boards/BoardRobotPicker.java",
    "record TargetKey(ResourceKey<Level> dimension, UUID itemUuid)",
    "item.getCommandSenderWorld().dimension()",
    "item.getUUID()",
)
for family in ("legacy", "modern"):
    rel = f"source-families/{family}/src/main/java/buildcraft/robotics/ai/AIRobotFetchItem.java"
    require(rel, "BoardRobotPicker.TargetKey.of(item)", "targetReservation", "targettedItems.add(targetReservation)")
    forbid(rel, "targettedItems.contains(item.getId())", "targettedItems.add(targetId)")

# Wire updates must be scoped to the actual player's tracked chunks, not global ticking-range state.
# Legacy keeps the shared implementation while modern intentionally has a canonical 1.21.11
# implementation plus a 1.21.1 downport.  Validate the effective modern views instead of
# rejecting that newest-first ownership model.
rel = "source-shared/src/main/java/buildcraft/transport/wire/WireSystem.java"
require(
    rel,
    "//? if <1.20 {",
    "ServerLevel world = (ServerLevel) serverPlayer.level;",
    "ServerLevel world = serverPlayer.serverLevel();",
    "chunkMap.getPlayers(chunkPos, false).contains(serverPlayer)",
    ".distinct()",
)
forbid(rel, "inBlockTickingRange", "player.level instanceof ServerLevel", "player.level() instanceof ServerLevel")

wire_logical = "src/main/java/buildcraft/transport/wire/WireSystem.java"
for target, world_expr in (
    ("1.21.1-neoforge", "ServerLevel world = serverPlayer.serverLevel();"),
    ("1.21.11-neoforge", "ServerLevel world = ((net.minecraft.server.level.ServerLevel) serverPlayer.level());"),
):
    path = resolve_target_source(target, wire_logical)
    text = path.read_text(encoding="utf-8") if path.is_file() else ""
    if not text:
        errors.append(f"{target}: missing effective WireSystem")
        continue
    for token in (world_expr, "chunkMap.getPlayers(chunkPos, false).contains(serverPlayer)", ".distinct()"):
        if token not in text:
            errors.append(f"{target} WireSystem: missing automation regression guard {token!r}")
    for token in ("inBlockTickingRange", "player.level instanceof ServerLevel", "player.level() instanceof ServerLevel"):
        if token in text:
            errors.append(f"{target} WireSystem: forbidden automation regression token {token!r}")

# Bomber may not consume TNT or prime an explosion before zone/API2 checks for the affected area succeed.
for family in ("legacy", "modern"):
    rel = f"source-families/{family}/src/main/java/buildcraft/robotics/boards/BoardRobotBomber.java"
    require(
        rel,
        "BLAST_SAFETY_RADIUS = 6",
        "canBombTarget(target, OperationMode.SIMULATE)",
        "canBombTarget(bombTarget, OperationMode.EXECUTE)",
        "zone != null && !zone.contains(affected)",
        "WorldOperationKind.BLOCK_BREAK, mode",
        "private void dropTnt(BlockPos target)",
    )

if errors:
    print("ERROR: automation regression validation failed")
    for error in errors:
        print(" -", error)
    raise SystemExit(1)
print("OK: automation regression guards are present")
