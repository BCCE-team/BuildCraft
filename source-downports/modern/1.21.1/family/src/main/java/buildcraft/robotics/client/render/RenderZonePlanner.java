/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.robotics.client.render;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalNotification;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import buildcraft.lib.block.BlockBCBase_Neptune;
import buildcraft.robotics.tile.TileZonePlanner;
import buildcraft.robotics.zone.ZonePlannerMapChunk;
import buildcraft.robotics.zone.ZonePlannerMapChunk.MapColourData;
import buildcraft.robotics.zone.ZonePlannerMapChunkKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * Restores the small terrain preview that BuildCraft 7/8 rendered directly on the Zone Planner's front display.
 *
 * <p>The GUI map uses server-backed map chunks because it may pan far beyond the client's render distance. The block
 * preview is deliberately different: it samples only already-loaded client chunks around the visible planner. This
 * keeps the preview available even when the planner GUI has never been opened, without turning the renderer into an
 * unrestricted remote map request mechanism.</p>
 */
public class RenderZonePlanner implements BlockEntityRenderer<TileZonePlanner> {
    private static final int TEXTURE_WIDTH = 10;
    private static final int TEXTURE_HEIGHT = 8;
    private static final int BLOCKS_PER_PIXEL = 4; // BC8 used a 40 x 32 block preview on the 10 x 8 display.
    private static final int REFRESH_TICKS = 100; // BC7 refreshed its preview every five seconds.
    private static final int RETRY_TICKS = 20;
    private static final int MAP_BACKGROUND_COLOUR = 0xFF_20_20_20;

    private static final float MIN_X = 3.0F / 16.0F;
    private static final float MAX_X = 13.0F / 16.0F;
    private static final float MIN_Y = 5.0F / 16.0F;
    private static final float MAX_Y = 13.0F / 16.0F;
    private static final float FACE_OFFSET = 1.0F / 1024.0F;

    private static final Cache<PreviewKey, PreviewTexture> TEXTURES = CacheBuilder
            .<PreviewKey, PreviewTexture>newBuilder()
            .expireAfterAccess(30, TimeUnit.SECONDS)
            .removalListener(RenderZonePlanner::onTextureRemoved)
            .build();

    public RenderZonePlanner(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(TileZonePlanner tile, float partialTicks, PoseStack poseStack, MultiBufferSource buffers,
            int packedLight, int packedOverlay) {
        Level level = tile.getLevel();
        if (level == null || tile.isRemoved()) {
            return;
        }

        Direction front = tile.getBlockState().getValue(BlockBCBase_Neptune.PROP_FACING);
        PreviewKey key = new PreviewKey(level.dimension().location().toString(), tile.getBlockPos());
        PreviewTexture preview = TEXTURES.getIfPresent(key);
        if (preview == null) {
            preview = new PreviewTexture(key);
            TEXTURES.put(key, preview);
        }
        TEXTURES.cleanUp();

        if (!preview.updateIfNeeded(tile, front)) {
            return;
        }

        VertexConsumer builder = buffers.getBuffer(RenderType.entityCutoutNoCull(preview.location));
        renderDisplay(builder, poseStack.last(), front);
    }

    private static void renderDisplay(VertexConsumer builder, PoseStack.Pose pose, Direction front) {
        // The original BC8 renderer called this direction "side = facing.getOpposite()" and then drew the opposite
        // geometric face. Expressing it directly as the actual front keeps the overlay aligned with the block model.
        switch (front) {
            case NORTH -> quad(builder, pose,
                    MIN_X, MIN_Y, -FACE_OFFSET,
                    MAX_X, MIN_Y, -FACE_OFFSET,
                    MAX_X, MAX_Y, -FACE_OFFSET,
                    MIN_X, MAX_Y, -FACE_OFFSET,
                    0.0F, 0.0F, -1.0F);
            case EAST -> quad(builder, pose,
                    1.0F + FACE_OFFSET, MIN_Y, MIN_X,
                    1.0F + FACE_OFFSET, MIN_Y, MAX_X,
                    1.0F + FACE_OFFSET, MAX_Y, MAX_X,
                    1.0F + FACE_OFFSET, MAX_Y, MIN_X,
                    1.0F, 0.0F, 0.0F);
            case SOUTH -> quad(builder, pose,
                    MAX_X, MIN_Y, 1.0F + FACE_OFFSET,
                    MIN_X, MIN_Y, 1.0F + FACE_OFFSET,
                    MIN_X, MAX_Y, 1.0F + FACE_OFFSET,
                    MAX_X, MAX_Y, 1.0F + FACE_OFFSET,
                    0.0F, 0.0F, 1.0F);
            case WEST -> quad(builder, pose,
                    -FACE_OFFSET, MIN_Y, MAX_X,
                    -FACE_OFFSET, MIN_Y, MIN_X,
                    -FACE_OFFSET, MAX_Y, MIN_X,
                    -FACE_OFFSET, MAX_Y, MAX_X,
                    -1.0F, 0.0F, 0.0F);
            default -> {
                // Zone Planner only has horizontal facings.
            }
        }
    }

    private static void quad(VertexConsumer builder, PoseStack.Pose pose,
            float x1, float y1, float z1,
            float x2, float y2, float z2,
            float x3, float y3, float z3,
            float x4, float y4, float z4,
            float nx, float ny, float nz) {
        vertex(builder, pose, x1, y1, z1, 0.0F, 1.0F, nx, ny, nz);
        vertex(builder, pose, x2, y2, z2, 1.0F, 1.0F, nx, ny, nz);
        vertex(builder, pose, x3, y3, z3, 1.0F, 0.0F, nx, ny, nz);
        vertex(builder, pose, x4, y4, z4, 0.0F, 0.0F, nx, ny, nz);
    }

    private static void vertex(VertexConsumer builder, PoseStack.Pose pose,
            float x, float y, float z, float u, float v, float nx, float ny, float nz) {
        builder.addVertex(pose.pose(), x, y, z)
                .setColor(1.0F, 1.0F, 1.0F, 1.0F)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT)
                .setNormal(pose, nx, ny, nz);
    }

    private static void onTextureRemoved(RemovalNotification<PreviewKey, PreviewTexture> notification) {
        PreviewTexture preview = notification.getValue();
        if (preview != null) {
            Minecraft.getInstance().getTextureManager().release(preview.location);
        }
    }

    private static final class PreviewTexture {
        private final DynamicTexture texture;
        private final ResourceLocation location;
        private long lastRefresh = Long.MIN_VALUE;
        private long lastAttempt = Long.MIN_VALUE;
        private Direction facing;
        private int mapLevel = Integer.MIN_VALUE;
        private boolean ready;

        private PreviewTexture(PreviewKey key) {
            texture = new DynamicTexture(TEXTURE_WIDTH, TEXTURE_HEIGHT, true);
            String path = "dynamic/zone_planner_preview/" + Integer.toUnsignedString(key.dimension.hashCode(), 16)
                    + "/" + Long.toUnsignedString(key.pos.asLong(), 16);
            location = ResourceLocation.fromNamespaceAndPath("buildcraftrobotics", path);
            Minecraft.getInstance().getTextureManager().register(location, texture);
        }

        private boolean updateIfNeeded(TileZonePlanner tile, Direction newFacing) {
            Level level = tile.getLevel();
            if (level == null) {
                return false;
            }
            Minecraft minecraft = Minecraft.getInstance();
            int newMapLevel = minecraft.player == null
                    ? Math.max(0, tile.getBlockPos().getY() / ZonePlannerMapChunkKey.LEVEL_HEIGHT)
                    : Math.max(0, minecraft.player.blockPosition().getY() / ZonePlannerMapChunkKey.LEVEL_HEIGHT);
            long now = level.getGameTime();
            boolean orientationChanged = facing != newFacing || mapLevel != newMapLevel;
            if (orientationChanged) {
                ready = false;
                lastAttempt = Long.MIN_VALUE;
            }

            if (!orientationChanged && ready && elapsed(now, lastRefresh) < REFRESH_TICKS) {
                return true;
            }
            if (elapsed(now, lastAttempt) < RETRY_TICKS) {
                return ready;
            }
            lastAttempt = now;

            if (rebuild(tile, newFacing, newMapLevel)) {
                facing = newFacing;
                mapLevel = newMapLevel;
                lastRefresh = now;
                ready = true;
            }
            return ready;
        }

        private boolean rebuild(TileZonePlanner tile, Direction front, int mapLevel) {
            Level level = tile.getLevel();
            NativeImage pixels = texture.getPixels();
            if (level == null || pixels == null) {
                return false;
            }

            int[] colours = new int[TEXTURE_WIDTH * TEXTURE_HEIGHT];
            Map<ChunkPos, ZonePlannerMapChunk> chunks = new HashMap<>();
            int dimension = level.dimension().location().hashCode();
            BlockPos origin = tile.getBlockPos();
            Direction side = front.getOpposite();

            for (int textureX = 0; textureX < TEXTURE_WIDTH; textureX++) {
                for (int textureY = 0; textureY < TEXTURE_HEIGHT; textureY++) {
                    int offset1 = (textureX - TEXTURE_WIDTH / 2) * BLOCKS_PER_PIXEL;
                    int offset2 = (textureY - TEXTURE_HEIGHT / 2) * BLOCKS_PER_PIXEL;
                    int worldX;
                    int worldZ;
                    // Same sample orientation as the original BC8 RenderZonePlanner.
                    switch (side) {
                        case NORTH -> {
                            worldX = origin.getX() + offset1;
                            worldZ = origin.getZ() - offset2;
                        }
                        case EAST -> {
                            worldX = origin.getX() + offset2;
                            worldZ = origin.getZ() + offset1;
                        }
                        case SOUTH -> {
                            worldX = origin.getX() + offset1;
                            worldZ = origin.getZ() + offset2;
                        }
                        case WEST -> {
                            worldX = origin.getX() - offset2;
                            worldZ = origin.getZ() + offset1;
                        }
                        default -> {
                            return false;
                        }
                    }

                    ChunkPos chunkPos = new ChunkPos(worldX >> 4, worldZ >> 4);
                    ZonePlannerMapChunk mapChunk = chunks.get(chunkPos);
                    if (mapChunk == null) {
                        mapChunk = new ZonePlannerMapChunk(
                                level,
                                new ZonePlannerMapChunkKey(chunkPos, dimension, mapLevel)
                        );
                        chunks.put(chunkPos, mapChunk);
                    }
                    if (!mapChunk.isAvailable()) {
                        return false;
                    }

                    MapColourData colour = mapChunk.getData(worldX, worldZ);
                    colours[textureY * TEXTURE_WIDTH + textureX] = colour == null
                            ? MAP_BACKGROUND_COLOUR
                            : colour.colour;
                }
            }

            for (int y = 0; y < TEXTURE_HEIGHT; y++) {
                for (int x = 0; x < TEXTURE_WIDTH; x++) {
                    pixels.setPixelRGBA(x, y, argbToAbgr(colours[y * TEXTURE_WIDTH + x]));
                }
            }
            texture.upload();
            return true;
        }
    }

    private static long elapsed(long now, long then) {
        if (then == Long.MIN_VALUE || now < then) {
            return Long.MAX_VALUE;
        }
        return now - then;
    }

    private static int argbToAbgr(int argb) {
        return argb & 0xFF_00_FF_00 | (argb & 0x00_FF_00_00) >> 16 | (argb & 0x00_00_00_FF) << 16;
    }

    private static final class PreviewKey {
        private final String dimension;
        private final BlockPos pos;

        private PreviewKey(String dimension, BlockPos pos) {
            this.dimension = dimension;
            this.pos = pos.immutable();
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof PreviewKey other)) {
                return false;
            }
            return dimension.equals(other.dimension) && pos.equals(other.pos);
        }

        @Override
        public int hashCode() {
            return Objects.hash(dimension, pos);
        }
    }
}
