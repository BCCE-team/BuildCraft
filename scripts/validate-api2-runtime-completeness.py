#!/usr/bin/env python3
"""Guard API2 contracts that require a live production backend, not just public types."""
from __future__ import annotations

import sys
import re
from pathlib import Path

from source_layout import ROOT, load_properties, preprocess_text, resolve_effective_source, target_ids, target_layout
from source_preprocessor import strip_source_condition
from source_lookup import resolve_source_path

ERRORS: list[str] = []


def fail(message: str) -> None:
    ERRORS.append(message)


def require(path: Path, *tokens: str) -> str:
    if not path.is_file():
        try:
            path = resolve_source_path(path.relative_to(ROOT))
        except ValueError:
            pass
    if not path.is_file():
        try:
            label = path.relative_to(ROOT)
        except ValueError:
            label = path
        fail(f"missing {label}")
        return ""
    text = path.read_text(encoding="utf-8")
    for token in tokens:
        if token not in text:
            fail(f"{path.relative_to(ROOT)}: missing {token!r}")
    return text


def effective_java(target: str, rel: str, props: dict[str, str]) -> str:
    layout = target_layout(target, props)
    logical = Path("src/main/java") / rel
    path = resolve_effective_source(layout, props, logical)
    if path is None:
        fail(f"{target}: missing effective {logical.as_posix()}")
        return ""
    source, _selector = strip_source_condition(path.read_text(encoding="utf-8"))
    return preprocess_text(
        source,
        minecraft=props[f"target.{target}.deps.minecraft"],
        family=layout.family,
        platform=layout.platform,
    )


def validate_lifecycle(props: dict[str, str]) -> None:
    registries = require(
        ROOT / "source-shared/src/main/java/buildcraft/lib/BCLibRegistries.java",
        "BuildCraftApiRuntime.bootstrap()",
        "ApiLifecycle.CONTENT_REGISTRATION",
        "ApiLifecycle.FROZEN",
        "ApiLifecycle.RUNNING",
        "BuildCraftApiRuntime.INSTANCE.advanceLifecycle(ApiLifecycle.FROZEN)",
        "BuildCraftApiRuntime.INSTANCE.advanceLifecycle(ApiLifecycle.RUNNING)",
    )
    if "public static synchronized void fmlPostInit()" not in registries:
        fail("BCLibRegistries: lifecycle freeze/run must happen in fmlPostInit")

    runtime = require(
        ROOT / "source-shared/src/main/java/buildcraft/lib/internal/api/v2/BuildCraftApiRuntime.java",
        "if (INSTANCE.lifecycle == ApiLifecycle.DISCOVERY)",
        "INSTANCE.advanceLifecycle(ApiLifecycle.TYPE_REGISTRATION)",
        "if (next == ApiLifecycle.FROZEN)",
        "registry.freeze()",
    )
    if "INSTANCE.advanceLifecycle(ApiLifecycle.TYPE_REGISTRATION);\n    }" in runtime and "if (INSTANCE.lifecycle == ApiLifecycle.DISCOVERY)" not in runtime:
        fail("BuildCraftApiRuntime.bootstrap is not idempotent after content registration")

    for target in target_ids(props):
        bclib = effective_java(target, "buildcraft/lib/BCLib.java", props)
        if "evt.enqueueWork(BCLibRegistries::fmlPostInit)" not in bclib:
            fail(f"{target}: load-complete event does not freeze/start API2 lifecycle")


def validate_runtime_services() -> None:
    runtime = require(
        ROOT / "source-shared/src/main/java/buildcraft/lib/internal/api/v2/BuildCraftApiRuntime.java",
        "BuildCraftServices.WORLD_RULES, worldRules",
        "BuildCraftServices.DIAGNOSTICS, diagnostics",
        "BuildCraftServices.CLIENT_PRESENTATIONS, clientPresentations",
    )
    for field in ("WorldRuleServiceImpl worldRules", "ApiDiagnosticsImpl diagnostics", "ClientPresentationServiceImpl clientPresentations"):
        if field not in runtime:
            fail(f"BuildCraftApiRuntime: missing live service field {field!r}")

    require(
        ROOT / "source-shared/src/main/java/buildcraft/lib/internal/api/v2/WorldRuleServiceImpl.java",
        "BuildCraftServices.PERMISSIONS",
        "WorldOperationKind.BLOCK_BREAK",
        "WorldOperationKind.BLOCK_PLACE",
        "state.getCollisionShape(level, pos).isEmpty()",
    )
    require(
        ROOT / "source-shared/src/main/java/buildcraft/lib/internal/api/v2/ApiDiagnosticsImpl.java",
        "implements ApiDiagnostics",
        "entries.add",
        "List.copyOf(entries)",
    )
    presentation = require(
        ROOT / "source-shared/src/main/java/buildcraft/lib/internal/api/v2/ClientPresentationServiceImpl.java",
        "BuildCraftRegistries.CLIENT_PRESENTATIONS",
        "BuildCraftRegistries.PIPE_PRESENTATIONS",
        "BuildCraftRegistries.STATEMENT_PRESENTATIONS",
        "BuildCraftRegistries.PARAMETER_PRESENTATIONS",
    )
    if "BuildCraftApi.registry" not in presentation:
        fail("ClientPresentationServiceImpl does not consume the presentation registries")

    shared_platform = require(
        ROOT / "source-shared/src/main/java/buildcraft/lib/internal/api/v2/platform/DefaultPlatformServices.java",
        "implements PlatformServices",
        "Optional<ItemTransfer>",
        "Optional<FluidTransfer>",
        "Optional<EnergyTransfer>",
        "PlatformTransferLookup",
        "TransferAdapters::itemPort",
        "TransferAdapters::fluidPort",
        "TransferAdapters::energyPort",
    )
    transfer_adapters = require(
        ROOT / "source-shared/src/main/java/buildcraft/lib/internal/transfer/TransferAdapters.java",
        "implements ItemTransferAccess",
        "implements FluidTransferAccess",
        "implements EnergyTransferAccess",
        "scope.simulate()",
        "scope.mode()",
        "scope.enter(",
    )
    require(
        ROOT / "source-shared/src/main/java/buildcraft/lib/internal/transfer/OperationScope.java",
        "ThreadLocal<Deque<OperationScope>>",
        "Guard enter(Object endpointIdentity)",
        "sharedAttachment(Object key",
    )

    for platform in ("forge", "neoforge"):
        require(
            ROOT / f"source-platforms/{platform}/src/main/java/buildcraft/lib/internal/api/v2/platform/PlatformApi2Bootstrap.java",
            "BuildCraftServices.PLATFORM",
            "new DefaultPlatformServices(Lookup.INSTANCE)",
            "implements PlatformTransferLookup",
            "TransferAdapters.items(storage)",
            "TransferAdapters.fluids(storage",
            "TransferAdapters.energy(storage)",
        )


def validate_robot_extensions() -> None:
    for public_rel, tokens in {
        "robot/RobotResourceType.java": ("Class<R> resourceType", "RobotResourceAcquirer<R> acquirer"),
        "robot/RobotTaskType.java": ("Class<T> taskType",),
        "robot/DockPortType.java": ("DockPortResolver<T> resolver", "resolve(RobotDockContext context)"),
        "robot/RobotDockContext.java": ("Level level", "BlockPos position", "Optional<Direction> side", "boolean occupied"),
    }.items():
        require(ROOT / "source-shared/src/main/java/buildcraft/api/v2" / public_rel, *tokens)

    for platform in ("forge", "neoforge"):
        service = require(
            ROOT / f"source-platforms/{platform}/src/main/java/buildcraft/robotics/internal/api2/RobotServiceImpl.java",
            "BuildCraftRegistries.ROBOT_RESOURCE_TYPES",
            "type.acquirer().acquire",
            "BuildCraftRegistries.ROBOT_TASK_TYPES",
            "type.taskType().isInstance(task)",
            "BuildCraftRegistries.ROBOT_DOCK_PORT_TYPES",
            "type.resolve(new RobotDockContext",
        )
        if "resource instanceof BlockRobotResource" in service:
            fail(f"{platform}: RobotService.acquire still hard-codes BlockRobotResource instead of registry dispatch")

        bootstrap = require(
            ROOT / f"source-platforms/{platform}/src/main/java/buildcraft/robotics/internal/api2/RoboticsApi2Bootstrap.java",
            "registerRobotResourceTypes()",
            "new RobotResourceType<>",
            "RobotServiceImpl::acquireBlockResource",
            "registerDockPortTypes()",
            "BuildCraftDockPorts.ITEMS",
            "BuildCraftDockPorts.FLUIDS",
            "BuildCraftDockPorts.MJ.id()",
            "BuildCraftServices.ENERGY",
            "BuildCraftDockPorts.EXTERNAL_ENERGY.id()",
            "BuildCraftServices.PLATFORM",
        )
        if not re.search(r"registry\.register\(\s*BlockRobotResource\.TYPE\s*,", bootstrap):
            fail(f"{platform}: built-in block robot resource type is not registered")


def validate_fixture_extensions() -> None:
    fixture = require(
        ROOT / "addon-fixture/src/main/java/dev/bcce/apifixture/ApiV2FixtureAddon.java",
        "new RobotTaskType<>",
        "new RobotResourceType<>",
        "new DockPortType<>",
        "BuildCraftRegistries.ROBOT_TASK_TYPES",
        "BuildCraftRegistries.ROBOT_RESOURCE_TYPES",
        "BuildCraftRegistries.ROBOT_DOCK_PORT_TYPES",
        "dock.port(FIXTURE_DOCK_PORT)",
    )
    if "buildcraft.lib.internal" in fixture or "net.minecraftforge" in fixture or "net.neoforged" in fixture:
        fail("addon fixture robot extensions leaked implementation/loader APIs")


def validate_retired_dead_surface() -> None:
    registries = require(ROOT / "source-shared/src/main/java/buildcraft/api/v2/BuildCraftRegistries.java")
    services = require(ROOT / "source-shared/src/main/java/buildcraft/api/v2/BuildCraftServices.java")
    features = require(ROOT / "source-shared/src/main/java/buildcraft/api/v2/BuildCraftFeatures.java")
    for token, text, label in (
        ("CHIPSET_TYPES", registries, "BuildCraftRegistries"),
        ("PIPE_EVENT_TYPES", registries, "BuildCraftRegistries"),
        ("PAYLOAD_TYPES", registries, "BuildCraftRegistries"),
        ("NETWORK", services, "BuildCraftServices"),
        ("NETWORK", features, "BuildCraftFeatures"),
    ):
        if token in text:
            fail(f"{label}: dead pre-release API2 surface {token} was re-advertised without a backend")

    for rel in (
        "source-shared/src/main/java/buildcraft/api/v2/machine/ChipsetType.java",
        "source-shared/src/main/java/buildcraft/api/v2/pipe/PipeEventType.java",
        "source-shared/src/main/java/buildcraft/api/v2/network/PayloadType.java",
        "source-shared/src/main/java/buildcraft/api/v2/network/NetworkService.java",
    ):
        if (ROOT / rel).exists():
            fail(f"retired backend-less API2 type returned: {rel}")


def validate_parser_and_snapshot() -> None:
    for rel in (
        "source-shared/src/main/java/buildcraft/lib/client/model/json/JsonModelPart.java",
        "source-shared/src/main/java/buildcraft/lib/client/model/json/JsonVariableModelPart.java",
    ):
        text = require(ROOT / rel, "JsonSyntaxException")
        if "AbstractMethodError" in text:
            fail(f"{rel}: latent AbstractMethodError remains in custom model parser")


def main() -> None:
    props = load_properties()
    validate_lifecycle(props)
    validate_runtime_services()
    validate_robot_extensions()
    validate_fixture_extensions()
    validate_retired_dead_surface()
    validate_parser_and_snapshot()
    if ERRORS:
        print("API2 runtime completeness FAILED:", file=sys.stderr)
        for error in ERRORS:
            print(f"- {error}", file=sys.stderr)
        raise SystemExit(1)
    print("API2 runtime completeness OK:")
    print(" - production lifecycle reaches CONTENT_REGISTRATION -> FROZEN -> RUNNING")
    print(" - world rules, diagnostics, presentations and loader transfer services are live")
    print(" - robot resource/task/dock registries dispatch into runtime consumers")
    print(" - backend-less pre-release network/chipset/pipe-event surfaces stay retired")
    print(" - unsupported custom-model face syntax fails cleanly, not with AbstractMethodError")


if __name__ == "__main__":
    main()
