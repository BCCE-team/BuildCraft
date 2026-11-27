package buildcraft.robotics.tile;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Map;
import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.energy.MjAmount;
import buildcraft.lib.BCLibConfig;
import buildcraft.lib.internal.mj.MjBattery;
import buildcraft.lib.mj.MjRedstoneBatteryReceiver;
import buildcraft.lib.internal.mj.MjCapabilityHelper;
import buildcraft.robotics.scheduler.RobotProcessorPower;
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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
//? if >=1.21.1 {
import buildcraft.lib.compat.minecraft.persistence.BCValueInput;
import buildcraft.lib.compat.minecraft.persistence.BCValueOutput;
//?}
import buildcraft.robotics.container.ContainerRobotProcessor;
import buildcraft.robotics.zone.ZonePlan;
import buildcraft.robotics.zone.ProcessorDiskLayout;
import buildcraft.robotics.item.ItemHardDisk;
import buildcraft.robotics.BCRoboticsItems;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.level.block.state.BlockState;

/**
 * User-facing robot control panel. The Zone Planner ancestor supplies the
 * editable 16-layer work-area map and its save/network lifecycle. The server
 * owns the MJ buffer, scan scheduler and explicit robot links.
 */
public class TileRobotProcessor extends TileZonePlanner implements IChunkLoadingTile {
    private static final int MAX_FORCED_ROBOT_CHUNKS = 16;
    private static final int FORCE_CHUNK_TTL_TICKS = 200;
    private static final long UNKNOWN_ROBOT_POS = Long.MIN_VALUE;
    public static final int MIN_EXECUTION_QUEUE = 1;
    public static final int MAX_EXECUTION_QUEUE = 8;
    public static final int DEFAULT_EXECUTION_QUEUE = 5;
    public static final int MAX_LINK_NAME_LENGTH = 32;
    public static final int MAX_LINKED_ROBOTS = RobotProcessorPower.MAX_ROBOTS;
    public static final long ENERGY_CAPACITY = RobotProcessorPower.CAPACITY;
    private final MjBattery processorBattery = new MjBattery(ENERGY_CAPACITY);
    private long lastStoredEnergy, requestedPower, consumedPower;
    private boolean schedulerRunning;
    private boolean diskMounted;
    private boolean diskFault;
    private boolean loadingDisk;
    private String linkDimension = "";
    private String diskIdentity = "";
    private transient buildcraft.robotics.internal.legacy.robots.IRobotRegistry linkRegistry;
    private ProcessorDiskLayout diskLayout;
    private byte[] diskPaint;
    private int mediaRevision;

    public final ItemHandlerSimple hardDisk = itemManager.addInvHandler("hardDisk", 1,
            (slot, stack) -> stack.isEmpty() || ItemHardDisk.isDisk(stack), EnumAccess.NONE);

    public boolean hasHardDisk() { return ItemHardDisk.isDisk(hardDisk.getStackInSlot(0)); }
    public boolean hasMountedDisk() { return hasHardDisk() && diskMounted && !diskFault; }
    public int getMediaRevision() { return mediaRevision; }
    public ProcessorDiskLayout getDiskLayout() {
        return diskLayout == null ? ProcessorDiskLayout.at(worldPosition) : diskLayout;
    }
    public int workMinX() { return getDiskLayout().minX(worldPosition); }
    public int workMinZ() { return getDiskLayout().minZ(worldPosition); }
    public boolean stationInRange(BlockPos pos) {
        return getDiskLayout().contains(worldPosition, pos.getX(), pos.getZ()) && linksInThisDimension();
    }
    public boolean isOperational() {
        if (!hasMountedDisk() || isPhysicsOperationPaused()) return false;
        long required = Math.max(RobotProcessorPower.BASE_PER_TICK, requestedPower);
        return consumedPower > 0 || getStoredEnergy() >= required;
    }
    private String currentDimension() {
        if (level == null) return "";
        return level.dimension().location().toString();
    }
    private boolean linksInThisDimension() { return linkDimension.equals(currentDimension()); }

    private static int diskInt(CompoundTag t, String key) {
        //? if >=1.21.11 {
        return buildcraft.lib.compat.NbtCompat.getInt(t, key);
        //?} else {
        return t.getInt(key);
        //?}
    }
    private static String diskString(CompoundTag t, String key) {
        //? if >=1.21.11 {
        return buildcraft.lib.compat.NbtCompat.getString(t, key);
        //?} else {
        return t.getString(key);
        //?}
    }
    private static long[] diskLongs(CompoundTag t, String key) {
        //? if >=1.21.11 {
        return buildcraft.lib.compat.NbtCompat.getLongArray(t, key);
        //?} else {
        return t.getLongArray(key);
        //?}
    }
    private static int[] diskInts(CompoundTag t, String key) {
        //? if >=1.21.11 {
        return buildcraft.lib.compat.NbtCompat.getIntArray(t, key);
        //?} else {
        return t.getIntArray(key);
        //?}
    }
    private static byte[] diskBytes(CompoundTag t, String key) {
        //? if >=1.21.11 {
        return buildcraft.lib.compat.NbtCompat.getByteArray(t, key);
        //?} else {
        return t.getByteArray(key);
        //?}
    }

    /** Write through on durable edits, before any menu extraction can copy the stack. */
    private void saveDisk(boolean paintChanged) {
        if (loadingDisk || !hasMountedDisk() || !(level instanceof ServerLevel)) return;
        if (paintChanged || diskPaint == null) diskPaint = getDiskLayout().encode(layers, worldPosition);
        CompoundTag data = new CompoundTag();
        data.putInt("format", ItemHardDisk.FORMAT);
        data.putInt("offsetX", getDiskLayout().offsetX());
        data.putInt("offsetZ", getDiskLayout().offsetZ());
        data.putByteArray("paint", diskPaint);
        data.putString("dimension", linkDimension);
        data.putString("diskIdentity", diskIdentity);
        data.putString("name", mapName);
        data.putInt("selectedLayer", getCurrentSelectedArea());
        data.putInt("maxExecutionQueue", maxExecutionQueue);
        data.putLongArray("linkedRobots", savedRobotIds());
        data.putLongArray("knownRobotPositions", savedRobotPositions());
        data.putLongArray("linkedStations", savedStationPositions());
        data.putIntArray("linkedStationSides", savedStationSides());
        data.putString("robotNames", serializeRobotNames());
        data.putString("stationNames", serializeStationNames());
        ItemHardDisk.write(hardDisk.getStackInSlot(0), data);
        setChanged();
    }

    private void resetDiskRuntime() {
        stopScheduling();
        workScheduler.onRemoved();
        workScheduler.onLinkChanged();
        Set<ChunkPos> obsoleteTickets = new HashSet<>(legacyForcedTicketCleanup);
        for (long id : forceLoadedRobotIds) {
            BlockPos pos = lastKnownRobotPositions.get(id);
            if (pos != null) obsoleteTickets.add(new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4));
        }
        loadLinks(new long[0], new long[0], new int[0], new long[0], new long[0]);
        legacyForcedTicketCleanup.addAll(obsoleteTickets);
        persistedMiningWork = new long[0];
        persistedCatalog = RobotWorkIndex.CatalogSnapshot.empty();
        for (int i = 0; i < layers.length; i++) layers[i] = new ZonePlan();
        super.selectArea(0);
        mapName = "";
        maxExecutionQueue = DEFAULT_EXECUTION_QUEUE;
        diskLayout = ProcessorDiskLayout.at(worldPosition);
        diskPaint = null;
        diskIdentity = "";
        linkRegistry = null;
        linkDimension = currentDimension();
        requestedPower = consumedPower = 0;
        diskMounted = false;
        diskFault = false;
    }

    private void mountDisk() {
        loadingDisk = true;
        try {
            resetDiskRuntime();
            ItemStack stack = hardDisk.getStackInSlot(0);
            if (!ItemHardDisk.isDisk(stack)) return;
            if (ItemHardDisk.initialized(stack)) {
                CompoundTag data = ItemHardDisk.read(stack);
                if (diskInt(data, "format") != ItemHardDisk.FORMAT) throw new IllegalArgumentException("Unknown HDD format");
                diskLayout = new ProcessorDiskLayout(diskInt(data, "offsetX"), diskInt(data, "offsetZ"));
                if (!data.contains("paint")) throw new IllegalArgumentException("Missing HDD paint data");
                diskPaint = diskBytes(data, "paint");
                ZonePlan[] decoded = diskLayout.decode(diskPaint, worldPosition);
                System.arraycopy(decoded, 0, layers, 0, layers.length);
                linkDimension = diskString(data, "dimension");
                diskIdentity = diskString(data, "diskIdentity");
                Set<ChunkPos> obsoleteTickets = new HashSet<>(legacyForcedTicketCleanup);
                loadLinks(diskLongs(data, "linkedRobots"), diskLongs(data, "linkedStations"),
                        diskInts(data, "linkedStationSides"), diskLongs(data, "knownRobotPositions"), new long[0]);
                legacyForcedTicketCleanup.addAll(obsoleteTickets);
                restoreNames(diskString(data, "robotNames"), diskString(data, "stationNames"));
                maxExecutionQueue = Math.max(MIN_EXECUTION_QUEUE, Math.min(MAX_EXECUTION_QUEUE, diskInt(data, "maxExecutionQueue")));
                super.selectArea(diskInt(data, "selectedLayer"));
                super.setMapName(diskString(data, "name"));
            }
            if (diskIdentity.isEmpty()) diskIdentity = java.util.UUID.randomUUID().toString();
            else java.util.UUID.fromString(diskIdentity); // Preserve unreadable media rather than assigning a new identity.
            diskMounted = true;
            reconcileControllerLinks(true);
        } catch (RuntimeException invalidDisk) {
            resetDiskRuntime();
            diskFault = true; // Preserve unreadable data verbatim; never silently format it.
        } finally {
            loadingDisk = false;
            mediaRevision++;
            notifyZoneMediaChanged();
        }
        saveDisk(false);
    }

    private void diskChanged(ItemStack before, ItemStack after) {
        if (loadingDisk || !(level instanceof ServerLevel)) return;
        // Slot#setChanged may notify with the exact same stack after a normal click.
        if (before == after && diskMounted) return;
        mountDisk();
    }

    private void restoreMediaOnLoad() {
        if (!(level instanceof ServerLevel)) return;
        if (hasHardDisk()) { mountDisk(); return; }
        // One-time migration for configured legacy processors. New empty blocks do not get a free disk.
        boolean legacy = !linkedRobotIds.isEmpty() || !linkedStations.isEmpty() || !mapName.isEmpty()
                || maxExecutionQueue != DEFAULT_EXECUTION_QUEUE;
        for (ZonePlan layer : layers) legacy |= !layer.getChunkMapping().isEmpty();
        if (legacy) {
            loadingDisk = true;
            try { hardDisk.setStackInSlot(0, new ItemStack(BCRoboticsItems.HARD_DISK.get())); }
            finally { loadingDisk = false; }
            linkDimension = currentDimension();
            diskLayout = ProcessorDiskLayout.at(worldPosition);
            diskIdentity = java.util.UUID.randomUUID().toString();
            diskMounted = true;
            reconcileControllerLinks(true);
            saveDisk(true);
            mediaRevision++;
            notifyZoneMediaChanged();
        } else mountDisk();
    }


    public long getStoredEnergy() { return processorBattery.getStored(); }
    public long getRequestedPower() { return requestedPower; }
    public long getConsumedPower() { return consumedPower; }

    private void restoreEnergy(long stored) {
        processorBattery.extractAll();
        processorBattery.insert(MjAmount.ofMicro(Math.max(0L, Math.min(ENERGY_CAPACITY, stored))),
                OperationMode.EXECUTE);
        lastStoredEnergy = processorBattery.getStored();
    }

    /** Charge for loaded linked robots, even if they are sleeping at a dock.
     * Unloaded/offline robots do not require scheduling work. */
    private int loadedLinkedRobotCount() {
        if (!hasMountedDisk() || !linksInThisDimension() || RobotManager.registryProvider == null || level == null) return 0;
        var registry = controllerRegistry();
        if (registry == null) return 0;
        int count = 0;
        for (long id : getLinkedRobotIds()) {
            EntityRobotBase robot = registry.getLoadedRobot(id);
            if (robot != null && !robot.isRemoved()) count++;
        }
        return count;
    }

    private void stopScheduling() {
        // Remove provisional leases and the panel from the world's active
        // scheduler registry. Actual robot-owned work/energy is independent.
        if (schedulerRunning) workScheduler.onPowerPaused();
        schedulerRunning = false;
        consumedPower = 0;
    }

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
        if (requestedPower == 0) {
            return new RobotProcessorWorkScheduler.ScanProgress(0, 0, RobotProcessorWorkScheduler.SCAN_WAITING, 0);
        }
        if (consumedPower == 0) {
            var progress = workScheduler.scanProgress();
            return new RobotProcessorWorkScheduler.ScanProgress(progress.percent(), progress.currentPassPercent(),
                    RobotProcessorWorkScheduler.SCAN_NO_POWER, progress.sections());
        }
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
        if (!isOperational()) return;
        int clamped = Math.max(MIN_EXECUTION_QUEUE, Math.min(MAX_EXECUTION_QUEUE, value));
        if (maxExecutionQueue != clamped) {
            maxExecutionQueue = clamped;
            workScheduler.onQueueLimitChanged();
            saveDisk(false);
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
        if (!isRobotLinked(robotId)) return false;
        String clean = normalizeLinkName(name);
        if (clean.isEmpty()) robotNames.remove(robotId);
        else robotNames.put(robotId, clean);
        saveDisk(false);
        return true;
    }

    public boolean renameStation(BlockPos pos, Direction side, String name) {
        StationKey key = new StationKey(pos, side);
        if (!isOperational() || !linksInThisDimension() || !linkedStations.contains(key)) return false;
        String clean = normalizeLinkName(name);
        if (clean.isEmpty()) stationNames.remove(key);
        else stationNames.put(key, clean);
        saveDisk(false);
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
    private boolean lastOperationalForTickets;
    private int chunkLoadingStartupDelay = 40;
    /** A 7x7 square of chunks centered on the chunk containing this machine. */
    public static final int WORK_AREA_RADIUS_CHUNKS = 3;

    public static boolean isWithinWorkArea(BlockPos origin, int chunkX, int chunkZ) {
        int centerChunkX = origin.getX() >> 4;
        int centerChunkZ = origin.getZ() >> 4;
        return Math.abs((long) chunkX - centerChunkX) <= WORK_AREA_RADIUS_CHUNKS
                && Math.abs((long) chunkZ - centerChunkZ) <= WORK_AREA_RADIUS_CHUNKS;
    }

    private static int chunkX(ChunkPos chunk) {
        //? if mc_26_x {
        return chunk.x();
        //?} else {
        return chunk.x;
        //?}
    }

    private static int chunkZ(ChunkPos chunk) {
        //? if mc_26_x {
        return chunk.z();
        //?} else {
        return chunk.z;
        //?}
    }

    public static ZonePlan limitToWorkArea(ZonePlan plan, BlockPos origin) {
        return plan == null ? new ZonePlan()
                : plan.copyChunksMatching(chunk -> isWithinWorkArea(origin, chunkX(chunk), chunkZ(chunk)));
    }

    /**
     * The map's active painted layers form a union. An unpainted panel retains
     * the full 7x7 area, matching the initial configuration without a map.
     */
    public boolean allowsWorkPosition(BlockPos target) {
        if (!isOperational()) return false;
        if (!getDiskLayout().contains(worldPosition, target.getX(), target.getZ())) return false;
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
        if (!(level instanceof ServerLevel) || !isOperational() || index < 0 || index >= layers.length) return;
        layers[index] = getDiskLayout().clip(area, worldPosition);
        workScheduler.onLinkChanged();
        saveDisk(true);
        notifyZoneMediaChanged();
    }

    @Override public ZonePlan selectArea(int index) {
        int previous = getCurrentSelectedArea();
        ZonePlan result = super.selectArea(index);
        if (previous != getCurrentSelectedArea()) saveDisk(false);
        return result;
    }

    @Override public void setMapName(String name) {
        if (!isOperational()) return;
        super.setMapName(name);
        saveDisk(false);
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
        return hasMountedDisk() && linksInThisDimension() ? List.copyOf(linkedStations) : List.of();
    }

    public boolean linkStation(BlockPos pos, Direction side) {
        if (!isOperational() || !linksInThisDimension()) return false;
        if (pos == null || side == null || linkedStations.size() >= 64) return false;
        boolean added = linkedStations.add(new StationKey(pos.immutable(), side));
        if (added) saveDisk(false);
        return added;
    }

    public boolean unlinkStation(BlockPos pos, Direction side) {
        if (!isOperational() || !linksInThisDimension()) return false;
        boolean removed = linkedStations.remove(new StationKey(pos, side));
        if (removed) {
            stationNames.remove(new StationKey(pos, side));
            saveDisk(false);
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
        for (int i = 0; i < robotIds.length && linkedRobotIds.size() < MAX_LINKED_ROBOTS; i++) {
            long id = robotIds[i];
            if (id <= 0L || id == Long.MAX_VALUE || !linkedRobotIds.add(id)) continue;
            if (i < knownPositions.length && knownPositions[i] != UNKNOWN_ROBOT_POS) {
                lastKnownRobotPositions.put(id, BlockPos.of(knownPositions[i]));
            }
        }
        // Old saves could contain 64 links. Release old platform tickets even
        // for links beyond the new cap, before forgetting those positions.
        Set<Long> oldForcedIds = new HashSet<>();
        for (long id : forcedIds) oldForcedIds.add(id);
        for (int i = 0; i < robotIds.length && i < knownPositions.length && i < 64; i++) {
            if (oldForcedIds.contains(robotIds[i]) && knownPositions[i] != UNKNOWN_ROBOT_POS) {
                BlockPos old = BlockPos.of(knownPositions[i]);
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
        restoreEnergy(data.readLong("processorEnergy"));
        loadLinks(data.readLongArray("linkedRobots"), data.readLongArray("linkedStations"),
                data.readIntArray("linkedStationSides"),
                data.readLongArray("knownRobotPositions"), data.readLongArray("forcedRobotChunks"));
        restoreCleanupTickets(data.readLongArray("cleanupTickets"));
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
        data.writeLong("processorEnergy", getStoredEnergy());
        removeLegacyMapData(data.tag());
        data.writeLongArray("cleanupTickets", cleanupTicketPositions());
    }
    //?} else {
    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        restoreEnergy(tag.getLong("processorEnergy"));
        loadLinks(tag.getLongArray("linkedRobots"), tag.getLongArray("linkedStations"),
                tag.getIntArray("linkedStationSides"),
                tag.getLongArray("knownRobotPositions"), tag.getLongArray("forcedRobotChunks"));
        restoreCleanupTickets(tag.getLongArray("cleanupTickets"));
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
        tag.putLong("processorEnergy", getStoredEnergy());
        removeLegacyMapData(tag);
        tag.putLongArray("cleanupTickets", cleanupTicketPositions());
    }
    //?}


    private long[] cleanupTicketPositions() {
        Set<ChunkPos> chunks = new HashSet<>(legacyForcedTicketCleanup);
        for (long id : forceLoadedRobotIds) {
            BlockPos pos = lastKnownRobotPositions.get(id);
            if (pos != null) chunks.add(new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4));
        }
        // Pack the chunk coordinates with the same block-position codec used by links.
        return chunks.stream().mapToLong(c -> new BlockPos(chunkX(c), 0, chunkZ(c)).asLong()).toArray();
    }

    private void restoreCleanupTickets(long[] encoded) {
        for (int i = 0; i < Math.min(128, encoded.length); i++) {
            BlockPos pos = BlockPos.of(encoded[i]);
            legacyForcedTicketCleanup.add(new ChunkPos(pos.getX(), pos.getZ()));
        }
    }

    private static void removeLegacyMapData(CompoundTag tag) {
        tag.remove("name");
        tag.remove("currentSelectedArea");
        for (int i = 0; i < LAYER_COUNT; i++) {
            tag.remove("selectedArea[" + i + "]");
            tag.remove("layer_" + i);
        }
    }

    private buildcraft.robotics.internal.legacy.robots.IRobotRegistry controllerRegistry() {
        if (!(level instanceof ServerLevel) || RobotManager.registryProvider == null
                || !hasMountedDisk() || !linksInThisDimension() || diskIdentity.isEmpty()) return null;
        if (linkRegistry == null) linkRegistry = RobotManager.registryProvider.getRegistry(level);
        return linkRegistry;
    }

    private boolean ownsRobotLink(long id) {
        var registry = controllerRegistry();
        return registry != null && registry.robotIdentities().controls(id, diskIdentity);
    }

    /** Never let a stale HDD reclaim a robot explicitly moved to a different HDD, even after unload. */
    private void reconcileControllerLinks(boolean adoptLegacy) {
        var registry = controllerRegistry();
        if (registry == null || linkedRobotIds.isEmpty()) return;
        var identities = registry.robotIdentities();
        boolean changed = false;
        for (long id : List.copyOf(linkedRobotIds)) {
            boolean accepted = adoptLegacy ? identities.adopt(id, diskIdentity) : identities.controls(id, diskIdentity);
            if (!accepted) changed |= removeLocalRobotLink(id);
        }
        if (adoptLegacy) registry.registryMarkDirty();
        if (changed) {
            workScheduler.onLinkChanged();
            saveDisk(false);
        }
    }

    public List<Long> getLinkedRobotIds() {
        var registry = controllerRegistry();
        if (registry == null) return List.of();
        var identities = registry.robotIdentities();
        return linkedRobotIds.stream().filter(id -> identities.controls(id, diskIdentity)).toList();
    }

    public boolean isRobotLinked(long robotId) {
        return isOperational() && linksInThisDimension() && linkedRobotIds.contains(robotId) && ownsRobotLink(robotId);
    }

    /** Called only by an explicit binding action, never from automatic discovery. */
    public boolean linkRobot(long robotId) {
        return linkRobot(robotId, null);
    }

    public boolean linkRobot(long robotId, BlockPos location) {
        if (!isOperational() || !linksInThisDimension()) return false;
        var registry = controllerRegistry();
        if (registry == null || robotId <= 0L || robotId == Long.MAX_VALUE) return false;
        reconcileControllerLinks(false);
        // Check the destination capacity before invalidating the previous controller.
        if (linkedRobotIds.contains(robotId) || linkedRobotIds.size() >= MAX_LINKED_ROBOTS) return false;
        registry.robotIdentities().assign(robotId, diskIdentity);
        registry.registryMarkDirty();
        linkedRobotIds.add(robotId);
        if (location != null) lastKnownRobotPositions.put(robotId, location.immutable());
        workScheduler.onLinkChanged();
        saveDisk(false);
        return true;
    }

    public BlockPos getLastKnownRobotPosition(long robotId) {
        return lastKnownRobotPositions.get(robotId);
    }

    /** Preserve the last known chunk even if the robot entity later unloads. */
    public void recordRobotPosition(long robotId, BlockPos pos) {
        if (!hasMountedDisk() || !linksInThisDimension() || !linkedRobotIds.contains(robotId) || !ownsRobotLink(robotId) || pos == null) return;
        BlockPos previous = lastKnownRobotPositions.put(robotId, pos.immutable());
        if (!pos.equals(previous)) {
            if (forceLoadedRobotIds.contains(robotId) && (previous == null
                    || (previous.getX() >> 4) != (pos.getX() >> 4)
                    || (previous.getZ() >> 4) != (pos.getZ() >> 4))) chunkTicketsDirty = true;
            saveDisk(false);
        }
    }

    public boolean unlinkRobot(long robotId) {
        if (!isOperational() || !linksInThisDimension()) return false;
        var registry = controllerRegistry();
        if (registry == null || !ownsRobotLink(robotId)) return false;
        if (!removeLocalRobotLink(robotId)) return false;
        registry.robotIdentities().release(robotId, diskIdentity);
        registry.registryMarkDirty();
        workScheduler.onLinkChanged();
        saveDisk(false);
        return true;
    }

    private boolean removeLocalRobotLink(long robotId) {
        if (!linkedRobotIds.remove(robotId)) return false;
        robotNames.remove(robotId);
        lastKnownRobotPositions.remove(robotId);
        forceChunkExpiries.remove(robotId);
        if (forceLoadedRobotIds.remove(robotId)) chunkTicketsDirty = true;
        return true;
    }

    /** Force-load only explicitly linked robots, using BC's owner-scoped tickets. */
    public boolean forceRobotChunk(long robotId) {
        if (!isOperational()) return false;
        if (!(level instanceof ServerLevel server) || !isRobotLinked(robotId)
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
        if (!isOperational()) return java.util.Collections.emptySet();
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
        schedulerRunning = false;
        requestedPower = consumedPower = 0;
        restoreMediaOnLoad();
        workScheduler.onLinkChanged();
        // Avoid ticket operations inside the owner's chunk load future.
        chunkLoadingStartupDelay = 40;
    }

    @Override
    public boolean isPhysicsOperationPaused() { return isOnPhysicalStructure(); }

    @Override
    public void update() {
        super.update();
        if (!(level instanceof ServerLevel server)) return;
        reconcileControllerLinks(false);
        if (!hasMountedDisk() || isPhysicsOperationPaused()) {
            requestedPower = 0;
            stopScheduling();
        } else {
            // Do not execute scheduler work without enough MJ for the full tick.
            requestedPower = RobotProcessorPower.requiredPerTick(loadedLinkedRobotCount());
            if (requestedPower > 0 && processorBattery.extractPower(requestedPower)) {
                consumedPower = requestedPower;
                schedulerRunning = true;
                workScheduler.tick(server);
            } else {
                stopScheduling();
            }
        }
        long now = server.getGameTime();
        if (hasMountedDisk()) workScheduler.flushCatalogDirty(now);
        if (lastOperationalForTickets != isOperational()) {
            lastOperationalForTickets = isOperational();
            chunkTicketsDirty = true;
        }
        markEnergyChanged();
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

    private void markEnergyChanged() {
        if (lastStoredEnergy != getStoredEnergy()) {
            lastStoredEnergy = getStoredEnergy();
            setChanged();
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
        schedulerRunning = false;
        requestedPower = consumedPower = 0;
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
        hardDisk.setLimitedInsertor(1);
        hardDisk.setCallback((handler, slot, before, after) -> {
            diskChanged(before, after);
            if (level != null) itemManager.callback.onStackChange(handler, slot, before, after);
        });
        caps.addProvider(new MjCapabilityHelper(new MjRedstoneBatteryReceiver(processorBattery)));
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new ContainerRobotProcessor(id, inventory, this,
                ContainerLevelAccess.create(level, worldPosition));
    }
}
