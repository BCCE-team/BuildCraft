#!/usr/bin/env python3
"""Compiles the real BCCE multi-version guide sync and checks per-entry fault isolation.

No Minecraft jars required; this uses a functional JVM-only framing/codec fixture.
"""
from __future__ import annotations
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
BASE = {
 '1.21.11':'source-families/1.21.X/src/main/java/buildcraft/lib/net/MessageGuideRecipeDisplays.java',
 '26.3':'source-families/26.X/src/main/java/buildcraft/lib/net/MessageGuideRecipeDisplays.java',
 '26.2':'source-downports/26.X/26.2/neoforge/src/main/java/buildcraft/lib/net/MessageGuideRecipeDisplays.java',
 '26.1.2':'source-downports/26.X/26.1.2/neoforge/src/main/java/buildcraft/lib/net/MessageGuideRecipeDisplays.java'
}

from textwrap import dedent
STUBS = {
'io/netty/handler/codec/DecoderException.java': '''package io.netty.handler.codec;
    public class DecoderException extends RuntimeException { public DecoderException(String text){super(text);} }''',
'io/netty/buffer/Unpooled.java': '''package io.netty.buffer;
    public final class Unpooled {
        public record Memory(byte[] initial) {}
        public static Memory buffer(){return new Memory(new byte[0]);}
        public static Memory wrappedBuffer(byte[] bytes){return new Memory(bytes);}
    }''',
'net/minecraft/resources/Identifier.java': '''package net.minecraft.resources;
    public record Identifier(String value) { public String toString(){return value;} }''',
'net/minecraft/network/RegistryFriendlyByteBuf.java': '''package net.minecraft.network;
    import io.netty.buffer.Unpooled;
    import java.nio.charset.StandardCharsets;
    import net.minecraft.resources.Identifier;
    import java.util.Arrays;
    public class RegistryFriendlyByteBuf {
        private byte[] bytes; private int reader; private int writer;
        private final Object registry; private boolean released;
        public static int releases;
        public RegistryFriendlyByteBuf(Object mem,Object registry) {
            this.registry=registry;
            byte[] initial=((Unpooled.Memory)mem).initial();
            bytes=Arrays.copyOf(initial,Math.max(128,initial.length));
            writer=initial.length;
        }
        public Object registryAccess(){return registry;}
        public RegistryFriendlyByteBuf clear(){reader=0;writer=0;return this;}
        private void ensure(int n){if(writer+n>bytes.length)bytes=Arrays.copyOf(bytes,Math.max(writer+n,bytes.length*2));}
        public int writerIndex(){return writer;}
        public int readerIndex(){return reader;}
        public int readableBytes(){return writer-reader;}
        public void release(){if(released)throw new AssertionError("double release");released=true;releases++;}
        public void writeVarInt(int n){while((n&~127)!=0){writeByte((n&127)|128);n>>>=7;}writeByte(n);}
        public int readVarInt(){int result=0;int shift=0;while(shift<35){int b=readByte()&255; result|=(b&127)<<shift; if((b&128)==0)return result; shift+=7;}throw new IllegalArgumentException("invalid varint");}
        public void writeByte(int b){ensure(1);bytes[writer++]=(byte)b;}
        public byte readByte(){if(reader>=writer)throw new IllegalArgumentException("short packet");return bytes[reader++];}
        public void writeBytes(byte[] payload){ensure(payload.length);System.arraycopy(payload,0,bytes,writer,payload.length);writer+=payload.length;}
        public void readBytes(byte[] payload){if(payload.length>readableBytes())throw new IllegalArgumentException("short packet");System.arraycopy(bytes,reader,payload,0,payload.length);reader+=payload.length;}
        public void getBytes(int index,byte[] output){if(index+output.length>writer)throw new IllegalArgumentException();System.arraycopy(bytes,index,output,0,output.length);}
        public void skipBytes(int length){if(length>readableBytes())throw new IllegalArgumentException();reader+=length;}
        public void writeIdentifier(Identifier id){byte[] arr=id.value().getBytes(StandardCharsets.UTF_8);writeVarInt(arr.length);writeBytes(arr);}
        public Identifier readIdentifier(){int n=readVarInt();if(n<0||n>2000)throw new IllegalArgumentException("bad identifier length");byte[] arr=new byte[n];readBytes(arr);return new Identifier(new String(arr,StandardCharsets.UTF_8));}
        public byte[] snapshot(){return Arrays.copyOf(bytes,writer);}
    }''',
'net/minecraft/world/item/crafting/display/RecipeDisplayEntry.java': '''package net.minecraft.world.item.crafting.display;
    import net.minecraft.network.RegistryFriendlyByteBuf;
    import net.minecraft.resources.Identifier;
    public record RecipeDisplayEntry(String type,int encodedSize) {
        public static final Codec STREAM_CODEC=new Codec();
        public static class Codec {
            public void encode(RegistryFriendlyByteBuf out,RecipeDisplayEntry e){
                if(e.type().equals("ae2:unregistered"))throw new IllegalArgumentException("id missing in recipe_display registry");
                if(e.type().equals("mod:brokenlink"))throw new NoClassDefFoundError("MissingRecipeAddon");
                out.writeIdentifier(new Identifier(e.type()));
                out.writeVarInt(e.encodedSize());
                out.writeBytes(new byte[e.encodedSize()]);
            }
            public RecipeDisplayEntry decode(RegistryFriendlyByteBuf in){
                String kind=in.readIdentifier().value();
                if(kind.equals("client:unknown"))throw new IllegalArgumentException("unknown client recipe_display type");
                int length=in.readVarInt();if(length<0||length>150000)throw new IllegalArgumentException("bad length");
                in.skipBytes(length);
                return new RecipeDisplayEntry(kind,length);
            }
        }
    }''',
'net/minecraft/world/item/crafting/RecipeType.java': '''package net.minecraft.world.item.crafting;
    public class RecipeType<T> {
        public static final RecipeType<?> CRAFTING=new RecipeType<>();
        public static final RecipeType<?> OTHER=new RecipeType<>();
    }''',
'net/minecraft/world/item/crafting/Recipe.java': '''package net.minecraft.world.item.crafting;
    public class Recipe {
        private RecipeType<?> type;
        public Recipe(RecipeType<?> type){this.type=type;}
        public RecipeType<?> getType(){return type;}
    }''',
'net/minecraft/world/item/crafting/RecipeHolder.java': '''package net.minecraft.world.item.crafting;
    import net.minecraft.resources.Identifier;
    public class RecipeHolder<T extends Recipe> {
        public record Key(Identifier identifier) {}
        private Key id;private T value;
        public RecipeHolder(Key id,T value){this.id=id;this.value=value;}
        public Key id(){return id;}public T value(){return value;}
    }''',
'net/minecraft/world/item/crafting/RecipeManager.java': '''package net.minecraft.world.item.crafting;
    import java.util.*; import java.util.function.Consumer;
    import net.minecraft.resources.Identifier;
    import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
    public class RecipeManager {
        private final Map<RecipeHolder.Key,RecipeHolder<?>> recipes=new LinkedHashMap<>();
        private final Map<RecipeHolder.Key,List<RecipeDisplayEntry>> displays=new LinkedHashMap<>();
        private boolean brokenCatalog;
        public void setBrokenCatalog(boolean broken) { brokenCatalog=broken; }
        public void add(String id,RecipeType<?> type,RecipeDisplayEntry... entries){
            RecipeHolder.Key k=new RecipeHolder.Key(new Identifier(id));recipes.put(k,new RecipeHolder<>(k,new Recipe(type)));
            displays.put(k,List.of(entries));
        }
        public Map<RecipeHolder.Key,RecipeHolder<?>> recipeMap(){if(brokenCatalog)throw new IllegalStateException("broken recipe index"); return recipes;}
        public Collection<RecipeHolder<?>> getRecipes(){if(brokenCatalog)throw new IllegalStateException("broken recipe index");return recipes.values();}
        public void listDisplaysForRecipe(RecipeHolder.Key id,Consumer<RecipeDisplayEntry> out){
            for(var d:displays.getOrDefault(id,List.of()))out.accept(d);
            if(id.identifier().value().contains("throwsEnumeration"))throw new IllegalStateException("mod recipe enumerator crashed");
        }
    }''',
'net/minecraft/server/MinecraftServer.java': '''package net.minecraft.server;
    import net.minecraft.world.item.crafting.RecipeManager;
    public class MinecraftServer {private RecipeManager manager;
        public MinecraftServer(RecipeManager m){manager=m;}
        public RecipeManager getRecipeManager(){return manager;}
    }''',
'net/minecraft/server/level/ServerLevel.java': '''package net.minecraft.server.level;
    import net.minecraft.server.MinecraftServer;
    public class ServerLevel {private MinecraftServer server;
        public ServerLevel(MinecraftServer s){server=s;}public MinecraftServer getServer(){return server;}}
    ''',
'net/minecraft/server/level/ServerPlayer.java': '''package net.minecraft.server.level;
    public class ServerPlayer {private ServerLevel level;
        public ServerPlayer(ServerLevel l){level=l;}public ServerLevel level(){return level;}
        public Object registryAccess(){return new Object();}
    }''',
'buildcraft/silicon/BCSiliconRecipes.java': '''package buildcraft.silicon;
    import net.minecraft.world.item.crafting.RecipeType;
    public class BCSiliconRecipes {private static final RecipeType<?> TYPE=new RecipeType<>();
        public static class Entry {public RecipeType<?> get(){return TYPE;}}
        public static final Entry ASSEMBLY_TYPE=new Entry();
    }''',
'buildcraft/lib/internal/debug/BCLog.java': '''package buildcraft.lib.internal.debug;
    import java.util.*;
    public class BCLog {public static final Logger logger=new Logger();
        public static class Logger {
            public static final List<String> warnings=new ArrayList<>();
            public void warn(String msg,Object... args){warnings.add(msg+" "+Arrays.toString(args));}
        }
    }''',
'buildcraft/lib/net/GuideRecipeDisplayCache.java': '''package buildcraft.lib.net;
    import java.util.*;
    import net.minecraft.resources.Identifier;
    import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
    public class GuideRecipeDisplayCache {
        public record Entry(Identifier recipeId,RecipeDisplayEntry display){}
        public static final List<Entry> received=new ArrayList<>();
        public static void appendChunk(List<Entry> entries,int index,int count){received.addAll(entries);}
    }''',
'buildcraft/lib/net/BCNetworkSide.java': '''package buildcraft.lib.net;
    public enum BCNetworkSide { SERVER,CLIENT }''',
'buildcraft/lib/net/BCPacketContext.java': '''package buildcraft.lib.net;
    public class BCPacketContext {
        public BCNetworkSide side(){return BCNetworkSide.CLIENT;}
        public void enqueueWork(Runnable r){r.run();}
    }''',
'buildcraft/lib/net/MessageManager.java': '''package buildcraft.lib.net;
    import java.util.*; import net.minecraft.server.level.ServerPlayer;
    public class MessageManager {
        public static final List<MessageGuideRecipeDisplays> sent=new ArrayList<>();
        public static void sendTo(MessageGuideRecipeDisplays msg,ServerPlayer p){sent.add(msg);}
    }''',
 'test/GuideSyncRuntime.java': '''package test;
    import java.lang.reflect.*; import java.util.*;
    import buildcraft.lib.net.*; import net.minecraft.server.*;
    import net.minecraft.server.level.*; import net.minecraft.network.*;
    import net.minecraft.world.item.crafting.*;
    import net.minecraft.world.item.crafting.display.*;
    import io.netty.buffer.Unpooled;
    public class GuideSyncRuntime {
        private static void ok(boolean value,String text){if(!value)throw new AssertionError(text);}
        private static ServerPlayer player(RecipeManager manager){return new ServerPlayer(new ServerLevel(new MinecraftServer(manager)));}
        private static RecipeDisplayEntry e(String type,int len){return new RecipeDisplayEntry(type,len);}
        private static void add(RecipeManager m,String id,String type,int len){m.add(id,RecipeType.CRAFTING,e(type,len));}
        @SuppressWarnings("unchecked") private static List<GuideRecipeDisplayCache.Entry> contents(MessageGuideRecipeDisplays msg)throws Exception {
            Field field=MessageGuideRecipeDisplays.class.getDeclaredField("entries");field.setAccessible(true);
            return (List<GuideRecipeDisplayCache.Entry>)field.get(msg);
        }
        private static List<String> names(MessageGuideRecipeDisplays msg)throws Exception {
            List<String> ids=new ArrayList<>();for(var entry:contents(msg))ids.add(entry.recipeId().value());return ids;
        }
        private static MessageGuideRecipeDisplays roundTrip(MessageGuideRecipeDisplays msg){
            var out=new RegistryFriendlyByteBuf(Unpooled.buffer(),new Object());
            MessageGuideRecipeDisplays.toBytes(msg,out);
            return new MessageGuideRecipeDisplays(new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(out.snapshot()),new Object()));
        }
        private static void testAE2()throws Exception {
            RecipeManager m=new RecipeManager();
            add(m,"vanilla:one","minecraft:shaped",32);
            add(m,"ae2:upgrade","ae2:unregistered",20);
            add(m,"vanilla:two","minecraft:shapeless",12);
            m.add("buildcraft:assembly",buildcraft.silicon.BCSiliconRecipes.ASSEMBLY_TYPE.get(),e("buildcraft:assembly",10));
            MessageManager.sent.clear();MessageGuideRecipeDisplays.sendAll(player(m));
            ok(MessageManager.sent.size()==1,"AE2: packet count");
            List<String> ids=names(roundTrip(MessageManager.sent.get(0)));
            ok(ids.equals(List.of("vanilla:one","vanilla:two","buildcraft:assembly")),"AE2: lost valid entries "+ids);
        }
        private static void testLateSerializationAndUnknownDecode()throws Exception {
            RecipeManager m=new RecipeManager();
            add(m,"vanilla:first","minecraft:shaped",4);
            add(m,"mod:clientMissing","client:unknown",4);
            add(m,"vanilla:last","minecraft:shapeless",4);
            MessageManager.sent.clear();MessageGuideRecipeDisplays.sendAll(player(m));
            ok(names(roundTrip(MessageManager.sent.get(0))).equals(List.of("vanilla:first","vanilla:last")),"unknown client type destroyed packet");
            add(m,"mod:badLink","mod:brokenlink",2);
            MessageManager.sent.clear();MessageGuideRecipeDisplays.sendAll(player(m));
            ok(names(roundTrip(MessageManager.sent.get(0))).equals(List.of("vanilla:first","vanilla:last")),"LinkageError did not isolate entry");
        }
        private static void testEnumerationRollback()throws Exception {
            RecipeManager m=new RecipeManager();
            add(m,"vanilla:first","minecraft:shaped",4);
            add(m,"mod:throwsEnumeration","minecraft:shaped",4);
            add(m,"vanilla:last","minecraft:shaped",4);
            MessageManager.sent.clear();MessageGuideRecipeDisplays.sendAll(player(m));
            ok(names(roundTrip(MessageManager.sent.get(0))).equals(List.of("vanilla:first","vanilla:last")),"enumeration rollback failed");
        }
        private static void testBatching()throws Exception {
            RecipeManager m=new RecipeManager();
            for(int i=0;i<263;i++)add(m,"test:r"+i,"minecraft:shapeless",18);
            MessageManager.sent.clear();MessageGuideRecipeDisplays.sendAll(player(m));
            ok(MessageManager.sent.size()==3,"wrong batch count");
            int count=0;for(var msg:MessageManager.sent)count+=contents(roundTrip(msg)).size();
            ok(count==263,"lost recipes to batch splitting");
            RecipeManager large=new RecipeManager();
            add(large,"test:large1","minecraft:shaped",70000);
            add(large,"test:large2","minecraft:shaped",70000);
            add(large,"test:oversized","minecraft:shaped",140000);
            MessageManager.sent.clear();MessageGuideRecipeDisplays.sendAll(player(large));
            ok(MessageManager.sent.size()==2,"wrong byte batches");
            int total=0;for(var msg:MessageManager.sent)total+=contents(roundTrip(msg)).size();
            ok(total==2,"oversized recipe not omitted");
        }
        private static void testEmptyAndCreate()throws Exception {
            RecipeManager m=new RecipeManager();add(m,"ae2:upgrade","ae2:unregistered",5);
            MessageManager.sent.clear();MessageGuideRecipeDisplays.sendAll(player(m));
            ok(contents(roundTrip(MessageManager.sent.get(0))).isEmpty(),"empty sync missing");
            RecipeManager simple=new RecipeManager();add(simple,"vanilla:valid","minecraft:shaped",5);
            add(simple,"ae2:broken","ae2:unregistered",5);
            ok(contents(roundTrip(MessageGuideRecipeDisplays.create(player(simple)))).size()==1,"create filtering broken");
        }
        private static void testBrokenCatalog()throws Exception {
            RecipeManager broken=new RecipeManager();broken.setBrokenCatalog(true);
            MessageManager.sent.clear();MessageGuideRecipeDisplays.sendAll(player(broken));
            ok(MessageManager.sent.size()==1,"broken catalog should send an empty sync");
            ok(contents(roundTrip(MessageManager.sent.get(0))).isEmpty(),"catalog failure returned non-empty index");
        }
        public static void main(String[]args)throws Exception {
            testAE2();testLateSerializationAndUnknownDecode();testEnumerationRollback();testBatching();testEmptyAndCreate();testBrokenCatalog();
            System.out.println("PASS: unknown registry types, LinkageError, bad enumeration, decoding, size budgets, empty sync");
        }
    }'''
}


def compile_and_run(name: str, source: Path) -> None:
    with tempfile.TemporaryDirectory(prefix='bcce-guide-') as tmp:
        root=Path(tmp)
        for path,code in STUBS.items():
            file=root/'src'/path
            file.parent.mkdir(parents=True,exist_ok=True)
            file.write_text(dedent(code).strip()+'\n',encoding='utf8')
        code=source.read_text(encoding='utf8')
        if name=='26.3':
            pattern=r'//\? if neoforge && \(>=26\.3\) \{\n(.*?)\n//\? \} else \{\n(.*?)\n//\?\}'
            code,n=re.subn(pattern,lambda m:m.group(1),code,flags=re.DOTALL)
            assert n==1,(name,n)
        path=root/'src/buildcraft/lib/net/MessageGuideRecipeDisplays.java'
        path.parent.mkdir(parents=True,exist_ok=True)
        path.write_text(code,encoding='utf8')
        helper=root/'src/buildcraft/lib/guide/GuideFaultIsolation.java'
        helper.parent.mkdir(parents=True,exist_ok=True)
        helper.write_text((ROOT/'source-shared/src/main/java/buildcraft/lib/guide/GuideFaultIsolation.java').read_text(encoding='utf8'))
        targets=[str(p) for p in (root/'src').rglob('*.java')]
        out=subprocess.run(['javac','-encoding','UTF-8','-d',str(root/'classes'),*targets],text=True,capture_output=True)
        if out.returncode: raise AssertionError(f'{name} Java compile:\n{out.stderr}')
        out=subprocess.run(['java','-cp',str(root/'classes'),'test.GuideSyncRuntime'],text=True,capture_output=True)
        if out.returncode: raise AssertionError(f'{name} Java runtime:\n{out.stdout}\n{out.stderr}')
        print(name, out.stdout.strip())

class GuideRuntimeTest(unittest.TestCase):
    def test_framed_guide_sync(self):
        if not shutil.which('javac') or not shutil.which('java'):
            self.skipTest('requires JDK')
        for name,relpath in BASE.items():
            with self.subTest(version=name):
                compile_and_run(name, ROOT/relpath)

if __name__=='__main__':
    unittest.main(verbosity=2)
