package buildcraft.robotics.container;

import buildcraft.robotics.RobotTaskNames;
import buildcraft.robotics.scheduler.RobotProcessorDiagnostics;
import buildcraft.robotics.scheduler.RobotProcessorNavigationProfiler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
//? if <1.19.3 {
/*?
import net.minecraft.core.Registry;
?*/
//?} else {
import net.minecraft.core.registries.BuiltInRegistries;
//?}
import java.util.List;

import buildcraft.lib.gui.slot.SlotBase;
import buildcraft.lib.misc.data.IdAllocator;
import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.net.BCPacketContext;
import buildcraft.lib.net.NetworkSecurity;
import buildcraft.robotics.BCRoboticsGuis;
import buildcraft.robotics.ai.AIRobotGoAndLinkToDock;
import buildcraft.robotics.internal.legacy.robots.DockingStation;
import buildcraft.robotics.internal.legacy.robots.IRobotRegistry;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.internal.legacy.robots.RobotManager;
import buildcraft.robotics.tile.TileRobotProcessor;
import buildcraft.robotics.item.ItemRobotController;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import buildcraft.robotics.tile.TileRobotProcessor.StationKey;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** The control panel shares Zone Planner's painted-map protocol but has its own slots and robot telemetry. */
public class ContainerRobotProcessor extends ContainerZonePlanner {
    private static final IdAllocator ROBOT_IDS = IDS.makeChild("robot_processor");
    private static final int ID_ROBOTS = ROBOT_IDS.allocId("robots");
    private static final int ID_STATIONS = ROBOT_IDS.allocId("stations");
    private static final int ID_RECALL = ROBOT_IDS.allocId("recall");
    private static final int ID_FORCE_CHUNK = ROBOT_IDS.allocId("force_robot_chunk");
    private static final int ID_UNLINK_ROBOT = ROBOT_IDS.allocId("unlink_robot");
    private static final int ID_UNLINK_STATION = ROBOT_IDS.allocId("unlink_station");
    private static final int ID_QUEUE_STATE = ROBOT_IDS.allocId("queue_state");
    private static final int ID_SET_QUEUE = ROBOT_IDS.allocId("set_execution_queue");
    private static final int ID_RENAME_ROBOT = ROBOT_IDS.allocId("rename_robot");
    private static final int ID_RENAME_STATION = ROBOT_IDS.allocId("rename_station");
    private static final int ID_REQUEST_CARGO = ROBOT_IDS.allocId("request_robot_cargo");
    private static final int ID_CARGO = ROBOT_IDS.allocId("robot_cargo");
    // Append the new ID after all previous message IDs to keep their values stable.
    private static final int ID_SCAN_PROGRESS = ROBOT_IDS.allocId("scan_progress");
    // Append only: existing protocol message IDs must not be renumbered.
    private static final int ID_DIAGNOSTICS = ROBOT_IDS.allocId("processor_diagnostics");
    private static final int ID_NAVIGATION_PROFILE = ROBOT_IDS.allocId("processor_navigation_profile");
    // A robot currently has four transfer slots and one tank; leave room for extensions.
    private static final int MAX_CARGO_ENTRIES = 32;
    private static final int MAX_CARGO_TANKS = 8;
    private static final int MAX_ROBOT_SLOTS = 32;
    private static final int MAX_FLUID_AMOUNT = 1_000_000;
    private static final int ROBOT_LIMIT = 64;
    private final List<RobotSnapshot> snapshots = new ArrayList<>();
    private final List<StationSnapshot> stationSnapshots = new ArrayList<>();
    // Demand-driven snapshots: only the selected, explicitly linked robot is inspected.
    private final List<CargoEntry> cargo = new ArrayList<>();
    private long cargoRobotId = -1;
    private boolean cargoOnline;
    private long requestedCargoRobotId = -1;
    private int snapshotTick;
    private int maxExecutionQueue = TileRobotProcessor.DEFAULT_EXECUTION_QUEUE;
    private int lastSentExecutionQueue = -1;
    public static final int SCAN_WAITING = 0;
    public static final int SCAN_RUNNING = 1;
    public static final int SCAN_COMPLETE = 2;
    public static final int SCAN_REFRESHING = 3;
    public static final int SCAN_NO_CHUNKS = 4;

    /** Values received from the server, never computed from the client's world. */
    public record ScanProgress(int percent, int passPercent, int phase, int sections) { }
    private ScanProgress scanProgress = new ScanProgress(0, 0, SCAN_WAITING, 0);
    private ScanProgress lastSentScanProgress;
    private RobotProcessorDiagnostics.Snapshot diagnostics = RobotProcessorDiagnostics.Snapshot.EMPTY;
    private RobotProcessorDiagnostics.Snapshot lastSentDiagnostics;
    private RobotProcessorNavigationProfiler.Snapshot navigationProfile =
            RobotProcessorNavigationProfiler.Snapshot.EMPTY;
    private RobotProcessorNavigationProfiler.Snapshot lastSentNavigationProfile;

    /** A position/status snapshot, not a client-side reference to a server entity. */
    public record RobotSnapshot(long id, String board, String task, String status,
                                int x, int y, int z, int charge, int progress, String name, int queued, String reason) {}
    /** Read-only contents used for GUI rendering. No inventory slots are exposed for interaction. */
    public record CargoEntry(ItemStack stack, String fluidId, int amountMb, int capacityMb) {
        public boolean isFluid() { return !fluidId.isEmpty(); }
    }
    public record StationSnapshot(int x, int y, int z, int side, String status, long robotId, String name) {}

    public ContainerRobotProcessor(int containerId, Inventory inventory, FriendlyByteBuf buffer) {
        this(containerId, inventory, null, createLevelAccess(inventory, buffer));
    }

    public ContainerRobotProcessor(int containerId, Inventory inventory, TileRobotProcessor tile,
                                   ContainerLevelAccess access) {
        super(BCRoboticsGuis.MENU_ROBOT_PROCESSOR.get(), containerId, inventory, tile, access);
        // Zone Planner's two map crafting slots are not used by this block.
        relocateSlot(0, -200, -200);
        relocateSlot(1, -200, -200);
        // No Map Location slot: leave an inert placeholder only to retain the
        // inherited menu's player-slot indices (3..38). It cannot accept items.
        Slot importedMap = slots.get(2);
        if (importedMap instanceof SlotBase base) {
            replaceSlot(2, new SlotBase(base.itemHandler, base.handlerIndex, -200, -200) {
                @Override public boolean mayPlace(ItemStack stack) { return false; }
                @Override public boolean mayPickup(Player player) { return false; }
                @Override public boolean canShift() { return false; }
            });
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int slot = 3 + row * 9 + col;
                replaceSlot(slot, new Slot(inventory, col + row * 9 + 9, 8 + col * 18, 191 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            replaceSlot(30 + col, new Slot(inventory, col, 8 + col * 18, 249));
        }
        // The 4x5 grid on the lower right is the map editor's interactive
        // palette (eraser/shapes + 16 brushes), NOT a 20-slot item inventory.
        TileRobotProcessor processor = getProcessor();
        if (processor != null) maxExecutionQueue = processor.getMaxExecutionQueue();
    }

    private void relocateSlot(int index, int x, int y) {
        Slot old = slots.get(index);
        if (old instanceof SlotBase base) {
            replaceSlot(index, new SlotBase(base.itemHandler, base.handlerIndex, x, y));
        }
    }

    private void replaceSlot(int index, Slot replacement) {
        replacement.index = index;
        slots.set(index, replacement);
    }

    @Override
    public void applyClientGuiLayout(boolean legacy) {
        // Screen positions are set by this menu rather than Zone Planner layout mode.
    }

    @Override
    public IdAllocator getIdAllocator() { return ROBOT_IDS; }

    public List<RobotSnapshot> getRobots() { return List.copyOf(snapshots); }
    public List<StationSnapshot> getStations() { return List.copyOf(stationSnapshots); }

    public List<CargoEntry> getCargo() { return Collections.unmodifiableList(cargo); }
    public long getCargoRobotId() { return cargoRobotId; }
    public boolean isCargoOnline() { return cargoOnline; }

    /** Ask the server for contents on selection change; never trust client-supplied contents. */
    public void requestCargo(long robotId) {
        if (cargoRobotId == robotId) return;
        cargoRobotId = robotId;
        cargo.clear();
        cargoOnline = false;
        sendMessage(ID_REQUEST_CARGO, out -> out.writeLong(robotId));
    }

    public int getMaxExecutionQueue() { return maxExecutionQueue; }
    public ScanProgress getScanProgress() { return scanProgress; }
    public RobotProcessorDiagnostics.Snapshot getDiagnostics() { return diagnostics; }
    public RobotProcessorNavigationProfiler.Snapshot getNavigationProfile() { return navigationProfile; }


    /** Configure the limit for queued execution actions, not pathfinding/search steps. */
    public void setMaxExecutionQueue(int requested) {
        int target = Math.max(TileRobotProcessor.MIN_EXECUTION_QUEUE,
                Math.min(TileRobotProcessor.MAX_EXECUTION_QUEUE, requested));
        if (target == maxExecutionQueue) return;
        maxExecutionQueue = target; // responsive UI; server sends the authoritative value.
        sendMessage(ID_SET_QUEUE, out -> out.writeByte(target));
    }

    public void renameRobot(long id, String name) {
        if (id <= 0) return;
        String requested = name == null ? "" : name;
        sendMessage(ID_RENAME_ROBOT, out -> {
            out.writeLong(id);
            out.writeUtf(requested, TileRobotProcessor.MAX_LINK_NAME_LENGTH);
        });
    }

    public void renameStation(int x, int y, int z, int side, String name) {
        if (side < 0 || side > 5) return;
        String requested = name == null ? "" : name;
        sendMessage(ID_RENAME_STATION, out -> {
            out.writeBlockPos(new BlockPos(x, y, z));
            out.writeByte(side);
            out.writeUtf(requested, TileRobotProcessor.MAX_LINK_NAME_LENGTH);
        });
    }

    public BlockPos getPanelPos() {
        return access.evaluate((level, pos) -> pos, null);
    }

    /** Entity/Player gained level() in 1.20; Forge 1.19.2 still exposes the level field. */
    private static Level playerLevel(Player player) {
        //? if <1.20 {
        return player.level;
        //?} else {
        return player.level();
        //?}
    }

    @Override
    public boolean stillValid(Player player) {
        if (super.stillValid(player)) return true;
        TileRobotProcessor panel = getProcessor();
        if (panel != null) {
            if (panel.isRemoved() || panel.getLevel() != playerLevel(player)) return false;
            return ItemRobotController.matchesPanel(player.getItemInHand(InteractionHand.MAIN_HAND), panel)
                || ItemRobotController.matchesPanel(player.getItemInHand(InteractionHand.OFF_HAND), panel);
        }
        // The client may not have the remote processor's chunk. The server owns
        // the authoritative menu lifetime and validates the held controller.
        //? if mc_26_x && (>=26.3) {
        return playerLevel(player).isClientSide();
        //?} else {
        return playerLevel(player).isClientSide;
        //?}
    }

    public TileRobotProcessor getProcessor() {
        return tile instanceof TileRobotProcessor processor ? processor : null;
    }

    public void recall(long robotId) {
        if (robotId > 0L) sendMessage(ID_RECALL, out -> out.writeLong(robotId));
    }

    public void forceRobotChunk(long robotId) {
        if (robotId > 0L) sendMessage(ID_FORCE_CHUNK, out -> out.writeLong(robotId));
    }

    public void unlinkRobot(long robotId) {
        if (robotId > 0L) sendMessage(ID_UNLINK_ROBOT, out -> out.writeLong(robotId));
    }

    public void unlinkStation(int x, int y, int z, int side) {
        if (side >= 0 && side < 6) sendMessage(ID_UNLINK_STATION, out -> {
            out.writeBlockPos(new BlockPos(x, y, z));
            out.writeByte(side);
        });
    }

    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        TileRobotProcessor processor = getProcessor();
        if (playerInventory.player instanceof ServerPlayer && processor != null
                && lastSentExecutionQueue != processor.getMaxExecutionQueue()) {
            lastSentExecutionQueue = processor.getMaxExecutionQueue();
            sendMessage(ID_QUEUE_STATE, out -> out.writeByte(lastSentExecutionQueue));
        }
        if (playerInventory.player instanceof ServerPlayer && processor != null) {
            var serverProgress = processor.getScanProgress();
            ScanProgress next = new ScanProgress(serverProgress.percent(),
                    serverProgress.currentPassPercent(), serverProgress.phase(), serverProgress.sections());
            // 10-tick throttle avoids 20 packets per second during a large scan.
            // State changes and first opening still arrive without that delay.
            if (lastSentScanProgress == null
                    || (snapshotTick % 10 == 0 || next.phase() != lastSentScanProgress.phase())
                    && !next.equals(lastSentScanProgress)) {
                lastSentScanProgress = next;
                scanProgress = next;
                sendMessage(ID_SCAN_PROGRESS, out -> {
                    out.writeByte(next.percent());
                    out.writeByte(next.passPercent());
                    out.writeByte(next.phase());
                    out.writeVarInt(next.sections());
                });
            }
        }
        if (playerInventory.player instanceof ServerPlayer && processor != null && snapshotTick % 20 == 0) {
            RobotProcessorDiagnostics.Snapshot next = processor.getDiagnostics();
            if (!next.equals(lastSentDiagnostics)) {
                lastSentDiagnostics = next;
                sendMessage(ID_DIAGNOSTICS, out -> {
                    out.writeVarInt(next.inspected());
                    out.writeVarInt(next.dirty());
                    out.writeVarInt(next.focused());
                    out.writeVarInt(next.background());
                    out.writeVarInt(next.skippedSections());
                    out.writeVarInt(next.budgetLimitedTicks());
                    out.writeVarInt(next.cachedCandidates());
                    out.writeVarInt(next.categories());
                    out.writeVarInt(next.linkedRobots());
                    out.writeVarInt(next.loadedChunks());
                    out.writeVarInt(next.averageMicros());
                    out.writeVarInt(next.peakMicros());
                });
            }
        }
        if (playerInventory.player instanceof ServerPlayer && processor != null && snapshotTick % 20 == 0) {
            RobotProcessorNavigationProfiler.Snapshot next = processor.getNavigationProfile();
            if (!next.equals(lastSentNavigationProfile)) {
                lastSentNavigationProfile = next;
                sendMessage(ID_NAVIGATION_PROFILE, out -> {
                    out.writeVarInt(next.cacheProbes());
                    out.writeVarInt(next.cacheCandidates());
                    out.writeVarInt(next.directHits());
                    out.writeVarInt(next.warmStarts());
                    out.writeVarInt(next.rejectedRoutes());
                    out.writeVarInt(next.storedRoutes());
                    out.writeVarInt(next.validatedWaypoints());
                    out.writeVarInt(next.aStarPolled());
                    out.writeVarInt(next.aStarExpanded());
                    out.writeVarInt(next.grantedNodes());
                    out.writeVarInt(next.refundedNodes());
                    out.writeVarInt(next.sharedWaitRobotTicks());
                    out.writeVarInt(next.budgetWaitRobotTicks());
                    out.writeVarInt(next.emptyQueueRobotTicks());
                    out.writeVarInt(next.readyMinerRobotTicks());
                    out.writeVarInt(next.estimatedReferenceNodesBypassed());
                    out.writeVarInt(next.cachedRoutes());
                });
            }
        }
        if (playerInventory.player instanceof ServerPlayer) {
            boolean normalRefresh = snapshotTick++ % 20 == 0;
            // Queue orders are issued/completed between 20-tick telemetry polls.
            // Push their count immediately; otherwise opening the GUI during
            // warm-up can leave the old 0/N on screen after a full queue appears.
            if (normalRefresh || queueCountsChanged(processor)) {
                refreshSnapshots();
            }
            if (normalRefresh) {
                refreshStationSnapshots();
                if (requestedCargoRobotId > 0) refreshCargoSnapshot();
            }
        }
    }

    /**
     * Only robots explicitly registered with this processor can appear in its GUI.
     * A nearby robot must never be silently linked just because it is loaded.
     */
    private boolean queueCountsChanged(TileRobotProcessor processor) {
        if (processor == null) return false;
        for (RobotSnapshot snapshot : snapshots) {
            if (snapshot.queued() != processor.getExecutionQueueSize(snapshot.id())) return true;
        }
        return false;
    }

    private void refreshSnapshots() {
        TileRobotProcessor processor = getProcessor();
        if (processor == null || processor.getLinkedRobotIds().isEmpty()) {
            if (!snapshots.isEmpty()) {
                snapshots.clear();
                sendMessage(ID_ROBOTS, buf -> buf.writeVarInt(0));
            }
            return;
        }
        if (RobotManager.registryProvider == null || tile.getLevel() == null) return;
        var registry = RobotManager.registryProvider.getRegistry(tile.getLevel());
        if (registry == null) return;
        List<RobotSnapshot> next = new ArrayList<>();
        for (long robotId : processor.getLinkedRobotIds()) {
            if (next.size() >= ROBOT_LIMIT) break;
            EntityRobotBase robot = registry.getLoadedRobot(robotId);
            if (robot == null || robot.isRemoved()) {
                BlockPos last = processor.getLastKnownRobotPosition(robotId);
                if (last != null) next.add(new RobotSnapshot(robotId, "Unknown", "Offline", "Offline",
                        last.getX(), last.getY(), last.getZ(), 0, -1, processor.getRobotName(robotId), 0,
                        "Robot chunk unloaded"));
                continue;
            }
            processor.recordRobotPosition(robotId, robot.blockPosition());
            String board = robot.getBoard() == null ? "Unprogrammed" : robot.getBoard().getNBTHandler().getID();
            var activeAI = robot.getActiveAI();
            String rawTask = activeAI == null ? "Idle" : activeAI.getClass().getSimpleName().replace("AIRobot", "");
            String task = RobotTaskNames.shortName(rawTask);
            String status = robot.getDockingStation() != null ? "Docked"
                    : rawTask.toLowerCase(java.util.Locale.ROOT).contains("sleep") ? "Sleeping"
                    : rawTask.toLowerCase(java.util.Locale.ROOT).contains("recharge") ? "Charging"
                    : robot.isMoving() ? "Moving" : "Working";
            if (robot.getBoard() instanceof buildcraft.robotics.boards.BoardRobotBuilder
                    && ("Docked".equals(status) || "Sleeping".equals(status))) {
                String missing = buildcraft.robotics.scheduler.RobotProcessorWorkScheduler.builderMaterialWait(robot);
                if (missing != null) {
                    status = "Materials";
                    task = missing;
                }
            }
            int percent = Math.max(0, Math.min(100,
                    (int) ((long) robot.getEnergy() * 100 / EntityRobotBase.MAX_ENERGY)));
            // Only execution AIs with measurable work expose a percentage.
            // Movement/search never masquerade as execution progress.
            int progress = activeAI == null ? -1 : activeAI.getExecutionProgress();
            progress = progress < 0 ? -1 : Math.max(0, Math.min(100, progress));
            next.add(new RobotSnapshot(robot.getRobotId(), truncate(board, 48), truncate(task, 64), status,
                    robot.blockPosition().getX(), robot.blockPosition().getY(), robot.blockPosition().getZ(),
                    percent, progress, processor.getRobotName(robotId), processor.getExecutionQueueSize(robotId),
                    truncate(processor.getRobotWaitReason(robot), 96)));
        }
        // Do not emit a full robot snapshot when nothing changed. The queue
        // trigger above is sufficient to deliver counts at execution speed.
        if (next.equals(snapshots)) return;
        snapshots.clear();
        snapshots.addAll(next);
        sendMessage(ID_ROBOTS, buf -> {
            buf.writeVarInt(next.size());
            for (RobotSnapshot robot : next) {
                buf.writeLong(robot.id());
                buf.writeUtf(robot.board(), 48);
                buf.writeUtf(robot.task(), 64);
                buf.writeUtf(robot.status(), 24);
                buf.writeInt(robot.x()); buf.writeInt(robot.y()); buf.writeInt(robot.z());
                buf.writeByte(robot.charge()); buf.writeByte(robot.progress());
                buf.writeUtf(robot.name(), TileRobotProcessor.MAX_LINK_NAME_LENGTH);
                buf.writeByte(robot.queued());
                buf.writeUtf(robot.reason(), 96);
            }
        });
    }

    private static void writeCargoItem(FriendlyByteBuf out, ItemStack stack) {
        //? if >=1.21.1 {
        buildcraft.lib.misc.ItemStackUtil.writeOptional(out, stack);
        //?} else {
        /*?
        out.writeItem(stack);
        ?*/
        //?}
    }

    private static ItemStack readCargoItem(FriendlyByteBuf in) {
        //? if >=1.21.1 {
        return buildcraft.lib.misc.ItemStackUtil.readOptional(in);
        //?} else {
        /*?
        return in.readItem();
        ?*/
        //?}
    }

    /** Never force-load a chunk just to display an inventory. */
    private void refreshCargoSnapshot() {
        TileRobotProcessor processor = getProcessor();
        long robotId = requestedCargoRobotId;
        if (processor == null || robotId <= 0 || !processor.isRobotLinked(robotId)
                || RobotManager.registryProvider == null || processor.getLevel() == null) {
            sendCargo(robotId, false, List.of());
            return;
        }
        IRobotRegistry registry = RobotManager.registryProvider.getRegistry(processor.getLevel());
        EntityRobotBase robot = registry == null ? null : registry.getLoadedRobot(robotId);
        if (robot == null || robot.isRemoved()) {
            sendCargo(robotId, false, List.of());
            return;
        }
        List<CargoEntry> current = new ArrayList<>();
        for (int slot = 0; slot < Math.min(MAX_ROBOT_SLOTS, robot.getContainerSize()); slot++) {
            if (current.size() >= MAX_CARGO_ENTRIES) break;
            ItemStack item = robot.getItem(slot);
            if (!item.isEmpty()) current.add(new CargoEntry(item.copy(), "", 0, 0));
        }
        for (int tank = 0; tank < Math.min(MAX_CARGO_TANKS, robot.getTanks()); tank++) {
            if (current.size() >= MAX_CARGO_ENTRIES) break;
            var fluid = robot.getFluidInTank(tank);
            if (fluid == null || fluid.isEmpty()) continue;
            // Minecraft 1.19.2 has Registry.FLUID; BuiltInRegistries was introduced later.
            //? if <1.19.3 {
            /*?
            var id = Registry.FLUID.getKey(fluid.getFluid());
            ?*/
            //?} else {
            var id = BuiltInRegistries.FLUID.getKey(fluid.getFluid());
            //?}
            if (id == null) continue;
            current.add(new CargoEntry(ItemStack.EMPTY, id.toString(),
                    Math.min(MAX_FLUID_AMOUNT, Math.max(0, fluid.getAmount())),
                    Math.min(MAX_FLUID_AMOUNT, Math.max(0, robot.getTankCapacity(tank)))));
        }
        sendCargo(robotId, true, current);
    }

    private void sendCargo(long robotId, boolean online, List<CargoEntry> contents) {
        sendMessage(ID_CARGO, out -> {
            out.writeLong(robotId);
            out.writeBoolean(online);
            out.writeVarInt(contents.size());
            for (CargoEntry entry : contents) {
                out.writeBoolean(entry.isFluid());
                if (entry.isFluid()) {
                    out.writeUtf(entry.fluidId(), 128);
                    out.writeVarInt(entry.amountMb());
                    out.writeVarInt(entry.capacityMb());
                } else {
                    writeCargoItem(out, entry.stack());
                }
            }
        });
    }

    private void refreshStationSnapshots() {
        TileRobotProcessor processor = getProcessor();
        if (processor == null) return;
        List<StationSnapshot> next = new ArrayList<>();
        IRobotRegistry registry = RobotManager.registryProvider == null || processor.getLevel() == null
                ? null : RobotManager.registryProvider.getRegistry(processor.getLevel());
        for (StationKey key : processor.getLinkedStations()) {
            if (next.size() >= ROBOT_LIMIT) break;
            DockingStation dock = registry == null ? null : registry.getStation(key.pos(), key.side());
            long robotId = dock == null ? EntityRobotBase.NULL_ROBOT_ID : dock.linkedId();
            String status = dock == null ? "Offline" : robotId != EntityRobotBase.NULL_ROBOT_ID
                    ? "Occupied" : "Available";
            next.add(new StationSnapshot(key.pos().getX(), key.pos().getY(), key.pos().getZ(),
                    key.side().get3DDataValue(), status, robotId,
                    processor.getStationName(key.pos(), key.side())));
        }
        // Only send when something has actually changed. Avoid a redundant packet every 20 ticks.
        if (next.equals(stationSnapshots)) return;
        stationSnapshots.clear();
        stationSnapshots.addAll(next);
        sendMessage(ID_STATIONS, buf -> {
            buf.writeVarInt(next.size());
            for (StationSnapshot station : next) {
                buf.writeInt(station.x()); buf.writeInt(station.y()); buf.writeInt(station.z());
                buf.writeByte(station.side());
                buf.writeUtf(station.status(), 24);
                buf.writeLong(station.robotId());
                buf.writeUtf(station.name(), TileRobotProcessor.MAX_LINK_NAME_LENGTH);
            }
        });
    }

    private static String truncate(String value, int max) {
        return value == null ? "" : value.length() <= max ? value : value.substring(0, max);
    }

    @Override
    public void readMessage(int id, FriendlyByteBuf buffer, BCNetworkSide side, BCPacketContext ctx) throws IOException {
        if (id == ID_REQUEST_CARGO && side == BCNetworkSide.SERVER) {
            long requested = buffer.readLong();
            TileRobotProcessor processor = getProcessor();
            if (processor != null && stillValid(playerInventory.player)
                    && (requested == -1L || requested > 0 && processor.isRobotLinked(requested))) {
                requestedCargoRobotId = requested;
                if (requested > 0) refreshCargoSnapshot();
                else sendCargo(-1L, false, List.of());
            }
        } else if (id == ID_CARGO && side == BCNetworkSide.CLIENT) {
            long receivedRobotId = buffer.readLong();
            boolean online = buffer.readBoolean();
            int count = NetworkSecurity.requireRange(buffer.readVarInt(), 0, MAX_CARGO_ENTRIES, "robot cargo entries");
            List<CargoEntry> received = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                if (buffer.readBoolean()) {
                    String fluidId = buffer.readUtf(128);
                    int amount = NetworkSecurity.requireRange(buffer.readVarInt(), 0, MAX_FLUID_AMOUNT, "robot cargo amount");
                    int capacity = NetworkSecurity.requireRange(buffer.readVarInt(), 0, MAX_FLUID_AMOUNT, "robot cargo capacity");
                    received.add(new CargoEntry(ItemStack.EMPTY, fluidId, amount, capacity));
                } else {
                    ItemStack stack = readCargoItem(buffer);
                    if (!stack.isEmpty()) received.add(new CargoEntry(stack, "", 0, 0));
                }
            }
            // Ignore delayed responses for an already-deselected robot.
            if (receivedRobotId == cargoRobotId) {
                cargo.clear();
                cargo.addAll(received);
                cargoOnline = online;
            }
        } else if (id == ID_ROBOTS && side == BCNetworkSide.CLIENT) {
            int count = NetworkSecurity.requireRange(buffer.readVarInt(), 0, ROBOT_LIMIT, "robot snapshots");
            snapshots.clear();
            for (int i = 0; i < count; i++) {
                snapshots.add(new RobotSnapshot(buffer.readLong(), buffer.readUtf(48), buffer.readUtf(64),
                        buffer.readUtf(24), buffer.readInt(), buffer.readInt(), buffer.readInt(),
                        buffer.readByte(), buffer.readByte(),
                        buffer.readUtf(TileRobotProcessor.MAX_LINK_NAME_LENGTH),
                        NetworkSecurity.requireRange(buffer.readUnsignedByte(), 0,
                                TileRobotProcessor.MAX_EXECUTION_QUEUE, "mining execution queue"),
                        buffer.readUtf(96)));
            }
        } else if (id == ID_STATIONS && side == BCNetworkSide.CLIENT) {
            int count = NetworkSecurity.requireRange(buffer.readVarInt(), 0, ROBOT_LIMIT, "station snapshots");
            stationSnapshots.clear();
            for (int i = 0; i < count; i++) {
                stationSnapshots.add(new StationSnapshot(buffer.readInt(), buffer.readInt(), buffer.readInt(),
                        NetworkSecurity.requireRange(buffer.readUnsignedByte(), 0, 5, "station side"),
                        buffer.readUtf(24), buffer.readLong(),
                        buffer.readUtf(TileRobotProcessor.MAX_LINK_NAME_LENGTH)));
            }
        } else if (id == ID_SCAN_PROGRESS && side == BCNetworkSide.CLIENT) {
            scanProgress = new ScanProgress(
                    NetworkSecurity.requireRange(buffer.readUnsignedByte(), 0, 100, "scan percent"),
                    NetworkSecurity.requireRange(buffer.readUnsignedByte(), 0, 100, "scan refresh percent"),
                    NetworkSecurity.requireRange(buffer.readUnsignedByte(), SCAN_WAITING, SCAN_NO_CHUNKS,
                            "scan phase"),
                    NetworkSecurity.requireRange(buffer.readVarInt(), 0, 1_000_000,
                            "scan section count"));
        } else if (id == ID_DIAGNOSTICS && side == BCNetworkSide.CLIENT) {
            diagnostics = new RobotProcessorDiagnostics.Snapshot(
                    NetworkSecurity.requireRange(buffer.readVarInt(), 0, 100_000, "processor inspected blocks"),
                    NetworkSecurity.requireRange(buffer.readVarInt(), 0, 100_000, "processor dirty positions"),
                    NetworkSecurity.requireRange(buffer.readVarInt(), 0, 100_000, "processor focused positions"),
                    NetworkSecurity.requireRange(buffer.readVarInt(), 0, 100_000, "processor background positions"),
                    NetworkSecurity.requireRange(buffer.readVarInt(), 0, 100_000, "processor skipped sections"),
                    NetworkSecurity.requireRange(buffer.readVarInt(), 0, 20, "processor budget-limited ticks"),
                    NetworkSecurity.requireRange(buffer.readVarInt(), 0, 5_000_000, "processor cached candidates"),
                    NetworkSecurity.requireRange(buffer.readVarInt(), 0, 128, "processor active categories"),
                    NetworkSecurity.requireRange(buffer.readVarInt(), 0, 1_000, "processor linked robots"),
                    NetworkSecurity.requireRange(buffer.readVarInt(), 0, 100, "processor loaded chunks"),
                    NetworkSecurity.requireRange(buffer.readVarInt(), 0, 10_000_000, "processor average us"),
                    NetworkSecurity.requireRange(buffer.readVarInt(), 0, 10_000_000, "processor peak us"));
        } else if (id == ID_NAVIGATION_PROFILE && side == BCNetworkSide.CLIENT) {
            navigationProfile = new RobotProcessorNavigationProfiler.Snapshot(
                    NetworkSecurity.requireRange(buffer.readVarInt(), 0, 100_000_000, "navigation cacheProbes"),
                    NetworkSecurity.requireRange(buffer.readVarInt(), 0, 100_000_000, "navigation cacheCandidates"),
                    NetworkSecurity.requireRange(buffer.readVarInt(), 0, 100_000_000, "navigation directHits"),
                    NetworkSecurity.requireRange(buffer.readVarInt(), 0, 100_000_000, "navigation warmStarts"),
                    NetworkSecurity.requireRange(buffer.readVarInt(), 0, 100_000_000, "navigation rejectedRoutes"),
                    NetworkSecurity.requireRange(buffer.readVarInt(), 0, 100_000_000, "navigation storedRoutes"),
                    NetworkSecurity.requireRange(buffer.readVarInt(), 0, 100_000_000, "navigation validatedWaypoints"),
                    NetworkSecurity.requireRange(buffer.readVarInt(), 0, 100_000_000, "navigation aStarPolled"),
                    NetworkSecurity.requireRange(buffer.readVarInt(), 0, 100_000_000, "navigation aStarExpanded"),
                    NetworkSecurity.requireRange(buffer.readVarInt(), 0, 100_000_000, "navigation grantedNodes"),
                    NetworkSecurity.requireRange(buffer.readVarInt(), 0, 100_000_000, "navigation refundedNodes"),
                    NetworkSecurity.requireRange(buffer.readVarInt(), 0, 100_000_000, "navigation sharedWaitRobotTicks"),
                    NetworkSecurity.requireRange(buffer.readVarInt(), 0, 100_000_000, "navigation budgetWaitRobotTicks"),
                    NetworkSecurity.requireRange(buffer.readVarInt(), 0, 100_000_000, "navigation emptyQueueRobotTicks"),
                    NetworkSecurity.requireRange(buffer.readVarInt(), 0, 100_000_000, "navigation readyMinerRobotTicks"),
                    NetworkSecurity.requireRange(buffer.readVarInt(), 0, 100_000_000, "navigation estimatedReferenceNodesBypassed"),
                    NetworkSecurity.requireRange(buffer.readVarInt(), 0, 64, "navigation cachedRoutes")
            );
        } else if (id == ID_QUEUE_STATE && side == BCNetworkSide.CLIENT) {
            maxExecutionQueue = NetworkSecurity.requireRange(buffer.readUnsignedByte(),
                    TileRobotProcessor.MIN_EXECUTION_QUEUE, TileRobotProcessor.MAX_EXECUTION_QUEUE,
                    "execution task queue");
        } else if (id == ID_SET_QUEUE && side == BCNetworkSide.SERVER) {
            int limit = NetworkSecurity.requireRange(buffer.readUnsignedByte(),
                    TileRobotProcessor.MIN_EXECUTION_QUEUE, TileRobotProcessor.MAX_EXECUTION_QUEUE,
                    "execution task queue");
            TileRobotProcessor processor = getProcessor();
            if (processor != null && stillValid(playerInventory.player)) processor.setMaxExecutionQueue(limit);
        } else if (id == ID_RENAME_ROBOT && side == BCNetworkSide.SERVER) {
            long robotId = buffer.readLong();
            String name = buffer.readUtf(TileRobotProcessor.MAX_LINK_NAME_LENGTH);
            TileRobotProcessor processor = getProcessor();
            if (processor != null && stillValid(playerInventory.player) && processor.isRobotLinked(robotId)) {
                processor.renameRobot(robotId, name);
                snapshotTick = 0;
            }
        } else if (id == ID_RENAME_STATION && side == BCNetworkSide.SERVER) {
            BlockPos pos = buffer.readBlockPos();
            int direction = NetworkSecurity.requireRange(buffer.readUnsignedByte(), 0, 5, "station side");
            String name = buffer.readUtf(TileRobotProcessor.MAX_LINK_NAME_LENGTH);
            TileRobotProcessor processor = getProcessor();
            if (processor != null && stillValid(playerInventory.player)) {
                processor.renameStation(pos, Direction.from3DDataValue(direction), name);
                snapshotTick = 0;
            }
        } else if (side == BCNetworkSide.SERVER && id == ID_UNLINK_ROBOT) {
            long robotId = buffer.readLong();
            TileRobotProcessor processor = getProcessor();
            if (processor != null && stillValid(playerInventory.player) && processor.isRobotLinked(robotId)) {
                processor.unlinkRobot(robotId);
                snapshotTick = 0;
            }
        } else if (side == BCNetworkSide.SERVER && id == ID_UNLINK_STATION) {
            BlockPos pos = buffer.readBlockPos();
            int direction = NetworkSecurity.requireRange(buffer.readUnsignedByte(), 0, 5, "station side");
            TileRobotProcessor processor = getProcessor();
            if (processor != null && stillValid(playerInventory.player)) {
                processor.unlinkStation(pos, Direction.from3DDataValue(direction));
                snapshotTick = 0;
            }
        } else if (side == BCNetworkSide.SERVER && id == ID_FORCE_CHUNK) {
            long robotId = buffer.readLong();
            TileRobotProcessor processor = getProcessor();
            if (processor == null || !stillValid(playerInventory.player) || !processor.isRobotLinked(robotId)) return;
            if (RobotManager.registryProvider != null && processor.getLevel() != null) {
                var registry = RobotManager.registryProvider.getRegistry(processor.getLevel());
                EntityRobotBase robot = registry == null ? null : registry.getLoadedRobot(robotId);
                if (robot != null && !robot.isRemoved()) {
                    processor.recordRobotPosition(robotId, robot.blockPosition());
                }
            }
            if (!processor.forceRobotChunk(robotId) && ctx.getSender() != null) {
                //? if mc_26_x {
                ctx.getSender().sendOverlayMessage(net.minecraft.network.chat.Component.literal(
                        "Cannot force-load robot chunk (unknown position, limit, or server configuration)"));
                //?} else {
                ctx.getSender().displayClientMessage(net.minecraft.network.chat.Component.literal(
                        "Cannot force-load robot chunk (unknown position, limit, or server configuration)"), true);
                //?}
            }
        } else if (id == ID_RECALL && side == BCNetworkSide.SERVER) {
            long robotId = buffer.readLong();
            TileRobotProcessor processor = getProcessor();
            if (processor == null || !processor.isRobotLinked(robotId)
                    || !stillValid(playerInventory.player) || RobotManager.registryProvider == null) return;
            var registry = RobotManager.registryProvider.getRegistry(tile.getLevel());
            if (registry == null) return;
            EntityRobotBase robot = registry.getLoadedRobot(robotId);
            if (robot == null || robot.isRemoved()) return;
            DockingStation station = robot.getLinkedStation();
            if (station != null) robot.setMainAIOverride(new AIRobotGoAndLinkToDock(robot, station));
        } else {
            super.readMessage(id, buffer, side, ctx);
        }
    }
}
