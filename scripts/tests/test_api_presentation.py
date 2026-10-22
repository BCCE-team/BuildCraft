#!/usr/bin/env python3
"""Addon models, client rendering and animation contracts."""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

import subprocess
import tempfile
import unittest
from source_preprocessor import preprocess_text

PRESENTATION_SRC = ROOT / "source-shared/src/main/java"

PRESENTATION_STUBS = {
    "net/minecraft/resources/ResourceLocation.java": '''package net.minecraft.resources;
public record ResourceLocation(String key) { public String toString(){return key;} }''',
    "net/minecraft/client/renderer/texture/TextureAtlasSprite.java": '''package net.minecraft.client.renderer.texture;
public class TextureAtlasSprite {
 public float getU0(){return 0;}public float getV0(){return 0;}
 public float getU1(){return 1;}public float getV1(){return 1;}
}''',
    "net/minecraft/client/renderer/texture/MissingTextureAtlasSprite.java": '''package net.minecraft.client.renderer.texture;
import net.minecraft.resources.ResourceLocation;
public class MissingTextureAtlasSprite {
 public static ResourceLocation getLocation(){return new ResourceLocation("minecraft:missingno");}
}''',
    "net/minecraft/client/Minecraft.java": '''package net.minecraft.client;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
public class Minecraft {
 public static Minecraft getInstance(){return new Minecraft();}
 public ModelManager getModelManager(){return new ModelManager();}
 public static class ModelManager {public Atlas getAtlas(ResourceLocation id){return new Atlas();}}
 public static class Atlas { public TextureAtlasSprite getSprite(ResourceLocation id){return new TextureAtlasSprite();} }
}''',
    "net/minecraft/world/inventory/InventoryMenu.java": '''package net.minecraft.world.inventory;
import net.minecraft.resources.ResourceLocation;
public class InventoryMenu {public static final ResourceLocation BLOCK_ATLAS=new ResourceLocation("minecraft:blocks");}''',
    "net/minecraft/world/level/Level.java": '''package net.minecraft.world.level;
public class Level {public boolean isClientSide(){return true;}}''',
    "net/minecraft/world/level/block/entity/BlockEntity.java": '''package net.minecraft.world.level.block.entity;
import net.minecraft.world.level.Level;
public class BlockEntity {public Level getLevel(){return new Level();}}''',
    "net/minecraft/client/renderer/RenderType.java": '''package net.minecraft.client.renderer;
public class RenderType {
 public static RenderType cutout(){return new RenderType();}
 public static RenderType translucent(){return new RenderType();}
}''',
    "net/minecraft/client/renderer/MultiBufferSource.java": '''package net.minecraft.client.renderer;
import com.mojang.blaze3d.vertex.VertexConsumer;
public interface MultiBufferSource {VertexConsumer getBuffer(Object type);}''',
    "com/mojang/blaze3d/vertex/VertexConsumer.java": '''package com.mojang.blaze3d.vertex;
public interface VertexConsumer {}''',
    "com/mojang/blaze3d/vertex/PoseStack.java": '''package com.mojang.blaze3d.vertex;
public class PoseStack {
 public void pushPose(){} public void popPose(){}
 public void translate(double x,double y,double z){} public void mulPose(Object quaternion){}
 public Pose last(){return new Pose();}
 public static class Pose {public Object pose(){return new Object();}public Object normal(){return new Object();}}
}''',
    "com/mojang/math/Vector3f.java": '''package com.mojang.math;
public class Vector3f {public static final Vector3f XP=new Vector3f(),YP=new Vector3f(),ZP=new Vector3f();
public Object rotationDegrees(float value){return new Object();}}''',
    "com/mojang/math/Axis.java": '''package com.mojang.math;
public class Axis {public static final Axis XP=new Axis(),YP=new Axis(),ZP=new Axis();
public Object rotationDegrees(float value){return new Object();}}''',
    "buildcraft/lib/client/model/MutableVertex.java": '''package buildcraft.lib.client.model;
public class MutableVertex { public float tex_u,tex_v,normal_x,normal_y,normal_z,position_x,position_y,position_z; public int overlay; public MutableVertex lighti(int value){return this;} }''',
    "buildcraft/lib/client/model/MutableQuad.java": '''package buildcraft.lib.client.model;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
public class MutableQuad {
 public static int rendered;public final MutableVertex[] vertexs={new MutableVertex()};
 public MutableQuad(){}public MutableQuad(MutableQuad from){}
 public MutableQuad multShade(){return this;}
 public void render(Object matrix,Object normals,VertexConsumer out){rendered++;}
 public TextureAtlasSprite getSprite(){return new TextureAtlasSprite();}
 public void setSprite(TextureAtlasSprite texture){}
}''',
    "buildcraft/lib/client/model/ModelHolderStatic.java": '''package buildcraft.lib.client.model;
import net.minecraft.resources.ResourceLocation;
public class ModelHolderStatic {
 public final ResourceLocation modelLocation;
 public ModelHolderStatic(String id){this.modelLocation=new ResourceLocation(id);}
 public boolean hasBakedQuads(){return true;}
 public MutableQuad[] getCutoutQuads(){return new MutableQuad[]{new MutableQuad()};}
 public MutableQuad[] getTranslucentQuads(){return new MutableQuad[0];}
}''',
    "buildcraft/lib/compat/RenderCompat.java": '''package buildcraft.lib.compat;
import java.util.function.Function;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
public class RenderCompat {
 public static Object cutout(){return new Object();}public static Object translucent(){return new Object();}
 public static Function<ResourceLocation,TextureAtlasSprite> blockSprites(){return id->new TextureAtlasSprite();}
}''',
    "buildcraft/lib/internal/debug/BCLog.java": '''package buildcraft.lib.internal.debug;
public class BCLog {public static final Logger logger=new Logger();
public static class Logger {public void warn(String x,Object...args) {}}}''',
    "buildcraft/api/v2/pipe/PipeView.java": '''package buildcraft.api.v2.pipe;
import net.minecraft.resources.ResourceLocation;
public interface PipeView {ResourceLocation typeId();boolean isClientSide();}''',
    "buildcraft/api/v2/machine/MachineExecutionContext.java": '''package buildcraft.api.v2.machine;
public interface MachineExecutionContext {}''',
    "buildcraft/api/v2/machine/MachineHost.java": '''package buildcraft.api.v2.machine;
public interface MachineHost extends MachineExecutionContext {}''',
    "buildcraft/api/v2/machine/EngineHost.java": '''package buildcraft.api.v2.machine;
public interface EngineHost extends MachineHost {}''',
    "buildcraft/api/v2/machine/MachineHostProvider.java": '''package buildcraft.api.v2.machine;
public interface MachineHostProvider {MachineHost machineHost();}''',
    "buildcraft/api/v2/machine/EngineHostProvider.java": '''package buildcraft.api.v2.machine;
public interface EngineHostProvider extends MachineHostProvider {EngineHost engineHost();}''',
    "buildcraft/api/v2/client/PresentationFrame.java": '''package buildcraft.api.v2.client;
import java.util.*;import net.minecraft.resources.ResourceLocation;
public record PresentationFrame(ResourceLocation model,List<ResourceLocation> textureLayers,
Map<ResourceLocation,Double> animations,ResourceLocation sound, Object menu) {
 public static PresentationFrame empty(){return new PresentationFrame(null,List.of(),Map.of(),null,null);}
}''',
    "buildcraft/api/v2/client/ContentPresentation.java": '''package buildcraft.api.v2.client;
import net.minecraft.resources.ResourceLocation;
public record ContentPresentation(ResourceLocation model) {}''',
    "buildcraft/api/v2/client/PipePresentation.java": '''package buildcraft.api.v2.client;
import java.util.*;import net.minecraft.resources.ResourceLocation;
public record PipePresentation(ContentPresentation base,List<ResourceLocation> textureLayers) {}''',
    "buildcraft/api/v2/client/ClientPresentationService.java": '''package buildcraft.api.v2.client;
import buildcraft.api.v2.pipe.PipeView;
import buildcraft.api.v2.machine.*;import java.util.*;
public interface ClientPresentationService {
 Optional<PresentationFrame> frame(PipeView pipe,float tick);
 Optional<PresentationFrame> frame(EngineHost engine,float tick);
 Optional<PresentationFrame> frame(MachineHost machine,float tick);
}''',
    "buildcraft/api/v2/BuildCraftRegistries.java": '''package buildcraft.api.v2;
import buildcraft.api.v2.client.*;
public class BuildCraftRegistries {
 public static final Key<ContentPresentation> CLIENT_PRESENTATIONS=new Key<>();
 public static final Key<PipePresentation> PIPE_PRESENTATIONS=new Key<>();
 public static final Key<DynamicPresentation> DYNAMIC_PRESENTATIONS=new Key<>();
 public static class Key<T> {}
}''',
    "buildcraft/api/v2/BuildCraftServices.java": '''package buildcraft.api.v2;
import buildcraft.api.v2.client.ClientPresentationService;
public class BuildCraftServices {
 public static final Key<ClientPresentationService> CLIENT_PRESENTATIONS=new Key<>();
 public static class Key<T>{}
}''',
    "buildcraft/api/v2/BuildCraftApi.java": '''package buildcraft.api.v2;
import java.util.*;import net.minecraft.resources.ResourceLocation;
import buildcraft.api.v2.client.*;import buildcraft.api.v2.pipe.*;import buildcraft.api.v2.machine.*;
public class BuildCraftApi {
 public static final Map<Object,Registry<?>> REGISTRIES=new HashMap<>();
 @SuppressWarnings("unchecked")public static <T> Registry<T> registry(BuildCraftRegistries.Key<T> k){
 return (Registry<T>)REGISTRIES.computeIfAbsent(k,x->new Registry<>());}
 public record Entry<T>(ResourceLocation id,T value){}
 public static class Registry<T> {
  private final Map<ResourceLocation,T> values=new LinkedHashMap<>();
  public void register(ResourceLocation id,T item){values.put(id,item);}
  public T get(ResourceLocation id){return values.get(id);}
  public Collection<T> values(){return values.values();}
  public Collection<Entry<T>> entries(){List<Entry<T>> result=new ArrayList<>();
   values.forEach((id,item)->result.add(new Entry<>(id,item)));return result;}
 }
 @SuppressWarnings("unchecked")public static <T> T service(BuildCraftServices.Key<T> k){return (T)new ClientPresentationService(){
 public Optional<PresentationFrame> frame(PipeView p,float tick){return Optional.of(new PresentationFrame(
 new ResourceLocation("addon:unbaked"),List.of(),Map.of(),null,null));}
 public Optional<PresentationFrame> frame(EngineHost e,float tick){return Optional.empty();}
 public Optional<PresentationFrame> frame(MachineHost m,float tick){return Optional.empty();}
 };}
}''',
    "buildcraft/transport/pipe/Pipe.java": '''package buildcraft.transport.pipe;
import buildcraft.api.v2.pipe.PipeView;
import net.minecraft.resources.ResourceLocation;
public class Pipe implements PipeView {
 public static final Pipe EMPTY=new Pipe(new ResourceLocation("buildcraft:empty"),false);
 private final ResourceLocation id;private final boolean client;
 public Pipe(ResourceLocation id,boolean client){this.id=id;this.client=client;}
 public ResourceLocation typeId(){return id;} public boolean isClientSide(){return client;}
}''',
    "buildcraft/transport/tile/TilePipeHolder.java": '''package buildcraft.transport.tile;
import buildcraft.transport.pipe.Pipe;
public class TilePipeHolder {private final Pipe pipe;
public TilePipeHolder(Pipe pipe){this.pipe=pipe;}public Pipe getPipe(){return pipe;}}
''',
}

PRESENTATION_FIXTURE = '''package fixture;
import java.util.*;
import net.minecraft.resources.ResourceLocation;
import buildcraft.api.v2.*;
import buildcraft.api.v2.client.*;
import buildcraft.lib.client.render.ApiPresentationModels;
import buildcraft.lib.client.model.MutableQuad;
import buildcraft.transport.pipe.Pipe;
import buildcraft.transport.tile.TilePipeHolder;
import com.mojang.blaze3d.vertex.*;
public class ClientPresentationFixture {
 static ResourceLocation id(String name){return new ResourceLocation("addon:"+name);}
 static void check(boolean test,String msg){if(!test)throw new AssertionError(msg);}
 public static void main(String[] args) {
  BuildCraftApi.registry(BuildCraftRegistries.CLIENT_PRESENTATIONS).register(id("pipe"),
    new ContentPresentation(id("base")));
  BuildCraftApi.registry(BuildCraftRegistries.DYNAMIC_PRESENTATIONS).register(id("pipe"),
    new DynamicPresentation() {public Set<ResourceLocation> models(){return Set.of(id("lit"));}});
  ApiPresentationModels.prepareModels();
  check(ApiPresentationModels.model(id("base"))!=null,"static asset registered before bake");
  check(ApiPresentationModels.model(id("lit"))!=null,"dynamic model variant registered before bake");
  var pipe=new Pipe(id("pipe"),true);
  check(ApiPresentationModels.animatedPipe(pipe),"pipe uses dynamic BER geometry");
  var selected=ApiPresentationModels.pipeFrame(new TilePipeHolder(pipe),.5f).orElseThrow();
  check(selected.model().equals(id("base")),"invalid frame falls back to baked base");
  check(ApiPresentationModels.renderable(ApiPresentationModels.model(id("base"))),"baked model available");
  var pose=new PoseStack();
  check(ApiPresentationModels.render(selected, pose, type -> new VertexConsumer(){}, 0x00F000F0, 0),
      "the legacy render adapter emitted baked geometry");
  ApiPresentationModels.renderLayer(selected,pose,new VertexConsumer(){},false,0x00F000F0,0);
  check(MutableQuad.rendered>0,"the native render adapter emitted baked model geometry");
  check(ApiPresentationModels.pipeFrame(new TilePipeHolder(new Pipe(id("pipe"),false)),.5f).isEmpty(),
      "server states never enter render adapter");
 }
}'''


def presentation_write(base: Path, name: str, data: str) -> Path:
    p = base / name
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(data, encoding="utf-8")
    return p


class ClientPresentationConformanceTest(unittest.TestCase):
    def test_addon_assets_and_native_render_adapters(self):
        source = (PRESENTATION_SRC / "buildcraft/lib/client/render/ApiPresentationModels.java").read_text()
        for version, family, platform in (("1.19.2", "old", "forge"),
                                          ("1.20.1", "old", "forge"),
                                          ("1.21.1", "1.21.X", "neoforge"),
                                          ("1.21.11", "1.21.X", "neoforge"),
                                          ("26.1.2", "26.X", "neoforge"),
                                          ("26.2", "26.X", "neoforge"),
                                          ("26.3", "26.X", "neoforge")):
            with self.subTest(target=version), tempfile.TemporaryDirectory(prefix="bcce-client-presentation-") as temp:
                root = Path(temp)
                files = [presentation_write(root, name, stub) for name, stub in PRESENTATION_STUBS.items()
                         if version in ("1.21.11", "26.1.2", "26.2", "26.3")
                         or name != "buildcraft/lib/compat/RenderCompat.java"]
                files.append(presentation_write(root, "buildcraft/api/v2/client/DynamicPresentation.java",
                                   (PRESENTATION_SRC / "buildcraft/api/v2/client/DynamicPresentation.java").read_text()))
                files.append(presentation_write(root, "buildcraft/lib/client/render/ApiPresentationModels.java",
                                   preprocess_text(source, minecraft=version, family=family,
                                                   platform=platform, source="ApiPresentationModels.java")))
                files.append(presentation_write(root, "fixture/ClientPresentationFixture.java", PRESENTATION_FIXTURE))
                classes = root / "classes"
                classes.mkdir()
                javac = subprocess.run(["javac", "-d", str(classes), *map(str, files)],
                                       text=True, capture_output=True, timeout=35)
                self.assertEqual(javac.returncode, 0, javac.stderr)
                run = subprocess.run(["java", "-cp", str(classes), "fixture.ClientPresentationFixture"],
                                     text=True, capture_output=True, timeout=10)
                self.assertEqual(run.returncode, 0, run.stderr)

    def test_client_rendering_entrypoints(self):
        from source_config import load_properties, target_ids, target_layout
        from source_layout import effective_source_files
        config = load_properties()
        for target in target_ids(config):
            with self.subTest(target=target):
                files = effective_source_files(target_layout(target, config), config)
                selected = {path.removeprefix("src/main/java/"): src for path, src in files.items()}
                for name, marker in (
                    ("buildcraft/core/client/render/RenderEngine_BC8.java", "ApiPresentationModels.hostFrame"),
                    ("buildcraft/lib/client/model/ModelHolderRegistry.java", "ApiPresentationModels.prepareModels()"),
                    ("buildcraft/transport/client/render/RenderPipeHolder.java", "ApiPresentationModels.pipeFrame"),
                ):
                    self.assertIn(name, selected)
                    self.assertIn(marker, selected[name].read_text(), str(selected[name]))
                model = "buildcraft/transport/client/model/ModelPipe.java" if target.startswith(("1.19", "1.20", "1.21")) and "1.21.11" not in target else "buildcraft/transport/client/model/ModelPipeNative2612.java"
                if target == "1.21.11-neoforge":
                    model = "buildcraft/transport/client/model/ModelPipeNative121111.java"
                self.assertIn(model, selected)
                self.assertIn("ApiPresentationModels.pipeBaseModel", selected[model].read_text(), model)
                self.assertIn("buildcraft/lib/client/render/ApiPresentationModels.java", selected)
                # Legacy BERs must forward both Minecraft light and overlay to the client presentation renderer.
                # A stale three-argument call compiles in standalone API fixtures but fails in Gradle.
                if target in ("1.19.2-forge", "1.20.1-forge", "1.21.1-neoforge"):
                    holder = selected["buildcraft/transport/client/render/RenderPipeHolder.java"].read_text()
                    self.assertRegex(
                        holder,
                        r"ApiPresentationModels\.render\(\s*frame\s*,\s*matrix\s*,\s*buffer\s*,"
                        r"\s*combinedLight\s*,\s*combinedOverlay\s*\)",
                        f"{target}: renderer call must pass light and overlay",
                    )
                # 1.21.11 and 26.X use submit-layer rendering rather than legacy BER.
                # Both passes must forward the light and overlay arguments to the
                # six-argument method; a stale four-argument call fails compileJava.
                if target in ("1.21.11-neoforge", "26.1.2-neoforge", "26.2-neoforge", "26.3-neoforge"):
                    holder = selected["buildcraft/transport/client/render/RenderPipeHolder.java"].read_text()
                    for layer, translucent in (("cutout", "false"), ("translucent", "true")):
                        self.assertRegex(
                            holder,
                            rf"ApiPresentationModels\.renderLayer\(\s*frame\s*,\s*pose\s*,"
                            rf"\s*buffers\.getBuffer\(RenderCompat\.{layer}\(\)\)\s*,"
                            rf"\s*{translucent}\s*,\s*light\s*,\s*overlay\s*\)",
                            f"{target}: {layer} pass must provide light and overlay",
                        )

if __name__ == "__main__":
    unittest.main()
