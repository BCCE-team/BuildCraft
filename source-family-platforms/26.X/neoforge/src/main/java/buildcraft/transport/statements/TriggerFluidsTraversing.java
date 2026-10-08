//? source if >=26.3
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
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.transfer.fluid.FluidUtil;

public class TriggerFluidsTraversing extends BCStatement implements ITriggerInternal {

    public TriggerFluidsTraversing() {
        super("buildcraft:pipe_contains_fluids");
    }

    public Component getDescription() {
        return Component.translatable("gate.trigger.pipe.containsFluids");
    }

    public ISprite getSprite() {
        return BCTransportSprites.TRIGGER_FLUIDS_TRAVERSING;
    }

    public boolean isTriggerActive(IStatementContainer source, IStatementParameter[] parameters) {
    	FluidStack searchedFluid = FluidStack.EMPTY;
        if (parameters != null && parameters.length >= 1 && parameters[0] != null) {
        	ItemStack searchedStack = parameters[0].getItemStack();
            searchedFluid = FluidUtil.getFirstStackContained(searchedStack);
        }
        return source instanceof IGate gate 
        		&& gate.getPipeHolder().getPipe().getFlow() instanceof PipeFlowFluids fluidflow
        		&& fluidflow.doesContainFluid(searchedFluid);
    }
    
    public int maxParameters() {
        return 1;
    }
    
    public IStatementParameter createParameter(int index) {
        return new StatementParameterItemStack();
    }
}
