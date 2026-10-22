/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2026, TeamAppliedEnergistics, All rights reserved.
 */

package ae2.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraftforge.client.event.GuiContainerEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.opengl.GL11;

/**
 * Draws pending entries as non-interactive translucent slot contents for ordinary container GUIs.
 */
public final class RecipeTransferPendingRenderer {
    public static final RecipeTransferPendingRenderer INSTANCE = new RecipeTransferPendingRenderer();

    private RecipeTransferPendingRenderer() {
    }

    private static Slot findSlot(GuiContainer gui, int slotNumber) {
        for (int i = 0; i < gui.inventorySlots.inventorySlots.size(); i++) {
            Slot slot = gui.inventorySlots.inventorySlots.get(i);
            if (slot != null && slot.slotNumber == slotNumber) {
                return slot;
            }
        }
        return null;
    }

    @SubscribeEvent
    public void drawPending(GuiContainerEvent.DrawForeground event) {
        GuiContainer gui = event.getGuiContainer();
        if (gui.inventorySlots == null || gui.inventorySlots.windowId != RecipeTransferPendingClientState.windowId()) {
            return;
        }

        var entries = RecipeTransferPendingClientState.entries();
        if (entries.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        var renderItem = minecraft.getRenderItem();
        GL11.glDepthMask(false);
        for (int i = 0; i < entries.size(); i++) {
            RecipeTransferPendingClientState.Entry entry = entries.get(i);
            Slot slot = findSlot(gui, entry.slotNumber());
            if (slot == null || !slot.getStack().isEmpty()) {
                continue;
            }
            ItemStack display = entry.displayStack();
            PendingRecipeTransferVisuals.drawPendingItem(renderItem, display, slot.xPos, slot.yPos);
        }
        GL11.glDepthMask(true);
    }
}
