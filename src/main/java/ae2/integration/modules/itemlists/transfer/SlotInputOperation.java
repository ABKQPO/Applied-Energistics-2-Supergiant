/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2026, TeamAppliedEnergistics, All rights reserved.
 */

package ae2.integration.modules.itemlists.transfer;

import net.minecraft.item.ItemStack;

/**
 * Normal target input for one mapped recipe slot. Implementations must use the target's own input protocol and report
 * the unaccepted remainder; they must not manufacture a remainder from a planned quantity.
 */
@FunctionalInterface
public interface SlotInputOperation {
    InsertionReceipt insert(ItemStack offered);
}
