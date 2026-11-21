package buildcraft.robotics.scheduler;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.WeakHashMap;

import buildcraft.lib.misc.ChunkUtil;
import buildcraft.robotics.RobotTrace;
import buildcraft.robotics.boards.BoardRobotBomber;
import buildcraft.robotics.boards.BoardRobotStripes;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.tile.TileRobotProcessor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Shared, *bounded* candidate discovery for Stripes (empty positions near a
 * worker) and Bomber (ground columns within the panel's marked area). Neither
 * profession can be indexed by the normal all-block discovery catalog:
 * Stripes' work is AIR, and Bomber's explosion excludes an entire footprint.
 *
 * The scheduler only proposes targets. Native search, movement, item-use and
 * TNT permission checks remain in their original boards/AIs. All methods run
 * on the server thread; no chunk is force-loaded or written to NBT.
 */
public final class RobotAreaActionScheduler {
    private static final int SCAN_INTERVAL = 20;
    private static final int MAX_STRIPES_PROBES_PER_WORKER = 24;
    private static final int MAX_BOMBER_PROBES_PER_WORKER = 24;
    private static final int MAX_PROBES_PER_PANEL = 96;
    private static final long CLAIM_TTL = 160L;
    private static final int BOMBER_RANGE_SQ = 100 * 100;
    private static final int BLAST_RADIUS = 6;

    public record Assignment(BlockPos target, long token) { }

    private static final class WorldState {
        final RobotAreaActionQueue queue = new RobotAreaActionQueue();
        final Map<Long, WeakReference<RobotAreaActionScheduler>> panels = new HashMap<>();
        long lastExpire = Long.MIN_VALUE;
        void expire(long now) {
            if (lastExpire == now) return;
            lastExpire = now;
            queue.expire(now);
            panels.entrySet().removeIf(entry -> {
                RobotAreaActionScheduler scheduler = entry.getValue().get();
                return scheduler == null || scheduler.panel.isRemoved();
            });
        }
    }

    private static final Map<Level, WorldState> WORLDS = new WeakHashMap<>();
    private final TileRobotProcessor panel;
    private final Map<Long, Integer> stripesCursors = new HashMap<>();
    private int bomberCursor;
    private int roundRobinStart;
    private long nextScanTick;

    public RobotAreaActionScheduler(TileRobotProcessor panel) { this.panel = panel; }
    private long panelId() { return panel.getBlockPos().asLong(); }

    private static RobotAreaActionQueue.Kind kind(EntityRobotBase robot) {
        if (robot == null) return null;
        if (robot.getBoard() instanceof BoardRobotStripes) return RobotAreaActionQueue.Kind.STRIPES;
        if (robot.getBoard() instanceof BoardRobotBomber) return RobotAreaActionQueue.Kind.BOMBER;
        return null;
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

    private static boolean interested(EntityRobotBase robot) {
        if (kind(robot) == null || robot.isRemoved()
                || robot.getEnergy() < EntityRobotBase.SAFETY_ENERGY
                || robot.getLinkedStation() == null) return false;
        AIRobot active = robot.getActiveAI();
        return active != null && active.isWithinWorkChain(robot.getBoard());
    }

    private static RobotAreaActionScheduler controller(EntityRobotBase robot, WorldState state) {
        RobotAreaActionScheduler chosen = null;
        for (WeakReference<RobotAreaActionScheduler> ref : state.panels.values()) {
            RobotAreaActionScheduler scheduler = ref.get();
            if (scheduler == null || scheduler.panel.isRemoved()
                    || !scheduler.panel.isRobotLinked(robot.getRobotId())) continue;
            if (chosen == null || scheduler.panelId() < chosen.panelId()) chosen = scheduler;
        }
        return chosen;
    }

    public static boolean hasController(EntityRobotBase robot) {
        if (kind(robot) == null) return false;
        WorldState state = WORLDS.get(robotLevel(robot));
        return state != null && controller(robot, state) != null;
    }

    private boolean basicValid(EntityRobotBase robot, BlockPos pos) {
        if (!panel.allowsWorkPosition(pos) || !robotLevel(robot).isLoaded(pos)) return false;
        if (robot.getBoard() instanceof BoardRobotStripes stripes) {
            // Pending target: avoid re-running protection hooks 20x/second.
            return stripes.processorHasItem() && robotLevel(robot).isEmptyBlock(pos);
        }
        return robot.getBoard() instanceof BoardRobotBomber bomber && bomber.processorHasTnt()
                && !robotLevel(robot).getBlockState(pos).isAir() && blastInsidePanel(pos);
    }

    private boolean validFor(EntityRobotBase robot, BlockPos pos) {
        if (!panel.allowsWorkPosition(pos) || !robotLevel(robot).isLoaded(pos)) return false;
        if (robot.getBoard() instanceof BoardRobotStripes stripes) return stripes.processorCanUseAt(pos);
        if (robot.getBoard() instanceof BoardRobotBomber bomber) {
            // A bomb must be confined to the panel's painted work area, not
            // merely have its *center* within the mask.
            if (!blastInsidePanel(pos)) return false;
            return bomber.processorCanBombAt(pos);
        }
        return false;
    }

    private boolean blastInsidePanel(BlockPos center) {
        for (int dx = -BLAST_RADIUS; dx <= BLAST_RADIUS; dx++) {
            for (int dz = -BLAST_RADIUS; dz <= BLAST_RADIUS; dz++) {
                if (dx * dx + dz * dz > BLAST_RADIUS * BLAST_RADIUS) continue;
                if (!panel.allowsWorkPosition(center.offset(dx, 0, dz))) return false;
            }
        }
        return true;
    }

    public void tick(ServerLevel level, List<EntityRobotBase> linkedWorkers) {
        WorldState state = WORLDS.computeIfAbsent(level, ignored -> new WorldState());
        long now = level.getGameTime();
        state.expire(now);
        state.panels.put(panelId(), new WeakReference<>(this));

        Map<Long, EntityRobotBase> eligible = new TreeMap<>();
        for (EntityRobotBase robot : linkedWorkers) {
            if (robot != null && robotLevel(robot) == level && interested(robot)
                    && controller(robot, state) == this) eligible.put(robot.getRobotId(), robot);
        }
        for (long id : panel.getLinkedRobotIds()) {
            EntityRobotBase worker = eligible.get(id);
            if (worker == null) {
                state.queue.clearRobot(panelId(), id);
                stripesCursors.remove(id);
                continue;
            }
            RobotAreaActionQueue.Kind role = kind(worker);
            for (RobotAreaActionQueue.Claim claim : state.queue.jobs(role, panelId(), id)) {
                BlockPos pos = BlockPos.of(claim.key().pos());
                // An executing action retains its claim while in its own AI
                // chain. A pending action is always rechecked before use.
                if ((!claim.active() && !basicValid(worker, pos))
                        || !state.queue.renew(claim, now, CLAIM_TTL)) {
                    state.queue.release(role, claim.key().pos(), id, claim.token(), false, now);
                }
            }
            state.queue.trim(role, panelId(), id, panel.getMaxExecutionQueue());
        }

        if (now < nextScanTick || eligible.isEmpty()) return;
        nextScanTick = now + SCAN_INTERVAL;
        int probes = MAX_PROBES_PER_PANEL;
        List<EntityRobotBase> fairOrder = new ArrayList<>(eligible.values());
        int start = Math.floorMod(roundRobinStart++, fairOrder.size());
        for (int index = 0; index < fairOrder.size() && probes > 0; index++) {
            // A panel with >4 area workers must not starve later robot IDs.
            EntityRobotBase worker = fairOrder.get((start + index) % fairOrder.size());
            RobotAreaActionQueue.Kind role = kind(worker);
            if (state.queue.count(role, panelId(), worker.getRobotId()) >= panel.getMaxExecutionQueue()) continue;
            int allowance = Math.min(probes, role == RobotAreaActionQueue.Kind.STRIPES
                    ? MAX_STRIPES_PROBES_PER_WORKER : MAX_BOMBER_PROBES_PER_WORKER);
            probes -= allowance;
            if (role == RobotAreaActionQueue.Kind.STRIPES) findStripes(level, state, worker, allowance, now);
            else findBombs(level, state, worker, allowance, now);
        }
    }

    private void findStripes(ServerLevel level, WorldState state, EntityRobotBase worker, int allowance, long now) {
        int cursor = stripesCursors.getOrDefault(worker.getRobotId(), 0);
        BlockPos origin = worker.blockPosition();
        int centerX = panel.getBlockPos().getX() >> 4, centerZ = panel.getBlockPos().getZ() >> 4;
        int width = (TileRobotProcessor.WORK_AREA_RADIUS_CHUNKS * 2 + 1) * 16;
        int cells = (width + 7) / 8;
        int grid = cells * cells;
        int startX = (centerX - TileRobotProcessor.WORK_AREA_RADIUS_CHUNKS) << 4;
        int startZ = (centerZ - TileRobotProcessor.WORK_AREA_RADIUS_CHUNKS) << 4;
        for (int i = 0; i < allowance; i++) {
            // Half the budget probes the actual robot's 3D neighborhood;
            // the other half eventually covers the *whole* panel at the
            // world's exposed surface. A painted work area 3 chunks away
            // from the charging dock must not be starved forever.
            int offset = cursor++;
            BlockPos target;
            if ((offset & 1) == 0) {
                int local = offset / 2;
                int ring = 1 + (local / 24) % 24;
                int direction = local % 8;
                int dx = switch (direction) { case 0, 1, 7 -> ring; case 3, 4, 5 -> -ring; default -> 0; };
                int dz = switch (direction) { case 1, 2, 3 -> ring; case 5, 6, 7 -> -ring; default -> 0; };
                int dy = local / 8 % 3 - 1;
                target = origin.offset(dx, dy, dz);
            } else {
                int sample = offset / 2;
                int index = sample % grid;
                int phase = sample / grid % 64;
                int x = startX + (index % cells) * 8 + phase % 8;
                int z = startZ + (index / cells) * 8 + phase / 8;
                if (x >= startX + width || z >= startZ + width
                        || ChunkUtil.getChunk(level, x >> 4, z >> 4, false) == null) continue;
                // The heightmap is read only for an already loaded column.
                int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                target = new BlockPos(x, y, z);
                long deltaX = (long) x - worker.getBlockX();
                long deltaZ = (long) z - worker.getBlockZ();
                if (deltaX * deltaX + deltaZ * deltaZ > 96L * 96L) continue;
            }
            if (target.getY() < level.getMinBuildHeight() || target.getY() >= level.getMaxBuildHeight()
                    || !level.hasChunkAt(target) || !panel.allowsWorkPosition(target)
                    || !state.queue.available(RobotAreaActionQueue.Kind.STRIPES, target.asLong(), now)
                    || !validFor(worker, target)) continue;
            RobotAreaActionQueue.Claim claim = state.queue.reserve(RobotAreaActionQueue.Kind.STRIPES,
                    target.asLong(), panelId(), worker.getRobotId(), panel.getMaxExecutionQueue(), now, CLAIM_TTL);
            if (claim != null && RobotTrace.enabled()) RobotTrace.event(worker, "PROCESSOR_AREA_ASSIGN",
                    "panel=%s role=stripes target=%s queue=%d", panel.getBlockPos(), target,
                    state.queue.count(RobotAreaActionQueue.Kind.STRIPES, panelId(), worker.getRobotId()));
            if (state.queue.count(RobotAreaActionQueue.Kind.STRIPES,
                    panelId(), worker.getRobotId()) >= panel.getMaxExecutionQueue()) break;
        }
        stripesCursors.put(worker.getRobotId(), cursor);
    }

    private void findBombs(ServerLevel level, WorldState state, EntityRobotBase worker, int allowance, long now) {
        // Complete deterministic coarse coverage of 7x7 chunks, with eight
        // sub-cell passes. Unlike random retries this visits every column
        // eventually, yet only examines a few loaded columns per scan tick.
        int cx = panel.getBlockPos().getX() >> 4, cz = panel.getBlockPos().getZ() >> 4;
        int startX = (cx - TileRobotProcessor.WORK_AREA_RADIUS_CHUNKS) << 4;
        int startZ = (cz - TileRobotProcessor.WORK_AREA_RADIUS_CHUNKS) << 4;
        int width = (TileRobotProcessor.WORK_AREA_RADIUS_CHUNKS * 2 + 1) * 16;
        int cells = (width + 7) / 8;
        int grid = cells * cells;
        for (int i = 0; i < allowance; i++) {
            int sample = bomberCursor++;
            int index = sample % grid;
            int phase = sample / grid % 64;
            int x = startX + (index % cells) * 8 + phase % 8;
            int z = startZ + (index / cells) * 8 + phase / 8;
            if (x >= startX + width || z >= startZ + width) continue;
            long dx = (long) x - worker.getBlockX(), dz = (long) z - worker.getBlockZ();
            if (dx * dx + dz * dz > BOMBER_RANGE_SQ) continue;
            // Query the heightmap only AFTER verifying that this chunk is
            // already loaded: the processor must not become a chunk loader.
            if (ChunkUtil.getChunk(level, x >> 4, z >> 4, false) == null) continue;
            int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
            if (y < level.getMinBuildHeight() || y >= level.getMaxBuildHeight() - 20) continue;
            BlockPos target = new BlockPos(x, y, z);
            if (!panel.allowsWorkPosition(target)
                    || !state.queue.available(RobotAreaActionQueue.Kind.BOMBER, target.asLong(), now)
                    || !validFor(worker, target)) continue;
            RobotAreaActionQueue.Claim claim = state.queue.reserve(RobotAreaActionQueue.Kind.BOMBER,
                    target.asLong(), panelId(), worker.getRobotId(), panel.getMaxExecutionQueue(), now, CLAIM_TTL);
            if (claim != null && RobotTrace.enabled()) RobotTrace.event(worker, "PROCESSOR_AREA_ASSIGN",
                    "panel=%s role=bomber target=%s queue=%d", panel.getBlockPos(), target,
                    state.queue.count(RobotAreaActionQueue.Kind.BOMBER, panelId(), worker.getRobotId()));
            if (state.queue.count(RobotAreaActionQueue.Kind.BOMBER,
                    panelId(), worker.getRobotId()) >= panel.getMaxExecutionQueue()) break;
        }
    }

    /** No autonomous search for linked workers while a panel is running. */
    public static Assignment acquire(EntityRobotBase robot) {
        RobotAreaActionQueue.Kind role = kind(robot);
        if (role == null || !interested(robot) || !(robotLevel(robot) instanceof ServerLevel level)) return null;
        WorldState state = WORLDS.get(level);
        if (state == null) return null;
        state.expire(level.getGameTime());
        RobotAreaActionScheduler owner = controller(robot, state);
        if (owner == null) return null;
        List<RobotAreaActionQueue.Claim> queued = state.queue.jobs(role, owner.panelId(), robot.getRobotId());
        queued.sort(Comparator.comparingLong(claim -> {
            BlockPos pos = BlockPos.of(claim.key().pos());
            long dx = (long) robot.getBlockX() - pos.getX();
            long dy = (long) robot.getBlockY() - pos.getY();
            long dz = (long) robot.getBlockZ() - pos.getZ();
            return dx * dx + dy * dy + dz * dz;
        }));
        for (RobotAreaActionQueue.Claim claim : queued) {
            if (claim.active()) continue;
            BlockPos target = BlockPos.of(claim.key().pos());
            if (owner.validFor(robot, target)) {
                RobotAreaActionQueue.Claim active = state.queue.activate(role, claim.key().pos(),
                        owner.panelId(), robot.getRobotId(), level.getGameTime(), CLAIM_TTL);
                if (active != null) return new Assignment(target, active.token());
            }
            state.queue.release(role, claim.key().pos(), robot.getRobotId(), claim.token(), false, level.getGameTime());
        }
        return null;
    }

    public static boolean hasAvailableTarget(EntityRobotBase robot) {
        RobotAreaActionQueue.Kind role = kind(robot);
        if (role == null || !interested(robot) || !(robotLevel(robot) instanceof ServerLevel level)) return false;
        WorldState state = WORLDS.get(level);
        RobotAreaActionScheduler owner = state == null ? null : controller(robot, state);
        if (owner == null) return false;
        for (RobotAreaActionQueue.Claim claim : state.queue.jobs(role, owner.panelId(), robot.getRobotId())) {
            if (!claim.active() && owner.validFor(robot, BlockPos.of(claim.key().pos()))) return true;
        }
        return false;
    }

    public static boolean owns(EntityRobotBase robot, BlockPos target, long token) {
        if (robot == null || target == null || token <= 0 || kind(robot) == null) return false;
        Level level = robotLevel(robot);
        WorldState state = WORLDS.get(level);
        return state != null && state.queue.owns(kind(robot), target.asLong(),
                robot.getRobotId(), token, level.getGameTime());
    }

    public static void finished(EntityRobotBase robot, BlockPos target, long token, boolean success) {
        if (robot == null || target == null || token <= 0) return;
        WorldState state = WORLDS.get(robotLevel(robot));
        if (state != null) state.queue.release(kind(robot), target.asLong(), robot.getRobotId(), token,
                success, robotLevel(robot).getGameTime());
    }

    public int queueCount(long robotId) {
        WorldState state = WORLDS.get(panel.getLevel());
        if (state == null) return 0;
        return state.queue.count(RobotAreaActionQueue.Kind.STRIPES, panelId(), robotId)
                + state.queue.count(RobotAreaActionQueue.Kind.BOMBER, panelId(), robotId);
    }

    public void onQueueLimitChanged() {
        WorldState state = WORLDS.get(panel.getLevel());
        if (state == null) return;
        for (long id : panel.getLinkedRobotIds()) {
            state.queue.trim(RobotAreaActionQueue.Kind.STRIPES, panelId(), id, panel.getMaxExecutionQueue());
            state.queue.trim(RobotAreaActionQueue.Kind.BOMBER, panelId(), id, panel.getMaxExecutionQueue());
        }
    }

    public void onLinkChanged() {
        WorldState state = WORLDS.get(panel.getLevel());
        if (state != null) state.queue.clearPanel(panelId());
        nextScanTick = 0L;
        bomberCursor = 0;
        roundRobinStart = 0;
        stripesCursors.clear();
    }

    public void onRemoved() {
        WorldState state = WORLDS.get(panel.getLevel());
        if (state == null) return;
        state.queue.clearPanel(panelId());
        WeakReference<RobotAreaActionScheduler> ref = state.panels.get(panelId());
        if (ref != null && ref.get() == this) state.panels.remove(panelId());
        stripesCursors.clear();
    }
}
