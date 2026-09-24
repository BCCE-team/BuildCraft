package buildcraft.lib;

import buildcraft.lib.internal.debug.BCLog;
import buildcraft.core.BCCoreBlocks;
import buildcraft.energy.BCEnergy;
import buildcraft.energy.BCEnergyFluids;
import buildcraft.factory.BCFactoryBlocks;
import buildcraft.transport.BCTransportBlocks;
import java.util.concurrent.CompletableFuture;

import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraftforge.common.data.BlockTagsProvider;
import net.minecraft.data.tags.FluidTagsProvider;
import net.minecraft.tags.BlockTags;
import net.minecraftforge.common.data.ExistingFileHelper;

public class BCTagsProvider {
	
	public static class BlockTag extends BlockTagsProvider{

		public BlockTag(PackOutput output, CompletableFuture<HolderLookup.Provider> lookupProvider, ExistingFileHelper existingFileHelper) {
			super(output, lookupProvider, BCLib.MODID, existingFileHelper);
		}

		@Override
		protected void addTags(HolderLookup.Provider lookupProvider) {
			tag(BlockTags.MINEABLE_WITH_PICKAXE).add(BCCoreBlocks.ENGINE_BC8.get())
			.add(BCFactoryBlocks.AUTO_BENCH_BLOCK.get())
			.add(BCFactoryBlocks.DISTILLER_BLOCK.get())
			.add(BCFactoryBlocks.FLOOD_GATE_BLOCK.get())
			.add(BCFactoryBlocks.CHUTE_BLOCK.get())
			.add(BCFactoryBlocks.HEATEXCHANGE_BLOCK.get())
			.add(BCFactoryBlocks.MINING_WELL_BLOCK.get())
			.add(BCFactoryBlocks.PUMP_BLOCK.get())
			.add(BCFactoryBlocks.TANK_BLOCK.get())
			.add(BCTransportBlocks.filterBuffer.get())
			.add(BCTransportBlocks.pipeHolder.get());
		}
		
		
	}
	

	public static class FluidTag extends FluidTagsProvider{

		public FluidTag(PackOutput output, CompletableFuture<HolderLookup.Provider> lookupProvider, ExistingFileHelper existingFileHelper) {
			super(output, lookupProvider, BCEnergy.MODID, existingFileHelper);
		}

		@Override
		protected void addTags(HolderLookup.Provider lookupProvider) {
			if(BCEnergyFluids.crudeOil[0] != null) {
			tag(BCEnergyFluids.IS_OIL)
			.add(BCEnergyFluids.crudeOil)
			.add(BCEnergyFluids.oilDense)
			.add(BCEnergyFluids.oilDistilled)
			.add(BCEnergyFluids.oilHeavy)
			.add(BCEnergyFluids.oilResidue);
			tag(BCEnergyFluids.IS_FUEL)
			.add(BCEnergyFluids.fuelDense)
			.add(BCEnergyFluids.fuelGaseous)
			.add(BCEnergyFluids.fuelLight)
			.add(BCEnergyFluids.fuelMixedHeavy)
			.add(BCEnergyFluids.fuelMixedLight);
			}
			else
				BCLog.logger.debug("fail to add tag in FluidTagsProvider");
		}
		
	}

}
