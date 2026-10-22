package buildcraft.transport.internal.pipe;

import buildcraft.transport.item.ItemPipeHolder;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import java.util.function.Supplier;

public interface IPipeRegistry {
    PipeDefinition getDefinition(ResourceLocation identifier);

    void registerPipe(PipeDefinition definition);
    
    IItemPipe getItemForPipe(PipeDefinition definition);

    /** Creates and registries an {@link IItemPipe} for the given {@link Block} and {@link PipeDefinition}. 
     *  The item will be automatically registered with forge.
     **/
    IItemPipe registryItemForPipe(Supplier<? extends Block> block, PipeDefinition definition);

    Iterable<PipeDefinition> getAllRegisteredPipes();

	void setItemForPipe(PipeDefinition definition, IItemPipe item);

	ItemPipeHolder createItemForPipe(PipeDefinition definition);
}
