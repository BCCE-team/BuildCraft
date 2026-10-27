"""Contract checks and a real javac exercise of the client-only recipe sync snapshot."""
from __future__ import annotations
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


class MachineRecipeSyncTest(unittest.TestCase):
    def test_all_target_network_and_disconnect(self):
        proxies = [
            "source-platforms/forge",
            "source-downports/1.21.X/1.21.1/neoforge",
            "source-family-platforms/1.21.X/neoforge",
            "source-families/26.X",
        ]
        for folder in proxies:
            with self.subTest(folder=folder):
                text = (ROOT / folder / "src/main/java/buildcraft/lib/BCLibProxy.java").read_text()
                self.assertIn("MessageMachineRecipes::toBytes", text)
                self.assertIn("MessageMachineRecipes.HANDLER", text)
        events = [
            "version-src/1.19.2-forge", "version-src/1.20.1-forge",
            "source-downports/1.21.X/1.21.1/neoforge",
            "source-family-platforms/1.21.X/neoforge",
            "source-downports/26.X/26.1.2/neoforge", "source-families/26.X",
        ]
        for folder in events:
            with self.subTest(folder=folder):
                text = (ROOT / folder / "src/main/java/buildcraft/lib/BCLibEventDist.java").read_text()
                self.assertIn("MessageMachineRecipes.sendAll(playerMP)", text)
                self.assertIn("ClientMachineRecipeCatalog.clear()", text)
        for folder in [
            "source-family-platforms/1.21.X/neoforge", "source-family-platforms/26.X/neoforge",
            "source-downports/26.X/26.1.2/neoforge", "source-downports/26.X/26.2/neoforge",
            "source-downports/1.21.X/1.21.1/neoforge", "version-src/1.19.2-forge",
            "version-src/1.20.1-forge",
        ]:
            with self.subTest(folder=folder):
                text = (ROOT / folder / "src/main/java/buildcraft/compat/jei/BuildCraftJeiPlugin.java").read_text()
                self.assertIn("ClientMachineRecipeCatalog.visibleRecipes(DistillationRecipeDefinition.class)", text)
                self.assertIn("ClientMachineRecipeCatalog.visibleRecipes(HeatExchangeRecipeDefinition.class)", text)
                self.assertIn('ProgrammingRecipeAccess.options(true)', text)
                self.assertIn('ClientMachineRecipeCatalog.visibleRecipes(StaticIntegrationRecipe.class)', text)

    def test_no_duplicate_reload_publication(self):
        """Regression: accidental repeated insertion must not break compilation or flood clients."""
        code = (ROOT / "source-shared/src/main/java/buildcraft/lib/recipe/MachineRecipeDatapackReloadListener.java").read_text()
        self.assertEqual(code.count("private static volatile List<buildcraft.lib.net.MessageMachineRecipes.Recipe> syncedRecipes"), 1)
        self.assertEqual(code.count("private static volatile boolean hasSyncedRecipeSnapshot"), 1)
        self.assertEqual(code.count("List<buildcraft.lib.net.MessageMachineRecipes.Recipe> outgoing = new ArrayList<>()"), 1)
        self.assertEqual(code.count("        buildcraft.lib.net.MessageMachineRecipes.broadcast();"), 4)

    def test_wire_chunks_and_roundtrips(self):
        """Compile the actual transport class against a minimal in-memory wire buffer."""
        source = ROOT / "source-shared/src/main/java/buildcraft/lib/net/MessageMachineRecipes.java"
        stubs = {
            "net/minecraft/resources/ResourceLocation.java": '''
                package net.minecraft.resources;
                public record ResourceLocation(String id) {
                  public static ResourceLocation tryParse(String s) {return new ResourceLocation(s);}
                  public String toString() {return id;}
                }
            ''',
            "net/minecraft/server/level/ServerPlayer.java": '''
                package net.minecraft.server.level;
                public class ServerPlayer {
                  public Name getName() {return new Name();}
                  public record Name() {public String getString() {return "tester";}}
                }
            ''',
            "org/slf4j/Logger.java": '''
                package org.slf4j;
                public interface Logger {default void warn(String f,Object... args) {}}
            ''',
            "org/slf4j/LoggerFactory.java": '''
                package org.slf4j;
                public class LoggerFactory {public static Logger getLogger(String s) {return new Logger(){};}}
            ''',
            "io/netty/handler/codec/DecoderException.java": '''
                package io.netty.handler.codec;
                public class DecoderException extends RuntimeException {
                  public DecoderException(String s) {super(s);}
                }
            ''',
            "net/minecraft/network/FriendlyByteBuf.java": '''
                package net.minecraft.network;
                import java.io.*;
                import java.nio.charset.StandardCharsets;
                public class FriendlyByteBuf {
                  private final ByteArrayOutputStream bytes;
                  private final DataOutputStream out;
                  private final DataInputStream in;
                  private int position;
                  public FriendlyByteBuf() { bytes=new ByteArrayOutputStream(); out=new DataOutputStream(bytes); in=null; }
                  public FriendlyByteBuf(byte[] data) { bytes=null; out=null; in=new DataInputStream(new ByteArrayInputStream(data)); }
                  public byte[] bytes() {return bytes.toByteArray();}
                  public int writerIndex() {return bytes.size();}
                  public int readerIndex() {return position;}
                  public void writeVarInt(int n) {try {out.writeInt(n);} catch(IOException e){throw new RuntimeException(e);}}
                  public int readVarInt() {try {position+=4;return in.readInt();} catch(IOException e){throw new RuntimeException(e);}}
                  public void writeLong(long n) {try {out.writeLong(n);}catch(IOException e){throw new RuntimeException(e);}}
                  public long readLong() {try {position+=8;return in.readLong();}catch(IOException e){throw new RuntimeException(e);}}
                  public void writeUtf(String s,int max) {
                    if(s.length()>max) throw new IllegalArgumentException("too long");
                    byte[] utf=s.getBytes(StandardCharsets.UTF_8);
                    try {out.writeInt(utf.length);out.write(utf);}catch(IOException e){throw new RuntimeException(e);}
                  }
                  public String readUtf(int max) {
                    try {int len=in.readInt(); if(len<0||len>max*3) throw new IllegalArgumentException();
                      byte[] utf=in.readNBytes(len); position+=4+len;
                      String s=new String(utf,StandardCharsets.UTF_8);
                      if(s.length()>max) throw new IllegalArgumentException(); return s;
                    }catch(IOException e){throw new RuntimeException(e);}
                  }
                }
            ''',
            "buildcraft/lib/recipe/ClientMachineRecipeCatalog.java": '''
                package buildcraft.lib.recipe;
                public class ClientMachineRecipeCatalog {
                  public static void accept(buildcraft.lib.net.MessageMachineRecipes msg) {}
                }
            ''',
            "buildcraft/lib/recipe/MachineRecipeDatapackReloadListener.java": '''
                package buildcraft.lib.recipe;
                import java.util.*;
                import net.minecraft.resources.ResourceLocation;
                import buildcraft.lib.net.MessageMachineRecipes;
                public class MachineRecipeDatapackReloadListener {
                  public static boolean hasSyncedRecipeSnapshot() {return true;}
                  public static List<MessageMachineRecipes.Recipe> syncedMachineRecipes() {
                    List<MessageMachineRecipes.Recipe> rows=new ArrayList<>();
                    for(int i=0;i<150;i++) rows.add(new MessageMachineRecipes.Recipe("distiller",
                      ResourceLocation.tryParse("test:distiller/"+i),"sample"));
                    return rows;
                  }
                }
            ''',
            "buildcraft/lib/recipe/RecipeBlacklist.java": '''
                package buildcraft.lib.recipe;
                import java.util.*;
                import net.minecraft.resources.ResourceLocation;
                public class RecipeBlacklist {
                  public record Snapshot(Map<String,Set<ResourceLocation>> denied) {}
                  public static Snapshot snapshot() {
                    return new Snapshot(Map.of("distiller",Set.of(ResourceLocation.tryParse("test:distiller/1"))));
                  }
                  public static long generation() {return 7;}
                }
            ''',
            "buildcraft/lib/net/BCNetworkSide.java": '''
                package buildcraft.lib.net; public enum BCNetworkSide {CLIENT, SERVER}
            ''',
            "buildcraft/lib/net/BCPacketContext.java": '''
                package buildcraft.lib.net;
                public interface BCPacketContext {
                  BCNetworkSide side();
                  void enqueueWork(Runnable work);
                  void setPacketHandled(boolean handled);
                }
            ''',
            "buildcraft/lib/net/MessageManager.java": '''
                package buildcraft.lib.net;
                import java.util.*;
                import net.minecraft.server.level.ServerPlayer;
                public class MessageManager {
                  public static final List<MessageMachineRecipes> sent = new ArrayList<>();
                  public static boolean authoritativeServerThread = true;
                  public static boolean isAuthoritativeServerThread() {return authoritativeServerThread;}
                  public static void sendTo(Object msg, ServerPlayer player) {sent.add((MessageMachineRecipes) msg);}
                  public static void sendToAll(Object msg) {sent.add((MessageMachineRecipes) msg);}
                }
            ''',
            "buildcraft/lib/net/MessageSyncExercise.java": '''
                package buildcraft.lib.net;
                import net.minecraft.server.level.ServerPlayer;
                import net.minecraft.network.FriendlyByteBuf;
                public class MessageSyncExercise {
                  public static void main(String[] args) {
                    MessageMachineRecipes.sendAll(new ServerPlayer());
                    if(MessageManager.sent.size()<3) throw new AssertionError("chunking failed");
                    int count=0;
                    for (int i=0;i<MessageManager.sent.size();i++) {
                      var msg=MessageManager.sent.get(i);
                      var buf=new FriendlyByteBuf();
                      MessageMachineRecipes.toBytes(msg,buf);
                      if(buf.writerIndex()>70*1024) throw new AssertionError("oversize packet");
                      var restored=new MessageMachineRecipes(new FriendlyByteBuf(buf.bytes()));
                      if(restored.partIndex()!=i || restored.partCount()!=MessageManager.sent.size())
                        throw new AssertionError("chunk ordering");
                      count+=restored.recipes().size();
                    }
                    if(count!=150) throw new AssertionError("dropped recipe entries: "+count);
                    int before = MessageManager.sent.size();
                    MessageManager.authoritativeServerThread = false;
                    MessageMachineRecipes.broadcast();
                    if (MessageManager.sent.size() != before) throw new AssertionError("client thread must not broadcast");
                    MessageManager.authoritativeServerThread = true;
                    MessageMachineRecipes.broadcast();
                    if (MessageManager.sent.size() <= before) throw new AssertionError("server thread did not broadcast");
                    System.out.println("message machine recipe codec PASS");
                  }
                }
            ''',
        }
        with tempfile.TemporaryDirectory(prefix="bcce-machine-recipe-wire-") as directory:
            base = Path(directory)
            inputs = [source]
            for path, java in stubs.items():
                target = base / path
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(textwrap.dedent(java), encoding="utf-8")
                inputs.append(target)
            classes = base / "out"
            compiled = subprocess.run(["javac", "--release", "17", "-d", str(classes), *map(str, inputs)],
                                      capture_output=True, text=True, timeout=30)
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            run = subprocess.run(["java", "-cp", str(classes), "buildcraft.lib.net.MessageSyncExercise"],
                                 capture_output=True, text=True, timeout=10)
            self.assertEqual(run.returncode, 0, run.stderr)
            self.assertIn("message machine recipe codec PASS", run.stdout)

    def test_client_snapshot_is_atomic(self):
        """Compile the production client state holder and exercise chunk ordering and ban inheritance."""
        source = ROOT / "source-shared/src/main/java/buildcraft/lib/recipe/ClientMachineRecipeCatalog.java"
        stubs = {
            "net/minecraft/resources/ResourceLocation.java": '''
                package net.minecraft.resources;
                public record ResourceLocation(String id) {
                    public static ResourceLocation tryParse(String id) {return new ResourceLocation(id);}
                }
            ''',
            "org/slf4j/Logger.java": '''
                package org.slf4j;
                public interface Logger { default void info(String format, Object... args) {}
                    default void warn(String format, Object... args) {} }
            ''',
            "org/slf4j/LoggerFactory.java": '''
                package org.slf4j;
                public class LoggerFactory { public static Logger getLogger(String name) {return new Logger(){};} }
            ''',
            "com/google/gson/JsonParser.java": '''
                package com.google.gson;
                public class JsonParser {
                  public static JsonParser parseString(String data) {return new JsonParser(data);}
                  private final String data;
                  private JsonParser(String data) {this.data=data;}
                  public JsonParser getAsJsonObject() {return this;}
                  public String toString() {return data;}
                }
            ''',
            "buildcraft/api/v2/recipe/RecipeDefinition.java": '''
                package buildcraft.api.v2.recipe;
                public interface RecipeDefinition {}
            ''',
            "buildcraft/api/v2/recipe/RecipeMatch.java": '''
                package buildcraft.api.v2.recipe;
                public record RecipeMatch<T>(T recipe) {}
            ''',
            "buildcraft/api/v2/recipe/MachineRecipeService.java": '''
                package buildcraft.api.v2.recipe;
                import java.util.List;
                public class MachineRecipeService {
                  public <T extends RecipeDefinition> List<RecipeMatch<T>> recipes(Class<T> type) {return List.of();}
                  public Snapshot snapshot() {return new Snapshot();}
                  public static class Snapshot {
                    public java.util.Optional<Resolved> resolved(net.minecraft.resources.ResourceLocation id) {
                      return java.util.Optional.empty();
                    }
                  }
                  public record Resolved(RecipeDefinition value) {}
                }
            ''',
            "buildcraft/api/v2/BuildCraftApi.java": '''
                package buildcraft.api.v2;
                public class BuildCraftApi {
                  public static buildcraft.api.v2.recipe.MachineRecipeService service(Object id) {
                    return new buildcraft.api.v2.recipe.MachineRecipeService();}
                }
            ''',
            "buildcraft/api/v2/BuildCraftServices.java": '''
                package buildcraft.api.v2;
                public class BuildCraftServices {public static final Object MACHINE_RECIPES = new Object();}
            ''',
            "buildcraft/lib/recipe/RecipeBlacklist.java": '''
                package buildcraft.lib.recipe;
                import net.minecraft.resources.ResourceLocation;
                public class RecipeBlacklist {
                  public static boolean isDenied(String machine, ResourceLocation id) {return false;}
                }
            ''',
            "buildcraft/lib/recipe/MachineRecipeDatapackReloadListener.java": '''
                package buildcraft.lib.recipe;
                public class MachineRecipeDatapackReloadListener {
                  public static buildcraft.api.v2.recipe.RecipeDefinition decode(
                    String machine, com.google.gson.JsonParser json) {
                    if (json.toString().equals("BAD")) throw new IllegalArgumentException("bad json");
                    return new TestDefinition(json.toString());
                  }
                }
            ''',
            "buildcraft/lib/recipe/TestDefinition.java": '''
                package buildcraft.lib.recipe;
                public record TestDefinition(String value) implements buildcraft.api.v2.recipe.RecipeDefinition {}
            ''',
            "buildcraft/lib/net/MessageMachineRecipes.java": '''
                package buildcraft.lib.net;
                import java.util.List;
                import net.minecraft.resources.ResourceLocation;
                public record MessageMachineRecipes(long generation,int partIndex,int partCount,
                    List<Recipe> recipes, List<Ban> bans) {
                  public static final int MAX_TOTAL_ENTRIES=8192;
                  public static final int MAX_TOTAL_BYTES=8*1024*1024;
                  public record Recipe(String machine,ResourceLocation id,String json) {}
                  public record Ban(String machine,ResourceLocation id) {}
                }
            ''',
            "buildcraft/lib/recipe/ClientCatalogExercise.java": '''
                package buildcraft.lib.recipe;
                import java.util.*;
                import net.minecraft.resources.ResourceLocation;
                import buildcraft.lib.net.MessageMachineRecipes;
                public class ClientCatalogExercise {
                  static ResourceLocation id(String s) {return ResourceLocation.tryParse(s);}
                  static MessageMachineRecipes part(long gen,int index,int total,String json,boolean deny) {
                    return new MessageMachineRecipes(gen,index,total,
                      List.of(new MessageMachineRecipes.Recipe("distiller",id("pack:distiller/steam"),json)),
                      deny ? List.of(new MessageMachineRecipes.Ban("distiller",id("pack:distiller/steam"))) : List.of());
                  }
                  static void require(boolean pass,String message) {if(!pass) throw new AssertionError(message);}
                  public static void main(String[] args) {
                    ClientMachineRecipeCatalog.clear();
                    require(!ClientMachineRecipeCatalog.snapshot().authoritative(),"no pre-login authority");
                    ClientMachineRecipeCatalog.accept(part(5,1,2,"second",true));
                    require(!ClientMachineRecipeCatalog.snapshot().authoritative(),"partial must not publish");
                    ClientMachineRecipeCatalog.accept(part(5,0,2,"first",false));
                    require(ClientMachineRecipeCatalog.snapshot().recipes().size()==2,"two chunks required");
                    require(ClientMachineRecipeCatalog.isDenied("distiller",id("pack:distiller/steam")),"deny union");
                    ClientMachineRecipeCatalog.accept(part(4,0,1,"stale",false));
                    require(ClientMachineRecipeCatalog.snapshot().generation()==5,"stale generation replaced published");
                    ClientMachineRecipeCatalog.accept(part(6,0,1,"BAD",false));
                    require(ClientMachineRecipeCatalog.snapshot().generation()==5,"invalid snapshot was applied");
                    ClientMachineRecipeCatalog.accept(part(7,0,1,"new",false));
                    require(!ClientMachineRecipeCatalog.isDenied("distiller",id("pack:distiller/steam")),"removed deny persists");
                    ClientMachineRecipeCatalog.clear();
                    require(!ClientMachineRecipeCatalog.snapshot().authoritative(),"logout leak");
                    System.out.println("client recipe sync PASS");
                  }
                }
            ''',
        }
        with tempfile.TemporaryDirectory(prefix="bcce-client-recipe-sync-") as directory:
            base = Path(directory)
            inputs = [source]
            for path, java in stubs.items():
                target = base / path
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(textwrap.dedent(java), encoding="utf-8")
                inputs.append(target)
            classes = base / "out"
            javac = subprocess.run(["javac", "--release", "17", "-d", str(classes), *map(str, inputs)],
                                   capture_output=True, text=True, timeout=30)
            self.assertEqual(javac.returncode, 0, javac.stderr)
            run = subprocess.run(["java", "-cp", str(classes), "buildcraft.lib.recipe.ClientCatalogExercise"],
                                 capture_output=True, text=True, timeout=10)
            self.assertEqual(run.returncode, 0, run.stderr)
            self.assertIn("client recipe sync PASS", run.stdout)


if __name__ == "__main__":
    unittest.main()
