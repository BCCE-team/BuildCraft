#!/usr/bin/env python3
"""Compile the real loader-neutral packet context and loader adapters against tiny offline doubles."""
from __future__ import annotations

from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]


def _write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def run() -> str:
    actual = [
        ROOT / "source-shared/src/main/java/buildcraft/lib/net/BCNetworkSide.java",
        ROOT / "source-shared/src/main/java/buildcraft/lib/net/BCPacketContext.java",
        ROOT / "source-family-platforms/legacy/forge/src/main/java/buildcraft/lib/net/ForgePacketContext.java",
        ROOT / "source-family-platforms/modern/neoforge/src/main/java/buildcraft/lib/net/NeoForgePacketContext.java",
    ]
    with tempfile.TemporaryDirectory(prefix="bc-loader-boundary-") as td:
        base = Path(td)
        src = base / "src"
        out = base / "out"
        for path in actual:
            rel = path.relative_to(path.parents[6] if "source-family-platforms" in path.parts else path.parents[5])
            # Preserve the package path by copying from src/main/java onward.
            parts = path.parts
            idx = parts.index("java")
            target = src.joinpath(*parts[idx + 1 :])
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, target)

        _write(src / "javax/annotation/Nullable.java", """
package javax.annotation;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE_USE, ElementType.METHOD, ElementType.PARAMETER, ElementType.FIELD})
public @interface Nullable {}
""")
        _write(src / "net/minecraft/world/entity/player/Player.java", """
package net.minecraft.world.entity.player;
public class Player {}
""")
        _write(src / "net/minecraft/server/level/ServerPlayer.java", """
package net.minecraft.server.level;
public class ServerPlayer extends net.minecraft.world.entity.player.Player {}
""")
        _write(src / "net/minecraft/network/protocol/PacketFlow.java", """
package net.minecraft.network.protocol;
public enum PacketFlow { CLIENTBOUND, SERVERBOUND }
""")
        _write(src / "net/minecraftforge/fml/LogicalSide.java", """
package net.minecraftforge.fml;
public enum LogicalSide { CLIENT, SERVER }
""")
        _write(src / "net/minecraftforge/network/NetworkDirection.java", """
package net.minecraftforge.network;
import net.minecraftforge.fml.LogicalSide;
public final class NetworkDirection {
    private final LogicalSide receptionSide;
    public NetworkDirection(LogicalSide receptionSide) { this.receptionSide = receptionSide; }
    public LogicalSide getReceptionSide() { return receptionSide; }
}
""")
        _write(src / "net/minecraftforge/network/NetworkEvent.java", """
package net.minecraftforge.network;
import net.minecraft.server.level.ServerPlayer;
public final class NetworkEvent {
    public static final class Context {
        public NetworkDirection direction;
        public ServerPlayer sender;
        public boolean enqueued;
        public boolean handled;
        public Context(NetworkDirection direction, ServerPlayer sender) { this.direction = direction; this.sender = sender; }
        public NetworkDirection getDirection() { return direction; }
        public ServerPlayer getSender() { return sender; }
        public void enqueueWork(Runnable task) { enqueued = true; task.run(); }
        public void setPacketHandled(boolean value) { handled = value; }
    }
}
""")
        _write(src / "net/neoforged/neoforge/network/handling/IPayloadContext.java", """
package net.neoforged.neoforge.network.handling;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.world.entity.player.Player;
public interface IPayloadContext {
    PacketFlow flow();
    Player player();
    void enqueueWork(Runnable task);
}
""")
        _write(src / "test/Probe.java", """
package test;
import buildcraft.lib.net.*;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.network.*;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public final class Probe {
    static int assertions;
    static void check(boolean ok) { assertions++; if (!ok) throw new AssertionError("assertion " + assertions); }
    public static void main(String[] args) {
        ServerPlayer server = new ServerPlayer();
        NetworkEvent.Context forgeClientRaw = new NetworkEvent.Context(new NetworkDirection(LogicalSide.CLIENT), server);
        ForgePacketContext forgeClient = new ForgePacketContext(forgeClientRaw);
        check(forgeClient.side() == BCNetworkSide.CLIENT);
        check(forgeClient.player() == server);
        check(forgeClient.getSender() == server);
        forgeClient.enqueueWork(() -> assertions++);
        check(forgeClientRaw.enqueued);
        forgeClient.setPacketHandled(true);
        check(forgeClientRaw.handled);

        NetworkEvent.Context forgeServerRaw = new NetworkEvent.Context(new NetworkDirection(LogicalSide.SERVER), null);
        ForgePacketContext forgeServer = new ForgePacketContext(forgeServerRaw);
        check(forgeServer.side() == BCNetworkSide.SERVER);
        check(forgeServer.player() == null);
        check(forgeServer.getSender() == null);

        final boolean[] neoRan = {false};
        IPayloadContext neoRaw = new IPayloadContext() {
            public PacketFlow flow() { return PacketFlow.SERVERBOUND; }
            public Player player() { return server; }
            public void enqueueWork(Runnable task) { neoRan[0] = true; task.run(); }
        };
        NeoForgePacketContext neo = new NeoForgePacketContext(neoRaw);
        check(neo.side() == BCNetworkSide.SERVER);
        check(neo.player() == server);
        check(neo.getSender() == server);
        neo.enqueueWork(() -> assertions++);
        check(neoRan[0]);
        neo.setPacketHandled(true);

        IPayloadContext neoClientRaw = new IPayloadContext() {
            public PacketFlow flow() { return PacketFlow.CLIENTBOUND; }
            public Player player() { return new Player(); }
            public void enqueueWork(Runnable task) { task.run(); }
        };
        NeoForgePacketContext neoClient = new NeoForgePacketContext(neoClientRaw);
        check(neoClient.side() == BCNetworkSide.CLIENT);
        check(neoClient.getSender() == null);
        check(BCNetworkSide.CLIENT.isClient());
        check(!BCNetworkSide.CLIENT.isServer());
        check(BCNetworkSide.SERVER.isServer());
        check(!BCNetworkSide.SERVER.isClient());
        System.out.println("Loader packet boundary probes: " + assertions + " assertions");
    }
}
""")

        java = sorted(str(p) for p in src.rglob("*.java"))
        compile_result = subprocess.run(
            ["javac", "--release", "21", "-d", str(out), *java],
            text=True, capture_output=True, timeout=30,
        )
        if compile_result.returncode:
            raise AssertionError(compile_result.stdout + compile_result.stderr)
        run_result = subprocess.run(
            ["java", "-cp", str(out), "test.Probe"],
            text=True, capture_output=True, timeout=30,
        )
        if run_result.returncode:
            raise AssertionError(run_result.stdout + run_result.stderr)
        return run_result.stdout.strip()
