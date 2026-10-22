package buildcraft.lib.gui;

import buildcraft.lib.internal.debug.BCLog;
import buildcraft.lib.fluid.Tank;
import buildcraft.lib.gui.elem.ToolTip;
import buildcraft.lib.misc.LocaleUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.ForgeRegistry;

public class TankContainerData implements ContainerData{
	
	private static final ForgeRegistry<Fluid> fluids = (ForgeRegistry<Fluid>) ForgeRegistries.FLUIDS;
	/** Each logical 32-bit field uses two 16-bit ContainerData slots. */
    /** Five scalar ints plus component-preserving server and render variant IDs. */
    public static final int LEN = 14;
	protected final Tank[] tanks ;
	protected final int count;
	
	public TankContainerData(Tank... tanks) {
		this.tanks = tanks;
		this.count = tanks.length;
	}

	@Override
	public int get(int p) {
		if(p >= LEN*count||p<0) {
			BCLog.logger.error("TankContainer.get: %d index out of range : %d",p , count*LEN);
			return 0;
		}
        int value = switch ((p % LEN) / 2) {
			case 0 -> fluids.getID(tanks[p/LEN].getFluidType());
			case 1 -> tanks[p/LEN].getFluidAmount();
			case 2 -> fluids.getID(tanks[p/LEN].getFluidForRender().getRawFluid());
			case 3 -> tanks[p/LEN].getFluidForRender().getAmount();
			case 4 -> tanks[p/LEN].getCapacity();
            case 5 -> cacheVariant(tanks[p/LEN].getFluidInTank(0));
            case 6 -> cacheVariant(tanks[p/LEN].getFluidForRender());
			default -> 0;
        };
        return ((p & 1) == 0 ? value : value >>> 16) & 0xFFFF;
		 
	}

	@Override
	public void set(int index, int info) {
/*		if(playerId != 0xFFFF&&(index <size*2 && index >= 0&&(index&1)==0))
			tanks[index/2].onGuiClicked(playerId);*/
	}

	@Override
	public int getCount() {
		return count*LEN;
	}
	
	public FluidStack getFluidStack(int index) {
		return tanks[index].getFluidInTank(0);
	}
	
	public static Fluid getFluid(int id) {
		return fluids.getValue(id);
	}
	
    /** Undo vanilla DataSlot's signed 16-bit transport without assuming a particular loader. */
    public static int getPacked(ContainerData data, int lowWordIndex) {
        return (data.get(lowWordIndex) & 0xFFFF) | ((data.get(lowWordIndex + 1) & 0xFFFF) << 16);
    }


    private static int cacheVariant(FluidStack fluid) {
        return fluid == null || fluid.isEmpty() ? -1
            : buildcraft.lib.net.cache.BuildCraftObjectCaches.CACHE_FLUIDS.server().store(fluid);
    }

    /** Resolve full NBT/Data Components using the already-bounded object cache.
     * Original fluid-ID display stays available until the async cache request completes. */
    private static FluidStack readVariant(ContainerData data, int point, int cacheOffset,
                                          int typeOffset, int amountOffset) {
        int amount = Math.max(0, getPacked(data, point + amountOffset));
        if (amount == 0) return FluidStack.EMPTY;
        int expectedId = getPacked(data, point + typeOffset);
        if (data.getCount() - point >= LEN) {
            int cacheId = getPacked(data, point + cacheOffset);
            if (cacheId >= 0) {
                var link = buildcraft.lib.net.cache.BuildCraftObjectCaches.CACHE_FLUIDS.client().retrieve(cacheId);
                if (link.hasBeenReceived()) {
                    FluidStack full = link.get();
                    if (full != null && !full.isEmpty() && fluids.getID(full.getFluid()) == expectedId) {
                        FluidStack result = full.copy();
                        result.setAmount(amount);
                        return result;
                    }
                }
            }
        }
        return new FluidStack(fluids.getValue(expectedId), amount);
    }

	public static FluidStack getFluidStack(ContainerData data, int point) {
        return readVariant(data, point, 10, 0, 2);
	}
	
	public static FluidStack getRenderFluidStack(ContainerData data, int point) {
        return readVariant(data, point, 12, 4, 6);
	}
	
	public static Fluid getFluidType(ContainerData data, int point) {
		return fluids.getValue(getPacked(data, point + 0));
	}
	
	public static int getFluidAmount(ContainerData data, int point) {
		return getPacked(data, point + 2);
	}
	
	public static Fluid getRenderFluidId(ContainerData data, int point) {
		return fluids.getValue(getPacked(data, point + 4));
	}
	
	public static int getRenderFluidAmount(ContainerData data, int point) {
		return getPacked(data, point + 6);
	}
	
	public static int getTankCapacity(ContainerData data, int point) {
		return getPacked(data, point + 8);
	}
	
	public static ToolTip getTankToopTip(ContainerData data, int point) {
		return new ToolTip() {
	        @Override
	        public void refresh() {
	            clear();
	            int amount = getPacked(data, point + 2);
	            FluidStack fluidStack = getRenderFluidStack(data, point);
	            if (fluidStack != FluidStack.EMPTY && amount > 0) {
	                add(fluidStack.getDisplayName());
	            }
	            add(LocaleUtil.localizeFluidStaticAmount(amount, getPacked(data, point + 8)).setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)));
	            FluidStack serverFluid = getFluidStack(data, point);
	            if (serverFluid != FluidStack.EMPTY && serverFluid.getAmount() > 0) {
	                add(Component.translatable("tooltip.buildcraftlib.fluid.server_side_on_client").setStyle(Style.EMPTY.withColor(ChatFormatting.RED)));
	                add(serverFluid.getDisplayName());
	                add(LocaleUtil.localizeFluidStaticAmount(serverFluid.getAmount(), getPacked(data, point + 8)));
	            }
	        }
	    };

	}
	

}
