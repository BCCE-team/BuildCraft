package buildcraft.robotics.boards;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.world.level.Level;

/**
 * Remembers the connected ore deposit chosen by a miner. This is target selection only:
 * navigation, protection checks, inventory handling and work area rules stay in the board/AI.
 *
 * <p>Uses all 26 neighbours because generated ores can be diagonally connected. It never
 * loads chunks and never mistakes a touching deposit of another ore family for the same vein.
 * The capped scan bounds pathological modded ore networks without crossing the work zone.</p>
 */
public final class RobotOreVeinMemory {
    private static final int MAX_VEIN_BLOCKS = 2048;
    private static final int MAX_HORIZONTAL_DISTANCE = 24;
    private static final int MAX_VERTICAL_DISTANCE = 48;

    @FunctionalInterface
    public interface OreFamilyLookup {
        /** Stable ore family of an eligible position, or null if it is not currently mineable. */
        String familyAt(Level level, BlockPos pos);
    }

    private final Set<BlockPos> remaining = new HashSet<>();
    private BlockPos origin;
    private String family;

    public boolean isActive() {
        return !remaining.isEmpty();
    }

    public boolean contains(BlockPos pos) {
        return remaining.contains(pos);
    }

    public void clear() {
        remaining.clear();
        origin = null;
        family = null;
    }

    /** Rebuild exactly once when a new, reachable ore has been selected by the normal search AI. */
    public void remember(Level level, BlockPos first, OreFamilyLookup lookup) {
        clear();
        if (first == null || !level.isLoaded(first)) return;
        String initialFamily = lookup.familyAt(level, first);
        if (initialFamily == null) return;
        origin = first.immutable();
        family = initialFamily;
        discover(level, lookup, first);
        if (remaining.isEmpty()) clear();
    }

    /** Drop mined/changed ore; do not discard ore merely because its chunk unloaded. */
    public void prune(Level level, OreFamilyLookup lookup) {
        if (!isActive()) return;
        for (Iterator<BlockPos> it = remaining.iterator(); it.hasNext();) {
            BlockPos pos = it.next();
            if (level.isLoaded(pos) && !family.equals(lookup.familyAt(level, pos))) {
                it.remove();
            }
        }
        if (remaining.isEmpty()) clear();
    }

    /**
     * Finish a mined block. Refresh the frontier so a vein crossing a chunk border is
     * extended when the other chunk becomes available while mining is in progress.
     */
    public void mined(Level level, BlockPos pos, OreFamilyLookup lookup) {
        if (!isActive() || !remaining.contains(pos)) return;
        remaining.remove(pos);
        if (remaining.isEmpty()) {
            clear();
            return;
        }
        discover(level, lookup, null);
    }

    private void discover(Level level, OreFamilyLookup lookup, BlockPos first) {
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> examined = new HashSet<>();
        if (first != null) {
            queue.add(first);
        } else {
            queue.addAll(remaining);
        }
        while (!queue.isEmpty() && remaining.size() < MAX_VEIN_BLOCKS) {
            BlockPos current = queue.removeFirst();
            if (!examined.add(current) || !withinBounds(current) || !level.isLoaded(current)) continue;
            if (!family.equals(lookup.familyAt(level, current))) continue;
            remaining.add(current.immutable());
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockPos neighbor = current.offset(dx, dy, dz);
                        if (!examined.contains(neighbor) && withinBounds(neighbor)) queue.addLast(neighbor);
                    }
                }
            }
        }
    }

    private boolean withinBounds(BlockPos pos) {
        return origin != null && Math.abs(pos.getX() - origin.getX()) <= MAX_HORIZONTAL_DISTANCE
                && Math.abs(pos.getY() - origin.getY()) <= MAX_VERTICAL_DISTANCE
                && Math.abs(pos.getZ() - origin.getZ()) <= MAX_HORIZONTAL_DISTANCE;
    }

    public void writeTo(CompoundTag boardNbt) {
        if (!isActive() || origin == null || family == null) return;
        CompoundTag data = new CompoundTag();
        data.putLong("origin", origin.asLong());
        data.putString("family", family);
        long[] blocks = new long[remaining.size()];
        int index = 0;
        for (BlockPos pos : remaining) blocks[index++] = pos.asLong();
        data.putLongArray("remaining", blocks);
        boardNbt.put("minerOreVein", data);
    }

    public void readFrom(CompoundTag boardNbt) {
        clear();
        if (!boardNbt.contains("minerOreVein")) return;
        CompoundTag data = boardNbt.getCompound("minerOreVein");
        String storedFamily = data.getString("family");
        if (storedFamily.isEmpty() || !data.contains("origin")) return;
        BlockPos storedOrigin = BlockPos.of(data.getLong("origin"));
        // CompoundTag#getLongArray returns Optional<long[]> on 1.21.11+,
        // while earlier Minecraft versions return long[] directly.
        if (!(data.get("remaining") instanceof LongArrayTag storedBlocks)) return;
        for (long packed : storedBlocks.getAsLongArray()) {
            BlockPos pos = BlockPos.of(packed);
            if (Math.abs(pos.getX() - storedOrigin.getX()) <= MAX_HORIZONTAL_DISTANCE
                    && Math.abs(pos.getY() - storedOrigin.getY()) <= MAX_VERTICAL_DISTANCE
                    && Math.abs(pos.getZ() - storedOrigin.getZ()) <= MAX_HORIZONTAL_DISTANCE
                    && remaining.size() < MAX_VEIN_BLOCKS) {
                remaining.add(pos.immutable());
            }
        }
        if (!remaining.isEmpty()) {
            origin = storedOrigin;
            family = storedFamily;
        }
    }
}