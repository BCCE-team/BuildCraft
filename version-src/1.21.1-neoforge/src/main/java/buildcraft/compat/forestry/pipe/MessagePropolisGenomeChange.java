package buildcraft.compat.forestry.pipe;

import buildcraft.lib.net.BCPacketContext;
import forestry.api.core.genetics.ISpecies;
import forestry.core.platform.util.SpeciesUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import javax.annotation.Nullable;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** Authenticated serverbound genetic filter mutation for an Apiarist's Pipe. */
public record MessagePropolisGenomeChange(BlockPos position, Direction face, int slot,
                                         boolean active, @Nullable ResourceLocation speciesId) {
    public MessagePropolisGenomeChange(RegistryFriendlyByteBuf buf) {
        this(buf.readBlockPos(), Direction.from3DDataValue(buf.readUnsignedByte()),
             buf.readUnsignedByte(), buf.readBoolean(),
             buf.readBoolean() ? buf.readResourceLocation() : null);
    }

    public void toBytes(RegistryFriendlyByteBuf buf) {
        buf.writeBlockPos(position);
        buf.writeByte(face.ordinal());
        buf.writeByte(slot);
        buf.writeBoolean(active);
        buf.writeBoolean(speciesId != null);
        if (speciesId != null) buf.writeResourceLocation(speciesId);
    }

    public static final BiConsumer<MessagePropolisGenomeChange, Supplier<BCPacketContext>> HANDLER =
        (packet, context) -> context.get().enqueueWork(() -> packet.handle(context.get().getSender()));

    private void handle(ServerPlayer player) {
        if (player == null || slot < 0 || slot >= 3
                || !MessagePropolisRuleChange.canEdit(player, position)) return;
        PipeBehaviourPropolis behaviour = ContainerPropolisPipe.findBehaviour(player.level(), position);
        if (behaviour == null) return;
        ISpecies<?> species = null;
        if (speciesId != null) {
            try {
                species = SpeciesUtil.getAnySpecies(speciesId);
            } catch (RuntimeException invalidSpecies) {
                return;
            }
            if (species == null) return;
        }
        if (behaviour.getFilter().setGenomeFilter(face, slot, active, species)) {
            behaviour.sendToPlayers(behaviour.getFilter(), player.serverLevel(), player);
        }
    }
}
