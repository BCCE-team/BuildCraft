#!/usr/bin/env python3
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
from source_lookup import resolve_source_path
SKIP = {"build", ".gradle", ".git"}
errors: list[str] = []


def text(rel: str) -> str:
    path = resolve_source_path(rel)
    if not path.is_file():
        errors.append(f"missing {rel}")
        return ""
    return path.read_text(encoding="utf-8", errors="ignore")


# Old robotics/boards extension namespaces must stay retired. The classic executor may remain,
# but only under buildcraft.robotics.internal.*.
for path in ROOT.rglob("*.java"):
    if any(part in SKIP for part in path.parts):
        continue
    data = path.read_text(encoding="utf-8", errors="ignore")
    rel = path.relative_to(ROOT)
    if re.search(r"\b(?:package|import)\s+buildcraft\.api\.(?:robots|boards)(?:\.|;)", data):
        errors.append(f"{rel}: retired public robotics/boards namespace remains")
    if re.search(r"\bimport\s+buildcraft\.api\.events\.RobotEvent\s*;", data):
        errors.append(f"{rel}: legacy RobotEvent is still consumed")

for rel in (
    "source-platforms/forge/src/main/java/buildcraft/api/events/RobotEvent.java",
    "source-platforms/neoforge/src/main/java/buildcraft/api/events/RobotEvent.java",
    "source-shared/src/main/java/buildcraft/robotics/internal/legacy/robots/IRequestProvider.java",
):
    if (ROOT / rel).exists():
        errors.append(f"obsolete robotics API/bridge remains: {rel}")

# API2 public contracts required by the live bridge.
for rel, tokens in {
    "source-shared/src/main/java/buildcraft/api/v2/robot/RobotService.java": (
        "Optional<RobotHandle> robot", "Collection<? extends RobotHandle> robots", "Optional<RobotDock> dock",
        "Optional<RobotResourceLease> acquire", "RobotEventDecision evaluateEvent",
    ),
    "source-shared/src/main/java/buildcraft/api/v2/request/RequestProvider.java": (
        "Collection<ItemRequest> requests()", "ItemTransferResult offer", "OperationMode mode",
    ),
    "source-shared/src/main/java/buildcraft/api/v2/robot/BuildCraftRobotBoards.java": (
        "PICKER", "DELIVERY", "STRIPES", "BUILDER",
    ),
    "source-shared/src/main/java/buildcraft/api/v2/robot/BlockRobotResource.java": (
        "implements RobotResource", "BlockPos position", "Optional<Direction> side",
    ),
}.items():
    data = text(rel)
    for token in tokens:
        if token not in data:
            errors.append(f"{rel}: missing API2 robotics contract token {token}")

# Both loader platforms must expose the same live services and runtime event/policy bridge.
for platform in ("forge", "neoforge"):
    service = text(f"source-platforms/{platform}/src/main/java/buildcraft/robotics/internal/api2/RobotServiceImpl.java")
    for token in (
        "implements RobotService", "getLoadedRobots()", "new RobotHandleAdapter", "RobotDockAdapter::new",
        "new Lease", "ROBOT_EVENT_LISTENERS", "new ApiTaskAI", "setMainAIOverride",
        "BuildCraftDockPorts.ITEMS", "new DockItemPort", "BuildCraftDockPorts.FLUIDS", "new DockFluidPort",
    ):
        if token not in service:
            errors.append(f"{platform} RobotServiceImpl: missing live runtime hook {token}")

    request = text(f"source-platforms/{platform}/src/main/java/buildcraft/robotics/internal/api2/RequestServiceImpl.java")
    for token in ("implements RequestService", "instanceof RequestProvider", "getRequestProvider()", "getStations()"):
        if token not in request:
            errors.append(f"{platform} RequestServiceImpl: missing live request hook {token}")

    bootstrap = text(f"source-platforms/{platform}/src/main/java/buildcraft/robotics/internal/api2/RoboticsApi2Bootstrap.java")
    for token in (
        "BuildCraftServices.ROBOTS", "new RobotServiceImpl()", "BuildCraftServices.REQUESTS", "new RequestServiceImpl()",
        "BuildCraftRegistries.ROBOT_BOARD_TYPES", "BuildCraftRobotBoards.id", "AutomationKinds.BREAK_BLOCK",
        "AutomationKinds.PLACE_BLOCK", "AutomationKinds.USE_ITEM", "BuildCraftServices.PERMISSIONS",
    ):
        if token not in bootstrap:
            errors.append(f"{platform} RoboticsApi2Bootstrap: missing API2 bootstrap hook {token}")

    events = text(f"source-platforms/{platform}/src/main/java/buildcraft/robotics/internal/api2/RobotEventSupport.java")
    for token in ("RobotEventContext", "BuildCraftServices.ROBOTS", "evaluateEvent", "RobotEventDecision.DENY"):
        if token not in events:
            errors.append(f"{platform} RobotEventSupport: missing API2 event hook {token}")

    policy = text(f"source-platforms/{platform}/src/main/java/buildcraft/robotics/internal/api2/RobotAutomationSupport.java")
    for token in ("AutomationActor", "BuildCraftServices.AUTOMATION", "AutomationResult.Status.DENIED", "AutomationResult.Status.FAILED"):
        if token not in policy:
            errors.append(f"{platform} RobotAutomationSupport: missing automation-policy hook {token}")

# Every active target must install robotics API2 after board registration.
for rel in (
    "version-src/1.19.2-forge/src/main/java/buildcraft/robotics/BCRobotics.java",
    "version-src/1.20.1-forge/src/main/java/buildcraft/robotics/BCRobotics.java",
    "source-platforms/neoforge/src/main/java/buildcraft/robotics/BCRobotics.java",
):
    data = text(rel)
    if "RoboticsApi2Bootstrap.bootstrap()" not in data:
        errors.append(f"{rel}: robotics API2 bootstrap is not installed")

# Live request providers and the delivery robot must use request IDs / simulation-aware API2 requests.
for platform in ("forge", "neoforge"):
    for rel in (
        f"source-platforms/{platform}/src/main/java/buildcraft/robotics/tile/TileRequester.java",
        f"source-platforms/{platform}/src/main/java/buildcraft/robotics/DockingStationPipe.java",
    ):
        data = text(rel)
        for token in ("implements RequestProvider", "Collection<ItemRequest> requests()", "ItemTransferResult offer", "OperationMode"):
            if token not in data:
                errors.append(f"{rel}: request provider not migrated to API2 ({token})")

for family in ("legacy", "modern"):
    stack_request = text(f"source-families/{family}/src/main/java/buildcraft/robotics/StackRequest.java")
    for token in ("RequestProvider", "ResourceLocation requestId", "ResourceIdApiRequest", '"requestId"'):
        if token not in stack_request:
            errors.append(f"{family} StackRequest: missing API2 request-id persistence hook {token}")
    search = text(f"source-families/{family}/src/main/java/buildcraft/robotics/ai/AIRobotSearchStackRequest.java")
    for token in ("provider.requests()", "ItemRequest", "matcher().examples()", "req.getResourceId"):
        if token not in search:
            errors.append(f"{family} AIRobotSearchStackRequest: missing API2 request discovery {token}")
    deliver = text(f"source-families/{family}/src/main/java/buildcraft/robotics/ai/AIRobotDeliverRequested.java")
    for token in ("RequestProvider", ".offer(requested.getRequestId()", "OperationMode.EXECUTE", "transferredCount()"):
        if token not in deliver:
            errors.append(f"{family} AIRobotDeliverRequested: missing API2 request delivery {token}")

    # Classic tool-aware executors remain internal, but API2 Automation/Permission policy is authoritative.
    brk = text(f"source-families/{family}/src/main/java/buildcraft/robotics/ai/AIRobotBreak.java")
    for token in ("RobotAutomationSupport.permits", "new BreakBlockRequest", "OperationMode.SIMULATE"):
        if token not in brk:
            errors.append(f"{family} AIRobotBreak: missing API2 automation policy {token}")
    use = text(f"source-families/{family}/src/main/java/buildcraft/robotics/ai/AIRobotUseToolOnBlock.java")
    for token in ("RobotAutomationSupport.permits", "new UseItemRequest", "OperationMode.SIMULATE"):
        if token not in use:
            errors.append(f"{family} AIRobotUseToolOnBlock: missing API2 automation policy {token}")

# Placement/interact/dismantle events use API2 listeners in every active implementation.
for rel in (
    "version-src/1.19.2-forge/src/main/java/buildcraft/robotics/item/ItemRobot.java",
    "version-src/1.20.1-forge/src/main/java/buildcraft/robotics/item/ItemRobot.java",
    "source-platforms/neoforge/src/main/java/buildcraft/robotics/item/ItemRobot.java",
):
    data = text(rel)
    if "RobotEventSupport.denied(RobotEventKind.PLACE" not in data:
        errors.append(f"{rel}: placement is not routed through API2 RobotEventService")
for rel in (
    "version-src/1.19.2-forge/src/main/java/buildcraft/robotics/entity/EntityRobot.java",
    "version-src/1.20.1-forge/src/main/java/buildcraft/robotics/entity/EntityRobot.java",
    "source-platforms/neoforge/src/main/java/buildcraft/robotics/entity/EntityRobot.java",
):
    data = text(rel)
    for kind in ("INTERACT", "DISMANTLE"):
        if f"RobotEventKind.{kind}" not in data or "RobotEventSupport.denied" not in data:
            errors.append(f"{rel}: {kind.lower()} event is not routed through API2")

fixture = text("addon-fixture/src/main/java/dev/bcce/apifixture/ApiV2FixtureAddon.java")
for token in (
    "BuildCraftServices.ROBOTS).robots", "control.assign(new FixtureRobotTask()", "BuildCraftServices.ROBOTS).dock",
    "BuildCraftServices.REQUESTS).provider", "BuildCraftRobotBoards.PICKER", "ROBOT_EVENT_LISTENERS",
):
    if token not in fixture:
        errors.append(f"addon fixture does not prove public robotics/request API: missing {token}")

if errors:
    print("Robots / Boards / Requests API2 migration FAILED:")
    for error in errors:
        print(f" - {error}")
    sys.exit(1)

print("Robots / Boards / Requests API2 migration OK: classic robotics runtime is internal; live robot, request, board, event and automation policy surfaces use API2")
