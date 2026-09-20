/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.misc;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import buildcraft.lib.internal.core.render.ISprite;
import buildcraft.lib.BCLibSprites;
import buildcraft.lib.client.sprite.SpriteRaw;
import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;

public class SpriteUtil {

    private static final ResourceLocation LOCATION_SKIN_LOADING = ResourceLocation.parse("skin:loading");
    protected static TextureAtlasSprite MISSING_TEX ;
    private static final Map<GameProfile, ResourceLocation> CACHED = new ConcurrentHashMap<>();
    private static final Set<GameProfile> LOADING = ConcurrentHashMap.newKeySet();

    public static void bindBlockTextureMap() {
        bindTexture(InventoryMenu.BLOCK_ATLAS);
    }

    public static void bindTexture(String identifier) {
        bindTexture(ResourceLocation.parse(identifier));
    }

    public static void bindTexture(ResourceLocation identifier) {
    	RenderSystem.setShaderTexture(0, identifier);
        //Minecraft.getInstance().textureManager.bindForSetup(identifier);
    }

    /** Transforms the given {@link ResourceLocation}, adding ".png" to the end and prepending that
     * {@link ResourceLocation#getResourcePath()} with "textures/", just like what {@link TextureMap} does. */
    public static ResourceLocation transformLocation(ResourceLocation location) {
        return ResourceLocation.fromNamespaceAndPath(location.getNamespace(), "textures/" + location.getPath() + ".png");
    }

    @Nullable
    public static ResourceLocation getSkinSpriteLocation(GameProfile profile) {
        ResourceLocation loc = getSkinSpriteLocation0(profile);
        return loc == LOCATION_SKIN_LOADING ? null : loc;
    }

    @Nullable
    private static ResourceLocation getSkinSpriteLocation0(GameProfile profile) {
        if (profile == null || profile.getId() == null || FakePlayerProvider.NULL_PROFILE.getId().equals(profile.getId())) {
            return null;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && profile.getId() != null && profile.getId().equals(mc.player.getUUID())) {
            return mc.player.getSkin().texture();
        }

        ResourceLocation cached = CACHED.get(profile);
        if (cached != null) {
            return cached;
        }

        try {
            if (LOADING.add(profile)) {
                GameProfile requestedProfile = profile;
                mc.getSkinManager().getOrLoad(profile).whenComplete((skin, error) -> {
                    LOADING.remove(requestedProfile);
                    if (error == null && skin != null) {
                        CACHED.put(requestedProfile, skin.texture());
                    }
                });
            }
            return LOCATION_SKIN_LOADING;
        } catch (RuntimeException exception) {
            buildcraft.lib.internal.debug.BCLog.logger.warn("Failed to load sprite data", exception);
            LOADING.remove(profile);
            return null;
        }
    }

    public static ISprite getFaceSprite(GameProfile profile) {
        if (profile == null) {
            return BCLibSprites.HELP;
        }
        ResourceLocation loc = getSkinSpriteLocation0(profile);
        if (loc == null) {
            return BCLibSprites.LOCK;
        }
        if (loc == LOCATION_SKIN_LOADING) {
            return BCLibSprites.LOADING;
        }
        return new SpriteRaw(loc, 8, 8, 8, 8, 64);
    }

    @Nullable
    public static ISprite getFaceOverlaySprite(GameProfile profile) {
        if (profile == null) {
            return null;
        }
        ResourceLocation loc = getSkinSpriteLocation0(profile);
        if (loc == null || loc == LOCATION_SKIN_LOADING) {
            return null;
        }
        return new SpriteRaw(loc, 40, 8, 8, 8, 64);
    }

    public static void clearAtlasCache() {
        MISSING_TEX = null;
    }

    public static TextureAtlasSprite missingSprite() {
        if(MISSING_TEX == null)
        	MISSING_TEX = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(MissingTextureAtlasSprite.getLocation());
        return MISSING_TEX;
    }
}
