/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2026, TeamAppliedEnergistics, All rights reserved.
 */

package ae2.integration.modules.itemlists.transfer;

import ae2.api.implementations.items.WirelessTerminalApi;
import ae2.api.integrations.recipetransfer.IRecipeTransferCustomization;
import ae2.api.integrations.recipetransfer.RecipeTransferContext;
import ae2.api.integrations.recipetransfer.RecipeTransferInsertion;
import ae2.api.integrations.recipetransfer.RecipeTransferSource;
import ae2.api.stacks.AEItemKey;
import ae2.container.implementations.ContainerCraftConfirm;
import ae2.core.network.InitNetwork;
import ae2.core.network.clientbound.RecipeTransferPendingSyncPacket;
import ae2.core.network.clientbound.RecipeTransferPendingSyncPacket.Entry;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;
import java.util.function.IntPredicate;

/**
 * Delivers finite recipe-transfer materials to the original target after a crafting confirmation completes.
 */
public final class PendingRecipeTransferManager {
    public static final PendingRecipeTransferManager INSTANCE = new PendingRecipeTransferManager();

    private final Object2ObjectOpenHashMap<UUID, PendingRecipeTransfer> requests = new Object2ObjectOpenHashMap<>();
    private final Object2ObjectOpenHashMap<UUID, UUID> lastTransferIds = new Object2ObjectOpenHashMap<>();
    private long nextVersion;

    public PendingRecipeTransferManager() {
    }

    private static void refund(EntityPlayerMP player, RecipeTransferSource source, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        ItemStack remainder;
        try {
            remainder = source.refund(stack);
        } catch (RuntimeException ignored) {
            remainder = stack;
        }
        if (remainder != null && !remainder.isEmpty()
            && !player.inventory.addItemStackToInventory(remainder)) {
            player.dropItem(remainder, false);
        }
    }

    @Nullable
    private static Slot findSlot(Container container, int slotNumber) {
        for (int i = 0; i < container.inventorySlots.size(); i++) {
            Slot slot = container.inventorySlots.get(i);
            if (slot != null && slot.slotNumber == slotNumber) {
                return slot;
            }
        }
        return null;
    }

    public void replace(EntityPlayer player, PendingRecipeTransfer request) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(request, "request");
        requests.put(player.getUniqueID(), request);
    }

    @Nullable
    public PendingRecipeTransfer get(EntityPlayer player) {
        Objects.requireNonNull(player, "player");
        return requests.get(player.getUniqueID());
    }

    public boolean isCurrent(EntityPlayer player, PendingRecipeTransfer request) {
        return get(player) == request;
    }

    public void clear(EntityPlayer player) {
        Objects.requireNonNull(player, "player");
        requests.remove(player.getUniqueID());
    }

    public void clearIfCurrent(EntityPlayer player, PendingRecipeTransfer request) {
        Objects.requireNonNull(player, "player");
        requests.remove(player.getUniqueID(), request);
    }

    public void clearAll() {
        requests.clear();
        lastTransferIds.clear();
    }

    /**
     * Returns false when the same client transfer nonce was already executed for this player.
     */
    public boolean acceptTransfer(EntityPlayer player, UUID transferId) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(transferId, "transferId");
        UUID previous = lastTransferIds.put(player.getUniqueID(), transferId);
        return !transferId.equals(previous);
    }

    public void clearTransferNonce(EntityPlayer player) {
        Objects.requireNonNull(player, "player");
        lastTransferIds.remove(player.getUniqueID());
    }

    public long nextVersion() {
        if (nextVersion < Long.MAX_VALUE) {
            nextVersion++;
        }
        return nextVersion;
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.player instanceof EntityPlayerMP player) {
            tick(player);
        }
    }

    /**
     * Attempts one delivery pass. The target remains suspended while AE's native crafting confirmation is open.
     */
    public void tick(EntityPlayerMP player) {
        PendingRecipeTransfer request = get(player);
        if (request == null) {
            return;
        }
        if (!(player.openContainer instanceof Container container) || request.targetIdentity() != container) {
            if (player.openContainer instanceof ContainerCraftConfirm confirm
                && confirm.getReturnToContainerOverride() == request.targetIdentity()) {
                return;
            }
            sendClear(player, player.openContainer == null ? -1 : player.openContainer.windowId, request);
            clearIfCurrent(player, request);
            return;
        }

        WirelessTerminalApi.TerminalContext wireless = WirelessTerminalApi.findUsableWirelessTermOfPlayer(player,
            ignored -> true);
        IntPredicate allowedLocalSlot = ignored -> true;
        if (wireless != null) {
            Integer wirelessInventorySlot = wireless.locator().getPlayerInventorySlot();
            if (wirelessInventorySlot != null) {
                int excludedSlot = wirelessInventorySlot;
                allowedLocalSlot = slot -> slot != excludedSlot;
            }
        }
        RecipeTransferSource source = RecipeTransferSources.forPlayer(player, wireless, allowedLocalSlot);
        var context = new RecipeTransferContext(source);
        context.setSourceIdentity(wireless == null || wireless.node() == null
            ? player.inventory : wireless.node().grid());
        context.setTargetInput((targetSlot, offered) -> {
            InsertionReceipt receipt = NormalSlotInput.insert(container, player, targetSlot, offered);
            return new RecipeTransferInsertion(receipt.remainder(), receipt.accepted());
        });
        IRecipeTransferCustomization customization = RecipeTransferCustomizations.find(container, player);
        if (customization != null) {
            try {
                customization.customizeRecipeTransfer(context);
            } catch (RuntimeException ignored) {
                sendClear(player, container.windowId, request);
                clearIfCurrent(player, request);
                return;
            }
        }
        if (request.sourceIdentity() != context.sourceIdentity()) {
            sendClear(player, container.windowId, request);
            clearIfCurrent(player, request);
            return;
        }

        var views = request.views();
        for (int i = 0; i < views.size(); i++) {
            PendingRecipeTransfer.SlotView view = views.get(i);
            Slot slot = findSlot(container, view.targetSlot());
            if (slot == null) {
                request.observe(view.targetSlot(), null, 0);
                continue;
            }

            ItemStack actual = slot.getStack();
            var actualKey = actual.isEmpty() ? null : AEItemKey.of(actual);
            int actualAmount = actual.isEmpty() ? 0 : actual.getCount();
            int remainingAmount = request.observe(view.targetSlot(), actualKey, actualAmount);
            if (remainingAmount == 0) {
                continue;
            }

            int freeCapacity = Math.max(0, slot.getSlotStackLimit() - actualAmount);
            int amount = Math.min(remainingAmount, freeCapacity);
            if (view.key() instanceof AEItemKey itemKey) {
                amount = Math.clamp(itemKey.getMaxStackSize() - actualAmount, 0, amount);
            }
            if (amount <= 0) {
                continue;
            }

            ItemStack extracted;
            try {
                extracted = context.source().extract(view.key(), amount);
            } catch (RuntimeException ignored) {
                continue;
            }
            if (extracted == null || extracted.isEmpty()) {
                continue;
            }
            if (!(view.key() instanceof AEItemKey itemKey) || !itemKey.equals(AEItemKey.of(extracted))) {
                refund(player, context.source(), extracted);
                continue;
            }

            int offeredAmount = Math.min(amount, extracted.getCount());
            boolean splitExtracted = extracted.getCount() > offeredAmount;
            ItemStack offered = splitExtracted ? extracted.splitStack(offeredAmount) : extracted;
            RecipeTransferInsertion insertion;
            try {
                insertion = context.targetInput() == null
                    ? new RecipeTransferInsertion(offered, 0)
                    : context.targetInput().insert(view.targetSlot(), offered);
            } catch (RuntimeException failure) {
                insertion = new RecipeTransferInsertion(offered, 0);
            }
            int accepted = insertion == null || insertion.remainder() == null
                || insertion.accepted() < 0 || insertion.accepted() > offeredAmount
                || insertion.remainder().getCount() != offeredAmount - insertion.accepted()
                || !insertion.remainder().isEmpty()
                && (!ItemStack.areItemsEqual(offered, insertion.remainder())
                || !ItemStack.areItemStackTagsEqual(offered, insertion.remainder()))
                ? 0 : insertion.accepted();
            int offeredRemainder = offered.getCount() - accepted;
            if (accepted > 0) {
                offered.setCount(accepted);
                try {
                    context.source().commit(offered);
                } catch (RuntimeException ignored) {
                    // Accepted items already belong to the target, so provenance cannot be retried.
                }
                request.recordDelivered(view.targetSlot(), accepted);
            }
            if (offeredRemainder > 0) {
                offered.setCount(offeredRemainder);
                refund(player, context.source(), offered);
            }
            if (splitExtracted && !extracted.isEmpty()) {
                refund(player, context.source(), extracted);
            }
        }
        if (request.isComplete()) {
            sendClear(player, container.windowId, request);
            clearIfCurrent(player, request);
        } else {
            sendState(player, container.windowId, request);
        }
    }

    public void sendState(EntityPlayerMP player, int windowId, PendingRecipeTransfer request) {
        var views = request.views();
        var entries = new ObjectArrayList<RecipeTransferPendingSyncPacket.Entry>(views.size());
        for (int i = 0; i < views.size(); i++) {
            var view = views.get(i);
            entries.add(new Entry(view.targetSlot(), view.key(), view.remainingAmount()));
        }
        InitNetwork.sendToClient(player, new RecipeTransferPendingSyncPacket(windowId, request.requestId(),
            request.revision(), entries));
    }

    public void sendClear(EntityPlayerMP player, int windowId, PendingRecipeTransfer request) {
        InitNetwork.sendToClient(player, RecipeTransferPendingSyncPacket.clear(windowId, request.requestId(),
            request.revision()));
    }
}
