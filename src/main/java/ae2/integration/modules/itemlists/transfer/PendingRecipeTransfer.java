/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2026, TeamAppliedEnergistics, All rights reserved.
 */

package ae2.integration.modules.itemlists.transfer;

import ae2.api.stacks.AEKey;
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Server-side finite delivery state for one Ctrl recipe transfer. Delivery is a quota: it never grows when a machine
 * consumes a stack that was already present in the target.
 */
public final class PendingRecipeTransfer {
    private final UUID requestId;
    private final Object targetIdentity;
    private final Object sourceIdentity;
    private final long version;
    private final Int2ObjectLinkedOpenHashMap<Entry> entries = new Int2ObjectLinkedOpenHashMap<>();
    private long revision;

    public PendingRecipeTransfer(UUID requestId, Object targetIdentity, Object sourceIdentity, long version,
                                 List<SlotSpec> slots) {
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.targetIdentity = Objects.requireNonNull(targetIdentity, "targetIdentity");
        this.sourceIdentity = Objects.requireNonNull(sourceIdentity, "sourceIdentity");
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        this.version = version;
        this.revision = version;
        var requiredSlots = Objects.requireNonNull(slots, "slots");
        for (int i = 0; i < requiredSlots.size(); i++) {
            var slot = requiredSlots.get(i);
            if (entries.put(slot.targetSlot(), new Entry(slot)) != null) {
                throw new IllegalArgumentException("Duplicate pending target slot " + slot.targetSlot());
            }
        }
    }

    public UUID requestId() {
        return requestId;
    }

    public Object targetIdentity() {
        return targetIdentity;
    }

    public Object sourceIdentity() {
        return sourceIdentity;
    }

    public long version() {
        return version;
    }

    /**
     * Monotonic state revision used to discard delayed client synchronization packets.
     */
    public long revision() {
        return revision;
    }

    /**
     * Uses identity for target/source because container and network instances can be replaced with equal values.
     */
    public boolean matches(Object targetIdentity, Object sourceIdentity, long version) {
        return this.targetIdentity == targetIdentity && this.sourceIdentity == sourceIdentity && this.version == version;
    }

    /**
     * Records material accepted by the normal target input operation.
     */
    public int recordDelivered(int targetSlot, int amount) {
        var entry = requireEntry(targetSlot);
        if (!entry.active || amount <= 0) {
            return entry.view().remainingAmount();
        }
        int accepted = Math.min(amount, entry.requiredAmount - entry.deliveredAmount);
        entry.deliveredAmount += accepted;
        entry.observedAmount += accepted;
        if (accepted > 0) {
            advanceRevision();
        }
        return entry.view().remainingAmount();
    }

    /**
     * Reconciles the target's actual slot after a player or machine change. Increasing the same expected stack is
     * counted as player-provided material; decreasing it never creates a new delivery quota.
     */
    public int observe(int targetSlot, @Nullable AEKey actualKey, int actualAmount) {
        var entry = requireEntry(targetSlot);
        if (actualAmount < 0) {
            throw new IllegalArgumentException("actualAmount must not be negative");
        }
        if (!entry.active) {
            return 0;
        }
        if (actualAmount > 0 && !entry.key.equals(actualKey)) {
            entry.active = false;
            return 0;
        }
        int playerAdded = Math.max(0, actualAmount - entry.observedAmount);
        entry.deliveredAmount = Math.min(entry.requiredAmount, entry.deliveredAmount + playerAdded);
        if (playerAdded > 0 || actualAmount != entry.observedAmount) {
            advanceRevision();
        }
        entry.observedAmount = actualAmount;
        return entry.view().remainingAmount();
    }

    public List<SlotView> views() {
        var result = new ObjectArrayList<SlotView>(entries.size());
        var iterator = entries.int2ObjectEntrySet().fastIterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next().getValue();
            if (entry.active && entry.deliveredAmount < entry.requiredAmount) {
                result.add(entry.view());
            }
        }
        return ObjectLists.unmodifiable(result);
    }

    public boolean isComplete() {
        var iterator = entries.int2ObjectEntrySet().fastIterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next().getValue();
            if (entry.active && entry.deliveredAmount < entry.requiredAmount) {
                return false;
            }
        }
        return true;
    }

    private Entry requireEntry(int targetSlot) {
        var entry = entries.get(targetSlot);
        if (entry == null) {
            throw new IllegalArgumentException("Unknown pending target slot " + targetSlot);
        }
        return entry;
    }

    private void advanceRevision() {
        if (revision < Long.MAX_VALUE) {
            revision++;
        }
    }

    public record SlotSpec(int targetSlot, AEKey key, int requiredAmount, @Nullable AEKey initialKey,
                           int initialAmount) {
        public SlotSpec {
            Objects.requireNonNull(key, "key");
            if (targetSlot < 0 || requiredAmount <= 0 || initialAmount < 0) {
                throw new IllegalArgumentException("Invalid pending transfer slot");
            }
            if (initialAmount > 0 && initialKey == null) {
                throw new IllegalArgumentException("An initial stack needs a key");
            }
        }
    }

    public record SlotView(int targetSlot, AEKey key, int remainingAmount, boolean active) {
    }

    private static final class Entry {
        private final int targetSlot;
        private final AEKey key;
        private final int requiredAmount;
        private int deliveredAmount;
        private int observedAmount;
        private boolean active = true;

        private Entry(SlotSpec spec) {
            this.targetSlot = spec.targetSlot();
            this.key = spec.key();
            this.requiredAmount = spec.requiredAmount();
            if (spec.initialAmount() > 0 && !spec.key().equals(spec.initialKey())) {
                active = false;
            }
            this.deliveredAmount = Math.min(spec.requiredAmount(), spec.initialAmount());
            this.observedAmount = spec.initialAmount();
        }

        private SlotView view() {
            return new SlotView(targetSlot, key, Math.max(0, requiredAmount - deliveredAmount), active);
        }
    }
}
