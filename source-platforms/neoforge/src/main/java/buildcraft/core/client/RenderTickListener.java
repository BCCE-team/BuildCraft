/* Copyright (c) 2016 SpaceToad and the BuildCraft team
 * 
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package buildcraft.core.client;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import buildcraft.lib.internal.tiles.IDebuggable;
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
import buildcraft.lib.debug.ClientDebuggables;
import buildcraft.lib.marker.MarkerCache;
import buildcraft.lib.marker.MarkerSubCache;
import buildcraft.lib.misc.MatrixUtil;
import buildcraft.lib.misc.VecUtil;
import buildcraft.lib.misc.data.Box;
import buildcraft.robotics.zone.ZoneChunk;
import buildcraft.robotics.zone.ZonePlan;
import net.minecraft.ChatFormatting;
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
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

public class RenderTickListener {
    private static final Vec3[][][] MAP_LOCATION_POINT = new Vec3[6][][];
    private static final String DIFF_START, DIFF_HEADER_FORMATTING;

    private static final Box LAST_RENDERED_MAP_LOC = new Box();
    private static final double MAP_LOCATION_RENDER_DISTANCE_SQ = 128.0 * 128.0;
    private static final int MAX_ZONE_RENDER_EDGES = 4096;

    static {
        double[][][] upFace = { // Comments for formatting
            { { 0.5, 0.9, 0.5 }, { 0.5, 1.6, 0.5 } }, // Main line
            { { 0.5, 0.9, 0.5 }, { 0.8, 1.2, 0.5 } }, // First arrow part (+X)
            { { 0.5, 0.9, 0.5 }, { 0.2, 1.2, 0.5 } }, // Second arrow part (-X)
            { { 0.5, 0.9, 0.5 }, { 0.5, 1.2, 0.8 } }, // Third arrow part (+Z)
            { { 0.5, 0.9, 0.5 }, { 0.5, 1.2, 0.2 } }, // Forth arrow part (-Z)
        };

        for (Direction face : Direction.values()) {
            Matrix4f matrix = MatrixUtil.rotateTowardsFace(Direction.UP, face);
            Vec3[][] arr = new Vec3[5][2];
            for (int i = 0; i < 5; i++) {
                for (int j = 0; j < 2; j++) {
                    double[] from = upFace[i][j];
                    Vector4f point = new Vector4f((float)from[0], (float)from[1], (float)from[2], 1);
                    matrix.transform(point);
                    Vec3 to = new Vec3(point.x(), point.y(), point.z());
                    arr[i][j] = to;
                }
            }

            MAP_LOCATION_POINT[face.ordinal()] = arr;
        }
        DIFF_START = ChatFormatting.RED + "" + ChatFormatting.BOLD + "!" + ChatFormatting.RESET;
        DIFF_HEADER_FORMATTING = ChatFormatting.AQUA + "" + ChatFormatting.BOLD;
    }

    public static void renderOverlay(CustomizeGuiOverlayEvent.DebugText event) {
        Minecraft mc = Minecraft.getInstance();
        IDebuggable debuggable = ClientDebuggables.getDebuggableObject(mc.hitResult);
        if (debuggable != null) {
            List<String> clientLeft = new ArrayList<>();
            List<String> clientRight = new ArrayList<>();
            Direction face = mc.cameraEntity.getDirection().getOpposite();
            debuggable.getDebugInfo(clientLeft, clientRight, face);
            String headerFirst = DIFF_HEADER_FORMATTING + "SERVER:";
            String headerSecond = DIFF_HEADER_FORMATTING + "CLIENT:";
            DebugTextLists text = getDebugTextLists(event);
            appendDiff(text.left(), ClientDebuggables.SERVER_LEFT, clientLeft, headerFirst, headerSecond);
            appendDiff(text.right(), ClientDebuggables.SERVER_RIGHT, clientRight, headerFirst, headerSecond);
            debuggable.getClientDebugInfo(text.left(), text.right(), face);
        }
    }

    private record DebugTextLists(List<String> left, List<String> right) {
    }

    private static DebugTextLists getDebugTextLists(CustomizeGuiOverlayEvent.DebugText event) {
        List<String> left = invokeTextList(event, "getLeft", "left");
        List<String> right = invokeTextList(event, "getRight", "right");
        return new DebugTextLists(
            left == null ? new ArrayList<>() : left,
            right == null ? new ArrayList<>() : right
        );
    }

    @SuppressWarnings("unchecked")
    private static List<String> invokeTextList(Object target, String... methodNames) {
        for (String methodName : methodNames) {
            try {
                Method method = target.getClass().getMethod(methodName);
                Object value = method.invoke(target);
                if (value instanceof List<?>) {
                    return (List<String>) value;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // NeoForge changed these accessors between 1.21 builds; try the next public accessor name.
            }
        }
        return null;
    }

    private static void appendDiff(List<String> dest, List<String> first, List<String> second, String headerFirst,
        String headerSecond) {
        dest.add("");
        dest.add(headerFirst);
        dest.addAll(first);
        dest.add("");
        dest.add(headerSecond);
        if (first.size() != second.size()) {
            // no diffing
            dest.addAll(second);
        } else {
            for (int l = 0; l < first.size(); l++) {
                String shownLine = first.get(l);
                String diffLine = second.get(l);
                if (shownLine.equals(diffLine)) {
                    dest.add(diffLine);
                } else {
                    if (diffLine.startsWith(" ")) {
                        dest.add(DIFF_START + diffLine.substring(1));
                    } else {
                        dest.add(DIFF_START + diffLine);
                    }
                }
            }
        }
    }

    public static void renderLast(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        LaserRenderer_BC8.setupLaserRenderState();
        float partialTicks = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        PoseStack poseStack = new PoseStack();
        poseStack.mulPose(new Matrix4f(event.getModelViewMatrix()));
        Matrix4f matrix = new Matrix4f(event.getProjectionMatrix());
        renderHeldItemInWorld(poseStack, matrix, partialTicks);
    }

    private static void renderHeldItemInWorld(PoseStack poseStack, Matrix4f matrix, float partialTicks) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) {
            return;
        }
        ItemStack mainHand = player.getItemInHand(InteractionHand.MAIN_HAND);
        ItemStack offHand = player.getItemInHand(InteractionHand.OFF_HAND);
        ClientLevel world = mc.level;

        mc.getProfiler().push("bc");
        mc.getProfiler().push("renderWorld");

        DetachedRenderer.fromWorldOriginPre(poseStack, matrix, partialTicks);

        Item mainHandItem = mainHand.getItem();
        Item offHandItem = offHand.getItem();

        var mapLocation = BuildCraftApi.service(BuildCraftServices.MAP_LOCATIONS).read(mainHand);
        if (mapLocation.isPresent()) {
            renderMapLocation(poseStack, matrix, world, player, mapLocation.get());
        } else if (mainHandItem == BCCoreItems.MARKER_CONNECTOR.get() || offHandItem == BCCoreItems.MARKER_CONNECTOR.get()) {
            renderMarkerConnector(poseStack, matrix, world, player);
        }

        DetachedRenderer.fromWorldOriginPost(poseStack, matrix);

        mc.getProfiler().pop();
        mc.getProfiler().pop();
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
                LaserData_BC8 laser =
                    new LaserData_BC8(BuildCraftLaserManager.STRIPES_WRITE, vec[0], vec[1], 1 / 16.0);
                LaserRenderer_BC8.renderLaserStatic(poseStack, matrix, laser);
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
        if (path == null || path.size() < 2) {
            return;
        }
        BlockPos previous = path.get(0);
        for (int i = 1; i < path.size(); i++) {
            BlockPos current = path.get(i);
            renderMapPathSegment(poseStack, matrix, player, previous, current);
            previous = current;
        }

        // Explicitly close repeating paths when the stored endpoints differ so imported or manually edited
        // map-location data still renders as a loop.
        BlockPos first = path.get(0);
        BlockPos last = path.get(path.size() - 1);
        if (repeating && !first.equals(last)) {
            renderMapPathSegment(poseStack, matrix, player, last, first);
        }
    }

    private static void renderMapPathSegment(PoseStack poseStack, Matrix4f matrix, Player player,
        BlockPos start, BlockPos end) {
        if (start.equals(end) || !isNearPlayer(start, player) && !isNearPlayer(end, player)) {
            return;
        }
        LaserData_BC8 laser = new LaserData_BC8(
            BuildCraftLaserManager.STRIPES_WRITE_DIRECTION,
            VecUtil.convertCenter(start),
            VecUtil.convertCenter(end),
            1 / 16.0
        );
        LaserRenderer_BC8.renderLaserStatic(poseStack, matrix, laser);
    }

    private static void renderMapZone(PoseStack poseStack, Matrix4f matrix, ClientLevel world, Player player,
        ZonePlan zonePlan) {
        int radius = (int) Math.sqrt(MAP_LOCATION_RENDER_DISTANCE_SQ);
        int minX = (int) Math.floor(player.getX()) - radius - 1;
        int maxX = (int) Math.floor(player.getX()) + radius + 1;
        int minZ = (int) Math.floor(player.getZ()) - radius - 1;
        int maxZ = (int) Math.floor(player.getZ()) + radius + 1;

        // Do not flatten the entire saved zone every frame. Only collect chunks intersecting the render radius,
        // including a one-block border so cells at the cutoff do not acquire false outer edges.
        Set<Long> cells = new HashSet<>();
        for (Map.Entry<ChunkPos, ZoneChunk> entry : zonePlan.getChunkMapping().entrySet()) {
            ChunkPos chunk = entry.getKey();
            int chunkMinX = chunk.getMinBlockX();
            int chunkMinZ = chunk.getMinBlockZ();
            if (chunkMinX > maxX || chunkMinX + 15 < minX || chunkMinZ > maxZ || chunkMinZ + 15 < minZ) {
                continue;
            }
            for (Vec2 local : entry.getValue().getAll()) {
                int x = chunkMinX + (int) local.x;
                int z = chunkMinZ + (int) local.y;
                if (x >= minX && x <= maxX && z >= minZ && z <= maxZ) {
                    cells.add(zoneKey(x, z));
                }
            }
        }
        if (cells.isEmpty()) {
            return;
        }

        int renderedEdges = 0;
        for (long cell : cells) {
            int x = (int) (cell >> 32);
            int z = (int) cell;
            double dx = x + 0.5D - player.getX();
            double dz = z + 0.5D - player.getZ();
            if (dx * dx + dz * dz > MAP_LOCATION_RENDER_DISTANCE_SQ
                || !isClientChunkLoaded(world, new BlockPos(x, world.getMinBuildHeight(), z))) {
                continue;
            }

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
        BlockPos firstColumn = new BlockPos(x1, world.getMinBuildHeight(), z1);
        BlockPos secondColumn = new BlockPos(x2, world.getMinBuildHeight(), z2);
        if (!isClientChunkLoaded(world, firstColumn) || !isClientChunkLoaded(world, secondColumn)) {
            return;
        }
        double y1 = world.getHeight(Heightmap.Types.WORLD_SURFACE, x1, z1) + 0.05D;
        double y2 = world.getHeight(Heightmap.Types.WORLD_SURFACE, x2, z2) + 0.05D;
        LaserData_BC8 laser = new LaserData_BC8(
            BuildCraftLaserManager.STRIPES_WRITE,
            new Vec3(x1, y1, z1),
            new Vec3(x2, y2, z2),
            1 / 32.0
        );
        LaserRenderer_BC8.renderLaserStatic(poseStack, matrix, laser);
    }

    private static boolean isNearPlayer(BlockPos pos, Player player) {
        return pos.distToCenterSqr(player.getX(), player.getY(), player.getZ()) <= MAP_LOCATION_RENDER_DISTANCE_SQ;
    }

    private static long zoneKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFF_FFFFL);
    }

    private static void renderMarkerConnector(PoseStack poseStack, Matrix4f matrix, ClientLevel world, Player player) {
        ProfilerFiller profiler = Minecraft.getInstance().getProfiler();
        profiler.push("marker");
        for (MarkerCache<?> cache : MarkerCache.CACHES) {
            profiler.push(cache.name);
            renderMarkerCache(poseStack, matrix, world, player, cache.getSubCache(world));
            profiler.pop();
        }
        profiler.pop();
    }

    private static void renderMarkerCache(PoseStack poseStack, Matrix4f matrix, ClientLevel world, Player player,
        MarkerSubCache<?> cache) {
        ProfilerFiller profiler = Minecraft.getInstance().getProfiler();
        profiler.push("compute");
        Set<LaserData_BC8> toRender = new HashSet<>();
        for (final BlockPos a : cache.getAllMarkers()) {
            for (final BlockPos b : cache.getValidConnections(a)) {
                if (a.asLong() > b.asLong()) {
                    // Only render each pair once
                    continue;
                }
                // Marker positions remain cached when their chunks leave the client so the authoritative connection
                // can reappear intact after reloading. Do not render possible-connection lasers for those cached-only
                // positions: otherwise disabling terrain fog for marker lasers makes them visible beyond render distance.
                if (!isClientChunkLoaded(world, a) || !isClientChunkLoaded(world, b)) {
                    continue;
                }

                Vec3 start = VecUtil.convertCenter(a);
                Vec3 end = VecUtil.convertCenter(b);

                Vec3 startToEnd = end.subtract(start).normalize();
                Vec3 endToStart = start.subtract(end).normalize();
                start = start.add(VecUtil.scale(startToEnd, 0.125));
                end = end.add(VecUtil.scale(endToStart, 0.125));

                LaserType laserType = cache.getPossibleLaserType();
                if (laserType == null || isLookingAt(a, b, player)) {
                    laserType = BuildCraftLaserManager.MARKER_DEFAULT_POSSIBLE;
                }

                LaserData_BC8 data = new LaserData_BC8(laserType, start, end, 1 / 16.0);
                toRender.add(data);
            }
        }
        profiler.popPush("render");
        for (LaserData_BC8 laser : toRender) {
            LaserRenderer_BC8.renderLaserStatic(poseStack, matrix, laser);
        }
        profiler.pop();
    }

    private static boolean isLookingAt(BlockPos from, BlockPos to, Player player) {
        return ItemMarkerConnector.doesInteract(from, to, player);
    }
    private static boolean isClientChunkLoaded(ClientLevel world, BlockPos pos) {
        return world != null && world.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null;
    }

}
