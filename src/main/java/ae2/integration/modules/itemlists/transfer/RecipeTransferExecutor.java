/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2026, TeamAppliedEnergistics, All rights reserved.
 */

package ae2.integration.modules.itemlists.transfer;

import ae2.api.integrations.recipetransfer.IRecipeTransferCustomization;
import ae2.api.integrations.recipetransfer.RecipeTransferContext;
import ae2.api.integrations.recipetransfer.RecipeTransferInsertion;
import ae2.api.integrations.recipetransfer.RecipeTransferQuantityPolicy;
import ae2.api.integrations.recipetransfer.RecipeTransferReceipt;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKey2LongMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.ints.IntSets;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongMaps;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Server-side executor shared by standard and adapted recipe-transfer handlers.
 *
 * <p>The executor deliberately knows only about a resource key, a target input operation and a source. It does not
 * write slots directly, and it never treats a planned amount as proof that a target accepted that amount.</p>
 */
public final class RecipeTransferExecutor {
    private RecipeTransferExecutor() {
    }

    /**
     * Executes a material transfer. The source is touched only here; callers should use their own read-only context
     * for previews.
     */
    public static Result execute(RecipeTransferContext context,
                                 Request request,
                                 @Nullable IRecipeTransferCustomization customization) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(request, "request");
        if (context.inventoryView() == null) {
            // Install the default read-only view before customization. The method reference follows the context's
            // source, so a customization that replaces the source still gets the replacement for execution.
            context.setInventoryView(context::available);
        }
        if (customization != null) {
            customization.customizeRecipeTransfer(context);
        }

        var inputs = new ObjectArrayList<Input>();
        var demands = new ObjectArrayList<TransferQuantityPlanner.Demand>();
        var available = new AEKey2LongMap.OpenHashMap();
        for (int i = 0; i < request.inputs().size(); i++) {
            var input = request.inputs().get(i);
            inputs.add(input);
            demands.add(new TransferQuantityPlanner.Demand(input.key(), input.amountPerSet(),
                input.existingAmount(), input.slotCapacity()));
        }
        for (int i = 0; i < inputs.size(); i++) {
            var input = inputs.get(i);
            available.computeIfAbsent(input.key(), ignored -> Math.max(0, context.available(input.key())));
        }
        var quantityRequest = new TransferQuantityPlanner.Request(
            request.maxTransfer() && context.quantityPolicy()
                == RecipeTransferQuantityPolicy.JEI,
            request.craftMissing(),
            request.requireCompleteSets() || context.requireCompleteSets());
        var plan = TransferQuantityPlanner.plan(demands, available, quantityRequest);

        var custody = new TransferCustody();
        var missing = new AEKey2LongMap.SaturatingOpenHashMap(request.inputs().size());
        var rejectedTargetSlots = new IntOpenHashSet();
        long moved = 0;
        boolean rejected = false;
        try {
            for (int inputIndex = 0; inputIndex < inputs.size(); inputIndex++) {
                var input = inputs.get(inputIndex);
                long wanted = plan.transferAmountsPerDemand().getLong(inputIndex);
                if (wanted <= 0) {
                    if (request.craftMissing()) {
                        addSaturated(missing, input.key(), input.missingAmount());
                    }
                    continue;
                }

                ItemStack extracted;
                try {
                    extracted = context.source().extract(input.key(), Math.toIntExact(wanted));
                } catch (RuntimeException e) {
                    addMissingIfUnavailable(context, request, missing, input);
                    continue;
                }
                if (extracted == null || extracted.isEmpty()) {
                    addMissingIfUnavailable(context, request, missing, input);
                    continue;
                }

                int extractedAmount = Math.min(extracted.getCount(), Math.toIntExact(wanted));

                if (!(input.key() instanceof AEItemKey itemKey) || !itemKey.equals(AEItemKey.of(extracted))) {
                    custody.hold(extracted, context.source()::refund);
                    rejected = true;
                    continue;
                }

                // Keep the complete source receipt in custody. If a faulty/custom source returns more than requested,
                // the excess is returned together with every other unaccepted remainder instead of being silently lost.
                var held = custody.hold(extracted, context.source()::refund);
                ItemStack offered = custody.offer(held, extractedAmount);
                InsertionReceipt receipt;
                try {
                    if (context.targetInput() != null && input.targetSlot() >= 0) {
                        RecipeTransferInsertion result = context.targetInput().insert(input.targetSlot(), offered);
                        if (!validInsertion(result, offered)) {
                            receipt = InsertionReceipt.refused(offered);
                            rejected = true;
                        } else {
                            receipt = new InsertionReceipt(offered, result.remainder(), result.accepted(),
                                result.accepted() == offered.getCount()
                                    ? InsertionReceipt.Status.ACCEPTED
                                    : result.accepted() == 0
                                    ? InsertionReceipt.Status.REFUSED : InsertionReceipt.Status.PARTIAL);
                        }
                    } else {
                        receipt = input.operation().insert(offered);
                        if (!validReceipt(receipt, offered)) {
                            receipt = InsertionReceipt.refused(offered);
                            rejected = true;
                        }
                    }
                } catch (RuntimeException e) {
                    receipt = InsertionReceipt.refused(offered);
                    rejected = true;
                }
                custody.settle(held, receipt);
                moved += receipt.accepted();
                boolean inputPartiallyRejected = receipt.accepted() < offered.getCount();
                if (inputPartiallyRejected && input.targetSlot() >= 0) {
                    rejectedTargetSlots.add(input.targetSlot());
                }
                if (receipt.accepted() > 0) {
                    ItemStack accepted = receipt.offered();
                    accepted.setCount(receipt.accepted());
                    try {
                        context.source().commit(accepted);
                    } catch (RuntimeException ignored) {
                        // Committing provenance is bookkeeping. The target already owns the accepted items, and they
                        // must never be refunded merely because a source-side bookkeeping callback failed.
                    }
                }
                if (request.craftMissing() && !inputPartiallyRejected) {
                    int missingAmount = Math.max(0, input.missingAmount() - receipt.accepted());
                    if (missingAmount > 0) {
                        addSaturated(missing, input.key(), missingAmount);
                    }
                }
                if (receipt.accepted() == 0 && receipt.status() != InsertionReceipt.Status.ACCEPTED) {
                    rejected = true;
                }
            }
        } catch (RuntimeException e) {
            rejected = true;
        }

        var unreturned = custody.refundAll();
        boolean complete = !rejected && missing.isEmpty() && unreturned.isEmpty();
        ResourceLocation recipeId = context.recipeId() != null ? context.recipeId() : request.recipeId();
        var result = new Result(recipeId, moved, missing, unreturned, rejectedTargetSlots, rejected, complete);
        if (customization != null) {
            // The target callback is notification only. A faulty integration must not turn an already-applied,
            // server-side slot operation into a second extraction or a network packet failure.
            try {
                customization.onRecipeTransferApplied(result.receipt());
            } catch (RuntimeException ignored) {
                // Keep the transfer result authoritative even when optional integration code fails.
            }
        }
        return result;
    }

    private static void addMissingIfUnavailable(RecipeTransferContext context, Request request,
                                                AEKey2LongMap missing, Input input) {
        if (!request.craftMissing()) {
            return;
        }
        try {
            // A source may fail an extraction because of energy or another transient constraint while still holding
            // the item. That is not a shortage to submit as a crafting job.
            if (context.available(input.key()) <= 0) {
                addSaturated(missing, input.key(), input.missingAmount());
            }
        } catch (RuntimeException ignored) {
            // An unavailable inventory view is not evidence that crafting is needed.
        }
    }

    private static boolean validInsertion(RecipeTransferInsertion result, ItemStack offered) {
        if (result == null || result.accepted() < 0 || result.accepted() > offered.getCount()
            || result.remainder() == null
            || result.remainder().getCount() != offered.getCount() - result.accepted()) {
            return false;
        }
        return result.remainder().isEmpty() || sameItem(result.remainder(), offered);
    }

    private static boolean validReceipt(InsertionReceipt receipt, ItemStack offered) {
        return receipt != null && sameItem(receipt.offered(), offered) && validInsertion(
            new RecipeTransferInsertion(receipt.remainder(), receipt.accepted()), offered);
    }

    private static boolean sameItem(ItemStack first, ItemStack second) {
        return ItemStack.areItemsEqual(first, second) && ItemStack.areItemStackTagsEqual(first, second);
    }

    private static void addSaturated(AEKey2LongMap map, AEKey key, long amount) {
        map.addTo(key, amount);
    }

    public record Input(int targetSlot, AEKey key, int amountPerSet, int existingAmount, int slotCapacity,
                        SlotInputOperation operation) {
        public Input {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(operation, "operation");
            if (amountPerSet <= 0 || existingAmount < 0 || slotCapacity < 0) {
                throw new IllegalArgumentException("Input amount and capacity are invalid");
            }
        }

        public Input(AEKey key, int amountPerSet, int slotCapacity, SlotInputOperation operation) {
            this(-1, key, amountPerSet, 0, slotCapacity, operation);
        }

        public Input(int targetSlot, AEKey key, int amountPerSet, int slotCapacity, SlotInputOperation operation) {
            this(targetSlot, key, amountPerSet, 0, slotCapacity, operation);
        }

        public int missingAmount() {
            return Math.max(0, amountPerSet - existingAmount);
        }
    }

    public record Request(List<Input> inputs,
                          boolean maxTransfer,
                          boolean craftMissing,
                          boolean requireCompleteSets,
                          @Nullable ResourceLocation recipeId) {
        public Request {
            inputs = immutableInputs(inputs);
        }

        private static List<Input> immutableInputs(List<Input> inputs) {
            Objects.requireNonNull(inputs, "inputs");
            for (int i = 0; i < inputs.size(); i++) {
                Objects.requireNonNull(inputs.get(i), "input");
            }
            return wrapInputs(inputs);
        }

        private static List<Input> wrapInputs(List<Input> inputs) {
            if (inputs instanceof ObjectList<?> objectList) {
                @SuppressWarnings("unchecked")
                ObjectList<? extends Input> typed = (ObjectList<? extends Input>) objectList;
                return ObjectLists.unmodifiable(typed);
            }
            var copied = new ObjectArrayList<Input>(inputs.size());
            copied.addAll(inputs);
            return ObjectLists.unmodifiable(copied);
        }
    }

    public record Result(@Nullable ResourceLocation recipeId,
                         long movedAmount,
                         AEKey2LongMap missingForOneSet,
                         List<ItemStack> unreturned,
                         IntSet rejectedTargetSlots,
                         boolean inputRejected,
                         boolean complete) {
        public Result {
            var suppliedMissing = Objects.requireNonNull(missingForOneSet, "missingForOneSet");
            missingForOneSet = immutableMissing(suppliedMissing);
            unreturned = immutableUnreturned(unreturned);
            rejectedTargetSlots = IntSets.unmodifiable(Objects.requireNonNull(rejectedTargetSlots,
                "rejectedTargetSlots"));
            if (movedAmount < 0) {
                throw new IllegalArgumentException("Moved amount must not be negative");
            }
        }

        private static List<ItemStack> immutableUnreturned(List<ItemStack> unreturned) {
            Objects.requireNonNull(unreturned, "unreturned");
            for (int i = 0; i < unreturned.size(); i++) {
                Objects.requireNonNull(unreturned.get(i), "unreturned stack");
            }
            if (unreturned instanceof ObjectList<?> objectList) {
                @SuppressWarnings("unchecked")
                ObjectList<? extends ItemStack> typed = (ObjectList<? extends ItemStack>) objectList;
                return ObjectLists.unmodifiable(typed);
            }
            var copied = new ObjectArrayList<ItemStack>(unreturned.size());
            copied.addAll(unreturned);
            return ObjectLists.unmodifiable(copied);
        }

        private static AEKey2LongMap immutableMissing(AEKey2LongMap supplied) {
            if (supplied instanceof AEKey2LongMap.UnmodifiableMap) {
                return supplied;
            }
            if (supplied instanceof AEKey2LongMap.LinkdOpenHashMap linked) {
                validateMissing(linked);
                return new AEKey2LongMap.UnmodifiableMap(linked);
            }
            if (supplied instanceof AEKey2LongMap.SaturatingOpenHashMap saturating) {
                validateMissing(saturating);
                return new AEKey2LongMap.UnmodifiableMap(saturating);
            }

            var copy = new AEKey2LongMap.LinkdOpenHashMap();
            ObjectIterator<Object2LongMap.Entry<AEKey>> iterator = Object2LongMaps.fastIterator(supplied);
            while (iterator.hasNext()) {
                var entry = iterator.next();
                var key = Objects.requireNonNull(entry.getKey(), "missing key");
                long amount = entry.getLongValue();
                if (amount < 0) {
                    throw new IllegalArgumentException("Missing amount must not be negative");
                }
                if (amount > 0) {
                    copy.addTo(key, amount);
                }
            }
            return new AEKey2LongMap.UnmodifiableMap(copy);
        }

        private static void validateMissing(AEKey2LongMap map) {
            ObjectIterator<Object2LongMap.Entry<AEKey>> iterator = Object2LongMaps.fastIterator(map);
            while (iterator.hasNext()) {
                var entry = iterator.next();
                Objects.requireNonNull(entry.getKey(), "missing key");
                if (entry.getLongValue() < 0) {
                    throw new IllegalArgumentException("Missing amount must not be negative");
                }
            }
        }

        public RecipeTransferReceipt receipt() {
            long missing = 0;
            ObjectIterator<Object2LongMap.Entry<AEKey>> iterator = Object2LongMaps.fastIterator(missingForOneSet);
            while (iterator.hasNext()) {
                var entry = iterator.next();
                long amount = entry.getLongValue();
                missing = amount > 0 && missing > Long.MAX_VALUE - amount
                    ? Long.MAX_VALUE : missing + amount;
            }
            return new RecipeTransferReceipt(recipeId, movedAmount, missing, complete);
        }
    }

}
