/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2026, TeamAppliedEnergistics, All rights reserved.
 */

package ae2.client.gui;

import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Client-only snapshot of one server-owned recipe transfer wait list.
 */
public final class RecipeTransferPendingClientState {
    private static int windowId = -1;
    private static long version = -1;
    @Nullable
    private static UUID requestId;
    private static List<Entry> entries = ObjectLists.emptyList();

    private RecipeTransferPendingClientState() {
    }

    /**
     * Drops a server-owned wait list when the client session or world is discarded.
     */
    public static void clearSession() {
        windowId = -1;
        version = -1;
        requestId = null;
        entries = ObjectLists.emptyList();
    }

    public static boolean update(int windowId, UUID requestId, long version, ObjectList<? extends Entry> entries) {
        if (version < RecipeTransferPendingClientState.version
            || version == RecipeTransferPendingClientState.version
            && (windowId != RecipeTransferPendingClientState.windowId
            || !Objects.equals(requestId, RecipeTransferPendingClientState.requestId))) {
            return false;
        }
        RecipeTransferPendingClientState.windowId = windowId;
        RecipeTransferPendingClientState.requestId = requestId;
        RecipeTransferPendingClientState.version = version;
        RecipeTransferPendingClientState.entries = ObjectLists.unmodifiable(entries);
        return true;
    }

    public static boolean clear(int windowId, @Nullable UUID requestId, long version) {
        if (version < RecipeTransferPendingClientState.version
            || version == RecipeTransferPendingClientState.version
            && (windowId != RecipeTransferPendingClientState.windowId
            || !Objects.equals(requestId, RecipeTransferPendingClientState.requestId))) {
            return false;
        }
        RecipeTransferPendingClientState.windowId = windowId;
        RecipeTransferPendingClientState.requestId = requestId;
        RecipeTransferPendingClientState.version = version;
        RecipeTransferPendingClientState.entries = ObjectLists.emptyList();
        return true;
    }

    public static boolean matches(int windowId, UUID requestId) {
        return RecipeTransferPendingClientState.windowId == windowId
            && requestId.equals(RecipeTransferPendingClientState.requestId)
            && !entries.isEmpty();
    }

    public static int windowId() {
        return windowId;
    }

    public static List<Entry> entries() {
        return entries;
    }

    public record Entry(int slotNumber, AEKey key, int remaining, ItemStack displayStack) {
        public Entry {
            if (slotNumber < 0 || remaining <= 0 || !(key instanceof AEItemKey)
                || displayStack == null || displayStack.isEmpty()) {
                throw new IllegalArgumentException("Invalid pending transfer entry");
            }
        }

        public Entry(int slotNumber, AEKey key, int remaining) {
            this(slotNumber, key, remaining, ((AEItemKey) key).toStack(1));
        }
    }
}
