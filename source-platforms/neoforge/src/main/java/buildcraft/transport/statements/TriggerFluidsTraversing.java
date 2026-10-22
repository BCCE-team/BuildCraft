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
//? if mc_26_x && (>=26.3) {
import net.neoforged.neoforge.transfer.fluid.FluidUtil;
//? } else {
import net.neoforged.neoforge.fluids.FluidUtil;
//?}

public class TriggerFluidsTraversing extends BCStatement implements ITriggerInternal {

    public TriggerFluidsTraversing() {
        super("buildcraft:pipe_contains_fluids");
    }

//? if mc_26_x && (>=26.3) {
//? } else {
    @Override
//?}
    public Component getDescription() {
        return Component.translatable("gate.trigger.pipe.containsFluids");
    }

//? if mc_26_x && (>=26.3) {
//? } else {
    @Override
//?}
    public ISprite getSprite() {
        return BCTransportSprites.TRIGGER_FLUIDS_TRAVERSING;
    }

//? if mc_26_x && (>=26.3) {
//? } else {
    @Override
//?}
    public boolean isTriggerActive(IStatementContainer source, IStatementParameter[] parameters) {
    	FluidStack searchedFluid = FluidStack.EMPTY;
        if (parameters != null && parameters.length >= 1 && parameters[0] != null) {
        	ItemStack searchedStack = parameters[0].getItemStack();
//? if mc_26_x && (>=26.3) {
            searchedFluid = FluidUtil.getFirstStackContained(searchedStack);
//? } else {
            searchedFluid = FluidUtil.getFluidContained(searchedStack).orElse(FluidStack.EMPTY);
//?}
        }
        return source instanceof IGate gate 
        		&& gate.getPipeHolder().getPipe().getFlow() instanceof PipeFlowFluids fluidflow
        		&& fluidflow.doesContainFluid(searchedFluid);
    }
    
//? if mc_26_x && (>=26.3) {
//? } else {
    @Override
//?}
    public int maxParameters() {
        return 1;
    }
    
//? if mc_26_x && (>=26.3) {
//? } else {
    @Override
//?}
    public IStatementParameter createParameter(int index) {
        return new StatementParameterItemStack();
    }
}
