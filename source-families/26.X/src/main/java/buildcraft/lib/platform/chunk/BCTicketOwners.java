package buildcraft.lib.platform.chunk;
import java.util.Set;
import net.minecraft.core.BlockPos;

/** View of block-owned persisted tickets during loader validation. UUIDs/player ownership are not ticket keys. */
public interface BCTicketOwners {
    Set<BlockPos> owners();
    void removeAllTickets(BlockPos owner);
}
