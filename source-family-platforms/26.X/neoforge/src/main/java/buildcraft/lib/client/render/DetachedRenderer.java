//? source if >=26.2
/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.client.render;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import buildcraft.lib.client.render.laser.LaserRenderer_BC8;
import net.minecraft.world.phys.Vec3;
import buildcraft.lib.compat.RenderCompat;
import buildcraft.lib.compat.LevelCompat;

/** Dispatches "detached renderer elements" - rendering that does not require a specific tile or entity in the world
 * (perhaps held item HUD elements) */
public enum DetachedRenderer {
    INSTANCE;

    public enum RenderMatrixType implements IGlPre, IGLPost {
        FROM_PLAYER(null, null),
        FROM_WORLD_ORIGIN(DetachedRenderer::fromWorldOriginPre, DetachedRenderer::fromWorldOriginPost);

        public final IGlPre pre;
        public final IGLPost post;

        RenderMatrixType(IGlPre pre, IGLPost post) {
            this.pre = pre;
            this.post = post;
        }

        public void glPre(PoseStack pose, Matrix4f matrix, float partialTicks) {
            if (pre != null) pre.glPre(pose, matrix, partialTicks);
        }

        public void glPost(PoseStack pose, Matrix4f matrix) {
            if (post != null) post.glPost(pose, matrix);
        }
    }

    @FunctionalInterface
    public interface IGlPre {
        void glPre(PoseStack pose, Matrix4f matrix, float partialTicks);
    }

    @FunctionalInterface
    public interface IGLPost {
        void glPost(PoseStack pose, Matrix4f matrix);
    }

    @FunctionalInterface
    public interface IDetachedRenderer {
        void render(PoseStack pose, Matrix4f matrix, Player player, float partialTicks);
    }

    private final Map<RenderMatrixType, List<IDetachedRenderer>> renders = new EnumMap<>(RenderMatrixType.class);

    DetachedRenderer() {
        for (RenderMatrixType type : RenderMatrixType.values()) {
            renders.put(type, new ArrayList<>());
        }
    }

    public void addRenderer(RenderMatrixType type, IDetachedRenderer renderer) {
        renders.get(type).add(renderer);
    }

    /** Evaluate live callbacks during extraction, never from a feature draw callback. */
    public List<buildcraft.lib.client.render.compat.CapturedBlockEntityRenderer.Layer> extract(
        Player player, float partialTicks) {
        return buildcraft.lib.client.render.compat.BCWorldGeometry.capture(
            () -> renderWorldLastEvent(new PoseStack(), new Matrix4f(), player, partialTicks));
    }

    public void renderWorldLastEvent(PoseStack pose, Matrix4f matrix, Player player, float partialTicks) {
        LaserRenderer_BC8.setupLaserRenderState();
        RenderCompat.setShaderTexture(0, net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS);
        for (RenderMatrixType type : RenderMatrixType.values()) {
            List<IDetachedRenderer> rendersForType = this.renders.get(type);
            if (rendersForType.isEmpty()) continue;
            type.glPre(pose, matrix, partialTicks);
            try {
                for (IDetachedRenderer render : rendersForType) {
                    render.render(pose, matrix, player, partialTicks);
                }
            } finally {
                type.glPost(pose, matrix);
            }
        }
        LaserRenderer_BC8.flushStaticLasers();

    }

    public static void fromWorldOriginPre(PoseStack pose, Matrix4f matrix, float partialTicks) {
        pose.pushPose();
        Minecraft mc = Minecraft.getInstance();
        Camera camera = mc.gameRenderer.mainCamera();
        Vec3 diff = Vec3.ZERO;
        diff = diff.subtract(LevelCompat.cameraPosition(camera));
        pose.translate(diff.x, diff.y, diff.z);
    }

    public static void fromWorldOriginPost(PoseStack pose, Matrix4f matrix) {
        pose.popPose();
    }
}
