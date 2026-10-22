/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2026, TeamAppliedEnergistics, All rights reserved.
 */

package ae2.integration.modules.itemlists.transfer;

import ae2.api.config.Actionable;
import ae2.api.integrations.recipetransfer.RecipeTransferSource;
import ae2.api.networking.energy.IEnergySource;
import ae2.api.networking.security.IActionSource;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.KeyCounter;
import ae2.api.storage.MEStorage;
import ae2.api.storage.StorageHelper;
import com.google.common.primitives.Ints;
import net.minecraft.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * AE network source used by wireless or container-owned transfer contexts.
 */
public final class NetworkRecipeTransferSource implements RecipeTransferSource {
    private final IEnergySource energy;
    private final MEStorage storage;
    private final IActionSource actionSource;
    @Nullable
    private final KeyCounter cachedAvailability;

    public NetworkRecipeTransferSource(IEnergySource energy, MEStorage storage, IActionSource actionSource,
                                       @Nullable KeyCounter cachedAvailability) {
        this.energy = Objects.requireNonNull(energy, "energy");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.actionSource = Objects.requireNonNull(actionSource, "actionSource");
        this.cachedAvailability = cachedAvailability;
    }

    @Override
    public long available(AEKey key) {
        if (cachedAvailability != null) {
            return Math.max(0, cachedAvailability.get(key));
        }
        return Math.max(0, storage.extract(key, Long.MAX_VALUE, Actionable.SIMULATE, actionSource));
    }

    @Override
    public ItemStack extract(AEKey key, int amount) {
        if (!(key instanceof AEItemKey itemKey) || amount <= 0) {
            return ItemStack.EMPTY;
        }
        long extracted = StorageHelper.poweredExtraction(energy, storage, key, amount, actionSource);
        return extracted <= 0 ? ItemStack.EMPTY : itemKey.toStack(Ints.saturatedCast(extracted));
    }

    @Override
    public ItemStack refund(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        AEItemKey key = AEItemKey.of(stack);
        if (key == null) {
            return stack;
        }
        long inserted = StorageHelper.poweredInsert(energy, storage, key, stack.getCount(), actionSource);
        stack.shrink(Ints.saturatedCast(inserted));
        return stack;
    }
}
