package buildcraft.robotics.scheduler;

import java.util.Map;
import java.util.WeakHashMap;

import buildcraft.lib.internal.area.IZone;
import buildcraft.lib.inventory.filter.ArrayStackFilter;
import buildcraft.robotics.RobotTrace;
import buildcraft.robotics.ai.AIRobotLoad;
import buildcraft.robotics.internal.legacy.robots.DockingStation;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.statements.ActionStationForbidRobot;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Shared across all robot processors in ONE dimension: assign available
 * materials at a particular source before a Builder starts travelling there.
 *
 * <p>These are conservative, expiring inventory promises, NOT authoritative
 * reservations. External hoppers, pipes or players may still change stock.
 * AIRobotLoad must revalidate the lease, permissions and actual inventory at
 * commit time. A source without available stock must not be sent to every
 * Builder independently. Autonomous robots are unaffected.</p>
 */
public final class RobotBuilderMaterialScheduler {
    private static final long LEASE_TICKS = 2400;
    private static final int MAX_STATIONS_CHECKED = 256;
    private static final Map<Level, RobotBuilderMaterialLeaseBook<Long, Material>> WORLDS = new WeakHashMap<>();

    /** A 1-count fingerprint. Minecraft ItemStack does not implement value equality. */
    private static final class Material {
        private final ItemStack stack;
        Material(ItemStack stack) { this.stack = stack.copy(); this.stack.setCount(1); }
        @Override public int hashCode() { return stack.getItem().hashCode(); }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Material material)) return false;
            //? if <1.21 {
            return ItemStack.isSameItemSameTags(stack, material.stack);
            //?} else {
            /*?
            return ItemStack.isSameItemSameComponents(stack, material.stack);
            ?*/
            //?}
        }
    }

    public record Allocation(DockingStation station, int quantity, long token) { }

    private RobotBuilderMaterialScheduler() { }

    private static Level level(EntityRobotBase robot) {
        //? if <1.20 {
        return robot.level;
        //?} else {
        /*?
        return robot.level();
        ?*/
        //?}
    }

    private static RobotBuilderMaterialLeaseBook<Long, Material> book(Level world) {
        return WORLDS.computeIfAbsent(world, ignored -> new RobotBuilderMaterialLeaseBook<>());
    }

    /**
     * Multiple stations on the same pipe or on the same block-entity inventory
     * draw from the same count. Never retain a Container or TileEntity in the
     * world ledger. Adapter-backed inventories may lack a block entity; these
     * conservatively use the station's block as their source identity.
     */
    private static long sourceId(DockingStation station, Container inventory) {
        if (inventory instanceof BlockEntity blockEntity) return blockEntity.getBlockPos().asLong();
        return station.index().toBlockPos().asLong();
    }

    public static Allocation allocate(EntityRobotBase robot, ItemStack requirement) {
        if (robot == null || requirement == null || requirement.isEmpty() || robot.getRegistry() == null) return null;
        Long panelId = RobotProcessorWorkScheduler.builderControllerPanelId(robot);
        if (panelId == null) return null;
        Level world = level(robot);
        if (world == null) return null;
        long now = world.getGameTime();
        RobotBuilderMaterialLeaseBook<Long, Material> ledger = book(world);
        ledger.expire(now);
        Material material = new Material(requirement);
        ArrayStackFilter filter = new ArrayStackFilter(requirement);
        IZone zone = robot.getZoneToLoadUnload();
        DockingStation best = null;
        long bestSource = 0L;
        int bestStock = 0;
        int bestQuantity = 0;
        double bestDistance = Double.POSITIVE_INFINITY;
        long physicalTotal = 0;
        long reservedTotal = 0;
        int checked = 0;
        for (DockingStation station : robot.getRegistry().getStations()) {
            if (station == null || station.level() != world || !station.isInitialized()
                    || !world.hasChunkAt(station.index().toBlockPos())
                    || (station.isTaken() && station.robotIdTaking() != robot.getRobotId())
                    || ActionStationForbidRobot.isForbidden(station, robot)
                    || zone != null && !zone.contains(new Vec3(station.x(), station.y(), station.z()))) continue;
            if (++checked > MAX_STATIONS_CHECKED) break;
            Container inventory = station.getItemInput();
            if (inventory == null) continue;
            int stock = AIRobotLoad.previewExtractableCount(robot, station, filter);
            if (stock <= 0) continue;
            long source = sourceId(station, inventory);
            int already = ledger.reserved(source, material, now);
            physicalTotal += stock;
            reservedTotal += Math.min(already, stock);
            int quantity = (int) Math.min((long) requirement.getCount(),
                    Math.max(0L, (long) stock - already));
            if (quantity <= 0 || !AIRobotLoad.load(robot, station, filter, quantity, false)) continue;
            double dx = robot.getX() - station.x();
            double dy = robot.getY() - station.y();
            double dz = robot.getZ() - station.z();
            double distance = dx * dx + dy * dy + dz * dz;
            if (best == null || distance < bestDistance || (distance == bestDistance && quantity > bestQuantity)) {
                best = station;
                bestSource = source;
                bestStock = stock;
                bestQuantity = quantity;
                bestDistance = distance;
            }
        }
        if (best == null) {
            if (RobotTrace.enabled()) RobotTrace.event(robot, "PROCESSOR_BUILDER_MATERIAL_SHORTAGE",
                    "item=%s requested=%d stockSeen=%d alreadyClaimed=%d stationsChecked=%d",
                    requirement.getItem(), requirement.getCount(), physicalTotal, reservedTotal, checked);
            return null;
        }
        var lease = ledger.reserve(bestSource, material, panelId, robot.getRobotId(),
                bestStock, bestQuantity, now, LEASE_TICKS);
        if (lease == null) return null;
        if (RobotTrace.enabled()) RobotTrace.event(robot, "PROCESSOR_BUILDER_MATERIAL_CLAIM",
                "item=%s source=%s requested=%d promised=%d stock=%d reserved=%d token=%d",
                requirement.getItem(), best.index().toBlockPos(), requirement.getCount(),
                lease.quantity(), bestStock, bestStock - bestQuantity, lease.token());
        return new Allocation(best, lease.quantity(), lease.token());
    }

    public static boolean owns(EntityRobotBase robot, DockingStation station, long token) {
        if (robot == null || station == null || token <= 0) return false;
        Level world = level(robot);
        if (world == null || station.level() != world ||
                RobotProcessorWorkScheduler.builderControllerPanelId(robot) == null) return false;
        var ledger = WORLDS.get(world);
        Container inventory = station.getItemInput();
        return ledger != null && inventory != null && ledger.ownsAt(
                robot.getRobotId(), token, sourceId(station, inventory), world.getGameTime());
    }

    public static void release(EntityRobotBase robot, long token) {
        if (robot == null || token <= 0) return;
        Level world = level(robot);
        if (world == null) return;
        var ledger = WORLDS.get(world);
        if (ledger != null) ledger.release(robot.getRobotId(), token);
    }

    public static void releaseRobot(EntityRobotBase robot) {
        if (robot == null) return;
        Level world = level(robot);
        var ledger = world == null ? null : WORLDS.get(world);
        if (ledger != null) ledger.releaseRobot(robot.getRobotId());
    }

    public static void releasePanel(Level world, long panelId) {
        var ledger = world == null ? null : WORLDS.get(world);
        if (ledger != null) ledger.releasePanel(panelId);
    }
}
