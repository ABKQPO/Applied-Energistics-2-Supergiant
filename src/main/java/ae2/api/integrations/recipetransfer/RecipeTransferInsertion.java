/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2026, TeamAppliedEnergistics, All rights reserved.
 */

package ae2.api.integrations.recipetransfer;

import net.minecraft.item.ItemStack;

/**
 * Result of a target's normal input operation, expressed without a JEI/HEI dependency.
 */
public record RecipeTransferInsertion(ItemStack remainder, int accepted) {
    public RecipeTransferInsertion {
        if (remainder == null || accepted < 0) {
            throw new IllegalArgumentException("Invalid insertion result");
        }
    }
}
