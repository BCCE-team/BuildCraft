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


# Retired BC8 wire/Stripes interfaces must stay absent; they were implementation details,
# not a second API beside buildcraft.api.v2.
retired_names = {
    "IWireEmitter", "IWireManager", "IStripesActivator", "IStripesRegistry",
    "IStripesHandlerItem", "IStripesHandlerBlock",
}
for path in ROOT.rglob("*.java"):
    if any(part in SKIP for part in path.parts):
        continue
    data = path.read_text(encoding="utf-8", errors="ignore")
    rel = path.relative_to(ROOT)
    for name in retired_names:
        if re.search(rf"\b(?:interface|class|enum|import\s+[\w.]*\.){name}\b", data):
            errors.append(f"{rel}: retired wire/Stripes interface remains: {name}")

runtime = text("source-shared/src/main/java/buildcraft/lib/internal/api/v2/BuildCraftApiRuntime.java")
for token in ("AutomationServiceImpl", "BuildCraftServices.AUTOMATION"):
    if token not in runtime:
        errors.append(f"BuildCraftApiRuntime: missing automation backend token {token}")

automation = text("source-shared/src/main/java/buildcraft/lib/internal/api/v2/AutomationServiceImpl.java")
for token in ("AUTOMATION_ACTION_TYPES", "request.kind()", "type.handler().execute(request)"):
    if token not in automation:
        errors.append(f"AutomationServiceImpl: missing registry-dispatch hook {token}")

transport = text("source-shared/src/main/java/buildcraft/transport/api2/TransportApi2.java")
for token in ("BuildCraftServices.SIGNALS", "SignalServiceImpl.INSTANCE", "ClassicSignalChannels.register()"):
    if token not in transport:
        errors.append(f"TransportApi2: missing signal runtime hook {token}")

channels = text("source-shared/src/main/java/buildcraft/api/v2/signal/BuildCraftSignalChannels.java")
for color in ("WHITE", "ORANGE", "MAGENTA", "LIGHT_BLUE", "YELLOW", "LIME", "PINK", "GRAY",
              "LIGHT_GRAY", "CYAN", "PURPLE", "BLUE", "BROWN", "GREEN", "RED", "BLACK"):
    if f" {color} " not in channels and f" {color}=" not in channels:
        errors.append(f"BuildCraftSignalChannels: missing classic channel {color}")

service = text("source-shared/src/main/java/buildcraft/transport/api2/SignalServiceImpl.java")
for token in ("implements SignalService", "ClassicWirePort", "manager.isAnyPowered", "manager.setSignalOutput",
              "manager.hasPartOfColor", "WorldSavedDataWireSystems.get(level)"):
    if token not in service:
        errors.append(f"SignalServiceImpl: missing live wire bridge {token}")

for platform in ("forge", "neoforge"):
    manager = text(f"source-platforms/{platform}/src/main/java/buildcraft/transport/wire/WireManager.java")
    for token in ("signalOutputs", "isSignalOutputActive", "setSignalOutput", 'putIntArray("signalOutputs"',
                  'getIntArray("signalOutputs"', "rotatedOutputs"):
        if token not in manager:
            errors.append(f"{platform} WireManager: missing API2 signal-state hook {token}")
    gate = text(f"source-platforms/{platform}/src/main/java/buildcraft/silicon/gate/GateLogic.java")
    for token in ("BuildCraftServices.SIGNALS", "BuildCraftSignalChannels.id", "emitSignal(DyeColor", "signalOutputsSynced"):
        if token not in gate:
            errors.append(f"{platform} GateLogic: missing API2 signal hook {token}")
    registries = text(f"source-platforms/{platform}/src/main/java/buildcraft/transport/BCTransportRegistries.java")
    for token in ("BuildCraftRegistries.STRIPES_HANDLERS", "StripesApi2Bridge.item", "StripesApi2Bridge.block"):
        if token not in registries:
            errors.append(f"{platform} BCTransportRegistries: missing API2 Stripes registration {token}")
    pipe_api = text(f"source-platforms/{platform}/src/main/java/buildcraft/transport/internal/pipe/PipeApi.java")
    if "stripeRegistry" in pipe_api:
        errors.append(f"{platform} PipeApi still owns legacy stripeRegistry")
    stripes = text(f"source-platforms/{platform}/src/main/java/buildcraft/transport/pipe/behaviour/PipeBehaviourStripes.java")
    if "implements StripesOutput" not in stripes or "StripesRegistry.INSTANCE.handleItem" not in stripes:
        errors.append(f"{platform} PipeBehaviourStripes is not using API2 StripesOutput/dispatcher")

for family in ("legacy", "modern"):
    wire_system = text(f"source-families/{family}/src/main/java/buildcraft/transport/wire/WireSystem.java")
    if "isSignalOutputActive" in wire_system:
        errors.append(f"{family} WireSystem should query signal state through WorldSavedDataWireSystems, not manager directly")
    if "holder.getWireManager().hasPartOfColor(tempColor)" not in wire_system:
        errors.append(f"{family} WireSystem does not expose side endpoints for API2 signal sources")
    saved = text(f"source-families/{family}/src/main/java/buildcraft/transport/wire/WorldSavedDataWireSystems.java")
    if "isSignalOutputActive" not in saved:
        errors.append(f"{family} WorldSavedDataWireSystems does not resolve API2 signal output state")
    robot = text(f"source-families/{family}/src/main/java/buildcraft/robotics/ai/AIRobotStripesHandler.java")
    if "implements StripesOutput" not in robot or "StripesRegistry.INSTANCE.handleItem" not in robot:
        errors.append(f"{family} robot Stripes bridge is not using API2")

trigger = text("source-shared/src/main/java/buildcraft/transport/statements/TriggerPipeSignal.java")
for token in ("BuildCraftServices.SIGNALS", "BuildCraftSignalChannels.id", "SignalPort::connected"):
    if token not in trigger:
        errors.append(f"TriggerPipeSignal: missing API2 signal read/connection hook {token}")

dispatch = text("source-shared/src/main/java/buildcraft/transport/pipe/StripesRegistry.java")
for token in ("BuildCraftRegistries.STRIPES_HANDLERS", "StripesContext", "handler.activate(context)"):
    if token not in dispatch:
        errors.append(f"StripesRegistry: missing API2 dispatch hook {token}")

fixture = text("addon-fixture/src/main/java/dev/bcce/apifixture/ApiV2FixtureAddon.java")
for token in ("BuildCraftRegistries.AUTOMATION_ACTION_TYPES", "BuildCraftRegistries.STRIPES_HANDLERS",
              "BuildCraftServices.SIGNALS", "BuildCraftSignalChannels.RED", "BuildCraftServices.AUTOMATION",
              "FixtureAutomationRequest"):
    if token not in fixture:
        errors.append(f"addon fixture does not prove public Signals/Automation API: missing {token}")

if errors:
    print("Signals / Wires / Stripes / Automation API2 migration FAILED:")
    for error in errors:
        print(f" - {error}")
    sys.exit(1)

print("Signals / Wires / Stripes / Automation API2 migration OK: classic wire runtime and Stripes dispatch use API2 services/registries")
