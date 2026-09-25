package buildcraft.fabric;

import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.net.FabricNetworkManager;
import buildcraft.lib.net.FabricPacketContext;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/** Client bootstrap currently installs only the networking half required by the server foundation. */
public final class BuildCraftFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        FabricNetworkManager.installClientBridge(new FabricNetworkManager.ClientBridge() {
            @Override
            public void register(FabricNetworkManager.MessageInfo<?> info) {
                registerTyped(info);
            }

            @Override
            public void send(ResourceLocation channel, FriendlyByteBuf buf) {
                ClientPlayNetworking.send(channel, buf);
            }

            private <I> void registerTyped(FabricNetworkManager.MessageInfo<I> info) {
                ClientPlayNetworking.registerGlobalReceiver(info.channel, (client, handler, buf, responseSender) -> {
                    final I message;
                    try {
                        message = info.decoder.apply(buf);
                    } catch (RuntimeException exception) {
                        return;
                    }
                    client.execute(() -> info.handler.accept(message, () -> new FabricPacketContext(
                        BCNetworkSide.CLIENT,
                        Minecraft.getInstance().player,
                        client::execute
                    )));
                });
            }
        });
    }
}
