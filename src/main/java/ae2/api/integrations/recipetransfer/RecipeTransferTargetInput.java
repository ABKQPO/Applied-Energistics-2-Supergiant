/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2026, TeamAppliedEnergistics, All rights reserved.
 */

package ae2.api.integrations.recipetransfer;

import net.minecraft.item.ItemStack;

/**
 * Optional target input protocol. Implementations must preserve the target's normal input rules and return the actual
 * unaccepted stack; a planned amount or a changed target icon is not an insertion receipt.
 */
@FunctionalInterface
public interface RecipeTransferTargetInput {
    RecipeTransferInsertion insert(int targetSlot, ItemStack offered);
}
