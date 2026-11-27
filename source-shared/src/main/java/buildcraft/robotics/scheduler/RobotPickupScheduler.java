package buildcraft.robotics.scheduler;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

import buildcraft.lib.internal.area.IZone;
import buildcraft.lib.internal.core.IStackFilter;
import buildcraft.robotics.ai.AIRobotFetchItem;
import buildcraft.robotics.ai.AIRobotGotoBlock;
import buildcraft.robotics.boards.BoardRobotPicker;
import buildcraft.robotics.entity.EntityRobot;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.DockingStation;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.statements.ActionRobotFilter;
import buildcraft.robotics.tile.TileRobotProcessor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/**
 * Stage 9: demand-driven pickup jobs, shared by pickers linked to a panel.
 * Only the existing AIRobotFetchItem may actually move ItemStacks. This class
 * never extracts, deletes, or transfers cargo, and never loads a chunk.
 *
 * One loaded ItemEntity UUID == one job. Reservations are per-dimension and
 * shared across panels, expire when unrefreshed, and are NOT persisted as item
 * entity IDs (entities may merge, despawn or be re-created during reload).
 */
public final class RobotPickupScheduler {
    private static final int SCAN_INTERVAL = 20;
    private static final int MAX_CANDIDATES = 512;
    private static final long CLAIM_TTL = 100L;
    private static final double MAX_RANGE_SQR = 250.0D * 250.0D;

    public record PickupTarget(ItemEntity entity, long leaseToken) { }

    private static final class WorldState {
        final RobotPickupQueue jobs = new RobotPickupQueue();
        final Map<Long, WeakReference<RobotPickupScheduler>> panels = new HashMap<>();
        long lastExpireTick = Long.MIN_VALUE;
        void expire(long now) {
            if (lastExpireTick == now) return;
            lastExpireTick = now;
            jobs.expire(now);
            panels.entrySet().removeIf(entry -> {
                RobotPickupScheduler scheduler = entry.getValue().get();
                return scheduler == null || scheduler.panel.isRemoved();
            });
        }
    }

    private static final Map<Level, WorldState> WORLDS = new WeakHashMap<>();
    private final TileRobotProcessor panel;
    private long nextScanTick;

    public RobotPickupScheduler(TileRobotProcessor panel) {
        this.panel = panel;
    }

    private long panelId() { return panel.getBlockPos().asLong(); }

    public void tick(ServerLevel level, List<EntityRobotBase> readyPickers) {
        WorldState state = WORLDS.computeIfAbsent(level, unused -> new WorldState());
        state.expire(level.getGameTime());
        WeakReference<RobotPickupScheduler> registered = state.panels.get(panelId());
        if (registered == null || registered.get() != this) {
            state.panels.put(panelId(), new WeakReference<>(this));
        }
        long now = level.getGameTime();
        Set<Long> readyIds = new HashSet<>();
        for (EntityRobotBase robot : readyPickers) readyIds.add(robot.getRobotId());

        // Invalid or unloaded robots cannot keep a pending reservation. A
        // running fetch AI separately retains the physical ItemEntity claim.
        for (long robotId : panel.getLinkedRobotIds()) {
            if (!readyIds.contains(robotId)) {
                state.jobs.clearRobot(panelId(), robotId);
                continue;
            }
            EntityRobotBase robot = null;
            for (EntityRobotBase candidate : readyPickers) {
                if (candidate.getRobotId() == robotId) { robot = candidate; break; }
            }
            if (robot == null) continue;
            for (RobotPickupQueue.Claim job : state.jobs.jobs(panelId(), robotId)) {
                Entity entity = level.getEntity(job.itemUuid());
                if (!(entity instanceof ItemEntity item) || !eligibleFor(robot, item)
                        || (BoardRobotPicker.targettedItems.contains(BoardRobotPicker.TargetKey.of(item)) && !job.active())) {
                    state.jobs.release(job.itemUuid(), robotId, job.token());
                } else {
                    state.jobs.renew(job.itemUuid(), panelId(), robotId, job.token(), now, CLAIM_TTL);
                }
            }
        }
        if (readyPickers.isEmpty() || now < nextScanTick) return;
        nextScanTick = now + SCAN_INTERVAL;
        // Compute queue occupancy once. Looking it up inside the item x robot
        // candidate loop would repeatedly scan every claim across the level.
        Map<Long, Integer> queueSizes = new HashMap<>();
        boolean needsWork = false;
        for (EntityRobotBase robot : readyPickers) {
            int count = state.jobs.size(panelId(), robot.getRobotId());
            queueSizes.put(robot.getRobotId(), count);
            if (count < panel.getMaxExecutionQueue()) needsWork = true;
        }
        if (!needsWork) return;

        // The AABB is the 7x7 chunk work area; server entity queries observe
        // only currently loaded entities and do not force-load other chunks.
        int centerX = panel.getBlockPos().getX() >> 4;
        int centerZ = panel.getBlockPos().getZ() >> 4;
        int minX = panel.workMinX(), minZ = panel.workMinZ();
        int maxX = minX + 112, maxZ = minZ + 112;
        AABB bounds = new AABB(minX, level.getMinBuildHeight(), minZ,
                maxX, level.getMaxBuildHeight(), maxZ);
        List<ItemEntity> nearby = level.getEntitiesOfClass(ItemEntity.class, bounds,
                item -> item.isAlive() && !item.getItem().isEmpty()
                        && panel.allowsWorkPosition(item.blockPosition()));
        nearby.sort(Comparator.comparingDouble(item -> {
            double distance = Double.POSITIVE_INFINITY;
            for (EntityRobotBase robot : readyPickers) {
                distance = Math.min(distance, item.distanceToSqr(robot));
            }
            return distance;
        }));
        int inspected = 0;
        for (ItemEntity item : nearby) {
            if (++inspected > MAX_CANDIDATES) break;
            if (BoardRobotPicker.targettedItems.contains(BoardRobotPicker.TargetKey.of(item))
                    || state.jobs.isClaimedByOther(item.getUUID(), -1L, now)) continue;
            EntityRobotBase best = null;
            int smallestQueue = Integer.MAX_VALUE;
            double smallestDistance = Double.POSITIVE_INFINITY;
            for (EntityRobotBase robot : readyPickers) {
                int count = queueSizes.getOrDefault(robot.getRobotId(), 0);
                if (count >= panel.getMaxExecutionQueue() || !eligibleFor(robot, item)) continue;
                double distance = item.distanceToSqr(robot);
                if (count < smallestQueue || (count == smallestQueue && distance < smallestDistance)) {
                    best = robot;
                    smallestQueue = count;
                    smallestDistance = distance;
                }
            }
            if (best != null) {
                if (state.jobs.claim(item.getUUID(), panelId(), best.getRobotId(),
                        panel.getMaxExecutionQueue(), now, CLAIM_TTL) != null) {
                    queueSizes.merge(best.getRobotId(), 1, Integer::sum);
                }
            }
        }
    }

    private boolean eligibleFor(EntityRobotBase robot, ItemEntity item) {
        if (item == null || !item.isAlive() || item.getItem().isEmpty()
                || item.distanceToSqr(robot) >= MAX_RANGE_SQR
                || !panel.allowsWorkPosition(item.blockPosition())
                || robot.isKnownUnreachable(item)) return false;
        IZone zone = robot.getZoneToWork();
        if (zone != null && !zone.contains(item.position())) return false;
        DockingStation station = robot.getLinkedStation();
        if (station == null) return false;
        IStackFilter filter = ActionRobotFilter.getGateFilter(station);
        if (filter != null && !filter.matches(item.getItem())) return false;
        return robot instanceof EntityRobot entityRobot
                && entityRobot.insertIntoInventory(item.getItem(), false).getCount() < item.getItem().getCount();
    }

    public void onRemoved() {
        Level level = panel.getLevel();
        WorldState state = level == null ? null : WORLDS.get(level);
        if (state == null) return;
        state.jobs.clearPanel(panelId());
        WeakReference<RobotPickupScheduler> ref = state.panels.get(panelId());
        if (ref != null && ref.get() == this) state.panels.remove(panelId());
    }

    public void onLinkChanged() {
        Level level = panel.getLevel();
        WorldState state = level == null ? null : WORLDS.get(level);
        if (state != null) state.jobs.clearPanel(panelId());
        nextScanTick = 0L;
    }

    public void onQueueLimitChanged() {
        Level level = panel.getLevel();
        WorldState state = level == null ? null : WORLDS.get(level);
        if (state == null) return;
        for (long id : panel.getLinkedRobotIds()) {
            state.jobs.trim(panelId(), id, panel.getMaxExecutionQueue());
        }
    }

    public int queueCount(long robotId) {
        Level level = panel.getLevel();
        WorldState state = level == null ? null : WORLDS.get(level);
        return state == null ? 0 : state.jobs.size(panelId(), robotId);
    }

    private static Level robotLevel(EntityRobotBase robot) {
        //? if <1.20 {
        return robot.level;
        //?} else {
        /*?
        return robot.level();
        ?*/
        //?}
    }

    private static RobotPickupScheduler controller(EntityRobotBase robot, WorldState state) {
        RobotPickupScheduler selected = null;
        for (WeakReference<RobotPickupScheduler> ref : state.panels.values()) {
            RobotPickupScheduler current = ref.get();
            if (current == null || current.panel.isRemoved()
                    || !current.panel.isRobotLinked(robot.getRobotId())) continue;
            if (selected == null || current.panelId() < selected.panelId()) selected = current;
        }
        return selected;
    }

    public static boolean hasController(EntityRobotBase robot) {
        if (robot == null || !(robot.getBoard() instanceof BoardRobotPicker)) return false;
        WorldState state = WORLDS.get(robotLevel(robot));
        return state != null && controller(robot, state) != null;
    }

    public static boolean ready(EntityRobotBase robot) {
        if (robot == null || robot.isRemoved() || robot.getEnergy() < EntityRobotBase.SAFETY_ENERGY
                || !(robot.getBoard() instanceof BoardRobotPicker) || robot.getLinkedStation() == null) return false;
        AIRobot ai = robot.getActiveAI();
        if (ai == null || !ai.isWithinWorkChain(robot.getBoard())) return false;
        return ai == robot.getBoard() || ai instanceof AIRobotFetchItem || ai instanceof AIRobotGotoBlock;
    }

    public static PickupTarget acquire(EntityRobotBase robot) {
        if (!ready(robot) || !(robotLevel(robot) instanceof ServerLevel level)) return null;
        WorldState state = WORLDS.get(level);
        if (state == null) return null;
        long now = level.getGameTime();
        state.expire(now);
        RobotPickupScheduler scheduler = controller(robot, state);
        if (scheduler == null) return null;
        for (RobotPickupQueue.Claim job : state.jobs.jobs(scheduler.panelId(), robot.getRobotId())) {
            Entity entity = level.getEntity(job.itemUuid());
            if (entity instanceof ItemEntity item && scheduler.eligibleFor(robot, item)
                    && !BoardRobotPicker.targettedItems.contains(BoardRobotPicker.TargetKey.of(item))) {
                RobotPickupQueue.Claim active = state.jobs.activate(job.itemUuid(), scheduler.panelId(),
                        robot.getRobotId(), now, CLAIM_TTL);
                if (active != null) return new PickupTarget(item, active.token());
            }
            state.jobs.release(job.itemUuid(), robot.getRobotId(), job.token());
        }
        return null;
    }

    /** A legacy picker must not steal a different robot's queued target. */
    public static boolean reservedByOther(EntityRobotBase robot, ItemEntity item) {
        WorldState state = WORLDS.get(robotLevel(robot));
        return state != null && state.jobs.isClaimedByOther(item.getUUID(), robot.getRobotId(),
                robotLevel(robot).getGameTime());
    }

    /** Re-check current paint and station/robot permissions before item transfer. */
    public static boolean permitsTarget(EntityRobotBase robot, ItemEntity item) {
        WorldState state = WORLDS.get(robotLevel(robot));
        RobotPickupScheduler scheduler = state == null ? null : controller(robot, state);
        return scheduler == null || scheduler.eligibleFor(robot, item);
    }

    public static void finish(EntityRobotBase robot, UUID itemUuid, long token) {
        if (robot == null || itemUuid == null || token == 0L) return;
        WorldState state = WORLDS.get(robotLevel(robot));
        if (state != null) state.jobs.release(itemUuid, robot.getRobotId(), token);
    }
}
