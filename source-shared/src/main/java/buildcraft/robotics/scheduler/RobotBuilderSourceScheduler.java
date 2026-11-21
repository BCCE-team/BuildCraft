package buildcraft.robotics.scheduler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import buildcraft.builders.tile.IRobotBuilderTarget;
import buildcraft.builders.tile.TileBuilder;
import buildcraft.builders.tile.TileConstructionMarker;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/**
 * A panel-local directory of loaded Builder / Construction Marker sources.
 *
 * This is deliberately NOT a block-position scanner and does not reserve
 * blueprint cells. Only IRobotBuilderTarget.reserveRobotBuildTasks() knows the
 * order, dependencies, material requirements and authoritative ownership of
 * a construction job. The controller amortizes source discovery and offers
 * nearby sources; the existing Builder board still reserves and executes its
 * batch directly through that API.
 *
 * All access is on the server thread. Source positions, rather than tile
 * references, are retained so unloading a chunk cannot keep its tile alive.
 */
public final class RobotBuilderSourceScheduler {
    private static final int REFRESH_TICKS = 40;
    private static final int EMPTY_RETRY_TICKS = 160;
    /** Material availability differs per robot; never penalize the whole source. */
    private static final int MATERIAL_RETRY_TICKS = 400;
    private static final int MAX_SOURCES = 256;
    private static final int BUILDER_RANGE_SQ = 3 * 64 * 64;
    private static final int MAX_RETRIES = 2048;

    private record Miss(long robotId, long position) { }
    private record MaterialWait(String item, int count, long until) { }

    /** A panel-level view of already native-reserved blueprint operations. */
    public record Operation(BlockPos position, int items) { }
    public record Activity(int robots, int operations, int outstandingItems) { }
    private static final class ActiveBatch {
        final long source;
        final List<Operation> operations;
        ActiveBatch(long source, List<Operation> operations) {
            this.source = source;
            this.operations = new ArrayList<>(operations);
        }
    }

    private final List<BlockPos> sources = new ArrayList<>();
    private final Map<Miss, Long> emptyUntil = new HashMap<>();
    /** User-visible last material shortage, bounded by linked Builder robot IDs. */
    private final Map<Long, MaterialWait> waitingForMaterials = new HashMap<>();
    /** Informational only: BlueprintBuilder and the robot registry own locks. */
    private final Map<Long, ActiveBatch> activeBatches = new HashMap<>();
    private ServerLevel level;
    private long refreshAfter;
    private boolean active;

    public void tick(ServerLevel current, List<EntityRobotBase> workers) {
        if (workers.isEmpty()) {
            clear();
            return;
        }
        if (active && level == current && current.getGameTime() < refreshAfter) return;
        if (level != null && level != current) {
            // A controller recreated in another dimension must never reuse
            // coordinates or robot IDs from its previous world.
            activeBatches.clear();
            emptyUntil.clear();
            waitingForMaterials.clear();
        }
        level = current;
        active = true;
        Set<Long> linked = new HashSet<>();
        for (EntityRobotBase robot : workers) linked.add(robot.getRobotId());
        activeBatches.keySet().retainAll(linked);
        waitingForMaterials.keySet().retainAll(linked);
        long now = current.getGameTime();
        waitingForMaterials.values().removeIf(wait -> wait.until() <= now);
        refreshAfter = now + REFRESH_TICKS;
        sources.clear();
        Set<Long> seen = new HashSet<>();
        List<BlockPos> discovered = new ArrayList<>();
        for (IRobotBuilderTarget target : TileBuilder.getLoadedBuilders()) {
            collect(current, target, workers, seen, discovered);
        }
        for (IRobotBuilderTarget target : TileConstructionMarker.getLoadedMarkers()) {
            collect(current, target, workers, seen, discovered);
        }
        // A global registry can contain hundreds of sources. Bound the index
        // by proximity to actual linked builders, never by registry iteration
        // order (otherwise the nearest blueprint may never get considered).
        discovered.sort(Comparator.comparingDouble((BlockPos at) -> distanceToWorkers(at, workers))
                .thenComparingLong(BlockPos::asLong));
        sources.addAll(discovered.subList(0, Math.min(MAX_SOURCES, discovered.size())));
        Set<Long> retained = new HashSet<>();
        for (BlockPos source : sources) retained.add(source.asLong());
        emptyUntil.entrySet().removeIf(entry -> entry.getValue() <= now
                || !retained.contains(entry.getKey().position()));
    }

    private static double distanceToWorkers(BlockPos at, List<EntityRobotBase> workers) {
        double closest = Double.POSITIVE_INFINITY;
        for (EntityRobotBase robot : workers) {
            closest = Math.min(closest, robot.blockPosition().distSqr(at));
        }
        return closest;
    }

    private void collect(ServerLevel world, IRobotBuilderTarget target, List<EntityRobotBase> workers,
            Set<Long> seen, List<BlockPos> discovered) {
        if (target == null || target.getLevel() != world || !target.canRobotsBuild()) return;
        BlockPos at = target.getBlockPos();
        if (at == null || !world.hasChunkAt(at) || distanceToWorkers(at, workers) > BUILDER_RANGE_SQ) return;
        if (seen.add(at.asLong())) discovered.add(at.immutable());
    }

    /** null means this robot is NOT managed by an initialized panel. */
    public List<IRobotBuilderTarget> preferred(EntityRobotBase robot, int maxDistanceSq) {
        if (!active || level == null || robot == null) return null;
        long now = level.getGameTime();
        BlockPos origin = robot.blockPosition();
        List<IRobotBuilderTarget> eligible = new ArrayList<>();
        for (BlockPos pos : sources) {
            if (origin.distSqr(pos) > maxDistanceSq || !level.hasChunkAt(pos)
                    || emptyUntil.getOrDefault(new Miss(robot.getRobotId(), pos.asLong()), 0L) > now) continue;
            if (level.getBlockEntity(pos) instanceof IRobotBuilderTarget target
                    && target.getLevel() == level && target.canRobotsBuild()) eligible.add(target);
        }
        // A near source that already has many claimed cells receives a small
        // penalty. BlueprintBuilder remains the authority on actual conflicts.
        Map<Long, Integer> load = sourceOperations();
        eligible.sort(Comparator.comparingDouble((IRobotBuilderTarget target) ->
                        origin.distSqr(target.getBlockPos())
                            + Math.min(1024L, (long) load.getOrDefault(target.getBlockPos().asLong(), 0) * 64L))
                .thenComparingLong(target -> target.getBlockPos().asLong()));
        return eligible;
    }

    /** Empty native reservations are retried, but not by every idle robot on every tick. */
    public void attempted(EntityRobotBase robot, IRobotBuilderTarget target, boolean acquired) {
        if (!active || level == null || robot == null || target == null) return;
        Miss key = new Miss(robot.getRobotId(), target.getBlockPos().asLong());
        if (acquired) emptyUntil.remove(key);
        else if (emptyUntil.size() < MAX_RETRIES || emptyUntil.containsKey(key)) {
            emptyUntil.put(key, level.getGameTime() + EMPTY_RETRY_TICKS);
        }
    }

    /** Called after native tasks have been reserved and trimmed to the panel queue. */
    public void claimed(EntityRobotBase robot, BlockPos source, List<Operation> operations) {
        if (!active || robot == null || source == null || operations == null || operations.isEmpty()) return;
        activeBatches.put(robot.getRobotId(), new ActiveBatch(source.asLong(), operations));
        waitingForMaterials.remove(robot.getRobotId());
    }

    /** Completion includes NOOP and failure; native code has released the cell. */
    public void completed(EntityRobotBase robot, BlockPos source, BlockPos position) {
        if (robot == null || source == null || position == null) return;
        ActiveBatch batch = activeBatches.get(robot.getRobotId());
        if (batch == null || batch.source != source.asLong()) return;
        batch.operations.removeIf(op -> op.position().equals(position));
        if (batch.operations.isEmpty()) activeBatches.remove(robot.getRobotId());
    }

    /** Never attempt to release native ResourceIdBlock claims from this ledger. */
    public void released(EntityRobotBase robot, BlockPos source) {
        if (robot == null || source == null) return;
        ActiveBatch batch = activeBatches.get(robot.getRobotId());
        if (batch != null && batch.source == source.asLong()) activeBatches.remove(robot.getRobotId());
    }

    public void materialStalled(EntityRobotBase robot, BlockPos source) {
        materialStalled(robot, source, ItemStack.EMPTY);
    }

    public void materialStalled(EntityRobotBase robot, BlockPos source, ItemStack missing) {
        if (!active || level == null || robot == null || source == null) return;
        Miss key = new Miss(robot.getRobotId(), source.asLong());
        long until = level.getGameTime() + MATERIAL_RETRY_TICKS;
        if (emptyUntil.size() < MAX_RETRIES || emptyUntil.containsKey(key)) {
            emptyUntil.put(key, until);
        }
        String item = missing == null || missing.isEmpty() ? "materials" : String.valueOf(missing.getItem());
        int amount = missing == null || missing.isEmpty() ? 0 : missing.getCount();
        waitingForMaterials.put(robot.getRobotId(), new MaterialWait(item, amount, until));
    }

    /** Non-sensitive diagnostic snapshot: no inventories or tiles are retained. */
    public String materialWaitDescription(long robotId) {
        if (level == null) return null;
        MaterialWait wait = waitingForMaterials.get(robotId);
        if (wait == null || wait.until() <= level.getGameTime()) return null;
        String name = wait.item().length() > 36 ? wait.item().substring(0, 36) : wait.item();
        return wait.count() > 0 ? "Missing " + name + " x" + wait.count() : "Missing materials";
    }

    public Activity activity() {
        int operations = 0;
        int items = 0;
        for (ActiveBatch batch : activeBatches.values()) {
            operations += batch.operations.size();
            for (Operation op : batch.operations) {
                items = (int) Math.min(Integer.MAX_VALUE, (long) items + Math.max(0, op.items()));
            }
        }
        return new Activity(activeBatches.size(), operations, items);
    }

    private Map<Long, Integer> sourceOperations() {
        Map<Long, Integer> loads = new HashMap<>();
        for (ActiveBatch batch : activeBatches.values()) {
            loads.merge(batch.source, batch.operations.size(), Integer::sum);
        }
        return loads;
    }

    public void clear() {
        sources.clear();
        emptyUntil.clear();
        activeBatches.clear();
        waitingForMaterials.clear();
        active = false;
        level = null;
        refreshAfter = 0;
    }
}
