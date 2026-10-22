//? source if >=1.21.11
/*
 * Copyright (c) 2011-2018 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.lib.compat.jade.mixin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.neoforged.fml.ModList;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Work around Jade's NeoForge multi-mod-jar entrypoint scan on versions where
 * every ModContainer for one physical jar scans the same @WailaPlugin classes.
 *
 * <p>BuildCraft intentionally ships several module mod ids in one jar. Jade
 * 1.21.11 scans that jar once per module and otherwise reports the same plugin
 * class as a fatal duplicate. Deduplicate only the returned entrypoint list;
 * provider registration and Jade's own plugin lifecycle remain untouched.</p>
 */
@Pseudo
@Mixin(targets = "snownee.jade.util.CommonProxy", remap = false)
public abstract class JadeEntrypointDedupMixin {
    @Inject(method = "loadEntrypoints", at = @At("RETURN"), cancellable = true, require = 0, remap = false)
    private static void buildcraft$deduplicateMultiModJarPlugins(CallbackInfoReturnable<List<?>> cir) {
        if (!ModList.get().isLoaded("jade")) {
            return;
        }

        List<?> original = cir.getReturnValue();
        if (original == null || original.size() < 2) {
            return;
        }

        Set<String> seenClasses = new LinkedHashSet<>();
        List<Object> unique = new ArrayList<>(original.size());
        for (Object entrypoint : original) {
            String className = className(entrypoint);
            // Unknown entrypoint implementations are preserved rather than risking
            // interference with another Jade version.
            if (className == null || seenClasses.add(className)) {
                unique.add(entrypoint);
            }
        }

        if (unique.size() != original.size()) {
            cir.setReturnValue(List.copyOf(unique));
        }
    }

    private static String className(Object entrypoint) {
        try {
            Method method = entrypoint.getClass().getMethod("className");
            Object value = method.invoke(entrypoint);
            return value instanceof String name ? name : null;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }
}
