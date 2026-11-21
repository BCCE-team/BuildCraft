package buildcraft.robotics.tile;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Map;
import buildcraft.lib.BCLibConfig;
import buildcraft.lib.chunkload.ChunkLoaderManager;
import buildcraft.lib.chunkload.IChunkLoadingTile;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import java.util.List;
import java.util.Set;

import buildcraft.lib.tile.item.ItemHandlerManager.EnumAccess;
import buildcraft.lib.tile.item.ItemHandlerSimple;
import buildcraft.robotics.BCRoboticsBlocks;
import buildcraft.robotics.ai.AIRobotBreak;
import buildcraft.robotics.ai.AIRobotFetchItem;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.internal.legacy.robots.RobotManager;
import buildcraft.robotics.scheduler.RobotProcessorWorkScheduler;
import buildcraft.robotics.scheduler.RobotWorkIndex;
import buildcraft.robotics.item.ItemRobotController;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
//? if <1.21.1 {
import net.minecraft.nbt.CompoundTag;
//?}
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
//? if >=1.21.1 {
import buildcraft.lib.compat.minecraft.persistence.BCValueInput;
import buildcraft.lib.compat.minecraft.persistence.BCValueOutput;
//?}
import buildcraft.robotics.container.ContainerRobotProcessor;
import buildcraft.robotics.zone.ZonePlan;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.level.block.state.BlockState;

/**
 * User-facing robot control panel. The Zone Planner ancestor supplies the
 * editable 16-layer work-area map and its save/network lifecycle. Dispatching
 * and job caching are intentionally not activated by this initial GUI patch.
 */
public class TileRobotProcessor extends TileZonePlanner implements IChunkLoadingTile {
    private static final int MAX_FORCED_ROBOT_CHUNKS = 16;
    private static final int FORCE_CHUNK_TTL_TICKS = 200;
    private static final long UNKNOWN_ROBOT_POS = Long.MIN_VALUE;
    public static final int MIN_EXECUTION_QUEUE = 1;
    public static final int MAX_EXECUTION_QUEUE = 8;
    public static final int DEFAULT_EXECUTION_QUEUE = 5;
    public static final int MAX_LINK_NAME_LENGTH = 32;

    /** Number of *execution* operations per robot packet; movement and searching are not jobs. */
    private int maxExecutionQueue = DEFAULT_EXECUTION_QUEUE;
    /** Last durable intentions, not claims; a new scheduler validates them on load. */
    private long[] persistedMiningWork = new long[0];
    /** Catalog survives world reload, but never stores active leases or robot claims. */
    private RobotWorkIndex.CatalogSnapshot persistedCatalog = RobotWorkIndex.CatalogSnapshot.empty();
    private boolean schedulerSuspended;
    private final Map<Long, String> robotNames = new LinkedHashMap<>();
    private final Map<StationKey, String> stationNames = new LinkedHashMap<>();

    public int getMaxExecutionQueue() {
        return maxExecutionQueue;
    }

    /** Progress of the full, loaded-section background scan (not robot jobs). */
    public RobotProcessorWorkScheduler.ScanProgress getScanProgress() {
        return workScheduler.scanProgress();
    }

    /** Server-maintained, 20-tick performance window; no world lookup on GUI read. */
    public buildcraft.robotics.scheduler.RobotProcessorDiagnostics.Snapshot getDiagnostics() {
        return workScheduler.diagnostics();
    }

    /** Server-only 20-tick navigation profile, without new world reads. */
    public buildcraft.robotics.scheduler.RobotProcessorNavigationProfiler.Snapshot getNavigationProfile() {
        return workScheduler.navigationProfile();
    }

    /** Snapshot-only status; it never claims or probes work. */
    public String getRobotWaitReason(EntityRobotBase robot) {
        return workScheduler.robotWaitReason(robot);
    }

    /** Number of provisional or executing mine-block operations for this linked robot. */
    public int getExecutionQueueSize(long robotId) {
        if (!linkedRobotIds.contains(robotId)) return 0;
        int prepared = workScheduler.queueCount(robotId);
        if (prepared > 0 || !(level instanceof ServerLevel) || RobotManager.registryProvider == null) {
            return prepared;
        }
        // The miner may have claimed a block via its original BFS while the
        // shared index warms up. A real AIRobotBreak is still one useful
        // operation, so GUI telemetry must not report 0 during that action.
        var registry = RobotManager.registryProvider.getRegistry(level);
        EntityRobotBase robot = registry == null ? null : registry.getLoadedRobot(robotId);
        if (robot == null || robot.getBoard() == null) return 0;
        var active = robot.getActiveAI();
        if (active == null || !active.isWithinWorkChain(robot.getBoard())) return 0;
        if (active instanceof AIRobotBreak) return 1;
        return active instanceof AIRobotFetchItem fetch && fetch.hasPickupTarget() ? 1 : 0;
    }

    public void setMaxExecutionQueue(int value) {
        int clamped = Math.max(MIN_EXECUTION_QUEUE, Math.min(MAX_EXECUTION_QUEUE, value));
        if (maxExecutionQueue != clamped) {
            maxExecutionQueue = clamped;
            workScheduler.onQueueLimitChanged();
            setChanged();
        }
    }

    private static String normalizeLinkName(String value) {
        if (value == null) return "";
        String clean = value.replaceAll("[\\p{Cntrl}]", "").strip();
        return clean.length() <= MAX_LINK_NAME_LENGTH ? clean : clean.substring(0, MAX_LINK_NAME_LENGTH);
    }

    public String getRobotName(long robotId) {
        return robotNames.getOrDefault(robotId, "");
    }

    public String getStationName(BlockPos pos, Direction side) {
        return stationNames.getOrDefault(new StationKey(pos, side), "");
    }

    public boolean renameRobot(long robotId, String name) {
        if (!linkedRobotIds.contains(robotId)) return false;
        String clean = normalizeLinkName(name);
        if (clean.isEmpty()) robotNames.remove(robotId);
        else robotNames.put(robotId, clean);
        setChanged();
        return true;
    }

    public boolean renameStation(BlockPos pos, Direction side, String name) {
        StationKey key = new StationKey(pos, side);
        if (!linkedStations.contains(key)) return false;
        String clean = normalizeLinkName(name);
        if (clean.isEmpty()) stationNames.remove(key);
        else stationNames.put(key, clean);
        setChanged();
        return true;
    }

    private String serializeRobotNames() {
        JsonArray entries = new JsonArray();
        robotNames.forEach((id, name) -> {
            if (!linkedRobotIds.contains(id)) return;
            JsonObject item = new JsonObject();
            item.addProperty("id", id);
            item.addProperty("name", name);
            entries.add(item);
        });
        return entries.toString();
    }

    private String serializeStationNames() {
        JsonArray entries = new JsonArray();
        stationNames.forEach((station, name) -> {
            if (!linkedStations.contains(station)) return;
            JsonObject item = new JsonObject();
            item.addProperty("pos", station.pos().asLong());
            item.addProperty("side", station.side().get3DDataValue());
            item.addProperty("name", name);
            entries.add(item);
        });
        return entries.toString();
    }

    private void restoreNames(String robots, String stations) {
        robotNames.clear();
        stationNames.clear();
        try {
            if (robots != null && !robots.isBlank()) {
                for (JsonElement element : JsonParser.parseString(robots).getAsJsonArray()) {
                    JsonObject entry = element.getAsJsonObject();
                    long id = entry.get("id").getAsLong();
                    String name = normalizeLinkName(entry.get("name").getAsString());
                    if (linkedRobotIds.contains(id) && !name.isEmpty()) robotNames.put(id, name);
                }
            }
        } catch (RuntimeException ignored) {
            robotNames.clear(); // Bad optional names must never break loading an existing panel.
        }
        try {
            if (stations != null && !stations.isBlank()) {
                for (JsonElement element : JsonParser.parseString(stations).getAsJsonArray()) {
                    JsonObject entry = element.getAsJsonObject();
                    int face = entry.get("side").getAsInt();
                    if (face < 0 || face > 5) continue;
                    StationKey key = new StationKey(BlockPos.of(entry.get("pos").getAsLong()),
                            Direction.from3DDataValue(face));
                    String name = normalizeLinkName(entry.get("name").getAsString());
                    if (linkedStations.contains(key) && !name.isEmpty()) stationNames.put(key, name);
                }
            }
        } catch (RuntimeException ignored) {
            stationNames.clear();
        }
    }
    private final Map<Long, BlockPos> lastKnownRobotPositions = new LinkedHashMap<>();
    private final Set<Long> forceLoadedRobotIds = new LinkedHashSet<>();
    /** Soft persistent discovery catalog; claims and real work remain transient. */
    private final RobotProcessorWorkScheduler workScheduler = new RobotProcessorWorkScheduler(this);
    private boolean chunkTicketsDirty = true;
    private int chunkLoadingStartupDelay = 40;
    /** A 7x7 square of chunks centered on the chunk containing this machine. */
    public static final int WORK_AREA_RADIUS_CHUNKS = 3;

    public static boolean isWithinWorkArea(BlockPos origin, int chunkX, int chunkZ) {
        int centerChunkX = origin.getX() >> 4;
        int centerChunkZ = origin.getZ() >> 4;
        return Math.abs((long) chunkX - centerChunkX) <= WORK_AREA_RADIUS_CHUNKS
                && Math.abs((long) chunkZ - centerChunkZ) <= WORK_AREA_RADIUS_CHUNKS;
    }

    public static ZonePlan limitToWorkArea(ZonePlan plan, BlockPos origin) {
        return plan == null ? new ZonePlan()
                : plan.copyChunksMatching(chunk -> isWithinWorkArea(origin, chunk.x, chunk.z));
    }

    /**
     * The map's active painted layers form a union. An unpainted panel retains
     * the full 7x7 area, matching the initial configuration without a map.
     */
    public boolean allowsWorkPosition(BlockPos target) {
        if (!isWithinWorkArea(worldPosition, target.getX() >> 4, target.getZ() >> 4)) return false;
        boolean hasPaint = false;
        for (ZonePlan layer : layers) {
            if (!layer.getChunkMapping().isEmpty()) {
                hasPaint = true;
                if (layer.get(target.getX(), target.getZ())) return true;
            }
        }
        return !hasPaint;
    }

    @Override
    public void setArea(int index, ZonePlan area) {
        workScheduler.onLinkChanged();
        // Enforce the limit on the server too: GUI packets and Map Location imports
        // must not silently install a larger selection than this machine allows.
        super.setArea(index, limitToWorkArea(area, worldPosition));
    }

    public final ItemHandlerSimple storage = itemManager.addInvHandler(
            "storage", 20, (slot, stack) -> true, EnumAccess.BOTH);

    // Intentionally empty for a newly placed machine. Robot discovery does not
    // create links; a future binding tool will explicitly add/remove robot IDs.
    private final Set<Long> linkedRobotIds = new LinkedHashSet<>();
    private final Map<Long, Long> forceChunkExpiries = new LinkedHashMap<>();
    private final Set<ChunkPos> legacyForcedTicketCleanup = new HashSet<>();
    /** A station is the pair (pipe block, mounted face), not just a position. */
    public record StationKey(BlockPos pos, Direction side) {}
    private final Set<StationKey> linkedStations = new LinkedHashSet<>();

    public List<StationKey> getLinkedStations() {
        return List.copyOf(linkedStations);
    }

    public boolean linkStation(BlockPos pos, Direction side) {
        if (pos == null || side == null || linkedStations.size() >= 64) return false;
        boolean added = linkedStations.add(new StationKey(pos.immutable(), side));
        if (added) setChanged();
        return added;
    }

    public boolean unlinkStation(BlockPos pos, Direction side) {
        boolean removed = linkedStations.remove(new StationKey(pos, side));
        if (removed) {
            stationNames.remove(new StationKey(pos, side));
            setChanged();
        }
        return removed;
    }

    private long[] savedMiningWork() {
        return !schedulerSuspended && level instanceof ServerLevel ? workScheduler.pendingWorkSnapshot() : persistedMiningWork;
    }

    private RobotWorkIndex.CatalogSnapshot savedCatalog() {
        return !schedulerSuspended && level instanceof ServerLevel
                ? workScheduler.catalogSnapshot() : persistedCatalog;
    }

    private static final String CATALOG_TYPES_KEY = "discoveryCatalogTypes";
    private static final String CATALOG_COUNTS_KEY = "discoveryCatalogCounts";
    private static final String CATALOG_POSITIONS_KEY = "discoveryCatalogPositions";
    private static final String CATALOG_VERSION_KEY = "discoveryCatalogVersion";

    private void loadLinks(long[] robotIds, long[] stations, int[] stationSides,
                           long[] knownPositions, long[] forcedIds) {
        linkedRobotIds.clear();
        linkedStations.clear();
        lastKnownRobotPositions.clear();
        forceLoadedRobotIds.clear();
        forceChunkExpiries.clear();
        legacyForcedTicketCleanup.clear();
        robotNames.clear();
        stationNames.clear();
        for (int i = 0; i < robotIds.length && linkedRobotIds.size() < 64; i++) {
            long id = robotIds[i];
            if (id <= 0L || id == Long.MAX_VALUE || !linkedRobotIds.add(id)) continue;
            if (i < knownPositions.length && knownPositions[i] != UNKNOWN_ROBOT_POS) {
                lastKnownRobotPositions.put(id, BlockPos.of(knownPositions[i]));
            }
        }
        for (long id : forcedIds) {
            if (forceLoadedRobotIds.size() >= MAX_FORCED_ROBOT_CHUNKS) break;
            if (linkedRobotIds.contains(id) && lastKnownRobotPositions.containsKey(id)) {
                BlockPos old = lastKnownRobotPositions.get(id);
                legacyForcedTicketCleanup.add(new ChunkPos(old.getX() >> 4, old.getZ() >> 4));
            }
        }
        chunkTicketsDirty = true;
        for (int i = 0; i < stations.length && i < stationSides.length && linkedStations.size() < 64; i++) {
            int side = stationSides[i];
            if (side >= 0 && side < 6) {
                linkedStations.add(new StationKey(BlockPos.of(stations[i]), Direction.from3DDataValue(side)));
            }
        }
    }

    private long[] savedRobotIds() {
        return linkedRobotIds.stream().mapToLong(Long::longValue).toArray();
    }

    private long[] savedRobotPositions() {
        return linkedRobotIds.stream().mapToLong(id -> {
            BlockPos pos = lastKnownRobotPositions.get(id);
            return pos == null ? UNKNOWN_ROBOT_POS : pos.asLong();
        }).toArray();
    }

    private long[] savedForceLoadedRobots() {
        // Persist only identities required to clean up platform tickets after a restart.
        // loadLinks never reactivates them.
        return forceLoadedRobotIds.stream().mapToLong(Long::longValue).toArray();
    }

    private long[] savedStationPositions() {
        return linkedStations.stream().mapToLong(station -> station.pos().asLong()).toArray();
    }

    private int[] savedStationSides() {
        return linkedStations.stream().mapToInt(station -> station.side().get3DDataValue()).toArray();
    }

    //? if >=1.21.1 {
    @Override
    protected void readData(BCValueInput data) {
        super.readData(data);
        loadLinks(data.readLongArray("linkedRobots"), data.readLongArray("linkedStations"),
                data.readIntArray("linkedStationSides"),
                data.readLongArray("knownRobotPositions"), data.readLongArray("forcedRobotChunks"));
        maxExecutionQueue = Math.max(MIN_EXECUTION_QUEUE, Math.min(MAX_EXECUTION_QUEUE,
                data.has("maxExecutionQueue") ? data.readInt("maxExecutionQueue") : DEFAULT_EXECUTION_QUEUE));
        restoreNames(data.readString("robotNames"), data.readString("stationNames"));
        persistedMiningWork = data.readLongArray("pendingMiningWork");
        persistedCatalog = data.has(CATALOG_VERSION_KEY)
                && data.readInt(CATALOG_VERSION_KEY) == RobotWorkIndex.CATALOG_FORMAT
                ? new RobotWorkIndex.CatalogSnapshot(data.readString(CATALOG_TYPES_KEY),
                        data.readIntArray(CATALOG_COUNTS_KEY), data.readLongArray(CATALOG_POSITIONS_KEY))
                : RobotWorkIndex.CatalogSnapshot.empty();
    }

    @Override
    protected void writeData(BCValueOutput data) {
        super.writeData(data);
        data.writeLongArray("linkedRobots", savedRobotIds());
        data.writeLongArray("knownRobotPositions", savedRobotPositions());
        data.writeLongArray("forcedRobotChunks", savedForceLoadedRobots());
        data.writeLongArray("linkedStations", savedStationPositions());
        data.writeIntArray("linkedStationSides", savedStationSides());
        data.writeInt("maxExecutionQueue", maxExecutionQueue);
        data.writeLongArray("pendingMiningWork", savedMiningWork());
        RobotWorkIndex.CatalogSnapshot catalog = savedCatalog();
        data.writeInt(CATALOG_VERSION_KEY, RobotWorkIndex.CATALOG_FORMAT);
        data.writeString(CATALOG_TYPES_KEY, catalog.categories());
        data.writeIntArray(CATALOG_COUNTS_KEY, catalog.counts());
        data.writeLongArray(CATALOG_POSITIONS_KEY, catalog.positions());
        data.writeString("robotNames", serializeRobotNames());
        data.writeString("stationNames", serializeStationNames());
    }
    //?} else {
    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        loadLinks(tag.getLongArray("linkedRobots"), tag.getLongArray("linkedStations"),
                tag.getIntArray("linkedStationSides"),
                tag.getLongArray("knownRobotPositions"), tag.getLongArray("forcedRobotChunks"));
        maxExecutionQueue = Math.max(MIN_EXECUTION_QUEUE, Math.min(MAX_EXECUTION_QUEUE,
                tag.contains("maxExecutionQueue") ? tag.getInt("maxExecutionQueue") : DEFAULT_EXECUTION_QUEUE));
        restoreNames(tag.getString("robotNames"), tag.getString("stationNames"));
        persistedMiningWork = tag.getLongArray("pendingMiningWork");
        persistedCatalog = tag.contains(CATALOG_VERSION_KEY)
                && tag.getInt(CATALOG_VERSION_KEY) == RobotWorkIndex.CATALOG_FORMAT
                ? new RobotWorkIndex.CatalogSnapshot(tag.getString(CATALOG_TYPES_KEY),
                        tag.getIntArray(CATALOG_COUNTS_KEY), tag.getLongArray(CATALOG_POSITIONS_KEY))
                : RobotWorkIndex.CatalogSnapshot.empty();
    }

    @Override
    public void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putLongArray("linkedRobots", savedRobotIds());
        tag.putLongArray("knownRobotPositions", savedRobotPositions());
        tag.putLongArray("forcedRobotChunks", savedForceLoadedRobots());
        tag.putLongArray("linkedStations", savedStationPositions());
        tag.putIntArray("linkedStationSides", savedStationSides());
        tag.putInt("maxExecutionQueue", maxExecutionQueue);
        tag.putLongArray("pendingMiningWork", savedMiningWork());
        RobotWorkIndex.CatalogSnapshot catalog = savedCatalog();
        tag.putInt(CATALOG_VERSION_KEY, RobotWorkIndex.CATALOG_FORMAT);
        tag.putString(CATALOG_TYPES_KEY, catalog.categories());
        tag.putIntArray(CATALOG_COUNTS_KEY, catalog.counts());
        tag.putLongArray(CATALOG_POSITIONS_KEY, catalog.positions());
        tag.putString("robotNames", serializeRobotNames());
        tag.putString("stationNames", serializeStationNames());
    }
    //?}


    public List<Long> getLinkedRobotIds() {
        return List.copyOf(linkedRobotIds);
    }

    public boolean isRobotLinked(long robotId) {
        return linkedRobotIds.contains(robotId);
    }

    /** Called only by an explicit binding action, never from automatic discovery. */
    public boolean linkRobot(long robotId) {
        return linkRobot(robotId, null);
    }

    public boolean linkRobot(long robotId, BlockPos location) {
        if (robotId <= 0L || robotId == Long.MAX_VALUE || linkedRobotIds.size() >= 64) return false;
        boolean added = linkedRobotIds.add(robotId);
        if (added) {
            if (location != null) lastKnownRobotPositions.put(robotId, location.immutable());
            workScheduler.onLinkChanged();
            setChanged();
        }
        return added;
    }

    public BlockPos getLastKnownRobotPosition(long robotId) {
        return lastKnownRobotPositions.get(robotId);
    }

    /** Preserve the last known chunk even if the robot entity later unloads. */
    public void recordRobotPosition(long robotId, BlockPos pos) {
        if (!linkedRobotIds.contains(robotId) || pos == null) return;
        BlockPos previous = lastKnownRobotPositions.put(robotId, pos.immutable());
        if (!pos.equals(previous)) {
            if (forceLoadedRobotIds.contains(robotId) && (previous == null
                    || (previous.getX() >> 4) != (pos.getX() >> 4)
                    || (previous.getZ() >> 4) != (pos.getZ() >> 4))) chunkTicketsDirty = true;
            setChanged();
        }
    }

    public boolean unlinkRobot(long robotId) {
        boolean removed = linkedRobotIds.remove(robotId);
        if (removed) {
            robotNames.remove(robotId);
            lastKnownRobotPositions.remove(robotId);
            workScheduler.onLinkChanged();
            forceChunkExpiries.remove(robotId);
            if (forceLoadedRobotIds.remove(robotId)) chunkTicketsDirty = true;
            setChanged();
        }
        return removed;
    }

    /** Force-load only explicitly linked robots, using BC's owner-scoped tickets. */
    public boolean forceRobotChunk(long robotId) {
        if (!(level instanceof ServerLevel server) || !linkedRobotIds.contains(robotId)
                || !lastKnownRobotPositions.containsKey(robotId)) return false;
        if (BCLibConfig.chunkLoadingType == BCLibConfig.ChunkLoaderType.OFF
                || (BCLibConfig.chunkLoadingType == BCLibConfig.ChunkLoaderType.AUTO
                        && server.getServer().isDedicatedServer())
                || !BCLibConfig.chunkLoadingLevel.canLoad(LoadType.HARD)) return false;
        if (forceLoadedRobotIds.contains(robotId)) return true; // Shift-key and click may both fire.
        if (forceLoadedRobotIds.size() >= MAX_FORCED_ROBOT_CHUNKS) return false;
        forceLoadedRobotIds.add(robotId);
        forceChunkExpiries.put(robotId, server.getGameTime() + FORCE_CHUNK_TTL_TICKS);
        chunkTicketsDirty = true;
        setChanged();
        return true;
    }

    @Override
    public LoadType getLoadType() {
        return forceLoadedRobotIds.isEmpty() ? null : LoadType.HARD;
    }

    @Override
    public Set<ChunkPos> getChunksToLoad() {
        Set<ChunkPos> chunks = new HashSet<>();
        for (long id : forceLoadedRobotIds) {
            BlockPos pos = lastKnownRobotPositions.get(id);
            if (pos != null) chunks.add(new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4));
        }
        return chunks;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        chunkTicketsDirty = true;
        schedulerSuspended = false;
        workScheduler.onLinkChanged();
        workScheduler.restorePendingWork(persistedMiningWork);
        if (level instanceof ServerLevel) {
            workScheduler.restoreCatalog(persistedCatalog);
            // The scheduler owns the restored positions now. Do not hold a
            // second large NBT array in the tile throughout its lifetime.
            persistedCatalog = RobotWorkIndex.CatalogSnapshot.empty();
        }
        // Avoid ticket operations inside the owner's chunk load future.
        chunkLoadingStartupDelay = 40;
    }

    @Override
    public void update() {
        super.update();
        if (!(level instanceof ServerLevel server)) return;
        workScheduler.tick(server);
        long now = server.getGameTime();
        workScheduler.flushCatalogDirty(now);
        if (forceLoadedRobotIds.removeIf(id -> now >= forceChunkExpiries.getOrDefault(id, now))) {
            forceChunkExpiries.keySet().retainAll(forceLoadedRobotIds);
            chunkTicketsDirty = true;
            setChanged();
        }
        if (chunkLoadingStartupDelay > 0) {
            chunkLoadingStartupDelay--;
        } else {
            // Release tickets from old saves where force requests never expired.
            // Exclude a chunk still legitimately requested by a fresh operation.
            if (!legacyForcedTicketCleanup.isEmpty()) {
                legacyForcedTicketCleanup.removeAll(getChunksToLoad());
                if (!legacyForcedTicketCleanup.isEmpty()) {
                    ChunkLoaderManager.releaseAdditionalChunksFor(this, legacyForcedTicketCleanup);
                }
                legacyForcedTicketCleanup.clear();
                chunkTicketsDirty = true;
            }
            if (chunkTicketsDirty) {
                chunkTicketsDirty = false;
                ChunkLoaderManager.loadChunksForTile(this);
            }
        }
    }

    @Override
    public void onChunkUnloaded() {
        // A panel must not keep short-lived candidate claims after its chunk
        // stops ticking. Existing real robot reservations remain authoritative.
        // Tile serialization already obtains the current snapshot directly.
        // Marking the chunk dirty *during* unload can requeue writes while the
        // integrated server is flushing chunks (the "Saving world" screen).
        // Ordinary queue mutations have already called setChanged().
        persistedMiningWork = workScheduler.pendingWorkSnapshot();
        persistedCatalog = workScheduler.catalogSnapshot();
        schedulerSuspended = true;
        workScheduler.onRemoved();
        super.onChunkUnloaded();
    }

    @Override
    public void onRemove(boolean dropSelf) {
        persistedMiningWork = new long[0];
        persistedCatalog = RobotWorkIndex.CatalogSnapshot.empty();
        workScheduler.onRemoved();
        if (level instanceof ServerLevel) ChunkLoaderManager.releaseChunksFor(this);
        super.onRemove(dropSelf);
    }

    @Override
    public InteractionResult onActivated(Player player, InteractionHand hand, BlockHitResult hit) {
        if (player.isShiftKeyDown() && player.getItemInHand(hand).getItem() instanceof ItemRobotController) {
            return ItemRobotController.bindProcessor(player.getItemInHand(hand), player, this);
        }
        return super.onActivated(player, hand, hit);
    }

    public TileRobotProcessor(BlockPos pos, BlockState state) {
        super(BCRoboticsBlocks.ROBOT_PROCESSOR_TILE.get(), pos, state);
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new ContainerRobotProcessor(id, inventory, this,
                ContainerLevelAccess.create(level, worldPosition));
    }
}
