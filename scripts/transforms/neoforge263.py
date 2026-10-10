"""26.3-only Java relocations for the legacy BuildCraft storage implementation.

NeoForge removed the deprecated IItemHandler/IFluidHandler/IEnergyStorage family
in 26.3; do not generate shadow classes under the loader's own namespaces.
The BuildCraft-owned compatibility contracts remain INTERNAL and are exposed
through TransferInterop's native ResourceHandler/EnergyHandler boundaries.
"""
from __future__ import annotations

import re

from source_preprocessor import version_tuple

_RELOCATIONS = {
    "net.neoforged.neoforge.fluids.capability.IFluidHandlerItem": "buildcraft.lib.compat.neoforge263.fluids.IFluidHandlerItem",
    "net.neoforged.neoforge.fluids.capability.IFluidHandler": "buildcraft.lib.compat.neoforge263.fluids.IFluidHandler",
    "net.neoforged.neoforge.fluids.IFluidTank": "buildcraft.lib.compat.neoforge263.fluids.IFluidTank",
    "net.neoforged.neoforge.fluids.FluidUtil": "buildcraft.lib.compat.neoforge263.fluids.FluidUtil",
    "net.neoforged.neoforge.items.wrapper.InvWrapper": "buildcraft.lib.compat.neoforge263.items.wrapper.InvWrapper",
    "net.neoforged.neoforge.items.wrapper.CombinedInvWrapper": "buildcraft.lib.compat.neoforge263.items.wrapper.CombinedInvWrapper",
    "net.neoforged.neoforge.items.IItemHandlerModifiable": "buildcraft.lib.compat.neoforge263.items.IItemHandlerModifiable",
    "net.neoforged.neoforge.items.IItemHandler": "buildcraft.lib.compat.neoforge263.items.IItemHandler",
    "net.neoforged.neoforge.items.ItemHandlerCopySlot": "buildcraft.lib.compat.neoforge263.items.ItemHandlerCopySlot",
    "net.neoforged.neoforge.items.SlotItemHandler": "buildcraft.lib.compat.neoforge263.items.SlotItemHandler",
    "net.neoforged.neoforge.energy.IEnergyStorage": "buildcraft.lib.compat.neoforge263.energy.IEnergyStorage",
}

def relocate_removed_neoforge_apis(text: str, *, minecraft: str, relative: str) -> str:
    if not relative.endswith('.java') or version_tuple(minecraft) < version_tuple('26.3'):
        return text
    for old, new in _RELOCATIONS.items():
        text = text.replace(old, new)
    return text


def upgrade_minecraft_263_apis(text: str, *, minecraft: str, relative: str) -> str:
    """Precisely scoped 26.3 vanilla/loader migration after Java source selection.

    Do not apply these changes to 26.2: 26.3 removed public GUI accessors,
    changed tool/animation contracts, and replaced several enum values.
    """
    if not relative.endswith(".java") or version_tuple(minecraft) < version_tuple("26.3"):
        return text

    # 26.3 GUI getters, including those in ordinary components (not BC screens).
    for old, new in (("getGuiLeft()", "getLeftPos()"),
                     ("getGuiTop()", "getTopPos()"),
                     ("getXSize()", "getImageWidth()"),
                     ("getYSize()", "getImageHeight()")):
        text = text.replace(old, new)
        text = text.replace("::" + old[:-2], "::" + new[:-2])

    # FML 11 config types; COMMON's unsynchronized, both-sides replacement is LOCAL.
    text = text.replace("Type.COMMON", "Type.LOCAL")
    # Legacy motion-blocking heuristic and renamed piston reactions.
    text = text.replace(".blocksMotion()", ".isSolid()")
    for old, new in (("NORMAL", "PUSH_PULL"), ("DESTROY", "POPPED"),
                     ("BLOCK", "IMMOVEABLE"), ("IGNORE", "IGNORE_ENTITY"),
                     ("PUSH_ONLY", "PUSH")):
        text = re.sub(r"\bPushReaction\." + old + r"\b", "PushReaction." + new, text)

    # Newer PoseStack takes quaternions via rotate(), not mulPose().
    text = re.sub(r"\.mulPose\((Axis\.[A-Z]+\.rotationDegrees\()", r".rotate(\1", text)
    # Vec3i copy-constructor removed. Preserve all three coordinates, not a mutable alias.
    text = text.replace(".map(BlockPos::new)",
                        ".map(pos -> new BlockPos(pos.getX(), pos.getY(), pos.getZ()))")

    if "import org.lwjgl.glfw.GLFW;" in text:
        text = text.replace("import org.lwjgl.glfw.GLFW;",
                            "import com.mojang.blaze3d.platform.InputConstants;")
        keys = {"ENTER": "RETURN", "KP_ENTER": "NUMPADENTER", "ESCAPE": "ESCAPE",
                "F5": "F5", "EQUAL": "EQUALS", "KP_ADD": "ADD",
                "MINUS": "MINUS", "KP_SUBTRACT": "SUBTRACT", "M": "M"}
        for before, after in keys.items():
            text = text.replace("GLFW.GLFW_KEY_" + before + " ", "InputConstants.KEY_" + after + " ")
            text = text.replace("GLFW.GLFW_KEY_" + before + ")", "InputConstants.KEY_" + after + ")")
            text = text.replace("GLFW.GLFW_KEY_" + before + " ||", "InputConstants.KEY_" + after + " ||")
        text = re.sub(r"GLFW\.GLFW_KEY_([A-Z0-9_]+)",
                      lambda m: "InputConstants.KEY_" + keys.get(m.group(1), m.group(1)), text)

    if relative.endswith("/robotics/gui/GuiZonePlanner.java"):
        # 26.3 uses SDL scancodes rather than GLFW key values. There is no
        # InputConstants.KEY_SUBTRACT: SDL names the numpad key KP_MINUS.
        text = text.replace("InputConstants.KEY_SUBTRACT",
                            "org.lwjgl.sdl.SDLScancode.SDL_SCANCODE_KP_MINUS")

    # Tool subclasses were removed with the block transformer rewrite.
    for cls, tag in (("HoeItem", "HOES"), ("AxeItem", "AXES"),
                     ("ShovelItem", "SHOVELS")):
        if "import net.minecraft.world.item." + cls + ";" in text:
            text = text.replace("import net.minecraft.world.item." + cls + ";", "")
            text = re.sub(r"(\b[a-zA-Z_$][\w$]*)\.getItem\(\) instanceof " + cls + r"\b",
                          lambda m: m.group(1) + ".is(net.minecraft.tags.ItemTags." + tag + ")", text)

    # Modern player actions must supply the prediction/animation explicitly.
    text = re.sub(r"\bplayer\.drop\(([^(),]+),\s*(true|false),\s*(true|false)\)",
                  r"player.drop(\1, \2, net.minecraft.util.Prediction.SERVER_ONLY)", text)
    text = re.sub(r"\bplayer\.drop\(([^(),]+),\s*(true|false)\)",
                  r"player.drop(\1, \2, net.minecraft.util.Prediction.SERVER_ONLY)", text)
    text = re.sub(r"\bplayer\.swing\((\w+(?:\.getHand\(\))?)\)",
                  r"player.swing(\1, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true)", text)
    text = re.sub(r"\bplayer\.swingingArm\s*=\s*(\w+)\s*;",
                  r"player.swing(\1, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true);", text)

    if relative.endswith("/energy/BCEnergyBiomeModifiers.java"):
        text = text.replace("import net.minecraft.core.Holder;",
                            "import net.minecraft.core.Holder;\nimport net.minecraft.core.RegistryAccess;")
        text = text.replace("void modify(Holder<Biome> biome, Phase phase, BiomeInfo.Builder builder)",
                            "void modify(RegistryAccess registries, Holder<Biome> biome, Phase phase, BiomeInfo.Builder builder)")

    if relative.endswith("/transport/block/BlockPipeHolder.java"):
        text = text.replace("spawnDestroyParticles(world, player, pos, state)",
                            "spawnDestroyParticles(world, pos, state)")
        text = text.replace("public void playerDestroy(Level world, Player player, BlockPos pos, BlockState state, BlockEntity be,",
                            "public void playerDestroy(ServerLevel world, Player player, BlockPos pos, BlockState state, BlockEntity be,")
        # Block#playerDestroy now expects ServerPlayer as well as ServerLevel;
        # preserving Player silently removes the override and fails at super.
        text = text.replace("public void playerDestroy(ServerLevel world, Player player, BlockPos pos, BlockState state, BlockEntity be,",
                            "public void playerDestroy(ServerLevel world, net.minecraft.server.level.ServerPlayer player, BlockPos pos, BlockState state, BlockEntity be,")

    if relative.endswith("/lib/client/guide/GuiGuide.java"):
        text = text.replace("Util.getPlatform().openUri(new URI(target))",
                            "com.mojang.blaze3d.Blaze3D.openUri(new URI(target))")

    if relative.endswith("/robotics/entity/EntityRobot.java"):
        if "OperationMode.EXECUTE" in text and "import buildcraft.api.v2.OperationMode;" not in text:
            text = text.replace("package buildcraft.robotics.entity;",
                "package buildcraft.robotics.entity;\n\nimport buildcraft.api.v2.OperationMode;")

    if relative.endswith("/robotics/tile/TileZonePlanner.java"):
        text = text.replace("chunkPos.x, chunkPos.z", "chunkPos.x(), chunkPos.z()")

    if relative.endswith("/silicon/client/model/plug/PlugBakerFacade.java"):
        # MaterialInfo carries a nullable shading direction, not the old shade() flag.
        text = text.replace("material.shade()", "material.shadeDirectionOverride() != null || material.ambientOcclusion()")

    if relative.endswith("/transport/client/render/RenderPipeHolder.java"):
        # Kept as a fallback for older source variants. The selected 26.X
        # source now implements setUv3 explicitly inside a 26.3-only branch.
        if "public VertexConsumer setUv3(float u, float v)" not in text:
            needle = "        public VertexConsumer setUv2(int u, int v) {\n            return this;\n        }"
            if needle in text:
                text = text.replace(needle, needle +
                    "\n\n        @Override\n        public VertexConsumer setUv3(float u, float v) {\n            return this;\n        }")

    if relative.endswith("/factory/BCFactory.java") or relative.endswith("/silicon/BCSilicon.java"):
        # Recipe datagen providers are excluded from standalone runtime compilation.
        # Their JSON output remains in resources; an uncompiled class cannot be
        # directly referred to even from a datagen-only method.
        text = re.sub(r"event\.createReloadableRegistryObjects\(\s*new RegistrySetBuilder\(\)\.add\(RecipeProvider\.asBootstrap\((?:BCFactoryRecipesProvider|BCSiliconRecipesProvider)::new\)\)\s*\);",
                      "// 26.3: generated recipe data is shipped as resources; runtime excludes the datagen providers.", text)

    if relative.endswith("/factory/container/ContainerAutoCraftItems.java"):
        text = text.replace("new ItemHandlerSimple(1), access);",
                            "new ItemProvider(slot -> net.minecraft.world.item.ItemStack.EMPTY, 1), access);")

    if relative.endswith("/builders/menu/ContainerBuilder.java"):
        text = text.replace("new ItemHandlerSimple(24), DataSlot.standalone()",
                            "new ItemProvider(slot -> net.minecraft.world.item.ItemStack.EMPTY, 24), DataSlot.standalone()")

    # The power-transport module has its own FluidAction enum. Keep both
    # contracts explicit: all IMjReceiver callers use an adapter, while each
    # transport-specific receiver implements the required legacy bridge.
    if relative.endswith("/lib/internal/mj/IMjReceiver.java"):
        needle = "    long receivePower(long microJoules, FluidAction simulate);"
        if needle in text:
            text = text.replace(needle, needle + "\n\n" +
                "    default long receivePower(long microJoules, Enum<?> action) {\n" +
                "        return receivePower(microJoules, FluidAction.valueOf(action.name()));\n" +
                "    }")

    transport_receivers = (
        "/transport/pipe/behaviour/PipeBehaviourObsidian.java",
        "/transport/pipe/behaviour/PipeBehaviourWood.java",
        "/transport/pipe/behaviour/PipeBehaviourStripes.java",
        "/transport/pipe/flow/PipeFlowPower.java",
        "/robotics/plug/RobotStationPluggable.java",
    )
    if any(relative.endswith(path) for path in transport_receivers):
        pattern = r"(\s+public long receivePower\(long microJoules, FluidAction (?:simulate|action)\) \{)"
        bridge = ("\n    public long receivePower(long microJoules, "
                  "buildcraft.lib.compat.neoforge263.fluids.IFluidHandler.FluidAction action) {\n"
                  "        return receivePower(microJoules, FluidAction.valueOf(action.name()));\n"
                  "    }\n")
        text = re.sub(pattern, lambda m: bridge + m.group(1), text)

    if relative.endswith("/lib/internal/mj/MjBattery.java"):
        needle = "    public long addPowerChecking(long microJoulesToAdd, FluidAction simulate) {"
        if needle in text:
            bridge = ("    public long addPowerChecking(long microJoulesToAdd, "
                "Enum<?> action) {\n"
                "        return addPowerChecking(microJoulesToAdd, FluidAction.valueOf(action.name()));\n"
                "    }\n\n")
            text = text.replace(needle, bridge + needle)

    return text
