#!/usr/bin/env python3
"""Compile and execute the ACTUAL 26.2 BCCE block preview batcher with API stubs.

This exercises draw-node count, per-cell transforms and quad cache retention.
It deliberately does not claim a full Minecraft/NeoForge compile.
"""
from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from source_lookup import read_materialized_java

MODEL = 'src/main/java/buildcraft/builders/client/render/pip/PreviewBlockModelRenderer.java'

STUBS = {
    'com/mojang/blaze3d/vertex/PoseStack.java': '''package com.mojang.blaze3d.vertex;
import java.util.ArrayDeque;
public class PoseStack {
    public static class Matrix {public float x,y,z; public void set(Matrix m){x=m.x;y=m.y;z=m.z;}}
    public static class Pose {private final Matrix p=new Matrix(),n=new Matrix();
        public Matrix pose(){return p;} public Matrix normal(){return n;}}
    private final ArrayDeque<Pose> frames=new ArrayDeque<>();
    public PoseStack(){frames.push(new Pose());}
    public Pose last(){return frames.peek();}
    public void pushPose(){Pose next=new Pose();next.pose().set(last().pose());next.normal().set(last().normal());frames.push(next);}
    public void popPose(){frames.pop();}
    public void translate(double x,double y,double z){last().pose().x+=x;last().pose().y+=y;last().pose().z+=z;}
}''',
    'com/mojang/blaze3d/vertex/VertexConsumer.java': '''package com.mojang.blaze3d.vertex;
import net.minecraft.client.resources.model.geometry.BakedQuad;
public interface VertexConsumer {void putBakedQuad(PoseStack.Pose pose,BakedQuad quad,QuadInstance props);}''',
    'com/mojang/blaze3d/vertex/QuadInstance.java': '''package com.mojang.blaze3d.vertex;
public class QuadInstance {public void setLightCoords(int a){}public void setOverlayCoords(int a){}public void setColor(int a){}}''',
    'net/minecraft/client/resources/model/geometry/BakedQuad.java': '''package net.minecraft.client.resources.model.geometry;
public class BakedQuad {public MaterialInfo materialInfo(){return new MaterialInfo();}
    public record MaterialInfo(int tintIndex){public MaterialInfo(){this(-1);}}}''',
    'net/minecraft/client/renderer/block/dispatch/BlockStateModel.java': '''package net.minecraft.client.renderer.block.dispatch;
import java.util.List;import net.minecraft.util.RandomSource;
public interface BlockStateModel{void collectParts(RandomSource r,List<BlockStateModelPart> p);}''',
    'net/minecraft/client/renderer/block/dispatch/BlockStateModelPart.java': '''package net.minecraft.client.renderer.block.dispatch;
import java.util.List;import net.minecraft.core.Direction;import net.minecraft.client.resources.model.geometry.BakedQuad;
public interface BlockStateModelPart{List<BakedQuad> getQuads(Direction d);}''',
    'net/minecraft/util/RandomSource.java': '''package net.minecraft.util;
public class RandomSource{public static RandomSource create(long seed){return new RandomSource();}}''',
    'net/minecraft/core/Direction.java': '''package net.minecraft.core;
public enum Direction{DOWN,UP,NORTH,SOUTH,WEST,EAST}''',
    'net/minecraft/core/BlockPos.java': '''package net.minecraft.core;
public record BlockPos(int x,int y,int z){public int getX(){return x;}public int getY(){return y;}public int getZ(){return z;}public BlockPos immutable(){return this;}}''',
    'net/minecraft/world/level/block/Block.java': '''package net.minecraft.world.level.block;public class Block {}''',
    'buildcraft/lib/engine/BlockEngineBase_BC8.java': '''package buildcraft.lib.engine;
public class BlockEngineBase_BC8 extends net.minecraft.world.level.block.Block{}''',
    'buildcraft/lib/internal/properties/BuildCraftProperties.java': '''package buildcraft.lib.internal.properties;
public class BuildCraftProperties{public static final Object BLOCK_FACING_6=new Object();}''',
    'net/minecraft/world/level/block/state/BlockState.java': '''package net.minecraft.world.level.block.state;
import net.minecraft.world.level.block.Block;import net.minecraft.core.Direction;
public class BlockState{private final Block block=new Block();public Block getBlock(){return block;}
    public boolean hasProperty(Object property){return false;}
    public Direction getValue(Object property){return Direction.UP;}}''',
    'net/minecraft/client/color/block/BlockTintSource.java': '''package net.minecraft.client.color.block;
public interface BlockTintSource{int color(net.minecraft.world.level.block.state.BlockState s);}''',
    'net/minecraft/client/color/block/BlockColors.java': '''package net.minecraft.client.color.block;
public class BlockColors{public BlockTintSource getTintSource(net.minecraft.world.level.block.state.BlockState b,int i){return null;}}''',
    'buildcraft/lib/compat/RenderCompat.java': '''package buildcraft.lib.compat;
public class RenderCompat{public static Object cutout(){return new Object();}}''',
    'net/minecraft/client/Minecraft.java': '''package net.minecraft.client;
import net.minecraft.client.renderer.block.dispatch.*;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.world.level.block.state.BlockState;
import java.util.List;
public class Minecraft{
    private static final Minecraft SELF=new Minecraft();
    public static int collects=0;
    public static Minecraft getInstance(){return SELF;}
    public Manager getModelManager(){return new Manager();}
    public net.minecraft.client.color.block.BlockColors getBlockColors(){return new net.minecraft.client.color.block.BlockColors();}
    public static class Manager{public Models getBlockStateModelSet(){return new Models();}}
    public static class Models{public BlockStateModel get(BlockState state){return (r,parts)->{
        collects++;parts.add(d -> d==null?List.of(new BakedQuad()):List.of());};}}
}''',
    'net/minecraft/client/renderer/SubmitNodeCollector.java': '''package net.minecraft.client.renderer;
import java.util.function.BiConsumer;
import com.mojang.blaze3d.vertex.*;
public class SubmitNodeCollector{
    public int submissions=0;public int vertices=0;
    public java.util.List<String> coords=new java.util.ArrayList<>();
    public void submitCustomGeometry(PoseStack p,Object type,BiConsumer<PoseStack.Pose,VertexConsumer> draw){
        submissions++;
        VertexConsumer consumer=(pose,quad,props)->{vertices++;coords.add(
            pose.pose().x+","+pose.pose().y+","+pose.pose().z);};
        draw.accept(p.last(),consumer);
    }
}''',
    'buildcraft/builders/client/render/pip/PreviewBatchProbe.java': '''package buildcraft.builders.client.render.pip;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import com.mojang.blaze3d.vertex.PoseStack;
public class PreviewBatchProbe {
    public static void main(String[] args){
        var cache=new PreviewBlockModelRenderer.CellCache();
        var batch=new PreviewBlockModelRenderer.Batch();
        var block=new BlockState();
        int cells=3000;
        for(int i=0;i<cells;i++) {
            if(!batch.add(block,new BlockPos(i%100, i/100, 3),cache,15728880,0))throw new AssertionError("no geometry");
        }
        var poses=new PoseStack();poses.translate(5,7,11);
        var sink=new SubmitNodeCollector();
        batch.submit(poses,sink);
        if(sink.submissions!=1)throw new AssertionError("expected 1 node, got "+sink.submissions);
        if(sink.vertices!=cells)throw new AssertionError("expected "+cells+" quads got "+sink.vertices);
        if(Minecraft.collects!=1)throw new AssertionError("baked "+Minecraft.collects+" times");
        if(!sink.coords.get(0).equals("5.0,7.0,14.0"))throw new AssertionError(sink.coords.get(0));
        if(!sink.coords.get(1).equals("6.0,7.0,14.0"))throw new AssertionError(sink.coords.get(1));
        if(!sink.coords.get(100).equals("5.0,8.0,14.0"))throw new AssertionError(sink.coords.get(100));
        var frame2=new PreviewBlockModelRenderer.Batch();
        frame2.add(block,new BlockPos(0,0,0),cache,15728880,0);
        frame2.submit(poses,new SubmitNodeCollector());
        if(Minecraft.collects!=1)throw new AssertionError("rebaked across frames");
        System.out.println("26.2 preview 3000 cells: 1 draw node, cached quads, coordinates preserved PASS");
    }
}'''
}


class PreviewBatchRuntimeTests(unittest.TestCase):
    def test_materialized_262_batch_compiles_and_batches_3000_cells(self):
        target=read_materialized_java('26.2-neoforge', MODEL)
        with tempfile.TemporaryDirectory(prefix='bcce-262-preview-batch-') as tmp:
            root=Path(tmp)
            sources=[]
            for path, content in {**STUBS,
                'buildcraft/builders/client/render/pip/PreviewBlockModelRenderer.java': target}.items():
                dst=root/path
                dst.parent.mkdir(parents=True,exist_ok=True)
                dst.write_text(content,encoding='utf8')
                sources.append(str(dst))
            cmd=['javac','-d',str(root),*sources]
            compiled=subprocess.run(cmd,capture_output=True,text=True,timeout=45)
            self.assertEqual(compiled.returncode,0,compiled.stderr)
            run=subprocess.run(['java','-cp',str(root),
                'buildcraft.builders.client.render.pip.PreviewBatchProbe'],
                capture_output=True,text=True,timeout=20)
            self.assertEqual(run.returncode,0,run.stderr)
            self.assertIn('1 draw node, cached quads, coordinates preserved PASS',run.stdout)


if __name__=='__main__':unittest.main()
