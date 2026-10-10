//? source if >=26.3
package buildcraft.lib.compat.neoforge263.fluids;

import buildcraft.lib.compat.transfer.TransferInterop;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.access.ItemAccess;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.ItemStackResourceHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/** 26.3 bridge from old BuildCraft fluid-item calls to NeoForge transactional ItemAccess. */
public final class FluidUtil {
    private FluidUtil() {}

    public static Optional<IFluidHandlerItem> getFluidHandler(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return Optional.empty();
        final ItemStack[] value = {stack.copyWithCount(1)};
        ItemStackResourceHandler cell = new ItemStackResourceHandler() {
            @Override protected ItemStack getStack() { return value[0]; }
            @Override protected void setStack(ItemStack next) { value[0] = next; }
        };
        ResourceHandler<FluidResource> nativeHandler = ItemAccess.forHandlerIndexStrict(cell, 0)
            .getCapability(Capabilities.Fluid.ITEM);
        if (nativeHandler == null) return Optional.empty();
        IFluidHandler delegate = TransferInterop.importFluids(nativeHandler);
        return Optional.of(new IFluidHandlerItem() {
            @Override public ItemStack getContainer() { return value[0].copy(); }
            @Override public int getTanks() { return delegate.getTanks(); }
            @Override public FluidStack getFluidInTank(int tank) { return delegate.getFluidInTank(tank); }
            @Override public int getTankCapacity(int tank) { return delegate.getTankCapacity(tank); }
            @Override public boolean isFluidValid(int tank, FluidStack stack) { return delegate.isFluidValid(tank,stack); }
            @Override public int fill(FluidStack stack, FluidAction action) { return delegate.fill(stack,action); }
            @Override public FluidStack drain(FluidStack stack, FluidAction action) { return delegate.drain(stack,action); }
            @Override public FluidStack drain(int amount, FluidAction action) { return delegate.drain(amount,action); }
        });
    }

    public static Optional<FluidStack> getFluidContained(ItemStack stack) {
        return getFluidHandler(stack).map(h -> {
            for(int i=0;i<h.getTanks();i++) {
                FluidStack contents=h.getFluidInTank(i);
                if(!contents.isEmpty())return contents;
            }
            return FluidStack.EMPTY;
        }).filter(fluid->!fluid.isEmpty());
    }

    public static ItemStack getFilledBucket(FluidStack fluid) {
        if (fluid == null || fluid.isEmpty() || fluid.getAmount() < net.neoforged.neoforge.fluids.FluidType.BUCKET_VOLUME) {
            return ItemStack.EMPTY;
        }
        return fluid.getFluidType().getBucket(fluid);
    }

    public static FluidStack getFirstStackContained(ItemStack stack) {
        return getFluidContained(stack).orElse(FluidStack.EMPTY);
    }

    /** Only commit the hand and tank transfer if the same fluid amount moved both ways. */
    public static boolean interactWithFluidHandler(Player player, InteractionHand hand, IFluidHandler tank) {
        if (player == null || tank == null || player.getItemInHand(hand).isEmpty()) return false;
        if (player.level().isClientSide()) return true;
        ItemAccess access=ItemAccess.forPlayerInteraction(player,hand);
        ResourceHandler<FluidResource> item=access.getCapability(Capabilities.Fluid.ITEM);
        if(item==null) return false;
        try(Transaction transaction=Transaction.openRoot()) {
            for(int i=0;i<item.size();i++) {
                FluidResource fluid=item.getResource(i);
                int amt=item.getAmountAsInt(i);
                if(fluid.isEmpty() || amt<=0)continue;
                FluidStack offered=fluid.toStack(amt);
                int accepted=tank.fill(offered, IFluidHandler.FluidAction.SIMULATE);
                if(accepted<=0)continue;
                int extracted=item.extract(i,fluid,accepted,transaction);
                if(extracted<=0)continue;
                int filled=tank.fill(fluid.toStack(extracted),IFluidHandler.FluidAction.EXECUTE);
                if(filled!=extracted)throw new IllegalStateException("Fluid transfer amount mismatch: "+filled+" != "+extracted);
                transaction.commit();
                return true;
            }
            for(int i=0;i<tank.getTanks();i++) {
                FluidStack available=tank.getFluidInTank(i);
                if(available.isEmpty())continue;
                FluidResource resource=FluidResource.of(available);
                FluidStack simulated=tank.drain(available,IFluidHandler.FluidAction.SIMULATE);
                if(simulated.isEmpty())continue;
                int inserted=item.insert(resource,simulated.getAmount(),transaction);
                if(inserted<=0)continue;
                FluidStack drained=tank.drain(resource.toStack(inserted),IFluidHandler.FluidAction.EXECUTE);
                if(drained.getAmount()!=inserted)throw new IllegalStateException("Fluid transfer amount mismatch");
                transaction.commit();
                return true;
            }
        }
        return false;
    }

    public static boolean interactWithFluidHandler(Player player,InteractionHand hand,BlockPos ignored,
          ResourceHandler<FluidResource> tank,Object side) {
        return interactWithFluidHandler(player,hand,TransferInterop.importFluids(tank));
    }

    /** Compatibility entry point used by Robotics and the Flood Gate. */
    public static FluidStack tryPlaceFluid(ResourceHandler<FluidResource> source,Player player,
            Level level,BlockPos position,boolean place,Object side) {
        if(source==null||level==null||position==null)return FluidStack.EMPTY;
        BlockState block=level.getBlockState(position);
        boolean container=block.getBlock() instanceof LiquidBlockContainer;
        if(!container&&!block.canBeReplaced())return FluidStack.EMPTY;
        for(int i=0;i<source.size();i++) {
            FluidResource fluid=source.getResource(i);
            if(fluid.isEmpty()||source.getAmountAsInt(i)<1000)continue;
            var state=fluid.getFluid().defaultFluidState();
            if(state.isEmpty())continue;
            if(!place)return fluid.toStack(1000);
            try(Transaction tx=Transaction.openRoot()) {
                int amount=source.extract(i,fluid,1000,tx);
                if(amount!=1000)continue;
                boolean succeeded=container
                    ? ((LiquidBlockContainer)block.getBlock()).placeLiquid(level,position,block,state)
                    : level.setBlock(position,state.createLegacyBlock(),3);
                if(!succeeded)continue;
                tx.commit();
                return fluid.toStack(1000);
            }
        }
        return FluidStack.EMPTY;
    }
}
