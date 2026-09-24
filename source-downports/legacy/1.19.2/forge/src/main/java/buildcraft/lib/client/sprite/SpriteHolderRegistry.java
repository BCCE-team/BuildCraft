/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.client.sprite;

import buildcraft.lib.platform.client.ClientAtlas;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.imageio.ImageIO;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

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
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.ModLoader;

@OnlyIn(Dist.CLIENT)
public class SpriteHolderRegistry {
    public static final boolean DEBUG = BCDebugging.shouldDebugLog("lib.sprite.holder");

    private static final Map<ResourceLocation, SpriteHolder> HOLDER_MAP = new HashMap<>();

    /**
     * Sprite holders are created by several BuildCraft modules that each have their own MOD event bus.
     * Forge does not provide a stable ordering between those buses, so BCLib may receive the stitch event
     * before another module has initialised its holder class. Keep the current event available until Post:
     * a holder created by a later module listener can then register itself immediately.
     */
    private static ClientAtlas.Before activeStitchEvent;
    private static final Set<ResourceLocation> REGISTERED_THIS_STITCH = new HashSet<>();
    private static int stitchGeneration;
    private static boolean atlasHasBeenStitched;

    /**
     * Every class in the current project that creates {@link SpriteHolder}s. Initialising them at the start
     * of a stitch prevents a holder from first being created when an obscure GUI or renderer is opened.
     * Names are strings deliberately: BCLib can still run when an optional BuildCraft module is absent.
     */
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

    public static SpriteHolder getHolder(ResourceLocation location) {
        SpriteHolder holder = HOLDER_MAP.get(location);
        if (holder == null) {
            holder = new SpriteHolder(location);
            HOLDER_MAP.put(location, holder);
            if (DEBUG) {
                BCLog.logger.info("[lib.sprite.holder] Created a new sprite holder for " + location);
            }
        } else if (DEBUG) {
            BCLog.logger.info("[lib.sprite.holder] Returned existing sprite holder for " + location);
        }

        // Another BuildCraft module may create this holder after BCLib's Pre listener has run.
        // Register it in the still-active event instead of silently leaving it as missingno.
        if (activeStitchEvent != null) {
            registerForStitch(holder, activeStitchEvent);
        } else if (atlasHasBeenStitched && holder.lastRegisteredGeneration != stitchGeneration) {
            BCLog.logger.error(
                "[lib.sprite.holder] Sprite holder {} was created after the block atlas had already been stitched. " +
                "It will be missing until resources are reloaded. Add its holder class to the sprite bootstrap.",
                location
            );
        }
        return holder;
    }

    public static SpriteHolder getHolder(String location) {
        return getHolder(new ResourceLocation(location));
    }

    public static void onTextureStitchPre(ClientAtlas.Before event) {
        if (!InventoryMenu.BLOCK_ATLAS.equals(event.getAtlas().location())) {
            return;
        }
        beginStitch(event);
        bootstrapBuiltinHolders();
        for (SpriteHolder holder : new ArrayList<>(HOLDER_MAP.values())) {
            registerForStitch(holder, event);
        }
    }

    private static void beginStitch(ClientAtlas.Before event) {
        if (activeStitchEvent == event) {
            return;
        }
        activeStitchEvent = event;
        REGISTERED_THIS_STITCH.clear();
        stitchGeneration++;
        atlasHasBeenStitched = false;
    }

    private static void registerForStitch(SpriteHolder holder, ClientAtlas.Before event) {
        if (!InventoryMenu.BLOCK_ATLAS.equals(event.getAtlas().location())) {
            return;
        }
        beginStitch(event);
        holder.sprite = null;
        holder.hasCalled = false;
        if (REGISTERED_THIS_STITCH.add(holder.spriteLocation)) {
            event.addSprite(holder.spriteLocation);
        }
        holder.lastRegisteredGeneration = stitchGeneration;
    }

    private static void bootstrapBuiltinHolders() {
        ClassLoader loader = SpriteHolderRegistry.class.getClassLoader();
        for (String className : BUILTIN_HOLDER_CLASSES) {
            try {
                Class.forName(className, true, loader);
            } catch (ClassNotFoundException ignored) {
                // Individual BuildCraft modules are optional.
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

        for (int l = 0; l < 4; l++) {
            int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, l, GL11.GL_TEXTURE_WIDTH);
            int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, l, GL11.GL_TEXTURE_HEIGHT);

            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);

            int totalSize = width * height;
            IntBuffer intbuffer = BufferUtils.createIntBuffer(totalSize);
            int[] aint = new int[totalSize];
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, l, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, intbuffer);
            intbuffer.get(aint);
            BufferedImage bufferedimage = new BufferedImage(width, height, 2);
            bufferedimage.setRGB(0, 0, width, height, aint, 0, width);

            try {
                ImageIO.write(bufferedimage, "png", new File("bc_spritemap_" + l + ".png"));
            } catch (IOException io) {
                BCLog.logger.warn(io.getLocalizedMessage());
            }
        }
    }

    public static void onTextureStitchPost(ClientAtlas.After event) {
        if (!InventoryMenu.BLOCK_ATLAS.equals(event.getAtlas().location())) {
            return;
        }
        List<ResourceLocation> locations = new ArrayList<>(HOLDER_MAP.keySet());
        locations.sort(Comparator.comparing(ResourceLocation::toString));

        TextureAtlas manager = event.getAtlas();
        TextureAtlasSprite missing = manager.getSprite(MissingTextureAtlasSprite.getLocation());
        List<ResourceLocation> missingLocations = new ArrayList<>();
        List<ResourceLocation> unregisteredLocations = new ArrayList<>();

        // Always refresh the cached atlas sprite after stitching/resource reload.
        for (ResourceLocation location : locations) {
            SpriteHolder holder = HOLDER_MAP.get(location);
            TextureAtlasSprite stitched = manager.getSprite(location);
            holder.sprite = stitched;
            holder.hasCalled = false;

            if (holder.lastRegisteredGeneration != stitchGeneration) {
                unregisteredLocations.add(location);
            }
            if (isMissingSprite(stitched, missing)) {
                missingLocations.add(location);
            }
        }

        activeStitchEvent = null;
        REGISTERED_THIS_STITCH.clear();
        atlasHasBeenStitched = true;

        if (!unregisteredLocations.isEmpty()) {
            BCLog.logger.error(
                "[lib.sprite.holder] {} sprite holders were not registered during block-atlas stitching: {}",
                unregisteredLocations.size(), unregisteredLocations
            );
        }
        if (!missingLocations.isEmpty()) {
            BCLog.logger.error(
                "[lib.sprite.holder] {} BuildCraft sprites resolved to missingno. " +
                "The exact missing resources are listed below:",
                missingLocations.size()
            );
            for (ResourceLocation location : missingLocations) {
                BCLog.logger.error(
                    "[lib.sprite.holder]   {}  (expected assets/{}/textures/{}.png)",
                    location, location.getNamespace(), location.getPath()
                );
            }

            if (Boolean.getBoolean("buildcraft.failOnMissingSprites")) {
                throw new IllegalStateException(
                    "BuildCraft has " + missingLocations.size() + " missing stitched sprites: " + missingLocations
                );
            }
        }

        if (DEBUG && ModLoader.isLoadingStateValid()) {
            BCLog.logger.info("[lib.sprite.holder] Successfully stitched {} sprite holders", locations.size());
        }
    }

    private static boolean isMissingSprite(TextureAtlasSprite sprite, TextureAtlasSprite missing) {
        return sprite == missing || (sprite.getU0() == missing.getU0() && sprite.getV0() == missing.getV0());
    }

    /** Holds a reference to a {@link TextureAtlasSprite} that is automatically refreshed when the resource packs are
     * reloaded. As such you should store this in a static final field in a client-side class, and make sure that the
     * class is initialised before init. */
    @OnlyIn(Dist.CLIENT)
    public static class SpriteHolder implements ISprite {
        public final ResourceLocation spriteLocation;
        private TextureAtlasSprite sprite;
        private boolean hasCalled = false;
        private int lastRegisteredGeneration = -1;

        private SpriteHolder(ResourceLocation spriteLocation) {
            this.spriteLocation = spriteLocation;
        }

        /**
         * Adds this holder to the atlas currently being stitched.
         *
         * This is public so every BuildCraft module can register its own holders on its own
         * MOD event bus. Event priorities do not order listeners that belong to different
         * mod containers, so relying on BCLib to discover holders created by another module
         * is unsafe during early/additional resource reloads.
         */
        public void onTextureStitchPre(ClientAtlas.Before event) {
            SpriteHolderRegistry.registerForStitch(this, event);
        }

        private TextureAtlasSprite getSpriteChecking() {
            if (sprite == null & !hasCalled) {
                hasCalled = true;
                Minecraft mc = Minecraft.getInstance();
                sprite = mc.getModelManager().getAtlas(InventoryMenu.BLOCK_ATLAS).getSprite(spriteLocation);
/*                String warnText = "[lib.sprite.holder] Tried to use the sprite " + spriteLocation + " before it was stitched!";
                if (DEBUG) {
                    BCLog.logger.warn(warnText, new Throwable());
                } else {
                    BCLog.logger.warn(warnText);
                }*/
            }
            return sprite;
        }

        public TextureAtlasSprite getSprite() {
            return getSpriteChecking();
        }

        @Override
        public float getInterpU(double u) {
            TextureAtlasSprite s = getSpriteChecking();
            return s == null ? (float)u : s.getU(u*16);
        }

        @Override
        public float getInterpV(double v) {
            TextureAtlasSprite s = getSpriteChecking();
            return s == null ? (float)v : s.getV(v*16);
        }

        @Override
        public void bindTexture() {
            SpriteUtil.bindBlockTextureMap();
        }

    }
}
