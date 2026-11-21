package buildcraft.robotics.scheduler;

import java.lang.ref.WeakReference;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.UUID;
import java.util.function.Predicate;

import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.permission.WorldOperationKind;
import buildcraft.lib.internal.area.IZone;
import buildcraft.robotics.RobotTrace;
import buildcraft.robotics.boards.BoardRobotButcher;
import buildcraft.robotics.boards.BoardRobotKnight;
import buildcraft.robotics.internal.api2.RobotAutomationSupport;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.tile.TileRobotProcessor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/**
 * Shared discovery/assignment of living targets for Butcher and Knight boards.
 *
 * The processor observes entities once per scan instead of N robot-wide
 * searches. Transient UUID jobs are globally reserved per level, including
 * across overlapping processor work areas. The native AIRobotAttack remains
 * responsible for navigation, hit permission and damage; this scheduler never
 * attacks, teleports, or loads chunks. All calls are server-thread-only.
 */
public final class RobotCombatTargetScheduler {
    private static final int SCAN_TICKS = 20;
    private static final int MAX_CANDIDATES = 512;
    private static final long CLAIM_TTL = 100L;
    private static final double MAX_RANGE_SQ = 250D * 250D;

    public record Assignment(LivingEntity entity, long token) { }

    private static final class WorldState {
        final RobotCombatTargetQueue queue = new RobotCombatTargetQueue();
        final Map<Long, WeakReference<RobotCombatTargetScheduler>> panels = new HashMap<>();
        long lastExpire = Long.MIN_VALUE;
        void expire(long now) {
            if (lastExpire == now) return;
            lastExpire = now;
            queue.expire(now);
            panels.entrySet().removeIf(entry -> {
                RobotCombatTargetScheduler scheduler = entry.getValue().get();
                return scheduler == null || scheduler.panel.isRemoved();
            });
        }
    }

    private static final Map<Level, WorldState> WORLDS = new WeakHashMap<>();
    private final TileRobotProcessor panel;
    private long nextScanTick;

    public RobotCombatTargetScheduler(TileRobotProcessor panel) {
        this.panel = panel;
    }

    private long panelId() { return panel.getBlockPos().asLong(); }

    private static boolean combatBoard(EntityRobotBase robot) {
        return robot != null && (robot.getBoard() instanceof BoardRobotButcher
                || robot.getBoard() instanceof BoardRobotKnight);
    }

    /** Sleeping workers may have candidates prepared, but Gate overrides are not work. */
    private static boolean interested(EntityRobotBase robot) {
        if (!combatBoard(robot) || robot.isRemoved() || robot.getEnergy() < EntityRobotBase.SAFETY_ENERGY
                || robot.getLinkedStation() == null) return false;
        AIRobot ai = robot.getActiveAI();
        return ai != null && ai.isWithinWorkChain(robot.getBoard());
    }

    private static boolean hasSword(EntityRobotBase robot) {
        ItemStack held = robot.getItemBySlot(EquipmentSlot.MAINHAND);
        return !held.isEmpty() && held.getItem() instanceof SwordItem
                && (!held.isDamageableItem() || held.getDamageValue() < held.getMaxDamage());
    }

    private static boolean validFor(EntityRobotBase robot, LivingEntity entity,
            TileRobotProcessor panel, boolean checkPermission) {
        if (entity == null || !entity.isAlive() || entity.isRemoved() || !combatBoard(robot)
                || !panel.allowsWorkPosition(entity.blockPosition())
                || robot.distanceToSqr(entity) >= MAX_RANGE_SQ || robot.isKnownUnreachable(entity)) return false;
        IZone zone = robot.getZoneToWork();
        if (zone != null && !zone.contains(entity.position())) return false;
        if (robot.getBoard() instanceof BoardRobotButcher && !BoardRobotButcher.isValidTarget(entity)) return false;
        if (robot.getBoard() instanceof BoardRobotKnight && !BoardRobotKnight.isHostileTarget(entity)) return false;
        return !checkPermission || RobotAutomationSupport.permitsEntity(robot,
                robot.getCommandSenderWorld(), entity, WorldOperationKind.ENTITY_ATTACK, OperationMode.SIMULATE);
    }

    public void tick(ServerLevel level, List<EntityRobotBase> linkedCombatants) {
        WorldState state = WORLDS.computeIfAbsent(level, ignored -> new WorldState());
        long now = level.getGameTime();
        state.expire(now);
        WeakReference<RobotCombatTargetScheduler> ref = state.panels.get(panelId());
        if (ref == null || ref.get() != this) state.panels.put(panelId(), new WeakReference<>(this));

        Map<Long, EntityRobotBase> eligible = new java.util.TreeMap<>();
        for (EntityRobotBase robot : linkedCombatants) {
            if (interested(robot)) eligible.put(robot.getRobotId(), robot);
        }
        // Revalidate both pending and active claims. Active attacks retain
        // their target while in their work chain, even if temporarily distant.
        for (long id : panel.getLinkedRobotIds()) {
            EntityRobotBase robot = eligible.get(id);
            if (robot == null || !hasSword(robot)) {
                state.queue.clearRobot(panelId(), id);
                continue;
            }
            for (RobotCombatTargetQueue.Claim job : state.queue.jobs(panelId(), id)) {
                var entity = level.getEntity(job.entity());
                boolean valid = entity instanceof LivingEntity living && validFor(robot, living, panel, false);
                if (!valid || !state.queue.renew(job.entity(), panelId(), id,
                        job.token(), now, CLAIM_TTL)) {
                    state.queue.release(job.entity(), id, job.token());
                }
            }
            state.queue.trim(panelId(), id, panel.getMaxExecutionQueue());
        }
        if (now < nextScanTick || eligible.isEmpty()) return;
        nextScanTick = now + SCAN_TICKS;
        Map<Long, Integer> sizes = new HashMap<>();
        boolean needsWork = false;
        for (EntityRobotBase robot : eligible.values()) {
            int size = state.queue.count(panelId(), robot.getRobotId());
            sizes.put(robot.getRobotId(), size);
            if (hasSword(robot) && size < panel.getMaxExecutionQueue()) needsWork = true;
        }
        if (!needsWork) return;

        // The panel's 7x7-chunk footprint, not a per-robot 250-block sweep.
        // Server entity queries only include loaded entities, never generate chunks.
        int cx = panel.getBlockPos().getX() >> 4, cz = panel.getBlockPos().getZ() >> 4;
        int radius = TileRobotProcessor.WORK_AREA_RADIUS_CHUNKS;
        AABB bounds = new AABB((cx - radius) << 4, level.getMinBuildHeight(), (cz - radius) << 4,
                (cx + radius + 1) << 4, level.getMaxBuildHeight(), (cz + radius + 1) << 4);
        boolean haveButchers = eligible.values().stream().anyMatch(robot -> robot.getBoard() instanceof BoardRobotButcher);
        boolean haveKnights = eligible.values().stream().anyMatch(robot -> robot.getBoard() instanceof BoardRobotKnight);
        List<LivingEntity> entities = level.getEntitiesOfClass(LivingEntity.class, bounds,
                entity -> entity.isAlive() && !entity.isRemoved()
                        && panel.allowsWorkPosition(entity.blockPosition())
                        && (haveButchers && BoardRobotButcher.isValidTarget(entity)
                            || haveKnights && BoardRobotKnight.isHostileTarget(entity)));
        entities.sort(Comparator.comparingDouble((LivingEntity entity) -> {
            double distance = Double.POSITIVE_INFINITY;
            for (EntityRobotBase robot : eligible.values()) {
                distance = Math.min(distance, robot.distanceToSqr(entity));
            }
            return distance;
        }).thenComparing(LivingEntity::getUUID));
        int seen = 0;
        for (LivingEntity entity : entities) {
            if (++seen > MAX_CANDIDATES) break;
            if (state.queue.isReserved(entity.getUUID(), now)) continue;
            EntityRobotBase chosen = null;
            int bestQueue = Integer.MAX_VALUE;
            double bestDistance = Double.POSITIVE_INFINITY;
            for (EntityRobotBase robot : eligible.values()) {
                int count = sizes.getOrDefault(robot.getRobotId(), 0);
                if (count >= panel.getMaxExecutionQueue() || !hasSword(robot)
                        || !validFor(robot, entity, panel, true)) continue;
                double distance = robot.distanceToSqr(entity);
                // A closer hunter should not lose a target merely because
                // another hunter has one fewer speculative queue entry.
                if (distance < bestDistance || (distance == bestDistance && count < bestQueue)) {
                    chosen = robot;
                    bestQueue = count;
                    bestDistance = distance;
                }
            }
            if (chosen != null && state.queue.reserve(entity.getUUID(), panelId(),
                    chosen.getRobotId(), panel.getMaxExecutionQueue(), now, CLAIM_TTL) != null) {
                sizes.merge(chosen.getRobotId(), 1, Integer::sum);
                if (RobotTrace.enabled()) RobotTrace.event(chosen, "PROCESSOR_COMBAT_ASSIGN",
                        "panel=%s target=%s kind=%s queue=%d/%d", panel.getBlockPos(),
                        entity.getUUID(), chosen.getBoard().getClass().getSimpleName(),
                        sizes.get(chosen.getRobotId()), panel.getMaxExecutionQueue());
            }
        }
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

    private static RobotCombatTargetScheduler controller(EntityRobotBase robot, WorldState state) {
        RobotCombatTargetScheduler chosen = null;
        for (WeakReference<RobotCombatTargetScheduler> ref : state.panels.values()) {
            RobotCombatTargetScheduler scheduler = ref.get();
            if (scheduler == null || scheduler.panel.isRemoved()
                    || !scheduler.panel.isRobotLinked(robot.getRobotId())) continue;
            if (chosen == null || scheduler.panelId() < chosen.panelId()) chosen = scheduler;
        }
        return chosen;
    }

    public static boolean hasController(EntityRobotBase robot) {
        if (!combatBoard(robot)) return false;
        WorldState state = WORLDS.get(robotLevel(robot));
        return state != null && controller(robot, state) != null;
    }

    /** null means no queued target; caller must not fall back to N independent scans. */
    public static Assignment acquire(EntityRobotBase robot, Predicate<net.minecraft.world.entity.Entity> filter,
            float range, IZone zone) {
        if (!interested(robot) || !hasSword(robot) || !(robotLevel(robot) instanceof ServerLevel level)) return null;
        WorldState state = WORLDS.get(level);
        if (state == null) return null;
        state.expire(level.getGameTime());
        RobotCombatTargetScheduler scheduler = controller(robot, state);
        if (scheduler == null) return null;
        List<RobotCombatTargetQueue.Claim> jobs = state.queue.jobs(scheduler.panelId(), robot.getRobotId());
        jobs.sort(Comparator.comparingDouble(job -> {
            var target = level.getEntity(job.entity());
            return target == null ? Double.POSITIVE_INFINITY : robot.distanceToSqr(target);
        }));
        for (RobotCombatTargetQueue.Claim job : jobs) {
            if (job.active()) continue;
            var target = level.getEntity(job.entity());
            if (target instanceof LivingEntity living && filter.test(living)
                    && robot.distanceToSqr(living) < (double) range * range
                    && (zone == null || zone.contains(living.position()))
                    && scheduler.validFor(robot, living, scheduler.panel, true)) {
                RobotCombatTargetQueue.Claim active = state.queue.activate(job.entity(),
                        scheduler.panelId(), robot.getRobotId(), level.getGameTime(), CLAIM_TTL);
                if (active != null) return new Assignment(living, active.token());
            }
            state.queue.release(job.entity(), robot.getRobotId(), job.token());
        }
        return null;
    }

    /** Existence check for the sleeping work board; does not reserve or activate. */
    public static boolean hasAvailableTarget(EntityRobotBase robot) {
        if (!interested(robot) || !hasSword(robot) || !(robotLevel(robot) instanceof ServerLevel level)) return false;
        WorldState state = WORLDS.get(level);
        RobotCombatTargetScheduler scheduler = state == null ? null : controller(robot, state);
        if (scheduler == null) return false;
        for (RobotCombatTargetQueue.Claim job : state.queue.jobs(scheduler.panelId(), robot.getRobotId())) {
            if (job.active()) continue;
            var entity = level.getEntity(job.entity());
            if (entity instanceof LivingEntity living && scheduler.validFor(robot, living, scheduler.panel, true)) {
                return true;
            }
        }
        return false;
    }

    public static void finished(EntityRobotBase robot, UUID target, long token) {
        if (robot == null || target == null || token <= 0L) return;
        WorldState state = WORLDS.get(robotLevel(robot));
        if (state != null) state.queue.release(target, robot.getRobotId(), token);
    }

    public int queueCount(long robotId) {
        WorldState state = WORLDS.get(panel.getLevel());
        return state == null ? 0 : state.queue.count(panelId(), robotId);
    }

    public void onQueueLimitChanged() {
        WorldState state = WORLDS.get(panel.getLevel());
        if (state == null) return;
        for (long id : panel.getLinkedRobotIds()) state.queue.trim(panelId(), id, panel.getMaxExecutionQueue());
    }

    public void onLinkChanged() {
        WorldState state = WORLDS.get(panel.getLevel());
        if (state != null) state.queue.clearPanel(panelId());
        nextScanTick = 0L;
    }

    public void onRemoved() {
        WorldState state = WORLDS.get(panel.getLevel());
        if (state == null) return;
        state.queue.clearPanel(panelId());
        WeakReference<RobotCombatTargetScheduler> ref = state.panels.get(panelId());
        if (ref != null && ref.get() == this) state.panels.remove(panelId());
    }
}
