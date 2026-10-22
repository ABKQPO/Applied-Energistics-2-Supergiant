/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2026, TeamAppliedEnergistics, All rights reserved.
 */

package ae2.core.network.serverbound;

import ae2.api.implementations.items.WirelessTerminalApi;
import ae2.api.integrations.recipetransfer.IRecipeTransferCustomization;
import ae2.api.integrations.recipetransfer.RecipeTransferContext;
import ae2.api.integrations.recipetransfer.RecipeTransferInsertion;
import ae2.api.integrations.recipetransfer.RecipeTransferSource;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKey2LongMap;
import ae2.api.stacks.GenericStack;
import ae2.container.implementations.ContainerCraftConfirm;
import ae2.core.network.ServerboundPacket;
import ae2.crafting.TemporaryPseudoCraftingProvider;
import ae2.crafting.pattern.AEProcessingPattern;
import ae2.integration.modules.itemlists.transfer.NormalSlotInput;
import ae2.integration.modules.itemlists.transfer.PendingRecipeTransfer;
import ae2.integration.modules.itemlists.transfer.PendingRecipeTransferManager;
import ae2.integration.modules.itemlists.transfer.RecipeTransferCustomizations;
import ae2.integration.modules.itemlists.transfer.RecipeTransferExecutor;
import ae2.integration.modules.itemlists.transfer.RecipeTransferSources;
import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongMaps;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.IntPredicate;
import java.util.function.Predicate;

/**
 * Server-side boundary for standard JEI/HEI recipe transfers.
 */
public final class RecipeTransferPacket extends ServerboundPacket {
    private static final int MAX_INPUTS = 128;
    private static final int MAX_AMOUNT = 1_000_000;

    private int windowId;
    private UUID transferId = UUID.randomUUID();
    private boolean maxTransfer;
    private boolean craftMissing;
    private boolean requireCompleteSets;
    @Nullable
    private ResourceLocation recipeId;
    private List<GenericStack> pseudoOutputs = ObjectLists.emptyList();
    private List<Input> inputs = ObjectLists.emptyList();
    private boolean invalidRequest;

    public RecipeTransferPacket() {
    }

    public RecipeTransferPacket(int windowId, UUID transferId, boolean maxTransfer, boolean craftMissing,
                                boolean requireCompleteSets, @Nullable ResourceLocation recipeId,
                                List<GenericStack> pseudoOutputs, List<Input> inputs) {
        this.windowId = windowId;
        this.transferId = Objects.requireNonNull(transferId, "transferId");
        this.maxTransfer = maxTransfer;
        this.craftMissing = craftMissing;
        this.requireCompleteSets = requireCompleteSets;
        this.recipeId = recipeId;
        this.pseudoOutputs = copyPseudoOutputs(pseudoOutputs);
        this.inputs = copyInputs(inputs);
    }

    @SuppressWarnings("unused")
    public RecipeTransferPacket(int windowId, UUID transferId, boolean maxTransfer, boolean craftMissing,
                                boolean requireCompleteSets, List<Input> inputs) {
        this(windowId, transferId, maxTransfer, craftMissing, requireCompleteSets, null, ObjectLists.emptyList(), inputs);
    }

    private static List<Input> copyInputs(List<Input> inputs) {
        Objects.requireNonNull(inputs, "inputs");
        if (inputs.isEmpty() || inputs.size() > MAX_INPUTS) {
            throw new IllegalArgumentException("Invalid recipe transfer input count");
        }
        var result = new ObjectArrayList<Input>(inputs.size());
        for (int i = 0; i < inputs.size(); i++) {
            Input input = Objects.requireNonNull(inputs.get(i), "input");
            if (input.targetSlot() < 0 || input.amount() <= 0 || input.amount() > MAX_AMOUNT
                || input.candidates().isEmpty() || input.candidates().size() > 256) {
                throw new IllegalArgumentException("Invalid recipe transfer input");
            }
            result.add(input);
        }
        return result;
    }

    private static List<GenericStack> copyPseudoOutputs(List<GenericStack> outputs) {
        Objects.requireNonNull(outputs, "pseudoOutputs");
        if (outputs.size() > AEProcessingPattern.MAX_OUTPUT_SLOTS) {
            throw new IllegalArgumentException("Too many pseudo recipe outputs");
        }
        var result = new ObjectArrayList<GenericStack>(outputs.size());
        for (int i = 0; i < outputs.size(); i++) {
            GenericStack output = Objects.requireNonNull(outputs.get(i), "pseudo output");
            if (output.amount() <= 0) {
                throw new IllegalArgumentException("Pseudo recipe output amount must be positive");
            }
            result.add(output);
        }
        return ObjectLists.unmodifiable(result);
    }

    private static void returnUnreturned(EntityPlayerMP player, List<ItemStack> stacks) {
        for (int i = 0; i < stacks.size(); i++) {
            ItemStack stack = stacks.get(i);
            if (stack != null && !stack.isEmpty() && !player.inventory.addItemStackToInventory(stack)) {
                player.dropItem(stack, false);
            }
        }
    }

    private static boolean validExistingStack(ItemStack existing, AEItemKey key) {
        return existing.isEmpty() || key.equals(AEItemKey.of(existing));
    }

    /**
     * Chooses one concrete key for every ingredient together, so an ingredient with alternatives cannot consume the
     * only copy of a key that a later, more constrained ingredient requires. The search is deliberately bounded: a
     * packet may contain many alternatives, and a deterministic best-effort result is preferable to blocking the
     * server thread on an exponential search.
     */
    @Nullable
    private static AEItemKey[] selectCandidates(List<Input> inputs, Container container,
                                                RecipeTransferSource source,
                                                Predicate<AEItemKey> craftable) {
        int count = inputs.size();
        var candidates = new ObjectArrayList<ObjectArrayList<AEItemKey>>(count);
        var availability = new AEKey2LongMap.OpenHashMap();
        int[] missingAmounts = new int[count];
        int[] order = new int[count];
        for (int i = 0; i < count; i++) {
            Input input = inputs.get(i);
            Slot slot = findSlot(container, input.targetSlot());
            if (slot == null) {
                return null;
            }
            ItemStack existing = slot.getStack();
            missingAmounts[i] = Math.max(0, input.amount() - (existing.isEmpty() ? 0 : existing.getCount()));
            var allowed = new ObjectArrayList<AEItemKey>(input.candidates().size());
            for (int j = 0; j < input.candidates().size(); j++) {
                AEItemKey candidate = input.candidates().get(j);
                if (!existing.isEmpty() && !candidate.equals(AEItemKey.of(existing))) {
                    continue;
                }
                int capacity = Math.min(slot.getSlotStackLimit(), candidate.getMaxStackSize());
                if (capacity <= 0 || input.amount() > capacity || !existing.isEmpty() && existing.getCount() > capacity) {
                    continue;
                }
                addCandidate(allowed, candidate);
                if (missingAmounts[i] > 0) {
                    availability.computeIfAbsent(candidate,
                        ignored -> Math.max(0, source.available(candidate)));
                }
            }
            if (allowed.isEmpty()) {
                return null;
            }
            candidates.add(allowed);
            order[i] = i;
        }

        // Solve the most constrained slots first. The output remains in packet order.
        for (int i = 1; i < count; i++) {
            int value = order[i];
            int j = i - 1;
            while (j >= 0 && candidates.get(order[j]).size() > candidates.get(value).size()) {
                order[j + 1] = order[j];
                j--;
            }
            order[j + 1] = value;
        }

        var selected = new AEItemKey[count];
        var best = new AEItemKey[count];
        var reserved = new AEKey2LongMap.OpenHashMap();
        long[] bestScore = {-1};
        int[] bestCraftable = {-1};
        int[] attempts = {0};
        selectCandidates(0, order, candidates, availability, missingAmounts, reserved, selected, best, 0,
            bestScore, 0, bestCraftable, craftable, attempts);
        return bestScore[0] < 0 ? null : best;
    }

    private static void selectCandidates(int depth, int[] order,
                                         ObjectArrayList<ObjectArrayList<AEItemKey>> candidates,
                                         AEKey2LongMap availability,
                                         int[] missingAmounts,
                                         AEKey2LongMap reserved,
                                         AEItemKey[] selected, AEItemKey[] best, long score, long[] bestScore,
                                         int craftableCount, int[] bestCraftable, Predicate<AEItemKey> craftable,
                                         int[] attempts) {
        if (attempts[0]++ >= 8192) {
            return;
        }
        if (depth == order.length) {
            if (score > bestScore[0] || score == bestScore[0] && craftableCount > bestCraftable[0]) {
                bestScore[0] = score;
                bestCraftable[0] = craftableCount;
                System.arraycopy(selected, 0, best, 0, selected.length);
            }
            return;
        }

        int inputIndex = order[depth];
        var options = candidates.get(inputIndex);
        // Try the currently best supplied option first, while still exploring alternatives when it starves a later
        // constrained slot.
        for (int optionIndex = 0; optionIndex < options.size(); optionIndex++) {
            AEItemKey candidate = options.get(optionIndex);
            long available = availability.getLong(candidate);
            long remaining = Math.max(0, available - reserved.getLong(candidate));
            long contribution = Math.min(remaining, missingAmounts[inputIndex]);
            boolean canCraft = contribution < missingAmounts[inputIndex] && craftable.test(candidate);
            long scoreContribution = contribution + (canCraft ? missingAmounts[inputIndex] - contribution : 0);
            selected[inputIndex] = candidate;
            if (contribution > 0) {
                reserved.addTo(candidate, contribution);
            }
            selectCandidates(depth + 1, order, candidates, availability, missingAmounts, reserved, selected, best,
                score + scoreContribution, bestScore, craftableCount + (canCraft ? 1 : 0), bestCraftable, craftable,
                attempts);
            if (contribution > 0) {
                reserved.addTo(candidate, -contribution);
            }
            if (attempts[0] >= 8192) {
                break;
            }
        }
        selected[inputIndex] = null;
    }

    private static void addCandidate(ObjectArrayList<AEItemKey> candidates, AEItemKey candidate) {
        for (int i = 0; i < candidates.size(); i++) {
            if (candidate.equals(candidates.get(i))) {
                return;
            }
        }
        candidates.add(candidate);
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

    private static IntPredicate localSlotPredicate(@Nullable WirelessTerminalApi.TerminalContext wireless) {
        if (wireless == null || wireless.locator().getPlayerInventorySlot() == null) {
            return ignored -> true;
        }
        int terminalSlot = wireless.locator().getPlayerInventorySlot();
        return slot -> slot != terminalSlot;
    }

    @Override
    protected void read(ByteBuf buf) {
        try {
            PacketBuffer data = new PacketBuffer(buf);
            this.windowId = data.readInt();
            this.transferId = data.readUniqueId();
            this.maxTransfer = data.readBoolean();
            this.craftMissing = data.readBoolean();
            this.requireCompleteSets = data.readBoolean();
            this.recipeId = data.readBoolean() ? data.readResourceLocation() : null;
            int outputCount = data.readVarInt();
            if (outputCount < 0 || outputCount > AEProcessingPattern.MAX_OUTPUT_SLOTS) {
                throw new IllegalArgumentException("Invalid pseudo recipe output count");
            }
            var decodedOutputs = new ObjectArrayList<GenericStack>(outputCount);
            for (int outputIndex = 0; outputIndex < outputCount; outputIndex++) {
                GenericStack output = GenericStack.readBuffer(data);
                if (output == null || output.amount() <= 0) {
                    throw new IllegalArgumentException("Invalid pseudo recipe output");
                }
                decodedOutputs.add(output);
            }
            this.pseudoOutputs = ObjectLists.unmodifiable(decodedOutputs);
            int count = data.readVarInt();
            if (count <= 0 || count > MAX_INPUTS) {
                throw new IllegalArgumentException("Invalid recipe transfer input count");
            }
            var decoded = new ObjectArrayList<Input>(count);
            for (int i = 0; i < count; i++) {
                int targetSlot = data.readVarInt();
                int amount = data.readVarInt();
                int candidateCount = data.readVarInt();
                if (targetSlot < 0 || amount <= 0 || amount > MAX_AMOUNT || candidateCount <= 0
                    || candidateCount > 256) {
                    throw new IllegalArgumentException("Invalid recipe transfer input");
                }
                var candidates = new ObjectArrayList<AEItemKey>(candidateCount);
                for (int candidateIndex = 0; candidateIndex < candidateCount; candidateIndex++) {
                    AEKey key = AEKey.readKey(data);
                    if (!(key instanceof AEItemKey itemKey)) {
                        throw new IllegalArgumentException("Recipe transfer candidates must be items");
                    }
                    addCandidate(candidates, itemKey);
                }
                if (candidates.isEmpty()) {
                    throw new IllegalArgumentException("Recipe transfer input has no candidates");
                }
                decoded.add(new Input(targetSlot, amount, candidates));
            }
            if (data.isReadable()) {
                throw new IllegalArgumentException("Trailing recipe transfer payload");
            }
            this.inputs = decoded;
        } catch (RuntimeException e) {
            this.inputs = ObjectLists.emptyList();
            this.invalidRequest = true;
            invalidateMalformed(buf, e);
        }
    }

    @Override
    protected void write(ByteBuf buf) {
        PacketBuffer data = new PacketBuffer(buf);
        data.writeInt(this.windowId);
        data.writeUniqueId(this.transferId);
        data.writeBoolean(this.maxTransfer);
        data.writeBoolean(this.craftMissing);
        data.writeBoolean(this.requireCompleteSets);
        data.writeBoolean(this.recipeId != null);
        if (this.recipeId != null) {
            data.writeResourceLocation(this.recipeId);
        }
        data.writeVarInt(this.pseudoOutputs.size());
        for (int i = 0; i < this.pseudoOutputs.size(); i++) {
            GenericStack.writeBuffer(this.pseudoOutputs.get(i), data);
        }
        data.writeVarInt(this.inputs.size());
        for (int i = 0; i < this.inputs.size(); i++) {
            Input input = this.inputs.get(i);
            data.writeVarInt(input.targetSlot());
            data.writeVarInt(input.amount());
            data.writeVarInt(input.candidates().size());
            for (int candidateIndex = 0; candidateIndex < input.candidates().size(); candidateIndex++) {
                AEKey.writeKey(data, input.candidates().get(candidateIndex));
            }
        }
    }

    @Override
    public void handleServer(EntityPlayerMP player) {
        if (this.invalidRequest || this.isInvalid() || player.openContainer == null
            || player.openContainer.windowId != this.windowId
            || !player.inventory.getItemStack().isEmpty()) {
            return;
        }
        if (!PendingRecipeTransferManager.INSTANCE.acceptTransfer(player, this.transferId)) {
            return;
        }

        Container container = player.openContainer;
        var previousPending = PendingRecipeTransferManager.INSTANCE.get(player);
        if (previousPending != null) {
            PendingRecipeTransferManager.INSTANCE.sendClear(player, container.windowId, previousPending);
        }
        PendingRecipeTransferManager.INSTANCE.clear(player);
        WirelessTerminalApi.TerminalContext wireless = WirelessTerminalApi.findUsableWirelessTermOfPlayer(player,
            ignored -> true);
        IntPredicate allowedLocalSlot = localSlotPredicate(wireless);
        var source = RecipeTransferSources.forPlayer(player, wireless, allowedLocalSlot);
        var context = new RecipeTransferContext(source);
        context.setSourceIdentity(wireless == null || wireless.node() == null
            ? player.inventory : wireless.node().grid());
        context.setTargetInput((targetSlot, offered) -> {
            var receipt = NormalSlotInput.insert(container, player, targetSlot, offered);
            return new RecipeTransferInsertion(receipt.remainder(), receipt.accepted());
        });

        IRecipeTransferCustomization customization = RecipeTransferCustomizations.find(container, player);
        if (customization != null) {
            try {
                customization.customizeRecipeTransfer(context);
            } catch (RuntimeException ignored) {
                return;
            }
        }

        var seenSlots = new IntOpenHashSet(this.inputs.size());
        Predicate<AEItemKey> craftable;
        if (!this.craftMissing) {
            craftable = ignored -> false;
        } else if (context.inventoryView() != null) {
            craftable = context.inventoryView()::isCraftable;
        } else {
            craftable = wireless == null || wireless.node() == null
                ? ignored -> false
                : wireless.node().grid().getCraftingService()::isCraftable;
        }
        AEItemKey[] selectedCandidates = selectCandidates(this.inputs, container, context.source(), craftable);
        if (selectedCandidates == null) {
            return;
        }
        var mapped = new ObjectArrayList<RecipeTransferExecutor.Input>(this.inputs.size());
        for (int i = 0; i < this.inputs.size(); i++) {
            Input input = this.inputs.get(i);
            if (!seenSlots.add(input.targetSlot())) {
                return;
            }
            AEItemKey key = selectedCandidates[i];
            if (key == null) {
                return;
            }
            Slot slot = findSlot(container, input.targetSlot());
            if (slot == null || !validExistingStack(slot.getStack(), key)) {
                return;
            }
            int existing = slot.getStack().isEmpty() ? 0 : slot.getStack().getCount();
            int capacity = Math.min(slot.getSlotStackLimit(), key.getMaxStackSize());
            if (capacity <= 0 || existing > capacity) {
                return;
            }
            mapped.add(new RecipeTransferExecutor.Input(input.targetSlot(), key, input.amount(), existing, capacity,
                offered -> NormalSlotInput.insert(container, player, input.targetSlot(), offered)));
        }

        var request = new RecipeTransferExecutor.Request(mapped, this.maxTransfer, this.craftMissing,
            this.requireCompleteSets, this.recipeId);
        RecipeTransferExecutor.Result result;
        try {
            result = RecipeTransferExecutor.execute(context, request, null);
        } catch (RuntimeException ignored) {
            // Optional target integration code must not turn a rejected transfer into a connection failure.
            return;
        }
        if (customization != null) {
            try {
                customization.onRecipeTransferApplied(result.receipt());
            } catch (RuntimeException ignored) {
                // The transfer is already authoritative; an optional callback cannot replay it.
            }
        }
        returnUnreturned(player, result.unreturned());
        if (this.craftMissing && wireless != null && wireless.node() != null && !this.pseudoOutputs.isEmpty()
            && !result.missingForOneSet().isEmpty()) {
            var pendingSlots = new ObjectArrayList<PendingRecipeTransfer.SlotSpec>();
            for (int i = 0; i < mapped.size(); i++) {
                RecipeTransferExecutor.Input input = mapped.get(i);
                if (result.rejectedTargetSlots().contains(input.targetSlot())
                    || result.missingForOneSet().getLong(input.key()) <= 0) {
                    continue;
                }
                Slot slot = findSlot(container, input.targetSlot());
                if (slot == null) {
                    continue;
                }
                ItemStack actual = slot.getStack();
                int actualAmount = actual.isEmpty() ? 0 : actual.getCount();
                if (actualAmount >= input.amountPerSet()) {
                    continue;
                }
                AEKey actualKey = actual.isEmpty() ? null : AEItemKey.of(actual);
                pendingSlots.add(new PendingRecipeTransfer.SlotSpec(input.targetSlot(), input.key(),
                    input.amountPerSet(), actualKey, actualAmount));
            }
            if (!pendingSlots.isEmpty()) {
                Object sourceIdentity = context.sourceIdentity();
                PendingRecipeTransfer pending = new PendingRecipeTransfer(UUID.randomUUID(), container,
                    Objects.requireNonNull(sourceIdentity), PendingRecipeTransferManager.INSTANCE.nextVersion(),
                    pendingSlots);
                PendingRecipeTransferManager.INSTANCE.replace(player, pending);
                var targets = new ObjectArrayList<GenericStack>();
                ObjectIterator<Object2LongMap.Entry<AEKey>> missingIterator =
                    Object2LongMaps.fastIterator(result.missingForOneSet());
                while (missingIterator.hasNext()) {
                    var missing = missingIterator.next();
                    if (missing.getKey() instanceof AEItemKey && missing.getLongValue() > 0) {
                        targets.add(new GenericStack(missing.getKey(), missing.getLongValue()));
                    }
                }
                var provider = new TemporaryPseudoCraftingProvider(targets, this.pseudoOutputs);
                boolean opened = ContainerCraftConfirm.openWithBatchCrafting(wireless.actionHost(), player,
                    wireless.locator(), targets, provider, container);
                if (opened && player.openContainer instanceof ContainerCraftConfirm confirm) {
                    confirm.setRecipeTransferCancellation(() ->
                        PendingRecipeTransferManager.INSTANCE.clearIfCurrent(player, pending));
                } else if (player.openContainer == container) {
                    PendingRecipeTransferManager.INSTANCE.sendState(player, container.windowId, pending);
                } else {
                    PendingRecipeTransferManager.INSTANCE.clearIfCurrent(player, pending);
                }
            }
        }
    }

    public record Input(int targetSlot, int amount, List<AEItemKey> candidates) {
        public Input {
            Objects.requireNonNull(candidates, "candidates");
            if (targetSlot < 0 || amount <= 0 || amount > MAX_AMOUNT || candidates.isEmpty()
                || candidates.size() > 256) {
                throw new IllegalArgumentException("Invalid recipe transfer input");
            }
            var copy = new ObjectArrayList<AEItemKey>(candidates.size());
            for (int i = 0; i < candidates.size(); i++) {
                addCandidate(copy, Objects.requireNonNull(candidates.get(i), "candidate"));
            }
            candidates = ObjectLists.unmodifiable(copy);
        }

        public Input(int targetSlot, AEKey key, int amount) {
            this(targetSlot, amount, ObjectLists.singleton((AEItemKey) Objects.requireNonNull(key, "key")));
        }

        public AEItemKey key() {
            return candidates.getFirst();
        }
    }
}
