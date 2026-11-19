/* Copyright (c) 2026 BuildCraft Community Edition contributors
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.robotics.zone;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.world.level.ChunkPos;

/**
 * Shared Zone Planner editing logic. A drag creates one edit, while each layer has its own
 * bounded, chunk-delta undo stack. No editor state is stored in the world or sent over the wire.
 */
public final class ZonePlannerEditor {
    private ZonePlannerEditor() {}

    public enum Tool {
        RECTANGLE("Rect"), ELLIPSE("Oval"), LINE("Line"), BRUSH("Brush"), FLOOD("Fill");

        public final String label;
        Tool(String label) { this.label = label; }

        public Tool next() {
            Tool[] tools = values();
            return tools[(ordinal() + 1) % tools.length];
        }
    }

    public record Bounds(int minX, int minZ, int maxX, int maxZ) {
        public Bounds {
            if (minX > maxX || minZ > maxZ) throw new IllegalArgumentException("Invalid map bounds");
        }
        public boolean contains(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
    }

    // Never permit a flood-fill to expand silently through an unbounded plane.
    public static final int MAX_FLOOD_BLOCKS = 65_536;
    private static final int MAX_UNDO_PER_LAYER = 24;

    public static boolean paint(ZonePlan area, Tool tool, int x1, int z1, int x2, int z2,
            boolean add, Predicate<ChunkPos> available, Bounds bounds) {
        if (tool == Tool.FLOOD) return flood(area, x1, z1, add, available, bounds);
        boolean changed = false;
        switch (tool) {
            case RECTANGLE -> {
                int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
                int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
                for (int x = minX; x <= maxX; x++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        changed |= set(area, x, z, add, available, bounds);
                    }
                }
            }
            case ELLIPSE -> {
                int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
                int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
                double centerX = (minX + (double) maxX) / 2.0D;
                double centerZ = (minZ + (double) maxZ) / 2.0D;
                double radiusX = Math.max(0.71D, (maxX - (double) minX) / 2.0D);
                double radiusZ = Math.max(0.71D, (maxZ - (double) minZ) / 2.0D);
                for (int x = minX; x <= maxX; x++) {
                    double normX = (x - centerX) / radiusX;
                    for (int z = minZ; z <= maxZ; z++) {
                        double normZ = (z - centerZ) / radiusZ;
                        if (normX * normX + normZ * normZ <= 1.000001D) {
                            changed |= set(area, x, z, add, available, bounds);
                        }
                    }
                }
            }
            case LINE, BRUSH -> {
                int x = x1, z = z1;
                long dx = Math.abs((long) x2 - x1), dz = Math.abs((long) z2 - z1);
                int stepX = x1 < x2 ? 1 : -1;
                int stepZ = z1 < z2 ? 1 : -1;
                long error = dx - dz;
                while (true) {
                    if (tool == Tool.BRUSH) {
                        // 3x3 square tip; adjoining drag events form one continuous stroke.
                        for (int bx = -1; bx <= 1; bx++) {
                            for (int bz = -1; bz <= 1; bz++) {
                                changed |= set(area, x + bx, z + bz, add, available, bounds);
                            }
                        }
                    } else {
                        changed |= set(area, x, z, add, available, bounds);
                    }
                    if (x == x2 && z == z2) break;
                    long doubled = 2L * error;
                    if (doubled > -dz) { error -= dz; x += stepX; }
                    if (doubled < dx) { error += dx; z += stepZ; }
                }
            }
            default -> throw new IllegalStateException("Unhandled tool: " + tool);
        }
        return changed;
    }

    private static boolean set(ZonePlan area, int x, int z, boolean value,
            Predicate<ChunkPos> available, Bounds bounds) {
        if (bounds != null && !bounds.contains(x, z)) return false;
        if (area.get(x, z) == value) return false;
        if (value && available != null && !available.test(new ChunkPos(x >> 4, z >> 4))) return false;
        area.set(x, z, value);
        return area.get(x, z) == value;
    }

    /** Only the visible map can be filled; a too-large fill is rejected atomically. */
    public static boolean flood(ZonePlan area, int startX, int startZ, boolean value,
            Predicate<ChunkPos> available, Bounds bounds) {
        if (bounds == null || !bounds.contains(startX, startZ) || area.get(startX, startZ) == value) return false;
        ArrayDeque<Long> queue = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        ArrayList<Long> fill = new ArrayList<>();
        queue.add(pack(startX, startZ));
        while (!queue.isEmpty()) {
            long packed = queue.removeFirst();
            if (!visited.add(packed)) continue;
            int x = (int) (packed >> 32);
            int z = (int) packed;
            if (!bounds.contains(x, z) || area.get(x, z) == value) continue;
            if (value && available != null && !available.test(new ChunkPos(x >> 4, z >> 4))) continue;
            if (fill.size() == MAX_FLOOD_BLOCKS) return false;
            fill.add(packed);
            if (x > bounds.minX) queue.add(pack(x - 1, z));
            if (x < bounds.maxX) queue.add(pack(x + 1, z));
            if (z > bounds.minZ) queue.add(pack(x, z - 1));
            if (z < bounds.maxZ) queue.add(pack(x, z + 1));
        }
        boolean changed = false;
        for (long packed : fill) changed |= set(area, (int) (packed >> 32), (int) packed, value, available, bounds);
        return changed;
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFF_FFFFL);
    }

    public static final class History {
        @SuppressWarnings("unchecked")
        private final Deque<Change>[] undo = new Deque[16];
        @SuppressWarnings("unchecked")
        private final Deque<Change>[] redo = new Deque[16];

        public History() {
            for (int i = 0; i < undo.length; i++) {
                undo[i] = new ArrayDeque<>();
                redo[i] = new ArrayDeque<>();
            }
        }

        public boolean canUndo(int layer) { return valid(layer) && !undo[layer].isEmpty(); }
        public boolean canRedo(int layer) { return valid(layer) && !redo[layer].isEmpty(); }

        /** Returns false if the edit does not actually change any cell. */
        public boolean record(int layer, ZonePlan before, ZonePlan after) {
            if (!valid(layer) || before == null || after == null) return false;
            Change change = Change.between(before, after);
            if (change == null) return false;
            undo[layer].addLast(change);
            while (undo[layer].size() > MAX_UNDO_PER_LAYER) undo[layer].removeFirst();
            redo[layer].clear();
            return true;
        }

        public ZonePlan undo(int layer, ZonePlan current) {
            if (!canUndo(layer) || current == null) return null;
            Change change = undo[layer].removeLast();
            if (!change.matches(current, true)) {
                // The server or another player modified the affected chunks.
                undo[layer].clear();
                redo[layer].clear();
                return null;
            }
            ZonePlan reverted = change.apply(current, false);
            redo[layer].addLast(change);
            return reverted;
        }

        public ZonePlan redo(int layer, ZonePlan current) {
            if (!canRedo(layer) || current == null) return null;
            Change change = redo[layer].removeLast();
            if (!change.matches(current, false)) {
                undo[layer].clear();
                redo[layer].clear();
                return null;
            }
            ZonePlan reapplied = change.apply(current, true);
            undo[layer].addLast(change);
            return reapplied;
        }

        private static boolean valid(int layer) { return layer >= 0 && layer < 16; }
    }

    private record Change(Map<ChunkPos, ZoneChunk> before, Map<ChunkPos, ZoneChunk> after) {
        static Change between(ZonePlan from, ZonePlan to) {
            Set<ChunkPos> keys = new HashSet<>(from.getChunkMapping().keySet());
            keys.addAll(to.getChunkMapping().keySet());
            Map<ChunkPos, ZoneChunk> prior = new HashMap<>(), later = new HashMap<>();
            for (ChunkPos key : keys) {
                ZoneChunk a = from.getChunkMapping().get(key);
                ZoneChunk b = to.getChunkMapping().get(key);
                if (equalChunk(a, b)) continue;
                prior.put(key, a == null ? null : new ZoneChunk(a));
                later.put(key, b == null ? null : new ZoneChunk(b));
            }
            return prior.isEmpty() ? null : new Change(prior, later);
        }

        boolean matches(ZonePlan current, boolean expectingAfter) {
            Map<ChunkPos, ZoneChunk> expected = expectingAfter ? after : before;
            for (Map.Entry<ChunkPos, ZoneChunk> entry : expected.entrySet()) {
                if (!equalChunk(current.getChunkMapping().get(entry.getKey()), entry.getValue())) return false;
            }
            return true;
        }

        ZonePlan apply(ZonePlan current, boolean applyAfter) {
            ZonePlan result = new ZonePlan(current);
            Map<ChunkPos, ZoneChunk> changes = applyAfter ? after : before;
            for (Map.Entry<ChunkPos, ZoneChunk> entry : changes.entrySet()) {
                if (entry.getValue() == null) result.getChunkMapping().remove(entry.getKey());
                else result.getChunkMapping().put(entry.getKey(), new ZoneChunk(entry.getValue()));
            }
            return result;
        }

        private static boolean equalChunk(ZoneChunk a, ZoneChunk b) {
            if (a == b) return true;
            if (a == null || b == null) return a == null ? b == null || b.isEmpty() : a.isEmpty();
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if (a.get(x, z) != b.get(x, z)) return false;
                }
            }
            return true;
        }
    }
}
