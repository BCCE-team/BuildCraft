#!/usr/bin/env python3
"""Gate-specific 1.21.1 -> 1.21.11 parity guard.

This intentionally validates the runtime paths that broke during the 1.21.11 port:
client->server message delivery, statement modifier input, server persistence/echo,
container connection sync, and the core resolver's behavioural parity.
"""
from __future__ import annotations

import importlib.util
from pathlib import Path
import re
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[3]


def load_layout():
    spec = importlib.util.spec_from_file_location("gate_source_layout", ROOT / "scripts/source_layout.py")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def method(source: str, signature: str) -> str:
    start = source.index(signature)
    brace = source.index("{", start)
    level = 1
    i = brace + 1
    while level:
        if source[i] == "{":
            level += 1
        elif source[i] == "}":
            level -= 1
        i += 1
    return source[start:i]


def normalize_port_api(text: str) -> str:
    text = text.replace(".isClientSide()", ".isClientSide")
    text = text.replace("net.minecraft.resources.Identifier", "net.minecraft.resources.ResourceLocation")
    text = re.sub(r"\s+", " ", text).strip()
    return text


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def main() -> int:
    layout = load_layout()
    props = layout.load_properties(ROOT / "builds/modern/targets.properties")
    with tempfile.TemporaryDirectory(prefix="bc-gate-parity-") as tmp:
        tmp = Path(tmp)
        old = tmp / "1.21.1"
        new = tmp / "1.21.11"
        layout.materialize_target("1.21.1-neoforge", old, props)
        layout.materialize_target("1.21.11-neoforge", new, props)

        def src(root: Path, rel: str) -> str:
            return (root / "src/main/java/buildcraft" / rel).read_text()

        # C2S is the essential commit path for gate statements and connection buttons.
        messages = src(new, "lib/net/MessageManager.java")
        require(
            "ClientPacketDistributor.sendToServer(payload(message));" in messages,
            "1.21.11 BuildCraft client->server payload delivery is not wired",
        )

        # Native MouseButtonEvent modifier state must survive the legacy 1.21.1 GUI bridge.
        gui = src(new, "lib/gui/GuiBC8.java")
        require("extends BCContainerScreen<" in gui, "GuiBC8 bypasses the native input boundary")
        bridge = src(new, "lib/compat/minecraft/gui/BCContainerScreen.java")
        click = method(bridge, "public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick)")
        require("BCInputState.pushShift(event.hasShiftDown())" in click and "try (" in click,
                "Native click must scope Shift state even across exceptions")
        state = src(new, "lib/compat/minecraft/gui/BCInputState.java")
        require("SHIFT.set(previous)" in state and "void close()" in state,
                "Input modifier scope must restore the previous state, not clear an outer scope")
        render_compat = src(new, "lib/compat/RenderCompat.java")
        require("return BCInputState.shiftDown();" in render_compat,
                "Legacy statement widgets do not read the scoped modifier state")

        stmt = src(new, "lib/gui/statement/GuiElementStatement.java")
        param = src(new, "lib/gui/statement/GuiElementStatementParam.java")
        require("if (RenderCompat.hasInputShiftDown())" in stmt,
                "Shift-click statement clearing does not match 1.21.1")
        require("new StatementMouseClick(button, RenderCompat.hasInputShiftDown())" in param,
                "Statement parameter click lost its Shift modifier")
        require("Screen.hasShiftDown()" not in stmt + param,
                "Removed Screen modifier helper survived materialization")

        # Statement/action options expose localized names already; the 1.21.11 post-slot tooltip pass must
        # actually draw them. This regressed when source_layout accidentally emptied BuildCraftGui.drawTooltips().
        statement_source = src(new, "lib/gui/statement/GuiElementStatementSource.java")
        statement_variant = src(new, "lib/gui/statement/GuiElementStatementVariant.java")
        buildcraft_gui = src(new, "lib/gui/BuildCraftGui.java")
        require("tooltips.add(new ToolTip(slot.getTooltip()));" in statement_source,
                "Statement source options no longer expose their localized tooltip")
        require("tooltips.add(new ToolTip(slot.getTooltip()));" in statement_variant,
                "Statement variant options no longer expose their localized tooltip")
        draw_tooltips = method(buildcraft_gui, "public void drawTooltips(GuiGraphics guiGraphics)")
        require("GuiUtil.drawVerticallyAppending(mouse, getAllTooltips(), this::drawTooltip, guiGraphics);" in draw_tooltips,
                "1.21.11 BuildCraft tooltip pass is empty, so gate option names never render")

        # Server receives a statement update, persists it, then echoes the authoritative value.
        logic = src(new, "silicon/gate/GateLogic.java")
        read_payload = method(logic, "public void readPayload(")
        require("if (side == BCNetworkSide.SERVER)" in read_payload, "Gate statement update has no server branch")
        require("markGateDirty();" in read_payload, "Gate statement update is not persisted")
        require("sendStatementUpdate(isAction, slot);" in read_payload,
                "Gate statement update is not echoed back to the open GUI")
        require("sendResolveData();" in method(logic, "public void resolveActions()"),
                "Gate active trigger/action state is not synchronised to clients")

        # Connection toggles use the container channel, persist on server, and echo resolver state.
        container = src(new, "silicon/container/ContainerGate.java")
        read_message = method(container, "public void readMessage(")
        require("id == ID_CONNECTION" in read_message, "Gate connection message handler missing")
        require("tile.setChanged();" in read_message and "bcTile.markChunkDirty();" in read_message,
                "Gate connection changes are not persisted")
        require("gate.sendResolveData();" in read_message,
                "Gate connection changes are not sent back to the GUI")
        set_connected = method(container, "public void setConnected(")
        require("sendMessage(ID_CONNECTION" in set_connected,
                "Gate connection button no longer sends a container message")

        # Gate rule evaluation/network semantics must remain literally equivalent after mechanical API renames.
        old_logic = src(old, "silicon/gate/GateLogic.java")
        for signature in (
            "public void readPayload(",
            "public void sendStatementUpdate(",
            "public void sendResolveData()",
            "public void resolveActions()",
            "public void onTick()",
        ):
            require(
                normalize_port_api(method(old_logic, signature)) == normalize_port_api(method(logic, signature)),
                f"Gate runtime behaviour diverged from 1.21.1 in {signature}",
            )

        old_container = src(old, "silicon/container/ContainerGate.java")
        for signature in ("public void readMessage(", "public void writeMessage(", "public void setConnected("):
            require(
                normalize_port_api(method(old_container, signature)) == normalize_port_api(method(container, signature)),
                f"Gate container behaviour diverged from 1.21.1 in {signature}",
            )

        # GUI expressions must stay live rather than being constant-folded snapshots.
        gate_gui = src(new, "silicon/gui/GuiGate.java")
        for key in ("gate.is_connected", "gate.trigger.is_on", "gate.set.is_on", "gate.action.is_on"):
            match = re.search(rf'context\.put_l_b\("{re.escape(key)}".*?\}}\)\.setNeverInline\(\);', gate_gui, re.S)
            require(match is not None, f"Dynamic gate GUI expression {key} is no longer live")

        # Gate copier/persistent NBT paths must remain present after 1.21.11 optional-NBT rewrites.
        plug = src(new, "silicon/plug/PluggableGate.java")
        require('nbt.put("data", logic.writeToNbt());' in plug, "Placed gate no longer writes logic NBT")
        require("logic.readConfigData(stored);" in plug, "Gate Copier paste path missing")
        for key in ('"connections"', '"trigger["', '"action["'):
            require(key in logic, f"Gate NBT key path missing: {key}")

    print("Gate 1.21.11 parity validation: OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
