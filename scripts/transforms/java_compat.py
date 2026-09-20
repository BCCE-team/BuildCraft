#!/usr/bin/env python3
"""Mechanical Minecraft Java compatibility transforms.

This module may perform only path-independent lexical/API-shape conversions.
BuildCraft-class-specific port logic belongs in canonical family sources or
explicit older-version downports, never in the materialization pipeline.
"""
from __future__ import annotations

import re

from source_preprocessor import version_tuple

# Compatibility alias for the mechanically extracted bootstrap code.
_version_tuple = version_tuple

# 1.21.11 compatibility aliases live only in BuildCraft-owned packages.
NEOFORGE_121111_SHIM_RELOCATIONS = (
    ("net.neoforged.neoforge.common.util.INBTSerializable", "buildcraft.lib.compat.neoforge121111.common.util.INBTSerializable"),
    ("net.neoforged.neoforge.client.ChunkRenderTypeSet", "buildcraft.lib.compat.neoforge121111.client.ChunkRenderTypeSet"),
    ("net.neoforged.neoforge.client.model.QuadTransformers", "buildcraft.lib.compat.neoforge121111.client.model.QuadTransformers"),
    ("net.neoforged.neoforge.client.model.IDynamicBakedModel", "buildcraft.lib.compat.neoforge121111.client.model.IDynamicBakedModel"),
    ("net.neoforged.neoforge.client.model.SimpleModelState", "buildcraft.lib.compat.neoforge121111.client.model.SimpleModelState"),
    ("net.neoforged.neoforge.client.model.CompositeModel", "buildcraft.lib.compat.neoforge121111.client.model.CompositeModel"),
    ("net.neoforged.neoforge.client.model.geometry.IGeometryLoader", "buildcraft.lib.compat.neoforge121111.client.model.geometry.IGeometryLoader"),
    ("net.neoforged.neoforge.client.model.geometry.UnbakedGeometryHelper", "buildcraft.lib.compat.neoforge121111.client.model.geometry.UnbakedGeometryHelper"),
    ("net.neoforged.neoforge.client.model.geometry.IGeometryBakingContext", "buildcraft.lib.compat.neoforge121111.client.model.geometry.IGeometryBakingContext"),
    ("net.neoforged.neoforge.client.model.geometry.IUnbakedGeometry", "buildcraft.lib.compat.neoforge121111.client.model.geometry.IUnbakedGeometry"),
    ("net.neoforged.neoforge.client.model.geometry.StandaloneGeometryBakingContext", "buildcraft.lib.compat.neoforge121111.client.model.geometry.StandaloneGeometryBakingContext"),
)


# Minecraft/Blaze3D aliases are likewise relocated into BuildCraft-owned packages.
MINECRAFT_121111_SHIM_RELOCATIONS = (
    ("com.mojang.blaze3d.vertex.BufferUploader", "buildcraft.lib.compat.mc121111.blaze3d.vertex.BufferUploader"),
    ("com.mojang.blaze3d.vertex.VertexBuffer", "buildcraft.lib.compat.mc121111.blaze3d.vertex.VertexBuffer"),
    ("net.minecraft.client.color.item.ItemColor", "buildcraft.lib.compat.mc121111.client.color.item.ItemColor"),
    ("net.minecraft.client.renderer.ShaderInstance", "buildcraft.lib.compat.mc121111.client.renderer.ShaderInstance"),
    ("net.minecraft.client.renderer.block.model.BakedQuad", "buildcraft.lib.compat.mc121111.client.renderer.block.model.BakedQuad"),
    ("net.minecraft.client.renderer.block.model.ItemOverrides", "buildcraft.lib.compat.mc121111.client.renderer.block.model.ItemOverrides"),
    ("net.minecraft.client.resources.model.BakedModel", "buildcraft.lib.compat.mc121111.client.resources.model.BakedModel"),
    ("net.minecraft.client.resources.model.ModelResourceLocation", "buildcraft.lib.compat.mc121111.client.resources.model.ModelResourceLocation"),
    ("net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer", "buildcraft.lib.compat.mc121111.world.item.crafting.SimpleCraftingRecipeSerializer"),
)




def _rewrite_deferred_register_calls(text: str, registry_suffix: str, compat_method: str) -> str:
    """Rewrite DeferredRegister.register(path, supplier) to RegistryCompat wrappers.

    The old regex only caught string literal paths. 1.21.11 also needs the
    thread-local id wrapper for computed paths such as fullName or
    def.identifier.getPath(). Single-argument calls like BLOCKS.register(bus)
    must be left untouched.
    """
    pattern = re.compile(r"\b((?:[A-Za-z_][A-Za-z0-9_]*\.)*" + re.escape(registry_suffix) + r")\.register\s*\(")
    pieces: list[str] = []
    last = 0
    pos = 0
    while True:
        match = pattern.search(text, pos)
        if match is None:
            break
        open_index = match.end() - 1
        index = open_index + 1
        depth = 1
        first_comma = -1
        quote: str | None = None
        escape = False
        while index < len(text) and depth > 0:
            char = text[index]
            if quote is not None:
                if escape:
                    escape = False
                elif char == "\\":
                    escape = True
                elif char == quote:
                    quote = None
            else:
                if char in ('"', "'"):
                    quote = char
                elif char == '(':
                    depth += 1
                elif char == ')':
                    depth -= 1
                elif char == ',' and depth == 1:
                    first_comma = index
                    break
            index += 1
        if first_comma < 0:
            pos = match.end()
            continue
        pieces.append(text[last:match.start()])
        pieces.append(f"RegistryCompat.{compat_method}({match.group(1)}, ")
        last = match.end()
        pos = match.end()
    if not pieces:
        return text
    pieces.append(text[last:])
    return "".join(pieces)


def _apply_121111_item_use_result_renames(text: str) -> str:
    """Adapt old Item#use ItemStack holders to 1.21.11 InteractionResult returns.

    Internal BuildCraft transfer helpers still use an InteractionResult + payload
    holder; those are remapped to a BCCE shim elsewhere.  This function only
    handles vanilla item-use methods whose payload was the used ItemStack.
    """
    if "InteractionResultHolder<ItemStack>" not in text and "InteractionResultHolder<net.minecraft.world.item.ItemStack>" not in text:
        return text

    text = text.replace("import net.minecraft.world.InteractionResultHolder;\n", "")
    text = text.replace("InteractionResultHolder<ItemStack>", "InteractionResult")
    text = text.replace("InteractionResultHolder<net.minecraft.world.item.ItemStack>", "InteractionResult")

    # The 1.21.11 Item#use result no longer carries the stack.  Existing BCCE
    # item-use implementations always return the same hand stack, so dropping the
    # payload is behavior-preserving for these methods.
    text = re.sub(
        r"new\s+InteractionResultHolder(?:<[^>]*>)?\s*\(\s*(InteractionResult\.[A-Z_]+)\s*,\s*[^;]+?\)",
        r"\1",
        text,
    )
    text = re.sub(
        r"InteractionResultHolder\.sidedSuccess\s*\(\s*[^,]+,\s*([^)]+)\)",
        r"((\1) ? InteractionResult.SUCCESS : InteractionResult.SUCCESS_SERVER)",
        text,
    )
    text = re.sub(r"return\s+(InteractionResult\.[A-Z_]+)\s*\)\s*;", r"return \1;", text)
    return text




def _replace_java_identifier(text: str, before: str, after: str) -> str:
    """Replace a Java simple-name token without touching longer identifiers.

    This is intentionally stricter than str.replace: Minecraft 1.21.11
    renamed ResourceLocation to Identifier, but classes such as
    ModelResourceLocation did not become ModelIdentifier.
    """
    return re.sub(r"(?<![A-Za-z0-9_$])" + re.escape(before) + r"(?![A-Za-z0-9_$])", after, text)


def _cleanup_empty_java_imports(text: str) -> str:
    # Compatibility rewrites can remove the imported type from a line.
    # Never leave a bare `import` token, because javac then treats the following
    # class body as part of a broken import and the next inserted import may end
    # up inside the type declaration.
    return re.sub(r"(?m)^[ \t]*import[ \t]*$\n?", "", text)


def _ensure_java_import(text: str, qualified_name: str) -> str:
    text = _cleanup_empty_java_imports(text)
    import_line = f"import {qualified_name};"
    if import_line in text:
        return text
    package_match = re.search(r"^package [^;]+;\n", text, re.MULTILINE)
    if not package_match:
        return import_line + "\n" + text
    imports = list(re.finditer(r"^[ \t]*import (?:static )?[^;\n]+;\n", text, re.MULTILINE))
    insert_at = imports[-1].end() if imports else package_match.end()
    return text[:insert_at] + import_line + "\n" + text[insert_at:]

def _repair_java_imports_before_package(text: str) -> str:
    """Move accidental imports that appear before the package declaration.

    During fast 1.21.11 port iterations, a stale generated tree can contain an
    import inserted before the source header/package. Keeping this repair local
    to the materializer makes the output valid Java without touching maintained
    sources or older targets.
    """
    package_match = re.search(r"^package [^;]+;\n", text, re.MULTILINE)
    if not package_match:
        return text
    prefix = text[:package_match.start()]
    misplaced = list(re.finditer(r"^[ \t]*import (?:static )?[^;\n]+;\n", prefix, re.MULTILINE))
    if not misplaced:
        return text
    import_lines = []
    seen = set()
    for m in misplaced:
        line = m.group(0)
        key = line.strip()
        if key not in seen:
            seen.add(key)
            import_lines.append(line if line.endswith("\n") else line + "\n")
    cleaned_prefix = prefix
    for m in reversed(misplaced):
        cleaned_prefix = cleaned_prefix[:m.start()] + cleaned_prefix[m.end():]
    body = cleaned_prefix + text[package_match.start():]
    imports = list(re.finditer(r"^[ \t]*import (?:static )?[^;]+;\n", body, re.MULTILINE))
    package_match = re.search(r"^package [^;]+;\n", body, re.MULTILINE)
    insert_at = imports[-1].end() if imports else package_match.end()
    return body[:insert_at] + "".join(import_lines) + body[insert_at:]


def _rewrite_121111_typed_nbt_contains(text: str) -> str:
    """Preserve old CompoundTag#contains(key, type) semantics on 1.21.11.

    Minecraft 1.21.11 removed the typed overload and kept only contains(key).
    Dropping the type check is not equivalent: migration code commonly uses it
    to distinguish legacy scalar tags from current compound/list payloads.
    """
    pattern = re.compile(
        r"(\w+)\.contains\(([^,\n()]+),\s*(?:net\.minecraft\.nbt\.)?Tag\.(TAG_[A-Z_]+)\)"
    )

    def replace(match: re.Match[str]) -> str:
        receiver, key, tag_type = match.groups()
        # TAG_ANY_NUMERIC existed on older targets but was removed from Tag in
        # 1.21.11. NbtCompat uses the numeric-type sentinel value 99.
        type_expr = "99" if tag_type == "TAG_ANY_NUMERIC" else f"Tag.{tag_type}"
        return f"NbtCompat.contains({receiver}, {key}, {type_expr})"

    return pattern.sub(replace, text)


def _apply_121111_nbt_compat(text: str) -> str:
    """Route old CompoundTag convenience getters through a 1.21.11 shim.

    1.21.11 made many CompoundTag getters optional-returning and removed a few
    old UUID/block-pos helpers. This pass keeps existing BCCE code readable in
    maintained sources while materializing a compatibility facade for
    the 1.21.11 target.
    """
    if not any(name in text for name in ("CompoundTag", "NbtUtils", "StringTag", "IntTag", "NumericTag", "ListTag")):
        return text
    changed = False
    patterns = {
        "getCompound": "getCompound",
        "getBoolean": "getBoolean",
        "getByteArray": "getByteArray",
        "getIntArray": "getIntArray",
        "getString": "getString",
        "getLong": "getLong",
        "getDouble": "getDouble",
        "getInt": "getInt",
        "getByte": "getByte",
        "getUUID": "getUUID",
        "hasUUID": "hasUUID",
    }
    for method, compat in patterns.items():
        before = text
        pattern = re.compile(
            rf"(?<![A-Za-z0-9_$\.])(\w+)\.{method}\(([^;\n()]*(?:\([^;\n()]*\)[^;\n()]*)*)\)"
        )

        def replace_getter(match: re.Match[str]) -> str:
            receiver = match.group(1)
            args = match.group(2).strip()
            # Entity/player getUUID() has no argument and is not a CompoundTag
            # getter. Rewriting it created broken calls such as
            # NbtCompat.getUUID(player, ).
            if not args:
                return match.group(0)
            # The target overlay may already use the compatibility facade directly. The pass must be
            # idempotent; otherwise NbtCompat.getX(tag, key) becomes NbtCompat.getX(NbtCompat, tag, key).
            if receiver == "NbtCompat":
                return match.group(0)
            # ValueInput uses Optional-returning accessors natively in 1.21.11.
            # Do not mistake its getIntArray call for an old CompoundTag getter.
            if receiver == "input" and method == "getIntArray":
                return match.group(0)
            return f"NbtCompat.{compat}({receiver}, {args})"

        text = pattern.sub(replace_getter, text)
        changed = changed or text != before

    before = text
    text = _rewrite_121111_typed_nbt_contains(text)
    text = re.sub(r"(\w+)\.putUUID\(([^,\n()]+),\s*([^;\n()]+)\)", r"NbtCompat.putUUID(\1, \2, \3)", text)
    text = text.replace("NbtUtils.writeBlockPos(", "NbtCompat.writeBlockPos(")
    text = text.replace("NbtUtils.readBlockPos(", "NbtCompat.readBlockPos(")
    text = text.replace("NbtUtils.loadUUID(", "NbtCompat.loadUUID(")
    text = text.replace("NbtUtils.createUUID(", "NbtCompat.createUUID(")
    text = text.replace("NbtUtils::writeBlockPos", "NbtCompat::writeBlockPos")
    text = text.replace("Direction::getNormal", "Direction::getUnitVec3i")
    text = re.sub(r"(\w+)\.getList\(([^,\n()]+),\s*(?:(?:net\.minecraft\.nbt\.)?Tag\.TAG_[A-Z_]+|\d+)\)", r"NbtCompat.getList(\1, \2)", text)
    text = re.sub(r"(NbtCompat\.getCompound\([^\n;]+?\))\.getList\(([^,\n()]+),\s*(?:(?:net\.minecraft\.nbt\.)?Tag\.TAG_[A-Z_]+|\d+)\)", r"NbtCompat.getList(\1, \2)", text)
    text = re.sub(r"((?:\([^)]+\)|\w+))\.getAllKeys\(\)", r"NbtCompat.getAllKeys(\1)", text)
    text = text.replace("((CompoundTag) destination).getAllKeys()", "NbtCompat.getAllKeys((CompoundTag) destination)")
    text = text.replace("((CompoundTag) source).getAllKeys()", "NbtCompat.getAllKeys((CompoundTag) source)")
    text = re.sub(r"(\w+)\.getAsString\(\)", r"NbtCompat.getString(\1)", text)
    text = re.sub(r"(\w+)\.getAsByte\(\)", r"NbtCompat.getByte(\1)", text)
    text = text.replace("StringTag::getAsString", "NbtCompat::getString")
    text = text.replace("IntTag::getAsInt", "NbtCompat::getInt")
    text = re.sub(r"\(\(NumericTag\)\s*([^\)]+)\)\.getAsByte\(\)", r"NbtCompat.getByte((NumericTag) \1)", text)
    text = re.sub(r"\(\(IntTag\)\s*([^\)]+)\)\.getAsInt\(\)", r"NbtCompat.getInt((IntTag) \1)", text)
    text = re.sub(r"\(\(StringTag\)\s*([^\)]+)\)\.getAsString\(\)", r"NbtCompat.getString((StringTag) \1)", text)
    text = re.sub(r"\(\(ByteTag\)\s*([^\)]+)\)\.getAsByte\(\)", r"NbtCompat.getByte((net.minecraft.nbt.NumericTag) \1)", text)
    # Defensive cleanup for malformed compatibility output.
    text = re.sub(r"NbtCompat\.getUUID\((\w+),\s*\)", r"\1.getUUID()", text)
    changed = changed or text != before

    if changed or "NbtCompat." in text:
        text = _ensure_java_import(text, "buildcraft.lib.compat.NbtCompat")
    return text


def _apply_121111_item_class_compat(text: str) -> str:
    """Replace removed concrete item subclasses with data-driven helpers."""
    if not any(name in text for name in ("ArmorItem", "SwordItem", "PickaxeItem", "Equipable")):
        return text
    changed = False
    for imp in (
        "import net.minecraft.world.item.ArmorItem;\n",
        "import net.minecraft.world.item.SwordItem;\n",
        "import net.minecraft.world.item.PickaxeItem;\n",
        "import net.minecraft.world.item.Equipable;\n",
    ):
        if imp in text:
            text = text.replace(imp, "")
            changed = True

    before = text
    text = re.sub(r"(\w+)\.getItem\(\)\s+instanceof\s+SwordItem", r"ItemCompat.isSword(\1)", text)
    text = re.sub(r"(\w+)\.getItem\(\)\s+instanceof\s+PickaxeItem", r"ItemCompat.isPickaxe(\1)", text)
    text = re.sub(r"(\w+)\.getItem\(\)\s+instanceof\s+ArmorItem\s+(\w+)", r"ItemCompat.isArmor(\1)", text)
    text = re.sub(r"(\w+)\.getItem\(\)\s+instanceof\s+ArmorItem", r"ItemCompat.isArmor(\1)", text)
    text = re.sub(r"\b\w+\.getDefense\(\)", "ItemCompat.getArmorDefense(wearable)", text)
    changed = changed or text != before

    if changed or "ItemCompat." in text:
        text = _ensure_java_import(text, "buildcraft.lib.compat.ItemCompat")
    return text


def _apply_121111_eventbus_compat(text: str) -> str:
    # NeoForge 21.11 removed the nested Bus selector from @EventBusSubscriber.
    return re.sub(r",\s*bus\s*=\s*EventBusSubscriber\.Bus\.MOD", "", text)

def upgrade_symbols(text: str, *, minecraft: str, relative: str) -> str:
    """Apply tiny official-name compatibility renames after Stonecutter preprocessing.

    Minecraft 1.21.11 performed a broad Mojang-mapping/API rename shuffle while
    retaining many runtime concepts. Keeping mechanical renames here avoids
    forking hundreds of otherwise identical BCCE Java sources just to spell names
    differently between 1.21.1 and 1.21.11.
    """
    if not relative.endswith(".java") or _version_tuple(minecraft) < _version_tuple("1.21.11"):
        return text

    # Normalize line endings before regex/header surgery. Maintained sources may
    # use CRLF on Windows, while package/import rewrites operate on LF boundaries.
    # Effective sources are generated artifacts, so LF output keeps materialization deterministic.
    text = text.replace("\r\n", "\n").replace("\r", "\n")

    # NeoForge 21.11 no longer performs runtime member/class stripping for @OnlyIn.
    # Keeping the legacy annotations therefore provides no side-safety and makes NeoForge emit a
    # loading warning for buildcraftlib. Strip them only from the generated 1.21.11+ sources; older
    # older targets keep their version-specific annotations/behaviour. Real client-only registration remains
    # controlled by the existing dist/event wiring rather than annotations that are now inert.
    text = re.sub(r"(?m)^[ \t]*@OnlyIn\(Dist\.[A-Z_]+\)[ \t]*\n", "", text)
    text = text.replace("import net.neoforged.api.distmarker.OnlyIn;\n", "")
    text = text.replace("import net.minecraftforge.api.distmarker.OnlyIn;\n", "")
    if "Dist." not in text:
        text = text.replace("import net.neoforged.api.distmarker.Dist;\n", "")
        text = text.replace("import net.minecraftforge.api.distmarker.Dist;\n", "")

    # GuiGraphics in 1.21.11 interprets integer colours as ARGB. Legacy BuildCraft GUI code
    # frequently passes 24-bit RGB values, which therefore become fully transparent. Keep this
    # target-specific so older versions retain their original colour handling.
    text = text.replace("0x404040, false)", "0xFF404040, false)")
    text = text.replace("0x20_20_20, false)", "0xFF_20_20_20, false)")

    # BlockStateBase#isSolidRender no longer takes BlockGetter/BlockPos in 1.21.11.
    # Apply this before the native-model early return below: the target-specific facade/item
    # bridges intentionally skip the generic rename pass, but still need this API-shape change.
    text = re.sub(
        r"(?P<state>\b(?:[A-Za-z_][A-Za-z0-9_]*\.)*[A-Za-z_][A-Za-z0-9_]*)"
        r"\.isSolidRender\(new SingleBlockAccess\((?P=state)\), BlockPos\.ZERO\)",
        r"\g<state>.isSolidRender()",
        text,
    )


    replacements = (
        ("net.minecraft.resources.ResourceLocation", "net.minecraft.resources.Identifier"),
        ("net.minecraft.ResourceLocationException", "net.minecraft.IdentifierException"),
        ("net.minecraft.advancements.critereon", "net.minecraft.advancements.criterion"),
        ("net.minecraft.Util", "net.minecraft.util.Util"),
        ("net.minecraft.BlockUtil", "net.minecraft.util.BlockUtil"),
        ("net.minecraft.FileUtil", "net.minecraft.util.FileUtil"),
        ("com.mojang.blaze3d.platform.GlStateManager", "com.mojang.blaze3d.opengl.GlStateManager"),
        ("net.minecraft.world.level.block.state.properties.DirectionProperty", "net.minecraft.world.level.block.state.properties.EnumProperty"),
        ("net.minecraft.world.ItemInteractionResult", "net.minecraft.world.InteractionResult"),
        ("net.minecraft.world.entity.projectile.AbstractArrow", "net.minecraft.world.entity.projectile.arrow.AbstractArrow"),
        ("net.minecraft.world.entity.projectile.SpectralArrow", "net.minecraft.world.entity.projectile.arrow.SpectralArrow"),
        ("net.minecraft.world.entity.projectile.ThrownTrident", "net.minecraft.world.entity.projectile.arrow.ThrownTrident"),
        ("net.minecraft.world.entity.vehicle.AbstractMinecartContainer", "net.minecraft.world.entity.vehicle.minecart.AbstractMinecartContainer"),
        ("net.minecraft.world.entity.vehicle.AbstractMinecart", "net.minecraft.world.entity.vehicle.minecart.AbstractMinecart"),
        ("net.minecraft.world.entity.animal.horse", "net.minecraft.world.entity.animal.equine"),
        ("net.minecraft.world.entity.animal.Cat", "net.minecraft.world.entity.animal.feline.Cat"),
        ("net.minecraft.world.entity.animal.Ocelot", "net.minecraft.world.entity.animal.feline.Ocelot"),
        ("net.minecraft.world.entity.animal.Wolf", "net.minecraft.world.entity.animal.wolf.Wolf"),
        ("net.minecraft.world.level.GameRules", "net.minecraft.world.level.gamerules.GameRules"),
        ("net.minecraft.world.item.UseAnim", "net.minecraft.world.item.ItemUseAnimation"),
        ("net.minecraft.client.renderer.RenderType", "net.minecraft.client.renderer.rendertype.RenderType;\nimport net.minecraft.client.renderer.rendertype.RenderTypes"),
        ("net.neoforged.neoforge.client.model.data", "net.neoforged.neoforge.model.data"),
        ("net.neoforged.neoforge.client.event.ModelEvent.RegisterAdditional", "net.neoforged.neoforge.client.event.ModelEvent.RegisterStandalone"),
        ("net.neoforged.neoforge.client.event.ModelEvent.RegisterGeometryLoaders", "net.neoforged.neoforge.client.event.ModelEvent.RegisterLoaders"),
        ("RegisterAdditional", "RegisterStandalone"),
        ("RegisterGeometryLoaders", "RegisterLoaders"),
        ("ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION", "InteractionResult.TRY_WITH_EMPTY_HAND"),
        ("ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION", "InteractionResult.PASS"),
        ("ItemInteractionResult.CONSUME_PARTIAL", "InteractionResult.CONSUME"),
        ("ItemInteractionResult.", "InteractionResult."),
        ("RenderType.cutoutMipped()", "RenderTypes.cutout()"),
        ("RenderType.cutout()", "RenderTypes.cutout()"),
        ("RenderType.translucent()", "RenderTypes.translucent()"),
        ("RenderType.solid()", "RenderTypes.solid()"),
        ("RenderType.entityCutoutNoCull(", "RenderTypes.entityCutoutNoCull("),
        ("RenderType.entityTranslucent(", "RenderTypes.entityTranslucent("),
        ("FMLEnvironment.dist", "FMLEnvironment.getDist()"),
        ("Capabilities.ItemHandler.BLOCK", "CapUtil.CAP_ITEMS"),
        ("Capabilities.FluidHandler.BLOCK", "CapUtil.CAP_FLUIDS"),
        ("Capabilities.EnergyStorage.BLOCK", "CapUtil.CAP_FE"),
        ("BlockEntityType.Builder.of(", "BlockEntityTypeCompat.create("),
        (".build(null)", ""),
        (".getNormal()", ".getUnitVec3i()"),
        ("Camera.getPosition()", "Camera.position()"),
        ("RenderTypes.cutout()", "RenderCompat.cutout()"),
        ("RenderTypes.solid()", "RenderCompat.solid()"),
        ("RenderTypes.translucentMovingBlock()", "RenderCompat.translucent()"),
        ("RenderTypes.translucent()", "RenderCompat.translucent()"),
    )
    for before, after in replacements:
        text = text.replace(before, after)
    for before, after in NEOFORGE_121111_SHIM_RELOCATIONS:
        text = text.replace(before, after)
    for before, after in MINECRAFT_121111_SHIM_RELOCATIONS:
        text = text.replace(before, after)

    # 1.21.11 removed/renamed several concrete item/entity packages and old event bus selectors.
    text = _apply_121111_eventbus_compat(text)
    text = re.sub(r"(?<![A-Za-z0-9_$])(\w+)\.isClientSide(?!\s*\()", r"\1.isClientSide()", text)
    text = re.sub(r"\.isClientSide(?![A-Za-z0-9_$\(])", ".isClientSide()", text)
    text = text.replace("InteractionResult.sidedSuccess(world.isClientSide())", "(world.isClientSide() ? InteractionResult.SUCCESS : InteractionResult.SUCCESS_SERVER)")
    text = text.replace("InteractionResult.sidedSuccess(level.isClientSide())", "(level.isClientSide() ? InteractionResult.SUCCESS : InteractionResult.SUCCESS_SERVER)")
    text = re.sub(r"InteractionResult\.sidedSuccess\(([^,;]+?\.isClientSide\(\))\)", r"(\1 ? InteractionResult.SUCCESS : InteractionResult.SUCCESS_SERVER)", text)
    text = text.replace("Direction.fromDelta(delta.getX(), delta.getY(), delta.getZ())", "Direction.getNearest(delta, Direction.NORTH)")
    text = text.replace("Direction.fromDelta(", "Direction.getNearest(")
    text = text.replace("CustomizeGuiOverlayEvent.DebugText", "CustomizeGuiOverlayEvent")
    text = re.sub(r"(?m)^[ \t]*import net\.minecraft\.world\.item\.ItemUseAnimation;\n", "", text)
    text = text.replace("RenderSystem.blendFunc(SourceFactor.SRC_ALPHA, DestFactor.ONE_MINUS_SRC_ALPHA);", "RenderSystem.defaultBlendFunc();")
    text = text.replace("import com.mojang.blaze3d.opengl.GlStateManager.DestFactor;\n", "")
    text = text.replace("import com.mojang.blaze3d.opengl.GlStateManager.SourceFactor;\n", "")
    text = text.replace("import com.mojang.blaze3d.platform.GlStateManager.DestFactor;\n", "")
    text = text.replace("import com.mojang.blaze3d.platform.GlStateManager.SourceFactor;\n", "")
    text = text.replace("InventoryMenu.BLOCK_ATLAS", "net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS")
    text = text.replace("Minecraft.getInstance().getModelManager().getAtlas(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS).getSprite(", "RenderCompat.blockSprites().apply(")
    text = text.replace("Minecraft.getInstance().getModelManager().getAtlas(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS)", "RenderCompat.blockSprites()")
    text = text.replace("Minecraft.getInstance().getTextureAtlas(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS).apply(", "RenderCompat.blockSprites().apply(")
    text = text.replace("Minecraft.getInstance().getTextureAtlas(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS)", "RenderCompat.blockSprites()")
    text = text.replace("Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)", "RenderCompat.blockSprites()")
    text = re.sub(r"Minecraft\.getInstance\(\)\s*\.\s*getTextureAtlas\(net\.minecraft\.client\.renderer\.texture\.TextureAtlas\.LOCATION_BLOCKS\)", "RenderCompat.blockSprites()", text)
    text = re.sub(r"Minecraft\.getInstance\(\)\s*\.\s*getTextureAtlas\(TextureAtlas\.LOCATION_BLOCKS\)", "RenderCompat.blockSprites()", text)
    text = text.replace("TextureAtlas map = RenderCompat.blockSprites();", "java.util.function.Function<Identifier, TextureAtlasSprite> map = RenderCompat.blockSprites();")
    text = text.replace("TextureAtlas atlas = RenderCompat.blockSprites();", "java.util.function.Function<Identifier, TextureAtlasSprite> atlas = RenderCompat.blockSprites();")
    text = text.replace("holder.refresh(atlas);", "holder.refresh(atlas::getSprite);")
    text = text.replace("holder.refresh(RenderCompat.blockSprites());", "holder.refresh(RenderCompat.blockSprites());")
    text = text.replace("private void refresh(TextureAtlas atlas)", "private void refresh(java.util.function.Function<Identifier, TextureAtlasSprite> atlas)")
    text = text.replace("sprite = atlas.getSprite(spriteLocation);", "sprite = atlas.apply(spriteLocation);")
    text = text.replace("current = atlas.getSprite(spriteLocation);", "current = atlas.apply(spriteLocation);")
    text = text.replace("GlStateManager._bindTexture(map.getId());", "// 1.21.11 texture binding is handled by render passes")
    text = text.replace(".dimension().location()", ".dimension().identifier()")
    text = text.replace("player.getGameProfile().getId()", "player.getUUID()")
    text = text.replace("player.getGameProfile().getName()", "GameProfileCompat.name(player.getGameProfile())")
    text = re.sub(r"([A-Za-z0-9_().]+)\.getGameProfile\(\)\.getName\(\)", r"GameProfileCompat.name(\1.getGameProfile())", text)
    text = re.sub(r"([A-Za-z0-9_().]+)\.getGameProfile\(\)\.getId\(\)", r"GameProfileCompat.id(\1.getGameProfile())", text)
    if "GameProfile" in text:
        text = re.sub(r"(?<![A-Za-z0-9_$])(owner|profile)\.getId\(\)", r"GameProfileCompat.id(\1)", text)
        text = re.sub(r"(?<![A-Za-z0-9_$])(owner|profile)\.getName\(\)", r"GameProfileCompat.name(\1)", text)
    if "GameProfileCompat." in text:
        text = _ensure_java_import(text, "buildcraft.lib.compat.GameProfileCompat")

    text = text.replace("BuiltInRegistries.BLOCK.get(Identifier.parse(NbtCompat.getString(nbt, \"block\")))", "BuiltInRegistries.BLOCK.get(Identifier.parse(NbtCompat.getString(nbt, \"block\"))).map(net.minecraft.core.Holder.Reference::value).orElse(net.minecraft.world.level.block.Blocks.AIR)")
    text = text.replace("BuiltInRegistries.BLOCK.get(Identifier.parse(buf.readUtf(1024)))", "BuiltInRegistries.BLOCK.get(Identifier.parse(buf.readUtf(1024))).map(net.minecraft.core.Holder.Reference::value).orElse(net.minecraft.world.level.block.Blocks.AIR)")
    text = re.sub(r"(?m)^(\s*)((?!NbtCompat\b)\w+)\.putUUID\(([^,]+),\s*([^;]+)\);", r"\1NbtCompat.putUUID(\2, \3, \4);", text)
    text = text.replace("NbtCompat.putUUID(NbtCompat, ", "NbtCompat.putUUID(")
    text = text.replace("BuiltInRegistries.BLOCK.get(Identifier.parse(NbtCompat.getString(nbt, \"block\").map(net.minecraft.core.Holder.Reference::value).orElse(net.minecraft.world.level.block.Blocks.AIR)))", "BuiltInRegistries.BLOCK.get(Identifier.parse(NbtCompat.getString(nbt, \"block\"))).map(net.minecraft.core.Holder.Reference::value).orElse(net.minecraft.world.level.block.Blocks.AIR)")
    text = text.replace("BuiltInRegistries.BLOCK.get(Identifier.parse(buf.readUtf(1024).map(net.minecraft.core.Holder.Reference::value).orElse(net.minecraft.world.level.block.Blocks.AIR)))", "BuiltInRegistries.BLOCK.get(Identifier.parse(buf.readUtf(1024))).map(net.minecraft.core.Holder.Reference::value).orElse(net.minecraft.world.level.block.Blocks.AIR)")
    # NeoForge 1.21.11 added the breaking tool stack to onDestroyedByPlayer. Preserve BuildCraft's
    # selected-part removal and tile teardown paths instead of replacing the superclass result with a constant.

    text = re.sub(r"(?m)^\s*super\.onRemove\([^;]+;\n", "", text)
    text = re.sub(r"(?m)^\s*super\.onBlockExploded\([^;]+;\n", "", text)
    text = re.sub(r"(?m)^\s*super\.wasExploded\([^;]+;\n", "", text)
    text = re.sub(r"(?m)^\s*super\.neighborChanged\([^;]+;\n", "", text)
    text = text.replace("super.getOcclusionShape(p_60578_, p_60579_, p_60580_)", "super.getOcclusionShape(p_60578_)")
    text = re.sub(r"(?m)^\s*return\s+super\.onRemove\([^;]+;\n", "", text)
    # Most BuildCraft block entities implement the CompoundTag persistence hook through TileBC_Neptune.
    # Preserve superclass calls for those inheritance chains; direct vanilla BlockEntity subclasses use
    # the target-specific persistence signatures instead.
    text = re.sub(r"(?m)^\s*\S+\.getChunkAt\([^)]+\)\.setUnsaved\(true\);\n", "", text)
    text = re.sub(r"([A-Za-z0-9_().]+)\.getProfiler\(\)\.push\(([^;]+)\);", r"LevelCompat.profilerPush(\1, \2);", text)
    text = re.sub(r"([A-Za-z0-9_().]+)\.getProfiler\(\)\.popPush\(([^;]+)\);", r"LevelCompat.profilerPopPush(\1, \2);", text)
    text = re.sub(r"([A-Za-z0-9_().]+)\.getProfiler\(\)\.pop\(\);", r"LevelCompat.profilerPop(\1);", text)
    if "LevelCompat." in text:
        text = _ensure_java_import(text, "buildcraft.lib.compat.LevelCompat")
    text = re.sub(r"RenderSystem\.setShaderTexture\(([^;]+)\);", r"RenderCompat.setShaderTexture(\1);", text)
    if "RenderCompat." in text:
        text = _ensure_java_import(text, "buildcraft.lib.compat.RenderCompat")


    # NeoForge 1.21.11 changed RenderLevelStageEvent from the old enum-stage
    # event into typed sub-events. The 1.21.1 bridge still uses getStage(),
    # getProjectionMatrix() and getPartialTick(), which no longer exist there.
    # Keep the detached world render hook registered, but bind it directly to
    # the typed AfterTranslucentBlocks stage and use the model-view matrix that
    # the new event still exposes.


    if "BlockEntityTypeCompat." in text:
        text = _ensure_java_import(text, "buildcraft.lib.compat.BlockEntityTypeCompat")

    text = text.replace("camera.getPosition()", "LevelCompat.cameraPosition(camera)")
    text = text.replace("fluidStack.saveOptional(ItemStackUtil.requireActiveRegistryProvider())", "FluidCompat.saveOptional(fluidStack, ItemStackUtil.requireActiveRegistryProvider())")
    text = re.sub(r"\b(fluidStack|serverFluid|fluid)\.getDisplayName\(\)", r"FluidCompat.getDisplayName(\1)", text)
    text = re.sub(r"\b(fluidStack|serverFluid|fluid)\.getTranslationKey\(\)", r"FluidCompat.getTranslationKey(\1)", text)
    text = text.replace("player.hasPermissions(2)", "player.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)")
    text = re.sub(r"([A-Za-z0-9_().]+)\.getMinBuildHeight\(\)", r"LevelCompat.getMinBuildHeight(\1)", text)
    text = re.sub(r"([A-Za-z0-9_().]+)\.getOwner\(\)\.getId\(\)", r"GameProfileCompat.id(\1.getOwner())", text)
    text = re.sub(r"([A-Za-z0-9_().]+)\.getOwner\(\)\.getName\(\)", r"GameProfileCompat.name(\1.getOwner())", text)
    text = text.replace("getOwner().getId()", "GameProfileCompat.id(getOwner())")
    text = text.replace("getOwner().getName()", "GameProfileCompat.name(getOwner())")
    text = re.sub(r"\b(ownerProfile|owner|profile)\.getId\(\)", r"GameProfileCompat.id(\1)", text)
    text = re.sub(r"\b(ownerProfile|owner|profile)\.getName\(\)", r"GameProfileCompat.name(\1)", text)
    text = text.replace("Entity::kill", "entity -> entity.kill(null)")
    text = text.replace("RenderSystem.setShader(GameRenderer::getPositionTexShader);", "")
    text = text.replace("RenderSystem.setShaderColor(1, 1, 1, 1);", "RenderCompat.setShaderColor(1, 1, 1, 1);")
    text = text.replace("RenderSystem.enableDepthTest();", "RenderCompat.enableDepthTest();")
    text = text.replace("RenderSystem.disableBlend();", "RenderCompat.disableBlend();")
    text = text.replace("guiGraphics.pose()", "RenderCompat.pose(guiGraphics)")
    text = text.replace("new DynamicTexture(width * scale, height * scale, false)", "RenderCompat.newDynamicTexture(width * scale, height * scale, false)")
    text = text.replace("new DynamicTexture(width * scale, height * scale, true)", "RenderCompat.newDynamicTexture(width * scale, height * scale, true)")
    text = re.sub(r"new DynamicTexture\(([^,;]+),\s*([^,;]+),\s*(true|false)\)", r"RenderCompat.newDynamicTexture(\1, \2, \3)", text)
    text = text.replace("bpc.getItemRenderer()", "Minecraft.getInstance().getItemRenderer()")
    text = text.replace("context.getItemRenderer()", "Minecraft.getInstance().getItemRenderer()")
    text = text.replace("itemRenderer.renderStatic(", "RenderCompat.renderStatic(itemRenderer, ")
    text = text.replace("BlockEntityRenderer.super.shouldRender(p_173568_, p_173569_)", "true")
    text = text.replace("InputConstants.getKey(keyCode, scanCode)", "RenderCompat.inputKey(keyCode, scanCode)")
    text = text.replace("InputConstants.getKey(a, b)", "RenderCompat.inputKey(a, b)")
    text = text.replace('CycleButton.booleanBuilder(Component.translatable("block.architect.allowCreative"), Component.translatable("block.architect.noallowCreative"))',
                        'CycleButton.booleanBuilder(Component.translatable("block.architect.allowCreative"), Component.translatable("block.architect.noallowCreative"), false)')
    # GuiBC8/ContainerScreenBase expose the old 1.21.1 input overloads on 1.21.11 and bridge the
    # native MouseButtonEvent/KeyEvent API back into them. Preserve super.oldStyle(...) calls in their
    # subclasses so machine-specific controls still reach the BuildCraft base handler before vanilla slots.
    legacy_gui_input_bridge = "extends GuiBC8<" in text or "extends ContainerScreenBase<" in text
    if not legacy_gui_input_bridge:
        text = re.sub(r"super\.mouseClicked\([^;]+?\)", "false", text)
        text = re.sub(r"super\.mouseDragged\([^;]+?\)", "false", text)
        text = re.sub(r"super\.mouseReleased\([^;]+?\)", "false", text)
        text = re.sub(r"super\.keyPressed\((?!event\))[^;]+?\)", "false", text)
    # Adapt child widget calls, but never rewrite the Java 'super' receiver.
    text = re.sub(r"\b(?!super\b)(?!RenderCompat\b)(\w+)\.mouseClicked\(([^;]+?)\)", r"RenderCompat.mouseClicked(\1, \2)", text)
    text = re.sub(r"\b(?!super\b)(?!RenderCompat\b)(\w+)\.keyPressed\(([^;]+?)\)", r"RenderCompat.keyPressed(\1, \2)", text)
    text = text.replace("Screen.hasShiftDown()", "false")
    text = re.sub(r"guiGraphics\.renderTooltip\(([^;]+?)\);", r"RenderCompat.renderTooltip(guiGraphics, \1);", text)
    text = re.sub(r"guiGraphics\.blit\(([^;]+?)\);", r"RenderCompat.blit(guiGraphics, \1);", text)
    text = text.replace("BlockItem.setBlockEntityData(itemstack, BlockEntityType.BANNER, compoundtag);", "")
    text = text.replace("NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(),", "NbtUtils.readBlockState(BuiltInRegistries.BLOCK,")
    # Additional 1.21.11 compile bridges for APIs that moved to events/value IO.

    # Datagen is disabled for 1.21.11; strip legacy GatherData bodies and listeners
    # from the runtime compile path because those RecipeProvider APIs are unavailable.
    text = re.sub(r"(?m)^\s*modEventBus\.addListener\(this::gatherData\);\n", "", text)
    text = re.sub(r"(?m)^\s*eventBus\.addListener\(this::gatherData\);\n", "", text)
    text = re.sub(
        r"(?ms)^[ \t]*(?:public[ \t]+)?(?:static[ \t]+)?void[ \t]+gatherData\s*\(\s*GatherDataEvent\s+event\s*\)\s*\{.*?^[ \t]*\}\n",
        "",
        text,
    )

    # Old GUI button/event helpers do not line up with the 1.21.11 event objects.
    text = text.replace(".withInitialValue(isCreative)\n", "")
    text = text.replace("this.RenderCompat.", "RenderCompat.")

    # Fake client snapshot worlds are preview-only helpers on this target.

    # SchematicEntityDefault has a native ValueInput/ValueOutput target overlay.

    # 1.21.11 runtime sources do not register datagen/provider events.
    text = text.replace("Capabilities.FluidHandler.ITEM", "Capabilities.Fluid.ITEM")

    # Optional NBT float getter.
    text = re.sub(r"(?<![A-Za-z0-9_$\.])(\w+)\.getFloat\(([^;\n()]+)\)", r"NbtCompat.getFloat(\1, \2)", text)

    # NeighborChanged gained Orientation; null keeps the notification as a compile bridge.
    text = re.sub(r"level\.neighborChanged\(([^;]+?),\s*worldPosition\);", r"level.neighborChanged(\1, null);", text)

    text = text.replace("player.serverLevel()", "((net.minecraft.server.level.ServerLevel) player.level())")
    text = text.replace("LivingEntity.getSlotForHand(hand)", "(hand == InteractionHand.MAIN_HAND ? net.minecraft.world.entity.EquipmentSlot.MAINHAND : net.minecraft.world.entity.EquipmentSlot.OFFHAND)")
    text = text.replace("new InteractionResultHolder<>(onItemRightClickVolumeBoxes(world, player), player.getItemInHand(hand))", "onItemRightClickVolumeBoxes(world, player)")
    text = text.replace("clearMarkerData(stack).getResult()", "clearMarkerData(stack)")
    text = text.replace("clearMarkerData(StackUtil.asNonNull(ctx.getItemInHand())).getResult()", "clearMarkerData(StackUtil.asNonNull(ctx.getItemInHand()))")
    text = re.sub(r"new\s+InteractionResult\(\s*InteractionResult\.SUCCESS\s*,\s*[^;]+?\)", "InteractionResult.SUCCESS", text)
    text = text.replace("return this.getDescription();", "return Component.translatable(getDescriptionId());")

    # Disable old 1.21.1 model bake hooks that no longer match NeoForge 1.21.11.
    text = text.replace(".withInitialValue(needMaterial)", "")
    text = re.sub(r"(\w+)\.serverLevel\(\)", r"((net.minecraft.server.level.ServerLevel) \1.level())", text)
    text = text.replace("return new InteractionResult(InteractionResult.SUCCESS, player.getItemInHand(hand));", "return InteractionResult.SUCCESS;")

    # NBT block-pos bridge after all NBT rewrites have run.
    text = re.sub(
        r"return\s+NbtCompat\.readBlockPos\(parent, key\)\s*\.or\(\(\) -> tryReadBlockPos\(parent\.get\(key\)\)\)\s*\.orElse\(BlockPos\.ZERO\);",
        "return NbtCompat.readBlockPos(parent, key);",
        text,
        flags=re.DOTALL,
    )


    text = text.replace("NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(),", "NbtUtils.readBlockState(BuiltInRegistries.BLOCK,")
    text = text.replace("NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(),", "NbtUtils.readBlockState(BuiltInRegistries.BLOCK,")
    text = re.sub(r'NbtUtils\.readBlockState\(BuiltInRegistries\.BLOCK\.asLookup\(\),\s*NbtCompat\.getCompound\(([^,]+),\s*"(blockState|state)"\)\)', r'NbtUtils.readBlockState(BuiltInRegistries.BLOCK, NbtCompat.getCompound(\1, "\2"))', text)

    if "FluidCompat." in text:
        text = _ensure_java_import(text, "buildcraft.lib.compat.FluidCompat")
    if "GameProfileCompat." in text:
        text = _ensure_java_import(text, "buildcraft.lib.compat.GameProfileCompat")

    # Keep old class names that merely contain ResourceLocation, e.g.
    # ModelResourceLocation. Only the standalone simple name was renamed.
    text = _replace_java_identifier(text, "ResourceLocationException", "IdentifierException")
    text = _replace_java_identifier(text, "ResourceLocation", "Identifier")
    text = _replace_java_identifier(text, "resourceLocation", "identifier")
    text = _replace_java_identifier(text, "DirectionProperty", "EnumProperty<Direction>")
    text = _replace_java_identifier(text, "ItemInteractionResult", "InteractionResult")

    # LootContextParams still exists as a constants holder; do not turn it into
    # the non-existent ContextKeys by a substring replacement. The singular
    # LootContextParam import was unused in the affected BCCE sources.
    text = text.replace("import net.minecraft.world.level.storage.loot.parameters.LootContextParam;\n", "")

    # Only old one-generic BlockEntityRenderer declarations need the compatibility bridge.
    # Native 1.21.11 renderers use BlockEntityRenderer<T, S> and must retain the submit API.
    text = re.sub(
        r"implements\s+BlockEntityRenderer<([^>,]+)>",
        r"implements buildcraft.lib.client.render.compat.LegacyBlockEntityRenderer<\1>",
        text,
    )

    # Only old one-generic EntityRenderer declarations need the compatibility bridge.
    # Native 1.21.11 renderers use EntityRenderer<T, S> and must retain extractRenderState/submit.
    text = re.sub(
        r"extends\s+EntityRenderer<([^>,]+)>",
        r"extends buildcraft.lib.client.render.compat.LegacyEntityRenderer<\1>",
        text,
    )

    text = text.replace("RecipeBookMenu<CraftingInput, CraftingRecipe>", "RecipeBookMenu")
    text = text.replace("net.minecraft.client.ParticleStatus", "net.minecraft.server.level.ParticleStatus")



    text = re.sub(r"return\s+switch\s*\(result\)\s*\{\s*case SUCCESS -> InteractionResult\.SUCCESS;\s*case CONSUME -> InteractionResult\.CONSUME;\s*case CONSUME_PARTIAL -> InteractionResult\.CONSUME;\s*case FAIL -> InteractionResult\.FAIL;\s*case PASS -> InteractionResult\.PASS;\s*case SUCCESS_NO_ITEM_USED -> InteractionResult\.TRY_WITH_EMPTY_HAND;\s*\};", "return result;", text, flags=re.DOTALL)
    text = re.sub(r"(?m)^[ \t]*@Override[ \t]*\n", "", text)
    text = _apply_121111_item_class_compat(text)
    text = _apply_121111_nbt_compat(text)
    text = text.replace("NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(),", "NbtUtils.readBlockState(BuiltInRegistries.BLOCK,")
    text = re.sub(r'NbtUtils\.readBlockState\(BuiltInRegistries\.BLOCK\.asLookup\(\),\s*NbtCompat\.getCompound\(([^,]+),\s*"(blockState|state)"\)\)', r'NbtUtils.readBlockState(BuiltInRegistries.BLOCK, NbtCompat.getCompound(\1, "\2"))', text)
    text = text.replace("BuiltInRegistries.BLOCK.get(Identifier.parse(NbtCompat.getString(nbt, \"block\")))", "BuiltInRegistries.BLOCK.get(Identifier.parse(NbtCompat.getString(nbt, \"block\"))).map(net.minecraft.core.Holder.Reference::value).orElse(net.minecraft.world.level.block.Blocks.AIR)")
    text = text.replace("BuiltInRegistries.BLOCK.get(Identifier.parse(buf.readUtf(1024)))", "BuiltInRegistries.BLOCK.get(Identifier.parse(buf.readUtf(1024))).map(net.minecraft.core.Holder.Reference::value).orElse(net.minecraft.world.level.block.Blocks.AIR)")
    text = re.sub(r"(?m)^(\s*)((?!NbtCompat\b)\w+)\.putUUID\(([^,]+),\s*([^;]+)\);", r"\1NbtCompat.putUUID(\2, \3, \4);", text)
    text = text.replace("NbtCompat.putUUID(NbtCompat, ", "NbtCompat.putUUID(")
    text = _apply_121111_item_use_result_renames(text)

    text = re.sub(
        r"return\s+NbtCompat\.readBlockPos\(parent, key\)\s*\.or\(\(\) -> tryReadBlockPos\(parent\.get\(key\)\)\)\s*\.orElse\(BlockPos\.ZERO\);",
        "return NbtCompat.readBlockPos(parent, key);",
        text,
        flags=re.DOTALL,
    )
    text = text.replace("return new InteractionResult(InteractionResult.SUCCESS, player.getItemInHand(hand));", "return InteractionResult.SUCCESS;")



    # Additional target-specific 1.21.11 compatibility rewrites.



    # The shard is read-only and consumed when drained; the ItemAccess transaction
    # owns replacement/consumption instead of a detached legacy ItemStack wrapper.

    # Energy datagen provider is nested inside BCEnergyRecipes; keep runtime recipe
    # registration but strip the old RecipeProvider implementation from main compile.

    # 1.21.11 API compatibility rewrites.
    text = text.replace("@Override public", "public")
    text = text.replace(".noCollission()", ".noCollision()")
    text = text.replace("ResourceKey::location", "ResourceKey::identifier")
    text = re.sub(r"resourceKey -> resourceKey\.location\(\)", "resourceKey -> resourceKey.identifier()", text)
    text = re.sub(r"(\w+)\.getMaxBuildHeight\(\)", r"LevelCompat.getMaxBuildHeight(\1)", text)
    text = text.replace("world.dimensionType().effectsLocation().toString()", '"minecraft:overworld"')
    text = text.replace("RenderSystem.enableBlend();", "RenderCompat.enableBlend();")
    text = text.replace("RenderSystem.defaultBlendFunc();", "RenderCompat.defaultBlendFunc();")
    text = text.replace("RenderSystem.disableDepthTest();", "RenderCompat.disableDepthTest();")
    text = text.replace("RenderSystem.setShaderColor(1, 1, 1, 0.65f);", "RenderCompat.setShaderColor(1, 1, 1, 0.65f);")
    text = re.sub(r"RenderSystem\.setShaderColor\(([^;]+)\);", r"RenderCompat.setShaderColor(\1);", text)
    text = text.replace("RenderSystem.disableScissor();", "RenderCompat.disableScissor();")
    text = re.sub(r"RenderSystem\.enableScissor\(([^;]+)\);", r"RenderCompat.enableScissor(\1);", text)
    text = text.replace("Lighting.setupFor3DItems();", "RenderCompat.setupFor3DItems();")
    text = text.replace("Lighting.setupForFlatItems();", "RenderCompat.setupForFlatItems();")
    # GuiGraphics#fillGradient lost the z parameter. Remove the fifth argument for the old 7-arg calls.
    text = re.sub(
        r"guiGraphics\.fillGradient\(([^,;]+),\s*([^,;]+),\s*([^,;]+),\s*([^,;]+),\s*([^,;]+),\s*([^,;]+),\s*([^;]+)\);",
        r"guiGraphics.fillGradient(\1, \2, \3, \4, \6, \7);",
        text,
    )

    # Stone engine fuel queries use ItemCompat so shared code does not depend on
    # target-specific fuel helper signatures.
    text = text.replace("b.getBurnTime(RecipeType.SMELTING) > 0", "ItemCompat.getBurnTime(b) > 0")
    text = text.replace("itemstack.getBurnTime(RecipeType.SMELTING)", "ItemCompat.getBurnTime(itemstack)")
    text = text.replace("fuel.getCraftingRemainingItem()", "ItemCompat.getCraftingRemainingItem(fuel)")

    # Owner name rewrite missed nested fields in TileSpringOil.
    text = text.replace("info.GameProfileCompat.name(profile)", "GameProfileCompat.name(info.profile)")

    # MutableQuad must remain fully functional on 1.21.11.  The relocated
    # buildcraft.lib.compat.mc121111 BakedQuad deliberately preserves the old
    # int[] vertex layout, so neutering toBaked*/fromBaked* destroys all
    # BuildCraft-generated geometry (most visibly every pipe body).


    # BCFluid's vanilla overrides changed to ServerLevel signatures in 1.21.11.


    # 1.21.11 API-shape compatibility for NBT, registries, rendering, recipes and GUI calls.
    text = _rewrite_121111_typed_nbt_contains(text)
    text = text.replace("level.neighborChanged(getBlockState(), currentPos.offset(side.getUnitVec3i()), BCFactoryBlocks.FLOOD_GATE_BLOCK.get(),\n                                    currentPos, false);", "level.neighborChanged(getBlockState(), currentPos.offset(side.getUnitVec3i()), BCFactoryBlocks.FLOOD_GATE_BLOCK.get(),\n                                    null, false);")
    text = text.replace("level.neighborChanged(worldPosition.relative(previousDirection), sourceBlock, worldPosition);", "level.neighborChanged(worldPosition.relative(previousDirection), sourceBlock, null);")
    text = text.replace("level.neighborChanged(worldPosition.relative(current), sourceBlock, worldPosition);", "level.neighborChanged(worldPosition.relative(current), sourceBlock, null);")
    text = text.replace("FMLEnvironment.production", "false")
    text = text.replace("guiGraphics.renderComponentTooltip(", "RenderCompat.renderComponentTooltip(guiGraphics, ")
    # Minecraft#getProfiler was removed after 1.21.1. 1.21.11 uses the
    # thread-local static profiler exposed by Profiler#get. Do not route this
    # through LevelCompat: that bridge intentionally has no ProfilerFiller
    # accessor and rewriting overlay sources back to LevelCompat.profiler()
    # makes prepareEffectiveSource generate uncompilable code.
    text = text.replace("Minecraft.getInstance().getProfiler()", "Profiler.get()")
    if "Profiler." in text:
        text = _ensure_java_import(text, "net.minecraft.util.profiling.Profiler")
    text = text.replace("TagParser.parseTag(", "NbtCompat.parseTag(")
    text = text.replace("net.minecraft.nbt.NbtCompat.parseTag(", "NbtCompat.parseTag(")
    text = text.replace("stack.saveOptional(StatementManager.getRegistryProvider())", "ItemCompat.saveOptional(stack, StatementManager.getRegistryProvider())")
    text = text.replace("stack.saveOptional(registries)", "ItemCompat.saveOptional(stack, registries)")
    text = text.replace("ItemStack.parseOptional(registries, normalized)", "ItemCompat.parseOptional(registries, normalized)")
    text = text.replace("FluidStack.parseOptional(registries, normalized)", "FluidCompat.parseOptional(registries, normalized)")
    text = text.replace("stack.getBurnTime(null) > 0", "ItemCompat.getBurnTime(stack) > 0")
    text = text.replace("Capabilities.ItemHandler.ENTITY_AUTOMATION", "Capabilities.Item.ENTITY_AUTOMATION")
    text = text.replace("Capabilities.ItemHandler.ENTITY", "Capabilities.Item.ENTITY")
    text = re.sub(r"(?<![A-Za-z0-9_$.])(\w+)\.server(?![A-Za-z0-9_$(])", r"\1.getServer()", text)
    text = text.replace("GameRules.RULE_DO_TILE_DROPS", "GameRules.RULE_DOBLOCKDROPS")
    text = text.replace("RenderSystem.depthMask(", "RenderCompat.depthMask(")
    text = text.replace("RenderSystem.disableCull();", "RenderCompat.disableCull();")
    text = text.replace("RenderSystem.getShaderFogStart()", "0.0F")
    text = text.replace("RenderSystem.getShaderFogEnd()", "1.0F")
    text = text.replace("RenderSystem.setShaderFogStart(", "RenderCompat.setShaderFogStart(")
    text = text.replace("RenderSystem.setShaderFogEnd(", "RenderCompat.setShaderFogEnd(")
    text = re.sub(r"RenderSystem\.setShader\([^;]+\);", "RenderCompat.setShader(null);", text)
    text = text.replace("BuiltInRegistries.ITEM.get(id)", "BuiltInRegistries.ITEM.get(id).map(net.minecraft.core.Holder.Reference::value).orElse(net.minecraft.world.item.Items.AIR)")
    text = text.replace("BuiltInRegistries.ITEM.get(itemId)", "BuiltInRegistries.ITEM.get(itemId).map(net.minecraft.core.Holder.Reference::value).orElse(net.minecraft.world.item.Items.AIR)")
    text = text.replace("BuiltInRegistries.ITEM.get(location)", "BuiltInRegistries.ITEM.get(location).map(net.minecraft.core.Holder.Reference::value).orElse(net.minecraft.world.item.Items.AIR)")
    text = text.replace("BuiltInRegistries.FLUID.get(id)", "BuiltInRegistries.FLUID.get(id).map(net.minecraft.core.Holder.Reference::value).orElse(net.minecraft.world.level.material.Fluids.EMPTY)")
    text = text.replace("BuiltInRegistries.FLUID.get(Identifier.parse(name))", "BuiltInRegistries.FLUID.get(Identifier.parse(name)).map(net.minecraft.core.Holder.Reference::value).orElse(net.minecraft.world.level.material.Fluids.EMPTY)")
    text = text.replace("BuiltInRegistries.FLUID.get(Identifier.parse(id))", "BuiltInRegistries.FLUID.get(Identifier.parse(id)).map(net.minecraft.core.Holder.Reference::value).orElse(net.minecraft.world.level.material.Fluids.EMPTY)")
    text = text.replace("BuiltInRegistries.BLOCK.get(Identifier.parse(paint.getName() + type))", "BuiltInRegistries.BLOCK.get(Identifier.parse(paint.getName() + type)).map(net.minecraft.core.Holder.Reference::value).orElse(net.minecraft.world.level.block.Blocks.AIR)")
    text = text.replace("BuiltInRegistries.BLOCK.get(id)", "BuiltInRegistries.BLOCK.get(id).map(net.minecraft.core.Holder.Reference::value).orElse(net.minecraft.world.level.block.Blocks.AIR)")
    text = text.replace("BuiltInRegistries.BLOCK.get(loc)", "BuiltInRegistries.BLOCK.get(loc).map(net.minecraft.core.Holder.Reference::value).orElse(net.minecraft.world.level.block.Blocks.AIR)")
    text = text.replace("FakePlayerProvider.NULL_PROFILE.getId()", "GameProfileCompat.id(FakePlayerProvider.NULL_PROFILE)")
    text = text.replace("Ingredient.of(item)", "IngredientCompat.of(item)")
    text = text.replace("Ingredient.of(stack)", "IngredientCompat.of(stack)")
    text = text.replace("Ingredient.of(tag)", "IngredientCompat.of(tag)")
    text = text.replace("Ingredient.of(Objects.requireNonNull(tag, \"tag\"))", "IngredientCompat.of(Objects.requireNonNull(tag, \"tag\"))")
    text = text.replace("public RecipeType<?> getType()", "public RecipeType<? extends Recipe<RecipeInput>> getType()")
    text = text.replace("RenderCompat.blit(guiGraphics, ", "RenderCompat.blit(guiGraphics, ")












    # Fix remaining direct model-bake map access in module model registrars.

    # The compatibility renderer does not expose the removed direct VBO shader lookup.
    text = text.replace("RenderSystem.getShader()", "null")
    text = text.replace("Minecraft.getInstance().getItemColors().register(colour, item);", "")




    # Use 1.21.11 registry optional wrappers only at the few remaining old direct-get call sites.
    text = text.replace("Item item = BuiltInRegistries.ITEM.get(loc);", "Item item = BuiltInRegistries.ITEM.get(loc).map(net.minecraft.core.Holder.Reference::value).orElse(net.minecraft.world.item.Items.AIR);")
    text = text.replace("Item item = BuiltInRegistries.ITEM.get(id);", "Item item = BuiltInRegistries.ITEM.get(id).map(net.minecraft.core.Holder.Reference::value).orElse(net.minecraft.world.item.Items.AIR);")

    text = text.replace("NbtCompat.getInt(JsonUtil, obj, \"count\")", "obj.get(\"count\").getAsInt()")


    # Narrow, path-independent rewrites for 1.21.11 API differences.
    text = re.sub(r"(?m)^[ \t]*profiler\.(?:push|popPush|pop)\([^;]*\);\n", "", text)
    text = text.replace("GameRules.RULE_DO_TILE_DROPS", "GameRules.RULE_DOBLOCKDROPS")
    text = text.replace(".writeResourceLocation(", ".writeIdentifier(")
    text = text.replace(".readResourceLocation()", ".readIdentifier()")
    text = text.replace(".getCommandSenderWorld()", ".level()")
    text = text.replace("player.getInventory().selected", "player.getInventory().getSelectedSlot()")
    text = text.replace("stack.getItem() instanceof AxeItem || stack.canPerformAction(ItemAbilities.AXE_DIG)", "ItemCompat.isAxe(stack)")
    text = text.replace("ItemCompat.isPickaxe(stack) || stack.canPerformAction(ItemAbilities.PICKAXE_DIG)", "ItemCompat.isPickaxe(stack)")
    text = text.replace("stack.getItem() instanceof ShovelItem || stack.canPerformAction(ItemAbilities.SHOVEL_DIG)", "ItemCompat.isShovel(stack)")
    if "ItemCompat.isAxe(" in text or "ItemCompat.isShovel(" in text:
        text = _ensure_java_import(text, "buildcraft.lib.compat.ItemCompat")
    if "ItemAbilities." not in text:
        text = text.replace("import net.neoforged.neoforge.common.ItemAbilities;\n", "")
    # 1.21.11 renamed MapColor#calculateRGBColor to calculateARGBColor. Do not zero the colour: Zone Planner
    # maps use this data both in the full GUI and on the block's front display. NativeImage#setPixel also accepts
    # ARGB directly in 1.21.11 and performs its own ARGB -> native ABGR conversion.
    text = text.replace(".setPixelRGBA(", ".setPixel(")
    text = text.replace("RenderCompat.blit(guiGraphics, TEXTURE_MAP, x0, y0, 0.0F, 0.0F, mapW, mapH, mapW, mapH)", "RenderCompat.blit(guiGraphics, TEXTURE_MAP, x0, y0, 0, 0, mapW, mapH, mapW, mapH)")
    text = text.replace("src.getAsShort()", "NbtCompat.getShort(src)")
    text = text.replace("src.getAsInt()", "NbtCompat.getInt(src)")
    text = text.replace("src.getAsLong()", "NbtCompat.getLong(src)")
    text = text.replace("src.getAsFloat()", "NbtCompat.getFloat(src)")
    text = text.replace("src.getAsDouble()", "NbtCompat.getDouble(src)")
    text = re.sub(r"\(\(ShortTag\)\s*([^\)]+)\)\.getAsShort\(\)", r"NbtCompat.getShort((ShortTag) \1)", text)
    text = re.sub(r"\(\(LongTag\)\s*([^\)]+)\)\.getAsLong\(\)", r"NbtCompat.getLong((LongTag) \1)", text)
    text = re.sub(r"\(\(FloatTag\)\s*([^\)]+)\)\.getAsFloat\(\)", r"NbtCompat.getFloat((FloatTag) \1)", text)
    text = re.sub(r"\(\(DoubleTag\)\s*([^\)]+)\)\.getAsDouble\(\)", r"NbtCompat.getDouble((DoubleTag) \1)", text)
    text = re.sub(r"(NbtCompat\.getCompound\([^\n;]+?\))\.getString\(([^,\n()]+)\)", r"NbtCompat.getString(\1, \2)", text)
    text = text.replace("nbt.getLongArray(\"currentTree\")", "NbtCompat.getLongArray(nbt, \"currentTree\")")


    # Entity inventories are bridged by the native target ItemTransactorHelper/CapUtil.



















    # Silicon and transport API-shape compatibility required by the generated 1.21.11 tree.
    text = text.replace("GameRules.RULE_DOBLOCKDROPS", "GameRules.BLOCK_DROPS")
    text = text.replace("GameRules.RULE_DO_TILE_DROPS", "GameRules.BLOCK_DROPS")
    text = text.replace(".getGameRules().getValue(GameRules.BLOCK_DROPS)", ".getGameRules().get(GameRules.BLOCK_DROPS)")
    text = text.replace(".getGameRules().getBoolean(GameRules.BLOCK_DROPS)", ".getGameRules().get(GameRules.BLOCK_DROPS)")
    text = text.replace(".getServer().getAdvancements()", ".level().getServer().getAdvancements()")
    text = text.replace("requestedPlayer.getServer() == null", "requestedPlayer.level().getServer() == null")
    text = text.replace("requestedPlayer.getServer().getPlayerList()", "requestedPlayer.level().getServer().getPlayerList()")
    text = text.replace("playerMP.getServer().getAdvancements()", "playerMP.level().getServer().getAdvancements()")
    text = text.replace("public RecipeSerializer<?> getSerializer()", "public RecipeSerializer<? extends Recipe<RecipeInput>> getSerializer()")
    if "Recipe<" in text:
        text = _ensure_java_import(text, "net.minecraft.world.item.crafting.Recipe")
    if "RecipeInput" in text:
        text = _ensure_java_import(text, "net.minecraft.world.item.crafting.RecipeInput")
    text = text.replace("ShapedRecipePattern.of(key, rows)", "ShapedRecipePattern.of(this.key, rows)")
    text = text.replace("Capabilities.EnergyStorage.BLOCK", "Capabilities.Energy.BLOCK")
    text = text.replace("Capabilities.EnergyStorage.ITEM", "Capabilities.Energy.ITEM")
    text = text.replace("Ingredient.EMPTY", "IngredientCompat.empty()")
    text = text.replace("expected == IngredientCompat.empty()", "IngredientCompat.isEmpty(expected)")
    text = text.replace("Ingredient.of(stateRequirement)", "IngredientCompat.of(stateRequirement)")
    text = text.replace("Ingredient.of(baseRequirementStack())", "IngredientCompat.of(baseRequirementStack())")
    text = text.replace("ing.getItems()", "IngredientCompat.getItems(ing)")
    text = text.replace("recipe.getIngredients()", "recipe.placementInfo().ingredients()")
    text = text.replace("state.propagatesSkylightDown(access, BlockPos.ZERO)", "state.propagatesSkylightDown()")
    text = text.replace("NbtCompat.getCompound(states, 0).getBoolean(\"isHollow\")", "NbtCompat.getBoolean(NbtCompat.getCompound(states, 0), \"isHollow\")")
    text = text.replace("NbtCompat.getCompound(tagStates, 0).getBoolean(\"isHollow\")", "NbtCompat.getBoolean(NbtCompat.getCompound(tagStates, 0), \"isHollow\")")
    text = text.replace("NBTUtilBC.getItemData(stack).getCompound(\"gate\")", "NbtCompat.getCompound(NBTUtilBC.getItemData(stack), \"gate\")")
    text = text.replace("BuiltInRegistries.BLOCK.get(Identifier.parse(regName))", "BuiltInRegistries.BLOCK.get(Identifier.parse(regName)).map(net.minecraft.core.Holder.Reference::value).orElse(net.minecraft.world.level.block.Blocks.AIR)")
    text = text.replace("new BlockParticleOption(ParticleTypes.BLOCK, BlockState).setPos(blockPosition)", "new BlockParticleOption(ParticleTypes.BLOCK, BlockState)")
    text = text.replace("particle.pickSprite(spriteSet);", "// Sprite selection is owned by the 1.21.11 particle construction path.")
    text = text.replace("particle.setSprite(spriteSet.first());", "// Sprite selection is owned by the 1.21.11 particle construction path.")
    text = text.replace("currentFluid.saveOptional(TilePipeHolder.getRegistryAccess(pipe))", "FluidCompat.saveOptional(currentFluid, TilePipeHolder.getRegistryAccess(pipe))")
    text = text.replace("itemRender.renderStatic(stack, ItemDisplayContext.GROUND, lightc, combinedOverlay, matrix, buffer, world, 0);", "RenderCompat.renderStatic(itemRender, stack, ItemDisplayContext.GROUND, lightc, combinedOverlay, matrix, buffer, world, 0);")
    text = text.replace("RenderSystem.clearColor(", "RenderCompat.clearColor(")
    text = text.replace("cart.kill();", "cart.discard();")
    text = re.sub(
        r"(?m)^\s*stripesTileNew\.loadWithComponents\(stripesNBTOld, w\.registryAccess\(\)\);",
        "            stripesTileNew.loadWithComponents(net.minecraft.world.level.storage.TagValueInput.create("
        "net.minecraft.util.ProblemReporter.DISCARDING, w.registryAccess(), stripesNBTOld));",
        text,
    )
    text = text.replace("ItemStackUtil.getCustomData(stack).getInt(\"color\")", "NbtCompat.getInt(ItemStackUtil.getCustomData(stack), \"color\")")
    text = text.replace("energyBuffers.getCompound(Integer.toString(face.ordinal()))", "NbtCompat.getCompound(energyBuffers, Integer.toString(face.ordinal()))")
    text = text.replace(
        "GameProfileCompat.id(AdvancementUtil.unlockAdvancement(GameProfileCompat.id(pipe.getHolder().getOwner()), ADVANCEMENT_NEED_LIST);",
        "AdvancementUtil.unlockAdvancement(GameProfileCompat.id(pipe.getHolder().getOwner()), ADVANCEMENT_NEED_LIST);"
    )
    text = text.replace(
        "AdvancementUtil.unlockAdvancement(pipe.getHolder().getOwner()), ADVANCEMENT_NEED_LIST",
        "AdvancementUtil.unlockAdvancement(GameProfileCompat.id(pipe.getHolder().getOwner()), ADVANCEMENT_NEED_LIST"
    )
    text = text.replace(
        "GameProfileCompat.id(AdvancementUtil.unlockAdvancement(GameProfileCompat.id(pipe.getHolder().getOwner()), ADVANCEMENT_NEED_LIST);",
        "AdvancementUtil.unlockAdvancement(GameProfileCompat.id(pipe.getHolder().getOwner()), ADVANCEMENT_NEED_LIST);"
    )

    # 1.21.11 CharacterEvent input calls. Child widgets need the native record, while GuiBC8 subclasses
    # must keep super.charTyped(char,int) so the native->legacy adapter does not recurse back into the subclass.
    text = re.sub(
        r"\b(?!super\b)(\w+)\.charTyped\(codePoint, modifiers\)",
        r"\1.charTyped(new net.minecraft.client.input.CharacterEvent(codePoint, modifiers))",
        text,
    )
    if not legacy_gui_input_bridge:
        text = text.replace(
            "super.charTyped(codePoint, modifiers)",
            "super.charTyped(new net.minecraft.client.input.CharacterEvent(codePoint, modifiers))",
        )























    # Callback compatibility for vanilla/NeoForge signatures that differ across modern targets.







    # MC 1.21.3+ requires Block/Item properties to know their registry key
    # before the object constructor runs. The maintained NeoForge sources still
    # use generic DeferredRegister.register(String, Supplier), so route block
    # and item registrations through a tiny id-aware wrapper and stamp newly
    # created property objects from a thread-local registry key.
    text = re.sub(
        r"\b((?:[A-Za-z_][A-Za-z0-9_]*\.)*BLOCKS)\.register\s*\(\s*\"",
        r'RegistryCompat.registerBlock(\1, "',
        text,
    )
    text = re.sub(
        r"\b((?:[A-Za-z_][A-Za-z0-9_]*\.)*ITEMS)\.register\s*\(\s*\"",
        r'RegistryCompat.registerItem(\1, "',
        text,
    )
    text = _rewrite_deferred_register_calls(text, "BLOCKS", "registerBlock")
    text = _rewrite_deferred_register_calls(text, "ITEMS", "registerItem")
    text = text.replace("BlockBehaviour.Properties.of()", "RegistryCompat.blockProperties(BlockBehaviour.Properties.of())")
    text = text.replace("BlockBehaviour.Properties.ofFullCopy(", "RegistryCompat.blockProperties(BlockBehaviour.Properties.ofFullCopy(")
    text = text.replace("BlockBehaviour.Properties.ofLegacyCopy(", "RegistryCompat.blockProperties(BlockBehaviour.Properties.ofLegacyCopy(")
    text = text.replace("new Item.Properties()", "RegistryCompat.itemProperties(new Item.Properties())")
    if "import net.minecraft.world.item.Item.Properties;" in text:
        text = text.replace("new Properties()", "RegistryCompat.itemProperties(new Properties())")



    # Final safety pass for compatibility imports. Some compatibility calls are
    # introduced after the main import pass (camera/min-height/render GUI
    # rewrites), so ensure their imports after all rewrites are complete.
    if "LevelCompat." in text:
        text = _ensure_java_import(text, "buildcraft.lib.compat.LevelCompat")
    if "RenderCompat." in text:
        text = _ensure_java_import(text, "buildcraft.lib.compat.RenderCompat")
    if "ItemCompat." in text:
        text = _ensure_java_import(text, "buildcraft.lib.compat.ItemCompat")
    if "IngredientCompat." in text:
        text = _ensure_java_import(text, "buildcraft.lib.compat.IngredientCompat")
    if "FluidCompat." in text:
        text = _ensure_java_import(text, "buildcraft.lib.compat.FluidCompat")
    if "GameProfileCompat." in text:
        text = _ensure_java_import(text, "buildcraft.lib.compat.GameProfileCompat")
    if "NbtCompat." in text:
        text = _ensure_java_import(text, "buildcraft.lib.compat.NbtCompat")
    if "BlockEntityTypeCompat." in text:
        text = _ensure_java_import(text, "buildcraft.lib.compat.BlockEntityTypeCompat")
    if "RegistryCompat." in text:
        text = _ensure_java_import(text, "buildcraft.lib.compat.RegistryCompat")
    if "Minecraft.getInstance()" in text and "net.minecraft.client.Minecraft.getInstance()" not in text:
        text = _ensure_java_import(text, "net.minecraft.client.Minecraft")
    if "CapUtil." in text and "import buildcraft.lib.misc.CapUtil;" not in text:
        text = _ensure_java_import(text, "buildcraft.lib.misc.CapUtil")
    if "InteractionResult" in text and "import net.minecraft.world.InteractionResult;" not in text:
        text = _ensure_java_import(text, "net.minecraft.world.InteractionResult")

    # NeoForge 21.11 uses item tint sources instead of direct ItemColor registration.
    # Remove legacy registration calls from the generated compile path.
    text = text.replace("RegisterColorHandlersEvent.Item event", "RegisterColorHandlersEvent.ItemTintSources event")
    text = re.sub(
        r"(?m)^\s*event\.register\(new FragileFluidContainerModel\.Colors\(\), BCCoreItems\.FRAGILE_FLUID_SHARD\.get\(\)\);\n",
        "",
        text,
    )
    text = re.sub(
        r"(?m)^\s*event\.register\(FacadeItemColours\.INSTANCE, BCSiliconItems\.PLUG_FACADE_ITEM\.get\(\)\);\n",
        "",
        text,
    )

    if "InteractionResultHolder" in text and "import net.minecraft.world.InteractionResultHolder;" in text:
        text = text.replace(
            "import net.minecraft.world.InteractionResultHolder;",
            "import buildcraft.lib.misc.InteractionResultHolder;",
        )

    text = text.replace(
        ".use(world, player, InteractionHand.MAIN_HAND).getResult()",
        ".use(world, player, InteractionHand.MAIN_HAND)",
    )


    # Final 1.21.11 GUI/guide canonicalization. Keep this at the end of the Java pass so
    # repeated source generation stays idempotent and generic rewrites do not re-wrap target calls.
    item_lookup = ".map(net.minecraft.core.Holder.Reference::value).orElse(net.minecraft.world.item.Items.AIR)"
    while item_lookup + item_lookup in text:
        text = text.replace(item_lookup + item_lookup, item_lookup)

    # Do not recursively rewrite our own compatibility wrappers on a second materialization.
    while "RenderCompat.mouseClicked(RenderCompat, " in text:
        text = text.replace("RenderCompat.mouseClicked(RenderCompat, ", "RenderCompat.mouseClicked(")
    while "RenderCompat.keyPressed(RenderCompat, " in text:
        text = text.replace("RenderCompat.keyPressed(RenderCompat, ", "RenderCompat.keyPressed(")

    # Legacy GUI classes may retain unused PoseStack locals while rendering through GuiGraphics.
    # RenderCompat.pose() is intentionally absent because a dummy PoseStack cannot affect
    # GuiGraphics' Matrix3x2fStack.

    # The 1.21.11 guide and wrapped-text path use native 2D matrix methods backed by
    # GuiGraphics' real Matrix3x2fStack rather than a dummy pose bridge.






    # Auto-crafting exposes the BuildCraft char hook; call the phantom bridge using that
    # signature instead of constructing a CharacterEvent for a class that may only provide char/int.

    # Typed CompoundTag checks moved behind NbtCompat on 1.21.11. Ensure the two tank paths use the
    # target bridge and retain the Tag import where the constant itself is still referenced.
    text = _cleanup_empty_java_imports(text)
    return _repair_java_imports_before_package(text)




