/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2026, TeamAppliedEnergistics, All rights reserved.
 */

package ae2.integration.modules.itemlists.transfer;

import ae2.api.integrations.recipetransfer.RecipeTransferSource;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayFIFOQueue;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.item.ItemStack;

import java.util.List;
import java.util.Objects;

/**
 * Combines sources in priority order and remembers which source owns every extracted amount. This lets a refused
 * target input return local material to the local inventory and network material to the network.
 */
public final class CompositeRecipeTransferSource implements RecipeTransferSource {
    private final ObjectList<? extends RecipeTransferSource> sources;
    private final Object2ObjectMap<AEKey, ObjectArrayFIFOQueue<Origin>> origins = new Object2ObjectOpenHashMap<>();

    public CompositeRecipeTransferSource(List<? extends RecipeTransferSource> sources) {
        if (sources == null || sources.isEmpty()) {
            throw new IllegalArgumentException("At least one transfer source is required");
        }
        if (sources instanceof ObjectList<?> objectList) {
            @SuppressWarnings("unchecked")
            ObjectList<? extends RecipeTransferSource> typed = (ObjectList<? extends RecipeTransferSource>) objectList;
            this.sources = ObjectLists.unmodifiable(typed);
        } else {
            var copied = new ObjectArrayList<RecipeTransferSource>(sources.size());
            copied.addAll(sources);
            this.sources = ObjectLists.unmodifiable(copied);
        }
    }

    private static ItemStack merge(ItemStack first, ItemStack second) {
        if (!ItemStack.areItemsEqual(first, second) || !ItemStack.areItemStackTagsEqual(first, second)) {
            throw new IllegalArgumentException("Cannot merge different transfer source stacks");
        }
        first.grow(second.getCount());
        return first;
    }

    @Override
    public long available(AEKey key) {
        long available = 0;
        for (int i = 0; i < sources.size(); i++) {
            var source = sources.get(i);
            long sourceAvailable = Math.max(0, source.available(key));
            available = sourceAvailable > 0 && available > Long.MAX_VALUE - sourceAvailable
                ? Long.MAX_VALUE : available + sourceAvailable;
        }
        return available;
    }

    @Override
    public ItemStack extract(AEKey key, int amount) {
        if (!(key instanceof AEItemKey) || amount <= 0) {
            return ItemStack.EMPTY;
        }
        int remaining = amount;
        ItemStack result = ItemStack.EMPTY;
        for (int i = 0; i < sources.size(); i++) {
            var source = sources.get(i);
            if (remaining <= 0) {
                break;
            }
            ItemStack extracted = source.extract(key, remaining);
            if (extracted == null || extracted.isEmpty()) {
                continue;
            }
            if (!key.equals(AEItemKey.of(extracted))) {
                ItemStack unreturned = source.refund(extracted);
                if (unreturned != null && !unreturned.isEmpty()) {
                    throw new IllegalStateException("Transfer source returned a different key");
                }
                continue;
            }

            int accepted = Math.min(remaining, extracted.getCount());
            if (extracted.getCount() > accepted) {
                ItemStack excess = extracted.splitStack(accepted);
                ItemStack excessRemainder = source.refund(extracted);
                if (excessRemainder != null && !excessRemainder.isEmpty()) {
                    throw new IllegalStateException("Transfer source returned more than requested");
                }
                extracted = excess;
            }
            ItemStack part = extracted.getCount() == accepted ? extracted : extracted.splitStack(accepted);
            result = result.isEmpty() ? part : merge(result, part);
            origins.computeIfAbsent(key, ignored -> new ObjectArrayFIFOQueue<>()).enqueue(new Origin(source, accepted));
            remaining -= accepted;
        }
        return result;
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

        int remaining = stack.getCount();
        var unreturned = new ObjectArrayList<ItemStack>();
        var queue = origins.get(key);
        while (remaining > 0 && queue != null && !queue.isEmpty()) {
            Origin origin = queue.dequeue();
            int partAmount = Math.min(remaining, origin.amount);
            ItemStack part = stack.getCount() == partAmount ? stack : stack.splitStack(partAmount);
            ItemStack remainder = origin.source.refund(part);
            if (remainder != null && !remainder.isEmpty()) {
                unreturned.add(remainder);
            }
            origin.amount -= partAmount;
            remaining -= partAmount;
            if (origin.amount > 0) {
                queue.enqueueFirst(origin);
            }
        }
        if (queue != null && queue.isEmpty()) {
            origins.remove(key);
        }

        if (remaining > 0) {
            if (stack.getCount() > remaining) {
                stack.shrink(stack.getCount() - remaining);
            }
            unreturned.add(stack);
        }
        if (unreturned.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack result = ItemStack.EMPTY;
        for (int i = 0; i < unreturned.size(); i++) {
            var part = unreturned.get(i);
            result = result.isEmpty() ? part : merge(result, part);
        }
        return result;
    }

    @Override
    public void commit(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        AEItemKey key = AEItemKey.of(stack);
        if (key == null) {
            return;
        }
        int remaining = stack.getCount();
        var queue = origins.get(key);
        while (remaining > 0 && queue != null && !queue.isEmpty()) {
            Origin origin = queue.dequeue();
            int committed = Math.min(remaining, origin.amount);
            ItemStack part = stack.getCount() == committed ? stack : stack.splitStack(committed);
            try {
                origin.source.commit(part);
            } catch (RuntimeException ignored) {
                // The target already owns accepted items. Provenance is still retired so a failed optional callback
                // cannot make a later transfer refund material that was already consumed by the target.
            }
            origin.amount -= committed;
            remaining -= committed;
            if (origin.amount > 0) {
                queue.enqueueFirst(origin);
            }
        }
        if (queue != null && queue.isEmpty()) {
            origins.remove(key);
        }
    }

    private static final class Origin {
        private final RecipeTransferSource source;
        private int amount;

        private Origin(RecipeTransferSource source, int amount) {
            this.source = Objects.requireNonNull(source, "source");
            this.amount = amount;
        }
    }
}
