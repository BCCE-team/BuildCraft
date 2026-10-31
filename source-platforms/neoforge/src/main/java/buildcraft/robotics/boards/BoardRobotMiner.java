package buildcraft.robotics.boards;

import buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobotNBT;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.BCRoboticsBoards;
import buildcraft.robotics.ai.AIRobotBreak;
import buildcraft.robotics.ai.AIRobotSearchAndGotoBlock;
import buildcraft.lib.internal.area.IZone;
import buildcraft.lib.internal.core.BlockIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
//? if mc_26_x && (>=26.2) {
//? } else {
import net.minecraft.world.item.PickaxeItem;
//?}
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.Tags;
//? if mc_26_x && (>=26.2) {
import buildcraft.lib.compat.ItemCompat;
//? } else {
import net.neoforged.neoforge.common.ItemAbilities;
//?}

/**
 * Mines an entire reachable ore vein before starting another one. The normal target
 * search is still used for reachability and work zone restrictions, but is constrained
 * to the current vein until it is exhausted or proven unreachable.
 */
public class BoardRobotMiner extends BoardRobotGenericBreakBlock {
    private final RobotOreVeinMemory vein = new RobotOreVeinMemory();

    public BoardRobotMiner(EntityRobotBase robot) {
        super(robot);
    }

//? if mc_26_x && (>=26.2) {
//? } else {
    @Override
//?}
    public RedstoneBoardRobotNBT getNBTHandler() {
        return BCRoboticsBoards.getByKey("miner").nbt();
    }

//? if mc_26_x && (>=26.2) {
//? } else {
    @Override
//?}
    public boolean isExpectedTool(ItemStack stack) {
        return !stack.isEmpty()
//? if mc_26_x && (>=26.2) {
                && (ItemCompat.isPickaxe(stack));
//? } else {
                && (stack.getItem() instanceof PickaxeItem || stack.canPerformAction(ItemAbilities.PICKAXE_DIG));
//?}
    }

    @Override
    public void update() {
        vein.prune(robot.level(), this::eligibleOreFamily);
        super.update();
    }

    /** A new deposit is chosen by shortest reachable travel, not chunk scan order. */
    protected boolean prioritizeShortestPath() {
        return true;
    }

//? if mc_26_x && (>=26.2) {
//? } else {
    @Override
//?}
    public boolean isExpectedBlock(Level level, BlockPos pos) {
        if (!isMineableOre(level, pos)) return false;
        return !vein.isActive() || vein.contains(pos);
    }

    private boolean isMineableOre(Level level, BlockPos pos) {
        if (!level.isLoaded(pos)) return false;
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.getDestroySpeed(level, pos) < 0.0F || !isOre(state)) return false;
        ItemStack held = robot.getItemBySlot(EquipmentSlot.MAINHAND);
        return isExpectedTool(held) && held.isCorrectToolForDrops(state);
    }

    private String eligibleOreFamily(Level level, BlockPos pos) {
        // Tool availability is intentionally NOT checked here: the remembered vein must
        // survive re-equipping a pickaxe and world reloads while the tool is still loading.
        if (!level.isLoaded(pos)) return null;
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.getDestroySpeed(level, pos) < 0.0F || !isOre(state)
                || !matchesGateFilter(level, pos)) return null;
        IZone zone = robot.getZoneToWork();
        if (zone != null && !zone.contains(new Vec3(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D))) {
            return null;
        }
        return oreFamily(state);
    }

    private static String oreFamily(BlockState state) {
        if (state.is(BlockTags.COPPER_ORES)) return "minecraft:copper";
        if (state.is(BlockTags.IRON_ORES)) return "minecraft:iron";
        if (state.is(BlockTags.GOLD_ORES)) return "minecraft:gold";
//? if mc_26_x && (>=26.2) {
//? } else {
        if (state.is(BlockTags.COAL_ORES)) return "minecraft:coal";
        if (state.is(BlockTags.REDSTONE_ORES)) return "minecraft:redstone";
        if (state.is(BlockTags.EMERALD_ORES)) return "minecraft:emerald";
        if (state.is(BlockTags.LAPIS_ORES)) return "minecraft:lapis";
        if (state.is(BlockTags.DIAMOND_ORES)) return "minecraft:diamond";
//?}
        // Unknown addon ores remain separated by their own stable block id.
        return "block:" + state.getBlock().getDescriptionId();
    }

    @Override
    public void delegateAIEnded(AIRobot ai) {
        if (ai instanceof AIRobotSearchAndGotoBlock search && !search.success() && vein.isActive()) {
            vein.clear();
            return;
        }
        BlockIndex completed = ai instanceof AIRobotBreak ? blockFound() : null;
        super.delegateAIEnded(ai);
        if (ai instanceof AIRobotSearchAndGotoBlock search && search.success() && !vein.isActive()) {
            BlockIndex found = blockFound();
            if (found != null) vein.remember(robot.level(), found.toBlockPos(), this::eligibleOreFamily);
        } else if (ai instanceof AIRobotBreak && completed != null && ai.success()) {
            vein.mined(robot.level(), completed.toBlockPos(), this::eligibleOreFamily);
        }
    }

    @Override
    public void writeSelfToNBT(CompoundTag nbt) {
        super.writeSelfToNBT(nbt);
        vein.writeTo(nbt);
    }

    @Override
    public void loadSelfFromNBT(CompoundTag nbt) {
        super.loadSelfFromNBT(nbt);
        vein.readFrom(nbt);
    }

    private static boolean isOre(BlockState state) {
        // Forge ore tags preserve OreDictionary-style "ore*" matching and keep modded ores compatible.
//? if mc_26_x && (>=26.2) {
        return state.is(Tags.Blocks.ORES) || state.is(BlockTags.IRON_ORES)
            || state.is(BlockTags.COPPER_ORES) || state.is(BlockTags.GOLD_ORES);
//? } else {
        return state.is(Tags.Blocks.ORES) || state.is(BlockTags.COAL_ORES) || state.is(BlockTags.IRON_ORES)
                || state.is(BlockTags.COPPER_ORES) || state.is(BlockTags.GOLD_ORES) || state.is(BlockTags.REDSTONE_ORES)
                || state.is(BlockTags.EMERALD_ORES) || state.is(BlockTags.LAPIS_ORES) || state.is(BlockTags.DIAMOND_ORES);
//?}
    }
}
