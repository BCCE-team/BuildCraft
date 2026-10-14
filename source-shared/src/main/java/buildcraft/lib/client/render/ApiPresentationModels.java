package buildcraft.lib.client.render;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.client.ContentPresentation;
import buildcraft.api.v2.client.DynamicPresentation;
import buildcraft.api.v2.client.PipePresentation;
import buildcraft.api.v2.client.PresentationFrame;
import buildcraft.api.v2.machine.EngineHostProvider;
import buildcraft.api.v2.machine.MachineHostProvider;
import buildcraft.api.v2.pipe.PipeView;
import buildcraft.lib.client.model.ModelHolderStatic;
import buildcraft.lib.client.model.MutableQuad;
import buildcraft.lib.client.model.MutableVertex;
//? if <1.21.11 {
import net.minecraft.client.renderer.RenderType;
//?} else {
/*?
import buildcraft.lib.compat.RenderCompat;
?*/
//?}
import buildcraft.lib.internal.debug.BCLog;
import buildcraft.transport.pipe.Pipe;
import buildcraft.transport.tile.TilePipeHolder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
//? if <1.20 {
import com.mojang.math.Vector3f;
//?} else {
/*?
import com.mojang.math.Axis;
?*/
//?}
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
//? if <1.21.11 {
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.InventoryMenu;
//?}
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Bakes addon presentation assets through the normal model pipeline and renders only client-side snapshots. */
public final class ApiPresentationModels {
    private static final Map<String, ModelHolderStatic> MODELS = new LinkedHashMap<>();
    private static final Set<String> WARNED = new HashSet<>();
    private static final Map<String, List<String>> DYNAMIC_MODELS = new LinkedHashMap<>();

    private ApiPresentationModels() {}

    /** Must run before collecting additional models for baking; never register holders during rendering. */
    public static synchronized void prepareModels() {
        for (ContentPresentation content : BuildCraftApi.registry(BuildCraftRegistries.CLIENT_PRESENTATIONS).values()) {
            register(content.model());
        }
        for (PipePresentation pipe : BuildCraftApi.registry(BuildCraftRegistries.PIPE_PRESENTATIONS).values()) {
            register(pipe.base().model());
        }
        DYNAMIC_MODELS.clear();
        for (var entry : BuildCraftApi.registry(BuildCraftRegistries.DYNAMIC_PRESENTATIONS).entries()) {
            try {
                List<String> variants = new ArrayList<>();
                for (ResourceLocation id : entry.value().models()) {
                    if (id == null) continue;
                    register(id);
                    variants.add(id.toString());
                }
                variants.sort(String::compareTo);
                DYNAMIC_MODELS.put(entry.id().toString(), List.copyOf(variants));
            } catch (RuntimeException error) {
                warn("model-list", error);
            }
        }
    }

    private static void register(ResourceLocation id) {
        if (id != null) MODELS.computeIfAbsent(id.toString(), ModelHolderStatic::new);
    }

    /** Resolves a pre-baked model. Unknown models must not trigger baking on the render or chunk-mesh thread. */
    public static synchronized ModelHolderStatic model(Object id) {
        return id == null ? null : MODELS.get(id.toString());
    }

    public static ModelHolderStatic pipeBaseModel(PipeView view) {
        if (view == null) return null;
        try {
            ResourceLocation id = view.typeId();
            PipePresentation pipe = BuildCraftApi.registry(BuildCraftRegistries.PIPE_PRESENTATIONS).get(id);
            ContentPresentation content = pipe == null
                ? BuildCraftApi.registry(BuildCraftRegistries.CLIENT_PRESENTATIONS).get(id) : pipe.base();
            return content == null ? null : model(content.model());
        } catch (RuntimeException error) {
            warn("pipe-model", error);
            return null;
        }
    }

    /** A failed or empty bake must fall back to the original BuildCraft body, not make the pipe invisible. */
    public static boolean renderable(ModelHolderStatic baked) {
        return baked != null && baked.hasBakedQuads()
            && (baked.getCutoutQuads().length != 0 || baked.getTranslucentQuads().length != 0);
    }

    /** Dynamic pipe bodies are drawn only by the BER, to avoid terrain/BER double geometry. */
    public static boolean animatedPipe(PipeView view) {
        if (view == null) return false;
        try {
            ResourceLocation id = view.typeId();
            DynamicPresentation selector = BuildCraftApi.registry(BuildCraftRegistries.DYNAMIC_PRESENTATIONS).get(id);
            PipePresentation pipe = BuildCraftApi.registry(BuildCraftRegistries.PIPE_PRESENTATIONS).get(id);
            if (selector == null && (pipe == null || pipe.textureLayers().isEmpty())) return false;
            ModelHolderStatic base = pipeBaseModel(view);
            if (renderable(base)) return true;
            synchronized (ApiPresentationModels.class) {
                for (String variant : DYNAMIC_MODELS.getOrDefault(id.toString(), List.of())) {
                    if (renderable(MODELS.get(variant))) return true;
                }
            }
        } catch (RuntimeException error) {
            warn("pipe-dynamic", error);
        }
        return false;
    }

    public static Optional<PresentationFrame> pipeFrame(TilePipeHolder holder, float partialTick) {
        Pipe pipe = holder.getPipe();
        if (pipe == null || pipe == Pipe.EMPTY || !pipe.isClientSide()) return Optional.empty();
        try {
            Optional<PresentationFrame> result = BuildCraftApi.service(BuildCraftServices.CLIENT_PRESENTATIONS)
                .frame(pipe, partialTick);
            if (result.isPresent() && renderable(model(result.orElseThrow().model()))) return result;
            ModelHolderStatic base = pipeBaseModel(pipe);
            if (renderable(base)) {
                var fallback = result.orElse(PresentationFrame.empty());
                return Optional.of(new PresentationFrame(base.modelLocation, fallback.textureLayers(),
                    fallback.animations(), fallback.sound(), fallback.menu()));
            }
            synchronized (ApiPresentationModels.class) {
                for (String alternative : DYNAMIC_MODELS.getOrDefault(pipe.typeId().toString(), List.of())) {
                    ModelHolderStatic baked = MODELS.get(alternative);
                    if (renderable(baked)) {
                        return Optional.of(new PresentationFrame(baked.modelLocation,
                            result.map(PresentationFrame::textureLayers).orElse(List.of()),
                            result.map(PresentationFrame::animations).orElse(Map.of()),
                            result.map(PresentationFrame::sound).orElse(null),
                            result.map(PresentationFrame::menu).orElse(null)));
                    }
                }
            }
            return Optional.empty();
        } catch (RuntimeException error) {
            warn("pipe-frame", error);
            return Optional.empty();
        }
    }

    /** Used from an addon's ordinary block-entity renderer, with no access to server-side components. */
    public static Optional<PresentationFrame> hostFrame(BlockEntity owner, float partialTick) {
        if (owner == null || owner.getLevel() == null || !owner.getLevel().isClientSide()) return Optional.empty();
        try {
            var service = BuildCraftApi.service(BuildCraftServices.CLIENT_PRESENTATIONS);
            if (owner instanceof EngineHostProvider provider && provider.engineHost() != null) {
                return service.frame(provider.engineHost(), partialTick);
            }
            if (owner instanceof MachineHostProvider provider && provider.machineHost() != null) {
                return service.frame(provider.machineHost(), partialTick);
            }
        } catch (RuntimeException error) {
            warn("host-frame", error);
        }
        return Optional.empty();
    }

    public static boolean render(PresentationFrame frame, PoseStack pose, MultiBufferSource buffers,
        int light, int overlay) {
        ModelHolderStatic baked = frame == null ? null : model(frame.model());
        if (!renderable(baked)) return false;
        pose.pushPose();
        try {
            applyTransform(frame, pose);
            //? if <1.21.11 {
            var cutout = RenderType.cutout();
            var translucent = RenderType.translucent();
            //?} else {
            /*?
            var cutout = RenderCompat.cutout();
            var translucent = RenderCompat.translucent();
            ?*/
            //?}
            emit(baked.getCutoutQuads(), pose, buffers.getBuffer(cutout), null, 0, light, overlay);
            emit(baked.getTranslucentQuads(), pose, buffers.getBuffer(translucent), null, 0, light, overlay);
            if (!frame.textureLayers().isEmpty()) {
                VertexConsumer layerBuffer = buffers.getBuffer(translucent);
                for (int i = 0; i < frame.textureLayers().size(); i++) {
                    TextureAtlasSprite sprite = sprite(frame.textureLayers().get(i));
                    if (sprite == null) continue;
                    emit(baked.getCutoutQuads(), pose, layerBuffer, sprite, i + 1, light, overlay);
                    emit(baked.getTranslucentQuads(), pose, layerBuffer, sprite, i + 1, light, overlay);
                }
            }
            return true;
        } finally {
            pose.popPose();
        }
    }

    /** Native submit renderers own their own layer buffers, so never mix cutout and translucent vertices. */
    public static void renderLayer(PresentationFrame frame, PoseStack pose, VertexConsumer vertices,
        boolean translucent, int light, int overlay) {
        ModelHolderStatic baked = frame == null ? null : model(frame.model());
        if (!renderable(baked)) return;
        pose.pushPose();
        try {
            applyTransform(frame, pose);
            if (!translucent) {
                emit(baked.getCutoutQuads(), pose, vertices, null, 0, light, overlay);
            } else {
                emit(baked.getTranslucentQuads(), pose, vertices, null, 0, light, overlay);
                for (int i = 0; i < frame.textureLayers().size(); i++) {
                    TextureAtlasSprite sprite = sprite(frame.textureLayers().get(i));
                    if (sprite == null) continue;
                    emit(baked.getCutoutQuads(), pose, vertices, sprite, i + 1, light, overlay);
                    emit(baked.getTranslucentQuads(), pose, vertices, sprite, i + 1, light, overlay);
                }
            }
        } finally {
            pose.popPose();
        }
    }

    private static TextureAtlasSprite sprite(ResourceLocation id) {
        // Texture layers refer to sprites declared by the addon in the block atlas (or by its baked models).
        //? if <1.21.11 {
        TextureAtlasSprite sprite = Minecraft.getInstance().getModelManager()
            .getAtlas(InventoryMenu.BLOCK_ATLAS).getSprite(id);
        //?} else {
        /*?
        TextureAtlasSprite sprite = RenderCompat.blockSprites().apply(id);
        ?*/
        //?}
        //? if <1.21.11 {
        TextureAtlasSprite missing = Minecraft.getInstance().getModelManager()
            .getAtlas(InventoryMenu.BLOCK_ATLAS).getSprite(MissingTextureAtlasSprite.getLocation());
        //?} else {
        /*?
        TextureAtlasSprite missing = RenderCompat.blockSprites().apply(MissingTextureAtlasSprite.getLocation());
        ?*/
        //?}
        return sprite == null || sprite == missing ? null : sprite;
    }

    private static void emit(MutableQuad[] quads, PoseStack pose, VertexConsumer vertices,
        TextureAtlasSprite replacement, int layer, int light, int overlay) {
        for (MutableQuad quad : quads) {
            MutableQuad copy = new MutableQuad(quad);
            if (replacement != null) {
                TextureAtlasSprite previous = copy.getSprite();
                if (previous == null) continue;
                float u0 = previous.getU0(), v0 = previous.getV0();
                float ur = previous.getU1() - u0, vr = previous.getV1() - v0;
                if (ur == 0 || vr == 0) continue;
                float dx = (replacement.getU1() - replacement.getU0()) / ur;
                float dy = (replacement.getV1() - replacement.getV0()) / vr;
                for (MutableVertex vertex : copy.vertexs) {
                    vertex.tex_u = replacement.getU0() + (vertex.tex_u - u0) * dx;
                    vertex.tex_v = replacement.getV0() + (vertex.tex_v - v0) * dy;
                    // Avoid z-fighting for transparent colour/decoration layers.
                    float shift = layer * 0.0005F;
                    vertex.position_x += vertex.normal_x * shift;
                    vertex.position_y += vertex.normal_y * shift;
                    vertex.position_z += vertex.normal_z * shift;
                }
                copy.setSprite(replacement);
            }
            for (MutableVertex vertex : copy.vertexs) {
                vertex.lighti(light);
                vertex.overlay = overlay;
            }
            copy.multShade();
            copy.render(pose.last().pose(), pose.last().normal(), vertices);
        }
    }

    private static void applyTransform(PresentationFrame frame, PoseStack pose) {
        float x = channel(frame, "offset_x");
        float y = channel(frame, "offset_y");
        float z = channel(frame, "offset_z");
        if (has(frame, "offset_x") || has(frame, "offset_y") || has(frame, "offset_z")) {
            pose.translate(has(frame, "offset_x") ? x - 0.5F : 0,
                has(frame, "offset_y") ? y - 0.5F : 0, has(frame, "offset_z") ? z - 0.5F : 0);
        }
        //? if <1.20 {
        if (has(frame, "rotation_x")) pose.mulPose(Vector3f.XP.rotationDegrees(channel(frame, "rotation_x") * 360));
        if (has(frame, "rotation_y")) pose.mulPose(Vector3f.YP.rotationDegrees(channel(frame, "rotation_y") * 360));
        if (has(frame, "rotation_z")) pose.mulPose(Vector3f.ZP.rotationDegrees(channel(frame, "rotation_z") * 360));
        //?} else {
        /*?
        if (has(frame, "rotation_x")) pose.mulPose(Axis.XP.rotationDegrees(channel(frame, "rotation_x") * 360));
        if (has(frame, "rotation_y")) pose.mulPose(Axis.YP.rotationDegrees(channel(frame, "rotation_y") * 360));
        if (has(frame, "rotation_z")) pose.mulPose(Axis.ZP.rotationDegrees(channel(frame, "rotation_z") * 360));
        ?*/
        //?}
    }

    private static boolean has(PresentationFrame frame, String key) {
        String id = "buildcraft:" + key;
        for (var channel : frame.animations().keySet()) if (channel.toString().equals(id)) return true;
        return false;
    }

    private static float channel(PresentationFrame frame, String key) {
        String id = "buildcraft:" + key;
        for (var entry : frame.animations().entrySet()) {
            if (entry.getKey().toString().equals(id)) return entry.getValue().floatValue();
        }
        return 0.5F;
    }

    private static void warn(String context, RuntimeException ex) {
        synchronized (WARNED) {
            if (WARNED.add(context)) BCLog.logger.warn("API presentation {} failed", context, ex);
        }
    }
}
