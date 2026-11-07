/* Copyright (c) 2016 SpaceToad and the BuildCraft team
 * 
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package buildcraft.builders.gui;

import java.util.List;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.gui.GuiGraphics;

import buildcraft.lib.internal.core.render.ISprite;
import buildcraft.builders.menu.ContainerElectronicLibrary;
import buildcraft.builders.snapshot.GlobalSavedDataSnapshots;
import buildcraft.builders.snapshot.Snapshot;
import buildcraft.lib.gui.GuiBC8;
import buildcraft.lib.gui.GuiIcon;
import buildcraft.lib.misc.GuiUtil;
import buildcraft.lib.compat.minecraft.gui.BCGuiTooltip;
import buildcraft.lib.gui.button.GuiButtonDrawable;
import buildcraft.lib.gui.button.IButtonClickEventTrigger;
import buildcraft.lib.gui.button.StandardSpriteButtons;
import buildcraft.lib.gui.pos.GuiRectangle;
import buildcraft.lib.gui.pos.IGuiPosition;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import buildcraft.lib.gui.help.GuiHelpUtil;

public class GuiElectronicLibrary extends GuiBC8<ContainerElectronicLibrary> {
    private static final ResourceLocation TEXTURE_BASE =
        ResourceLocation.parse("buildcraftbuilders:textures/gui/electronic_library.png");
    private static final int SIZE_X = 244, SIZE_Y = 220;
    private static final GuiIcon ICON_GUI = new GuiIcon(TEXTURE_BASE, 0, 0, SIZE_X, SIZE_Y);
    private static final GuiIcon ICON_PROGRESS_DOWN = new GuiIcon(TEXTURE_BASE, 234, 240, 22, 16);
    private static final GuiRectangle RECT_PROGRESS_DOWN = new GuiRectangle(194, 58, 22, 16);
    private static final GuiIcon ICON_PROGRESS_UP = new GuiIcon(TEXTURE_BASE, 234, 224, 22, 16);
    private static final GuiRectangle RECT_PROGRESS_UP = new GuiRectangle(194, 79, 22, 16);
    private static final GuiRectangle RECT_SNAPSHOT_LIST = new GuiRectangle(8, 22, 154, 108);
    private static final int SNAPSHOT_ROW_HEIGHT = 8;
    private static final int MAX_VISIBLE_SNAPSHOTS = RECT_SNAPSHOT_LIST.height < SNAPSHOT_ROW_HEIGHT ? 0
        : (int) (RECT_SNAPSHOT_LIST.height / SNAPSHOT_ROW_HEIGHT);
    private static final int SCROLLBAR_WIDTH = 7;
    private static final int SCROLLBAR_GAP = 3;
    private static final int SCROLLBAR_MIN_THUMB_HEIGHT = 12;
    private static final int LIST_TEXT_WIDTH = (int) RECT_SNAPSHOT_LIST.width - SCROLLBAR_WIDTH - SCROLLBAR_GAP - 3;
    private static final GuiRectangle RECT_DELETE_BUTTON = new GuiRectangle(174, 109, 25, 15);

    private final GuiButtonDrawable delButton;
    private final ElectronicLibraryViewport<Snapshot.Key> viewport = new ElectronicLibraryViewport<>(MAX_VISIBLE_SNAPSHOTS);
    private boolean draggingScrollbar;
    private double scrollbarDragOffset;

    public GuiElectronicLibrary(ContainerElectronicLibrary container, Inventory inv, Component title) {
        super(container, inv, title);
        imageWidth = SIZE_X;
        imageHeight = SIZE_Y;
        IGuiPosition buttonPos = mainGui.rootElement.offset(174, 109);
        delButton = new GuiButtonDrawable(mainGui, "del", buttonPos, StandardSpriteButtons.EIGHTH_BUTTON_DRAWABLE);
        delButton.enabled = false;
        delButton.registerListener(this::onDelButtonClick);
        mainGui.shownElements.add(delButton);
        mainGui.shownElements.add(delButton.createTextElement(Component.translatable("gui.del")));
        GuiHelpUtil.addRoot(mainGui, (int) RECT_SNAPSHOT_LIST.x, (int) RECT_SNAPSHOT_LIST.y, (int) RECT_SNAPSHOT_LIST.width,
            (int) RECT_SNAPSHOT_LIST.height, "buildcraft.help.library.list.title", 0xFF_66_AA_FF,
            "buildcraft.help.library.list.desc");
        GuiHelpUtil.addSlot(mainGui, 175, 57, "buildcraft.help.library.download_output.title", 0xFF_88_CC_88, "buildcraft.help.library.download_output.desc");
        GuiHelpUtil.addSlot(mainGui, 219, 57, "buildcraft.help.library.download_input.title", 0xFF_DD_CC_55, "buildcraft.help.library.download_input.desc");
        GuiHelpUtil.addSlot(mainGui, 175, 79, "buildcraft.help.library.upload_input.title", 0xFF_CC_AA_FF, "buildcraft.help.library.upload_input.desc");
        GuiHelpUtil.addSlot(mainGui, 219, 79, "buildcraft.help.library.upload_output.title", 0xFF_55_BB_DD, "buildcraft.help.library.upload_output.desc");
        GuiHelpUtil.addRoot(mainGui, 194, 58, 22, 37, "buildcraft.help.library.progress.title", 0xFF_EE_DD_77, "buildcraft.help.library.progress.desc");
        GuiHelpUtil.addRoot(mainGui, (int) RECT_DELETE_BUTTON.x, (int) RECT_DELETE_BUTTON.y, (int) RECT_DELETE_BUTTON.width,
            (int) RECT_DELETE_BUTTON.height, "buildcraft.help.library.delete.title", 0xFF_CC_55_55,
            "buildcraft.help.library.delete.desc");
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        super.render(graphics, mouseX, mouseY, partialTicks);
        renderLibraryEntryTooltip(graphics, mouseX, mouseY);
    }

    private void renderLibraryEntryTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        Snapshot.Key key = hoveredEntry(mouseX, mouseY);
        if (key == null) return;
        Snapshot snapshot = GlobalSavedDataSnapshots.getClientSnapshot(key);
        // The library renders its own framed preview. Do not ask ItemStack for
        // its inventory tooltip, which also contains preview-specific details.
        BCGuiTooltip.components(graphics, font, ElectronicLibraryEntryTooltip.compactLines(key), mouseX, mouseY);
        if (snapshot != null) {
            int x = Math.max(4, Math.min(width - 108, mouseX + 16));
            int y = Math.max(4, Math.min(height - 108, mouseY + 36));
            BlueprintPreviewFrame.draw(graphics::fill, x, y, 100, 100);
            buildcraft.builders.client.render.BlueprintRenderer.renderSnapshot(graphics, snapshot, x, y, 100, 100);
        }
    }

    private Snapshot.Key hoveredEntry(int mouseX, int mouseY) {
        // The scrollbar/gap is not an entry, and an empty row must never show a stale tooltip.
        if (draggingScrollbar || !insideList(mouseX, mouseY)
            || mouseX >= listLeft() + LIST_TEXT_WIDTH + 1) {
            return null;
        }
        List<Snapshot.Key> entries = GlobalSavedDataSnapshots.getClientSnapshotList();
        viewport.update(entries);
        int row = (int) ((mouseY - listTop()) / SNAPSHOT_ROW_HEIGHT);
        return viewport.entryAtRow(entries, row);
    }

    private void onDelButtonClick(IButtonClickEventTrigger button, int buttonKey) {
        if (container.tile.selected != null) {
            Snapshot snapshot = GlobalSavedDataSnapshots.getClientSnapshot(container.tile.selected);
            if (snapshot != null) {
                container.sendSelectedToServer(null);
                container.tile.selected = null;
                GlobalSavedDataSnapshots.removeClientSnapshot(snapshot.key);
                viewport.update(GlobalSavedDataSnapshots.getClientSnapshotList());
                delButton.enabled = false;
            }
        }
    }

    @Override
    protected void drawBackgroundLayer(PoseStack pose, int mouseX, int mouseY, float partialTicks) {
        GuiGraphics guiGraphics = getActiveGraphics();
        RenderSystem.setShaderTexture(0, TEXTURE_BASE);
        ICON_GUI.drawAt(guiGraphics, mainGui.rootElement);
        drawHorizontalProgress(guiGraphics, RECT_PROGRESS_DOWN, ICON_PROGRESS_DOWN, container.tile.getDownloadProgress(partialTicks), true);
        drawHorizontalProgress(guiGraphics, RECT_PROGRESS_UP, ICON_PROGRESS_UP, container.tile.getUploadProgress(partialTicks), false);
        List<Snapshot.Key> entries = GlobalSavedDataSnapshots.getClientSnapshotList();
        viewport.update(entries);
        // A removed blueprint must not remain selected for an upload/download.
        if (container.tile.selected != null && !entries.contains(container.tile.selected)) {
            container.sendSelectedToServer(null);
            container.tile.selected = null;
        }
        iterateSnapshots(entries, (i, rect, key) -> {
            boolean isSelected = key.equals(container.tile.selected);
            if (isSelected) {
                drawGradientRect(guiGraphics, rect, 0xFF_55_55_55, 0xFF_55_55_55);
            }
            int colour = isSelected ? 0xffffa0 : 0xe0e0e0;
            String text = ElectronicLibraryEntryTooltip.displayName(key);
            drawString(guiGraphics, font, fitSnapshotName(text, LIST_TEXT_WIDTH), rect.x, rect.y, colour);
        });
        drawScrollbar(guiGraphics, entries);
        delButton.enabled = container.tile.selected != null && entries.contains(container.tile.selected)
            && GlobalSavedDataSnapshots.getClientSnapshot(container.tile.selected) != null;
    }



    private String fitSnapshotName(String text, int maxWidth) {
        if (text == null || text.isEmpty() || font.width(text) <= maxWidth) {
            return text == null ? "" : text;
        }
        String ellipsis = "...";
        int prefixWidth = maxWidth - font.width(ellipsis);
        if (prefixWidth <= 0) {
            return font.plainSubstrByWidth(text, maxWidth);
        }
        return font.plainSubstrByWidth(text, prefixWidth) + ellipsis;
    }

    private void drawHorizontalProgress(GuiGraphics guiGraphics, GuiRectangle rect, GuiIcon icon, double progress, boolean rightToLeft) {
        if (progress <= 0) {
            return;
        }
        progress = Math.min(1, progress);
        double width = rect.width * progress;
        double x = mainGui.rootElement.getX() + rect.x;
        if (rightToLeft) {
            x += rect.width - width;
        }
        double y = mainGui.rootElement.getY() + rect.y;
        double u = rightToLeft ? 1 - progress : 0;
        ISprite sprite = GuiUtil.subRelative(icon.sprite, u, 0, progress, 1);
        GuiIcon.draw(guiGraphics, sprite, x, y, x + width, y + rect.height);
    }


    private void iterateSnapshots(List<Snapshot.Key> entries, ISnapshotIterator iterator) {
        int first = viewport.firstIndex(entries);
        int count = viewport.visibleCount(entries);
        GuiRectangle rect = new GuiRectangle(mainGui.rootElement.getX() + RECT_SNAPSHOT_LIST.x,
            mainGui.rootElement.getY() + RECT_SNAPSHOT_LIST.y,
            LIST_TEXT_WIDTH + 1, SNAPSHOT_ROW_HEIGHT);
        for (int row = 0; row < count; row++) {
            iterator.call(first + row, rect, entries.get(first + row));
            rect = rect.offset(0, SNAPSHOT_ROW_HEIGHT);
        }
    }

    private double listLeft() {
        return mainGui.rootElement.getX() + RECT_SNAPSHOT_LIST.x;
    }

    private double listTop() {
        return mainGui.rootElement.getY() + RECT_SNAPSHOT_LIST.y;
    }

    private double scrollbarLeft() {
        return listLeft() + RECT_SNAPSHOT_LIST.width - SCROLLBAR_WIDTH;
    }

    private boolean insideList(double x, double y) {
        return x >= listLeft() && x < listLeft() + RECT_SNAPSHOT_LIST.width
            && y >= listTop() && y < listTop() + RECT_SNAPSHOT_LIST.height;
    }

    private int scrollbarThumbHeight(int total) {
        if (total <= MAX_VISIBLE_SNAPSHOTS) {
            return (int) RECT_SNAPSHOT_LIST.height;
        }
        return Math.max(SCROLLBAR_MIN_THUMB_HEIGHT,
            (int) Math.round(RECT_SNAPSHOT_LIST.height * MAX_VISIBLE_SNAPSHOTS / (double) total));
    }

    private double scrollbarThumbTop(List<Snapshot.Key> entries) {
        int movement = (int) RECT_SNAPSHOT_LIST.height - scrollbarThumbHeight(entries.size());
        int max = viewport.maxFirstIndex(entries.size());
        return listTop() + (max == 0 ? 0 : movement * viewport.firstIndex(entries) / (double) max);
    }

    private void drawScrollbar(GuiGraphics guiGraphics, List<Snapshot.Key> entries) {
        if (entries.size() <= MAX_VISIBLE_SNAPSHOTS) {
            return;
        }
        double x = scrollbarLeft();
        double y = listTop();
        drawGradientRect(guiGraphics, new GuiRectangle(x, y, SCROLLBAR_WIDTH, RECT_SNAPSHOT_LIST.height),
            0xFF_33_33_33, 0xFF_33_33_33);
        drawGradientRect(guiGraphics, new GuiRectangle(x + 1, scrollbarThumbTop(entries) + 1,
            SCROLLBAR_WIDTH - 2, scrollbarThumbHeight(entries.size()) - 2),
            draggingScrollbar ? 0xFF_DD_DD_DD : 0xFF_B8_B8_B8,
            draggingScrollbar ? 0xFF_DD_DD_DD : 0xFF_B8_B8_B8);
    }

    private void scrollFromMouse(List<Snapshot.Key> entries, double mouseY) {
        int travel = (int) RECT_SNAPSHOT_LIST.height - scrollbarThumbHeight(entries.size());
        if (travel <= 0) {
            return;
        }
        viewport.scrollToFraction(entries, (mouseY - scrollbarDragOffset - listTop()) / travel);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int mouseButton) {
        if (mouseButton == 0 && insideList(mouseX, mouseY)) {
            List<Snapshot.Key> entries = GlobalSavedDataSnapshots.getClientSnapshotList();
            viewport.update(entries);
            if (mouseX >= scrollbarLeft()) {
                if (entries.size() > MAX_VISIBLE_SNAPSHOTS) {
                    double thumbTop = scrollbarThumbTop(entries);
                    double thumbHeight = scrollbarThumbHeight(entries.size());
                    scrollbarDragOffset = mouseY >= thumbTop && mouseY < thumbTop + thumbHeight
                        ? mouseY - thumbTop : thumbHeight / 2.0;
                    draggingScrollbar = true;
                    scrollFromMouse(entries, mouseY);
                }
                return true;
            }
            int row = (int) ((mouseY - listTop()) / SNAPSHOT_ROW_HEIGHT);
            Snapshot.Key key = viewport.entryAtRow(entries, row);
            if (key != null) {
                container.sendSelectedToServer(key);
                container.tile.selected = key;
                delButton.enabled = GlobalSavedDataSnapshots.getClientSnapshot(key) != null;
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingScrollbar && button == 0) {
            scrollFromMouse(GlobalSavedDataSnapshots.getClientSnapshotList(), mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (draggingScrollbar && button == 0) {
            draggingScrollbar = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (insideList(mouseX, mouseY) && scrollY != 0) {
            int rows = Math.max(1, (int) Math.round(Math.abs(scrollY) * 3));
            viewport.scrollBy(GlobalSavedDataSnapshots.getClientSnapshotList(), scrollY > 0 ? -rows : rows);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @FunctionalInterface
    private interface ISnapshotIterator {
        void call(int snapshotIndex, GuiRectangle rect, Snapshot.Key key);
    }
}
