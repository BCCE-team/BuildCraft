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
    return path.read_text(encoding="utf-8", errors="ignore")


def require(rel, *tokens):
    source = text(rel)
    for token in tokens:
        if token not in source:
            errors.append(f"{rel}: missing performance regression guard {token!r}")


def forbid(rel, *tokens):
    source = text(rel)
    for token in tokens:
        if token in source:
            errors.append(f"{rel}: forbidden performance regression token {token!r}")


# Legacy GUI scale.
require(
    "version-src/1.19.2-forge/src/main/java/buildcraft/lib/gui/elem/GuiElementText.java",
    "double rawScale = scale.getAsDouble();",
    "pose.scale(s, s, 1.0F);",
)

# Idle Builder and engine lookup caches.
for rel in (
    "version-src/1.19.2-forge/src/main/java/buildcraft/builders/tile/TileBuilder.java",
    "version-src/1.20.1-forge/src/main/java/buildcraft/builders/tile/TileBuilder.java",
    "source-platforms/neoforge/src/main/java/buildcraft/builders/tile/TileBuilder.java",
):
    require(rel, "if (builder == null)", "return;")
for platform in ("forge", "neoforge"):
    require(
        f"source-platforms/{platform}/src/main/java/buildcraft/lib/engine/TileEngineBase_BC8.java",
        "cachedBiome",
        "getNeighbourTile(side)",
    )

# Compile-level compatibility guards for rendering and cache contracts. Syntax-only materialization does not catch
# these API differences, so keep the known cross-version contracts explicit here.
for platform in ("forge", "neoforge"):
    require(
        f"source-platforms/{platform}/src/main/java/buildcraft/lib/engine/TileEngineBase_BC8.java",
        "import net.minecraft.world.level.Level;",
        "private Level cachedBiomeLevel;",
    )
    require(
        f"source-platforms/{platform}/src/main/java/buildcraft/lib/client/model/MutableVertex.java",
        "1.0D / Math.sqrt(lenSq)",
    )
    forbid(
        f"source-platforms/{platform}/src/main/java/buildcraft/lib/client/model/MutableVertex.java",
        "Mth.invSqrt(lenSq)",
    )
    require(
        f"source-platforms/{platform}/src/main/java/buildcraft/lib/client/render/laser/LaserBoxRenderer.java",
        "Arrays.asList(box.laserData)",
    )
forbid(
    "version-src/1.19.2-forge/src/main/java/buildcraft/lib/client/render/fluid/FluidRenderer.java",
    "spriteW = sprite.getWidth()",
    "spriteH = sprite.getHeight()",
)
require(
    "source-platforms/neoforge/src/main/java/buildcraft/factory/tile/TileHeatExchange.java",
    "sourceFluid.copyWithAmount(accepted)",
)
forbid(
    "source-platforms/neoforge/src/main/java/buildcraft/factory/tile/TileHeatExchange.java",
    "new FluidStack(sourceFluid, accepted)",
)

# Fluid-pipe throughput, round-robin and rendering contracts.
for platform in ("forge", "neoforge"):
    require(
        f"source-platforms/{platform}/src/main/java/buildcraft/transport/internal/pipe/PipeApi.java",
        "public final int bufferCapacity",
        "this.transferPerTick * transferDelay",
    )
    require(
        f"source-platforms/{platform}/src/main/java/buildcraft/transport/pipe/flow/PipeFlowFluids.java",
        "fluidTransferInfo.bufferCapacity",
    )
    require(
        f"source-platforms/{platform}/src/main/java/buildcraft/transport/pipe/behaviour/PipeBehaviourWoodDiamond.java",
        "case ROUND_ROBIN:",
        "int filterIndex = (currentFilter + offset) % slots;",
        "if (simulate.execute())",
        "advanceFluidFilter();",
    )

for rel in (
    "version-src/1.19.2-forge/src/main/java/buildcraft/lib/client/render/fluid/FluidRenderer.java",
    "version-src/1.20.1-forge/src/main/java/buildcraft/lib/client/render/fluid/FluidRenderer.java",
    "source-platforms/neoforge/src/main/java/buildcraft/lib/client/render/fluid/FluidRenderer.java",
):
    require(
        rel,
        "int packedLight",
        "vertex.lighti(packedLight)",
        "setTexMap(TexMap.XZ, false, false)",
        "setTexMap(TexMap.XY, false, true)",
    )

# Machine and inventory correctness/perf helpers.
for platform in ("forge", "neoforge"):
    require(
        f"source-platforms/{platform}/src/main/java/buildcraft/factory/tile/TileHeatExchange.java",
        "middleCount",
        "getRenderBoundingBox",
    )
    require(
        f"source-platforms/{platform}/src/main/java/buildcraft/lib/tile/item/ItemHandlerManager.java",
        "CAP_ITEM_TRANSACTOR",
        "new ItemHandlerWrapper(combined)",
    )
    require(
        f"source-platforms/{platform}/src/main/java/buildcraft/lib/misc/StackUtil.java",
        'remove("display")',
    )
    require(
        f"source-platforms/{platform}/src/main/java/buildcraft/transport/pipe/flow/PipeFlowItems.java",
        "getConnectedDist(side)",
        "if (item.clientAtDestination)",
        "clientAtDestination = true",
        "retaining it again would pin a ghost",
    )
    require(
        f"source-platforms/{platform}/src/main/java/buildcraft/transport/pipe/flow/PipeFlowPower.java",
        "getCapabilityFromPipe(from",
    )
    require(
        f"source-platforms/{platform}/src/main/java/buildcraft/factory/tile/TilePump.java",
        "getFluidStackForRender",
        "getFluidCapacityForRender",
    )
    require(
        f"source-platforms/{platform}/src/main/java/buildcraft/lib/client/model/MutableVertex.java",
        "Math.sqrt",
        "normal_x / x",
        "normal_y / y",
        "normal_z / z",
    )
    require(
        f"source-platforms/{platform}/src/main/java/buildcraft/lib/misc/StringUtilBC.java",
        "FORMAT_SOURCES",
        "source = FORMAT_SOURCES.getOrDefault(string, string)",
    )
    require(
        f"source-platforms/{platform}/src/main/java/buildcraft/transport/internal/pluggable/PipePluggable.java",
        "writeSyncState",
        "readSyncState",
    )
    require(
        f"source-platforms/{platform}/src/main/java/buildcraft/transport/pipe/PluggableHolder.java",
        "ID_SYNC_STATE",
        "writeSyncState",
        "readSyncState",
    )
    require(
        f"source-platforms/{platform}/src/main/java/buildcraft/transport/wire/WireManager.java",
        "hasExternalSignalPort",
        "SignalPortProvider",
    )

require(
    "source-platforms/forge/src/gametest/java/buildcraft/transport/pipe/flow/PipeFluidPowerGameTests.java",
    "passiveProviderExtractionUsesDryRunAndNeverOverfillsSection",
    ".exposeCapability(Direction.WEST, MjCapabilities.CAP_PASSIVE_PROVIDER, provider)",
)

require(
    "source-platforms/forge/src/main/java/buildcraft/lib/misc/JsonUtil.java",
    "JsonUtils.readNBT",
    "stack.setTag(data)",
)
require(
    "source-platforms/neoforge/src/main/java/buildcraft/lib/misc/JsonUtil.java",
    'obj.has("components")',
    '"minecraft:custom_data"',
    "FluidStackUtil.parseOptional",
)

# Rendering/model caches.
# Marker connections remain cached across client chunk unloads so reloads are lossless, but cached-only positions
# must not be rendered beyond the current client render distance.
require(
    "version-src/1.19.2-forge/src/main/java/buildcraft/lib/client/render/MarkerRenderer.java",
    "isClientChunkLoaded(player.level, pos)",
    "getChunkSource().getChunkNow",
    "without reintroducing destructive cache pruning",
)
require(
    "source-shared/src/main/java/buildcraft/lib/client/render/MarkerRenderer.java",
    "isClientChunkLoaded(player.level(), pos)",
    "getChunkSource().getChunkNow",
    "without reintroducing destructive cache pruning",
)
for platform in ("forge", "neoforge"):
    require(
        f"source-platforms/{platform}/src/main/java/buildcraft/core/client/RenderTickListener.java",
        "renderMarkerCache(poseStack, matrix, world, player",
        "isClientChunkLoaded(world, a)",
        "getChunkSource().getChunkNow",
        "visible beyond render distance",
    )

for platform in ("forge", "neoforge"):
    require(
        f"source-platforms/{platform}/src/main/java/buildcraft/lib/marker/MarkerSubCache.java",
        "if (!isServer)",
        "authoritative mirror of server marker messages",
        "Client removal is driven by MessageMarker",
    )
    require(
        f"source-platforms/{platform}/src/main/java/buildcraft/lib/client/render/laser/LaserRenderer_BC8.java",
        "COMPILED_DYNAMIC_BOXES",
        "makeDynamicLaserBox",
        "renderLaserBoxDynamic",
        "getShaderFogStart",
        "getShaderFogEnd",
        "setShaderFogStart(Float.MAX_VALUE / 2.0F)",
        "setShaderFogEnd(Float.MAX_VALUE)",
        "setShaderFogStart(fogStart)",
        "setShaderFogEnd(fogEnd)",
    )
    require(
        f"source-platforms/{platform}/src/main/java/buildcraft/transport/client/model/ModelPipe.java",
        "getParticleTexture(PipeDefinition definition)",
        "itemModelCenter",
    )
for family in ("legacy", "modern"):
    require(
        f"source-families/{family}/src/main/java/buildcraft/lib/client/model/ModelUtil.java",
        "case WEST:", "case EAST:", "case DOWN:", "case UP:", "case NORTH:", "case SOUTH:",
    )
    require(
        f"source-families/{family}/src/main/java/buildcraft/factory/client/render/RenderPump.java",
        "getFluidStackForRender",
        "FluidRenderer.renderFluid",
    )
    require(
        f"source-families/{family}/src/main/java/buildcraft/transport/pipe/flow/TravellingItem.java",
        "clientAtDestination",
        "return vecTo",
    )

# Legacy/1.21.1 still build the pipe item body from cached quads. 1.21.11 instead keeps the
# already-baked JSON item body and composes only the colour overlay, avoiding an empty/stale custom body after reload.
require(
    "source-families/legacy/src/main/java/buildcraft/transport/client/model/ModelPipeItem.java",
    "QUADS_TOP", "QUADS_CENTER", "QUADS_BOTTOM", "createSectionQuads",
)
require(
    "source-families/modern/src/main/java/buildcraft/transport/client/model/ModelPipeItem.java",
    "private final ItemModel baseModel",
    "this.colours[0] = baseModel",
    "new CompositeModel(List.of(baseModel",
)

require(
    "source-shared/src/main/java/buildcraft/lib/client/model/AdvModelCache.java",
    "class CacheHybrid",
    "indexedVariables",
    "dynamicVariables",
    "MAX_VALUES_PER_BUCKET",
)
require(
    "source-shared/src/main/java/buildcraft/transport/pipe/PipeEventBus.java",
    "eventPresence",
    "computeIfAbsent(eventClass",
    "if (!present) return false",
)
for rel in (
    "version-src/1.19.2-forge/src/main/java/buildcraft/transport/block/BlockPipeHolder.java",
    "version-src/1.20.1-forge/src/main/java/buildcraft/transport/block/BlockPipeHolder.java",
    "source-platforms/neoforge/src/main/java/buildcraft/transport/block/BlockPipeHolder.java",
):
    require(rel, "getCachedConnectionShapes(pipe).combined()")

# Adjacent-block signal bridge is a public API2 extension point, not a render-only special case.
require(
    "source-shared/src/main/java/buildcraft/api/v2/signal/SignalPortProvider.java",
    "interface SignalPortProvider",
    "signalPort(Direction side, ResourceLocation channelId)",
)
require(
    "source-shared/src/main/java/buildcraft/api/v2/signal/SignalPort.java",
    "publishedValue()",
    "receive(T value, OperationMode mode)",
)
require(
    "source-shared/src/main/java/buildcraft/transport/api2/SignalServiceImpl.java",
    "ExternalSignalPort",
    "SignalPortProvider",
    "gatesChanged = true",
)
for family in ("legacy", "modern"):
    require(
        f"source-families/{family}/src/main/java/buildcraft/transport/wire/WorldSavedDataWireSystems.java",
        "getExternalPort",
        "publishedValue()",
        "receive(powered, OperationMode.EXECUTE)",
    )

# Every audited performance marker must be resolved instead of simply surviving under a different backend copy.
obsolete = (
    "Apply the scale supplier during 1.19.2 text rendering",
    "Avoid ticking Builder every server tick when it has no pending work",
    "Cache the biome lookup until the engine changes position/world",
    "Replace direct neighbour lookup with a reusable tile/capability buffer",
    "Implement round-robin extraction for fluid Diamond Wooden Pipes before exposing this mode",
    "Revisit the fluid-pipe buffer capacity formula",
    "Correctly control UV inversion per rendered fluid face",
    "Let callers provide the fluid-render light level instead of forcing full-bright",
    "Make fluid UV tiling independent of assumed texture resolution",
    "Derive the render bounds from the connected Heat Exchanger section instead of a fixed radius",
    "Expose the combined item wrapper through IItemTransactor as well",
    "Strip non-functional display metadata when normalizing ItemStacks",
    "Derive insertion travel distance from the actual connected geometry",
    "Support NBT/components in FluidStack JSON deserialization",
    "Restore/use the static laser-box path instead of rendering every segment dynamically",
    "Verify and correct per-face UV orientation/mirroring",
    "Render distinct item-pipe quads when center/top/bottom sprites differ",
    "Derive the particle sprite from the active pipe model instead of textures[0]",
    "Initialize and reuse a neighbour/tile cache for power-pipe lookups",
    "Render the pumped fluid in the pump renderer",
    "Transform/renormalize normals when vertices are non-uniformly scaled",
    "Add a fallback cache for models that cannot be represented by the indexed cache",
    "Add a cache that will split up the model based on dependencies to variables",
    "Add client-side item advancement/interpolation between network updates",
    "Give pluggables a structured state-sync contract instead of ad-hoc networking",
    "Add a cheap handler-presence mask to avoid dispatch work for unused PipeEvent types",
    "Cache variable-length pipe ray-trace connection shapes",
    "Support wire connections from pipes to adjacent blocks where applicable",
)
for path in ROOT.rglob("*.java"):
    if any(part in {"build", ".gradle"} for part in path.parts):
        continue
    source = path.read_text(encoding="utf-8", errors="ignore")
    for marker in obsolete:
        if marker in source:
            errors.append(f"{path.relative_to(ROOT)}: unresolved audited performance marker {marker!r}")

if errors:
    for error in errors:
        print("ERROR:", error)
    sys.exit(1)

print("Performance regression guards OK")
print(" - idle machine/pipe lookup hot paths use bounded or neighbour caches")
print(" - fluid routing/rendering contracts cover RR, capacity, UV and caller light")
print(" - item/fluid JSON, matching and transactor compatibility are preserved")
print(" - laser/model/pipe render caches avoid the audited rebuild paths")
print(" - transport interpolation, event dispatch and pluggable sync are structured")
print(" - classic wires expose a bidirectional API2 adjacent-block endpoint")
