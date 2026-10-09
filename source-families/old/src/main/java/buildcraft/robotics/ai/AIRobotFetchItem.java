package buildcraft.robotics.ai;

import buildcraft.lib.internal.area.IBox;
import buildcraft.lib.internal.core.IStackFilter;
import buildcraft.lib.internal.area.IZone;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.DockingStation;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.lib.misc.data.Box;
import buildcraft.robotics.boards.BoardRobotPicker;
import buildcraft.robotics.entity.EntityRobot;
import buildcraft.robotics.statements.ActionRobotFilter;
import buildcraft.robotics.zone.ZonePlan;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class AIRobotFetchItem extends AIRobot {
    private static final String NBT_ZONE = "zone";
    private static final String NBT_ZONE_TYPE = "type";
    private static final String ZONE_TYPE_BOX = "box";
    private static final String ZONE_TYPE_PLAN = "plan";

    private ItemEntity target;
    private int targetId = -1;
    private BoardRobotPicker.TargetKey targetReservation;
    private float maxRange = 250;
    private IStackFilter stackFilter;
    private int pickTime = -1;
    private IZone zone;
    private boolean zoneRestricted;
    private boolean refreshContextAfterLoad;

    public AIRobotFetchItem(EntityRobotBase robot) {
        super(robot);
    }

    public AIRobotFetchItem(EntityRobotBase robot, float maxRange, IZone zone) {
        this(robot, maxRange, null, zone);
    }

    public AIRobotFetchItem(EntityRobotBase robot, float maxRange, IStackFilter stackFilter, IZone zone) {
        this(robot);
        this.maxRange = maxRange;
        this.stackFilter = stackFilter;
        this.zone = zone;
        this.zoneRestricted = zone != null;
    }

    @Override
    public void preempt(AIRobot ai) {
        if (target != null && (!isValidTarget(target) || !isInsideZone(target.position()))) {
            failAndTerminate(false);
        }
    }

    @Override
    public void update() {
        if (refreshContextAfterLoad && !restoreRuntimeContext()) {
            return;
        }

        if (target == null && targetId != -1) {
            // Entity IDs are runtime-only and must never be trusted after a world/chunk reload. This branch only
            // handles an ID selected during the current runtime.
            AABB lookup = robot.getBoundingBox().inflate(maxRange);
            //? if <1.20 {
            for (ItemEntity item : robot.level.getEntitiesOfClass(ItemEntity.class, lookup)) {
            //?} else {
            /*?
            for (ItemEntity item : robot.level().getEntitiesOfClass(ItemEntity.class, lookup)) {
            ?*/
            //?}
                if (item.getId() == targetId && isValidTarget(item) && isInsideZone(item.position())) {
                    target = item;
                    break;
                }
            }
            if (target == null) {
                releaseTargetReservation();
            }
        }

        if (target == null) {
            scanForItem();
            return;
        }

        if (!isValidTarget(target) || !isInsideZone(target.position())) {
            failAndTerminate(false);
            return;
        }

        if (!canPickTargetNow()) {
            BlockPos pickupPos = findPickupTargetPos(target);
            if (pickupPos == null) {
                robot.unreachableEntityDetected(target);
                failAndTerminate(false);
                return;
            }
            startDelegateAI(new AIRobotGotoBlock(robot, pickupPos.getX(), pickupPos.getY(), pickupPos.getZ(), maxRange));
            return;
        }

        pickTime++;
        if (pickTime > 5) {
            ItemStack stack = target.getItem();
            ItemStack remaining = insert(stack, true);
            target.setItem(remaining);
            if (remaining.isEmpty()) {
                target.discard();
            }
            terminate();
        }
    }

    @Override
    public void delegateAIEnded(AIRobot ai) {
        if (ai instanceof AIRobotGotoBlock) {
            if (target == null || !isValidTarget(target) || !isInsideZone(target.position())) {
                failAndTerminate(false);
            } else if (!ai.success()) {
                robot.unreachableEntityDetected(target);
                failAndTerminate(false);
            } else {
                pickTime = 0;
            }
        }
    }

    @Override
    public void end() {
        releaseTargetReservation();
    }

    private boolean restoreRuntimeContext() {
        refreshContextAfterLoad = false;

        DockingStation station = robot.getLinkedStation();
        if (station == null) {
            // Never turn a collector into an unrestricted 250-block item vacuum just because its station is not
            // available during the first tick after loading.
            setSuccess(false);
            terminate();
            return false;
        }

        IZone liveZone = robot.getZoneToWork();
        if (liveZone != null) {
            zone = liveZone;
            zoneRestricted = true;
        }
        stackFilter = ActionRobotFilter.getGateFilter(station);

        if (zoneRestricted && zone == null) {
            setSuccess(false);
            terminate();
            return false;
        }
        return true;
    }

    private void scanForItem() {
        double bestDistance = Double.MAX_VALUE;
        ItemEntity best = null;
        AABB box = robot.getBoundingBox().inflate(maxRange);
        //? if <1.20 {
        for (ItemEntity item : robot.level.getEntitiesOfClass(ItemEntity.class, box)) {
        //?} else {
        /*?
        for (ItemEntity item : robot.level().getEntitiesOfClass(ItemEntity.class, box)) {
        ?*/
        //?}
            if (!isValidTarget(item)) continue;
            BoardRobotPicker.TargetKey reservation = BoardRobotPicker.TargetKey.of(item);
            if (BoardRobotPicker.targettedItems.contains(reservation)) continue;
            if (robot.isKnownUnreachable(item)) continue;
            if (stackFilter != null && !stackFilter.matches(item.getItem())) continue;
            if (!isInsideZone(item.position())) continue;
            double sqrDistance = item.distanceToSqr(robot);
            if (sqrDistance >= maxRange * maxRange) continue;
            if (insert(item.getItem(), false).getCount() >= item.getItem().getCount()) continue;
            if (best == null || sqrDistance < bestDistance) {
                best = item;
                bestDistance = sqrDistance;
            }
        }
        if (best != null) {
            target = best;
            targetId = best.getId();
            targetReservation = BoardRobotPicker.TargetKey.of(best);
            BoardRobotPicker.targettedItems.add(targetReservation);
            if (!canPickTargetNow()) {
                BlockPos pickupPos = findPickupTargetPos(target);
                if (pickupPos == null) {
                    robot.unreachableEntityDetected(target);
                    failAndTerminate(false);
                    return;
                }
                startDelegateAI(new AIRobotGotoBlock(robot, pickupPos.getX(), pickupPos.getY(), pickupPos.getZ(), maxRange));
            }
        } else {
            setSuccess(false);
            terminate();
        }
    }

    private boolean isValidTarget(ItemEntity item) {
        return item != null && item.isAlive() && !item.getItem().isEmpty();
    }

    private boolean isInsideZone(Vec3 position) {
        return zone == null || zone.contains(position);
    }

    private boolean canPickTargetNow() {
        if (target == null || !isInsideZone(target.position())) {
            return false;
        }
        if (Math.floor(target.getX()) == Math.floor(robot.getX())
                && Math.floor(target.getY()) == Math.floor(robot.getY())
                && Math.floor(target.getZ()) == Math.floor(robot.getZ())) {
            return true;
        }
        return target.distanceToSqr(robot) <= 2.25D;
    }

    private BlockPos findPickupTargetPos(ItemEntity item) {
        if (!isInsideZone(item.position())) {
            return null;
        }

        BlockPos itemPos = new BlockPos((int) Math.floor(item.getX()), (int) Math.floor(item.getY()), (int) Math.floor(item.getZ()));
        BlockPos[] candidates = new BlockPos[] {
                itemPos,
                itemPos.above(),
                itemPos.north(),
                itemPos.south(),
                itemPos.west(),
                itemPos.east(),
                itemPos.above().north(),
                itemPos.above().south(),
                itemPos.above().west(),
                itemPos.above().east()
        };

        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos candidate : candidates) {
            //? if <1.20 {
            if (!isInsideZone(Vec3.atCenterOf(candidate)) || !isSoft(robot.level, candidate)) {
            //?} else {
            /*?
            if (!isInsideZone(Vec3.atCenterOf(candidate)) || !isSoft(robot.level(), candidate)) {
            ?*/
            //?}
                continue;
            }
            double distance = candidate.distToCenterSqr(item.getX(), item.getY(), item.getZ());
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static boolean isSoft(Level level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        return state.isAir() || state.getCollisionShape(level, pos).isEmpty();
    }

    private ItemStack insert(ItemStack stack, boolean doInsert) {
        if (robot instanceof EntityRobot entityRobot) {
            return entityRobot.insertIntoInventory(stack, doInsert);
        }
        return stack;
    }

    private void failAndTerminate(boolean markUnreachable) {
        if (markUnreachable && target != null) {
            robot.unreachableEntityDetected(target);
        }
        setSuccess(false);
        terminate();
    }

    private void releaseTargetReservation() {
        if (targetReservation != null) {
            BoardRobotPicker.targettedItems.remove(targetReservation);
        }
        targetReservation = null;
        targetId = -1;
        target = null;
    }

    @Override
    public int getEnergyCost() {
        return 15;
    }

    @Override
    public boolean canLoadFromNBT() {
        return true;
    }

    @Override
    public void writeSelfToNBT(CompoundTag nbt) {
        nbt.putFloat("maxRange", maxRange);
        nbt.putBoolean("zoneRestricted", zoneRestricted);

        CompoundTag zoneTag = writeZone(zone);
        if (zoneTag != null) {
            nbt.put(NBT_ZONE, zoneTag);
        }

        // targetId is deliberately not persisted: Minecraft entity IDs are only stable for the current runtime.
    }

    @Override
    public void loadSelfFromNBT(CompoundTag nbt) {
        maxRange = nbt.contains("maxRange") ? nbt.getFloat("maxRange") : 250;
        zone = readZone(nbt.getCompound(NBT_ZONE));
        zoneRestricted = nbt.getBoolean("zoneRestricted") || zone != null;

        // Always reacquire a target after loading. A saved numeric entity ID may now refer to an unrelated entity.
        target = null;
        targetId = -1;
        targetReservation = null;
        pickTime = -1;
        stackFilter = null;
        refreshContextAfterLoad = true;
    }

    private static CompoundTag writeZone(IZone zone) {
        if (zone instanceof ZonePlan plan) {
            CompoundTag tag = new CompoundTag();
            tag.putString(NBT_ZONE_TYPE, ZONE_TYPE_PLAN);
            plan.writeToNBT(tag);
            return tag;
        }
        if (zone instanceof IBox box && box.min() != null && box.max() != null) {
            CompoundTag tag = new CompoundTag();
            tag.putString(NBT_ZONE_TYPE, ZONE_TYPE_BOX);
            tag.putInt("minX", box.min().getX());
            tag.putInt("minY", box.min().getY());
            tag.putInt("minZ", box.min().getZ());
            tag.putInt("maxX", box.max().getX());
            tag.putInt("maxY", box.max().getY());
            tag.putInt("maxZ", box.max().getZ());
            return tag;
        }
        return null;
    }

    private static IZone readZone(CompoundTag tag) {
        if (tag == null || tag.isEmpty()) {
            return null;
        }
        String type = tag.getString(NBT_ZONE_TYPE);
        if (ZONE_TYPE_PLAN.equals(type)) {
            ZonePlan plan = new ZonePlan();
            plan.readFromNBT(tag);
            return plan;
        }
        if (ZONE_TYPE_BOX.equals(type)) {
            BlockPos min = new BlockPos(tag.getInt("minX"), tag.getInt("minY"), tag.getInt("minZ"));
            BlockPos max = new BlockPos(tag.getInt("maxX"), tag.getInt("maxY"), tag.getInt("maxZ"));
            return new Box(min, max);
        }
        return null;
    }
}
