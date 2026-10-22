/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2026, TeamAppliedEnergistics, All rights reserved.
 */

package ae2.api.integrations.recipetransfer;

import ae2.api.stacks.AEKey;
import net.minecraft.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Optional source used by a recipe transfer. Implementations are expected to reserve or extract only on the
 * server-side execution path. Preview calls must not mutate the source.
 */
public interface RecipeTransferSource {
    /**
     * Stable identity for the backing inventory or network used by a pending transfer. A custom source recreated on
     * each request should override this with its underlying storage or grid identity.
     */
    default Object identity() {
        return this;
    }

    long available(AEKey key);

    /**
     * Extracts up to {@code amount}. The returned stack is owned by the transfer until it is accepted or returned.
     */
    @Nullable
    ItemStack extract(AEKey key, int amount);

    /**
     * Returns an unaccepted stack. The source may consume the passed stack in place; the return value is any part
     * that could not be returned to this source.
     */
    @Nullable
    ItemStack refund(ItemStack stack);

    /**
     * Marks an accepted stack as final. Sources that keep per-extraction provenance may use this to discard it after
     * the target has accepted the material. The default is sufficient for stateless sources.
     */
    default void commit(ItemStack stack) {
    }
}
