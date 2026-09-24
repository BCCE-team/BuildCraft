package buildcraft.core.item;

import buildcraft.lib.internal.api.v2.BlockInteractionRuntime;
import buildcraft.lib.internal.tool.IToolWrench;
import buildcraft.core.BCCore;
import buildcraft.lib.misc.AdvancementUtil;
import buildcraft.lib.engine.TileEngineBase_BC8;
import buildcraft.lib.internal.enums.EnumPowerStage;
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

public class ItemWrench extends Item implements IToolWrench{
    private static final ResourceLocation ADVANCEMENT_TOO_MUCH_POWER =
        new ResourceLocation("buildcraftenergy:to_much_power");

	public ItemWrench() {
		super(new Item.Properties().stacksTo(1).tab(BCCore.BUILDCRAFT_TAB));
	}

	@Override
	public boolean doesSneakBypassUse(ItemStack stack, net.minecraft.world.level.LevelReader world,
			BlockPos pos, Player player) {
		// Allow the targeted block to handle shift-right-click before the wrench item.
		// Otherwise Forge skips BlockPipeHolder.use() while sneaking, so pluggables such
		// as robot stations never receive the interaction.
		return true;
	}

	@Override
	public InteractionResult useOn(UseOnContext coc) {
		Level world = coc.getLevel();
		BlockPos pos = coc.getClickedPos();
		Direction side = coc.getClickedFace();
		Player player = coc.getPlayer();
		InteractionHand hand = coc.getHand();
		Vec3 c = coc.getClickLocation();
        BlockState state = world.getBlockState(pos);
        var f = world.getBlockEntity(pos);
        if (!world.isClientSide && f instanceof TileEngineBase_BC8 engine
            && engine.getPowerStage() == EnumPowerStage.OVERHEAT) {
            AdvancementUtil.unlockAdvancement(player, ADVANCEMENT_TOO_MUCH_POWER);
        }
        InteractionResult result = BlockInteractionRuntime.rotate(world, pos, state, side, player);

        if (result == InteractionResult.SUCCESS) {
            wrenchUsed(player, hand, player.getItemInHand(hand), BlockHitResult.miss(c, side, pos));
        }
        SoundUtil.playSlideSound(world, pos, state, result);

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
