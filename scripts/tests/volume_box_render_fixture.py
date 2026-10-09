"""Compile and exercise the real materialized volume addon renderer against a strict BLOCK vertex format.

The fake VertexConsumer deliberately raises on missing color, atlas UV, overlay, lightmap or normal,
reproducing the BufferBuilder.endVertex failure from Forge 1.19.2. Also checks 6 faces / 24 vertices.
This is not a substitute for Minecraft GPU rendering.
"""
from __future__ import annotations
import subprocess
import tempfile
from pathlib import Path

SOURCES = {
    'net/minecraft/world/phys/AABB': '''package net.minecraft.world.phys;
public class AABB {
 public final double minX,minY,minZ,maxX,maxY,maxZ;
 public AABB(double a,double b,double c,double d,double e,double f) {minX=a;minY=b;minZ=c;maxX=d;maxY=e;maxZ=f;}
}''',
    'net/minecraft/client/renderer/texture/TextureAtlasSprite': '''package net.minecraft.client.renderer.texture;
public class TextureAtlasSprite {
 public float getU0(){return .25f;}public float getU1(){return .75f;}
 public float getV0(){return .125f;}public float getV1(){return .875f;}
}''',
    'net/minecraft/client/renderer/texture/OverlayTexture': '''package net.minecraft.client.renderer.texture;
public final class OverlayTexture {public static final int NO_OVERLAY = 10;}''',
    'com/mojang/blaze3d/vertex/VertexConsumer': '''package com.mojang.blaze3d.vertex;
import java.util.*;
public class VertexConsumer {
 public int count, alpha;public final List<float[]> normals=new ArrayList<>();
 private boolean active,position,color,uv,overlay,light,normal;
 private float nx,ny,nz;
 private void check() {if (!active)return; if (!position||!color||!uv||!overlay||!light||!normal)
 throw new IllegalStateException("Not filled all elements of the vertex: "+count+" "
 +position+color+uv+overlay+light+normal);
 normals.add(new float[]{nx,ny,nz});count++;active=false;}
 private VertexConsumer begin(){check();active=position=true;color=uv=overlay=light=normal=false;return this;}
 public VertexConsumer vertex(double x,double y,double z){return begin();}
 public VertexConsumer addVertex(float x,float y,float z){return begin();}
 public VertexConsumer color(int r,int g,int b,int a){color=true;alpha=a;return this;}
 public VertexConsumer setColor(int r,int g,int b,int a){return color(r,g,b,a);}
 public VertexConsumer uv(float u,float v){uv=true;return this;}
 public VertexConsumer setUv(float u,float v){return uv(u,v);}
 public VertexConsumer overlayCoords(int o){overlay=true;return this;}
 public VertexConsumer setOverlay(int o){return overlayCoords(o);}
 public VertexConsumer uv2(int l){light=true;return this;}
 public VertexConsumer setLight(int l){return uv2(l);}
 public VertexConsumer normal(float x,float y,float z){normal=true;nx=x;ny=y;nz=z;return this;}
 public VertexConsumer setNormal(float x,float y,float z){return normal(x,y,z);}
 public void endVertex(){check();}
 public void finish(){check();}
}''',
    'buildcraft/core/marker/volume/Harness': '''package buildcraft.core.marker.volume;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.phys.AABB;
public final class Harness {
 static int assertions;
 static void ok(boolean v){assertions++;if(!v)throw new AssertionError("check "+assertions);}
 public static void main(String[] argv){
 var consumer=new VertexConsumer();
 var bb=new AABB(1,2,3,4,5,6);
 AddonQuadRenderer.box(consumer,bb,new TextureAtlasSprite(),255);
 consumer.finish();ok(consumer.count==24);ok(consumer.alpha==255);
 float[][] expected={{0,0,-1},{0,0,1},{0,-1,0},{0,1,0},{-1,0,0},{1,0,0}};
 for(int face=0;face<6;face++){
 for(int vert=0;vert<4;vert++){
 var normal=consumer.normals.get(face*4+vert);
 ok(normal[0]==expected[face][0]&&normal[1]==expected[face][1]&&normal[2]==expected[face][2]);}}
 var ghost=new VertexConsumer();AddonQuadRenderer.box(ghost,bb,new TextureAtlasSprite(),127);
 ghost.finish();ok(ghost.count==24);ok(ghost.alpha==127);
 System.out.println(assertions+" assertions PASS");
 }}'''
}


def execute(real_source: Path) -> str:
    with tempfile.TemporaryDirectory(prefix='bc-volume-render-') as temp:
        root = Path(temp)
        sources=[]
        for name,text in SOURCES.items():
            dest=root/'src'/(name+'.java')
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_text(text)
            sources.append(str(dest))
        real=root/'src/buildcraft/core/marker/volume/AddonQuadRenderer.java'
        real.write_bytes(real_source.read_bytes())
        sources.append(str(real))
        build=subprocess.run(['javac','--release','21','-encoding','UTF-8','-d',str(root/'classes'),*sources],
            capture_output=True,text=True,timeout=70)
        if build.returncode:raise AssertionError('Renderer Java compile failed:\n'+build.stdout+'\n'+build.stderr)
        run=subprocess.run(['java','-cp',str(root/'classes'),'buildcraft.core.marker.volume.Harness'],
            capture_output=True,text=True,timeout=30)
        if run.returncode:raise AssertionError('Renderer BLOCK vertex check failed:\n'+run.stdout+'\n'+run.stderr)
        return run.stdout.strip()
