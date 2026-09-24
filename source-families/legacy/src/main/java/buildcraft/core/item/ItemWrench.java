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
        new ResourceLocation("buildcraftenergy:to_much_power");

    public ItemWrench() {
        super(new Item.Properties().stacksTo(1));
    }

    @Override
    public boolean doesSneakBypassUse(ItemStack stack, net.minecraft.world.level.LevelReader world,
            BlockPos pos, Player player) {
        // Let the targeted block handle shift-right-click first. This is required for
        // pipe pluggables such as robot stations to receive the interaction.
        return true;
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
