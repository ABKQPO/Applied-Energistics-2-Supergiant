/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2026, TeamAppliedEnergistics, All rights reserved.
 */

package ae2.integration.modules.itemlists.transfer;

import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKey2LongMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongLists;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongMaps;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

import java.util.List;
import java.util.Objects;

/**
 * Pure planning for JEI/HEI transfer quantities.
 *
 * <p>The planner only describes quantities. It never extracts a stack, writes a slot, or submits a crafting job. The
 * executor must still validate the target and settle the plan using the amount accepted by normal input.</p>
 */
public final class TransferQuantityPlanner {
    private TransferQuantityPlanner() {
    }

    /**
     * Plans quantities for one recipe transfer. A demand's {@code amountPerSet} is the actual recipe amount, so a
     * request for three planks remains three planks rather than being normalized to one item per slot.
     */
    public static Plan plan(List<Demand> demands, AEKey2LongMap available, Request request) {
        Objects.requireNonNull(demands, "demands");
        Objects.requireNonNull(available, "available");
        Objects.requireNonNull(request, "request");

        ObjectIterator<Object2LongMap.Entry<AEKey>> availableIterator = Object2LongMaps.fastIterator(available);
        while (availableIterator.hasNext()) {
            var entry = availableIterator.next();
            Objects.requireNonNull(entry.getKey(), "available key");
            if (entry.getLongValue() < 0) {
                throw new IllegalArgumentException("Available amounts must not be negative");
            }
        }

        var totalDemand = new AEKey2LongMap.SaturatingOpenHashMap(demands.size());
        var totalAvailable = new AEKey2LongMap.SaturatingOpenHashMap(demands.size());
        boolean capacitySufficient = true;
        boolean demandExceedsStock = false;
        for (int i = 0; i < demands.size(); i++) {
            var demand = demands.get(i);
            if (demand.amountPerSet() > demand.slotCapacity() || demand.existingAmount() > demand.slotCapacity()) {
                capacitySufficient = false;
            }
            long stock = available.getLong(demand.key());
            long existing = demand.existingAmount();
            totalAvailable.putIfAbsent(demand.key(), stock);
            totalAvailable.addTo(demand.key(), existing);
            long previousDemand = totalDemand.addTo(demand.key(), demand.amountPerSet());
            if (previousDemand > Long.MAX_VALUE - demand.amountPerSet()) {
                demandExceedsStock = true;
            }
        }
        ObjectIterator<Object2LongMap.Entry<AEKey>> totalDemandIterator = Object2LongMaps.fastIterator(totalDemand);
        while (totalDemandIterator.hasNext()) {
            var entry = totalDemandIterator.next();
            if (entry.getLongValue() > totalAvailable.getLong(entry.getKey())) {
                demandExceedsStock = true;
                break;
            }
        }

        long completeSets = demandExceedsStock ? 0 : Long.MAX_VALUE;
        if (!demandExceedsStock) {
            ObjectIterator<Object2LongMap.Entry<AEKey>> demandIterator = Object2LongMaps.fastIterator(totalDemand);
            while (demandIterator.hasNext()) {
                var entry = demandIterator.next();
                long stock = totalAvailable.getLong(entry.getKey());
                long stockSets = stock / entry.getLongValue();
                completeSets = Math.min(completeSets, stockSets);
            }
        }
        for (int i = 0; i < demands.size(); i++) {
            var demand = demands.get(i);
            completeSets = Math.min(completeSets, demand.slotCapacity() / demand.amountPerSet());
        }
        if (demands.isEmpty()) {
            completeSets = 0;
        }
        if (completeSets == Long.MAX_VALUE) {
            completeSets = 0;
        }

        var missingAllocated = new AEKey2LongMap.SaturatingOpenHashMap(demands.size());
        var missing = new AEKey2LongMap.SaturatingOpenHashMap(demands.size());
        for (int i = 0; i < demands.size(); i++) {
            var demand = demands.get(i);
            long required = demand.remainingAmount();
            long stock = Math.max(0, available.getLong(demand.key()) - missingAllocated.getLong(demand.key()));
            long oneSetAvailable = Math.min(stock, required);
            long oneSetMissing = required - oneSetAvailable;
            if (oneSetMissing > 0) {
                addSaturated(missing, demand.key(), oneSetMissing);
            }
            if (oneSetAvailable > 0) {
                addSaturated(missingAllocated, demand.key(), oneSetAvailable);
            }
        }

        var transferAllocated = new AEKey2LongMap.SaturatingOpenHashMap(demands.size());
        var transfer = new AEKey2LongMap.SaturatingOpenHashMap(demands.size());
        var transferPerDemand = new LongArrayList(demands.size());
        for (int i = 0; i < demands.size(); i++) {
            var demand = demands.get(i);
            long required = demand.remainingAmount();
            long stock = Math.max(0, available.getLong(demand.key()) - transferAllocated.getLong(demand.key()));
            long oneSetAvailable = Math.min(stock, required);
            long oneSetMissing = required - oneSetAvailable;
            long capacity = Math.max(0, demand.slotCapacity() - demand.existingAmount());

            long amount;
            if (request.maxTransfer()) {
                if (request.requireCompleteSets()) {
                    long totalForSets = completeSets > demand.slotCapacity() / demand.amountPerSet()
                        ? demand.slotCapacity() : demand.amountPerSet() * completeSets;
                    long amountForSets = Math.max(0, totalForSets - demand.existingAmount());
                    amount = Math.min(stock, Math.min(capacity, amountForSets));
                } else {
                    // JEI permits a partial final set when the handler does not require complete sets. Capacity is
                    // applied per target slot, so a large stack is never silently expanded to 64 items.
                    amount = Math.min(stock, capacity);
                }
            } else {
                amount = request.craftMissing() ? oneSetAvailable
                    : (oneSetMissing == 0 ? required : 0);
                amount = Math.min(amount, capacity);
            }
            if (amount > 0) {
                addSaturated(transfer, demand.key(), amount);
                addSaturated(transferAllocated, demand.key(), amount);
            }
            transferPerDemand.add(amount);
        }

        boolean complete = capacitySufficient && missing.isEmpty();
        if (!request.craftMissing() && request.maxTransfer() && request.requireCompleteSets()) {
            complete = completeSets > 0;
        }
        return new Plan(transfer, missing, transferPerDemand, completeSets, complete);
    }

    private static void addSaturated(AEKey2LongMap map, AEKey key, long amount) {
        map.addTo(key, amount);
    }

    public record Demand(AEKey key, long amountPerSet, long existingAmount, long slotCapacity) {
        public Demand {
            Objects.requireNonNull(key, "key");
            if (amountPerSet <= 0 || existingAmount < 0 || slotCapacity < 0) {
                throw new IllegalArgumentException("Demand and capacity must be positive");
            }
        }

        public Demand(AEKey key, long amountPerSet, long slotCapacity) {
            this(key, amountPerSet, 0, slotCapacity);
        }

        public long remainingAmount() {
            return Math.max(0, amountPerSet - existingAmount);
        }
    }

    public record Request(boolean maxTransfer, boolean craftMissing, boolean requireCompleteSets) {
    }

    public record Plan(AEKey2LongMap transferAmounts,
                       AEKey2LongMap missingForOneSet,
                       LongList transferAmountsPerDemand,
                       long completeSets,
                       boolean complete) {
        public Plan {
            validateNonNegative(transferAmounts);
            validateNonNegative(missingForOneSet);
            validateNonNegative(transferAmountsPerDemand);
            transferAmounts = immutable(transferAmounts);
            missingForOneSet = immutable(missingForOneSet);
            transferAmountsPerDemand = LongLists.unmodifiable(transferAmountsPerDemand);
            if (completeSets < 0) {
                throw new IllegalArgumentException("completeSets must not be negative");
            }
        }

        private static void validateNonNegative(AEKey2LongMap values) {
            Objects.requireNonNull(values, "map");
            ObjectIterator<Object2LongMap.Entry<AEKey>> iterator = Object2LongMaps.fastIterator(values);
            while (iterator.hasNext()) {
                var entry = iterator.next();
                Objects.requireNonNull(entry.getKey(), "map key");
                long value = entry.getLongValue();
                if (value < 0) {
                    throw new IllegalArgumentException("Transfer amounts must not be negative");
                }
            }
        }

        private static void validateNonNegative(LongList values) {
            Objects.requireNonNull(values, "transferAmountsPerDemand");
            for (int i = 0; i < values.size(); i++) {
                if (values.getLong(i) < 0) {
                    throw new IllegalArgumentException("Transfer amounts must not be negative");
                }
            }
        }

        private static AEKey2LongMap immutable(AEKey2LongMap values) {
            if (values instanceof AEKey2LongMap.UnmodifiableMap) {
                return values;
            }
            if (values instanceof AEKey2LongMap.LinkdOpenHashMap linked) {
                return new AEKey2LongMap.UnmodifiableMap(linked);
            }
            if (values instanceof AEKey2LongMap.SaturatingOpenHashMap saturating) {
                return new AEKey2LongMap.UnmodifiableMap(saturating);
            }
            var copy = new AEKey2LongMap.LinkdOpenHashMap();
            ObjectIterator<Object2LongMap.Entry<AEKey>> iterator = Object2LongMaps.fastIterator(values);
            while (iterator.hasNext()) {
                var entry = iterator.next();
                copy.addTo(entry.getKey(), entry.getLongValue());
            }
            return new AEKey2LongMap.UnmodifiableMap(copy);
        }
    }
}
