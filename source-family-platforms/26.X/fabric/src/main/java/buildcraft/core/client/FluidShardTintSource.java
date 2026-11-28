/*
 * Copyright (c) 2026 the BuildCraft Community Edition contributors.
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.core.client;

import buildcraft.core.item.ItemFragileFluidContainer;
import buildcraft.lib.fluid.FluidStack;
import com.mojang.serialization.MapCodec;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.client.fluid.FluidVariantRendering;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.item.ItemTintSource;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.WeakHashMap;
import javax.imageio.ImageIO;

/**
 * Fabric equivalent of the old NeoForge fluid-container item tint.
 * The translucent shard mask is a separate generated-model layer, and its
 * colour follows the fluid (including its Data Components) stored in the item.
 */
public final class FluidShardTintSource implements ItemTintSource {
    public static final MapCodec<FluidShardTintSource> MAP_CODEC =
        MapCodec.unit(FluidShardTintSource::new);

    // Cache the sampled texture, not the final tint: different Data Components can
    // produce different colors for the same sprite. Weak keys also avoid retaining
    // old atlases after a resource reload.
    private static final Map<TextureAtlasSprite, Integer> TEXTURE_COLORS = new WeakHashMap<>();

    @Override
    public int calculate(ItemStack stack, @Nullable ClientLevel level, @Nullable LivingEntity owner) {
        FluidStack fluid = ItemFragileFluidContainer.getFluid(stack);
        if (fluid.isEmpty()) {
            // Hide the fluid mask when the shard is empty; the glass frame remains.
            return 0x00FFFFFF;
        }
        FluidVariant variant = FluidVariant.of(fluid.getFluid(), fluid.getComponentsPatch());
        int tint = FluidVariantRendering.getColor(variant);
        // BCCE petroleum (and e.g. vanilla lava) has pre-colored textures. Its
        // render tint is white, so tint alone would leave the shard mask white.
        return FluidShardColors.multiplyRgb(textureColor(variant), tint);
    }

    private static int textureColor(FluidVariant variant) {
        Minecraft client = Minecraft.getInstance();
        try {
            var model = client.getModelManager().getFluidStateModelSet()
                .get(variant.getFluid().defaultFluidState());
            TextureAtlasSprite sprite = model.stillMaterial().sprite();
            if (sprite == null || MissingTextureAtlasSprite.getLocation().equals(sprite.contents().name())) {
                return 0xFFFFFFFF;
            }
            synchronized (TEXTURE_COLORS) {
                return TEXTURE_COLORS.computeIfAbsent(sprite, FluidShardTintSource::readTextureColor);
            }
        } catch (RuntimeException unavailableDuringReload) {
            // No baked model yet; keep the fluid's own tint and retry next render.
            return 0xFFFFFFFF;
        }
    }

    private static int readTextureColor(TextureAtlasSprite sprite) {
        Identifier id = sprite.contents().name();
        Identifier png = Identifier.fromNamespaceAndPath(id.getNamespace(), "textures/" + id.getPath() + ".png");
        var resource = Minecraft.getInstance().getResourceManager().getResource(png);
        if (resource.isEmpty()) return 0xFFFFFFFF; // e.g. a synthetic atlas sprite
        // Read the active resource pack, not a hardcoded palette or GPU pixels.
        // ImageIO works even after Minecraft has closed the atlas upload images.
        try (InputStream stream = resource.get().open()) {
            var image = ImageIO.read(stream);
            return image == null ? 0xFFFFFFFF : FluidShardColors.averageTexture(image);
        } catch (IOException ignored) {
            return 0xFFFFFFFF;
        }
    }

    /** The Fabric atlas lifecycle calls this after F3+T / resource-pack reload. */
    public static void clearTextureColors() {
        synchronized (TEXTURE_COLORS) {
            TEXTURE_COLORS.clear();
        }
    }

    @Override
    public MapCodec<? extends ItemTintSource> type() {
        return MAP_CODEC;
    }
}
