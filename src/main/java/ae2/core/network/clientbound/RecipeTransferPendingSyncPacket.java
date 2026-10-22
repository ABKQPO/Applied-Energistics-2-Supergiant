/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2026, TeamAppliedEnergistics, All rights reserved.
 */

package ae2.core.network.clientbound;

import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import ae2.client.gui.RecipeTransferPendingClientState;
import ae2.core.network.ClientboundPacket;
import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.List;
import java.util.UUID;

/**
 * Synchronizes finite pending recipe-transfer entries and invalidates stale client placeholders.
 */
public final class RecipeTransferPendingSyncPacket extends ClientboundPacket {
    private static final int MAX_ENTRIES = 128;

    private int windowId;
    private UUID requestId;
    private long version;
    private List<Entry> entries = ObjectLists.emptyList();

    public RecipeTransferPendingSyncPacket() {
    }

    public RecipeTransferPendingSyncPacket(int windowId, UUID requestId, long version, List<Entry> entries) {
        this.windowId = windowId;
        this.requestId = requestId;
        this.version = version;
        this.entries = entries;
    }

    public static RecipeTransferPendingSyncPacket clear(int windowId, UUID requestId, long version) {
        return new RecipeTransferPendingSyncPacket(windowId, requestId, version, ObjectLists.emptyList());
    }

    @Override
    protected void read(ByteBuf buf) {
        PacketBuffer data = new PacketBuffer(buf);
        this.windowId = data.readInt();
        this.requestId = data.readUniqueId();
        this.version = data.readLong();
        int count = data.readVarInt();
        if (count < 0 || count > MAX_ENTRIES) {
            throw new IllegalArgumentException("Invalid pending transfer entry count");
        }
        var decoded = new ObjectArrayList<Entry>(count);
        for (int i = 0; i < count; i++) {
            int slotNumber = data.readVarInt();
            int remaining = data.readVarInt();
            AEKey key = AEKey.readKey(data);
            if (slotNumber < 0 || remaining <= 0 || !(key instanceof AEItemKey)) {
                throw new IllegalArgumentException("Invalid pending transfer entry");
            }
            decoded.add(new Entry(slotNumber, key, remaining));
        }
        this.entries = decoded;
    }

    @Override
    protected void write(ByteBuf buf) {
        PacketBuffer data = new PacketBuffer(buf);
        data.writeInt(this.windowId);
        data.writeUniqueId(this.requestId);
        data.writeLong(this.version);
        data.writeVarInt(this.entries.size());
        for (int i = 0; i < this.entries.size(); i++) {
            Entry entry = this.entries.get(i);
            data.writeVarInt(entry.slotNumber());
            data.writeVarInt(entry.remaining());
            AEKey.writeKey(data, entry.key());
        }
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void handleClient(Minecraft minecraft) {
        if (this.entries.isEmpty()) {
            RecipeTransferPendingClientState.clear(this.windowId, this.requestId, this.version);
            return;
        }
        var result = new ObjectArrayList<RecipeTransferPendingClientState.Entry>(this.entries.size());
        for (int i = 0; i < this.entries.size(); i++) {
            Entry entry = this.entries.get(i);
            int displayAmount = entry.key() instanceof AEItemKey itemKey
                ? Math.min(entry.remaining(), itemKey.getMaxStackSize()) : 1;
            result.add(new RecipeTransferPendingClientState.Entry(entry.slotNumber(), entry.key(), entry.remaining(),
                entry.key() instanceof AEItemKey itemKey ? itemKey.toStack(displayAmount) : ItemStack.EMPTY));
        }
        RecipeTransferPendingClientState.update(this.windowId, this.requestId, this.version, result);
    }

    public record Entry(int slotNumber, AEKey key, int remaining) {
        public Entry {
            if (slotNumber < 0 || remaining <= 0 || !(key instanceof AEItemKey)) {
                throw new IllegalArgumentException("Invalid pending transfer entry");
            }
        }
    }
}
