package ae2.core.network.clientbound;

import ae2.api.stacks.AEKey;
import ae2.client.NetworkAmount;
import ae2.core.network.ClientboundPacket;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.network.PacketBuffer;

public class NetworkAmountResultPacket extends ClientboundPacket {

    /**
     * The amount used when the player has no usable wireless terminal to ask, told apart from a stored amount of zero.
     */
    public static final long NO_ANSWER = -1;

    private AEKey key;
    private long amount;
    private boolean craftable;

    public NetworkAmountResultPacket() {}

    public NetworkAmountResultPacket(AEKey what, long amount) {
        this(what, amount, false);
    }

    public NetworkAmountResultPacket(AEKey what, long amount, boolean craftable) {
        this.key = what;
        this.amount = amount;
        this.craftable = craftable;
    }

    @Override
    protected void read(ByteBuf buf) {
        PacketBuffer packetBuffer = new PacketBuffer(buf);
        this.key = AEKey.readKey(packetBuffer);
        this.amount = packetBuffer.readLong();
        this.craftable = packetBuffer.readBoolean();
    }

    @Override
    protected void write(ByteBuf buf) {
        PacketBuffer packetBuffer = new PacketBuffer(buf);
        AEKey.writeKey(packetBuffer, this.key);
        packetBuffer.writeLong(this.amount);
        packetBuffer.writeBoolean(this.craftable);
    }

    @Override
    public void handleClient(Minecraft minecraft) {
        if (this.key == null) {
            return;
        }

        NetworkAmount.acceptAmountResult(this.key, this.amount, this.craftable);
    }
}
