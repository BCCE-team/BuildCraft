/** Copyright (c) 2011-2015, SpaceToad and the BuildCraft Team http://www.mod-buildcraft.com
 *
 * The BuildCraft API is distributed under the terms of the MIT License. Please check the contents of the license, which
 * should be located as "LICENSE.API" in the BuildCraft source code distribution. */
package buildcraft.lib.internal.core;

import java.util.Optional;

//? if mc_26_x {
import net.minecraft.resources.Identifier;
//? } else {
import net.minecraft.resources.ResourceLocation;
//?}
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.ModLoadingContext;

public final class BuildCraftAPI {
    /** Deactivate constructor */
    private BuildCraftAPI() {}

    public static String getVersion() {
        Optional<? extends ModContainer> container = ModList.get().getModContainerById("buildcraftlib");
        if (container.isPresent()) {
            return container.get().getModInfo().getVersion().getQualifier();
        }
        return "UNKNOWN VERSION";
    }

//? if mc_26_x {
    public static Identifier nameToResourceLocation(String name) {
        if (name.indexOf(':') > 0) return Identifier.parse(name);
//? } else {
    public static ResourceLocation nameToResourceLocation(String name) {
        if (name.indexOf(':') > 0) return ResourceLocation.parse(name);
//?}
        ModContainer modContainer = ModLoadingContext.get().getActiveContainer();
        if (modContainer == null) {
            throw new IllegalStateException("Illegal recipe name " + name + ". Provide domain id to register it correctly.");
        }
//? if mc_26_x {
        return Identifier.fromNamespaceAndPath(modContainer.getModId(), name);
//? } else {
        return ResourceLocation.fromNamespaceAndPath(modContainer.getModId(), name);
//?}
    }
}
