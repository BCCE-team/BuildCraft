#!/usr/bin/env python3
"""Real Java codec probe for NeoForge 1.21.X compressed blueprint snapshots.

Uses the *materialized production MessageSnapshotResponse* against minimal Netty/
Minecraft API doubles. It checks the real encoder/decoder's byte consumption,
including the four unread GZIP trailer bytes that previously disconnected a
1.21.1 integrated-server client. Not a replacement for Gradle or GameTests.
"""
from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from source_lookup import read_materialized_java, resolve_target_source

REL = 'src/main/java/buildcraft/builders/snapshot/MessageSnapshotResponse.java'

STUBS = {
    'buildcraft/builders/snapshot/Snapshot.java': '''package buildcraft.builders.snapshot;
import net.minecraft.nbt.CompoundTag;
public final class Snapshot {
    final byte[] content;
    public Snapshot(byte[] content){this.content=content;}
    public static CompoundTag writeToNBT(Snapshot s){return new CompoundTag(s.content);}
    public static Snapshot readFromNBT(CompoundTag nbt){return new Snapshot(nbt.content);}
}''',
    'buildcraft/builders/snapshot/MessageSnapshotResponseClientHandler.java': '''package buildcraft.builders.snapshot;
public final class MessageSnapshotResponseClientHandler {
    public static void handle(MessageSnapshotResponse ignored){}
}''',
    'buildcraft/lib/internal/debug/BCLog.java': '''package buildcraft.lib.internal.debug;
public final class BCLog {
    public static final Logger logger=new Logger();
    public static final class Logger {public void warn(String message,Throwable failure){}}
}''',
    'buildcraft/lib/net/BCPacketContext.java': '''package buildcraft.lib.net;
public interface BCPacketContext {void enqueueWork(Runnable task);}''',
    'io/netty/handler/codec/DecoderException.java': '''package io.netty.handler.codec;
public class DecoderException extends RuntimeException {
    public DecoderException(String s){super(s);}
    public DecoderException(String s,Throwable t){super(s,t);}
}''',
    'io/netty/handler/codec/EncoderException.java': '''package io.netty.handler.codec;
public class EncoderException extends RuntimeException {
    public EncoderException(String s){super(s);}
    public EncoderException(String s,Throwable t){super(s,t);}
}''',
    'net/minecraft/network/FriendlyByteBuf.java': '''package net.minecraft.network;
import java.util.Arrays;
public final class FriendlyByteBuf {
    private byte[] bytes;private int readIndex;private int writeIndex;
    public FriendlyByteBuf(){bytes=new byte[128];}
    public FriendlyByteBuf(byte[] data){bytes=data.clone();writeIndex=data.length;}
    public void write(int b){if(writeIndex>=bytes.length)bytes=Arrays.copyOf(bytes,bytes.length*2);bytes[writeIndex++]=(byte)b;}
    public int read(){return readIndex==writeIndex?-1:bytes[readIndex++]&255;}
    public int read(byte[] target,int off,int len){if(readIndex==writeIndex)return -1;
      int count=Math.min(len,readableBytes());System.arraycopy(bytes,readIndex,target,off,count);readIndex+=count;return count;}
    public int readableBytes(){return writeIndex-readIndex;}
    public int writerIndex(){return writeIndex;}
    public byte[] toByteArray(){return Arrays.copyOf(bytes,writeIndex);}
}''',
    'io/netty/buffer/ByteBufInputStream.java': '''package io.netty.buffer;
import java.io.InputStream;import net.minecraft.network.FriendlyByteBuf;
public final class ByteBufInputStream extends InputStream {
    private final FriendlyByteBuf buffer;
    public ByteBufInputStream(FriendlyByteBuf buffer){this.buffer=buffer;}
    @Override public int read(){return buffer.read();}
    @Override public int read(byte[] bytes,int off,int length){return buffer.read(bytes,off,length);}
}''',
    'io/netty/buffer/ByteBufOutputStream.java': '''package io.netty.buffer;
import java.io.OutputStream;import net.minecraft.network.FriendlyByteBuf;
public final class ByteBufOutputStream extends OutputStream {
    private final FriendlyByteBuf buffer;
    public ByteBufOutputStream(FriendlyByteBuf buffer){this.buffer=buffer;}
    @Override public void write(int value){buffer.write(value);}
    @Override public void write(byte[] bytes,int off,int len){for(int i=0;i<len;i++)write(bytes[off+i]);}
}''',
    'net/minecraft/nbt/CompoundTag.java': '''package net.minecraft.nbt;
public final class CompoundTag {
    public final byte[] content;
    public CompoundTag(byte[] bytes){this.content=bytes;}
}''',
    'net/minecraft/nbt/NbtAccounter.java': '''package net.minecraft.nbt;
public final class NbtAccounter {
    public final long limit;
    private NbtAccounter(long limit){this.limit=limit;}
    public static NbtAccounter create(long limit){return new NbtAccounter(limit);}
}''',
    'net/minecraft/nbt/NbtIo.java': '''package net.minecraft.nbt;
import java.io.*;import java.util.zip.*;
public final class NbtIo {
    public static void writeCompressed(CompoundTag tag,OutputStream output)throws IOException {
        try(DataOutputStream stream=new DataOutputStream(new GZIPOutputStream(output))){
            stream.writeInt(tag.content.length);stream.write(tag.content);
        }
    }
    public static CompoundTag read(DataInput input,NbtAccounter account)throws IOException {
        int len=input.readInt();if(len<0||len>account.limit)throw new IOException("size exceeded");
        byte[] bytes=new byte[len];input.readFully(bytes);return new CompoundTag(bytes);
    }
    public static CompoundTag readCompressed(InputStream input,NbtAccounter account)throws IOException {
        try(DataInputStream gzip=new DataInputStream(new GZIPInputStream(input))){return read(gzip,account);}
    }
}''',
    'net/neoforged/api/distmarker/Dist.java': '''package net.neoforged.api.distmarker;
public enum Dist {CLIENT,DEDICATED_SERVER}''',
    'net/neoforged/api/distmarker/OnlyIn.java': '''package net.neoforged.api.distmarker;
public @interface OnlyIn {Dist value();}''',
    'net/neoforged/fml/loading/FMLEnvironment.java': '''package net.neoforged.fml.loading;
import net.neoforged.api.distmarker.Dist;
public final class FMLEnvironment {public static Dist dist=Dist.CLIENT;public static Dist getDist(){return dist;}}''',
    'buildcraft/builders/snapshot/SnapshotGzipProbe.java': '''package buildcraft.builders.snapshot;
import java.io.*;import java.util.*;import java.util.zip.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtAccounter;
import io.netty.buffer.ByteBufInputStream;
public final class SnapshotGzipProbe {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void rejected(byte[] data,String caseName){
        try {new MessageSnapshotResponse(new FriendlyByteBuf(data));
             throw new AssertionError("accepted "+caseName);
        }catch(io.netty.handler.codec.DecoderException expected){}
    }
    private static byte[] writeBadPayload(byte[] bytes)throws Exception{
        ByteArrayOutputStream encoded=new ByteArrayOutputStream();
        try(DataOutputStream o=new DataOutputStream(new GZIPOutputStream(encoded))){
            o.writeInt(bytes.length-1);o.write(bytes);
        }
        return encoded.toByteArray();
    }
    public static void main(String[] args)throws Exception {
        byte[] body=new byte[2547];new Random(2547).nextBytes(body);
        FriendlyByteBuf encoded=new FriendlyByteBuf();
        new MessageSnapshotResponse(new Snapshot(body)).toBytes(encoded);
        byte[] packet=encoded.toByteArray();
        FriendlyByteBuf legacy=new FriendlyByteBuf(packet);
        NbtIo.readCompressed(new ByteBufInputStream(legacy), NbtAccounter.create(64L*1024*1024));
        check(legacy.readableBytes()==4,"fixture must reproduce the four trailing gzip bytes, got "+legacy.readableBytes());
        FriendlyByteBuf fixed=new FriendlyByteBuf(packet);
        MessageSnapshotResponse response=new MessageSnapshotResponse(fixed);
        check(fixed.readableBytes()==0,"production decoder left unread compressed bytes");
        check(Arrays.equals(response.getSnapshot().content,body),"round-trip changed payload");
        byte[] damaged=packet.clone();damaged[damaged.length-1]^=0x7f;
        rejected(damaged,"CRC/ISIZE corruption");
        rejected(Arrays.copyOf(packet,packet.length-4),"truncated GZIP trailer");
        byte[] extra=Arrays.copyOf(body,body.length+1);extra[body.length]=42;
        rejected(writeBadPayload(extra),"extra uncompressed byte after snapshot");
        rejected(new byte[8*1024*1024+1],"oversized compressed snapshot");
        System.out.println("snapshot gzip wire PASS: legacy trailing=4, fixed trailing=0, CRC/truncation/extra/size rejected");
    }
}''',
}


class SnapshotResponseGzipFramingTests(unittest.TestCase):
    def test_overlay_only_selected_for_1_21_x_neoforge(self):
        for target in ('1.21.1-neoforge', '1.21.11-neoforge'):
            with self.subTest(target=target):
                path = resolve_target_source(target, REL)
                self.assertIn('source-family-platforms/1.21.X/neoforge/', path.as_posix())
        for target in ('26.1.2-neoforge', '26.2-neoforge', '26.3-neoforge'):
            with self.subTest(target=target):
                self.assertIn('source-platforms/neoforge/', resolve_target_source(target, REL).as_posix())
        self.assertIn('source-platforms/forge/', resolve_target_source('1.20.1-forge', REL).as_posix())

    def test_decoder_finishes_gzip_and_keeps_original_protocol(self):
        for target in ('1.21.1-neoforge', '1.21.11-neoforge'):
            with self.subTest(target=target):
                source = read_materialized_java(target, REL)
                for required in (
                    'NbtIo.writeCompressed(Snapshot.writeToNBT(snapshot), new ByteBufOutputStream(buf))',
                    'NbtIo.read(input, NbtAccounter.create(MAX_UNCOMPRESSED_SNAPSHOT_BYTES))',
                    'new GZIPInputStream(new ByteBufInputStream(buf))',
                    'if (input.read() != -1)',
                    'MAX_COMPRESSED_SNAPSHOT_BYTES = 8 * 1024 * 1024',
                ):
                    self.assertIn(required, source)
                self.assertNotIn('NbtIo.readCompressed(', source.replace('NbtIo.readCompressed() finishes', ''))
                self.assertNotIn('skipBytes(', source)
                self.assertNotIn('buf.readerIndex(buf.writerIndex())', source)
                if target == '1.21.11-neoforge':
                    self.assertIn('FMLEnvironment.getDist()', source)
                    self.assertNotIn('FMLEnvironment.dist', source)
                    self.assertNotIn('@OnlyIn(Dist.CLIENT)', source)
                else:
                    self.assertIn('FMLEnvironment.dist', source)
                    self.assertIn('@OnlyIn(Dist.CLIENT)', source)

    def test_production_codec_compiles_and_consumes_the_gzip_trailer(self):
        for target in ('1.21.1-neoforge', '1.21.11-neoforge'):
            with self.subTest(target=target), tempfile.TemporaryDirectory(prefix='bcce-gzip-') as temp:
                root = Path(temp)
                sources=[]
                for relative, text in STUBS.items():
                    path=root/relative
                    path.parent.mkdir(parents=True,exist_ok=True)
                    path.write_text(text,encoding='utf-8')
                    sources.append(path)
                source_path=root/REL.removeprefix('src/main/java/')
                source_path.parent.mkdir(parents=True,exist_ok=True)
                source_path.write_text(read_materialized_java(target, REL), encoding='utf-8')
                sources.append(source_path)
                classes=root/'classes'
                classes.mkdir()
                compile_result=subprocess.run(['javac','-d',str(classes),*[str(p) for p in sources]],
                    capture_output=True,text=True,timeout=30)
                self.assertEqual(compile_result.returncode,0,compile_result.stderr)
                run_result=subprocess.run(['java','-cp',str(classes),
                    'buildcraft.builders.snapshot.SnapshotGzipProbe'],
                    capture_output=True,text=True,timeout=30)
                self.assertEqual(run_result.returncode,0,run_result.stderr)
                self.assertIn('snapshot gzip wire PASS:',run_result.stdout)


if __name__=='__main__':
    unittest.main()
