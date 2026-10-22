/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2026, TeamAppliedEnergistics, All rights reserved.
 */

package ae2.api.integrations.recipetransfer;

/**
 * Optional target customization for the common recipe-transfer pipeline.
 *
 * <p>The interface is deliberately optional. Standard JEI/HEI slot handlers use the defaults and do not require a
 * target container to implement an AE type.</p>
 */
public interface IRecipeTransferCustomization {
    /**
     * Allows a target to replace the default source, quantity policy, or recipe identity.
     */
    default void customizeRecipeTransfer(RecipeTransferContext context) {
    }

    /**
     * Receives the server result after the normal input path has been applied.
     */
    default void onRecipeTransferApplied(RecipeTransferReceipt receipt) {
    }
}
