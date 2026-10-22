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
     * types using only the built-in flow must declare exactly one medium.
     * A registered custom flow may combine built-in and namespaced custom media inside
     * the standard BCCE holder; the optional archetype controls its carrier appearance.
     * Addons can register their own block/renderer where the standard carrier is unsuitable.
     * Addons remain responsible for registering the returned Item with their loader's item registry.
     */
    net.minecraft.world.item.Item createItem(ResourceLocation pipeTypeId);

    Optional<PipeView> pipe(Level level, BlockPos pos);

    /** Find a registered external endpoint without forcing the neighbouring chunk to load. */
    default Optional<PipeExternalEndpoint> externalEndpoint(Level level, BlockPos pipePos, Direction side) {
        return Optional.empty();
    }

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
