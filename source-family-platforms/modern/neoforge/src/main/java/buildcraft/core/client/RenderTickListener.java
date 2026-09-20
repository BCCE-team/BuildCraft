//? source if >=1.21.11
package buildcraft.core.client;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.map.MapLocationKind;
import buildcraft.api.v2.map.MapLocationView;
import buildcraft.core.BCCoreItems;
import buildcraft.core.item.ItemMarkerConnector;
import buildcraft.lib.client.render.DetachedRenderer;
import buildcraft.lib.client.render.laser.LaserBoxRenderer;
import buildcraft.lib.client.render.laser.LaserData_BC8;
import buildcraft.lib.client.render.laser.LaserData_BC8.LaserType;
import buildcraft.lib.client.render.laser.LaserRenderer_BC8;
import buildcraft.lib.marker.MarkerCache;
import buildcraft.lib.marker.MarkerSubCache;
import buildcraft.lib.misc.MatrixUtil;
import buildcraft.lib.misc.VecUtil;
import buildcraft.lib.misc.data.Box;
import buildcraft.robotics.zone.ZoneChunk;
import buildcraft.robotics.zone.ZonePlan;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import buildcraft.lib.compat.LevelCompat;
import net.minecraft.util.profiling.Profiler;

/** Native 1.21.11 world-laser event bridge. */
public final class RenderTickListener {
    private static final Vec3[][][] MAP_LOCATION_POINT = new Vec3[6][][];
    private static final Box LAST_RENDERED_MAP_LOC = new Box();
    private static final double MAP_LOCATION_RENDER_DISTANCE_SQ = 128.0 * 128.0;
    private static final int MAX_ZONE_RENDER_EDGES = 4096;

    static {
        double[][][] upFace = {
            { { 0.5, 0.9, 0.5 }, { 0.5, 1.6, 0.5 } },
            { { 0.5, 0.9, 0.5 }, { 0.8, 1.2, 0.5 } },
            { { 0.5, 0.9, 0.5 }, { 0.2, 1.2, 0.5 } },
            { { 0.5, 0.9, 0.5 }, { 0.5, 1.2, 0.8 } },
            { { 0.5, 0.9, 0.5 }, { 0.5, 1.2, 0.2 } },
        };

        for (Direction face : Direction.values()) {
            Matrix4f matrix = MatrixUtil.rotateTowardsFace(Direction.UP, face);
            Vec3[][] arr = new Vec3[5][2];
            for (int i = 0; i < 5; i++) {
                for (int j = 0; j < 2; j++) {
                    double[] from = upFace[i][j];
                    Vector4f point = new Vector4f((float) from[0], (float) from[1], (float) from[2], 1);
                    matrix.transform(point);
                    arr[i][j] = new Vec3(point.x(), point.y(), point.z());
                }
            }
            MAP_LOCATION_POINT[face.ordinal()] = arr;
        }
    }

    private RenderTickListener() {
    }

    public static void renderLast(RenderLevelStageEvent.AfterTranslucentBlocks event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }

        PoseStack poseStack = event.getPoseStack();
        Matrix4f matrix = new Matrix4f(event.getModelViewMatrix());
        try {
            renderHeldItemInWorld(poseStack, matrix, 0.0F);
        } finally {
            LaserRenderer_BC8.flushStaticLasers();
        }
    }

    private static void renderHeldItemInWorld(PoseStack poseStack, Matrix4f matrix, float partialTicks) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        ClientLevel world = mc.level;
        if (player == null || world == null) {
            return;
        }

        ItemStack mainHand = player.getItemInHand(InteractionHand.MAIN_HAND);
        ItemStack offHand = player.getItemInHand(InteractionHand.OFF_HAND);
        LevelCompat.profilerPush(mc, "bc");
        LevelCompat.profilerPush(mc, "renderWorld");
        try {
            DetachedRenderer.fromWorldOriginPre(poseStack, matrix, partialTicks);
            try {
                Item mainHandItem = mainHand.getItem();
                Item offHandItem = offHand.getItem();

                var mapLocation = BuildCraftApi.service(BuildCraftServices.MAP_LOCATIONS).read(mainHand);
                if (mapLocation.isPresent()) {
                    renderMapLocation(poseStack, matrix, world, player, mapLocation.get());
                } else if (mainHandItem == BCCoreItems.MARKER_CONNECTOR.get()
                    || offHandItem == BCCoreItems.MARKER_CONNECTOR.get()) {
                    renderMarkerConnector(poseStack, matrix, world, player);
                }
            } finally {
                DetachedRenderer.fromWorldOriginPost(poseStack, matrix);
            }
        } finally {
            LevelCompat.profilerPop(mc);
            LevelCompat.profilerPop(mc);
        }
    }

    private static void renderMapLocation(PoseStack poseStack, Matrix4f matrix, ClientLevel world, Player player,
        MapLocationView location) {
        MapLocationKind type = location.kind();
        if (type == MapLocationKind.SPOT) {
            BlockPos point = location.point().orElse(null);
            if (point == null) return;
            Direction face = location.pointSide().orElse(Direction.UP);
            Vec3[][] vectors = MAP_LOCATION_POINT[face.ordinal()];
            poseStack.pushPose();
            poseStack.translate(point.getX(), point.getY(), point.getZ());
            for (Vec3[] vec : vectors) {
                LaserRenderer_BC8.renderLaserStatic(poseStack, matrix,
                    new LaserData_BC8(BuildCraftLaserManager.STRIPES_WRITE, vec[0], vec[1], 1 / 16.0));
            }
            poseStack.popPose();
        } else if (type == MapLocationKind.AREA) {
            var box = location.box().orElse(null);
            if (box == null) return;
            LAST_RENDERED_MAP_LOC.reset();
            LAST_RENDERED_MAP_LOC.extendToEncompassBoth(box.min(), box.max());
            LaserBoxRenderer.renderLaserBoxStatic(
                poseStack, matrix, LAST_RENDERED_MAP_LOC, BuildCraftLaserManager.STRIPES_WRITE, true
            );
        } else if (type == MapLocationKind.PATH || type == MapLocationKind.PATH_REPEATING) {
            var path = location.path().orElse(null);
            if (path != null) {
                renderMapPath(poseStack, matrix, player, path.points(), type == MapLocationKind.PATH_REPEATING);
            }
        } else if (type == MapLocationKind.ZONE) {
            var zone = location.zone().orElse(null);
            if (zone instanceof ZonePlan zonePlan) {
                renderMapZone(poseStack, matrix, world, player, zonePlan);
            }
        }
    }

    private static void renderMapPath(PoseStack poseStack, Matrix4f matrix, Player player, List<BlockPos> path,
        boolean repeating) {
        if (path == null || path.size() < 2) return;
        BlockPos previous = path.get(0);
        for (int i = 1; i < path.size(); i++) {
            BlockPos current = path.get(i);
            renderMapPathSegment(poseStack, matrix, player, previous, current);
            previous = current;
        }

        BlockPos first = path.get(0);
        BlockPos last = path.get(path.size() - 1);
        if (repeating && !first.equals(last)) {
            renderMapPathSegment(poseStack, matrix, player, last, first);
        }
    }

    private static void renderMapPathSegment(PoseStack poseStack, Matrix4f matrix, Player player,
        BlockPos start, BlockPos end) {
        if (start.equals(end) || !isNearPlayer(start, player) && !isNearPlayer(end, player)) return;
        LaserRenderer_BC8.renderLaserStatic(poseStack, matrix, new LaserData_BC8(
            BuildCraftLaserManager.STRIPES_WRITE_DIRECTION,
            VecUtil.convertCenter(start), VecUtil.convertCenter(end), 1 / 16.0
        ));
    }

    private static void renderMapZone(PoseStack poseStack, Matrix4f matrix, ClientLevel world, Player player,
        ZonePlan zonePlan) {
        int radius = (int) Math.sqrt(MAP_LOCATION_RENDER_DISTANCE_SQ);
        int minX = (int) Math.floor(player.getX()) - radius - 1;
        int maxX = (int) Math.floor(player.getX()) + radius + 1;
        int minZ = (int) Math.floor(player.getZ()) - radius - 1;
        int maxZ = (int) Math.floor(player.getZ()) + radius + 1;

        Set<Long> cells = new HashSet<>();
        for (Map.Entry<ChunkPos, ZoneChunk> entry : zonePlan.getChunkMapping().entrySet()) {
            ChunkPos chunk = entry.getKey();
            int chunkMinX = chunk.getMinBlockX();
            int chunkMinZ = chunk.getMinBlockZ();
            if (chunkMinX > maxX || chunkMinX + 15 < minX || chunkMinZ > maxZ || chunkMinZ + 15 < minZ) continue;
            for (Vec2 local : entry.getValue().getAll()) {
                int x = chunkMinX + (int) local.x;
                int z = chunkMinZ + (int) local.y;
                if (x >= minX && x <= maxX && z >= minZ && z <= maxZ) {
                    cells.add(zoneKey(x, z));
                }
            }
        }
        if (cells.isEmpty()) return;

        int renderedEdges = 0;
        for (long cell : cells) {
            int x = (int) (cell >> 32);
            int z = (int) cell;
            double dx = x + 0.5D - player.getX();
            double dz = z + 0.5D - player.getZ();
            if (dx * dx + dz * dz > MAP_LOCATION_RENDER_DISTANCE_SQ
                || !isClientChunkLoaded(world, new BlockPos(x, LevelCompat.getMinBuildHeight(world), z))) continue;

            if (!cells.contains(zoneKey(x, z - 1))) {
                renderZoneEdge(poseStack, matrix, world, x, z, x + 1, z);
                if (++renderedEdges >= MAX_ZONE_RENDER_EDGES) return;
            }
            if (!cells.contains(zoneKey(x + 1, z))) {
                renderZoneEdge(poseStack, matrix, world, x + 1, z, x + 1, z + 1);
                if (++renderedEdges >= MAX_ZONE_RENDER_EDGES) return;
            }
            if (!cells.contains(zoneKey(x, z + 1))) {
                renderZoneEdge(poseStack, matrix, world, x + 1, z + 1, x, z + 1);
                if (++renderedEdges >= MAX_ZONE_RENDER_EDGES) return;
            }
            if (!cells.contains(zoneKey(x - 1, z))) {
                renderZoneEdge(poseStack, matrix, world, x, z + 1, x, z);
                if (++renderedEdges >= MAX_ZONE_RENDER_EDGES) return;
            }
        }
    }

    private static void renderZoneEdge(PoseStack poseStack, Matrix4f matrix, ClientLevel world,
        int x1, int z1, int x2, int z2) {
        BlockPos firstColumn = new BlockPos(x1, LevelCompat.getMinBuildHeight(world), z1);
        BlockPos secondColumn = new BlockPos(x2, LevelCompat.getMinBuildHeight(world), z2);
        if (!isClientChunkLoaded(world, firstColumn) || !isClientChunkLoaded(world, secondColumn)) return;
        double y1 = world.getHeight(Heightmap.Types.WORLD_SURFACE, x1, z1) + 0.05D;
        double y2 = world.getHeight(Heightmap.Types.WORLD_SURFACE, x2, z2) + 0.05D;
        LaserRenderer_BC8.renderLaserStatic(poseStack, matrix, new LaserData_BC8(
            BuildCraftLaserManager.STRIPES_WRITE,
            new Vec3(x1, y1, z1), new Vec3(x2, y2, z2), 1 / 32.0
        ));
    }

    private static boolean isNearPlayer(BlockPos pos, Player player) {
        return pos.distToCenterSqr(player.getX(), player.getY(), player.getZ()) <= MAP_LOCATION_RENDER_DISTANCE_SQ;
    }

    private static long zoneKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFF_FFFFL);
    }

    private static void renderMarkerConnector(PoseStack poseStack, Matrix4f matrix, ClientLevel world, Player player) {
        ProfilerFiller profiler = Profiler.get();
        for (MarkerCache<?> cache : MarkerCache.CACHES) {
            renderMarkerCache(poseStack, matrix, world, player, cache.getSubCache(world));
        }
    }

    private static void renderMarkerCache(PoseStack poseStack, Matrix4f matrix, ClientLevel world, Player player,
        MarkerSubCache<?> cache) {
        ProfilerFiller profiler = Profiler.get();
        Set<LaserData_BC8> toRender = new HashSet<>();
        for (BlockPos a : cache.getAllMarkers()) {
            for (BlockPos b : cache.getValidConnections(a)) {
                if (a.asLong() > b.asLong() || !isClientChunkLoaded(world, a) || !isClientChunkLoaded(world, b)) continue;

                Vec3 start = VecUtil.convertCenter(a);
                Vec3 end = VecUtil.convertCenter(b);
                Vec3 startToEnd = end.subtract(start).normalize();
                Vec3 endToStart = start.subtract(end).normalize();
                start = start.add(VecUtil.scale(startToEnd, 0.125));
                end = end.add(VecUtil.scale(endToStart, 0.125));

                LaserType laserType = cache.getPossibleLaserType();
                if (laserType == null || ItemMarkerConnector.doesInteract(a, b, player)) {
                    laserType = BuildCraftLaserManager.MARKER_DEFAULT_POSSIBLE;
                }
                toRender.add(new LaserData_BC8(laserType, start, end, 1 / 16.0));
            }
        }
        for (LaserData_BC8 laser : toRender) {
            LaserRenderer_BC8.renderLaserStatic(poseStack, matrix, laser);
        }
    }

    private static boolean isClientChunkLoaded(ClientLevel world, BlockPos pos) {
        return world != null && world.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null;
    }
}
