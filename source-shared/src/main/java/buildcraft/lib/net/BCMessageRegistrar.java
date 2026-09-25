package buildcraft.lib.net;

import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

import buildcraft.lib.internal.module.IBuildCraftMod;
import net.minecraft.network.FriendlyByteBuf;

/** Loader adapter used by common/family message catalogs. */
@FunctionalInterface
public interface BCMessageRegistrar {
    <I> void register(
        IBuildCraftMod module,
        Class<I> messageClass,
        BiConsumer<I, Supplier<BCPacketContext>> handler,
        BiConsumer<I, FriendlyByteBuf> encoder,
        Function<FriendlyByteBuf, I> decoder,
        BCMessageDirection direction
    );
}
