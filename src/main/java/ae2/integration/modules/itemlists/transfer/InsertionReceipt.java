/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2026, TeamAppliedEnergistics, All rights reserved.
 */

package ae2.integration.modules.itemlists.transfer;

import net.minecraft.item.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * The observable result of one normal target-input operation.
 */
public record InsertionReceipt(ItemStack offered, ItemStack remainder, int accepted, Status status) {
    public InsertionReceipt {
        offered = copyNonNull(offered, "offered");
        remainder = copyNonNull(remainder, "remainder");
        if (accepted < 0 || accepted > offered.getCount()) {
            throw new IllegalArgumentException("accepted must be within the offered stack");
        }
        if (remainder.getCount() != offered.getCount() - accepted) {
            throw new IllegalArgumentException("remainder does not match accepted amount");
        }
        if (status == null) {
            throw new NullPointerException("status");
        }
    }

    public static InsertionReceipt refused(ItemStack offered) {
        return new InsertionReceipt(offered, offered, 0, Status.REFUSED);
    }

    public static InsertionReceipt accepted(ItemStack offered) {
        return new InsertionReceipt(offered, ItemStack.EMPTY, offered.getCount(), Status.ACCEPTED);
    }

    private static @NotNull ItemStack copyNonNull(ItemStack stack, String name) {
        if (stack == null) {
            throw new NullPointerException(name);
        }
        return stack.copy();
    }

    public enum Status {
        ACCEPTED,
        PARTIAL,
        REFUSED,
        FAILED
    }
}
