/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2026, TeamAppliedEnergistics, All rights reserved.
 */

package ae2.integration.modules.itemlists.transfer;

import ae2.api.integrations.recipetransfer.RecipeTransferSource;
import ae2.api.inventories.InternalInventory;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import net.minecraft.item.ItemStack;

import java.util.Objects;
import java.util.function.IntPredicate;

/**
 * A source backed by an allowed set of normal item-inventory slots.
 */
public final class InternalInventoryRecipeTransferSource implements RecipeTransferSource {
    private final InternalInventory inventory;
    private final IntPredicate allowedSlot;

    public InternalInventoryRecipeTransferSource(InternalInventory inventory, IntPredicate allowedSlot) {
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.allowedSlot = Objects.requireNonNull(allowedSlot, "allowedSlot");
    }

    @Override
    public long available(AEKey key) {
        if (!(key instanceof AEItemKey itemKey)) {
            return 0;
        }
        long available = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (!allowedSlot.test(slot)) {
                continue;
            }
            ItemStack stack = inventory.getStackInSlot(slot);
            if (!stack.isEmpty() && itemKey.equals(AEItemKey.of(stack))) {
                int count = stack.getCount();
                available = available > Long.MAX_VALUE - count ? Long.MAX_VALUE : available + count;
            }
        }
        return available;
    }

    @Override
    public ItemStack extract(AEKey key, int amount) {
        if (!(key instanceof AEItemKey itemKey) || amount <= 0) {
            return ItemStack.EMPTY;
        }
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (!allowedSlot.test(slot)) {
                continue;
            }
            ItemStack stack = inventory.getStackInSlot(slot);
            if (!stack.isEmpty() && itemKey.equals(AEItemKey.of(stack))) {
                return inventory.extractItem(slot, amount, false);
            }
        }
        return ItemStack.EMPTY;
    }

    @Override
    public ItemStack refund(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack remainder = stack;
        for (int slot = 0; slot < inventory.size() && !remainder.isEmpty(); slot++) {
            if (allowedSlot.test(slot)) {
                remainder = inventory.insertItem(slot, remainder, false);
            }
        }
        return remainder;
    }
}
