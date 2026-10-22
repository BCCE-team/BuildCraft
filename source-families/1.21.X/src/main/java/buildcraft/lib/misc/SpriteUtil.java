//? source if >=1.21.1
/* Copyright (c) 2017-2026 the BuildCraft team. MPL-2.0. */
package buildcraft.lib.misc;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.mojang.authlib.GameProfile;
import buildcraft.lib.BCLibSprites;
import buildcraft.lib.client.sprite.SpriteRaw;
import buildcraft.lib.compat.RenderCompat;
import buildcraft.lib.internal.core.render.ISprite;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.PlayerSkin;

public class SpriteUtil {
    protected static TextureAtlasSprite MISSING_TEX;
    // Vanilla's lookup supplies a deterministic default while its asynchronous skin request is pending.
    // Cache lookups, not their initial placeholder texture: a face must update once the download completes.
    private static final LoadingCache<GameProfile, Supplier<PlayerSkin>> SKINS = CacheBuilder.newBuilder()
        .maximumSize(256).expireAfterAccess(5, TimeUnit.MINUTES)
        .build(new CacheLoader<GameProfile, Supplier<PlayerSkin>>() {
            @Override public Supplier<PlayerSkin> load(GameProfile profile) {
                Minecraft mc = Minecraft.getInstance();
                PlayerSkin defaultSkin = DefaultPlayerSkin.get(profile);
                Supplier<PlayerSkin> fallback = () -> defaultSkin;
                // Saved machine owners carry only UUID/name. Resolve their signed texture properties
                // off-thread instead of freezing a default face forever for an owner who is offline.
                var resolver = mc.services().profileResolver();
                CompletableFuture<Supplier<PlayerSkin>> lookup = CompletableFuture.supplyAsync(
                    () -> resolver.fetchById(profile.id()).orElse(profile), Util.nonCriticalIoPool())
                    .thenApplyAsync(resolved -> mc.getSkinManager().createLookup(resolved, true), mc)
                    .exceptionally(error -> fallback);
                return () -> lookup.getNow(fallback).get();
            }
        });

    public static void bindBlockTextureMap() { bindTexture(TextureAtlas.LOCATION_BLOCKS); }
    public static void bindTexture(String identifier) { bindTexture(Identifier.parse(identifier)); }
    public static void bindTexture(Identifier identifier) { RenderCompat.setShaderTexture(0, identifier); }
    public static Identifier transformLocation(Identifier location) {
        return Identifier.fromNamespaceAndPath(location.getNamespace(), "textures/" + location.getPath() + ".png");
    }

    @Nullable
    public static Identifier getSkinSpriteLocation(GameProfile profile) {
        if (profile == null || profile.id() == null || FakePlayerProvider.NULL_PROFILE.id().equals(profile.id())) return null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.getUUID().equals(profile.id())) {
            return mc.player.getSkin().body().texturePath();
        }
        // Connected players already have resolved, signed textures from the player-info packet.
        if (mc.getConnection() != null) {
            var info = mc.getConnection().getPlayerInfo(profile.id());
            if (info != null) return info.getSkin().body().texturePath();
        }
        try {
            return SKINS.getUnchecked(profile).get().body().texturePath();
        } catch (RuntimeException error) {
            // Offline services must not blank the ledger or prevent opening a machine GUI.
            return DefaultPlayerSkin.get(profile).body().texturePath();
        }
    }

    public static ISprite getFaceSprite(GameProfile profile) {
        if (profile == null) return BCLibSprites.HELP;
        Identifier location = getSkinSpriteLocation(profile);
        return location == null ? BCLibSprites.LOCK : new SpriteRaw(location, 8, 8, 8, 8, 64);
    }

    @Nullable
    public static ISprite getFaceOverlaySprite(GameProfile profile) {
        Identifier location = getSkinSpriteLocation(profile);
        return location == null ? null : new SpriteRaw(location, 40, 8, 8, 8, 64);
    }

    public static void clearAtlasCache() {
        MISSING_TEX = null;
        SKINS.invalidateAll();
    }
    public static TextureAtlasSprite missingSprite() {
        if (MISSING_TEX == null) MISSING_TEX = RenderCompat.blockSprites().apply(MissingTextureAtlasSprite.getLocation());
        return MISSING_TEX;
    }
}
