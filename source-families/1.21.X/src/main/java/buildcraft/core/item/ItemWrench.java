package buildcraft.core.item;

import buildcraft.lib.internal.api.v2.BlockInteractionRuntime;
import buildcraft.lib.internal.enums.EnumPowerStage;
import buildcraft.lib.internal.tool.IToolWrench;
import buildcraft.lib.engine.TileEngineBase_BC8;
import buildcraft.lib.misc.AdvancementUtil;
import buildcraft.lib.misc.SoundUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public class ItemWrench extends Item implements IToolWrench {
    private static final ResourceLocation ADVANCEMENT_TOO_MUCH_POWER =
        ResourceLocation.parse("buildcraftenergy:to_much_power");

    public ItemWrench() {
        super(new Item.Properties().stacksTo(1));
    }

    @Override
    public boolean doesSneakBypassUse(ItemStack stack, net.minecraft.world.level.LevelReader world,
            BlockPos pos, Player player) {
        //? if mc_26_x {
        // NeoForge 26.x only runs the block interaction phase when this returns false.
        // Keep that phase enabled so shift-right-click reaches pipe pluggables, then lets
        // Item#useOn handle the wrench when the block returns PASS (for example engines).
        return false;
        //?} else {
        // Older targets use the historical Forge/NeoForge sneak-bypass contract.
        return true;
        //?}
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        Direction side = context.getClickedFace();
        Player player = context.getPlayer();
        InteractionHand hand = context.getHand();
        Vec3 click = context.getClickLocation();
        BlockState state = level.getBlockState(pos);

        if (!level.isClientSide && player != null && level.getBlockEntity(pos) instanceof TileEngineBase_BC8 engine
            && engine.getPowerStage() == EnumPowerStage.OVERHEAT) {
            AdvancementUtil.unlockAdvancement(player, ADVANCEMENT_TOO_MUCH_POWER);
        }

        //? if mc_26_x {
        // Engine receiver discovery is capability-backed on 26.x. Do not run that lookup on the
        // prediction client: a client-only FAIL can swallow the shift-wrench interaction before
        // the authoritative server gets a chance to rotate the engine.
        if (level.isClientSide() && level.getBlockEntity(pos) instanceof TileEngineBase_BC8) {
            return InteractionResult.SUCCESS;
        }
        //?}

        InteractionResult result = BlockInteractionRuntime.rotate(level, pos, state, side, player);
        if (result == InteractionResult.SUCCESS && player != null) {
            wrenchUsed(player, hand, player.getItemInHand(hand), BlockHitResult.miss(click, side, pos));
        }
        SoundUtil.playSlideSound(level, pos, state, result);
        return result;
    }

    @Override
    public boolean canWrench(Player player, InteractionHand hand, ItemStack wrench, HitResult rayTrace) {
        return true;
    }

    @Override
    public void wrenchUsed(Player player, InteractionHand hand, ItemStack wrench, HitResult rayTrace) {
        player.swingingArm = hand;
    }
}
