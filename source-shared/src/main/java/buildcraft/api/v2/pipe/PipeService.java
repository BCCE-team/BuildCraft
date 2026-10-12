package buildcraft.api.v2.pipe;

import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.item.ItemTransferResult;
import buildcraft.api.v2.permission.AutomationActor;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Runtime lookup and safe mutation facade for BuildCraft pipes.
 *
 * <p>{@link #createItem(ResourceLocation)} creates a placeable item for a registered pipe type.
 * Standalone types built with {@link PipeType#builder(ResourceLocation)} use BCCE's default
 * carrier for their single declared {@link PipeMedium}. Their registered components determine
 * behaviour; placement, item/fluid/energy transport, gates, wires, persistence, and sync use
 * the standard BCCE pipe holder. {@link PipeType#variant(ResourceLocation, PipeType)} keeps
 * an explicit carrier's appearance and default behaviour.</p>
 */
public interface PipeService {
    /**
     * Creates the vanilla Item used to place a registered API2 pipe type. Standalone pipe
     * types must declare exactly one medium; multiple independent flows in the same holder
     * and entirely new carrier/block models are not supported by this contract.
     * Addons remain responsible for registering the returned Item with their loader's item registry.
     */
    net.minecraft.world.item.Item createItem(ResourceLocation pipeTypeId);

    Optional<PipeView> pipe(Level level, BlockPos pos);

    PipeAttachmentResult placeAttachment(
        Level level,
        BlockPos pos,
        Direction side,
        ResourceLocation attachmentTypeId,
        ItemStack stack,
        AutomationActor actor,
        OperationMode mode
    );

    boolean removeAttachment(Level level, BlockPos pos, Direction side, AutomationActor actor, OperationMode mode);

    ItemTransferResult injectItem(Level level, BlockPos pos, ItemInjectionRequest request, OperationMode mode);
}
