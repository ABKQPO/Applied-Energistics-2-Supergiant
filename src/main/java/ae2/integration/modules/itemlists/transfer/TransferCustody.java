/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2026, TeamAppliedEnergistics, All rights reserved.
 */

package ae2.integration.modules.itemlists.transfer;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.item.ItemStack;

import java.util.Objects;

/**
 * Owns material extracted for a transfer until normal target input accepts it. Unaccepted items are returned to the
 * source and any source remainder is returned to the caller for player-inventory insertion or dropping.
 */
public final class TransferCustody {
    private final ObjectArrayList<Held> held = new ObjectArrayList<>();

    private static void refund(Held entry, ItemStack stack, ObjectArrayList<ItemStack> unreturned) {
        ItemStack remainder;
        try {
            remainder = entry.source.refund(stack);
        } catch (RuntimeException e) {
            // Keep ownership explicit when a source callback fails. The caller can place this stack in the
            // player's inventory or drop it instead of silently losing it.
            remainder = stack;
        }
        if (remainder != null && !remainder.isEmpty()) {
            unreturned.add(remainder);
        }
    }

    public Held hold(ItemStack stack, RefundSink source) {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(source, "source");
        if (stack.isEmpty()) {
            throw new IllegalArgumentException("Cannot hold an empty stack");
        }
        var entry = new Held(stack, source);
        held.add(entry);
        return entry;
    }

    /**
     * Removes an offered portion from custody for one input operation.
     */
    public ItemStack offer(Held entry, int amount) {
        requireHeld(entry);
        if (amount <= 0 || amount > entry.remaining.getCount()) {
            throw new IllegalArgumentException("Invalid offered amount");
        }
        entry.offered = entry.remaining.splitStack(amount);
        return entry.offered.copy();
    }

    /**
     * Settles the operation using its actual remainder, never the planned amount.
     */
    public void settle(Held entry, InsertionReceipt receipt) {
        requireHeld(entry);
        Objects.requireNonNull(receipt, "receipt");
        if (entry.offered.isEmpty()
            || !ItemStack.areItemsEqual(entry.offered, receipt.offered())
            || !ItemStack.areItemStackTagsEqual(entry.offered, receipt.offered())
            || entry.offered.getCount() != receipt.offered().getCount()) {
            throw new IllegalArgumentException("Input receipt does not match the offered stack");
        }
        ItemStack returned = receipt.remainder();
        if (!returned.isEmpty()) {
            if (entry.remaining.isEmpty()) {
                entry.remaining = returned;
            } else if (ItemStack.areItemsEqual(entry.remaining, returned)
                && ItemStack.areItemStackTagsEqual(entry.remaining, returned)) {
                entry.remaining.grow(returned.getCount());
            } else {
                throw new IllegalArgumentException("Input returned a different item stack");
            }
        }
        entry.offered = ItemStack.EMPTY;
    }

    /**
     * Returns all remaining custody to its original source and reports source remainders.
     */
    public ObjectArrayList<ItemStack> refundAll() {
        var unreturned = new ObjectArrayList<ItemStack>();
        for (int i = 0; i < held.size(); i++) {
            var entry = held.get(i);
            ItemStack remaining = entry.remaining;
            ItemStack offered = entry.offered;
            entry.remaining = ItemStack.EMPTY;
            entry.offered = ItemStack.EMPTY;
            if (remaining.isEmpty() && offered.isEmpty()) {
                continue;
            }
            if (!remaining.isEmpty()) {
                refund(entry, remaining, unreturned);
            }
            if (!offered.isEmpty()) {
                refund(entry, offered, unreturned);
            }
        }
        return unreturned;
    }

    private void requireHeld(Held entry) {
        if (entry == null || !held.contains(entry)) {
            throw new IllegalArgumentException("Stack is not held by this custody");
        }
    }

    @FunctionalInterface
    public interface RefundSink {
        ItemStack refund(ItemStack stack);
    }

    public static final class Held {
        private final RefundSink source;
        private ItemStack remaining;
        private ItemStack offered = ItemStack.EMPTY;

        private Held(ItemStack remaining, RefundSink source) {
            // The source transfers ownership to the custody. Copying here would duplicate every extracted stack and
            // is unnecessary while the stack is kept private until refund or insertion settles it.
            this.remaining = remaining;
            this.source = source;
        }

    }
}
