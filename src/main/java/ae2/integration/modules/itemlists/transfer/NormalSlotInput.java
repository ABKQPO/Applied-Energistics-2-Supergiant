/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2026, TeamAppliedEnergistics, All rights reserved.
 */

package ae2.integration.modules.itemlists.transfer;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;

/**
 * Executes a mapped input through the same pickup operation a player uses in the target GUI.
 */
public final class NormalSlotInput {
    private NormalSlotInput() {
    }

    public static InsertionReceipt insert(Container container, EntityPlayer player, int slotId, ItemStack offered) {
        if (container == null || player == null || offered == null || offered.isEmpty()
            || player.openContainer != container || !player.inventory.getItemStack().isEmpty() || slotId < 0) {
            return InsertionReceipt.refused(offered == null ? ItemStack.EMPTY : offered);
        }

        int slotIndex = findSlotIndex(container, slotId);
        if (slotIndex < 0) {
            return InsertionReceipt.refused(offered);
        }

        var slot = container.getSlot(slotIndex);
        if (slot == null) {
            return InsertionReceipt.refused(offered);
        }

        ItemStack supplied = offered.copy();
        ItemStack remainder;
        ItemStack unexpected = ItemStack.EMPTY;
        InsertionReceipt.Status status;
        try {
            player.inventory.setItemStack(supplied);
            // Container.slotClick addresses the inventorySlots list, while recipe-transfer packets carry the
            // stable Slot.slotNumber. They are usually equal, but custom containers are allowed to choose otherwise.
            container.slotClick(slotIndex, 0, ClickType.PICKUP, player);
            remainder = carriedOrEmpty(player);
            status = remainder.isEmpty() ? InsertionReceipt.Status.ACCEPTED
                : (remainder.getCount() == offered.getCount()
                ? InsertionReceipt.Status.REFUSED : InsertionReceipt.Status.PARTIAL);
        } catch (RuntimeException e) {
            // The carried stack is still the only reliable ownership record after a custom slot throws. Treat the
            // observed remainder as authoritative and let custody return it to its original source.
            remainder = carriedOrEmpty(player);
            status = InsertionReceipt.Status.FAILED;
        } finally {
            ItemStack carried = carriedOrEmpty(player);
            if (!carried.isEmpty() && !sameItem(carried, offered)) {
                unexpected = carried.copy();
                remainder = offered.copy();
                status = InsertionReceipt.Status.FAILED;
            }
            player.inventory.setItemStack(ItemStack.EMPTY);
        }

        if (!unexpected.isEmpty() && !player.inventory.addItemStackToInventory(unexpected)) {
            player.dropItem(unexpected, false);
        }

        int accepted = offered.getCount() - remainder.getCount();
        if (accepted < 0 || accepted > offered.getCount()) {
            return new InsertionReceipt(offered, offered, 0, InsertionReceipt.Status.FAILED);
        }
        return new InsertionReceipt(offered, remainder, accepted, status);
    }

    private static int findSlotIndex(Container container, int slotNumber) {
        for (int i = 0; i < container.inventorySlots.size(); i++) {
            var slot = container.inventorySlots.get(i);
            if (slot != null && slot.slotNumber == slotNumber) {
                return i;
            }
        }
        return -1;
    }

    private static ItemStack carriedOrEmpty(EntityPlayer player) {
        ItemStack carried = player.inventory.getItemStack();
        return carried == null ? ItemStack.EMPTY : carried;
    }

    private static boolean sameItem(ItemStack first, ItemStack second) {
        return ItemStack.areItemsEqual(first, second) && ItemStack.areItemStackTagsEqual(first, second);
    }
}
