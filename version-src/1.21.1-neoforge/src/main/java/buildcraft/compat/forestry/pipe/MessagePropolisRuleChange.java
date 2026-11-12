package buildcraft.compat.forestry.pipe;

import buildcraft.lib.net.BCPacketContext;
import forestry.api.IForestryApi;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** Serverbound rule change: only an open, accessible Apiarist's Pipe can be edited. */
public record MessagePropolisRuleChange(BlockPos position, Direction face, String ruleId) {
    private static final int MAX_RULE_ID_LENGTH = 256;

    public MessagePropolisRuleChange(RegistryFriendlyByteBuf buf) {
        this(buf.readBlockPos(), Direction.from3DDataValue(buf.readUnsignedByte()),
             buf.readUtf(MAX_RULE_ID_LENGTH));
    }

    public void toBytes(RegistryFriendlyByteBuf buf) {
        buf.writeBlockPos(position);
        buf.writeByte(face.ordinal());
        buf.writeUtf(ruleId, MAX_RULE_ID_LENGTH);
    }

    public static final BiConsumer<MessagePropolisRuleChange, Supplier<BCPacketContext>> HANDLER =
        (packet, context) -> context.get().enqueueWork(() -> packet.handle(context.get().getSender()));

    private void handle(ServerPlayer player) {
        if (player == null || !canEdit(player, position)) return;
        var rule = IForestryApi.INSTANCE.getFilterManager().getRule(ruleId);
        if (rule == null) return;
        PipeBehaviourPropolis behaviour = ContainerPropolisPipe.findBehaviour(player.level(), position);
        if (behaviour != null && behaviour.getFilter().setRule(face, rule)) {
            behaviour.sendToPlayers(behaviour.getFilter(), player.serverLevel(), player);
        }
    }

    static boolean canEdit(ServerPlayer player, BlockPos position) {
        return player.containerMenu instanceof ContainerPropolisPipe menu
            && menu.hasSamePipe(position) && menu.stillValid(player);
    }
}
