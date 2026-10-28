/* Copyright (c) the BuildCraft team. SPDX-License-Identifier: MPL-2.0 */
package buildcraft.lib.client.render.material;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.List;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.serialization.MapCodec;

import buildcraft.lib.BCLib;
import buildcraft.lib.internal.debug.BCLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.atlas.SpriteSource;
import net.minecraft.client.renderer.texture.atlas.SpriteSourceType;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.inventory.InventoryMenu;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterSpriteSourceTypesEvent;

/**
 * Minecraft 1.21.1 terrain consumers can discard baked-quad vertex alpha. In particular,
 * opaque alpha in the chunk buffer makes glass facades, lens surfaces and painted-pipe
 * overlays incorrect in Iris as well as some non-Iris chunk renderers.
 *
 * Fix the MATERIAL rather than submitting duplicate geometry through a block-entity or
 * world-last pass: the custom sprite source makes alpha-reduced copies of only the required
 * images. Quads keep their original positions, normals, culling, lighting and render layer.
 * The original sprites remain unchanged for ordinary blocks, inventory models and addons.
 */
@EventBusSubscriber(modid = BCLib.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class LegacyMaterialAlpha {
    private static final int[] MATERIAL_ALPHAS = { 51, 64, 144 };
    private static final SpriteSourceType SOURCE_TYPE = new SpriteSourceType(MapCodec.unit(new AlphaSpriteSource()));
    private static final String SPRITE_PREFIX = "material_alpha/";
    // Cached quads live only as long as their existing model-cache owners. Weak keys
    // avoid leaking old baked models across resource reloads or temporary API quads.
    private static final Map<BakedQuad, BakedQuad> REWRITTEN =
        Collections.synchronizedMap(new WeakHashMap<>());

    private LegacyMaterialAlpha() {}

    @SubscribeEvent
    public static void registerSpriteSource(RegisterSpriteSourceTypesEvent event) {
        event.register(ResourceLocation.fromNamespaceAndPath(BCLib.MODID, "material_alpha"), SOURCE_TYPE);
    }

    /** Return the original quad on any unsupported texture or missing generated atlas sprite. */
    public static BakedQuad rewrite(BakedQuad original) {
        if (original == null || original.getSprite() == null) return original;
        BakedQuad cached = REWRITTEN.get(original);
        if (cached != null) return cached;
        int[] vertices = original.getVertices();
        if (vertices == null || vertices.length < 32 || vertices.length % 4 != 0) return original;
        int stride = vertices.length / 4;
        if (stride < 6) return original;

        int alpha = (vertices[3] >>> 24) & 255;
        if (!isMaterialAlpha(alpha)) return original;
        for (int index = 1; index < 4; index++) {
            if (((vertices[index * stride + 3] >>> 24) & 255) != alpha) return original;
        }
        TextureAtlasSprite source = original.getSprite();
        ResourceLocation sourceId = source.contents().name();
        if (!supportsSprite(sourceId)) return original;
        ResourceLocation targetId = spriteId(sourceId, alpha);
        TextureAtlasSprite replacement = Minecraft.getInstance()
            .getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(targetId);
        if (replacement == null || replacement.contents().name().equals(MissingTextureAtlasSprite.getLocation())) {
            // Avoid corrupting the geometry if the atlas source has not been installed yet.
            return original;
        }
        float du = source.getU1() - source.getU0();
        float dv = source.getV1() - source.getV0();
        if (du == 0 || dv == 0) return original;
        float newDu = replacement.getU1() - replacement.getU0();
        float newDv = replacement.getV1() - replacement.getV0();
        int[] data = vertices.clone();
        for (int i = 0; i < 4; i++) {
            int offset = i * stride;
            float u = Float.intBitsToFloat(data[offset + 4]);
            float v = Float.intBitsToFloat(data[offset + 5]);
            data[offset + 4] = Float.floatToRawIntBits(replacement.getU0() + (u - source.getU0()) / du * newDu);
            data[offset + 5] = Float.floatToRawIntBits(replacement.getV0() + (v - source.getV0()) / dv * newDv);
            // Alpha is now in the texture. No dependence on chunk renderer's vertex alpha handling.
            data[offset + 3] = (data[offset + 3] & 0x00FFFFFF) | 0xFF000000;
        }
        BakedQuad result = new BakedQuad(data, original.getTintIndex(), original.getDirection(),
            replacement, original.isShade());
        REWRITTEN.put(original, result);
        return result;
    }

    public static void clearRewriteCache() {
        REWRITTEN.clear();
    }

    public static List<BakedQuad> rewriteAll(List<BakedQuad> quads) {
        if (quads.isEmpty()) return quads;
        List<BakedQuad> result = new ArrayList<>(quads.size());
        for (BakedQuad quad : quads) result.add(rewrite(quad));
        return List.copyOf(result);
    }

    private static boolean isMaterialAlpha(int alpha) {
        for (int supported : MATERIAL_ALPHAS) if (supported == alpha) return true;
        return false;
    }

    public static ResourceLocation spriteId(ResourceLocation source, int alpha) {
        return ResourceLocation.fromNamespaceAndPath(BCLib.MODID,
            SPRITE_PREFIX + alpha + "/" + source.getNamespace() + "/" + source.getPath());
    }

    private static boolean supportsSprite(ResourceLocation id) {
        String ns = id.getNamespace();
        String path = id.getPath();
        if (ns.equals("minecraft") && path.startsWith("block/")) {
            // In-world glass facades copy the ordinary stained/clear glass texture.
            return path.equals("block/glass") || path.endsWith("_stained_glass");
        }
        if (ns.equals("buildcrafttransport")) {
            return path.equals("pipes/overlay_stained");
        }
        if (ns.equals("buildcraftsilicon")) {
            // Lens/filter cutout frames are NOT rewritten; only their translucent quads use this sprite.
            return path.equals("plugs/lens") || path.equals("plugs/filter")
                || path.equals("plugs/overlay_lens");
        }
        return false;
    }

    private static final class AlphaSpriteSource implements SpriteSource {
        @Override
        public SpriteSourceType type() {
            return SOURCE_TYPE;
        }

        @Override
        public void run(ResourceManager resourceManager, Output output) {
            // Materialise variants from the currently selected resource pack, not embedded copies
            // of Mojang/BuildCraft textures. This also respects user texture-pack replacements.
            resourceManager.listResources("textures", key -> key.getPath().endsWith(".png"))
                .forEach((fileId, resource) -> {
                    String path = fileId.getPath();
                    if (!path.startsWith("textures/") || !path.endsWith(".png")) return;
                    ResourceLocation original = ResourceLocation.fromNamespaceAndPath(
                        fileId.getNamespace(), path.substring("textures/".length(), path.length() - 4));
                    if (!supportsSprite(original)) return;
                    for (int alpha : MATERIAL_ALPHAS) {
                        ResourceLocation destination = spriteId(original, alpha);
                        output.add(destination, loader -> loadAlphaSprite(destination, resource, alpha));
                    }
                });
        }

        private static SpriteContents loadAlphaSprite(ResourceLocation destination, Resource resource, int alpha) {
            NativeImage image = null;
            try (var input = resource.open()) {
                image = NativeImage.read(input);
                if (image.getWidth() <= 0 || image.getHeight() <= 0) {
                    image.close();
                    return null;
                }
                for (int y = 0; y < image.getHeight(); y++) {
                    for (int x = 0; x < image.getWidth(); x++) {
                        // NativeImage packs alpha in the highest byte; preserve all original RGB values.
                        int pixel = image.getPixelRGBA(x, y);
                        int existingAlpha = (pixel >>> 24) & 255;
                        int materialAlpha = (existingAlpha * alpha + 127) / 255;
                        image.setPixelRGBA(x, y, (pixel & 0x00FFFFFF) | (materialAlpha << 24));
                    }
                }
                var metadata = resource.metadata();
                FrameSize size = metadata.getSection(AnimationMetadataSection.SERIALIZER)
                    .orElse(AnimationMetadataSection.EMPTY)
                    .calculateFrameSize(image.getWidth(), image.getHeight());
                return new SpriteContents(destination, size, image, metadata);
            } catch (IOException | RuntimeException error) {
                if (image != null) image.close();
                BCLog.logger.warn("Unable to prepare BuildCraft material-alpha sprite {}", destination, error);
                return null;
            }
        }
    }
}
