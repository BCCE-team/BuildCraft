/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */
package buildcraft.lib.client.sprite;

import buildcraft.lib.platform.client.ClientAtlas;
import buildcraft.lib.internal.debug.BCDebugging;
import buildcraft.lib.internal.debug.BCLog;
import buildcraft.lib.internal.core.render.ISprite;
import buildcraft.lib.misc.SpriteUtil;
import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps stable references to sprites in the block atlas.
 *
 * <p>Since 1.20 the legacy pre-stitch event is no longer exposed. Sprite inclusion is therefore declared through
 * {@code assets/minecraft/atlases/blocks.json}; this registry only refreshes the resolved atlas entries after a reload.</p>
 */
public final class SpriteHolderRegistry {
    public static final boolean DEBUG = BCDebugging.shouldDebugLog("lib.sprite.holder");

    private static final Map<ResourceLocation, SpriteHolder> HOLDER_MAP = new ConcurrentHashMap<>();
    private static volatile boolean atlasHasBeenStitched;

    /** Classes which declare BuildCraft sprite holders. Strings keep optional modules optional. */
    private static final String[] BUILTIN_HOLDER_CLASSES = {
        "buildcraft.lib.BCLibSprites",
        "buildcraft.core.BCCoreSprites",
        "buildcraft.builders.BCBuildersSprites",
        "buildcraft.factory.BCFactorySprites",
        "buildcraft.robotics.BCRoboticsSprites",
        "buildcraft.silicon.BCSiliconSprites",
        "buildcraft.transport.BCTransportSprites",
        "buildcraft.transport.client.render.PipeWireRenderer"
    };

    private SpriteHolderRegistry() {
    }

    public static SpriteHolder getHolder(ResourceLocation location) {
        SpriteHolder holder = HOLDER_MAP.computeIfAbsent(location, SpriteHolder::new);
        if (atlasHasBeenStitched && holder.sprite == null) {
            holder.refresh(Minecraft.getInstance().getModelManager().getAtlas(InventoryMenu.BLOCK_ATLAS));
        }
        return holder;
    }

    public static SpriteHolder getHolder(String location) {
        return getHolder(ResourceLocation.parse(location));
    }

    /** Forces holder declarations to be initialised before the first model/atlas reload. */
    public static void bootstrapBuiltinHolders() {
        ClassLoader loader = SpriteHolderRegistry.class.getClassLoader();
        for (String className : BUILTIN_HOLDER_CLASSES) {
            try {
                Class.forName(className, true, loader);
            } catch (ClassNotFoundException ignored) {
                // BuildCraft modules are optional.
            } catch (LinkageError | RuntimeException error) {
                BCLog.logger.error("[lib.sprite.holder] Failed to initialise sprite holder class " + className, error);
            }
        }
    }

    public static void exportTextureMap() {
        if (!DEBUG) {
            return;
        }
        TextureAtlas map = Minecraft.getInstance().getModelManager().getAtlas(InventoryMenu.BLOCK_ATLAS);
        GlStateManager._bindTexture(map.getId());

        for (int level = 0; level < 32; level++) {
            int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, level, GL11.GL_TEXTURE_WIDTH);
            int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, level, GL11.GL_TEXTURE_HEIGHT);
            if (width <= 0 || height <= 0) {
                break;
            }
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            int totalSize = width * height;
            IntBuffer buffer = BufferUtils.createIntBuffer(totalSize);
            int[] pixels = new int[totalSize];
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, level, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, buffer);
            buffer.get(pixels);
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            image.setRGB(0, 0, width, height, pixels, 0, width);
            try {
                ImageIO.write(image, "png", new File("bc_spritemap_" + level + ".png"));
            } catch (IOException io) {
                BCLog.logger.warn("Unable to export BuildCraft texture atlas", io);
            }
        }
    }

    public static void onTextureStitchPost(ClientAtlas.After event) {
        TextureAtlas atlas = event.getAtlas();
        if (!InventoryMenu.BLOCK_ATLAS.equals(atlas.location())) {
            return;
        }

        bootstrapBuiltinHolders();
        TextureAtlasSprite missing = atlas.getSprite(MissingTextureAtlasSprite.getLocation());
        List<ResourceLocation> locations = new ArrayList<>(HOLDER_MAP.keySet());
        locations.sort(Comparator.comparing(ResourceLocation::toString));
        List<ResourceLocation> missingLocations = new ArrayList<>();

        for (ResourceLocation location : locations) {
            SpriteHolder holder = HOLDER_MAP.get(location);
            holder.refresh(atlas);
            if (isMissingSprite(holder.sprite, missing)) {
                missingLocations.add(location);
            }
        }
        atlasHasBeenStitched = true;

        if (!missingLocations.isEmpty()) {
            BCLog.logger.error("[lib.sprite.holder] {} BuildCraft sprites resolved to missingno:", missingLocations.size());
            for (ResourceLocation location : missingLocations) {
                BCLog.logger.error("[lib.sprite.holder]   {} (expected assets/{}/textures/{}.png)",
                    location, location.getNamespace(), location.getPath());
            }
        } else if (DEBUG) {
            BCLog.logger.info("[lib.sprite.holder] Refreshed {} stitched sprite holders", locations.size());
        }
    }

    private static boolean isMissingSprite(TextureAtlasSprite sprite, TextureAtlasSprite missing) {
        return sprite == null || sprite == missing
            || sprite.getU0() == missing.getU0() && sprite.getV0() == missing.getV0();
    }
    public static final class SpriteHolder implements ISprite {
        public final ResourceLocation spriteLocation;
        private volatile TextureAtlasSprite sprite;
        private boolean warnedBeforeStitch;

        private SpriteHolder(ResourceLocation spriteLocation) {
            this.spriteLocation = spriteLocation;
            if (DEBUG) {
                BCLog.logger.info("[lib.sprite.holder] Created holder for " + spriteLocation);
            }
        }

        private void refresh(TextureAtlas atlas) {
            sprite = atlas.getSprite(spriteLocation);
            warnedBeforeStitch = false;
        }

        private TextureAtlasSprite getSpriteChecking() {
            TextureAtlasSprite current = sprite;
            if (current == null) {
                TextureAtlas atlas = Minecraft.getInstance().getModelManager().getAtlas(InventoryMenu.BLOCK_ATLAS);
                current = atlas.getSprite(spriteLocation);
                sprite = current;
                if (!atlasHasBeenStitched && !warnedBeforeStitch) {
                    warnedBeforeStitch = true;
                    BCLog.logger.warn("[lib.sprite.holder] Sprite {} was requested before the block atlas finished loading", spriteLocation);
                }
            }
            return current;
        }

        public TextureAtlasSprite getSprite() {
            return getSpriteChecking();
        }

        @Override
        public float getInterpU(double u) {
            TextureAtlasSprite current = getSpriteChecking();
            return current == null ? (float) u : current.getU((float) u);
        }

        @Override
        public float getInterpV(double v) {
            TextureAtlasSprite current = getSpriteChecking();
            return current == null ? (float) v : current.getV((float) v);
        }

        @Override
        public void bindTexture() {
            SpriteUtil.bindBlockTextureMap();
        }
    }
}
