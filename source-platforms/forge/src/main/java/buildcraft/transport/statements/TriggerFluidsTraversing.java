package buildcraft.transport.statements;

import buildcraft.lib.internal.core.render.ISprite;
import buildcraft.transport.internal.gate.IGate;
import buildcraft.lib.internal.statement.IStatementContainer;
import buildcraft.lib.internal.statement.IStatementParameter;
import buildcraft.lib.internal.statement.ITriggerInternal;
import buildcraft.lib.internal.statement.StatementParameterItemStack;
import buildcraft.core.statements.BCStatement;
import buildcraft.transport.BCTransportSprites;
import buildcraft.transport.pipe.flow.PipeFlowFluids;
import buildcraft.lib.platform.storage.PlatformFluidPipeTransfer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;

public class TriggerFluidsTraversing extends BCStatement implements ITriggerInternal {

    public TriggerFluidsTraversing() {
        super("buildcraft:pipe_contains_fluids");
    }

    @Override
    public Component getDescription() {
        return Component.translatable("gate.trigger.pipe.containsFluids");
    }

    @Override
    public ISprite getSprite() {
        return BCTransportSprites.TRIGGER_FLUIDS_TRAVERSING;
    }

    @Override
    public boolean isTriggerActive(IStatementContainer source, IStatementParameter[] parameters) {
    	FluidStack searchedFluid = FluidStack.EMPTY;
        if (parameters != null && parameters.length >= 1 && parameters[0] != null) {
        	ItemStack searchedStack = parameters[0].getItemStack();
            searchedFluid = FluidUtil.getFluidContained(searchedStack).orElse(FluidStack.EMPTY);
        }
        return source instanceof IGate gate 
        		&& gate.getPipeHolder().getPipe().getFlow() instanceof PipeFlowFluids fluidflow
        		&& fluidflow.doesContainFluid(PlatformFluidPipeTransfer.toVolume(searchedFluid));
    }
    
    @Override
    public int maxParameters() {
        return 1;
    }
    
    @Override
    public IStatementParameter createParameter(int index) {
        return new StatementParameterItemStack();
    }
}
