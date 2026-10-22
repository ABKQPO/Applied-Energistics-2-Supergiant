/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2026, TeamAppliedEnergistics, All rights reserved.
 */

package ae2.api.integrations.recipetransfer;

import ae2.api.stacks.AEKey;

/**
 * Read-only availability used for previews and preflight checks. It never reserves or extracts resources.
 */
public interface RecipeTransferInventoryView {
    long available(AEKey key);

    default boolean isCraftable(AEKey ignoredKey) {
        return false;
    }
}
