package buildcraft.robotics.scheduler;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.Map;
import java.util.WeakHashMap;

import buildcraft.lib.internal.area.IZone;
import buildcraft.robotics.boards.BoardRobotGenericSearchBlock;
import buildcraft.robotics.boards.BoardRobotPicker;
import buildcraft.robotics.boards.BoardRobotBuilder;
import buildcraft.robotics.boards.BoardRobotButcher;
import buildcraft.robotics.boards.BoardRobotKnight;
import buildcraft.builders.tile.IRobotBuilderTarget;
import buildcraft.builders.snapshot.BlueprintBuilder.RobotBuildTask;
import buildcraft.robotics.ai.AIRobotPumpBlock;
import buildcraft.robotics.RobotTrace;
import buildcraft.robotics.scheduler.ProcessorCandidateBoard;
import buildcraft.lib.misc.ChunkUtil;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.internal.legacy.robots.DockingStation;
import buildcraft.robotics.internal.legacy.robots.IRobotRegistry;
import buildcraft.robotics.internal.legacy.robots.ResourceIdBlock;
import buildcraft.robotics.internal.legacy.robots.RobotManager;
import buildcraft.robotics.tile.TileRobotProcessor;
import buildcraft.robotics.ai.AIRobotSearchBlock;
import buildcraft.robotics.ai.AIRobotSearchAndGotoBlock;
import buildcraft.robotics.ai.AIRobotGotoBlock;
import buildcraft.robotics.ai.AIRobotBreak;
import buildcraft.robotics.ai.AIRobotHarvest;
import buildcraft.robotics.ai.AIRobotPlant;
import buildcraft.robotics.ai.AIRobotUseToolOnBlock;
import buildcraft.robotics.ai.AIRobotSearchNearestBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Stage 1/2: one incremental candidate scanner per loaded panel, plus short-lived
 * preliminary assignments. Existing robot AIs still validate tools, rights, routes,
 * and use the authoritative IRobotRegistry reservation immediately before work.
 * Never loads/generates chunks and never overrides a gate, sleeping robot or charger.
 * The miner queue holds one logical operation per connected ore deposit;
 * individual blocks are execution steps within that operation. It never
 * replaces the miner's native
 * pathfinder, break AI, permission checks or authoritative resource registry.
 */
public final class RobotProcessorWorkScheduler {
    /** Wire-safe scan phases for the control panel GUI. */
    public static final int SCAN_WAITING = 0;
    public static final int SCAN_RUNNING = 1;
    public static final int SCAN_COMPLETE = 2;
    public static final int SCAN_REFRESHING = 3;
    public static final int SCAN_NO_CHUNKS = 4;

    /** Initial coverage is sticky at 100% while periodic passes refresh it.
     * The second percentage always refers to the currently active pass. */
    public record ScanProgress(int percent, int currentPassPercent, int phase, int sections) { }

    private static final int PER_PANEL_BLOCK_BUDGET = 384;
    private static final int PER_LEVEL_BLOCK_BUDGET = 2048;
    /** Rescan loaded sections periodically; external block placement does not
     * necessarily notify the panel. Keep the full pass TPS-bounded. */
    private static final int MAX_SCAN_IDLE_TICKS = 200;
    private static final int LEASE_TICKS = 100;
    private static final int QUEUED_LEASE_TICKS = 100;
    private static final int FAILED_TARGET_RETRY_TICKS = 200;
    /** A complete bounded route search is far more expensive than rejecting a
     * busy source; avoid immediately reassigning the same unreachable deposit. */
    private static final int UNREACHABLE_DEPOSIT_RETRY_TICKS = 1200;
    private static final int MAX_ENQUEUE_PER_TICK = 2;
    /** Re-evaluate speculative travel plans once per second, never every tick. */
    private static final int QUEUE_LOCALITY_CHECK_TICKS = 20;
    private static final int LOCALITY_COOLDOWN_TICKS = 100;
    private static final int MAX_VEIN_MEMBERS = 2048;
    private static final int MAX_SEARCH_TARGETS = 64;
    private static final int MAX_DIRTY_POSITIONS = 256;
    private static final int MAX_DIRTY_EVALUATIONS = 48;
    private static final int MAX_FOCUSED_EVALUATIONS = 128;
    private static final int MAX_FOCUSED_PROBES = 8;
    private static final int FOCUSED_PROBE_RADIUS = 12;
    private static final long PROBE_REPEAT_TICKS = 200;
    public record SuggestedWork(BlockPos position, long leaseToken) { }
    private static final Map<Level, WorldState> WORLDS = new WeakHashMap<>();

    private static final class WorldState {
        final Map<BlockPos, WeakReference<RobotProcessorWorkScheduler>> panels = new HashMap<>();
        final RobotWorkLeases leases = new RobotWorkLeases();
        final RobotSharedSearchBudget routeSearchBudget = new RobotSharedSearchBudget();
        long routeWorkerCountTick = Long.MIN_VALUE;
        int routeWorkerCount = 1;
        /** Avoid waking a miner with a known unreachable target on every tick. */
        final Map<Long, Long> wakeRetryAfter = new HashMap<>();
        long budgetTick = Long.MIN_VALUE;
        int remainingBudget = PER_LEVEL_BLOCK_BUDGET;
        int budget(long tick, int requested) {
            if (budgetTick != tick) {
                budgetTick = tick;
                remainingBudget = PER_LEVEL_BLOCK_BUDGET;
                leases.expire(tick);
                panels.entrySet().removeIf(entry -> {
                    RobotProcessorWorkScheduler scheduler = entry.getValue().get();
                    return scheduler == null || scheduler.panel.isRemoved();
                });
            }
            // A stable tick-order must not let the first six panels starve all
            // others: divide the level budget between registered, loaded panels.
            int activePanels = 0;
            for (WeakReference<RobotProcessorWorkScheduler> reference : panels.values()) {
                RobotProcessorWorkScheduler scheduler = reference.get();
                if (scheduler != null && !scheduler.activeCategories.isEmpty()) activePanels++;
            }
            int fairShare = Math.max(1, PER_LEVEL_BLOCK_BUDGET / Math.max(1, activePanels));
            int amount = Math.min(Math.min(requested, fairShare), remainingBudget);
            remainingBudget -= amount;
            return amount;
        }
    }

    private final TileRobotProcessor panel;
    private final RobotWorkIndex index = new RobotWorkIndex();
    /** Dirty the owning chunk at most once per 200 ticks while catalog grows. */
    private static final long CATALOG_DIRTY_INTERVAL_TICKS = 200L;
    private long lastMarkedCatalogGeneration;
    private long nextCatalogDirtyTick;
    private final RobotExecutionQueue executionQueues = new RobotExecutionQueue();
    /** Transient failed A* regions shared only by linked miners of this panel. */
    private final RobotSharedRouteFailures sharedRouteFailures = new RobotSharedRouteFailures();
    /** A robot shares one cheap charger snapshot across all candidate checks in 20t. */
    private record CachedEnergy(RobotWorkEnergyPlanner.Context context, long refreshed) { }
    private record EnergyPressure(long minimumNeeded, long untilTick) { }
    private final Map<Long, CachedEnergy> chargerSnapshots = new HashMap<>();
    private final Map<Long, EnergyPressure> energyPressure = new HashMap<>();
    private static final long ENERGY_CONTEXT_REFRESH_TICKS = 20L;
    private static final long ENERGY_RECHECK_TICKS = 60L;
    private final RobotPickupScheduler pickupScheduler;
    private final RobotLogisticsScheduler logisticsScheduler;
    /** Blueprint sources have their own task reservation API; never scan them as world blocks. */
    private final RobotBuilderSourceScheduler builderSources = new RobotBuilderSourceScheduler();
    /** One UUID-based target dispatcher for all linked Butcher and Knight robots. */
    private final RobotCombatTargetScheduler combatTargets;
    private final RobotAreaActionScheduler areaActions;
    /** Pending intentions survive unloads; real registry claims and lease tokens never do. */
    private final Map<Long, LinkedHashSet<Long>> deferredMiningTargets = new HashMap<>();
    /** Unreachable target backoff is per robot: other miners may have a route. */
    private final Map<Long, Map<Long, Long>> minerTargetCooldowns = new HashMap<>();
    private final Map<String, List<ProcessorCandidateBoard>> boardsByCategory = new HashMap<>();
    private final Map<String, ProcessorCandidateBoard> activeCategories = new LinkedHashMap<>();
    private final Map<String, Integer> remainingDemand = new HashMap<>();
    private final Map<String, Boolean> sharedCategoryPredicates = new HashMap<>();
    private final LinkedHashSet<BlockPos> dirtyPositions = new LinkedHashSet<>();
    /** Small, fair, server-tick-driven probes around actual ore targets.
     * Their reads share the very same block budget as the background scan. */
    private final LinkedHashMap<Long, RobotLocalDiscoveryProbe> focusedProbes = new LinkedHashMap<>();
    private final LinkedHashMap<Long, Long> probeCooldowns = new LinkedHashMap<>();
    private final Map<Long, BlockPos> minerProbeAnchors = new HashMap<>();
    /** Interleaved chunk+height priorities prevent lower quarry ores from
     * waiting behind a complete sweep of every surface chunk. */
    private record ScanSection(ChunkPos chunk, int sectionY, long priority) { }
    private final List<ScanSection> scanSections = new ArrayList<>();
    private final RobotVerticalSectionScanCursor verticalCursor = new RobotVerticalSectionScanCursor();
    private boolean passComplete;
    private boolean firstPassComplete;
    private boolean scanPassStarted;
    private int loadedChunkCount;
    private long scanScopeFingerprint = Long.MIN_VALUE;
    private long nextPass;
    private String demandFingerprint = "";
    private boolean registered;

    public RobotProcessorWorkScheduler(TileRobotProcessor panel) {
        this.panel = panel;
        this.pickupScheduler = new RobotPickupScheduler(panel);
        this.logisticsScheduler = new RobotLogisticsScheduler(panel);
        this.combatTargets = new RobotCombatTargetScheduler(panel);
        this.areaActions = new RobotAreaActionScheduler(panel);
    }

    /** A read-only snapshot. No chunk scan, NBT write or world lookup occurs
     * when the GUI polls this once every few ticks. */
    public ScanProgress scanProgress() {
        if (activeCategories.isEmpty() || !scanPassStarted) {
            return new ScanProgress(0, 0, SCAN_WAITING, 0);
        }
        if (loadedChunkCount == 0) {
            return new ScanProgress(0, 0, SCAN_NO_CHUNKS, 0);
        }
        int current = verticalCursor.progressPercent();
        if (firstPassComplete) {
            return new ScanProgress(100, current,
                    passComplete ? SCAN_COMPLETE : SCAN_REFRESHING, scanSections.size());
        }
        return new ScanProgress(current, current, SCAN_RUNNING, scanSections.size());
    }

    private static WorldState world(Level level) {
        return WORLDS.computeIfAbsent(level, ignored -> new WorldState());
    }

    public void onRemoved() {
        pickupScheduler.onRemoved();
        logisticsScheduler.onRemoved();
        RobotBuilderMaterialScheduler.releasePanel(panel.getLevel(), panel.getBlockPos().asLong());
        builderSources.clear();
        combatTargets.onRemoved();
        areaActions.onRemoved();
        executionQueues.clearAll();
        deferredMiningTargets.clear();
        minerTargetCooldowns.clear();
        sharedRouteFailures.worldChanged();
        chargerSnapshots.clear();
        energyPressure.clear();
        activeCategories.clear();
        remainingDemand.clear();
        dirtyPositions.clear();
        focusedProbes.clear();
        probeCooldowns.clear();
        minerProbeAnchors.clear();
        scanSections.clear();
        verticalCursor.reset(0);
        scanPassStarted = false;
        firstPassComplete = false;
        loadedChunkCount = 0;
        scanScopeFingerprint = Long.MIN_VALUE;
        registered = false;
        Level level = panel.getLevel();
        if (level == null) return;
        WorldState state = WORLDS.get(level);
        if (state == null) return;
        WeakReference<RobotProcessorWorkScheduler> reference = state.panels.get(panel.getBlockPos());
        if (reference != null && reference.get() == this) state.panels.remove(panel.getBlockPos());
        state.leases.releasePanel(panel.getBlockPos().asLong());
    }

    public void onLinkChanged() {
        pickupScheduler.onLinkChanged();
        logisticsScheduler.onLinkChanged();
        RobotBuilderMaterialScheduler.releasePanel(panel.getLevel(), panel.getBlockPos().asLong());
        builderSources.clear();
        combatTargets.onLinkChanged();
        areaActions.onLinkChanged();
        // A changed policy/connection invalidates all uncommitted work hints.
        // Authoritative robot claims are still owned by IRobotRegistry.
        Level level = panel.getLevel();
        WorldState state = level == null ? null : WORLDS.get(level);
        if (state != null) {
            WeakReference<RobotProcessorWorkScheduler> ref = state.panels.get(panel.getBlockPos());
            if (ref != null && ref.get() == this) state.leases.releasePanel(panel.getBlockPos().asLong());
        }
        executionQueues.clearAll();
        deferredMiningTargets.clear();
        minerTargetCooldowns.clear();
        sharedRouteFailures.worldChanged();
        chargerSnapshots.clear();
        energyPressure.clear();
        remainingDemand.clear();
        dirtyPositions.clear();
        focusedProbes.clear();
        probeCooldowns.clear();
        minerProbeAnchors.clear();
        nextPass = 0;
        scanPassStarted = false;
        firstPassComplete = false;
        loadedChunkCount = 0;
        scanScopeFingerprint = Long.MIN_VALUE;
        demandFingerprint = "";
        index.forgetUnused(List.of());
    }

    /** Live server snapshot. The catalog is soft information, never leases. */
    public RobotWorkIndex.CatalogSnapshot catalogSnapshot() {
        return index.snapshot();
    }

    /**
     * Called once after TileZonePlanner has loaded its work-area layers and
     * the scheduler has reset transient claims. Do NOT inspect world blocks or
     * load chunks here: the loaded catalog is tentative until dispatch.
     */
    public int restoreCatalog(RobotWorkIndex.CatalogSnapshot snapshot) {
        Level level = panel.getLevel();
        if (level == null) return 0;
        int count = index.restore(snapshot, packed -> {
            BlockPos pos = BlockPos.of(packed);
            return pos.getY() >= level.getMinBuildHeight()
                    && pos.getY() < level.getMaxBuildHeight()
                    && panel.allowsWorkPosition(pos);
        });
        lastMarkedCatalogGeneration = index.generation();
        nextCatalogDirtyTick = 0L;
        return count;
    }

    /** Only the server tick schedules a chunk save; GUI polling never does. */
    public void flushCatalogDirty(long now) {
        if (now < nextCatalogDirtyTick || index.generation() == lastMarkedCatalogGeneration) return;
        lastMarkedCatalogGeneration = index.generation();
        nextCatalogDirtyTick = now + CATALOG_DIRTY_INTERVAL_TICKS;
        panel.setChanged();
    }

    /** Must run on the server main thread and only while the panel is loaded. */
    public void tick(ServerLevel level) {
        WorldState state = world(level);
        if (!registered || state.panels.get(panel.getBlockPos()) == null) {
            state.panels.put(panel.getBlockPos().immutable(), new WeakReference<>(this));
            registered = true;
        }
        activeCategories.clear();
        boardsByCategory.clear();
        remainingDemand.clear();
        sharedCategoryPredicates.clear();
        IRobotRegistry registry = RobotManager.registryProvider == null
                ? null : RobotManager.registryProvider.getRegistry(level);
        if (registry == null) return;
        List<BlockPos> consumers = new ArrayList<>();
        List<EntityRobotBase> readyMiners = new ArrayList<>();
        List<EntityRobotBase> readyWorkers = new ArrayList<>();
        List<EntityRobotBase> readyPickers = new ArrayList<>();
        Map<String, List<BlockPos>> byCategory = new HashMap<>();
        List<Long> linkedIds = panel.getLinkedRobotIds();
        Set<String> queuedCategories = new HashSet<>();
        List<EntityRobotBase> builderWorkers = new ArrayList<>();
        List<EntityRobotBase> combatWorkers = new ArrayList<>();
        List<EntityRobotBase> areaWorkers = new ArrayList<>();
        for (long id : linkedIds) {
            EntityRobotBase robot = registry.getLoadedRobot(id);
            if (robot != null && (robot.getBoard() instanceof BoardRobotButcher
                    || robot.getBoard() instanceof BoardRobotKnight)
                    && robotLevelIs(level, robot) && isPrimaryController(state, id)) {
                combatWorkers.add(robot);
            }
            if (robot != null && (robot.getBoard() instanceof buildcraft.robotics.boards.BoardRobotBomber
                    || robot.getBoard() instanceof buildcraft.robotics.boards.BoardRobotStripes)
                    && robotLevelIs(level, robot) && isPrimaryController(state, id)) {
                areaWorkers.add(robot);
            }
            if (robot != null && robot.getBoard() instanceof BoardRobotBuilder
                    && robotLevelIs(level, robot) && isPrimaryController(state, id)) {
                builderWorkers.add(robot);
            }
            if (RobotPickupScheduler.ready(robot) && robot.getBoard() instanceof BoardRobotPicker
                    && robotLevelIs(level, robot) && isPrimaryController(state, robot.getRobotId())) {
                readyPickers.add(robot);
            }
            if (robot == null || !(robot.getBoard() instanceof ProcessorCandidateBoard board)
                    || !robotLevelIs(level, robot) || !isPrimaryController(state, id)) continue;
            boolean queued = isQueuedWorker(robot);
            boolean working = ready(robot);
            // The discovery catalog belongs to the PANEL, not to the currently
            // active AI. A miner sleeping at its dock is still interested in ore.
            // Only dispatch/leases require ready(), never background discovery.
            if ((!working && !queued) || (!board.processorAcceptsHints() && !queued)) continue;
            if (working && isQueueMiner(robot)) readyMiners.add(robot);
            if (working && queued) readyWorkers.add(robot);
            String category = board.processorWorkCategory();
            if (category != null && !category.isEmpty()) {
                activeCategories.putIfAbsent(category, board);
                boardsByCategory.computeIfAbsent(category, ignored -> new ArrayList<>()).add(board);
                byCategory.computeIfAbsent(category, ignored -> new ArrayList<>())
                        .add(robot.blockPosition());
                if (queued) queuedCategories.add(category);
            }
        }
        builderSources.tick(level, builderWorkers);
        combatTargets.tick(level, combatWorkers);
        areaActions.tick(level, areaWorkers);
        pickupScheduler.tick(level, readyPickers);
        logisticsScheduler.tick(level);
        // There is no duplicated discovery work to eliminate when only one
        // robot in a category is ready. Leave its existing autonomous search
        // untouched and do not spend extra scanning CPU for that category.
        activeCategories.keySet().removeIf(category -> byCategory.get(category).size() < 2
                && !queuedCategories.contains(category));
        // Reuse the connected deposit already discovered by a miner. One
        // deposit occupies one queue slot, regardless of its block count.
        seedMinerVeins(level, registry, readyMiners);
        rehydrateMiningQueues(level, state, registry, readyWorkers);
        maintainMiningQueues(level, state, registry, readyWorkers);
        // A panel may start with an empty index. Prime the area around each
        // ready miner without waiting for the huge background chunk pass.
        long now = level.getGameTime();
        sharedRouteFailures.expire(now);
        for (EntityRobotBase worker : readyWorkers) {
            long id = worker.getRobotId();
            if (executionQueues.size(id) >= panel.getMaxExecutionQueue()) continue;
            BlockPos at = worker.blockPosition();
            BlockPos last = minerProbeAnchors.get(id);
            long dx = last == null ? 0 : (long) at.getX() - last.getX();
            long dy = last == null ? 0 : (long) at.getY() - last.getY();
            long dz = last == null ? 0 : (long) at.getZ() - last.getZ();
            if (last == null || dx * dx + dy * dy + dz * dz >= 64L) {
                minerProbeAnchors.put(id, at.immutable());
                scheduleFocusedProbe(at, now);
            }
        }
        minerProbeAnchors.keySet().retainAll(linkedIds);
        chargerSnapshots.keySet().retainAll(linkedIds);
        energyPressure.keySet().retainAll(linkedIds);
        for (String category : activeCategories.keySet()) {
            sharedCategoryPredicates.put(category,
                    boardsByCategory.get(category).stream()
                            .allMatch(ProcessorCandidateBoard::processorSharedScanPredicate));
            int missing;
            missing = 0;
            for (EntityRobotBase worker : readyWorkers) {
                ProcessorCandidateBoard workerBoard = (ProcessorCandidateBoard) worker.getBoard();
                if (!category.equals(workerBoard.processorWorkCategory())) continue;
                missing += Math.max(0, panel.getMaxExecutionQueue()
                        - executionQueues.size(worker.getRobotId()));
            }
            // Discovery is never capped by the robot's execution window. Scan
            // even when every queue is full or every miner is at its dock.
            if (queuedCategories.contains(category)) missing = Math.max(1, missing);
            else if (missing == 0) missing = byCategory.get(category).size();
            remainingDemand.put(category, missing);
            if (missing > 0) consumers.addAll(byCategory.get(category));
        }
        // Do not erase previously found ores just because linked robots or
        // their chunks are temporarily unloaded. Explicit policy/link changes
        // clear the catalog in onLinkChanged().
        // A periodic bounded trace distinguishes an empty candidate cache from
        // an AI that ignores already prepared orders. No per-tick log spam.
        // This trace also works while all miners are parked at a dock; it
        // distinguishes ore unseen at low Y from a target rejected by A*.
        if (RobotTrace.enabled() && now % 200L == 0L && queuedCategories.contains("buildcraft:miner")) {
            for (long minerId : linkedIds) {
                EntityRobotBase miner = registry.getLoadedRobot(minerId);
                if (!isQueueMiner(miner) || !isPrimaryController(state, minerId)) continue;
                RobotWorkIndex.HeightExtent height = index.heightExtent("buildcraft:miner", panel.getBlockPos().getY());
                RobotTrace.event(miner, "PROCESSOR_VERTICAL_HEALTH",
                        "panel=%s known=%d lowerThanPanel=%d indexedY=%d..%d slice=%d/%d section=%d/%d scanComplete=%s",
                        panel.getBlockPos(), height.total(), height.below(), height.minY(), height.maxY(),
                        verticalCursor.sliceIndex(), RobotVerticalSectionScanCursor.SLICE_COUNT,
                        verticalCursor.sectionIndex(), scanSections.size(), passComplete);
                break;
            }
        }
        if (RobotTrace.enabled() && now % 100L == 0L) {
            for (EntityRobotBase miner : readyMiners) {
                int count = executionQueues.size(miner.getRobotId());
                if (count >= panel.getMaxExecutionQueue()) continue;
                RobotTrace.event(miner, "PROCESSOR_QUEUE_HEALTH",
                        "panel=%s assigned=%d/%d indexed=%d probes=%d scan=%d/%d slice=%d/%d complete=%s",
                        panel.getBlockPos(), count, panel.getMaxExecutionQueue(),
                        index.count("buildcraft:miner", now), focusedProbes.size(),
                        verticalCursor.sectionIndex(), scanSections.size(),
                        verticalCursor.sliceIndex(), RobotVerticalSectionScanCursor.SLICE_COUNT, passComplete);
            }
        }
        if (activeCategories.isEmpty()) {
            scanSections.clear();
            verticalCursor.reset(0);
            passComplete = false;
            scanPassStarted = false;
            firstPassComplete = false;
            loadedChunkCount = 0;
            demandFingerprint = "";
            return;
        }
        if (consumers.isEmpty()) return;
        consumers.sort(Comparator.comparingLong(BlockPos::asLong));
        // Worker's movement is not a policy change. Restarting the entire
        // scan whenever a miner crosses a section/chunk boundary starves the
        // remainder of the 7x7 area as miners travel to successive targets.
        // The next completed pass will be reordered around their new position.
        String signature = activeCategories.keySet().toString();
        if (!signature.equals(demandFingerprint)) {
            demandFingerprint = signature;
            preparePass(level, consumers);
        }
        // The level may have acquired chunks after the previous pass. Rebuild
        // the loaded-only work order, without ever loading missing chunks.
        if ((passComplete || scanSections.isEmpty()) && now >= nextPass) {
            preparePass(level, consumers);
            if (scanSections.isEmpty()) nextPass = now + MAX_SCAN_IDLE_TICKS;
        }
        // Dirty changes must still be processed while the full pass is sleeping.
        // A full cache of ALREADY RESERVED targets is not a useful work pool.
        // Count unclaimed, loaded, in-zone candidates per category.
        boolean full = true;
        for (var entry : remainingDemand.entrySet()) {
            int needed = entry.getValue();
            if (needed <= 0) continue;
            int desired = Math.min(RobotWorkIndex.MAX_CANDIDATES_PER_CATEGORY,
                    Math.max(2, needed * 2));
            // A catalog is NOT a queue window. Continue the bounded pass even
            // if enough work has already been discovered to fill every slot.
            if (queuedCategories.contains(entry.getKey())) {
                full = false;
                break;
            }
            int available = index.countMatching(entry.getKey(), now, packed -> {
                BlockPos pos = BlockPos.of(packed);
                return level.hasChunkAt(pos) && panel.allowsWorkPosition(pos)
                        && state.leases.available(packed, -1L, panel.getBlockPos().asLong(), now)
                        && !registry.isTaken(new ResourceIdBlock(pos));
            });
            if (available < desired) { full = false; break; }
        }
        if (full && dirtyPositions.isEmpty()) return;
        int budget = state.budget(now, PER_PANEL_BLOCK_BUDGET);
        int processedDirty = 0;
        int urgent = Math.min(MAX_DIRTY_EVALUATIONS, budget);
        for (; processedDirty < urgent && !dirtyPositions.isEmpty(); processedDirty++) {
            BlockPos pos = dirtyPositions.iterator().next();
            dirtyPositions.remove(pos);
            observePosition(level, pos, now);
        }
        budget -= processedDirty;
        // Unlike the full section scan, a focused probe reaches ores separated
        // by one air block in its second shell (at most 124 coordinates).
        // Rotate probes so a busy miner cannot starve other miners; both scans
        // remain inside the same shared per-level/per-panel block budget.
        int focused = Math.min(MAX_FOCUSED_EVALUATIONS, budget);
        for (int i = 0; i < focused && !focusedProbes.isEmpty(); i++) {
            var iterator = focusedProbes.entrySet().iterator();
            var entry = iterator.next();
            long key = entry.getKey();
            RobotLocalDiscoveryProbe probe = entry.getValue();
            iterator.remove();
            RobotLocalDiscoveryProbe.Position coord = probe.next();
            if (coord != null) {
                observePosition(level, new BlockPos(coord.x(), coord.y(), coord.z()), now);
                budget--;
            }
            if (probe.hasNext()) focusedProbes.put(key, probe);
        }
        if (full || scanSections.isEmpty() || now < nextPass || passComplete) return;
        for (int i = 0; i < budget && !passComplete; i++) {
            ScanSection section = scanSections.get(verticalCursor.sectionIndex());
            ChunkPos chunk = section.chunk();
            int sy = section.sectionY();
            // Skip air-only sections in O(1) instead of checking 4096 voxels.
            // A chunk is retrieved WITHOUT loading it, and every later read is
            // again guarded against unload in this tick.
            if (verticalCursor.sliceOffset() == 0 && isEmptySection(level, chunk, sy)) {
                advanceSection(now);
                continue;
            }
            int x = (chunk.x << 4) + verticalCursor.localX();
            int z = (chunk.z << 4) + verticalCursor.localZ();
            int y = (sy << 4) + verticalCursor.localY();
            BlockPos pos = new BlockPos(x, y, z);
            advance(now);
            observePosition(level, pos, now);
        }
    }

    private void observePosition(ServerLevel level, BlockPos pos, long now) {
        if (pos.getY() < level.getMinBuildHeight() || pos.getY() >= level.getMaxBuildHeight()
                || !level.hasChunkAt(pos) || !panel.allowsWorkPosition(pos)) return;
        BlockState block = level.getBlockState(pos);
        if (block.isAir()) {
            index.invalidatePosition(pos.asLong());
            return;
        }
        // One world-state read feeds all demanded categories. No permission or
        // tool decision is cached: that belongs to the individual robot.
        for (var entry : activeCategories.entrySet()) {
            if (remainingDemand.getOrDefault(entry.getKey(), 0) <= 0) continue;
            boolean candidate = false;
            List<ProcessorCandidateBoard> boards = boardsByCategory.getOrDefault(entry.getKey(), List.of());
            if (!boards.isEmpty() && sharedCategoryPredicates.getOrDefault(entry.getKey(), false)) {
                // The result is independent of which robot owns the board.
                candidate = boards.get(0).processorDiscoveryCandidate(level, pos, block);
            } else {
                // Trees, ore veins and seed-specific planting require the
                // union of each worker's own current eligibility constraints.
                for (ProcessorCandidateBoard board : boards) {
                    if (board.processorDiscoveryCandidate(level, pos, block)) {
                        candidate = true;
                        break;
                    }
                }
            }
            if (candidate) index.observe(entry.getKey(), pos.asLong(), now);
            else index.invalidate(entry.getKey(), pos.asLong());
        }
    }

    private boolean isEmptySection(ServerLevel level, ChunkPos pos, int sectionY) {
        var loadedChunk = ChunkUtil.getChunk(level, pos.x, pos.z, false);
        if (loadedChunk == null) return true;
        int index = sectionY - (level.getMinBuildHeight() >> 4);
        var sections = loadedChunk.getSections();
        if (index < 0 || index >= sections.length || sections[index].hasOnlyAir()) return true;
        // PalettedContainer.maybeHas consults the SECTION PALETTE rather than
        // checking 4096 coordinates. With only miners linked, a stone-only
        // section can be skipped safely; modded ore tags are handled by their
        // platform-specific BoardRobotMiner.isOre predicate. Other professions
        // conservatively return true unless they provide their own filter.
        return !sections[index].getStates().maybeHas(state -> {
            for (var category : activeCategories.entrySet()) {
                if (remainingDemand.getOrDefault(category.getKey(), 0) <= 0) continue;
                for (ProcessorCandidateBoard board : boardsByCategory.getOrDefault(category.getKey(), List.of())) {
                    if (board.processorPaletteMightContain(state)) return true;
                }
            }
            return false;
        });
    }

    private void advanceSection(long now) {
        verticalCursor.skipSection();
        finishPassIfComplete(now);
    }

    private void finishPassIfComplete(long now) {
        if (!verticalCursor.complete()) return;
        passComplete = true;
        if (loadedChunkCount > 0) firstPassComplete = true;
        nextPass = now + MAX_SCAN_IDLE_TICKS;
    }

    private void scheduleFocusedProbe(BlockPos origin, long now) {
        if (!panel.allowsWorkPosition(origin)) return;
        long packed = origin.asLong();
        Long last = probeCooldowns.get(packed);
        if (focusedProbes.containsKey(packed) || last != null && now - last < PROBE_REPEAT_TICKS) return;
        focusedProbes.put(packed, new RobotLocalDiscoveryProbe(
                origin.getX(), origin.getY(), origin.getZ(), FOCUSED_PROBE_RADIUS));
        probeCooldowns.remove(packed);
        probeCooldowns.put(packed, now);
        while (focusedProbes.size() > MAX_FOCUSED_PROBES) {
            focusedProbes.remove(focusedProbes.keySet().iterator().next());
        }
        // Keep the repeat filter bounded, independently of the number of
        // successful mining operations over the lifetime of a world.
        while (probeCooldowns.size() > 128) {
            probeCooldowns.remove(probeCooldowns.keySet().iterator().next());
        }
    }

    /** Local mutation notification. Unknown external changes are caught by
     * periodic full passes and by final per-robot candidate revalidation. */
    public static void worldBlockChanged(Level level, BlockPos pos) {
        WorldState state = WORLDS.get(level);
        if (state == null || pos == null) return;
        for (var ref : state.panels.values()) {
            RobotProcessorWorkScheduler scheduler = ref.get();
            if (scheduler == null || scheduler.panel.isRemoved()) continue;
            scheduler.index.invalidatePosition(pos.asLong());
            // Even a change far from the ore can open a traversable corridor.
            // Discard negative route hints without discarding the ore catalog.
            scheduler.sharedRouteFailures.worldChanged();
            scheduler.markDirty(pos);
            scheduler.markDirty(pos.above());
            scheduler.markDirty(pos.below());
            scheduler.markDirty(pos.offset(1, 0, 0));
            scheduler.markDirty(pos.offset(-1, 0, 0));
            scheduler.markDirty(pos.offset(0, 0, 1));
            scheduler.markDirty(pos.offset(0, 0, -1));
        }
    }

    private void markDirty(BlockPos pos) {
        if (!panel.allowsWorkPosition(pos)) return;
        dirtyPositions.add(pos.immutable());
        if (dirtyPositions.size() > MAX_DIRTY_POSITIONS) {
            dirtyPositions.remove(dirtyPositions.iterator().next());
        }
    }

    private void preparePass(ServerLevel level, List<BlockPos> consumers) {
        scanSections.clear();
        int chunks = 0;
        long scope = 1469598103934665603L ^ demandFingerprint.hashCode();
        int cx = panel.getBlockPos().getX() >> 4, cz = panel.getBlockPos().getZ() >> 4;
        List<BlockPos> anchors = new ArrayList<>(consumers);
        anchors.add(panel.getBlockPos());
        int min = level.getMinBuildHeight() >> 4;
        int max = (level.getMaxBuildHeight() - 1) >> 4;
        for (int dx = -TileRobotProcessor.WORK_AREA_RADIUS_CHUNKS;
                dx <= TileRobotProcessor.WORK_AREA_RADIUS_CHUNKS; dx++) {
            for (int dz = -TileRobotProcessor.WORK_AREA_RADIUS_CHUNKS;
                    dz <= TileRobotProcessor.WORK_AREA_RADIUS_CHUNKS; dz++) {
                int x = cx + dx, z = cz + dz;
                if (ChunkUtil.getChunk(level, x, z, false) == null) continue;
                chunks++;
                scope = (scope ^ (((long) x << 32) ^ (z & 0xffffffffL))) * 1099511628211L;
                ChunkPos chunk = new ChunkPos(x, z);
                for (int sy = min; sy <= max; sy++) {
                    // Do the palette test while BUILDING the pass: stone-only,
                    // air-only and unloaded vertical sections need no 4096-block
                    // scan and should not consume a round-robin slot.
                    if (isEmptySection(level, chunk, sy)) continue;
                    long score = Long.MAX_VALUE;
                    for (BlockPos anchor : anchors) {
                        long sx = x - (anchor.getX() >> 4);
                        long sz = z - (anchor.getZ() >> 4);
                        long vertical = Math.abs((long) sy - (anchor.getY() >> 4));
                        // Prioritize deep sections in the same shaft over far
                        // surface chunks. The round-robin still visits EVERY
                        // eligible section, so lower layers never starve.
                        score = Math.min(score, 4L * (sx * sx + sz * sz) + vertical);
                    }
                    scanSections.add(new ScanSection(chunk, sy, score));
                }
            }
        }
        scanSections.sort(Comparator.comparingLong(ScanSection::priority)
                .thenComparingInt(section -> section.chunk().x)
                .thenComparingInt(section -> section.chunk().z)
                .thenComparingInt(ScanSection::sectionY));
        verticalCursor.reset(scanSections.size());
        // A changed set of loaded chunks or a profession/policy change requires
        // a fresh first pass: the old 100% cannot describe newly loaded blocks.
        if (scanPassStarted && scanScopeFingerprint != scope) firstPassComplete = false;
        scanScopeFingerprint = scope;
        loadedChunkCount = chunks;
        scanPassStarted = true;
        passComplete = verticalCursor.complete();
        if (passComplete && loadedChunkCount > 0) firstPassComplete = true;
        nextPass = passComplete ? level.getGameTime() + MAX_SCAN_IDLE_TICKS : 0;
    }

    private void advance(long now) {
        verticalCursor.advanceBlock();
        finishPassIfComplete(now);
    }

    public static boolean ready(EntityRobotBase robot) {
        if (robot == null || robot.isRemoved() || robot.getEnergy() < EntityRobotBase.SAFETY_ENERGY
                || robot.getBoard() == null) return false;
        AIRobot ai = robot.getActiveAI();
        if (ai == null || !ai.isWithinWorkChain(robot.getBoard())) return false;
        // Fetching tools, returning home, being held at a gate or charging do
        // not create a demand for more world scanning.
        return ai == robot.getBoard() || ai instanceof AIRobotSearchBlock
                || ai instanceof AIRobotSearchNearestBlock
                || ai instanceof AIRobotSearchAndGotoBlock || ai instanceof AIRobotGotoBlock
                || ai instanceof AIRobotBreak || ai instanceof AIRobotHarvest
                || ai instanceof AIRobotPlant || ai instanceof AIRobotUseToolOnBlock;
    }

    private static boolean robotLevelIs(Level level, EntityRobotBase robot) {
        //? if <1.20 {
        return robot.level == level;
        //?} else {
        return robot.level() == level;
        //?}
    }

    /**
     * An idle miner does not create normal queue demand, but the panel may
     * discover new work while the robot sleeps. Poll at most once per 20 ticks
     * from AIRobotSleep; do not wake for absent tools, forbidden gates, stale
     * blocks, foreign claims, or an uncharged robot. The usual board and path
     * search run after wake-up, not inside this method.
     */
    public static boolean hasAvailableWorkForSleepingRobot(EntityRobotBase robot) {
        if (robot == null || robot.getEnergy() < EntityRobotBase.SAFETY_ENERGY
                || !(robot.getBoard() instanceof ProcessorCandidateBoard board)
                || !isQueuedWorker(robot)) return false;
        // Do not wake a robot held asleep by an unrelated Gate/API command.
        AIRobot current = robot.getActiveAI();
        if (current == null || !current.isWithinWorkChain(robot.getBoard())) return false;
        Level level = robotLevel(robot);
        WorldState state = WORLDS.get(level);
        if (state == null) return false;
        long now = level.getGameTime(), id = robot.getRobotId();
        if (state.wakeRetryAfter.getOrDefault(id, 0L) > now) return false;
        for (var ref : state.panels.values()) {
            RobotProcessorWorkScheduler scheduler = ref.get();
            if (scheduler == null || scheduler.panel.isRemoved()
                    || !scheduler.panel.isRobotLinked(id)
                    || !scheduler.isPrimaryController(state, id)) continue;
            String category = board.processorWorkCategory();
            if (!scheduler.activeCategories.containsKey(category)) continue;
            for (long packed : scheduler.index.nearest(category, robot.getBlockX(), robot.getBlockY(),
                    robot.getBlockZ(), now, 32, pos -> state.leases.available(
                            pos, id, scheduler.panel.getBlockPos().asLong(), now))) {
                BlockPos pos = BlockPos.of(packed);
                if (!level.hasChunkAt(pos) || !scheduler.panel.allowsWorkPosition(pos)) continue;
                if (level.getBlockState(pos).isAir()) {
                    scheduler.index.invalidate(category, packed);
                    continue;
                }
                IRobotRegistry registry = robot.getRegistry();
                if (registry != null && registry.isTaken(new ResourceIdBlock(pos))) continue;
                // A pathfinding failure is not a new task just because the
                // robot went to sleep. Let its cooldown expire before waking.
                if (isQueueMiner(robot) && scheduler.minerTargetCooldowns
                        .getOrDefault(id, Map.of()).getOrDefault(packed, 0L) > now) continue;
                if (board.processorPlanningEligible(level, pos)) {
                    RobotWorkEnergyBudget.Estimate power = scheduler.energyEstimate(robot, pos, 1, now);
                    if (!power.affordable()) {
                        scheduler.energyRejected(robot, pos, power, now);
                        continue;
                    }
                    scheduler.energyPressure.remove(id);
                    state.wakeRetryAfter.put(id, now + 200L);
                    if (RobotTrace.enabled()) RobotTrace.event(robot, "PROCESSOR_WORK_WAKE",
                            "panel=%s target=%s", scheduler.panel.getBlockPos(), pos);
                    return true;
                }
                if (!board.processorDiscoveryCandidate(level, pos)) scheduler.index.invalidate(category, packed);
            }
        }
        return false;
    }

    /**
     * Builder sources are NOT voxels. A panel shares a snapshot of loaded
     * Builder / Construction Marker blocks; the board alone reserves actual
     * blueprint tasks. null preserves the autonomous behavior for unlinked
     * robots, including when a panel is loading or has just been removed.
     */
    public static List<IRobotBuilderTarget> builderSourcesFor(EntityRobotBase robot, int maxDistanceSq) {
        if (robot == null || !(robot.getBoard() instanceof BoardRobotBuilder)) return null;
        WorldState state = WORLDS.get(robotLevel(robot));
        if (state == null) return null;
        for (var ref : state.panels.values()) {
            RobotProcessorWorkScheduler scheduler = ref.get();
            if (scheduler == null || scheduler.panel.isRemoved()
                    || !scheduler.panel.isRobotLinked(robot.getRobotId())
                    || !scheduler.isPrimaryController(state, robot.getRobotId())) continue;
            return scheduler.builderSources.preferred(robot, maxDistanceSq);
        }
        return null;
    }

    public static void builderSourceAttempted(EntityRobotBase robot, IRobotBuilderTarget target,
            boolean success) {
        if (robot == null || target == null) return;
        WorldState state = WORLDS.get(robotLevel(robot));
        if (state == null) return;
        for (var ref : state.panels.values()) {
            RobotProcessorWorkScheduler scheduler = ref.get();
            if (scheduler == null || scheduler.panel.isRemoved()
                    || !scheduler.panel.isRobotLinked(robot.getRobotId())
                    || !scheduler.isPrimaryController(state, robot.getRobotId())) continue;
            scheduler.builderSources.attempted(robot, target, success);
            return;
        }
    }

    /**
     * Only linked builders have bounded operation queues. Unlinked builders
     * retain classic 128-item batches and independent search behavior.
     */
    public static int builderQueueLimit(EntityRobotBase robot) {
        if (robot == null || !(robot.getBoard() instanceof BoardRobotBuilder)) return 0;
        WorldState state = WORLDS.get(robotLevel(robot));
        if (state == null) return 0;
        for (var ref : state.panels.values()) {
            RobotProcessorWorkScheduler scheduler = ref.get();
            if (scheduler == null || scheduler.panel.isRemoved()
                    || !scheduler.panel.isRobotLinked(robot.getRobotId())
                    || !scheduler.isPrimaryController(state, robot.getRobotId())) continue;
            return Math.max(1, Math.min(8, scheduler.panel.getMaxExecutionQueue()));
        }
        return 0;
    }

    /** Primary controller only; null denotes an autonomous/unlinked Builder. */
    public static Long builderControllerPanelId(EntityRobotBase robot) {
        if (robot == null || !(robot.getBoard() instanceof BoardRobotBuilder)) return null;
        WorldState state = WORLDS.get(robotLevel(robot));
        if (state == null) return null;
        for (var ref : state.panels.values()) {
            RobotProcessorWorkScheduler scheduler = ref.get();
            if (scheduler == null || scheduler.panel.isRemoved()
                    || !scheduler.panel.isRobotLinked(robot.getRobotId())
                    || !scheduler.isPrimaryController(state, robot.getRobotId())) continue;
            return scheduler.panel.getBlockPos().asLong();
        }
        return null;
    }

    /** Observes authoritative native reservations; this ledger owns NO locks. */
    public static void builderBatchClaimed(EntityRobotBase robot, IRobotBuilderTarget source,
            List<RobotBuildTask> tasks) {
        if (robot == null || source == null || tasks == null || tasks.isEmpty()) return;
        WorldState state = WORLDS.get(robotLevel(robot));
        if (state == null) return;
        for (var ref : state.panels.values()) {
            RobotProcessorWorkScheduler scheduler = ref.get();
            if (scheduler == null || scheduler.panel.isRemoved()
                    || !scheduler.panel.isRobotLinked(robot.getRobotId())
                    || !scheduler.isPrimaryController(state, robot.getRobotId())) continue;
            List<RobotBuilderSourceScheduler.Operation> operations = new ArrayList<>(tasks.size());
            int items = 0;
            for (RobotBuildTask task : tasks) {
                int count = 0;
                for (var requirement : task.requirements()) {
                    if (requirement != null && !requirement.isEmpty()) {
                        count = (int) Math.min(Integer.MAX_VALUE, (long) count + requirement.getCount());
                    }
                }
                operations.add(new RobotBuilderSourceScheduler.Operation(task.pos(), count));
                items = (int) Math.min(Integer.MAX_VALUE, (long) items + count);
            }
            scheduler.builderSources.claimed(robot, source.getBlockPos(), operations);
            if (RobotTrace.enabled()) RobotTrace.event(robot, "PROCESSOR_BUILDER_BATCH",
                    "panel=%s builder=%s ops=%d materials=%d first=%s",
                    scheduler.panel.getBlockPos(), source.getBlockPos(), tasks.size(), items, tasks.get(0).pos());
            return;
        }
    }

    /** Called only AFTER the native Builder released a completed operation. */
    public static void builderTaskFinished(EntityRobotBase robot, BlockPos source, BlockPos position,
            boolean successful) {
        if (robot == null || source == null || position == null) return;
        WorldState state = WORLDS.get(robotLevel(robot));
        if (state == null) return;
        for (var ref : state.panels.values()) {
            RobotProcessorWorkScheduler scheduler = ref.get();
            if (scheduler == null || scheduler.panel.isRemoved()
                    || !scheduler.panel.isRobotLinked(robot.getRobotId())
                    || !scheduler.isPrimaryController(state, robot.getRobotId())) continue;
            scheduler.builderSources.completed(robot, source, position);
            if (RobotTrace.enabled()) RobotTrace.event(robot, "PROCESSOR_BUILDER_OPERATION",
                    "builder=%s position=%s successful=%s", source, position, successful);
            return;
        }
    }

    public static void builderBatchAborted(EntityRobotBase robot, BlockPos source) {
        if (robot == null || source == null) return;
        WorldState state = WORLDS.get(robotLevel(robot));
        if (state == null) return;
        for (var ref : state.panels.values()) {
            RobotProcessorWorkScheduler scheduler = ref.get();
            if (scheduler == null || scheduler.panel.isRemoved()
                    || !scheduler.panel.isRobotLinked(robot.getRobotId())
                    || !scheduler.isPrimaryController(state, robot.getRobotId())) continue;
            scheduler.builderSources.released(robot, source);
            return;
        }
    }

    /** A failed inventory fetch is not proof that the blueprint is empty. */
    public static void builderMaterialStalled(EntityRobotBase robot, BlockPos source) {
        builderMaterialStalled(robot, source, net.minecraft.world.item.ItemStack.EMPTY);
    }

    public static void builderMaterialStalled(EntityRobotBase robot, BlockPos source,
                                              net.minecraft.world.item.ItemStack missing) {
        if (robot == null || source == null) return;
        WorldState state = WORLDS.get(robotLevel(robot));
        if (state == null) return;
        for (var ref : state.panels.values()) {
            RobotProcessorWorkScheduler scheduler = ref.get();
            if (scheduler == null || scheduler.panel.isRemoved()
                    || !scheduler.panel.isRobotLinked(robot.getRobotId())
                    || !scheduler.isPrimaryController(state, robot.getRobotId())) continue;
            scheduler.builderSources.materialStalled(robot, source, missing);
            if (RobotTrace.enabled()) RobotTrace.event(robot, "PROCESSOR_BUILDER_MATERIAL_WAIT",
                    "panel=%s builder=%s missing=%s count=%d retry=400t",
                    scheduler.panel.getBlockPos(), source,
                    missing == null || missing.isEmpty() ? "unknown" : missing.getItem(),
                    missing == null || missing.isEmpty() ? 0 : missing.getCount());
            return;
        }
    }

    /** A concise status for the panel's existing robot snapshot packet. */
    public static String builderMaterialWait(EntityRobotBase robot) {
        if (robot == null || !(robot.getBoard() instanceof BoardRobotBuilder)) return null;
        WorldState state = WORLDS.get(robotLevel(robot));
        if (state == null) return null;
        for (var ref : state.panels.values()) {
            RobotProcessorWorkScheduler scheduler = ref.get();
            if (scheduler == null || scheduler.panel.isRemoved()
                    || !scheduler.panel.isRobotLinked(robot.getRobotId())
                    || !scheduler.isPrimaryController(state, robot.getRobotId())) continue;
            return scheduler.builderSources.materialWaitDescription(robot.getRobotId());
        }
        return null;
    }

    /** Do not probe (reserve!) construction tasks just to wake a sleeping robot. */
    public static boolean hasAvailableBuilderSource(EntityRobotBase robot) {
        if (robot == null || robot.getEnergy() < EntityRobotBase.SAFETY_ENERGY
                || !(robot.getBoard() instanceof BoardRobotBuilder)) return false;
        AIRobot ai = robot.getActiveAI();
        if (ai == null || !ai.isWithinWorkChain(robot.getBoard())) return false;
        List<IRobotBuilderTarget> targets = builderSourcesFor(robot, 3 * 64 * 64);
        return targets != null && !targets.isEmpty();
    }

    /** Work hint is temporary and must be rechecked using the robot's own predicates. */
    public static SuggestedWork suggest(EntityRobotBase robot) {
        if (!ready(robot) || !(robot.getBoard() instanceof ProcessorCandidateBoard board)
                || !board.processorAcceptsHints()) return null;
        Level level = robotLevel(robot);
        WorldState state = WORLDS.get(level);
        if (state == null) return null;
        long now = level.getGameTime();
        state.budget(now, 0);
        for (var entry : state.panels.entrySet()) {
            RobotProcessorWorkScheduler scheduler = entry.getValue().get();
            if (scheduler == null || scheduler.panel.isRemoved() || !scheduler.panel.isRobotLinked(robot.getRobotId())
                    || !scheduler.isPrimaryController(state, robot.getRobotId())) continue;
            SuggestedWork hint = scheduler.suggestFor(robot, board, state, now);
            if (hint != null) return hint;
        }
        return null;
    }

    private SuggestedWork suggestFor(EntityRobotBase robot, ProcessorCandidateBoard board,
            WorldState state, long now) {
        Level level = panel.getLevel();
        if (level == null || !activeCategories.containsKey(board.processorWorkCategory())) return null;
        IZone zone = robot.getZoneToWork();
        for (long packed : index.nearest(board.processorWorkCategory(), robot.getBlockX(),
                robot.getBlockY(), robot.getBlockZ(), now, 32, packed ->
                        state.leases.available(packed, robot.getRobotId(), panel.getBlockPos().asLong(), now))) {
            BlockPos pos = BlockPos.of(packed);
            if (!level.hasChunkAt(pos) || !panel.allowsWorkPosition(pos)) continue;
            if (level.getBlockState(pos).isAir()) {
                index.invalidate(board.processorWorkCategory(), packed);
                continue;
            }
            if (!state.leases.available(packed, robot.getRobotId(), panel.getBlockPos().asLong(), now)) continue;
            if (zone != null && !zone.contains(new Vec3(pos.getX() + .5D, pos.getY() + .5D, pos.getZ() + .5D))) continue;
            if (!board.processorTargetEligible(level, pos)) continue;
            RobotWorkEnergyBudget.Estimate power = energyEstimate(robot, pos, 1, now);
            if (!power.affordable()) {
                energyRejected(robot, pos, power, now);
                continue;
            }
            IRobotRegistry registry = robot.getRegistry();
            if (registry != null && registry.isTaken(new ResourceIdBlock(pos))) continue;
            long token = state.leases.claim(packed, robot.getRobotId(), panel.getBlockPos().asLong(),
                    now, LEASE_TICKS);
            if (token != 0L) return new SuggestedWork(pos, token);
        }
        return null;
    }

    /**
     * Never scan charging docks once per candidate or per A* expansion.
     * This snapshot records only loaded, permitted powered docks. Battery
     * level is read live when calculating every decision.
     */
    private RobotWorkEnergyPlanner.Context energyContext(EntityRobotBase robot, long now) {
        long id = robot.getRobotId();
        CachedEnergy current = chargerSnapshots.get(id);
        if (current == null || now < current.refreshed()
                || now - current.refreshed() >= ENERGY_CONTEXT_REFRESH_TICKS) {
            current = new CachedEnergy(RobotWorkEnergyPlanner.capture(robot), now);
            chargerSnapshots.put(id, current);
        }
        return current.context();
    }

    private RobotWorkEnergyBudget.Estimate energyEstimate(EntityRobotBase robot, BlockPos target,
                                                          int plannedWork, long now) {
        return energyContext(robot, now).evaluate(robot, target, plannedWork);
    }

    private void energyRejected(EntityRobotBase robot, BlockPos target,
                                RobotWorkEnergyBudget.Estimate estimate, long now) {
        if (!estimate.chargerKnown() || estimate.required() > RobotWorkEnergyBudget.MAX_BATTERY_ENERGY) return;
        long id = robot.getRobotId();
        EnergyPressure old = energyPressure.get(id);
        long required = old != null && old.untilTick() > now
                ? Math.min(old.minimumNeeded(), estimate.required()) : estimate.required();
        energyPressure.put(id, new EnergyPressure(required, now + ENERGY_RECHECK_TICKS));
        if (RobotTrace.enabled() && (old == null || now >= old.untilTick()
                || old.minimumNeeded() != required)) {
            RobotTrace.event(robot, "PROCESSOR_ENERGY_WAIT",
                    "target=%s energy=%d required=%d travel=%d work=%d",
                    target, robot.getEnergy(), required, estimate.travel(), estimate.work());
        }
    }

    /** True only when a known reachable power source exists and every examined
     * next job exceeds the robot's remaining reserve. AIRobotMain decides the
     * actual charging route; this never takes/forces a dock itself. */
    public static boolean shouldRechargeBeforeWork(EntityRobotBase robot) {
        if (robot == null || !isQueuedWorker(robot)) return false;
        Level level = robotLevel(robot);
        WorldState state = WORLDS.get(level);
        if (state == null) return false;
        long now = level.getGameTime();
        for (var ref : state.panels.values()) {
            RobotProcessorWorkScheduler scheduler = ref.get();
            if (scheduler == null || scheduler.panel.isRemoved()
                    || !scheduler.panel.isRobotLinked(robot.getRobotId())
                    || !scheduler.isPrimaryController(state, robot.getRobotId())) continue;
            EnergyPressure pressure = scheduler.energyPressure.get(robot.getRobotId());
            if (pressure == null || pressure.untilTick() <= now
                    || robot.getEnergy() >= pressure.minimumNeeded()) return false;
            // A physical work AI is already underway. Never interrupt its
            // current block due to a different speculative queue entry.
            AIRobot ai = robot.getActiveAI();
            return !(ai instanceof AIRobotBreak || ai instanceof AIRobotGotoBlock);
        }
        return false;
    }

    private static Level robotLevel(EntityRobotBase robot) {
        //? if <1.20 {
        return robot.level;
        //?} else {
        return robot.level();
        //?}
    }

    public static void releaseHint(EntityRobotBase robot, SuggestedWork hint) {
        if (hint == null) return;
        WorldState state = WORLDS.get(robotLevel(robot));
        if (state == null) return;
        state.leases.release(hint.position().asLong(), robot.getRobotId(), hint.leaseToken());
    }

    private static boolean isMinerCategory(String category) {
        return "buildcraft:miner".equals(category);
    }

    private static boolean isQueueMiner(EntityRobotBase robot) {
        return robot != null && robot.getBoard() instanceof BoardRobotGenericSearchBlock board
                && isMinerCategory(board.processorWorkCategory());
    }

    /** Third-party candidate providers stay hint-only unless explicitly opted in. */
    private static boolean isQueuedWorker(EntityRobotBase robot) {
        return robot != null && robot.getBoard() instanceof ProcessorCandidateBoard board
                && (board.processorSupportsQueue() || isQueueMiner(robot));
    }

    /** A robot must have at most one active loaded controller even with corrupt/old links. */
    private boolean isPrimaryController(WorldState state, long robotId) {
        long current = panel.getBlockPos().asLong();
        for (var ref : state.panels.values()) {
            RobotProcessorWorkScheduler other = ref.get();
            if (other == null || other == this || other.panel.isRemoved()) continue;
            if (other.panel.isRobotLinked(robotId)
                    && other.panel.getBlockPos().asLong() < current) return false;
        }
        return true;
    }

    /** Revalidate pending work, renew its temporary leases and refill fairly. */
    private void seedMinerVeins(ServerLevel level, IRobotRegistry registry, List<EntityRobotBase> miners) {
        long now = level.getGameTime();
        for (EntityRobotBase robot : miners) {
            if (!(robot.getBoard() instanceof ProcessorCandidateBoard board)) continue;
            int inspected = 0;
            boolean seededProbe = false;
            for (BlockPos pos : board.processorKnownCandidates()) {
                if (++inspected > 32) break;
                if (pos == null || !level.hasChunkAt(pos) || !panel.allowsWorkPosition(pos)) continue;
                if (!board.processorTargetEligible(level, pos)) continue;
                // An existing claim by the same robot is still an active job,
                // not an available candidate for a second miner.
                if (registry.isTaken(new ResourceIdBlock(pos))) continue;
                index.observe(board.processorWorkCategory(), pos.asLong(), now);
                // Vein memory covers connected blocks only. A second ore
                // separated by air needs an independent logical job.
                if (!seededProbe) {
                    scheduleFocusedProbe(pos, now);
                    seededProbe = true;
                }
            }
        }
    }

    /** A compact [robotId, deposit representative] snapshot. Each job, not
     * each block, is persisted; stale lease tokens are never persisted. */
    public long[] pendingWorkSnapshot() {
        List<Long> data = new ArrayList<>();
        for (var entry : deferredMiningTargets.entrySet()) {
            if (!panel.isRobotLinked(entry.getKey())) continue;
            for (long pos : entry.getValue()) {
                if (data.size() >= 1024) break;
                data.add(entry.getKey());
                data.add(pos);
            }
        }
        for (long robotId : executionQueues.robotIds()) {
            if (!panel.isRobotLinked(robotId)) continue;
            for (RobotExecutionQueue.Job job : executionQueues.jobs(robotId)) {
                if (data.size() >= 1024) break;
                if (deferredMiningTargets.getOrDefault(robotId, new LinkedHashSet<>()).contains(job.position())) continue;
                data.add(robotId);
                data.add(job.recoveryPosition());
            }
        }
        long[] snapshot = new long[data.size()];
        for (int i = 0; i < data.size(); i++) snapshot[i] = data.get(i);
        return snapshot;
    }

    /** Loading NBT never restores a claim. Every target is reconsidered on a server tick. */
    public void restorePendingWork(long[] snapshot) {
        deferredMiningTargets.clear();
        if (snapshot == null) return;
        for (int i = 0; i + 1 < Math.min(snapshot.length, 1024); i += 2) {
            long robotId = snapshot[i];
            if (robotId <= 0L || !panel.isRobotLinked(robotId)) continue;
            LinkedHashSet<Long> pending = deferredMiningTargets.computeIfAbsent(robotId, ignored -> new LinkedHashSet<>());
            if (pending.size() < TileRobotProcessor.MAX_EXECUTION_QUEUE) pending.add(snapshot[i+1]);
        }
    }

    private void deferUnloadedMiningQueue(WorldState state, long robotId) {
        if (panel.isRobotLinked(robotId)) {
            LinkedHashSet<Long> pending = deferredMiningTargets.computeIfAbsent(robotId, ignored -> new LinkedHashSet<>());
            for (RobotExecutionQueue.Job job : executionQueues.jobs(robotId)) {
                if (pending.size() >= TileRobotProcessor.MAX_EXECUTION_QUEUE) break;
                pending.add(job.recoveryPosition());
            }
        }
        releaseJobs(state, robotId, executionQueues.clear(robotId));
        panel.setChanged();
    }

    private void rehydrateMiningQueues(ServerLevel level, WorldState state, IRobotRegistry registry,
            List<EntityRobotBase> workers) {
        if (deferredMiningTargets.isEmpty()) return;
        long now = level.getGameTime();
        for (EntityRobotBase robot : workers) {
            long robotId = robot.getRobotId();
            LinkedHashSet<Long> pending = deferredMiningTargets.get(robotId);
            if (pending == null || pending.isEmpty()) continue;
            for (var it = pending.iterator(); it.hasNext();) {
                long packed = it.next();
                if (executionQueues.size(robotId) >= panel.getMaxExecutionQueue()) break;
                BlockPos pos = BlockPos.of(packed);
                if (!level.hasChunkAt(pos)) continue; // never load a missing chunk
                var board = (ProcessorCandidateBoard) robot.getBoard();
                if (!panel.allowsWorkPosition(pos) || !board.processorPlanningEligible(level, pos)) {
                    it.remove(); // changed or already mined while unloaded
                    continue;
                }
                if (queueMiningDeposit(level, state, registry, robot, pos, now)) {
                    it.remove();
                    panel.setChanged();
                    if (RobotTrace.enabled()) RobotTrace.event(robot, "PROCESSOR_QUEUE_RESUME",
                            "panel=%s target=%s remaining=%d",
                            panel.getBlockPos(), pos, pending.size());
                }
            }
            if (pending.isEmpty()) deferredMiningTargets.remove(robotId);
        }
    }

    private void maintainMiningQueues(ServerLevel level, WorldState state, IRobotRegistry registry,
            List<EntityRobotBase> readyWorkers) {
        long now = level.getGameTime();
        int max = panel.getMaxExecutionQueue();
        Set<Long> eligible = new HashSet<>();
        for (EntityRobotBase robot : readyWorkers) eligible.add(robot.getRobotId());
        for (long id : executionQueues.robotIds()) {
            if (!eligible.contains(id)) {
                EntityRobotBase stillWorking = registry.getLoadedRobot(id);
                boolean executing = panel.isRobotLinked(id) && stillWorking != null
                        && robotLevelIs(level, stillWorking)
                        && stillWorking.getActiveAI() != null
                        && (stillWorking.getActiveAI() instanceof AIRobotBreak
                                || stillWorking.getActiveAI() instanceof AIRobotHarvest
                                || stillWorking.getActiveAI() instanceof AIRobotPlant
                                || stillWorking.getActiveAI() instanceof AIRobotUseToolOnBlock
                                || stillWorking.getActiveAI() instanceof AIRobotPumpBlock)
                        && stillWorking.getActiveAI().isWithinWorkChain(stillWorking.getBoard());
                if (executing) {
                    // Temporary AI transitions must not destroy the rest of
                    // a deposit or the robot's pending jobs. Revalidate them
                    // when the board becomes ready on the next tick.
                    continue;
                }
                if (panel.isRobotLinked(id)
                        && (stillWorking == null || !stillWorking.isRemoved()
                                && robotLevelIs(level, stillWorking) && isQueuedWorker(stillWorking)
                                && isPrimaryController(state, id))) {
                    // Recharge, docking, manual Home and gate holds are pauses,
                    // not completed mining jobs. Keep only durable intentions;
                    // release ALL preliminary leases so another robot remains
                    // free to work. Every block is revalidated on resume.
                    if (RobotTrace.enabled() && stillWorking != null) {
                        RobotTrace.event(stillWorking, "PROCESSOR_QUEUE_SUSPEND",
                                "panel=%s jobs=%d energy=%d",
                                panel.getBlockPos(), executionQueues.size(id), stillWorking.getEnergy());
                    }
                    deferUnloadedMiningQueue(state, id);
                } else {
                    // Unlinked, dead, transferred or invalid workers do not
                    // retain ghost jobs or claims in this controller.
                    releaseJobs(state, id, executionQueues.clear(id));
                    deferredMiningTargets.remove(id);
                    panel.setChanged();
                }
                minerTargetCooldowns.remove(id);
                continue;
            }
            EntityRobotBase robot = registry.getLoadedRobot(id);
            if (robot == null || !isQueuedWorker(robot)) continue;
            // Leases live for 100 ticks. Checking pending members every 20
            // ticks is sufficient and avoids O(vein size) reads every tick.
            if ((now + id) % 20 == 0) {
                var board = (ProcessorCandidateBoard) robot.getBoard();
                for (RobotExecutionQueue.Job job : executionQueues.jobs(id)) {
                    for (long packed : job.positions()) {
                        BlockPos pos = BlockPos.of(packed);
                        if (!level.hasChunkAt(pos)) continue;
                        ResourceIdBlock resource = new ResourceIdBlock(pos);
                        long token = executionQueues.token(id, packed);
                        if (!panel.allowsWorkPosition(pos)
                                || !board.processorPlanningEligible(level, pos)
                                || registry.isTaken(resource) && registry.robotIdTaking(resource) != id
                                || token != 0 && !state.leases.renew(packed, id,
                                        panel.getBlockPos().asLong(), token, now, QUEUED_LEASE_TICKS)) {
                            discardJob(state, id, packed, false, now);
                        }
                    }
                }
            }
            releaseJobs(state, id, executionQueues.trim(id, max));
        }
        // A previously assigned job can become too expensive after the robot
        // travels/works. Check once per 20 ticks, without evicting/abandoning
        // the prepared job and without touching the active break.
        for (EntityRobotBase worker : readyWorkers) {
            long id = worker.getRobotId();
            if (Math.floorMod(now + id, ENERGY_CONTEXT_REFRESH_TICKS) != 0) continue;
            boolean affordable = false;
            long smallestRequirement = Long.MAX_VALUE;
            for (RobotExecutionQueue.Job job : executionQueues.jobs(id)) {
                if (job.active()) { affordable = true; break; }
                BlockPos destination = BlockPos.of(job.position());
                var forecast = energyEstimate(worker, destination,
                        Math.min(RobotWorkEnergyBudget.MAX_PLANNED_BLOCKS, job.remainingBlocks()), now);
                if (forecast.affordable()) { affordable = true; break; }
                if (forecast.chargerKnown()) smallestRequirement = Math.min(smallestRequirement, forecast.required());
            }
            if (affordable) {
                energyPressure.remove(id);
            } else if (smallestRequirement != Long.MAX_VALUE) {
                energyRejected(worker, worker.blockPosition(),
                        new RobotWorkEnergyBudget.Estimate(true, false, smallestRequirement, 0, 0, 0), now);
            }
        }
        // The ore catalog may discover a nearby vein AFTER this robot's queue
        // was filled (or after its saved distant jobs were restored). Replace
        // only one unstarted distant job with a markedly closer deposit, using
        // atomic lease replacement. Running breaks are never interrupted.
        for (EntityRobotBase robot : readyWorkers) {
            if (isQueueMiner(robot)
                    && Math.floorMod(now + robot.getRobotId(), QUEUE_LOCALITY_CHECK_TICKS) == 0) {
                preferNearbyMiningDeposit(level, state, registry, robot, now);
            }
        }
        // One new target per worker in each round prevents early-listed miners
        // from monopolising the shared candidate index.
        readyWorkers.sort(Comparator.comparingInt((EntityRobotBase robot) -> executionQueues.size(robot.getRobotId()))
                .thenComparingLong(EntityRobotBase::getRobotId));
        int filled = 0;
        for (int round = 0; round < max && filled < MAX_ENQUEUE_PER_TICK; round++) {
            boolean any = false;
            for (EntityRobotBase robot : readyWorkers) {
                if (filled >= MAX_ENQUEUE_PER_TICK) break;
                if (executionQueues.size(robot.getRobotId()) >= max) continue;
                if (enqueueMiningTarget(level, state, registry, robot, now)) {
                    filled++;
                    any = true;
                }
            }
            if (!any) break;
        }
    }

    private boolean enqueueMiningTarget(ServerLevel level, WorldState state, IRobotRegistry registry,
            EntityRobotBase robot, long now) {
        long id = robot.getRobotId();
        ProcessorCandidateBoard board = (ProcessorCandidateBoard) robot.getBoard();
        List<RobotExecutionQueue.Job> current = executionQueues.jobs(id);
        // Planning from the last PREFETCHED job causes queues to drift across
        // the map (and down quarry levels) even while the robot remains at its
        // dock. Always rank new jobs from the robot's actual current location.
        BlockPos anchor = robot.blockPosition();
        Map<Long, Long> cooldown = minerTargetCooldowns.computeIfAbsent(id, ignored -> new HashMap<>());
        cooldown.entrySet().removeIf(entry -> now >= entry.getValue());
        Set<Long> alreadyQueued = new HashSet<>();
        for (RobotExecutionQueue.Job job : current) alreadyQueued.addAll(job.positions());
        for (long target : index.nearest(board.processorWorkCategory(), anchor.getX(), anchor.getY(),
                anchor.getZ(), now, 64, packed ->
                        state.leases.available(packed, id, panel.getBlockPos().asLong(), now)
                                // Exclude existing deposit members BEFORE
                                // applying the 64-target nearest limit.
                                && !alreadyQueued.contains(packed)
                                && !cooldown.containsKey(packed)
                                && !sharedRouteFailures.shouldDefer(anchor, packed, now))) {
            BlockPos pos = BlockPos.of(target);
            if (!level.hasChunkAt(pos) || !panel.allowsWorkPosition(pos)) continue;
            if (!board.processorPlanningEligible(level, pos)) {
                if (!board.processorDiscoveryCandidate(level, pos)) {
                    index.invalidate(board.processorWorkCategory(), target);
                }
                continue;
            }
            RobotWorkEnergyBudget.Estimate forecast = energyEstimate(robot, pos, 1, now);
            if (!forecast.affordable()) {
                energyRejected(robot, pos, forecast, now);
                continue;
            }
            if (queueMiningDeposit(level, state, registry, robot, pos, now)) return true;
            cooldown.put(target, now + FAILED_TARGET_RETRY_TICKS);
        }
        return false;
    }

    /**
     * Replace a distant pending vein if a substantially nearer UNCLAIMED vein
     * was discovered after the execution queue was filled. The catalog remains
     * independent of that queue; a failed tentative replacement does not
     * discard either the original deposit or its leases.
     *
     * Never replace an active job. Replan at most one job per worker per
     * second to avoid churn when several ores are equally close.
     */
    private void preferNearbyMiningDeposit(ServerLevel level, WorldState state, IRobotRegistry registry,
            EntityRobotBase robot, long now) {
        long id = robot.getRobotId();
        if (executionQueues.size(id) < panel.getMaxExecutionQueue()) return;
        ProcessorCandidateBoard board = (ProcessorCandidateBoard) robot.getBoard();
        BlockPos anchor = robot.blockPosition();
        RobotExecutionQueue.Job worstPending = null;
        long worstDistance = -1L;
        for (RobotExecutionQueue.Job job : executionQueues.jobs(id)) {
            if (job.active()) continue;
            long distance = RobotMiningPriority.nearestMemberSquared(anchor, job.positions());
            if (distance > worstDistance) {
                worstDistance = distance;
                worstPending = job;
            }
        }
        if (worstPending == null) return;
        Map<Long, Long> cooldown = minerTargetCooldowns.computeIfAbsent(id, unused -> new HashMap<>());
        cooldown.entrySet().removeIf(entry -> now >= entry.getValue());
        // A large deposit may have 2,048 members. Avoid testing membership by
        // walking every job for each of the catalog's thousands of entries.
        Set<Long> alreadyQueued = new HashSet<>();
        for (RobotExecutionQueue.Job job : executionQueues.jobs(id)) {
            alreadyQueued.addAll(job.positions());
        }
        for (long packed : index.nearest(board.processorWorkCategory(), anchor.getX(), anchor.getY(),
                anchor.getZ(), now, 32, candidate ->
                        !alreadyQueued.contains(candidate)
                                && !cooldown.containsKey(candidate)
                                && !sharedRouteFailures.shouldDefer(anchor, candidate, now)
                                && state.leases.available(candidate, id, panel.getBlockPos().asLong(), now))) {
            BlockPos pos = BlockPos.of(packed);
            long distance = RobotMiningPriority.squaredDistance(anchor, pos);
            // Sorted nearest-first; if even this candidate is not substantially
            // better, no later candidate can improve the current queue.
            if (!RobotMiningPriority.materiallyCloser(distance, worstDistance)) break;
            if (!level.hasChunkAt(pos) || !panel.allowsWorkPosition(pos)) continue;
            if (!board.processorPlanningEligible(level, pos)) {
                if (!board.processorDiscoveryCandidate(level, pos)) {
                    index.invalidate(board.processorWorkCategory(), packed);
                }
                continue;
            }
            RobotWorkEnergyBudget.Estimate forecast = energyEstimate(robot, pos, 1, now);
            if (!forecast.affordable()) {
                energyRejected(robot, pos, forecast, now);
                continue;
            }
            if (queueMiningDeposit(level, state, registry, robot, pos, now, worstPending)) {
                energyPressure.remove(id);
                return;
            }
            cooldown.put(packed, now + FAILED_TARGET_RETRY_TICKS);
        }
    }

    /**
     * Claim an entire observed connected deposit before assigning it to a
     * robot. A failed member claim rolls back every new lease atomically;
     * no two panels or miners can split the same discovered vein. The regular
     * registry still arbitrates each physical block at execution time.
     */
    private boolean queueMiningDeposit(ServerLevel level, WorldState state, IRobotRegistry registry,
            EntityRobotBase robot, BlockPos seed, long now) {
        return queueMiningDeposit(level, state, registry, robot, seed, now, null);
    }

    private boolean queueMiningDeposit(ServerLevel level, WorldState state, IRobotRegistry registry,
            EntityRobotBase robot, BlockPos seed, long now, RobotExecutionQueue.Job replacePending) {
        ProcessorCandidateBoard board = (ProcessorCandidateBoard) robot.getBoard();
        List<BlockPos> discovered = board.processorJobMembers(level, seed, MAX_VEIN_MEMBERS);
        if (discovered.isEmpty()) return false;
        RobotWorkEnergyBudget.Estimate forecast = energyEstimate(robot, seed,
                Math.min(RobotWorkEnergyBudget.MAX_PLANNED_BLOCKS, discovered.size()), now);
        if (!forecast.affordable()) {
            energyRejected(robot, seed, forecast, now);
            return false;
        }
        long id = robot.getRobotId();
        IZone zone = robot.getZoneToWork();
        Map<Long, Long> members = new LinkedHashMap<>();
        Map<Long, Long> newLeases = new LinkedHashMap<>();
        for (BlockPos pos : discovered) {
            if (!level.hasChunkAt(pos) || !panel.allowsWorkPosition(pos)
                    || zone != null && !zone.contains(new Vec3(pos.getX() + .5D,
                            pos.getY() + .5D, pos.getZ() + .5D))
                    || !board.processorPlanningEligible(level, pos)) continue;
            long packed = pos.asLong();
            ResourceIdBlock resource = new ResourceIdBlock(pos);
            if (registry.isTaken(resource) && registry.robotIdTaking(resource) != id) {
                releaseNewLeases(state, id, newLeases);
                return false;
            }
            if (executionQueues.contains(id, packed)) {
                if (replacePending != null) {
                    // This is already part of an active or prepared deposit.
                    // Never make a new job alias an existing lease.
                    releaseNewLeases(state, id, newLeases);
                    return false;
                }
                members.put(packed, executionQueues.token(id, packed));
                continue;
            }
            // The robot has already reserved this actual block itself.
            if (registry.isTaken(resource) && registry.robotIdTaking(resource) == id) {
                if (replacePending != null) {
                    releaseNewLeases(state, id, newLeases);
                    return false;
                }
                members.put(packed, 0L);
                continue;
            }
            long token = state.leases.claim(packed, id, panel.getBlockPos().asLong(),
                    now, QUEUED_LEASE_TICKS);
            if (token == 0L) {
                releaseNewLeases(state, id, newLeases);
                return false;
            }
            members.put(packed, token);
            newLeases.put(packed, token);
        }
        RobotExecutionQueue.Job displaced = null;
        if (members.isEmpty()) {
            releaseNewLeases(state, id, newLeases);
            return false;
        }
        if (replacePending == null) {
            if (!executionQueues.addDeposit(id, members, panel.getMaxExecutionQueue())) {
                releaseNewLeases(state, id, newLeases);
                return false;
            }
        } else {
            displaced = executionQueues.replacePendingDeposit(id, replacePending.position(), members);
            if (displaced == null) {
                releaseNewLeases(state, id, newLeases);
                return false;
            }
            // The swap succeeded atomically. Only now release the old claims;
            // any failed attempt above left the previous queue untouched.
            releaseJobs(state, id, List.of(displaced));
            Map<Long, Long> cooldown = minerTargetCooldowns.computeIfAbsent(id, unused -> new HashMap<>());
            for (long previous : displaced.positions()) {
                cooldown.put(previous, now + LOCALITY_COOLDOWN_TICKS);
            }
            if (RobotTrace.enabled()) RobotTrace.event(robot, "PROCESSOR_QUEUE_REPRIORITIZE",
                    "old=%s oldDistance=%.1f new=%s newDistance=%.1f jobs=%d",
                    BlockPos.of(displaced.position()),
                    Math.sqrt(RobotMiningPriority.nearestMemberSquared(robot.blockPosition(), displaced.positions())),
                    seed, Math.sqrt(RobotMiningPriority.squaredDistance(robot.blockPosition(), seed)),
                    executionQueues.size(id));
        }
        scheduleFocusedProbe(seed, now);
        if (RobotTrace.enabled()) {
            RobotTrace.event(robot, "PROCESSOR_QUEUE_ASSIGN",
                    "panel=%s deposit=%s members=%d queue=%d/%d",
                    panel.getBlockPos(), seed, members.size(),
                    executionQueues.size(id), panel.getMaxExecutionQueue());
        }
        energyPressure.remove(id);
        if (RobotTrace.enabled()) RobotTrace.event(robot, "PROCESSOR_ENERGY_PLAN",
                "target=%s energy=%d required=%d estimatedBlocks=%d chargers=%s",
                seed, robot.getEnergy(), forecast.required(), forecast.estimatedBlocks(),
                forecast.chargerKnown());
        panel.setChanged();
        return true;
    }

    private static void releaseNewLeases(WorldState state, long robotId, Map<Long, Long> leases) {
        leases.forEach((position, token) -> {
            if (token != 0L) state.leases.release(position, robotId, token);
        });
    }

    private void releaseJobs(WorldState state, long robotId, List<RobotExecutionQueue.Job> jobs) {
        for (RobotExecutionQueue.Job job : jobs) {
            releaseNewLeases(state, robotId, job.reservations());
        }
    }

    private void discardJob(WorldState state, long robotId, long packed, boolean cooldown, long now) {
        RobotExecutionQueue.Job job = executionQueues.remove(robotId, packed);
        if (job != null) panel.setChanged();
        if (job != null && job.leaseToken() != 0) {
            state.leases.release(packed, robotId, job.leaseToken());
        }
        if (cooldown) minerTargetCooldowns.computeIfAbsent(robotId, ignored -> new HashMap<>())
                .put(packed, now + FAILED_TARGET_RETRY_TICKS);
    }

    /**
     * A linked miner must also respect the panel's actual painted work area.
     * Unlinked/autonomous robots use the original board constraints only.
     * Re-evaluated in the final execution AI so an edited map revokes old work.
     */
    public static boolean allowsRobotTarget(EntityRobotBase robot, BlockPos pos) {
        if (robot == null || pos == null) return false;
        Level level = robotLevel(robot);
        WorldState state = WORLDS.get(level);
        if (state == null) return true;
        for (var ref : state.panels.values()) {
            RobotProcessorWorkScheduler scheduler = ref.get();
            if (scheduler != null && !scheduler.panel.isRemoved()
                    && scheduler.panel.isRobotLinked(robot.getRobotId())
                    && scheduler.isPrimaryController(state, robot.getRobotId())) {
                return scheduler.panel.allowsWorkPosition(pos);
            }
        }
        return true;
    }

    /** A link is a preference, never a permission bypass or a forced station claim. */
    public static boolean isLinkedChargingStation(EntityRobotBase robot, DockingStation station) {
        if (robot == null || station == null) return false;
        WorldState state = WORLDS.get(robotLevel(robot));
        if (state == null) return false;
        BlockPos pos = new BlockPos(station.x(), station.y(), station.z());
        TileRobotProcessor.StationKey key = new TileRobotProcessor.StationKey(pos, station.side());
        for (var ref : state.panels.values()) {
            RobotProcessorWorkScheduler scheduler = ref.get();
            if (scheduler != null && !scheduler.panel.isRemoved()
                    && scheduler.panel.isRobotLinked(robot.getRobotId())
                    && scheduler.isPrimaryController(state, robot.getRobotId())
                    && scheduler.panel.getLinkedStations().contains(key)) return true;
        }
        return false;
    }

    /**
     * Count executing work while active. A paused/unloaded miner reports its
     * saved intentions instead of showing an incorrect empty queue. Deferred
     * positions are not leases and are revalidated before work resumes.
     */
    public int queueCount(long robotId) {
        int combat = combatTargets.queueCount(robotId);
        if (combat > 0) return combat;
        int actions = areaActions.queueCount(robotId);
        if (actions > 0) return actions;
        int pickups = pickupScheduler.queueCount(robotId);
        if (pickups > 0) return pickups;
        int cargo = logisticsScheduler.queueCount(robotId);
        if (cargo > 0) return cargo;
        int active = executionQueues.size(robotId);
        if (active > 0) return active;
        LinkedHashSet<Long> pending = deferredMiningTargets.get(robotId);
        if (pending == null || pending.isEmpty()) return 0;
        Level level = panel.getLevel();
        if (level != null && RobotManager.registryProvider != null) {
            IRobotRegistry registry = RobotManager.registryProvider.getRegistry(level);
            EntityRobotBase robot = registry == null ? null : registry.getLoadedRobot(robotId);
            if (ready(robot)) return 0;
        }
        return Math.min(panel.getMaxExecutionQueue(), pending.size());
    }

    /** React immediately to a lower configured limit instead of waiting for a tick. */
    public void onQueueLimitChanged() {
        pickupScheduler.onQueueLimitChanged();
        logisticsScheduler.onQueueLimitChanged();
        areaActions.onQueueLimitChanged();
        combatTargets.onQueueLimitChanged();
        Level level = panel.getLevel();
        WorldState state = level == null ? null : WORLDS.get(level);
        if (state != null) {
            for (long id : executionQueues.robotIds()) {
                releaseJobs(state, id, executionQueues.trim(id, panel.getMaxExecutionQueue()));
            }
        }
        // Keep oldest / active intentions when reducing capacity; the
        // previous implementation discarded the head of the saved queue.
        for (LinkedHashSet<Long> deferred : deferredMiningTargets.values()) {
            while (deferred.size() > panel.getMaxExecutionQueue()) {
                long newest = 0L;
                for (long position : deferred) newest = position;
                deferred.remove(newest);
            }
        }
        panel.setChanged();
    }

    /**
     * A linked miner must not go to sleep merely because its own BFS finished
     * before the demand-driven chunk scanner found the next deposit. Waiting
     * here does NOT load chunks or scan extra blocks; the panel is already
     * doing that on its normal per-tick budget. Manual sleep/gate controls are
     * still respected by ready(robot).
     */
    public static boolean isMiningDiscoveryInProgress(EntityRobotBase robot) {
        return isQueueMiner(robot) && isWorkDiscoveryInProgress(robot);
    }

    /** Keep searching workers awake while the panel is still discovering work.
     * No extra world reads or chunk loads occur in this waiting state. */
    public static boolean isWorkDiscoveryInProgress(EntityRobotBase robot) {
        if (!isQueuedWorker(robot) || !ready(robot)) return false;
        WorldState state = WORLDS.get(robotLevel(robot));
        if (state == null) return false;
        for (var ref : state.panels.values()) {
            RobotProcessorWorkScheduler scheduler = ref.get();
            if (scheduler == null || scheduler.panel.isRemoved()
                    || !scheduler.panel.isRobotLinked(robot.getRobotId())
                    || !scheduler.isPrimaryController(state, robot.getRobotId())) continue;
            return scheduler.activeCategories.containsKey(
                    ((ProcessorCandidateBoard) robot.getBoard()).processorWorkCategory())
                    && scheduler.executionQueues.size(robot.getRobotId()) < scheduler.panel.getMaxExecutionQueue()
                    && (!scheduler.passComplete || !scheduler.focusedProbes.isEmpty()
                            || !scheduler.dirtyPositions.isEmpty());
        }
        return false;
    }

    /**
     * Central TPS budget for A* traversals of loaded, panel-managed miners.
     * The autonomous search path retains its independent 1,024 nodes per tick.
     * Counting active workers once per world tick prevents a fleet of miners
     * from multiplying path cost while preserving fair progress for each one.
     */
    public static int requestRouteSearchBudget(EntityRobotBase robot, int requested) {
        if (robot == null || requested <= 0 || !isQueueMiner(robot)) return requested;
        Level level = robotLevel(robot);
        WorldState state = WORLDS.get(level);
        if (state == null) return requested;
        boolean controlled = false;
        for (var ref : state.panels.values()) {
            RobotProcessorWorkScheduler panelScheduler = ref.get();
            if (panelScheduler != null && !panelScheduler.panel.isRemoved()
                    && panelScheduler.panel.isRobotLinked(robot.getRobotId())
                    && panelScheduler.isPrimaryController(state, robot.getRobotId())) {
                controlled = true;
                break;
            }
        }
        if (!controlled) return requested;
        long now = level.getGameTime();
        if (state.routeWorkerCountTick != now) {
            state.routeWorkerCountTick = now;
            Set<Long> unique = new HashSet<>();
            IRobotRegistry registry = robot.getRegistry();
            if (registry != null) {
                for (var ref : state.panels.values()) {
                    RobotProcessorWorkScheduler panelScheduler = ref.get();
                    if (panelScheduler == null || panelScheduler.panel.isRemoved()) continue;
                    for (long id : panelScheduler.panel.getLinkedRobotIds()) {
                        if (!panelScheduler.isPrimaryController(state, id) || !unique.add(id)) continue;
                        EntityRobotBase other = registry.getLoadedRobot(id);
                        if (other == null || other.isRemoved() || !isQueueMiner(other)
                                || !robotLevelIs(level, other) || other.getBoard() == null) continue;
                        AIRobot active = other.getActiveAI();
                        boolean potentiallySearching = active != null
                                && active.isWithinWorkChain(other.getBoard())
                                && (active == other.getBoard()
                                        || active instanceof AIRobotSearchAndGotoBlock
                                        || active instanceof AIRobotSearchNearestBlock);
                        if (!potentiallySearching) {
                            // A miner travelling, breaking ore or charging is
                            // not consuming A* CPU. Do not slow down the
                            // actually searching robots because of it.
                            unique.remove(id);
                        }
                    }
                }
            }
            // The current miner always participates even if board transitions
            // temporarily hide its active search from the registry snapshot.
            state.routeWorkerCount = Math.max(1, unique.size());
        }
        return state.routeSearchBudget.acquire(now, robot.getRobotId(),
                state.routeWorkerCount, requested);
    }

    /** True only for a miner actively controlled by a loaded panel. Other
     * miners retain their original autonomous nearest-route search. */
    public static boolean isManagedMiner(EntityRobotBase robot) {
        if (!isQueueMiner(robot) || !ready(robot)) return false;
        WorldState state = WORLDS.get(robotLevel(robot));
        if (state == null) return false;
        for (var ref : state.panels.values()) {
            RobotProcessorWorkScheduler scheduler = ref.get();
            if (scheduler != null && !scheduler.panel.isRemoved()
                    && scheduler.panel.isRobotLinked(robot.getRobotId())
                    && scheduler.isPrimaryController(state, robot.getRobotId())) return true;
        }
        return false;
    }

    /** Prefer the current remembered vein, but only its members that are still
     * queued for this miner. A board may expose several discovered independent
     * targets: that must not bypass the execution queue's reduced capacity. */
    public static List<BlockPos> queuedMiningTargets(EntityRobotBase robot) {
        if (!isQueueMiner(robot) || !ready(robot)) return List.of();
        List<BlockPos> planned = queuedWorkTargets(robot);
        var board = (ProcessorCandidateBoard) robot.getBoard();
        List<BlockPos> remembered = board.processorKnownCandidates();
        if (remembered.isEmpty() || !planned.isEmpty()) {
            // A* sees every prepared vein sampled by distance. Previously an
            // old remembered vein returned alone, hiding nearer queued work.
            return planned;
        }
        Level level = robotLevel(robot);
        WorldState state = WORLDS.get(level);
        if (state == null) return planned;
        for (var ref : state.panels.values()) {
            RobotProcessorWorkScheduler scheduler = ref.get();
            if (scheduler == null || scheduler.panel.isRemoved()
                    || !scheduler.panel.isRobotLinked(robot.getRobotId())
                    || !scheduler.isPrimaryController(state, robot.getRobotId())) continue;
            List<RobotExecutionQueue.Job> jobs = scheduler.executionQueues.jobs(robot.getRobotId());
            // After a hand-off, the remembered vein may temporarily have no
            // queued lease. Avoid a premature switch to a different ore only
            // when the execution queue itself is still completely empty.
            if (jobs.isEmpty()) {
                List<BlockPos> valid = new ArrayList<>();
                for (BlockPos pos : remembered) {
                    if (!level.hasChunkAt(pos) || !board.processorTargetEligible(level, pos)) continue;
                    var forecast = scheduler.energyEstimate(robot, pos, 1, level.getGameTime());
                    if (!forecast.affordable()) {
                        scheduler.energyRejected(robot, pos, forecast, level.getGameTime());
                        continue;
                    }
                    valid.add(pos);
                    if (valid.size() >= MAX_SEARCH_TARGETS) break;
                }
                if (!valid.isEmpty()) {
                    scheduler.energyPressure.remove(robot.getRobotId());
                    return valid;
                }
            }
            return planned;
        }
        return planned;
    }

    /** Sample pending positions fairly across *all* queued jobs. The previous
     * flattening took 64 members from the first large vein and hid the other
     * five jobs from the robot's search entirely. */
    public static List<BlockPos> queuedWorkTargets(EntityRobotBase robot) {
        if (!isQueuedWorker(robot) || !ready(robot)) return List.of();
        Level level = robotLevel(robot);
        WorldState state = WORLDS.get(level);
        if (state == null) return List.of();
        state.budget(level.getGameTime(), 0);
        for (var entry : state.panels.entrySet()) {
            RobotProcessorWorkScheduler scheduler = entry.getValue().get();
            if (scheduler == null || scheduler.panel.isRemoved() || !scheduler.panel.isRobotLinked(robot.getRobotId())
                    || !scheduler.isPrimaryController(state, robot.getRobotId())) continue;
            List<RobotExecutionQueue.Job> jobs = scheduler.executionQueues.jobs(robot.getRobotId());
            if (jobs.isEmpty()) return List.of();
            // An active job is considered first, but no one deposit may use
            // the entire target budget if other jobs are still waiting.
            List<List<Long>> samples = new ArrayList<>(jobs.size());
            boolean anyAffordable = false;
            int perJob = Math.max(1, (MAX_SEARCH_TARGETS + jobs.size() - 1) / jobs.size());
            BlockPos currentPosition = robot.blockPosition();
            for (RobotExecutionQueue.Job job : jobs) {
                List<Long> members = job.positions();
                List<Long> chosen = new ArrayList<>();
                if (!job.active()) {
                    BlockPos representative = BlockPos.of(job.position());
                    var forecast = scheduler.energyEstimate(robot, representative,
                            Math.min(RobotWorkEnergyBudget.MAX_PLANNED_BLOCKS, job.remainingBlocks()),
                            level.getGameTime());
                    if (!forecast.affordable()) {
                        scheduler.energyRejected(robot, representative, forecast, level.getGameTime());
                        samples.add(chosen);
                        continue;
                    }
                }
                anyAffordable = true;
                if (job.active()) chosen.add(job.position());
                // Near-only sampling can lose a reachable far-side approach
                // to a large deposit. Reserve half the slots for its nearest
                // ore and spread the others across the whole connected vein.
                int count = Math.min(perJob, members.size());
                int nearSlots = Math.max(1, (count + 1) / 2);
                for (long packed : RobotMiningPriority.nearestMembers(currentPosition, members, nearSlots)) {
                    if (!chosen.contains(packed) && chosen.size() < count) chosen.add(packed);
                }
                if (chosen.size() < count) {
                    for (int i = 0; i < members.size() && chosen.size() < count; i++) {
                        long packed = members.get((int) ((long) i * members.size() / count));
                        if (!chosen.contains(packed)) chosen.add(packed);
                    }
                }
                samples.add(chosen);
            }
            if (anyAffordable) scheduler.energyPressure.remove(robot.getRobotId());
            List<BlockPos> result = new ArrayList<>(MAX_SEARCH_TARGETS);
            for (int i = 0; result.size() < MAX_SEARCH_TARGETS; i++) {
                boolean added = false;
                for (List<Long> sample : samples) {
                    if (i >= sample.size()) continue;
                    result.add(BlockPos.of(sample.get(i)));
                    added = true;
                    if (result.size() >= MAX_SEARCH_TARGETS) break;
                }
                if (!added) break;
            }
            return result;
        }
        return List.of();
    }

    /** The actual AI has taken ResourceIdBlock. Path and break still run normally. */
    public static void targetAcquired(EntityRobotBase robot, BlockPos target) {
        if (!isQueuedWorker(robot) || target == null) return;
        Level level = robotLevel(robot);
        WorldState state = WORLDS.get(level);
        if (state == null) return;
        for (var entry : state.panels.entrySet()) {
            RobotProcessorWorkScheduler scheduler = entry.getValue().get();
            if (scheduler == null || scheduler.panel.isRemoved() || !scheduler.panel.isRobotLinked(robot.getRobotId())
                    || !scheduler.isPrimaryController(state, robot.getRobotId())) continue;
            long id = robot.getRobotId(), packed = target.asLong();
            // Prefetch adjacent *independent* deposits while the miner is
            // travelling/breaking this one, not after its queue runs dry.
            if (isQueueMiner(robot)) scheduler.scheduleFocusedProbe(target, level.getGameTime());
            if (!scheduler.executionQueues.activate(id, packed)) {
                // Native BFS may find a better reachable ore while the queue
                // is full. First try merging it into an existing connected
                // deposit WITHOUT evicting another job. Only if it is truly a
                // new deposit do we free a pending slot for the actual action.
                if (level instanceof ServerLevel server) {
                    scheduler.queueMiningDeposit(server, state, robot.getRegistry(), robot,
                            target, level.getGameTime());
                }
                if (!scheduler.executionQueues.contains(id, packed)) {
                    if (scheduler.executionQueues.size(id) >= scheduler.panel.getMaxExecutionQueue()) {
                        List<RobotExecutionQueue.Job> tail = scheduler.executionQueues.trim(id,
                                scheduler.panel.getMaxExecutionQueue() - 1);
                        scheduler.releaseJobs(state, id, tail);
                    }
                    if (level instanceof ServerLevel server) {
                        scheduler.queueMiningDeposit(server, state, robot.getRegistry(), robot,
                                target, level.getGameTime());
                    }
                    if (!scheduler.executionQueues.contains(id, packed)) {
                        // Still represent this physically reserved break, even
                        // if a larger deposit could not be reserved as a whole.
                        long token = state.leases.claim(packed, id, scheduler.panel.getBlockPos().asLong(),
                                level.getGameTime(), QUEUED_LEASE_TICKS);
                        scheduler.executionQueues.add(id, packed, token, scheduler.panel.getMaxExecutionQueue());
                    }
                }
                scheduler.executionQueues.activate(id, packed);
            }
            return;
        }
    }

    /** A selected route was cancelled before reaching the actual break operation. */
    public static void targetAbandoned(EntityRobotBase robot, BlockPos target) {
        discardActualTarget(robot, target, false);
    }

    /** Only called by the original AIRobotBreak's completion callback. */
    public static void executionFinished(EntityRobotBase robot, BlockPos target, boolean successful) {
        discardActualTarget(robot, target, successful);
        if (successful && robot != null && target != null) {
            worldBlockChanged(robotLevel(robot), target);
        }
    }

    private static void discardActualTarget(EntityRobotBase robot, BlockPos target, boolean successful) {
        if (!isQueuedWorker(robot) || target == null) return;
        Level level = robotLevel(robot);
        WorldState state = WORLDS.get(level);
        if (state == null) return;
        for (var ref : state.panels.values()) {
            RobotProcessorWorkScheduler scheduler = ref.get();
            if (scheduler == null || !scheduler.panel.isRobotLinked(robot.getRobotId())) continue;
            long packed = target.asLong();
            scheduler.discardJob(state, robot.getRobotId(), packed, !successful, level.getGameTime());
            if (successful) scheduler.index.invalidate("buildcraft:miner", packed);
        }
    }

    /** The ordinary A* worker may reject a prepared block because there is no
     * loaded, navigable approach. Release that BLOCK's temporary claim, not
     * the rest of its tree/job. Missing permissions and changed tool filters
     * are checked by the executor; this is path failure backoff only. */
    public static void queuedWorkSearchFailed(EntityRobotBase robot, List<BlockPos> unreachable) {
        if (!isQueuedWorker(robot) || isQueueMiner(robot) || unreachable == null || unreachable.isEmpty()) return;
        Level level = robotLevel(robot);
        WorldState state = WORLDS.get(level);
        if (state == null) return;
        for (var ref : state.panels.values()) {
            RobotProcessorWorkScheduler scheduler = ref.get();
            if (scheduler == null || scheduler.panel.isRemoved()
                    || !scheduler.panel.isRobotLinked(robot.getRobotId())
                    || !scheduler.isPrimaryController(state, robot.getRobotId())) continue;
            long now = level.getGameTime();
            for (BlockPos pos : unreachable) {
                if (scheduler.executionQueues.contains(robot.getRobotId(), pos.asLong())) {
                    scheduler.discardJob(state, robot.getRobotId(), pos.asLong(), true, now);
                }
            }
        }
    }

    /**
     * Delay only a duplicate A* from a previously explored, connected start
     * cell. All assigned targets must have fresh witnesses, otherwise normal
     * route search proceeds. This is a temporary CPU hint, NOT a permanent
     * blocked-ore classification or a command to discard queued work.
     */
    public static long sharedRouteDeferUntil(EntityRobotBase robot, BlockPos origin,
            List<BlockPos> targets) {
        if (!isManagedMiner(robot) || origin == null || targets == null || targets.isEmpty()) return 0L;
        Level level = robotLevel(robot);
        WorldState state = WORLDS.get(level);
        if (state == null) return 0L;
        long now = level.getGameTime();
        for (var ref : state.panels.values()) {
            RobotProcessorWorkScheduler scheduler = ref.get();
            if (scheduler == null || scheduler.panel.isRemoved()
                    || !scheduler.panel.isRobotLinked(robot.getRobotId())
                    || !scheduler.isPrimaryController(state, robot.getRobotId())) continue;
            return scheduler.sharedRouteFailures.deferUntil(origin, targets, now);
        }
        return 0L;
    }

    /**
     * Record only the destinations whose block/tool/Gate predicate passed at
     * the end of the failed search. The reached-start witnesses come from the
     * A* traversal itself, so two nearby but DISCONNECTED caves do not share
     * one another's negative route hints.
     */
    public static void rememberFailedRoute(EntityRobotBase robot, BlockPos origin,
            List<BlockPos> eligibleTargets, java.util.Collection<BlockPos> reachedAir,
            boolean exhausted, int expanded) {
        if (!isManagedMiner(robot) || origin == null || eligibleTargets == null
                || eligibleTargets.isEmpty() || reachedAir == null) return;
        Level level = robotLevel(robot);
        WorldState state = WORLDS.get(level);
        if (state == null) return;
        long now = level.getGameTime();
        for (var ref : state.panels.values()) {
            RobotProcessorWorkScheduler scheduler = ref.get();
            if (scheduler == null || scheduler.panel.isRemoved()
                    || !scheduler.panel.isRobotLinked(robot.getRobotId())
                    || !scheduler.isPrimaryController(state, robot.getRobotId())) continue;
            int stored = scheduler.sharedRouteFailures.remember(origin, eligibleTargets,
                    reachedAir, now, exhausted);
            if (stored > 0 && RobotTrace.enabled()) {
                RobotTrace.event(robot, "PROCESSOR_ROUTE_CACHE_STORE",
                        "origin=%s goals=%d expanded=%d complete=%s entries=%d",
                        origin, stored, expanded, exhausted,
                        scheduler.sharedRouteFailures.cachedSearches());
            }
            return;
        }
    }

    /** All unreachable queued ores are cooled down for this robot; native BFS remains a fallback. */
    public static void queuedMiningSearchFailed(EntityRobotBase robot, List<BlockPos> attempted) {
        if (!isQueueMiner(robot) || attempted.isEmpty()) return;
        Level level = robotLevel(robot);
        WorldState state = WORLDS.get(level);
        if (state == null) return;
        for (var ref : state.panels.values()) {
            RobotProcessorWorkScheduler scheduler = ref.get();
            if (scheduler == null || !scheduler.panel.isRobotLinked(robot.getRobotId())) continue;
            java.util.Set<Long> failed = new java.util.HashSet<>();
            for (BlockPos pos : attempted) {
                for (RobotExecutionQueue.Job job : scheduler.executionQueues.jobs(robot.getRobotId())) {
                    if (job.contains(pos.asLong())) failed.add(job.position());
                }
            }
            for (long position : failed) {
                RobotExecutionQueue.Job dropped = scheduler.executionQueues.removeDeposit(robot.getRobotId(), position);
                if (dropped == null) continue;
                scheduler.releaseJobs(state, robot.getRobotId(), List.of(dropped));
                Map<Long, Long> cooldown = scheduler.minerTargetCooldowns.computeIfAbsent(
                        robot.getRobotId(), ignored -> new HashMap<>());
                for (long member : dropped.positions()) {
                    cooldown.put(member, level.getGameTime() + UNREACHABLE_DEPOSIT_RETRY_TICKS);
                }
                scheduler.panel.setChanged();
            }
        }
    }

    public int cachedCandidates() {
        Level level = panel.getLevel();
        if (level == null) return 0;
        int sum = 0;
        for (String category : activeCategories.keySet()) sum += index.count(category, level.getGameTime());
        return sum;
    }
}
