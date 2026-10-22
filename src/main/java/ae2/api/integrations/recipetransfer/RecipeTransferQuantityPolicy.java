/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2026, TeamAppliedEnergistics, All rights reserved.
 */

package ae2.api.integrations.recipetransfer;

/**
 * Controls how many sets a recipe transfer requests from a target.
 *
 * <p>The default policy follows the quantity requested by JEI/HEI. A target integration may select
 * {@link #SINGLE_SET} when it must retain a one-set transfer behaviour.</p>
 */
public enum RecipeTransferQuantityPolicy {
    /**
     * Follow JEI/HEI's normal and maximum-transfer rules.
     */
    JEI,
    /**
     * Request exactly one recipe set, including when the transfer key is held.
     */
    SINGLE_SET
}
