//? source if >=1.21.11
package buildcraft.lib.misc;

import net.minecraft.world.InteractionResult;

/**
 * Local payload holder for BCCE helper code on Minecraft 1.21.11+.
 *
 * Minecraft 1.21.11 folds InteractionResultHolder into InteractionResult. BuildCraft still uses a holder-shaped
 * value for internal validation/extraction helpers that carry a payload. Do not use this for Item#use overrides;
 * the materializer rewrites those to plain InteractionResult.
 */
public final class InteractionResultHolder<T> {
    private final InteractionResult result;
    private final T object;

    public InteractionResultHolder(InteractionResult result, T object) {
        this.result = result;
        this.object = object;
    }

    public InteractionResult getResult() {
        return result;
    }

    public T getObject() {
        return object;
    }

    public static <T> InteractionResultHolder<T> sidedSuccess(T object, boolean clientSide) {
        return new InteractionResultHolder<>(clientSide ? InteractionResult.SUCCESS : InteractionResult.SUCCESS_SERVER, object);
    }
}
