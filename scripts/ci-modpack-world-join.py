#!/usr/bin/env python3
"""IT16: actual Minecraft modpack -> world login -> save -> server restart -> rejoin.

Tests the real client/server handshake and world persistence on all seven
maintained targets. JEI and Jade are mandatory. AE2, Forestry CE and IC2 Classic
are included wherever matching target releases exist. Two original targets retain
full item NBT, recipe reload and chunk lifecycle fixtures; others run a real
server+client join/rejoin smoke until equivalent NBT fixtures are available.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import secrets
import socket
import struct
import shutil
import signal
import subprocess
import sys
import time
import urllib.request
from urllib.parse import quote
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LOCK = ROOT / "build-config" / "modpack-world-join-lock.json"
TARGETS = {
    "1.19.2-forge": "old",
    "1.20.1-forge": "old",
    "1.21.1-neoforge": "1.21.X",
    "1.21.11-neoforge": "1.21.X",
    "26.1.2-neoforge": "26.X",
    "26.2-neoforge": "26.X",
    "26.3-neoforge": "26.X",
}
# Supported upstream releases, not a claim that BCCE integration has been implemented.
# These expectations are intentionally independent of the lock contents: removing
# a dependency from the lock must not silently turn the world-join test green.
EXPECTED_PRESENCE = {
    "1.19.2-forge": (True, True, True, True),
    "1.20.1-forge": (True, True, False, True),
    "1.21.1-neoforge": (True, True, False, True),
    "1.21.11-neoforge": (False, False, False, False),
    "26.1.2-neoforge": (True, False, False, False),
    "26.2-neoforge": (True, False, False, False),
    "26.3-neoforge": (True, False, False, False),
}
OPTIONAL_NAMES = ("ae2", "forestry", "ic2", "create")
MODRINTH_PROJECTS = {"ae2": "XxWD5pD3", "forestry": "2oORTOi2", "guideme": "Ck4E7v7R",
    "theoneprobe": "Eyw0UxEx", "curios": "vvuO3ImH"}
FULL_FIXTURE_TARGETS = {"1.20.1-forge", "1.21.1-neoforge"}
TARGET_PROPERTIES = ROOT / "build-config" / "targets.properties"


def target_properties(target: str) -> dict[str, str]:
    if target not in TARGETS:
        raise ValueError(f"Unsupported BCCE world-join target: {target}")
    props: dict[str, str] = {}
    for line in TARGET_PROPERTIES.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            props[key.strip()] = value.strip()
    prefix = f"target.{target}."
    return {key[len(prefix):]: value for key, value in props.items() if key.startswith(prefix)}


def native_mod_present(props: dict[str, str], mod: str) -> bool:
    enabled = props.get(f"compat.{mod}.enabled", "true").lower() == "true"
    return enabled and bool(props.get(f"deps.{mod}", ""))


def validate_modpack_lock(target: str) -> dict:
    data = json.loads(LOCK.read_text(encoding="utf-8"))
    if data.get("schema") != 2 or set(data.get("targets", {})) != set(TARGETS):
        raise ValueError("IT16 lock must declare every supported BCCE target (schema 2)")
    props = target_properties(target)
    entry = data["targets"][target]
    if entry.get("generation") != TARGETS[target] or entry["generation"] != props.get("build.generation"):
        raise ValueError(f"Wrong Stonecutter generation for {target}")
    if set(entry.get("present", {})) != set(OPTIONAL_NAMES):
        raise ValueError(f"IT16 missing explicit optional-mod expectations for {target}")
    if tuple(entry["present"][name] for name in OPTIONAL_NAMES) != EXPECTED_PRESENCE[target]:
        raise ValueError(f"IT16 optional-mod coverage mismatch for {target}")
    mods = entry.get("mods", {})
    if not isinstance(mods, dict) or any(not isinstance(v, str) or not v for v in mods.values()):
        raise ValueError(f"Invalid external pinned versions for {target}")
    if bool(mods.get("ae2")) != entry["present"]["ae2"]:
        raise ValueError(f"AE2 coverage is not pinned correctly for {target}")
    if target != "1.19.2-forge" and entry["present"]["ae2"] and not mods.get("guideme"):
        raise ValueError(f"AE2 requires a pinned GuideME release on {target}")
    if target == "1.19.2-forge" and (not mods.get("curios") or not mods.get("theoneprobe")):
        raise ValueError("IC2 Classic 1.19.2 requires pinned Curios and The One Probe")
    allowed_external_mods = {"ae2", "modernfix", "forestry", "guideme", "curios", "theoneprobe"}
    if not set(mods) <= allowed_external_mods:
        raise ValueError(f"IT16 includes unreviewed third-party JARs on {target}: {set(mods)-allowed_external_mods}")
    if "guideme" in mods and not entry["present"]["ae2"]:
        raise ValueError("GuideME must not remain installed without AE2 in normal lock")
    for mod in ("forestry", "ic2", "create"):
        present = bool(mods.get(mod)) or native_mod_present(props, mod)
        if present != entry["present"][mod]:
            raise ValueError(f"{target} {mod}: required runtime absent or unsupported runtime included")
    if "ic2" in mods or "create" in mods:
        raise ValueError("IC2/Create must be selected through targets.properties to preserve Gradle dependencies")
    for mod in ("jei", "jade"):
        if not native_mod_present(props, mod):
            raise ValueError(f"Required {mod.upper()} is missing/disabled for {target}")
    return entry


# No log-only "joined the game" can satisfy mandatory client mod checks. These
# signatures must come from the actual running client log, not static config or
# the Gradle dependency declaration. The log is saved in IT16 diagnostics.
CLIENT_MOD_EVIDENCE = {
    "jei": re.compile(r"(?i)\b(?:JEI|Just Enough Items|mezz\.jei)\b"),
    "jade": re.compile(r"(?i)\b(?:Jade|snownee\.jade)\b"),
}

def verify_mandatory_client_mods(client_log: Path, client_launch_log: Path) -> None:
    # Restrict evidence to the actual Minecraft client log. A dependency name
    # printed by Gradle is not proof that the runtime successfully loaded a mod.
    content = read_text(client_log)
    for name, pattern in CLIENT_MOD_EVIDENCE.items():
        candidates = [line for line in content.splitlines() if pattern.search(line)]
        candidates = [line for line in candidates if not re.search(
            r"(?i)\b(?:missing|required mod|not loaded|not found|could not find|is absent|not applied)\b", line)]
        if not candidates:
            raise RuntimeError(
                f"Mandatory {name.upper()} has no runtime client loading evidence in {client_log}; "
                "a successful connection alone does not prove the mod is installed")
JOIN_RE = re.compile(r"joined the game", re.IGNORECASE)
JOIN_PLAYER_RE = re.compile(r"(?P<name>[A-Za-z0-9_]{1,16}) joined the game", re.IGNORECASE)
READY_RE = re.compile(r"Done \([0-9.,]+s\)!|For help, type .help.", re.IGNORECASE)
FATAL_RE = re.compile(
    r"Crash report saved|Mod loading has failed|Loading errors encountered|"
    r"ExceptionInInitializerError|NoClassDefFoundError|ReportedException: Rendering|"
    r"FATAL.*(?:Exception|Error)|A fatal error has been detected",
    re.IGNORECASE,
)



# Minecraft RCON is used only on localhost. This verifies that the world contains
# *working block registries and block entities* after the actual player join,
# rather than treating the existence of a "joined the game" line as sufficient.
RCON_AUTH = 3
RCON_EXEC = 2
RCON_AUTH_RESPONSE = 2
RCON_REQUEST_ID = 0xBCCE

SECOND_PLAYER = "BCCE_Tester2"


def rcon_packet(request_id: int, request_type: int, payload: str) -> bytes:
    body = struct.pack("<ii", request_id, request_type) + payload.encode("utf-8") + b"\0\0"
    return struct.pack("<i", len(body)) + body


def recv_exact(connection: socket.socket, length: int) -> bytes:
    result = bytearray()
    while len(result) < length:
        chunk = connection.recv(length - len(result))
        if not chunk:
            raise ConnectionError("RCON closed mid-packet")
        result.extend(chunk)
    return bytes(result)


def recv_rcon(connection: socket.socket) -> tuple[int, int, str]:
    length = struct.unpack("<i", recv_exact(connection, 4))[0]
    if length < 10 or length > 4096:
        raise RuntimeError(f"Invalid RCON packet length {length}")
    data = recv_exact(connection, length)
    request_id, request_type = struct.unpack("<ii", data[:8])
    if data[-2:] != b"\0\0":
        raise RuntimeError("Missing RCON packet terminator")
    return request_id, request_type, data[8:-2].decode("utf-8", errors="replace")


def rcon_command(port: int, password: str, command: str) -> str:
    with socket.create_connection(("127.0.0.1", port), timeout=12) as conn:
        conn.settimeout(12)
        conn.sendall(rcon_packet(RCON_REQUEST_ID, RCON_AUTH, password))
        # Some servers send an empty RCON response before their auth response.
        for _ in range(3):
            ident, packet_type, _ = recv_rcon(conn)
            if ident == -1:
                raise RuntimeError("RCON authentication refused")
            if ident == RCON_REQUEST_ID and packet_type == RCON_AUTH_RESPONSE:
                break
        else:
            raise RuntimeError("RCON authentication was never acknowledged")
        conn.sendall(rcon_packet(RCON_REQUEST_ID + 1, RCON_EXEC, command))
        for _ in range(3):
            ident, _, message = recv_rcon(conn)
            if ident == RCON_REQUEST_ID + 1:
                return message
        raise RuntimeError(f"No RCON response for {command!r}")


def player_name_from_join_log(log: str) -> str:
    matches = list(JOIN_PLAYER_RE.finditer(log))
    if not matches:
        raise RuntimeError("Server logged a join but no valid player name was found")
    return matches[-1].group("name")


def online_players(reply: str) -> set[str]:
    """Parse the authoritative RCON /list reply, not arbitrary username substrings."""
    match = re.search(r"players online:\s*(.*)$", reply, re.IGNORECASE)
    if not match:
        raise RuntimeError(f"Unrecognized /list response: {reply!r}")
    players = [name.strip() for name in match.group(1).split(",") if name.strip()]
    if any(not re.fullmatch(r"[A-Za-z0-9_]{1,16}", name) for name in players):
        raise RuntimeError(f"Invalid player names in server /list: {reply!r}")
    if len(players) != len(set(players)):
        raise RuntimeError(f"Duplicate player names in /list: {reply!r}")
    return set(players)


def require_online(port: int, password: str, expected: set[str]) -> None:
    seen = online_players(rcon_command(port, password, "list"))
    if not expected.issubset(seen):
        raise RuntimeError(f"Expected online {sorted(expected)}, saw {sorted(seen)}")


def verify_second_client_and_factory(port: int, password: str, player: str,
                                     origin: tuple[int, int, int], *, first_join: bool) -> None:
    """IT15 subset: two real clients share a loaded factory through reconfiguration/restart."""
    require_online(port, password, {player, SECOND_PLAYER})
    x, y, z = origin
    position = f"{x + 12} {y} {z + 2}"
    if first_join:
        # Change an actual BCCE machine while BOTH clients have this world loaded.
        for block in ("buildcraftfactory:tank", "buildcraftfactory:autoworkbench_item"):
            reply = rcon_command(port, password, f"setblock {position} {block}")
            if "changed the block" not in reply.lower():
                raise RuntimeError(f"Second-player factory mutation failed for {block}: {reply!r}")
    token = "BCCE_IT15_FACTORY_PERSISTED"
    reply = rcon_command(port, password, f"execute if block {position} "
                         f"buildcraftfactory:autoworkbench_item run say {token}")
    if token not in reply:
        raise RuntimeError(f"Shared BCCE factory changed across two-player restart: {reply!r}")
    data = rcon_command(port, password, f"data get block {position}")
    if "following block data" not in data.lower():
        raise RuntimeError(f"Shared Auto Workbench Block Entity missing after two-player join: {data!r}")


def verify_second_client_factory_alone(port: int, password: str,
                                       origin: tuple[int, int, int]) -> None:
    x, y, z = origin
    position = f"{x + 12} {y} {z + 2}"
    token = "BCCE_IT15_SECOND_PLAYER_SURVIVED"
    reply = rcon_command(port, password, f"execute if block {position} "
                         f"buildcraftfactory:autoworkbench_item run say {token}")
    if token not in reply:
        raise RuntimeError("Factory disappeared when only the second client remained online")


def verify_create_absent(port: int, password: str, position: str) -> None:
    """IT11: the *running* registry, not a Gradle property, must lack Create."""
    response = rcon_command(port, password, f"setblock {position} create:shaft")
    if not re.search(r"(?:unknown|unrecognized).*create:shaft|create:shaft.*(?:unknown|unrecognized)",
                     response, re.IGNORECASE):
        raise RuntimeError(
            "IT11 Create is present or the runtime block-absence probe did not fail specifically "
            f"for an unknown Create block: {response!r}"
        )


def verify_ae2_absent(port: int, password: str, position: str) -> None:
    """IT02/IT11: a live BCCE server must also work without any AE2 registry entries."""
    response = rcon_command(port, password, f"setblock {position} ae2:controller")
    if not re.search(r"(?:unknown|unrecognized).*ae2:controller|ae2:controller.*(?:unknown|unrecognized)",
                     response, re.IGNORECASE):
        raise RuntimeError(
            "AE2 is present or the runtime block-absence probe did not fail specifically "
            f"for an unknown AE2 block: {response!r}"
        )


def modpack_runtime_profile(*, without_create: bool) -> str:
    return "modpack-no-create" if without_create else "modpack"


def parse_entity_position(reply: str) -> tuple[int, int, int]:
    # Vanilla /data get entity <name> Pos reports a three-double NBT list.
    number = r"([+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?)[dDfF]?"
    match = re.search(r"\[\s*" + number + r"\s*,\s*" + number + r"\s*,\s*" + number + r"\s*\]", reply)
    if not match:
        raise RuntimeError(f"Cannot resolve live player position from RCON: {reply!r}")
    return tuple(math.floor(float(v)) for v in match.groups())


def verify_actual_modded_world(port: int, password: str, player: str, *,
                               origin: tuple[int, int, int] | None,
                               include_create: bool = True,
                               include_ae2: bool = True,
                               include_forestry: bool = False,
                               include_ic2: bool = False) -> tuple[int, int, int]:
    # One guaranteed loaded chunk around the joining player. Coordinate probes
    # use ABSOLUTE positions so the rejoin confirms persistence, even if the
    # player moved a few blocks or fell after the first login.
    require_online(port, password, {player})
    first_join = origin is None
    if first_join:
        origin = parse_entity_position(rcon_command(port, password, f"data get entity {player} Pos"))
    x, y, z = origin
    blocks = [
        ("buildcraftfactory:tank", x + 2),
        ("buildcraftfactory:autoworkbench_item", x + 8),
    ]
    if include_ae2:
        blocks.append(("ae2:controller", x + 4))
    if include_create:
        blocks.append(("create:shaft", x + 6))
    if include_forestry:
        blocks.append(("forestry:apiary", x + 20))
    if include_ic2:
        blocks.append(("ic2:generator", x + 22))
    for block_id, px in blocks:
        position = f"{px} {y} {z + 2}"
        if first_join:
            if block_id in ("forestry:apiary", "ic2:generator"):
                # Give industrial machines a stable support block across updates.
                support = rcon_command(port, password, f"setblock {px} {y - 1} {z + 2} minecraft:stone")
                if "changed the block" not in support.lower():
                    raise RuntimeError(f"Failed to prepare support for {block_id}: {support!r}")
            placed = rcon_command(port, password, f"setblock {position} {block_id}")
            if "changed the block" not in placed.lower():
                raise RuntimeError(f"Modded block {block_id} could not be placed: {placed!r}")
        # The Create shaft is a real modded block but has no Block Entity.
        # Requiring /data get block for every registered block is incorrect.
        token = "BCCE_IT16_BLOCK_VERIFIED"
        checked = rcon_command(port, password,
            f"execute if block {position} {block_id} run say {token}")
        if token not in checked:
            raise RuntimeError(f"Block registry/world check failed for {block_id}: {checked!r}")
    if not include_create:
        # Negative, runtime-visible compatibility probe: the server command
        # parser MUST reject a Create-only block ID. Merely omitting a JAR from
        # the requested profile does not prove that Create was absent at runtime.
        verify_create_absent(port, password, f"{x + 6} {y} {z + 2}")
    if not include_ae2:
        verify_ae2_absent(port, password, f"{x + 4} {y} {z + 2}")
    for block, px in (("tank", x + 2), ("autoworkbench", x + 8)):
        data = rcon_command(port, password, f"data get block {px} {y} {z + 2}")
        if "following block data" not in data.lower():
            raise RuntimeError(f"BCCE {block} BlockEntity did not load in the joined world: {data!r}")
    return origin


# A charged/enhanced stack fixture lives in a real chest, not a fabricated
# inventory. 1.20.1 uses legacy NBT; 1.21.1 serializes item Data Components.
# This is a persistence probe; robot/pipe transfers have separate GameTests.
def charged_stack_snbt(target: str) -> str:
    minecraft, loader = target.rsplit("-", 1)
    if minecraft == "1.20.1" and loader == "forge":
        return (
            "{Items:[{Slot:0b,id:\"minecraft:diamond_sword\",Count:1b,"
            "tag:{Damage:117,display:{Name:'{\"text\":\"IT16 charged sword\"}'},"
            "Enchantments:[{id:\"minecraft:sharpness\",lvl:3s}],"
            "BCCEProbeCharge:8765432L,BCCEProbeNested:{owner:\"test\"}}}]}"
        )
    if minecraft == "1.21.1" and loader == "neoforge":
        return (
            "{Items:[{Slot:0b,id:\"minecraft:diamond_sword\",count:1,"
            "components:{\"minecraft:damage\":117,\"minecraft:custom_name\":"
            "'{\"text\":\"IT16 charged sword\"}',\"minecraft:enchantments\":"
            "{levels:{\"minecraft:sharpness\":3}},"
            "\"minecraft:custom_data\":{BCCEProbeCharge:8765432L,"
            "BCCEProbeNested:{owner:\"test\"}}}}]}"
        )
    raise ValueError(f"No verified charged stack NBT fixture for {target}")


def verify_persisted_charged_cargo(port: int, password: str, target: str,
                                   origin: tuple[int, int, int], *,
                                   expected: str | None) -> str:
    x, y, z = origin
    position = f"{x + 10} {y} {z + 2}"
    if expected is None:
        result = rcon_command(port, password, f"setblock {position} minecraft:chest")
        if "changed the block" not in result.lower():
            raise RuntimeError(f"Could not place charged-stack persistence fixture: {result!r}")
        result = rcon_command(port, password, f"data merge block {position} {charged_stack_snbt(target)}")
        if "modified" not in result.lower():
            raise RuntimeError(f"Charged stack was not installed in real chest: {result!r}")
    snapshot = rcon_command(port, password, f"data get block {position} Items[0]")
    for required in ("minecraft:diamond_sword", "117", "sharpness", "8765432", "BCCEProbeNested"):
        if required not in snapshot:
            raise RuntimeError(f"Persisted ItemStack lost {required!r}: {snapshot!r}")
    if expected is not None and snapshot != expected:
        raise RuntimeError("Charged/enhanced ItemStack NBT/Data Components changed across the world restart: "
                           f"before={expected!r} after={snapshot!r}")
    return snapshot


# IT01: persisted travelling-item fixture. The item is written into the genuine
# BCCE Pipe Holder's persistent flow schema, then advances exclusively through
# the real PipeFlowItems tick logic. This is a true server-process restart,
# unlike a GameTest that only round-trips Block Entity NBT in one process.
# The injection itself is a deterministic saved-state fixture, not a wooden
# pipe extraction or a robot selecting a task.
FLIGHT_PIPE = "buildcrafttransport:pipe_holder"
FLIGHT_DEFINITION = "buildcrafttransport:cobblestone_item"
FLIGHT_STACK_KEYS = (
    "minecraft:diamond_sword", "117", "sharpness", "8765432",
    "BCCEProbeNested", "IT16 charged sword", "owner",
)


def in_flight_positions(origin: tuple[int, int, int]) -> tuple[str, str, str, str]:
    x, y, z = origin
    return tuple(f"{x + offset} {y} {z + 2}" for offset in (15, 16, 17, 18))


def pipe_travelling_item_snbt(target: str) -> str:
    """Use the same version-specific full-component item as the chest persistence probe."""
    chest = charged_stack_snbt(target)
    prefix = "{Items:[{Slot:0b,"
    if not chest.startswith(prefix) or not chest.endswith("}]}"):
        raise ValueError("IT01 cannot extract the pinned full-component item NBT")
    stack = "{" + chest[len(prefix):-3] + "}"
    return (
        "{stack:" + stack + ",colour:0b,toCenter:1b,pausedAtCenter:0b,"
        "speed:0.001d,tickStarted:0,tickFinished:1000,timeToDest:1000,"
        'side:"WEST",tried:0b}'
    )


def require_rcon_changed(response: str, description: str) -> None:
    if not re.search(r"\bchanged the block\b|\bmodified\b", response, re.IGNORECASE):
        raise RuntimeError(f"IT01 could not install {description}: {response!r}")


def verify_pipe_flow_snapshot(response: str) -> None:
    """A failed RCON path query must not count as an active travelling item."""
    if any(value not in response for value in FLIGHT_STACK_KEYS):
        raise RuntimeError(f"IT01 flow does not contain the complete charged weapon: {response!r}")
    if "stack" not in response or "tickFinished" not in response or "toCenter" not in response:
        raise RuntimeError(f"IT01 travelling-item state is incomplete: {response!r}")


def prepare_saved_inflight_pipe(port: int, password: str, target: str,
                                origin: tuple[int, int, int]) -> None:
    """Stage a non-empty BCCE item flow immediately BEFORE the server flush/stop."""
    chest_in, first, second, chest_out = in_flight_positions(origin)
    for position, block in ((chest_in, "minecraft:chest"), (first, FLIGHT_PIPE),
                            (second, FLIGHT_PIPE), (chest_out, "minecraft:chest")):
        reply = rcon_command(port, password, f"setblock {position} {block}")
        require_rcon_changed(reply, f"{block} at {position}")
    # Install the real BCCE pipe definition and its persistent item flow.
    # The west chest provides a legitimate entry connection; the second pipe
    # and output chest are physical, real neighbours in the same loaded world.
    first_nbt = (
        '{pipe:{def:"' + FLIGHT_DEFINITION +
        '",beh:{},flow:{items:[' + pipe_travelling_item_snbt(target) + ']}}}'
    )
    second_nbt = '{pipe:{def:"' + FLIGHT_DEFINITION + '",beh:{},flow:{items:[]}}}'
    for position, data in ((second, second_nbt), (first, first_nbt)):
        reply = rcon_command(port, password, f"data merge block {position} {data}")
        require_rcon_changed(reply, f"physical BCCE item pipe at {position}")
    snapshot = rcon_command(port, password, f"data get block {first} pipe.flow.items[0]")
    verify_pipe_flow_snapshot(snapshot)


def verify_saved_inflight_pipe_delivered(port: int, password: str,
                                        origin: tuple[int, int, int],
                                        processes: list[subprocess.Popen]) -> None:
    """Fail unless the restarted world naturally delivers exactly one intact stack."""
    _, first, second, chest_out = in_flight_positions(origin)
    for position in (first, second):
        token = "BCCE_IT01_PIPE_EXISTS"
        reply = rcon_command(port, password,
                             f"execute if block {position} {FLIGHT_PIPE} run say {token}")
        if token not in reply:
            raise RuntimeError(f"IT01 pipeline block was missing after server restart: {position}: {reply!r}")

    def destination_received() -> bool:
        reply = rcon_command(port, password, f"data get block {chest_out} Items[0]")
        return all(key in reply for key in FLIGHT_STACK_KEYS)

    wait_until("saved BCCE in-flight item reached real warehouse after server restart",
               destination_received, processes, 180)
    # The output chest must hold only the requested single item. A second copy
    # or a stale/undelivered in-pipe item is a failure, not a successful restore.
    chest = rcon_command(port, password, f"data get block {chest_out} Items")
    if chest.count("minecraft:diamond_sword") != 1 or chest.count("Slot:") != 1:
        raise RuntimeError(f"IT01 stored cargo was duplicated or mixed with extra stacks: {chest!r}")
    if not re.search(r"\b(?:Count|count)\s*:\s*1b?\b", chest):
        raise RuntimeError(f"IT01 delivered a non-unit item count: {chest!r}")
    if not all(key in chest for key in FLIGHT_STACK_KEYS):
        raise RuntimeError(f"IT01 destination lost part of item components: {chest!r}")
    for position in (first, second):
        flow = rcon_command(port, password, f"data get block {position} pipe.flow.items")
        if not re.search(r"\[\s*\]", flow):
            raise RuntimeError(f"IT01 restored pipe still holds in-flight cargo: {position}: {flow!r}")
    # ItemEntity spillover is not a valid alternate storage destination.
    x, y, z = origin
    check = rcon_command(port, password,
        f"execute positioned {x + 16} {y} {z + 2} if entity "
        f"@e[type=minecraft:item,distance=..6] run say BCCE_IT01_DROP")
    if "BCCE_IT01_DROP" in check:
        raise RuntimeError("IT01 pipeline leaked an ItemEntity into the world after restart")


# IT08: perform actual chunk unload/reload in the running dedicated world.
# The fixture lives 16 chunks away from spawn, outside both 1.20.1's legacy
# spawn-loaded area and the much smaller newer spawn area. It is intentionally
# NOT forced: a positive unload observation is mandatory before a pass.
REMOTE_OFFSET = 256
REMOTE_ESCAPE_OFFSET = 1024
UNLOADED_REPLY_RE = re.compile(r"\b(?:not loaded|is unloaded|chunk is unloaded)\b", re.IGNORECASE)


def chunk_fixture_positions(origin: tuple[int, int, int]) -> tuple[str, str, tuple[int, int, int], tuple[int, int, int]]:
    x, _, z = origin
    visit = (x + REMOTE_OFFSET, 220, z + REMOTE_OFFSET)
    escape = (x + REMOTE_ESCAPE_OFFSET, 220, z + REMOTE_ESCAPE_OFFSET)
    chest = f"{visit[0] + 1} 219 {visit[2] + 1}"
    workbench = f"{visit[0] + 3} 219 {visit[2] + 1}"
    return chest, workbench, visit, escape


def chunk_loaded_reply(reply: str) -> bool:
    # A missing BE / syntax error is NOT evidence of successful chunk unloading.
    if UNLOADED_REPLY_RE.search(reply):
        return False
    if "following block data" in reply.lower():
        return True
    raise RuntimeError(f"Cannot determine chunk load state from RCON: {reply!r}")


def teleport_and_confirm(port: int, password: str, player: str,
                         point: tuple[int, int, int], processes: list[subprocess.Popen]) -> None:
    x, y, z = point
    response = rcon_command(port, password, f"tp {player} {x} {y} {z}")
    if "teleport" not in response.lower():
        raise RuntimeError(f"Server did not confirm teleport of {player}: {response!r}")
    wait_until("real player reached chunk probe", lambda:
               parse_entity_position(rcon_command(port, password, f"data get entity {player} Pos"))[::2]
               == (x, z), processes, 40)


def verify_chunk_unload_and_machine_persistence(port: int, password: str, target: str,
                                                  player: str, origin: tuple[int, int, int],
                                                  processes: list[subprocess.Popen],
                                                  *, expected: str | None) -> str:
    """Probe real chunk unload, return, and stable world NBT across a server restart.

    Server-side command access is deliberate: /data get block checks the chunk
    without forcing it to load, unlike a GameTest helper or a ticket request.
    This is a chunk lifecycle + persistence test, NOT a claim that a live robot
    or a Quarry's persisted ticket survived the restart.
    """
    chest, machine, near, far = chunk_fixture_positions(origin)
    # Prevent fixture visits from killing a survival-mode test player on return.
    gamemode = rcon_command(port, password, f"gamemode creative {player}")
    if not re.search(r"creative|game mode", gamemode, re.IGNORECASE):
        raise RuntimeError(f"Could not set harmless probe game mode: {gamemode!r}")
    if expected is not None:
        # The server just restarted. Before approaching the remote site, its
        # chunks must not be kept alive by an orphaned chunk-loading ticket.
        wait_until("remote probe chunk unloaded after server restart", lambda:
                   not chunk_loaded_reply(rcon_command(port, password, f"data get block {chest}")),
                   processes, 100)
    teleport_and_confirm(port, password, player, near, processes)
    if expected is None:
        for position, block in ((chest, "minecraft:chest"),
                                (machine, "buildcraftfactory:autoworkbench_item")):
            reply = rcon_command(port, password, f"setblock {position} {block}")
            if "changed the block" not in reply.lower():
                raise RuntimeError(f"IT08 could not place real {block}: {reply!r}")
        reply = rcon_command(port, password, f"data merge block {chest} {charged_stack_snbt(target)}")
        if "modified" not in reply.lower():
            raise RuntimeError(f"IT08 failed to store charged stack in world: {reply!r}")
    # Read the actual block entity, not merely a registry entry or a logfile.
    snapshot = rcon_command(port, password, f"data get block {chest} Items[0]")
    for marker in ("minecraft:diamond_sword", "sharpness", "117", "8765432", "BCCEProbeNested"):
        if marker not in snapshot:
            raise RuntimeError(f"IT08 lost stack field {marker}: {snapshot!r}")
    if expected is not None and snapshot != expected:
        raise RuntimeError(f"IT08 remote chest changed across real server restart: {snapshot!r} != {expected!r}")
    block_data = rcon_command(port, password, f"data get block {machine}")
    if not chunk_loaded_reply(block_data):
        raise RuntimeError("IT08 remote BCCE Auto Workbench Block Entity missing after world reload")

    if expected is None:
        # Walking far away must release the remote machine chunk. Its contents
        # may only reappear after returning and loading that same chunk again.
        teleport_and_confirm(port, password, player, far, processes)
        wait_until("remote chunk genuinely unloaded", lambda:
                   not chunk_loaded_reply(rcon_command(port, password, f"data get block {chest}")),
                   processes, 100)
        teleport_and_confirm(port, password, player, near, processes)
        recovered = rcon_command(port, password, f"data get block {chest} Items[0]")
        if recovered != snapshot:
            raise RuntimeError("Remote charged item changed after the actual chunk unload/reload")
        if not chunk_loaded_reply(rcon_command(port, password, f"data get block {machine}")):
            raise RuntimeError("Remote BCCE machine Block Entity missing after chunk unload/reload")

    teleport_and_confirm(port, password, player, origin, processes)
    require_online(port, password, {player})
    return snapshot


# IT09 subset, exercised during IT16's real dedicated-client session. The
# recipe is deliberately vanilla-serializable but registered by a separate
# datapack namespace. It tests a live recipe-registry *mutation* and reload,
# not merely startup with an unchanged recipe set.
RELOAD_RECIPE_ID = "bcce_ci:reload_probe"
RELOAD_PACK_NAME = "bcce-it09-live-recipes"


def recipe_pack_layout(target: str) -> tuple[int, str, str]:
    minecraft, loader = target.rsplit("-", 1)
    if minecraft == "1.20.1" and loader == "forge":
        return 15, "recipes", "item"
    if minecraft == "1.21.1" and loader == "neoforge":
        return 48, "recipe", "id"
    raise ValueError(f"No tested live-reload datapack layout for {target}")


def install_live_recipe_fixture(target: str, world: Path) -> Path:
    pack_format, folder, result_key = recipe_pack_layout(target)
    pack = world / "datapacks" / RELOAD_PACK_NAME
    recipe = pack / "data" / "bcce_ci" / folder / "reload_probe.json"
    recipe.parent.mkdir(parents=True, exist_ok=True)
    (pack / "pack.mcmeta").write_text(json.dumps({"pack": {"pack_format": pack_format,
        "description": "BCCE in-world recipe hot-reload probe"}}), encoding="utf-8")
    recipe.write_text(json.dumps({"type": "minecraft:crafting_shapeless",
        "ingredients": [{"item": "minecraft:cobblestone"}],
        "result": {result_key: "minecraft:stone", "count": 1}}, sort_keys=True), encoding="utf-8")
    return recipe


def recipe_reply_state(reply: str) -> str:
    # The RCON response may reflect a previously granted/unlocked recipe. Both
    # positive outcomes prove the recipe ID was recognized by the server parser.
    lower = reply.lower()
    if re.search(r"\bgave\b.+\brecipe|\bno new recipes\b", lower):
        return "present"
    if re.search(r"unknown recipe|invalid recipe|incorrect argument|expected recipe", lower):
        return "absent"
    raise RuntimeError(f"Unrecognised /recipe response (not a pass): {reply!r}")


def recipe_state(port: int, password: str, player: str) -> str:
    return recipe_reply_state(rcon_command(port, password, f"recipe give {player} {RELOAD_RECIPE_ID}"))


def await_recipe_state(port: int, password: str, player: str, state: str,
                       processes: list[subprocess.Popen], timeout: float = 120) -> None:
    # A reload returns early while recipe parsing and client sync continue.
    # Never accept a /reload RCON ACK alone as completion.
    wait_until(f"live recipe state {state}",
               lambda: recipe_state(port, password, player) == state,
               processes, timeout)


def verify_live_recipe_reload(target: str, server_dir: Path, port: int, password: str,
                              player: str, processes: list[subprocess.Popen]) -> None:
    recipe = install_live_recipe_fixture(target, server_dir / "bcce-it16-world")
    # A datapack created while Minecraft is already running may not appear in
    # the repository until its first /reload scans the world datapack directory.
    response = rcon_command(port, password, "reload")
    if re.search(r"unknown|failed|could not", response, re.IGNORECASE):
        raise RuntimeError(f"Could not refresh world datapack repository: {response!r}")
    wait_until("new recipe datapack discovered", lambda:
               RELOAD_PACK_NAME in rcon_command(port, password, "datapack list available")
               or RELOAD_PACK_NAME in rcon_command(port, password, "datapack list enabled"),
               processes, 120)
    enabled = rcon_command(port, password, "datapack list enabled")
    if RELOAD_PACK_NAME not in enabled:
        response = rcon_command(port, password, f'datapack enable "file/{RELOAD_PACK_NAME}"')
        if re.search(r"unknown|failed|cannot|could not|already enabled", response, re.IGNORECASE):
            raise RuntimeError(f"Could not enable IT09 datapack: {response!r}")
    await_recipe_state(port, password, player, "present", processes)

    # Remove exactly the recipe JSON while leaving the datapack itself enabled.
    # A known but removed recipe must not remain visible to recipe commands.
    previous = recipe.read_bytes()
    recipe.unlink()
    try:
        response = rcon_command(port, password, "reload")
        if re.search(r"unknown|failed|could not", response, re.IGNORECASE):
            raise RuntimeError(f"Datapack removal reload rejected: {response!r}")
        await_recipe_state(port, password, player, "absent", processes)
    finally:
        recipe.write_bytes(previous)
    response = rcon_command(port, password, "reload")
    if re.search(r"unknown|failed|could not", response, re.IGNORECASE):
        raise RuntimeError(f"Datapack restoration reload rejected: {response!r}")
    await_recipe_state(port, password, player, "present", processes)
    online = rcon_command(port, password, "list")
    if player.lower() not in online.lower():
        raise RuntimeError("Hot recipe reload disconnected the active modded client")


def read_text(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8", errors="replace")
    except FileNotFoundError:
        return ""


# IT06: optional real-client resource reload. This requires a controllable X11
# display, since xvfb-run owns a private DISPLAY that an external xdotool cannot
# address. No success is inferred from key delivery alone: a fresh resource
# manager reload AND a fresh baked block atlas must be observed in latest.log.
CLIENT_RESOURCE_RELOAD_RE = re.compile(r"Reloading ResourceManager|Reloading resource packs", re.IGNORECASE)
CLIENT_BLOCK_ATLAS_RE = re.compile(r"minecraft:textures/atlas/blocks\.png-atlas", re.IGNORECASE)
CLIENT_BCCE_RESOURCE_ERROR_RE = re.compile(
    r"(?:Missing model for variant|Failed to load model|Unable to load model|"
    r"Using missing texture, unable to load).*buildcraft|"
    r"buildcraft.*(?:Missing model for variant|Failed to load model|Unable to load model|"
    r"Using missing texture, unable to load)|Invalid path in pack: buildcraft",
    re.IGNORECASE,
)


def client_reload_log_state(log: Path, start_offset: int) -> tuple[bool, bool]:
    content = read_text(log)
    if len(content) < start_offset:
        raise RuntimeError("Client log was truncated during hot resource reload")
    delta = content[start_offset:]
    return bool(CLIENT_RESOURCE_RELOAD_RE.search(delta)), bool(CLIENT_BLOCK_ATLAS_RE.search(delta))


def reject_client_resource_errors(log: Path, start_offset: int) -> None:
    content = read_text(log)
    if len(content) < start_offset:
        raise RuntimeError("Client log was truncated during resource error verification")
    error = CLIENT_BCCE_RESOURCE_ERROR_RE.search(content[start_offset:])
    if error:
        raise RuntimeError(f"BCCE model/texture failed after real client resource reload: {error.group(0)}")


def start_private_display(log: Path, cwd: Path, env: dict[str, str]) -> tuple[subprocess.Popen, str]:
    """Give xdotool a known X server, without sharing another test's display."""
    for _ in range(12):
        number = secrets.randbelow(25_000) + 300
        display_socket = Path(f"/tmp/.X11-unix/X{number}")
        lock = Path(f"/tmp/.X{number}-lock")
        if display_socket.exists() or lock.exists():
            continue
        display = f":{number}"
        process = start_process(["Xvfb", display, "-screen", "0", "1280x720x24", "-nolisten", "tcp"],
                                log, cwd, env)
        until = time.monotonic() + 15
        while time.monotonic() < until:
            if process.poll() is not None:
                break
            if display_socket.exists():
                return process, display
            time.sleep(0.25)
        stop_process(process)
    raise RuntimeError("Unable to launch an isolated Xvfb display for client UI tests")


def find_minecraft_window(env: dict[str, str]) -> str | None:
    result = subprocess.run(["xdotool", "search", "--onlyvisible", "--name", "Minecraft"],
                            env=env, text=True, capture_output=True, timeout=10, check=False)
    if result.returncode not in (0, 1):
        raise RuntimeError(f"xdotool window search failed: {result.stderr.strip()}")
    ids = [line for line in result.stdout.splitlines() if line.isdecimal()]
    if not ids:
        return None
    # Exactly one Minecraft client is allowed in this experimental UI mode.
    if len(ids) != 1:
        raise RuntimeError(f"Expected one visible Minecraft window, found {ids}")
    return ids[0]


def verify_client_resource_reload(client_log: Path, port: int, password: str, player: str,
                                  processes: list[subprocess.Popen], env: dict[str, str]) -> None:
    """Send F3+T to the actual game window, then observe a new block atlas.

    This proves that a connected JEI/Jade/BCCE client survived a real resource
    reload; it does not assert screenshot/pixel correctness of JEI/Jade overlays.
    """
    wait_until("visible Minecraft client window", lambda: find_minecraft_window(env) is not None,
               processes, 60)
    window = find_minecraft_window(env)
    if window is None:
        raise RuntimeError("Minecraft client window disappeared before F3+T")
    baseline = len(read_text(client_log))
    subprocess.run(["xdotool", "windowfocus", "--sync", window], env=env,
                   check=True, text=True, capture_output=True, timeout=20)
    subprocess.run(["xdotool", "key", "--clearmodifiers", "F3+t"], env=env,
                   check=True, text=True, capture_output=True, timeout=20)
    wait_until("F3+T triggered a new Minecraft ResourceManager reload",
               lambda: client_reload_log_state(client_log, baseline)[0], processes, 100)
    wait_until("Minecraft block atlas rebuilt after F3+T",
               lambda: client_reload_log_state(client_log, baseline)[1], processes, 150)
    require_online(port, password, {player})
    check_fatal(client_log)
    reject_client_resource_errors(client_log, baseline)


def wait_until(label: str, predicate, processes: list[subprocess.Popen], timeout: float) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        for proc in processes:
            rc = proc.poll()
            if rc is not None:
                raise RuntimeError(f"{label}: Minecraft/Gradle process exited with {rc}")
        if predicate():
            return
        time.sleep(2)
    raise TimeoutError(f"{label}: timed out after {timeout:g}s")


def start_process(cmd: list[str], log: Path, cwd: Path, env: dict[str, str]) -> subprocess.Popen:
    log.parent.mkdir(parents=True, exist_ok=True)
    stream = log.open("wb")
    try:
        proc = subprocess.Popen(
            cmd, cwd=cwd, env=env, stdin=subprocess.DEVNULL,
            stdout=stream, stderr=subprocess.STDOUT, start_new_session=True,
        )
    finally:
        stream.close()
    print("START", " ".join(cmd), "log=", log, flush=True)
    return proc


def stop_process(proc: subprocess.Popen | None) -> None:
    if proc is None or proc.poll() is not None:
        return
    try:
        os.killpg(proc.pid, signal.SIGTERM)
        proc.wait(timeout=15)
    except (ProcessLookupError, subprocess.TimeoutExpired):
        try:
            os.killpg(proc.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        try:
            proc.wait(timeout=3)
        except subprocess.TimeoutExpired:
            pass


def resolve_locked_mods(target: str) -> tuple[str, dict[str, str]]:
    config = validate_modpack_lock(target)
    return config["generation"], dict(config["mods"])


def select_locked_mods(target: str, *, without_ae2: bool) -> tuple[str, dict[str, str]]:
    generation, mods = resolve_locked_mods(target)
    if without_ae2:
        # AE2 is an external pinned JAR, not a Gradle runtime dependency.
        # Suppress it for every game directory in the negative profile.
        if "ae2" not in mods:
            raise RuntimeError("Cannot exercise optional AE2 removal without an AE2 lock entry")
        mods = {name: version for name, version in mods.items() if name not in ("ae2", "guideme")}
    return generation, mods


def download_locked_mod(version_id: str, target: str, destination: Path,
                        *, mod_name: str = "") -> Path:
    """Resolve a locked Modrinth version, verify loader/MC/project, then SHA-512."""
    minecraft_version, loader = target.rsplit("-", 1)
    request_headers = {"User-Agent": "BCCE-CI-WorldJoin/2.0"}
    if "@" in version_id:
        project, exact_version = version_id.split("@", 1)
        if mod_name != project or not exact_version:
            raise RuntimeError(f"Unknown project/version pin {version_id!r}")
        url = f"https://api.modrinth.com/v2/project/{quote(project)}/version"
        with urllib.request.urlopen(urllib.request.Request(url, headers=request_headers), timeout=60) as response:
            versions = json.load(response)
        matches = [item for item in versions if item.get("version_number") == exact_version and
                   minecraft_version in item.get("game_versions", []) and loader in item.get("loaders", [])]
        if len(matches) != 1:
            raise RuntimeError(f"No unique exact Modrinth pin {version_id} for {target}")
        meta = matches[0]
    else:
        if not re.fullmatch(r"[A-Za-z0-9]{8}", version_id):
            raise RuntimeError(f"Invalid immutable Modrinth version ID {version_id!r}")
        url = "https://api.modrinth.com/v2/version/" + quote(version_id)
        with urllib.request.urlopen(urllib.request.Request(url, headers=request_headers), timeout=60) as response:
            meta = json.load(response)
        if meta.get("id") != version_id:
            raise RuntimeError(f"Modrinth version ID changed for {version_id}")
    if minecraft_version not in meta.get("game_versions", []) or loader not in meta.get("loaders", []):
        raise RuntimeError(f"Pinned Modrinth version {version_id} does not match target {target}")
    expected_project = MODRINTH_PROJECTS.get(mod_name)
    if expected_project and meta.get("project_id") != expected_project:
        raise RuntimeError(f"Expected Modrinth project {expected_project} for {mod_name}, got {meta.get('project_id')}")
    files = [item for item in meta.get("files", []) if item.get("filename", "").endswith(".jar")]
    if not files:
        raise RuntimeError(f"No JAR for Modrinth version {version_id}")
    chosen = next((item for item in files if item.get("primary")), None)
    if chosen is None:
        raise RuntimeError(f"No primary JAR for {version_id}, refusing an auxiliary/source JAR")
    filename = Path(chosen["filename"]).name
    file_url = chosen["url"]
    if not file_url.startswith("https://"):
        raise RuntimeError("Refusing non-HTTPS mod download")
    destination.mkdir(parents=True, exist_ok=True)
    resolved_id = meta["id"]
    path = destination / (resolved_id + "-" + filename)
    sha512 = chosen.get("hashes", {}).get("sha512")
    if not sha512:
        raise RuntimeError(f"Missing SHA-512 checksum for {filename}")
    if not path.exists() or hashlib.sha512(path.read_bytes()).hexdigest() != sha512:
        request = urllib.request.Request(file_url, headers=request_headers)
        with urllib.request.urlopen(request, timeout=180) as response:
            data = response.read()
        if hashlib.sha512(data).hexdigest() != sha512:
            raise RuntimeError(f"Checksum mismatch downloading {filename}")
        path.write_bytes(data)
    print("LOCKED", mod_name, filename, "sha512=", sha512, flush=True)
    return path


def create_run_directories(generation: str, target: str, mods: dict[str, str], port: int, offline: bool,
                           rcon_port: int = 25589, rcon_password: str = "",
                           second_client: bool = False) -> tuple[Path, Path, Path]:
    base = ROOT / "run-modpack-join" / generation / target
    if base.exists():
        shutil.rmtree(base)
    server = base / "server"
    client = base / "client"
    run_directories = (server, client, base / "client-2") if second_client else (server, client)
    for directory in run_directories:
        (directory / "mods").mkdir(parents=True, exist_ok=True)
    (server / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (server / "server.properties").write_text(
        "\n".join([
            "online-mode=false", "enforce-secure-profile=false", "server-ip=127.0.0.1",
            f"server-port={port}", "max-players=2", "spawn-protection=0", "allow-flight=true",
            "view-distance=4", "simulation-distance=4", "level-name=bcce-it16-world",
            "enable-rcon=true", f"rcon.port={rcon_port}", f"rcon.password={rcon_password}",
            "generate-structures=false", "motd=BCCE IT16 world join and rejoin",
        ]) + "\n", encoding="utf-8",
    )
    (server / "logs").mkdir(parents=True, exist_ok=True)
    (client / "logs").mkdir(parents=True, exist_ok=True)
    if second_client:
        (base / "client-2" / "logs").mkdir(parents=True, exist_ok=True)
    if offline:
        for name, version in mods.items():
            # project@exact-version pins may be cached under the resolved Modrinth
            # version ID, but their descriptive JAR filename retains the release.
            version_hint = version.split("@", 1)[-1]
            matches = list((ROOT / "ci-modpack-cache").glob(f"*{version_hint}*.jar"))
            if len(matches) != 1:
                raise RuntimeError(f"Offline mode: expected 1 cached JAR for {name} ({version}), found {len(matches)}")
            jar = matches[0]
            for run in run_directories:
                shutil.copy2(jar, run / "mods" / jar.name)
    else:
        for name, version in mods.items():
            jar = download_locked_mod(version, target, ROOT / "ci-modpack-cache", mod_name=name)
            for run in run_directories:
                shutil.copy2(jar, run / "mods" / jar.name)
    return base, server, client


def remove_pinned_ae2_after_shutdown(base: Path, version_id: str, *, two_players: bool) -> None:
    """IT14: change the *actual* client and dedicated server modpacks between restarts.

    The server/client processes must be stopped before this call. A missing or
    ambiguous pinned JAR is a harness error, not evidence of a migration pass.
    """
    directories = [base / "server", base / "client"]
    if two_players:
        directories.append(base / "client-2")
    planned: list[Path] = []
    for game in directories:
        mods = game / "mods"
        matches = sorted(mods.glob(version_id + "-*.jar"))
        if len(matches) != 1:
            raise RuntimeError(
                f"IT14 expected exactly one locked AE2 JAR in {mods}, found {len(matches)}"
            )
        planned.append(matches[0])
    # Verify *all* three directories before mutating any of them.
    for jar in planned:
        jar.unlink()
    for jar in planned:
        if jar.exists():
            raise RuntimeError(f"IT14 AE2 JAR still exists after removal: {jar}")


def check_fatal(*files: Path) -> None:
    for file in files:
        text = read_text(file)
        match = FATAL_RE.search(text)
        if match:
            raise RuntimeError(f"Fatal client/server problem in {file}: {match.group(0)}")


def tail_logs(*files: Path) -> None:
    for path in files:
        text = read_text(path)
        print(f"--- {path} (last 90 lines) ---", file=sys.stderr)
        print("\n".join(text.splitlines()[-90:]), file=sys.stderr)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", required=True)
    parser.add_argument("--port", type=int, default=25588)
    parser.add_argument("--rcon-port", type=int, default=25589)
    parser.add_argument("--startup-timeout", type=int, default=750)
    parser.add_argument("--join-timeout", type=int, default=570)
    parser.add_argument("--stable-seconds", type=int, default=25)
    parser.add_argument("--restart-timeout", type=int, default=750)
    parser.add_argument("--offline", action="store_true", help="Use pre-cached pinned external JARs")
    parser.add_argument("--smoke-only", action="store_true",
                        help="Actual server/client join, save, restart and rejoin; skip older version-specific NBT fixtures")
    parser.add_argument("--inflight-pipe", action="store_true",
                        help="IT01 manual: resume a full-component travelling item in a real pipe after server restart")
    parser.add_argument("--chunk-cycle", action="store_true",
                        help="IT08: verify a real remote chunk unload/reload and persistence across restart")
    parser.add_argument("--two-players", action="store_true",
                        help="IT15 subset: second real client, online reconfiguration and restart")
    parser.add_argument("--client-resource-reload", action="store_true",
                        help="IT06 manual: press F3+T in real joined client and verify a newly rebuilt block atlas")
    parser.add_argument("--without-create", action="store_true",
                        help="IT11: run with JEI/Jade and all supported mods except Create")
    parser.add_argument("--without-ae2", action="store_true",
                        help="IT02/IT11: run with JEI/Jade and all supported mods except AE2/GuideME")
    parser.add_argument("--remove-ae2-after-restart", action="store_true",
                        help="IT14: save a world with AE2, remove AE2 between two real server/client sessions")
    parser.add_argument("--validate-lock", action="store_true", help="Check IT16 lock without downloading/starting")
    args = parser.parse_args()
    if args.client_resource_reload and args.two_players:
        parser.error("--client-resource-reload needs a single Minecraft window; disable --two-players")
    if args.without_ae2 and args.remove_ae2_after_restart:
        parser.error("--remove-ae2-after-restart requires AE2 in the first session")
    if args.smoke_only and (args.chunk_cycle or args.inflight_pipe):
        parser.error("--smoke-only is incompatible with IT08 chunk cycle and IT01 in-flight cargo")
    if not args.smoke_only and args.target not in FULL_FIXTURE_TARGETS and not args.validate_lock:
        parser.error(f"{args.target} requires --smoke-only until exact native ItemStack NBT fixtures are ported")
    config = validate_modpack_lock(args.target)
    if args.without_ae2 and not config["present"]["ae2"]:
        parser.error("--without-ae2 requires a target with AE2 in its normal profile")
    if args.without_create and not config["present"]["create"]:
        parser.error("--without-create requires a target with Create in its normal profile")
    generation, mods = select_locked_mods(args.target, without_ae2=args.without_ae2)
    if args.validate_lock:
        print(json.dumps({"target": args.target, "generation": generation, "mods": mods,
                          "present": config["present"], "required_client": ["jei", "jade"]}, indent=2))
        return 0
    if shutil.which("xvfb-run") is None:
        raise RuntimeError("xvfb-run is required for a real client world join")
    if args.client_resource_reload and (shutil.which("Xvfb") is None or shutil.which("xdotool") is None):
        raise RuntimeError("IT06 requires Xvfb and xdotool for real client keyboard input")
    # The per-target Java version is selected by the workflow environment / JAVA_HOME.
    gradlew = ROOT / "builds" / generation / "gradlew"
    if not gradlew.is_file():
        raise FileNotFoundError(gradlew)
    password = secrets.token_hex(24)
    base, server_dir, client_dir = create_run_directories(generation, args.target, mods, args.port, args.offline,
                                                          args.rcon_port, password, second_client=args.two_players)
    launch_dir = gradlew.parent
    connection = f"127.0.0.1:{args.port}"
    flags = ["--no-daemon", "--console=plain", "--stacktrace",
             "-Pci_runtime_profile=" + modpack_runtime_profile(without_create=args.without_create),
             "-Pci_world_join=true", f"-Pci_world_join_address={connection}"]
    server_log = base / "server-gradle.log"
    client_gradle_log = base / "client-gradle.log"
    world_log = server_dir / "logs" / "latest.log"
    client_log = client_dir / "logs" / "latest.log"
    secondary_log = base / "client2-gradle.log"
    secondary_client_log = base / "client-2" / "logs" / "latest.log"
    server = None
    client = None
    secondary_client = None
    private_display = None
    results = {
        "test": "IT02/IT11/IT16 optional-mod compatibility world join",
        "target": args.target,
        "locked_external_mods": mods,
        "runtime_profile": modpack_runtime_profile(without_create=args.without_create),
        "required_client_mods": ["jei", "jade"],
        "expected_mods": config["present"],
        "mode": "join-smoke" if args.smoke_only else "full-integration",
        "ae2_requested": config["present"]["ae2"] and not args.without_ae2,
        "create_requested": config["present"]["create"] and not args.without_create,
        "remove_ae2_after_restart": args.remove_ae2_after_restart,
        "client_resource_reload_requested": args.client_resource_reload,
        "inflight_pipe_requested": args.inflight_pipe,
        "success": False, "server_restarts": 0,
    }
    try:
        env = os.environ.copy()
        if args.client_resource_reload:
            private_display, display = start_private_display(base / "xvfb-display.log", launch_dir, env)
            env["DISPLAY"] = display
        server = start_process([str(gradlew), *flags, f":{args.target}:runServer"], server_log, launch_dir, env)
        wait_until("dedicated server ready", lambda: bool(READY_RE.search(read_text(world_log))),
                   [server], args.startup_timeout)
        check_fatal(server_log, world_log)
        print("IT16 dedicated server created world and reached ready state", flush=True)
        probe_origin = None
        charged_cargo_snapshot = None
        remote_chunk_snapshot = None
        for attempt in range(1, 3):
            existing = len(JOIN_RE.findall(read_text(world_log)))
            # Forge/NeoForge runs need an actual graphical display, not the server-side atlas smoke probe.
            graphical_prefix = [] if args.client_resource_reload else [
                "xvfb-run", "-a", "-s", "-screen 0 1280x720x24"]
            client_cmd = [*graphical_prefix, str(gradlew), *flags, f":{args.target}:runClient"]
            client = start_process(client_cmd, client_gradle_log, launch_dir, env)
            wait_until(f"client {attempt} joined world", lambda:
                       len(JOIN_RE.findall(read_text(world_log))) > existing,
                       [server, client], args.join_timeout)
            player = player_name_from_join_log(read_text(world_log))
            verify_mandatory_client_mods(client_log, client_gradle_log)
            ae2_expected = config["present"]["ae2"] and not args.without_ae2 and not (
                args.remove_ae2_after_restart and attempt == 2)
            create_expected = config["present"]["create"] and not args.without_create
            probe_origin = verify_actual_modded_world(
                args.rcon_port, password, player, origin=probe_origin,
                include_create=create_expected, include_ae2=ae2_expected,
                include_forestry=config["present"]["forestry"],
                include_ic2=config["present"]["ic2"])
            if not args.smoke_only:
                charged_cargo_snapshot = verify_persisted_charged_cargo(
                    args.rcon_port, password, args.target, probe_origin, expected=charged_cargo_snapshot)
            if args.chunk_cycle:
                remote_chunk_snapshot = verify_chunk_unload_and_machine_persistence(
                    args.rcon_port, password, args.target, player, probe_origin,
                    [server, client], expected=remote_chunk_snapshot)
                results["remote_chunk_lifecycle"] = (
                    "created -> actually unloaded -> reloaded -> server restarted -> reloaded"
                    if attempt == 2 else "created -> actually unloaded -> reloaded")
                print(f"IT08 remote chunk probe {attempt}/2: real Block Entity and components intact", flush=True)
            if attempt == 1 and not args.smoke_only:
                verify_live_recipe_reload(args.target, server_dir, args.rcon_port, password,
                                          player, [server, client])
                verify_persisted_charged_cargo(args.rcon_port, password, args.target,
                                               probe_origin, expected=charged_cargo_snapshot)
                results["live_recipe_reload"] = "present -> absent -> present, client remained online"
                print("IT09: live datapack recipe added, removed and restored while client stayed online", flush=True)
            elif attempt == 2:
                if not args.smoke_only:
                    await_recipe_state(args.rcon_port, password, player, "present", [server, client])
                if args.inflight_pipe:
                    verify_saved_inflight_pipe_delivered(
                        args.rcon_port, password, probe_origin, [server, client])
                    results["saved_inflight_pipe"] = "one exact item delivered, both flows empty, no dropped cargo"
                    print("IT01: saved travelling item survived actual server restart and arrived exactly once", flush=True)
            if attempt == 1 and args.client_resource_reload:
                verify_client_resource_reload(client_log, args.rcon_port, password, player,
                                              [server, client], env)
                results["client_resource_reload"] = "F3+T -> resource manager reloaded -> block atlas rebaked"
                print("IT06: real joined Minecraft client completed F3+T resource reload", flush=True)
            if args.two_players:
                previous_second_joins = sum(match.group("name") == SECOND_PLAYER
                    for match in JOIN_PLAYER_RE.finditer(read_text(world_log)))
                second_flags = [*flags,
                    "-Pci_world_join_client_slot=2",
                    f"-Pci_world_join_username={SECOND_PLAYER}"]
                secondary_cmd = ["xvfb-run", "-a", "-s", "-screen 0 1280x720x24",
                    str(gradlew), *second_flags, f":{args.target}:runClient"]
                secondary_client = start_process(secondary_cmd, secondary_log, launch_dir, env)
                wait_until(f"second actual client joined world (attempt {attempt})", lambda:
                    sum(match.group("name") == SECOND_PLAYER for match
                        in JOIN_PLAYER_RE.finditer(read_text(world_log))) > previous_second_joins,
                    [server, client, secondary_client], args.join_timeout)
                verify_second_client_and_factory(args.rcon_port, password, player,
                                                 probe_origin, first_join=attempt == 1)
                check_fatal(secondary_log, secondary_client_log)
                print("IT15 subset: second distinct client joined and shared machine persisted", flush=True)
            print(f"IT16 join {attempt}/2 confirmed, BCCE world probes passed; "
                  f"Create {'present' if create_expected else 'absent'}, "
                  f"AE2 {'present' if ae2_expected else 'absent'} as required", flush=True)
            stable_until = time.monotonic() + args.stable_seconds
            while time.monotonic() < stable_until:
                if client.poll() is not None or server.poll() is not None or (
                    args.two_players and (secondary_client is None or secondary_client.poll() is not None)):
                    raise RuntimeError("client/server exited after successful world join")
                check_fatal(server_log, world_log, client_gradle_log, client_log)
                if args.two_players:
                    check_fatal(secondary_log, secondary_client_log)
                    require_online(args.rcon_port, password, {player, SECOND_PLAYER})
                time.sleep(2)
            if attempt == 1 and args.inflight_pipe:
                # Stage LAST, while the first player still keeps the chunks
                # loaded. save-all and /stop follow immediately, preserving
                # a genuinely unfinished pipe transit across the process boundary.
                prepare_saved_inflight_pipe(args.rcon_port, password, args.target, probe_origin)
                print("IT01: captured one charged item still travelling inside live BCCE pipe", flush=True)
            stop_process(client)
            client = None
            if args.two_players:
                wait_until("first client disconnected, second stayed online", lambda:
                    player not in online_players(rcon_command(args.rcon_port, password, "list"))
                    and SECOND_PLAYER in online_players(rcon_command(args.rcon_port, password, "list")),
                    [server, secondary_client], 60)
                # Shared machine must remain intact while one user is disconnected.
                verify_second_client_factory_alone(args.rcon_port, password, probe_origin)
                stop_process(secondary_client)
                secondary_client = None
                wait_until("second client disconnected", lambda:
                    SECOND_PLAYER not in online_players(rcon_command(args.rcon_port, password, "list")),
                    [server], 60)
            if attempt == 1:
                wait_until("first client disconnect", lambda:
                           f"{player} left the game".lower() in read_text(world_log).lower(), [server], 45)
                save_result = rcon_command(args.rcon_port, password, "save-all flush")
                if "failed" in save_result.lower() or "unknown" in save_result.lower():
                    raise RuntimeError(f"World save failed before restart: {save_result!r}")
                expected_world = server_dir / "bcce-it16-world" / "level.dat"
                if not expected_world.is_file():
                    raise RuntimeError("save-all reported success but level.dat is missing")
                # Stop cleanly through Minecraft before launching another dedicated-server
                # process. This exercises world serialization, mod registries and reload.
                try:
                    rcon_command(args.rcon_port, password, "stop")
                except (ConnectionError, OSError, TimeoutError) as error:
                    # /stop may close RCON before the reply is flushed. Only
                    # accept that race if the Minecraft server really exits.
                    print(f"IT16 RCON closed while stopping server: {error}", flush=True)
                try:
                    server.wait(timeout=60)
                except subprocess.TimeoutExpired:
                    stop_process(server)
                    raise RuntimeError("Minecraft server failed to stop after /stop")
                server = None
                if args.remove_ae2_after_restart:
                    remove_pinned_ae2_after_shutdown(
                        base, mods["ae2"], two_players=args.two_players)
                    results["ae2_removed_between_processes"] = True
                    print("IT14: removed pinned AE2 JAR from all runtime directories after save/stop", flush=True)
                world_log.unlink(missing_ok=True)
                server_log = base / "server-restart-gradle.log"
                server = start_process([str(gradlew), *flags, f":{args.target}:runServer"],
                                       server_log, launch_dir, env)
                wait_until("restarted dedicated server ready", lambda:
                           bool(READY_RE.search(read_text(world_log))), [server], args.restart_timeout)
                check_fatal(server_log, world_log)
                if not expected_world.is_file():
                    raise RuntimeError("Saved world disappeared across a dedicated server restart")
                results["server_restarts"] += 1
                print("IT16 dedicated server restarted and reloaded the saved world", flush=True)
        level_file = server_dir / "bcce-it16-world" / "level.dat"
        if not level_file.exists():
            raise RuntimeError("World join was observed, but level.dat does not exist")
        check_fatal(server_log, world_log, client_gradle_log, client_log)
        results.update(success=True, joined=2, world=str(level_file),
                       charged_cargo_persisted=not args.smoke_only,
                       create_registry="present" if config["present"]["create"] and not args.without_create else "absent",
                       ae2_registry=("removed_after_restart" if args.remove_ae2_after_restart else
                                     "present" if config["present"]["ae2"] and not args.without_ae2 else "absent"),
                       verified_client_mods=["jei", "jade"],
                       tested_content_mods=[name for name in ("ae2", "forestry", "ic2", "create")
                                            if config["present"][name] and not (
                                                (name == "ae2" and args.without_ae2) or
                                                (name == "create" and args.without_create))])
        if args.two_players:
            results["two_player_factory"] = "two distinct clients joined twice; one-client disconnect and factory restart survived"
        print("PASS IT11/IT16: modpack joined with JEI/Jade plus supported optional mods; "
              "world saved/restarted and client rejoined", flush=True)
        return 0
    except Exception as error:
        results["error"] = str(error)
        print("FAIL IT16:", error, file=sys.stderr)
        tail_logs(server_log, world_log, client_gradle_log, client_log)
        if args.two_players:
            tail_logs(secondary_log, secondary_client_log)
        return 1
    finally:
        stop_process(client)
        stop_process(secondary_client)
        stop_process(server)
        stop_process(private_display)
        base.mkdir(parents=True, exist_ok=True)
        (base / "result.json").write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    sys.exit(main())
