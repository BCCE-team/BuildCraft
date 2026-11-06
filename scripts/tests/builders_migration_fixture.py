#!/usr/bin/env python3
"""Behavior-based Builders migration probes used by maintained CI suites.

These probes exercise materialized BCCE Java against minimal, documented API
contracts. They intentionally do not assert that particular Java source text
exists. Full Minecraft compilation, in-game rendering, and GameTests remain
separate acceptance checks.
"""
from __future__ import annotations

# --- preview_batch: executable regression from test_builders_26x_preview_batch_runtime.py ---
def run_preview_batch(case):
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
        case.assertEqual(compiled.returncode,0,compiled.stderr)
        run=subprocess.run(['java','-cp',str(root),
            'buildcraft.builders.client.render.pip.PreviewBatchProbe'],
            capture_output=True,text=True,timeout=20)
        case.assertEqual(run.returncode,0,run.stderr)
        case.assertIn('1 draw node, cached quads, coordinates preserved PASS',run.stdout)


# --- preview_budget: executable regression from test_builders_26x_render_throttle_and_entity_fallback.py ---
def run_preview_budget(case):
    import subprocess

    import sys

    import tempfile

    import unittest

    from pathlib import Path

    ROOT=Path(__file__).resolve().parents[2]

    sys.path[:0]=[str(ROOT/'scripts'),str(ROOT/'scripts/tests')]

    from source_lookup import read_materialized_java, resolve_target_source

    from minecraft_compat_fixture import parse_sources

    B='src/main/java/buildcraft/builders/client/render/pip/'

    SCENE=B+'BlueprintPreviewScene.java'

    PIP=B+'BlueprintPipRenderer.java'

    BUDGET=B+'PreviewFrameBudget.java'

    source=read_materialized_java('26.2-neoforge',BUDGET)
    probe='''package buildcraft.builders.client.render.pip;
public final class BudgetProbe {
    public static void main(String... args) {
        var b=PreviewFrameBudget.forMaxFps(60);
        Object world=new Object(), models=new Object(), w2=new Object(), m2=new Object();
        long started=1000000000L;
        if(b.canReuse("a",world,models,started)) throw new AssertionError("reused undrawn");
        b.markRendered("a",world,models,started);
        if(!b.canReuse("a",world,models,started+16000000L))throw new AssertionError("missed valid 60 fps frame");
        if(b.canReuse("a",world,models,started+16666666L))throw new AssertionError("60 fps frame was still locked");
        if(b.canReuse("a",world,models,started+125000000L))throw new AssertionError("old 8 fps cap persists");
        if(b.canReuse("b",world,models,started+10000000L))throw new AssertionError("wrong snapshot");
        if(b.canReuse("a",w2,models,started+10000000L))throw new AssertionError("wrong level");
        if(b.canReuse("a",world,m2,started+10000000L))throw new AssertionError("old models");
        if(b.canReuse("a",world,models,started-1L))throw new AssertionError("clock reversal");
        b.markRendered("b",w2,m2,started+16666666L);
        if(!b.canReuse("b",w2,m2,started+16666667L))throw new AssertionError("new state");
        if(b.canReuse("a",world,models,started+16666667L))throw new AssertionError("stale old frame");
        // At 120 display frames/s, the capped 60 Hz preview must actually
        // submit roughly 60 frames per second (the old budget managed eight).
        var simulated=PreviewFrameBudget.forMaxFps(60);
        int redraws=0;
        for(int i=0;i<120;i++){
            long now=started+i*8333334L;
            if(!simulated.canReuse("a",world,models,now)){
                simulated.markRendered("a",world,models,now);
                redraws++;
            }
        }
        if(redraws<57 || redraws>61) throw new AssertionError("expected 60 Hz preview, got "+redraws);
        var unlimited=PreviewFrameBudget.forMaxFps(0);
        unlimited.markRendered("a",world,models,started);
        if(unlimited.canReuse("a",world,models,started+1L))throw new AssertionError("uncapped reused texture");
        var slow=PreviewFrameBudget.forMaxFps(30);
        slow.markRendered("a",world,models,started);
        if(!slow.canReuse("a",world,models,started+33333332L))throw new AssertionError("30 fps too fast");
        if(slow.canReuse("a",world,models,started+33333333L))throw new AssertionError("30 fps too slow");
        boolean rejected=false;
        try { PreviewFrameBudget.forMaxFps(-1); } catch(IllegalArgumentException expected){ rejected=true; }
        if(!rejected)throw new AssertionError("negative frame rate accepted");
        System.out.println("PiP budget PASS");
    }
}
'''
    with tempfile.TemporaryDirectory(prefix='bcce26-budget-javac-') as tmp:
        root=Path(tmp)
        for name,body in [('PreviewFrameBudget.java',source),('BudgetProbe.java',probe)]:
            (root/name).write_text(body,encoding='utf8')
        result=subprocess.run(['javac','-d',str(root),str(root/'PreviewFrameBudget.java'),
            str(root/'BudgetProbe.java')],capture_output=True,text=True,timeout=30)
        case.assertEqual(result.returncode,0,result.stderr)
        result=subprocess.run(['java','-cp',str(root),
            'buildcraft.builders.client.render.pip.BudgetProbe'],capture_output=True,text=True,timeout=30)
        case.assertEqual(result.returncode,0,result.stderr)
        case.assertIn('PiP budget PASS',result.stdout)


# --- detached_entity_ids: executable regression from test_builders_26x_preview_entity_ids.py ---
def run_detached_entity_ids(case):
    import re

    import subprocess

    import sys

    import tempfile

    import unittest

    from pathlib import Path

    ROOT = Path(__file__).resolve().parents[2]

    sys.path[:0] = [str(ROOT / 'scripts'), str(ROOT / 'scripts/tests')]

    from source_lookup import read_materialized_java

    from minecraft_compat_fixture import parse_sources

    SCENE = 'src/main/java/buildcraft/builders/client/render/pip/BlueprintPreviewScene.java'

    source = read_materialized_java('26.2-neoforge', SCENE)
    helper = re.search(
        r'    private static final AtomicInteger NEXT_PREVIEW_ENTITY_ID = new AtomicInteger\(-1\);'
        r'\s+private static <T extends Entity> T withPreviewId\(T entity\) \{.*?\n    \}',
        source, flags=re.S)
    case.assertIsNotNone(helper, 'Allocator must be extracted from the actual Java source')
    program = '''import java.util.concurrent.atomic.AtomicInteger;
import java.util.HashSet;
class PreviewIdProbe {
  static class Entity {
    private int id;
    public int getId() {
      if (id == 0) throw new IllegalStateException("Tried to access entity ID before ID assignment");
      return id;
    }
    public void setId(int id) { this.id = id; }
  }
''' + helper.group(0) + '''
  public static void main(String[] args) {
    HashSet<Integer> unique = new HashSet<>();
    for (int i = 0; i < 256; ++i) {
      Entity entity = withPreviewId(new Entity());
      int id = entity.getId(); // Simulates render-state extraction.
      if (id >= 0 || !unique.add(id)) throw new AssertionError("invalid detached ID " + id);
    }
    if (withPreviewId(null) != null) throw new AssertionError("null should stay null");
    System.out.println("preview entity IDs PASS " + unique.size());
  }
}
'''
    with tempfile.TemporaryDirectory(prefix='bcce-ids-run-') as work:
        main = Path(work) / 'PreviewIdProbe.java'
        main.write_text(program, encoding='utf-8')
        compiled = subprocess.run(['javac', '-d', work, str(main)], capture_output=True, text=True, timeout=30)
        case.assertEqual(0, compiled.returncode, compiled.stderr)
        result = subprocess.run(['java', '-cp', work, 'PreviewIdProbe'], capture_output=True, text=True, timeout=30)
        case.assertEqual(0, result.returncode, result.stderr)
        case.assertIn('preview entity IDs PASS 256', result.stdout)


# --- pipe_vertex_consumer_compat: executable regression from test_builders_263_pipe_vertex_consumer.py ---
def run_pipe_vertex_consumer_compat(case):
    import subprocess

    import sys

    import tempfile

    import unittest

    from pathlib import Path

    ROOT = Path(__file__).resolve().parents[2]

    sys.path[:0] = [str(ROOT / 'scripts'), str(ROOT / 'scripts/tests')]

    from source_lookup import read_materialized_java

    from minecraft_compat_fixture import parse_sources

    PIPE = 'src/main/java/buildcraft/builders/client/render/pip/PreviewPipeModel.java'

    TARGETS = ('1.21.11-neoforge', '26.1.2-neoforge', '26.2-neoforge', '26.3-neoforge')

    def extract_discard(java_source: str) -> str:
        begin = java_source.index('private static final VertexConsumer DISCARD = new VertexConsumer() {')
        end = java_source.index('\n    };', begin) + len('\n    };')
        return java_source[begin:end]

    signatures = '''
            VertexConsumer addVertex(float x, float y, float z);
            VertexConsumer setColor(int r, int g, int b, int a);
            VertexConsumer setColor(int color);
            VertexConsumer setUv(float u, float v);
            VertexConsumer setUv1(int u, int v);
            VertexConsumer setUv2(int u, int v);
            VertexConsumer setNormal(float x, float y, float z);
            VertexConsumer setLineWidth(float width);
'''
    for target in ('26.2-neoforge', '26.3-neoforge'):
        with case.subTest(target=target), tempfile.TemporaryDirectory() as tmp:
            is_263 = target == '26.3-neoforge'
            extra = 'VertexConsumer setUv3(float u, float v);' if is_263 else ''
            extracted = extract_discard(read_materialized_java(target, PIPE))
            test = f'''public final class PipeDiscardProbe {{
                interface VertexConsumer {{ {signatures} {extra} }}
                {extracted}
                public static void main(String[] args) {{
                    if (DISCARD.setUv2(1, 2) != DISCARD) throw new AssertionError();
                    {'if (DISCARD.setUv3(0.5f, 0.75f) != DISCARD) throw new AssertionError();' if is_263 else ''}
                }}
            }}'''
            path = Path(tmp) / 'PipeDiscardProbe.java'
            path.write_text(test, encoding='utf-8')
            built = subprocess.run(['javac', '-d', tmp, str(path)], capture_output=True, text=True, timeout=30)
            case.assertEqual(0, built.returncode, built.stderr)
            ran = subprocess.run(['java', '-cp', tmp, 'PipeDiscardProbe'], capture_output=True, text=True, timeout=30)
            case.assertEqual(0, ran.returncode, ran.stderr)


# --- saved_blockstate_nbt_263: executable regression from test_builders_263_saved_states_and_old_facade_preview.py ---
def run_saved_blockstate_nbt_263(case):
    import sys

    import shutil

    import subprocess

    import tempfile

    import unittest

    from pathlib import Path

    ROOT = Path(__file__).resolve().parents[2]

    sys.path.insert(0, str(ROOT / "scripts"))

    sys.path.insert(0, str(ROOT / "scripts/tests"))

    from source_lookup import read_materialized_java, resolve_target_source

    from minecraft_compat_fixture import parse_sources

    NATIVE = (
        "1.19.2-forge", "1.20.1-forge", "1.21.1-neoforge", "1.21.11-neoforge",
        "26.1.2-neoforge", "26.2-neoforge", "26.3-neoforge",
    )

    SAVED_STATE = "src/main/java/buildcraft/lib/compat/SavedBlockStateNbt.java"

    OLD_GUI = "src/main/java/buildcraft/builders/client/render/LegacyBlueprintGuiRenderer.java"

    OLD_ALPHA = "src/main/java/buildcraft/transport/client/render/LegacyPipeAlphaRenderer.java"

    FACADE = "src/main/java/buildcraft/silicon/plug/FacadePhasedState.java"

    DEFAULT = "src/main/java/buildcraft/builders/snapshot/SchematicBlockDefault.java"

    FLUID = "src/main/java/buildcraft/builders/snapshot/SchematicBlockFluid.java"

    """Compile the actual materialized codec with minimal NBT/Minecraft contracts."""
    fixtures = {
        "buildcraft/lib/internal/core/InvalidInputDataException.java": '''package buildcraft.lib.internal.core;
                public class InvalidInputDataException extends java.io.IOException {
                    public InvalidInputDataException(String message) { super(message); }
                }''',
        "net/minecraft/nbt/Tag.java": '''package net.minecraft.nbt;
                public abstract class Tag { public abstract Tag copy(); }''',
        "net/minecraft/nbt/StringTag.java": '''package net.minecraft.nbt;
                public final class StringTag extends Tag {
                    public final String value;
                    public StringTag(String value) { this.value = value; }
                    @Override public Tag copy() { return new StringTag(value); }
                    @Override public boolean equals(Object obj) {
                        return obj instanceof StringTag tag && tag.value.equals(value);
                    }
                }''',
        "net/minecraft/nbt/CompoundTag.java": '''package net.minecraft.nbt;
                public final class CompoundTag extends Tag {
                    private final java.util.Map<String, Tag> data = new java.util.HashMap<>();
                    public Tag get(String name) { return data.get(name); }
                    public void put(String name, Tag value) { data.put(name, value); }
                    public void putString(String name, String value) { put(name, new StringTag(value)); }
                    public void remove(String name) { data.remove(name); }
                    @Override public CompoundTag copy() {
                        CompoundTag result = new CompoundTag();
                        data.forEach((key, value) -> result.put(key, value.copy()));
                        return result;
                    }
                    @Override public boolean equals(Object obj) {
                        return obj instanceof CompoundTag tag && tag.data.equals(data);
                    }
                }''',
        "net/minecraft/core/registries/BuiltInRegistries.java": '''package net.minecraft.core.registries;
                public final class BuiltInRegistries { public static final Object BLOCK = new Object(); }''',
        "net/minecraft/world/level/block/state/BlockState.java": '''package net.minecraft.world.level.block.state;
                import net.minecraft.nbt.CompoundTag;
                public final class BlockState {
                    public final String name;
                    public final CompoundTag properties;
                    public BlockState(String name, CompoundTag properties) {
                        this.name = name; this.properties = properties;
                    }
                }''',
        "net/minecraft/nbt/NbtUtils.java": '''package net.minecraft.nbt;
                import net.minecraft.world.level.block.state.BlockState;
                public final class NbtUtils {
                    public static CompoundTag writeBlockState(BlockState state) {
                        CompoundTag tag = new CompoundTag();
                        tag.putString("id", state.name);
                        tag.put("properties", state.properties.copy());
                        return tag;
                    }
                    public static BlockState readBlockState(Object blocks, CompoundTag tag) {
                        StringTag id = (StringTag) tag.get("id");
                        CompoundTag props = (CompoundTag) tag.get("properties");
                        return new BlockState(id.value, props == null ? new CompoundTag() : props.copy());
                    }
                }''',
        "CodecRuntimeTest.java": '''import buildcraft.lib.compat.SavedBlockStateNbt;
                import buildcraft.lib.internal.core.InvalidInputDataException;
                import net.minecraft.nbt.CompoundTag;
                import net.minecraft.world.level.block.state.BlockState;
                public final class CodecRuntimeTest {
                    public static void main(String[] args) throws Exception {
                        CompoundTag old = new CompoundTag();
                        old.putString("Name", "buildcraftcore:engine");
                        CompoundTag props = new CompoundTag();
                        props.putString("type", "creative");
                        old.put("Properties", props);
                        CompoundTag untouched = old.copy();
                        BlockState restored = SavedBlockStateNbt.read(old);
                        if (!restored.name.equals("buildcraftcore:engine") ||
                            !restored.properties.equals(props) || !old.equals(untouched))
                            throw new AssertionError("Legacy engine state lost or input changed");
                        CompoundTag save = SavedBlockStateNbt.write(restored);
                        if (save.get("Name") == null || save.get("Properties") == null ||
                            save.get("id") != null || save.get("properties") != null)
                            throw new AssertionError("New saves are not backward compatible");
                        CompoundTag modern = new CompoundTag();
                        modern.putString("id", "minecraft:glass");
                        if (!SavedBlockStateNbt.read(modern).name.equals("minecraft:glass"))
                            throw new AssertionError("Modern format failed");
                        CompoundTag conflicting = old.copy();
                        conflicting.putString("id", "minecraft:bedrock");
                        try {
                            SavedBlockStateNbt.read(conflicting);
                            throw new AssertionError("Conflicting block IDs accepted");
                        } catch (InvalidInputDataException correct) { }
                        CompoundTag conflictProps = old.copy();
                        CompoundTag otherProps = new CompoundTag();
                        otherProps.putString("type", "redstone");
                        conflictProps.put("properties", otherProps);
                        try {
                            SavedBlockStateNbt.read(conflictProps);
                            throw new AssertionError("Conflicting engine properties accepted");
                        } catch (InvalidInputDataException correct) { }
                    }
                }''',
    }
    with tempfile.TemporaryDirectory(prefix="bcce-263-state-codec-") as tmp:
        project = Path(tmp)
        for name, source in fixtures.items():
            path = project / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(source, encoding="utf-8")
        codec = project / "buildcraft/lib/compat/SavedBlockStateNbt.java"
        codec.parent.mkdir(parents=True, exist_ok=True)
        codec.write_text(read_materialized_java("26.3-neoforge", SAVED_STATE), encoding="utf-8")
        sources = sorted(str(path) for path in project.rglob("*.java"))
        subprocess.run(["javac", *sources], cwd=project, check=True,
                       capture_output=True, text=True, timeout=30)
        subprocess.run(["java", "-cp", str(project), "CodecRuntimeTest"],
                       cwd=project, check=True, capture_output=True, text=True, timeout=15)


# --- frame_anchor_matching: executable regression from test_builders_26x_entity_build_and_assembly_codec.py ---
def run_frame_anchor_matching(case):
    import json

    import subprocess

    import sys

    import tempfile

    import unittest

    from pathlib import Path

    ROOT = Path(__file__).resolve().parents[2]

    sys.path[:0] = [str(ROOT / 'scripts'), str(ROOT / 'scripts/tests')]

    from minecraft_compat_fixture import parse_sources

    from source_config import load_properties, target_layout

    from source_layout import effective_source_files

    from source_lookup import read_materialized_java, resolve_target_source

    TARGETS = ('26.1.2-neoforge', '26.2-neoforge', '26.3-neoforge')

    BUILDER = 'src/main/java/buildcraft/builders/snapshot/BlueprintBuilder.java'

    SCHEMATIC = 'src/main/java/buildcraft/builders/snapshot/SchematicEntityDefault.java'

    ASSEMBLY = 'src/main/java/buildcraft/lib/recipe/AssemblyRecipe.java'

    source = read_materialized_java('26.1.2-neoforge', SCHEMATIC)
    method = source.split('    public boolean matchesPlacedEntity(', 1)[1].split('    @Nonnull', 1)[0].strip()
    method = 'public boolean matchesPlacedEntity(' + method
    code = '''public final class EntityPlacementProbe {
            enum Direction { NORTH, EAST, SOUTH, WEST }
            record BlockPos(int x,int y,int z) {
                BlockPos offset(BlockPos o){return new BlockPos(x+o.x,y+o.y,z+o.z);}
            }
            record Vec3(double x,double y,double z) {
                static Vec3 atLowerCornerOf(BlockPos pos){return new Vec3(pos.x(),pos.y(),pos.z());}
                Vec3 add(Vec3 o){return new Vec3(x+o.x,y+o.y,z+o.z);}
                double distanceToSqr(Vec3 o){return Math.pow(x-o.x,2)+Math.pow(y-o.y,2)+Math.pow(z-o.z,2);}
            }
            static class Entity {
                final String type;
                Vec3 location;
                Entity(String type,Vec3 location){this.type=type;this.location=location;}
                double distanceToSqr(Vec3 pos){return location.distanceToSqr(pos);}
                Object registryAccess(){return new Object();}
            }
            static class HangingEntity extends Entity {
                final BlockPos hanging;
                final Direction dir;
                HangingEntity(String type,Vec3 location,BlockPos hanging,Direction dir){
                    super(type,location);this.hanging=hanging;this.dir=dir;
                }
                BlockPos getPos(){return hanging;}
                Direction getDirection(){return dir;}
            }
            static final class ItemStack {
                final String item; final int count;
                ItemStack(String item,int count){this.item=item;this.count=count;}
                static boolean isSameItemSameComponents(ItemStack a,ItemStack b){return a.item.equals(b.item);}
                int getCount(){return count;}
            }
            static final class ItemStackUtil {
                static ItemStack parseOptional(Object context,String item){return new ItemStack(item,1);}
            }
            static final class NbtCompat {
                static String getCompound(java.util.Map<String,String> tag,String key){return tag.getOrDefault(key,"empty");}
            }
            static final class ItemFrame extends HangingEntity {
                final ItemStack item;
                ItemFrame(String type,Vec3 location,BlockPos pos,Direction facing,ItemStack item) {
                    super(type,location,pos,facing); this.item=item;
                }
                ItemStack getItem(){return item;}
            }
            private final java.util.Map<String,String> entityNbt = new java.util.HashMap<>();
            private final String type;
            private final BlockPos hangingPos;
            private final Direction hangingFacing;
            private final Vec3 pos;
            EntityPlacementProbe(String type, BlockPos anchor, Direction dir, Vec3 local) {
                this.type=type; this.hangingPos=anchor; this.hangingFacing=dir; this.pos=local;
            }
            boolean matchesEntityType(Entity entity){return entity != null && type.equals(entity.type);}
''' + method + '''
            public static void main(String[] args) {
                var source = new EntityPlacementProbe("frame",new BlockPos(2,1,3),Direction.WEST,new Vec3(2.5,1.5,3.5));
                var origin = new BlockPos(100,60,100);
                source.entityNbt.put("Item", "chest");
                var correct = new ItemFrame("frame",new Vec3(99.99,59.99,99.99),
                    new BlockPos(102,61,103),Direction.WEST,new ItemStack("chest",1));
                if (!source.matchesPlacedEntity(correct, origin, 0.1))
                    throw new AssertionError("attached frame lost due to position rounding");
                if (source.matchesPlacedEntity(new HangingEntity("frame", correct.location,
                    new BlockPos(102,61,102),Direction.WEST),origin,0.1))
                    throw new AssertionError("wrong frame anchor");
                if (source.matchesPlacedEntity(new HangingEntity("frame",correct.location,
                    new BlockPos(102,61,103),Direction.EAST),origin,0.1))
                    throw new AssertionError("wrong frame facing");
                if (source.matchesPlacedEntity(new HangingEntity("painting",correct.location,
                    new BlockPos(102,61,103),Direction.WEST),origin,0.1))
                    throw new AssertionError("wrong entity type");
                if (source.matchesPlacedEntity(new ItemFrame("frame",correct.location,
                    new BlockPos(102,61,103),Direction.WEST,new ItemStack("stone",1)),origin,0.1))
                    throw new AssertionError("wrong framed item accepted");
                var armor = new EntityPlacementProbe("armor_stand",new BlockPos(0,0,0),Direction.NORTH,
                    new Vec3(3.5,1,2.5));
                if (!armor.matchesPlacedEntity(new Entity("armor_stand",new Vec3(103.5,61,102.5)),origin,0.1))
                    throw new AssertionError("armor stand position lost");
                if (armor.matchesPlacedEntity(new Entity("armor_stand",new Vec3(104.5,61,102.5)),origin,0.1))
                    throw new AssertionError("distant armor stand accepted");
                System.out.println("entity anchors and positions PASS");
            }
        }
'''
    with tempfile.TemporaryDirectory(prefix='bcce-26-anchor-') as tmp:
        path = Path(tmp) / 'EntityPlacementProbe.java'
        path.write_text(code, encoding='utf-8')
        compile = subprocess.run(['javac', str(path)], capture_output=True, text=True, timeout=30)
        case.assertEqual(compile.returncode, 0, compile.stderr)
        execute = subprocess.run(['java', '-cp', tmp, 'EntityPlacementProbe'], capture_output=True, text=True, timeout=30)
        case.assertEqual(execute.returncode, 0, execute.stderr)
        case.assertIn('entity anchors and positions PASS', execute.stdout)


# --- fluid_preview_tint: executable regression from test_builders_26x_snapshot_gui_fluid.py ---
def run_fluid_preview_tint(case):
    import subprocess

    import sys

    import tempfile

    import unittest

    from pathlib import Path

    ROOT = Path(__file__).resolve().parents[2]

    sys.path[:0] = [str(ROOT / 'scripts'), str(ROOT / 'scripts/tests')]

    from source_lookup import read_materialized_java, resolve_target_source

    from minecraft_compat_fixture import parse_sources

    TARGETS = ('26.1.2-neoforge', '26.2-neoforge', '26.3-neoforge')

    GUI = 'src/main/java/buildcraft/builders/client/render/BlueprintScreenPreview.java'

    FLUID = 'src/main/java/buildcraft/builders/client/render/pip/PreviewFluidRenderer.java'

    TINT = 'src/main/java/buildcraft/builders/client/render/pip/PreviewFluidTint.java'

    NET = 'src/main/java/buildcraft/builders/snapshot/MessageSnapshotResponse.java'

    code = read_materialized_java('26.1.2-neoforge', TINT)
    stub_java = {
        'net/minecraft/client/Minecraft.java': '''package net.minecraft.client;
public class Minecraft {
    public boolean broken;
    private static final Minecraft INSTANCE = new Minecraft();
    public static Minecraft getInstance(){return INSTANCE;}
    public Manager getModelManager(){ if(broken) throw new IllegalStateException(); return new Manager(); }
    public static class Manager{public Models getFluidStateModelSet(){return new Models();}}
    public static class Models{public net.minecraft.client.renderer.block.FluidModel get(net.minecraft.world.level.material.FluidState s){
        return new net.minecraft.client.renderer.block.FluidModel(s.value); }}
}''',
        'net/minecraft/client/renderer/block/FluidModel.java': '''package net.minecraft.client.renderer.block;
public class FluidModel {
    final int color;
    public FluidModel(int color){this.color=color;}
    public Tint fluidTintSource(){return color == 0 ? null : new Tint(color);}
    public static class Tint {
        final int color;Tint(int color){this.color=color;}
        public int color(net.minecraft.world.level.material.FluidState state){return color;}
    }
}''',
        'net/minecraft/world/level/material/FluidState.java': '''package net.minecraft.world.level.material;
public class FluidState {
    public int value;public boolean empty;
    public FluidState(int value,boolean empty){this.value=value;this.empty=empty;}
    public boolean isEmpty(){return empty;}
}''',
        'buildcraft/builders/client/render/pip/PreviewFluidTintProbe.java': '''package buildcraft.builders.client.render.pip;
public class PreviewFluidTintProbe {
    public static void main(String[] args) {
        var dyed = new net.minecraft.world.level.material.FluidState(0xB0553388,false);
        var clean = new net.minecraft.world.level.material.FluidState(0,false);
        var empty = new net.minecraft.world.level.material.FluidState(42,true);
        if(PreviewFluidTint.color(dyed)!=0xB0553388)throw new AssertionError("tint lost");
        if(PreviewFluidTint.color(clean)!=-1)throw new AssertionError("untinted lost");
        if(PreviewFluidTint.color(empty)!=-1)throw new AssertionError("empty lost");
        net.minecraft.client.Minecraft.getInstance().broken=true;
        if(PreviewFluidTint.color(dyed)!=-1)throw new AssertionError("reload failure");
        System.out.println("26x fluid preview tint PASS");
    }
}''',
    }
    with tempfile.TemporaryDirectory(prefix='26x-fluid-api-') as temp:
        root = Path(temp)
        files = []
        for relative, contents in (*stub_java.items(), ('buildcraft/builders/client/render/pip/PreviewFluidTint.java', code)):
            path = root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(contents, encoding='utf-8')
            files.append(path)
        result = subprocess.run(['javac', '-d', str(root), *[str(p) for p in files]],
                                capture_output=True, text=True, timeout=30)
        case.assertEqual(result.returncode, 0, result.stderr)
        result = subprocess.run(['java', '-cp', str(root),
                                 'buildcraft.builders.client.render.pip.PreviewFluidTintProbe'],
                                capture_output=True, text=True, timeout=30)
        case.assertEqual(result.returncode, 0, result.stderr)
        case.assertIn('26x fluid preview tint PASS', result.stdout)


# --- single_block_schematic_cache: executable regression from test_builders_single_schematic_preview.py ---
def run_single_block_schematic_cache(case):
    from pathlib import Path

    import shutil

    import subprocess

    import sys

    import tempfile

    import unittest

    ROOT = Path(__file__).resolve().parents[2]

    sys.path.insert(0, str(ROOT / 'scripts'))

    from source_lookup import read_materialized_java, resolve_target_source

    TARGETS = ('1.19.2-forge', '1.20.1-forge', '1.21.1-neoforge',
               '1.21.11-neoforge', '26.1.2-neoforge', '26.2-neoforge', '26.3-neoforge')

    PKG = 'src/main/java/buildcraft/builders/client/tooltip/'

    def read(target, filename):
        return read_materialized_java(target, PKG + filename)

    stubs = {
        'javax/annotation/Nullable.java': 'package javax.annotation; public @interface Nullable {}',
        'net/minecraft/core/BlockPos.java': '''package net.minecraft.core;
                public record BlockPos(int x,int y,int z) {public static final BlockPos ZERO = new BlockPos(0,0,0);}''',
        'net/minecraft/core/Direction.java': 'package net.minecraft.core; public enum Direction {NORTH}',
        'net/minecraft/nbt/Tag.java': 'package net.minecraft.nbt; public abstract class Tag {public abstract Tag copy();}',
        'net/minecraft/nbt/CompoundTag.java': '''package net.minecraft.nbt;
                import java.util.*;
                public class CompoundTag extends Tag {
                    private final Map<String,Object> entries = new HashMap<>();
                    public void put(String k,Tag v){entries.put(k,v);}
                    public void putInt(String k,int n){entries.put(k,n);}
                    public int getInt(String k){return (int)entries.getOrDefault(k,0);}
                    public Tag get(String k){Object o=entries.get(k);return o instanceof Tag ? (Tag)o : null;}
                    public CompoundTag getCompound(String k){return (CompoundTag)entries.get(k);}
                    public boolean contains(String k,int t){return t==10 && get(k) instanceof CompoundTag;}
                    @Override public CompoundTag copy(){CompoundTag result=new CompoundTag();
                        for(var entry:entries.entrySet())result.entries.put(entry.getKey(),entry.getValue() instanceof Tag t?t.copy():entry.getValue());
                        return result;}
                    @Override public boolean equals(Object other){return other instanceof CompoundTag c && entries.equals(c.entries);}
                    @Override public int hashCode(){return entries.hashCode();}
                }''',
        'net/minecraft/world/item/ItemStack.java': '''package net.minecraft.world.item;
                import net.minecraft.nbt.CompoundTag;
                public class ItemStack {public CompoundTag data;public boolean used;
                  public ItemStack(CompoundTag d,boolean u){data=d;used=u;}
                  public CompoundTag getTag(){return data;}}
            ''',
        'buildcraft/builders/internal/schematic/legacy/ISchematicBlock.java': '''package buildcraft.builders.internal.schematic.legacy;
                public interface ISchematicBlock{boolean isAir();}''',
        'buildcraft/builders/snapshot/Blueprint.java': '''package buildcraft.builders.snapshot;
                import net.minecraft.core.*;import java.util.*;
                import buildcraft.builders.internal.schematic.legacy.ISchematicBlock;
                public class Blueprint {public BlockPos size,offset;public Direction facing;
                  public int[] data;public final List<ISchematicBlock> palette=new ArrayList<>();}''',
        'buildcraft/builders/item/ItemSchematicSingle.java': '''package buildcraft.builders.item;
                import net.minecraft.world.item.ItemStack;
                import buildcraft.builders.internal.schematic.legacy.ISchematicBlock;
                public class ItemSchematicSingle {
                  public static final String NBT_KEY="schematic";public static int reads;
                  public static boolean isUsed(ItemStack s){return s.used;}
                  public static ISchematicBlock getSchematicSafe(ItemStack s){reads++;
                    int id=s.data.getCompound(NBT_KEY).getInt("block");
                    return id<0?null:()->id==0;}}
                ''',
        'buildcraft/lib/misc/ItemStackUtil.java': '''package buildcraft.lib.misc;
                import net.minecraft.world.item.ItemStack;import net.minecraft.nbt.CompoundTag;
                public class ItemStackUtil{public static CompoundTag getCustomDataOrNull(ItemStack s){return s.data==null?null:s.data.copy();}}''',
        'Test.java': '''import buildcraft.builders.client.tooltip.SingleSchematicPreviewCache;
                import buildcraft.builders.item.ItemSchematicSingle;
                import buildcraft.builders.snapshot.Blueprint;
                import net.minecraft.nbt.CompoundTag;import net.minecraft.world.item.ItemStack;
                public class Test {
                  static ItemStack stack(int id){CompoundTag payload=new CompoundTag();payload.putInt("block",id);
                    CompoundTag root=new CompoundTag();root.put("schematic",payload);return new ItemStack(root,true);}
                  static void require(boolean x){if(!x)throw new AssertionError();}
                  public static void main(String[]args){
                    ItemStack s=stack(2);
                    Blueprint first=SingleSchematicPreviewCache.previewFor(s);
                    require(first!=null && first.data.length==1 && first.palette.size()==1);
                    require(first==SingleSchematicPreviewCache.previewFor(s));
                    require(first==SingleSchematicPreviewCache.previewFor(stack(2)));
                    require(ItemSchematicSingle.reads==1);
                    s.data.getCompound("schematic").putInt("block",3);
                    Blueprint second=SingleSchematicPreviewCache.previewFor(s);
                    require(second!=null && second!=first && ItemSchematicSingle.reads==2);
                    require(SingleSchematicPreviewCache.previewFor(stack(0))==null);
                    require(SingleSchematicPreviewCache.previewFor(stack(-1))==null);
                    require(SingleSchematicPreviewCache.previewFor(new ItemStack(null,true))==null);
                    require(SingleSchematicPreviewCache.previewFor(new ItemStack(null,false))==null);
                    SingleSchematicPreviewCache.clear();
                    require(SingleSchematicPreviewCache.previewFor(stack(2))!=first);
                    require(first.palette.size()==1 && second.palette.size()==1);
                  }
                }''',
    }
    for target in ('1.19.2-forge', '1.21.11-neoforge', '26.3-neoforge'):
        with case.subTest(target=target), tempfile.TemporaryDirectory(prefix='bcce-single-preview-') as temporary:
            folder = Path(temporary)
            for name, contents in stubs.items():
                path = folder / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(contents, encoding='utf-8')
            cache = folder / 'buildcraft/builders/client/tooltip/SingleSchematicPreviewCache.java'
            cache.parent.mkdir(parents=True, exist_ok=True)
            cache.write_text(read(target, 'SingleSchematicPreviewCache.java'), encoding='utf-8')
            subprocess.run(['javac', '-d', str(folder)] + [str(path) for path in folder.rglob('*.java')],
                           check=True, capture_output=True, text=True, timeout=30)
            subprocess.run(['java', '-cp', str(folder), 'Test'],
                           check=True, capture_output=True, text=True, timeout=15)


# --- engine_variant_material_cost: executable regression from test_builders_engine_variants_121x.py ---
def run_engine_variant_material_cost(case):
    import re

    import subprocess

    import sys

    import tempfile

    import unittest

    from pathlib import Path

    ROOT = Path(__file__).resolve().parents[2]

    sys.path.insert(0, str(ROOT / 'scripts'))

    from source_lookup import read_materialized_java

    BASE = 'src/main/java/buildcraft/'

    ENGINE_ITEM = BASE + 'core/EngineItemVariants.java'

    EXTRACTOR = BASE + 'builders/snapshot/RequiredExtractorItemFromBlock.java'

    R_1211 = BASE + 'builders/client/render/BlueprintGuiRenderer.java'

    S_1211 = BASE + 'builders/client/render/BlueprintBlockEntityScene.java'

    R_12111 = BASE + 'builders/client/render/pip/BlueprintPipRenderer.java'

    stubs = {
        'javax/annotation/Nonnull.java': 'package javax.annotation; public @interface Nonnull {}',
        'javax/annotation/Nullable.java': 'package javax.annotation; public @interface Nullable {}',
        'net/minecraft/core/BlockPos.java': 'package net.minecraft.core; public class BlockPos { public static final BlockPos ZERO = new BlockPos(); }',
        'net/minecraft/nbt/CompoundTag.java': 'package net.minecraft.nbt; public class CompoundTag {}',
        'net/minecraft/world/level/Level.java': 'package net.minecraft.world.level; public class Level {}',
        'net/minecraft/world/level/block/state/properties/Property.java': 'package net.minecraft.world.level.block.state.properties; public class Property<T> {}',
        'net/minecraft/world/item/Item.java': '''package net.minecraft.world.item; public class Item {
                public final String id; public Item(String id) { this.id=id; }
            }''',
        'net/minecraft/world/item/ItemStack.java': '''package net.minecraft.world.item; public class ItemStack {
                public static final ItemStack EMPTY = new ItemStack((Item)null);
                public final Item item; public ItemStack(Item item) {this.item=item;}
                public boolean isEmpty() { return item==null; }
            }''',
        'net/minecraft/world/level/block/Block.java': '''package net.minecraft.world.level.block;
                import net.minecraft.world.item.*;
                import net.minecraft.world.level.Level;
                import net.minecraft.core.BlockPos;
                import net.minecraft.world.level.block.state.BlockState;
                public class Block {
                    // Simulate the observed ambiguity: every variant shares one block,
                    // and the default item incorrectly resolves to FE.
                    public Item asItem() {return new Item("FE");}
                    public ItemStack getCloneItemStack(Level world, BlockPos pos, BlockState state) {
                        return new ItemStack(asItem());
                    }
                }''',
        'net/minecraft/world/level/block/state/BlockState.java': '''package net.minecraft.world.level.block.state;
                import net.minecraft.world.level.block.Block;
                import net.minecraft.world.level.block.state.properties.Property;
                public class BlockState {
                    private final Block block; private final Object variant;
                    public BlockState(Block block, Object variant) {this.block=block; this.variant=variant;}
                    public Block getBlock() {return block;}
                    @SuppressWarnings("unchecked") public <T> T getValue(Property<T> p) {return (T)variant;}
                }''',
        'buildcraft/lib/internal/enums/EnumEngineType.java': '''package buildcraft.lib.internal.enums;
                public enum EnumEngineType { WOOD, STONE, IRON, CREATIVE, FE }''',
        'buildcraft/lib/item/MultiBlockItem.java': '''package buildcraft.lib.item;
                import net.minecraft.world.item.Item;
                public class MultiBlockItem<T> extends Item {
                    public MultiBlockItem(String id) {super(id);}
                }''',
        'buildcraft/core/BCCoreItems.java': '''package buildcraft.core;
                import java.util.EnumMap;
                import buildcraft.lib.item.MultiBlockItem;
                import buildcraft.lib.internal.enums.EnumEngineType;
                public class BCCoreItems {
                    public static final EnumMap<EnumEngineType, MultiBlockItem<EnumEngineType>> ENGINE_ITEM_MAP =
                        new EnumMap<>(EnumEngineType.class);
                }''',
        'buildcraft/core/block/BlockEngine_BC8.java': '''package buildcraft.core.block;
                import buildcraft.lib.internal.enums.EnumEngineType;
                import net.minecraft.world.level.block.Block;
                import net.minecraft.world.level.block.state.properties.Property;
                public class BlockEngine_BC8 extends Block {
                    private final Property<EnumEngineType> type = new Property<>();
                    public Property<EnumEngineType> getEngineProperty() {return type;}
                }''',
        'buildcraft/builders/snapshot/RequiredExtractor.java': '''package buildcraft.builders.snapshot;
                import java.util.List;
                import javax.annotation.*;
                import net.minecraft.world.item.ItemStack;
                import net.minecraft.world.level.Level;
                import net.minecraft.world.level.block.state.BlockState;
                import net.minecraft.nbt.CompoundTag;
                public abstract class RequiredExtractor {
                    public abstract List<ItemStack> extractItemsFromBlock(BlockState s, CompoundTag tag, Level level);
                }''',
        'EngineVariantsProbe.java': '''import buildcraft.core.*;
                import buildcraft.core.block.BlockEngine_BC8;
                import buildcraft.builders.snapshot.RequiredExtractorItemFromBlock;
                import buildcraft.lib.internal.enums.EnumEngineType;
                import buildcraft.lib.item.MultiBlockItem;
                import net.minecraft.world.level.block.state.BlockState;
                import net.minecraft.world.level.Level;
                public class EngineVariantsProbe {
                    public static void main(String[] args) {
                        for (EnumEngineType type : EnumEngineType.values()) {
                            BCCoreItems.ENGINE_ITEM_MAP.put(type, new MultiBlockItem<>(type.name()));
                        }
                        var extractor = new RequiredExtractorItemFromBlock();
                        for (EnumEngineType type : EnumEngineType.values()) {
                            var state = new BlockState(new BlockEngine_BC8(), type);
                            var cost = extractor.extractItemsFromBlock(state, null, new Level());
                            if (cost.size() != 1 || cost.get(0).isEmpty()
                                || !cost.get(0).item.id.equals(type.name())) {
                                throw new AssertionError("Wrong engine variant cost: " + type);
                            }
                            if (!EngineItemVariants.fromState(state).item.id.equals(type.name())) {
                                throw new AssertionError("Wrong engine preview item: " + type);
                            }
                        }
                        System.out.println("5/5 engine variants passed");
                    }
                }''',
    }
    for target in ('1.21.1-neoforge', '1.21.11-neoforge'):
        with case.subTest(target=target), tempfile.TemporaryDirectory(prefix='bcce-engine-cost-') as tmp:
            directory = Path(tmp)
            for path, text in stubs.items():
                dest = directory / path
                dest.parent.mkdir(parents=True, exist_ok=True)
                dest.write_text(text)
            for logical in (ENGINE_ITEM, EXTRACTOR):
                dest = directory / logical.removeprefix('src/main/java/')
                dest.parent.mkdir(parents=True, exist_ok=True)
                dest.write_text(read_materialized_java(target, logical))
            files = [str(p) for p in directory.rglob('*.java')]
            classes = directory / 'classes'
            classes.mkdir()
            built = subprocess.run(['javac', '-d', str(classes), *files],
                                   text=True, capture_output=True, timeout=40)
            case.assertEqual(built.returncode, 0, built.stderr)
            ran = subprocess.run(['java', '-cp', str(classes), 'EngineVariantsProbe'],
                                 text=True, capture_output=True, timeout=15)
            case.assertEqual(ran.returncode, 0, ran.stderr)
            case.assertIn('5/5 engine variants passed', ran.stdout)


# --- old_pipe_connection_mask: executable regression from test_builders_old_pipe_preview_connections.py ---
def run_old_pipe_connection_mask(case):
    import re

    import shutil

    import subprocess

    import sys

    import tempfile

    import unittest

    from pathlib import Path

    ROOT = Path(__file__).resolve().parents[2]

    sys.path[:0] = [str(ROOT / 'scripts'), str(ROOT / 'scripts/tests')]

    from source_lookup import read_materialized_java, resolve_target_source

    from minecraft_compat_fixture import parse_sources

    TARGETS = ('1.19.2-forge', '1.20.1-forge')

    LOGICAL = 'src/main/java/buildcraft/builders/client/render/LegacyBlueprintPreviewScene.java'

    source = read_materialized_java(TARGETS[0], LOGICAL)
    expression = re.search(
        r'private static int previewConnectionBits\(Direction face, boolean pipeNeighbour\)\s*\{' r'[^}]*\}',
        source)
    case.assertIsNotNone(expression, 'Expected packed mask helper in effective source')
    test_source = '''public class PipePreviewMaskTest {
            enum Direction { DOWN, UP, NORTH, SOUTH, WEST, EAST }
            %s
            public static void main(String[] args) {
                for (Direction direction : Direction.values()) {
                    int shift = direction.ordinal() * 2;
                    int maskPipe = previewConnectionBits(direction, true);
                    int maskTile = previewConnectionBits(direction, false);
                    if (maskPipe != (1 << shift) || maskTile != (2 << shift))
                        throw new AssertionError("Incorrect side mask: " + direction);
                    if ((maskPipe & maskTile) != 0)
                        throw new AssertionError("Pipe/tile encodings overlap");
                }
                int threeWay = previewConnectionBits(Direction.NORTH, true)
                    | previewConnectionBits(Direction.SOUTH, true)
                    | previewConnectionBits(Direction.UP, false);
                if (threeWay != ((1 << 4) | (1 << 6) | (2 << 2)))
                    throw new AssertionError("Incorrect mixed three-way mask");
            }
        }''' % expression.group(0)
    with tempfile.TemporaryDirectory(prefix='bcce-preview-mask-') as temporary:
        src = Path(temporary) / 'PipePreviewMaskTest.java'
        src.write_text(test_source, encoding='utf-8')
        subprocess.run(['javac', str(src)], cwd=temporary, check=True,
                       capture_output=True, text=True, timeout=25)
        subprocess.run(['java', '-cp', temporary, 'PipePreviewMaskTest'],
                       cwd=temporary, check=True, capture_output=True, text=True, timeout=25)


# --- snapshot_response_gzip: executable regression from test_snapshot_response_gzip_framing.py ---
def run_snapshot_response_gzip(case):
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

    for target in ('1.21.1-neoforge', '1.21.11-neoforge', '26.1.2-neoforge', '26.2-neoforge', '26.3-neoforge'):
        with case.subTest(target=target), tempfile.TemporaryDirectory(prefix='bcce-gzip-') as temp:
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
            case.assertEqual(compile_result.returncode,0,compile_result.stderr)
            run_result=subprocess.run(['java','-cp',str(classes),
                'buildcraft.builders.snapshot.SnapshotGzipProbe'],
                capture_output=True,text=True,timeout=30)
            case.assertEqual(run_result.returncode,0,run_result.stderr)
            case.assertIn('snapshot gzip wire PASS:',run_result.stdout)


