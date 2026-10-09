//? source if >=1.21.11
package buildcraft.lib.compat;

import buildcraft.lib.compat.minecraft.render.BCRenderTypes;
import buildcraft.lib.compat.minecraft.gui.BCInputState;
import buildcraft.lib.compat.minecraft.gui.BCGuiTooltip;
import buildcraft.lib.compat.minecraft.gui.BCGuiInput;
import buildcraft.lib.compat.minecraft.gui.BCGraphics;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Function;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.data.AtlasIds;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Runtime bridge for legacy BuildCraft client rendering on Minecraft/NeoForge 1.21.11. */
public final class RenderCompat {
    private static Identifier currentShaderTexture;
    private static final float[] currentShaderColor = { 1.0F, 1.0F, 1.0F, 1.0F };

    private RenderCompat() {
    }

    public static void setShaderTexture(int slot, Identifier texture) {
        if (slot == 0) {
            currentShaderTexture = texture;
        }
        // Some 1.21.11 GUI paths use GuiGraphics.blit instead of global texture binding. Preserve the compatibility
        // RenderSystem call when available and always store the texture for BuildCraft GUI helpers.
        invokeRenderSystem("setShaderTexture", new Class<?>[] { int.class, Identifier.class }, slot, texture);
    }

    public static Identifier getShaderTexture() {
        return currentShaderTexture;
    }

    public static void setShaderColor(float red, float green, float blue, float alpha) {
        currentShaderColor[0] = red;
        currentShaderColor[1] = green;
        currentShaderColor[2] = blue;
        currentShaderColor[3] = alpha;
        invokeRenderSystem("setShaderColor", new Class<?>[] { float.class, float.class, float.class, float.class }, red, green, blue, alpha);
    }

    public static float[] getShaderColor() {
        return currentShaderColor.clone();
    }

    public static void clearColor(float red, float green, float blue, float alpha) {
        invokeRenderSystem("clearColor", new Class<?>[] { float.class, float.class, float.class, float.class }, red, green, blue, alpha);
    }

    public static void enableDepthTest() {
        invokeRenderSystem("enableDepthTest");
    }

    public static void disableBlend() {
        invokeRenderSystem("disableBlend");
    }

    public static Function<Identifier, TextureAtlasSprite> blockSprites() {
        // 1.21.11 exposes loaded atlases through AtlasManager. Use the atlas definition id (AtlasIds.BLOCKS), not
        // the backing texture location (TextureAtlas.LOCATION_BLOCKS), and provide missingno as the final fallback
        // instead of propagating null into MutableQuad.
        TextureAtlas atlas = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS);
        TextureAtlasSprite missing = atlas.getSprite(MissingTextureAtlasSprite.getLocation());
        return location -> {
            TextureAtlasSprite sprite = atlas.getSprite(location);
            return sprite != null ? sprite : missing;
        };
    }

    public static DynamicTexture newDynamicTexture(int width, int height, boolean clear) {
        return new DynamicTexture("buildcraft_dynamic", width, height, clear);
    }

    /**
     * Native 1.21.11 mouse events carry modifier state, while the shared BC8 statement widgets still expose the
     * 1.21.1 callback shape. GuiBC8 scopes this value around the legacy callback so those widgets preserve the exact
     * Shift-click behaviour of 1.21.1 without polling removed Screen static helpers.
     */
    public static void setInputShiftDown(boolean shiftDown) {
        BCInputState.setShiftDown(shiftDown);
    }

    public static boolean hasInputShiftDown() {
        return BCInputState.shiftDown();
    }

    public static InputConstants.Key inputKey(int keyCode, int scanCode) {
        return BCGuiInput.key(keyCode, scanCode);
    }

    public static boolean mouseClicked(Object target, double mouseX, double mouseY, int button) {
        return BCGuiInput.click(target, mouseX, mouseY, button);
    }

    public static boolean mouseClicked(Object target, double mouseX, double mouseY, int button, boolean original) {
        boolean handled = mouseClicked(target, mouseX, mouseY, button);
        return original || handled;
    }

    public static boolean keyPressed(Object target, int keyCode, int scanCode, int modifiers) {
        return BCGuiInput.key(target, keyCode, scanCode, modifiers);
    }

    public static void renderTooltip(GuiGraphics graphics, Font font, ItemStack stack, int mouseX, int mouseY) {
        BCGuiTooltip.item(graphics, font, stack, mouseX, mouseY);
    }

    public static void blit(GuiGraphics graphics, Identifier texture, int x, int y, int u, int v, int width, int height) {
        blit(graphics, texture, x, y, u, v, width, height, 256, 256);
    }

    public static ItemRenderer itemRenderer() {
        return Minecraft.getInstance().getItemRenderer();
    }

    public static RenderType solid() {
        return BCRenderTypes.solid();
    }

    public static RenderType cutout() {
        return BCRenderTypes.cutout();
    }

    public static RenderType translucent() {
        return BCRenderTypes.translucent();
    }

    public static RenderType entityCutout(Identifier texture) {
        return BCRenderTypes.entityCutout(texture);
    }

    public static RenderType entityCutoutNoCull(Identifier texture) {
        return BCRenderTypes.entityCutoutNoCull(texture);
    }

    public static RenderType entityTranslucent(Identifier texture) {
        return BCRenderTypes.entityTranslucent(texture);
    }

    public static void enableBlend() {
        invokeRenderSystem("enableBlend");
    }

    public static void defaultBlendFunc() {
        invokeRenderSystem("defaultBlendFunc");
    }

    public static void disableDepthTest() {
        invokeRenderSystem("disableDepthTest");
    }

    public static void disableScissor() {
        invokeRenderSystem("disableScissor");
    }

    public static void enableScissor(int x, int y, int w, int h) {
        invokeRenderSystem("enableScissor", new Class<?>[] { int.class, int.class, int.class, int.class }, x, y, w, h);
    }

    public static void setupFor3DItems() {
    }

    public static void setupForFlatItems() {
    }

    public static void renderComponentTooltip(GuiGraphics graphics, Font font, List<Component> components, int mouseX, int mouseY) {
        BCGuiTooltip.components(graphics, font, components, mouseX, mouseY);
    }

    public static void renderTooltip(GuiGraphics graphics, Font font, Component component, int mouseX, int mouseY) {
        BCGuiTooltip.text(graphics, font, component, mouseX, mouseY);
    }

    public static void blit(GuiGraphics graphics, Identifier texture, int x, int y, int u, int v, int width, int height, int texWidth, int texHeight) {
        blit(graphics, texture, x, y, u, v, width, height, width, height, texWidth, texHeight);
    }

    public static void blit(GuiGraphics graphics, Identifier texture, int x, int y, int u, int v, int width, int height,
        int sourceWidth, int sourceHeight, int texWidth, int texHeight) {
        BCGraphics.blit(graphics, texture, x, y, u, v, width, height, sourceWidth, sourceHeight, texWidth, texHeight);
    }

    public static void setShader(Object shader) {
    }

    public static void depthMask(boolean enabled) {
        invokeRenderSystem("depthMask", new Class<?>[] { boolean.class }, enabled);
    }

    public static void disableCull() {
        invokeRenderSystem("disableCull");
    }

    public static void setShaderFogStart(float value) {
    }

    public static void setShaderFogEnd(float value) {
    }

    public static Object profiler() {
        return null;
    }

    private static void invokeRenderSystem(String methodName, Class<?>[] parameterTypes, Object... args) {
        try {
            Class<?> renderSystem = Class.forName("com.mojang.blaze3d.systems.RenderSystem");
            Method method = renderSystem.getMethod(methodName, parameterTypes);
            method.invoke(null, args);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException | InvocationTargetException |
                 RuntimeException | LinkageError ignored) {
        }
    }

    private static void invokeRenderSystem(String methodName) {
        invokeRenderSystem(methodName, new Class<?>[0]);
    }

}
