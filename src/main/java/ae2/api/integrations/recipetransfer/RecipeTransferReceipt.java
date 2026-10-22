/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2026, TeamAppliedEnergistics, All rights reserved.
 */

package ae2.api.integrations.recipetransfer;

import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Result delivered to an optional recipe-transfer customization.
 */
public record RecipeTransferReceipt(@Nullable ResourceLocation recipeId,
                                    long movedAmount,
                                    long missingAmount,
                                    boolean complete) {
    public RecipeTransferReceipt {
        if (movedAmount < 0 || missingAmount < 0) {
            throw new IllegalArgumentException("Transfer amounts must not be negative");
        }
    }
}
